# CyanBridge AutoDiary ↔ Tasker contract

AutoDiary follows the same architectural boundary as the Tasker-backed Local Agent, but it uses Tasker only as an Android UI observer.

## Responsibility boundary

**CyanBridge owns**

- AutoDiary enabled state
- capture interval / scheduling
- device-ready and Memory Vault checks
- app blacklist and overlay exclusions
- capture normalization and deduplication policy
- screen-memory persistence
- Room / FTS indexing
- derived facts, summaries, embeddings and review UI
- diagnostics and feature state

**Tasker + AutoInput own**

- querying the current Android UI when CyanBridge requests a sample
- returning the current package, visible text and best-effort node metadata

Tasker does not schedule AutoDiary, decide whether a package is allowed, store memory, or apply privacy policy.

## Request

Action:

`com.fersaiyan.cyanbridge.TASKER_AUTO_DIARY_OBSERVE`

CyanBridge sends the same correlation extras used by the Local Agent Tasker bridge:

- `contract_version`
- `request_id`
- `callback_token`
- `payload`

The payload currently identifies the request source as `auto_diary`.

## Response

Tasker replies to:

`com.fersaiyan.cyanbridge.TASKER_AGENT_RESPONSE`

using the compact `response` JSON envelope:

```json
{
  "contract_version": 1,
  "request_id": "exact incoming request id",
  "callback_token": "exact incoming callback token",
  "success": true,
  "payload": "{...observation JSON...}",
  "error": null
}
```

Observation payload:

```json
{
  "created_at_ms": 1770000000000,
  "package_name": "com.example.app",
  "text_summary": "Visible text...",
  "nodes": []
}
```

`CyanBridge_AutoDiary_Tasker.XML` currently returns up to 400 unique visible-text entries (bounded to 25,000 characters) plus a bounded set of AutoInput node IDs/coordinates where available.

## Existing-user migration

The previous AutoDiary implementation shared `LocalAgentPrefs.auto_capture_enabled` with `LocalAgentAccessibilityService`. The Tasker migration introduces a separate `tasker_auto_diary_enabled` state.

When an already-enabled AutoDiary instance starts on the new branch, CyanBridge:

1. migrates the old enabled state to the Tasker-backed state;
2. clears the legacy Accessibility auto-capture bit;
3. keeps the configured interval, blacklist and memory settings;
4. starts the CyanBridge AutoDiary scheduler;
5. requests observations through Tasker.

This prevents duplicate diary entries when the old CyanBridge Accessibility service is still enabled for Local Agent migration/debug comparison.

## Capture flow

```text
AutoDiaryService timer
        |
        v
AutoDiaryCaptureCoordinator
        |
        | correlated observation request
        v
Tasker + AutoInput
        |
        | package/text/nodes
        v
AutoDiaryCaptureCoordinator
        |
        +-- device/vault check
        +-- blacklist/overlay check
        +-- normalize text
        +-- LocalAgentMemoryStore
        +-- LocalAgentMemoryRoomIndex
```

## On-device validation

1. Import `CyanBridge_AutoDiary_Tasker.XML` into Tasker.
2. Confirm Tasker shows `CyanBridge AutoDiary Observe`.
3. Ensure AutoInput Accessibility access is enabled for AutoInput, not CyanBridge.
4. Disable CyanBridge Accessibility access.
5. Enable AutoDiary in CyanBridge.
6. Visit a benign app and wait for the configured capture interval.
7. Confirm a new screen capture appears in CyanBridge with the correct package/text.
8. Blacklist that app and confirm subsequent observations are rejected in CyanBridge rather than Tasker.
9. Lock the Memory Vault and confirm captures are skipped.
10. Re-enable the vault and confirm capture resumes.
11. Confirm daily summaries/facts still consume the same stored screen-memory pipeline.

The first real-device import should also verify the AutoInput UI Query action does not show `Configuration Required`; if it does, open/re-save that action in Tasker and export its XML so the checked-in profile can be made device/version exact.
