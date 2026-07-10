// Device & sensor capability discovery — typed API over the native
// getDeviceCapabilities(). Side-effect-free on the native side (no Session, no
// permission requests). See Capabilities.kt (aggregation) + CameraProbe.kt /
// AudioProbe.kt (the camera/audio inventory sections).
import { nativeModule } from "../native";

/** Clock domain a Camera2 device stamps frames in (SENSOR_INFO_TIMESTAMP_SOURCE). */
export type XrTimestampSource = "REALTIME" | "UNKNOWN";

export type XrLensFacing = "front" | "back" | "external" | "unknown";

export interface XrCameraInfo {
  /** Camera2 id; "0" is the world-facing (forward) camera on the headset. */
  id: string;
  lensFacing: XrLensFacing;
  /**
   * `REALTIME` → SENSOR_TIMESTAMP shares the `elapsedRealtimeNanos()` (BOOTTIME)
   * base and is comparable across subsystems; `UNKNOWN` → monotonic, not
   * guaranteed comparable to any other time source. Determines the future
   * camera↔pose alignment strategy.
   */
  timestampSource: XrTimestampSource;
  /** Present (true) on the world-facing camera the capture pipeline will preview. */
  worldFacing?: boolean;
  /** A few sample YUV output sizes ("WxH") for the world-facing camera. */
  sampleOutputSizes?: string[];
  /** True if a logical camera wrapping multiple physical sensors (stereo path). */
  isLogicalMultiCamera?: boolean;
  /** Physical sub-camera ids; non-empty only for logical multi-cameras. */
  physicalCameraIds?: string[];
}

export interface XrPermissionStatus {
  headTracking: boolean;
  handTracking: boolean;
  /** Gates plane tracking (FLOOR plane → session-origin floor reference), hit-testing, anchor persistence. */
  sceneUnderstandingCoarse: boolean;
  /** Gates the Depth API only — does NOT imply `sceneUnderstandingCoarse`. */
  sceneUnderstandingFine: boolean;
  camera: boolean;
  recordAudio: boolean;
}

export interface XrMicrophoneInfo {
  /** Stable MicrophoneInfo id for this input. */
  id: number;
  /** AudioDeviceInfo type, e.g. "builtin_mic", "bluetooth_sco", "usb_device". */
  type: string;
  /** Physical placement: "mainbody" | "mainbody_movable" | "peripheral" | "unknown". */
  location: string;
  /** Polar pattern: "omni" | "cardioid" | "bi_directional" | … | "unknown". */
  directionality: string;
  address: string;
  description: string;
}

export interface XrAudioCapability {
  /** Preferred output sample rate (Hz) as reported by AudioManager, or null. */
  outputSampleRate: string | null;
  /** Preferred output buffer size (frames), or null. */
  framesPerBuffer: string | null;
  /** Whether the rawest UNPROCESSED mic source is available on this device. */
  unprocessedSupported: boolean;
  /** Enumerated microphone array (empty below API 28). */
  microphones: XrMicrophoneInfo[];
}

export interface XrFeatureSupport {
  /** `android.software.xr.api.spatial` — Jetpack XR perception/spatial runtime. */
  spatialApi: boolean;
  /** `android.software.xr.api.openxr` — OpenXR runtime. */
  openxrApi: boolean;
}

export interface XrDeviceCapabilities {
  /** True when the device exposes an XR runtime (spatial or OpenXR feature). */
  xrRuntimeAvailable: boolean;
  xrFeatures: XrFeatureSupport;
  permissions: XrPermissionStatus;
  cameras: XrCameraInfo[];
  /** Camera-id sets the device can open + configure concurrently (API 29+). */
  concurrentCameraCombos: string[][];
  /** Whether the world-facing stereo pair (cameras 0 + 2) can run concurrently. */
  worldStereoConcurrent: boolean;
  audio: XrAudioCapability;
  /**
   * Jetpack XR perception exposes no per-sample capture timestamp, so head/hand
   * poses are delivery-stamped (display-grade alignment). Always false today;
   * surfaced so the UI can reflect the alignment fidelity.
   */
  perceptionTimestampExposed: boolean;
}

// The native result is treated as potentially-partial: normalizing over the defaults
// keeps a missing field from crashing the UI (it shows the default instead).
type RawCapabilities = {
  xrRuntimeAvailable?: boolean;
  xrFeatures?: Partial<XrFeatureSupport>;
  permissions?: Partial<XrPermissionStatus>;
  cameras?: XrCameraInfo[];
  concurrentCameraCombos?: string[][];
  worldStereoConcurrent?: boolean;
  audio?: Partial<XrAudioCapability>;
  perceptionTimestampExposed?: boolean;
};

const EMPTY_CAPABILITIES: XrDeviceCapabilities = {
  xrRuntimeAvailable: false,
  xrFeatures: { spatialApi: false, openxrApi: false },
  permissions: {
    headTracking: false,
    handTracking: false,
    sceneUnderstandingCoarse: false,
    sceneUnderstandingFine: false,
    camera: false,
    recordAudio: false,
  },
  cameras: [],
  concurrentCameraCombos: [],
  worldStereoConcurrent: false,
  audio: {
    outputSampleRate: null,
    framesPerBuffer: null,
    unprocessedSupported: false,
    microphones: [],
  },
  perceptionTimestampExposed: false,
};

/** Merge a (possibly-partial) native result over the defaults so missing fields
 * never crash the UI — see RawCapabilities. */
function normalizeCapabilities(raw: RawCapabilities | null | undefined): XrDeviceCapabilities {
  if (!raw) return EMPTY_CAPABILITIES;
  return {
    xrRuntimeAvailable: raw.xrRuntimeAvailable ?? EMPTY_CAPABILITIES.xrRuntimeAvailable,
    xrFeatures: { ...EMPTY_CAPABILITIES.xrFeatures, ...raw.xrFeatures },
    permissions: { ...EMPTY_CAPABILITIES.permissions, ...raw.permissions },
    cameras: raw.cameras ?? EMPTY_CAPABILITIES.cameras,
    concurrentCameraCombos:
      raw.concurrentCameraCombos ?? EMPTY_CAPABILITIES.concurrentCameraCombos,
    worldStereoConcurrent:
      raw.worldStereoConcurrent ?? EMPTY_CAPABILITIES.worldStereoConcurrent,
    audio: { ...EMPTY_CAPABILITIES.audio, ...raw.audio },
    perceptionTimestampExposed:
      raw.perceptionTimestampExposed ?? EMPTY_CAPABILITIES.perceptionTimestampExposed,
  };
}

/** Whether an Android XR runtime is present (false off-device / before prebuild). */
export function isXrAvailable(): boolean {
  return nativeModule?.isAvailable() ?? false;
}

/**
 * Side-effect-free discovery of the device's XR runtime, granted permissions,
 * camera inventory (incl. each camera's timestamp clock + concurrency), and audio
 * inputs. Resolves to a safe all-false/empty report when the native module is
 * unavailable, and normalizes partial native results over the defaults.
 */
export async function getDeviceCapabilities(): Promise<XrDeviceCapabilities> {
  if (!nativeModule) return EMPTY_CAPABILITIES;
  return normalizeCapabilities((await nativeModule.getDeviceCapabilities()) as RawCapabilities);
}
