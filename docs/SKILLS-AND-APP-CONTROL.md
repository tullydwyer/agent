# Agent Skills and control of other Android apps

Research date: 28 October 2026 (Australia/Brisbane).
Scope: the Pixel Agent project, standard Agent Skills, Gmail/Drive, and a generic
pattern for operating other apps on a stock, unrooted Android phone. No
vendor-specific device integration is selected or implemented.

**Selected design, updated 28 October 2026:** the user wants a dedicated agent
phone/identity, ordinary sign-in to installed apps, and agent operation of those
existing app sessions. The earlier recommendation to build Gmail/Drive API
connectors is superseded. Google Cloud/API/OAuth developer setup is outside the
selected path. Shared phone control is now required before Gmail/Drive skills,
with feasibility still to be established on-device. The plan retains P6 task IDs
but moves that work ahead of phase 3.

## Assessment

**Agent Skills are a good fit. Gmail and Drive will be delivered as skills
using shared tools to operate their signed-in Android apps. Operating other apps without root is technically
possible, with user-enabled Accessibility access, but compatibility must be
tested per app.** A skill does not itself grant phone control or account access.

Phase 1 now implements bundled skill loading, explicit/model-selected activation,
bounded resource reads, context restoration and native permission checks in normal
conversations. Live skill model trials passed on the Pixel (Experiment 004).
Google tools and an Accessibility service remain unimplemented; no app-control
experiment was performed for this research. See [the Phase 1 guide](PHASE1.md).

## What a skill provides

