# CyanBridge Tasker plugin migration plan

This document keeps the three Tasker migrations separate so Local Agent debugging does not become entangled with AutoDiary or Visual Diary behavior.

## 1. Local Agent — current branch

### Responsibility split

**CyanBridge owns**

- task goal and task state
- local/remote model inference
- action selection
- risk classification
- approval queue and rejection state
- repeat limits and recovery decisions
- native Android actions that do not need Accessibility
- task history and execution-result logging

**Tasker + AutoInput own**

- screen observation
- Accessibility-dependent UI primitives
- returning the concrete result of the requested primitive

Tasker intentionally has no independent risk/cancellation policy. If AutoInput cannot execute a requested primitive, it reports the failure and CyanBridge decides what the planner does next.

### Contract files

- `CyanBridge_LocalAgent_Tasker.XML`
- `LOCAL_AGENT_TASKER_CONTRACT.md`
- `TaskerAgentContract.kt`
- `TaskerAgentBridge.kt`

### First on-device validation sequence

1. Import `CyanBridge_LocalAgent_Tasker.XML` into Tasker.
2. Ensure Tasker and AutoInput are installed and AutoInput can use Accessibility when its actions run.
3. Start with a deterministic goal such as opening an app and navigating to a visible text button.
4. Verify `TASKER_AGENT_OBSERVE` returns package/text data.
5. Verify `click_text` and `click_coord` independently.
6. Verify text entry into a focused field.
7. Verify Back/Home/Recents/Notifications.
8. Verify scrolling and long press.
9. Exercise one CyanBridge-native action such as `open_app` to confirm it does not round-trip through Tasker.
10. Exercise a HIGH-risk action and confirm the sequence is CyanBridge approval -> execution -> stored result -> planner resume.
11. Finalize device-compatible `press_enter` and arbitrary `swipe` adapters; v1 currently returns explicit adapter errors for those two rather than hiding failure.

### Protocol parity follow-up

The current base-branch Local Agent protocol does not contain first-class actions for package installation, generic arbitrary intents, or generic shell commands. If those are desired as model-selectable actions, add them deliberately to:

1. `LocalAgentUiControlProtocol.Action`
2. the response JSON schema/parser
3. `LocalAgentAction`
4. risk classification and serialization
5. the execution backend
6. Tasker only when the operation genuinely belongs on the Tasker side

This is separate from the Accessibility migration so protocol expansion can be reviewed/debugged independently.

---

## 2. AutoDiary — planned next migration

### Current architecture

`AutoDiaryService` is primarily a lifecycle wrapper around the existing screen-memory and daily-summary pipeline. Its direct Accessibility dependency is the screen-observation prerequisite. Daily summary generation, memory storage, indexing, and review should remain in CyanBridge.

### Target responsibility split

**CyanBridge owns**

- AutoDiary enabled state and settings
- capture blacklist / exclusions
- Memory Mode policy
- deduplication and capture throttling
- screen-memory persistence
- embeddings/indexing
- candidate/daily facts processing
- daily-summary generation and regeneration
- all diary review UI
- capture/result diagnostics

**Tasker + AutoInput own**

- detecting that useful screen context should be sampled
- AutoInput UI Query
- sending the raw/normalized observation to CyanBridge
- reporting query/capture failure

Tasker should not decide whether captured content is semantically sensitive, useful, memorable, duplicated, or allowed by diary policy. Those decisions remain observable in one CyanBridge pipeline.

### Proposed profile

`CyanBridge_AutoDiary_Tasker.XML`

Suggested profiles/tasks inside it:

1. **CyanBridge AutoDiary Capture Trigger**
   - initially periodic while screen is on/unlocked, with a conservative interval
   - later optionally add app/window-change triggers
2. **CyanBridge AutoDiary Capture**
   - run AutoInput UI Query
   - build observation JSON
   - send it to CyanBridge
3. **CyanBridge AutoDiary Status**
   - optional callback/error receiver for diagnostics

### Proposed IPC

Tasker -> CyanBridge:

`com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_CAPTURE`

Payload v1:

```json
{
  "contract_version": 1,
  "captured_at_ms": 1770000000000,
  "package_name": "com.example.app",
  "text_summary": "...",
  "nodes": []
}
```

Optional CyanBridge -> Tasker state broadcasts:

- `com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_ENABLE`
- `com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_DISABLE`

