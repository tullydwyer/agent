# Phase 2 — Memory and vision

Implemented 28 October 2026. See Experiment 006 for evidence. The phase exit gate
remains open until the integrated restart/forget and user-captured image-answer
trials below are recorded.

## Memory

Open **Settings → Memory · on this phone**. Enter a fact, its source and an
optional expiry date (`YYYY-MM-DD`, midnight in the phone's current time zone).
**Save draft** keeps it out of model context. **Confirm and remember** explicitly
allows retrieval. **Edit / confirm** loads an existing fact; saving a draft also
withdraws confirmation. **Forget** removes that row and its FTS index entries.

Room stores facts, confirmation, provenance, creation/update/expiry timestamps
and the persona. The previous preference-based persona migrates once. Schemas
are checked into `app/schemas`; no destructive migration is used. Limits: 200
facts, 1000 characters each, 300-character sources. Search uses at most 16 quoted
literal tokens and returns at most eight confirmed, unexpired facts by most recent
update. No embeddings or generated summaries are stored. There is no automatic
model memory-write tool: the user chooses facts.

Every memory/persona edit cancels the active turn and clears transcript, partial
output and saved conversation pairs. A durable invalidation marker precedes the
database mutation so a process interruption cannot restore old derived replies.
Expiry is checked before model context construction/export and at startup, and
also clears conversation context. Retrieval is rebuilt each turn and marked as
data with no authority to change application rules. Bundled skill resources
contain no generated memory.

**Export memory and persona** writes versioned JSON to a file chosen with the
Android document picker, including drafts, sources and timestamps. **Delete all
memory, persona and chat history** removes facts/index entries/history and resets
the persona after the in-app deletion dialog. Exported files and content already
sent to the provider are outside this local deletion. The private app database
uses Android application storage and existing backup exclusions; additional
content encryption and forensic erasure are Phase 5 work.

## Camera

Stop hands-free/audio, then tap **Camera** on the conversation screen and allow
camera access. Select front/rear camera. Preview stays on the phone and is bound
to the Activity lifecycle. **Take snapshot** captures a still into private cache,
applies EXIF orientation, scales to at most 1024 pixels on the long edge,
re-encodes JPEG without metadata, and bounds encoded data to 180,000 bytes.
Capture files are removed on success/error/cancellation; stale files are removed
after a fresh process start.

Review displays the resized image, dimensions and size. The camera closes at
review. Enter a question and tap **Send snapshot to model** to upload that image
with the question, persona, relevant confirmed memory and bounded recent text
history through the existing subscription provider. The selected model must
support images. No inference retry or alternate billing route is added. During
that turn the home screen shows **Snapshot sent online**. Completion discards
image bytes; a marker and textual answer remain in chat history. Images are never
saved in Room/preferences or resent with later turns.

**Retake**, **Cancel and discard**, Back, backgrounding and Activity recreation
discard the pending review. Backgrounding also cancels an image request, even
with a hands-free service. Cancelling HTTP cannot recall bytes already transmitted.
Camera failure/permission denial allows explicit retry; camera-open status
follows CameraX's observed state.

Implementation references: [CameraX lifecycle architecture](https://developer.android.com/media/camera/camerax/architecture),
[preview](https://developer.android.com/media/camera/camerax/preview), and
[Room FTS entities](https://developer.android.com/training/data-storage/room/defining-data).
Dependencies are pinned compatible baselines, not claims of latest versions.

## Verification

```powershell
.\scripts\dev.ps1 build
.\scripts\dev.ps1 test-phase2
```

The device helper retains sign-in, uses isolated test databases and briefly opens
front/rear preview and captures a local still. **It uploads no image.** Keep the
phone unlocked with an account model selected and hands-free stopped. Camera
permission is temporarily granted when needed and its original denied state is
restored by the host after instrumentation: revocation kills the app process.
The UI test checks review/cancel, background closure, camera CLOSED states and
no retained capture files. Room tests close/reopen a file-backed database and
check confirmation, editing, forgetting, FTS deletion, expiry, export, deletion
and bounds. Encoder tests check dimensions, orientation, metadata and cleanup.
JVM tests check literal/bounded search and one-turn image context.

Remaining integrated trial:

1. Remember a distinctive harmless fact. Ask about a matching keyword, restart
   the app/process and ask again. Verify the saved fact/persona and actual answer.
2. Forget/edit the fact. Verify history clears; restart and ask again. Inspect
   the next request through a fixture/debugger if needed: neither the old fact
   nor an old derived reply should appear. No automatic turn is replayed.
3. Point the camera at a harmless label/object, capture and review it, then
   explicitly send a question. Record selected model, result and camera status.
   Test retake, Cancel, Back/background during preview/review/upload and denied
   camera access. Confirm reopening retains no snapshot and later text turns
   have no image attachment. Actual image-answer evidence is separate from the
   earlier synthetic provider diagnostic.
