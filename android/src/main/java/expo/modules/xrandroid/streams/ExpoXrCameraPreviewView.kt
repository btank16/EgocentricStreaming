package expo.modules.xrandroid.streams

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.LinearLayout
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.viewevent.ViewEventCallback
import expo.modules.kotlin.views.ExpoView
import expo.modules.xrandroid.ACTIVE_PROFILE
import expo.modules.xrandroid.Tuning
import expo.modules.xrandroid.diagnostics.CameraProbe
import expo.modules.xrandroid.diagnostics.EncoderProbe
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

// A self-contained Camera2 preview for a single `cameraId`. Mounting two of these
// (e.g. ids "0" and "2") empirically tests whether the headset allows concurrent opens.
// Pixels stay native (TextureView); only frame timestamps / fps / status cross the bridge.
// Recording: the SAME open device also feeds an all-intra HEVC encoder (see README → Recording).
class ExpoXrCameraPreviewView(context: Context, appContext: AppContext) :
  ExpoView(context, appContext) {

  val onCameraReady by EventDispatcher<Map<String, Any>>()
  val onCameraError by EventDispatcher<Map<String, Any>>()
  val onCameraFrame by EventDispatcher<Map<String, Any>>()

  private val textureView = TextureView(context)
  private var cameraId: String? = null
  private var cameraDevice: CameraDevice? = null
  private var captureSession: CameraCaptureSession? = null
  private var backgroundThread: HandlerThread? = null
  private var backgroundHandler: Handler? = null
  private var opening = false
  private var previewSize = ACTIVE_PROFILE.previewFallbackSize
  private var previewSurface: Surface? = null

  // Extra Camera2 capture targets supplied by other native modules via XrFrameBus — raw
  // pixels fan out to them IN HARDWARE (no bridge). Thread-safe: attach/detach run off
  // the camera thread while the capture callback iterates the target list.
  private val consumerSurfaces = CopyOnWriteArraySet<Surface>()

  // Recording state. `recorder` non-null and `recording` true between start/stopRecording().
  // @Volatile so the camera background thread reliably sees the latest reference.
  @Volatile
  private var recorder: EncoderRecorder? = null

  @Volatile
  private var recording = false

  // The AE target-fps range applied to the record request to cap the camera at the
  // derived capture fps (null = leave the camera at its default).
  private var recordFpsRange: Range<Int>? = null

  // Rolling fps over a short window; emit ~twice per second to keep the bridge light.
  private var frameCount = 0
  private var windowStartNs = 0L
  private var lastEmitNs = 0L

  init {
    addView(
      textureView,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.MATCH_PARENT,
      ),
    )
    textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = tryStart()
      override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
      override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
        stop()
        return true
      }
      override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
    }
  }

  fun setCameraId(id: String) {
    if (id == cameraId) return
    stop()
    cameraId = id
    tryStart()
  }

  private fun tryStart() {
    val id = cameraId ?: return
    if (!textureView.isAvailable || cameraDevice != null || opening) return
    if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      emit(onCameraError, "code" to "permission_denied", "message" to "CAMERA permission not granted")
      return
    }
    opening = true
    startBackgroundThread()
    openCamera(id)
  }

  @SuppressLint("MissingPermission") // permission is checked in tryStart()
  private fun openCamera(id: String) {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    if (manager == null) {
      opening = false
      emit(onCameraError, "code" to "no_camera_service", "message" to "CameraManager unavailable")
      return
    }
    try {
      val chars = manager.getCameraCharacteristics(id)
      previewSize = choosePreviewSize(chars)
      val texture = textureView.surfaceTexture ?: run {
        opening = false
        return
      }
      texture.setDefaultBufferSize(previewSize.width, previewSize.height)
      manager.openCamera(
        id,
        object : CameraDevice.StateCallback() {
          override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            opening = false
            previewSurface = Surface(texture)
            CameraRegistry.register(id, this@ExpoXrCameraPreviewView)
            configureSession(activeTargets(), currentTemplate())
          }
          override fun onDisconnected(device: CameraDevice) {
            device.close()
            if (cameraDevice === device) cameraDevice = null
            opening = false
          }
          override fun onError(device: CameraDevice, error: Int) {
            device.close()
            if (cameraDevice === device) cameraDevice = null
            opening = false
            emit(
              onCameraError,
              "code" to errorLabel(error),
              "message" to "Camera $id open error ($error)",
            )
          }
        },
        backgroundHandler,
      )
    } catch (e: CameraAccessException) {
      opening = false
      emit(onCameraError, "code" to "access_exception", "message" to (e.message ?: "CameraAccessException"))
    } catch (e: Exception) {
      opening = false
      emit(onCameraError, "code" to "exception", "message" to (e.message ?: e.toString()))
    }
  }

  // (Re)create the capture session targeting `targets` — preview-only (TEMPLATE_PREVIEW)
  // or preview+encoder (TEMPLATE_RECORD). Creating a new session supersedes the prior one.
  private fun configureSession(targets: List<Surface>, template: Int) {
    val device = cameraDevice ?: return
    if (targets.isEmpty()) return
    try {
      val requestBuilder = device.createCaptureRequest(template)
      targets.forEach { requestBuilder.addTarget(it) }
      // Cap the camera at the derived capture fps while recording (no-op for preview).
      if (template == CameraDevice.TEMPLATE_RECORD) {
        recordFpsRange?.let { requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
      }
      val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
          session: CameraCaptureSession,
          request: CaptureRequest,
          result: TotalCaptureResult,
        ) {
          val sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
          if (recording) recorder?.onCameraFrame(sensorTs)
          onFrame(sensorTs)
        }
      }
      @Suppress("DEPRECATION") // createCaptureSession(list, …) covers minSdk 24
      device.createCaptureSession(
        targets,
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            try {
              session.setRepeatingRequest(requestBuilder.build(), captureCallback, backgroundHandler)
              emit(
                onCameraReady,
                "cameraId" to (cameraId ?: ""),
                "width" to previewSize.width,
                "height" to previewSize.height,
                "recording" to recording,
              )
            } catch (e: Exception) {
              emit(onCameraError, "code" to "request_exception", "message" to (e.message ?: e.toString()))
            }
          }
          override fun onConfigureFailed(session: CameraCaptureSession) {
            emit(onCameraError, "code" to "configure_failed", "message" to "Capture session config failed")
          }
        },
        backgroundHandler,
      )
    } catch (e: Exception) {
      emit(onCameraError, "code" to "session_exception", "message" to (e.message ?: e.toString()))
    }
  }

  // --- recording (driven by TakeRecorder via CameraRegistry) ----------

  // Start encoding this camera into `mp4File` (all-intra HEVC) with a per-frame
  // SENSOR_TIMESTAMP sidecar. Returns the ACTUAL resolved config + provenance (not the
  // requested preset) so the manifest records what was really encoded. `status` != "recording"
  // means the other fields are unset. Session reconfigure runs on the camera background thread.
  internal fun startRecording(
    mp4File: File,
    framesSidecar: File,
    requestedSize: Size?,
    maxFps: Int,
    bitRate: Int,
    qualityScale: Double,
  ): CameraRecordStart {
    val device = cameraDevice ?: return CameraRecordStart("camera_not_open")
    if (recording) return CameraRecordStart("already_recording")
    // Record size + fps come from CameraProbe.resolve() (probe first, profile as fallback);
    // fps is DERIVED from the sensor's advertised max, capped at maxFps (Galaxy XR caps at
    // 24; faster sensors reach maxFps).
    val sizeResolved = CameraProbe.resolveRecordSize(context, device.id, requestedSize)
    val size = sizeResolved.value ?: return CameraRecordStart("no_record_size")
    val fpsResolved = CameraProbe.resolveFps(context, device.id, maxFps)
    val fps = fpsResolved.value
    recordFpsRange = CameraProbe.resolveFpsRange(context, device.id, fps)
    // Choose the hardware encoder + clamp/scale bitrate from its advertised ceiling.
    // qualityScale (0..1, from the TS control surface) scales the bitrate down from the
    // clamped max; 1.0 leaves it at the preset (unchanged on the Galaxy XR).
    val encChoice = EncoderProbe.resolve(size, fps, bitRate, qualityScale)
    Log.i(
      TAG,
      "record cam ${device.id}: size=${size.width}x${size.height}(${sizeResolved.label()}) " +
        "fps=$fps(${fpsResolved.label()}) " +
        "codec=${encChoice.codecName ?: "default"}(${encChoice.codecProvenance.name.lowercase()}) " +
        "bitRate=${encChoice.bitRate}(${encChoice.bitRateProvenance.name.lowercase()})",
    )
    val enc = EncoderRecorder(
      mp4File, framesSidecar, encChoice.codecName, size.width, size.height, fps, encChoice.bitRate,
    )
    try {
      enc.start()
    } catch (e: Exception) {
      emit(onCameraError, "code" to "encoder_failed", "message" to (e.message ?: e.toString()))
      return CameraRecordStart("encoder_failed")
    }
    if (enc.inputSurface == null) {
      enc.stop()
      return CameraRecordStart("no_encoder_surface")
    }
    recorder = enc
    recording = true
    // activeTargets() now includes the encoder surface (recording == true).
    reconfigureTargets()
    return CameraRecordStart(
      status = "recording",
      width = size.width,
      height = size.height,
      fps = fps,
      sizeProvenance = sizeResolved.label(),
      fpsProvenance = fpsResolved.label(),
      codecName = encChoice.codecName,
      codecProvenance = encChoice.codecProvenance.name.lowercase(),
      bitRate = encChoice.bitRate,
      bitRateProvenance = encChoice.bitRateProvenance.name.lowercase(),
      qualityScale = qualityScale,
    )
  }

  // Blocks until the encoder is finalized and the mp4/sidecar are fully written, so the
  // manifest can bake per-file stats at record-stop. Teardown runs on the camera background
  // thread (to serialize with the capture session); bounded so a wedged thread can't hang stop.
  fun stopRecording() {
    if (!recording) return
    recording = false
    val enc = recorder
    recorder = null
    recordFpsRange = null
    val handler = backgroundHandler
    if (handler == null) {
      // No background thread (view never fully opened) — finalize inline as a fallback.
      enc?.stop()
      return
    }
    val done = java.util.concurrent.CountDownLatch(1)
    handler.post {
      // Halt frames to the encoder surface before finalizing/releasing it, then resume
      // without it. stopRepeating ensures the camera isn't writing to a torn-down surface.
      try {
        captureSession?.stopRepeating()
      } catch (e: Exception) {
        // ignore
      }
      try {
        enc?.stop()
        // recording is now false → activeTargets() = preview + consumer surfaces (no encoder).
        if (previewSurface != null) configureSession(activeTargets(), CameraDevice.TEMPLATE_PREVIEW)
      } finally {
        done.countDown()
      }
    }
    runCatching { done.await(STOP_FINALIZE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
  }

  // --- consumer surfaces (XrFrameBus open door) ------------------------

  // Add another native module's Surface as an extra capture target; frames fan out to it in
  // hardware (no bridge). Returns a handle that removes it on close. See README → Consuming raw
  // streams (+ the LIMITED hardware stream-count caveat).
  fun attachConsumerSurface(surface: Surface): AutoCloseable {
    consumerSurfaces.add(surface)
    reconfigureTargets()
    return AutoCloseable {
      consumerSurfaces.remove(surface)
      reconfigureTargets()
    }
  }

  // The full capture-target set for the current state: preview + (encoder while recording) +
  // consumer surfaces. currentTemplate() picks TEMPLATE_RECORD while recording, else PREVIEW.
  private fun activeTargets(): List<Surface> {
    val enc = if (recording) recorder?.inputSurface else null
    return listOfNotNull(previewSurface, enc) + consumerSurfaces
  }

  private fun currentTemplate(): Int =
    if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW

  // Rebuild the capture session for the current targets/template on the camera thread. No-op
  // until the device is open (onOpened does the first configure).
  private fun reconfigureTargets() {
    if (cameraDevice == null) return
    backgroundHandler?.post { configureSession(activeTargets(), currentTemplate()) }
  }

  // The clock domain this camera stamps SENSOR_TIMESTAMP in ("REALTIME"/"UNKNOWN"),
  // delegated to CameraProbe.resolveTimestamp. Recorded in the manifest, so a non-REALTIME
  // camera (which can't be capture-accurate) is a recorded fact, not a silent assumption.
  fun timestampSource(): String? {
    val id = cameraId ?: return null
    return CameraProbe.resolveTimestamp(context, id).first
  }

  private fun onFrame(sensorTs: Long) {
    frameCount++
    val now = SystemClock.elapsedRealtimeNanos()
    if (windowStartNs == 0L) windowStartNs = now
    if (lastEmitNs == 0L || now - lastEmitNs >= Tuning.CAMERA_STATUS_EMIT_NS) {
      val windowDurNs = (now - windowStartNs).coerceAtLeast(1L)
      val fps = frameCount * 1_000_000_000.0 / windowDurNs
      emit(
        onCameraFrame,
        "cameraId" to (cameraId ?: ""),
        "frameTs" to sensorTs,
        "fps" to fps,
        "recording" to recording,
      )
      lastEmitNs = now
      windowStartNs = now
      frameCount = 0
    }
  }

  private fun choosePreviewSize(chars: CameraCharacteristics): Size {
    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
    // Smallest available — lowest bandwidth, which matters for the concurrent test.
    return sizes?.minByOrNull { it.width.toLong() * it.height } ?: ACTIVE_PROFILE.previewFallbackSize
  }

  private fun startBackgroundThread() {
    if (backgroundThread != null) return
    backgroundThread = HandlerThread("xr-cam-${cameraId ?: "?"}").also { it.start() }
    backgroundHandler = Handler(backgroundThread!!.looper)
  }

  private fun stopBackgroundThread() {
    backgroundThread?.quitSafely()
    try {
      backgroundThread?.join(500)
    } catch (e: InterruptedException) {
      // ignore
    }
    backgroundThread = null
    backgroundHandler = null
  }

  private fun stop() {
    // Finalize an in-flight recording first so the MP4 isn't left unclosed.
    if (recording) {
      recording = false
      try {
        captureSession?.stopRepeating()
      } catch (e: Exception) {
        // ignore
      }
      recorder?.stop()
      recorder = null
    }
    cameraId?.let { CameraRegistry.unregister(it, this) }
    try {
      captureSession?.close()
    } catch (e: Exception) {
      // ignore
    }
    captureSession = null
    try {
      cameraDevice?.close()
    } catch (e: Exception) {
      // ignore
    }
    cameraDevice = null
    previewSurface?.release()
    previewSurface = null
    // Drop consumer target references (the consumer owns each Surface's lifecycle — we don't
    // release them). A consumer re-attaches after the camera is re-mounted.
    consumerSurfaces.clear()
    stopBackgroundThread()
    opening = false
    frameCount = 0
    windowStartNs = 0L
    lastEmitNs = 0L
  }

  override fun onDetachedFromWindow() {
    stop()
    super.onDetachedFromWindow()
  }

  // Dispatch on the UI thread — capture callbacks run on the background handler.
  // The dispatcher is a ViewEventCallback (fun interface), so it's typed explicitly.
  private fun emit(dispatcher: ViewEventCallback<Map<String, Any>>, vararg pairs: Pair<String, Any>) {
    post { dispatcher(mapOf(*pairs)) }
  }

  private fun errorLabel(error: Int): String = when (error) {
    ERROR_CAMERA_IN_USE -> "in_use"
    ERROR_MAX_CAMERAS_IN_USE -> "max_cameras_in_use"
    ERROR_CAMERA_DISABLED -> "disabled"
    ERROR_CAMERA_DEVICE -> "device_error"
    ERROR_CAMERA_SERVICE -> "service_error"
    else -> "error_$error"
  }

  private companion object {
    const val TAG = "XrCameraView"
    const val ERROR_CAMERA_IN_USE = CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
    const val ERROR_MAX_CAMERAS_IN_USE = CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
    const val ERROR_CAMERA_DISABLED = CameraDevice.StateCallback.ERROR_CAMERA_DISABLED
    const val ERROR_CAMERA_DEVICE = CameraDevice.StateCallback.ERROR_CAMERA_DEVICE
    const val ERROR_CAMERA_SERVICE = CameraDevice.StateCallback.ERROR_CAMERA_SERVICE
    // Cap on how long stopRecording() blocks awaiting the background finalize (encoder stop +
    // muxer flush). Bounded so a wedged camera thread can't hang record-stop.
    const val STOP_FINALIZE_TIMEOUT_MS = 3000L
  }
}

// The actual encode config a camera recording started with, plus the provenance of each
// value (detected, fell back to profile, or clamped). Baked into the manifest so a take
// documents what was really encoded. `status` != "recording" → remaining fields are unset.
internal data class CameraRecordStart(
  val status: String,
  val width: Int = 0,
  val height: Int = 0,
  val fps: Int = 0,
  val sizeProvenance: String = "",
  val fpsProvenance: String = "",
  val codecName: String? = null,
  val codecProvenance: String = "",
  val bitRate: Int = 0,
  val bitRateProvenance: String = "",
  val qualityScale: Double = 1.0,
)
