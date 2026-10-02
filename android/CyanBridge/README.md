# CyanBridge Manager (Android)

CyanBridge Manager is the Android app in this repository for smart-glasses pairing,
meeting capture, transcription, summarization, and privacy-first note export.

This app lives under:

- `android/<app-folder>/` (this repository's Android app module)

## What is implemented today

- Multi-thread chat UI backed by local Room persistence (`chat/`, `ui/ChatThreadActivity.kt`).
- Glasses manager with class detection + manual class override (`devices/`, `ui/MainActivity.kt`).
- Meeting capture foreground service with timer and metadata persistence (`audio/MeetingCaptureService.kt`).
- Pluggable transcription service and chunking pipeline (`ai/transcription/`).
- Structured meeting summarization and notes storage (`ai/summarization/`, `notes/`).
- Privacy toggles for transcript storage, redaction, and export behavior (`privacy/`, `ui/SettingsActivity.kt`).
- Accessibility-driven Local Agent for foreground phone control, including structured UI observation, action approval, keyboard submission, safe low-risk skill replay, local task history, opt-in screenshot planning, allowlisted Telegram control, and optional fixed-operation Shizuku recovery (`localagent/`, `plugins/localagent/`).

## Local Agent Inspiration

The local-agent phone-control direction in CyanBridge takes architectural inspiration from `orailnoor/private-agent`, especially its Accessibility-based observe -> choose one action -> execute -> observe loop.

Our implementation remains CyanBridge-native and Kotlin-first, built on top of the app's own local-agent, privacy, and approval systems.

## Module map

- `app/src/main/java/com/fersaiyan/cyanbridge/ui/` - activities, adapters, and UI glue.
- `app/src/main/java/com/fersaiyan/cyanbridge/devices/` - device classes, detection heuristics, gating, profile persistence.
- `app/src/main/java/com/fersaiyan/cyanbridge/audio/` - capture source/timer/prefs and foreground capture service.
- `app/src/main/java/com/fersaiyan/cyanbridge/ai/` - transcription + summarization interfaces and implementations.
- `app/src/main/java/com/fersaiyan/cyanbridge/notes/` - notes repository and note creation from transcripts.
- `app/src/main/java/com/fersaiyan/cyanbridge/privacy/` - redaction and export policy helpers.
- `app/src/main/java/com/fersaiyan/cyanbridge/data/` - Room entities, DAOs, and repository.

## Build and test

Use the Android Studio bundled JDK (Java 17+):

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
```

Useful test commands:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest
```

### Fast local device builds

Gradle enables parallel projects, a 24-worker limit, build/configuration caches,
an 8 GB Gradle heap, and a separate 6 GB Kotlin compiler heap. Smaller machines
can override these settings in `~/.gradle/gradle.properties` or use
`--max-workers=4`.

For ARM64 phones, run from the repository root:

```bash
bash tools/hil/build_debug_fast.sh
```

This puts generated files, native CMake staging, and the project cache under
`~/.cache/cyanbridge/` on the host disk, and builds only ARM64. The final APK
path is printed. Set `CYANBRIDGE_FAST_BUILD_ROOT` to choose an SSD directory,
and `CYANBRIDGE_BUILD_ABI=x86_64` for an emulator. Pass Gradle tasks/options
after the script:

```bash
bash tools/hil/build_debug_fast.sh :app:assembleDebug :app:testDebugUnitTest \
  --tests 'com.fersaiyan.cyanbridge.ota.*'
```

Regular `./gradlew` builds keep their usual output locations. `-PtestAbi` also
limits the native llama runtime to the selected ABI.

### Native llama runtime

The `:llama-runtime` dependency is built automatically with the app. No manual
llama.cpp checkout or `llamaSourceDir` argument is required. CMake downloads
upstream revision `0c1e57098bba43ac29e6e3b677cdceebdd22334f`, verifies its pinned
SHA-256, and builds both llama.cpp and its multimodal `libmtmd` library. The
first build needs Internet access to fetch that archive; subsequent builds
reuse the source and native compilation state in CMake's staging directory.
Gradle installs the pinned NDK/CMake toolchain through the configured Android
SDK when needed.

For development with an existing local checkout, `-PllamaSourceDir=/path/to/llama.cpp`
is an optional override. The fast-build script accepts the equivalent
`LLAMA_SOURCE_DIR` environment variable. This override is useful for offline
development and is not part of the normal build command.

## Privacy defaults (MVP)

- Transcript storage: ON by default; users can opt out in Settings.
- Name redaction in exports: ON by default.
- Full transcript in exports: OFF by default.

These defaults are controlled by `privacy/PrivacyPrefs.kt` and applied through
`privacy/NoteExportFormatter.kt`.

## AI prompts (single source of truth)

CyanBridge keeps **two** prompt bases and composes them per-request:

*   **Default image question** `ai/vision/ImageQuestionPrompt.kt:72` `questionForLanguage` (`Give me a concise description of the image` localized `en/pt/es/de/fr/it/zh/ko/ru`) stored in `ImageQuestionPreferences.kt` `defaultQuestion`. `ImageQuestionPromptResolver.kt:95` `resolve()` for **single-shot** (`CliRelayClient.imageQuery` Pro `3.7 flash` / local Gemma / Tasker) appends strict `ImageQuestionPrompt.kt:86` `Answer only in ${label} (${tag}).` so the one-turn answer locks to the app language. For **Gemini Live** callers use `ImageQuestionPromptResolver.baseQuestion()` (no language lock) and let the Live `systemInstruction` handle language permissively.

*   **Assistant system prompt** `localmodels/settings/LocalGenerationSettings.kt:63` `DEFAULT_SYSTEM_PROMPT` mirrored in `Cyanbridge_website/lib/assistant-prompt.ts:1` `DEFAULT_ASSISTANT_SYSTEM_PROMPT`:
    > You are CyanBridge's assistant for smart glasses. Answer the user's request directly... Use the latest glasses image as visual context when the user refers to what they see.
    Keep this prompt language-agnostic. Per-request language is added only as:
    *   single-shot: `Answer only in X` turn suffix (see above)
    *   Live: `lib/gemini-live.ts:131` `buildLiveSystemInstruction(language, baseImageQuestion, systemPrompt)` -> `You support 97 languages. Respond in the language the user is currently speaking, defaulting to ${language} only when unclear. Switch immediately when the user asks to speak another language.` + `Default image question when user gives no specific question and you have a fresh glasses image: ${baseQuestion}`. Do **not** embed strict `Answer only in` into the Live system (would block switching, observed as "could only speak English").

Both flows read the same bases; Live just composes them permissively so the native-audio model can auto-switch across its 97 supported languages (`ai.google.dev/gemini-api/docs/live-guide#supported-languages`).

## Future agent handoff

- Product scope and chapter-based acceptance gates are in root `AGENTS.md`.
- Working checklist and evidence tracking are in root `MVP_CHECKLIST.md`.
- Android-specific protocol notes for vendor behavior are in `android/AGENTS.md`.

When adding features, keep interfaces pluggable (especially in `ai/` and `audio/`) and
prefer local-first storage + explicit user control for any recording/transcription flow.
