# Phase 4: seeing and talking to Pixel Agent

Implemented 28 October 2026 (Australia/Brisbane). Skills, Gmail/Drive and app
control are separate work. This phase adds hands-free conversation to the
existing subscription-backed conversation controller.

## Use the agent

1. Open Pixel Agent. The face shows ready, listening, thinking and speaking.
   **Settings** holds ChatGPT connection/model selection and the agent's persona.
   An existing account's model catalog loads on a fresh process start; the saved
   selection is reused if still available. Reconnect/refresh if access fails.
2. Tap **Talk to Pixel** to speak once, or send a typed message. Voice turns are
   spoken locally. Typed replies appear as text. Recent completed exchanges persist.
3. Choose **15 min**, **1 hr** or **8 hr**, then **Start hands-free** while the app
   is visible. Allow microphone and notification access. The notification channel
   must also be enabled so Stop remains available.
4. Say **hello world** by itself. Wait for the short tone, then say your message.
   Leave a pause after the wake word; speech spoken before recognition starts is
   not buffered. After each reply, wait for the next tone and keep talking; no
   repeated wake word is needed during that dialogue.
5. The keyword recorder is released before local recognition starts. Detection
   stays paused while transcribing, thinking or speaking, then waits 1.5 seconds
   before opening another local listening window. Silence/no-match (or a
   12-second recognition-window limit) returns to keyword mode. The face stays visible above the conversation. No skills are
   required; optional existing bundled skills remain under Settings.
6. Tap **Stop hands-free**, the main **Stop**, or notification **Stop**. Stop ends
   the whole hands-free session, cancels network/audio, and releases its mic,
   native model, audio focus and wake lock. Start explicitly to listen again.

Only a user-started hands-free session continues when the screen turns off or
another app is foreground. One-shot speech/conversation is cancelled on leaving
the app. Do not expect spoken interruption during playback: the detector is
paused to avoid hearing its own reply. Use the physical UI/notification Stop.

Keyword audio is discarded locally, and local STT/TTS have no cloud fallback.
The transcript, bounded recent history, persona and any active skill context go
to the selected online model using the existing ChatGPT plan route. The camera
stays off. Sessions can speak conversation content aloud with the phone locked;
private-readout/unlock enforcement and wider phone integration remain Phase 5.

## Engine choice and limits

- sherpa-onnx **1.12.14**, English
  `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`.
  Int8 encoder/joiner, fp32 decoder, one CPU thread, 16 kHz mono capture.
- Three ONNX files total **6,033,728 bytes (5.75 MiB)**. Preparation is pinned and
  checksum-verified by `scripts/prepare-keyword.ps1`. No runtime model download.
- Engine Apache-2.0 license and model README declaring Apache-2.0 are packaged
  with assets, as are ONNX Runtime's license and third-party notices. See
  [Phase 0 dependency details](PHASE0.md#dependencies-and-sources).
- Selected because the existing Pixel/native prerecorded and user-confirmed
  offline/locked keyword spike worked. This establishes feasibility, not measured
  detection accuracy, acoustic turn-taking quality or superiority over alternatives.
  `hello world` is retained with its known tokenization; custom wake phrases are
  not exposed in this build.
- A partial CPU wake lock lasts only for the bounded session (maximum eight
  hours plus ten seconds of cleanup). This is software keyword detection, with
  no low-power hardware/all-day battery claim. An eight-hour option enables the
  benchmark; it does not establish an acceptable drain rate.
- The service is non-sticky and has no boot receiver. Reboot, force-stop, Android
  Task Manager Stop, or process death leave listening off. A persisted interruption
  marker explains this at the next launch. It does not restore a session or turn.
- Notification/microphone access loss or negative audio-focus changes stop the
  session. Errors and 90-second controller timeouts have no automatic inference
  retry. Recognition is bounded to 12 seconds per utterance; a separate
  120-second voice-cycle watchdog also covers stuck STT/TTS.
  No successful wake is guaranteed during calls, competing capture, mute/privacy
  controls, Bluetooth changes or platform/vendor restrictions.

## Repeatable checks

Build/lint/JVM checks: `./scripts/dev.ps1 build`.
Local device lifecycle checks: `./scripts/dev.ps1 test-phase4`.
The helper updates the target/test APKs without uninstalling the signed-in app.
Start with the phone unlocked, speech data installed, a working account model,
and a quiet room. Tests expire a short session, explicitly restart it, turn the
screen off, send the notification's actual Stop PendingIntent, and verify the
recording configurations and notification clear. They restore the prior permission
grants and wake the screen; a secure keyguard still needs normal user unlocking.
Account catalog lookup may use the network; these tests intentionally request no
model inference. Do not speak the wake word during the lifecycle trial.

For integrated acoustic validation, record actual expected/heard text and audible
reply for at least five wake/message/reply cycles, including one screen-off cycle. Include at least one follow-up without another
wake word, and let a dialogue time out in silence to verify keyword mode resumes.
Use benign prompts, interrupt playback with Stop, and verify no new request or wake
from playback. Try silence/no-match, network interruption, and explicit restart.
The existing voice timing samples measure speech-end to first playback, not the
wake-word handoff. Confirm local speech separately in airplane mode; model replies
still need network. Do not count failed inference as a successful voice turn.

For P4-04, count 50 intended wakes per setting (quiet, TV/music, screen locked),
recording successes/misses separately from false wakes. Target at least 45/50
intended detections and at most one false activation during eight representative
ambient hours. The displayed wake count alone provides no denominator or labels.

For the battery gate, compare repeated **unplugged** eight-hour idle versus
eight-hour listening runs, matched for network, screen-off/lock, temperature,
background apps and start charge. Record start/end battery percentage and charge
counter using `./scripts/dev.ps1 measure` before/after, elapsed time and wakes.
Subtract matched idle drain from session drain; target under five additional
percentage points. USB charging snapshots do not measure this. Do not simulate
unplugging with `dumpsys battery unplug` and call that physical drain evidence.

Also test force-stop/reopen, a real reboot/reopen, permission loss, audio-focus
loss and explicit restart. Keep untested gates open in PLAN.md. See
[Experiment 005](EXPERIMENTS.md#experiment-005--hands-free-conversation-and-agent-interface-28-october-2026)
for the actual results and remaining work.

Implementation references: [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[on-device speech recognizer and lifecycle](https://developer.android.com/reference/android/speech/SpeechRecognizer),
[sherpa keyword models](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html).
