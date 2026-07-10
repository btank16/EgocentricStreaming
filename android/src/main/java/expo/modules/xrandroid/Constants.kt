package expo.modules.xrandroid

import android.media.AudioFormat
import android.os.Build
import android.util.Size

// Centralized compile-time tunables: Permissions, per-hardware DeviceProfile (audio format,
// fps cap, presets), PROFILES/detectProfile (recognize device, else fall back), device-
// agnostic Tuning, and Provenance/Resolved (how a value was chosen). The profile is only ever
// a FALLBACK — the runtime prefers hardware-reported values via the diagnostics/ resolve()
// layer (probe first, profile fallback) and stamps each value's Provenance. See README →
// Configuration.

// ============ Permissions ============
// Deduped from per-file copies (HEAD_TRACKING was declared in 3 files). CAMERA /
// RECORD_AUDIO stay on Manifest.permission.* (framework constants) at their call sites.
internal object Permissions {
  const val HEAD_TRACKING = "android.permission.HEAD_TRACKING"
  const val HAND_TRACKING = "android.permission.HAND_TRACKING"
  const val SCENE_UNDERSTANDING_COARSE = "android.permission.SCENE_UNDERSTANDING_COARSE"
  const val SCENE_UNDERSTANDING_FINE = "android.permission.SCENE_UNDERSTANDING_FINE"
}

// ============ Device profile (per-hardware physics/format) ============

// One camera quality preset: capture size (null = the camera's native max) + HEVC bitrate.
internal data class CameraPreset(val size: Size?, val bitRate: Int)

// The device-bound values. Grouped so re-validating on new Android XR hardware is
// "add a DeviceProfile + repoint ACTIVE_PROFILE", not a scattered-const hunt.
internal data class DeviceProfile(
  // Audio: the device-native capture format (chosen to avoid resampling).
  val sampleRate: Int,
  val audioChannel: Int,
  val audioEncoding: Int,
  val framesPerRead: Int,
  // Capture: fps CAP (the actual fps is derived from the sensor at runtime, ≤ this cap).
  val maxCaptureFps: Int,
  // Preview size used when the camera advertises none / query fails.
  val previewFallbackSize: Size,
  // Quality presets by name, and which one `startRecording` defaults to.
  val cameraPresets: Map<String, CameraPreset>,
  val defaultPreset: String,
) {
  // Derived from the audio format so they can't silently drift from audioChannel/
  // audioEncoding (a hand-set bytesPerFrame could disagree with a changed encoding).
  val channelCount: Int
    get() = when (audioChannel) {
      AudioFormat.CHANNEL_IN_MONO -> 1
      AudioFormat.CHANNEL_IN_STEREO -> 2
      else -> Integer.bitCount(audioChannel) // input masks: one set bit per channel
    }
  val bitsPerSample: Int
    get() = when (audioEncoding) {
      AudioFormat.ENCODING_PCM_8BIT -> 8
      AudioFormat.ENCODING_PCM_16BIT -> 16
      AudioFormat.ENCODING_PCM_FLOAT -> 32
      else -> 16
    }
  val bytesPerFrame: Int get() = channelCount * (bitsPerSample / 8)
}

// Samsung Galaxy XR — the verified device (see README.md → Device support). The bitrates
// are a first pass; tune from on-device measurement. FPS is pinned at the sensor max for
// ALL presets — only resolution/bitrate vary.
internal val GALAXY_XR = DeviceProfile(
  sampleRate = 48_000,
  audioChannel = AudioFormat.CHANNEL_IN_MONO,
  audioEncoding = AudioFormat.ENCODING_PCM_16BIT, // bytesPerFrame derives from these two
  framesPerRead = 512,
  maxCaptureFps = 30,
  previewFallbackSize = Size(1920, 1440),
  cameraPresets = mapOf(
    "research-max" to CameraPreset(null, 120_000_000), // native 3000², near-lossless
    "balanced" to CameraPreset(null, 50_000_000), // native 3000², high quality (default)
    "efficient" to CameraPreset(Size(1920, 1920), 20_000_000), // downscaled square, long takes
  ),
  defaultPreset = "balanced",
)

// The global fallback for an UNRECOGNIZED device. The verified Galaxy XR values are the
// known-good baseline — since every runtime value flows through the diagnostics `resolve()`
// layer (hardware first, profile as fallback), an unknown device degrades gracefully rather
// than mis-capturing.
internal val DEFAULT_PROFILE = GALAXY_XR

// Recognized devices, keyed by deviceKey() = "manufacturer/model" (lowercased). Add an
// entry via scripts/add-device-profile.mjs. The Galaxy XR's
// exact Build.MODEL is confirmed by that export script; until it's registered here the
// headset resolves to DEFAULT_PROFILE (identical values), so capture is correct regardless.
internal val PROFILES: Map<String, DeviceProfile> = emptyMap()

// Stable device key for profile lookup. Manufacturer + model is enough to distinguish
// headset silicon; kept lowercase so the generator and the runtime agree on casing.
internal fun deviceKey(): String = "${Build.MANUFACTURER}/${Build.MODEL}".lowercase()

// The active device profile: the recognized profile for this hardware, else the fallback.
internal fun detectProfile(): DeviceProfile = PROFILES[deviceKey()] ?: DEFAULT_PROFILE

internal val ACTIVE_PROFILE = detectProfile()

// ============ Provenance (how a runtime value was chosen) ============
// Every value the runtime resolves is one of: DETECTED (the hardware advertised it),
// FALLBACK (the device query failed → we used the profile constant), or CLAMPED (a
// detected value pinned to a policy cap, e.g. sensor fps capped at maxCaptureFps). The
// manifest records this per value so a take documents WHY each parameter was used.
internal enum class Provenance { DETECTED, FALLBACK, CLAMPED }

// A resolved value paired with its provenance. `label()` is the stable JS token.
internal data class Resolved<out T>(val value: T, val provenance: Provenance) {
  fun label(): String = provenance.name.lowercase()
}

// ============ Audio container format (WAV) ============
// The canonical PCM WAV header WavStorageSink writes and RecordingInspector reads back:
// RIFF(12) + "fmt "(24) + "data"(8) = 44 bytes. A format fact (not a tunable), centralized
// so the writer and the reader share one definition instead of each hardcoding 44.
internal const val WAV_HEADER_BYTES = 44

// ============ Tuning (UX/product policy; device-agnostic) ============
// Display/monitoring cadences and behavior timeouts. These are product decisions, not
// hardware facts — they don't belong to a device profile. Display throttles are a bridge
// concern only; storage sinks always record the full native rate.
internal object Tuning {
  const val AUDIO_LEVEL_EMIT_NS = 100_000_000L // 10 Hz level readout
  const val CAMERA_STATUS_EMIT_NS = 500_000_000L // 2 Hz fps/status readout
  const val AUDIO_CLOCKSYNC_NS = 1_000_000_000L // 1 Hz WAV clock-sync sample (HAL drift)
  const val ORIGIN_TIMEOUT_MS = 5_000L
  const val FLOOR_TIMEOUT_MS = 2_000L
}