The open format defines a directory containing `SKILL.md`, with YAML frontmatter
including `name` and `description`, followed by Markdown instructions. Optional
references, scripts, and assets travel with it. The optional `compatibility`
field describes environment requirements; `allowed-tools` is experimental.
Supported script languages depend on the host implementation. Sharing the file
format therefore does not guarantee that a desktop skill's Bash/Python scripts,
CLIs, or tool names work on Android. Source: [Agent Skills specification](https://agentskills.io/specification).

The client integration guide supports bundled skills and a dedicated activation
tool when the model cannot read files. It describes discovery of names and
descriptions, loading instructions when selected, and reading resources as
needed. This fits APK assets and app-private storage; it does not require a Unix
shell or desktop filesystem. Source: [client integration guide](https://agentskills.io/integrate-skills).

Proposed separation:

| Layer | Responsibility | Example |
| --- | --- | --- |
| Skill | Task instructions and workflow | Search mail, resolve the intended recipient, prepare a reply. |
| Tool | Typed operation implemented by the app | `phone.observe`, `phone.click`, `phone.type`, `phone.scroll`. |
| Driver/session | Execution in signed-in target apps | Android Accessibility and supported intents; Gmail/Drive retain their own sessions. |
| Application policy | Operating authority and action checks | Agent-owned phone/accounts; routine actions use standing authority, exceptional decisions can require confirmation. |

Installing a skill adds knowledge and workflows. New operating-system privileges,
unsupported executables, or an entirely new native driver can require app code
and setup. A skill may request existing capabilities but cannot grant them.

## Proposed Android skill runtime

These are project design choices, not additional requirements of the standard:

1. Bundle initial skills under `assets/skills/`; support deliberate import into
   app-private storage later. Parse standard YAML frontmatter, preserve metadata,
   and report invalid files and missing dependencies clearly.
2. Put a compact catalog in model context. Expose `skills.activate(name)` and
   `skills.read_resource(name, relative_path)` as ordinary function tools. Resolve
   resources within the selected skill; bound package size, path traversal,
   symlinks, and context consumption when imports are added.
3. Return instructions with the skill name, base location, and resource listing.
   Allow explicit user selection as well as model selection. Keep activated
   instructions across tool rounds and restore them when rebuilding context.
4. Register executable capabilities separately. Start with Kotlin tools and
   instruction/resource skills. Report unsupported script runtimes instead of
   treating a parsed skill as executable. Adding a script sandbox is a separate
   feature, not a prerequisite for the initial runtime.
5. Keep grants and confirmations in application code. Imported skill text and
   `allowed-tools` must not expand an existing grant or bypass confirmation.
   Mail, documents, and screen text remain external data rather than authority
   to install skills or change policy.

The phase 1 adapter now exposes the catalog and activation/resource functions
through `ConversationController` and `PlanUsageProvider.complete()`, using
`tool_choice: auto`. It bounds rounds/calls, validates arguments/results, checks
native grants, and cancels outstanding HTTP calls. The diagnostic `toolTrial()`
remains separate. Bundled instructions/resources are supported; deliberate
filesystem imports and executable runtimes remain future work.

## Gmail and Drive

Recommended initial packages:

```text
skills/
  gmail/
    SKILL.md
    references/search-and-replies.md
  google-drive/
    SKILL.md
    references/files-and-exports.md
```

The user signs in normally to Gmail, Drive, and any required document editors on
the agent's phone. The Gmail skill describes navigating search results, reading
threads, identifying recipients, composing/replying, and verifying sends in the
installed app. The Drive skill describes search/open/read and tested create/edit
flows through Drive and its installed editors. Both use the same Android driver;
skills supply the app-specific task knowledge.

There is no Google Cloud project, API enablement, developer OAuth registration,
consent/test-user setup, or API scope selection in this path. Sign-in remains
inside the target apps: Pixel Agent acts through their UI rather than extracting
session tokens or reading private app databases. Reauthentication/MFA and system
permission changes may still need human intervention. Captured screen text/images
sent to the reasoning model leave the phone.

The phone and identity are specifically for the agent. Routine activity within
user instructions or standing authority should not require repeated approval.
Runtime policy still resolves ambiguous targets and exceptional destructive or
security-sensitive decisions; external app content cannot authorize new actions.

Direct APIs remain a deferred alternative only if the user later chooses that
architecture. Their scope/verification requirements are not onboarding tasks for
the selected signed-in-app design. Unsupported UI flows must be reported rather
than silently replaced with a developer-setup requirement.

## Compatibility with the selected model connection

Official OpenAI documentation currently permits namespaced function/custom tools
and text/image input on the selected ChatGPT plan-usage route, subject to model
support. It excludes hosted connectors/MCP and native computer use. Our app can
load skills and execute its own function tools locally; a hosted computer-use
tool cannot be assumed available. Source: [plan-usage limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

Engineering conclusion: the skill runtime fits this route. The required phone
driver could return screen structure or a screenshot and accept ordinary custom
function calls. This would be our own observe/action loop, with app-specific
validation, not evidence that a native computer-use model/tool is supported.
The existing diagnostic establishes function-call transport only; the complete
skill/tool loop still needs on-device validation.

## Generic control of other apps without root

The selected route is signed-in app UI control, supplemented by supported intents
that avoid additional account/developer setup. Other routes are alternatives:

| Route | What it can do | Main boundary |
| --- | --- | --- |
| Public API or automation bridge | Invoke documented operations and read their results. | Requires a supported integration and authorization; a bridge may add another running service. |
| Android intents / deep links | Open another app or request an action it exposes. | The target must expose the operation; opening its screen does not prove the action completed. |
| User-enabled Accessibility service | Inspect exposed UI nodes, click/type/scroll, navigate, and optionally capture screenshots. | Depends on the app's UI, Android protections, and distribution policy. |

Android documents intents as inter-app requests. Accessibility exposes
`AccessibilityNodeInfo` actions, `dispatchGesture`, global navigation, and
screenshot APIs with declared service capabilities. These are platform mechanisms
available to an appropriately configured service, without rooting. The user must
enable the service; sideloaded installs may also require allowing restricted
settings. Sources: [intents](https://developer.android.com/guide/components/intents-filters),
[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService),
[restricted settings](https://support.google.com/android/answer/12623953?hl=en).

For a generic smart-device example, a skill could direct the agent to open the
already signed-in controller app, select the configured device, invoke a tested
action, and verify the resulting status. The same driver could support unrelated
apps, while each skill supplies its own navigation and task knowledge. A UI
button being tapped is only evidence of a tap; success requires a resulting UI
state or device status. This is a proposed reusable pattern, not a compatibility
claim for any particular product.

Where an integration exists, a bridge can avoid foreground UI work. For example,
Home Assistant offers authenticated state reads and service/action calls; device
support still depends on a separate integration. This is optional and would add a
Home Assistant instance to the architecture. Source: [Home Assistant REST API](https://contributors.home-assistant.io/core/internals/rest-api/).

### Practical boundaries

- Prefer semantic nodes and actions. Custom canvases and poorly labelled controls
  may require screenshot understanding and gestures, increasing latency and
  fragility. Screen layouts, app versions, and language changes need retesting.
- Design the first UI-control prototype for a visible, unlocked phone. Do not
  promise interaction through the secure lock screen or arbitrary background app
  launches. Android restricts background activity starts. Source: [activity security](https://developer.android.com/guide/components/activities/secure-bal).
- Protected windows can block screenshots. Sensitive nodes can be withheld from
  services that are not accessibility tools. Do not claim to overcome those
  protections. Sources: [secure windows](https://developer.android.com/security/fraud-prevention/activities),
  [sensitive accessibility nodes](https://developer.android.com/reference/androidx/core/view/accessibility/AccessibilityNodeInfoCompat#setAccessibilityDataSensitive(boolean)).
- A MediaProjection capture path needs user consent for each capture session and
  does not by itself provide touch injection. Source: [media projection](https://developer.android.com/media/grow/media-projection).
- ADB/UI Automator is useful for development evidence, but a laptop-driven trial
  does not establish autonomous operation by the installed app. UI Automator is a
  testing framework; the production design needs its own permitted driver.
  Source: [UI Automator](https://developer.android.com/training/testing/other-components/ui-automator).

### Distribution changes the answer

Google Play currently prohibits Accessibility use for autonomous initiation,
planning, and execution by general-purpose apps. Its policy distinguishes static,
human-defined automation and exempts qualifying disability-focused accessibility
tools. A general voice assistant does not qualify merely because it can help
someone with a disability. Source: [Accessibility automation policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en).

Therefore, root-free technical feasibility does not establish Play eligibility.
The existing personal/sideloaded scope supports a limited feasibility experiment;
it does not remove Android restrictions or justify misdeclaring the app as an
accessibility tool. Any public release needs a separate design/policy review.

## Recommended sequence and proof

1. Add the skill catalog, activation/resource tools, and general tool loop to the
   core conversation. Test two instruction-only skills and an incompatible skill.
2. Build the required generic app-control spike using a harmless test app on the
   stock Pixel. User-enable Accessibility, then demonstrate inspect -> click ->
   type/scroll -> verify with no root and no ongoing ADB connection.
3. Exercise cancellation, permission revocation, changed/missing controls, lock,
   process recreation, and service loss. Stop on uncertain state; do not replay
   pending actions after restart. Bound actions/time and show an immediate Stop.
4. Use ordinary sign-in to Gmail/Drive on the agent-owned identity. Validate target
   app/account detection, then deliver their workflows as skills over shared
   phone-control tools. Exercise routine standing authority and exceptional
   confirmation paths, session expiry, changed UI, and uncertain sends.
5. Extend to other installed apps through further skills. Record package/version
   and supported flows. Keep generic capabilities separate from per-app skills
   and publish only the compatibility actually measured.

Skills and generic app control are committed architecture requirements; individual
UI workflows remain conditional on measured compatibility. No root, custom ROM, or permanent
desktop connection should be a prerequisite for the intended extension.
