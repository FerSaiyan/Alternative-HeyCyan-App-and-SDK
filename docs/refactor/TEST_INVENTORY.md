# Test inventory (cyan-refactor)

Line counts are per file; "before" is cyan-refactor at f908faf. CI legend:
- **U** = `:app:testDebugUnitTest`, which runs every JVM test in app/src/test (android-self-hosted.yml).
- **P** = `:shared:portabilityTest`, which runs commonTest + portabilityTest (android-self-hosted.yml and the hil scripts).
- **E** = named in an emulator/HIL CI step.
- **M** = manual procedure documented in docs or tools/hil.
- **—** = not run by CI.

## Size

| Source set | Before (files / lines / @Test) | After |
|---|---|---|
| app/src/test | 179 / 15,921 / 857 | 159 / 14,102 / 729 (incl. 2,406 parked in `refactorparity/`); 160 / 14,522 / 735 after merging the TuneBuds session work; 152 / 12,986 / 653 after the dead-code pass (parked `localagentservice/` tests and prefs round-trip tests removed); 152 / 12,871 / 644 after the Telegram removal |
| app/src/androidTest | 65 / 8,337 / 139 | 56 / 7,406 / 124 |
| app/src/androidTestLitertNpu | 4 / 793 / 7 | unchanged (compiled only with `-PlocalLiteRtNpuAarPath`) |
| shared/src/commonTest | 58 / 4,995 / 291 | 47 / 4,155 / 224; 47 / 4,283 / 228 after the TuneBuds merge; 48 / 4,721 / 229 after the shared dashboard controller merge and the MemoMind encoder test's removal |
| shared/src/portabilityTest | 5 / 629 / 12 | unchanged |

## By core feature

