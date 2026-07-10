// Head-tracking endpoint probe — typed API over the native probeHeadTracking().
// Side-effect-free on the native side (no Session, no permission prompt). Reports the
// static facts: runtime + permission + the fixed perception config + the timestamp-mode
// seam. See HeadTrackingProbe.kt.
import { nativeModule } from "../native";

/** Alignment fidelity the pose path can deliver. `delivery-stamped` on Jetpack (no capture
 * timestamp); `capture-stamped` once an OpenXR PoseSource lands (xrLocateSpace at the
 * camera frame time). */
export type XrTimestampMode = "delivery-stamped" | "capture-stamped";

export interface XrHeadTrackingProbe {
  xrRuntimeAvailable: boolean;
  permissionGranted: boolean;
  /** The perception source backing the stream, e.g. "jetpack-ardevice". */
  source: string;
  /** Device tracking mode applied (e.g. "SPATIAL"). */
  trackingMode: string;
  /** "fused-6dof" — a SLAM/VIO pose, not raw IMU. */
  poseKind: string;
  clock: string;
  timestampMode: XrTimestampMode;
  /** Whether per-sample capture timestamps are exposed (false until OpenXR). */
  captureTimestampExposed: boolean;
}

const EMPTY: XrHeadTrackingProbe = {
  xrRuntimeAvailable: false,
  permissionGranted: false,
  source: "jetpack-ardevice",
  trackingMode: "SPATIAL",
  poseKind: "fused-6dof",
  clock: "boottime",
  timestampMode: "delivery-stamped",
  captureTimestampExposed: false,
};

/** Probe the head-tracking endpoint. Resolves to a safe default off-device / before prebuild. */
export async function probeHeadTracking(): Promise<XrHeadTrackingProbe> {
  if (!nativeModule) return EMPTY;
  const raw = (await nativeModule.probeHeadTracking()) as Partial<XrHeadTrackingProbe> | null;
  if (!raw) return EMPTY;
  return { ...EMPTY, ...raw };
}
