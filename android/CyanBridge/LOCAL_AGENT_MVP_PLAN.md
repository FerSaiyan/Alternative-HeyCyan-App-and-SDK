## Local Agent Phone-Control MVP Plan

This document turns CyanBridge's existing local-agent groundwork into a concrete MVP for AI-driven phone control via Android Accessibility.

Reference repo reviewed locally:

- `https://github.com/orailnoor/private-agent`
- Cloned for review at `/tmp/opencode/private-agent`

Important note:

- The upstream repo is useful architecturally, but the clone reviewed here did not include a top-level `LICENSE` file.
- Treat it as product/architecture inspiration unless license terms are clarified.
- Prefer implementing our own Kotlin-first version on top of CyanBridge's existing local-agent code.

## What PrivateAgent Gets Right

The strongest idea in `private-agent` is its simple loop:

1. Read the current screen.
2. Send a compact structured observation to the model.
3. Ask for exactly one next UI action.
4. Execute it.
5. Feed the result back into the next step.
6. Stop when complete or when max steps is reached.

Useful patterns worth adopting:

- Structured screen dump with bounds and center coordinates, not just plain text.
- Small native action surface: click text, click coordinates, type, scroll, back, home.
- Step-by-step JSON contract instead of asking the model for a whole long plan up front.
- Explicit max-step guardrails.
- Current foreground package included in the observation.
- Accessibility-backed execution with gesture fallback when direct node click fails.

Initially deferred from the MVP (implemented later with explicit safety gates):

- Flutter UI architecture.
- Voice-control extras.
- Optional Shizuku fallback (fixed input operations only after Accessibility failure).
- Opt-in screenshot planning with separate remote-upload consent.

## Current CyanBridge Baseline

CyanBridge already has more groundwork than a blank MVP:

- Accessibility service exists: `app/src/main/java/com/fersaiyan/cyanbridge/localagent/accessibility/LocalAgentAccessibilityService.kt`
- Foreground agent loop exists: `app/src/main/java/com/fersaiyan/cyanbridge/localagent/LocalAgentService.kt`
- Minimal observe/plan/act plumbing exists:
  - `localagent/LocalAgentObserver.kt`
  - `localagent/LocalAgentObservation.kt`
  - `localagent/LocalAgentBrain.kt`
  - `localagent/LocalAgentStepEngine.kt`
  - `localagent/LocalAgentAccessibilityBridge.kt`
- JSON action parsing exists: `localagent/LocalAgentActionParser.kt`
- Action approval/pending queue exists: `localagent/actions/LocalAgentActionManager.kt`
- Settings and accessibility enablement UI already exist.
- Local memory/screen-capture infrastructure already exists and should remain privacy-first.

Main gap today:

- The runtime loop is real, but the brain is still `NoOpLocalAgentBrain()` and the observation/action schema is too small for serious phone control.

## MVP Goal

Enable a user to type a request like:

- "Open WhatsApp and search for John"
- "Open Settings and turn on Bluetooth"
- "Go to YouTube and search for lo-fi jazz"

And have CyanBridge:

1. Route the request into the local-agent runtime.
2. Read the active UI using Accessibility.
3. Execute a bounded multi-step action loop.
4. Ask for confirmation before medium/high-risk actions, according to settings.
5. Return a clear success/failure summary to the chat UI.

## Non-Goals For MVP

- Full autonomous background control without user awareness.
- Remote control from unconfigured or arbitrary web clients.
- Screenshot vision models as a hard dependency; text-only planning remains the fallback.
- Arbitrary device admin actions.
- General-purpose plugin/tool ecosystem for phone control.
- Cross-app workflows that require login/session secrets to be copied into prompts.

## Recommended MVP Architecture

Keep CyanBridge's existing service-based architecture and add a dedicated UI-control loop on top.

### 1. Add a Dedicated UI-Control Protocol

Do not overload `ai/localagent/LocalAgentProtocol.kt`.

Why:

- That protocol is currently aimed at app-internal actions like `open_screen`, `start_meeting_capture`, and `broadcast_intent`.
- Phone-control actions need different semantics and step feedback.

Implement:

- New file, likely `localagent/LocalAgentUiControlProtocol.kt`
- Strict JSON response shape for one-step decisions, for example:

```json
{
  "version": 1,
  "reasoning": "Tap the search field first.",
  "action": {
    "type": "click_text",
    "text": "Search"
  },
  "is_complete": false
}
```

Required action types for MVP:

