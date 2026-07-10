package expo.modules.xrandroid.bus

// Public seam for a foreign native module to consume a raw, full-rate light stream (head
// pose, hand joints). XrCaptureBus wraps it in the internal CaptureSink, keeping the fan-out
// sealed. Samples are the same by-reference maps the recorder/display receive (head:
// {translation, rotation, trackingState, ts}; hands: {handedness, joints, …}) — native-to-
// native, no bridge. Heavy streams go elsewhere: camera pixels via XrFrameBus, audio PCM via
// XrPcmConsumer. See README → Consuming raw streams.
interface XrStreamConsumer {
  // Called once when the consumer is attached, with the stream's metadata (stream id, clock,
  // fidelity tier). Default no-op so a consumer can override only onSample.
  fun onStart(meta: Map<String, Any>) {}

  // Called for every full-rate sample. Runs on the source's capture thread — keep it cheap
  // and hand heavy work to your own executor.
  fun onSample(sample: Map<String, Any>)

  // Called once when the consumer is detached (or the module is destroyed).
  fun onStop() {}
}
