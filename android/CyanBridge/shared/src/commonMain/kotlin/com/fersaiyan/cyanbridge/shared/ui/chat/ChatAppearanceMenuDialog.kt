package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.fersaiyan.cyanbridge.shared.chat.ChatAppearanceMenuAction
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_cancel
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_appearance
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_change_assistant_bubble
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_change_user_bubble
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_choose_wallpaper
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_remove_wallpaper
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_reset_appearance
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/** Compose-owned overflow menu; host callbacks retain preference and picker work. */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun ChatAppearanceMenuDialog(
    modelOptionLabel: String?,
    onDismissRequest: () -> Unit,
    onAction: (ChatAppearanceMenuAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.chat_appearance)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ChatAppearanceMenuItem(
                     label = stringResource(Res.string.chat_change_user_bubble),
                    action = ChatAppearanceMenuAction.CHANGE_USER_BUBBLE_COLOR,
                    onAction = onAction,
                )
                ChatAppearanceMenuItem(
                     label = stringResource(Res.string.chat_change_assistant_bubble),
                    action = ChatAppearanceMenuAction.CHANGE_ASSISTANT_BUBBLE_COLOR,
                    onAction = onAction,
                )
                ChatAppearanceMenuItem(
                     label = stringResource(Res.string.chat_choose_wallpaper),
                    action = ChatAppearanceMenuAction.CHOOSE_WALLPAPER,
                    onAction = onAction,
                )
                ChatAppearanceMenuItem(
                     label = stringResource(Res.string.chat_remove_wallpaper),
                    action = ChatAppearanceMenuAction.REMOVE_WALLPAPER,
                    onAction = onAction,
                )
                ChatAppearanceMenuItem(
                     label = stringResource(Res.string.chat_reset_appearance),
                    action = ChatAppearanceMenuAction.RESET_APPEARANCE,
                    onAction = onAction,
                )
                modelOptionLabel?.let { label ->
                    ChatAppearanceMenuItem(
                        label = label,
                        action = ChatAppearanceMenuAction.CHANGE_MODEL,
                        onAction = onAction,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ChatAppearanceMenuItem(
    label: String,
    action: ChatAppearanceMenuAction,
    onAction: (ChatAppearanceMenuAction) -> Unit,
) {
    TextButton(
        onClick = { onAction(action) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_appearance_action_${action.name}"),
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
    }
}
