// Take catalog — list / inspect / export. No database: the filesystem + each take's
// manifest.json IS the catalog. Listing and manifest reading live in TS (via
// expo-file-system), so the same code can serve future cloud takes (swap the source).
// Native keeps only the seams it alone provides: `catalogRoot()` (app-private base path),
// `inspectTake()` (deep re-probe fallback for pre-v3 takes with no baked `stats`), and
// `zipTake()` (bundle a take dir into a cache .zip). v3+ takes bake their full inspection
// into manifest.json at record-stop, so `inspect()` is then a pure manifest read.
import { Directory, File } from "expo-file-system";
import { nativeModule } from "../native";

/** Cheap per-take summary for the files list (no media probing). */
export interface TakeSummary {
  name: string;
  path: string;
  /** Epoch ms parsed from the dir name (take_<wallMs>). */
  createdAtWallMs: number;
  totalBytes: number;
  hasManifest: boolean;
  videoCount: number;
  audioCount: number;
  /** Count of full-rate data tracks (head.jsonl / hands.jsonl). */
  dataTrackCount: number;
}

export interface RecordingsList {
  /** Absolute base dir (getExternalFilesDir), or null off-device. */
  basePath: string | null;
  /** Takes, newest first. */
  takes: TakeSummary[];
}

// Numeric stats below (durationS, fps, bitrateMbps, rateHz, *Ms) are full precision —
// round at display (.toFixed).
export interface TakeVideo {
  file: string;
  mime?: string;
  width?: number;
  height?: number;
  frames?: number;
  syncFrames?: number;
  /** Every frame a keyframe → the all-intra HEVC contract held. */
  allIntra?: boolean;
  durationS?: number;
  fps?: number;
  sizeBytes: number;
  bitrateMbps?: number;
  /** First/last frame SENSOR_TIMESTAMP (boottime ns) from the frames sidecar. */
  startBootNs?: number | null;
  endBootNs?: number | null;
  /** Frame-sidecar line count (should equal `frames`). */
  sidecarLines?: number;
  /** Present when the MP4 couldn't be read (e.g. unfinalized). */
  error?: string;
}

export interface TakeAudio {
  file: string;
  sizeBytes: number;
  durationS: number;
  /** Sample-0 / sample-N boottime (ns), derived from the WAV clock-sync sidecar. */
  startBootNs?: number | null;
  endBootNs?: number | null;
}

export interface TakeTrack {
  file: string;
  lines: number;
  sizeBytes: number;
  startBootNs?: number | null;
  endBootNs?: number | null;
  /** Effective sample rate (lines / span). For hands.jsonl this is the COMBINED rate
   * of both hands interleaved into one file; see `perHand` for the per-hand rate. */
  rateHz?: number;
  /** Present only when the track carries samples from more than one hand (hands.jsonl):
   * each hand's own line count and rate, so the combined `rateHz` isn't mistaken for a
   * per-hand rate. */
  perHand?: { handedness: string; lines: number; rateHz: number }[];
}

/** One stream's extent, in ms relative to the earliest stream start (`t0`). */
export interface AlignmentStream {
  stream: string;
  startMs: number;
  endMs: number;
}

/** Cross-stream timing: each stream's offset + the mutually-covered window. All on the
 * boottime clock, so differing durations are reconciled by start offset, not skew.
 * All numeric fields are full precision — round at display (.toFixed). */
export interface Alignment {
  available: boolean;
  t0BootNs?: number;
  streams?: AlignmentStream[];
  overlapStartMs?: number;
  overlapEndMs?: number;
  overlapDurationS?: number;
}

export interface TakeInspection {
  name: string;
  path: string;
  totalBytes: number;
  hasManifest: boolean;
  /** Where the stats came from: "manifest" = baked by the recorder at record-stop (v3+
   * takes, read here in TS); "recomputed" = native deep re-probe (older/hollow manifest). */
  statsSource?: "manifest" | "recomputed";
  videos: TakeVideo[];
  audios: TakeAudio[];
  /** Full-rate data tracks (head.jsonl / hands.jsonl). */
  dataTracks: TakeTrack[];
  alignment: Alignment;
}

export interface ZipResult {
  /** "ok" on success; otherwise "not_found" / "error" / "no_context" / "no_storage" / "unavailable". */
  status: string;
  /** Absolute path of the .zip (on success) — pass to expo-sharing. */
  path?: string;
  sizeBytes?: number;
  message?: string;
}

