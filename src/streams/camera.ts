// Native Camera2 preview view. Pixels stay native (a TextureView); only frame
// timestamps / fps / status cross the bridge. See ExpoXrCameraPreviewView.kt.
import type { ViewProps } from "react-native";
import { requireNativeView } from "expo";

import { nativeModule } from "../native";

export interface XrCameraReadyEvent {
  cameraId: string;
  width: number;
  height: number;
  /** Whether this camera is currently recording to a take. */
  recording: boolean;
}

export interface XrCameraErrorEvent {
  cameraId: string;
  /** e.g. "max_cameras_in_use", "permission_denied", "configure_failed". */
  code: string;
  message: string;
}

export interface XrCameraFrameEvent {
  cameraId: string;
  /** Camera2 SENSOR_TIMESTAMP (ns). REALTIME cameras → boottime, audio-comparable. */
  frameTs: number;
  fps: number;
  /** Whether this camera is currently recording to a take. */
  recording: boolean;
}

export interface XrCameraPreviewProps extends ViewProps {
  /** Camera2 id to preview — "0" world-facing, "2" the stereo-pair candidate. */
  cameraId: string;
  onCameraReady?: (event: { nativeEvent: XrCameraReadyEvent }) => void;
  onCameraError?: (event: { nativeEvent: XrCameraErrorEvent }) => void;
  onCameraFrame?: (event: { nativeEvent: XrCameraFrameEvent }) => void;
}

// Null off-device / before prebuild; callers must guard. Type inferred from
// `requireNativeView` — React types resolve via `expo`, so we avoid a direct `react` import.
function loadCameraPreview() {
  if (!nativeModule) return null;
  try {
    return requireNativeView<XrCameraPreviewProps>("ExpoXrAndroid");
  } catch {
    return null;
  }
}

export const XrCameraPreview = loadCameraPreview();
