package com.fersaiyan.cyanbridge.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.chat.ChatMessage
import com.fersaiyan.cyanbridge.chat.ChatRole
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.chat.ChatThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class ChatUiState {
    data object Idle : ChatUiState()
    data object Loading : ChatUiState()
    data class Error(val message: String) : ChatUiState()
}

data class ChatScreenState(
    val currentThread: ChatThread? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val relayHealth: String? = null,
    val currentModel: String = "auto",
)

class ChatViewModel(private val context: Context) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatScreenState())
    val uiState: StateFlow<ChatScreenState> = _uiState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    init {
        loadOrCreateThread()
    }

    private fun loadOrCreateThread() {
        viewModelScope.launch(Dispatchers.IO) {
            val threads = ChatStore.listNonEmptyThreads()
            val thread = if (threads.isNotEmpty()) {
                threads.first()
            } else {
                ChatStore.createThread()
            }
            val messages = ChatStore.listMessages(thread.id)
            val model = ProSubscriptionAiPrefs.getRequestsModel(context)
            _uiState.value = ChatScreenState(
                currentThread = thread,
                messages = messages,
                currentModel = model,
            )
        }
    }

    fun setInputText(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return
        if (_uiState.value.isLoading) return

        _inputText.value = ""
        val thread = _uiState.value.currentThread ?: return

        viewModelScope.launch {
            val userMsg = ChatStore.addMessage(
                chatId = thread.id,
                role = ChatRole.USER,
                content = text,
            )
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + userMsg,
                isLoading = true,
                error = null,
            )

            try {
                val history = _uiState.value.messages.map {
                    mapOf("role" to it.role.name.lowercase(), "content" to it.content)
                } + mapOf("role" to "user", "content" to text)

                val model = _uiState.value.currentModel
                val result = withContext(Dispatchers.IO) {
                    CliRelayClient.chat(
                        context = context,
                        chatId = thread.id,
                        prompt = text,
                        messages = history,
                        modelOverride = if (model == "auto") null else model,
                    )
                }

                result.fold(
                    onSuccess = { reply ->
                        val assistantMsg = ChatStore.addMessage(
                            chatId = thread.id,
                            role = ChatRole.ASSISTANT,
                            content = reply,
                        )
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + assistantMsg,
                            isLoading = false,
                        )
                    },
                    onFailure = { err ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = err.message ?: "Unknown error",
                        )
                    },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to send message",
                )
            }
        }
    }

    fun setModel(model: String) {
        ProSubscriptionAiPrefs.setRequestsModel(context, model)
        _uiState.value = _uiState.value.copy(currentModel = model)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun newChat() {
        viewModelScope.launch(Dispatchers.IO) {
            val thread = ChatStore.createThread()
            _uiState.value = ChatScreenState(
                currentThread = thread,
                messages = emptyList(),
                currentModel = _uiState.value.currentModel,
            )
            _inputText.value = ""
        }
    }

    fun loadThread(thread: ChatThread) {
        viewModelScope.launch(Dispatchers.IO) {
            val messages = ChatStore.listMessages(thread.id)
            _uiState.value = _uiState.value.copy(
                currentThread = thread,
                messages = messages,
                isLoading = false,
                error = null,
            )
            _inputText.value = ""
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(context.applicationContext) as T
        }
    }
}
