# CyanBridge Android: changes since v2.3.0

Range: tag `v2.3.0` (`fc9794cf`, release 2.3.0 code 31, 2026-09-14) to `origin/cyan-refactor` `420e07b3` (2026-11-12). 513 commits, 79 of them merges. The app still reports versionName 2.3.0, code 31.

The range has two parts. Work from 2026-09-04 to 2026-10-10 was merged after the tag: Gemini Live session fixes, Mentra Live SDK, the onboarding redesign, HeyCyan/TuneBuds/EyeVue live preview, Walking Aid video and the NPU runtime. Then `cyan-refactor` ran from 2026-10-22 to 2026-11-12. Most of the refactor is meant to keep behaviour the same: the MainActivity extraction, shared KMP ViewModels, typed preferences, the shared Room database, audio and network session owners, the single Activity and the iOS port. This file lists only what a v2.3.0 user would notice. iOS gets one short section at the end.

## Status values

| Status | Meaning |
|---|---|
| `CI` | Run by `.github/workflows/android-self-hosted.yml` on every push that touches `android/**`. Either a named emulator step (read-only Pixel_9a, port 5584), or a JVM test in `:app:testDebugUnitTest` / `:shared:portabilityTest` ("unit"). A unit test covers logic, not the screen. `android-tasker-hil.yml` and `jev-like-local-agent.yml` do not trigger on this branch. |
| `HIL` | Checked against real services or a hardware/AVD rig. Evidence is named: worklog date, evidence dir or rig. |
| `crash-sweep only` | Opened and tapped without a crash in CRASH_SWEEP.md (2026-10-27 at `65fabc60`, 2026-11-11 at `b695e3ca`, every route). Where noted, also shown in the SCREEN_PARITY.md captures or the 2026-10-26 adb navigation smoke test. Not functionally verified. |
| `this sweep` | Tested functionally on 2026-11-12 on a read-only Pixel_9a (API 37, rooted, Play, Tasker; CI Pro account linked) with the `cyan-refactor-v230-sweep` build. PASS, PARTIAL, FAIL (fixed: commit), or NOT RUN with the reason. Evidence: `cyan-refactor-wt/v230-sweep-evidence-20261112/` (`shots/NN-*.png`, `logs/`). |
| `needs hardware` | Needs the named glasses, earbuds or phone hardware. |

Evidence dirs from earlier HIL runs: `cyan-refactor-wt/pro-hil-evidence-20261112/` (Pro questions, every model) and `cyan-refactor-wt/pro-hil-evidence-20261112b/` (Live photo turns, cue and idle close). The media-sync rig evidence is in `firmware_dump` RESULTS reports (add.221 to add.224).

CI steps in `android-self-hosted.yml`, used as names below:
1. "Validate Tasker integration contracts" (`validate_tasker_profiles.py`).
2. "Run Android and shared tests" (unit).
3. "Run Mock DAT, Meta pairing, and Walking Aid video controls": MetaDatLifecycleTest, MetaDatSdkMockTest, MetaPairingMockFlowTest, MetaPairingScreenTest, WalkingAidVideoSourceButtonsTest and WalkingAidVideoCaptureLifecycleTest.
4. "Run hermetic audio instrumentation" (AudioEmulatorHarnessTest, 4 methods).
5. "Run Walking Aid real models and glasses video streams" (`run_walking_aid_model_ci.sh`, phases yolo11, yolo-world, depth-anything, eyevue-video and meta-video).

## Onboarding

