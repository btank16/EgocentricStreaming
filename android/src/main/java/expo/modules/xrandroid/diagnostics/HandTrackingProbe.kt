package expo.modules.xrandroid.diagnostics

import android.content.Context
import expo.modules.xrandroid.Permissions

// Hand-tracking endpoint probe: static facts only (runtime, permission, BOTH-hands config,
// per-hand joint count, timestamp mode) — no Session, no prompt. timestampMode is the same
// DELIVERY_STAMPED OpenXR seam as head tracking (see README → OpenXR open door).
internal object HandTrackingProbe {

  // Jetpack XR HandJointType exposes 26 joints per hand.
  private const val JOINTS_PER_HAND = 26

  fun describe(ctx: Context?): Map<String, Any?> = mapOf(
    "xrRuntimeAvailable" to Capabilities.isXrRuntimeAvailable(ctx),
    "permissionGranted" to Capabilities.permissionGranted(ctx, Permissions.HAND_TRACKING),
    "source" to "jetpack-hand",
    // The runtime tracks both hands together (HandTrackingMode.BOTH); a single hand
    // can't be tracked alone.
    "trackingMode" to "BOTH",
    "jointsPerHand" to JOINTS_PER_HAND,
    "clock" to "boottime",
    "timestampMode" to resolveTimestampMode(),
    "captureTimestampExposed" to (resolveTimestampMode() == HeadTrackingProbe.MODE_CAPTURE_STAMPED),
  )

  // Shares head tracking's timestamp-mode vocabulary — both perception streams ride the
  // same Jetpack/OpenXR seam, so they report the same tier.
  fun resolveTimestampMode(): String = HeadTrackingProbe.resolveTimestampMode()
}
