package com.fersaiyan.cyanbridge.ui.notes

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.data.local.entity.Note
import com.fersaiyan.cyanbridge.data.local.dao.NoteDao
import com.fersaiyan.cyanbridge.data.local.AppDatabase
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NoteDetailState(
    val isLoading: Boolean = false,
    val noteId: Long? = null,
    val title: String = "",
    val summary: String = "",
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val successMessage: String? = null,
    val error: String? = null,
)

class NoteDetailViewModel(
    private val noteId: Long?,
    private val noteDao: NoteDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteDetailState(noteId = noteId))
    val uiState: StateFlow<NoteDetailState> = _uiState.asStateFlow()

    init {
        if (noteId != null && noteId > 0) {
            loadNote(noteId)
        } else {
            _uiState.update { it.copy(isEditing = true) }
        }
    }

    private fun loadNote(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val note = noteDao.getNoteById(id)
                if (note != null) {
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            title = note.title,
                            summary = note.summary,
                            noteId = note.id,
                        )
                    }
                } else {
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            error = "Note not found",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load note",
                    )
                }
            }
        }
    }

    fun setTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun setSummary(summary: String) {
        _uiState.update { it.copy(summary = summary) }
    }

    fun startEditing() {
        _uiState.update { it.copy(isEditing = true) }
    }

    fun saveNote() {
        val state = _uiState.value
        if (state.title.isBlank() && state.summary.isBlank()) {
            _uiState.update { it.copy(error = "Please add a title or content") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val now = System.currentTimeMillis()
                val note = Note(
                    id = state.noteId ?: 0,
                    title = state.title.ifBlank { "Untitled Note" },
                    summary = state.summary,
                    transcript = null,
                    redactedTranscript = null,
                    createdAt = now,
                    updatedAt = now,
                    durationSec = null,
                    deviceClass = null,
                    tags = null,
                )

                if (state.noteId != null && state.noteId > 0) {
                    noteDao.updateNote(note)
                } else {
                    noteDao.insertNote(note)
                }

                _uiState.update { it.copy(isSaving = false, successMessage = "Note saved") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Failed to save note") }
            }
        }
    }

    fun deleteNote() {
        val noteId = _uiState.value.noteId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val note = noteDao.getNoteById(noteId)
                if (note != null) {
                    noteDao.deleteNote(note)
                    _uiState.update { it.copy(isLoading = false, successMessage = "Note deleted") }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Note not found") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to delete note") }
            }
        }
    }

    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    class Factory(
        private val noteId: Long?,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = com.fersaiyan.cyanbridge.ui.MyApplication.database
            return NoteDetailViewModel(noteId, db.noteDao()) as T
        }
    }
}