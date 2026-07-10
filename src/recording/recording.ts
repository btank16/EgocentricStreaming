// Recording control — typed API over the native TakeRecorder (see README.md →
// Recording). Attaches a storage sink to each requested stream into one take directory
// (alongside any live display), and writes manifest.json on stop. See TakeRecorder.kt.
import { nativeModule } from "../native";

/** Streams a take can record. "cam0"/"cam2" are the world-facing cameras (all-intra
 * HEVC); a camera records only while its preview view is mounted. */
export type RecordingStream =
  | "head"
  | "hands"
  | "camcorder"
  | "voice_recognition"
  | "cam0"
  | "cam2";

/** Camera quality preset. FPS is pinned at the sensor-derived max (≤ the profile cap,
 * 30) for ALL — only resolution/bitrate vary. (Galaxy XR sensor max: 24.)
 * `research-max` = native 3000², near-lossless; `balanced` = native 3000², high
 * quality (default); `efficient` = downscaled square, long takes. */
export type RecordingPreset = "research-max" | "balanced" | "efficient";

export interface RecordingStartResult {
  /** "recording" on success; "already_recording" / "no_storage" / "unavailable" otherwise. */
  status: string;
  /** Absolute path of the take directory (on success). */
  takeDir?: string;
  /** The streams actually attached. */
  streams?: string[];
}

export interface RecordingStopResult {
  /** "stopped" on success; "not_recording" / "unavailable" otherwise. */
  status: string;
  takeDir?: string;
  /** Absolute path of the written manifest.json (on success). */
  manifest?: string;
}

/** Start a take recording the given streams at the given quality preset (default
 * "balanced"). An empty `streams` array records everything currently live. `qualityScale`
 * (0..1) scales the camera bitrate down from the clamped encoder max (omit ⇒ 1.0). Rejects
 * if a required permission isn't granted (mirrors the per-stream start). Returns
 * `{ status: "unavailable" }` off-device. */
export async function startRecording(
  streams: RecordingStream[],
  preset: RecordingPreset = "balanced",
  qualityScale?: number,
): Promise<RecordingStartResult> {
  if (!nativeModule) return { status: "unavailable" };
  return (await nativeModule.startRecording(streams, preset, qualityScale ?? null)) as RecordingStartResult;
}

/** Stop the current take: detach sinks, finalize files, write the manifest. */
export async function stopRecording(): Promise<RecordingStopResult> {
  if (!nativeModule) return { status: "unavailable" };
  return (await nativeModule.stopRecording()) as RecordingStopResult;
}

/** Whether a take is currently recording. */
export async function isRecording(): Promise<boolean> {
  if (!nativeModule) return false;
  return nativeModule.isRecording();
}
