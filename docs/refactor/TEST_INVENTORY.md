# Test inventory (cyan-refactor)

Line counts are per file. CI legend:
- **U** = `:app:testDebugUnitTest`, which runs every JVM test in app/src/test (android-self-hosted.yml).
- **P** = `:shared:portabilityTest`, which runs commonTest + portabilityTest (android-self-hosted.yml and the hil scripts).
- **E** = named in an emulator/HIL CI step.
- **M** = manual procedure documented in docs or tools/hil.
- **—** = not run by CI.

## Size

| Source set | Before (files / lines / @Test) | After |
|---|---|---|
| app/src/test | 179 / 15,921 / 857 | 159 / 14,102 / 729 (incl. 2,406 parked in `refactorparity/`) |
| app/src/androidTest | 65 / 8,337 / 139 | 56 / 7,406 / 124 |
| app/src/androidTestLitertNpu | 4 / 793 / 7 | unchanged (compiled only with `-PlocalLiteRtNpuAarPath`) |
| shared/src/commonTest | 58 / 4,995 / 291 | 47 / 4,155 / 224 |
| shared/src/portabilityTest | 5 / 629 / 12 | unchanged |

## By core feature

| Feature | Tests (lines) | CI | Notes |
|---|---|---|---|
| Meta DAT / pairing | MetaDatLifecycleTest (147), MetaDatSdkMockTest (99), MetaPairingMockFlowTest (239), MetaPairingScreenTest (249), MetaPairingScreenStateTest (205), MetaDatRegistrationSupportTest (110), MetaCameraPermissionCheckTest (42) | E, U | Protected |
| HeyCyan connect / control / photo | HeyCyanThumbnailReceiverTest (170), HeyCyanCaptureOwnerTest (115), NativeGlassesPhotoCaptureTest (192), GlassesAiPhotoCaptureRoutingTest (57), GlassesSessionCoordinatorTest (110), BatteryResponseParserTest (44), DeviceClassifierTest (91) | U | BLE thumbnail framing is byte-level |
| Eyevue | shared EyevueProtocolTest (220), EyevuePeerMatchingTest (21), EyevueSetTimeCalendarTest (40, P); EyevueLiveVideoViewTest (31), EyevueMediaProfileTest (30), EyevueReleaseSafetyTest (58) | P, U | Byte-exact packets |
| MYVU / Mentra / Moyoung | MeizuMyvuConnectionPolicyTest (44), MentraPhotoCaptureTest (24), MoyoungW620ManagerTest (18) | U | Thin (see gaps) |
| MemoMind | shared MemoMindPacketEncoderTest (140) | P | Byte-exact, but the encoder has no production caller |
| TuneBuds | app devices/tunebuds/* (519), shared tunebuds/* (327) | U, P | Owned by the TuneBuds agent; untouched |
| Device gating / dashboard | GlassesManagerGatingMatrixTest (93), GlassesDashboardGatingTest (97), DashboardSessionPolicyTest (243), GlassesDeviceTest (83), GlassesFamilyProfileTest (138), GlassesDashboardPresentationTest (45); GlassesDashboardScreenTest (479, androidTest) | P, — | |
| Media sync / glasses network | GlassesNetworkSessionCoreTest (307), GlassesLinkArbiterTest (196), GlassesWifiErrorPolicyTest (41), GlassesNetworkAddressingTest (95), GlassesWifiNotifyTest (46), HeyCyanP2pPeerMatchingTest (76), AdaptiveP2pSyncTest (98), GlassesMediaSyncHelpersTest (57), OggOpusWrapperTest (127), OggOpusWrapperDigestTest (36), GlassesNetworkOwnershipSourceTest (75) | P, U | |
| OTA | BleDfuManagerTest (183), OtaFirmwareTargetTest (149), OtaHttpServerTest (74), WifiOtaModeResponseTest (27), OtaSectionUiStateTest (87) | U | |
| Live preview / RTSP | GlassesRtspRelayTest (347), RtspWireTest (68), LibVlcLivePreviewLifecycleTest (96), HeyCyanLiveP2pRouteProbeTest (64), HeyCyanLiveP2pConnectionStateTest (36), LivePreviewSourceSafetyTest (33); GlassesRtspRelayDecodeTest (251, androidTest) | U, P, M | |
| AI questions / relay | RelayClientTest (251), RelayErrorClassifierTest (43), RelayAiServicesTest (22), OpenAiCompatibleTest (115), MediaInferenceRoutingTest (56), GlassesAssistantRoutingPolicyTest (43), AssistantSpeechPolicyTest (19), GlassesAssistantTest (133), JsonCompatTest (57); AssistantRequestRouterTest (82), AssistantRequestRouterJevTest (97), AssistantTestReadinessTest (63), ai/image/* (288), ImageQuestionPromptResolverTest (89), RemoteOpenAiClientTest (85), StudioBridgeTest (43), agent/ProSubscription* + SubscriptionCheckoutPolicyTest (466) | P, U, E(Jev) | |
| Gemini Live | ai/live/* 14 classes (1,033): GeminiLiveProtocolTest (210), GeminiLiveControlRouterTest (89), session, vision, socket-failure, audio-hold and speech-activity tests; AudioEmulatorHarnessTest (274) | U, E | ControlRouter is protected (Jev CI) |
| Local agent / Tasker | UiActionCandidateBuilderTest (382), LocalAgentUiControlProtocolTest (187), ai/decision/* (398), GroundedNavigationTest (80), ApprovedEmailUiStepTest (43), EmailGroundedDraftTest (69), LocalAgentActionParserTest (75), LocalAgentDeviceStateTest (101), LocalAgentApprovalCoordinatorTest (53), LocalAgentTelegramProtocolTest (55), LocalAgentSkillStoreTest (53), LocalAgentSafetyPolicyTest (22), LocalAgentRuntimePolicyTest (30), UiActionDecisionStateTest (32); HIL: HilFixtureSmokeTest, TaskerLocalAgentHilTest, LocalAiTaskerChromeHilTest, LocalAgentEmailApprovalHilTest, JevLikeDecisionEmulatorTest | U, E | |
| Audio sessions / TTS | AudioSessionArbiterTest (204), AudioRoutePolicyTest (141), SpeechEnergyTest (51), AudioSessionCoordinatorTest (143), TtsSpeakerTest (127), TtsSpeechStreamTest (73), MultilingualSpeechChunkerTest (219), StreamingSpeechChunkerTest (45), QuestionEndpointTest (46) | P, U | |
| Transcription | ChunkPlannerTest (55), RetryPolicyTest (46), OfflineQuestionTranscriberTest (18); TranscriptionIntegrationTest (82, androidTest) | U | `ai.transcription.*` filter in run_audio_emulator_ci.sh |
| Local models | DeviceCapabilityServiceTest (65), LocalModelFileUtilsTest (76), engine/* (164), provider/* (89), settings/* (152), PromptTemplateRegistryTest (61), LocalMtpBenchmarkPolicyTest (43), shared LocalModelCatalogTest (72), NpuVariantResolverTest (46) | U, P | Device runtimes: M (hil/LiteRt*, NPU set) |
| Plugins | Walking Aid: 10 JVM classes (1,037) + WalkingAidVideoSourceButtonsTest, WalkingAidVideoCaptureLifecycleTest, WalkingAidVisionStackIntegrationTest (E); Meeting notes: MeetingSparkNotesServiceTest, MeetingNoteMarkdownTest, settings test; per-plugin *SettingsActivityTest (8 files, ~870); AutoDiary / VisualDiary / AiTaskerProfile HILs (E) | U, E | |
| DB / persistence | AppDatabaseSchemaTest (227), SharedAppDatabaseTest (416), RoomChatRepositoryTest (107), RoomNotesRepositoryTest (94), ChatStoreTest (47), PersistenceEntityTest (17), VaultCryptoTest (30) | U, P | Protected (schema and upgrade) |
| Settings / UI | Compose UI tests in androidTest/ui/* (~2,050), shared ChatRichTextTest, CyanBridgeThemeTest, CyanBridgeThemeContrastTest, AppearanceModelsTest, NavigationModelsTest, BillingModelsTest; prefs tests (see below) | —, P, U | Compose UI tests run only in the manual `connectedDebugAndroidTest` gate |
| Safety (source scans) | EyevueReleaseSafetyTest, WifiAdbSourceSafetyTest, LivePreviewSourceSafetyTest, GlassesNetworkOwnershipSourceTest | U | Protected |

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
- **Data and safety:** AppDatabaseSchemaTest, SharedAppDatabaseTest, the four source-safety tests above, and the byte-exact protocol tests (Eyevue, MemoMind, Ogg/Opus, RTSP wire, Wi-Fi notify, HeyCyan thumbnail).

## Parked refactor scaffolding (delete after hardware validation)

`app/src/test/.../refactorparity/` holds 2,406 lines of Kotlin; its README carries the delete note.
- RelayLegacyParityTest (314)
- RemoteOpenAiLegacyParityTest (150)
- JsonCompatParityTest (54)
- GeminiLiveClientCharacterizationTest (518)
- `localagentservice/`: LocalAgentRuntimeTest (295), LocalAgentTaskLoopTest (424), LocalAgentScreenReaderTest (204), LocalAgentSpeechOutputTest (171), LocalAgentServiceActionExecutorTest (29), LocalAgentServiceFakes (247). Nothing starts `LocalAgentService` any more, so the production code these cover is unreachable too.

## Core behavior with weak or no test coverage

- **Gemini Live client end to end:** once the parked characterization test goes, only `geminiLive_pcmInjectionDoesNotCrash` exercises `GeminiLiveClient` itself. Its parts (protocol, session state, vision, socket policy) are covered separately.
- **TaskerLocalAgentService task loop:** the live local-agent loop has no JVM test. Only the HILs exercise it, and only when a Tasker target is present.
- **MemoMind:** the GATT/RFCOMM clients and the audio bridge are untested, and the tested packet encoder is unused.
- **Mentra, Moyoung, Meizu MYVU:** one small policy or parse test each, and no connect/control flow test.
- **HeyCyan Wi-Fi media download** (the DataDownload transfer loop): only helpers, addressing and the network session are tested.
- **AI question foreground service orchestration** (record → route → speak): only its parts are tested.
- **Plugin services** (Errand Brain, Live Caption Relay, Hands-Free Translator, Auto Audio): only their settings screens are tested. The translator is also covered by one hermetic emulator test.
- **Compose screens:** UI tests exist but no CI job runs them.

## Deferred: prefs-centric tests

A typed-preferences layer is being built concurrently, so these tests were not edited. They are candidates to revisit once it lands:
- WalkingAidPreferencesTest (229; 29 getter/setter cases, some for unused APIs)
- MemoryModeManagerTest (41)
- AppearancePreferencesTest (43)
- CommunityPluginPrefsTest (44)
- LocalAgentTaskHistoryTest (45)
- PrivacyPrefsTest (39)
- GeminiLiveModePreferencesTest (29)
- LocalMtpSettingsRepositoryTest (86)
- the `firstFrameStateComesFromPreferences` cases in the plugin settings tests