- `noop`
- `wait`
- `click_text`
- `click_coord`
- `type_text`
- `scroll`
- `press_back`
- `press_home`
- `open_app`
- `finish`

Parser requirements:

- Extract JSON from fenced or noisy responses.
- Reject malformed actions clearly.
- Preserve a raw response preview in logs for debugging.

### 2. Upgrade Observations From Plain Text To Structured UI State

Current state:

- `LocalAgentObserver.observe()` only returns `screenText`.

MVP target:

- Include `packageName`
- Include a flattened node list
- Include per-node:
  - visible text
  - content description
  - class name
  - clickable/editable/scrollable flags
  - bounds
  - center coordinates
  - optional viewId when available

Implementation path:

- Extend `LocalAgentAccessibilityService` with a `dumpScreenNodes()` API similar in spirit to `private-agent`.
- Extend `LocalAgentAccessibilityBridge` to return a structured snapshot object, not only a text blob.
- Expand `LocalAgentObservation.kt` into a richer data model.

Important constraint:

- Keep the payload compact. The model should see a trimmed, deterministic subset, not the entire raw tree forever.

### 3. Expand The Local Action Model

Current action model is too narrow:

- `Sleep`
- `GlobalBack`
- `GlobalHome`
- `ClickText`
- `TypeText`
- `SendEmail`

Needed for MVP:

- `Wait(ms)`
- `ClickText(text)`
- `ClickCoord(x, y)`
- `TypeText(text, hint?)`
- `Scroll(direction)`
- `PressBack`
- `PressHome`
- `OpenApp(appName)`
- `Finish(message?)`

Files to update:

- `localagent/LocalAgentAction.kt`
- `localagent/LocalAgentActionParser.kt`
- `localagent/LocalAgentAccessibilityBridge.kt`
- `localagent/LocalAgentStepEngine.kt`
- `localagent/actions/LocalAgentActionManager.kt`

Execution policy:

- `OpenApp` should use a normal Android launch intent, not Accessibility.
- `ClickCoord` should use gesture dispatch.
- `Scroll` should use gesture scrolling first.
- `TypeText` should focus the field before set-text when possible.

### 4. Replace The No-Op Brain With A Real Planner

Current state:

- `LocalAgentService` uses `NoOpLocalAgentBrain()`.

MVP target:

- A real `LocalAgentBrain` implementation that calls the currently selected model backend.

Recommended first version:

- Use the existing network-backed AI path already present in CyanBridge for the MVP.
- Keep the interface pluggable so we can later swap in a fully local model.

Recommended classes:

- `RemoteUiControlLocalAgentBrain`
- optional `ScriptedFallbackLocalAgentBrain` for testing predictable flows

Prompt strategy:

- Send goal
- Send current app package
- Send structured node summary
- Send previous action result
- Ask for exactly one next action

Guardrails:

- Max steps from prefs
- Stop after repeated failures
- Stop when no screen is readable
- Stop when the same action repeats too many times

### 5. Introduce A Task Session Model

The current loop is continuous but not task-oriented enough.

Add a session object that holds:

- user goal
- step index
- previous action
- previous result
- failure count
- startedAt
- package history
- completion status

Why:

- Phone control should run per request, not as an unbounded idle loop.
- We need logs and user-visible summaries for each task.

Implementation direction:

- Keep `LocalAgentService`, but add explicit "run one task" semantics.
- The service can remain foreground while a task is active.
- After completion, it should return to idle or stop.

### 6. Connect The Agent To Chat UX

Users should not need to manually start a background loop and hope it acts.

MVP UX recommendation:

- In chat, detect requests that are clearly phone-control requests.
- Offer a dedicated action chip/button like `Run on phone` when needed.
- Start a task session with the user's message as the goal.
- Stream step updates into the chat thread or a lightweight status panel.

Good first integration points:

- `ui/ChatThreadActivity.kt`
- existing local-agent provider routing
- `LocalAgentController` / `LocalAgentService`

Do not ship MVP as a hidden background automation feature only accessible from Settings.

### 7. Tighten Safety And Approval Rules

This is where CyanBridge should be stricter than the inspiration repo.

Keep and extend:

- accessibility must be explicitly enabled by the user
- max step limit
- pending-action approval queue
- app blacklist / privacy controls
- do not run when device is locked

Add for MVP:

- blocklist sensitive packages by default:
  - banking
  - password managers
  - package installer
  - system settings areas that can factory-reset / uninstall / grant dangerous permissions
