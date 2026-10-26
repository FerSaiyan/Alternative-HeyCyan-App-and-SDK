package com.fersaiyan.cyanbridge.ui.nav

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalModelTestDestination
import com.fersaiyan.cyanbridge.bridge.runtimes.evenhub.EvenHubRuntimeDestination
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationsDestination
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
    }

    companion object {
        const val EXTRA_ROUTE = "com.fersaiyan.cyanbridge.nav.ROUTE"
        private const val ACTION_PREFIX = "com.fersaiyan.cyanbridge.nav.OPEN_"

        fun from(intent: Intent?): AppRoute? =
            intent?.getStringExtra(EXTRA_ROUTE)?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
