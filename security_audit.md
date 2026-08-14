# CyanBridge Android Security Audit

**Audit date:** 2026-08-14  
**Application:** CyanBridge  
**Package:** `com.fersaiyan.cyanbridge`  
**Versions reviewed:** 2.1.1, version codes 15 and 16  
**Scope:** Google Play Protect malware warning, Android manifest, packaged release APK, Local Agent automation, sensitive permissions, remote control, privileged execution, data handling, and release history.

## Executive Summary

A user reported that Google Play Protect identified CyanBridge 2.1.1 as malware and recommended against installation. This is not the normal warning shown for a new or uncommon developer. Google documents the uncommon-app warning as: "Play Protect hasn't seen this app from this developer before. It may be unsafe." The reported warning instead states that the application was identified as malware.

The strongest likely cause is the combination of:

- An autonomous LLM-driven Accessibility `observe -> plan -> act` loop.
- Telegram-based remote phone control.
- Automatic execution of taps, typing, swipes, and Enter actions.
- Optional Shizuku privileged execution through `/system/bin/input`.
- Reading Accessibility content from every application and optionally capturing screenshots.
- Sending current screen text to remote AI planners.
- Broad and restricted permissions such as `MANAGE_EXTERNAL_STORAGE` and `QUERY_ALL_PACKAGES`.

Google explicitly prohibits non-accessibility-tool applications from using Accessibility to autonomously initiate, plan, and execute actions or decisions. CyanBridge is a general smart-glasses assistant rather than an accessibility tool, so its Local Agent implementation does not fit Google's permitted automation model.

The most plausible Play Protect categories are:

1. Backdoor or unauthorized remote control.
2. Elevated privilege abuse.
3. Spyware or restricted data collection.

The audit did not find APK installation, dynamic DEX loading, a hidden root exploit, direct SMS sending, or malformed signing. Hostile-downloader and malformed-APK classifications are therefore less likely.

## Severity-Ranked Findings

### 1. Critical: Autonomous Accessibility automation conflicts with Google Play policy

Google permits deterministic, rule-based Accessibility automation for narrow and clearly understood purposes. It explicitly prohibits apps from autonomously initiating, planning, and executing actions or decisions unless the app is a verified accessibility tool whose primary purpose is helping people with disabilities.

CyanBridge implements an autonomous LLM-driven loop:

- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/LocalAgentService.kt:44-49` describes an `observe -> plan -> act` loop.
- `LocalAgentService.kt:278-325` observes the screen, asks the Local Agent brain to choose an action, and executes the result.
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/LocalAgentBrain.kt:36-112` sends observations to an AI planner and converts its response into a device action.
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/LocalAgentUiControlProtocol.kt:576-607` instructs the model to act as a phone automation agent.

The Accessibility configuration does not claim `isAccessibilityTool=true`, which is correct for a general assistant. However, the autonomous behavior remains prohibited for a non-accessibility-tool app.

### 2. Critical: Telegram exposes remote phone-control functionality

`android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/LocalAgentTelegramService.kt:154-185` accepts remote task goals and starts the Local Agent. A Telegram command can therefore initiate the same LLM-driven Accessibility loop used for local automation.

This combination closely resembles Google's definition of a backdoor: code that enables unwanted or potentially harmful remote-controlled operations on a device.

Existing mitigations include:

- Telegram control is disabled by default.
- The user must provide a bot token and an exact allowed chat ID.
- The token is stored with `EncryptedSharedPreferences`.
- Polling uses HTTPS.
- A foreground notification is displayed while the listener is running.
- Poll offsets are persisted before executing commands to reduce replay risk.

These controls reduce unauthorized use but do not eliminate the Play policy and scanner risk created by remotely initiated phone control.

### 3. Critical: The auto-execution safety control is ineffective

The application displays an "auto-execute low-risk" setting and persists it through `LocalAgentPrefs.isAutoExecuteLowRiskEnabled()`. The action executor never reads that preference.

Evidence:

- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/LocalAgentPrefs.kt:72-81` stores the setting.
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/plugins/localagent/LocalAgentSettingsActivity.kt:521-529` displays and updates it.
- No execution code calls `isAutoExecuteLowRiskEnabled()`.
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/LocalAgentStepEngine.kt:53-75` automatically executes both low- and medium-risk actions.

