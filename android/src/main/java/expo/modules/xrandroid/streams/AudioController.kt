package expo.modules.xrandroid.streams

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import expo.modules.xrandroid.ACTIVE_PROFILE
import expo.modules.xrandroid.Provenance
import expo.modules.xrandroid.Tuning
import expo.modules.xrandroid.WAV_HEADER_BYTES
import expo.modules.xrandroid.diagnostics.AudioProbe
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

// One block of raw PCM read from an AudioRecord, with its boottime stamp. `pcm` is a
// fresh copy per chunk (the read buffer is reused), so a sink may hold it past the next read.
internal class AudioChunk(
  val source: String,
  val pcm: ShortArray,
  val frameCount: Int,
  val sampleRate: Int,
  val channels: Int,
  // AudioTimestamp BOOTTIME nanoTime when available (comparable to the camera's
  // REALTIME clock), else a delivery stamp.
  val ts: Long,
  // AudioTimestamp framePosition at `ts` (for WAV clock-sync marks), or -1 if unavailable.
  val framePosition: Long,
)

// Per-source audio capture. Whether the HAL allows two AudioRecords at once isn't
// knowable until tried, so start records a status ("recording" / "init_failed" / …).
// The read loop runs on its own thread and dispatches raw AudioChunks.
internal class AudioCaptureSource(
  private val label: String,
  private val audioSource: Int,
) : CaptureSource<AudioChunk>() {
  private var record: AudioRecord? = null
  private var thread: Thread? = null

  @Volatile
  private var running = false

  // Empirical start outcome, read by the controller after attach: "can this source
  // capture (alongside any other already running)?".
  var startStatus: String = "idle"
    private set

  // Actual granted capture format, read back from the live AudioRecord at start (DETECTED);
  // the profile is only the request. Defaults to profile (FALLBACK) until a start reads real values.
  @Volatile private var actualSampleRate = ACTIVE_PROFILE.sampleRate
  @Volatile private var actualChannelCount = ACTIVE_PROFILE.channelCount
  @Volatile private var actualEncoding = ACTIVE_PROFILE.audioEncoding
  @Volatile private var formatProvenance = Provenance.FALLBACK

  // Timestamp mode, decided from the LIVE recorder at start (AudioProbe.resolveTimestampMode):
  // HAL_BOOTTIME if the HAL's boottime AudioTimestamp is trustworthy (→ capture-accurate),
  // else READ_TIME (→ delivery-stamped, the Galaxy XR case).
  @Volatile private var timestampMode = AudioProbe.MODE_READ_TIME
  @Volatile private var timestampProvenance = Provenance.FALLBACK

  override fun streamMeta(): Map<String, Any> = mapOf(
    "stream" to "audio:$label",
    "clock" to "boottime",
    "fidelity" to AudioProbe.fidelityForMode(timestampMode),
    "timestampMode" to timestampMode,
    "timestampProvenance" to timestampProvenance.name.lowercase(),
    "clockSource" to if (timestampMode == AudioProbe.MODE_HAL_BOOTTIME) {
      "HAL AudioTimestamp (boottime)"
    } else {
      "read-time elapsedRealtimeNanos"
    },
    "sampleRate" to actualSampleRate,
    "channels" to actualChannelCount,
    "encoding" to encodingLabel(actualEncoding),
    "formatProvenance" to formatProvenance.name.lowercase(),
  )

  @SuppressLint("MissingPermission") // RECORD_AUDIO checked below before any AudioRecord use
  override fun onStart(activity: Activity?) {
    requireNotNull(activity) { "No current activity" }
    require(
      activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    ) { "RECORD_AUDIO permission not granted" }
    startStatus = startRecorder()
  }

  private fun startRecorder(): String {
    // The format REQUEST comes from the probe (profile as fallback); the ACTUAL granted
    // format is read back from the live recorder below.
    val fmt = AudioProbe.resolveFormat(ACTIVE_PROFILE)
    val minBuffer = AudioRecord.getMinBufferSize(fmt.sampleRate, fmt.audioChannel, fmt.audioEncoding)
    if (minBuffer <= 0) return "buffer_error"
    val bufferSize = maxOf(minBuffer, fmt.framesPerRead * ACTIVE_PROFILE.bytesPerFrame * 2)
    val rec = try {
      AudioRecord(audioSource, fmt.sampleRate, fmt.audioChannel, fmt.audioEncoding, bufferSize)
    } catch (e: Exception) {
      return "exception"
    }
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      rec.release()
      return "init_failed"
    }
    rec.startRecording()
    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
      rec.release()
      return "start_failed"
    }
    // Read back the ACTUAL granted format (DETECTED) — what actually gets recorded.
    actualSampleRate = rec.sampleRate
    actualChannelCount = rec.channelCount
    actualEncoding = rec.audioFormat
    formatProvenance = Provenance.DETECTED
    // Decide the timestamp mode from the live recorder (probed once at start).
    val probeBoot = SystemClock.elapsedRealtimeNanos()
    val modeResolved = AudioProbe.resolveTimestampMode(rec, probeBoot)
    timestampMode = modeResolved.value
    timestampProvenance = modeResolved.provenance
    record = rec
    running = true
    thread = Thread { readLoop(rec) }.apply { start() }
    return "recording"
  }

  private fun readLoop(rec: AudioRecord) {
    val buffer = ShortArray(ACTIVE_PROFILE.framesPerRead)
    var totalFrames = 0L
    while (running) {
      val n = rec.read(buffer, 0, buffer.size)
      if (n <= 0) continue
      totalFrames += n
      // Stamp per resolved mode. READ_TIME: elapsedRealtimeNanos at read — the boottime clock
      // the camera + perception streams use, so audio aligns by construction (delivery-stamped).
      // HAL_BOOTTIME: the HAL's capture instant extrapolated to the end of this buffer.
      val bootNs = SystemClock.elapsedRealtimeNanos()
      val ts = if (timestampMode == AudioProbe.MODE_HAL_BOOTTIME) {
        halCaptureTs(rec, totalFrames) ?: bootNs
      } else {
        bootNs
      }
      // Copy: the read buffer is reused, but a storage sink may write asynchronously.
      dispatch(AudioChunk(label, buffer.copyOf(n), n, actualSampleRate, actualChannelCount, ts, totalFrames))
    }
  }

  // Capture instant (boottime ns) of the LAST frame in the current buffer, from the HAL's
  // boottime AudioTimestamp mark extrapolated by the frame delta. null (→ caller falls back
  // to read-time) if the HAL mark is momentarily unavailable. Unreachable on the Galaxy XR.
  private fun halCaptureTs(rec: AudioRecord, totalFrames: Long): Long? {
    val t = AudioTimestamp()
    if (rec.getTimestamp(t, AudioTimestamp.TIMEBASE_BOOTTIME) != AudioRecord.SUCCESS || t.nanoTime <= 0L) {
      return null
    }
    val rate = actualSampleRate.toDouble()
    return t.nanoTime + ((totalFrames - t.framePosition) * 1_000_000_000.0 / rate).toLong()
  }

  private fun encodingLabel(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> "pcm8"
    AudioFormat.ENCODING_PCM_16BIT -> "pcm16"
    AudioFormat.ENCODING_PCM_FLOAT -> "pcmfloat"
    else -> "enc_$encoding"
  }

  override fun onStop() {
    running = false
    thread?.join(300)
    thread = null
    try {
      record?.stop()
    } catch (e: Exception) {
      // ignore — may not be recording
    }
    try {
      record?.release()
    } catch (e: Exception) {
      // ignore
    }
    record = null
    startStatus = "idle"
  }
}

