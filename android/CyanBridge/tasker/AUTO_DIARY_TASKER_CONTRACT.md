# CyanBridge AutoDiary ↔ Tasker contract

AutoDiary uses Tasker + AutoInput for Android scheduling, foreground-package exclusion and screen observation. CyanBridge receives only allowed observations and keeps ownership of the memory system.

## Responsibility boundary

**CyanBridge owns**

- AutoDiary enabled/disabled state
- Memory Mode and Vault lock state
- device-ready checks
- observation validation/normalization
- encrypted screen-memory persistence
- Room / FTS indexing
- embeddings / RAG retrieval
- candidate and confirmed daily facts
- daily summaries and review UI
- retention and memory policy

**Tasker + AutoInput own**

- periodic scheduling
- user-selected passive-capture exclusions
- current foreground package detection as part of AutoInput UI Query
- visible Android UI observation
- dropping excluded observations before their screen text is sent to CyanBridge

Tasker does not decide memory retention, fact extraction, RAG eligibility, model behavior or Local Agent action approval.

## State sync

CyanBridge remains authoritative for whether AutoDiary is enabled and sends explicit broadcasts to Tasker:

- `com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_ENABLE`
- `com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_DISABLE`

The imported profile mirrors this into `%CB_AutoDiaryEnabled`.

## Periodicity

`CyanBridge_AutoDiary_Tasker.prj.xml` contains `CyanBridge AutoDiary Periodic Capture`, configured for every **10 minutes** by default. Edit that Time context in Tasker to change periodicity.

CyanBridge no longer runs a long-lived AutoDiary `dataSync` foreground service just to wait between captures.

## Passive-capture exclusions

Set Tasker global variable:

`%CB_AutoDiaryExcluded`

Package names may be separated by commas, semicolons, spaces or new lines. Example:

```text
com.example.bank, com.example.passwordmanager
```

Tasker obtains the foreground package from AutoInput UI Query, checks this list, and exits before broadcasting the observation when the package is excluded. Fixed launcher/System UI exclusions remain as defense in depth.

CyanBridge does **not** enumerate installed packages and therefore does not need `QUERY_ALL_PACKAGES` for this feature.

## Periodic push into CyanBridge

Allowed observations are sent to:

`com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_CAPTURE`

with extra `payload` containing:

```json
{
  "created_at_ms": 1770000000000,
  "package_name": "com.example.app",
  "text_summary": "Visible text...",
  "nodes": []
}
```

`AutoDiaryTaskerCaptureReceiver` passes that payload to `AutoDiaryCaptureCoordinator.ingestObservationJson()`.

The coordinator then feeds the **existing** memory pipeline:

```text
Tasker observation
      |
      v
AutoDiaryCaptureCoordinator
      |
      +-- feature/device/vault checks
      +-- normalize text
      +-- LocalAgentMemoryStore.appendScreenCapture
      +-- LocalAgentMemoryRoomIndex.indexScreenCaptureAsync
      |
      v
Memory Vault / Room / FTS / embeddings / RAG
      |
      +-- candidate daily facts
      +-- confirmed daily facts
      +-- daily summaries
```

No separate diary store was introduced.

## Debug pull path

The old correlated request-response path remains only for parity/debug testing:

- request: `com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_OBSERVE`
- response: `com.fersaiyan.cyanbridge.TASKER_AGENT_RESPONSE`

Normal periodic operation is Tasker-push.

## Storage / media independence

AutoDiary memory remains in CyanBridge app-private storage and the encrypted Room-backed Memory Vault. The Tasker migration does not change synced photo/video storage or the `DCIM/CyanBridge` MediaStore path.

## On-device validation

1. Import `CyanBridge_AutoDiary_Tasker.prj.xml`.
2. Give AutoInput Accessibility access; CyanBridge Accessibility should not be needed for AutoDiary.
3. Enable AutoDiary in CyanBridge and verify `%CB_AutoDiaryEnabled` becomes `1` in Tasker.
4. Verify the periodic profile produces a screen capture after its Time interval.
5. Put the current app package in `%CB_AutoDiaryExcluded` and verify no new screen text reaches CyanBridge.
6. Remove it and verify capture resumes.
7. Lock the Memory Vault and verify incoming observations are not stored.
8. Verify screen captures are still searchable by RAG.
9. Verify candidate facts, confirmed facts and daily summaries still use the same captures.
10. Disable AutoDiary and verify `%CB_AutoDiaryEnabled` becomes `0`.

The first real-device import should also verify the AutoInput UI Query action does not show `Configuration Required`; if it does, open/re-save that action in Tasker and export its XML so the checked-in profile can be made version exact.
