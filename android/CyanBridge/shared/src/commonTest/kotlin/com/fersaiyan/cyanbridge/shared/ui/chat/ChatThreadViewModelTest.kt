package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import com.fersaiyan.cyanbridge.shared.chat.ChatComposerPrimaryAction
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatModelInfo
import com.fersaiyan.cyanbridge.shared.chat.ChatProviderKind
import com.fersaiyan.cyanbridge.shared.chat.ChatRepository
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.chat.ChatThread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadViewModelTest {
    private val repository = MemoryChatRepository()
    private val backend = FakeChatBackend()
    private val recorder = FakeRecorder()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ChatThreadViewModel(repository, backend, recorder, now = { 1_000L }).apply {
        openOnce(ChatThreadArgs())
    }

    @Test
    fun firstMessageCreatesTheThreadStoresTheReplyAndTitlesIt() = runTest {
        val vm = viewModel()
        vm.updateComposerText("  what is the capital of France  ")
        vm.onPrimaryAction()

        val thread = repository.threads.single()
        assertEquals(listOf("what is the capital of France", "Paris"), repository.messages.map { it.content })
        assertEquals("Capital of France", thread.title)
        assertEquals(thread.id, vm.chatId)
        assertEquals("", vm.state.composerText)
        assertEquals(listOf("what is the capital of France", "Paris"), vm.state.messages.map { it.content })
        assertFalse(vm.isThinking)
    }

    @Test
    fun missingLocalModelAsksForSetupInsteadOfSending() = runTest {
        backend.model = ChatModelInfo(ChatProviderKind.LOCAL, modelName = null)
        val vm = viewModel()
        assertEquals(ChatComposerPrimaryAction.CONFIGURE_LOCAL_MODEL, vm.composer.primaryAction)
        assertEquals("Local model: none installed", vm.modelBadge)

        vm.updateComposerText("hello")
        vm.onPrimaryAction()

        assertEquals(ChatThreadEffect.PromptLocalModelSetup, vm.effects.first())
        assertTrue(repository.messages.isEmpty())
    }

    @Test
    fun sendingWhileRecordingAttachesTheVoiceNoteToTheMessage() = runTest {
        val vm = viewModel()
        vm.onRecordAudio()
        assertTrue(vm.isRecording)
        assertEquals("recording voice note attached", vm.attachments.label)

        vm.onPrimaryAction()
        // The composer was empty, so the note stays attached until the next send.
        assertEquals("voice note attached", vm.attachments.label)
        vm.onPrimaryAction()

        assertEquals("Please analyze the attached media.\n[Attached voice note]", repository.messages.first().content)
        assertEquals("voice.wav", backend.requests.single().audioPath)
        assertNull(vm.attachments.label)
    }

    @Test
    fun stoppingALocalReplyKeepsTheStreamedText() = runTest {
        backend.model = ChatModelInfo(ChatProviderKind.LOCAL, modelName = "Gemma")
        backend.streamThenCancel = true
        val vm = viewModel()
        vm.updateComposerText("tell me a story")
        vm.onPrimaryAction()

        assertEquals("Once upon", repository.messages.last { it.role == ChatRole.ASSISTANT }.content)
        assertEquals(ChatComposerPrimaryAction.SEND, vm.composer.primaryAction)
        assertEquals("Local model: Gemma (Ready)", vm.modelBadge)
        assertNull(vm.state.streamingAssistantText)
    }

    @Test
    fun threadTitlesAreTrimmedToSixWords() {
        assertEquals("One two three four five six", ChatThreadViewModel.sanitizeThreadTitle("Title: One two three four five six seven.", "x"))
        assertEquals("fallback prompt", ChatThreadViewModel.sanitizeThreadTitle("Demo mode reply: hi", "fallback prompt?"))
    }
}

private class FakeChatBackend : ChatThreadBackend {
    var model = ChatModelInfo(ChatProviderKind.RELAY, "auto")
    var streamThenCancel = false
    val requests = mutableListOf<ChatReplyRequest>()

    override fun modelInfo() = model
    override fun bubbleColors(): Pair<Int?, Int?> = null to null
    override suspend fun wallpaper(): ImageBitmap? = null

    override suspend fun reply(request: ChatReplyRequest, stream: ChatReplyStream?): String {
        requests += request
        if (streamThenCancel) {
            stream?.onToken("Once ")
            stream?.onToken("upon")
            throw kotlinx.coroutines.CancellationException("stopped")
        }
        return "Paris"
    }

    override suspend fun textReply(prompt: String) = "Capital of France"
    override suspend fun cancelGeneration() = Unit
}

private class FakeRecorder : ChatVoiceRecorder {
    override fun hasPermission() = true
    override suspend fun start() = true
    override suspend fun stop(keep: Boolean) = if (keep) "voice.wav" else null
    override fun release() = Unit
}

/** Stores chats like the Android chat store: first user message names a "New chat". */
internal class MemoryChatRepository : ChatRepository {
    val threads = mutableListOf<ChatThread>()
    val messages = mutableListOf<ChatMessage>()

    override suspend fun listThreads() = threads.sortedByDescending { it.updatedAt }
    override suspend fun listNonEmptyThreads() = listThreads().filter { t -> messages.any { it.chatId == t.id } }
    override suspend fun getThread(chatId: String) = threads.firstOrNull { it.id == chatId }

    override suspend fun createThread(title: String?, nowMs: Long) =
        ChatThread("chat-${threads.size + 1}", title ?: "New chat", nowMs, nowMs).also { threads += it }

    override suspend fun listMessages(chatId: String) = messages.filter { it.chatId == chatId }

    override suspend fun addMessage(chatId: String, role: ChatRole, content: String, nowMs: Long) =
        ChatMessage("m${messages.size + 1}", chatId, role, content, nowMs).also { messages += it }

    override suspend fun updateThreadTitle(chatId: String, title: String, nowMs: Long): Boolean {
        val index = threads.indexOfFirst { it.id == chatId }
        if (index < 0 || threads[index].title == title) return false
        threads[index] = threads[index].copy(title = title)
        return true
    }

    override suspend fun deleteThread(chatId: String) {
        threads.removeAll { it.id == chatId }
        messages.removeAll { it.chatId == chatId }
    }

    override suspend fun clearAll() {
        threads.clear()
        messages.clear()
    }
}
