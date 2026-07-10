// The single access point — one import, grouped namespaces. Composes the per-concern
// wrappers into `xr.streams` / `xr.recording` / `xr.sessionOrigin` / `xr.diagnostics` /
// `xr.catalog`. Native stays the single source of truth; this object caches no stream state.
//
// The camera is intentionally asymmetric: it's a mounted View that owns the Camera2 device,
// so `xr.streams.camera.Preview` IS its start — there's no `camera.start()`. Recording +
// tunables are governed via `xr.recording`.
import { getDeviceCapabilities, isXrAvailable } from "./diagnostics/capabilities";
import {
  hasFactoryIntrinsics,
  intrinsicsStubbed,
  intrinsicsUsable,
  probeCameraCalibration,
  summarizeCalibration,
} from "./diagnostics/camera-calibration";
import {
  jpegFormat,
  probeCameraCapture,
  recommendedCaptureSize,
  summarizeCaptureEnvelope,
} from "./diagnostics/camera-capture-probe";
import {
  hardwareHevcEncoder,
  probeVideoEncoders,
  summarizeEncoder,
} from "./diagnostics/video-encoder-probe";
import { probeHeadTracking } from "./diagnostics/head-tracking";
import { probeHandTracking } from "./diagnostics/hand-tracking";
import { exportProfileDraft, profileDraft } from "./diagnostics/profile-draft";
import { addHeadPoseListener, startHeadPose, stopHeadPose } from "./streams/headpose";
import { addHandStateListener, startHands, stopHands } from "./streams/hands";
import {
  addAudioLevelListener,
  startAudioSource,
  stopAudioSource,
  type AudioSourceLabel,
} from "./streams/audio";
import {
  addSessionOriginListener,
  getSessionOrigin,
  XR_COORDINATE_SYSTEM,
} from "./streams/session-origin";
import { XrCameraPreview } from "./streams/camera";
import {
  isRecording,
  startRecording,
  stopRecording,
  type RecordingPreset,
  type RecordingStream,
} from "./recording/recording";
import { inspectTake, listTakes, zipTake } from "./catalog/catalog";

/** Options for a headless perception stream. `bridgeRateHz` throttles the DISPLAY emit only
 * (the throttle runs native, the rate is set from here); storage always records full rate. */
export interface StreamStartOptions {
  bridgeRateHz?: number;
}

/** Options for a recording. Omit `streams` to record everything currently live. */
export interface RecordingStartOptions {
  streams?: RecordingStream[];
  preset?: RecordingPreset;
  /** 0..1 — scales the camera bitrate down from the clamped encoder max (default 1.0). */
  qualityScale?: number;
}

export const xr = {
  /** Whether an Android XR runtime is present (false off-device / before prebuild). */
  isAvailable: isXrAvailable,

  streams: {
    head: {
      start: (opts?: StreamStartOptions) => startHeadPose(opts?.bridgeRateHz),
      stop: stopHeadPose,
      subscribe: addHeadPoseListener,
    },
    hands: {
      start: (opts?: StreamStartOptions) => startHands(opts?.bridgeRateHz),
      stop: stopHands,
      subscribe: addHandStateListener,
    },
    audio: {
      start: (source: AudioSourceLabel) => startAudioSource(source),
      stop: (source: AudioSourceLabel) => stopAudioSource(source),
      subscribe: addAudioLevelListener,
    },
    camera: {
      /** The Camera2 preview component (null off-device / before prebuild). */
      Preview: XrCameraPreview,
    },
  },

  recording: {
    start: (opts?: RecordingStartOptions) =>
      startRecording(opts?.streams ?? [], opts?.preset, opts?.qualityScale),
    stop: stopRecording,
    isActive: isRecording,
  },

  sessionOrigin: {
    get: getSessionOrigin,
    subscribe: addSessionOriginListener,
    /** The static, runtime-invariant coordinate frame convention. */
    coordinateSystem: XR_COORDINATE_SYSTEM,
  },

  diagnostics: {
    // Async probe entry points.
    capabilities: getDeviceCapabilities,
    cameraCalibration: probeCameraCalibration,
    captureEnvelope: probeCameraCapture,
    encoder: probeVideoEncoders,
    head: probeHeadTracking,
    hands: probeHandTracking,
    // Pure helpers over the probe results (decode/summarize; no native call).
    summarizeCalibration,
    intrinsicsUsable,
    intrinsicsStubbed,
    hasFactoryIntrinsics,
    jpegFormat,
    recommendedCaptureSize,
    summarizeCaptureEnvelope,
    hardwareHevcEncoder,
    summarizeEncoder,
  },

  catalog: {
    list: listTakes,
    inspect: inspectTake,
    export: zipTake,
  },

  // Device-profile onboarding (dev tooling): `draft()` probes this hardware; `exportDraft()`
  // writes it to disk for `adb pull` → scripts/add-device-profile.mjs → Constants.kt PROFILES.
  profiles: {
    draft: profileDraft,
    exportDraft: exportProfileDraft,
  },
} as const;
