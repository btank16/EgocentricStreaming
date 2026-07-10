package expo.modules.xrandroid.streams

import android.app.Activity
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Plane
import androidx.xr.arcore.PlaneLabel
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import expo.modules.xrandroid.Permissions
import expo.modules.xrandroid.Tuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Latches the SESSION ORIGIN — the world tracking frame documented at session bring-up,
// surfaced via onSessionOrigin. Latched automatically once the XR session reaches a
// TRACKING device pose. Refcounted via streamStarted/streamStopped + recordingStarted.
// (The runtime Session can't be torn down in alpha — see XrSessionManager — so the physical
// world frame persists across recordings; only the logical record resets.)
// @Synchronized on every refcount transition: these run on Expo async threads and can
// race; active + latchJob + latched must move together. `latched` is also @Volatile so
// currentOrigin() (async thread) sees the value written by latchOrigin (Main).
internal class SessionOriginController {
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var active = 0
  private var latchJob: Job? = null
  @Volatile private var latched: Map<String, Any>? = null

  // A perception stream started (DISPLAY path). Only the 0→1 transition latches, so
  // the live readout gets an origin without disturbing one a recording already latched.
  @Synchronized
  fun streamStarted(activity: Activity?, emit: (Map<String, Any>) -> Unit) {
    active++
    if (active != 1) return
    val act = activity ?: return
    relatch(act, emit)
  }

  // A recording started (RECORD path). Holds a refcount AND always relatches a FRESH
  // origin — the manifest must document the head pose at RECORDING start, not one a
  // display stream may have latched minutes earlier.
  @Synchronized
  fun recordingStarted(activity: Activity?, emit: (Map<String, Any>) -> Unit) {
    active++
    val act = activity ?: return
    relatch(act, emit)
  }

  // A stream/recording stopped. The 1→0 transition clears the latch so the next
  // recording captures a fresh origin.
  @Synchronized
  fun streamStopped() {
    if (active == 0) return
    active--
    if (active > 0) return
    latchJob?.cancel()
    latchJob = null
    latched = null
  }

  // Cancel any in-flight latch and kick a fresh one. Caller holds the monitor.
  private fun relatch(activity: Activity, emit: (Map<String, Any>) -> Unit) {
    latchJob?.cancel()
    latchJob = scope.launch { latchOrigin(activity, emit) }
  }

  // Current latched origin (or null) — for getSessionOrigin()/late subscribers.
  fun currentOrigin(): Map<String, Any>? = latched

  @Synchronized
  fun dispose() {
    latchJob?.cancel()
    latchJob = null
    latched = null
    scope.cancel()
  }

  private suspend fun latchOrigin(activity: Activity, emit: (Map<String, Any>) -> Unit) {
    // Origin core = device pose → needs HEAD_TRACKING. Without it, no origin.
    if (
      activity.checkSelfPermission(Permissions.HEAD_TRACKING) != PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val session = XrSessionManager.ensureSession(activity) ?: return
    if (XrSessionManager.applyDeviceTracking(session) !is SessionConfigureSuccess) return

    // Floor reference is best-effort: only attempt if COARSE is granted AND plane
    // tracking configures successfully.
    val floorEnabled =
      activity.checkSelfPermission(Permissions.SCENE_UNDERSTANDING_COARSE) ==
        PackageManager.PERMISSION_GRANTED &&
        XrSessionManager.applyPlaneTracking(session) is SessionConfigureSuccess

    // Await the first solidly-tracked device pose; on timeout, fall back to the
    // latest sample and mark the origin unreliable (honest, non-fatal).
    val arDevice = ArDevice.getInstance(session)
    val tracked = withTimeoutOrNull(Tuning.ORIGIN_TIMEOUT_MS) {
      arDevice.state.first { it.trackingState == TrackingState.TRACKING }
    }
    val state = tracked ?: arDevice.state.value
    val reliable = tracked != null

    val t = state.devicePose.translation
    val r = state.devicePose.rotation
    val floor = if (floorEnabled) findFloor(session, t.y) else floorUnavailable()

    val origin = mapOf(
      "capturedAtBootNs" to SystemClock.elapsedRealtimeNanos(),
      "capturedAtWallMs" to System.currentTimeMillis(),
      "trackingState" to trackingStateLabel(state.trackingState),
      "reliable" to reliable,
      "devicePose" to mapOf(
        "translation" to listOf(t.x, t.y, t.z),
        "rotation" to listOf(r.x, r.y, r.z, r.w),
      ),
      "headHeightAboveOriginM" to t.y,
      "floor" to floor,
    )
    // Publish under the monitor so a concurrent streamStopped()/relatch can't
    // interleave the set with a clear; emit outside the lock (bridge call).
    synchronized(this) { latched = origin }
    emit(origin)
  }

  // Best-effort: wait briefly for a TRACKING FLOOR plane to appear, then read its
  // height (centerPose.y) in the world frame. Absent → available:false.
  private suspend fun findFloor(session: Session, headY: Float): Map<String, Any> {
    return try {
      val planes = withTimeoutOrNull(Tuning.FLOOR_TIMEOUT_MS) {
        Plane.subscribe(session).first { planes -> planes.any { isTrackedFloor(it) } }
      }
      val floorPlane = planes?.firstOrNull { isTrackedFloor(it) } ?: return floorUnavailable()
      val floorY = floorPlane.state.value.centerPose.translation.y
      mapOf(
        "available" to true,
        "floorYInWorldM" to floorY,
        "headHeightAboveFloorM" to (headY - floorY),
      )
    } catch (e: Exception) {
      // Floor is best-effort: a plane-tracking hiccup (e.g. mode momentarily
      // DISABLED by a concurrent reconfigure → Plane.subscribe throws) must never
      // crash the origin latch. Degrade to "no floor".
      floorUnavailable()
    }
  }

  private fun isTrackedFloor(plane: Plane): Boolean {
    val s = plane.state.value
    return s.label == PlaneLabel.FLOOR && s.trackingState == TrackingState.TRACKING
  }

  private fun floorUnavailable(): Map<String, Any> = mapOf("available" to false)
}
