# Phase 1 conversation and skills

Phase 4 navigation update (28 October 2026): the main screen now shows the agent
face, **Talk to Pixel** (formerly Push to talk), text input and hands-free controls.
Account/model, persona, skills and diagnostic tests are under **Settings**.
Fresh-process startup restores a valid saved account model from its catalog.
Leaving the Activity cancels one-shot turns; an explicitly started hands-free
service keeps its application-owned conversation runtime alive. See
[the Phase 4 guide](PHASE4.md) for that mode and its remaining validation gates.

The Android app, speech recognition/playback, skill loader, and storage run on
the Pixel. Reasoning runs online through the signed-in ChatGPT plan at
`https://api.openai.com/v1/responses`. Requests contain the current message,
persona, bounded recent exchanges, skill catalog, and active instructions/resources.
Microphone audio stays with the on-device recognizer; transcripts go to the model
when Push to talk starts a conversation. No billable API fallback is implemented.

## Conversation

Fetch account models in the model lab, select one, then use Message/Send or Push
to talk. Voice replies play in sentence-sized chunks with an installed offline
English voice. Stop / cancel cancels the current HTTP call and local audio,
invalidates delayed callbacks, and immediately permits another turn. Leaving the
activity cancels unless a hands-free service owns the session; configuration
changes retain the application-owned turn.

Only completed user/assistant exchanges are saved. A partial reply may already
have been spoken before a later error; it is not saved as a completed exchange.
Model tool transcripts and pending work are never restored or replayed.
Timeout, network interruption, account/usage errors, cancellation, and completion
have separate controller states. Automatic inference retries are bounded to zero:
an interrupted request may already have consumed plan allowance. Retry explicitly.

Limits: 90 seconds per whole turn, five tool rounds (plus a final response),
four calls per response, twelve calls per turn, 16,000 characters per final reply,
4,000 characters per user message, 40 stored messages, twelve recent messages and
24,000 history characters in context. Storage also bounds escaped JSON to 450,000
characters. Current-turn tool context is capped at 384,000 characters.

## Bundled Agent Skills

Packages live in `app/src/main/assets/skills/<name>/SKILL.md`. The loader parses
standard YAML frontmatter with a safe YAML constructor, validates names,
descriptions and optional fields, and isolates malformed packages. The catalog
initially exposes only names/descriptions, compatibility and availability.
Two instruction-only packages are bundled: `clear-explanation` and `daily-briefing`.
They provide no phone-control or Google capabilities.

Activate/deactivate a package in Agent Skills, or let the model use
`skills.activate(name)` and `skills.read_resource(name, relative_path)`. These are
ordinary namespaced custom functions in normal conversation turns. Resources
require activation and must be listed within the package. Unknown functions,
extra/wrongly typed arguments, traversal, missing resources, oversized results,
and duplicate call IDs are rejected. Tools execute only after response completion.

Disable skill loading to revoke the native grant. Revocation cancels an active
turn and omits catalog/active content from subsequent contexts. `allowed-tools`,
model output, resource text and saved conversation text cannot grant permissions.
Saved active skill names/resource paths are reloaded from bundled assets; no
function calls or credentials are saved with them.

There is no shell/script executor or package import UI. Packages with scripts or
unverified compatibility requirements are shown as unavailable. An author may
declare `metadata: {pixel-agent-runtime: instructions}` when the package can run
using instructions/resources alone; this never enables script execution.

Limits: 24 packages, 64 paths per package, five nested directory levels,
16 KiB per SKILL.md (8,192-character header/body limits), 8 KiB UTF-8 per resource,
three active skills, six loaded resources per skill, and 24,000 characters of
combined active skill context. Resource paths use relative forward-slash segments;
absolute, Windows, encoded and parent paths are rejected. APK assets are immutable
and cannot contain traversable filesystem symlinks. Filesystem imports would need
their own canonical-path/symlink checks before being supported.

## Verification

```powershell
.\scripts\dev.ps1 build
.\scripts\dev.ps1 test-phase1
# Explicitly use the signed-in plan with a slug available in its model catalog:
.\scripts\dev.ps1 test-phase1 -LiveModel gpt-6-luna
.\scripts\dev.ps1 run
```

`test-phase1` updates the target/test APKs and invokes instrumentation directly;
it does not uninstall or clear the signed-in app. Without `-LiveModel`, only
offline tests run. With it, synthetic prompts and bundled skills go to OpenAI.
The live suite briefly disables Wi-Fi/mobile data after a stream starts, restores
both original settings in `finally`, and tests cancellation/manual recovery.
Test persistence uses a separate scratch preferences file and clears it afterwards.
Do not interrupt the test host during the network trial; if the process is killed,
restore the phone's networking manually. No production conversations are uploaded
by these instrumentation trials.

Manual voice/latency gate: on a stable connection, make at least 20 short Push to
talk turns, confirm the transcript and audible answer each time, and include Stop
during playback. Record the displayed count/median/p95, model, connectivity and
conditions in EXPERIMENTS.md. Voice latency measures recognizer end-of-speech to
first local playback; text latency measures Send to first text delta. The rolling
100-sample distributions include attempts that reached first output even if they
later failed. They do not measure full-answer duration or microphone endpointing
before the recognizer callback. A single sample cannot establish a useful p95.
The target voice median is below four seconds; p95 is reported separately.
