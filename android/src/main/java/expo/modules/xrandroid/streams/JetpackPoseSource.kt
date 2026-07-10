package expo.modules.xrandroid.streams

import android.os.SystemClock
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// Head pose via ARCore-for-Jetpack-XR: collect ArDevice.state (a StateFlow the runtime
// updates while the Session is resumed) and stamp each emission with
// elapsedRealtimeNanos() at delivery (display-grade; no capture timestamp).
internal class JetpackPoseSource(
  private val session: Session,
  private val scope: CoroutineScope,
) : PoseSource {
  private var job: Job? = null

  override fun start(onSample: (HeadPoseSample) -> Unit) {
    if (job != null) return
    val arDevice = ArDevice.getInstance(session)
    job = scope.launch {
      arDevice.state.collect { state ->
        val t = state.devicePose.translation
        val r = state.devicePose.rotation
        onSample(
          HeadPoseSample(
            t.x, t.y, t.z,
            r.x, r.y, r.z, r.w,
            trackingStateLabel(state.trackingState),
            SystemClock.elapsedRealtimeNanos(),
          ),
        )
      }
    }
  }

  override fun stop() {
    job?.cancel()
    job = null
  }
}

// Shared by the head-pose and hand-joint sources.
internal fun trackingStateLabel(state: TrackingState): String = when (state) {
  TrackingState.TRACKING -> "tracking"
  TrackingState.PAUSED -> "paused"
  TrackingState.STOPPED -> "stopped"
  TrackingState.TRACKING_DEGRADED -> "degraded"
  else -> "unknown"
}