Medium-risk actions include:

- Clicking text or coordinates.
- Typing text.
- Pressing Enter.
- Swiping and scrolling.
- Long-pressing.
- Toggling Wi-Fi or Bluetooth through settings.

Disabling the visible setting therefore does not prevent these actions from executing automatically.

### 4. Critical: No default sensitive-application denylist

`android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/LocalAgentSafetyPolicy.kt:7-14` blocks only packages selected by the user.

`android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/agent/LocalAgentPrefs.kt:140-147` defaults the blacklist to an empty set.

There is no built-in denylist for:

- Banking and payment applications.
- Password managers and authenticators.
- Android Settings.
- Permission Controller.
- Package Installer.
- Device administration screens.
- Play Store purchase and account screens.

The agent can therefore read and interact with sensitive apps unless the user manually discovers and blocks each package.

### 5. High: Semantic actions can bypass high-risk confirmation

`android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/actions/LocalAgentActionManager.kt:27-53` classifies explicit calls, SMS, emails, alarms, and screen reading as high-risk. Typing and clicking are classified as medium-risk and execute automatically.

An agent does not need to select the explicit `SendSms` or `SendEmail` action. It can open a messaging application, type a message, and click a visible Send button. Those component actions are medium-risk and bypass the high-risk confirmation boundary.

This makes the safety model vulnerable to semantic bypasses and prompt injection from visible screen content.

### 6. High: Shizuku introduces elevated-privilege execution

The release contains the Shizuku API and provider:

- `android/CyanBridge/app/src/main/AndroidManifest.xml:415-423` declares an exported `ShizukuProvider`.
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/LocalAgentShizukuFallback.kt` binds a privileged Shizuku user service.
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/shizuku/LocalAgentShizukuUserService.kt:46-61` executes `/system/bin/input` using `ProcessBuilder`.
- `android/CyanBridge/app/build.gradle:198-199` packages the Shizuku API and provider dependencies.

The release build does not enable code shrinking, so unused generic Shizuku APIs such as remote process creation remain visible in the APK. Static scanners can therefore observe more privileged capability than CyanBridge directly invokes.

Shizuku requires separate user setup, permission, and opt-in. Those controls are positive, but the packaged capability remains a strong Play Protect signal for elevated privilege abuse.

### 7. High: Accessibility screen text is transmitted to remote planners

The current screen's Accessibility content is incorporated into the planning request:

- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/LocalAgentUiControlProtocol.kt:251-275` inserts the current screen dump into the planner prompt.
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/router/AgentInferenceRouter.kt:168-205` sends that prompt to the selected provider.

Screenshot upload has a separate opt-in. Screen text does not have an equivalent per-request gate. When a remote provider is selected, text visible in another application may be sent to the remote provider as part of normal planning.

The onboarding disclosure mentions this possibility. That disclosure is a positive control, but the behavior remains highly sensitive and must be accurately represented in Play Console's Accessibility declaration, Data Safety form, privacy policy, and in-app consent flow.

### 8. High: Restricted permissions are not justified by the app's core purpose

The merged release requests:

- `android.permission.MANAGE_EXTERNAL_STORAGE`
- `android.permission.QUERY_ALL_PACKAGES`

Sources:

- `android/CyanBridge/app/src/main/AndroidManifest.xml:25-26`
- `android/CyanBridge/app/src/main/AndroidManifest.xml:44-47`

Google restricts All Files Access primarily to file managers, backup and restore applications, antivirus tools, document managers, on-device file search, encryption tools, and device migration tools. Media access is specifically listed as an invalid use when MediaStore can provide the functionality.

