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

These were pending at the end of Experiment 001. Experiment 002 below supersedes
the implementation/validation status; an availability flag alone is still not a
passed trial.

## Next experiments

1. Verify local speech on the device, including airplane-mode runs.
2. Implement and test the documented model-authentication route separately.
3. Measure a user-started local keyword-listening session with the screen locked.

Keep tokens, personal transcripts, email contents, and device serial numbers out of
this log. See `PLAN.md` for the phase-0 acceptance gates.

## Experiment 002: API 36, account access, offline speech, and keyword spike

Date: 27 October 2026 (Australia/Brisbane). Device: same stock Pixel 7,
Android 16/API 36, arm64-v8a, en-US, 4096-byte memory pages.

### Decisions and implementation

- User chose private source/personal local installation and accepted user-started
  listening sessions. No billable API fallback was approved or introduced.
- Compile/target SDK upgraded to 36; AGP 8.10.1, Gradle 8.11.1, Kotlin 2.1.10,
  JDK 21, Build-Tools 35.0.0. Dependency versions are pinned, not claimed latest.
- Implemented browser OAuth with stable host ID, dynamic registration, loopback
  callback, PKCE, state/nonce, RS256/JWKS/issuer/audience/expiry/subject checks,
  granted-scope enforcement, separate saved connections, renewal and revocation.
  An atomic AES-GCM record uses Android Keystore and no-backup storage.
- Model catalog selection and explicit text, synthetic-image, fixed-schema
  namespaced echo, and renewal experiments use the public Responses endpoint.
  Requests use `store:false`, `stream:true`, no automatic transport retries,
  no paid fallback, and no mail/file access. Tool results require completion.
- Implemented sherpa-onnx 1.12.14 with the English GigaSpeech 3.3M keyword model
  dated 2024-01-01: int8 encoder/joiner, fp32 decoder, one CPU thread. Bundled
  files are prepared from checksum-verified downloads; see `docs/PHASE0.md`.
  The five-minute microphone service has a persistent Stop notification, partial
  wake lock, native-resource cleanup, no automatic restart, and no saved audio.

### Build and automated device evidence

- `assembleDebug`, `lintDebug`, and JVM tests passed. Eleven JVM tests cover
  PKCE, valid/signed and rejected identities, callback state/client binding,
  terminal usage failure after text, incomplete/disconnected streams, and final
  envelopes that omit already-streamed output. Zero lint errors; remaining
  advisories concern pinned dependency updates, KTX suggestions, an Intent-related
  false positive, and a native binding in the upstream AAR.
- Three Pixel instrumentation tests passed, with no skipped tests: a Keystore
  encrypted-record round trip across store instances, a known prerecorded keyword
  decoded by the native engine, and user-visible foreground-service start followed
  by five seconds screen-off and explicit Stop. The storage fixture uses a
  separate test record, not account credentials.
- The prerecorded fixture contains `LIGHT UP`; it is not evidence for live
  `hello world` microphone accuracy. The later user trial below covers live use.
- Excluded the unused `libonnxruntime4j_jni.so` from the APK; verified its absence
  in the APK archive. Lint still flags the original AAR's copy for 16 KB alignment.
  The tested phone has 4 KB pages; no 16 KB device claim is made.
- Initial lint run hit a Windows/Gradle JAR lock; stopping idle Gradle daemons
  allowed a fresh check. Fixed escaping in the ignored SDK `local.properties`.
- Initial storage instrumentation fixture targeted the test package's unwritable
  data directory; corrected it to a separate record in the target's no-backup
  directory and reran successfully.
- Gradle's connected-test cleanup removed the test installation. Reinstalled the
  debug APK before sign-in. Subsequent `adb install -r` updates retained the real
  account connection. Do not rerun uninstalling instrumentation on a signed-in
  installation without planning for app-data loss.
- Final checked APK installed and launched; inspected the rendered diagnostics.
  No AndroidRuntime crash was returned in the inspected recent startup log.

### Actual account/browser/provider trials

User completed browser authentication. The consent page initially appeared stuck
on a spinner; returning Pixel Agent to the foreground allowed sign-in to finish.
This was consistent with callback work being suspended while the app was cached.
Added a five-minute data-sync foreground service for the callback/token transfer.
On the repeat reconnect, ADB confirmed the authentication service was foreground
while Chrome was open. After consent, the browser reached the local completion
page without bringing the app forward; the service ended. The **Open Pixel Agent**
link reopened the existing activity and the validated connection was active.
No token or authorization URL was printed to the log.

