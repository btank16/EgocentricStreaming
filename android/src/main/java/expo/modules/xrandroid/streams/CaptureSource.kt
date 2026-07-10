package expo.modules.xrandroid.streams

import android.app.Activity
import java.util.concurrent.CopyOnWriteArrayList

// Capture↔consumer split for the recording layer (see README → Recording). A
// CaptureSource fans full-rate, sink-agnostic samples out to any number of CaptureSinks;
// throttling and formatting are sink concerns, so one capture can feed a throttled
// display and a full-rate storage writer at once.
//
// Lifecycle is sink-refcounted: source starts on first attach, stops on last detach.
// Activity is passed at attach for sources needing permission + the shared XR session;
// a later attach to an already-running source ignores it.

internal interface CaptureSink<S> {
  // Called on attach with the source's stream metadata (name, clock, fidelity tier).
  fun onStart(meta: Map<String, Any>)
  fun onSample(sample: S)
  fun onStop()
}

internal abstract class CaptureSource<S> {
  private val sinks = CopyOnWriteArrayList<CaptureSink<S>>()
  private var started = false

  // Attach a sink; brings the source up on the first one. onStart(activity) may throw
  // (missing permission / no session) — nothing is left half-attached, the rejection
  // propagates.
  // @Synchronized: attach/detach can race across Expo async functions, and the `started`
  // check-then-act must run onStart() exactly once.
  @Synchronized
  fun attach(activity: Activity?, sink: CaptureSink<S>) {
    if (!started) {
      onStart(activity)
      started = true
    }
    sink.onStart(streamMeta())
    sinks.add(sink)
  }

  // Detach a sink; tears the source down when the last one leaves.
  @Synchronized
  fun detach(sink: CaptureSink<S>) {
    if (!sinks.remove(sink)) return
    sink.onStop()
    if (sinks.isEmpty() && started) {
      started = false
      onStop()
    }
  }

  // Deliver a sample to every attached sink (called from subclasses' capture loop).
  // CopyOnWriteArrayList keeps iteration safe against concurrent attach/detach.
  protected fun dispatch(sample: S) {
    sinks.forEach { it.onSample(sample) }
  }

  // Stream metadata (name, clock, fidelity); handed to each sink on attach. Override to describe.
  open fun streamMeta(): Map<String, Any> = emptyMap()

  // Bring the sensor/stream up. Called once, on first attach. May throw to reject.
  protected abstract fun onStart(activity: Activity?)

  // Tear the underlying stream down. Called once, when the last sink detaches.
  protected abstract fun onStop()

  // Full teardown for module destruction: stop every sink, then the source.
  @Synchronized
  open fun dispose() {
    sinks.forEach { it.onStop() }
    sinks.clear()
    if (started) {
      started = false
      onStop()
    }
  }
}
