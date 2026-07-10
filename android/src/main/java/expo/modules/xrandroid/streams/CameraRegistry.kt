package expo.modules.xrandroid.streams

import java.util.concurrent.ConcurrentHashMap

// Bridges the headless TakeRecorder to live camera preview VIEWS. The camera is a View
// (owns the Camera2 device + preview Surface), so it can't be a headless CaptureSource
// like the other streams; each mounted preview registers by cameraId and the
// TakeRecorder looks it up to start/stop the encoder on the same open device. A camera
// is recordable only while its preview is mounted.
internal object CameraRegistry {
  private val views = ConcurrentHashMap<String, ExpoXrCameraPreviewView>()

  fun register(cameraId: String, view: ExpoXrCameraPreviewView) {
    views[cameraId] = view
  }

  fun unregister(cameraId: String, view: ExpoXrCameraPreviewView) {
    // remove only if this exact view is still the registered one (handles remount races).
    views.remove(cameraId, view)
  }

  fun get(cameraId: String): ExpoXrCameraPreviewView? = views[cameraId]

  // Currently-mounted camera ids — recordable right now. Resolves "record all live
  // streams" in StreamController.
  fun mountedIds(): Set<String> = views.keys.toSet()
}