| Change | Commit(s) | Status |
|---|---|---|
| Welcome and feature steps redesigned: benefit cards, "About 2 minutes" note, 48 dp language selector, "Continue without Bluetooth?" confirmation, "Find available glasses". | 060c50a2, a43e9c3f, d7e726e9 | this sweep: PASS. Cleared install through the launcher alias: battery guide updates after the system dialog; "Continue without Bluetooth?" on Next; granting opens the scanner; on 4 GB RAM the Gemma 4 choices are greyed out with the "Limited memory" card and the compact default (Qwen3.5 0.8B, 537 MB) downloads in the background; Skip all; onboarding not shown again (shots 01–20). |
| Five steps instead of four: the voice profile ("Teach CyanBridge your voice", three phrases) is step 4. | c8fc4efb | this sweep: PARTIAL. Consent switch, microphone prompt, and a phrase take (`VoiceEnrollment VOICE_COMMAND granted`/`released`) work. No speech reached the AVD: emulator 37.1.11 logs "Could not init `pa` audio driver" even with host audio and `avd hostmicon`, so saving a profile needs a phone. On 1080×2424 the "Allow microphone" button is below the fold while "Tap and say the phrase" is disabled (shots 11–14). |
| "Do you need vision assistance?" comes first when a screen reader is on. If TalkBack is turned on after onboarding, the question is offered once at the next launch. | c8fc4efb | this sweep: PASS. With TalkBack on, the offer comes first with Yes preselected (Continue stores ENABLED); TalkBack turned on after onboarding: offered at the next launch, No stores DISABLED and it is not offered again (shots 22–25). |
| Onboarding runs inside MainActivity: no fade between steps, Back on a step leaves the app, and no Bluetooth or permission prompts appear until onboarding ends. | 494c9730, a1c7fc25 | this sweep: PASS (one MainActivity task; no Bluetooth prompt before onboarding ends; Skip all asks for it on the glasses screen). |
| The accessibility disclosure now says CyanBridge has no Accessibility service of its own. Screen Memory and the Local Agent go through Tasker and AutoInput, and screenshots are an off-by-default Tasker setting. | b17858e3 | crash-sweep only |

## Settings

| Change | Commit(s) | Status |
|---|---|---|
| Shared-UI changes: the intro card is removed, "Custom AI provider" is renamed "AI provider" (9 languages), FAQ moved into Support, and dashboard developer tools are collapsed by default. "Test image AI description" was renamed and then restored, and so was the chat palette icon. | b49ff72a, 92987d5f | crash-sweep only (SCREEN_PARITY 2026-10-25) |
| Settings → Media sync: "Ask every time" (default) or a remembered flow. A remembered flow skips the Sync picker and is also used for full-resolution image questions; with "Ask every time", those questions use Custom. | 4984919d | HIL (add.224 rig: remembered Adaptive, 1/1, no picker); this sweep: PASS (Adaptive kept across a restart, shot 31). Note: the Custom flow's subtitle still says "then a local subnet scan", which the v2.3.0 resolver never reaches (worklog 2026-11-11). |
| Settings → AI provider → Question routing: Classic, Smart (default) and Shadow (debug). | c8fc4efb | this sweep: PASS (Smart is the default after install; Classic/Smart stored in `question_routing`, shot 32). |
| Settings → AI provider → Voice profile: enroll (consent, three phrases), enroll again, delete. Stored only in `no_backup/voiceprint`, and answers are not filtered yet. Settings → Clear local data deletes it. | c8fc4efb | this sweep: PARTIAL ("Not enrolled … Answers are not filtered yet." shown; enrolling needs a working microphone, see Onboarding; delete not reachable). The profile is only observed (`SPEAKER_OBSERVE` logs), never used to filter. |
| Settings → Accessibility → Vision assistance switch. | c8fc4efb | this sweep: PASS (one checkable row with its description; toggles ENABLED/DISABLED, shots 81–82). |

## AI / questions

