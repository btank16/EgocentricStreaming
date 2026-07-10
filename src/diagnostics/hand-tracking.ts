// Hand-tracking endpoint probe — typed API over the native probeHandTracking().
// Side-effect-free on the native side (no Session, no permission prompt). See
// HandTrackingProbe.kt.
import { nativeModule } from "../native";
import type { XrTimestampMode } from "./head-tracking";

export interface XrHandTrackingProbe {
  xrRuntimeAvailable: boolean;
  permissionGranted: boolean;
  /** The perception source backing the stream, e.g. "jetpack-hand". */
  source: string;
  /** Hands are tracked together (e.g. "BOTH"); a single hand can't be tracked alone. */
  trackingMode: string;
  /** Joints reported per hand (26 on Jetpack XR). */
  jointsPerHand: number;
  clock: string;
  timestampMode: XrTimestampMode;
  /** Whether per-sample capture timestamps are exposed (false until OpenXR). */
  captureTimestampExposed: boolean;
}

const EMPTY: XrHandTrackingProbe = {
  xrRuntimeAvailable: false,
  permissionGranted: false,
  source: "jetpack-hand",
  trackingMode: "BOTH",
  jointsPerHand: 26,
  clock: "boottime",
  timestampMode: "delivery-stamped",
  captureTimestampExposed: false,
};

/** Probe the hand-tracking endpoint. Resolves to a safe default off-device / before prebuild. */
export async function probeHandTracking(): Promise<XrHandTrackingProbe> {
  if (!nativeModule) return EMPTY;
  const raw = (await nativeModule.probeHandTracking()) as Partial<XrHandTrackingProbe> | null;
  if (!raw) return EMPTY;
  return { ...EMPTY, ...raw };
}
