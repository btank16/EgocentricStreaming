package expo.modules.xrandroid.streams

import android.app.Activity
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.xr.arcore.Hand
import androidx.xr.runtime.SessionConfigureSuccess
import expo.modules.xrandroid.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// Hand-joint capture source: reuses the shared Session (adds HandTrackingMode.BOTH
// additively) and collects both hands together (26 joints × 7 floats each). Capture is
// full native rate; display throttle is opt-in via bridgeRateHz (keyed per handedness in
// the DisplaySink), storage records every frame. Delivery-stamped; both hands start/stop
// together.
internal class HandCaptureSource : CaptureSource<Map<String, Any>>() {
  // Collect on Default (off the render thread): building the 26-joint map per hand per
  // frame at full rate must not run on Main. Left/right are separate coroutines, so the
  // DisplaySink guards its throttle state and marshals the emit back to Main.
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var leftJob: Job? = null
  private var rightJob: Job? = null

  override fun streamMeta(): Map<String, Any> = mapOf(
    "stream" to "hands",
    "clock" to "boottime",
    "fidelity" to "delivery-stamped",
  )

  override fun onStart(activity: Activity?) {
    requireNotNull(activity) { "No current activity to host the XR session" }
    require(
      activity.checkSelfPermission(Permissions.HAND_TRACKING) == PackageManager.PERMISSION_GRANTED,
    ) { "HAND_TRACKING permission not granted" }
    val session = XrSessionManager.ensureSession(activity)
      ?: throw IllegalStateException("XR session could not be created on this device")
    val result = XrSessionManager.applyHandTracking(session)
    check(result is SessionConfigureSuccess) { "Session configure failed: $result" }
    stopJobs()
    leftJob = collectHand(Hand.left(session), "left")
    rightJob = collectHand(Hand.right(session), "right")
  }

  private fun collectHand(hand: Hand, handedness: String): Job =
    scope.launch {
      hand.state.collect { state ->
        val joints = HashMap<String, Any>(state.handJoints.size)
        for ((type, pose) in state.handJoints) {
          val t = pose.translation
          val r = pose.rotation
          joints[type.name] = listOf(t.x, t.y, t.z, r.x, r.y, r.z, r.w)
        }
        dispatch(
          mapOf(
            "handedness" to handedness,
            "trackingState" to trackingStateLabel(state.trackingState),
            "jointCount" to joints.size,
            "joints" to joints,
            "ts" to SystemClock.elapsedRealtimeNanos(),
          ),
        )
      }
    }

  private fun stopJobs() {
    leftJob?.cancel()
    rightJob?.cancel()
    leftJob = null
    rightJob = null
  }

  override fun onStop() {
    stopJobs()
  }

  override fun dispose() {
    super.dispose()
    scope.cancel()
  }
}
