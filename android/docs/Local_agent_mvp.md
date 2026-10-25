# Local Agent Plugin: Current Implementation Analysis, Objectives, and Suggestions

This note is a verbatim record of the findings, analysis, and suggestions prepared for the Local Agent native plugin in CyanBridge.

## Executive Assessment

The Local Agent is a functional generic Android Accessibility automation MVP, not yet a complete hands-free phone agent.

Its core architecture matches the strongest idea from `private-agent`: repeatedly observe the active screen, ask a model for one action, execute it, and observe again. CyanBridge improves on the reference with stricter action schemas, approval classes, lock-screen checks, privacy controls, and safer Shizuku use.

However, the intended glasses experience is only partially complete:

| Objective | Current state |
|---|---|
| Start from the glasses Audio Question button | Implemented |
| Distinguish normal questions from phone commands | Implemented, primarily English-oriented |
| Open apps and navigate their UI | Implemented generically |
| Search YouTube and play a result | Plausible but not validated end to end |
| Like/save Spotify content | Plausible but fragile |
| Read Gmail and summarize it aloud | Not complete |
| Reply/forward Gmail with confirmation | Generic controls exist, but confirmation UX is unsuitable |
| Confirm actions through voice | Not implemented for Local Agent |
| Show a listening/approval window | Not implemented |
| Use localized “Answer now” cues | Not implemented for Local Agent |
| Prevent audio interference | Not implemented reliably |
| Provide a useful spoken completion result | Not implemented; usually says “Done.” |

The best description is: **a model-driven Accessibility execution engine with glasses voice routing, but not yet a coherent voice-first agent session.**

## Current End-To-End Flow

1. The glasses send notification command `0x03` when the AI/audio button is pressed.
2. `MainActivity` calls `triggerAssistantVoiceQuery()` when AI hijacking is enabled.
3. CyanBridge selects the configured assistant route: phone assistant, local model, Pro relay, or Tasker.
4. For Local or Pro routing, CyanBridge establishes the Bluetooth microphone route.
5. It speaks the English cue `"I am listening"`.
6. Android `SpeechRecognizer` transcribes the request.
7. `AssistantRequestRouter` classifies it as:
   - `ANSWER_QUESTION`
   - `ANALYZE_IMAGE`
   - `EXECUTE_UI_TASK`
   - `CLARIFY`
8. An `EXECUTE_UI_TASK` request calls `LocalAgentController.start(context, goal)`.
9. `LocalAgentService` starts a foreground, bounded observe-plan-act loop.
10. `LocalAgentObserver` captures the current package, visible text, and structured Accessibility nodes.
11. `RemoteUiControlLocalAgentBrain` asks the selected local or remote model for exactly one JSON action.
12. `LocalAgentStepEngine` classifies its risk, executes it or queues it for approval, then observes again.
13. On model-declared completion, `LocalAgentService` normally speaks `"Done."`.

The glasses entry point is at `MainActivity.kt:9724`, voice routing at `MainActivity.kt:4882`, and Local Agent dispatch at `MainActivity.kt:5053`.

## Plugin Structure

The “native plugin” is a built-in facade, not a dynamically loaded plugin:

- `plugins/localagent/LocalAgentPlugin.kt` synchronizes the plugin registry with the existing automation preference.
- Enabling it permits phone automation.
- Starting it delegates to `LocalAgentController`.
- Disabling it stops the Local Agent.
- Planning can use a local model or Pro/remote provider.

This distinction matters: enabling the plugin does not necessarily mean all processing is local. Accessibility observations are sent to whichever planning backend is selected. Remote screenshot upload has a separate consent switch, but structured screen text is still sent to a remote planner when a remote provider is selected.

There are also older classes under `ai/assistant/`, including `LocalAgentRouter`, that still return “not implemented.” They are legacy/skeleton routing code and are not the active phone-control planner. The active path uses:

- `localagent/LocalAgentBrain.kt`
- `ai/router/AgentInferenceRouter.kt`
- `localmodels/provider/LocalModelsProvider.kt`

This duplicate terminology is architectural debt and can mislead maintainers.

## Observation And Planning

The current observer is reasonably mature:

