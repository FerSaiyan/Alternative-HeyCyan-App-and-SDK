package com.fersaiyan.cyanbridge.shared.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.vector.ImageVector

fun AppIcon.imageVector(): ImageVector = when (this) {
    AppIcon.Glasses -> Icons.Outlined.DevicesOther
    AppIcon.Chat -> Icons.Outlined.ChatBubbleOutline
    AppIcon.Notes -> Icons.Outlined.Description
    AppIcon.Recordings -> Icons.Outlined.LibraryMusic
    AppIcon.Settings -> Icons.Outlined.Settings
    AppIcon.Plugins -> Icons.Outlined.Extension
    AppIcon.Camera -> Icons.Outlined.CameraAlt
    AppIcon.Video -> Icons.Outlined.Videocam
    AppIcon.Microphone -> Icons.Outlined.Mic
    AppIcon.Battery -> Icons.Outlined.BatteryStd
    AppIcon.Sync -> Icons.Outlined.SwapHoriz
    AppIcon.Model -> Icons.Outlined.SmartToy
    AppIcon.Send -> Icons.AutoMirrored.Outlined.Send
    AppIcon.Appearance -> Icons.Outlined.Palette
    AppIcon.Add -> Icons.Outlined.Add
    AppIcon.Delete -> Icons.Outlined.Delete
    AppIcon.Back -> Icons.AutoMirrored.Outlined.ArrowBack
    AppIcon.More -> Icons.Outlined.MoreVert
    AppIcon.Attachment -> Icons.Outlined.AttachFile
    AppIcon.Stop -> Icons.Outlined.Stop
    AppIcon.Close -> Icons.Outlined.Close
}