// Display sink: accumulates a ~100 ms window of raw PCM and emits windowed RMS + peak
// (0..1 linear) at ~10 Hz to the bridge. Dispatch runs on the read thread; the final
// emit is marshaled to Main (the bridge expects the UI thread).
internal class AudioLevelDisplaySink(
  private val emit: (Map<String, Any>) -> Unit,
) : CaptureSink<AudioChunk> {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var windowStartNs = 0L
  private var windowPeak = 0f
  private var windowSumSq = 0.0
  private var windowCount = 0L

  override fun onStart(meta: Map<String, Any>) = reset()

  override fun onSample(sample: AudioChunk) {
    // PCM full-scale from the profile's bit depth (32768 for PCM16) — normalizes to 0..1.
    val fullScale = (1 shl (ACTIVE_PROFILE.bitsPerSample - 1)).toFloat()
    for (i in 0 until sample.frameCount) {
      val s = sample.pcm[i] / fullScale
      val mag = abs(s)
      if (mag > windowPeak) windowPeak = mag
      windowSumSq += (s * s).toDouble()
    }
    windowCount += sample.frameCount
    val now = SystemClock.elapsedRealtimeNanos()
    if (windowStartNs == 0L) windowStartNs = now
    if (now - windowStartNs >= Tuning.AUDIO_LEVEL_EMIT_NS) {
      val rms = if (windowCount > 0) sqrt(windowSumSq / windowCount).toFloat() else 0f
      val body = mapOf(
        "source" to sample.source,
        "rms" to rms,
        "peak" to windowPeak,
        "ts" to sample.ts,
        "sampleRate" to sample.sampleRate,
      )
      mainHandler.post { emit(body) }
      windowStartNs = now
      windowPeak = 0f
      windowSumSq = 0.0
      windowCount = 0
    }
  }

  override fun onStop() = reset()

  private fun reset() {
    windowStartNs = 0L
    windowPeak = 0f
    windowSumSq = 0.0
    windowCount = 0
  }
}

