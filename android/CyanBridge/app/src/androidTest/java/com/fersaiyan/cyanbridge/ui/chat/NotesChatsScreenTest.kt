package com.fersaiyan.cyanbridge.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsScreen
import com.fersaiyan.cyanbridge.shared.ui.chat.NotesChatsTab
import com.fersaiyan.cyanbridge.shared.notes.NoteSource
import com.fersaiyan.cyanbridge.shared.notes.NoteSummary
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotesChatsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun switchesTabsAndKeepsPrimaryControlsAtLeast48Dp() {
        var selectedTab by mutableStateOf(NotesChatsTab.CHATS)
        var newNoteClicks = 0
        composeRule.setContent {
            CyanBridgeTheme {
                NotesChatsScreen(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    threads = emptyList(),
                    pendingDelete = null,
                    notes = listOf(
                        NoteSummary(1L, "App note", "Body", 1L, NoteSource.APP),
                        NoteSummary(2L, "Meeting note", "Summary", 2L, NoteSource.MEETING),
                        NoteSummary(3L, "Vault note", "Markdown", 3L, NoteSource.OBSIDIAN, "content://note"),
                    ),
                    formatTimestamp = { "now" },
                    onOpenThread = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                    onNewChat = {},
                    onOpenNote = {},
                    onNewNote = { newNoteClicks++ },
                    onChatAppearance = {},
                    onOpenNotesSettings = {},
                    onDestinationSelected = {},
                )
            }
        }

        composeRule.onNodeWithTag("notes_chats_tab_chats").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("notes_chats_tab_notes").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(NotesChatsTab.NOTES, selectedTab) }
        composeRule.onNodeWithText("Created in CyanBridge").assertExists()
        composeRule.onNodeWithText("Meeting or transcript summary").assertExists()
        composeRule.onNodeWithText("Stored in Obsidian").assertExists()
        composeRule.onNodeWithTag("notes_chats_primary_action").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, newNoteClicks) }
    }
}
