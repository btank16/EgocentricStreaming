package expo.modules.xrandroid.diagnostics

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.util.Size
import expo.modules.xrandroid.Provenance
import expo.modules.xrandroid.Resolved
import expo.modules.xrandroid.megapixels
import expo.modules.xrandroid.sizeLabel

// The video-encoder endpoint probe. describe() is the side-effect-free MediaCodecList dump.
// resolve() picks a hardware codec that fits the size, sustains the CAMERA-derived fps (the
// encoder's own achievable fps is far higher and must never drive sampling — Galaxy XR is
// sensor-bound at 24) with ≥2 instances for stereo, then clamps bitrate to the codec ceiling × qualityScale.
internal object EncoderProbe {

  private const val TAG = "XrEncoderProbe"

  private val MIMES = listOf(
    MediaFormat.MIMETYPE_VIDEO_HEVC to "HEVC",
    MediaFormat.MIMETYPE_VIDEO_AVC to "H264",
  )

  // The capture sizes under consideration (square world-cam res + 16:9/4:3 fallbacks).
  private val CANDIDATES = listOf(
    Size(3000, 3000),
    Size(2400, 2400),
    Size(1920, 1440),
    Size(1920, 1080),
    Size(1500, 1500),
  )

  // Stereo wants two encoder instances of the same codec at once.
  private const val STEREO_INSTANCES = 2

  // A chosen encoder for a specific (size, fps): the codec name to instantiate by, the
  // clamped/scaled bitrate, and where each came from.
  data class EncoderResolved(
    val codecName: String?,
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitRate: Int,
    val bitRateProvenance: Provenance,
    val codecProvenance: Provenance,
  )

  // ======================================================================
  // describe() (was VideoEncoderProbe.build → probeVideoEncoders) — shape unchanged
  // ======================================================================

  fun describe(): Map<String, Any?> {
    val encoders = enumerate()
    val result = mapOf("encoders" to encoders)
    logSummary(encoders)
    return result
  }

  private fun enumerate(): List<Map<String, Any?>> {
    return try {
      val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
      val out = ArrayList<Map<String, Any?>>()
      for (info in codecs) {
        if (!info.isEncoder) continue
        for ((mime, label) in MIMES) {
          if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
          probeEncoder(info, mime, label)?.let { out.add(it) }
        }
      }
      out
    } catch (e: Exception) {
      Log.w(TAG, "encoder enumeration failed: ${e.message}")
      emptyList()
    }
  }

  private fun probeEncoder(info: MediaCodecInfo, mime: String, label: String): Map<String, Any?>? {
    return try {
      val caps = info.getCapabilitiesForType(mime)
      val vc = caps.videoCapabilities ?: return null
      mapOf(
        "name" to info.name,
        "mime" to label,
        "hardwareAccelerated" to isHardware(info),
        "maxInstances" to caps.maxSupportedInstances,
        "supportedWidths" to rangeLabel(vc.supportedWidths.lower, vc.supportedWidths.upper),
        "supportedHeights" to rangeLabel(vc.supportedHeights.lower, vc.supportedHeights.upper),
        "candidates" to CANDIDATES.map { candidate(vc, it) },
      )
    } catch (e: Exception) {
      Log.w(TAG, "probe of ${info.name}/$label failed: ${e.message}")
      null
    }
  }

  private fun candidate(vc: MediaCodecInfo.VideoCapabilities, size: Size): Map<String, Any?> {
    val w = size.width
    val h = size.height
    val supported = runCatching { vc.isSizeSupported(w, h) }.getOrDefault(false)
    if (!supported) {
      return mapOf("size" to sizeLabel(size), "sizeSupported" to false)
    }
    val supportedFpsMax = runCatching { vc.getSupportedFrameRatesFor(w, h).upper }.getOrNull()
    val achievableFpsMax = runCatching { vc.getAchievableFrameRatesFor(w, h)?.upper }.getOrNull()
    return mapOf(
      "size" to sizeLabel(size),
      "sizeSupported" to true,
      "megapixels" to megapixels(w, h),
      "supportedFpsMax" to supportedFpsMax,
      "achievableFpsMax" to achievableFpsMax,
      "at24fps" to rateSupported(vc, w, h, 24.0),
      "at30fps" to rateSupported(vc, w, h, 30.0),
    )
  }

  // ======================================================================
  // resolve() — the decision the recorder consumes
  // ======================================================================

