package com.fersaiyan.cyanbridge.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.chat.ChatThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryScreenState(
    val threads: List<ChatThread> = emptyList(),
    val filteredThreads: List<ChatThread> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

class HistoryViewModel(
    private val context: Context,
    private val onThreadSelected: (ChatThread) -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryScreenState())
    val uiState: StateFlow<HistoryScreenState> = _uiState.asStateFlow()

    init {
        loadThreads()
    }

    fun loadThreads() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val threads = ChatStore.listThreads()
                val filtered = filterThreads(threads, _uiState.value.searchQuery)
                _uiState.value = _uiState.value.copy(
                    threads = threads,
                    filteredThreads = filtered,
                    isLoading = false,
                    error = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load threads",
                )
            }
        }
    }

    fun setSearchQuery(query: String) {
        val filtered = filterThreads(_uiState.value.threads, query)
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            filteredThreads = filtered,
        )
    }

    fun selectThread(thread: ChatThread) {
        onThreadSelected(thread)
    }

    fun deleteThread(thread: ChatThread) {
        viewModelScope.launch(Dispatchers.IO) {
            ChatStore.deleteThread(thread.id)
            loadThreads()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun filterThreads(threads: List<ChatThread>, query: String): List<ChatThread> {
        return if (query.isBlank()) {
            threads
        } else {
            threads.filter { it.title.contains(query, ignoreCase = true) }
        }
    }

    class Factory(
        private val context: Context,
        private val onThreadSelected: (ChatThread) -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(context, onThreadSelected) as T
        }
    }
}
