# agent

## Project Goals
- AI Agent that runs on a google pixel 7 phone.
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

The initial native Kotlin/Jetpack Compose app is a device diagnostics lab. It
reports the Android version and on-device speech availability, transcribes a
short utterance locally, and plays an installed offline English voice. Microphone
access is requested when you tap **Listen to a phrase**. Audio stops when the app
leaves the foreground. Transcript text is held in memory only.

Phase 0 now includes a separate ChatGPT plan authentication/inference lab and a
five-minute local keyword session with a foreground notification and Stop action.
The keyword is **hello world**; detections increment a counter. The model lab
tests text, a generated diagnostic image, a harmless function, and credential
renewal. Credentials are encrypted with Android Keystore. Text/image/function
turns, renewal, browser callback, and reconnect have passed on the Pixel account.
The user also confirmed offline speech and a locked-phone keyword/Stop trial.

The intended distribution is private source/personal use. User-started listening
sessions are acceptable. Subscription access is the only implemented model route;
no billable API fallback is enabled. Phase 0's feasibility gate is complete;
see the experiment log for evidence and measurement limits. Gmail, Drive, camera capture, durable memory,
and the complete voice-to-model conversation loop remain later phases.
Skill loading and generic app control are also unimplemented. See the
[skills and app-control research](docs/SKILLS-AND-APP-CONTROL.md) for the proposed
runtime, compatibility limits, and root-free Android control options.

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

`build` runs assembly, lint, and JVM security/stream tests. Keyword preparation
downloads pinned, checksum-verified engine/model files into ignored `.tools/`;
the helper prepares them automatically when absent. Run it before using the
wrapper directly. See [the Phase 0 lab guide](docs/PHASE0.md) for model versions,
licenses, account sign-in, screen-off trials, measurements, and test limits.

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
2. Tap **Test local voice**. Then tap **Listen to a phrase**, allow the microphone,
   and say a short sentence. Read it back using **Read transcript aloud**.
3. If language/voice data is unavailable, install it in Android's speech settings.
   The recognition test uses the phone's current language; playback selects English,
   preferring an Australian voice. Reopen the app after installing voice data.
4. Repeat with Wi-Fi and mobile data disabled to verify offline behavior. Availability
   checks alone do not establish accuracy or prove an offline run succeeded.
5. Tap **Stop audio**, or leave the app, and check that microphone use ends.

Cloud authentication and keyword capture have separate controls further down the
lab screen. They do not automatically run after a speech test. Account sign-in
and real offline speech passed in Phase 0. Instrumentation tests
can erase app data when Gradle uninstalls test APKs; run them before signing in.
See [PLAN.md](PLAN.md) for the implementation plan and
[docs/EXPERIMENTS.md](docs/EXPERIMENTS.md) for measured results and pending checks.