| Feature | Tests (lines) | CI | Notes |
|---|---|---|---|
| Meta DAT / pairing | MetaDatLifecycleTest (147), MetaDatSdkMockTest (99), MetaPairingMockFlowTest (239), MetaPairingScreenTest (249), MetaPairingScreenStateTest (205), MetaDatRegistrationSupportTest (110), MetaCameraPermissionCheckTest (42) | E, U | Protected |
| HeyCyan connect / control / photo | HeyCyanThumbnailReceiverTest (170), HeyCyanCaptureOwnerTest (115), NativeGlassesPhotoCaptureTest (192), GlassesAiPhotoCaptureRoutingTest (57), GlassesSessionCoordinatorTest (110), BatteryResponseParserTest (44), DeviceClassifierTest (91) | U | BLE thumbnail framing is byte-level |
| Eyevue | shared EyevueProtocolTest (220), EyevuePeerMatchingTest (21), EyevueSetTimeCalendarTest (40, P); EyevueLiveVideoViewTest (31), EyevueMediaProfileTest (30), EyevueReleaseSafetyTest (58) | P, U | Byte-exact packets |
| MYVU / Mentra / Moyoung | MeizuMyvuConnectionPolicyTest (44), MentraPhotoCaptureTest (24), MoyoungW620ManagerTest (18) | U | Thin (see gaps) |
| TuneBuds | app devices/tunebuds/* (939), shared tunebuds/* (455) | U, P | Owned by the TuneBuds agent; untouched |
| Device gating / dashboard | GlassesManagerGatingMatrixTest (93), GlassesDashboardGatingTest (97), DashboardSessionPolicyTest (243), GlassesDeviceTest (83), GlassesFamilyProfileTest (138), GlassesDashboardPresentationTest (45); GlassesDashboardScreenTest (479, androidTest) | P, — | |
| Media sync / glasses network | GlassesNetworkSessionCoreTest (307), GlassesLinkArbiterTest (196), GlassesWifiErrorPolicyTest (41), GlassesNetworkAddressingTest (95), GlassesWifiNotifyTest (46), HeyCyanP2pPeerMatchingTest (76), AdaptiveP2pSyncTest (98), GlassesMediaSyncHelpersTest (57), OggOpusWrapperTest (127), OggOpusWrapperDigestTest (36), GlassesNetworkOwnershipSourceTest (75) | P, U | |
| OTA | BleDfuManagerTest (183), OtaFirmwareTargetTest (149), OtaHttpServerTest (74), WifiOtaModeResponseTest (27), OtaSectionUiStateTest (87) | U | |
| Live preview / RTSP | GlassesRtspRelayTest (347), RtspWireTest (68), LibVlcLivePreviewLifecycleTest (96), HeyCyanLiveP2pRouteProbeTest (64), HeyCyanLiveP2pConnectionStateTest (36), LivePreviewSourceSafetyTest (33); GlassesRtspRelayDecodeTest (251, androidTest) | U, P, M | |
| AI questions / relay | RelayClientTest (251), RelayErrorClassifierTest (43), RelayAiServicesTest (22), OpenAiCompatibleTest (115), MediaInferenceRoutingTest (56), GlassesAssistantRoutingPolicyTest (43), AssistantSpeechPolicyTest (19), GlassesAssistantTest (133), JsonCompatTest (57); AssistantRequestRouterTest (82), AssistantRequestRouterJevTest (97), AssistantTestReadinessTest (63), ai/image/* (288), ImageQuestionPromptResolverTest (89), RemoteOpenAiClientTest (85), StudioBridgeTest (43), agent/ProSubscription* + SubscriptionCheckoutPolicyTest (466) | P, U, E(Jev) | |
| Gemini Live | ai/live/* 14 classes (1,033): GeminiLiveProtocolTest (210), GeminiLiveControlRouterTest (89), session, vision, socket-failure, audio-hold and speech-activity tests; AudioEmulatorHarnessTest (274) | U, E | ControlRouter is protected (Jev CI) |
| Local agent / Tasker | UiActionCandidateBuilderTest (382), LocalAgentUiControlProtocolTest (187), ai/decision/* (398), GroundedNavigationTest (80), ApprovedEmailUiStepTest (43), EmailGroundedDraftTest (69), LocalAgentActionParserTest (75), LocalAgentDeviceStateTest (62), LocalAgentApprovalCoordinatorTest (53), LocalAgentSkillStoreTest (53), LocalAgentSafetyPolicyTest (22), LocalAgentRuntimePolicyTest (30), UiActionDecisionStateTest (32); HIL: HilFixtureSmokeTest, TaskerLocalAgentHilTest, LocalAiTaskerChromeHilTest, LocalAgentEmailApprovalHilTest, JevLikeDecisionEmulatorTest | U, E | |
| Audio sessions / TTS | AudioSessionArbiterTest (204), AudioRoutePolicyTest (141), SpeechEnergyTest (51), AudioSessionCoordinatorTest (143), TtsSpeakerTest (127), MultilingualSpeechChunkerTest (219), StreamingSpeechChunkerTest (45), QuestionEndpointTest (46) | P, U | |
| Transcription | ChunkPlannerTest (55), RetryPolicyTest (46), OfflineQuestionTranscriberTest (18); TranscriptionIntegrationTest (82, androidTest) | U | `ai.transcription.*` filter in run_audio_emulator_ci.sh |
| Local models | DeviceCapabilityServiceTest (65), LocalModelFileUtilsTest (76), engine/* (164), provider/* (89), settings/* (152), PromptTemplateRegistryTest (61), LocalMtpBenchmarkPolicyTest (43), shared LocalModelCatalogTest (72), NpuVariantResolverTest (46) | U, P | Device runtimes: M (hil/LiteRt*, NPU set) |
| Plugins | Walking Aid: 10 JVM classes (1,037) + WalkingAidVideoSourceButtonsTest, WalkingAidVideoCaptureLifecycleTest, WalkingAidVisionStackIntegrationTest (E); Meeting notes: MeetingSparkNotesServiceTest, MeetingNoteMarkdownTest, settings test; per-plugin *SettingsActivityTest (8 files, ~855); AutoDiary / VisualDiary / AiTaskerProfile HILs (E) | U, E | |
| DB / persistence | AppDatabaseSchemaTest (227), SharedAppDatabaseTest (416), RoomChatRepositoryTest (107), RoomNotesRepositoryTest (94), ChatStoreTest (47), PersistenceEntityTest (17), VaultCryptoTest (30) | U, P | Protected (schema and upgrade) |
| Settings / UI | Compose UI tests in androidTest/ui/* (~2,050), shared ChatRichTextTest, CyanBridgeThemeTest, CyanBridgeThemeContrastTest, AppearanceModelsTest, NavigationModelsTest, BillingModelsTest; prefs tests (see below) | —, P, U | Compose UI tests run only in the manual `connectedDebugAndroidTest` gate |
| Safety (source scans) | EyevueReleaseSafetyTest, WifiAdbSourceSafetyTest, LivePreviewSourceSafetyTest, GlassesNetworkOwnershipSourceTest | U | Protected |
| Architecture guardrails | ArchitectureGuardrailSourceTest (MainActivity line budget, no vendor SDK imports in ui/ outside the ui/main/glasses adapters, no android/java imports in commonMain, getSharedPreferences only in the listed files) | U | Ratchets: lower the budget or drop exceptions as code moves |

## Protected (derived from CI and the owner's list)

These were left untouched, or tidied without dropping any assertion.

- **android-self-hosted.yml:**
  - MetaDatLifecycleTest, MetaDatSdkMockTest, MetaPairingMockFlowTest, MetaPairingScreenTest, WalkingAidVideoSourceButtonsTest, WalkingAidVideoCaptureLifecycleTest.
  - `AudioEmulatorHarnessTest#{assets_AllThreeFixturesExist, meetingTranscriptionFromSeededAsset_hermetic, handsFreeTranslator_fromAssetTranscript, geminiLive_pcmInjectionDoesNotCrash}`.
  - `WalkingAidVisionStackIntegrationTest#realModel_*` (5 methods, via run_walking_aid_model_ci.sh).
  - Gradle tasks: `:app:testDebugUnitTest` and `:shared:portabilityTest`.
- **android-tasker-hil.yml:** HilFixtureSmokeTest, TaskerLocalAgentHilTest, AutoDiaryTaskerHilTest, AiTaskerProfileHilTest, VisualDiaryHeyCyanHilTest, LocalAiTaskerChromeHilTest, LocalAgentEmailApprovalHilTest.
- **jev-like-local-agent.yml:** JevLikeDecisionEmulatorTest, `ai.decision.*`, GeminiLiveControlRouterTest, AssistantRequestRouterTest, AssistantRequestRouterJevTest, UiActionCandidateBuilderTest, LocalAgentUiControlProtocolTest.
- **tools/hil scripts:**
  - run_mock_dat_emulator_ci.sh: MetaPairingScreenStateTest, MetaDatRegistrationSupportTest.
  - run_audio_emulator_ci.sh: package filters `ai.live.*`, `ai.transcription.*`, `plugins.meetingsparknotes.*`.
  - run_pro_live_audio.sh: `AudioEmulatorHarnessTest#geminiLive_real_ifPro_repliesWithRedFlowers`.
  - Also RealDecisionCalibrationEmulatorTest, RealEmbeddingModelEmulatorTest and LocalAiTaskerYouTubeHilTest.
- **Support and fixtures:** HilTestSupport, hil/audio/{FakeGeminiLiveTokenProvider, TestAudioAssets, TestMeetingCaptureSeeder}, androidTest assets (audio, walkingaid, meta).
- **Data and safety:** AppDatabaseSchemaTest, SharedAppDatabaseTest, the four source-safety tests above, and the byte-exact protocol tests (Eyevue, Ogg/Opus, RTSP wire, Wi-Fi notify, HeyCyan thumbnail).

## Parked refactor scaffolding (delete after hardware validation)

`app/src/test/.../refactorparity/` holds 1,036 lines of Kotlin; its README carries the delete note. Its `localagentservice/` tests (1,370 lines) were deleted with `LocalAgentService`, which nothing started.
- RelayLegacyParityTest (314)
- RemoteOpenAiLegacyParityTest (150)
- JsonCompatParityTest (54)
- GeminiLiveClientCharacterizationTest (518)

## Core behavior with weak or no test coverage

- **Gemini Live client end to end:** once the parked characterization test goes, only `geminiLive_pcmInjectionDoesNotCrash` exercises `GeminiLiveClient` itself. Its parts (protocol, session state, vision, socket policy) are covered separately.
- **TaskerLocalAgentService task loop:** the live local-agent loop has no JVM test. Only the HILs exercise it, and only when a Tasker target is present.
- **Mentra, Moyoung, Meizu MYVU:** one small policy or parse test each, and no connect/control flow test.
- **HeyCyan Wi-Fi media download** (the DataDownload transfer loop): only helpers, addressing and the network session are tested.
- **AI question foreground service orchestration** (record → route → speak): only its parts are tested.
- **Plugin services** (Errand Brain, Live Caption Relay, Hands-Free Translator, Auto Audio): only their settings screens are tested. The translator is also covered by one hermetic emulator test.
- **Compose screens:** UI tests exist but no CI job runs them.

## Prefs-centric tests (revisited after the typed preferences landed)

`PrefsInventoryTest` (P) pins every preference file, key, type and default, and `TypedPrefsCompatibilityTest` (U) reads settings written the old way back through it, so plain default and set-then-get cases were removed:
- Removed: MemoryModeManagerTest, AppearancePreferencesTest, PrivacyPrefsTest, 22 of WalkingAidPreferencesTest's 29 cases, CommunityPluginPrefsTest's default case, and GeminiLiveModePreferencesTest (earlier, with its wrapper).
- Kept because they test logic: WalkingAidPreferencesTest's mode fallback, clamping and cloud model override cases; CommunityPluginPrefsTest's select-and-clear rule; LocalAgentTaskHistoryTest (newest-first order); LocalMtpSettingsRepositoryTest (benchmark record with nullable MTP fields); and the plugin settings tests' `firstFrameStateComesFromPreferences` cases, which check that each screen's first frame maps the saved settings into its state.

## Merged from the retired protocol-fixes branch (2026-10-26)

`c8fc4efb` (spoken-question decision model, web answers, follow-up sessions, memory, voice profile, vision assistance) brought:
- **U:** SpokenDecisionParserTest, DecideOncePromptTest, ThresholdedDecisionEngineTest, SpokenMemoryContextTest, SpokenQuestionInstructionTest, CurrentContextInstructionTest, LocalModelLoadAnnouncerTest, ProSubscriptionAudioModelsTest, SpeakerMathTest, VoiceEnrollmentStoreTest, plus new cases in LocalModelsProviderTest and LiteRtLocalInferenceEngineStreamingTest.
- **P:** VisionAssistancePreferencesTest (moved from commonTest to portabilityTest, because it needs the in-memory preference store);  the new keys (`question_routing/*`, `cyanbridge_accessibility/*`, `pro_subscription_ai_prefs/audio_capable_models*`) are pinned in PrefsInventoryTest.
- **androidTest (M, debug build, needs a local Gemma 4 audio model):** GlassesQuestionFlowTest with the `assets/glasses_questions` WAV/JPEG fixtures, QuestionLatencyQuickTest, QuestionPathBenchmarkTest, QuestionPromptProbeTest. No CI job runs them.
- Not taken: the commit's copy of the enum-membership cases in SettingsScreenTest, which the refactor had pruned; only its `visionAssistanceEnabled` default assertion was kept.

## A3: glasses questions out of MainActivity (2026-10-26)

- **Moved U → P:** SpokenDecisionParserTest (72, now `shared/.../assistant`, kotlin.test), with the parser.
- **New P (shared commonTest):** SpokenQuestionFlowTest (188; the decision flow with fake ports: answer and follow-up continuing the conversation, camera without a conversation, search answered in the conversation or the offline sentence, failures and silence spoken, a conversation that cannot continue) and AssistantQuestionPolicyTest (114, table-driven: wake/provider routing, relay photo replies, web answer plans, memory selection and prompt order, follow-up window).
- **Unchanged and still compiling:** GlassesQuestionFlowTest (only the `SpokenConversation` import moved), QuestionLatencyQuickTest, QuestionPathBenchmarkTest (a comment names `AssistantProviderQueries.query`), QuestionPromptProbeTest; they still drive MainActivity's `debugGlassesEvent` and `GlassesQuestionTestHooks`.
- `ArchitectureGuardrailSourceTest`: MainActivity budget 5,069 → 1,860.

## Question audio on the shared session (2026-10-26)

- **Removed U:** TtsSpeechStreamTest (73), with `TtsSpeechStream` (unused; streamed answers go through `StreamingSpeechSessionManager`).
- **New U:** QuestionMicrophoneTest (1 test: another spoken prompt refuses the question with the busy sentence, before any cue, and the question leaves no session behind).
- **Unchanged and still compiling:** GlassesQuestionFlowTest, QuestionLatencyQuickTest, QuestionPathBenchmarkTest, QuestionPromptProbeTest (fixture audio skips the microphone session, as it skipped the route before).
