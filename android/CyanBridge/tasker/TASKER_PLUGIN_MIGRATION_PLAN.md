# CyanBridge Tasker migration plan

The migration boundary is now explicit:

> **CyanBridge owns product logic, state, policy, memory, models and smart-glasses integrations. Tasker owns Android UI observation and model-selected Android-side execution.**

Tasker is deliberately mechanical. It does not independently reclassify risk, silently cancel actions, apply AutoDiary privacy rules, or become a second source of truth for CyanBridge settings.

---

## 1. Local Agent — implemented on this branch

### CyanBridge owns

- task goal and task state
- local/remote model inference
- action selection
- risk classification and approval queue
- repeat limits and recovery decisions
- task history and exact result logging

### Tasker + AutoInput own

- Android screen observation
- UI primitives such as clicks, typing, scrolling and global navigation
- model-selected Android device effects such as app launch, dialer/SMS/email composers, alarms, contacts, settings and flashlight
- returning concrete success/failure details

`wait` and `finish` remain CyanBridge runtime operations because they are not Android device effects.

Files:

- `CyanBridge_LocalAgent_Tasker.XML`
- `LOCAL_AGENT_TASKER_CONTRACT.md`
- `TaskerAgentContract.kt`
- `TaskerAgentBridge.kt`

Current caveat: `press_enter` and arbitrary `swipe` still return explicit adapter errors until their exact AutoInput configuration is validated on-device.

### Protocol-parity follow-up

The current Local Agent action schema still does not contain first-class model actions for package installation, arbitrary Android intents or generic shell commands. If/when those are added, CyanBridge should own parsing/risk/approval and Tasker should own execution. Do not add parallel CyanBridge-native executors for them.

---

## 2. AutoDiary — Tasker observation path implemented on this branch

AutoDiary is different from Local Agent: Tasker does not execute a model action. CyanBridge decides when a diary sample is due; Tasker only observes the Android UI.

### CyanBridge owns

- AutoDiary enabled state
- capture interval and scheduler
- device-ready/vault checks
- app blacklist and overlay exclusions
- capture normalization
- memory persistence and retention
- Room / FTS indexing
- facts, embeddings, summaries and review UI
- diagnostics

### Tasker + AutoInput own

- responding to a correlated observation request
- AutoInput UI Query
- returning foreground package, visible text and best-effort node metadata

Files:

- `CyanBridge_AutoDiary_Tasker.XML`
- `AUTO_DIARY_TASKER_CONTRACT.md`
- `AutoDiaryCaptureCoordinator.kt`
- updated `AutoDiaryService.kt`
- shared `TaskerAgentBridge.kt` / `TaskerAgentContract.kt`

### State migration

The old Accessibility-backed AutoDiary reused `LocalAgentPrefs.auto_capture_enabled`. The Tasker-backed version uses a separate `tasker_auto_diary_enabled` state. On migration, CyanBridge persists the new state and clears the legacy Accessibility auto-capture bit so an enabled CyanBridge Accessibility service cannot create duplicate screen captures.

The capture interval, blacklist and memory settings remain in CyanBridge.

### On-device validation

1. Import `CyanBridge_AutoDiary_Tasker.XML`.
2. Disable CyanBridge Accessibility access.
3. Keep AutoInput Accessibility access enabled.
4. Enable AutoDiary.
5. Verify a benign app is captured at the configured interval.
6. Verify blacklisted/overlay packages are rejected by CyanBridge.
7. Verify Memory Vault lock prevents storage.
8. Verify captures still feed the existing daily-summary/facts pipeline.
9. Verify no duplicate capture comes from the legacy CyanBridge Accessibility service.

Once this works on-device, the remaining AutoDiary-specific periodic capture code inside `LocalAgentAccessibilityService` can be deleted as cleanup rather than as a functional dependency.

---

## 3. Visual Diary / Vision Diary — keep in CyanBridge

Visual Diary does **not** need a mandatory Tasker migration for the Accessibility-removal objective.

Its camera path is a CyanBridge-owned smart-glasses integration, not Android UI automation:

- Meta Ray-Ban capture goes through `MetaRaybanManager` / DAT
- other supported glasses use the existing CyanBridge capture path
- image persistence and Gemma/vision processing stay in CyanBridge

### CyanBridge owns

- Visual Diary enabled state and interval
- selected-device capability checks
- Meta/HeyCyan/native glasses communication
- one-shot photo capture
- image persistence
- prompt/model selection
- vision inference
- visual-note storage and diagnostics

The existing `VisualDiaryService` can continue to schedule periodic one-shot captures internally. A future refactor may extract a `VisualDiaryCaptureCoordinator` so scheduling and one-shot capture are cleaner units, but this does not require Tasker.

### Optional Tasker triggers

A Tasker Visual Diary profile is useful only for Android-context triggers that CyanBridge does not otherwise own, for example:

- capture after phone unlock
- capture when arriving at a location
- capture when a particular Android app opens
- capture when a particular Bluetooth condition occurs

In that optional design, Tasker only sends a one-shot `capture now` trigger. CyanBridge still performs all glasses-camera and vision-model work. Tasker must not mirror the normal Visual Diary interval because that would create two interval sources of truth.

---

## Recommended validation order

1. Import and validate `CyanBridge_LocalAgent_Tasker.XML`.
2. Resolve real-device AutoInput quirks for `press_enter` / arbitrary `swipe`.
3. Import and validate `CyanBridge_AutoDiary_Tasker.XML` with CyanBridge Accessibility disabled.
4. Compare Tasker AutoDiary observations against representative legacy captures.
5. Remove the now-unused AutoDiary periodic-capture implementation from `LocalAgentAccessibilityService` after parity is proven.
6. Keep Visual Diary in CyanBridge; only add optional Tasker triggers if a concrete Android-context trigger is desired.

This keeps each failure domain easy to audit:

- **CyanBridge policy/state failure** → logged in CyanBridge
- **Tasker/AutoInput Android automation failure** → returned by Tasker and logged in CyanBridge
- **smart-glasses camera/protocol failure** → remains in CyanBridge diagnostics
- **model/memory pipeline failure** → remains in CyanBridge diagnostics
