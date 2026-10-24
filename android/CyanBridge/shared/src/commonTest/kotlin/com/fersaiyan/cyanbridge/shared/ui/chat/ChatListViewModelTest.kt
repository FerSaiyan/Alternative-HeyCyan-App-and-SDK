package com.fersaiyan.cyanbridge.shared.ui.chat

import com.fersaiyan.cyanbridge.shared.chat.ChatModelInfo
import com.fersaiyan.cyanbridge.shared.chat.ChatProviderKind
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadSummary
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.notes.NoteSummary
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModelTest {
    private val source = FakeChatListSource()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun appAndVaultNotesAreListedNewestFirstWhileVisible() = runTest {
        val vm = ChatListViewModel(source)
        vm.onStart()
        vm.refresh()
        assertEquals(listOf("vault", "app"), vm.notes.map { it.title })

        vm.onStop()
        source.appNotes.value = listOf(note("later", 9))
        assertEquals(listOf("vault", "app"), vm.notes.map { it.title })
    }

    @Test
    fun confirmingADeleteRemovesTheChatAndReportsIt() = runTest {
        val vm = ChatListViewModel(source)
        vm.refresh()
        vm.pendingDelete = vm.threads.first()
        vm.confirmDelete()

        assertEquals(ChatListEffect.ChatDeleted, vm.effects.first())
        assertEquals(listOf("b"), vm.threads.map { it.id })
        assertNull(vm.pendingDelete)
    }

    @Test
    fun newChatAsksForALocalModelWhenNoneIsInstalled() = runTest {
        val vm = ChatListViewModel(source)
        vm.newChat()
        assertEquals(ChatListEffect.ChooseNewChat, vm.effects.first())

        source.model = ChatModelInfo(ChatProviderKind.LOCAL, null)
        vm.newChat()
        assertEquals(ChatListEffect.PromptLocalModelSetup, vm.effects.first())
    }

    private fun note(title: String, createdAt: Long, source: NoteSource = NoteSource.APP) =
        NoteSummary(id = createdAt, title = title, summary = "", createdAt = createdAt, source = source)

    private inner class FakeChatListSource : ChatListSource {
        val threads = mutableListOf(ChatThreadSummary("a", "A", 2), ChatThreadSummary("b", "B", 1))
        val appNotes = MutableStateFlow(listOf(note("app", 1)))
        var model = ChatModelInfo(ChatProviderKind.RELAY, "auto")

        override suspend fun threads() = threads.toList()
        override fun appNotes() = appNotes
        override suspend fun externalNotes() = listOf(note("vault", 5, NoteSource.OBSIDIAN))
        override suspend fun deleteThread(id: String) {
            threads.removeAll { it.id == id }
        }
        override fun modelInfo() = model
    }
}
