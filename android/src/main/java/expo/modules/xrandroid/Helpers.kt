package expo.modules.xrandroid

import android.hardware.camera2.CameraCharacteristics
import android.util.Size

// Small shared helpers for both `diagnostics/` and `recording/` (in the root package so neither
// sub-package depends on the other): bridge labels (native enum/const → stable JS token) and
// unit conversions. NOTE: rounding is a DISPLAY concern and lives in TS (.toFixed) — these
// conversions emit FULL PRECISION.

// ============ Bridge labels (native enum/const → stable JS token) ============

internal fun lensFacingLabel(lensFacing: Int?): String = when (lensFacing) {
  CameraCharacteristics.LENS_FACING_FRONT -> "front"
  CameraCharacteristics.LENS_FACING_BACK -> "back"
  CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
  else -> "unknown"
}

internal fun sizeLabel(size: Size?): String? = size?.let { "${it.width}x${it.height}" }

// Clock domain a Camera2 device stamps SENSOR_TIMESTAMP in. REALTIME → the boottime clock
// (SystemClock.elapsedRealtimeNanos), comparable across subsystems; UNKNOWN → an unspecified
// monotonic base, comparable only to itself.
internal fun timestampSourceLabel(source: Int?): String = when (source) {
  CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
  else -> "UNKNOWN"
}

// A camera stream is capture-accurate only when its sensor timestamps are on the boottime
// clock (timestampSource == REALTIME); otherwise we can only delivery-stamp it, the same
// tier as the perception/audio streams. See README → Time-sync model.
internal fun cameraFidelity(timestampSource: String?): String =
  if (timestampSource == "REALTIME") "capture-accurate" else "delivery-stamped"

// ============ Unit conversions (derived numbers for the bridge) ============

internal fun megapixels(width: Int, height: Int): Double =
  width.toLong() * height.toLong() / 1_000_000.0

// Frames-per-second from a per-frame duration in nanoseconds; null when duration ≤ 0.
internal fun fpsFromFrameDurationNs(ns: Long): Double? =
  if (ns > 0) 1_000_000_000.0 / ns else null

internal fun bitrateMbps(sizeBytes: Long, durationS: Double): Double =
  if (durationS > 0) sizeBytes * 8 / durationS / 1_000_000.0 else 0.0

internal fun nanosToMillis(ns: Long): Double = ns / 1e6

internal fun nanosToSeconds(ns: Long): Double = ns / 1e9

internal fun microsToSeconds(us: Long): Double = us / 1e6
