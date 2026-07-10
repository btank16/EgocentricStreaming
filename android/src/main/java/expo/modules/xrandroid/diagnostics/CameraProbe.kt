package expo.modules.xrandroid.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.os.Build
import android.util.Log
import android.util.Size
import android.util.SizeF
import expo.modules.xrandroid.Provenance
import expo.modules.xrandroid.Resolved
import expo.modules.xrandroid.cameraFidelity
import expo.modules.xrandroid.fpsFromFrameDurationNs
import expo.modules.xrandroid.lensFacingLabel
import expo.modules.xrandroid.megapixels
import expo.modules.xrandroid.sizeLabel
import expo.modules.xrandroid.timestampSourceLabel

// The single camera endpoint probe. Side-effect-free (reads CameraCharacteristics, never
// opens a device; a permission-gated key reads null until CAMERA is granted). describe*()
// feeds the UI; resolve*() returns the runtime decisions (probe first, profile fallback).
// See README → Camera calibration probe.
internal object CameraProbe {

  private const val TAG = "XrCameraProbe"
  private const val CAPTURE_TAG = "XrCaptureProbe"
  private const val NS_PER_SEC = 1_000_000_000.0
  private const val FRAME_NS_30 = (NS_PER_SEC / 30.0).toLong()
  private const val FRAME_NS_60 = (NS_PER_SEC / 60.0).toLong()

  private fun cameraManager(ctx: Context?): CameraManager? =
    ctx?.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

  private fun isWorldFacing(id: String, lensFacing: Int?): Boolean =
    id == "0" || lensFacing == CameraCharacteristics.LENS_FACING_BACK

  // ======================================================================
  // Calibration dump (was CameraProbe.build → probeCameras)
  // ======================================================================
  //
  // The one test for whether the headset hands us FACTORY intrinsics/extrinsics for
  // egocentric capture, or whether we must self-calibrate. Two gotchas it encodes:
  //   1. The LENS_* calibration keys are in getKeysNeedingPermission() on Android 10+ —
  //      they read back null until CAMERA is granted; cameraPermissionGranted is surfaced
  //      so a permission-induced null isn't misread as "device has no calibration".
  //   2. LENS_POSE_REFERENCE is NOT a head frame (PRIMARY_CAMERA/GYROSCOPE/UNDEFINED/
  //      AUTOMOTIVE); a camera→head extrinsic still has to be derived by us.

  fun describeCalibration(ctx: Context?): Map<String, Any?> {
    val manager = cameraManager(ctx)
    val permissionGranted =
      ctx?.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val cameras = calibrationCameras(manager, permissionGranted)
    val result = mapOf(
      "cameraPermissionGranted" to permissionGranted,
      "cameras" to cameras,
    )
    logCalibration(permissionGranted, cameras)
    return result
  }

  private fun calibrationCameras(
    manager: CameraManager?,
    permissionGranted: Boolean,
  ): List<Map<String, Any?>> {
    if (manager == null) return emptyList()
    return try {
      manager.cameraIdList.map { id -> calibrationCamera(manager, id, permissionGranted) }
    } catch (e: Exception) {
      Log.w(TAG, "camera enumeration failed: ${e.message}")
      emptyList()
    }
  }

  private fun calibrationCamera(
    manager: CameraManager,
    id: String,
    permissionGranted: Boolean,
  ): Map<String, Any?> {
    val chars = manager.getCameraCharacteristics(id)
    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
    val depthOutput = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
    val logicalMultiCamera =
      caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

    val intrinsics = floats(chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION))
    val distortion = floats(chars.get(CameraCharacteristics.LENS_DISTORTION))
    @Suppress("DEPRECATION") // read the deprecated key only as a fallback, for older HALs
    val radialDistortion = floats(chars.get(CameraCharacteristics.LENS_RADIAL_DISTORTION))
    val poseTranslation = floats(chars.get(CameraCharacteristics.LENS_POSE_TRANSLATION))
    val poseRotation = floats(chars.get(CameraCharacteristics.LENS_POSE_ROTATION))
    val poseReference = poseReferenceLabel(chars.get(CameraCharacteristics.LENS_POSE_REFERENCE))