| Change | Commit(s) | Status |
|---|---|---|
| Smart routing (default): the recording goes to the model as bare audio with a glasses system prompt. The reply starts with a tag ([answer] [camera] [search] [phone] [live] [ignore]) and the app acts on it at once. This only applies on the Local route with a LiteRT model that hears audio, or on Pro with an audio-capable questions model; anything else behaves as Classic. | c8fc4efb, 11e05746, 4a687fe0 | this sweep: PASS on Pro (questions model Gemini 3.7 Flash, audio-capable): ANSWER "Canberra", CAMERA with 1 photo "yellow socks", SEARCH (Google), PHONE (Local Agent gate spoken), IGNORE; decisions in 3.2–3.6 s. `GlassesQuestionFlowTest -e gq_provider pro` (option added on this branch), logs `instrument-smart-*.txt`, `logcat-smart-*.txt`. The Local LiteRT route was not run: the 2.6 GB model does not fit the AVD. |
| Classic: transcribe first, then follow the AI button's route (voice or photo), as in v2.3.0. | c8fc4efb | this sweep: PASS on Pro (classicWakeOnVoiceRoute… "Canberra" without a photo, classicWakeOnImageRoute… "bright yellow" with the photo; `instrument-classic-routes.txt`). |
| Web answers with no API key: an off-screen WebView reads Google (default) or DuckDuckGo. The AI Overview is read out directly. Otherwise the info box or top 3 results go to the model. Offline, blocked, missing-WebView and nothing-found cases speak fixed sentences in 9 languages. The worklog (2026-10-24) records the Google ToS concern as the owner's call. | c8fc4efb | this sweep: PASS with Google (smartWebQuestionReadsTheWebAnswer: AI answer + info box + 3 results in 4.6 s, weather read out). DuckDuckGo and the offline sentences were not run. |
| Follow-ups: a 5 s window after each answer with no wake word. LiteRT keeps the conversation open, so earlier audio and photos are not prefilled again, and it restarts when the context is full. | c8fc4efb | NOT RUN: LiteRT keeps the conversation, Pro does not; needs a phone with Gemma 4. |
| Memory for spoken questions: a compact block of up to 1,000 characters from the Memory Vault (user facts, today's facts and summary). | c8fc4efb | NOT RUN (LiteRT test; needs a phone with Gemma 4). |
| "Initializing local model" is spoken once, only on a cold load. Model errors are never read aloud ("I couldn't process that question"). | c8fc4efb | NOT RUN (local model test; needs a phone with Gemma 4). |
| Pro relay: spoken questions go as audio to models the server flags `supports_audio` (STT fallback), photo + audio to `/image-query`. | c8fc4efb | HIL 2026-11-12 (ProQuestionsHilTest, 14 non-Live models, 39/42; misses are model answers and one server 502) |
| Questions record through a shared audio session. A question during a meeting recording joins its route and no longer cuts it. Gemini Live and voice plugins pause during a question and resume afterwards. | 402571a9, c0f24ab9, f9bd06b6 | needs hardware (any BT glasses) |
| A question asked while another spoken prompt listens is refused with "The microphone is busy; please ask again", before the beep. | 402571a9 | CI (unit QuestionMicrophoneTest) |
| Background question service: no crash when Android refuses the microphone type (Android 14+). A silenced recording says "Android blocked the microphone while CyanBridge was in the background…". | 7e35a172 | needs hardware (MemoMind HFP fallback or Mentra Live, app in background) |
| Crash fixed: Test voice with the "Pro (Free Gemini Live)" provider (ForegroundServiceDidNotStartInTimeException). | 0a08b7a5, 28fd5369 | CI (unit AiQuestionForegroundServiceTest) |
| Gemini Live closes 60 s after an answer with no reply or new question. v2.3.0 had no idle close (the service stopped at 15 min); the September builds used 30 s. | b69fd95f, 1afc44a9, b46efd04 | HIL 2026-11-12 (pro-hil-evidence-20261112b: open at 59 s, closed at 60.001 s) |
| Free/Economy Live sessions end at 5 minutes without reconnecting. | c15d89c5, 1d0d5342, 8107a769 | NOT RUN (time). |
| A second or later photo question joins the running Live session (v2.3.0 dropped it as "duplicate Live start"). Turns queued before setup are sent in order. | b695e3ca | HIL 2026-11-12 (Economy photos 1-3, Private 1-2) |
| A photo and its question are one ordered turn when the proxy announces `clientImageTurns`; otherwise the frame goes first with a 1.5 s wait. | c95c13e9, 8be26e66, 420e07b3 | HIL 2026-11-12 (single-message image turns, pro-hil-evidence-20261112b) |
| The "Initializing Live" cue is spoken only when a question starts a new session. | 7af09e58 | HIL 2026-11-12 (cue on new sessions only) |
| Switching between Live and non-Live questions models in one session. | 1df622d9, b695e3ca | HIL 2026-11-12 (Live → non-Live → Live) |
| Live start asks for the notification permission. If it is denied, the visible preview screen runs instead of the service. | b0fed02d, a2abd809, 3725caba | HIL 2026-11-12 (notifications-denied preview route, 3 photos) |
| Dashboard snackbar "Gemini Live: microphone may be active" with a Stop Live action. A crash in the single Activity was fixed in c95c13e9. | 73b4693a, c95c13e9 | NOT RUN. |
| Private Live: rejections for an active session and for rate limits are spoken with actionable text, and startup errors keep their retry-after. | fb51c97d, 3b2fc55c, 203f4c56 | CI (unit GeminiLivePrivateStartupTest, GeminiLiveAnnouncementTest) |
| Gemini Live picks its microphone route from the glasses family's audio profile (Mentra uses SDK PCM). | 142eeeac, 429f4216 | needs hardware (BT glasses, Mentra Live) |
| Local models: "Gemma 4 E2B IT (Snapdragon NPU)" replaces the "coming soon" entry. It resolves per SoC (SM8650 community SRQ build, HF commit 2f5d0668, SM8750 Google build) with sha256 and size per file. | 6641668c, 98587c81, e74cc988 | needs hardware (Snapdragon 8 Gen 3 / 8 Elite phone, `-PlocalLiteRtNpuAarPath` build) |
| In builds without the NPU runtime (every CI/Play build), that entry is listed but disabled, with "This app build has no NPU runtime." | 6641668c | this sweep: PASS (listed as Unavailable with "This app build has no NPU runtime."). Local models: Qwen3.5 0.8B download, CPU/GPU/NPU backend choice and "Test model now" PASS (shots 40–46); the test reports a placeholder "1000 tok/s, 1 ms" for Qwen3.5 because its warm-up is skipped (pre-existing since b7c048f5). |
| Imported Gemma 4 GGUFs use the `gemma4_it` template (they were prompted as ChatML). LiteRT media requests pass the system prompt as the system instruction. | 6641668c | CI (unit PromptTemplateRegistryTest); the LiteRT part needs hardware |

## Devices

| Change | Commit(s) | Status |
|---|---|---|
| Device picker: Mentra Live rows come from the normal scan and connect through the Mentra SDK. The "Pair Mentra Live" button and its screen are gone, and the glasses reconnect on launch. | ca06bc3a, 0aef9d10 | needs hardware (Mentra Live) |
| Device picker: bonded and Classic-inquiry TuneBuds/MemoMind rows are merged with their BLE row, and Classic results now arrive on Android 14+. | 508c6f6f, 13b463ec | needs hardware (TuneBuds) |
| Device picker type dialog: new choices MemoMind, Mentra Live and the Mentra display models; the last two are offered only for detected devices. | 79b04950, af271456 | needs hardware (MemoMind, Mentra) |
| Mentra Live becomes a full family: photo and video on the glasses, counts, battery, firmware, time and volume; press = image question, long press = voice question; SDK microphone for questions and Live; BLE photo for AI photos; gallery mode off. | 298bdcec, 860fb791, b67c6e70, c5c21af0 | needs hardware (Mentra Live) |
| Mentra Live firmware update: Advanced → Device info → Firmware update, with preconditions, glasses Wi-Fi setup, progress, and Back refused during the update. | 3be129af, ec5736cf, ddad4ee5 | needs hardware (Mentra Live) |
| Mentra Live preview card (in-app RTMP ingest over the glasses hotspot or a shared Wi-Fi) and "Broadcast to my server" (RTMP/SRT). | e1b028b6, e6ba8fc2 | needs hardware (Mentra Live) |
| Mentra SDK display glasses: Even Realities G1 and G2, Mentra Display (Android only), NIMO and Xingyi AR99. Pairing, reconnect, answers as paged text, SDK microphone questions, text plugins and notifications. | af271456, 0ff8c43d, 5a4d8e85 | needs hardware (one of the five models) |
| MemoMind One, a new family over SPP: battery, HUD (notification card, teleprompter, brightness), notifications as native cards, long-press voice question, Opus-link questions with HFP fallback, answers on the HUD, and button questions with the app off screen. | f2e4b295, 9aade413, f180acbb, 87b0a16f, 3b7e3133 | needs hardware (MemoMind One) |
| TuneBuds: runs through the shared session (the E9 coprocessor query is optional). New RTSP live preview on the dashboard. Button 0xB0 now starts the assistant. | d92b4ca1, 01035cbf, 7aadfd1f | needs hardware (TuneBuds) |
| EyeVue: dashboard Photo is a manual shutter (0x30), video start uses 0, AC55 replies parse (battery and count toasts), inline LibVLC live preview, one connect attempt at startup, and Disconnect or switching glasses closes the GATT link. | 3872ff32, 05463fb2, 025f364d | needs hardware (EyeVue) |
| HeyCyan: inline live preview over Wi-Fi Direct (LibVLC, vendor stop opcode, full-MAC peer match), AI photo transfer and ownership repair, and the 0x03 wake ignored during a capture. | 49e24d06, 58316da9, 3d7cfe58, 01035cbf | needs hardware (HeyCyan) |
| Meizu MYVU: the app no longer crashes at launch with MYVU selected. | c50e7c27, f227179d | CI (unit MeizuMyvuManagerTest) |
| Meizu MYVU: HFP/A2DP state broadcasts are received on Android 14+ (submodule fix). | 3e6338d2 | needs hardware (MYVU) |
| Meta setup choice: "I use Developer Mode" or "Request access with my Meta email". One build covers both (registered app id in every build type). The registration path is recorded. When a Developer Mode registration ends, a spoken notice and a "Register again" notification appear. | 4c9894c7, a1a272e7, a8d93534, d90f24a5 | CI (MetaDatSdkMockTest both choices, MetaPairingMockFlowTest, MetaPairingScreenTest) |
| Meta: the same flows against real Meta AI (Developer Mode, invite, and Developer Mode turned off). | 4c9894c7 | needs hardware (Ray-Ban Meta + Meta AI) |
| Meta: UNAVAILABLE before registration is now NEEDS_SETUP (opens Meta AI to pair) instead of "Request Meta access". Errors while registered no longer flip access to FAILED. | 1f27c5c2 | CI (unit MetaPairingScreenStateTest, MetaDatRegistrationSupportTest) |
| Meta: the first DAT STOPPED state is ignored, so camera and display streams no longer close at start. | a13fffcd | CI (MetaDatLifecycleTest) |
| Meta DAT analytics opt-out (`ANALYTICS_OPT_OUT=true`). This avoids the DAT 0.8.0 registration deadlock and stops DAT usage analytics to Meta. | 6ddf847a | CI (MetaDatSdkMockTest asserts it) |

## Plugins

| Change | Commit(s) | Status |
|---|---|---|
| Plugins page sections depend on the selected glasses. MemoMind gets "MemoMind Web plugins" and "Mentra miniapps". MYVU, Mentra Live and the Mentra display models get "Mentra miniapps". Other families are unchanged. | 8bbe3fae, a924209e | this sweep: PASS (HeyCyan: neither section; MemoMind: both, and Walking Aid "Unavailable for MemoMind: device has no camera"; Mentra Live and Even G1: miniapps; shots 50-*). |
| Mentra miniapps (Experimental, off by default): Enable switch, install from URL (HTTPS and domain rule, ETag update), store browser and search, permission consent, remove. | 179eac24, 234d79d8 | this sweep: PARTIAL. Enable, install Teleprompter from its URL (consent dialog; "Your glasses lack display" for Mentra Live), Update, permission dialog, and its UI on Even G1 (QuickJS background started; the WebView page rendered per DevTools, `logs/miniapp-ui-dom.json`, but the headless swiftshader screen stays blank) PASS. FAIL (external): the store browser shows "The store is unavailable: No miniapp was found at this URL" because `api.apps.mentraglass.com/api/store/apps` now answers 404. Fixed in d7170a34: the section text showed `developer\'s` (escaped apostrophes in Compose resources). Shots 51–60. |
| Mentra miniapps on glasses: Captions and Teleprompter on the display, button and touch events, the "A miniapp is listening" foreground service. | 179eac24, a924209e | needs hardware (MemoMind, MYVU or a Mentra display model) |
| MemoMind Web plugins (.mmpkg): install dialog, a non-package file refused, permission dialog, remove. | c997fd54, f561274e, e781c41a | this sweep: PASS for what needs no package: "MemoMind is not connected" banner, file picker, a JPEG refused with "Not a valid MemoMind Web plugin: Not a ZIP archive" (shots 68–69). Install/permissions need a real .mmpkg. |
| MemoMind Web plugins on the glasses: display, buttons, microphone capture, plugin messages. | 4914f6bc, 2fd2630f | needs hardware (MemoMind One, Web Bridge) |
| Walking Aid video sources: Periodic photos, HeyCyan, EyeVue, Meta and Mentra Live continuous video, with the source locked while running and permission prompts. | 06fcfd14, b7da3451, bb3a6be0 | CI (WalkingAidVideoSourceButtonsTest, WalkingAidVideoCaptureLifecycleTest; eyevue-video and meta-video phases) |
| Walking Aid with real HeyCyan or Mentra Live video. | b7da3451, bb3a6be0 | needs hardware (HeyCyan, Mentra Live) |
| Walking Aid detectors: YOLO-World boxes decoded (they collapsed to a corner), and YOLO11n labels are right (every label used to be "person"). | e74cc988, 06fcfd14 | CI (yolo11 and yolo-world phases) |
| Walking Aid Explore mode. | 01035cbf | needs hardware (glasses camera) |
| Oral Lesion Bench (experimental, Android only, off by default): enable, model card (pinned revision, SHA-256, "Solicite acesso" on 401/403), case form, photo from the phone camera or gallery, delete and zip export. Not crash-swept: it merged after the 2026-11-11 sweep. | acce8284, 8827519f | this sweep: PASS up to the download: enable, research banner, the two stock baselines (E4B 3659 MB, E2B 2588 MB) with Download, gallery photo picked, Assess disabled until a model is installed (shots 61–67). Turn 1 not run (no stock model on the AVD). |
| Oral Lesion Bench inference (Gemma 4 E4B/E2B on its own LiteRT engine, GPU first). | acce8284 | needs hardware (phone GPU, multi-GB model download) |
| Local Agent screenshots: an optional Tasker step (`%CB_LocalAgentScreenshot`=1, off by default). A validated copy goes to the planner, remote and Pro planners included; Settings shows "Screenshot status". | c5ecc516, 3f4e6c22, 11ed962a | NOT RUN (CI step 1 checks the profile contract). |
| AutoDiary Tasker profile mirrors each observation to `Documents/CyanBridgeMirror`. | 03b88ea0 | NOT RUN. |

## Media sync

| Change | Commit(s) | Status |
|---|---|---|
| The HeyCyan Sync picker has three flows: "HeyCyan app flow", "Custom flow" (the v2.3.0 resolver again) and "Adaptive flow" (Experimental, learns the route). From eac40aee to 4984919d, "Custom" silently ran the adaptive engine. | 4984919d, eac40aee | HIL (add.224 BLE + Wi-Fi Direct AVD rig: HeyCyan 5/5, Custom 3/3, Adaptive 3/3, 12/12 photos sha256-matched) |
| Custom and Adaptive under a VPN use the VPN-safe LOCAL_ADDRESS route. Truncated downloads resume with Range (up to 8 attempts). | fc6c7a27, 3d426068 | CI (unit GlassesHttpResumeTest); a VPN and a real truncation were not run on the rig |
| The app's own discovery timeout or reset no longer tears down an in-flight P2P join (it used to fire at about connect+4 s), in all three flows. | a24abe4f, 56417378 | HIL (add.224 rig: resetDeviceP2p 0) |
| Patched vendor `.aar`: length guards in the cmd-65 GlassModelControl parsers (an uncaught AIOOBE crash on short frames) and the 0x42 battery parser. An app-side guard also drops short cmd-65 frames. | f2858247, bf740e07, a24abe4f | HIL (add.224 rig: short frame dropped every run, 0 AIOOBE) |
| One glasses network session owns Wi-Fi Direct (sync, live preview, OTA, Wi-Fi ADB). Group removal is confirmed and retried. HeyCyan sync binds the process under a VPN. After 15 s busy, the toast says "Wi-Fi Direct is still busy with another glasses session". TuneBuds waits up to about 17 s before the hotspot fallback. | 4eb9d300, 3676bd3e, 5f77f630 | needs hardware (HeyCyan, EyeVue, TuneBuds) |
| EyeVue sync clears the glasses after a full successful sync, as the vendor app does. Audio is saved as WAV under `Music/CyanBridge` (no longer DCIM). Sync continues past single-file failures. | 41a64d4e, 4e1e45a5 | needs hardware (EyeVue) |
| Mentra Live gallery download over the glasses hotspot: app-scoped join with the default network unchanged, then a "Delete from glasses?" prompt for verified files. | 9014be18, 335de1ff | needs hardware (Mentra Live) |

## Pro

| Change | Commit(s) | Status |
|---|---|---|
| The Live mode is picked in the Photo & voice questions picker ("Gemini 3.8 Live · Economy (7x)" or "· Private (36x)"). The two chips are gone, Live is no longer offered for chats or background work, and saved values are mapped on read. | 1df622d9 | HIL 2026-11-12 (ProQuestionsHilTest) |
| Pro pickers list only `supports_audio` models (an older server keeps the full list). The offline fallback list is audio-only. | 1df622d9 | HIL 2026-11-12 (every picker model) |
| Pro answers on every questions model, photo and speech. | c8fc4efb, 1df622d9 | HIL 2026-11-12 (pro-hil-evidence-20261112) |
| Glasses customization card (Standard/Max): tones, LED and shutter, wake phrase (8 to 12 samples through the glasses microphone), consent, build list, and install of the emailed firmware (HeyCyan combined OTA, EyeVue RCSP, TuneBuds FOTA). | 7aadfd1f, f628ab73, 7b6f0386, f774da5e | needs hardware (HeyCyan, EyeVue or TuneBuds, plus a Standard/Max plan) |
| Customization card without that plan: the upgrade state ("View Standard & Max"). | 7aadfd1f | this sweep: FAIL, fixed (see below). With the CI Standard account the production relay answers a bare 404 for `/api/customization/*` (not deployed), and the card said "Server answered HTTP 404". b8c99e6b: it now says "The customization service is not open yet." (shots 34, 89). |
| Pro checkout and Pro settings keep their state through a rotation: no billing reconnect or refetch, and plan selection is kept. | 49dba029, bef4dc86 | NOT RUN. |

## Removals

| Change | Commit(s) | Status |
|---|---|---|
| Telegram Remote Control removed from Local Agent settings. | 5ae52370, 5e880ea9 | crash-sweep only (SCREEN_PARITY 2026-10-26) |
| CyanBridge's own accessibility service code removed (it was not in the manifest, so it never ran). The Local Agent "Accessibility service" row now shows AutoInput's state. | 67c3142c | crash-sweep only (the parity capture shows "Enabled" with AutoInput on) |
| Local Agent switches "Use screenshots…" and "Allow screenshots to be sent to remote planners" removed (they did nothing). Saved values are no longer read. | b17858e3 | crash-sweep only |
| Transcription endpoint debug screen (TranscriptionDebugActivity, which nothing opened) and its chunking pipeline removed. The startup deletion of the old prefs file was added in 6b6e26cb and dropped in 54ccad84. | 6b6e26cb, 54ccad84 | CI (unit ArchitectureGuardrailSourceTest pins the manifest) |
| Even Hub runtime removed (unreachable). | 8bbe3fae | CI (unit ArchitectureGuardrailSourceTest) |
| The separate Mentra pairing screen and the "Pair Mentra Live" button. | ca06bc3a | crash-sweep only |
| Unwired code: Gemini Live relay client, transcription services, LocalAgentService, mock display adapter, Mentra/HUD runtimes, MemoMind clients, and Device Bind protocol probes. | 23039d6d, e1e7a81f, c3a6fef5, e128e38b, eac79284 | CI (build and unit) |
| 122 unused app strings and 194 unused shared strings in every locale; dead View layouts. | 30bcfbce, 8ca71f1d, 72912bc0, 79c2f3bd | crash-sweep only |

## Behaviour changes a v2.3.0 user would notice

| Change | Commit(s) | Status |
|---|---|---|
| Voice questions use Smart routing by default when the model can hear audio. The question is no longer transcribed first, and the model may take a photo, search the web, start the Local Agent or Live, or stay silent. See AI / questions. | c8fc4efb | this sweep: PASS on Pro (see AI / questions). |
| Glasses assistant default: CUSTOM_AI_PROVIDER when nothing is saved. Unchanged on Android (v2.3.0 already defaulted to it); it changed on iOS only. | f428497a | CI (unit PrefsInventoryTest) |
| Gemini Live: closes 60 s after an answer, Free/Economy ends at 5 min, follow-up photos join the session, the "Initializing Live" cue plays only for a new session, and a notification-permission request comes first. | b69fd95f, c15d89c5, b695e3ca, 7af09e58, b0fed02d | HIL 2026-11-12 (5-min cap not run) |
| Sync picker: three entries with an "Experimental" tag, and it can be skipped through Settings → Media sync. "Custom" is the v2.3.0 resolver again. | 4984919d | HIL (add.224 rig) |
| EyeVue full sync deletes the synced files from the glasses. | 41a64d4e | needs hardware (EyeVue) |
| Meta DAT no longer sends usage analytics to Meta. | 6ddf847a | CI (MetaDatSdkMockTest) |
| Single Activity: 43 Activities became 6. Screens open without window animations (the onboarding fade included), one task shows in recents, and the launcher icon is the alias `.ui.WelcomeActivity`, so pinned shortcuts still work. | 7ce18042, 494c9730, a44c4f67 | this sweep: one task, Back order (Settings → previous tab → glasses → home), rotation and process death PASS. FAIL, fixed: the launcher icon on the running app (MAIN/LAUNCHER delivered to the singleTask Activity) dropped the whole back stack to the glasses screen (fixed in 722b083e); v2.3.0 brought the app back on the open screen (shots 84–86). |
| Single Activity: notification taps (AI question, Live, meeting, previews, model download, daily facts), the `fersaiyan://pro-sub/restore` link, and picker results (Settings export/import, chat image, Local models import, Obsidian vault, Tasker profile save) open or return inside the running app. | 7ce18042, a6b0049d, 913868bb, 8c391e0e | this sweep: PARTIAL. `fersaiyan://pro-sub/restore` opens Pro settings over the open screen and Back returns (shot 83); the gallery picker (Oral Lesion Bench) and file picker (MemoMind) return into the screen. Notification taps NOT RUN (no notification posted on the AVD). |
| While a tab or settings screen covers the glasses screen, battery polling and the glasses listener pause, as when those were separate Activities. | 7ce18042 | needs hardware (any connected glasses) |
| Chats: a generation or voice-note recording continues through a rotation (it used to stop and attach). Leaving the chat while recording discards the note silently. | 9199512d, 61e521d0 | this sweep: Pro chat answers, auto title, rotation keeps the draft PASS. FAIL, fixed: after process death a chat started from "New Chat" reopened as an empty new chat (also in v2.3.0; fixed in 845ae035) (shots 72–74, 88). Generation through rotation not run. |
| Plugin settings, Walking Aid, Community Plugins, Pending actions, Daily summary and the battery guide keep their state on rotation, and Community Plugins no longer refetches. | 4e4c6602, 671ec3a6, 1d08eaf3 | NOT RUN (Local models rotation during a download: the 537 MB download finished first). |
| Firmware catalog and customization traffic: plain HTTP to local hosts only in debug builds; release builds stay HTTPS-only. | dc83b828, f774da5e | CI (unit OtaFirmwareTargetTest) |
| TuneBuds button 0xB0 starts the assistant. | 7aadfd1f | needs hardware (TuneBuds) |
| Questions during a meeting recording no longer cut the meeting's Bluetooth route. Live pauses during a question and resumes afterwards. | 402571a9, f9bd06b6 | needs hardware (BT glasses) |
| Denying the nearby-devices permission twice used to reopen the app's system Settings on every return to the glasses screen (also in v2.3.0). Settings now open once per process. | 28fed707 | this sweep: FAIL, fixed; verified with the permission user-fixed (shots 21, 91) |

## Bugs found by this sweep

| Bug | Since | Fix |
|---|---|---|
| Launcher icon on the running app drops the back stack to the glasses screen (singleTask `onNewIntent` with a routeless MAIN/LAUNCHER intent). | single Activity (494c9730) | 722b083e + AppNavigatorTest case |
| A chat started from New Chat reopens as an empty new chat after process death. | v2.3.0 | 845ae035 |
| Bluetooth permission denied twice: Settings reopen on every return, the app is unusable without granting. | v2.3.0 | 28fed707 |
| Customization card shows "Server answered HTTP 404" (production relay has no customization API). | 7aadfd1f | b8c99e6b + GlassesCustomizationViewModelTest case |
| Backslash before apostrophes in Compose strings (Mentra miniapps text; fr/it recordings strings). | a924209e and earlier | d7170a34 |

Fixed on `cyan-refactor-fixes-logs` (2026-11-17): the onboarding voice step (the pinned button asks for the
microphone), "Test model now" (measured prompt/decode rates or "Not measured"), the Custom flow subtitle, the Mentra
store (new directory API, first-party fallback), and support reports (see worklog 2026-11-17).

Not fixed, for the owner:
- The Mentra Miniapp Store feed (`https://api.apps.mentraglass.com/api/store/apps`) answers 404, and the store screen says "No miniapp was found at this URL". The endpoint has to be found again; installs from a miniapp URL still work.
- The production website has no `/api/customization/*` yet, so the card cannot leave "not open yet" until it is deployed.
- Onboarding voice step on a 1080×2424 screen: "Allow microphone" is below the fold while the record button is disabled.
- ~~Local models: "Test model now" shows a placeholder 1000 tok/s / 1 ms for Qwen3.5 (warm-up skipped since b7c048f5).~~ Fixed 2026-11-17: the skip is gone; Qwen3.5 0.8B runs the test generation on the current upstream llama.cpp (AVD: ~38 tok/s prompt, ~19.5 tok/s decode).
- The Custom sync flow subtitle mentions a subnet scan that the resolver never reaches.
- Emulator limits met here: no microphone input on emulator 37.1.11 headless ("Could not init `pa` audio driver"), and miniapp WebView pages render blank on the swiftshader screen although their DOM is complete.

## iOS (short)

These changes are outside this sweep. The iOS port (KMP host `ios/CyanBridgeKMPHost`) runs chats, notes, dashboard, device bind, Pro and Live on the shared ViewModels. iOS also gained:
- MemoMind over BLE GATT or iAP2;
- Mentra display glasses through the SDK bridge;
- the Meta setup choice (Developer Mode only on DAT 1.0);
- a 4-step first-run setup with the VoiceOver vision offer;
- localized prompt defaults;
- the custom AI provider as the default mode;
- Tasker hidden.

`ios-kmp-host.yml` now builds on `macos-15`/Xcode 16.4 (2f03b54d). No iPhone checks were run in this range; see HARDWARE_TEST_CHECKLIST.md (iOS sections).
