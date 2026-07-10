package expo.modules.xrandroid.bus

import android.app.Activity
import android.util.Log
import expo.modules.xrandroid.streams.AudioChunk
import expo.modules.xrandroid.streams.CaptureSink
import expo.modules.xrandroid.streams.CaptureSource

// PUBLIC open door: attach an XrStreamConsumer to a running light stream by id. Attaching
// refcounts the source up; the returned AutoCloseable detaches (and tears it down if last).
// Bound to an XrSourceProvider by the module at create time — an indirection so the bus
// depends on neither the Module nor StreamController concretely. Before bind / after unbind,
// attach is a logged no-op so a cross-module consumer never crashes the host.
// See README → Consuming raw streams.
object XrCaptureBus {

  private const val TAG = "XrCaptureBus"

  @Volatile
  private var provider: XrSourceProvider? = null

  // Bound by the module (OnCreate → unbind on OnDestroy); only this module owns the sources.
  internal fun bind(p: XrSourceProvider) {
    provider = p
  }

  internal fun unbind() {
    provider = null
  }

  // Attach a consumer to a light stream. `streamId` ∈ {"head","hands"}. Returns an AutoCloseable
  // that detaches; a logged no-op (never thrown) when the bus isn't bound, the stream is unknown,
  // or the source can't start (e.g. missing permission).
  fun attach(streamId: String, consumer: XrStreamConsumer): AutoCloseable {
    val p = provider ?: run {
      Log.w(TAG, "attach($streamId) before bind — no-op (module not created yet?)")
      return AutoCloseable {}
    }
    val source = p.sourceFor(streamId) ?: run {
      Log.w(TAG, "attach($streamId) — unknown/unsupported stream — no-op")
      return AutoCloseable {}
    }
    val sink = ConsumerSink(consumer)
    return try {
      source.attach(p.currentActivity(), sink) // refcount-brings-up; may throw on permission
      Log.i(TAG, "consumer attached to $streamId")
      AutoCloseable {
        source.detach(sink)
        Log.i(TAG, "consumer detached from $streamId")
      }
    } catch (e: Throwable) {
      Log.w(TAG, "attach($streamId) failed: ${e.message} — no-op")
      AutoCloseable {}
    }
  }

  // Attach a raw-PCM consumer to a mic source. `sourceLabel` ∈ {"camcorder","voice_recognition"}.
  // Returns an AutoCloseable that detaches; a logged no-op (never thrown) when the bus isn't
  // bound, the label is unknown, or the source can't start.
  fun attachAudio(sourceLabel: String, consumer: XrPcmConsumer): AutoCloseable {
    val p = provider ?: run {
      Log.w(TAG, "attachAudio($sourceLabel) before bind — no-op")
      return AutoCloseable {}
    }
    val sink = PcmConsumerSink(consumer)
    return try {
      p.attachAudioSink(sourceLabel, sink)?.also { Log.i(TAG, "pcm consumer attached to $sourceLabel") }
        ?: run {
          Log.w(TAG, "attachAudio($sourceLabel) — unknown source — no-op")
          AutoCloseable {}
        }
    } catch (e: Throwable) {
      Log.w(TAG, "attachAudio($sourceLabel) failed: ${e.message} — no-op")
      AutoCloseable {}
    }
  }

  // Adapts a public XrStreamConsumer to the internal CaptureSink, sealing the internals.
  private class ConsumerSink(private val consumer: XrStreamConsumer) : CaptureSink<Map<String, Any>> {
    override fun onStart(meta: Map<String, Any>) = consumer.onStart(meta)
    override fun onSample(sample: Map<String, Any>) = consumer.onSample(sample)
    override fun onStop() = consumer.onStop()
  }

  // Adapts a public XrPcmConsumer to the internal AudioChunk sink (typed PCM, no Map boxing).
  private class PcmConsumerSink(private val consumer: XrPcmConsumer) : CaptureSink<AudioChunk> {
    override fun onStart(meta: Map<String, Any>) = consumer.onStart(meta)
    override fun onSample(sample: AudioChunk) =
      consumer.onPcm(sample.pcm, sample.frameCount, sample.sampleRate, sample.channels, sample.ts, sample.framePosition)
    override fun onStop() = consumer.onStop()
  }
}

// The bus's view of the live sources — implemented by whoever owns them (StreamController).
internal interface XrSourceProvider {
  // The map-based light source for a stream id ("head"/"hands"), or null if unknown.
  fun sourceFor(streamId: String): CaptureSource<Map<String, Any>>?

  // Attach a PCM sink to a mic source, get-or-creating it; returns a detach handle or null if
  // the label is unknown. May throw on missing permission (the bus catches). Audio sources are
  // lazily created, so this can't be a plain `sourceFor` like the light streams.
  fun attachAudioSink(label: String, sink: CaptureSink<AudioChunk>): AutoCloseable?

  // The Activity to bring a source up (perception needs it for permission + the XR session).
  fun currentActivity(): Activity?
}
