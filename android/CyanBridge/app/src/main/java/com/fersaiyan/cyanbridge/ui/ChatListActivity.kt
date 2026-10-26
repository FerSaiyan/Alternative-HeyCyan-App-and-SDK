package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationPrefs
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationsDestination
import com.fersaiyan.cyanbridge.integrations.knowledge.ObsidianMarkdownCodec
import com.fersaiyan.cyanbridge.integrations.knowledge.SafKnowledgeRepository
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsReviewThreadStore
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadSummary
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.notes.NoteSummary
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatAppearanceMenuDialog
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatListEffect
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatListSource
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatListViewModel
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsScreen
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsTab
import com.fersaiyan.cyanbridge.ui.chat.ChatAppearanceMenu
import com.fersaiyan.cyanbridge.ui.chat.chatModelInfo
import com.fersaiyan.cyanbridge.ui.main.openAppDestination
import com.fersaiyan.cyanbridge.ui.main.putChatExtras
import com.fersaiyan.cyanbridge.ui.main.recentChatIntent
import com.fersaiyan.cyanbridge.ui.notes.NoteEditorActivity
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatListActivity : AppCompatActivity() {
    private val viewModel by viewModels<ChatListViewModel> {
        viewModelFactory {
            initializer {
                val initialTab = intent.getStringExtra(EXTRA_INITIAL_TAB)
                    ?.let { runCatching { NotesChatsTab.valueOf(it) }.getOrNull() } ?: NotesChatsTab.CHATS
                ChatListViewModel(StoredChatList(applicationContext), initialTab)
            }
        }
    }
    private val appearanceMenu = ChatAppearanceMenu(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            viewModel.effects.collect { effect ->
                when (effect) {
                    ChatListEffect.ChatDeleted ->
                        Toast.makeText(this@ChatListActivity, getString(R.string.chat_deleted), Toast.LENGTH_SHORT).show()
                    ChatListEffect.PromptLocalModelSetup -> promptLocalModelSetup()
                    ChatListEffect.ChooseNewChat -> showNewChatTypePicker()
                }
            }
        }
        setThemedComposeScreen {
            val vm = viewModel
            NotesChatsScreen(
                selectedTab = vm.selectedTab,
                onTabSelected = { vm.selectedTab = it },
                threads = vm.threads,
                pendingDelete = vm.pendingDelete,
                notes = vm.notes,
                formatTimestamp = { millis ->
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
                },
                onOpenThread = { startActivity(Intent(this, ChatThreadActivity::class.java).putChatExtras(this, it.id)) },
                onRequestDelete = { vm.pendingDelete = it },
                onConfirmDelete = vm::confirmDelete,
                onDismissDelete = { vm.pendingDelete = null },
                onNewChat = vm::newChat,
                onOpenNote = ::openNote,
                onNewNote = { startActivity(Intent(this, NoteEditorActivity::class.java)) },
                onChatAppearance = { vm.appearanceMenuVisible = true },
                onOpenNotesSettings = { startActivity(AppRoute.KNOWLEDGE_INTEGRATIONS.intent(this)) },
                onDestinationSelected = { openAppDestination(it) { recentChatIntent(withDailyReview = true) } },
            )
            if (vm.appearanceMenuVisible) {
                ChatAppearanceMenuDialog(
                    modelOptionLabel = null,
                    onDismissRequest = { vm.appearanceMenuVisible = false },
                    onAction = { action ->
                        vm.appearanceMenuVisible = false
                        appearanceMenu.handle(action)
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun openNote(note: NoteSummary) {
        startActivity(Intent(this, NoteEditorActivity::class.java).apply {
            if (note.source == NoteSource.OBSIDIAN) {
                putExtra(NoteEditorActivity.EXTRA_OBSIDIAN_URI, note.externalId)
                putExtra(NoteEditorActivity.EXTRA_OBSIDIAN_NAME, note.title)
            } else {
                putExtra(NoteEditorActivity.EXTRA_NOTE_ID, note.id)
            }
        })
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

    private fun showNewChatTypePicker() {
        AlertDialog.Builder(this)
            .setTitle("Start a new chat")
            .setItems(arrayOf("Normal chat", "Daily review chat")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ChatThreadActivity::class.java))
                    1 -> showDailyReviewDateChooser()
                }
            }
            .show()
    }

    private fun showDailyReviewDateChooser() {
        val retentionDays = MemoryModeManager.getScreenOcrRetentionDays(this).coerceIn(1, 365)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = noonToday()
        val dateOptions = (0 until retentionDays.coerceAtMost(7)).map { i ->
            fmt.format((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }.time)
        }
        val labels = dateOptions.mapIndexed { i, date ->
            val human = when (i) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> "${i} days ago"
            }
            "$human ($date)"
        }
        val todayDate = fmt.format(cal.time)

        AlertDialog.Builder(this)
            .setTitle("Daily review date")
            .setItems((labels + "Pick another date...").toTypedArray()) { _, which ->
                if (which in dateOptions.indices) {
                    startDailyReviewChat(dateOptions[which], retentionDays)
                } else {
                    showDailyReviewDatePicker(retentionDays)
                }
            }
            .setPositiveButton("Open calendar") { _, _ -> showDailyReviewDatePicker(retentionDays) }
            .setNeutralButton("Today") { _, _ -> startDailyReviewChat(todayDate, retentionDays) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDailyReviewDatePicker(retentionDays: Int) {
        val now = noonToday()
        val min = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(retentionDays - 1)) }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 12, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                startDailyReviewChat(fmt.format(Date(picked.timeInMillis)), retentionDays)
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
        ).apply {
            datePicker.minDate = min.timeInMillis
            datePicker.maxDate = now.timeInMillis
        }.show()
    }

    private fun noonToday(): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
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

    companion object {
        const val EXTRA_INITIAL_TAB = "initial_tab"
    }
}

/** Chats from [ChatStore], notes from Room and the connected Obsidian vault. */
private class StoredChatList(private val context: Context) : ChatListSource {
    override suspend fun threads() = ChatStore.listNonEmptyThreads().map { ChatThreadSummary(it.id, it.title, it.updatedAt) }

    override fun appNotes() = MyApplication.notesRepository.getAllNotes().map { notes ->
        notes.map {
            val meeting = it.transcript != null || it.durationSec != null || it.deviceClass != null
            NoteSummary(it.id, it.title, it.summary, it.createdAt, if (meeting) NoteSource.MEETING else NoteSource.APP)
        }
    }

    override suspend fun externalNotes(): List<NoteSummary> {
        val vault = KnowledgeIntegrationPrefs.obsidianVault(context)
        if (vault == null || !SafKnowledgeRepository.hasPersistedTreePermission(context, vault.permissionTreeUri, writable = false)) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            SafKnowledgeRepository.listObsidianNotes(context, vault.permissionTreeUri, vault.rootDocumentId).mapNotNull { entry ->
                runCatching {
                    val draft = ObsidianMarkdownCodec.parse(entry.name, SafKnowledgeRepository.readObsidianNote(context, entry.uri))
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

    override suspend fun deleteThread(id: String) {
        ChatStore.deleteThread(id)
        DailyFactsReviewThreadStore.remove(context, id)
    }

    override fun modelInfo() = chatModelInfo(context)
}