The first text experiment reached completion but the initial collector relied on
the terminal response envelope for output and displayed no text. Fixed collection
of text deltas and finished output items, retaining the completion requirement;
added two regression tests. The corrected build then passed:

| Trial | Model slug | Observed round trip | Result |
| --- | --- | --- | --- |
| Fixed text prompt | `gpt-6-luna` | 2322 ms | Completed with the requested diagnostic text |
| Generated image | `gpt-6-luna` | 2571 ms | Correctly described the red square, blue circle, relative positions, white background |
| Namespaced `diagnostics.echo` plus follow-up | `gpt-6-luna` | 6142 ms | One exact-payload local echo after completion, followed by completed text |
| Forced credential renewal | Same connection | Not separately timed | Renewed credentials saved successfully |
| Text after renewal | `gpt-6-luna` | 7054 ms | Completed with the requested diagnostic text |
| Saved-connection reconnect | Same connection | Not separately timed | Browser callback, identity check, and app-return link succeeded |

These are single round-trip observations, including HTTP setup and stream
completion; the function trial includes two requests. They are not first-token,
end-of-speech, p50/p95, or spoken-answer latency measurements. No personal image,
mail, document or transcript was sent. Model tests used the user's plan allowance.

**Provider decision:** use direct ChatGPT plan usage for this tested single-user
local prototype. Completed inference and renewal establish operational access for
this account/install. They do not establish permission for a paid/hosted/public
distribution or promise future account/model availability.

### Human speech and locked-phone trials

- After login was resolved, the user was instructed to disable Wi-Fi/mobile data,
  test audible local TTS, verify an on-device transcript, and try Stop during
  listening/playback while checking microphone release. User reported **works**.
  Record P0-12/P0-13 and these Stop checks as passed by user observation. Expected
  phrase, transcript, timing and attempt count were not supplied; no accuracy
  percentage is inferred.
- User was instructed to start the keyword session, say `hello world` with the
  phone locked, and use the notification Stop action while checking microphone
  release. User reported **all worked**. This establishes the short feasibility
  trial by user observation. A later screen inspection showed 3 detections over
  16 seconds, session stopped. The attempt count was not supplied; false-wake/miss
  rates and all-day reliability remain unmeasured.
- Post-trial service inspection returned no active keyword or authentication
  foreground service. Additional lifecycle/recovery results are recorded below.

### Initial measurements and limits

`scripts/dev.ps1 measure` collected these snapshots, without device serials or
account/transcript data:

- `/data`: 229 GiB total; approximately 223–224 GiB free.
- Battery reports health `2` (good). USB powered/charging throughout the measured
  snapshots: 63% at 34.0 °C, 65% at 33.8 °C, and post-trial 78% at 33.6 °C.
  Charge counters included 2,530,000 and post-trial 3,120,000 µAh. **These are
  charging-condition observations, not discharge or incremental-drain results.**
- Prior scaffold foreground PSS: 123,914 KiB. API 36 process after native-fixture
  testing: 103,012 KiB PSS / 239,576 KiB RSS. Post interactive account/speech/keyword
  trials: 175,746 KiB PSS / 318,428 KiB RSS. Conditions differ; these snapshots do
  not establish peak model RAM, a leak, or an apples-to-apples memory improvement.
- Available local voice: `en-us-x-tpf-local`.
- Google TTS package version:
  `googletts.google-speech-apk_20241125.02_p2.702443970`.
- Android System Intelligence: `B.0.playstore.pixel7.738427685`.
- Google app: `16.20.49.sa.arm64`.
- These package versions are diagnostics candidates, not independently identified
  on-device STT model weight/version numbers.

### Final lifecycle/recovery checks and exit decision

- With the final checked APK, disabled Wi-Fi and mobile data for one text trial.
  UI reported `UnknownHostException`, no completion, and no tool execution. Both
  network flags were restored to their original enabled values in a `finally`
  block. A manual online retry completed on `gpt-6-luna` in 3705 ms.
- Revoked the microphone grant, reopened the app, and tapped Listen: Android
  displayed the runtime request. Explicit denial produced the app's declined
  notice and Idle state. Restored the grant and successfully retried listening.
