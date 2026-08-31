package com.fersaiyan.cyanbridge.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.app.DatePickerDialog
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsReviewThreadStore
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.data.local.entity.Note
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationsActivity
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationPrefs
import com.fersaiyan.cyanbridge.integrations.knowledge.ObsidianMarkdownCodec
import com.fersaiyan.cyanbridge.integrations.knowledge.SafKnowledgeRepository
import com.fersaiyan.cyanbridge.shared.chat.ChatAppearanceMenuAction
import com.fersaiyan.cyanbridge.shared.notes.NoteSummary
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatAppearanceMenuDialog
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsScreen
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsTab
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.chat.ChatAppearancePrefs
import com.fersaiyan.cyanbridge.ui.notes.NoteEditorActivity
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadSummary
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ChatListActivity : AppCompatActivity() {
    private var threads by mutableStateOf<List<ChatThreadSummary>>(emptyList())
    private var notes by mutableStateOf<List<Note>>(emptyList())
    private var pendingDelete by mutableStateOf<ChatThreadSummary?>(null)
    private var chatAppearanceMenuVisible by mutableStateOf(false)
    private var selectedTab by mutableStateOf(NotesChatsTab.CHATS)
    private var obsidianNotes by mutableStateOf<List<NoteSummary>>(emptyList())
    private val uiScope = MainScope()
    private var notesJob: Job? = null

    private val pickWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            ChatAppearancePrefs.setWallpaperUri(this, uri.toString())
            Toast.makeText(this, "Chat wallpaper updated", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearancePreferences = AppearancePreferences(this)
        // Restore last selected tab if provided via intent (deep link from notes)
        intent.getStringExtra(EXTRA_INITIAL_TAB)?.let { raw ->
            selectedTab = runCatching { NotesChatsTab.valueOf(raw) }.getOrDefault(NotesChatsTab.CHATS)
        }
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                NotesChatsScreen(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    threads = threads,
                    pendingDelete = pendingDelete,
                    notes = (
                        notes.map {
                            NoteSummary(
                                id = it.id,
                                title = it.title,
                                summary = it.summary,
                                createdAt = it.createdAt,
                                source = if (it.transcript != null || it.durationSec != null || it.deviceClass != null) {
                                    NoteSource.MEETING
                                } else {
                                    NoteSource.APP
                                },
                            )
                        } + obsidianNotes
                    ).sortedByDescending { it.createdAt },
                    formatTimestamp = { millis ->
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(millis))
                    },
                    onOpenThread = { startActivity(buildOpenChatIntent(it.id)) },
                    onRequestDelete = { pendingDelete = it },
                    onConfirmDelete = {
                        pendingDelete?.let(::deleteChat)
                        pendingDelete = null
                    },
                    onDismissDelete = { pendingDelete = null },
                    onNewChat = {
                        if (isLocalModelsMissingSelection()) {
                            promptLocalModelSetup()
                        } else {
                            showNewChatTypePicker()
                        }
                    },
                    onOpenNote = { note ->
                        startActivity(Intent(this, NoteEditorActivity::class.java).apply {
                            if (note.source == NoteSource.OBSIDIAN) {
                                putExtra(NoteEditorActivity.EXTRA_OBSIDIAN_URI, note.externalId)
                                putExtra(NoteEditorActivity.EXTRA_OBSIDIAN_NAME, note.title)
                            } else {
                                putExtra(NoteEditorActivity.EXTRA_NOTE_ID, note.id)
                            }
                        })
                    },
                    onNewNote = {
                        startActivity(Intent(this, NoteEditorActivity::class.java))
                    },
                    onChatAppearance = ::showChatAppearanceMenu,
                    onOpenNotesSettings = {
                        startActivity(Intent(this, KnowledgeIntegrationsActivity::class.java))
                    },
                    onDestinationSelected = ::navigateTo,
                )
                if (chatAppearanceMenuVisible) {
                    ChatAppearanceMenuDialog(
                        modelOptionLabel = null,
                        onDismissRequest = { chatAppearanceMenuVisible = false },
                        onAction = ::handleChatAppearanceMenuAction,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        notesJob?.cancel()
        notesJob = uiScope.launch {
            MyApplication.notesRepository.getAllNotes().collect { collected ->
                notes = collected
            }
        }
    }

    override fun onStop() {
        super.onStop()
        notesJob?.cancel()
        notesJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        refreshObsidianNotes()
    }

    private fun refreshObsidianNotes() {
        val vault = KnowledgeIntegrationPrefs.obsidianVault(this)
        if (vault == null || !SafKnowledgeRepository.hasPersistedTreePermission(this, vault.permissionTreeUri, writable = false)) {
            obsidianNotes = emptyList()
            return
        }
        uiScope.launch {
            obsidianNotes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SafKnowledgeRepository.listObsidianNotes(
                    context = this@ChatListActivity,
                    treeUri = vault.permissionTreeUri,
                    rootDocumentId = vault.rootDocumentId,
                ).mapNotNull { entry ->
                    runCatching {
                        val markdown = SafKnowledgeRepository.readObsidianNote(this@ChatListActivity, entry.uri)
                        val draft = ObsidianMarkdownCodec.parse(entry.name, markdown)
                        val externalId = entry.uri.toString()
                        NoteSummary(
                            id = externalId.hashCode().toLong(),
                            title = draft.title,
                            summary = draft.body,
                            createdAt = entry.lastModified.takeIf { it > 0 } ?: 0L,
                            source = NoteSource.OBSIDIAN,
                            externalId = externalId,
                        )
                    }.getOrNull()
                }
            }
        }
    }

    private fun deleteChat(thread: ChatThreadSummary) {
        ChatStore.deleteThread(thread.id)
        DailyFactsReviewThreadStore.remove(this, thread.id)
        Toast.makeText(this, getString(R.string.chat_deleted), Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun buildOpenChatIntent(chatId: String): Intent {
        return Intent(this, ChatThreadActivity::class.java).apply {
            putExtra(ChatThreadActivity.EXTRA_CHAT_ID, chatId)

            val cfg = DailyFactsReviewThreadStore.load(this@ChatListActivity, chatId)
            if (cfg != null) {
                putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_REVIEW, true)
                putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_DATE, cfg.date)
                putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_LOOKBACK_DAYS, cfg.lookbackDays)
            }
        }
    }

    private fun refreshList() {
        threads = ChatStore.listNonEmptyThreads().map { thread ->
            ChatThreadSummary(
                id = thread.id,
                title = thread.title,
                updatedAtEpochMillis = thread.updatedAt,
            )
        }
    }

    private fun isLocalModelsMissingSelection(): Boolean {
        val localSelected = when (AutomationPrefs.getProviderType(this)) {
            AgentProviderType.LOCAL_AGENT -> true
            AgentProviderType.PRO_SUBSCRIPTION -> false
            AgentProviderType.TASKER -> AiProviderPrefs.getProvider(this) == AiProviderType.LOCAL_MODELS
        }
        if (!localSelected) return false
        if (RemoteOpenAiPrefs.isActive(this)) return false
        return LocalModelStorageRepository.resolveSelectedModel(this) == null
    }

    private fun promptLocalModelSetup() {
        AlertDialog.Builder(this)
            .setTitle("Install a local model")
            .setMessage("Local Models is selected, but no local model is installed. Configure a model to start a new chat.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Configure") { _, _ ->
                startActivity(Intent(this, LocalModelsConfigureActivity::class.java))
            }
            .show()
    }

    private fun navigateTo(destination: AppDestination) {
        val target = when (destination) {
            AppDestination.GLASSES -> Intent(this, MainActivity::class.java)
            AppDestination.CHATS -> buildRecentChatIntent()
            AppDestination.MEDIA -> Intent(this, com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity::class.java)
            AppDestination.PLUGINS -> Intent(this, CommunityPluginsActivity::class.java)
            AppDestination.SETTINGS -> Intent(this, SettingsActivity::class.java)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(target)
    }

    private fun buildRecentChatIntent(): Intent {
        val last = ChatStore.listNonEmptyThreads().firstOrNull()
        val lastUserAt = last?.let { thread ->
            ChatStore.listMessages(thread.id)
                .lastOrNull { it.role == com.fersaiyan.cyanbridge.shared.chat.ChatRole.USER }
                ?.createdAt
        } ?: 0L
        val openChatId = last?.id?.takeIf {
            lastUserAt > 0L && System.currentTimeMillis() - lastUserAt < 30 * 60 * 1000
        }
        return Intent(this, ChatThreadActivity::class.java).apply {
            if (openChatId != null) {
                putExtra(ChatThreadActivity.EXTRA_CHAT_ID, openChatId)
                DailyFactsReviewThreadStore.load(this@ChatListActivity, openChatId)?.let { config ->
                    putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_REVIEW, true)
                    putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_DATE, config.date)
                    putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_LOOKBACK_DAYS, config.lookbackDays)
                }
            }
        }
    }

    private fun showNewChatTypePicker() {
        val items = arrayOf(
            "Normal chat",
            "Daily review chat",
        )

        AlertDialog.Builder(this)
            .setTitle("Start a new chat")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startNormalChat()
                    1 -> showDailyReviewDateChooser()
                }
            }
            .show()
    }

    private fun startNormalChat() {
        startActivity(Intent(this, ChatThreadActivity::class.java))
    }

    private fun showDailyReviewDateChooser() {
        val retentionDays = MemoryModeManager.getScreenOcrRetentionDays(this).coerceIn(1, 365)
        val maxQuick = retentionDays.coerceAtMost(7)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val dateOptions = ArrayList<String>(maxQuick)
        val labels = ArrayList<String>(maxQuick + 1)
        for (i in 0 until maxQuick) {
            val dayCal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }
            val date = fmt.format(dayCal.time)
            dateOptions += date
            val human = when (i) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> "${i} days ago"
            }
            labels += "$human ($date)"
        }

        val customLabel = "Pick another date..."
        val allLabels = (labels + customLabel).toTypedArray()
        val todayDate = fmt.format(cal.time)

        AlertDialog.Builder(this)
            .setTitle("Daily review date")
            .setItems(allLabels) { _, which ->
                if (which in dateOptions.indices) {
                    startDailyReviewChat(dateOptions[which], retentionDays)
                } else {
                    showDailyReviewDatePicker(retentionDays)
                }
            }
            .setPositiveButton("Open calendar") { _, _ ->
                showDailyReviewDatePicker(retentionDays)
            }
            .setNeutralButton("Today") { _, _ ->
                startDailyReviewChat(todayDate, retentionDays)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDailyReviewDatePicker(retentionDays: Int) {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val min = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(retentionDays - 1)) }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 12, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val date = fmt.format(Date(picked.timeInMillis))
                startDailyReviewChat(date, retentionDays)
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
        ).apply {
            datePicker.minDate = min.timeInMillis
            datePicker.maxDate = now.timeInMillis
        }.show()
    }

    private fun startDailyReviewChat(date: String, lookbackDays: Int) {
        startActivity(
            Intent(this, ChatThreadActivity::class.java)
                .putExtra(ChatThreadActivity.EXTRA_CREATE_THREAD_TITLE, "Daily review ($date)")
                .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_REVIEW, true)
                .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_DATE, date)
                .putExtra(ChatThreadActivity.EXTRA_DAILY_FACTS_LOOKBACK_DAYS, lookbackDays),
        )
    }

    private fun showChatAppearanceMenu() {
        chatAppearanceMenuVisible = true
    }

    private fun handleChatAppearanceMenuAction(action: ChatAppearanceMenuAction) {
        chatAppearanceMenuVisible = false
        when (action) {
            ChatAppearanceMenuAction.CHANGE_USER_BUBBLE_COLOR -> showColorPicker(
                title = "User bubble color",
                current = ChatAppearancePrefs.getUserBubbleColor(this),
            ) { selected ->
                ChatAppearancePrefs.setUserBubbleColor(this, selected)
            }

            ChatAppearanceMenuAction.CHANGE_ASSISTANT_BUBBLE_COLOR -> showColorPicker(
                title = "Assistant bubble color",
                current = ChatAppearancePrefs.getAssistantBubbleColor(this),
            ) { selected ->
                ChatAppearancePrefs.setAssistantBubbleColor(this, selected)
            }

            ChatAppearanceMenuAction.CHOOSE_WALLPAPER -> {
                pickWallpaperLauncher.launch(arrayOf("image/*"))
            }

            ChatAppearanceMenuAction.REMOVE_WALLPAPER -> {
                ChatAppearancePrefs.clearWallpaper(this)
                Toast.makeText(this, "Wallpaper removed", Toast.LENGTH_SHORT).show()
            }

            ChatAppearanceMenuAction.RESET_APPEARANCE -> {
                ChatAppearancePrefs.reset(this)
                Toast.makeText(this, "Chat appearance reset", Toast.LENGTH_SHORT).show()
            }

            ChatAppearanceMenuAction.CHANGE_MODEL -> Unit
        }
    }

    private fun showColorPicker(title: String, current: Int, onPick: (Int) -> Unit) {
        val options = listOf(
            "Cyan" to 0xFF00E5FF.toInt(),
            "Ocean" to 0xFF1F8AFA.toInt(),
            "Forest" to 0xFF2E7D32.toInt(),
            "Amber" to 0xFFFFB300.toInt(),
            "Coral" to 0xFFFF6F61.toInt(),
            "Slate" to 0xFF455A64.toInt(),
            "Rose" to 0xFFE91E63.toInt(),
            "Purple" to 0xFF7E57C2.toInt(),
        )

        val labels = options.map { (name, color) ->
            if (color == current) "$name (Current)" else name
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                onPick(options[which].second)
            }
            .show()
    }

    companion object {
        const val EXTRA_INITIAL_TAB = "initial_tab"
    }
}
