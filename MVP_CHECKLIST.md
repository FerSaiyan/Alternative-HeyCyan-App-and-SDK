# MVP_CHECKLIST (master)

This file is the living checklist for shipping the **Ninja Concepts Glasses App MVP**.

- Source of truth for scope and acceptance gates: **`AGENTS.md`**
- Each chapter should be advanced by a PR with:
  - what changed + screenshots/logs
  - test results (unit + integration)
  - manual sign-off script evidence (when applicable)

---

## How to use this checklist

For each chapter:
1. Link the PR(s) / commit(s)
2. Record evidence (screenshots, logcat excerpt, test command output)
3. Mark the chapter complete only when its **Acceptance criteria** and **Tests** pass (per `AGENTS.md`).

Suggested fields to fill:
- **PR/Commit:**
- **Evidence:**
- **Notes / follow-ups:**

---

## MVP chapters (from `AGENTS.md`)

### Chapter 1 — Branding + UI foundation
- [ ] PR/Commit:
- [ ] Evidence:
  - [ ] Screenshots (tabs + chat list + chat detail)
  - [ ] UI smoke test: create chat → appears in list → open → send dummy message
- [ ] Notes:

### Chapter 2 — Storage layer (local-first)
- [ ] PR/Commit:
- [ ] Evidence:
  - [ ] Repo CRUD test output
  - [ ] Integration: cold start → load DB → display chat list & notes list
- [ ] Notes:

### Chapter 3 — Device scanning + pairing (classification + override)
- [ ] PR/Commit:
- [ ] Evidence:
  - [ ] Unit: name/service heuristics → expected class
  - [ ] Integration: fake BT list → override → profile stored
  - [ ] Manual: permissions prompt works; scanning shows devices; selecting updates UI
- [ ] Notes:

### Chapter 4 — Glasses Manager baseline (capability gating)
- [ ] PR/Commit:
- [ ] Evidence:
  - [ ] Unit: capability gating tests
  - [ ] UI: switching device class changes visible actions
- [ ] Notes:

### Chapter 5 — Audio capture pipeline (meeting mode)
- [ ] PR/Commit:
- [ ] Evidence:
  - [ ] Unit: timer auto-stop tests
  - [ ] Integration: start → wait → stop → metadata saved
  - [ ] Manual: record 2 min; lock/unlock during capture; file saved
- [ ] Notes:

### Chapter 6 — Transcription proof-of-concept
- [ ] PR/Commit:
- [ ] Evidence:
  - [ ] Unit: chunker tests
  - [ ] Integration: fake provider returns text → saved to DB
  - [ ] Manual: record short meeting → transcribe → view transcript (if enabled)
- [ ] Notes:

### Chapter 7 — Summarization + notes formatting
- [ ] PR/Commit:
- [ ] Evidence:
  - [ ] Unit: formatting tests (stable headings)
  - [ ] Integration: transcript → summary → DB → note detail renders
  - [ ] Manual: export/share sheet contains expected content
- [ ] Notes:

### Chapter 8 — Privacy settings (MVP)
- [ ] PR/Commit:
- [ ] Evidence:
  - [ ] Unit: redaction tests on fixed samples
  - [ ] Integration: toggles affect export output
  - [ ] Manual: clear data → returns to empty state
- [ ] Notes:

---

## Local Agent (MVP) — Android Accessibility automation

> Goal: replace or complement the **Tasker + AutoInput** dependency with an **in-app “Local Agent”** that can drive assistant UIs (Gemini/ChatGPT/etc.) using Android’s Accessibility APIs.
>
> This section focuses on **setup, operation, debugging, safety, and merge readiness**.

### 1) Enable Accessibility (device setup)
- [ ] User-facing setup doc exists (where to tap, what to expect)
- [ ] On Android 13+:
  - [ ] App is allowed under **“Restricted settings”** (if applicable on device)
- [ ] Accessibility Service enabled:
  - [ ] Settings → Accessibility → Installed apps → **Ninja Concepts Local Agent** (or equivalent) → ON
  - [ ] Service permissions acknowledged (screen content, interactions)
