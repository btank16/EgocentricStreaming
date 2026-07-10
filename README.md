# @chameleon/expo-xr-android

Local Expo native module exposing **Android XR** perception, camera, and audio streams to the React Native app for **time-synced egocentric capture**. Android only; built and verified on the **Samsung Galaxy XR** (Android XR).

It surfaces live sensor data and records time-synced egocentric takes to **disk** — stereo all-intra HEVC video, audio, and full-rate pose/joint tracks with a co-registration manifest (see [Recording](#recording)).

## Stack


|                          |                                                                                                                                                                                                                                                                                                                                           |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Perception (head, hands) | **Jetpack XR SDK** — `androidx.xr.arcore` / `androidx.xr.runtime` `1.0.0-alpha15`                                                                                                                                                                                                                                                         |
| Camera                   | **Camera2** + `TextureView` (no XR SDK)                                                                                                                                                                                                                                                                                                   |
| Audio                    | `**AudioRecord`** (PCM)                                                                                                                                                                                                                                                                                                                   |
| Module framework         | Expo Modules (Kotlin) · `minSdk 24`                                                                                                                                                                                                                                                                                                       |
| Bridge policy            | Heavy data stays native — camera **pixels** (a native preview *view*), audio **PCM** (reduced to levels natively), and a take's files (written to disk natively). The bridge carries only the **live-monitoring** readouts: small pose/level numbers + frame fps/timestamps for the on-screen cards. A recording crosses *nothing* extra. |


Jetpack XR deps live in `android/build.gradle` and propagate to the app via Expo autolinking. The "augmented / glasses" libs (`glimmer`, `projected`) are commented out — they pull `compileSdk 37` / AGP 9, which Expo SDK 56 (compileSdk 36 / AGP 8.12) can't satisfy. The immersive stack (runtime, arcore, scenecore) is unaffected. `com.android.extensions.xr` is `compileOnly` (provided by the device at runtime).

## File layout

Kotlin (`android/src/main/java/expo/modules/xrandroid/`) is grouped into sub-packages by concern; the module file at the root is pure declaration. The three capture concerns — `diagnostics/`, `streams/`, `recording/` — are detailed below; `control/` holds the stateful runtime coordinator (`StreamController`) and `bus/` is the public cross-module seam (see [Consuming raw streams](#consuming-raw-streams-from-another-native-module)). The TS layer (`src/`) mirrors the capture grouping, with the optional module handle + barrel at the root.

**Root** — `ExpoXrAndroidModule.kt`: declaration only (`Name`, Functions, `View`, `Events`, `OnCreate`, `OnDestroy`) — the stateless diagnostics probes call the probe objects directly, and every stateful stream/recording Function delegates to `control/StreamController.kt`, which owns the capture sources, sink handles, session-origin latch, and recorder (and is the `XrSourceProvider` the bus resolves against). `OnCreate` binds `XrCaptureBus` to that controller. `Helpers.kt`: shared bridge helpers used by both `diagnostics/` and `recording/` — enum→token **labels** and full-precision **unit conversions** (rounding is a display concern, done in TS; see [Configuration](#configuration--device-profiles--tuning)); `Constants.kt`: the centralized tunables — `Permissions`, the per-device `DeviceProfile` (`ACTIVE_PROFILE`), and device-agnostic `Tuning` (see below).

`**diagnostics/`** — per-endpoint device/capability **probes**. These are the *portable* layer: standard `Camera2` / `MediaCodec` / `AudioManager` queries that run on any Android device and report whatever it exposes — the mechanism for re-validating on new Android XR hardware (the [hardware-bound decisions](#hardware-bound-decisions-galaxy-xr) came from running these on the Galaxy XR). The `describe*()` probe faces are side-effect-free — none open a device or need a permission; the camera/audio/encoder files also host the `resolve()` decision face (probe-first, profile-fallback) the capture paths consume (see [Configuration](#configuration--device-profiles--tuning)).


| File                   | Role                                                                                                                                                  |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Capabilities.kt`      | Aggregator: XR runtime + permission grants; composes the camera + audio inventory from the endpoint probes (`getDeviceCapabilities`)                  |
| `CameraProbe.kt`       | Camera2 `LENS_*` calibration dump (`probeCameras`) + capture envelope — output sizes × advertised/sustained fps, AE ranges (`probeCaptureSizes`)      |
| `AudioProbe.kt`        | `AudioManager` inventory (rate, buffer, mics) + the audio capture-format / timestamp-mode decisions                                                   |
| `EncoderProbe.kt`      | HEVC/AVC encoder capabilities (hw flag, instances, achievable fps) + the encoder-pick decision                                                        |
| `HeadTrackingProbe.kt` | Head-tracking endpoint facts: runtime, permission, fixed config, timestamp seam                                                                       |
| `HandTrackingProbe.kt` | Hand-tracking endpoint facts: runtime, permission, `BOTH` config, 26 joints/hand, timestamp seam                                                      |
| `ProfileDraft.kt`      | Probes this hardware into a `DeviceProfile`-shaped draft for onboarding (`xr.profiles`; see [Configuration](#configuration--device-profiles--tuning)) |


`**streams/`** — the live sensor streams (the `CaptureSource`↔`CaptureSink` foundation) + the camera view.


| File                         | Role                                                                                             |
| ---------------------------- | ------------------------------------------------------------------------------------------------ |
| `CaptureSource.kt`           | Foundation: `CaptureSink` + sink-refcounted `CaptureSource` fan-out                              |
| `CaptureSinks.kt`            | `DisplaySink` (→ bridge, optional throttle) · `JsonlStorageSink` (→ JSONL)                       |
| `XrSessionManager.kt`        | One shared Jetpack XR `Session`; **additive** config; activity-bound lifecycle                   |
| `PoseSource.kt`              | `HeadPoseSample` + `PoseSource` interface (the OpenXR swap seam)                                 |
| `JetpackPoseSource.kt`       | Head pose from `ArDevice.state`; `trackingStateLabel` (shared)                                   |
| `HeadPoseCaptureSource.kt`   | Head-pose `CaptureSource`                                                                        |
| `HandCaptureSource.kt`       | Both hands' joint `CaptureSource` (full-rate dispatch; any display throttle is opt-in, per-hand) |
| `SessionOriginController.kt` | Latches the session origin at the recording boundary                                             |
| `AudioController.kt`         | `AudioCaptureSource` (raw `AudioChunk`) + `AudioLevelDisplaySink` / `WavStorageSink` + facade    |
| `ExpoXrCameraPreviewView.kt` | Camera2 → `TextureView` preview; adds the encoder Surface when recording                         |
| `EncoderRecorder.kt`         | All-intra HEVC `MediaCodec` → `MediaMuxer` (`cam<N>.mp4`) + frame-timestamp sidecar              |
| `CameraRegistry.kt`          | cameraId → live preview view, so `TakeRecorder` can drive its encoder                            |


`**recording/`** — turning the running sources into a take, and reading takes back.


| File                    | Role                                                                                                                                                                                    |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `TakeRecorder.kt`       | A take: attach storage sinks per stream, own the origin latch, write `manifest.json`                                                                                                    |
| `RecordingInspector.kt` | Native catalog seams: `catalogRoot` (base path) + `inspectTake` deep re-probe (fallback) + `computeStats` (baked into the manifest at record-stop). Listing + manifest reads live in TS |
| `RecordingExporter.kt`  | Zips a take dir → cache `.zip` for the share sheet                                                                                                                                      |


TS (`src/`): root `native.ts` (the **module handle** — `requireOptionalNativeModule`, which is `null` off-device / before prebuild, so every JS API guards on it and degrades to safe defaults instead of crashing; the method surface itself is a committed, non-optional contract since JS and native ship in one build) + `index.ts` (barrel); `diagnostics/` (`capabilities` · `camera-calibration` · `camera-capture-probe` · `video-encoder-probe` · `head-tracking` · `hand-tracking`); `streams/` (`camera` · `headpose` · `hands` · `audio` · `session-origin`); `recording/` (`recording` — control
only); `catalog/` (`catalog` — `list`/`inspect`/`export` over `expo-file-system`). The single access point `xr` (`xr.ts`) composes them all.

## Capability discovery — `getDeviceCapabilities()`

Side-effect-free (no `Session`, no permission prompts). Stable platform APIs only — chosen over the alpha `Session.create`/`XrDevice` probes so it compiles deterministically and never triggers a prompt. Reports:

- **XR runtime** — `PackageManager.hasSystemFeature("android.software.xr.api.spatial" | ".openxr")`.
- **Permissions** — `checkSelfPermission` for HEAD_TRACKING, HAND_TRACKING, SCENE_UNDERSTANDING_COARSE, SCENE_UNDERSTANDING_FINE, CAMERA, RECORD_AUDIO (grant status only).
- **Cameras** — Camera2 enumeration per device: id, lens facing, `SENSOR_INFO_TIMESTAMP_SOURCE` (`REALTIME`/`UNKNOWN`), `isLogicalMultiCamera` + `physicalCameraIds`, world-facing flag + sample YUV output sizes. Plus `concurrentCameraCombos` (`getConcurrentCameraIds`) and a derived `worldStereoConcurrent` (is `{0,2}` a guaranteed combo).
- **Audio** — `AudioManager`: `PROPERTY_OUTPUT_SAMPLE_RATE`, `PROPERTY_OUTPUT_FRAMES_PER_BUFFER`, `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`, and `getMicrophones()` (id, type, location, directionality).
- `**perceptionTimestampExposed: false`** — a constant documenting that Jetpack XR exposes no per-sample capture timestamp (see [Time-sync](#time-sync-model)).

## Camera calibration probe — `probeCameraCalibration()`

Side-effect-free dump of each camera's Camera2 `LENS_*` **calibration metadata** — the on-device test for whether the headset hands us **factory intrinsics/extrinsics** for egocentric capture, or whether we must calibrate ourselves. Reads only `CameraCharacteristics` (never opens a device); also logged to logcat under tag XrCameraProbe. Surfaced in the Record screen's "Camera calibration" card.

Why it exists: **OpenXR** exposes no physical-camera intrinsics (only render-view FOV), and **ARCore-for-XR** exposes no distortion/extrinsics — Camera2's `LENS_*` keys are the only platform path. Those keys are *guaranteed* populated only when a camera advertises `DEPTH_OUTPUT` or `LOGICAL_MULTI_CAMERA` (`calibrationGuaranteed`); otherwise they're best-effort and may be null.

Per camera it reports: `capabilities`, `calibrationGuaranteed`, `intrinsics` (`[f_x, f_y, c_x, c_y, s]`), `distortion` (Brown-Conrady `[k1..k5]`, + deprecated radial fallback), `poseTranslation`/`poseRotation`/`poseReference`, the intrinsics pixel frame (`preCorrectionActiveArraySize`), and convenience flags.

Two gotchas it encodes:

- **Permission-gated keys.** The `LENS_*` calibration keys are in `getKeysNeedingPermission()` on Android 10+ — they read back **null until CAMERA is granted**. `cameraPermissionGranted` / `calibrationKeysReadable` are surfaced so a permission-induced null isn't misread as "device has no calibration".
- `**LENS_POSE_REFERENCE` is not a head frame.** It resolves to `PRIMARY_CAMERA` / `GYROSCOPE` / `UNDEFINED` / `AUTOMOTIVE`; on a headset `GYROSCOPE` (camera→IMU) is the closest to a head extrinsic, which still has to be derived by us.
- **Present ≠ usable.** A camera can satisfy the `DEPTH_OUTPUT` key contract by stubbing the calibration to all-zeros (degenerate `fx=fy=0`). `intrinsicsUsable()` (requires `fx,fy>0`) distinguishes a real matrix from the zero-stub; the verdict string renders a stub as `intrinsics ⚠ stub(0)`, not a misleading `✓`.

**Galaxy XR result:** no usable factory calibration (see [Device support](#device-support-verified--samsung-galaxy-xr)) — egocentric capture must **self-calibrate**.

## Streams

All four are **runtime-gated** (started only on explicit action) and emit via the Expo event bridge, except the camera which is a native view.

### Head pose — `ArDevice` (Jetpack XR ARCore)

- **Config:** `DeviceTrackingMode.SPATIAL`. **Permission:** `HEAD_TRACKING`.
- **Data:** `Pose` in **perception space** — `translation` (Vector3, metres) + `rotation` (Quaternion x,y,z,w) — plus `trackingState`. This is a **fused 6-DoF pose** (the runtime's SLAM/VIO from camera + IMU + depth) — absolute, gravity-aligned, drift-corrected — **not raw gyroscope/IMU** (angular velocity / acceleration are a lower-level signal that *feeds* the fusion; we don't capture it). The `GYROSCOPE` `LENS_POSE_REFERENCE` above is unrelated (a camera→IMU extrinsic frame).
- **Rate:** `ArDevice.state` `StateFlow` at the perception's **full native rate** — no native throttle. Both the **display** and **storage** sinks receive the full rate (head pose has no display throttle). (Measured rate on the Galaxy XR: see [Hardware-bound decisions](#hardware-bound-decisions-galaxy-xr).)
- **Timestamp:** `elapsedRealtimeNanos()` at delivery — **delivery-stamped** (Jetpack has no capture timestamp).

### Hand joints — `Hand.left/right` (Jetpack XR ARCore)

- **Config:** `HandTrackingMode.BOTH` (the runtime can't track a single hand). **Permission:** `HAND_TRACKING`.
- **Data:** **26** `HandJointType` joints **per hand**, each a `Pose` (translation + rotation) in perception space; `trackingState`, `jointCount`. Joint map keyed by joint name (e.g. `PALM`, `INDEX_TIP`).
- **Rate:** **full native rate** — no native throttle. The **display** sink also runs at full rate by default (same as head); pass `bridgeRateHz` to throttle the bridge emit per hand (keyed per handedness). A **storage** sink always records the full rate. Both hands start/stop together. (Measured rate: see [Hardware-bound decisions](#hardware-bound-decisions-galaxy-xr).)
- **Timestamp:** delivery-stamped (`elapsedRealtimeNanos()`).

### Camera preview — Camera2 (`ExpoXrCameraPreviewView`)

- A native view parameterized by `cameraId`; pixels render to a `TextureView` and **never cross the bridge**. **Permission:** `CAMERA`.
- **Preview size:** the **smallest** `SurfaceTexture` output size (lowest bandwidth — matters when two cameras run at once).
- **Emits:** `onCameraReady` (w×h), `onCameraError` (code — incl. `max_cameras_in_use`), `onCameraFrame` (`frameTs`, `fps`) at ~2 Hz.
- **Timestamp:** per-frame `SENSOR_TIMESTAMP`. On Galaxy XR's world cameras this is `REALTIME` (boottime) — i.e. **capture-accurate** and audio-comparable. This is **detected, not assumed**: at record-start `TakeRecorder` reads each recorded camera's `SENSOR_INFO_TIMESTAMP_SOURCE` and writes it (`timestampSource`) plus the derived `fidelity` into the manifest, logging a warning if a world camera isn't `REALTIME`. A non-`REALTIME` camera is recorded as `delivery-stamped` (the perception/audio tier) rather than silently mislabeled — so a hardware/OS change that flips the source shows up in the take instead of breaking alignment.
- **Stereo:** two independent views (cam 0 + cam 2). `getConcurrentCameraIds()` does **not** advertise the pair, but both open concurrently in practice — treat as **best-effort** (undocumented; may fail under thermal/OS changes), with single-camera (cam 0) as the supported fallback.
- **Recording:** when a take starts, the view reconfigures its capture session to *also* target an HEVC encoder input surface (full-res), writing `cam<N>.mp4`. See [Recording](#recording).

### Audio — `AudioRecord` per source (`AudioController`)

- **Sources:** `CAMCORDER` + `VOICE_RECOGNITION`, each started/stopped independently. **Permission:** `RECORD_AUDIO`.
- **Format:** **48000 Hz, mono, PCM 16-bit** (the device-native rate; avoids resampling). Read in 512-frame chunks; buffer `max(getMinBufferSize, 2048 B)`.
- **Emits:** RMS + peak (0..1 linear, computed natively) at **~10 Hz** (`Tuning.AUDIO_LEVEL_EMIT_NS = 100 ms`). PCM stays native. `start` returns a per-source status (`recording` / `init_failed` / …).
- **Timestamp:** **read-time `elapsedRealtimeNanos()`** (boottime) at each `read()`, with a running frame counter — the *same call* the camera/perception streams use, so audio aligns by construction. The HAL's `AudioTimestamp` clock is **deliberately not used** (unusable on this device — see [Hardware-bound decisions](#hardware-bound-decisions-galaxy-xr)). Tradeoff: audio is **delivery-stamped** (~buffer latency, tens of ms), not capture-exact — same tier as the poses. A one-time `XrAudio` logcat line records the HAL clock deltas for the record.
- **Note:** `UNPROCESSED` is unsupported on Galaxy XR, so audio is system-processed (beamformed).

### Session origin — `SessionOriginController` (Jetpack XR ARCore)

- **What:** the world tracking frame, documented per recording. *Not* user-captured — latched automatically when the shared session reaches a `TRACKING` device pose, surfaced via the `onSessionOrigin` event (and `getSessionOrigin()` for late
subscribers).
- **Recording boundary (refcount):** the perception streams (head + hands) reference-count the session, and **starting a recording re-latches a fresh origin at record-start** — so a take's `manifest.json` documents the head pose when *recording* began, not when a display stream first came up. The live display readout keeps its own first-stream latch. When the last stream stops, the latch is **cleared** so the next recording latches fresh. Cameras/audio don't create the session, so a camera/audio-only recording has no origin.
- **No session teardown:** Jetpack XR alpha exposes no manual `Session` teardown (`destroy()` is private; `Session` is a per-`Context` singleton), so the runtime world frame persists across recordings — only our *logical* origin record resets. Consecutive takes therefore share a stable frame (which aids co-registration).
- **Data:** `devicePose` (`world_from_device` — translation + quaternion at session start), `trackingState`, `reliable` (false if latched on timeout before `TRACKING`), `headHeightAboveOriginM`, and a best-effort `floor` reference.
- **Floor (best-effort):** needs `SCENE_UNDERSTANDING_COARSE` + a detected `FLOOR` plane (`Plane.subscribe` → `PlaneLabel.FLOOR` → `centerPose.y`). Absent → `available:false`; never blocks the origin.
- **Convention:** gravity-aligned, right-handed, +X right / +Y up / −Z forward, metres — a runtime constant attached JS-side (`XR_COORDINATE_SYSTEM`). Up/gravity is absolute; XZ + heading are arbitrary per session, which is why the start pose is recorded.

## Time-sync model

Every stream is stamped on the **boottime** clock, but in two tiers:


| Stream               | Timestamp                                     | Fidelity                                               |
| -------------------- | --------------------------------------------- | ------------------------------------------------------ |
| Camera (cam 0/2)     | `SENSOR_TIMESTAMP` (REALTIME)                 | **capture-accurate**                                   |
| Audio (both sources) | read-time `elapsedRealtimeNanos()` (boottime) | delivery-stamped                                       |
| Head pose · hands    | `elapsedRealtimeNanos()` at delivery          | **delivery-stamped** (carries sensor→delivery latency) |


So the **camera is the one capture-accurate stream** (its `SENSOR_TIMESTAMP` is the reference clock for alignment); **audio and the perception poses are delivery-stamped on the same boottime clock** (tens-of-ms latency), so they align to the camera within that tolerance but aren't shutter-exact. The audio HAL *could* in principle be capture-exact via
`AudioTimestamp`, but its clock is unusable on this device (see Audio above); closing the
pose gap is the [OpenXR open door](#openxr-open-door).

**Startup / teardown offsets.** Streams begin producing data at slightly different instants — audio ~immediately, perception ~30 ms (waiting for the next ~42 Hz sample), cameras ~130–170 ms (the capture session reconfigures to add the encoder surface). This is *not* misalignment: every sample carries its boottime stamp, so a take aligns by timestamp and the manifest's **overlap window** `[latest-start, earliest-stop]` is the mutually-covered range — typically ~0.2–0.3 s of edge-trim on a multi-second take.

## Recording

Takes are persisted via a **capture → sink fan-out**: each `CaptureSource` (head, hands, audio, camera) produces full-rate, sink-agnostic samples and fans them out to a `DisplaySink` (throttle → bridge, for the live UI) and a `StorageSink` (→ disk), *simultaneously* — the live readout never compromises recorded fidelity. Capture is a strict **superset**; matching any downstream ML format is a converter concern that *reduces* the reference, never constrains it. A `TakeRecorder` turns the running sources into a take: it attaches a storage sink per stream, owns the session-origin latch at the recording boundary, and writes the manifest on stop.

### Master format — all-intra HEVC

The world cameras record **all-intra HEVC** (`KEY_I_FRAME_INTERVAL = 0` → every frame a keyframe) on the hardware `c2.qti.hevc.encoder`, fed straight from the camera surface (no CPU pixel copy), at full **3000×3000**. The fps is **derived from the sensor**, capped at 30 (`ACTIVE_PROFILE.maxCaptureFps`) — **24 on the Galaxy XR**. All-intra preserves frame-exact random
access (the reason per-frame JPEG was considered) without temporal artifacts.

The device-forced parts — choosing all-intra HEVC *over* per-frame JPEG, the 24 fps sensor cap, and best-effort stereo (cam 0 + cam 2 are independent, **not** shutter-synced → pair by timestamp) — are explained in [Hardware-bound decisions](#hardware-bound-decisions-galaxy-xr).

### Quality presets

FPS is pinned (the sensor max ≤ 30) for **all** presets — only resolution/bitrate vary. Sizes are approximate (research-max measured ≈ 119 Mbps); tune the bitrates from on-device runs. The presets (size + bitrate) are defined in `ACTIVE_PROFILE.cameraPresets` (`Constants.kt` — see [Configuration](#configuration--device-profiles--tuning)).


| Preset                 | Resolution | Bitrate   | ~Stereo size | Use                       |
| ---------------------- | ---------- | --------- | ------------ | ------------------------- |
| `research-max`         | 3000×3000  | ~120 Mbps | ~1.8 GB/min  | ground-truth captures     |
| `balanced` *(default)* | 3000×3000  | ~50 Mbps  | ~0.75 GB/min | full res, longer takes    |
| `efficient`            | 1920×1920  | ~20 Mbps  | ~0.3 GB/min  | long takes / quick review |


### On-disk take layout

One directory per take under `getExternalFilesDir()` (pulls off via wireless adb, no root):

```
take_<wallMs>/
  manifest.json                    # keystone: origin, sync model, track list, calibration note
  cam0.mp4 / cam2.mp4              # all-intra HEVC, 3000×3000 @ sensor fps (24 on Galaxy XR)
  cam0_frames.jsonl               # frameIndex → SENSOR_TIMESTAMP (boottime ns) [+ cam2_frames.jsonl]
  audio_camcorder.wav             # LPCM 48k mono 16-bit [+ audio_voice_recognition.wav]
  audio_camcorder.clocksync.jsonl # (framePosition, bootNs) ~1 Hz, WAV-relative
  head.jsonl / hands.jsonl        # full-rate {ts, …} data tracks
```

The **manifest** (schema `version: 3`) is the co-registration keystone — session origin (`world_from_device`), each stream's clock + `fidelity` tier (cameras also carry the detected `timestampSource`), a derived `sync.fidelityByStream` summary, the self-calibration note, and the file list. Fidelity is sourced per stream from the actual recorded streams — not hardcoded — so it can't drift from reality. Everything shares one boottime clock, so a take is aligned in post by timestamp (see [Time-sync](#time-sync-model)), not by stream duration.

Camera track entries carry the **actual resolved encode config** (`width`/`height`, sensor- derived `fps`, chosen `encoder`, clamped `bitRate`, `qualityScale`) each with a `*Provenance` tag (`detected`/`fallback`/`clamped`) — what was *really* encoded, not the requested preset. At record-stop, once every file is finalized, the recorder **bakes** the full inspection
(`stats`: per-file `videos`/`audios`/`dataTracks` measurements + cross-stream `alignment`) into the manifest, so `inspectTake` is a manifest read (`statsSource: "manifest"`) rather than a re-probe.

### Catalog · inspect · export

There is **no database** — the filesystem + per-take `manifest.json` *is* the catalog. **Listing and manifest reads live in TS** (`src/catalog/catalog.ts`, via `expo-file-system`), so the same code serves local takes today and future cloud takes (swap the `Directory`/`File` source). Native provides only the three seams TS can't: the storage base path (`catalogRoot`), the deep re-probe (`inspectTake`), and zipping (`zipTake`).

- **List** (`xr.catalog.list`) — enumerates the take dirs in TS and builds the cheap per-take summary (name/date/size/counts), no media probing, for the files screen.
- **Inspect** (`xr.catalog.inspect`) — for a **v3+ take** this is a pure manifest read: the full stats were baked at record-stop (`statsSource: "manifest"`). For an older take it falls back to the native deep re-probe (`"recomputed"`) — `MediaExtractor` stats (resolution, all-intra check, fps, bitrate, sizes) plus per-stream first/last boottime and the **overlap window**. For `hands.jsonl` (both hands interleaved into one file) it also reports **per-hand** rates, so the combined line rate isn't mistaken for a single hand's.
- **Export** (`xr.catalog.export` → native `zipTake`) — zips the whole take dir to a cache `.zip` for the OS share sheet (email / Drive / nearby). Whole-take, because the files are only meaningful together.

The Record-screen UI flow (start streams → record, control locking) is documented in the app's README (`apps/mobile/README.md`), not here — this module exposes the capture/recording *API*; the screen that drives it is an app concern.

### Implementation gotchas (lessons)

Code/design pitfalls hit during development — distinct from the [hardware-bound decisions](#hardware-bound-decisions-galaxy-xr) (the one *hardware* pitfall, the audio clock, cross-refs there). Note up front: **all streams share one boottime clock**, so clock alignment is always *universal* — the bugs below were stream-*placement* errors, not clock errors.

- **Audio clock-sync frames count from *recording* start, not stream start.** `WavStorageSink` keeps its own frame counter; using the source's read-loop counter placed audio's start at *stream*-start, mis-aligning it by the start-stream→record gap. **The clock was never wrong** (every stream is on boottime) — this was a frame-*index* reference error that merely *masqueraded* as a growing clock offset, and cost several debug cycles.
- **Don't trust the audio HAL** `AudioTimestamp` **clock** — it's unusable on this device, so audio is stamped read-time boottime instead (see [Hardware-bound decisions](#hardware-bound-decisions-galaxy-xr)).
- **Align by timestamp + the overlap window, never by stream duration.** Durations differ by the startup/teardown offsets above; the timestamps reconcile them.
- **Stop-race:** a sample can dispatch just after a sink's executor shuts down; the storage sinks drop such late tasks (catch `RejectedExecutionException`) rather than crash.

## Device support (verified — Samsung Galaxy XR)

- XR runtime: **spatial + OpenXR** both present.
- **Cameras (4 exposed, 2 used):** `0` and `2` (both back/`REALTIME`) are the **world-facing passthrough pair we capture as stereo** — `0` is also the documented `camera_id=0` primary and the mono fallback, `2` is the second of the pair. Unused:
`1` front/`UNKNOWN` (selfie/avatar — synthetic, non-boottime) and `3` back/`REALTIME`. No logical multi-camera; `getConcurrentCameraIds()` returns singletons (no guaranteed pair), but `0`+`2` open concurrently in practice.
- **Camera calibration (probed): none usable.** World cams `0`/`2` (3000×3000) advertise only `BACKWARD_COMPATIBLE` + `READ_SENSOR_SETTINGS` — no `DEPTH_OUTPUT`/`LOGICAL_MULTI_CAMERA`, so all `LENS_*` keys are **null** (no intrinsics, distortion, or extrinsics). Cam `3` (depth, 672×672) advertises `DEPTH_OUTPUT` so the keys are *present* but Samsung **stubs them to all-zeros** (degenerate). ⇒ must self-calibrate — see [Hardware-bound decisions](#hardware-bound-decisions-galaxy-xr).
- **Audio:** 48000 Hz / 512 frames/buffer / **unprocessed unsupported**. 4 built-in mics (mainbody, omni) + 1 `remote_submix` (virtual, excluded). 6-mic array per spec.

## Hardware-bound decisions (Galaxy XR)

Several choices exist **only** because of this device's hardware/HAL bounds — on other silicon they might be made differently. Each was *measured on-device* (via the probes above) and then forced a decision. They're consolidated here so the device specific parts of the design are explicit.


| Galaxy XR constraint (measured)                                                                                                                                                                                                                                | Decision it forced                                                                                                                                                                                                                                                                                                        |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **No factory camera calibration** — world cams 0/2 advertise only `BACKWARD_COMPATIBLE` (+`READ_SENSOR_SETTINGS`), so all `LENS_*` keys are **null**; the depth cam (3) advertises `DEPTH_OUTPUT` but **stubs intrinsics/distortion/extrinsics to all-zeros**. | Egocentric capture must **self-calibrate** the world camera (intrinsics + distortion) and **derive the camera→head extrinsic** itself; the manifest records `worldCameraFactoryCalibration: false`.                                                                                                                       |
| **Sensor caps at 24 fps** — JPEG, YUV, and the encoder Surface all peak at 24 fps; no 30 fps mode exists.                                                                                                                                                      | Capture fps is **not hard-coded**: the camera view *derives* it from the sensor's advertised max (`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`), **capped at 30** (`ACTIVE_PROFILE.maxCaptureFps`). On the Galaxy XR that resolves to **24**; faster sensors would run up to 30. FPS is pinned (never traded across presets). |
| **Jetpack perception rate ~41 Hz** — `ArDevice` / `Hand` `StateFlow` updates at ~41 Hz on this device.                                                                                                                                                         | Head/hands record at **full native rate** (≫ the 24 fps camera, so interpolating poses to camera frames stays well-posed); the *display* emit also defaults to full rate for both, with an optional per-stream `bridgeRateHz` throttle as a bridge concern only.                                                          |
| **Hardware JPEG ISP is slow** — ~10 fps @ 9 MP (stall-bound), never reaches 24 — while the **HW HEVC encoder is fast** (`c2.qti.hevc.encoder`: 58.7 achievable fps @ 9 MP, 16 instances).                                                                      | Master format = **all-intra HEVC fed from the camera Surface**, *not* per-frame JPEG. (On silicon with a fast JPEG path, per-frame JPEG would have been viable — this was a device-specific pivot.)                                                                                                                       |
| **No logical multi-camera**; `getConcurrentCameraIds()` does **not** advertise the `{0,2}` pair (they open concurrently only in practice).                                                                                                                     | Stereo = **two independent Camera2 devices + two HEVC encoder instances**, treated as **best-effort** (cam 0 mono is the supported fallback). cam0/cam2 are **not shutter-synced** → store timestamps independently, pair by nearest.                                                                                     |
| `**AudioRecord` `AudioTimestamp` clock is unusable** — it ignores `TIMEBASE_BOOTTIME` and reads seconds off boottime.                                                                                                                                          | Audio is stamped with **read-time `elapsedRealtimeNanos()`** (delivery-tier, but on the same boottime clock as camera/perception) instead of the HAL timestamp.                                                                                                                                                           |
| `**UNPROCESSED` audio source unsupported**                                                                                                                                                                                                                     | Audio is **system-processed (beamformed)** — there's no raw-mic path.                                                                                                                                                                                                                                                     |
| **Hardware level `LIMITED`**                                                                                                                                                                                                                                   | Camera streams stay within guaranteed `LIMITED` combos (preview `PRIV` + record `PRIV`); no FULL-level features assumed.                                                                                                                                                                                                  |
| **Camera session reconfigures on record** (~130–170 ms to add the encoder Surface).                                                                                                                                                                            | Accepted as a startup offset and handled by the **overlap window** rather than recording the camera blind. (Pre-warming the encoder could cut it — see [Not yet done](#not-yet-done).)                                                                                                                                    |


Closely related **platform / SDK** bounds (not strictly hardware) that also shaped the design: Jetpack XR exposes **no per-sample pose capture timestamp** → poses are delivery-stamped (the [OpenXR open door](#openxr-open-door)); Android has **no public MV-HEVC encode API** → stereo is two independent streams, not multiview; Jetpack alpha exposes **no** `Session` **teardown** → the world frame persists across takes (only the logical origin resets).

## Configuration — device profiles + tuning

The device-forced choices above are **not scattered through the code** — every tunable value lives in `Constants.kt`, so re-validating on new Android XR hardware (re-run the [probes](#capability-discovery--getdevicecapabilities), then retune) is a single-file edit, not a hunt. This is **compile-time** (code-level) configuration — one place to retune — distinct from any runtime, client-set surface.

- `DeviceProfile` **/** `PROFILES` **/** `ACTIVE_PROFILE` — the per-hardware physics/format: audio capture format (rate, channel, encoding, read size), the capture-fps **cap**, the preview fallback size, and the quality `cameraPresets`. `GALAXY_XR` is the verified profile and the `DEFAULT_PROFILE` fallback. Devices are recognized by `detectProfile()`, which keys `PROFILES` on `deviceKey()` = `"${Build.MANUFACTURER}/${Build.MODEL}"` (lowercased) and falls back to `DEFAULT_PROFILE` when unrecognized. **Adding a headset = add a** `DeviceProfile` **to** `PROFILES` **under its** `deviceKey` — detection is then automatic; nothing else changes. Because every runtime value still flows through the `resolve()`
layer (hardware first), an *unregistered* device already captures correctly against the fallback; a profile just gives it a tuned baseline.
  - **Onboarding tooling (`**xr.profiles`**)** — don't hand-write the stanza. On a dev build call `xr.profiles.exportDraft()`: native **probes this hardware** (validates the audio capture format, reads each world camera's native size + advertised max fps, and the HEVC encoder ceiling) and writes `profile-draft.json` to the app-private dir. Pull it (`adb pull <path>`) and run `node scripts/add-device-profile.mjs <path>` — it prints the paste-ready `DeviceProfile` stanza + `PROFILES` entry keyed by the device's real `deviceKey`. Probed values (audio format, fps cap) are device-measured; the subjective knobs (preset bitrates, `efficient` size, preview size) are seeded from the Galaxy XR baseline and flagged to tune. `xr.profiles.draft()` returns the same dump for display.
- `Tuning` — device-agnostic UX/product policy: the display/monitoring cadences (10 Hz audio level, 2 Hz camera status, 1 Hz WAV clock-sync) and the origin/floor latch timeouts. These are display-side only — storage sinks always record the full native rate. (Head/hand bridge rates aren't here — they default to full rate and are throttled only via an explicit `bridgeRateHz` from the client.)
- `**Permissions`** — the `android.permission.`* strings (deduped; were copied per file).

**Rounding is not here — it's in TS.** The native probes/inspector emit **full-precision** derived numbers (fps, Mbps, MP, ms); rounding is a display concern applied at the render site with `.toFixed()` (see `Helpers.kt` and the diagnostics/recording TS wrappers).

## Permissions

Declared in the module's `AndroidManifest.xml` (merged into the app at prebuild), requested **at runtime from JS** (`PermissionsAndroid`) on the Record screen. The XR permissions (`HEAD_TRACKING`, `HAND_TRACKING`, `SCENE_UNDERSTANDING_COARSE`, `SCENE_UNDERSTANDING_FINE`) are Android XR–specific *dangerous* permissions; the OS presents them under its grouped "spatial" / "face and body" XR UI, distinct from the standard Camera/Microphone dialogs.

`SCENE_UNDERSTANDING_COARSE` and `_FINE` gate **different** APIs and are independent — `_FINE` does **not** imply `_COARSE`. `_COARSE` gates plane tracking (the session-origin `FLOOR` reference), hit-testing, and anchor persistence; `_FINE` gates only the Depth API. Both are declared and requested separately.

## Build & rebuild

- Native (Kotlin) changes require a **full app rebuild** (`expo prebuild` + Gradle / `pnpm --filter @chameleon/mobile android`), **not** a Metro reload — a Kotlin change is compiled into the binary, so a Metro-only reload runs old native code (off-device, the JS layer just shows its safe defaults).
- Fast native error surfacing: `./gradlew :chameleon-expo-xr-android:compileDebugKotlin` (the Expo CLI only prints the wrapper exit code).
- `Session.create(activity)` is used (deprecated in alpha15 for a `(context, coroutineContext, lifecycleOwner)` overload whose intended default args aren't documented; suppressed).

## Consuming raw streams from another native module

Other native modules tap the **raw sensor streams in-process** — not over the JS bridge — through the public `bus/` seam. Heavy data never round-trips through TS: light streams pass by reference natively, and camera pixels fan out in hardware.

- **Light streams (head, hands)** — implement `XrStreamConsumer` and attach by id:
  ```kotlin
  val handle = XrCaptureBus.attach("hands", myConsumer)  // brings the source up (refcounted)
  // …full-rate onSample(Map) callbacks on the capture thread…
  handle.close()                                         // detaches (tears down if last sink)
  ```
  The bus is bound to the module's live sources at `OnCreate`; `attach` before create / for an unknown stream / on a missing permission is a **logged no-op**, never a crash.
- **Camera pixels (heavy)** — supply a `Surface` (your `ImageReader`/`SurfaceTexture`); the camera adds it as an extra Camera2 target so frames arrive with `SENSOR_TIMESTAMP`, no copy:
  ```kotlin
  val handle = XrFrameBus.attachConsumerSurface("0", mySurface)  // camera must be previewing
  ```
  A consumer surface is an **additional stream** — on the **LIMITED** Galaxy XR the guaranteed combo is preview + record, so a third stream is **best-effort** (may fail configuration under the same constraint as concurrent stereo).
- **Audio PCM (heavy)** — implement `XrPcmConsumer` (a typed, allocation-free callback, not the map-based `XrStreamConsumer`) and attach by mic source:
  ```kotlin
  val handle = XrCaptureBus.attachAudio("camcorder", myPcmConsumer)  // "camcorder" | "voice_recognition"
  ```
  `onPcm(pcm, frameCount, sampleRate, channels, tsNs, framePosition)` fires per block on the audio read thread; `pcm` is the block's own copy.

The internal `CaptureSource`/`CaptureSink` fan-out stays sealed; `bus/` is its public projection, so the recorder, the display bridge, and a foreign module are all just sinks on the same capture — recording and third-party consumption compose without extra copies.

## OpenXR open door

The Jetpack XR (Kotlin) path used here is ergonomic but bounded. It does **not** expose: per-sample pose **capture timestamps**, hand-joint **velocities/radius**, or a **hand mesh**. The escalation path is **OpenXR** (`XR_ANDROID_`* / `XR_EXT_`*
extensions, via C++/JNI):

- `xrLocateSpace(viewSpace | handSpace, baseSpace, time)` — resample head/hand pose **at an arbitrary** `XrTime` (e.g. a camera frame's timestamp) → **measurement-grade** alignment, replacing the delivery-stamped Jetpack poses.
- `XR_KHR_convert_timespec_time` — convert camera `SENSOR_TIMESTAMP` ↔ `XrTime` (the shared-clock bridge).
- `XR_EXT_hand_tracking` — per-joint radius + linear/angular velocity.
- `XR_ANDROID_hand_mesh` — skinned hand mesh (vertex/index buffers).

`PoseSource` is the **designed seam**: `JetpackPoseSource` today; an `OpenXrPoseSource` would slot in without touching `HeadPoseCaptureSource` or the JS API. Cost is a native C++/JNI OpenXR app (own `XrInstance`/`XrSession` + frame loop), so it's warranted only when capture must be measurement-grade.

## Out of scope

- Environment depth/mesh (`SCENE_UNDERSTANDING_FINE` Depth API), face/eye tracking, and the augmented/glasses surfaces are not implemented.

## Not yet done

The recording layer is built and verified end-to-end (capture → sinks → take →
manifest → inspect/export). Open items:

- **In-app playback** — an HEVC player for `cam<N>.mp4` (all-intra → frame-accurate seek); today the app *inspects* takes but doesn't play them.
- **Camera startup latency** — pre-configuring the encoder surface during preview would cut the ~130–170 ms camera offset (only matters for very short takes).
- **Measurement-grade poses** — the [OpenXR open door](#openxr-open-door): capture-stamped head/hand poses for tight hand-eye tasks.
- **Native build marker** — a bumped version string surfaced in-app, so "did my native change deploy?" is a glance instead of a logcat session.