// The baked `stats` block a v3+ manifest carries (see TakeRecorder.buildManifest).
interface BakedStats {
  videos?: TakeVideo[];
  audios?: TakeAudio[];
  dataTracks?: TakeTrack[];
  alignment?: Alignment;
}

// App-private storage base path; only native knows it, null off-device.
async function catalogRoot(): Promise<string | null> {
  if (!nativeModule) return null;
  return (await nativeModule.catalogRoot()) as string | null;
}

// expo-file-system's File/Directory want a file:// URI; native hands us a bare absolute path.
function toUri(path: string): string {
  return path.startsWith("file://") ? path : `file://${path}`;
}

// A .jsonl that is a full-rate DATA track (head/hands) — not a per-frame video sidecar
// (*_frames.jsonl) or an audio clock-sync sidecar (*.clocksync.jsonl).
function isDataTrack(name: string): boolean {
  return name.endsWith(".jsonl") && !name.includes("_frames") && !name.includes(".clocksync");
}

function summarize(dir: Directory): TakeSummary {
  const files = dir.list().filter((e): e is File => e instanceof File);
  return {
    name: dir.name,
    path: dir.uri,
    createdAtWallMs: Number(dir.name.replace(/^take_/, "")) || 0,
    totalBytes: files.reduce((sum, f) => sum + f.size, 0),
    hasManifest: files.some((f) => f.name === "manifest.json"),
    videoCount: files.filter((f) => f.name.endsWith(".mp4")).length,
    audioCount: files.filter((f) => f.name.endsWith(".wav")).length,
    dataTrackCount: files.filter((f) => isDataTrack(f.name)).length,
  };
}

/** Cheap list of recorded takes for the files screen. Enumerates the take dirs in TS (no
 * media probing); empty off-device. Newest first (dir names sort by capture wall-ms). */
export async function listTakes(): Promise<RecordingsList> {
  const basePath = await catalogRoot();
  if (!basePath) return { basePath: null, takes: [] };
  try {
    const base = new Directory(toUri(basePath));
    if (!base.exists) return { basePath, takes: [] };
    const takes = base
      .list()
      .filter((e): e is Directory => e instanceof Directory && e.name.startsWith("take_"))
      .map(summarize)
      .sort((a, b) => b.name.localeCompare(a.name));
    return { basePath, takes };
  } catch {
    // A transient FS error shouldn't crash the files screen — show it as empty.
    return { basePath, takes: [] };
  }
}

// Read + parse a take's manifest.json, or null if absent/unreadable.
function readManifest(takeDirUri: string): Record<string, unknown> | null {
  try {
    const mf = new File(`${takeDirUri}/manifest.json`);
    if (!mf.exists) return null;
    return JSON.parse(mf.textSync()) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** Full inspection of one take: stats (resolution, all-intra, fps, bitrate), per-stream
 * boottime spans, and the cross-stream overlap window. For a v3+ take this is a pure
 * manifest read (`statsSource: "manifest"`); older takes fall back to the native deep
 * re-probe (`"recomputed"`). Null off-device / unknown take. */
export async function inspectTake(name: string): Promise<TakeInspection | null> {
  const basePath = await catalogRoot();
  if (basePath) {
    const takeDirUri = `${toUri(basePath)}/${name}`;
    const manifest = readManifest(takeDirUri);
    const stats = manifest?.stats as BakedStats | undefined;
    // A v3+ manifest bakes the full inspection — assemble it here, no native probe.
    if (stats?.videos) {
      const dir = new Directory(takeDirUri);
      const files = dir.exists ? dir.list().filter((e): e is File => e instanceof File) : [];
      return {
        name,
        path: dir.uri,
        totalBytes: files.reduce((sum, f) => sum + f.size, 0),
        hasManifest: true,
        statsSource: "manifest",
        videos: stats.videos ?? [],
        audios: stats.audios ?? [],
        dataTracks: stats.dataTracks ?? [],
        alignment: stats.alignment ?? { available: false },
      };
    }
  }
  // Pre-v3 / hollow manifest: fall back to the native on-demand deep re-probe.
  if (!nativeModule) return null;
  const raw = (await nativeModule.inspectTake(name)) as TakeInspection | { error: string } | null;
  if (!raw || "error" in raw) return null;
  return { ...raw, statsSource: "recomputed" };
}

/** Bundle a take into a cache .zip (native); returns its path for expo-sharing. */
export async function zipTake(name: string): Promise<ZipResult> {
  if (!nativeModule) return { status: "unavailable" };
  return (await nativeModule.zipTake(name)) as ZipResult;
}
