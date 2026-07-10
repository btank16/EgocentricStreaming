package expo.modules.xrandroid.streams

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

// The two concrete sinks. A CaptureSource fans out to any mix of these: the display
// sink forwards to the live UI, the storage sink writes the lossless track to disk.

// Live-display sink: forwards samples to the Expo bridge `emit`. Throttling is a display
// concern (bridge needs only ~display rate), so it lives here, leaving any storage sink
// to receive every sample. throttleNs == 0 forwards everything; > 0 drops samples within
// the interval. `keyOf` throttles PER key: a throttled hand stream interleaves
// left+right, so it keys on handedness — a global throttle would cap the pair at the
// combined rate and starve one hand.
internal class DisplaySink<S>(
  private val throttleNs: Long = 0L,
  private val keyOf: ((S) -> Any?)? = null,
  private val emit: (S) -> Unit,
) : CaptureSink<S> {
  // Per-key last-emit time. onSample can run concurrently (head/hands collect off Main,
  // and hands' left+right are separate coroutines), so guard the map.
  private val lastEmitNs = HashMap<Any?, Long>()
  private val mainHandler = Handler(Looper.getMainLooper())

  override fun onStart(meta: Map<String, Any>) {}

  override fun onSample(sample: S) {
    if (throttleNs > 0L) {
      val key = keyOf?.invoke(sample)
      val now = SystemClock.elapsedRealtimeNanos()
      synchronized(lastEmitNs) {
        val last = lastEmitNs[key] ?: 0L
        if (last != 0L && now - last < throttleNs) return
        lastEmitNs[key] = now
      }
    }
    // Marshal the emit to Main; the sample was built off-thread, so the per-frame
    // allocation stays off the render thread.
    mainHandler.post { emit(sample) }
  }

  override fun onStop() {
    synchronized(lastEmitNs) { lastEmitNs.clear() }
  }
}

// Storage sink: appends each sample as one JSON line (JSONL) to a file. Writes run on a
// single background thread so capture is never blocked by disk I/O and sample order is
// preserved. Owned by the TakeRecorder, which supplies the target file. Nested sample
// maps (e.g. hand joints) — org.json wraps nested Maps/Lists recursively.
internal class JsonlStorageSink(private val file: File) : CaptureSink<Map<String, Any>> {
  private val io = Executors.newSingleThreadExecutor()

  @Volatile
  private var writer: BufferedWriter? = null

  override fun onStart(meta: Map<String, Any>) {
    submit {
      file.parentFile?.mkdirs()
      writer = file.bufferedWriter()
    }
  }

  override fun onSample(sample: Map<String, Any>) {
    submit {
      val w = writer ?: return@submit
      w.append(JSONObject(sample).toString())
      w.append('\n')
    }
  }

  override fun onStop() {
    submit {
      writer?.flush()
      writer?.close()
      writer = null
    }
    io.shutdown()
    // Block until flushed/closed so the take manifest can bake stats from this file at
    // record-stop. Bounded so a stuck writer can't hang teardown.
    runCatching { io.awaitTermination(FLUSH_TIMEOUT_SEC, TimeUnit.SECONDS) }
  }

  private companion object {
    const val FLUSH_TIMEOUT_SEC = 2L
  }

  // A sample can dispatch concurrently with onStop: dispatch() iterates a
  // CopyOnWriteArrayList snapshot, so an in-flight emit may submit after io.shutdown().
  // Drop such late tasks instead of crashing the capture thread with
  // RejectedExecutionException.
  private fun submit(task: () -> Unit) {
    try {
      io.execute(task)
    } catch (e: RejectedExecutionException) {
      // sink already stopped — drop the trailing sample
    }
  }
}
