package expo.modules.xrandroid.bus

// The typed counterpart to XrStreamConsumer for raw audio PCM — the highest-rate stream, so
// it gets an allocation-free callback rather than a Map per block. Native-to-native, never
// crosses the bridge. Attach via XrCaptureBus.attachAudio(sourceLabel, consumer) where
// sourceLabel ∈ {"camcorder","voice_recognition"}. See README → Consuming raw streams.
interface XrPcmConsumer {
  // Called once when attached, with the source's metadata (sampleRate, channels, fidelity,
  // timestampMode). Default no-op so a consumer can override only onPcm.
  fun onStart(meta: Map<String, Any>) {}

  // Called for every PCM block on the audio read thread — keep it cheap. `pcm` is the block's
  // OWN copy (the read buffer is not reused across the bus), so it may be retained.
  //   frameCount   — valid samples in `pcm` (per channel)
  //   sampleRate   — actual granted rate (Hz)
  //   channels     — actual channel count
  //   tsNs         — boottime timestamp of the block (read-time, or HAL capture instant)
  //   framePosition — running frame count since the source started
  fun onPcm(
    pcm: ShortArray,
    frameCount: Int,
    sampleRate: Int,
    channels: Int,
    tsNs: Long,
    framePosition: Long,
  )

  // Called once when detached (or the module is destroyed).
  fun onStop() {}
}
