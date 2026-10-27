# agent

## Project Goals
- AI Agent that runs on a google pixel 7 phone.
- Supports email (gmail).
- Supports google drive.
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

AI authentication, Gmail, Drive, camera capture, durable memory, and wake-word
listening are not implemented yet. The model interface deliberately reports that
it is unconfigured; this build does not simulate an AI reply.

### Requirements

- Android Studio with Android SDK Platform 35, Build-Tools 35.0.0, and Platform-Tools.
- JDK 17 or 21. The helper skips newer bundled JDKs that this Gradle baseline
  does not support (the installed Android Studio runtime is Java 25).
- Android 12/API 31 or later; the initial target device is a stock Pixel 7.
- Internet access for the first Gradle dependency download.

Pinned build tools: Gradle 8.11.1, Android Gradle Plugin 8.9.1, Kotlin 2.1.10,
Compose BOM 2025.03.00. These are a compatible baseline, not a claim to be the
latest releases. Open the repository root in Android Studio, or use PowerShell:

```powershell
.\scripts\dev.ps1 doctor
.\scripts\dev.ps1 build
.\scripts\dev.ps1 install
.\scripts\dev.ps1 run
```

The helper discovers the conventional Windows SDK/JDK locations. Set `ANDROID_HOME`
and `JAVA_HOME` for custom locations. It does not edit system environment variables.
With multiple devices, pass `-Serial <adb-serial>` to `install` and `run`.

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

Do not infer cloud authentication or wake-word feasibility from these tests.
See [PLAN.md](PLAN.md) for the implementation plan and
[docs/EXPERIMENTS.md](docs/EXPERIMENTS.md) for measured results and pending checks.