- [ ] Required supporting permissions (as applicable):
  - [ ] **Notifications** allowed (for foreground-service notification)
  - [ ] **Display over other apps** allowed (only if the agent uses overlays)
  - [ ] **Battery optimization disabled** (or an explicit prompt/instructions exist)

### 2) Start / Stop behavior (operator controls)
- [ ] Clear UI entry-point to start the agent
- [ ] Starting the agent:
  - [ ] Shows an explicit **“Agent Active”** indicator
  - [ ] Starts a **foreground service** with a persistent notification
  - [ ] Does *not* auto-record or auto-send content without user action
- [ ] Stopping the agent:
  - [ ] Stop button in-app works reliably
  - [ ] Notification **Stop** action works reliably
  - [ ] Agent halts interactions immediately (no “queued clicks”)
- [ ] Fail-safe:
  - [ ] If target UI cannot be found / timeouts occur → agent stops and surfaces an error
  - [ ] Max-step / max-duration guardrails exist (prevents infinite loops)

### 3) Debugging & observability
- [ ] Logcat tags documented (example command included)
- [ ] Minimal structured logging exists for:
  - [ ] current state (idle/running/waiting-for-ui)
  - [ ] chosen target package (Gemini/ChatGPT/etc.)
  - [ ] key actions (tap/type/back) and their outcomes
  - [ ] timeouts + recovery decisions
- [ ] Repro checklist exists:
  - [ ] device model + Android version
  - [ ] assistant app version
  - [ ] “works once / fails after X” notes
- [ ] Debug artifacts are easy to collect:
  - [ ] `adb logcat` filter snippet available
  - [ ] (Optional) “Export logs” button or copy-to-clipboard

### 4) Safety & privacy (MVP requirements)
- [ ] Transparency:
  - [ ] Clear disclosure that Accessibility can read/act on screen content
  - [ ] Always-visible indicator while agent is active
- [ ] Scope limitation:
  - [ ] Agent only acts on **allowlisted packages** (e.g., Gemini/ChatGPT), not globally
  - [ ] Agent runs only after explicit user initiation (no stealth background automation)
- [ ] Sensitive data handling:
  - [ ] Do not capture/store passwords, OTPs, banking apps, etc.
  - [ ] Avoid acting on `FLAG_SECURE`/secure surfaces; fail closed when uncertain
- [ ] Emergency stop:
  - [ ] User can stop within ≤1 tap from notification
  - [ ] App provides an immediate “disable agent” path

### 5) Merge notes (landing Local Agent MVP on `master`)
- [ ] Build / packaging
  - [ ] Manifest entries reviewed: exported components are minimized (`android:exported="false"` where possible)
  - [ ] Foreground-service type(s) are correct for Android 14+
  - [ ] Proguard/R8 rules added if reflection is used
- [ ] Compatibility
  - [ ] Tested on at least 2 Android versions (e.g., 13 + 15) and 2 OEMs (Pixel/Samsung if possible)
  - [ ] Accessibility enable/disable flows verified on both
- [ ] Fallback behavior
  - [ ] If Local Agent is unavailable, a clear fallback exists (e.g., Tasker mode) and is documented
- [ ] Docs updated
  - [ ] README mentions Local Agent vs Tasker (what’s required, what’s optional)
  - [ ] Known limitations tracked (UI changes in assistant apps, OEM restrictions)

---

## Manual sign-off scripts

Use the scripts defined in `AGENTS.md` for MVP sign-off:
- Script A — Generic audio glasses flow
- Script B — HeyCyan-class flow
- Script C — Timer reliability

Add a dedicated Local Agent script (recommended):
- [ ] **Script D — Local Agent sanity**
  1. Enable Accessibility service
  2. Start Local Agent (confirm indicator + notification)
  3. Run a single assistant interaction (text-only) end-to-end
  4. Trigger an intentional failure (wrong target app) → verify agent stops with a readable error
  5. Stop via notification → verify immediate halt
