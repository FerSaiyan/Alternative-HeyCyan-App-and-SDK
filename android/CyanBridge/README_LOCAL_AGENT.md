# Local Agent Context Injection

This document describes the timing and mechanisms for context generation and injection for the local AI assistant.

## Normal Chats

When a user sends a message in a standard chat thread (`ChatThreadActivity`):

1. **Daily Summary Stale Check**: `maybeGenerateDailySummaryIfStale()` runs to ensure the daily summary is relatively fresh. If screen captures exist and the summary is missing or older than the latest captures, it regenerates the summary in the background.
2. **Relevant Memory Search**: `LocalAgentMemorySearch.buildRelevantMemoryBlock()` searches FTS5 chunks for facts relevant to the user's latest prompt.
3. **Context Injection**: `LocalAgentContextBuilder.buildSystemMessage()` is called on *every send*. It concatenates the Agent Persona, User Facts, confirmed daily facts, and the daily summary. To prevent unbounded prompt sizes, it aggressively truncates individual files and enforces an overall character cap (e.g., 14,000 chars).
4. **Proactive Memory Extraction**: After the assistant replies, `ChatMemoryAutoUpdater.extractAndStore()` runs in the background. It analyzes the user's message and the assistant's reply to extract *Candidate User Facts* and *Draft Daily Facts*, which are saved for later review.

## Daily Facts Review

When the user initiates a "Daily Facts Review" (via the Settings reminder or manually):

1. **Protocol Message**: The chat thread bypasses the normal context builder and instead uses `DailyFactsReviewProtocol.buildSystemMessage()`.
2. **State Injection**: This system message explicitly provides the current draft facts, confirmed facts, user facts, and candidate user facts.
3. **Interactive Review**: As the user chats, the assistant replies with JSON formatted updates (`DailyFactsReviewProtocol.parseUpdate()`), which the UI parses to automatically save, confirm, or reject facts and user preferences.

## Debugging

To verify what text is actually being injected into the prompt:
- Open **Native Plugins** > **Local Agent**.
- Tap **View last injected context (debug)**.
- This displays a detailed breakdown of which files contributed to the last normal chat's system prompt, their file sizes, and how many characters were actually included after truncation.

## Phone-Control Extensions

### Screenshot planning

- Local Agent tasks, direct screen reading, and screenshot planning require an interactive, unlocked phone. An active task stops before additional actions if the screen turns off or the device becomes locked.
- Screenshot planning is off by default and requires Android 11+ plus the enabled Accessibility service.
- CyanBridge first verifies the exact active package against the Local Agent privacy blacklist. If the screen changes during capture or vision is unavailable, it continues with structured text-only planning.
- Screenshot capture and remote screenshot upload are separate settings. A screenshot is never sent to a cloud, relay, or remote OpenAI-compatible planner unless **Allow screenshots to be sent to remote planners** is explicitly enabled.
- Planning screenshots are resized, held in the app cache only for the current inference call, and deleted immediately afterward. They are not written to Local Agent history or memory.

### Optional Shizuku fallback

- Shizuku is off by default and requires an installed/running Shizuku service plus user-granted Shizuku permission.
- It runs only after the ordinary Accessibility primitive failed and only for fixed IME submit, swipe, Back, and Home operations that have already passed the normal risk and approval policy.
- The privileged user service exposes no generic shell-command API and never executes a model-produced command string.
