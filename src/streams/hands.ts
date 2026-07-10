// Hand-joint stream — both hands' 26 joints from ARCore for Jetpack XR. Perception
// space, delivery-stamped. Each hand emits independently. See HandCaptureSource.kt.
import { nativeModule, NOOP_SUBSCRIPTION, type Subscription } from "../native";

export type Handedness = "left" | "right";

export interface HandSample {
  handedness: Handedness;
  trackingState: "tracking" | "degraded" | "paused" | "stopped" | "unknown";
  /** Number of joints present this frame (26 when fully tracked). */
  jointCount: number;
  /**
   * Joint name → [tx, ty, tz, qx, qy, qz, qw] (metres + quaternion, perception
   * space). Keys are HandJointType names, e.g. "PALM", "INDEX_TIP".
   */
  joints: Record<string, number[]>;
  /** elapsedRealtimeNanos() at delivery (boottime ns) — display-grade stamp. */
  ts: number;
}

export type HandStateListener = (sample: HandSample) => void;

/** Subscribe to per-hand joint samples (left and right emit separately). */
export function addHandStateListener(listener: HandStateListener): Subscription {
  if (!nativeModule) return NOOP_SUBSCRIPTION;
  return nativeModule.addListener("onHandState", (event) =>
    listener(event as HandSample),
  );
}

/** Start hand tracking on the shared session. `bridgeRateHz` throttles the DISPLAY emit per
 * hand (omit ⇒ full rate, same as head); a storage sink records the full rate. Rejects if
 * HAND_TRACKING isn't granted or the session can't be created. */
export function startHands(bridgeRateHz?: number): Promise<void> {
  return nativeModule?.startHands(bridgeRateHz ?? null) ?? Promise.resolve();
}

/** Stop hand tracking (leaves the session alive for other streams). */
export function stopHands(): Promise<void> {
  return nativeModule?.stopHands() ?? Promise.resolve();
}
