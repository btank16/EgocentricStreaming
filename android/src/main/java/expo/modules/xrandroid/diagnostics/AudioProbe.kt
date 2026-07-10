package expo.modules.xrandroid.diagnostics

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MicrophoneInfo
import android.os.Build
import android.util.Log
import expo.modules.xrandroid.DeviceProfile
import expo.modules.xrandroid.Provenance
import expo.modules.xrandroid.Resolved

// The audio endpoint probe. describeInventory() is the side-effect-free hardware dump
// (AudioManager properties + getMicrophones(); no AudioRecord, no RECORD_AUDIO). resolve*()
// returns the decisions AudioController consumes: the capture format request and the
// HAL-boottime trust check (trustworthy → capture-accurate, else read-time delivery-stamped).
internal object AudioProbe {

  private const val TAG = "XrAudio"

  // How far the HAL's reported boottime capture instant may sit from our read-time boottime
  // before we distrust it. The Galaxy XR HAL reads whole seconds off boottime (it ignores
  // TIMEBASE_BOOTTIME), so its delta is enormous → READ_TIME; a sane HAL is within a buffer.
  private const val HAL_TRUST_THRESHOLD_MS = 250L

  // Stream-level timestamp modes (highest → lowest fidelity).
  const val MODE_HAL_BOOTTIME = "hal-boottime" // HAL capture instant, trustworthy → capture-accurate
  const val MODE_READ_TIME = "read-time" // read-time boottime stamp → delivery-stamped

  data class AudioFormatResolved(
    val sampleRate: Int,
    val audioChannel: Int,
    val audioEncoding: Int,
    val framesPerRead: Int,
    val provenance: Provenance,
  )

  // ======================================================================
  // Inventory (was DeviceCapabilities.buildAudio, for getDeviceCapabilities)
  // ======================================================================

  fun describeInventory(ctx: Context?): Map<String, Any?> {
    val manager = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    return mapOf(
      "outputSampleRate" to manager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE),
      "framesPerBuffer" to manager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER),
      "unprocessedSupported" to
        (manager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"),
      "microphones" to enumerateMicrophones(manager),
    )
  }

  private fun enumerateMicrophones(manager: AudioManager?): List<Map<String, Any?>> {
    // getMicrophones() is API 28+ (the Galaxy XR is well above this).
    if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
    return try {
      manager.microphones.map { mic ->
        mapOf(
          "id" to mic.id,
          "type" to micTypeLabel(mic.type),
          "location" to micLocationLabel(mic.location),
          "directionality" to micDirectionalityLabel(mic.directionality),
          "address" to mic.address,
          "description" to mic.description,
        )
      }
    } catch (e: Exception) {
      emptyList()
    }
  }

  // ======================================================================
  // resolve() — the decisions AudioController consumes
  // ======================================================================

  // The capture format REQUEST. Side-effect-free — the actual granted values are read back
  // from the live AudioRecord at stream start and reported via streamMeta() into the take
  // manifest (the WAV header itself is written from the profile constants). Provenance is
  // FALLBACK here because nothing has been queried yet; the live read-back upgrades it
  // to DETECTED.
  fun resolveFormat(profile: DeviceProfile): AudioFormatResolved =
    AudioFormatResolved(
      sampleRate = profile.sampleRate,
      audioChannel = profile.audioChannel,
      audioEncoding = profile.audioEncoding,
      framesPerRead = profile.framesPerRead,
      provenance = Provenance.FALLBACK,
    )

  // Decide the timestamp mode from a LIVE recorder: if the HAL's boottime AudioTimestamp is
  // within HAL_TRUST_THRESHOLD_MS of our read-time boottime, trust it (capture-accurate);
  // otherwise fall back to read-time (delivery-stamped, same boottime clock as everything
  // else). `readBootNs` is elapsedRealtimeNanos() sampled at the same read(). DETECTED when
  // the HAL clock is usable, FALLBACK when it isn't (the Galaxy XR case).
  fun resolveTimestampMode(rec: AudioRecord, readBootNs: Long): Resolved<String> {
    val ts = AudioTimestamp()
    val ok = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
    if (!ok || ts.nanoTime <= 0L) return Resolved(MODE_READ_TIME, Provenance.FALLBACK)
    val deltaMs = kotlin.math.abs(readBootNs - ts.nanoTime) / 1_000_000
    Log.i(TAG, "HAL boottime trust check: |readBoot-halBoot|=${deltaMs}ms (threshold ${HAL_TRUST_THRESHOLD_MS}ms)")
    return if (deltaMs <= HAL_TRUST_THRESHOLD_MS) {
      Resolved(MODE_HAL_BOOTTIME, Provenance.DETECTED)
    } else {
      Resolved(MODE_READ_TIME, Provenance.FALLBACK)
    }
  }

  // A timestamp mode → fidelity tier, the same vocabulary the camera/perception streams use.
  fun fidelityForMode(mode: String): String =
    if (mode == MODE_HAL_BOOTTIME) "capture-accurate" else "delivery-stamped"

  // ======================================================================
  // label maps
  // ======================================================================

  private fun micTypeLabel(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
    AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix" // virtual loopback, not a physical mic
    else -> "type_$type"
  }

  private fun micLocationLabel(location: Int): String = when (location) {
    MicrophoneInfo.LOCATION_MAINBODY -> "mainbody"
    MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "mainbody_movable"
    MicrophoneInfo.LOCATION_PERIPHERAL -> "peripheral"
    else -> "unknown"
  }

  private fun micDirectionalityLabel(directionality: Int): String = when (directionality) {
    MicrophoneInfo.DIRECTIONALITY_OMNI -> "omni"
    MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "bi_directional"
    MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "cardioid"
    MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "hyper_cardioid"
    MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "super_cardioid"
    else -> "unknown"
  }
}
