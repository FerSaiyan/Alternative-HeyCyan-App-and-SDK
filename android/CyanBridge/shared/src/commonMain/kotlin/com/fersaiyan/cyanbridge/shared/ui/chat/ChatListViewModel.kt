package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.chat.ChatModelInfo
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadSummary
import com.fersaiyan.cyanbridge.shared.notes.NoteSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Chats and notes listed by the Notes & chats screen. */
interface ChatListSource {
    /** Chats with at least one message, newest first. */
    suspend fun threads(): List<ChatThreadSummary>

    /** Notes stored by the app; emits again when they change. */
    fun appNotes(): Flow<List<NoteSummary>>

    /** Notes from a connected external vault (Obsidian). */
    suspend fun externalNotes(): List<NoteSummary> = emptyList()
    suspend fun deleteThread(id: String)
    fun modelInfo(): ChatModelInfo
}

sealed interface ChatListEffect {
    data object ChatDeleted : ChatListEffect

    /** No local model is installed while the Local provider is selected. */
    data object PromptLocalModelSetup : ChatListEffect

    /** Ask which kind of chat to start (Android: normal or daily review). */
    data object ChooseNewChat : ChatListEffect
}

class ChatListViewModel(
    private val source: ChatListSource,
    initialTab: NotesChatsTab = NotesChatsTab.CHATS,
) : ViewModel() {
    var selectedTab by mutableStateOf(initialTab)
    var threads by mutableStateOf<List<ChatThreadSummary>>(emptyList())
        private set
    var pendingDelete by mutableStateOf<ChatThreadSummary?>(null)
    var appearanceMenuVisible by mutableStateOf(false)
    private var appNotes by mutableStateOf<List<NoteSummary>>(emptyList())
    private var externalNotes by mutableStateOf<List<NoteSummary>>(emptyList())

    /** App and external notes together, newest first. */
    val notes: List<NoteSummary> get() = (appNotes + externalNotes).sortedByDescending { it.createdAt }

    private val effectChannel = Channel<ChatListEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var notesJob: Job? = null

    /** Follows the app notes while the screen is visible. */
    fun onStart() {
        notesJob?.cancel()
        notesJob = viewModelScope.launch { source.appNotes().collect { appNotes = it } }
    }

    fun onStop() {
        notesJob?.cancel()
        notesJob = null
    }

    fun refresh() {
        refreshThreads()
        viewModelScope.launch { externalNotes = source.externalNotes() }
    }

    private fun refreshThreads() {
        viewModelScope.launch { threads = source.threads() }
    }

    fun confirmDelete() {
        pendingDelete?.let { thread ->
            viewModelScope.launch {
                source.deleteThread(thread.id)
                effectChannel.send(ChatListEffect.ChatDeleted)
                refreshThreads()
            }
        }
        pendingDelete = null
    }

    fun newChat() {
        effectChannel.trySend(
            if (source.modelInfo().localModelMissing) ChatListEffect.PromptLocalModelSetup else ChatListEffect.ChooseNewChat,
        )
    }
}