Google restricts broad package visibility to applications whose core purpose requires discovering all installed applications. CyanBridge uses it for an optional Local Agent blacklist and app-name resolution. That is unlikely to qualify as the app's core purpose, and narrower package visibility mechanisms are available.

### 9. Medium: Excessive permissions amplify scanner suspicion

The release APK requests a broad permission set including:

- `SYSTEM_ALERT_WINDOW`
- `KILL_BACKGROUND_PROCESSES`
- `DOWNLOAD_WITHOUT_NOTIFICATION`
- `ANSWER_PHONE_CALLS`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- `MANAGE_EXTERNAL_STORAGE`
- `QUERY_ALL_PACKAGES`
- Camera, microphone, location, notification, Bluetooth, and Wi-Fi permissions.
- Notification-listener access through a bound service.

Notable origins:

- `KILL_BACKGROUND_PROCESSES` and `DOWNLOAD_WITHOUT_NOTIFICATION` are declared in the main manifest but no corresponding use was found.
- `ANSWER_PHONE_CALLS` is merged from `glasses_sdk_20250723_v01.aar`.
- The notification listener is defensible because Google explicitly recognizes forwarding notifications to wearable hardware as an allowed use case.

The cumulative capability profile is consistent with applications that can observe, remotely control, and alter device behavior, even though many individual permissions have legitimate smart-glasses use cases.

### 10. Medium: Broad Accessibility monitoring resembles spyware behavior

`android/CyanBridge/app/src/main/res/xml/accessibility_service_config.xml:3-8` configures:

- All Accessibility event types.
- Retrieval of interactive windows.
- Inclusion of otherwise unimportant views.
- Full window-content retrieval.
- Gesture execution.
- Screenshot capture.

`android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/accessibility/LocalAgentAccessibilityService.kt:84-143` can periodically capture visible screen text. The periodic check runs every 30 seconds at `LocalAgentAccessibilityService.kt:697-720` when capture is enabled.

The application redacts nodes marked as password fields and supports a user-defined blacklist. Those are useful safeguards, but they do not protect non-password financial data, messages, account information, one-time codes displayed outside password fields, or apps omitted from the blacklist.

### 11. Medium: Backup and privacy rules are incomplete

The application enables `android:allowBackup="true"` at `android/CyanBridge/app/src/main/AndroidManifest.xml:62`.

The backup rules exclude several billing and remote-provider preferences, but do not explicitly exclude:

- `local_agent_secrets.xml`
- `local_agent_memory/`
- Local Agent task history.
- Saved automation skills.
- Databases containing memory-vault records or Accessibility-derived indexes.

Screen captures and derived memories are stored under `context.filesDir` or in the memory vault, as shown in `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/memory/LocalAgentMemoryStore.kt:30-45` and `230-267`.

Vault encryption may protect payload contents, but the current backup rules do not guarantee that all sensitive Local Agent state remains exclusively on the device.

### 12. Medium: Cleartext traffic is enabled globally

`android/CyanBridge/app/src/main/AndroidManifest.xml:71-72` enables cleartext traffic, and `android/CyanBridge/app/src/main/res/xml/network_security_config.xml:8-16` permits it globally.

Cleartext HTTP is required for local glasses media transfer over Wi-Fi Direct. The current configuration permits it for all destinations rather than limiting it to the local-device transport path. External application endpoints appear to use HTTPS, but global allowance increases the impact of a future endpoint or redirect mistake.

## Positive Security Findings

The audit found several controls that reduce risk:

