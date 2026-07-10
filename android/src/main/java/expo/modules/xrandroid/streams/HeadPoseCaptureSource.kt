package expo.modules.xrandroid.streams

import android.app.Activity
import android.content.pm.PackageManager
import androidx.xr.runtime.SessionConfigureSuccess
import expo.modules.xrandroid.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// Head-pose capture source: ensures the shared XR session, enables device tracking, and
// dispatches ArDevice samples (full rate, delivery-stamped) to its sinks. PoseSource is
// the swap seam (JetpackPoseSource today, OpenXrPoseSource later). Collection runs on
// Default; the DisplaySink marshals its bridge emit back to Main.
internal class HeadPoseCaptureSource : CaptureSource<Map<String, Any>>() {
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var poseSource: PoseSource? = null

  override fun streamMeta(): Map<String, Any> = mapOf(
    "stream" to "head",
    "clock" to "boottime",
    "fidelity" to "delivery-stamped",
  )

  override fun onStart(activity: Activity?) {
    requireNotNull(activity) { "No current activity to host the XR session" }
    require(
      activity.checkSelfPermission(Permissions.HEAD_TRACKING) == PackageManager.PERMISSION_GRANTED,
    ) { "HEAD_TRACKING permission not granted" }
    val session = XrSessionManager.ensureSession(activity)
      ?: throw IllegalStateException("XR session could not be created on this device")
    val result = XrSessionManager.applyDeviceTracking(session)
    check(result is SessionConfigureSuccess) { "Session configure failed: $result" }
    poseSource?.stop()
    poseSource = JetpackPoseSource(session, scope).also { source ->
      source.start { sample -> dispatch(sample.toMap()) }
    }
  }

  override fun onStop() {
    poseSource?.stop()
    poseSource = null
  }

  override fun dispose() {
    super.dispose()
    scope.cancel()
  }
}
