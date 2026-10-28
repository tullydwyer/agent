# Pixel 7 personal AI agent: feasibility and implementation plan

Research dates: 27–28 October 2026 (Australia/Brisbane).
Scope: the goals in `README.md`, including standard Agent Skills and research into
generic control of other apps without root. Initial research started with goals only.
The API 36 Kotlin/Compose feasibility lab builds and deploys over USB to a stock
Pixel 7. Account text/image/function turns, renewal, and reconnect passed; the user
confirmed offline speech and a short locked-phone keyword/Stop trial. See
`docs/EXPERIMENTS.md` for exact evidence, conditions, and remaining checks.

## Work tracking

Last updated: 28 October 2026 (Australia/Brisbane).

**Current stage:** phase 0 complete; phase 1 code implemented with voice latency validation open. Phase 2 memory and camera code is implemented; integrated restart/forget and user-captured image-answer gates remain open (Experiment 006). Phase 4 hands-free code and a visible conversation interface are implemented at the user’s request, ahead of app-control/Google skills. Device and measurement evidence is tracked in Experiment 005; implementation does not close the sustained accuracy/battery gate.

**User decision (28 October):** the phone and its accounts/identity are dedicated
to the agent. Setup should be ordinary sign-in to installed apps plus one-time
Android control permissions. Gmail, Drive, and other app workflows will be Agent
Skills using those signed-in app sessions through a shared phone-control driver.
Google Cloud projects, API enablement, developer OAuth registration, consent/test
users, and incremental Google API authorization are outside the selected path.
App control is now a required prerequisite for Gmail/Drive, with on-device
feasibility still unproven. See [research and sources](docs/SKILLS-AND-APP-CONTROL.md).

