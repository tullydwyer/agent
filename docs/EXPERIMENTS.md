# Device experiment log

Date: 27 October 2026 (Australia/Brisbane).

## Experiment 001: stock Pixel connection and diagnostics scaffold

Purpose: establish a reproducible Android build and USB deployment before adding
model authentication or background listening.

Confirmed on the connected device:

- USB ADB authorization succeeded after enabling USB debugging.
- Device model: Google Pixel 7 (`panther`).
- Android release: 16; API: 36; primary ABI: `arm64-v8a`.
- No root, bootloader changes, driver replacement, or phone reset performed.

Build and deployment verification:

- `assembleDebug` and `lintDebug` passed; initial install returned `Success`.
- The launcher activity started successfully (`Status: ok`, cold start reported
  538 ms). This is an ADB activity startup measurement, not speech latency.
- Inspected the running screen and startup crash log: diagnostics rendered and
  no AndroidRuntime errors were returned in the inspected recent log window.
- App reports an available on-device recognizer, phone language `en-US`, and
  offline English voice `en-us-x-tpf-local`. Actual offline speech is still untested.
- PowerShell helper syntax parsed successfully; build/install/run/doctor actions worked.
- Final rebuild and lint passed with zero errors and five advisory warnings
  (older target API and available dependency updates). The final APK was reinstalled.
- Fixed the helper to select JDK 17/21 rather than Android Studio's installed
  Java 25 runtime, which this Gradle version cannot use.

Build baseline uses target/compile API 35 while the device runs API 36. Lint
reports the older target and available dependency upgrades. This is an intentional
initial baseline, not proof of Android 16 target behavior. Before background/audio
experiments, update the target/build toolchain and rerun relevant lifecycle checks.

## Checks requiring a real speech trial

- [ ] Installed offline English TTS voice speaks without networking.
- [ ] On-device recognizer transcribes the phone's selected language without networking.
- [ ] Recognition accuracy for a representative set of Australian names/phrases.
- [ ] Stop button, app backgrounding, microphone permission denial/revocation.
- [ ] Calls and headset changes release/recover audio as intended.

These are pending experiments, not capabilities established by an availability flag.
The app contains no cloud model connection or wake-word engine yet.

## Next experiments

1. Verify local speech on the device, including airplane-mode runs.
2. Implement and test the documented model-authentication route separately.
3. Measure a user-started local keyword-listening session with the screen locked.

Keep tokens, personal transcripts, email contents, and device serial numbers out of
this log. See `PLAN.md` for the phase-0 acceptance gates.
