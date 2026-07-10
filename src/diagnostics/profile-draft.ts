// Device-profile draft — typed API over native profileDraft() / exportProfileDraft().
// Probes THIS headset's audio/camera/encoder limits into a DeviceProfile-shaped dump, the
// input to `scripts/add-device-profile.mjs` which emits a Kotlin stanza for Constants.kt →
// PROFILES. Side-effect-free on the native side (no mic/camera opened). See ProfileDraft.kt.
import { nativeModule } from "../native";

/** One capture format the device advertises as supported for input. */
export interface AudioFormatCandidate {
  sampleRate: number;
  /** Kotlin AudioFormat symbol, e.g. "CHANNEL_IN_MONO". */
  channel: string;
  /** Kotlin AudioFormat symbol, e.g. "ENCODING_PCM_16BIT". */
  encoding: string;
  minBufferBytes: number;
}

export interface ProfileDraftCamera {
  id: string;
  lensFacing: string;
  worldFacing: boolean;
  /** "WxH" native (max) record size, or null if the query failed. */
  nativeRecordSize: string | null;
  advertisedMaxFps: number;
  advertisedMaxFpsProvenance: string;
  timestampSource: string;
  fidelity: string;
}

/** The generator's direct input — maps 1:1 onto Kotlin DeviceProfile fields. Probed values
 * (audio format, maxCaptureFps) are from this device; the subjective knobs (preset bitrates,
 * efficient size, previewFallbackSize) are copied from the Galaxy XR baseline — see `notes`. */
export interface SuggestedProfile {
  sampleRate: number;
  audioChannel: string;
  audioEncoding: string;
  framesPerRead: number;
  maxCaptureFps: number;
  previewFallbackSize: string;
  cameraPresets: Record<string, { size: string | null; bitRate: number }>;
  defaultPreset: string;
  notes: string[];
}

export interface ProfileDraft {
  /** The PROFILES map key: "manufacturer/model" lowercased (Build.MANUFACTURER/MODEL). */
  deviceKey: string;
  identity: {
    manufacturer: string;
    model: string;
    device: string;
    brand: string;
    product: string;
    androidRelease: string;
    sdkInt: number;
  };
  probes: {
    audio: { supportedFormats: AudioFormatCandidate[]; suggested: AudioFormatCandidate | null };
    cameras: ProfileDraftCamera[];
    encoder: Record<string, unknown>;
  };
  suggestedProfile: SuggestedProfile;
  /** True when this device currently falls back to DEFAULT_PROFILE (i.e. not yet registered). */
  activeProfileIsDefault: boolean;
}

/** Result of writing the draft to disk for `adb pull`. */
export interface ProfileDraftExport {
  /** "ok" on success; "no_storage" / "error" / "unavailable" otherwise. */
  status: string;
  /** Absolute path of the written profile-draft.json (on success). */
  path?: string;
  deviceKey?: string;
  message?: string;
}

/** Probe this device into a profile draft (for display). Null off-device / before prebuild. */
export async function profileDraft(): Promise<ProfileDraft | null> {
  if (!nativeModule) return null;
  return (await nativeModule.profileDraft()) as ProfileDraft | null;
}

/** Write the draft to the app-private dir and return its path — pull it with `adb pull`,
 * then run `node scripts/add-device-profile.mjs <path>`. `{ status: "unavailable" }`
 * off-device. */
export async function exportProfileDraft(): Promise<ProfileDraftExport> {
  if (!nativeModule) return { status: "unavailable" };
  return (await nativeModule.exportProfileDraft()) as ProfileDraftExport;
}
