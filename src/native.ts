// Shared native-module handle. `requireOptionalNativeModule` (re-exported by `expo`)
// returns null off-device / before prebuild, so every wrapper guards with a single
// `if (!nativeModule)` and degrades to a safe default: void calls resolve, status calls
// resolve to "unavailable", lists/getters return empty/null, listeners return
// NOOP_SUBSCRIPTION. The two loosely-typed payloads (capabilities, session origin) stay
// `unknown` and are normalized by their wrapper — the one intentional trust boundary.
import { requireOptionalNativeModule } from "expo";

export interface Subscription {
  remove(): void;
}

export interface ExpoXrAndroidNativeModule {
  isAvailable(): boolean;
  // `unknown` → normalized over defaults by the wrapper (capabilities.ts / session-origin.ts).
  getDeviceCapabilities(): Promise<unknown>;
  // Read-only Camera2 / encoder diagnostics dumps; cast to their typed shape by the wrappers.
  probeCameras(): Promise<unknown>;
  probeCaptureSizes(): Promise<unknown>;
  probeVideoEncoders(): Promise<unknown>;
  probeHeadTracking(): Promise<unknown>;
  probeHandTracking(): Promise<unknown>;
  // Device-profile draft (cast by profile-draft.ts); `exportProfileDraft` writes it to
  // disk for `adb pull`.
  profileDraft(): Promise<unknown>;
  exportProfileDraft(): Promise<unknown>;
  // Recording control (see recording.ts). `streams` empty ⇒ record all currently-live
  // streams; qualityScale null ⇒ 1.0.
  startRecording(streams: string[], preset: string, qualityScale: number | null): Promise<unknown>;
  stopRecording(): Promise<unknown>;
  isRecording(): Promise<boolean>;
  // Catalog seams native alone provides (listing/manifest reads are TS, see catalog.ts):
  // `catalogRoot` = app-private base path; `inspectTake` = deep re-probe fallback for pre-v3
  // takes; `zipTake` = bundle a take dir.
  catalogRoot(): Promise<string | null>;
  inspectTake(name: string): Promise<unknown>;
  zipTake(name: string): Promise<unknown>;
  // bridgeRateHz throttles the DISPLAY emit only (null ⇒ per-stream default); storage records full rate.
  startHeadPose(bridgeRateHz: number | null): Promise<void>;
  stopHeadPose(): Promise<void>;
  startHands(bridgeRateHz: number | null): Promise<void>;
  stopHands(): Promise<void>;
  startAudioSource(source: string): Promise<unknown>;
  stopAudioSource(source: string): Promise<void>;
  getSessionOrigin(): Promise<unknown>;
  // EventEmitter; payload typed `unknown`, cast by callers.
  addListener(eventName: string, listener: (event: unknown) => void): Subscription;
}

export const nativeModule =
  requireOptionalNativeModule<ExpoXrAndroidNativeModule>("ExpoXrAndroid");

// Returned by add*Listener when native is unavailable, so subscribing is always safe.
export const NOOP_SUBSCRIPTION: Subscription = { remove() {} };
