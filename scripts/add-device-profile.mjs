#!/usr/bin/env node
// Device-profile generator. Turns a probe dump from `xr.profiles.exportDraft()`
// (profile-draft.json) into a Kotlin DeviceProfile stanza to paste into Constants.kt →
// PROFILES, keyed by the device's deviceKey().
//
// Flow:
//   1. On the headset (dev build): call xr.profiles.exportDraft() — writes profile-draft.json
//      to the app-private dir and returns its path.
//   2. Pull it:   adb pull <path> ./profile-draft.json
//      (or let this script pull it: `--adb` uses the default app-private path below.)
//   3. Generate:  node scripts/add-device-profile.mjs ./profile-draft.json
//   4. Paste the printed stanza + PROFILES entry into
//      android/src/main/java/expo/modules/xrandroid/Constants.kt, then rebuild.
//
// The generator PROBES nothing itself — it only formats the device-measured draft. Values
// the draft flags as copied-from-baseline (preset bitrates, efficient size, preview size)
// are emitted with a TUNE marker; tune them from on-device measurement.
import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";

// The host app's Android package — the app-private external dir `--adb` pulls from.
// This is the original host app; override for other host apps.
const APP_PACKAGE = "com.btanski.chameleon";
const DRAFT_FILE = "profile-draft.json";
const DEVICE_PATH = `/sdcard/Android/data/${APP_PACKAGE}/files/${DRAFT_FILE}`;

function fail(msg) {
  console.error(`✖ ${msg}`);
  process.exit(1);
}

function usage() {
  console.log(`Usage:
  node add-device-profile.mjs <profile-draft.json>   Generate from a pulled draft
  node add-device-profile.mjs --adb                  adb pull the draft first, then generate

The draft comes from xr.profiles.exportDraft() on the device.`);
}

// Pull the draft off the device with adb (falls back to an error if adb/device is absent).
function adbPull() {
  try {
    execFileSync("adb", ["pull", DEVICE_PATH, DRAFT_FILE], { stdio: "inherit" });
    return DRAFT_FILE;
  } catch {
    fail(`adb pull failed. Is the headset connected (adb devices) and has the app run\n` +
      `  xr.profiles.exportDraft() at least once? Expected on-device path:\n  ${DEVICE_PATH}`);
  }
}

// "WxH" → Size(W, H); null/absent → null (the camera's native max at runtime).
function sizeExpr(label) {
  if (!label) return "null";
  const m = /^(\d+)x(\d+)$/.exec(String(label).trim());
  if (!m) fail(`Bad size "${label}" — expected "WxH".`);
  return `Size(${m[1]}, ${m[2]})`;
}

// deviceKey "samsung/sm-i130" → Kotlin val symbol "SAMSUNG_SM_I130".
function symbolFor(deviceKey) {
  const s = String(deviceKey).toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_+|_+$/g, "");
  return /^[A-Z]/.test(s) ? s : `DEVICE_${s}`;
}

function main() {
  const args = process.argv.slice(2);
  if (args.includes("-h") || args.includes("--help")) return usage();

  const file = args.includes("--adb") ? adbPull() : args[0];
  if (!file) {
    usage();
    fail("No draft file given.");
  }

  let draft;
  try {
    draft = JSON.parse(readFileSync(file, "utf8"));
  } catch (e) {
    fail(`Could not read/parse ${file}: ${e.message}`);
  }

  const { deviceKey, identity, suggestedProfile: p, activeProfileIsDefault } = draft;
  if (!deviceKey || !p) fail("Draft is missing `deviceKey` or `suggestedProfile`. Re-export it.");

  const symbol = symbolFor(deviceKey);
  const presets = Object.entries(p.cameraPresets ?? {})
    .map(([name, v]) => `    "${name}" to CameraPreset(${sizeExpr(v.size)}, ${v.bitRate}),`)
    .join("\n");

  const stanza = `// ${identity?.manufacturer ?? "?"} ${identity?.model ?? "?"} — generated from a device probe (add-device-profile.mjs).
// TUNE: preset bitRates, the "efficient" size, and previewFallbackSize are copied from the
// Galaxy XR baseline; measure on-device and adjust. sampleRate/audioChannel/audioEncoding
// and maxCaptureFps were probed from this hardware.
internal val ${symbol} = DeviceProfile(
  sampleRate = ${p.sampleRate},
  audioChannel = AudioFormat.${p.audioChannel},
  audioEncoding = AudioFormat.${p.audioEncoding}, // bytesPerFrame derives from these two
  framesPerRead = ${p.framesPerRead},
  maxCaptureFps = ${p.maxCaptureFps},
  previewFallbackSize = ${sizeExpr(p.previewFallbackSize)},
  cameraPresets = mapOf(
${presets}
  ),
  defaultPreset = "${p.defaultPreset}",
)`;

  console.log(`\n${"=".repeat(78)}`);
  console.log(`Device:    ${identity?.manufacturer}/${identity?.model}  (key: ${deviceKey})`);
  console.log(`Android:   ${identity?.androidRelease} (sdk ${identity?.sdkInt})`);
  if (activeProfileIsDefault) {
    console.log(`Status:    currently falls back to DEFAULT_PROFILE — register it below.`);
  }
  console.log("=".repeat(78));
  console.log(`\n// 1) Add this DeviceProfile in Constants.kt (near GALAXY_XR):\n`);
  console.log(stanza);
  console.log(`\n// 2) Register it in the PROFILES map:\n`);
  console.log(`internal val PROFILES: Map<String, DeviceProfile> = mapOf(`);
  console.log(`  "${deviceKey}" to ${symbol},`);
  console.log(`)`);
  console.log(`\n// 3) Rebuild. detectProfile() will now match this device by deviceKey().\n`);
}

main();
