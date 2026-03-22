package com.fersaiyan.cyanbridge.ui.plugins

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TimeWindow { ALL_TIME, WEEKLY, MONTHLY }

data class PluginCardData(
    val title: String,
    val author: String,
    val description: String,
    val badge: String,
    val downloadsAll: Int,
    val downloadsMonthly: Int,
    val downloadsWeekly: Int,
    val votesAll: Int,
    val votesMonthly: Int,
    val votesWeekly: Int,
    val trendAll: Int,
    val trendMonthly: Int,
    val trendWeekly: Int,
) {
    fun downloads(window: TimeWindow): Int = when (window) {
        TimeWindow.ALL_TIME -> downloadsAll
        TimeWindow.MONTHLY -> downloadsMonthly
        TimeWindow.WEEKLY -> downloadsWeekly
    }

    fun votes(window: TimeWindow): Int = when (window) {
        TimeWindow.ALL_TIME -> votesAll
        TimeWindow.MONTHLY -> votesMonthly
        TimeWindow.WEEKLY -> votesWeekly
    }

    fun trend(window: TimeWindow): Int = when (window) {
        TimeWindow.ALL_TIME -> trendAll
        TimeWindow.MONTHLY -> trendMonthly
        TimeWindow.WEEKLY -> trendWeekly
    }
}

data class PluginsUiState(
    val selectedWindow: TimeWindow = TimeWindow.ALL_TIME,
    val imageAutomationEnabled: Boolean = false,
    val showPublishHelp: Boolean = false,
)

class PluginsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PluginsUiState())
    val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    val pluginPool: List<PluginCardData> = listOf(
        PluginCardData(
            title = "Meeting Spark Notes",
            author = "cyanlabs",
            description = "Builds concise action summaries from captures and chats.",
            badge = "Productivity",
            downloadsAll = 182_400,
            downloadsMonthly = 28_400,
            downloadsWeekly = 7_100,
            votesAll = 21_600,
            votesMonthly = 4_100,
            votesWeekly = 980,
            trendAll = 92,
            trendMonthly = 96,
            trendWeekly = 97,
        ),
        PluginCardData(
            title = "Live Caption Relay",
            author = "captionsmith",
            description = "Streams glasses audio to phone and pushes bilingual captions.",
            badge = "Accessibility",
            downloadsAll = 131_300,
            downloadsMonthly = 24_900,
            downloadsWeekly = 6_900,
            votesAll = 18_500,
            votesMonthly = 3_700,
            votesWeekly = 1_020,
            trendAll = 88,
            trendMonthly = 94,
            trendWeekly = 98,
        ),
        PluginCardData(
            title = "Errand Brain",
            author = "urbanaut",
            description = "Turns quick voice notes into checklist tasks and reminders.",
            badge = "Planner",
            downloadsAll = 98_200,
            downloadsMonthly = 15_600,
            downloadsWeekly = 4_200,
            votesAll = 12_900,
            votesMonthly = 2_100,
            votesWeekly = 610,
            trendAll = 81,
            trendMonthly = 85,
            trendWeekly = 89,
        ),
        PluginCardData(
            title = "Commute Copilot",
            author = "routepilot",
            description = "Summarizes route changes and sends trip status prompts.",
            badge = "Mobility",
            downloadsAll = 87_500,
            downloadsMonthly = 13_900,
            downloadsWeekly = 3_700,
            votesAll = 11_300,
            votesMonthly = 1_900,
            votesWeekly = 520,
            trendAll = 77,
            trendMonthly = 80,
            trendWeekly = 84,
        ),
        PluginCardData(
            title = "Retail Field Scout",
            author = "shelfops",
            description = "Captures shelf notes and auto-tags price/checklist anomalies.",
            badge = "Operations",
            downloadsAll = 74_800,
            downloadsMonthly = 11_100,
            downloadsWeekly = 2_900,
            votesAll = 9_900,
            votesMonthly = 1_600,
            votesWeekly = 430,
            trendAll = 73,
            trendMonthly = 78,
            trendWeekly = 82,
        ),
        PluginCardData(
            title = "Hands-Free Translator",
            author = "polyglot.dev",
            description = "Voice command translation presets for frequent phrases.",
            badge = "Language",
            downloadsAll = 165_000,
            downloadsMonthly = 19_700,
            downloadsWeekly = 4_800,
            votesAll = 23_100,
            votesMonthly = 3_400,
            votesWeekly = 820,
            trendAll = 86,
            trendMonthly = 83,
            trendWeekly = 79,
        ),
    )

    init {
        _uiState.value = PluginsUiState(
            imageAutomationEnabled = CommunityPluginPrefs.isGeminiChatGptImageAutomationEnabled(application),
        )
    }

    fun selectWindow(window: TimeWindow) {
        _uiState.value = _uiState.value.copy(selectedWindow = window)
    }

    fun toggleImageAutomation() {
        val current = _uiState.value.imageAutomationEnabled
        val next = !current
        CommunityPluginPrefs.setGeminiChatGptImageAutomationEnabled(getApplication(), next)
        _uiState.value = _uiState.value.copy(imageAutomationEnabled = next)
    }

    fun showPublishHelp() {
        _uiState.value = _uiState.value.copy(showPublishHelp = true)
    }

    fun dismissPublishHelp() {
        _uiState.value = _uiState.value.copy(showPublishHelp = false)
    }

    fun trendingPlugins(window: TimeWindow): List<PluginCardData> =
        pluginPool.sortedByDescending { it.trend(window) }.take(4)

    fun topVotedPlugins(window: TimeWindow): List<PluginCardData> =
        pluginPool.sortedByDescending { it.votes(window) }.take(4)

    fun topDownloadedPlugins(window: TimeWindow): List<PluginCardData> =
        pluginPool.sortedByDescending { it.downloads(window) }.take(4)

    companion object {
        fun formatCount(value: Int): String = when {
            value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000f)
            value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000f)
            else -> value.toString()
        }

        fun windowLabel(window: TimeWindow): String = when (window) {
            TimeWindow.ALL_TIME -> "all-time"
            TimeWindow.WEEKLY -> "weekly"
            TimeWindow.MONTHLY -> "monthly"
        }
    }
}
