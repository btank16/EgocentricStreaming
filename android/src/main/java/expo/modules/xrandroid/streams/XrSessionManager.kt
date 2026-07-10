package expo.modules.xrandroid.streams

import android.app.Activity
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.PlaneTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureResult
import androidx.xr.runtime.SessionCreateSuccess

// One shared Jetpack XR Session, reused across perception streams (head pose, hands,
// plane tracking). Config is applied ADDITIVELY via Config.Builder(session.config), so
// enabling one mode preserves the others. Session binds to the Activity lifecycle
// internally (Session.create(activity)) — no manual lifecycle calls.
// The apply* methods are @Synchronized because head/hand controllers (Expo async thread)
// and the origin latch (Main) reconfigure concurrently: each read-modify-write on the
// live config must be atomic, else a stale-base configure clobbers another mode back to
// DISABLED (e.g. plane tracking, crashing Plane.subscribe) — which is what makes the
// additive config actually additive across threads.
internal object XrSessionManager {
  private var session: Session? = null

  // The 1-arg Session.create(activity) is deprecated in alpha15 for a (context,
  // coroutineContext, lifecycleOwner) overload whose required CoroutineContext default
  // isn't documented. The 1-arg form uses activity-bound defaults and works, so keep it.
  @Suppress("DEPRECATION")
  @Synchronized
  fun ensureSession(activity: Activity): Session? {
    session?.let { return it }
    return when (val result = Session.create(activity)) {
      is SessionCreateSuccess -> result.session.also { session = it }
      else -> null
    }
  }

  @Synchronized
  fun applyDeviceTracking(session: Session): SessionConfigureResult {
    val config = Config.Builder(session.config)
      .setDeviceTracking(DeviceTrackingMode.SPATIAL)
      .build()
    return session.configure(config)
  }

  @Synchronized
  fun applyHandTracking(session: Session): SessionConfigureResult {
    val config = Config.Builder(session.config)
      .setHandTracking(HandTrackingMode.BOTH)
      .build()
    return session.configure(config)
  }

  // Plane tracking (semantic planes) for the session-origin floor reference. Requires
  // SCENE_UNDERSTANDING_COARSE (checked by the caller); additive like the others.
  @Synchronized
  fun applyPlaneTracking(session: Session): SessionConfigureResult {
    val config = Config.Builder(session.config)
      .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)
      .build()
    return session.configure(config)
  }

  // NOTE: no manual session teardown in Jetpack XR alpha — Session.destroy() is private
  // and lifecycle-driven, and Session is a per-Context singleton. The session lives for
  // the Activity's lifetime; "per-recording" origin boundaries are handled in
  // SessionOriginController (latch reset on last stream stop), not by teardown.
}
