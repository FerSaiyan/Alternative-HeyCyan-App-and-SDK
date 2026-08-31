package com.fersaiyan.cyanbridge.ui.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.ui.notes.MarkdownNoteEditorScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MarkdownNoteEditorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun markdownToolbarEditsSelectionAndSaveTargetIs48Dp() {
        var body by mutableStateOf(TextFieldValue("hello", TextRange(0, 5)))
        composeRule.setContent {
            CyanBridgeTheme {
                MarkdownNoteEditorScreen(
                    screenTitle = "New note",
                    title = "Greeting",
                    tags = "personal",
                    body = body,
                    sourceLabel = "Created in CyanBridge",
                    isSaving = false,
                    onTitleChange = {},
                    onTagsChange = {},
                    onBodyChange = { body = it },
                    onSave = {},
                    onCopy = {},
                    onShare = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Bold").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals("**hello**", body.text) }
        composeRule.onNodeWithText("Save").assertHeightIsAtLeast(48.dp)
    }
}
