package com.fersaiyan.cyanbridge.ui.notes

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.data.local.entity.Note
import com.fersaiyan.cyanbridge.notes.NotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

data class NotesListState(
    val isLoading: Boolean = true,
    val notes: List<Note> = emptyList(),
    val error: String? = null,
)

class NotesListViewModel(
    private val notesRepository: NotesRepository,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesListState())
    val uiState: StateFlow<NotesListState> = _uiState.asStateFlow()

    init {
        loadNotes()
    }

    private fun loadNotes() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                notesRepository.getAllNotes().collectLatest { notes ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            notes = notes.sortedByDescending { it.updatedAt },
                            error = null,
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load notes",
                    )
                }
            }
        }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            try {
                val db = com.fersaiyan.cyanbridge.ui.MyApplication.database
                db.noteDao().deleteNote(note)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete note: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NotesListViewModel(
                com.fersaiyan.cyanbridge.ui.MyApplication.notesRepository,
                context,
            ) as T
        }
    }
}