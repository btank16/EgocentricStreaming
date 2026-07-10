package expo.modules.xrandroid.diagnostics

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import android.util.Size
import expo.modules.xrandroid.ACTIVE_PROFILE
import expo.modules.xrandroid.DEFAULT_PROFILE
import expo.modules.xrandroid.deviceKey
import org.json.JSONObject
import java.io.File

// App-assisted device-profile export: probes this hardware (audio format, camera sensor
// limits, encoder ceiling) and writes a JSON dump that scripts/add-device-profile.mjs turns
// into a Constants.kt → PROFILES stanza keyed by deviceKey(). Side-effect-free — static
// queries only, no mic/camera opened. See README → Configuration.
internal object ProfileDraft {

  private const val TAG = "XrProfileDraft"
  const val FILE_NAME = "profile-draft.json"

  // Candidate capture formats, best-first. getMinBufferSize returning a positive value means
  // the device supports that (rate, channel, encoding) for input. We suggest the first
  // supported — 48 kHz mono 16-bit is the Galaxy XR baseline and the general XR default.
  private val AUDIO_CANDIDATES = listOf(
    Triple(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
    Triple(44_100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
    Triple(48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT),
    Triple(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
  )

  // Assemble the draft. `identity` locates the device (deviceKey is the PROFILES map key);
  // `probes` is the raw evidence for a human to eyeball; `suggestedProfile` is the
  // generator's direct input (maps 1:1 onto DeviceProfile fields).
  fun build(ctx: Context?): Map<String, Any?> {
    val audio = probeAudio()
    val cameras = probeCameras(ctx)
    val world = cameras.filter { it["worldFacing"] == true }
    // Sensor fps: the max advertised across world cameras (the runtime caps per-camera at
    // maxCaptureFps; here we surface the ceiling so a human sets the cap knowingly).
    val sensorMaxFps = world.mapNotNull { it["advertisedMaxFps"] as? Int }.maxOrNull()
    // Native record size for the encoder probe: the largest world-camera record size.
    val recordSize = world.mapNotNull { it["nativeRecordSize"] as? String }
      .mapNotNull { parseSize(it) }
      .maxByOrNull { it.width.toLong() * it.height }
    val encoder = probeEncoder(recordSize, sensorMaxFps ?: DEFAULT_PROFILE.maxCaptureFps)

    return mapOf(
      "deviceKey" to deviceKey(),
      "identity" to identity(),
      "probes" to mapOf(
        "audio" to audio,
        "cameras" to cameras,
        "encoder" to encoder,
      ),
      "suggestedProfile" to suggestedProfile(audio, sensorMaxFps),
      "activeProfileIsDefault" to (ACTIVE_PROFILE === DEFAULT_PROFILE),
    )
  }

  // Write the draft to the app-private external dir (same place takes live) and return its
  // path + deviceKey. The dev pulls it with `adb pull <path>` and feeds it to the generator.
  fun export(ctx: Context?): Map<String, Any?> {
    val base = ctx?.getExternalFilesDir(null) ?: return mapOf("status" to "no_storage")
    val draft = build(ctx)
    val file = File(base, FILE_NAME)
    return try {
      file.writeText(JSONObject(draft).toString(2))
      mapOf("status" to "ok", "path" to file.absolutePath, "deviceKey" to deviceKey())
    } catch (e: Exception) {
      Log.w(TAG, "profile-draft export failed: ${e.message}")
      mapOf("status" to "error", "message" to (e.message ?: e.toString()))
    }
  }

  // --- identity ------------------------------------------------------------

  private fun identity(): Map<String, Any?> = mapOf(
    "manufacturer" to Build.MANUFACTURER,
    "model" to Build.MODEL,
    "device" to Build.DEVICE,
    "brand" to Build.BRAND,
    "product" to Build.PRODUCT,
    "androidRelease" to Build.VERSION.RELEASE,
    "sdkInt" to Build.VERSION.SDK_INT,
  )

  // --- audio ---------------------------------------------------------------

  private fun probeAudio(): Map<String, Any?> {
    val supported = AUDIO_CANDIDATES.mapNotNull { (rate, channel, encoding) ->
      val min = runCatching { AudioRecord.getMinBufferSize(rate, channel, encoding) }.getOrNull()
      if (min != null && min > 0) {
        mapOf(
          "sampleRate" to rate,
          "channel" to channelSymbol(channel),
          "encoding" to encodingSymbol(encoding),
          "minBufferBytes" to min,
        )
      } else {
        null
      }
    }
    return mapOf(
      "supportedFormats" to supported,
      // The chosen primary is the first supported candidate (best-first order).
      "suggested" to supported.firstOrNull(),
    )
  }

  // --- cameras -------------------------------------------------------------

  private fun probeCameras(ctx: Context?): List<Map<String, Any?>> {
    val manager = ctx?.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
      ?: return emptyList()
    return try {
      manager.cameraIdList.map { id -> probeCamera(ctx, manager, id) }
    } catch (e: Exception) {
      Log.w(TAG, "camera enumeration failed: ${e.message}")
      emptyList()
    }
  }

  private fun probeCamera(ctx: Context?, manager: CameraManager, id: String): Map<String, Any?> {
    val facing = runCatching {
      manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
    }.getOrNull()
    // World/back-facing is what egocentric capture records; front is a user/selfie sensor.
    val worldFacing = facing == CameraCharacteristics.LENS_FACING_BACK
    val nativeSize = CameraProbe.resolveRecordSize(ctx, id, null).value
    // A very high cap makes resolveFps return the sensor's advertised max (never CLAMPED).
    val fps = CameraProbe.resolveFps(ctx, id, Int.MAX_VALUE)
    val (tsSource, fidelity) = CameraProbe.resolveTimestamp(ctx, id)
    return mapOf(
      "id" to id,
      "lensFacing" to facingLabel(facing),
      "worldFacing" to worldFacing,
      "nativeRecordSize" to nativeSize?.let { "${it.width}x${it.height}" },
      "advertisedMaxFps" to fps.value,
      "advertisedMaxFpsProvenance" to fps.label(),
      "timestampSource" to tsSource,
      "fidelity" to fidelity,
    )
  }

  // --- encoder -------------------------------------------------------------

  private fun probeEncoder(recordSize: Size?, fps: Int): Map<String, Any?> {
    val size = recordSize ?: return mapOf("status" to "no_record_size")
    // Request an absurd bitrate so resolve() reveals the codec's advertised ceiling
    // (clamped-to-ceiling), and which HW HEVC codec it picks for this size+fps.
    val enc = EncoderProbe.resolve(size, fps, Int.MAX_VALUE, 1.0)
    return mapOf(
      "size" to "${size.width}x${size.height}",
      "fps" to fps,
      "codecName" to enc.codecName,
      "codecProvenance" to enc.codecProvenance.name.lowercase(),
      // With an infinite request, this bitRate IS the codec's ceiling for (size, fps).
      "bitrateCeiling" to enc.bitRate,
      "bitrateCeilingProvenance" to enc.bitRateProvenance.name.lowercase(),
    )
  }

  // --- suggested profile (generator input) ---------------------------------

  // The DeviceProfile-shaped suggestion. Probed values (audio format, maxCaptureFps) are
  // filled from this hardware; the subjective quality knobs (preset bitrates, efficient
  // size, previewFallbackSize) are copied from the Galaxy XR baseline and flagged to tune.
  private fun suggestedProfile(audio: Map<String, Any?>, sensorMaxFps: Int?): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val a = audio["suggested"] as? Map<String, Any?>
    val d = DEFAULT_PROFILE
    return mapOf(
      "sampleRate" to (a?.get("sampleRate") ?: d.sampleRate),
      "audioChannel" to (a?.get("channel") ?: channelSymbol(d.audioChannel)),
      "audioEncoding" to (a?.get("encoding") ?: encodingSymbol(d.audioEncoding)),
      "framesPerRead" to d.framesPerRead,
      "maxCaptureFps" to (sensorMaxFps ?: d.maxCaptureFps),
      "previewFallbackSize" to "${d.previewFallbackSize.width}x${d.previewFallbackSize.height}",
      "cameraPresets" to d.cameraPresets.mapValues { (_, p) ->
        mapOf(
          "size" to p.size?.let { "${it.width}x${it.height}" },
          "bitRate" to p.bitRate,
        )
      },
      "defaultPreset" to d.defaultPreset,
      "notes" to listOf(
        "sampleRate/audioChannel/audioEncoding + maxCaptureFps are PROBED from this device.",
        "preset bitRates, the efficient size, and previewFallbackSize are copied from the " +
          "Galaxy XR baseline — tune from on-device measurement.",
      ),
    )
  }

  // --- labels / coercion ---------------------------------------------------

  // Kotlin symbol strings the generator emits verbatim into the DeviceProfile stanza.
  private fun channelSymbol(channel: Int): String = when (channel) {
    AudioFormat.CHANNEL_IN_MONO -> "CHANNEL_IN_MONO"
    AudioFormat.CHANNEL_IN_STEREO -> "CHANNEL_IN_STEREO"
    else -> "CHANNEL_IN_MONO"
  }

  private fun encodingSymbol(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> "ENCODING_PCM_8BIT"
    AudioFormat.ENCODING_PCM_16BIT -> "ENCODING_PCM_16BIT"
    AudioFormat.ENCODING_PCM_FLOAT -> "ENCODING_PCM_FLOAT"
    else -> "ENCODING_PCM_16BIT"
  }

  private fun facingLabel(facing: Int?): String = when (facing) {
    CameraCharacteristics.LENS_FACING_FRONT -> "front"
    CameraCharacteristics.LENS_FACING_BACK -> "back"
    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
    else -> "unknown"
  }

  private fun parseSize(label: String): Size? = runCatching {
    val (w, h) = label.split("x").map { it.trim().toInt() }
    Size(w, h)
  }.getOrNull()
}
