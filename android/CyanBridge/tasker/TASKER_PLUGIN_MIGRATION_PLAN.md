# CyanBridge Tasker migration plan

The current boundary is:

> **CyanBridge owns product logic, state, model/action policy, memory, facts/RAG and smart-glasses integrations. Tasker owns Android UI observation/execution plus the lightweight periodic triggers that would otherwise require long-running CyanBridge scheduler services.**

Package exclusions are a source-privacy exception: Tasker may suppress an observation before screen contents are returned to CyanBridge. Tasker does not independently reclassify Local Agent action risk.

---

## 1. Local Agent — implemented

### CyanBridge owns

- goal/task state
- model inference and action selection
- risk classification / approvals
- recovery/repeat logic
- task history and result logging

### Tasker + AutoInput own

- Android screen observation
- `%CB_LocalAgentBlocked` source-privacy blocklist
- UI primitives and global navigation
- every model-selected Android device effect
- concrete execution results

The old CyanBridge installed-app blacklist is no longer a second policy authority. CyanBridge does not enumerate installed packages for this feature.

Files:

- `CyanBridge_LocalAgent_Tasker.XML`
- `LOCAL_AGENT_TASKER_CONTRACT.md`
- `TaskerAgentContract.kt`
- `TaskerAgentBridge.kt`

`press_enter` and arbitrary `swipe` remain explicit adapter errors pending real-device AutoInput validation.

---

## 2. AutoDiary — Tasker scheduled/push path implemented

### CyanBridge owns

- enabled state
- Memory Mode / Vault checks
- observation validation/normalization
- encrypted memory persistence and retention
- Room / FTS / embeddings / RAG
- candidate and confirmed daily facts
- daily summaries/review UI

### Tasker + AutoInput own

- periodic Time profile, default 10 minutes
- `%CB_AutoDiaryExcluded` passive-capture exclusions
- foreground package detection via AutoInput query
- screen observation
- sending allowed observations to CyanBridge

The normal flow is:

```text
Tasker Time profile
  -> AutoInput UI Query
  -> exclude package if configured
  -> TASKER_AUTO_DIARY_CAPTURE
  -> AutoDiaryTaskerCaptureReceiver
  -> AutoDiaryCaptureCoordinator
  -> LocalAgentMemoryStore + LocalAgentMemoryRoomIndex
  -> Memory Vault / FTS / RAG / facts / summaries
```

CyanBridge no longer maintains a long-running AutoDiary `dataSync` scheduler service.

Files:

- `CyanBridge_AutoDiary_Tasker.XML`
- `AUTO_DIARY_TASKER_CONTRACT.md`
- `AutoDiaryTaskerCaptureReceiver.kt`
- `AutoDiaryCaptureCoordinator.kt`
- `AutoDiaryService.kt` (feature-state controller, no Android Service)

---

## 3. Visual Diary / Vision Diary — Tasker schedules; CyanBridge captures

Tasker owns only the periodic trigger. Glasses I/O and vision processing stay in CyanBridge.

### Tasker owns

- Visual Diary Time profile, default 15 minutes
- enable/disable mirror (`%CB_VisualDiaryEnabled`)
- sending `TASKER_VISUAL_DIARY_CAPTURE`

### CyanBridge owns

- selected-device capability checks
- Meta DAT / HeyCyan/native glasses communication
- photo acquisition and persistence
- custom visual prompt
- vision inference / visual-note generation
- errors and diagnostics

`VisualDiaryTaskerCaptureReceiver` turns Tasker's trigger into a one-shot `VisualDiaryCaptureWorker`. `VisualDiaryCaptureCoordinator` remains the only glasses/vision implementation. `VisualDiaryService` is retained only as a short-lived manual "Capture now" foreground host and uses `connectedDevice`, not `dataSync`.

Files:

- `CyanBridge_VisualDiary_Tasker.XML`
- `VisualDiaryTaskerCaptureReceiver.kt`
- `VisualDiaryCaptureWorker.kt`
- `VisualDiaryCaptureCoordinator.kt`
- one-shot `VisualDiaryService.kt`

---

## 4. Permission de-escalation implemented

The branch removes from the modern CyanBridge manifest:

- `QUERY_ALL_PACKAGES`
- `MANAGE_EXTERNAL_STORAGE`

Storage permissions are restricted to legacy Android only:

- `READ_EXTERNAL_STORAGE` with `maxSdkVersion=28`
- `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28`

Modern Android keeps using existing scoped mechanisms:

- encrypted/private memory: Room + `filesDir`
- app-private external files: `getExternalFilesDir()`
- user-visible synced images/videos: existing MediaStore `DCIM/CyanBridge` path
- backup/import/export: existing user-selected URI / Storage Access Framework style flow

Only Tasker and AutoInput are declared in manifest `<queries>` for targeted package visibility.

The package-enumerating `AppBlacklistActivity` and the obsolete `requestAllPermission()` helper were removed. Onboarding no longer asks for All Files Access.

These changes intentionally do **not** modify:

- `SyncedMediaFolder` / `SyncedMediaQuery`
- Meta image saving into `DCIM/CyanBridge`
- `LocalAgentMemoryStore`
- `MemoryVaultService`
- candidate/confirmed daily-facts processing
- daily summaries
- memory embeddings / RAG retrieval

---

## Validation order

1. Import all three Tasker XML files.
2. Validate Local Agent observation/execution and `%CB_LocalAgentBlocked`.
3. Validate AutoDiary enable/disable mirroring and 10-minute default schedule.
4. Validate `%CB_AutoDiaryExcluded` prevents excluded screen contents from reaching CyanBridge.
5. Verify allowed captures appear in screen memory and remain retrievable through RAG.
6. Verify candidate facts, confirmed facts and summaries still consume those captures.
7. Verify synced photos/videos still appear in `DCIM/CyanBridge` and the CyanBridge gallery.
8. Validate Visual Diary 15-minute Tasker trigger on Meta and non-Meta supported glasses.
9. Validate manual Visual Diary "Capture now" separately.
10. Build/test the branch and remove any remaining dead legacy Accessibility-specific diary capture code only after on-device parity is confirmed.

Failure domains remain distinct:

- CyanBridge action policy/state → CyanBridge logs
- Tasker/AutoInput observation/device automation → Tasker result
- glasses protocol/camera → CyanBridge diagnostics
- memory/facts/RAG/model pipeline → CyanBridge diagnostics