    return mapOf(
      "id" to id,
      "lensFacing" to lensFacingLabel(chars.get(CameraCharacteristics.LENS_FACING)),
      "capabilities" to caps.map { capabilityLabel(it) },
      "depthOutput" to depthOutput,
      "logicalMultiCamera" to logicalMultiCamera,
      "calibrationGuaranteed" to (depthOutput || logicalMultiCamera),

      // Intrinsics: [f_x, f_y, c_x, c_y, s] in PRE-CORRECTION active-array pixels.
      "intrinsics" to intrinsics,
      // Distortion: Brown-Conrady [k1, k2, k3, k4, k5] (3 radial + 2 tangential).
      "distortion" to distortion,
      // Deprecated 6-element predecessor; only meaningful if `distortion` is null.
      "radialDistortionDeprecated" to radialDistortion,

      // Extrinsic of the optical center relative to `poseReference` (NOT the head).
      "poseTranslation" to poseTranslation,
      "poseRotation" to poseRotation,
      "poseReference" to poseReference,

      "preCorrectionActiveArraySize" to rect(
        chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE),
      ),
      "activeArraySize" to rect(chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)),
      "physicalSizeMm" to sizeF(chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)),
      "outputSizes" to yuvOutputSizes(chars, 6),

      "hasIntrinsics" to (intrinsics != null),
      "hasDistortion" to (distortion != null || radialDistortion != null),
      "hasExtrinsics" to (poseTranslation != null && poseRotation != null),
      "calibrationKeysReadable" to permissionGranted,
    )
  }

  // ======================================================================
  // Capture envelope (was CameraCaptureProbe.build → probeCaptureSizes)
  // ======================================================================
  //
  // Per world-facing camera: advertised vs sustained (stall-included) fps for each output
  // path — JPEG (ISP), YUV (software encode), PRIVATE/MediaCodec (encoder Surface) — plus the
  // sensor's AE fps ranges. Concurrent cam0+cam2 sustainability needs a live open (stereoCaveat).

  fun describeCaptureEnvelope(ctx: Context?): Map<String, Any?> {
    val manager = cameraManager(ctx)
    val cameras = envelopeCameras(manager)
    val result = mapOf(
      "stereoCaveat" to
        "Per-camera advertised ceiling. Concurrent cam0+cam2 sustainability needs a live stereo open.",
      "cameras" to cameras,
    )
    logCaptureEnvelope(cameras)
    return result
  }

  private fun envelopeCameras(manager: CameraManager?): List<Map<String, Any?>> {
    if (manager == null) return emptyList()
    return try {
      manager.cameraIdList.mapNotNull { id -> envelopeCamera(manager, id) }
    } catch (e: Exception) {
      Log.w(CAPTURE_TAG, "camera enumeration failed: ${e.message}")
      emptyList()
    }
  }

  private fun envelopeCamera(manager: CameraManager, id: String): Map<String, Any?>? {
    val chars = manager.getCameraCharacteristics(id)
    val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
    if (!isWorldFacing(id, lensFacing)) return null

    val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val formats = listOf(
      imageFormatLadder("JPEG", ImageFormat.JPEG, configMap),
      imageFormatLadder("YUV_420_888", ImageFormat.YUV_420_888, configMap),
      privateLadder(configMap),
    )

    return mapOf(
      "id" to id,
      "lensFacing" to lensFacingLabel(lensFacing),
      "worldFacing" to true,
      "hardwareLevel" to hardwareLevelLabel(
        chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
      ),
      "nativeMaxSize" to sizeLabel(largestJpegYuvSize(configMap)),
      "aeFpsRanges" to aeFpsRangeLabels(chars),
      "formats" to formats,
    )
  }

  private fun imageFormatLadder(label: String, format: Int, map: StreamConfigurationMap?): Map<String, Any?> {
    if (map == null) return unsupportedLadder(label)
    val sizes = map.getOutputSizes(format)?.toList().orEmpty()
    return buildLadder(
      label,
      sizes,
      minDur = { map.getOutputMinFrameDuration(format, it) },
      stall = { map.getOutputStallDuration(format, it) },
    )
  }

  private fun privateLadder(map: StreamConfigurationMap?): Map<String, Any?> {
    val label = "PRIVATE(MediaCodec)"
    if (map == null) return unsupportedLadder(label)
    val sizes = try {
      map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
    } catch (e: Exception) {
      emptyList()
    }
    return buildLadder(
      label,
      sizes,
      minDur = { map.getOutputMinFrameDuration(MediaCodec::class.java, it) },
      stall = { runCatching { map.getOutputStallDuration(MediaCodec::class.java, it) }.getOrDefault(0L) },
    )
  }

  private fun buildLadder(
    label: String,
    sizes: List<Size>,
    minDur: (Size) -> Long,
    stall: (Size) -> Long,
  ): Map<String, Any?> {
    if (sizes.isEmpty()) return unsupportedLadder(label)
    val ordered = sizes
      .distinctBy { it.width to it.height }
      .sortedByDescending { it.width.toLong() * it.height.toLong() }
    val ladder = ordered.map { s -> sizeInfo(s, minDur(s), stall(s)) }
    return mapOf(
      "format" to label,
      "supported" to true,
      "maxSizeAt30Fps" to sizeLabel(largestSizeAtFps(ordered, minDur, stall, FRAME_NS_30)),
      "maxSizeAt60Fps" to sizeLabel(largestSizeAtFps(ordered, minDur, stall, FRAME_NS_60)),
      "sizes" to ladder,
    )
  }

  private fun sizeInfo(size: Size, minFrame: Long, stall: Long): Map<String, Any?> {
    val sustainedFrame = minFrame + stall
    return mapOf(
      "size" to sizeLabel(size),
      "width" to size.width,
      "height" to size.height,
      "megapixels" to megapixels(size.width, size.height),
      "minFrameDurationNs" to minFrame,
      "stallDurationNs" to stall,
      "maxFps" to fpsFromFrameDurationNs(minFrame),
      "sustainedFps" to fpsFromFrameDurationNs(sustainedFrame),
    )
  }

  private fun largestSizeAtFps(
    orderedDesc: List<Size>,
    minDur: (Size) -> Long,
    stall: (Size) -> Long,
    frameBudgetNs: Long,
  ): Size? = orderedDesc.firstOrNull { (minDur(it) + stall(it)) in 1..frameBudgetNs }

  private fun unsupportedLadder(label: String): Map<String, Any?> =
    mapOf("format" to label, "supported" to false, "sizes" to emptyList<Any>())

  // ======================================================================
  // Inventory (was DeviceCapabilities camera section, for getDeviceCapabilities)
  // ======================================================================

  fun describeInventory(manager: CameraManager?): Map<String, Any?> {
    val combos = concurrentCameraCombos(manager)
    return mapOf(
      "cameras" to inventoryCameras(manager),
      "concurrentCameraCombos" to combos,
      "worldStereoConcurrent" to combos.any { it.containsAll(listOf("0", "2")) },
    )
  }

  private fun inventoryCameras(manager: CameraManager?): List<Map<String, Any?>> {
    if (manager == null) return emptyList()
    return try {
      manager.cameraIdList.map { id ->
        val chars = manager.getCameraCharacteristics(id)
        val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
        val info = mutableMapOf<String, Any?>(
          "id" to id,
          "lensFacing" to lensFacingLabel(lensFacing),
          "timestampSource" to timestampSourceLabel(
            chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
          ),
          "isLogicalMultiCamera" to isLogicalMultiCamera(chars),
          "physicalCameraIds" to physicalCameraIds(chars),
        )
        if (isWorldFacing(id, lensFacing)) {
          info["worldFacing"] = true
          info["sampleOutputSizes"] = yuvOutputSizes(chars, 4)
        }
        info
      }
    } catch (e: Exception) {
      emptyList()
    }
  }

  private fun isLogicalMultiCamera(chars: CameraCharacteristics): Boolean {
    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
    return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
  }

  private fun physicalCameraIds(chars: CameraCharacteristics): List<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
    return try {
      chars.physicalCameraIds.sorted()
    } catch (e: Exception) {
      emptyList()
    }
  }

  private fun concurrentCameraCombos(manager: CameraManager?): List<List<String>> {
    if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
    return try {
      manager.concurrentCameraIds.map { combo -> combo.sorted() }
    } catch (e: Exception) {
      emptyList()
    }
  }

  // ======================================================================
  // resolve() — the decisions the recorder consumes (probe first, profile fallback)
  // ======================================================================

  // Camera-supported record size for the encoder Surface: the requested size if the
  // camera advertises it, else the largest (native sensor) size; the profile's request
  // is the fallback when the query fails. Provenance: DETECTED when the size came from the
  // camera's advertised MediaCodec output sizes, FALLBACK when it didn't.
  fun resolveRecordSize(ctx: Context?, deviceId: String, requested: Size?): Resolved<Size?> {
    val manager = cameraManager(ctx) ?: return Resolved(requested, Provenance.FALLBACK)
    return try {
      val chars = manager.getCameraCharacteristics(deviceId)
      val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?: return Resolved(requested, Provenance.FALLBACK)
      val sizes = map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
      if (sizes.isEmpty()) return Resolved(requested, Provenance.FALLBACK)
      if (requested != null && sizes.any { it.width == requested.width && it.height == requested.height }) {
        Resolved(requested, Provenance.DETECTED)
      } else {
        Resolved(sizes.maxByOrNull { it.width.toLong() * it.height }, Provenance.DETECTED)
      }
    } catch (e: Exception) {
      Resolved(requested, Provenance.FALLBACK)
    }
  }

  // Capture fps DERIVED from the sensor's advertised max (CONTROL_AE_AVAILABLE_TARGET_FPS_
  // RANGES), capped at `cap` (the profile's maxCaptureFps). CLAMPED when the sensor max
  // exceeds the cap (policy), DETECTED when the sensor value stands, FALLBACK when the query
  // fails. The encoder never drives this — see EncoderProbe.
  fun resolveFps(ctx: Context?, deviceId: String, cap: Int): Resolved<Int> {
    val manager = cameraManager(ctx) ?: return Resolved(cap, Provenance.FALLBACK)
    return try {
      val chars = manager.getCameraCharacteristics(deviceId)
      val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
      val sensorMax = ranges?.maxOfOrNull { it.upper }
        ?: return Resolved(cap, Provenance.FALLBACK)
      if (sensorMax > cap) Resolved(cap, Provenance.CLAMPED) else Resolved(sensorMax, Provenance.DETECTED)
    } catch (e: Exception) {
      Resolved(cap, Provenance.FALLBACK)
    }
  }

  // A supported AE target-fps range that holds the camera at `fps` — a fixed [fps,fps] if
  // advertised, else the highest range with upper ≤ fps. null when none advertised.
  fun resolveFpsRange(ctx: Context?, deviceId: String, fps: Int): android.util.Range<Int>? {
    val manager = cameraManager(ctx) ?: return null
    return try {
      val chars = manager.getCameraCharacteristics(deviceId)
      val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
      ranges.firstOrNull { it.lower == fps && it.upper == fps }
        ?: ranges.filter { it.upper <= fps }.maxByOrNull { it.upper }
        ?: ranges.minByOrNull { it.upper }
    } catch (e: Exception) {
      null
    }
  }

  // The camera's clock domain ("REALTIME"/"UNKNOWN") + derived fidelity tier — a world
  // camera is capture-accurate ONLY when its SENSOR_TIMESTAMP is REALTIME (boottime).
  // Detected, not assumed; null id/lookup failure → UNKNOWN/delivery-stamped.
  fun resolveTimestamp(ctx: Context?, deviceId: String): Pair<String, String> {
    val manager = cameraManager(ctx)
    val source = try {
      manager?.getCameraCharacteristics(deviceId)
        ?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        ?.let { timestampSourceLabel(it) } ?: "UNKNOWN"
    } catch (e: Exception) {
      "UNKNOWN"
    }
    return source to cameraFidelity(source)
  }

  // ======================================================================
  // Shared value coercion / label maps
  // ======================================================================

  private fun floats(arr: FloatArray?): List<Float>? = arr?.toList()

  private fun rect(r: Rect?): String? = r?.let { "${it.width()}x${it.height()}" }

  private fun sizeF(s: SizeF?): String? = s?.let { "${it.width}x${it.height}" }

  private fun yuvOutputSizes(chars: CameraCharacteristics, take: Int): List<String> {
    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
    return map.getOutputSizes(ImageFormat.YUV_420_888)
      ?.take(take)
      ?.map { "${it.width}x${it.height}" }
      ?: emptyList()
  }

  private fun largestJpegYuvSize(configMap: StreamConfigurationMap?): Size? {
    val map = configMap ?: return null
    return listOf(ImageFormat.JPEG, ImageFormat.YUV_420_888)
      .flatMap { format -> map.getOutputSizes(format)?.toList().orEmpty() }
      .maxByOrNull { it.width.toLong() * it.height.toLong() }
  }

  private fun aeFpsRangeLabels(chars: CameraCharacteristics): List<String> {
    val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
      ?: return emptyList()
    return ranges.map { "${it.lower}-${it.upper}" }.distinct()
  }

  private fun hardwareLevelLabel(level: Int?): String = when (level) {
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    else -> "unknown"
  }

  private fun capabilityLabel(cap: Int): String = when (cap) {
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
    else -> "CAP_$cap"
  }

  private fun poseReferenceLabel(ref: Int?): String? = when (ref) {
    null -> null
    CameraCharacteristics.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> "PRIMARY_CAMERA"
    CameraCharacteristics.LENS_POSE_REFERENCE_GYROSCOPE -> "GYROSCOPE"
    CameraCharacteristics.LENS_POSE_REFERENCE_UNDEFINED -> "UNDEFINED"
    CameraCharacteristics.LENS_POSE_REFERENCE_AUTOMOTIVE -> "AUTOMOTIVE"
    else -> "REF_$ref"
  }

  // ======================================================================
  // logcat summaries (mirror the JS payloads; readable off `adb logcat`)
  // ======================================================================

  private fun logCalibration(permissionGranted: Boolean, cameras: List<Map<String, Any?>>) {
    Log.i(TAG, "=== Camera2 calibration probe (CAMERA granted=$permissionGranted) ===")
    if (!permissionGranted) {
      Log.w(TAG, "CAMERA not granted — LENS_* calibration keys read back null; grant + re-run.")
    }
    if (cameras.isEmpty()) {
      Log.i(TAG, "no cameras enumerated")
      return
    }
    cameras.forEach { c ->
      Log.i(
        TAG,
        "cam ${c["id"]} (${c["lensFacing"]}): " +
          "calibrationGuaranteed=${c["calibrationGuaranteed"]} " +
          "[depthOutput=${c["depthOutput"]} logicalMulti=${c["logicalMultiCamera"]}] " +
          "intrinsics=${c["intrinsics"] ?: "null"} " +
          "distortion=${c["distortion"] ?: c["radialDistortionDeprecated"] ?: "null"} " +
          "poseT=${c["poseTranslation"] ?: "null"} " +
          "poseR=${c["poseRotation"] ?: "null"} " +
          "poseRef=${c["poseReference"] ?: "null"} " +
          "intrinsicsFrame=${c["preCorrectionActiveArraySize"] ?: "?"}",
      )
    }
  }

  private fun logCaptureEnvelope(cameras: List<Map<String, Any?>>) {
    Log.i(CAPTURE_TAG, "=== Camera2 capture-size probe ===")
    if (cameras.isEmpty()) {
      Log.i(CAPTURE_TAG, "no world-facing cameras enumerated")
      return
    }
    cameras.forEach { c ->
      Log.i(
        CAPTURE_TAG,
        "cam ${c["id"]} (${c["lensFacing"]}, ${c["hardwareLevel"]}) " +
          "native=${c["nativeMaxSize"]} aeFps=${c["aeFpsRanges"]}",
      )
      @Suppress("UNCHECKED_CAST")
      val formats = c["formats"] as? List<Map<String, Any?>> ?: emptyList()
      formats.forEach { f ->
        if (f["supported"] == true) {
          Log.i(
            CAPTURE_TAG,
            "  ${f["format"]}: max@30fps=${f["maxSizeAt30Fps"] ?: "none"} " +
              "max@60fps=${f["maxSizeAt60Fps"] ?: "none"}",
          )
        } else {
          Log.i(CAPTURE_TAG, "  ${f["format"]}: unsupported")
        }
      }
    }
  }
}
