package com.fersaiyan.cyanbridge.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.fersaiyan.cyanbridge.shared.chat.ChatAttachmentsUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatComposerUiState
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.chat.ChatThread
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadEvent
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadStateReducer
import com.fersaiyan.cyanbridge.shared.chat.ChatThreadUiState
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatThreadScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersMessagesAndRoutesComposerActionsThroughCallbacks() {
        val thread = ChatThread("chat-1", "Trip plans", 1L, 2L)
        val messages = listOf(
            ChatMessage("message-1", "chat-1", ChatRole.USER, "Plan a weekend trip", 1L),
            ChatMessage("message-2", "chat-1", ChatRole.ASSISTANT, "Start with a destination.", 2L),
        )
        var state by mutableStateOf(ChatThreadUiState(thread = thread, messages = messages))
        var primaryActionCount = 0
        var attachActionCount = 0

        composeRule.setContent {
            CyanBridgeTheme {
                ChatThreadScreen(
                    state = state,
                    messages = messages,
                    composer = ChatComposerUiState(isMediaEnabled = true),
                    attachments = ChatAttachmentsUiState(),
                    modelBadge = "Relay model: auto",
                    dailySummaryProgress = null,
                    dailyReviewQueueStatus = null,
                    userBubbleColor = null,
                    assistantBubbleColor = null,
                    wallpaper = null,
                    isThinking = false,
                    onOpenChatList = {},
                    onChatAppearance = {},
                    onComposerTextChanged = { value ->
                        state = ChatThreadStateReducer.reduce(
                            state,
                            ChatThreadEvent.ComposerTextChanged(value),
                        )
                    },
                    onPrimaryAction = { primaryActionCount += 1 },
                    onAttachImage = { attachActionCount += 1 },
                    onRecordAudio = {},
                    onClearAttachments = {},
                    onDestinationSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Trip plans").assertExists()
        composeRule.onNodeWithText("Plan a weekend trip").assertExists()
        composeRule.onNodeWithText("Start with a destination.").assertExists()
        composeRule.onNodeWithTag("chat_input").performTextInput("Find train tickets")
        composeRule.onNodeWithTag("chat_input").assertTextContains("Find train tickets")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.onNodeWithContentDescription("Attach image").performClick()

        composeRule.runOnIdle {
            assertEquals(1, primaryActionCount)
            assertEquals(1, attachActionCount)
        }
    }

    @Test
    fun exposesRecordingAndNavigationControlsWithAccessibleLabels() {
        composeRule.setContent {
            CyanBridgeTheme {
                ChatThreadScreen(
                    state = ChatThreadUiState(),
                    messages = emptyList(),
                    composer = ChatComposerUiState(isMediaEnabled = true),
                    attachments = ChatAttachmentsUiState(
                        label = "Recording voice note",
                        isRecording = true,
                    ),
                    modelBadge = null,
                    dailySummaryProgress = null,
                    dailyReviewQueueStatus = null,
                    userBubbleColor = null,
                    assistantBubbleColor = null,
                    wallpaper = null,
                    isThinking = true,
                    onOpenChatList = {},
                    onChatAppearance = {},
                    onComposerTextChanged = {},
                    onPrimaryAction = {},
                    onAttachImage = {},
                    onRecordAudio = {},
                    onClearAttachments = {},
                    onDestinationSelected = {},
                )
            }
        }

        composeRule.onNodeWithTag("chat_composer").assertExists()
        composeRule.onNodeWithTag("chat_bottom_controls").assertExists()
        composeRule.onNodeWithText("New Chat").assertExists()
        composeRule.onNodeWithText("Notes & Chats").assertExists()
        composeRule.onNodeWithText("Recording voice note").assertExists()
        composeRule.onNodeWithContentDescription("Stop audio recording").assertExists()
        composeRule.onNodeWithContentDescription("Chats list").assertExists()
        composeRule.onNodeWithContentDescription("Chat appearance").assertExists()
    }
}
