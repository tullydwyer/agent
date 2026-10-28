# Phase 0 feasibility lab

This lab implements experiments, not a complete conversation assistant. It uses
the phone's on-device utterance recognizer and offline English TTS, a separate
ChatGPT plan provider experiment, and a five-minute local keyword session.
Phase 0's feasibility gate passed on 27 October 2026; see Experiment 002 for the
device/account results and measurement limits. The full assistant remains later work.

## Decisions recorded on 27 October 2026

- Distribution: private source, personal local installation (user decision).
- User-started keyword sessions are acceptable (user decision).
- Subscription access only. No paid API fallback has been approved or implemented.
- Selected provider for this personal local prototype: direct ChatGPT plan usage.
  The actual account completed text, image, diagnostic function, renewal, and
  reconnect trials. This establishes operational account access for the tested
  installation; it is not approval for a paid, hosted, or public distribution.

## Build and measurements

Use JDK 17/21, SDK Platform 36, Build-Tools 35.0.0, and Platform-Tools. AGP 8.10.1
supports API 36 with the existing Gradle 8.11.1 wrapper.

```powershell
.\scripts\prepare-keyword.ps1
.\scripts\dev.ps1 build
.\scripts\dev.ps1 install
.\scripts\dev.ps1 run
.\scripts\dev.ps1 measure
```

The preparation script downloads a pinned AAR and English model, verifies their
SHA-256 digests, and copies the required files into ignored `.tools/keyword`.
Run it before using the Gradle wrapper directly on a fresh checkout. Downloads
are approximately 40 MB and 18 MB; installed model assets are approximately 6 MB.
The keyword engine and model run without a runtime network download.

`measure` reports storage, battery, page size, app PSS/RSS, OS and candidate speech
package versions. It does not collect credentials, serials, recordings, or mail.
Capture a foreground idle reading and a session reading under the same conditions.
USB charging snapshots are not a battery-discharge experiment. For battery tests,
unplug and compare repeated equal-duration idle/session runs with the same screen,
network and temperature conditions; record both percentages and charge counters.

Build includes local security/stream tests. The three instrumentation tests cover
encrypted storage, a known prerecorded keyword, and a short screen-off service
session. They do not establish live keyword accuracy or offline utterance quality.
`connectedDebugAndroidTest` can uninstall the target APK and erase its app data;
run it on a test installation **before signing in**, or use a separate device.
It temporarily grants microphone/notification permissions, restores their prior
grant state, and requires the phone initially unlocked. It does not bypass a lock.

## Account experiment

1. Scroll to **ChatGPT plan experiment**, tap **Continue with ChatGPT**, and finish
   the system-browser authorization. Return to Pixel Agent after the loopback
   page says the authorization was received, using its **Open Pixel Agent** link.
   Only enter credentials in the
   provider's browser flow. The listener binds to `127.0.0.1` on an available
   port and expires after five minutes. A temporary data-sync foreground service
   keeps the callback/token transfer running while Chrome is foreground; a
   ViewModel alone can be frozen as a cached Android process. The service has a
   Cancel notification (if notifications are allowed), never restarts itself,
   and ends on completion/cancel/timeout. If Android kills the process, start a
   new attempt. Callback HTML removes authorization parameters from browser history.
2. Tap **Fetch account models**. Select a model from the account's catalog.
3. Run **Test text turn**, **Upload diagnostic image**, and **Test harmless
   function** separately. The generated image has a red square and blue circle.
   No personal photo or camera permission is used. The function has an exact
   fixed-value schema and only echoes `pixel-agent-test` after completed inference.
4. Record the model slug, completion result and displayed round-trip milliseconds
   for each trial. A failed/partial stream is never a passed turn. The tool trial
   has at most two requests and one local echo; it has no mail/file permissions.
5. Tap **Test token renewal**, rerun a text trial, restart the app, and fetch
   models again. Reconnect a saved connection and verify it retains the correct
   account. New connections remain separate from saved registrations.