- Local Agent automation is disabled by default.
- Telegram remote control is disabled by default.
- Shizuku fallback is disabled by default and requires an external Shizuku service and explicit permission.
- Telegram control accepts only one configured chat ID.
- Telegram polling uses HTTPS and persists update offsets before command execution.
- Telegram bot tokens use encrypted preferences.
- Remote screenshot upload requires a separate opt-in.
- Screenshots used for planning are temporary and deleted after the planner call.
- Accessibility nodes marked as password fields are redacted from text dumps.
- The agent stops when the device is locked or inactive.
- A foreground notification is used while remote control or automation runs.
- Explicit call and SMS actions open system UI instead of silently placing calls or directly sending SMS.
- The notification listener has a legitimate smart-glasses forwarding use case.

These mitigations support a future appeal but do not resolve the autonomous Accessibility, remote control, or privileged execution concerns.

## Capabilities Not Found

The following commonly malicious capabilities were not found in the release source or merged APK:

- No `REQUEST_INSTALL_PACKAGES` permission.
- No APK installation code.
- No `PackageInstaller` use.
- No dynamic DEX loading through `DexClassLoader`, `PathClassLoader`, or `InMemoryDexClassLoader`.
- No hidden root exploit.
- No direct SMS sending implementation.
- No Call Log or SMS permissions in the final merged APK.
- No evidence of malformed APK headers.
- No signing inconsistency in the locally built artifacts.

The release APK and AAB are signed with the expected AkiosLabs certificate. The APK uses standard Android packaging and verifies successfully.

## Version Analysis

The user warning was reported for CyanBridge 2.1.1. The Play-distributed build was version code 15.

The new version-code-16 build does not remediate the security surface described in this audit. A focused comparison between release commit `5515fec` and current commit `0e1793d` showed that the Local Agent implementation, Accessibility declaration, Shizuku integration, sensitive permissions, and data handling are unchanged. Within the audited security surface, only `versionCode` changed from 15 to 16.

Uploading version code 16 to Play without remediation is therefore likely to preserve the warning or trigger a Play Console enforcement action.

## Likely Play Protect Classifications

### Backdoor

Google defines a backdoor as code that enables unwanted or potentially harmful remote-controlled operations. Telegram-controlled LLM automation over Accessibility is the strongest match.

### Elevated Privilege Abuse

Google defines this category as code that breaks the app sandbox, gains elevated privileges, or changes protected system behavior. The Shizuku integration and `/system/bin/input` execution are the strongest signals.

### Spyware or Data Collection

Google defines spyware as behavior that collects, exfiltrates, or shares device data unrelated to policy-compliant functionality, or does so without adequate notice and consent. Full Accessibility-tree collection, periodic screen memory, screenshot capability, and remote planning create this risk.

### Restricted Permissions Abuse

Broad application inventory access and All Files Access are expressly restricted. Even if they did not trigger the malware warning, they create independent Play policy exposure.

### Less Likely Classifications

- **Hostile downloader:** No APK installation or package-installer capability was found.
- **Billing fraud:** No direct premium SMS or call behavior was found.
- **Riskware:** The release does not appear to use cloaking or dynamic code loading, although unminified bundled privileged APIs may still attract static-scanner attention.
- **Uncommon app:** The warning text reported by the user differs from Google's documented uncommon-app warning.

## Recommended Remediation

### Recommended Play Build

Create a dedicated Play-distribution variant that physically excludes, rather than merely hides:

- Telegram remote phone control.
- Shizuku API, provider, and privileged user service.
- Autonomous LLM Accessibility planning and execution.
- `QUERY_ALL_PACKAGES`.
- `MANAGE_EXTERNAL_STORAGE`.
- Unused `KILL_BACKGROUND_PROCESSES` and `DOWNLOAD_WITHOUT_NOTIFICATION` permissions.
- Overlay capability if it is not essential to the core smart-glasses experience.

Code excluded from the UI but still present in DEX can continue to trigger static analysis. Play-specific remediation must remove the classes, dependencies, services, providers, and permissions from the packaged artifact.

### If Accessibility Remains

Accessibility automation should be redesigned as narrow, deterministic, user-authored behavior rather than LLM-selected actions. At minimum:

