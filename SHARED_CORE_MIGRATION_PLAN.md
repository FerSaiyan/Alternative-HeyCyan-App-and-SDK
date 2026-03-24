# Shared Core Migration Plan (CyanBridge Public + Ninja Private)

This plan defines how to share Android code between:

- `CyanBridge` (public/open-source)
- `Ninja Concepts Manager` (private/proprietary)

without leaking private features while keeping day-to-day development fast.

## Target architecture

Use an open-core split with three repositories in the same parent directory:

1. `cyanbridge` (public app repo)
2. `ninja-manager` (private app repo)
3. `heycyan-android-core` (shared code repo; public only if all content is safe)

In each app repo:

- Use Gradle composite build (`includeBuild`) for local side-by-side editing.
- Use versioned artifacts in CI/release for reproducible builds.

## Guardrails (must hold)

- Never move Ninja-only features/secrets/prompts into `heycyan-android-core`.
- Keep app branding, private providers, private automation flows, and proprietary UX in `ninja-manager`.
- Treat `heycyan-android-core` as reusable API surface, not app-specific implementation.
- Every extraction PR must include tests proving behavior parity in the source app.

## Suggested workspace layout

```text
~/heycyan-sdk/
  cyanbridge/
  ninja-manager/
  heycyan-android-core/
```

## Migration phases

Use this as a checklist. Mark items complete with `[x]` and add links/evidence.

### Phase 0 - Prepare and inventory

- [x] Create migration branch in `cyanbridge` and `ninja-manager`.
- [x] Document code ownership boundaries (public-safe vs private-only). See `SHARED_CORE_BOUNDARIES.md`.
- [x] Inventory duplicate packages/classes across both apps. See `SHARED_CORE_INVENTORY.md`.
- [x] Tag each candidate: `core`, `app-specific`, or `needs refactor before extract`. See `SHARED_CORE_BOUNDARIES.md`.
- [x] Capture baseline build/test results for both apps. See `SHARED_CORE_PHASE0_LOG.md`.

Deliverables:

- Class/package inventory doc with disposition labels: `SHARED_CORE_INVENTORY.md` and `SHARED_CORE_BOUNDARIES.md`.
- Baseline test logs for both apps: `SHARED_CORE_PHASE0_LOG.md`.

### Phase 1 - Bootstrap shared repository

- [x] Create `heycyan-android-core` repo.
- [x] Create initial shared module: `core-connectivity` (first extraction batch).
- [x] Add strict package namespace policy (for example `com.heycyan.core.*`).
- [x] Add remaining planned modules:
  - `core-ble`
  - `core-audio`
  - `core-transcription-api`
  - `core-summarization-api`
  - `core-data`
  - `core-utils`
- [x] Add CI skeleton for core repo (`assemble`, unit tests).
- [x] Add CODEOWNERS / review rules for public-safety checks.

Deliverables:

- Initial shared repo + first module scaffold complete. See `SHARED_CORE_PHASE1_LOG.md`.
- CI skeleton in shared repo builds all current core modules.

### Phase 2 - Extract low-risk shared code first

- [x] Move pure helpers/models/interfaces first (no Android UI dependencies).
- [x] Add compatibility adapters in source apps to preserve current app behavior.
- [x] Replace direct app-internal imports with core module imports.
- [x] Keep package moves incremental to reduce merge conflicts.
- [x] Add regression tests around extracted behavior.

Recommended extraction order:

1. Data models + pure formatting helpers
2. Transcription/summarization interfaces
3. Audio capture helpers (non-service logic)
4. BLE/protocol abstractions (implementation only if truly shared)

Initial prepared extraction queue:

- `SHARED_CORE_BATCH1_CONNECTIVITY.md`

Deliverables:

- First released core version tag (for example `v0.1.0`).

### Phase 3 - Integrate shared core in CyanBridge

- [x] Add local composite-build integration in `cyanbridge/settings.gradle*`.
- [x] Add artifact-based dependency fallback for CI/release (`mavenLocal` + optional env-configured Maven repo).
- [x] Update imports/module dependencies to consume core modules (batch-1 subset).
- [x] Verify app still builds and tests pass.
- [x] Confirm no behavior regressions in key user flows.

Deliverables:

- CyanBridge building against `heycyan-android-core` locally and in CI.

### Phase 4 - Integrate shared core in Ninja Manager

- [x] Add local composite-build integration in `ninja-manager/settings.gradle*`.
- [x] Port first shared dependency to core modules (`core-connectivity`, batch-1 subset).
- [x] Add artifact-based dependency fallback for CI/release (`mavenLocal` + optional env-configured Maven repo).
- [x] Keep private features in app-private modules only (enforced by extraction boundary policy in `SHARED_CORE_BOUNDARIES.md`).
- [x] Add static checks to block forbidden imports into public core.
- [x] Validate private feature parity after integration (unit tests + debug assemble pass).

Deliverables:

- Ninja building against same core version with private deltas preserved.

### Phase 5 - Release/version discipline

- [x] Adopt semantic versioning baseline in `heycyan-android-core` (first stable tag/version: `0.1.0`).
- [x] Add release notes template/process (`RELEASE_NOTES_TEMPLATE.md` + `RELEASE_RUNBOOK.md`).
- [x] Publish release notes for `0.1.0` (`releases/0.1.0.md`).
- [x] Pin core version in each app (`heycyanCoreConnectivityVersion` in app `gradle.properties`).
- [x] Add upgrade playbook (`how to bump from vX to vY`). See `SHARED_CORE_UPGRADE_PLAYBOOK.md`.
- [x] Add deprecation policy for shared interfaces (`DEPRECATION_POLICY.md`).

Deliverables:

- Stable versioned dependency contract for both apps.

### Phase 6 - Operational hardening

- [x] Add CI matrix job to build both apps against latest core (`.github/workflows/shared-core-consumer-matrix.yml`, workflow_dispatch).
- [x] Add API compatibility checks for core public interfaces (`tools/check_binary_compat.py`).
- [x] Add smoke test scripts for critical shared flows (`tools/shared_core_smoke_check.sh`).
- [x] Add incident rollback procedure for bad core releases (`RELEASE_RUNBOOK.md`).
- [x] Document long-term ownership (who approves core changes) (`OWNERSHIP.md` + `CODEOWNERS`).

Deliverables:

- Repeatable, low-risk shared development workflow.

## Local development pattern

Use local composite builds when editing shared code and an app in the same session.

Example workflow:

1. Edit in `heycyan-android-core`.
2. Build/test app via local `includeBuild`.
3. Merge and tag core.
4. Bump app to released core version.

Do not rely on local relative paths in CI release jobs.

## Security and IP checklist

- [x] No private API keys/prompts/config in shared or public repos.
- [x] No Ninja-only classes referenced by shared modules.
- [x] Public PR template includes "private feature leakage" checkbox.
- [x] Weekly audit: verify extracted modules remain public-safe.

## Rollback plan

If a core migration step regresses either app:

1. Pin affected app back to last known-good core tag.
2. Re-enable temporary local implementation adapter in app repo.
3. Fix in core behind tests.
4. Re-release core and re-upgrade app.

## Definition of done

Migration is complete when:

- Both apps build/test in CI against versioned `heycyan-android-core` artifacts.
- Local composite build works for fast shared edits.
- No private Ninja logic exists in public repos.
- Shared changes are implemented once in core and consumed by both apps.