- Started recognition then backgrounded the app: AppOps reported a completed
  551 ms recording operation, not a running operation. Repeated listening/Stop;
  later AppOps durations also ended, with no running record operation.
- Revoked permission during a started listening attempt: Android terminated the
  process/recording as expected. Regranted, relaunched, retried and stopped audio
  without a stuck listening state. This brief test does not cover every possible
  mid-utterance permission race.
- Started local playback, fully backgrounded the app for two seconds, reopened it,
  and inspected stable Idle state with Stop disabled. User's earlier test had
  confirmed audible offline output and Stop.
- Restored the original microphone grant and permission flags; cleared the
  test-created `USER_FIXED` flag while retaining the original `USER_SET` and
  sensitivity flags. AppOps mode returned to `foreground`, with an ended recording
  duration. Removed the temporary browser debugging forward and device UI dump.
  Account credentials remain on the phone; no running audio/auth service remains.
- Final source build, lint and eleven JVM tests passed. The three native/storage/
  service device tests passed earlier in this experiment; human and ADB/UI trials
  then exercised the final installed app without uninstalling its account data.

**Exit decision:** phase-0 feasibility gate passed for this personal local
prototype. The evidence covers operational provider access, text/image/function
completion, renewal/reconnect, actual offline speech, short locked-phone keyword
use, basic permission/audio lifecycle recovery, and initial measurements.

Full timeout/permission-loss stress, counted wake accuracy, sustained unplugged
battery comparisons, calls/headsets, in-flight conversation cancellation, and
multi-day reliability remain later-phase work. Partial-stream/terminal usage-limit
errors are simulated in JVM tests; the actual account was not deliberately
exhausted. No end-to-end conversational assistant or all-day behavior is claimed.

For repeatable steps and implementation boundaries, see [the lab guide](PHASE0.md).

## Experiment 003 — Phase 1 initial build and launch

27 October 2026: assembled the phase 1 debug build, ran lint and JVM unit tests successfully, installed it over the existing app on the connected Pixel 7, and launched it successfully. The UI hierarchy showed the conversation, message, push-to-talk, cancellation and persona controls, with the saved account connected and model selection required. This is a startup/UI smoke test only: no end-to-end conversation, cancellation, network interruption, process-recreation or latency distribution was measured. Phase 1's exit gate remains open.

Follow-up on the same date: a live text turn returned `PHASE ONE TEST`; force-stopping and relaunching restored the exchange. A second turn was cancelled immediately and displayed cancellation with no saved answer. A request while Wi-Fi and mobile data were briefly disabled reported `UnknownHostException` and discarded its partial response. Both network settings were restored and a subsequent turn correctly recalled the earlier phrase. The disconnect test did not prove a partial response had already arrived. These checks do not establish voice latency or p95.

The user confirmed that a push-to-talk phrase was transcribed and its reply spoken on the first conversation build. An added latency callback subsequently caused a stack overflow at end of speech during the measured trial; the callback name collision was fixed, the app rebuilt and reinstalled, and a repeat voice trial is pending. Do not count the initial voice trial as a latency sample.

The repeat voice turn after fixing the stack overflow completed and the user confirmed audible playback. The timing display still reported no samples; a missing utterance-registration step was fixed and the app rebuilt and reinstalled. That latest timing fix has not yet been exercised in a live voice trial. Phase 1 remains open until the interruption and latency measurements are completed.

After registering queued utterance IDs, the user confirmed another completed voice reply. The app recorded 3,194 ms from recognizer end-of-speech callback to first local playback start. This is one sample on the connected Pixel and cannot establish a meaningful median or p95; more measured turns are required.

## Experiment 004 — Phase 1 controller and bundled skills

28 October 2026 (Australia/Brisbane). Stock Pixel 7, reporting Android 17/API 37;
the app still compiles/targets API 36 with minimum API 31. Installed debug updates
over the existing signed-in app with `adb install -r`. No uninstall/data clearing.
The user explicitly enabled live testing after clarification that speech/storage
run locally and ChatGPT reasoning sends context to OpenAI. Live instrumentation
used only synthetic prompts and bundled skills, with the existing plan connection
and account-catalog model `gpt-6-luna`; no saved conversations were uploaded.

### Implemented behavior

- Normal turns now use namespaced `skills.activate`/`skills.read_resource` through
  a shared controller: 90-second turn timeout, five tool rounds, four calls per
  response, twelve total calls, validated arguments/results and duplicate-ID
  rejection. Tools execute only after a completed response. Zero automatic
  inference retries; cancellation never replays work.
