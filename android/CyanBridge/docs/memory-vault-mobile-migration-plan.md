# Memory Vault Mobile Migration Plan

## Current Memory Architecture (Discovered)

The app had a mixed local-memory design before this migration:

- File-based memory under `files/local_agent_memory/` via `LocalAgentMemoryStore`:
  - `USER_FACTS.md`
  - `AGENT_PERSONA.md`
  - `daily_facts/*.md`
  - `daily_facts_confirmed/*.md`
  - `daily_summaries/*.md`
  - `user_facts_candidates/*.md`
  - `screen_captures/*.jsonl`
- Room FTS index for OCR chunks via:
  - `memory_chunks`
  - `memory_chunks_fts`
  - `MemoryChunkDao`, `LocalAgentMemoryRoomIndex`
- Retrieval entry points used by agent/chat:
  - `LocalAgentMemorySearch.buildRelevantMemoryBlock(...)`
  - `LocalAgentContextBuilder.buildSystemMessage(...)`
  - `MainActivity.buildCompactMemoryAwareSystemPrompt(...)`
- Ingestion paths:
  - passive screen text from Tasker AutoDiary (`AutoDiaryCaptureCoordinator`)
  - chat fact extraction (`ChatMemoryAutoUpdater`)
  - daily summary generation (`DailySummaryGenerator`)

## What Is Preserved

- Existing memory file paths and helper methods in `LocalAgentMemoryStore` remain.
- Existing memory search API shape remains (`LocalAgentMemorySearch`).
- Existing daily facts / review / summary flows and UI routes remain.
- Existing OCR Room FTS table stays in place.

## What Is Wrapped/Upgraded

- Added a vault layer and policy layer around existing storage/search:
  - `MemoryVaultService`
  - `MemoryPolicyService`
  - `MemoryModeManager`
  - `MemorySearchOrchestrator`
  - `MemorySyncPreparationService`
  - `MemoryMigrationService`
- `LocalAgentMemoryStore` now writes/reads through vault when available while keeping compatibility APIs.
- `LocalAgentMemorySearch` now delegates to privacy-aware orchestration.
- `LocalAgentMemoryRoomIndex` now attaches policy metadata and filters by policy/mode.

## Client-Side Vault Design

- Key hierarchy:
  - Android Keystore device key (`DeviceKeyManager`) for local key protection.
  - Vault master key (`VaultKeyManager`) wrapped by device key.
  - Optional passphrase wrapper for the master key.
  - Per-memory-item content key wrapped by master key.
- Content encryption:
  - AES-GCM authenticated encryption (`VaultCrypto`), versioned from v1.
- Lock lifecycle:
  - lock/unlock state via `VaultLockStateManager`.
  - passphrase set/clear/unlock flows supported.

## Privacy Modes in Mobile-Only World

- `Private Local`: fully available; local retrieval/indexing only.
- `Encrypted Sync`: local encrypted payload and sync queue prepared; backend intentionally not implemented.
- `Fast Cloud Memory`: mode exists in app, but marked unavailable until backend exists.
- `Confidential Cloud Beta`: mode exists in app, but marked unavailable until backend exists.

## Implemented Now vs Future Contracts

Implemented now:

- Vault schema, encryption, lock state, migration, local policy metadata, local search routing.
- Encrypted sync preparation queue and payload manifest generation on device.
- Settings UI controls for mode, per-category policy, OCR retention, lock/unlock, vault reset.

Future backend contract only:

- Actual encrypted upload/sync API calls.
- Actual cloud memory search execution.
- Device enrollment and key exchange backend.
