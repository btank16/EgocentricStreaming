package expo.modules.xrandroid.streams

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// All-intra HEVC encoder → MP4, the camera capture reference. See README → Recording.
// All-intra (KEY_I_FRAME_INTERVAL = 0): frame-exact random access with no temporal artifacts,
// on the fast hardware HEVC encoder rather than the slow JPEG ISP (probed: 58.7 vs 10 fps @ 9 MP).
// The camera writes straight into `inputSurface` (no CPU copy), so buffer timestamps are the
// camera's SENSOR_TIMESTAMP; we also record it per frame to a sidecar (the alignment reference;
// cam0/cam2 aren't shutter-synced). `codecName` is the encoder chosen by EncoderProbe.resolve();
// null → platform picks via createEncoderByType (pre-probe fallback).
internal class EncoderRecorder(
  private val mp4File: File,
  private val framesSidecar: File,
  private val codecName: String?,
  private val width: Int,
  private val height: Int,
  private val fps: Int,
  private val bitRate: Int,
) {
  private var codec: MediaCodec? = null
  private var muxer: MediaMuxer? = null
  private var trackIndex = -1
  private var muxerStarted = false
  private var drainThread: Thread? = null

  @Volatile
  private var running = false

  // stop() can be reached from two threads (the view's onDetached/UI path and the
  // TakeRecorder's background-handler path) — run the teardown exactly once.
  private val stopped = AtomicBoolean(false)

  // Frame sidecar is written from the camera thread; guarded against the stop close.
  private val sidecarLock = Any()
  private var frameWriter: BufferedWriter? = null
  private var frameIndex = 0

  // The Surface the camera capture session targets. Valid only between start()/stop().
  var inputSurface: Surface? = null
    private set

  fun start() {
    val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      // 0 → all frames are sync (I-)frames: frame-exact random access, no inter-frame coding.
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
    }
    // Prefer the probe-chosen encoder by name; fall back to type-based selection if the
    // named codec can't be created (stale name / unavailable).
    val c = codecName?.let {
      runCatching { MediaCodec.createByCodecName(it) }.getOrNull()
    } ?: MediaCodec.createEncoderByType(MIME)
    c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    inputSurface = c.createInputSurface()
    c.start()
    codec = c

    muxer = MediaMuxer(mp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    framesSidecar.parentFile?.mkdirs()
    frameWriter = framesSidecar.bufferedWriter()
    frameIndex = 0

    running = true
    drainThread = Thread { drainLoop(c) }.apply { start() }
  }

  // Called per camera frame from the capture CaptureCallback (background handler).
  // Records frameIndex → SENSOR_TIMESTAMP (boottime ns) — the alignment reference.
  fun onCameraFrame(sensorTs: Long) {
    synchronized(sidecarLock) {
      val w = frameWriter ?: return
      try {
        w.append("{\"i\":")
        w.append(frameIndex.toString())
        w.append(",\"sensorTs\":")
        w.append(sensorTs.toString())
        w.append("}\n")
        frameIndex++
      } catch (e: Exception) {
        // sidecar closed mid-stop — drop
      }
    }
  }

  private fun drainLoop(c: MediaCodec) {
    val info = MediaCodec.BufferInfo()
    while (running) {
      val outIndex = try {
        c.dequeueOutputBuffer(info, TIMEOUT_US)
      } catch (e: Exception) {
        Log.w(TAG, "dequeue failed: ${e.message}")
        break
      }
      when {
        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          // The real format (with csd) arrives once before the first frame. Null-check
          // rather than !! so a concurrent stop() nulling the muxer can't NPE-crash
          // this bare thread.
          val m = muxer ?: break
          trackIndex = m.addTrack(c.outputFormat)
          m.start()
          muxerStarted = true
        }
        outIndex >= 0 -> {
          val buf = c.getOutputBuffer(outIndex)
          val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
          if (buf != null && info.size > 0 && muxerStarted && !isConfig) {
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            muxer?.writeSampleData(trackIndex, buf, info)
          }
          c.releaseOutputBuffer(outIndex, false)
          if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }
        // INFO_TRY_AGAIN_LATER → keep polling
      }
    }
  }

  fun stop() {
    // Run exactly once even if called concurrently from two threads.
    if (!stopped.compareAndSet(false, true)) return
    // End the input stream so the drain loop sees EOS, then let it finish in order.
    try {
      codec?.signalEndOfInputStream()
    } catch (e: Exception) {
      // ignore — codec may already be gone
    }
    try {
      drainThread?.join(DRAIN_JOIN_MS)
    } catch (e: InterruptedException) {
      // ignore
    }
    running = false
    drainThread = null

    try { codec?.stop() } catch (e: Exception) {}
    try { codec?.release() } catch (e: Exception) {}
    codec = null
    inputSurface = null

    try { if (muxerStarted) muxer?.stop() } catch (e: Exception) {}
    try { muxer?.release() } catch (e: Exception) {}
    muxer = null
    muxerStarted = false

    synchronized(sidecarLock) {
      try {
        frameWriter?.flush()
        frameWriter?.close()
      } catch (e: Exception) {
        // ignore
      }
      frameWriter = null
    }
  }

  private companion object {
    const val TAG = "XrEncoderRecorder"
    const val MIME = MediaFormat.MIMETYPE_VIDEO_HEVC
    const val TIMEOUT_US = 10_000L
    const val DRAIN_JOIN_MS = 2_000L
  }
}
