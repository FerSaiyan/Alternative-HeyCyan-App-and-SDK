package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.shared.assistant.GlassesAssistant
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardHost
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDevice
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSession
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// Same options as Android's meeting capture timer: none, 15 min, 1 h, 3 h.
private val MEETING_TIMER_SECONDS = listOf<Long?>(null, 15L * 60L, 60L * 60L, 3L * 60L * 60L)

/**
 * iOS effects of the shared dashboard actions (Android: DashboardController). Controls the iOS
 * host does not have (local agent, OTA, Gemini Live, Wi-Fi ADB, transport permissions) keep the
 * [GlassesDashboardHost] defaults and end in [onActionUnavailable].
 */
internal class IosDashboardHost(
    private val state: MutableStateFlow<GlassesDashboardUiState>,
    private val scope: CoroutineScope,
    private val selectedGlasses: () -> IosGlassesDevice,
    private val assistant: GlassesAssistant,
    private val meetingRecorder: IosMeetingRecorder,
    private val pluginsRuntime: IosPluginsRuntime,
) : GlassesDashboardHost {
    override val selectedDevice: GlassesDevice get() = selectedGlasses()

    override var dashboard: GlassesDashboardUiState
        get() = state.value
        set(value) {
            state.value = value
        }

    override fun updateDashboard(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState) {
        state.value = transform(state.value)
    }

    override fun onBlockedByExclusiveSession(session: GlassesSession) = updateDashboard {
        it.copy(agentLastError = "${session.label.replaceFirstChar { c -> c.uppercase() }} is using the glasses connection.")
    }

    override fun onActionUnavailable(action: GlassesDashboardAction) = updateDashboard {
        it.copy(
            agentLastError = when (action) {
                is GlassesDashboardAction.RequestOtaFirmware,
                GlassesDashboardAction.CancelOta,
                GlassesDashboardAction.DumpOtaInfo,
                GlassesDashboardAction.TestPullOta,
                -> "Firmware updates still require the Android app"
                else -> IOS_CONTROL_NOT_IMPLEMENTED
            },
        )
    }

    /** The iOS shell switches destinations itself. */
    override fun navigate(destination: AppDestination) = Unit

    override val meetingTimerLastIndex: Int get() = MEETING_TIMER_SECONDS.lastIndex

    override fun startMeetingCapture() {
        scope.launch {
            val timer = MEETING_TIMER_SECONDS[state.value.meeting.timerIndex]
            meetingRecorder.start(timer)?.let { error -> updateDashboard { it.copy(agentLastError = error) } }
        }
    }

    override fun stopMeetingCapture() {
        meetingRecorder.stopMeetingCapture()
    }

    override fun runNativePluginShortcut(action: NativePluginShortcutAction) {
        pluginsRuntime.runShortcut(action)
    }

    override fun selectAssistantMode(mode: GlassesAssistantMode) {
        AgentPrefs.LocalAgent.glassesAssistantMode.set(mode.name)
        updateDashboard { it.copy(assistantMode = mode) }
    }

    override fun saveAiWakeWordRoute(route: AiWakeWordRoute) {
        AssistantPrefs.WakeWord.route.set(route)
    }

    /** Unknown values fall back to Clearer. */
    override fun saveImageThumbnailQuality(sdkValue: Int): ImageThumbnailQuality {
        val quality = ImageThumbnailQuality.entries.firstOrNull { it.sdkValue == sdkValue }
            ?: ImageThumbnailQuality.CLEARER
        AssistantPrefs.ImageQuestions.thumbnailQuality.set(quality.sdkValue)
        return quality
    }

    override fun askVoiceQuestion() {
        assistant.askByVoice()
    }

    /** Asks the selected glasses for a photo and listens for the question while it transfers. */
    override fun askImageQuestion() {
        val device = selectedGlasses()
        if (device.canTakeAiPhoto) {
            assistant.startPhotoQuestion { device.requestAiPhoto() }
        } else {
            updateDashboard { it.copy(agentLastError = device.aiPhotoUnavailableMessage) }
        }
    }
}
