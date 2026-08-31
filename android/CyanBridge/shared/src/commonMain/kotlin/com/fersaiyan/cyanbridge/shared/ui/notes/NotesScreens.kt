package com.fersaiyan.cyanbridge.shared.ui.notes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_back
import com.fersaiyan.cyanbridge.shared.generated.resources.action_copy
import com.fersaiyan.cyanbridge.shared.generated.resources.action_share
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_body
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_editor_help
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_save
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_tags
import com.fersaiyan.cyanbridge.shared.generated.resources.notes_title
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun MarkdownNoteEditorScreen(
    screenTitle: String,
    title: String,
    tags: String,
    body: TextFieldValue,
    sourceLabel: String,
    isSaving: Boolean,
    onTitleChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onBodyChange: (TextFieldValue) -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = !isSaving && body.text.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (isSaving) "Saving..." else stringResource(Res.string.notes_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    text = sourceLabel,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(Res.string.notes_title)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = tags,
                onValueChange = onTagsChange,
                label = { Text(stringResource(Res.string.notes_tags)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            MarkdownToolbar(
                enabled = !isSaving,
                value = body,
                onValueChange = onBodyChange,
            )
            OutlinedTextField(
                value = body,
                onValueChange = onBodyChange,
                label = { Text(stringResource(Res.string.notes_body)) },
                supportingText = { Text(stringResource(Res.string.notes_editor_help)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                minLines = 8,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onCopy,
                    enabled = body.text.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.action_copy)) }
                TextButton(
                    onClick = onShare,
                    enabled = body.text.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.action_share)) }
            }
        }
    }
}

@Composable
private fun MarkdownToolbar(
    enabled: Boolean,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EditorToolButton("H1", enabled) { onValueChange(MarkdownEditorActions.prefixCurrentLine(value, "# ")) }
        EditorToolButton("List", enabled) { onValueChange(MarkdownEditorActions.prefixCurrentLine(value, "- ")) }
        EditorToolButton("Task", enabled) { onValueChange(MarkdownEditorActions.prefixCurrentLine(value, "- [ ] ")) }
        EditorToolButton("1.", enabled) { onValueChange(MarkdownEditorActions.prefixCurrentLine(value, "1. ")) }
        EditorToolButton("Bold", enabled) { onValueChange(MarkdownEditorActions.wrap(value, "**", "**", "bold")) }
        EditorToolButton("Italic", enabled) { onValueChange(MarkdownEditorActions.wrap(value, "_", "_", "italic")) }
        EditorToolButton("Code", enabled) { onValueChange(MarkdownEditorActions.wrap(value, "`", "`", "code")) }
        EditorToolButton("Quote", enabled) { onValueChange(MarkdownEditorActions.prefixCurrentLine(value, "> ")) }
        EditorToolButton("Link", enabled) { onValueChange(MarkdownEditorActions.wrap(value, "[", "](https://)", "link text")) }
        EditorToolButton("#tag", enabled) { onValueChange(MarkdownEditorActions.insert(value, "#tag")) }
        EditorToolButton("[[Wiki]]", enabled) { onValueChange(MarkdownEditorActions.wrap(value, "[[", "]]", "note")) }
    }
}

@Composable
private fun EditorToolButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(label) }
}

object MarkdownEditorActions {
    fun wrap(
        value: TextFieldValue,
        prefix: String,
        suffix: String = prefix,
        placeholder: String,
    ): TextFieldValue {
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
        val selected = value.text.substring(start, end)
        val replacementText = selected.ifBlank { placeholder }
        val replacement = prefix + replacementText + suffix
        val newText = value.text.replaceRange(start, end, replacement)
        val selection = if (selected.isBlank()) {
            TextRange(start + prefix.length, start + prefix.length + placeholder.length)
        } else {
            TextRange(start + replacement.length)
        }
        return TextFieldValue(newText, selection)
    }

    fun insert(value: TextFieldValue, insertion: String): TextFieldValue {
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
        val newText = value.text.replaceRange(start, end, insertion)
        return TextFieldValue(newText, TextRange(start + insertion.length))
    }

    fun prefixCurrentLine(value: TextFieldValue, prefix: String): TextFieldValue {
        val cursor = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val lineStart = value.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val newText = value.text.replaceRange(lineStart, lineStart, prefix)
        val newStart = (value.selection.start + prefix.length).coerceAtMost(newText.length)
        val newEnd = (value.selection.end + prefix.length).coerceAtMost(newText.length)
        return TextFieldValue(newText, TextRange(newStart, newEnd))
    }
}
