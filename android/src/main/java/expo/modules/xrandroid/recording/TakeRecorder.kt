package expo.modules.xrandroid.recording

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import expo.modules.xrandroid.ACTIVE_PROFILE
import expo.modules.xrandroid.CameraPreset
import expo.modules.xrandroid.cameraFidelity
import expo.modules.xrandroid.streams.AudioController
import expo.modules.xrandroid.streams.CameraRecordStart
import expo.modules.xrandroid.streams.CameraRegistry
import expo.modules.xrandroid.streams.HandCaptureSource
import expo.modules.xrandroid.streams.HeadPoseCaptureSource
import expo.modules.xrandroid.streams.JsonlStorageSink
import expo.modules.xrandroid.streams.SessionOriginController
import expo.modules.xrandroid.streams.WavStorageSink
import org.json.JSONObject
import java.io.File

// Turns "streams with sinks" into a TAKE (see README → Recording). On start, attaches a
// storage sink to each requested source (fanning out alongside any live display sink, so a
// stream can be both shown and recorded); on stop, detaches every sink and writes
// manifest.json. Holds one session-origin ref per take, relatched fresh at record-start so
// the manifest documents the head pose when recording began.
internal class TakeRecorder(
  private val headPose: HeadPoseCaptureSource,
  private val hands: HandCaptureSource,
  private val audio: AudioController,
  private val sessionOrigin: SessionOriginController,
) {
  private var dir: File? = null
  private var startBootNs = 0L
  private var startWallMs = 0L
  private val detachers = ArrayList<() -> Unit>()
  private val trackEntries = ArrayList<Map<String, Any>>()
  private var originRefHeld = false

  @Synchronized
  fun isRecording(): Boolean = dir != null

  // @Synchronized: start/stop/dispose mutate the same take state (dir, detachers,
  // trackEntries, originRefHeld) and can be called from different Expo async threads.
  @Synchronized
  fun start(
    context: Context?,
    activity: Activity?,
    streams: List<String>,
    preset: String,
    qualityScale: Double,
    originEmit: (Map<String, Any>) -> Unit,
  ): Map<String, Any> {
    if (isRecording()) return mapOf("status" to "already_recording")
    requireNotNull(context) { "No context for recording storage" }
    requireNotNull(activity) { "No current activity" }
    val base = context.getExternalFilesDir(null) ?: return mapOf("status" to "no_storage")

    startWallMs = System.currentTimeMillis()
    startBootNs = SystemClock.elapsedRealtimeNanos()
    val d = File(base, "take_$startWallMs").apply { mkdirs() }
    dir = d

    val recorded = ArrayList<String>()
    try {
    for (stream in streams.distinct()) {
      when (stream) {
        "head" -> {
          val sink = JsonlStorageSink(File(d, "head.jsonl"))
          headPose.attach(activity, sink)
          detachers.add { headPose.detach(sink) }
          trackEntries.add(headPose.streamMeta() + mapOf("file" to "head.jsonl"))
          holdOrigin(activity, originEmit)
          recorded.add(stream)
        }
        "hands" -> {
          val sink = JsonlStorageSink(File(d, "hands.jsonl"))
          hands.attach(activity, sink)
          detachers.add { hands.detach(sink) }
          trackEntries.add(hands.streamMeta() + mapOf("file" to "hands.jsonl"))
          holdOrigin(activity, originEmit)
          recorded.add(stream)
        }
        "camcorder", "voice_recognition" -> {
          val fileName = "audio_$stream.wav"
          val sink = WavStorageSink(File(d, fileName))
          val status = audio.attachStorage(activity, stream, sink)
          detachers.add { audio.detachStorage(stream, sink) }
          val meta = audio.sourceFor(stream)?.streamMeta() ?: mapOf("stream" to "audio:$stream")
          trackEntries.add(meta + mapOf("file" to fileName, "startStatus" to status))
          recorded.add(stream)
        }
        "cam0", "cam2" -> {
          val id = stream.removePrefix("cam") // "0" | "2"
          val view = CameraRegistry.get(id)
          if (view == null) {
            // A camera is recordable only while its preview is mounted.
            trackEntries.add(mapOf("stream" to "camera:$id", "status" to "not_previewing"))
          } else {
            val mp4 = "cam$id.mp4"
            val framesFile = "cam${id}_frames.jsonl"
            val (size, bitRate) = cameraPreset(preset)
            // start returns the ACTUAL resolved config + provenance (probe-first: sensor fps,
            // clamped bitrate, chosen codec) — not the requested preset — so the manifest
            // documents what was really encoded.
            val rec = view.startRecording(
              File(d, mp4), File(d, framesFile), size, ACTIVE_PROFILE.maxCaptureFps, bitRate, qualityScale,
            )
            detachers.add { view.stopRecording() }
            // Detect the camera's clock domain and derive its fidelity — a world camera is
            // capture-accurate ONLY when its SENSOR_TIMESTAMP is REALTIME (boottime). Record
            // it so a non-REALTIME camera degrades to delivery-stamped as a fact, not silently.
            val timestampSource = view.timestampSource()
            val fidelity = cameraFidelity(timestampSource)
            if (rec.status == "recording" && timestampSource != "REALTIME") {
              Log.w(
                TAG,
                "camera:$id recorded with timestampSource=$timestampSource (not REALTIME): " +
                  "frames are delivery-stamped, not capture-accurate — see manifest fidelity.",
              )
            }
            trackEntries.add(
              mapOf(
                "stream" to "camera:$id",
                "file" to mp4,
                "framesFile" to framesFile,
                "codec" to "hevc-all-intra",
                "preset" to preset,
                "status" to rec.status,
                // cam0/cam2 are independent devices (no logical multicam) — not shutter-synced.
                "shutterSynced" to false,
                // Detected from SENSOR_INFO_TIMESTAMP_SOURCE at record-start (not assumed).
                "timestampSource" to (timestampSource ?: "unknown"),
                "fidelity" to fidelity,
              ) + cameraConfigMeta(rec),
            )
            if (rec.status == "recording") recorded.add(stream)
          }
        }
        // Unknown stream key — skip.
      }
    }
    } catch (e: Throwable) {
      // A stream attach failed (e.g. a missing permission throws). Roll back fully so
      // the session isn't left wedged "recording" with leaked sinks, then rethrow so
      // the JS promise still rejects.
      rollback()
      throw e
    }

    return mapOf("status" to "recording", "takeDir" to d.absolutePath, "streams" to recorded)
  }

  @Synchronized
  fun stop(): Map<String, Any> {
    val d = dir ?: return mapOf("status" to "not_recording")

    // Detachers now BLOCK until each sink is finalized (JSONL/WAV flush-and-close, camera
    // encoder stop + muxer flush), so every file is fully written on disk here — safe to
    // probe for baked stats below.
    detachers.forEach { it() }
    detachers.clear()

    // Read the origin BEFORE releasing the ref — streamStopped clears the latch on 1→0.
    val origin = sessionOrigin.currentOrigin()
    if (originRefHeld) {
      sessionOrigin.streamStopped()
      originRefHeld = false
    }

    // Bake per-file stats (durations, fps, bitrate, cross-stream boottime alignment) now
    // that the files are finalized, so inspect is a manifest read instead of a re-probe.
    val stats = runCatching { RecordingInspector.computeStats(d) }.getOrElse { emptyMap() }
    val manifest = buildManifest(origin, stats)
    val manifestFile = File(d, "manifest.json")
    manifestFile.writeText(JSONObject(manifest).toString(2))

    val result = mapOf(
      "status" to "stopped",
      "takeDir" to d.absolutePath,
      "manifest" to manifestFile.absolutePath,
    )
    reset()
    return result
  }

  @Synchronized
  fun dispose() {
    if (isRecording()) stop()
  }

  // Undo a partial start(): detach whatever attached (reverse order, best-effort),
  // release the origin ref, and reset to not-recording. Keeps a failed start from
  // wedging the session.
  private fun rollback() {
    detachers.asReversed().forEach { runCatching { it() } }
    detachers.clear()
    if (originRefHeld) {
      sessionOrigin.streamStopped()
      originRefHeld = false
    }
    reset()
  }

  // One origin ref per take. Latches a FRESH origin at record-start (not the one a
  // display stream may have latched earlier), so the manifest documents the head pose
  // at recording start.
  private fun holdOrigin(activity: Activity?, emit: (Map<String, Any>) -> Unit) {
    if (originRefHeld) return
    originRefHeld = true
    sessionOrigin.recordingStarted(activity, emit)
  }

  private fun buildManifest(origin: Map<String, Any>?, stats: Map<String, Any?>): Map<String, Any> {
    // Per-stream fidelity is the ground truth — each recorded stream entry carries its own
    // tier (perception/audio from streamMeta; camera resolved from SENSOR_INFO_TIMESTAMP_SOURCE
    // at record-start). Summarize from the entries instead of hardcoding: the old hardcoded
    // map wrongly labeled audio "capture-accurate" (audio is delivery-stamped).
    val fidelityByStream = trackEntries.mapNotNull { e ->
      val streamLabel = e["stream"] as? String ?: return@mapNotNull null
      val tier = e["fidelity"] as? String ?: return@mapNotNull null
      streamLabel to tier
    }.toMap()
    // version 3: track entries carry the ACTUAL resolved encode config + provenance
    // (fps/bitrate/codec/size), and `stats` bakes the finalized per-file measurements +
    // cross-stream alignment so inspect is a manifest read, not a re-probe.
    return mapOf(
      "version" to 3,
      "capturedAtWallMs" to startWallMs,
      "capturedAtBootNs" to startBootNs,
      "stoppedAtBootNs" to SystemClock.elapsedRealtimeNanos(),
      "tracks" to trackEntries.toList(),
      // Baked finalized stats {videos, audios, dataTracks, alignment} — see RecordingInspector.
      "stats" to stats,
      // null when no perception stream was recorded (audio/video-only take has no origin).
      "sessionOrigin" to (origin ?: JSONObject.NULL),
      "sync" to mapOf(
        "clock" to "boottime",
        "alignment" to "post-hoc against the camera/RGB frame clock; index-based after resampling",
        // Derived from the recorded streams (see each entry's `fidelity`); a camera is
        // capture-accurate only when its timestampSource is REALTIME.
        "fidelityByStream" to fidelityByStream,
        "audioClockSync" to "per-WAV <name>.clocksync.jsonl: (framePosition, bootNs) ~1 Hz (HAL drift)",
        "cameraStereo" to "cam0/cam2 not shutter-synced (no logical multicam) — pair by timestamp",
        "wallClockEpoch" to mapOf("bootNs" to startBootNs, "wallMs" to startWallMs),
      ),
      "calibration" to mapOf(
        "worldCameraFactoryCalibration" to false,
        "note" to "Galaxy XR world cams expose no usable LENS_* intrinsics — self-calibrate.",
      ),
    )
  }

  private fun reset() {
    dir = null
    trackEntries.clear()
    startBootNs = 0L
    startWallMs = 0L
  }

  // Quality preset → (capture size, HEVC bitrate), from the active device profile
  // (ACTIVE_PROFILE.cameraPresets — see Constants.kt). FPS is pinned at the sensor max
  // (ACTIVE_PROFILE.maxCaptureFps) for ALL presets — never traded. An unknown preset
  // falls back to the profile's default. `null` size = the camera's native max (3000²).
  private fun cameraPreset(preset: String): CameraPreset =
    ACTIVE_PROFILE.cameraPresets[preset]
      ?: ACTIVE_PROFILE.cameraPresets.getValue(ACTIVE_PROFILE.defaultPreset)

  // The actual encode config + per-value provenance for a camera track entry (probe-first:
  // sensor-derived fps, clamped bitrate, chosen codec). Empty when the recording didn't
  // start (rec.status != "recording") — those fields would be meaningless zeros.
  private fun cameraConfigMeta(rec: CameraRecordStart): Map<String, Any> {
    if (rec.status != "recording") return emptyMap()
    return mapOf(
      "width" to rec.width,
      "height" to rec.height,
      "resolution" to "${rec.width}x${rec.height}",
      "sizeProvenance" to rec.sizeProvenance,
      // Actual sensor-derived fps the encoder ran at (≤ the profile cap), not the cap.
      "fps" to rec.fps,
      "fpsProvenance" to rec.fpsProvenance,
      "fpsCap" to ACTIVE_PROFILE.maxCaptureFps,
      "encoder" to (rec.codecName ?: "platform-default"),
      "encoderProvenance" to rec.codecProvenance,
      // Actual (clamped/scaled) bitrate the encoder was configured with — not the preset.
      "bitRate" to rec.bitRate,
      "bitRateProvenance" to rec.bitRateProvenance,
      "qualityScale" to rec.qualityScale,
    )
  }

  private companion object {
    const val TAG = "XrRecording"
  }
}
