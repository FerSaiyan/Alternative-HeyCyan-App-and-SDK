# Memory Vault Mobile Architecture

## Layered Design

This implementation keeps the legacy memory APIs and adds policy/vault orchestration around them.

1. **Current memory layer (preserved)**
   - `LocalAgentMemoryStore`
   - `DailyFactsStorage`
   - `CandidateUserFactsStorage`
   - `UserFactsStorage`
   - `LocalAgentMemoryRoomIndex` + `MemoryChunkDao`
   - `LocalAgentMemorySearch` (same entry point)

2. **New policy and vault layer**
   - `MemoryModeManager` (privacy mode + category toggles)
   - `MemoryPolicyService` (classification + eligibility + inheritance)
   - `MemoryVaultService` (encrypted payload storage)
   - `VaultKeyManager` / `DeviceKeyManager` / `VaultLockStateManager`
   - `MemorySearchOrchestrator` (mode-aware retrieval routing)
   - `MemorySyncPreparationService` (local queue/contracts)
   - `MemoryMigrationService` (legacy-to-vault migration)

## Schema Additions

Room v7 adds:

- `vault_items`
- `vault_item_keys`
- `memory_policy_metadata`
- `sync_preparation_queue`
- `sync_payload_manifest`
- `local_embedding_store`
- `local_search_index_state`
- `memory_mode_preferences`
- `vault_lock_state`
- `migration_state`

## Search Adaptation (No Entry-Point Break)

- Existing callers still invoke `LocalAgentMemorySearch.buildRelevantMemoryBlock(...)`.
- Internally it now routes through `MemorySearchOrchestrator`.
- Orchestrator keeps local retrieval semantics but applies:
  - vault lock checks
  - policy eligibility filtering by selected privacy mode
  - OCR chunk filtering for cloud-capable modes

## Ingestion Adaptation

- Passive OCR capture:
  - ingested from Tasker AutoDiary (`AutoDiaryCaptureCoordinator`)
  - now gated by privacy settings and vault lock state
  - policy metadata defaults to local-only
- File-based memory writes:
  - same `LocalAgentMemoryStore.writeText(...)` call sites
  - now classified and stored through vault path
  - summary/ocr data are no longer mirrored as plaintext by default

## Migration Behavior

- `MemoryMigrationService` scans legacy `local_agent_memory` files.
- For each file, creates encrypted vault item + policy metadata.
- Builds policy sidecar metadata for legacy OCR chunks in `memory_chunks`.
- Persists resumable migration progress in `migration_state`.
