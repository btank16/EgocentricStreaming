// Camera2 capture-envelope probe — typed API over native probeCaptureSizes(). Answers the
// recording-layer question "what resolution / fps can we actually sustain?" (see README →
// Recording). The hardware JPEG path is stall-bound: Camera2 advertises minFrameDuration +
// stallDuration per output size, so `sustainedFps` (= 1e9 / their sum) is the honest
// predictor — which is why recording uses the camera-Surface → all-intra HEVC path instead.
// Reads only CameraCharacteristics (never opens a device), no permission. See CameraProbe.kt.
//
// SCOPE: per-camera ceilings only; concurrent stereo (shared ISP/bandwidth) needs a live
// open, flagged by `stereoCaveat`.
import { nativeModule } from "../native";

/** Camera2 capture format the recording pipeline can use. `PRIVATE(MediaCodec)` is
 * the frames-straight-to-hardware-video-encoder Surface path. */
export type XrCaptureFormat = "JPEG" | "YUV_420_888" | "PRIVATE(MediaCodec)";

export interface XrCaptureSizeInfo {
  /** "WxH". */
  size: string;
  width: number;
  height: number;
  /** width·height / 1e6. Full precision — round at display (.toFixed). */
  megapixels: number;
  /** SCALER min frame duration at this size (ns) — the sensor's peak interval. */
  minFrameDurationNs: number;
  /** Extra inter-frame delay this output forces (ns). The JPEG-encode stall; ~0 for YUV. */
  stallDurationNs: number;
  /** Advertised peak fps (1e9 / minFrameDuration), ignoring the encode stall. Full
   * precision — round at display (.toFixed). */
  maxFps: number | null;
  /** Realistic sustained fps with the stall included — the figure the decision uses.
   * Full precision — round at display (.toFixed). */
  sustainedFps: number | null;
}

export interface XrCaptureFormatSupport {
  format: XrCaptureFormat | string;
  /** False when the camera offers no output stream of this format. */
  supported: boolean;
  /** Largest size whose `sustainedFps` ≥ 30 ("WxH"), or null if none clears 30. */
  maxSizeAt30Fps?: string | null;
  /** Largest size whose `sustainedFps` ≥ 60 ("WxH"), or null if none clears 60. */
  maxSizeAt60Fps?: string | null;
  /** Full size ladder, largest first. */
  sizes: XrCaptureSizeInfo[];
}

export interface XrCaptureCameraInfo {
  /** Camera2 id; "0" world-facing primary, "2" the stereo-pair candidate. */
  id: string;
  lensFacing: "front" | "back" | "external" | "unknown";
  worldFacing: boolean;
  /** INFO_SUPPORTED_HARDWARE_LEVEL: "LEGACY" | "LIMITED" | "FULL" | "LEVEL_3" | "EXTERNAL". */
  hardwareLevel: string;
  /** Largest output size across formats ("WxH") — the native sensor capture size. */
  nativeMaxSize: string | null;
  /** CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES as "min-max" labels (e.g. "24-24",
   * "15-30") — the sensor's advertised fps modes, the truth about whether 30 fps
   * exists at all (the per-format ImageReader caps can read lower). */
  aeFpsRanges: string[];
  /** Per-format capture envelope: JPEG (hardware-JPEG ISP), YUV_420_888 (raw frames,
   * own-encode), PRIVATE(MediaCodec) (frames to a hardware video-encoder Surface). */
  formats: XrCaptureFormatSupport[];
}

export interface XrCaptureProbeResult {
  /** Why the advertised ceilings aren't the final word on concurrent stereo. */
  stereoCaveat: string;
  /** World-facing cameras only (the egocentric capture candidates). */
  cameras: XrCaptureCameraInfo[];
}

const EMPTY_PROBE: XrCaptureProbeResult = {
  stereoCaveat: "",
  cameras: [],
};

/**
 * Dump each world-facing camera's capture envelope (output sizes × advertised /
 * sustained fps). Resolves to an empty result off-device / before prebuild. The native
 * side also logs the same data to logcat under tag "XrCaptureProbe".
 */
export async function probeCameraCapture(): Promise<XrCaptureProbeResult> {
  if (!nativeModule) return EMPTY_PROBE;
  const raw = (await nativeModule.probeCaptureSizes()) as Partial<XrCaptureProbeResult> | null;
  if (!raw) return EMPTY_PROBE;
  return {
    stereoCaveat: raw.stereoCaveat ?? "",
    cameras: raw.cameras ?? [],
  };
}

/** The JPEG format envelope for a camera (the former JPEG-master candidate), or null. */
export function jpegFormat(cam: XrCaptureCameraInfo): XrCaptureFormatSupport | null {
  return cam.formats.find((f) => f.format === "JPEG" && f.supported) ?? null;
}

/**
 * The recommended capture size for a camera: the largest JPEG size that sustains 30
 * fps. Falls back to the YUV path's 30 fps ceiling if the camera offers no JPEG
 * stream (capture YUV, software-encode), or null if nothing clears 30 fps. A
 * diagnostics/UI helper only — the recorder resolves its size natively via
 * CameraProbe.resolveRecordSize.
 */
export function recommendedCaptureSize(cam: XrCaptureCameraInfo): string | null {
  const jpeg = jpegFormat(cam);
  if (jpeg?.maxSizeAt30Fps) return jpeg.maxSizeAt30Fps;
  const yuv = cam.formats.find((f) => f.format === "YUV_420_888" && f.supported);
  return yuv?.maxSizeAt30Fps ?? null;
}

/** A one-line human verdict per camera for logging / UI. */
export function summarizeCaptureEnvelope(cam: XrCaptureCameraInfo): string {
  const jpeg = jpegFormat(cam);
  if (!jpeg) return `cam ${cam.id}: no JPEG stream — YUV+software-encode path`;
  const at30 = jpeg.maxSizeAt30Fps ?? "none";
  const at60 = jpeg.maxSizeAt60Fps ?? "none";
  return `cam ${cam.id} (${cam.lensFacing}, ${cam.hardwareLevel}): JPEG 30fps≤${at30}, 60fps≤${at60}`;
}
