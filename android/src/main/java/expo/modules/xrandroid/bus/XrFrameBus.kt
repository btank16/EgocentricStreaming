package expo.modules.xrandroid.bus

import android.util.Log
import android.view.Surface
import expo.modules.xrandroid.streams.CameraRegistry

// PUBLIC open door for HEAVY camera pixels — which must never cross the bridge. A consumer
// supplies a Surface; the camera adds it as an extra Camera2 capture target, so frames fan out
// IN HARDWARE with their SENSOR_TIMESTAMP — no pixel copy, no bridge. Requires the camera's
// preview to be mounted; returns a no-op AutoCloseable if that camera isn't previewing.
//
// CAVEAT (LIMITED hardware, e.g. Galaxy XR): a consumer surface is an ADDITIONAL stream. The
// guaranteed combo is preview + record; a third stream may exceed it and fail session
// configuration (surfaced via onCameraError). Best-effort, like concurrent stereo.
// See README → Consuming raw streams.
object XrFrameBus {

  private const val TAG = "XrFrameBus"

  fun attachConsumerSurface(cameraId: String, surface: Surface): AutoCloseable {
    val view = CameraRegistry.get(cameraId) ?: run {
      Log.w(TAG, "attachConsumerSurface($cameraId) — camera not previewing — no-op")
      return AutoCloseable {}
    }
    return view.attachConsumerSurface(surface)
  }
}
