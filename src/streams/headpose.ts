// Head-pose stream — headset orientation/position from ARCore for Jetpack XR.
// Perception space, delivery-stamped (Jetpack exposes no capture timestamp).
// See HeadPoseCaptureSource.kt / JetpackPoseSource.kt.
import { nativeModule, NOOP_SUBSCRIPTION, type Subscription } from "../native";

export interface HeadPoseSample {
  /** Position in metres (perception space), [x, y, z]. */
  translation: [number, number, number];
  /** Orientation quaternion, [x, y, z, w]. */
  rotation: [number, number, number, number];
  trackingState: "tracking" | "degraded" | "paused" | "stopped" | "unknown";
  /** elapsedRealtimeNanos() at delivery (boottime ns) — display-grade stamp. */
  ts: number;
}

export type HeadPoseListener = (sample: HeadPoseSample) => void;

/** Subscribe to head-pose samples. Returns a subscription; call `.remove()` to stop
 * listening. No-op when the native module is unavailable. */
export function addHeadPoseListener(listener: HeadPoseListener): Subscription {
  if (!nativeModule) return NOOP_SUBSCRIPTION;
  return nativeModule.addListener("onDevicePose", (event) =>
    listener(event as HeadPoseSample),
  );
}

/** Start the perception session with device tracking and begin emitting samples.
 * `bridgeRateHz` throttles the DISPLAY emit only (omit ⇒ full native rate); a storage sink
 * records the full rate regardless. Rejects if HEAD_TRACKING isn't granted or the session
 * can't be created. */
export function startHeadPose(bridgeRateHz?: number): Promise<void> {
  return nativeModule?.startHeadPose(bridgeRateHz ?? null) ?? Promise.resolve();
}

/** Stop emitting head-pose samples (leaves the session alive for other streams). */
export function stopHeadPose(): Promise<void> {
  return nativeModule?.stopHeadPose() ?? Promise.resolve();
}