// Storage sink: writes raw PCM to a WAV (LPCM) file and, periodically, a
// (framePosition, bootNs) clock-sync mark to a sidecar — the AudioRecord clock drifts vs
// boottime, so one mark isn't enough to align long takes. Writes run on a single
// background thread (capture never blocks on disk; order preserved). Owned by the TakeRecorder.
internal class WavStorageSink(
  private val file: File,
  private val sampleRate: Int = ACTIVE_PROFILE.sampleRate,
) : CaptureSink<AudioChunk> {
  private val io = Executors.newSingleThreadExecutor()

  @Volatile
  private var raf: RandomAccessFile? = null
  private var clockSyncWriter: BufferedWriter? = null
  private var dataBytes = 0L
  private var lastClockSyncNs = 0L

  // Frames written to THIS WAV (from recording start) — NOT the source's read-loop
  // counter, which starts at stream-start and is far ahead if the stream was already
  // running before recording began. Using the source counter placed audio's start at
  // stream-start instead of recording-start (the real misalignment bug). See onSample.
  private var wavFrames = 0L

  override fun onStart(meta: Map<String, Any>) {
    submit {
      file.parentFile?.mkdirs()
      val f = RandomAccessFile(file, "rw")
      f.setLength(0)
      writeWavHeader(f, sampleRate)
      raf = f
      dataBytes = 0
      lastClockSyncNs = 0
      wavFrames = 0
      clockSyncWriter = File(file.parentFile, "${file.nameWithoutExtension}.clocksync.jsonl").bufferedWriter()
    }
  }

  override fun onSample(sample: AudioChunk) {
    submit {
      val f = raf ?: return@submit
      // PCM little-endian, the WAV sample layout (PCM16 today; ShortArray assumes 16-bit).
      val bytes = ByteArray(sample.frameCount * ACTIVE_PROFILE.bytesPerFrame)
      ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        .put(sample.pcm, 0, sample.frameCount)
      f.write(bytes)
      dataBytes += bytes.size
      // Clock-sync framePosition is WAV-relative (frames written here), so the inspector's
      // start = bootNs − framePosition/rate lands on RECORDING start, not stream start.
      wavFrames += sample.frameCount
      if (lastClockSyncNs == 0L || sample.ts - lastClockSyncNs >= Tuning.AUDIO_CLOCKSYNC_NS) {
        lastClockSyncNs = sample.ts
        val clockSync = JSONObject(
          mapOf("framePosition" to wavFrames, "bootNs" to sample.ts),
        )
        clockSyncWriter?.append(clockSync.toString())
        clockSyncWriter?.append('\n')
      }
    }
  }

  override fun onStop() {
    submit {
      raf?.let { f ->
        patchWavSizes(f, dataBytes)
        f.close()
      }
      raf = null
      clockSyncWriter?.flush()
      clockSyncWriter?.close()
      clockSyncWriter = null
    }
    io.shutdown()
    // Block until the WAV header is patched + closed so the manifest can bake this file's
    // stats at record-stop. Bounded so a stuck writer can't hang teardown.
    runCatching { io.awaitTermination(2L, TimeUnit.SECONDS) }
  }

  // A chunk can be dispatched from the read thread concurrently with onStop (the
  // source's CopyOnWriteArrayList snapshot still holds this just-detached sink), which
  // would submit after io.shutdown(). Drop such late tasks rather than crashing.
  private fun submit(task: () -> Unit) {
    try {
      io.execute(task)
    } catch (e: RejectedExecutionException) {
      // sink already stopped — drop the trailing chunk
    }
  }

  private fun writeWavHeader(f: RandomAccessFile, rate: Int) {
    val channels = ACTIVE_PROFILE.channelCount
    val bits = ACTIVE_PROFILE.bitsPerSample
    val byteRate = rate * channels * bits / 8
    val blockAlign = channels * bits / 8
    f.seek(0)
    f.writeBytes("RIFF"); f.write(le32(0)); f.writeBytes("WAVE")
    f.writeBytes("fmt "); f.write(le32(16)); f.write(le16(1)); f.write(le16(channels))
    f.write(le32(rate)); f.write(le32(byteRate)); f.write(le16(blockAlign)); f.write(le16(bits))
    f.writeBytes("data"); f.write(le32(0))
  }

  private fun patchWavSizes(f: RandomAccessFile, data: Long) {
    // RIFF chunk size = whole file − 8 (the "RIFF" tag + this size field itself).
    f.seek(4); f.write(le32(((WAV_HEADER_BYTES - 8) + data).toInt()))
    f.seek(40); f.write(le32(data.toInt()))
  }

  private fun le32(v: Int) = byteArrayOf(
    (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
    ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
  )

  private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}

// Facade over the two independent audio sources (camcorder + voice_recognition).
// Attaches the display sink and exposes the live source so the TakeRecorder can attach a WavStorageSink.
// @Synchronized on every map-touching method: startSource/attachStorage (and their
// stops) run on Expo async threads and can race — a plain HashMap would lose entries
// or throw ConcurrentModificationException. The monitor is reentrant, so stop()→
// stopSource() is fine.
internal class AudioController {
  private val sources = HashMap<String, AudioCaptureSource>()
  private val displaySinks = HashMap<String, CaptureSink<AudioChunk>>()

  @Synchronized
  fun startSource(activity: Activity?, label: String, emit: (Map<String, Any>) -> Unit): String {
    val audioSource = SOURCES[label] ?: return "unknown_source"
    val source = sources.getOrPut(label) { AudioCaptureSource(label, audioSource) }
    // Re-entrant: detach any prior display sink before attaching a fresh one.
    displaySinks.remove(label)?.let { source.detach(it) }
    val sink = AudioLevelDisplaySink(emit)
    source.attach(activity, sink) // throws on missing permission → promise rejects
    displaySinks[label] = sink
    return source.startStatus
  }

  @Synchronized
  fun stopSource(label: String) {
    val source = sources[label] ?: return
    displaySinks.remove(label)?.let { source.detach(it) }
  }

  @Synchronized
  fun stop() {
    sources.keys.toList().forEach { stopSource(it) }
  }

  @Synchronized
  fun dispose() {
    sources.values.forEach { it.dispose() }
    sources.clear()
    displaySinks.clear()
  }

  // Attach a storage sink (the TakeRecorder's WavStorageSink) to a source, get-or-creating
  // it. Returns the source's start status. Throws on missing permission, matching startSource.
  @Synchronized
  fun attachStorage(activity: Activity?, label: String, sink: CaptureSink<AudioChunk>): String {
    val audioSource = SOURCES[label] ?: return "unknown_source"
    val source = sources.getOrPut(label) { AudioCaptureSource(label, audioSource) }
    source.attach(activity, sink)
    return source.startStatus
  }

  @Synchronized
  fun detachStorage(label: String, sink: CaptureSink<AudioChunk>) {
    sources[label]?.detach(sink)
  }

  // Attach a raw-PCM consumer sink from another native module (XrCaptureBus.attachAudio),
  // get-or-creating the source. Returns "unknown_source" for an unknown label; throws on a
  // missing permission (the bus catches). See README → Consuming raw streams.
  @Synchronized
  fun attachConsumer(activity: Activity?, label: String, sink: CaptureSink<AudioChunk>): String {
    val audioSource = SOURCES[label] ?: return "unknown_source"
    val source = sources.getOrPut(label) { AudioCaptureSource(label, audioSource) }
    source.attach(activity, sink)
    return source.startStatus
  }

  @Synchronized
  fun detachConsumer(label: String, sink: CaptureSink<AudioChunk>) {
    sources[label]?.detach(sink)
  }

  // The live source for a label (or null), so callers can read its streamMeta().
  @Synchronized
  fun sourceFor(label: String): AudioCaptureSource? = sources[label]

  // Labels currently streaming to the display (started via startSource).
  @Synchronized
  fun liveLabels(): List<String> = displaySinks.keys.toList()

  fun knownLabels(): Set<String> = SOURCES.keys

  private companion object {
    val SOURCES = mapOf(
      "camcorder" to MediaRecorder.AudioSource.CAMCORDER,
      "voice_recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
    )
  }
}
