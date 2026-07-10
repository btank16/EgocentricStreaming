package expo.modules.xrandroid.diagnostics

import android.content.Context
import expo.modules.xrandroid.Permissions

// Head-tracking endpoint probe: static facts only (runtime, permission, fixed config,
// timestamp mode) — no Session, no prompt. timestampMode is DELIVERY_STAMPED until the
// OpenXR seam lands (see README → OpenXR open door).
internal object HeadTrackingProbe {

  const val MODE_DELIVERY_STAMPED = "delivery-stamped"
  const val MODE_CAPTURE_STAMPED = "capture-stamped"

  fun describe(ctx: Context?): Map<String, Any?> = mapOf(
    "xrRuntimeAvailable" to Capabilities.isXrRuntimeAvailable(ctx),
    "permissionGranted" to Capabilities.permissionGranted(ctx, Permissions.HEAD_TRACKING),
    // The fixed perception config the head stream applies (Jetpack ArDevice, SPATIAL).
    "source" to "jetpack-ardevice",
    "trackingMode" to "SPATIAL",
    // A fused 6-DoF pose (SLAM/VIO), not raw IMU. Full native rate, no native throttle.
    "poseKind" to "fused-6dof",
    "clock" to "boottime",
    // The alignment fidelity the pose path can deliver today (the OpenXR seam).
    "timestampMode" to resolveTimestampMode(),
    "captureTimestampExposed" to (resolveTimestampMode() == MODE_CAPTURE_STAMPED),
  )

  // The pose path's timestamp mode. DELIVERY_STAMPED for Jetpack; an OpenXrPoseSource would
  // return CAPTURE_STAMPED. Kept as a single source of truth so the probe and the recorder
  // agree; wired to the live PoseSource when the OpenXR implementation exists.
  fun resolveTimestampMode(): String = MODE_DELIVERY_STAMPED
}
