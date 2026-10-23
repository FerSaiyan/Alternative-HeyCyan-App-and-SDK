# LensMoo shared dashboard implementation plan

> **For agentic workers:** Use executing-plans for the Android host; shared UI work may run independently against the explicit state/action contract.

**Goal:** Give LensMoo the same actual CyanBridge dashboard, theme and bottom navigation as other glasses, preserving tested transport and voice behavior.

**Architecture:** Keep the LensMoo Android transport host, replace its prototype LinearLayout with CyanBridgeApp and GlassesDashboardScreen. Shared presentation gets a nullable LensMoo state and commands; Android owns credentials, media surface, connection/session and foreground-service lifecycle. This avoids running the HeyCyan native presenter against LensMoo.

**Tech stack:** Existing Kotlin/Compose Multiplatform/Android AppCompatActivity, StateFlow, MediaCodec, LensMoo SPP client.

## Constraints and review focus

- Reuse the existing navigation shell, dashboard cards, themes, and native destination Activities. Do not draw a lookalike navigation bar.
- No camera on connection or navigation; preview is explicit and closes when leaving it.
- Never expose owner ID in shared state, diagnostics, saved UI state, or screenshots; edit in a password field dialog.
- Service connection/battery/status must be observable; a running service is not proof of connected glasses.
- Manual radio work must not overlap foreground-assistant ownership. Closing a screen must not stop the enabled assistant.
- Preserve current local Gemma and remote provider work from commit b28cc48.

## Tasks

- [x] Shared presentation: LensMooUiState, commands, localized standard cards, capability-gated controls; preserve other device screens.
- [x] Android host: replace prototype UI, wire real actions and bottom destinations, owner settings dialog, explicit preview dialog, safe session cleanup.
- [x] Service: observable running/ready/battery/status state, reset on shutdown; dashboard reflects actual state.
- [x] Tests: state ownership/stale battery, shared screen navigation/action gating, preview cleanup and lifecycle review.
- [x] Verify full unit suite and debug/instrumentation build, inspect portrait/landscape on device, navigate all bottom tabs, verify original photo and assistant continue to work.
- [x] Review changes, update LensMoo documentation and existing feature branch/PR when verified.

## Verification (2026-10-23)

- Debug APK and instrumentation APK build succeeded; 609 unit tests passed.
- Device instrumentation: both shared navigation/gating tests passed. Temporary test package removed.
- Pixel physical test: actual five destinations opened, battery 100%, original JPEG saved (158913 bytes), preview decoded over 400 frames and explicit close confirmed camera off.
- Foreground assistant connected with camera off; service remained foreground while Settings opened. Voice pipeline was preserved, not re-exercised with a spoken request in this UI pass.
- Portrait inspected; landscape rotation requested, but captured during rotation, so landscape visual validation remains limited.
- Restored original screen-awake and rotation settings.
