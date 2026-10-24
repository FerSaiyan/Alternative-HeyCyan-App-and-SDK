package com.fersaiyan.cyanbridge.shared.ui.notes

import androidx.compose.ui.text.input.TextFieldValue
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aMissingNoteClosesTheEditor() = runTest {
        val vm = NoteEditorViewModel(FakeStore(isNew = false, stored = null))
        assertEquals(
            listOf(NoteEditorEvent.Message(UiMessage("Note not found")), NoteEditorEvent.Close),
            vm.events.take(2).toList(),
        )
    }

    @Test
    fun savingANewNoteKeepsTheStoredTitleAndBecomesAnEdit() = runTest {
        val store = FakeStore(isNew = true, stored = null)
        val vm = NoteEditorViewModel(store)
        vm.save()
        assertNull(store.saved, "a blank body is not saved")

        vm.body = TextFieldValue("# Groceries")
        vm.save()

        assertEquals("Untitled note", vm.title)
        assertFalse(vm.isNew)
        assertFalse(vm.isSaving)
        assertEquals(NoteEditorEvent.Message(UiMessage("Note saved")), vm.events.first())
    }

    @Test
    fun aRefusedSaveShowsTheStoresReason() = runTest {
        val vm = NoteEditorViewModel(FakeStore(isNew = false, stored = NoteDraft("t", "", "body", NoteSource.OBSIDIAN), refuse = true))
        assertEquals(NoteSource.OBSIDIAN, vm.source)
        vm.save()
        assertEquals(NoteEditorEvent.Message(UiMessage("Reconnect first", long = true)), vm.events.first())
    }

    private class FakeStore(
        override val isNew: Boolean,
        private val stored: NoteDraft?,
        private val refuse: Boolean = false,
    ) : NoteEditorStore {
        var saved: NoteDraft? = null

        override suspend fun load() = stored

        override suspend fun save(draft: NoteDraft): NoteDraft {
            if (refuse) throw NoteStoreUnavailable("Reconnect first")
            saved = draft
            return draft.copy(title = draft.title.ifBlank { "Untitled note" })
        }
    }
}
