package com.fersaiyan.cyanbridge.shared.ui.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class NoteDraft(val title: String, val tags: String, val body: String, val source: NoteSource = NoteSource.APP)

/** Where the edited note lives (the app database, an Obsidian vault...). */
interface NoteEditorStore {
    /** True for a note that does not exist yet; [load] is not called for it. */
    val isNew: Boolean
    val savedMessage: String get() = "Note saved"

    /** The stored note, or null when it no longer exists. Throws when it cannot be read. */
    suspend fun load(): NoteDraft?

    /** Saves [draft] and returns the title and tags as stored. [NoteStoreUnavailable] explains a refusal. */
    suspend fun save(draft: NoteDraft): NoteDraft
}

class NoteStoreUnavailable(message: String) : Exception(message)

sealed interface NoteEditorEvent {
    data class Message(val message: UiMessage) : NoteEditorEvent
    data object Saved : NoteEditorEvent
    data object Close : NoteEditorEvent
}

class NoteEditorViewModel(private val store: NoteEditorStore) : ViewModel() {
    var isNew by mutableStateOf(store.isNew)
        private set
    var title by mutableStateOf("")
    var tags by mutableStateOf("")
    var body by mutableStateOf(TextFieldValue(""))
    var source by mutableStateOf(NoteSource.APP)
        private set
    var isSaving by mutableStateOf(false)
        private set

    private val eventChannel = Channel<NoteEditorEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        if (!store.isNew) {
            viewModelScope.launch {
                runCatching { store.load() }
                    .onSuccess { draft ->
                        if (draft == null) {
                            notify("Note not found")
                            eventChannel.send(NoteEditorEvent.Close)
                        } else {
                            title = draft.title
                            tags = draft.tags
                            body = TextFieldValue(draft.body)
                            source = draft.source
                        }
                    }
                    .onFailure {
                        notify("Could not open note: ${it.message}", long = true)
                        eventChannel.send(NoteEditorEvent.Close)
                    }
            }
        }
    }

    fun save() {
        if (body.text.isBlank() || isSaving) return
        isSaving = true
        viewModelScope.launch {
            runCatching { store.save(NoteDraft(title, tags, body.text, source)) }
                .onSuccess { saved ->
                    title = saved.title
                    tags = saved.tags
                    isNew = false
                    notify(store.savedMessage)
                    eventChannel.send(NoteEditorEvent.Saved)
                }
                .onFailure {
                    notify(if (it is NoteStoreUnavailable) it.message.orEmpty() else "Could not save note: ${it.message}", long = true)
                }
            isSaving = false
        }
    }

    private suspend fun notify(text: String, long: Boolean = false) =
        eventChannel.send(NoteEditorEvent.Message(UiMessage(text, long)))
}
