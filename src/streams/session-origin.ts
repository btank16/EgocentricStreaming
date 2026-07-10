// Session origin — the world tracking frame documented at session bring-up.
//
// Latched automatically (not user-captured) when the first perception stream starts — the
// first TRACKING pose, or a timed-out `reliable: false` pose — relatched at every recording
// start, cleared when the last stream stops (see SessionOriginController.kt). The runtime's
// world origin is gravity-aligned but arbitrary in XZ + heading and user-resettable, so a
// recording is co-registerable later only if its start frame is documented — which is what
// this records: the frame convention + the head's pose at session start + a best-effort
// floor reference.
import { nativeModule, NOOP_SUBSCRIPTION, type Subscription } from "../native";

/** Right-handed, +X right / +Y up / −Z forward, metres, gravity-aligned. A
 * property of the Android XR runtime (androidx.xr.arcore world / ActivitySpace) —
 * constant across sessions, so it is attached JS-side rather than measured. */
export interface XrCoordinateSystem {
  frame: "androidx.xr.arcore world / ActivitySpace";
  handedness: "right";
  axes: { right: "+X"; up: "+Y"; forward: "-Z" };
  units: "meters";
  /** +Y is physical up; pitch/roll/height are absolute, comparable across sessions. */
  gravityAligned: true;
  rotationFormat: "quaternion_xyzw";
  /** Origin is runtime-defined: arbitrary in XZ + heading, user-resettable. */
  originSemantics: "runtime-defined; arbitrary in XZ + heading; user-resettable";
}

export const XR_COORDINATE_SYSTEM: XrCoordinateSystem = {
  frame: "androidx.xr.arcore world / ActivitySpace",
  handedness: "right",
  axes: { right: "+X", up: "+Y", forward: "-Z" },
  units: "meters",
  gravityAligned: true,
  rotationFormat: "quaternion_xyzw",
  originSemantics: "runtime-defined; arbitrary in XZ + heading; user-resettable",
};

export type XrTrackingState = "tracking" | "degraded" | "paused" | "stopped" | "unknown";

export interface XrPose {
  /** Position in metres, world_from_device (head position in the world frame). */
  translation: [number, number, number];
  /** Orientation quaternion [x, y, z, w], world_from_device. */
  rotation: [number, number, number, number];
}

/** Best-effort floor reference from a FLOOR-labelled plane (needs
 * SCENE_UNDERSTANDING_COARSE + a detected floor). Absent → `available: false`. */
export interface SessionOriginFloor {
  available: boolean;
  /** Floor height along gravity in the world frame (origin → floor offset). */
  floorYInWorldM?: number;
  /** Head height above the floor at session start (devicePose.y − floorY). */
  headHeightAboveFloorM?: number;
}

export interface SessionOrigin {
  /** Static frame convention — attached JS-side, runtime-invariant. */
  coordinateSystem: XrCoordinateSystem;
  /** elapsedRealtimeNanos() at latch (boottime ns). */
  capturedAtBootNs: number;
  /** Epoch ms at latch — human-readable session identity. */
  capturedAtWallMs: number;
  /** Tracking state of the latched pose. */
  trackingState: XrTrackingState;
  /** False when latched on timeout without reaching TRACKING (low-confidence origin). */
  reliable: boolean;
  /** Head pose at session start (the origin record). */
  devicePose: XrPose;
  /** devicePose is `world_from_device`: re-anchor later via inv(world_from_device₀). */
  transformConvention: "world_from_device";
  /** Head height above the (arbitrary) world origin = devicePose.translation[1]. */
  headHeightAboveOriginM: number;
  floor: SessionOriginFloor;
}

export type SessionOriginListener = (origin: SessionOrigin) => void;

// Native returns only the MEASURED fields; the static convention + transform direction
// are constants attached here, merged over defaults so a missing field can't crash the UI.
function normalizeOrigin(raw: unknown): SessionOrigin | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const pose = (r.devicePose ?? {}) as Record<string, unknown>;
  const floor = (r.floor ?? {}) as Record<string, unknown>;
  return {
    coordinateSystem: XR_COORDINATE_SYSTEM,
    capturedAtBootNs: Number(r.capturedAtBootNs ?? 0),
    capturedAtWallMs: Number(r.capturedAtWallMs ?? 0),
    trackingState: (r.trackingState as XrTrackingState) ?? "unknown",
    reliable: Boolean(r.reliable ?? false),
    devicePose: {
      translation: (pose.translation as [number, number, number]) ?? [0, 0, 0],
      rotation: (pose.rotation as [number, number, number, number]) ?? [0, 0, 0, 1],
    },
    transformConvention: "world_from_device",
    headHeightAboveOriginM: Number(r.headHeightAboveOriginM ?? 0),
    floor: {
      available: Boolean(floor.available ?? false),
      floorYInWorldM: floor.floorYInWorldM as number | undefined,
      headHeightAboveFloorM: floor.headHeightAboveFloorM as number | undefined,
    },
  };
}

/** Subscribe to the session-origin latch. Fires on each latch: once when the first
 * stream brings the session up, and again at every recording start (which relatches
 * a fresh origin). Returns a subscription; `.remove()` to unsubscribe.
 * No-op when the native module is unavailable. */
export function addSessionOriginListener(listener: SessionOriginListener): Subscription {
  if (!nativeModule) return NOOP_SUBSCRIPTION;
  return nativeModule.addListener("onSessionOrigin", (event) => {
    const origin = normalizeOrigin(event);
    if (origin) listener(origin);
  });
}

/** The currently-latched session origin, or null if none has latched yet (no
 * session, or the session hasn't reached a tracked pose). Lets a late-mounting
 * consumer read the origin without waiting for the one-shot event. */
export async function getSessionOrigin(): Promise<SessionOrigin | null> {
  if (!nativeModule) return null;
  return normalizeOrigin(await nativeModule.getSessionOrigin());
}
