// @chameleon/expo-xr-android — Android XR Expo Module. Barrel.
//
// SINGLE runtime entry point: `xr` (see xr.ts) — streams, recording, session origin,
// diagnostics, and the take catalog all reach through it; the per-concern wrapper files are
// internal. The only other exports are TYPES via `export type *`: it re-exports type meanings
// with no runtime binding, so `xr` stays the sole value export.
export { xr } from "./xr";

export type { StreamStartOptions, RecordingStartOptions } from "./xr";
export type { Subscription } from "./native";
export type * from "./diagnostics/capabilities";
export type * from "./diagnostics/camera-calibration";
export type * from "./diagnostics/camera-capture-probe";
export type * from "./diagnostics/video-encoder-probe";
export type * from "./diagnostics/head-tracking";
export type * from "./diagnostics/hand-tracking";
export type * from "./diagnostics/profile-draft";
export type * from "./streams/camera";
export type * from "./streams/headpose";
export type * from "./streams/hands";
export type * from "./streams/audio";
export type * from "./streams/session-origin";
export type * from "./recording/recording";
export type * from "./catalog/catalog";
