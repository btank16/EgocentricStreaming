package expo.modules.xrandroid

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.xrandroid.bus.XrCaptureBus
import expo.modules.xrandroid.control.StreamController
import expo.modules.xrandroid.diagnostics.Capabilities
import expo.modules.xrandroid.diagnostics.CameraProbe
import expo.modules.xrandroid.diagnostics.EncoderProbe
import expo.modules.xrandroid.diagnostics.HandTrackingProbe
import expo.modules.xrandroid.diagnostics.HeadTrackingProbe
import expo.modules.xrandroid.diagnostics.ProfileDraft
import expo.modules.xrandroid.recording.RecordingExporter
import expo.modules.xrandroid.recording.RecordingInspector
import expo.modules.xrandroid.streams.ExpoXrCameraPreviewView

// Wiring only — declares the JS-facing surface and delegates; reads as a table of contents.
// STATELESS diagnostics probes call the probe objects directly; STATEFUL stream/recording
// control delegates to StreamController (which owns the sources and is the bus's XrSourceProvider).
class ExpoXrAndroidModule : Module() {

  // The runtime owner. Lazy so it's built once the module is attached (sendEvent ready).
  private val controller by lazy { StreamController(appContext) { name, body -> sendEvent(name, body) } }

  override fun definition() = ModuleDefinition {
    Name("ExpoXrAndroid")

    Events("onDevicePose", "onHandState", "onAudioLevel", "onSessionOrigin")

    // Bind the capture bus to the controller (the XrSourceProvider) so a cross-module consumer
    // reaches the live sources through the single owner.
    OnCreate {
      XrCaptureBus.bind(controller)
    }

    // ---- Diagnostics (stateless probes; side-effect-free) ------------------

    Function("isAvailable") {
      Capabilities.isXrRuntimeAvailable(appContext.reactContext)
    }

    AsyncFunction("getDeviceCapabilities") {
      Capabilities.build(appContext.reactContext)
    }

    // Camera LENS_* calibration dump (factory intrinsics/extrinsics test). Logcat "XrCameraProbe".
    AsyncFunction("probeCameras") {
      CameraProbe.describeCalibration(appContext.reactContext)
    }

    // World-facing capture envelope (size × sustained fps). Logcat "XrCaptureProbe".
    AsyncFunction("probeCaptureSizes") {
      CameraProbe.describeCaptureEnvelope(appContext.reactContext)
    }

    // Hardware HEVC/AVC encoder capabilities. Logcat "XrEncoderProbe".
    AsyncFunction("probeVideoEncoders") {
      EncoderProbe.describe()
    }

    // Head/hand tracking endpoint probes — runtime + permission + config + timestamp seam.
    AsyncFunction("probeHeadTracking") {
      HeadTrackingProbe.describe(appContext.reactContext)
    }

    AsyncFunction("probeHandTracking") {
      HandTrackingProbe.describe(appContext.reactContext)
    }

    // Device-profile draft: probe this hardware's audio/camera/encoder limits into a
    // DeviceProfile-shaped dump. `profileDraft` returns it; `exportProfileDraft` writes it
    // to the app-private dir and returns the path for `adb pull`.
    AsyncFunction("profileDraft") {
      ProfileDraft.build(appContext.reactContext)
    }

    AsyncFunction("exportProfileDraft") {
      ProfileDraft.export(appContext.reactContext)
    }

    // ---- Streams (delegate to StreamController) ----------------------------

    // Head-pose stream → onDevicePose. bridgeRateHz throttles the DISPLAY only (null = full
    // rate); a storage sink records full rate. Starting it also latches the session origin.
    AsyncFunction("startHeadPose") { bridgeRateHz: Double? ->
      controller.startHead(bridgeRateHz)
    }

    AsyncFunction("stopHeadPose") {
      controller.stopHead()
    }

    // Hand-joint stream → onHandState. bridgeRateHz throttles the DISPLAY per hand (null =
    // full rate, same as head); storage records full rate.
    AsyncFunction("startHands") { bridgeRateHz: Double? ->
      controller.startHands(bridgeRateHz)
    }

    AsyncFunction("stopHands") {
      controller.stopHands()
    }

    AsyncFunction("getSessionOrigin") {
      controller.getSessionOrigin()
    }

    // Audio: each source (camcorder / voice_recognition) starts/stops independently;
    // startAudioSource returns the per-source status. Levels stream via onAudioLevel.
    AsyncFunction("startAudioSource") { label: String ->
      controller.startAudioSource(label)
    }

    AsyncFunction("stopAudioSource") { label: String ->
      controller.stopAudioSource(label)
    }

    // Recording: `streams` empty → record everything currently live. qualityScale (0..1, null
    // = 1.0) scales the camera bitrate down from the clamped encoder max. Resolves with the
    // take dir; stopRecording writes manifest.json.
    AsyncFunction("startRecording") { streams: List<String>, preset: String, qualityScale: Double? ->
      controller.startRecording(streams, preset, qualityScale)
    }

    AsyncFunction("stopRecording") {
      controller.stopRecording()
    }

    AsyncFunction("isRecording") {
      controller.isRecording()
    }

    // ---- Catalog (filesystem + manifest; no database) ----------------------
    // Listing/manifest reads live in TS; native provides only the base path, deep re-probe, zip.

    AsyncFunction("catalogRoot") {
      RecordingInspector.catalogRoot(appContext.reactContext)
    }

    AsyncFunction("inspectTake") { name: String ->
      RecordingInspector.inspectTake(appContext.reactContext, name)
    }

    AsyncFunction("zipTake") { name: String ->
      RecordingExporter.zipTake(appContext.reactContext, name)
    }

    // ---- Camera preview view ----------------------------------------------

    // Native Camera2 preview, parameterized by `cameraId`. Mounting two (ids "0" and "2") is
    // how the app empirically tests concurrent-open support.
    View(ExpoXrCameraPreviewView::class) {
      Events("onCameraReady", "onCameraError", "onCameraFrame")
      Prop("cameraId") { view: ExpoXrCameraPreviewView, id: String ->
        view.setCameraId(id)
      }
    }

    OnDestroy {
      XrCaptureBus.unbind() // stop resolving sources through a module being torn down
      controller.dispose()
    }
  }
}
