# Tasker + AutoInput setup for CyanBridge

CyanBridge uses Tasker as the Android/external-app executor and AutoInput for UI observation and interaction. CyanBridge remains responsible for model decisions, safety policy, memory/facts/RAG and glasses logic.

## Install

1. Install **Tasker** from the Play Store account that owns the app.
2. Install **AutoInput**.
3. If AutoInput actions silently do nothing even though AutoInput is installed, install/open the official **AutoApps** app and restore the existing AutoInput entitlement through the normal Play/AutoApps flow.
4. Open Tasker at least once and allow external access when requested by the CyanBridge profiles.

## Accessibility permissions

Two separate services are required for the UI-automation integrations:

- Tasker Accessibility Access
- AutoInput Accessibility Access

Tasker's own Accessibility Access must be granted through Tasker's disclosure/permission flow. HIL testing showed that writing Android's secure accessibility setting directly is reverted and does not set Tasker's internal consent state.

AutoInput Accessibility is required for screen observation, clicks and typing.

## Import CyanBridge Tasker projects

Open **CyanBridge → Plugins** for the current downloads, or open **Settings → AI → Tasker integrations** for diagnostics and the Gemini/ChatGPT repair flow.

Current projects include:

- Gemini / ChatGPT assistant automation
- Local Agent
- AutoDiary
- Visual Diary

After importing a project, accept Tasker's replace/import prompt and ensure its profiles are enabled.

## Gemini / ChatGPT version verification

The assistant projects expose a version handshake:

- Gemini requires `gemini-v3`
- ChatGPT requires `chatgpt-v1`

Open **Tasker integrations**, select the desired assistant as Android's default assistant, then press **Verify profile**. CyanBridge stores the verified version separately for Gemini and ChatGPT so both profiles can coexist.

### Migrating an old `Tasker_AI.xml`

Older CyanBridge releases shipped assistant profiles that did not implement the current handshake. If Tasker is installed and configured but CyanBridge says the profile is unverified/outdated:

1. Open **Tasker integrations**.
2. Press **Import / update profile** for the selected assistant.
3. Replace the old Tasker project when prompted and enable the imported profiles.
4. Return to CyanBridge and press **Verify profile**.

Do not troubleshoot this as a Tasker package-name issue. CyanBridge intentionally targets the standard Tasker package `net.dinglisch.android.taskerm`.

## Diagnostics meanings

- **Tasker missing** — install Tasker from the licensed Play account.
- **Tasker Accessibility missing** — grant it through Tasker's own permission flow.
- **AutoInput missing** — install AutoInput; restore entitlement through AutoApps if necessary.
- **AutoInput Accessibility missing** — enable AutoInput in Android Accessibility settings.
- **Profile needs setup** — import/update the current project and verify again.
- **Profile outdated** — the reported version differs from CyanBridge's required version.
- **Wrong profile** — another assistant answered/was last verified; verify the intended assistant profile.
- **Local Agent / AutoDiary / Visual Diary says profile not versioned** — their projects do not yet expose a version handshake. The environment status is real, but CyanBridge intentionally does not claim a project version it cannot verify. Re-import the current project from Plugins if execution/scheduling fails.

## When asking for support

Open **Settings → AI → Tasker integrations** first and include the failed check, Tasker/AutoInput versions, selected assistant and reported profile version. This is more useful than a generic "Tasker is configured" report.
