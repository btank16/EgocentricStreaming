// Hardware video-encoder (HEVC/AVC) capability probe — typed API over native
// probeVideoEncoders(). Counterpart to the capture probe for the recording-format decision
// (see README → Recording): JPEG is stall-bound, but the video encoder is separate realtime
// silicon, so all-intra HEVC/AVC may sustain far more. Reports each encoder's advertised
// envelope (max size, fps, concurrent instances — stereo wants ≥2). Queries MediaCodec
// capabilities only (never configures a codec, no camera, no permission). See EncoderProbe.kt.
import { nativeModule } from "../native";

export interface XrEncoderCandidate {
  /** "WxH". */
  size: string;
  /** Whether the encoder accepts this frame size at all. */
  sizeSupported: boolean;
  /** width·height / 1e6 (present only when sizeSupported). Full precision — round at
   * display (.toFixed). */
  megapixels?: number;
  /** Theoretical max fps the codec reports for this size, or null. Full precision. */
  supportedFpsMax?: number | null;
  /** Measured "achievable" max fps (device perf data); null when no data shipped —
   * the more honest figure than supportedFpsMax when present. Full precision. */
  achievableFpsMax?: number | null;
  /** areSizeAndRateSupported(w,h,24). */
  at24fps?: boolean;
  /** areSizeAndRateSupported(w,h,30). */
  at30fps?: boolean;
}

export interface XrVideoEncoder {
  /** Codec name, e.g. "c2.qti.hevc.encoder" / "OMX.qcom.video.encoder.hevc". */
  name: string;
  /** "HEVC" | "H264". */
  mime: string;
  /** True for a dedicated hardware block (the reason to prefer it over the JPEG ISP). */
  hardwareAccelerated: boolean;
  /** Concurrent instances of this codec (stereo wants ≥2 to encode cam0+cam2). */
  maxInstances: number;
  /** Supported width range "min-max". */
  supportedWidths: string;
  /** Supported height range "min-max". */
  supportedHeights: string;
  /** Per candidate capture size, the advertised fps envelope. */
  candidates: XrEncoderCandidate[];
}

export interface XrVideoEncoderProbeResult {
  encoders: XrVideoEncoder[];
}

const EMPTY_PROBE: XrVideoEncoderProbeResult = { encoders: [] };

/**
 * Dump the device's hardware HEVC/AVC video encoders and their advertised envelope at
 * the candidate capture sizes. Resolves to an empty result off-device / before prebuild.
 * The native side also logs the same data to logcat under tag "XrEncoderProbe".
 */
export async function probeVideoEncoders(): Promise<XrVideoEncoderProbeResult> {
  if (!nativeModule) return EMPTY_PROBE;
  const raw = (await nativeModule.probeVideoEncoders()) as Partial<XrVideoEncoderProbeResult> | null;
  if (!raw) return EMPTY_PROBE;
  return { encoders: raw.encoders ?? [] };
}

/** The preferred hardware HEVC encoder, if one exists (the candidate master path). */
export function hardwareHevcEncoder(result: XrVideoEncoderProbeResult): XrVideoEncoder | null {
  return result.encoders.find((e) => e.mime === "HEVC" && e.hardwareAccelerated) ?? null;
}

/** A one-line human verdict per encoder for logging / UI. */
export function summarizeEncoder(enc: XrVideoEncoder): string {
  const hw = enc.hardwareAccelerated ? "hw" : "sw";
  const full = enc.candidates.find((c) => c.size === "3000x3000");
  const fps9mp = full?.achievableFpsMax ?? full?.supportedFpsMax;
  const at9mp = full?.sizeSupported
    ? `9MP ${fps9mp?.toFixed(1) ?? "?"}fps`
    : "9MP unsupported";
  return `${enc.mime} (${hw}, ${enc.maxInstances}×): ${at9mp}`;
}
