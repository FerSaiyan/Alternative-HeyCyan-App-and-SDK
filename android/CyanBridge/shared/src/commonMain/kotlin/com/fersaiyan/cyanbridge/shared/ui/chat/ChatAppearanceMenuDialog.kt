package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.chat.ChatAppearanceMenuAction
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_cancel
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_appearance
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_change_assistant_bubble
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_change_user_bubble
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_choose_wallpaper
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_remove_wallpaper
import com.fersaiyan.cyanbridge.shared.generated.resources.chat_reset_appearance
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
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
        icon = {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AppIcon.Appearance.imageVector(),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
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
    val icon = when (action) {
        ChatAppearanceMenuAction.CHANGE_USER_BUBBLE_COLOR -> AppIcon.Appearance
        ChatAppearanceMenuAction.CHANGE_ASSISTANT_BUBBLE_COLOR -> AppIcon.Chat
        ChatAppearanceMenuAction.CHOOSE_WALLPAPER -> AppIcon.Camera
        ChatAppearanceMenuAction.REMOVE_WALLPAPER -> AppIcon.Close
        ChatAppearanceMenuAction.RESET_APPEARANCE -> AppIcon.Sync
        ChatAppearanceMenuAction.CHANGE_MODEL -> AppIcon.Model
    }
    TextButton(
        onClick = { onAction(action) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_appearance_action_${action.name}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon.imageVector(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = label,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
