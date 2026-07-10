package expo.modules.xrandroid.streams

// One head-pose sample. Translation in metres, rotation as a quaternion (x,y,z,w), both
// in perception space. `ts` is elapsedRealtimeNanos() at delivery — Jetpack exposes no
// capture timestamp, so this is a delivery (display-grade) stamp on the boottime clock.
internal data class HeadPoseSample(
  val tx: Float,
  val ty: Float,
  val tz: Float,
  val qx: Float,
  val qy: Float,
  val qz: Float,
  val qw: Float,
  val trackingState: String,
  val ts: Long,
) {
  fun toMap(): Map<String, Any> = mapOf(
    "translation" to listOf(tx, ty, tz),
    "rotation" to listOf(qx, qy, qz, qw),
    "trackingState" to trackingState,
    "ts" to ts,
  )
}

// Abstraction over how head pose is obtained — the swap seam for OpenXR (see README →
// OpenXR open door). JetpackPoseSource reads ArDevice.state (delivery-stamped); a future
// OpenXrPoseSource would xrLocateSpace(VIEW, …, cameraTime) for measurement-grade
// alignment with the camera/audio clock.
internal interface PoseSource {
  fun start(onSample: (HeadPoseSample) -> Unit)
  fun stop()
}