- Captures the active package.
- Extracts visible text and content descriptions.
- Includes hints, class names, view IDs, bounds, and center coordinates.
- Marks nodes as clickable, editable, scrollable, checkable, checked, focused, or password fields.
- Redacts password text.
- Includes the keyboard/IME window so the planner can see Search, Send, Done, and similar actions.
- Limits prompt size to 120 nodes and 12,000 characters by default.

Relevant files:

- `localagent/LocalAgentObservation.kt`
- `localagent/LocalAgentObserver.kt`
- `localagent/accessibility/LocalAgentAccessibilityService.kt`
- `localagent/LocalAgentUiControlProtocol.kt`

Optional screenshots can supplement the structured tree. They are deleted immediately after inference and are not added to task history or long-term memory.

## Action Surface

The planner can request:

- Open an app.
- Click text or content descriptions.
- Tap coordinates.
- Type into an editable field.
- Submit the keyboard action.
- Scroll or perform a custom swipe.
- Long-press.
- Press Back or Home.
- Open notifications or recent apps.
- Open contacts.
- Open a dialer.
- Open SMS or email composers.
- Set an alarm.
- Open Wi-Fi or Bluetooth settings.
- Read visible screen text aloud.
- Finish the task.

This is enough in principle for the YouTube flow:

1. Open YouTube.
2. Click Search.
3. Type the video query.
4. Press Enter.
5. Click a result.

It is also enough in principle to press Spotify’s Like/Save control if that control is visible through Accessibility or can be targeted by coordinates.

There are no app-specific adapters, deep links, media-session APIs, Gmail APIs, YouTube APIs, or Spotify APIs. Everything is based on the current UI tree and generic gestures.

Some action names overstate their behavior:

- `toggle_wifi` opens Wi-Fi settings rather than toggling Wi-Fi.
- `toggle_bluetooth` opens Bluetooth settings.
- `toggle_flashlight` opens CyanBridge’s application-details settings and does not toggle the flashlight.

## Safety Model

Implemented safeguards include:

- Phone must be awake and unlocked.
- Accessibility and notification permissions are required.
- Maximum task steps are configurable.
- Five consecutive planner failures stop the task.
- Repeated identical actions are blocked.
- Screenshots are opt-in.
- Remote screenshot transport requires separate consent.
- Password fields are redacted.
- Low-risk skill replay stores only exact-goal, low-risk paths.
- Shizuku is optional and restricted to fixed operations.
- Task history excludes screen text.

CyanBridge is safer than `private-agent` around Shizuku. Upstream exposes generic shell commands, including force-stop and clear-data operations. CyanBridge deliberately exposes no model-generated shell command API.

The main safety weakness is `LocalAgentSafetyPolicy`: it blocks only packages manually added to the user’s capture blacklist. There is no default denylist for banking, password managers, package installers, authentication apps, or sensitive system settings. The test at `LocalAgentSafetyPolicyTest.kt:18` explicitly verifies this behavior.

## Approval System

The current approval system is not suitable for a hands-free glasses workflow.

Current behavior:

- Low-risk actions may execute automatically.
- Clicks, typing, swipes, and long-presses are medium risk.
- Keyboard submission, screen reading, calls, SMS, alarms, and email are high risk.
- Medium/high-risk actions are inserted into the pending-actions Room table.
- The service pauses and changes its notification to “Waiting for your approval.”
- The user must manually find `PendingActionsActivity`, inspect JSON, and tap Approve or Reject.

Important problems:

- The approval window is not opened automatically.
- The notification opens `MainActivity`, not the pending action directly.
- No approval request is spoken.
- No voice response is captured.
- The pending action is shown primarily as JSON rather than a user-oriented explanation.
- Rejecting an action updates the database but does not signal the waiting service. The task can remain paused indefinitely.
- Approval resumes the task even when execution returned failure; the service records it as successfully approved and executed.
- With default settings, a YouTube task can require several separate manual approvals for Search, typing, Enter, and selecting a result.

Disabling confirmation makes the workflow more autonomous but removes the main safety boundary. There is currently no balanced “hands-free but confirmed” mode.

## Why “Done” Is Heard

The source is confirmed in `LocalAgentService.kt:597`:

```kotlin
return if (failed) "I couldn't finish that task." else "Done."
```

There are three related causes:

1. Every apparently successful Local Agent task discards its meaningful finish message and speaks `"Done."`.
2. Starting Local Agent without a goal uses the default instruction to inspect the screen and “finish quickly rather than guessing.”
3. Several entry points start the agent with no goal:
   - The Native Plugin “Start agent” shortcut.
   - The Local Agent Settings “Start” button.
   - The background-feature permission restart path.

Consequently, a goal-less run can quickly complete and speak `"Done."` even though the user did not issue a phone-control command.

There is also no shared audio-session coordinator between:

- `MainActivity` voice-question TTS
- `LocalAgentService` completion TTS
- Streaming local-model speech
- Other plugin TTS services

`LocalAgentService` owns a separate `TextToSpeech` instance and uses `QUEUE_FLUSH`. Its completion can therefore overlap with, interrupt, or be heard near the next Audio Question cue.

A second lifecycle issue exists when a task is already running. A new `start(goal)` is accepted by the controller, but `LocalAgentService` detects that it is already running and ignores the new goal. `MainActivity` may still say `"Okay. I'll do that."`, even though the request was not queued or executed.

## Normal Questions Versus Automation

The architecture correctly allows the Local Agent to coexist with normal Audio Questions.

`AssistantRequestRouter` uses explicit command classification:

- “Open Spotify and play my liked songs” routes to `EXECUTE_UI_TASK`.
- “How do I open Bluetooth settings?” remains `ANSWER_QUESTION`.
- Image requests route to `ANALYZE_IMAGE`.
- Ambiguous requests use model classification or clarification.

This means Local Agent should not automatically steal every normal question.

Limitations:

- Heuristic classification is English-only.
- Verbs such as “like,” “save,” “forward,” and “reply” are missing from the action-verb regex.
- Non-English requests depend on model classification.
- If classification fails, it safely falls back to answering rather than controlling the phone.
- A `CLARIFY` response is spoken, but CyanBridge does not automatically reopen listening. The user must press the glasses button again.

## Workflow Assessment

| Workflow | Assessment |
|---|---|
| Open YouTube | Strong: direct app launch support |
| Search for a video | Moderate: click, type, Enter supported |
| Play a selected result | Moderate: generic click only, no playback verification |
| Like/save Spotify song | Weak-to-moderate: depends on accessible labels or stable coordinates |
| Open Gmail | Strong: direct app launch |
| Open a specific email | Moderate: generic screen navigation |
| Read visible email aloud | Partial: only currently visible Accessibility text |
| Summarize an email aloud | Missing result pipeline |
| Reply or forward | Technically possible but approval-heavy and fragile |
| Confirm send by voice | Missing |
| Verify that mail was sent | Weak: completion is model-declared |
| General phone tasks | Basic generic capability, variable reliability |

Gmail summarization is not end-to-end because the planner’s terminal message is not delivered as a spoken answer. `Finish(message)` is reduced to either `"Done."` or `"I couldn't finish that task."`. `ReadScreenAloud` speaks raw visible text, not an LLM-generated summary.

## Comparison With `private-agent`

I reviewed upstream at commit `ce84a4710ffecea0ab1b3d9a1c16ae5d7794b199`, dated July 17, 2026.

Clearly adopted architectural ideas include:

- One action per model decision.
- Accessibility-tree dumps with coordinates.
- Observe-action-observe iteration.
- Previous-result feedback.
- App launching and gesture primitives.
- Maximum-step limits.
- Repetition detection.
- Task history.
- Skill replay.
- Optional Shizuku fallback.

CyanBridge improvements include:

- Kotlin-first native implementation rather than Flutter/native channels.
- Strict versioned JSON schema.
- Local, Pro, and OpenAI-compatible planner routing.
- Lock-screen termination.
- Risk classification and approval queue.
- Password redaction.
- Exact-goal, low-risk-only skill replay.
- Ephemeral screenshot handling.
- Separate remote screenshot consent.
- No generic Shizuku shell API.

Areas where upstream remains more complete include:

- Visible floating progress overlay.
- Better screen-description compression.
- Navigation shortcuts.
- Explicit recovery actions after failures.
- Approximate skill matching.
- A dedicated voice input UI.
- More mature task progress reporting.

Neither implementation currently provides the voice-confirmation experience described in the desired glasses objective. Upstream’s voice support starts tasks, but it does not provide CyanBridge-style risk approval.