- require confirmation for:
  - text entry into unknown fields
  - sending messages/emails
  - destructive taps
  - actions outside an allowlisted package set if we expose that setting
- show current target app in the approval UI

### 8. Keep Memory Capture Separate From Control

Current memory capture is useful, but it should not become the control protocol.

Rules:

- Reuse screen-capture infrastructure for logging and later memory if helpful.
- Do not force every control step into long-term memory.
- Store task traces separately from passive daily memory.
- Make it easy to delete automation traces.

Recommended new artifact:

- `localagent/tasktrace/` or a small Room table for task sessions + steps.

### 9. Logging And Debugging

MVP will be hard to tune without step-level logs.

Log per step:

- task id
- goal
- app package
- compact observation summary size
- raw model reply
- parsed action
- execution result
- stop reason

Add a debug viewer if cheap:

- latest task trace screen
- exportable JSON trace for failed tasks

## Concrete File-Level Work Breakdown

### Phase 1: Core Protocol + Observation

Implement:

- add `LOCAL_AGENT_UI_CONTROL_PROTOCOL.md` or Kotlin schema object
- add `LocalAgentUiControlProtocol.kt`
- extend `LocalAgentObservation.kt`
- extend `LocalAgentAccessibilityService.kt` with structured node dump
- extend `LocalAgentAccessibilityBridge.kt` to expose node snapshots and more action primitives

Done when:

- a debug call can capture current package + node list + plain-text summary from any normal app screen

### Phase 2: Action Surface + Execution Engine

Implement:

- expand `LocalAgentAction.kt`
- expand `LocalAgentActionParser.kt`
- update `LocalAgentStepEngine.kt`
- update `LocalAgentActionManager.kt` risk classes for new actions
- add `OpenApp` and `ClickCoord` execution

Done when:

- a scripted test brain can open an app, tap a known element, type, scroll, and stop

### Phase 3: Real Brain Integration

Implement:

- `RemoteUiControlLocalAgentBrain`
- prompt builder using one-step JSON output
- repeated-action/failure guards
- previous-result feedback loop

Done when:

- a user can run 2-3 demo tasks successfully on common apps like Settings, YouTube, or Maps

### Phase 4: Chat And Service UX

Implement:

- task session start from chat
- step progress surfaced to user
- success/failure summary back into chat
- idle/active service state cleanup

Done when:

- the feature is usable without opening the Settings screen first, except for one-time accessibility setup

### Phase 5: Safety + Polish

Implement:

- default sensitive-package denylist
- stronger confirmation routing
- task trace viewer/export
- better copy in settings and README

Done when:

- failures are understandable and risky actions are never silent

## Recommended First Demo Flows

Use these as manual acceptance tests:

1. Open Settings and search for Bluetooth.
2. Open YouTube and search for lo-fi jazz.
3. Open Google Maps and search for coffee.
4. Open WhatsApp and search for a contact without sending any message.
5. Read the current screen aloud using existing TTS demo plumbing.

Avoid using messaging send flows as the very first success metric.

## Testing Plan

Automated:

- parser tests for the new UI-control protocol
- malformed JSON extraction tests
- action parser tests for every action type
- risk-classification tests
- session stop-condition tests

Manual:

- accessibility enabled/disabled transitions
- locked-screen behavior
- blocked-package behavior
- confirmation-required behavior
- max-step exhaustion behavior
- Samsung/Pixel device sanity checks if available

## Recommended Order Of Implementation

1. Structured observation dump.
2. Expanded action model and executor.
3. Dedicated UI-control protocol.
4. Real brain implementation using existing remote model path.
5. Task session plumbing in `LocalAgentService`.
6. Chat entrypoint and user-facing progress.
7. Safety hardening and debug trace UI.

## MVP Success Criteria

The MVP is successful when all of the following are true:

- A chat request can launch a bounded phone-control task.
- The agent can inspect the active UI and take multiple sequential steps.
- The agent can recover from at least simple UI changes by re-reading the screen.
- The user can see what the agent is doing and stop it.
- Risky actions require approval.
- The feature works without Tasker or AutoInput.
- The implementation stays compatible with CyanBridge's privacy-first local-memory direction.

## Summary Recommendation

Build this as a native CyanBridge Kotlin feature, borrowing `private-agent`'s best idea: the small, repeated observe -> decide one action -> execute -> observe loop.

Do not copy its whole product surface. For our MVP, the right scope is:

- one-step JSON phone-control protocol
- structured accessibility observation
- bounded task sessions
- strict approval/safety rules
- chat-driven entrypoint inside CyanBridge
