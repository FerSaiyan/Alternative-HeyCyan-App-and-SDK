package com.fersaiyan.cyanbridge.shared.appearance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.chat.ChatThread
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadEvent
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadStateReducer
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadUiState

class AppearanceModelsTest {
    @Test
    fun defaultsRemainStableAcrossPlatforms() {
        assertEquals(ThemeMode.SYSTEM, AppearanceSettings().themeMode)
        assertEquals(AccentProfiles.CYAN_ID, AppearanceSettings().accentProfileId)
    }

    @Test
    fun accentIdsAreStableAndUnique() {
        val ids = AccentProfiles.all.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(AccentProfiles.all.all { it.label.isNotBlank() })
    }

    @Test
    fun unknownAccentFallsBackToCyan() {
        assertEquals(AccentProfiles.CYAN_ID, AccentProfiles.find("missing").id)
    }

    @Test
    fun chatThreadReducerKeepsStreamingRepliesOutOfPersistence() {
        val message = ChatMessage("message-1", "chat-1", ChatRole.USER, "Hello", 1234L)
        val thread = ChatThread("chat-1", "Hello", 1000L, 1234L)
        val loaded = ChatThreadStateReducer.reduce(
            ChatThreadUiState(),
            ChatThreadEvent.Loaded(thread, listOf(message)),
        )
        val streaming = ChatThreadStateReducer.reduce(
            loaded,
            ChatThreadEvent.GenerationStarted("Loading"),
        )
        val updated = ChatThreadStateReducer.reduce(
            streaming,
            ChatThreadEvent.StreamUpdated("Partial reply"),
        )
        val renamed = ChatThreadStateReducer.reduce(
            updated,
            ChatThreadEvent.ThreadChanged(thread.copy(title = "Renamed chat")),
        )

        assertEquals(1, renamed.messages.size)
        assertEquals("Renamed chat", renamed.thread?.title)
        assertEquals("Partial reply", renamed.streamingAssistantText)
        assertEquals(2, ChatThreadStateReducer.visibleMessages(renamed.messages, "chat-1", renamed.streamingAssistantText, 1500L).size)
    }
}
