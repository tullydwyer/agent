# agent

## Project Goals
- AI Agent that runs on a google pixel 7 phone.
- The phone and all identities are completely dedicated and controlled by the agent.
- Supports email (gmail).
- Supports google drive.
- Supports the open [Agent Skills](https://agentskills.io/home) format, with Gmail and Drive workflows using shared phone-control tools to operate signed-in apps.
- Controls installed apps on a stock phone without rooting, through user-enabled UI automation and supported intents; compatibility must be verified per workflow.
- Uses a phone and identity dedicated to the agent: sign in normally to desired apps and enable Android control permissions once, with routine operation under standing authority. No Google Cloud/API/OAuth developer setup is required for Gmail or Drive.
- Consumes AI from a ChatGPT subscription.
- Supports vision via cameras.
- Supports text to speech (running locally).
- Supports speech to text (running locally).
- Supports talking to via wake word.
- Supports on device memory.
- Has an AI identity to talk to naturally.
- Has a UI to show when it is using the camera and mic etc.

## Development

The native Kotlin/Jetpack Compose app opens on an animated agent face with text
conversation, **Talk to Pixel**, and **Start hands-free**. Choose 15 minutes,
one hour (default), or eight hours. Say **Jenkins**, wait for the tone, then
speak your message. Local recognition sends the transcript to your selected
ChatGPT model, and the agent replies through the phone's offline voice. The
agent listens for a follow-up after playback and a short cooldown, without another
wake word. Silence returns it to waiting for your wake word. Change it under
**Settings → Wake word**; the saved phrase applies to the next session. Tap Stop in the app or its
persistent notification to end the session. Skills are optional and live in
Settings; adding Gmail/Drive skills is separate work.

An explicitly started hands-free session can continue with the screen off.
Microphone and notification access are required; sessions do not automatically
resume after process death, force-stop or reboot. Without a hands-free session,
conversation/audio stop when the app leaves the foreground. Settings contains
account/model selection, persona, existing skill controls and speech/model tests.
The selected model is saved on this phone and restored when the app opens, even
if the model catalog cannot be refreshed. Reconnecting keeps your choice; a
successful catalog refresh uses the first available model if that choice is no
longer offered. Completed history and persona also remain on this phone.

See [the Phase 4 guide](docs/PHASE4.md) for the wake engine, lifecycle limits and
remaining live-voice/accuracy/battery checks. Implementation does not establish
all-day reliability or the eight-hour battery target.

Speech recognition/playback, skill loading and storage run locally. AI reasoning
runs online through the signed-in ChatGPT subscription: prompts, recent history,
persona and relevant skill instructions/resources are sent to OpenAI. This is not
an offline reasoning model. Speech-test transcripts are not uploaded automatically.

The original Phase 0 diagnostics retain a separate ChatGPT plan authentication/
inference lab and five-minute keyword counter service. The main hands-free
control now connects your saved wake word to real conversation turns. The model lab
tests text, a generated diagnostic image, a harmless function, and credential
renewal. Credentials are encrypted with Android Keystore. Text/image/function
turns, renewal, browser callback, and reconnect have passed on the Pixel account.
The user also confirmed offline speech and a locked-phone keyword/Stop trial.

The intended distribution is private source/personal use. User-started listening
sessions are acceptable. Subscription access is the only implemented model route;
no billable API fallback is enabled. Phase 0's feasibility gate is complete;
see the experiment log for evidence and measurement limits. Phase 1 implementation
adds a bounded conversation/tool controller, standard skill catalog and resource
loading, explicit activation, permission controls, and safe context restoration.
Its voice latency distribution still needs measurement. See [the Phase 1 guide](docs/PHASE1.md) and [Phase 4 guide](docs/PHASE4.md).
Gmail, Drive, camera capture, durable factual memory, and generic app control remain
later work. See the [skills and app-control research](docs/SKILLS-AND-APP-CONTROL.md)
for compatibility limits and root-free Android control options.

### Requirements

- Android Studio with Android SDK Platform 36, Build-Tools 35.0.0, and Platform-Tools.
- JDK 17 or 21. The helper skips newer bundled JDKs that this Gradle baseline
  does not support (the installed Android Studio runtime is Java 25).
- Android 12/API 31 or later; the initial target device is a stock Pixel 7.
- Internet access for the first Gradle dependency download.

Pinned build tools: Gradle 8.11.1, Android Gradle Plugin 8.10.1, Kotlin 2.1.10,
Compose BOM 2025.03.00. These are a compatible baseline, not a claim to be the
latest releases. Open the repository root in Android Studio, or use PowerShell:

```powershell
.\scripts\dev.ps1 doctor
.\scripts\prepare-keyword.ps1
.\scripts\dev.ps1 build
.\scripts\dev.ps1 install
.\scripts\dev.ps1 run
.\scripts\dev.ps1 measure
```

The helper discovers the conventional Windows SDK/JDK locations. Set `ANDROID_HOME`
and `JAVA_HOME` for custom locations. It does not edit system environment variables.
With multiple devices, pass `-Serial <adb-serial>` to `install` and `run`.

`build` runs assembly, lint, and JVM security/stream/conversation/skill tests. Keyword preparation
downloads pinned, checksum-verified engine/model files into ignored `.tools/`;
the helper prepares them automatically when absent. Run it before using the
wrapper directly. See [the Phase 0 lab guide](docs/PHASE0.md) for model versions,
licenses, account sign-in, screen-off trials, measurements, and test limits.

Use `.\scripts\dev.ps1 test-phase1` for offline Pixel tests that retain the signed-in
installation. Adding `-LiveModel <account-model-slug>` explicitly enables live
synthetic model trials and a temporary network interruption/restoration test.
See [the Phase 1 guide](docs/PHASE1.md) before running the live suite.

Use `.\scripts\dev.ps1 test-phase4` for short local microphone/service lifecycle
checks. Start unlocked in a quiet room with a selected account model. It tests
expiry/restart and notification Stop with the screen off, preserving sign-in;
normal unlocking is needed after the screen-off test.

The standard wrapper is also available: `bash ./gradlew assembleDebug lintDebug` on
macOS/Linux or `.\gradlew.bat assembleDebug lintDebug` on Windows. Configure your
SDK with `ANDROID_HOME` or an untracked `local.properties` file. No credentials,
debug APKs, machine-specific paths, or signing keys belong in Git.

### Connect the phone

1. Unlock it. In **Settings → About phone**, tap **Build number** seven times.
2. Enable **Settings → System → Developer options → USB debugging**.
3. Connect a USB data cable and accept the phone's debugging authorization prompt.
4. Run `doctor`. The device must be listed as `device`, not `unauthorized`.

If no device appears, try a data-capable cable/USB port and check Android Studio's
USB connection assistant or Google's Windows USB driver. Root is not required.

### First speech experiment

1. Launch **Pixel Agent**. Check the local recognizer and offline voice status.
2. Open **Settings**, tap **Test local voice**, then **Test local recognition**, allow the microphone,
   and say a short sentence. Read it back using **Test local voice**.
3. If language/voice data is unavailable, install it in Android's speech settings.
   The recognition test uses the phone's current language; playback selects English,
   preferring an Australian voice. Reopen the app after installing voice data.
4. Repeat with Wi-Fi and mobile data disabled to verify offline behavior. Availability
   checks alone do not establish accuracy or prove an offline run succeeded.
5. Tap **Stop audio**, or leave the app without starting hands-free, and check that microphone use ends.

Account controls and diagnostic speech/model tests are in **Settings**.
They do not automatically run after a speech test. Account sign-in
and real offline speech passed in Phase 0. Instrumentation tests
can erase app data when Gradle uninstalls test APKs; run them before signing in.
See [PLAN.md](PLAN.md) for the implementation plan and
[docs/EXPERIMENTS.md](docs/EXPERIMENTS.md) for measured results and pending checks.
