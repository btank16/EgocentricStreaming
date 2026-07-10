package expo.modules.xrandroid.control

import android.app.Activity
import expo.modules.kotlin.AppContext
import expo.modules.xrandroid.bus.XrSourceProvider
import expo.modules.xrandroid.streams.AudioChunk
import expo.modules.xrandroid.streams.AudioController
import expo.modules.xrandroid.streams.CameraRegistry
import expo.modules.xrandroid.streams.CaptureSink
import expo.modules.xrandroid.streams.CaptureSource
import expo.modules.xrandroid.streams.DisplaySink
import expo.modules.xrandroid.streams.HandCaptureSource
import expo.modules.xrandroid.streams.HeadPoseCaptureSource
import expo.modules.xrandroid.streams.SessionOriginController
import expo.modules.xrandroid.recording.TakeRecorder

// Owns the module's mutable RUNTIME coordination: the capture sources, their display-sink
// handles, the session-origin latch, and the take recorder. The module delegates each stateful
// Function here; stateless probes stay in the module.
//
// Also the XrSourceProvider the capture bus resolves against, so a cross-module consumer reaches
// the live sources through this single owner. `emit` is the module's sendEvent, injected so the
// display sinks can push to the bridge without the controller depending on the Module.
internal class StreamController(
  private val appContext: AppContext,
  private val emit: (String, Map<String, Any>) -> Unit,
) : XrSourceProvider {

  private val headPose = HeadPoseCaptureSource()
  private var headPoseDisplaySink: CaptureSink<Map<String, Any>>? = null
  private val hands = HandCaptureSource()
  private var handsDisplaySink: CaptureSink<Map<String, Any>>? = null
  private val audio = AudioController()
  private val sessionOrigin = SessionOriginController()
  private val takeRecorder = TakeRecorder(headPose, hands, audio, sessionOrigin)

  // Guards the display-sink handles against concurrent start/stop on Expo's async pool.
  private val streamLock = Any()

  // ============ Perception display streams (head, hands) ============

  // bridgeRateHz throttles the DISPLAY only (null → full rate for both head and hands); a
  // storage sink always records the full native rate regardless.
  fun startHead(bridgeRateHz: Double?) {
    val throttleNs = throttleNsOr(bridgeRateHz, 0L) // head default: no throttle
    startPerceptionStream(
      headPose,
      { headPoseDisplaySink },
      { headPoseDisplaySink = it },
      { DisplaySink(throttleNs = throttleNs) { body -> emit("onDevicePose", body) } },
    )
  }

  fun stopHead() = stopPerceptionStream(headPose, { headPoseDisplaySink }, { headPoseDisplaySink = it })

  fun startHands(bridgeRateHz: Double?) {
    val throttleNs = throttleNsOr(bridgeRateHz, 0L) // hands default: no throttle (full rate, like head)
    startPerceptionStream(
      hands,
      { handsDisplaySink },
      { handsDisplaySink = it },
      {
        // If throttled (explicit bridgeRateHz), throttle PER hand (keyed on handedness) so the
        // pair isn't capped at the combined rate; at full rate (throttleNs == 0) keyOf is unused.
        DisplaySink(throttleNs = throttleNs, keyOf = { it["handedness"] }) { body ->
          emit("onHandState", body)
        }
      },
    )
  }

  fun stopHands() = stopPerceptionStream(hands, { handsDisplaySink }, { handsDisplaySink = it })

  // ============ Audio ============

  fun startAudioSource(label: String): String =
    audio.startSource(appContext.currentActivity, label) { body -> emit("onAudioLevel", body) }

  fun stopAudioSource(label: String) = audio.stopSource(label)

  // ============ Session origin ============

  fun getSessionOrigin(): Map<String, Any>? = sessionOrigin.currentOrigin()

  // ============ Recording ============

  // `streams` empty → record everything currently live (the "omit ⇒ all live" contract).
  // qualityScale null → 1.0 (no scale). Emits onSessionOrigin when the origin relatches.
  fun startRecording(streams: List<String>, preset: String, qualityScale: Double?): Map<String, Any> {
    val resolved = streams.ifEmpty { liveStreams() }
    return takeRecorder.start(
      appContext.reactContext,
      appContext.currentActivity,
      resolved,
      preset,
      qualityScale ?: 1.0,
    ) { body -> emit("onSessionOrigin", body) }
  }

  fun stopRecording(): Map<String, Any> = takeRecorder.stop()

  fun isRecording(): Boolean = takeRecorder.isRecording()

  // Streams currently live (display-started), the default set for a recording with no explicit
  // list. A camera is included only while its preview is mounted (it's recordable only then).
  fun liveStreams(): List<String> {
    val out = ArrayList<String>()
    synchronized(streamLock) {
      if (headPoseDisplaySink != null) out.add("head")
      if (handsDisplaySink != null) out.add("hands")
    }
    out.addAll(audio.liveLabels())
    out.addAll(CameraRegistry.mountedIds().map { "cam$it" })
    return out
  }

  // ============ XrSourceProvider (the capture-bus seam) ============

  override fun sourceFor(streamId: String): CaptureSource<Map<String, Any>>? = when (streamId) {
    "head" -> headPose
    "hands" -> hands
    else -> null
  }

  override fun attachAudioSink(label: String, sink: CaptureSink<AudioChunk>): AutoCloseable? {
    val status = audio.attachConsumer(appContext.currentActivity, label, sink)
    if (status == "unknown_source") return null
    return AutoCloseable { audio.detachConsumer(label, sink) }
  }

  override fun currentActivity(): Activity? = appContext.currentActivity

  // ============ Lifecycle ============

  fun dispose() {
    takeRecorder.dispose() // flush + finalize any in-flight take first
    headPose.dispose()
    hands.dispose()
    audio.dispose()
    sessionOrigin.dispose()
  }

  // ============ internals ============

  private fun throttleNsOr(rateHz: Double?, default: Long): Long =
    if (rateHz != null && rateHz > 0) (1_000_000_000.0 / rateHz).toLong() else default

  // Swap the display sink under streamLock and open/close the origin refcount. The sink
  // factory and the per-stream field get/set differ, so they're passed in.
  private fun startPerceptionStream(
    source: CaptureSource<Map<String, Any>>,
    currentSink: () -> CaptureSink<Map<String, Any>>?,
    setSink: (CaptureSink<Map<String, Any>>?) -> Unit,
    makeSink: () -> CaptureSink<Map<String, Any>>,
  ) {
    synchronized(streamLock) {
      currentSink()?.let { source.detach(it) } // re-entrant: drop any prior sink first
      setSink(null)
      val sink = makeSink()
      source.attach(appContext.currentActivity, sink) // throws → promise rejects
      setSink(sink)
      sessionOrigin.streamStarted(appContext.currentActivity) { body -> emit("onSessionOrigin", body) }
    }
  }

  private fun stopPerceptionStream(
    source: CaptureSource<Map<String, Any>>,
    currentSink: () -> CaptureSink<Map<String, Any>>?,
    setSink: (CaptureSink<Map<String, Any>>?) -> Unit,
  ) {
    synchronized(streamLock) {
      currentSink()?.let { source.detach(it) }
      setSink(null)
      sessionOrigin.streamStopped()
    }
  }
}
