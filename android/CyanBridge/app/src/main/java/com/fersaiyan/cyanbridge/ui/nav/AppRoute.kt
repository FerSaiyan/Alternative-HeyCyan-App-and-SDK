package com.fersaiyan.cyanbridge.ui.nav

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalModelTestDestination
import com.fersaiyan.cyanbridge.bridge.runtimes.evenhub.EvenHubRuntimeDestination
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationsDestination
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidSettingsDestination
import com.fersaiyan.cyanbridge.plugins.meetingsparknotes.MeetingSparkNotesSettingsDestination
import com.fersaiyan.cyanbridge.plugins.livecaptionrelay.LiveCaptionRelaySettingsDestination
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorSettingsDestination
import com.fersaiyan.cyanbridge.plugins.errandbrain.ErrandBrainSettingsDestination
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiarySettingsDestination
import com.fersaiyan.cyanbridge.plugins.localagent.LocalAgentSettingsDestination
import com.fersaiyan.cyanbridge.plugins.autoaudio.AutoAudioSettingsDestination
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiarySettingsDestination
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidChatDestination
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationSetupDestination
import com.fersaiyan.cyanbridge.ui.SettingsDestination
import com.fersaiyan.cyanbridge.ui.CommunityPluginsDestination
import com.fersaiyan.cyanbridge.ui.ChatListDestination
import com.fersaiyan.cyanbridge.ui.ChatThreadDestination
import com.fersaiyan.cyanbridge.ui.notes.NoteEditorDestination
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListDestination
import com.fersaiyan.cyanbridge.ui.recordings.SyncedMediaGalleryDestination
import com.fersaiyan.cyanbridge.agent.ProSubscriptionDestination
import com.fersaiyan.cyanbridge.agent.ProSubscriptionSettingsDestination
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureDestination
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveDestination
import com.fersaiyan.cyanbridge.ui.DeviceBindDestination
import com.fersaiyan.cyanbridge.ui.MetaPairingDestination
import com.fersaiyan.cyanbridge.ui.voiceprofile.VoiceEnrollmentDestination
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.ui.MentraBroadcastDestination
import com.fersaiyan.cyanbridge.ui.MentraFirmwareUpdateDestination
import com.fersaiyan.cyanbridge.ui.PublishPluginDestination
import com.fersaiyan.cyanbridge.ui.appearance.AppearanceDestination
import com.fersaiyan.cyanbridge.ui.localagent.DailyFactsDestination
import com.fersaiyan.cyanbridge.ui.localagent.DailySummaryDestination
import com.fersaiyan.cyanbridge.ui.localagent.PendingActionsDestination
import com.fersaiyan.cyanbridge.ui.localagent.ScreenCapturesDestination

/**
 * The screens of the single Activity, each formerly its own Activity.
 *
 * [label] is the old Activity label (screen readers hear it as the pane title), [tab] the bottom
 * bar tab the screen belongs to, [singleTask] the old `launchMode="singleTask"`, and
 * [softInputMode] the old `windowSoftInputMode`.
 */