Use the [implementation checklist](#6-implementation-checklist) as the source of
truth for task completion. `[x]` means the stated deliverable is complete; `[ ]`
means outstanding. Implementation and device validation have separate items, so
working code does not automatically count as a passed experiment. Record test
conditions, results, failures, and dates in [the experiment log](docs/EXPERIMENTS.md),
then update the matching task and phase status here. Keep task IDs stable as work
progresses. A phase is complete only when its exit gate in section 4 is satisfied.

**Phase 2 next:** run the integrated restart/forget and explicit snapshot/model
trials in [the Phase 2 guide](docs/PHASE2.md). Implementation and local device
tests are recorded separately from those live exit gates in Experiment 006.

**Next work:** collect Phase 4 wake/battery measurements and verify silence fallback. The user confirmed the integrated voice loop and automatic follow-ups without repeating the wake word on the Pixel (Experiment 005). Skills will be added later at the user’s request. Also finish the phase 1 voice-turn latency distribution and playback/Stop trial. See [the Phase 1 guide](docs/PHASE1.md) and Experiment 004 for controller/skill checks and exact remaining evidence. Direct ChatGPT plan usage is selected for the tested personal local
prototype. Then prove the shared app-control driver (P6 tasks, retained IDs)
before implementing Gmail/Drive skills. Counted accuracy and unplugged battery comparisons remain Phase 4
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
| Gmail | Depends on app-control gate | Operate the already signed-in Gmail app through a skill; verify reading, composing, and sending on-device. No developer API setup. |
| Google Drive | Depends on app-control gate | Operate signed-in Drive and its installed document editors through a skill; validate supported search/read/create/edit flows. |
| Agent Skills | Realistic; required architecture | Standard `SKILL.md` packages, catalog and on-demand activation/resources; Gmail/Drive skills use shared phone-control tools. Desktop scripts require compatible runtimes. |
| Control other Android apps without root | Required architecture; compatibility unproven | User-enabled Accessibility and supported intents operate agent-owned app sessions. Validate each supported workflow on the phone. |
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
        -> SkillRegistry + activation/resource loading
        -> ToolDispatcher + permission/confirmation checks
            -> AppControlDriver (required feasibility gate)
                -> Signed-in Gmail / Drive / other installed apps
            -> CameraController
```

Normal turn: activation -> local transcription -> retrieve relevant local context -> stream model response -> validate/execute permitted tools -> return tool results -> speak reply locally -> return to idle/listening.

Keep cancellation, timeouts, maximum tool rounds, and a turn ID in the controller. Limit the initial loop to five tool rounds. Persist pending mutations separately from conversational text. A restarted process must never automatically replay a send or file update.

Suggested package layout: `ui`, `conversation`, `model`, `speech`, `wakeword`, `camera`, `memory`, `skills`, `appcontrol`, and `tools`. Add a backend directory only if the API fallback is selected.

### Agent Skills and shared phone control

Implement the open [Agent Skills format](https://agentskills.io/specification).
Start with bundled skills, disclose their names/descriptions to the model, and
load instructions/resources through dedicated function tools when needed. Keep
skills separate from native capabilities and application-enforced operating
permissions. Do not assume imported desktop scripts or tool names run on Android.
Gmail and Drive will each have a skill package using the shared Android driver
to operate the app sessions established by normal sign-in on the phone.

Normal conversation now uses `ConversationController` and the provider's `complete()`
adapter with namespaced skill tools, bounded rounds/calls, validated dispatch,
native permissions and restored skill context. `toolTrial()` remains a separate
diagnostic, not the conversation runtime. See Experiment 004 and `docs/PHASE1.md`.
The selected provider route supports custom function tools but excludes native
computer use and hosted connectors. Any phone-control loop must use our own
Android driver and ordinary custom functions. Source: [provider limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

Generic app control is a prerequisite for Google skills. Evaluate user-enabled
Accessibility and useful supported intents on an unlocked stock Pixel, without
root or an ongoing ADB connection. Test actual state changes, cancellation,
permission loss, and screen changes. Existing sign-in is reused by operating the
target app's UI; it does not expose that app's private credentials or grant API
access to Pixel Agent. Google Play restricts autonomous Accessibility use by
general assistants; technical feasibility is not Play eligibility. Source:
[Accessibility policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en).
The [research document](docs/SKILLS-AND-APP-CONTROL.md) records the generic design,
platform boundaries, sources, and proposed experiment; no device/vendor-specific
integration is selected.

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

### Agent-owned phone identity and signed-in apps

The user signs in normally to Gmail, Drive, document editors, and other desired
apps on the dedicated agent phone. The agent then uses those apps through the
shared phone-control tools and app-specific skills. Enable Android Accessibility
and any other required system control access once during onboarding. App
accounts remain signed in through their normal sessions; login expiry, MFA, and
system permission prompts may require the user's intervention.

**Gmail:** search/read messages, compose/reply, and send through the installed
Gmail app. Check the active agent account and exact recipient/content, and verify
the resulting sent state. Do not assume tapping Send proves delivery or repeat
an uncertain send without reconciliation.

**Drive:** search and open files in the signed-in Drive app; read, create, or edit
supported documents through installed editors. Use exposed UI, intents, or
ordinary file-open/share flows as appropriate. Record format, screen, and editor
limitations rather than promising complete Drive access from sign-in alone.

**Operating authority:** the dedicated phone and identity are intended for broad
agent use. Routine actions within the user's instructions or configured standing
authority should run without repeated approval prompts. Keep operating rules in
the app, show actions and Stop, and ask only when authority is missing, the target
is ambiguous, or an exceptional destructive/security-sensitive action needs a
decision. Skill files and external app content cannot expand that authority.

No Google Cloud project, Gmail/Drive API enablement, Android OAuth client,
consent/test-user setup, API scope selection, or token extraction is required for
this design. Direct service APIs are deferred alternatives only if the user
later chooses them. If app-control validation fails, report the unsupported flow
and reassess; do not silently make developer setup a prerequisite again.

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

Application rules: external content is untrusted data; enforce tool schemas and
the agent-owned phone/account boundaries. Routine actions may use standing
authority as described above. Where confirmation is needed, show the exact
recipients/content or file changes and bind the decision to that payload. After
an ambiguous send timeout, inspect the target app's state rather than blindly
retrying. Treat local action IDs as deduplication aids, not remote idempotency.

Document that screen text and images sent to the reasoning model leave the phone.
Bound captured content and retention; keep passwords and authentication codes out
of skill files, logs, and normal model context. This app-session design does not
depend on Google Workspace API verification or a developer consent screen.

Show `Idle`, `Wake word enabled`, `Listening`, `Transcribing locally`, `Thinking online`, `Using Gmail/Drive`, `Awaiting confirmation`, `Speaking`, and `Camera active` from actual controller state. Show offline/error states, cancel controls, connected accounts, and pending actions. Locked-phone mode must withhold private mail readout and mutations until unlock.

## 4. Delivery sequence and acceptance gates

Estimates below are engineering estimates for one developer already comfortable with Android, working roughly full-time. They exclude third-party approval delays and are not measured commitments.

| Phase | Status | Effort | Deliverable and exit gate |
| --- | --- | --- | --- |
| 0. Resolve feasibility | Complete: tested on Pixel; evidence and measurement limits in Experiment 002 | 3–5 days | Minimal APK on the actual Pixel; prove subscription auth/text/image/tool/refresh, offline STT/TTS, and a short screen-off keyword session. Record OS, account eligibility, model versions, latency, RAM, and battery observations. Select provider route explicitly. |
| 1. Core conversation and skills | Code implemented; voice latency distribution gate open | 1–2 weeks baseline; skill runtime adds unestimated work | Push-to-talk/text input, local speech, streaming, persona, cancellation, history, standard skill discovery/activation/resources, and a bounded tool loop. Survive network loss without executing phantom actions. |
| App-control prerequisite (P6 task IDs retained) | Research only; required before phase 3 | Unestimated | On a stock Pixel, demonstrate observe/action/verify in a harmless app without root or ongoing ADB, then validate signed-in Gmail/Drive sessions and required Android permissions. |
| 2. Memory and vision | Code implemented; integrated memory/image-answer gates open | 1 week | Confirm/edit/forget facts across restarts; answer about an explicitly captured image; camera resources release on cancel/background. |
| 3. Signed-in app skills | Not started; depends on app-control prerequisite | Re-estimate after UI-control spike | Gmail and Drive skills operate signed-in apps. Search/read mail, send one test email under operating authority, read a document, and create a Drive note. Verify session expiry, account identity, changed UI, cancellation, and duplicate-action handling. |
| 4. Hands-free sessions | Code implemented; integrated voice and sustained measurement gates open | 1–2 weeks | Keyword -> utterance -> reply with screen off in a user-started session; Stop works; measure false wakes, missed wakes, and incremental battery use. |
| 5. Daily-use hardening | Not started | 1–2 weeks | Lock-screen privacy, calls/headsets, process recreation, deletion/export, permission changes, bounded storage, and several days of real use. |

The original **6–10 week** personal-MVP estimate predates the skill runtime and
required UI-control route; re-estimate after their spikes. Several months is
more realistic when learning Android alongside the project. A public app needs a
separate release plan for verification, policy review, distribution requirements,
support, and account/backend operations.

### Measurements to make the decision concrete

These are proposed acceptance targets, not advertised performance:

- Voice: correctly capture at least 27 of 30 representative commands in a quiet room, including Australian names and email addresses. Confirm ambiguous recipients instead of guessing.
- Offline speech: transcribe and speak with networking disabled after model installation; cloud reasoning should clearly report unavailable.
- Conversation: aim for median end-of-speech to first spoken answer below four seconds on a stable connection; measure p95 separately. If missed, optimize endpointing, model choice, and sentence-level playback.
- Wake word: at least 45/50 intended detections in the tested setting and at most one false activation during eight hours of representative ambient audio. Repeat with TV/music and the screen locked.
- Battery: target less than five percentage points of additional drain over an eight-hour idle baseline for wake listening. Compare repeated runs on the same phone, network, and screen conditions; reduce session duration if unacceptable.
- Reliability: permission loss, service termination, reboot, and network changes produce honest UI state and an explicit restart path. No claim of all-day reliability before an actual all-day test.
- Actions: one authorized test send produces exactly one message; cancellation, replay, injected instructions in mail/screens, and interrupted streams produce no unintended mutation. Routine actions under standing authority do not require repeated approval.
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

Defer continuous video, unrestricted permanent deletion, whole-account indexing,
fully local general reasoning, background inbox monitoring, and hardware hotword
integration. Routine sending within user instructions/standing authority is in
scope after the signed-in app workflow passes its gate.

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

Implementation updated 28 October 2026: text/push-to-talk, streaming local sentence playback, persona, bounded history, reliable turn cancellation, a whole-turn timeout, and explicit network/account/error states are implemented. Normal conversations now support namespaced skill activation/resource tools with five tool rounds, twelve calls, schema checks, native permissions and context restoration. Two instruction-only skills are bundled; unsupported script runtimes remain unavailable. Automatic inference retries are bounded to zero, requiring an explicit retry after interruption. Prior Pixel text/voice/history evidence remains in Experiment 003; current controller/skill evidence is in Experiment 004. Partial spoken output may already have been heard if a stream later fails. A meaningful voice latency distribution remains outstanding; the earlier single 3,194 ms observation is insufficient.

- [x] **P1-01:** Add text input and connect push-to-talk transcription to the selected model provider and local speech output.
- [x] **P1-02:** Implement streaming replies, sentence-level playback, persona settings, and bounded conversation context.
- [x] **P1-03:** Extend the controller with turn IDs, cancellation, timeouts, bounded retries/tool rounds, and network/error states. Whole-turn timeout 90 seconds; five tool rounds/twelve calls; zero automatic inference retries. Cancellation invalidates stale callbacks and cancels HTTP/audio without resetting a newer turn.
- [x] **P1-04:** Persist conversation history and restore it safely after process recreation; never replay pending mutations automatically.
- [ ] **P1-05:** Verify a complete voice/text conversation, cancellation, and network interruption on-device; record median/p95 response latency against section 4 targets.
- [x] **P1-06:** Implement standard Agent Skills parsing/catalog, bundled discovery, explicit/model-selected activation, bounded resource reads, and active-skill context restoration; surface incompatible runtime requirements. Safe YAML and APK assets; deliberate imports/script execution remain unsupported.
- [x] **P1-07:** Extend normal model conversations with namespaced tools, bounded tool rounds, validated arguments/results, cancellation, and application-enforced permissions; diagnostic transport alone is insufficient. Normal turns use `skills.activate` and `skills.read_resource`; `allowed-tools` cannot grant native permissions.
- [x] **P1-08:** Validate skill activation/resource loading through real model turns on the Pixel; exercise malformed/incompatible skills, missing resources, traversal attempts, permission denial, cancellation, and context rebuilding without action replay. Live activation/resource/restored-context and denied-tool turns passed; offline device/JVM tests exercise malformed packages, bounds and no replay. See Experiment 004.

### Phase 2 — Memory and vision

- [x] **P2-01:** Room stores memories/persona, provenance, timestamps, confirmation and expiry; bounded literal-token FTS retrieval returns up to eight confirmed facts from at most 200. Legacy persona migrates once; schema is committed. See docs/PHASE2.md and Experiment 006.
- [x] **P2-02:** Added draft/confirm/edit/remember/forget, JSON export and delete controls. Room maintains index deletion; edits/forget/expiry clear derived chat context with durable invalidation before mutation. No summaries/embeddings are stored.
- [ ] **P2-03:** File-backed Room close/reopen, edit/forget/expiry, FTS deletion and rebuilt prompt checks pass on the Pixel. Integrated app-process restart/forget with real model turns remains open; see docs/PHASE2.md.
- [x] **P2-04:** CameraX front/rear preview, explicit capture/review/send, orientation-aware resizing/metadata removal, bounded temporary data, cleanup and visible camera/upload states implemented. Camera closes at review, Cancel and background.
- [ ] **P2-05:** User-captured image answer through the selected provider remains open. Snapshot uses the normal conversation path; local camera/encoder checks are recorded in Experiment 006. Follow the explicit live trial in docs/PHASE2.md.

### Phase 3 — Gmail and Drive skills using signed-in apps

Prerequisite: complete the app-control feasibility tasks P6-01 through P6-04.
The P3 IDs are retained; their previous developer-API/OAuth deliverables are
superseded by the user's signed-in-phone decision on 28 October 2026.

- [ ] **P3-01:** Sign in normally to Gmail, Drive, and required editor/other apps on the dedicated agent phone using its agent-owned identity; enable required Android app-control permissions once. No Google Cloud/API/OAuth developer setup.
- [ ] **P3-02:** Implement Gmail skill workflows for bounded search/read, compose, and reply through the signed-in Gmail UI using shared phone-control tools.
- [ ] **P3-03:** Implement Drive skill workflows for search/open/read and supported document creation/editing through signed-in Drive/editors and ordinary file-open/share flows.
- [ ] **P3-04:** Enforce schema-validated dispatch, active account/target checks, external-content handling, and standing operating authority; request exact-action confirmation only when the action requires a new decision.
- [ ] **P3-05:** Implement authorized Gmail sending and Drive note creation through app UI, with resulting-state verification, interrupted-action reconciliation, and local deduplication.
- [ ] **P3-06:** Verify read tools, one authorized test email, and one Drive note; test cancellation, signed-out/expired sessions, revoked Accessibility access, account mismatch, changed UI, injected instructions, and ambiguous outcomes.
- [ ] **P3-07:** Document normal sign-in and one-time Android permission setup, screen-content/cloud-model transfer, observed app/version compatibility, and remaining limits for this personal sideloaded phone.
- [ ] **P3-08:** Author standard Gmail and Google Drive skills using shared phone-control tools, supported UI flows, agent-account expectations, and operating authority; keep credentials out of skill files/context.
- [ ] **P3-09:** Verify each skill through real model turns and the signed-in target app; enforce standing-authority boundaries and required confirmations without prompting again for already-authorized routine actions.

### Phase 4 — Hands-free sessions

- [x] **P4-01:** Select the pinned sherpa-onnx 1.12.14/GigaSpeech 3.3M English model from the phase-0 spike. Engine/model Apache-2.0 notices are bundled; ONNX model files total 6,033,728 bytes. Offline evidence and unmeasured quality limits are recorded in docs/PHASE4.md. Keep hello world for this implementation; no accuracy-rate claim.
- [x] **P4-02:** Implement user-started 15-minute/one-hour/eight-hour microphone/media-playback foreground sessions, microphone/notification checks, notification Stop, visible listening state and animated agent face. The application-owned conversation runtime outlives the Activity while a session is active.
- [x] **P4-03:** Integrate keyword → microphone release → tone → local utterance recognition → streaming model/local playback → 1.5-second cooldown → automatic follow-up listening. Silence/no-match or a 12-second utterance window returns to keyword capture. Await transcript dispatch as well as HTTP/playback completion. Errors stop without inference retry; no parallel manual speech/diagnostic session. User confirmed live voice replies and automatic follow-ups on the Pixel; silence fallback and sustained self-trigger checks remain separate evidence.
- [ ] **P4-04:** Measure intended/missed/false wakes, locked-screen operation, and incremental eight-hour battery drain against section 4 targets.
- [ ] **P4-05:** Verify service termination, reboot/force-stop recovery messaging, and explicit restart behavior; document supported session limits. Code uses START_NOT_STICKY, no boot receiver, persisted interruption messaging, bounded session/wake lock, permission/focus loss checks, and explicit restart. See Experiment 005 for verified lifecycle cases; untested cases remain open.

### Phase 5 — Daily-use hardening

- [ ] **P5-01:** Enforce unlock before private readout and external mutations; verify lock-screen behavior.
- [ ] **P5-02:** Exercise calls, Bluetooth/headset changes, audio focus, permission changes, process recreation, and network transitions.
- [ ] **P5-03:** Finish credential/content encryption, bounded storage, temporary-media cleanup, and user-controlled export/deletion; verify backup/transfer behavior.
- [ ] **P5-04:** Add/enforce daily request/token budgets and bounded context; test exhaustion without automatic billable fallback.
- [ ] **P5-05:** Run the representative 30-command speech trial and several days of normal usage; record failures and fix material regressions.
- [ ] **P5-06:** Review all phase exit gates and document remaining limitations before calling the personal MVP complete. Plan public distribution separately if requested.

### App-control prerequisite — generic control without root (P6 IDs retained)

These tasks now precede phase 3; they are required infrastructure for the chosen
signed-in-app architecture, not a post-MVP optional extension. Compatibility
remains a measured feasibility gate.

- [ ] **P6-01:** Build a generic Accessibility-driver spike with user setup, package allowlisting, inspect/click/type/scroll/navigation, bounded steps, and visible Stop; prefer supported APIs/intents when available.
- [ ] **P6-02:** Demonstrate observe/action/verify on a harmless test app using the stock Pixel, with no root and no ongoing ADB connection. Record OS, package/version, permissions, and actual resulting state.
- [ ] **P6-03:** Exercise service/permission loss, process recreation, lock, unavailable/changed nodes, protected content, cancellation, and ambiguous results; stop safely and never automatically replay pending actions.
- [ ] **P6-04:** Define reusable Android capabilities versus per-app skills; validate entry/account detection for signed-in Gmail and Drive, document session/technical/distribution limits, and decide whether the phase-3 UI workflows can proceed. No smart-device/vendor-specific integration is committed.

**Current decision:** phase 0 complete for the tested personal local prototype.
The API 36 lab is deployed; automated checks and live account/offline speech/
locked-keyword/basic lifecycle/network-recovery trials pass. Use direct ChatGPT
plan usage. Phase 1 is in progress; sustained accuracy/battery and
daily-use reliability remain later work. See Experiment 002 and `docs/PHASE0.md`
for exact evidence and limits.
