# Pixel 7 personal AI agent: feasibility and implementation plan

Research date: 27 October 2026 (Australia/Brisbane).
Scope: the eleven goals in `README.md`. Initial research started with goals only.
The API 36 Kotlin/Compose feasibility lab builds and deploys over USB to a stock
Pixel 7. Account text/image/function turns, renewal, and reconnect passed; the user
confirmed offline speech and a short locked-phone keyword/Stop trial. See
`docs/EXPERIMENTS.md` for exact evidence, conditions, and remaining checks.

## Work tracking

Last updated: 27 October 2026 (Australia/Brisbane).

**Current stage:** phase 0 complete. The feasibility lab is installed and its
exit gate passed on the Pixel, with the measurement limits in Experiment 002.
Full assistant functionality is not complete; phase 1 has not started.

Use the [implementation checklist](#6-implementation-checklist) as the source of
truth for task completion. `[x]` means the stated deliverable is complete; `[ ]`
means outstanding. Implementation and device validation have separate items, so
working code does not automatically count as a passed experiment. Record test
conditions, results, failures, and dates in [the experiment log](docs/EXPERIMENTS.md),
then update the matching task and phase status here. Keep task IDs stable as work
progresses. A phase is complete only when its exit gate in section 4 is satisfied.

**Next work:** phase 1 core conversation: connect text/push-to-talk to the selected
provider, stream replies and local playback, and add bounded context/cancellation/
history. Direct ChatGPT plan usage is selected for the tested personal local
prototype. Counted accuracy and unplugged battery comparisons remain Phase 4
benchmarks. See [the lab guide](docs/PHASE0.md) and Experiment 002.

## 1. Feasibility verdict

**A useful personal assistant is realistic.** Build a native Android app that runs its interface, speech, wake-word detection, memory, and Google integrations locally, with cloud AI for reasoning and image understanding. “Runs on the phone” must allow network access for the model, Gmail, and Drive. Fully offline ChatGPT reasoning is outside this architecture.

Two requirements need early validation:

1. **ChatGPT subscription access:** current official documentation describes a supported Sign in with ChatGPT route for eligible Responses requests from open-source/local apps. This is a promising fit, but it is a preview with account, tool, and request restrictions. Prove it works for this app and account before relying on it. Keeping the app closed source or distributing a hosted/paid product changes the assessment.
2. **Wake word:** opt-in listening sessions are plausible; a permanently available replacement for the system assistant is a separate, harder goal. Android service rules, microphone contention, battery consumption, and lock-screen behavior must be tested on the actual Pixel.

Recommendation: start with a sideloaded, single-user app, push-to-talk, local speech, explicit camera snapshots, inspectable memory, and a small tool set. Add hands-free sessions after the basic loop works. Do not make root access or a custom ROM prerequisites.

### Goal-by-goal assessment

| README goal | Assessment | Implementation and boundary |
| --- | --- | --- |
| Runs on Google Pixel 7 | Realistic | Kotlin Android app; prototype on the real phone early. |
| Gmail | Realistic, with OAuth work | Search/read initially; compose locally and require confirmation to send. |
| Google Drive | Realistic | Begin with selected documents and app-created files; whole-Drive search needs broader access. |
| Uses a ChatGPT subscription | Conditional, worth testing first | Official plan-usage OAuth flow for eligible open-source/local apps; API billing is an explicit fallback. |
| Camera vision | Realistic | Camera preview and user-requested still image; cloud vision when connected. |
| Local text-to-speech | Realistic | Installed offline Android voice, verified without network. |
| Local speech-to-text | Realistic, device/language dependent | Android on-device recognizer; bundled local engine if needed. |
| Wake word | Realistic within a tested session mode | Local keyword detector; dependable all-day locked-phone operation remains unproven. |
| On-device memory | Realistic | Local structured storage and retrieval, with edit/delete controls. |
| AI identity and natural conversation | Realistic | Persistent persona and selected memory; voice latency and turn-taking need tuning. |
| Camera/microphone status UI | Realistic and foundational | Explicit state display, camera preview, and a listening notification with Stop. |

The Pixel 7 has Tensor G2 and 8 GB RAM. My engineering assessment is that this is suitable for the proposed local components; that is not a benchmark of simultaneous speech models. Avoid loading an additional large language model in the MVP. Google specifies five years of OS/security updates for this device family; keep portability to a newer phone in scope. Sources: [Pixel hardware](https://support.google.com/pixelphone/answer/7158570), [update policy](https://support.google.com/pixelphone/answer/4457705).

## 2. Resolve model access before building around it

### Preferred experiment: direct ChatGPT plan usage

OpenAI documents an optional plan-usage capability for open-source/local apps. It does not import the user's ChatGPT history or account context. Paid or remotely hosted applications have a separate interest process. Source: [plan-usage overview](https://developers.openai.com/siwc/token-sharing-open-source).

Implement an isolated authentication spike using the system browser, PKCE, state/nonce validation, a persistent opaque host ID, and the documented dynamic registration flow. Persist the returned client ID and protect refresh credentials. The documented callback uses HTTP loopback on `127.0.0.1`; validate browser return, process survival, and token exchange on Android rather than assuming a desktop example transfers unchanged. Source: [registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in).

Fetch the signed-in account's model catalog and select an available model. Stream requests through the public Responses endpoint with `store: false` and `stream: true`. Count a turn as successful only on its completion event; handle terminal usage failures even after text has arrived. Do not rely on a hard-coded model name. Source: [models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference).

The preview accepts image inputs for compatible models but excludes audio/video input, transcription, and hosted connectors/MCP. Execute Gmail and Drive tools in the Android app. Retain conversation context locally and resend a bounded history; do not assume server-side conversation persistence. Implement the route's supported namespaced/custom-tool representation and omit unsupported request fields. This fits local STT/TTS well. Source: [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

**Pass gate:** the actual account can sign in, complete text and image turns, make a supported function call, renew credentials, and recover from disconnect/usage-limit errors. Also confirm the intended source-distribution model qualifies. Documentation establishes a route, not successful access for this account.

### Fallbacks and decisions

- If direct plan usage fails, preserve the provider interface and document why. Do not silently replace the subscription goal with paid API calls.
- For a paid API implementation, use a small authenticated backend to hold the provider key, enforce quotas, and relay streaming responses. Never ship a shared provider secret in the APK. A backend adds hosting and privacy work. Source: [OpenAI API quickstart](https://developers.openai.com/api/docs/quickstart).
- Codex app-server is a documented integration surface, but adding a desktop/server runtime would change the standalone-phone design. It is an alternative architecture, not an assumed Android dependency. Source: [app-server documentation](https://learn.chatgpt.com/docs/app-server).
- If subscription-only and standalone operation are both non-negotiable and the supported direct flow fails, pause model-dependent work. Browser scraping, copied session tokens, and ChatGPT UI automation are not an implementation foundation.

## 3. Proposed Android architecture

Use Kotlin, Jetpack Compose, coroutines/Flow, a ViewModel, Room, and a streaming HTTP client such as OkHttp. Start with one app module and clear package boundaries. Use API 31 as a proposed minimum for the on-device speech APIs; choose the current stable compile/target SDK at implementation time and test the phone's installed OS explicitly.

```text
Compose UI + ViewModel
    -> ConversationController + explicit state machine
        -> SpeechInput / SpeechOutput / WakeWordEngine
        -> MemoryRepository + PersonaRepository
        -> ModelProvider (plan-usage adapter or explicit API adapter)
        -> ToolDispatcher + permission/confirmation checks
            -> GmailRepository
            -> DriveRepository
            -> CameraController
```

Normal turn: activation -> local transcription -> retrieve relevant local context -> stream model response -> validate/execute permitted tools -> return tool results -> speak reply locally -> return to idle/listening.

Keep cancellation, timeouts, maximum tool rounds, and a turn ID in the controller. Limit the initial loop to five tool rounds. Persist pending mutations separately from conversational text. A restarted process must never automatically replay a send or file update.

Suggested package layout: `ui`, `conversation`, `model`, `speech`, `wakeword`, `camera`, `memory`, `google`, and `tools`. Add a backend directory only if the API fallback is selected.

### Local speech and turn-taking

- Check `SpeechRecognizer.isOnDeviceRecognitionAvailable()` and use `createOnDeviceSpeechRecognizer()`. Verify the chosen language is installed. Do not treat the ordinary recognizer or an “offline preferred” flag as a local-processing guarantee. The speech recognizer is for utterances, not the continuous wake-word loop. Source: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer).
- Select an Android TTS voice with `isNetworkConnectionRequired() == false`; install its data and verify playback in airplane mode. Source: [Voice](https://developer.android.com/reference/android/speech/tts/Voice).
- If the built-in speech path fails quality or availability tests, evaluate sherpa-onnx's Android STT/TTS examples. Benchmark one model at a time, including startup, memory, accent handling, and heat. Source: [Android speech examples](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/README.md).
- MVP turn-taking: pause wake-word capture during transcription and playback, then resume. Provide a Stop button; add spoken interruption later. Handle audio focus, phone calls, Bluetooth changes, and permission revocation. These are proposed application behaviors.

### Wake word and Android lifecycle

Prototype sherpa-onnx keyword spotting first; it has Android examples. Check the selected model's license separately from the engine's license. Porcupine is an alternative with Android support and an AccessKey requirement; evaluate licensing and offline authorization behavior before adopting it. Sources: [sherpa keyword APKs](https://k2-fsa.github.io/sherpa/onnx/kws/apk.html), [Porcupine Android](https://picovoice.ai/quick-start/wake-word-android/).

For normal-app hands-free mode, let the user start a microphone foreground service while the activity is visible. Declare the microphone service type and required permissions, show a Stop action, and expect Android restrictions on starting it from the background. Do not promise boot-time listening or automatic recovery after force-stop. Source: [foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Treat default-assistant integration through `VoiceInteractionService` as a later investigation. Selection as the assistant does not itself prove arbitrary custom wake words can use low-power hardware detection. Source: [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService).

### Camera and vision

Use CameraX preview and image capture with explicit front/rear selection. Initially capture one resized still image on request, release the camera afterward, and clearly show when an image will leave the device. Source: [CameraX](https://developer.android.com/media/camera/camerax).

Attach that image to a compatible model turn; validate this on the selected subscription route separately from ordinary API vision support. Start with descriptions and reading visible text. Defer continuous video and background capture. Source: [image input guidance](https://developers.openai.com/api/docs/guides/images-vision).

### Google authorization and integrations

Create a Google Cloud project, enable Gmail and Drive APIs, configure consent/test users, and register the Android package/signing certificate. Use Google Identity Services `AuthorizationClient` for incremental data authorization; Google authentication and API authorization are separate flows. Source: [Android authorization](https://developer.android.com/identity/authorization).

**Gmail:** start with `gmail.readonly` for search/read and request `gmail.send` only when sending is introduced. Read access is restricted; sending is sensitive. Compose drafts locally at first, avoiding a broader draft-management scope. Fetch bounded results and decode message MIME content; do not mirror the entire mailbox. Source: [Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes).

**Drive:** first use Android's Storage Access Framework to open user-selected documents through the installed provider. This grants document-URI access, not automatic Drive API authorization. Then add Drive API support with `drive.file` for app-created or explicitly authorized files. Whole-Drive search requires a separate broader-scope decision. Sources: [Android document access](https://developer.android.com/training/data-storage/shared/documents-files), [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

For Drive API reads, distinguish binary downloads from Google Workspace document exports. Begin with text and exported Google Docs; add other formats only with explicit extraction and size limits. Source: [download/export guidance](https://developers.google.com/workspace/drive/api/guides/manage-downloads).

Personal-use verification exceptions may apply. They do not establish that a public release is exempt, nor that transmitting restricted data to a cloud model is “local only.” Assess the actual data path before distribution; public use can require verification and a security assessment. Source: [restricted-scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification).

If a server OAuth flow is introduced, external projects in Testing can have refresh tokens expire after seven days. Test reconnect and revoked-consent behavior rather than assuming indefinite access. Source: [OAuth token lifecycle](https://developers.google.com/identity/protocols/oauth2/).

### On-device memory and identity

Use Room for `conversations`, `messages`, `memories`, `persona`, and `tool_actions`. Source: [Room](https://developer.android.com/training/data-storage/room).

Proposed design:

- Store durable memories as editable facts with provenance, timestamps, and optional expiry. Separate user-confirmed facts from generated summaries.
- Start with keyword/full-text retrieval and a fixed context budget. Add embeddings only if measured retrieval quality warrants them.
- Store the assistant's name, tone, and interaction preferences separately from factual memory. Load both into each turn.
- Provide “remember,” “forget,” inspect, export, and delete-all controls. Delete dependent summaries and indexes when deleting a memory.
- Keep raw audio ephemeral and images temporary by default. Exclude sensitive app data from automatic backups unless explicitly configured otherwise.
- Protect credentials with encryption backed by Android Keystore. Room is not automatically an encrypted database; select field/database encryption deliberately for sensitive content. Source: [Keystore](https://developer.android.com/privacy-and-security/keystore).

Local storage does not mean local inference: retrieved excerpts sent to the model leave the phone. Explain that boundary in settings and send only necessary context.

### Tool execution and visible state

Expose a narrow initial set: `search_email`, `read_email`, `prepare_email`, `send_email`, `read_selected_document`, `create_drive_note`, `remember`, and `forget`. Model function calls request actions; application code validates and executes them. Source: [function calling](https://developers.openai.com/api/docs/guides/function-calling).

Application rules: external content is untrusted data; enforce schemas and account/file boundaries; require an unlocked confirmation screen showing recipients, subject, body, attachments, or exact file changes before external mutations. Bind confirmation to the exact payload. After an ambiguous send timeout, reconcile state rather than blindly retrying. Treat tool-call IDs as local deduplication aids, not a guarantee of remote idempotency.

Review the cloud provider's processing settings against Google's permitted-use and transfer requirements before sending mailbox/document content. Disclose transfers, honor deletion, and do not use Workspace data to train a general model. Source: [Workspace developer policy](https://developers.google.com/workspace/workspace-api-user-data-developer-policy).

Show `Idle`, `Wake word enabled`, `Listening`, `Transcribing locally`, `Thinking online`, `Using Gmail/Drive`, `Awaiting confirmation`, `Speaking`, and `Camera active` from actual controller state. Show offline/error states, cancel controls, connected accounts, and pending actions. Locked-phone mode must withhold private mail readout and mutations until unlock.

## 4. Delivery sequence and acceptance gates

Estimates below are engineering estimates for one developer already comfortable with Android, working roughly full-time. They exclude third-party approval delays and are not measured commitments.

| Phase | Status | Effort | Deliverable and exit gate |
| --- | --- | --- | --- |
| 0. Resolve feasibility | Complete: tested on Pixel; evidence and measurement limits in Experiment 002 | 3–5 days | Minimal APK on the actual Pixel; prove subscription auth/text/image/tool/refresh, offline STT/TTS, and a short screen-off keyword session. Record OS, account eligibility, model versions, latency, RAM, and battery observations. Select provider route explicitly. |
| 1. Core conversation | Not started; audio scaffold exists | 1–2 weeks | Push-to-talk/text input, local speech, streamed model output, persona, cancellation, visible state, and persisted history. Survive network loss without executing phantom actions. |
| 2. Memory and vision | Not started | 1 week | Confirm/edit/forget facts across restarts; answer about an explicitly captured image; camera resources release on cancel/background. |
| 3. Google tools | Not started | 1–2 weeks | Search/read mail, prepare and explicitly send one test email, read a selected document, create a confirmed Drive note. Verify scopes, expired grants, account mismatch, and duplicate-send handling. |
| 4. Hands-free sessions | Not started | 1–2 weeks | Keyword -> utterance -> reply with screen off in a user-started session; Stop works; measure false wakes, missed wakes, and incremental battery use. |
| 5. Daily-use hardening | Not started | 1–2 weeks | Lock-screen privacy, calls/headsets, process recreation, deletion/export, permission changes, bounded storage, and several days of real use. |

Allow roughly **6–10 weeks** for a personal MVP with contingency; several months is more realistic when learning Android alongside the project. A public app needs a separate release plan for verification, policy review, distribution requirements, support, and account/backend operations.

### Measurements to make the decision concrete

These are proposed acceptance targets, not advertised performance:

- Voice: correctly capture at least 27 of 30 representative commands in a quiet room, including Australian names and email addresses. Confirm ambiguous recipients instead of guessing.
- Offline speech: transcribe and speak with networking disabled after model installation; cloud reasoning should clearly report unavailable.
- Conversation: aim for median end-of-speech to first spoken answer below four seconds on a stable connection; measure p95 separately. If missed, optimize endpointing, model choice, and sentence-level playback.
- Wake word: at least 45/50 intended detections in the tested setting and at most one false activation during eight hours of representative ambient audio. Repeat with TV/music and the screen locked.
- Battery: target less than five percentage points of additional drain over an eight-hour idle baseline for wake listening. Compare repeated runs on the same phone, network, and screen conditions; reduce session duration if unacceptable.
- Reliability: permission loss, service termination, reboot, and network changes produce honest UI state and an explicit restart path. No claim of all-day reliability before an actual all-day test.
- Actions: one confirmed test send produces exactly one message; cancellation, replay, injected instructions in mail, and interrupted streams produce no unintended mutation.
- Memory: a confirmed fact survives restart; forgetting it removes the fact and derived retrieval content from subsequent prompts.

## 5. Cost and scope controls

Existing hardware is enough to begin; do not buy another phone for the prototype. Budget developer time first. Subscription-based inference draws on the user's available plan allowance and must handle exhaustion; it is not unlimited capacity.

For the API fallback, estimate monthly USD cost from measured workload:

```text
monthly inference cost =
    input_tokens / 1,000,000 * selected_input_rate
  + output_tokens / 1,000,000 * selected_output_rate
  + image/tool charges where applicable
  + hosting
```

For planning, 50 turns/day at 2,000 input and 250 output tokens/turn means about 3 million input and 375,000 output tokens over 30 days, before images, reasoning overhead, and additional tool rounds. Insert the selected model's current rates; avoid assuming this token estimate predicts actual bills. Source: [API pricing](https://developers.openai.com/api/docs/pricing).

Use a locally enforced daily request/token budget, bounded context, and backend enforcement if a backend exists. Keep retries bounded. No automatic switch from subscription to billable API access. Local speech eliminates a cloud speech dependency, but models, licensing, storage, and device battery still have costs.

Defer continuous video, autonomous sending/deletion, whole-account indexing, fully local general reasoning, background inbox monitoring, and hardware hotword integration. These can be reconsidered using measurements from the MVP.

## 6. Implementation checklist

### Phase 0 — Scaffold and feasibility

Completed items below were verified on 27 October 2026. Build/deployment evidence
is in [Experiment 001](docs/EXPERIMENTS.md#experiment-001-stock-pixel-connection-and-diagnostics-scaffold).
Items labeled “implement” confirm code exists and builds; their separate trial
items remain open until exercised on-device.

- [x] **P0-01:** Research each README goal, document feasibility, architecture, risks, estimates, and primary sources in this plan.
- [x] **P0-02:** Locate a working Android SDK, compatible JDK, and Gradle installation; resolve the helper's Java 25 incompatibility by selecting Java 17/21.
- [x] **P0-03:** Enable and authorize USB debugging; establish ADB access to the stock Pixel 7 without rooting or resetting it.
- [x] **P0-04:** Record device model, Android release/API, CPU ABI, phone language, and reported speech capabilities. Observed: Pixel 7, Android 16/API 36, arm64-v8a, en-US, recognizer available, `en-us-x-tpf-local` voice.
- [x] **P0-05:** Create the Kotlin/Compose app module, pinned dependencies, Gradle wrapper, and Git exclusions for local configuration, outputs, and signing files.
- [x] **P0-06:** Implement diagnostics UI, ViewModel/Flow state, explicit audio states, and a separate unconfigured model-provider interface.
- [x] **P0-07:** Implement an on-device-only utterance recognition path, runtime microphone permission request, transcript display, and recognition error messages.
- [x] **P0-08:** Implement offline-English voice selection, local playback controls, and transcript readback.
- [x] **P0-09:** Implement Stop/background audio cleanup, stale callback guards, app icon, and backup/transfer exclusions.
- [x] **P0-10:** Add and exercise PowerShell `doctor`, `build`, `install`, and `run` actions; document setup and speech-test steps in README.
- [x] **P0-11:** Build and lint the final scaffold, install it, launch it on the Pixel, inspect the rendered diagnostics and recent startup crash logs. Result: zero lint errors, five advisory warnings; startup succeeded.
- [x] **P0-12:** User confirmed audible local TTS with networking disabled on 27 October 2026 (Experiment 002).
- [x] **P0-13:** User confirmed the offline on-device recognition/transcript trial on 27 October 2026 (Experiment 002).
- [x] **P0-14:** Stop, listening/playback backgrounding, runtime denial, revocation during a started listening attempt, and regrant/retry exercised. AppOps recording durations ended without a running operation; UI returned to Idle. Original microphone grant/flags restored.
- [x] **P0-15:** Upgraded compile/target SDK to 36 with AGP 8.10.1; build/lint/reinstall and repeated audio lifecycle checks passed on Android 16.
  - [x] **P0-15a:** Compile/target API 36, AGP 8.10.1; assembly/lint and automated Pixel keyword start/screen-off/Stop check pass. Reinstalled and launched the lab.
  - [x] **P0-15b:** User confirmed offline utterance/playback/Stop; automated and ADB/UI backgrounding, denial/revocation, and retry checks passed with the upgraded build.
- [x] **P0-16:** Recorded storage, battery/charging condition, speech/model versions, initial RAM snapshots, and cloud round-trip latency in Experiment 002. User confirmed actual offline speech. USB charging prevents discharge conclusions; sustained battery comparison remains Phase 4.
  - [x] **P0-16a:** Add `measure` helper; record storage, battery/charging state, page size, candidate speech package versions, and initial RAM snapshots in Experiment 002. Pin and record the keyword engine/model.
  - [x] **P0-16b:** Record completed model timings and post-use RAM/battery observations. Speech timing distributions, peak RAM, and unplugged comparative drain remain later benchmarks.
- [x] **P0-17:** User chose private source and accepted user-started sessions on 27 October 2026. Paid fallback is not approved; only subscription access is implemented. Private-source provider eligibility still requires confirmation under P0-18/P0-20.
- [x] **P0-18:** Implemented and verified operational account access, browser callback/code exchange, protected storage, renewal, and reconnect on the Pixel (Experiment 002). Temporary authentication foreground service fixes browser/background callback progress.
  - [x] **P0-18a:** Implement browser loopback OAuth/PKCE, state/nonce/signed identity checks, separate registrations, Keystore storage, serialized renewal, reconnect, and revocation. Security tests and a Pixel encrypted-storage test pass.
  - [x] **P0-18b:** Actual account sign-in/exchange, completed inference, renewal, saved credentials across APK replacement, and reconnect passed. A killed pending sign-in requires a fresh attempt; full process-recreation hardening remains Phase 5.
- [x] **P0-19:** Completed text/image/function turns, renewal, actual offline request failure and online recovery validated. Model/timings recorded below. Terminal usage-limit and interrupted-stream cases pass JVM tests; account allowance was not deliberately exhausted.
  - [x] **P0-19a:** Implement catalog selection and explicit text/generated-image/namespaced-echo test controls. Stream tests reject disconnect, incomplete completion, and terminal usage failure after text.
  - [x] **P0-19b:** `gpt-6-luna`: text 2322 ms, generated image 2571 ms, namespaced function/follow-up 6142 ms, renewed-token text 7054 ms. Single observations, not percentiles.
  - [x] **P0-19c:** With Wi-Fi/mobile data disabled, the text trial reported `UnknownHostException` and no completion/action. Restored original networking; manual retry completed in 3705 ms. Partial-stream and terminal usage-limit failures are simulated in JVM tests; richer in-flight cancellation tests remain Phase 1.
- [x] **P0-20:** Selected direct ChatGPT plan usage for the tested single-user local prototype after completed text/image/function/renewal/reconnect trials. No paid fallback. This is operational account evidence, not approval for a different distribution model.
- [x] **P0-21:** Implemented the short local keyword spike. User confirmed locked-phone `hello world` detections and notification Stop/microphone release; automated screen-off/native-engine checks pass. Initial USB-charging battery observations are in Experiment 002. Accuracy rates and discharge behavior remain unmeasured.
  - [x] **P0-21a:** Implement five-minute sherpa-onnx keyword service, offline bundled model, notification Stop, timeout, audio cleanup, and explicit restart. Pixel tests detect a prerecorded keyword and pass a five-second screen-off session/Stop trial.
  - [x] **P0-21b:** User confirmed live locked-phone keyword/notification Stop trial; post-trial UI showed 3 detections over 16 seconds. Attempt count unknown; do not infer detection rates. Full timeout, permission-loss stress, and unplugged drain remain Phase 4/5 validation.
- [x] **P0-22:** Reviewed Experiment 002 and closed the phase-0 feasibility gate for the personal local prototype on 27 October 2026. Provider access, offline speech, short locked keyword use, basic lifecycle/recovery and initial measurements are evidenced. No all-day reliability, accuracy-rate, peak-RAM, or battery-drain claim; phase 1 remains separate.

### Phase 1 — Core conversation

- [ ] **P1-01:** Add text input and connect push-to-talk transcription to the selected model provider and local speech output.
- [ ] **P1-02:** Implement streaming replies, sentence-level playback, persona settings, and bounded conversation context.
- [ ] **P1-03:** Extend the controller with turn IDs, cancellation, timeouts, bounded retries/tool rounds, and network/error states.
- [ ] **P1-04:** Persist conversation history and restore it safely after process recreation; never replay pending mutations automatically.
- [ ] **P1-05:** Verify a complete voice/text conversation, cancellation, and network interruption on-device; record median/p95 response latency against section 4 targets.

### Phase 2 — Memory and vision

- [ ] **P2-01:** Add Room storage for memories/persona, provenance, timestamps, expiry, and bounded keyword/full-text retrieval.
- [ ] **P2-02:** Add confirm/edit/remember/forget/export/delete controls, including removal of derived summaries and indexes.
- [ ] **P2-03:** Verify memory persistence across restart and deletion from subsequent model context.
- [ ] **P2-04:** Add CameraX preview, front/rear selection, explicit snapshot capture, resize, temporary-file cleanup, and visible camera/upload state.
- [ ] **P2-05:** Verify an answer about a user-captured image through the selected provider; confirm cancel/background releases the camera and uploaded context is clear to the user.

### Phase 3 — Gmail and Drive tools

- [ ] **P3-01:** Configure the Google Cloud project, APIs, Android OAuth registration, consent/test users, and incremental authorization.
- [ ] **P3-02:** Implement bounded Gmail search/read with MIME extraction and local email preparation using the minimum required scopes.
- [ ] **P3-03:** Implement selected-document access through Android's document picker, then scoped Drive API access and Google Docs export as needed.
- [ ] **P3-04:** Add schema-validated tool dispatch, account/file boundaries, untrusted-content handling, and an exact-payload confirmation screen for mutations.
- [ ] **P3-05:** Implement confirmed Gmail sending and Drive note creation, including interrupted-send reconciliation and local deduplication.
- [ ] **P3-06:** Verify read tools, one confirmed test email, and one confirmed Drive note; test cancellation, expired/revoked grants, account mismatch, injected instructions, and ambiguous timeouts.
- [ ] **P3-07:** Document the actual Google-data/cloud-model path, required disclosures, permitted use, and any verification/security-assessment requirements for the intended distribution.

### Phase 4 — Hands-free sessions

- [ ] **P4-01:** Select a keyword engine/model after the phase-0 spike; record licensing, model size, quality, and offline behavior.
- [ ] **P4-02:** Implement user-started microphone foreground-service sessions with correct permissions, notification, Stop action, and visible listening state.
- [ ] **P4-03:** Integrate keyword detection, utterance recognition, model turns, and playback without microphone conflicts or self-triggering.
- [ ] **P4-04:** Measure intended/missed/false wakes, locked-screen operation, and incremental eight-hour battery drain against section 4 targets.
- [ ] **P4-05:** Verify service termination, reboot/force-stop recovery messaging, and explicit restart behavior; document supported session limits.

### Phase 5 — Daily-use hardening

- [ ] **P5-01:** Enforce unlock before private readout and external mutations; verify lock-screen behavior.
- [ ] **P5-02:** Exercise calls, Bluetooth/headset changes, audio focus, permission changes, process recreation, and network transitions.
- [ ] **P5-03:** Finish credential/content encryption, bounded storage, temporary-media cleanup, and user-controlled export/deletion; verify backup/transfer behavior.
- [ ] **P5-04:** Add/enforce daily request/token budgets and bounded context; test exhaustion without automatic billable fallback.
- [ ] **P5-05:** Run the representative 30-command speech trial and several days of normal usage; record failures and fix material regressions.
- [ ] **P5-06:** Review all phase exit gates and document remaining limitations before calling the personal MVP complete. Plan public distribution separately if requested.

**Current decision:** phase 0 complete for the tested personal local prototype.
The API 36 lab is deployed; automated checks and live account/offline speech/
locked-keyword/basic lifecycle/network-recovery trials pass. Use direct ChatGPT
plan usage. Proceed to phase 1 when requested; sustained accuracy/battery and
daily-use reliability remain later work. See Experiment 002 and `docs/PHASE0.md`
for exact evidence and limits.
