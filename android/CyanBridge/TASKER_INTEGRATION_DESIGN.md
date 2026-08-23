# Tasker Integration Center

## Goal

Provide one Settings entry point for Tasker-powered features instead of separate
Gemini, ChatGPT, AutoDiary and local-agent diagnostics.

## Settings UI

Place beside **Configure Local Models**:

- Tasker Integrations
- Installed apps status
- Accessibility status
- Profile versions
- Last handshake/error
- Diagnostics action

## Architecture

`TaskerIntegrationStatus` is the shared state object consumed by integrations.

Future steps:

1. Add `TaskerIntegrationManager` service.
2. Migrate Gemini/ChatGPT checks to the shared manager.
3. Add profile version negotiation.
4. Add migration detection for older Tasker XML profiles.
5. Connect Settings UI card.

## Error handling

Errors should explain the missing requirement:

- Tasker missing
- AutoInput missing
- Accessibility disabled
- Profile outdated
- Integration handshake failed

Avoid generic "integration unavailable" messages.
