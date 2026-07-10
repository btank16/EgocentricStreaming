// Camera2 calibration probe — typed API over native probeCameras(). The on-device test
// for whether the headset exposes FACTORY camera calibration for egocentric capture.
// Camera2's LENS_* keys are the only platform path (OpenXR/ARCore-for-XR expose no
// intrinsics), and are guaranteed populated only when a camera advertises DEPTH_OUTPUT or
// LOGICAL_MULTI_CAMERA. Side-effect-free (reads CameraCharacteristics, never opens a
// device). See CameraProbe.kt.
import { nativeModule } from "../native";

/** LENS_POSE_REFERENCE — the frame an extrinsic is measured against. None is the
 * head: on a headset `GYROSCOPE` (camera→IMU) is the closest, `UNDEFINED` means the
 * device reports zeros, `PRIMARY_CAMERA` is relative to the largest same-facing
 * camera's optical center. */
export type XrLensPoseReference =
  | "PRIMARY_CAMERA"
  | "GYROSCOPE"
  | "UNDEFINED"
  | "AUTOMOTIVE"
  | string;

export interface XrCameraCalibration {
  /** Camera2 id; "0" is world-facing on the headset, "2" the stereo-pair candidate. */
  id: string;
  lensFacing: "front" | "back" | "external" | "unknown";
  /** REQUEST_AVAILABLE_CAPABILITIES, e.g. "DEPTH_OUTPUT", "LOGICAL_MULTI_CAMERA". */
  capabilities: string[];
  /** Camera advertises DEPTH_OUTPUT. */
  depthOutput: boolean;
  /** Camera advertises LOGICAL_MULTI_CAMERA. */
  logicalMultiCamera: boolean;
  /**
   * The decisive gate: when true (depthOutput || logicalMultiCamera) the LENS_*
   * keys below are GUARANTEED populated — factory calibration. When false they're
   * best-effort and may be null.
   */
  calibrationGuaranteed: boolean;

  /** [f_x, f_y, c_x, c_y, s] in pre-correction active-array pixels, or null. */
  intrinsics: number[] | null;
  /** Brown-Conrady [k1, k2, k3, k4, k5] (3 radial + 2 tangential), or null. */
  distortion: number[] | null;
  /** Deprecated 6-element predecessor; only meaningful when `distortion` is null. */
  radialDistortionDeprecated: number[] | null;

  /** Optical-center position relative to `poseReference`, meters [x,y,z], or null.
   * NOT relative to the head; needs negating for camera→origin in most CV libs. */
  poseTranslation: number[] | null;
  /** Quaternion [x,y,z,w]: sensor-axes → camera-axes rotation, or null. */
  poseRotation: number[] | null;
  /** The frame the extrinsic is measured against (none is the head). */
  poseReference: XrLensPoseReference | null;

  /** "WxH" pixel frame the intrinsics are expressed in (pre-correction array). */
  preCorrectionActiveArraySize: string | null;
  /** "WxH" corrected active array. */
  activeArraySize: string | null;
  /** Physical sensor size "WxH" in millimeters. */
  physicalSizeMm: string | null;
  /** A few sample YUV output sizes ("WxH"). */
  outputSizes: string[];

  /** intrinsics != null. */
  hasIntrinsics: boolean;
  /** distortion or radialDistortionDeprecated present. */
  hasDistortion: boolean;
  /** poseTranslation && poseRotation both present. */
  hasExtrinsics: boolean;
  /** Whether the calibration keys were readable — false when CAMERA isn't granted
   * (the keys need permission on Android 10+), so a null then means "unknown", not
   * "absent". Equals the top-level `cameraPermissionGranted`. */
  calibrationKeysReadable: boolean;
}

export interface XrCameraProbeResult {
  /** The LENS_* calibration keys need CAMERA on Android 10+; without it they read
   * back null. False here means every null below is permission-induced, not a real
   * "device lacks calibration" — grant CAMERA and probe again. */
  cameraPermissionGranted: boolean;
  cameras: XrCameraCalibration[];
}

const EMPTY_PROBE: XrCameraProbeResult = {
  cameraPermissionGranted: false,
  cameras: [],
};

/**
 * Dump every camera's Camera2 LENS_* calibration metadata. Resolves to an empty result
 * off-device / before prebuild. The native side also logs the same data to logcat under
 * tag "XrCameraProbe".
 */
export async function probeCameraCalibration(): Promise<XrCameraProbeResult> {
  if (!nativeModule) return EMPTY_PROBE;
  const raw = (await nativeModule.probeCameras()) as Partial<XrCameraProbeResult> | null;
  if (!raw) return EMPTY_PROBE;
  return {
    cameraPermissionGranted: raw.cameraPermissionGranted ?? false,
    cameras: raw.cameras ?? [],
  };
}

/**
 * Whether the intrinsics are actually USABLE, not just present. Some HALs (observed
 * on the Galaxy XR's depth camera) satisfy the DEPTH_OUTPUT key contract by stubbing
 * the calibration to all-zeros — an `fx=fy=0` matrix is degenerate. So "present"
 * (`hasIntrinsics`) is not the same as "real": require positive focal lengths.
 */
export function intrinsicsUsable(cam: XrCameraCalibration): boolean {
  const k = cam.intrinsics;
  return !!k && k.length >= 2 && k[0] > 0 && k[1] > 0;
}

/** Present (key populated) but degenerate (zero focal length) — a HAL placeholder. */
export function intrinsicsStubbed(cam: XrCameraCalibration): boolean {
  return cam.hasIntrinsics && !intrinsicsUsable(cam);
}

/** True only when a camera exposes a *usable* factory intrinsics matrix. */
export function hasFactoryIntrinsics(cam: XrCameraCalibration): boolean {
  return intrinsicsUsable(cam);
}

/** A one-line human verdict per camera for logging / UI. */
export function summarizeCalibration(cam: XrCameraCalibration): string {
  if (!cam.calibrationKeysReadable) {
    return `cam ${cam.id}: unknown — grant CAMERA and re-probe`;
  }
  const usable = intrinsicsUsable(cam);
  const stubbed = intrinsicsStubbed(cam);
  const parts: string[] = [];
  // "present but zeros" is worse than honest-absent — call it out explicitly so a
  // DEPTH_OUTPUT ✓ on a stubbed camera isn't mistaken for real calibration.
  parts.push(usable ? "intrinsics ✓" : stubbed ? "intrinsics ⚠ stub(0)" : "intrinsics ✗");
  parts.push(cam.hasDistortion ? "distortion ✓" : "distortion ✗");
  parts.push(
    cam.hasExtrinsics
      ? `extrinsics ✓ (ref ${cam.poseReference ?? "?"})`
      : "extrinsics ✗",
  );
  const gate = cam.calibrationGuaranteed ? "guaranteed" : "best-effort";
  return `cam ${cam.id} (${cam.lensFacing}, ${gate}): ${parts.join(", ")}`;
}
