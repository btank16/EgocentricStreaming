package expo.modules.xrandroid.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import expo.modules.xrandroid.Permissions

// Cross-cutting device capability discovery: XR runtime feature flags + permission grants,
// plus the top-level getDeviceCapabilities aggregator (camera/audio delegated to their
// probes). Side-effect-free — no XR Session, no prompts. See README → Capability discovery.
internal object Capabilities {

  // The getDeviceCapabilities() bridge payload, composed from the endpoint probes.
  fun build(ctx: Context?): Map<String, Any?> {
    val cameraManager = ctx?.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    val cameras = CameraProbe.describeInventory(cameraManager)
    return mapOf(
      "xrRuntimeAvailable" to isXrRuntimeAvailable(ctx),
      "xrFeatures" to mapOf(
        "spatialApi" to hasFeature(ctx, FEATURE_XR_SPATIAL),
        "openxrApi" to hasFeature(ctx, FEATURE_XR_OPENXR),
      ),
      "permissions" to mapOf(
        "headTracking" to permissionGranted(ctx, Permissions.HEAD_TRACKING),
        "handTracking" to permissionGranted(ctx, Permissions.HAND_TRACKING),
        "sceneUnderstandingCoarse" to permissionGranted(ctx, Permissions.SCENE_UNDERSTANDING_COARSE),
        "sceneUnderstandingFine" to permissionGranted(ctx, Permissions.SCENE_UNDERSTANDING_FINE),
        "camera" to permissionGranted(ctx, Manifest.permission.CAMERA),
        "recordAudio" to permissionGranted(ctx, Manifest.permission.RECORD_AUDIO),
      ),
      // Camera inventory + concurrency (CameraProbe owns the camera facts).
      "cameras" to cameras["cameras"],
      "concurrentCameraCombos" to cameras["concurrentCameraCombos"],
      "worldStereoConcurrent" to cameras["worldStereoConcurrent"],
      "audio" to AudioProbe.describeInventory(ctx),
      // Jetpack XR perception exposes no per-sample capture timestamp, so head/hand poses
      // can only be delivery-stamped. The seam for measurement-grade (OpenXR) capture
      // reports this via HeadTrackingProbe; kept here too for the legacy capabilities shape.
      "perceptionTimestampExposed" to false,
    )
  }

  fun isXrRuntimeAvailable(ctx: Context?): Boolean =
    hasFeature(ctx, FEATURE_XR_SPATIAL) || hasFeature(ctx, FEATURE_XR_OPENXR)

  private fun hasFeature(ctx: Context?, name: String): Boolean =
    ctx?.packageManager?.hasSystemFeature(name) == true

  fun permissionGranted(ctx: Context?, permission: String): Boolean {
    if (ctx == null) return false
    // Context.checkSelfPermission exists since API 23 (module minSdk is 24), so no
    // androidx.core dependency is needed.
    return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
  }

  private const val FEATURE_XR_SPATIAL = "android.software.xr.api.spatial"
  private const val FEATURE_XR_OPENXR = "android.software.xr.api.openxr"
}