enum class AppRoute(
    private val labelRes: Int = R.string.app_name,
    private val labelText: String? = null,
    val tab: AppDestination? = null,
    val singleTask: Boolean = false,
    val softInputMode: Int = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED,
) {
    APPEARANCE(labelText = "Appearance"),
    KNOWLEDGE_INTEGRATIONS(labelText = "Obsidian sync"),
    PENDING_ACTIONS(labelText = "Pending actions"),
    DAILY_FACTS(labelText = "Daily facts"),
    SCREEN_CAPTURES(labelText = "Screen captures"),
    DAILY_SUMMARY(labelText = "Daily summary"),
    PUBLISH_PLUGIN(labelText = "Publish Plugin"),
    LOCAL_MODEL_TEST(labelText = "Model performance test"),
    MENTRA_FIRMWARE_UPDATE(labelText = "Mentra Live firmware update"),
    MENTRA_BROADCAST(labelText = "Mentra Live broadcast"),
    EVENHUB_RUNTIME,
    WALKING_AID_SETTINGS(labelText = "Walking Aid Settings"),
    MEETING_SPARK_NOTES_SETTINGS(labelText = "Meeting Spark Notes Settings"),
    LIVE_CAPTION_RELAY_SETTINGS(labelText = "Live Caption Relay Settings"),
    HANDS_FREE_TRANSLATOR_SETTINGS(labelText = "Hands-Free Translator Settings"),
    ERRAND_BRAIN_SETTINGS(labelText = "Errand Brain Settings"),
    AUTO_DIARY_SETTINGS(labelText = "AutoDiary Settings"),
    LOCAL_AGENT_SETTINGS(labelText = "Local Agent Settings"),
    AUTO_AUDIO_SETTINGS(labelText = "Auto Audio Settings"),
    VISUAL_DIARY_SETTINGS(labelText = "Visual Diary Settings"),
    WALKING_AID_CHAT(labelText = "Walking Aid Chat", softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE),
    EXTERNAL_ASSISTANT_SETUP(labelText = "Gemini / ChatGPT automation setup"),
    SETTINGS(labelText = "Settings", tab = AppDestination.SETTINGS),
    COMMUNITY_PLUGINS(labelText = "Community Plugins", tab = AppDestination.PLUGINS),
    CHAT_LIST(labelText = "Chats", tab = AppDestination.CHATS),
    CHAT_THREAD(labelText = "Chat", tab = AppDestination.CHATS, softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE),
    NOTE_EDITOR(labelText = "Note editor", softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE),
    RECORDINGS(labelRes = R.string.nav_transcriptions_recordings, tab = AppDestination.MEDIA),
    SYNCED_MEDIA(labelText = "Synced photos and videos"),
    PRO_SUBSCRIPTION(labelText = "Pro Subscription"),
    PRO_SUBSCRIPTION_SETTINGS(labelText = "Pro Subscription Settings"),
    LOCAL_MODELS(labelText = "Configure Local Models"),
    GEMINI_LIVE(labelText = "Gemini Live Preview"),
    DEVICE_BIND,
    META_PAIRING(labelRes = R.string.meta_pairing_title, singleTask = true),
    VOICE_ENROLLMENT(labelText = "Voice profile"),
    ;

    fun label(context: Context): String = labelText ?: context.getString(labelRes)

    /** Opens this screen in [MainActivity]; from outside an Activity add `FLAG_ACTIVITY_NEW_TASK`. */
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        // The action keeps PendingIntents for different screens apart (extras do not count).
        .setAction(ACTION_PREFIX + name)
        .putExtra(EXTRA_ROUTE, name)

    internal fun createScreen(): NavScreen = when (this) {
        APPEARANCE -> AppearanceDestination()
        KNOWLEDGE_INTEGRATIONS -> KnowledgeIntegrationsDestination()
        PENDING_ACTIONS -> PendingActionsDestination()
        DAILY_FACTS -> DailyFactsDestination()
        SCREEN_CAPTURES -> ScreenCapturesDestination()
        DAILY_SUMMARY -> DailySummaryDestination()
        PUBLISH_PLUGIN -> PublishPluginDestination()
        LOCAL_MODEL_TEST -> LocalModelTestDestination()
        MENTRA_FIRMWARE_UPDATE -> MentraFirmwareUpdateDestination()
        MENTRA_BROADCAST -> MentraBroadcastDestination()
        EVENHUB_RUNTIME -> EvenHubRuntimeDestination()
        WALKING_AID_SETTINGS -> WalkingAidSettingsDestination()
        MEETING_SPARK_NOTES_SETTINGS -> MeetingSparkNotesSettingsDestination()
        LIVE_CAPTION_RELAY_SETTINGS -> LiveCaptionRelaySettingsDestination()
        HANDS_FREE_TRANSLATOR_SETTINGS -> HandsFreeTranslatorSettingsDestination()
        ERRAND_BRAIN_SETTINGS -> ErrandBrainSettingsDestination()
        AUTO_DIARY_SETTINGS -> AutoDiarySettingsDestination()
        LOCAL_AGENT_SETTINGS -> LocalAgentSettingsDestination()
        AUTO_AUDIO_SETTINGS -> AutoAudioSettingsDestination()
        VISUAL_DIARY_SETTINGS -> VisualDiarySettingsDestination()
        WALKING_AID_CHAT -> WalkingAidChatDestination()
        EXTERNAL_ASSISTANT_SETUP -> ExternalAssistantAutomationSetupDestination()
        SETTINGS -> SettingsDestination()
        COMMUNITY_PLUGINS -> CommunityPluginsDestination()
        CHAT_LIST -> ChatListDestination()
        CHAT_THREAD -> ChatThreadDestination()
        NOTE_EDITOR -> NoteEditorDestination()
        RECORDINGS -> RecordingsListDestination()
        SYNCED_MEDIA -> SyncedMediaGalleryDestination()
        PRO_SUBSCRIPTION -> ProSubscriptionDestination()
        PRO_SUBSCRIPTION_SETTINGS -> ProSubscriptionSettingsDestination()
        LOCAL_MODELS -> LocalModelsConfigureDestination()
        GEMINI_LIVE -> GeminiLiveDestination()
        DEVICE_BIND -> DeviceBindDestination()
        META_PAIRING -> MetaPairingDestination()
        VOICE_ENROLLMENT -> VoiceEnrollmentDestination()
    }

    companion object {
        const val EXTRA_ROUTE = "com.fersaiyan.cyanbridge.nav.ROUTE"
        private const val ACTION_PREFIX = "com.fersaiyan.cyanbridge.nav.OPEN_"

        fun from(intent: Intent?): AppRoute? =
            intent?.getStringExtra(EXTRA_ROUTE)?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