There is also still no clear top-level license grant in the source repository. Its README says it is open source, but there is no top-level `LICENSE`, and `local_plugins/agent_native/LICENSE` contains a TODO. CyanBridge should continue treating it as architecture inspiration rather than copied implementation. CyanBridge’s Accessibility service separately attributes PhoneClaw under MIT.

## Required Voice Confirmation Design

The intended flow should be:

1. Pause when an action needs confirmation.
2. Display a dedicated approval surface showing the task, target app, and exact proposed action.
3. Route audio to the glasses.
4. Speak a localized explanation such as “CyanBridge wants to send this reply. Approve?”
5. Speak a short listening cue after TTS has fully drained.
6. Start speech recognition using the selected app language.
7. Accept approve, deny, repeat, or cancel.
8. Execute only after affirmative confirmation.
9. Resume only if execution succeeds.
10. Speak one meaningful localized final result.

Suggested cues for the app’s language set:

| Language | Cue |
|---|---|
| English | “Answer now.” |
| Portuguese | “Responda agora.” |
| Spanish | “Responda ahora.” |
| German | “Jetzt antworten.” |
| French | “Répondez maintenant.” |
| Italian | “Rispondi ora.” |
| Simplified Chinese | “请现在回答。” |
| Korean | “지금 대답해 주세요.” |
| Russian | “Ответьте сейчас.” |

The existing image-question implementation already demonstrates the correct sequencing pattern: configure the Bluetooth route, speak a localized cue, wait for TTS completion plus a Bluetooth tail delay, then start `SpeechRecognizer`. `StudioApprovalHandler` also already contains multilingual approval classification and retry logic. Those two implementations provide most of the reusable design, but they are not connected to Local Agent approvals.

Because Android restricts launching Activities from background services, the approval surface should use a direct notification `PendingIntent` and a heads-up/full-screen presentation only where platform and policy rules permit it. A dedicated approval Activity should still be the visible destination.

## Suggested Priorities

1. Remove all goal-less Local Agent starts from enablement, permission restart, and “Start agent” paths.
2. Replace `"Done."` with a session-owned, meaningful, localized result and ensure it is spoken only once.
3. Add a process-wide audio coordinator so Local Agent cannot overlap Audio Question listening or another TTS response.
4. Implement Local Agent voice approval using the image-question audio sequencing and Studio approval classifier.
5. Make Reject signal the paused service immediately and make failed approved execution remain a failure.
6. Open the pending action directly from the notification and show a human-readable action summary.
7. Add a result-response contract so tasks can return Gmail summaries or other generated content.
8. Show the active package automatically in every approval prompt and spoken approval summary.
9. Add app-specific acceptance tests for YouTube search/play, Spotify save, and Gmail read/reply.
10. Add multilingual command heuristics and configure recognition/TTS from `AppLanguagePreferences`.

## Next execution changes (audio-first)

The current audio-first execution plan is:

1. Remove goal-less Local Agent starts from non-voice-control entry points.
2. Make Local Agent completion speech session-owned, meaningful, localized, and spoken exactly once.
3. Add a shared audio-session coordinator so Local Agent TTS, voice-question TTS, and streaming speech cannot overlap.
4. Replace the current manual approval UX with audio-first approval: speak the proposed action, speak a localized listening cue, then accept approve/deny/repeat/cancel by voice.
5. Keep a visual approval Activity as a backup accessibility surface, but do not require it for the normal flow.
6. Make Reject signal the paused service immediately and keep failed approved execution as a failure.
7. Add a result-response contract so tasks such as Gmail summarization can return spoken generated content.
8. Add multilingual approval cues and route recognition/TTS from `AppLanguagePreferences`.
9. Add app-specific acceptance flows for YouTube search/play, Spotify save, and Gmail read/reply.

Target code surfaces for these changes:

- `LocalAgentService.kt`
- `LocalAgentController.kt`
- `LocalAgentPlugin.kt`
- `LocalAgentSettingsActivity.kt`
- `PendingActionsActivity.kt`
- `MainActivity.kt`
- shared approval/audio helpers reused from `ImageQuestionAudio` / `StudioApprovalHandler`

## Verification Notes

The focused Local Agent and assistant-routing unit tests pass:

```text
:app:testDebugUnitTest
BUILD SUCCESSFUL
```

There are no instrumentation or physical-device tests proving the complete YouTube, Spotify, Gmail, Bluetooth-audio, or voice-approval workflows.
