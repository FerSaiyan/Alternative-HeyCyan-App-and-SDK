# Tasker Integration Center

## Status

Implemented on `tasker-integration-polish`.

The existing Gemini/ChatGPT automation setup Activity is now the single Tasker diagnostics and repair surface. It is reachable from the glasses flow and from **Settings → AI**, where **Tasker integrations** sits directly beside **Configure Local Models**.

## Shared environment model

`TaskerIntegrationManager` and `TaskerEnvironmentInspector` own common checks so individual features do not disagree about readiness:

- Tasker installed + version
- AutoInput installed + version
- Tasker Accessibility Access
- AutoInput Accessibility Access
- per-integration status and remediation

The Tasker Accessibility check is intentional. HIL validation showed `%WIN`-based foreground-app detection requires Tasker's own Accessibility Access, granted through Tasker's disclosure flow.

## Profile negotiation

Gemini and ChatGPT expose a real versioned handshake:

- Gemini: `gemini-v3`
- ChatGPT: `chatgpt-v1`

CyanBridge stores verified versions **per assistant**, so verifying ChatGPT no longer overwrites the known Gemini profile and vice versa. The UI distinguishes:

- verified/current
- missing/unverified
- outdated
- wrong assistant profile
- assistant not selected

Old `Tasker_AI.xml` installs that do not answer the current handshake are presented as a migration/update case rather than a generic failure.

Local Agent, AutoDiary and Visual Diary do not currently expose their own version-response contract. The Integration Center therefore reports their Tasker/AutoInput environment readiness and links users back to Plugins for the current project download, but deliberately does **not** claim a verified project version.

## User-facing repair flow

The Integration Center keeps the existing, proven assistant actions:

1. choose Android default assistant
2. import/update the matching Gemini or ChatGPT Tasker profile
3. verify the profile handshake
4. open Accessibility settings
5. test Tasker voice launch

It adds:

- Tasker/AutoInput version display
- both Accessibility checks
- profile migration notice
- integration cards for Gemini, ChatGPT, Local Agent, AutoDiary and Visual Diary
- shortcut to the Plugins page for current Tasker project downloads

## Architecture boundary

CyanBridge still owns AI/model decisions, policy, memory, facts/RAG, approvals and glasses logic. Tasker/AutoInput own Android/external-app observation and execution. The diagnostics screen reports this boundary; it does not move policy decisions into Tasker.