6. Interrupt a request by disabling networking or tapping **Cancel experiment**.
   Verify an explicit failure/cancellation, no completed result, and no retry.
   Do not intentionally exhaust the account; record real usage-limit failures
   if encountered. Local tests simulate terminal usage failure after partial text.
7. Sign out when desired. Revocation is attempted before local tokens are cleared;
   a failed remote revocation is reported with a ChatGPT Settings recovery path.

The implementation validates state, PKCE, nonce, RS256 signature/JWKS, issuer,
audience, expiry, granted plan scope and returning account identity. Tokens and
the stable host ID live in an atomic AES-GCM record backed by Android Keystore
and excluded from backup/transfer. Refreshes are serialized and rotating tokens
are saved together. Outputs remain in memory; no request bodies/tokens are logged.
No automatic retries or paid fallback are present. Each request uses the public
Responses endpoint, bounded experiment input, `store:false`, and `stream:true`.

## Offline speech and lifecycle

1. Install language/voice data, reopen the app, and disable Wi-Fi and mobile data.
2. Tap **Test local voice** and confirm actual audible output.
3. Tap **Listen to a phrase**, grant the microphone, and say a known short phrase.
   Record expected text, actual text, language, and observed latency without
   including personal names or account details in the committed log.
4. Stop recognition and playback mid-operation. Repeat several times; background
   the app while each is active. Confirm the system microphone indicator clears.
5. Decline microphone permission, then grant it and retry. Revoke it through
   Android settings while testing, reopen, and verify a permission request or
   explicit failure rather than a stuck listening state.
6. Restore networking when finished. Speech availability flags and TTS completion
   callbacks alone cannot replace hearing and checking these trials.

## Short keyword session

1. Stop speech tests. Tap **Start local keyword session** while the app is visible;
   grant microphone and notification permissions. Look for the persistent Stop
   notification and a local listening state after the model loads.
2. Say **hello world**, with a pause between attempts. Count intended detections,
   misses and false detections. No AI call follows a detection in this spike.
3. Lock the phone and repeat. Unlock to inspect the counter; use the notification
   Stop action while locked. Verify the microphone indicator clears.
4. Repeat using the app's Stop control and the five-minute automatic timeout.
   Force-stop/permission loss should require an explicit fresh start. A killed
   process does not restart itself. The app's state is in-memory and returns to
   stopped after process death.
5. Record duration, screen/lock conditions, warm-up time, PSS/RSS, battery readings,
   ambient audio and detections. A partial CPU wake lock is held only for the
   bounded session. This is not evidence of low-power hardware hotword support.

Speech buttons are disabled during the keyword session to avoid app-owned
microphone contention and self-triggering. The service rejects silenced capture,
discards raw audio, and releases the recorder and native stream/model on stop.
Only a detection count and elapsed time are shown; no private readout is possible.

## Dependencies and sources

- [OpenAI registration/sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Account renewal/revocation](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Model catalog and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Preview request restrictions](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes)
- [sherpa-onnx v1.12.14](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.14),
  Apache-2.0; [license copy](third-party/sherpa-onnx-LICENSE.txt).
- [English GigaSpeech keyword model](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html),
  `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, Apache-2.0 according to
  the distributed model README. Int8 encoder/joiner, fp32 decoder, one CPU thread.
  Original model README and Apache license are bundled alongside the assets.
- Native ONNX Runtime is bundled by the engine AAR. Its unused Java JNI binding
  is excluded from the APK; lint still inspects the upstream AAR and reports that
  binding's 16 KB alignment. The tested Pixel uses 4096-byte pages; no 16 KB device
  claim is made. See [ONNX Runtime license](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)
  and [third-party notices](https://github.com/microsoft/onnxruntime/blob/main/ThirdPartyNotices.txt).
  Reference copies from upstream v1.23.2 are included in `docs/third-party` and
  packaged with the model assets; the AAR's runtime version is not independently
  measured by this lab.

The keyword and model were retained for the Phase 4 implementation. Phase 4 still
needs counted quality, false-wake and sustained battery tests; call/headset and
privacy hardening remain later gates. The original five-minute counter is a lab
service; use the main screen's hands-free control for conversation. See
[the Phase 4 guide](PHASE4.md).