- Cancellation clears busy state immediately and invalidates delayed stream/audio
  callbacks. Removed the old job's unconditional busy-state cleanup, which could
  reset a newer turn. Cancellable HTTP requests close the outstanding call when
  the coroutine is cancelled or times out. Error states distinguish network,
  account/usage, timeout, cancellation and generic failure.
- Safe YAML parsing and immutable APK-asset discovery; bounded catalog, packages,
  resources, active context and tool output. Malformed packages are isolated;
  unknown runtime requirements remain visible/unavailable. Script execution and
  filesystem imports are unsupported. Two instruction/resource skills are bundled:
  `clear-explanation` and `daily-briefing`.
- Explicit/model-selected activation, application-owned skill-loading permission,
  and restoration of skill names/resource paths by re-reading bundled content.
  Skill metadata and saved text cannot grant native permission. Tool transcripts
  and pending actions are never restored.
- Complete history is bounded by pair count and escaped JSON size, preserving
  whole recent exchanges. Persona/history remain local preferences in this phase;
  durable factual memory and content encryption remain later tasks.

### Verification and observed failures

- Final assembly, lint and **35 JVM tests passed**, with no lint errors. Coverage
  includes existing OAuth/stream handling plus YAML/name/metadata validation,
  unsafe tags/aliases/duplicate keys, incompatible runtimes, activation/resource
  limits, traversal/missing paths, native denial, invalid/extra arguments,
  duplicate/oversized tool batches, bounded rounds, timeout/cancellation,
  partial-stream failures without retry, restored context without call replay,
  and escaped history bounds. Existing dependency/KTX/native-library advisories
  remain; no 16 KB device compatibility claim is added.
- Three offline Pixel tests passed: bundled discovery/resource/permission and
  scratch-preference restoration; malformed/incompatible packages; cancellation
  and simulated partial-stream failure without actions/replay.
- Two initial live tests passed in 27.114 seconds total: actual model-selected
  activation/resource loading followed by an answer containing the resource's
  synthetic check phrase; a separate turn rebuilt active resource context and
  answered without further calls. Another live turn requested activation after
  the native grant was revoked, received `permission_denied`, and left the active
  session empty.
- The expanded first seven-test run had three subsequent live failures with
  `UnknownHostException`: the network interruption trial had correctly restored
  radio switches, but the next tests ran before connectivity was usable. Fixed
  the test harness to wait (at most 30 seconds) for a validated network and DNS
  resolution after restoring the original Wi-Fi/mobile-data flags. This adds no
  production inference retry.
- **All seven expanded Pixel tests then passed in 40.949 seconds total**, with
  no skipped tests: the three offline cases, the two live skill cases, a real
  streamed-turn cancellation followed by an explicit successful new request, and
  a real stream interrupted after its first text delta by temporarily disabling
  Wi-Fi/mobile data. The interrupted request failed rather than completing or
  executing tools. Both network settings were restored and checked in `finally`;
  later live turns succeeded. These totals are test-suite wall times, not response
  latency samples.
- Added `scripts/dev.ps1 test-phase1` to install/update and invoke the selected
  offline tests without Gradle's uninstalling connected-test cleanup. Explicit
  `-LiveModel <slug>` enables live cases. The helper reports instrumentation
  failures even when `am instrument` exits zero.
- Startup inspection showed the conversation app and persisted earlier voice
  sample. No new fatal startup crash was returned in the inspected recent
  AndroidRuntime output. The final history-bound refactor then passed the full
  build/lint/JVM checks and was installed/launched for further manual testing.

### Remaining gate

P1-03/P1-06/P1-07 implementation and P1-08 skill validation are complete. P1-05
remains open for a representative set of real microphone-to-model-to-local-voice
turns, audible playback/Stop confirmation on the current build, and voice
median/p95 against the below-four-second median target. The previous 3,194 ms
sample is still the only observed voice timing; it is not a useful distribution.
Text Send-to-first-delta and voice end-of-speech-to-first-playback are recorded
separately, with at most 100 samples each; attempts that reached first output may
still fail later. No wake accuracy, all-day reliability or battery claims follow.
See [PHASE1.md](PHASE1.md) for the repeatable manual trial and runtime limits.
