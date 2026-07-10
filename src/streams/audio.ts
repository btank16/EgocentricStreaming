// Audio capture — two concurrent sources (CAMCORDER + VOICE_RECOGNITION). PCM
// stays native; only RMS/peak levels + a boottime timestamp cross the bridge.
// See AudioController.kt.
import { nativeModule, NOOP_SUBSCRIPTION, type Subscription } from "../native";

export type AudioSourceLabel = "camcorder" | "voice_recognition";

export interface AudioLevelSample {
  source: AudioSourceLabel;
  /** RMS amplitude over the window, 0..1 (linear). */
  rms: number;
  /** Peak amplitude over the window, 0..1. */
  peak: number;
  /**
   * `AudioTimestamp` on `TIMEBASE_BOOTTIME` when available (boottime ns —
   * comparable to the world camera's REALTIME clock), else a delivery stamp.
   */
  ts: number;
  sampleRate: number;
}

/**
 * Per-source start status: `"recording"` on success, otherwise a failure reason
 * (`"init_failed"`, `"start_failed"`, `"exception"`, `"buffer_error"`,
 * `"unknown_source"`). The empirical answer to whether a source can capture
 * (alongside any other already running).
 */
export type AudioStartStatus = string;

export type AudioLevelListener = (sample: AudioLevelSample) => void;

/** Subscribe to per-source audio level samples (each source emits separately). */
export function addAudioLevelListener(listener: AudioLevelListener): Subscription {
  if (!nativeModule) return NOOP_SUBSCRIPTION;
  return nativeModule.addListener("onAudioLevel", (event) =>
    listener(event as AudioLevelSample),
  );
}

/** Start one audio source. Resolves to its status string ("recording" on
 * success). Rejects if RECORD_AUDIO isn't granted. */
export async function startAudioSource(source: AudioSourceLabel): Promise<AudioStartStatus> {
  if (!nativeModule) return "unavailable";
  return (await nativeModule.startAudioSource(source)) as AudioStartStatus;
}

/** Stop one audio source. */
export function stopAudioSource(source: AudioSourceLabel): Promise<void> {
  return nativeModule?.stopAudioSource(source) ?? Promise.resolve();
}