These let the CyanBridge plugin toggle remain the source of truth rather than requiring users to separately remember whether a Tasker profile is enabled.

### CyanBridge changes

1. Add a small exported receiver dedicated to AutoDiary ingestion.
2. Move the existing accessibility-screen-capture entry point behind a `ScreenContextSource`-style abstraction.
3. Add `TaskerAutoDiarySource` that validates/parses the Tasker observation and feeds the exact existing memory pipeline.
4. Keep capture blacklist, Memory Mode, dedupe and storage checks after ingestion, not in Tasker.
5. Remove AutoDiary's Accessibility permission prerequisite only after the Tasker path is proven.
6. Keep `AUTO_DIARY_SUMMARIZE` and `DailySummaryRegenerateWorker` in CyanBridge.
7. Keep the legacy observer temporarily selectable for debugging, then remove it once diary parity is demonstrated.

### Validation

Compare legacy Accessibility capture and Tasker capture for the same screens:

- foreground package
- text coverage
- duplicate suppression
- capture frequency
- resulting memory entries
- generated daily summary

The migration is complete when diary output is equivalent enough that `AutoDiaryService.enable()` no longer needs CyanBridge Accessibility permission.

---

## 3. Visual Diary / Vision Diary — planned after AutoDiary

Repository naming currently uses `VisualDiary`; the user-facing/tasker filename can remain explicit, for example `CyanBridge_VisualDiary_Tasker.XML`.

### Current architecture

Visual Diary does not use Android Accessibility. `VisualDiaryService` currently owns the periodic loop and delegates actual glasses-camera capture and visual-note generation to CyanBridge components.

Therefore this migration is primarily orchestration cleanup and consistency with the Tasker-based automation architecture, not an Accessibility migration.

### Target responsibility split

**CyanBridge owns**

- selected device capability checks
- HeyCyan/native glasses capture path
- Meta Ray-Ban DAT initialization and capture
- image persistence
- prompt selection
- Gemma/local visual-model processing
- visual-note creation/storage
- error state and diagnostics

**Tasker owns**

- the periodic schedule/trigger only
- invoking a one-shot CyanBridge capture command

Tasker should not attempt to access the glasses camera or reproduce the visual-model pipeline.

### Proposed profile

`CyanBridge_VisualDiary_Tasker.XML`

Initial design:

1. A Tasker Time profile at the configured interval.
2. Send an explicit intent to CyanBridge requesting one visual capture.
3. CyanBridge performs `captureNow` using the selected glasses profile and returns/logs the result.

We can initially reuse:

`com.fersaiyan.cyanbridge.action.VISUAL_DIARY_CAPTURE_NOW`

or introduce a Tasker-specific wrapper action if explicit callback diagnostics are useful:

`com.fersaiyan.cyanbridge.TASKER_VISUAL_DIARY_CAPTURE`

### CyanBridge changes

1. Keep the existing one-shot `captureNow()` implementation intact.
2. Add a Tasker entry point only if the existing service action is inconvenient for external invocation.
3. Keep the Visual Diary enable/disable state in CyanBridge.
4. When enabled, CyanBridge can broadcast the desired interval to Tasker or the Tasker project can start with a user-configured fixed interval.
5. Once the Tasker scheduler is proven, remove the long-running periodic coroutine from `VisualDiaryService` so it becomes a one-shot capture host rather than a scheduler.
6. Keep foreground-service behavior only for the duration needed by capture/processing.

### Validation

For each supported device family:

- manual `capture now`
- scheduled capture
- disconnected glasses behavior
- Meta camera initialization
- HeyCyan/native capture
- file persistence
- visual-note generation
- custom prompt behavior
- error propagation

---

## Recommended order

1. Finish and test `CyanBridge_LocalAgent_Tasker.XML`.
2. Fix any Tasker/AutoInput export-version quirks found on the real device.
3. Migrate AutoDiary screen observation into `CyanBridge_AutoDiary_Tasker.XML`.
4. Compare diary output against the legacy Accessibility observer.
5. Remove the AutoDiary Accessibility prerequisite from CyanBridge.
6. Create `CyanBridge_VisualDiary_Tasker.XML` for scheduling one-shot visual captures.
7. Remove VisualDiary's internal periodic scheduler only after Tasker scheduling is reliable.

Keeping these as separate profiles gives each automation a small, inspectable failure surface and makes it obvious whether a failure occurred in Tasker observation/execution, CyanBridge policy/state, camera capture, or model processing.