  // Choose the hardware encoder for (size, fps) and clamp the requested bitrate.
  //   codec:   prefer a hardware codec that supports the size, sustains fps, and has
  //            ≥ STEREO_INSTANCES concurrent instances; else the first that fits; else null.
  //   bitrate: min(requestedBitRate, codec bitrate ceiling) × qualityScale (0..1].
  // A null codecName means the caller falls back to createEncoderByType (Provenance.FALLBACK).
  fun resolve(size: Size, fps: Int, requestedBitRate: Int, qualityScale: Double): EncoderResolved {
    val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
    val scale = qualityScale.coerceIn(0.0, 1.0)
    val fits = fittingEncoders(mime, size, fps)
    // Prefer hw + enough instances for stereo; fall back progressively.
    val chosen = fits.firstOrNull { it.hardware && it.maxInstances >= STEREO_INSTANCES }
      ?: fits.firstOrNull { it.hardware }
      ?: fits.firstOrNull()

    if (chosen == null) {
      // No codec advertised the size/fps — leave selection to the platform, clamp nothing.
      val bitRate = (requestedBitRate * scale).toInt().coerceAtLeast(1)
      return EncoderResolved(
        codecName = null,
        mime = mime,
        width = size.width,
        height = size.height,
        fps = fps,
        bitRate = bitRate,
        bitRateProvenance = Provenance.FALLBACK,
        codecProvenance = Provenance.FALLBACK,
      )
    }

    val ceiling = chosen.maxBitRate
    val clamped = if (ceiling in 1 until requestedBitRate) ceiling else requestedBitRate
    val bitRate = (clamped * scale).toInt().coerceAtLeast(1)
    val bitRateProvenance =
      if (ceiling in 1 until requestedBitRate) Provenance.CLAMPED else Provenance.DETECTED
    return EncoderResolved(
      codecName = chosen.name,
      mime = mime,
      width = size.width,
      height = size.height,
      fps = fps,
      bitRate = bitRate,
      bitRateProvenance = bitRateProvenance,
      codecProvenance = Provenance.DETECTED,
    )
  }

  private data class EncoderFit(
    val name: String,
    val hardware: Boolean,
    val maxInstances: Int,
    val maxBitRate: Int,
  )

  private fun fittingEncoders(mime: String, size: Size, fps: Int): List<EncoderFit> {
    return try {
      MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
        .mapNotNull { info ->
          val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@mapNotNull null
          val vc = caps.videoCapabilities ?: return@mapNotNull null
          val ok = runCatching { vc.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) }
            .getOrDefault(false)
          if (!ok) return@mapNotNull null
          EncoderFit(
            name = info.name,
            hardware = isHardware(info),
            maxInstances = caps.maxSupportedInstances,
            maxBitRate = runCatching { vc.bitrateRange.upper }.getOrDefault(0),
          )
        }
        // Hardware first, then more instances, then higher bitrate ceiling.
        .sortedWith(
          compareByDescending<EncoderFit> { it.hardware }
            .thenByDescending { it.maxInstances }
            .thenByDescending { it.maxBitRate },
        )
    } catch (e: Exception) {
      Log.w(TAG, "fitting-encoder query failed: ${e.message}")
      emptyList()
    }
  }

  // ======================================================================
  // shared helpers
  // ======================================================================

  private fun rateSupported(
    vc: MediaCodecInfo.VideoCapabilities,
    w: Int,
    h: Int,
    fps: Double,
  ): Boolean = runCatching { vc.areSizeAndRateSupported(w, h, fps) }.getOrDefault(false)

  // isHardwareAccelerated is API 29+; below that fall back to the long-standing name
  // heuristic (software codecs are "OMX.google.*" / "c2.android.*").
  private fun isHardware(info: MediaCodecInfo): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      info.isHardwareAccelerated
    } else {
      val n = info.name
      !(n.startsWith("OMX.google", ignoreCase = true) || n.startsWith("c2.android", ignoreCase = true))
    }

  private fun rangeLabel(lower: Int, upper: Int): String = "$lower-$upper"

  private fun logSummary(encoders: List<Map<String, Any?>>) {
    Log.i(TAG, "=== Video encoder probe ===")
    if (encoders.isEmpty()) {
      Log.i(TAG, "no HEVC/AVC encoders found")
      return
    }
    encoders.forEach { e ->
      Log.i(
        TAG,
        "${e["mime"]} ${e["name"]} hw=${e["hardwareAccelerated"]} " +
          "maxInstances=${e["maxInstances"]} w=${e["supportedWidths"]} h=${e["supportedHeights"]}",
      )
      @Suppress("UNCHECKED_CAST")
      val candidates = e["candidates"] as? List<Map<String, Any?>> ?: emptyList()
      candidates.forEach { c ->
        if (c["sizeSupported"] == true) {
          Log.i(
            TAG,
            "  ${c["size"]}: supFps=${c["supportedFpsMax"] ?: "?"} " +
              "achFps=${c["achievableFpsMax"] ?: "?"} @24=${c["at24fps"]} @30=${c["at30fps"]}",
          )
        } else {
          Log.i(TAG, "  ${c["size"]}: unsupported")
        }
      }
    }
  }
}