- Remove autonomous multi-step planning.
- Require explicit confirmation for every click, type, Enter, swipe, and submission action.
- Enforce the auto-execution preference in the executor.
- Add a non-configurable sensitive-package denylist.
- Prevent interaction with package installers, permission controllers, Android Settings, device-admin screens, financial apps, authenticators, and password managers.
- Treat visible screen content as untrusted input to prevent prompt injection.
- Add a separate consent gate before transmitting Accessibility text to any remote provider.

Do not set `isAccessibilityTool=true` unless CyanBridge is redesigned and marketed primarily to help people with disabilities. Google explicitly states that general assistants and automation tools do not qualify.

### Permission Reduction

- Replace All Files Access with MediaStore and Storage Access Framework operations.
- Replace broad package visibility with launcher-scoped queries, user-selected packages, or targeted `<queries>` declarations.
- Remove permissions that have no verified runtime use.
- Use manifest-merger removal directives for unnecessary permissions introduced by bundled AARs.
- Reassess `ANSWER_PHONE_CALLS` from the proprietary glasses SDK.
- Limit cleartext traffic to the local glasses transport as narrowly as Android's network security model permits.

### Data Protection

- Exclude Local Agent secrets, screen memory, task history, saved skills, and vault databases from cloud backup and device transfer unless there is an explicit encrypted backup feature.
- Ensure Play Console's Data Safety form declares Accessibility-derived screen text, screenshots, app inventory, microphone data, and remote AI processing accurately.
- Ensure prominent consent separately covers Accessibility text transmission, screenshot upload, periodic screen memory, and remote control.
- Document retention and deletion behavior.

### Release and Appeal Process

1. Pause further Play rollout while the classification is investigated.
2. Obtain the exact category shown under the user's **More info** screen.
3. Review Play Console's **Policy status** page.
4. Review Accessibility, All Files Access, and App Visibility declarations.
5. Produce a remediated Play artifact and verify its merged manifest and DEX contents.
6. Submit the remediated build.
7. File a Play Protect appeal only after confirming policy alignment, unless Google clearly identifies a false positive unrelated to the audited behavior.

## Information Still Required

The following evidence is needed to identify the exact enforcement category:

- Full text from the warning's **More info** screen.
- Whether installation used production, open testing, closed testing, internal testing, or Internal App Sharing.
- Device manufacturer and Android version.
- Play Console **Policy status** output.
- Current Accessibility Service declaration.
- Current All Files Access declaration.
- Current App Visibility declaration.
- Current Data Safety answers for screen content, installed apps, microphone, camera, files, and remote AI processing.

The public Play listing URL for `com.fersaiyan.cyanbridge` returned 404 during the audit. This may mean the application is available only through a testing track, restricted account, region, or unpublished listing.

## Official References

- Google Play Malware policy: <https://support.google.com/googleplay/android-developer/answer/9888380>
- Potentially Harmful Applications: <https://developers.google.com/android/play-protect/potentially-harmful-applications>
- Play Protect malware categories: <https://developers.google.com/android/play-protect/phacategories>
- Play Protect warning strings: <https://developers.google.com/android/play-protect/warning-strings>
- Developer guidance and appeal process: <https://developers.google.com/android/play-protect/warning-dev-guidance>
- AccessibilityService API policy: <https://support.google.com/googleplay/android-developer/answer/10964491>
- All Files Access policy: <https://support.google.com/googleplay/android-developer/answer/10467955>
- Broad package visibility policy: <https://support.google.com/googleplay/android-developer/answer/10158779>
- Mobile Unwanted Software policy: <https://developers.google.com/android/play-protect/mobile-unwanted-software>
- Play Protect appeal form: <https://support.google.com/googleplay/android-developer/contact/protectappeals>

## Audit Integrity

This was a read-only audit of application source, release build outputs, manifest-merger reports, Git history, and official Google documentation. No application code, release asset, Git history, or Tailscale configuration was changed as part of the audit.
