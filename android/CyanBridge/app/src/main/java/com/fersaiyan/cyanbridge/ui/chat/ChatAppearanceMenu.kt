package com.fersaiyan.cyanbridge.ui.chat

import android.content.Intent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import com.fersaiyan.cyanbridge.shared.chat.ChatAppearanceMenuAction

/**
 * Carries out the chat appearance menu: color pickers, the wallpaper picker and resets.
 * Create it while the screen is initialized (it registers the picker); [onChanged] runs after
 * each saved change.
 */
internal class ChatAppearanceMenu(
    private val screen: NavScreen,
    private val onChanged: () -> Unit = {},
) {
    private val pickWallpaper = screen.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                screen.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ChatAppearancePrefs.setWallpaperUri(screen, uri.toString())
            onChanged()
            toast("Chat wallpaper updated")
        }
    }

    fun handle(action: ChatAppearanceMenuAction, onChangeModel: () -> Unit = {}) {
        when (action) {
            ChatAppearanceMenuAction.CHANGE_USER_BUBBLE_COLOR -> pickColor(
                title = "User bubble color",
                current = ChatAppearancePrefs.getUserBubbleColor(screen),
            ) { ChatAppearancePrefs.setUserBubbleColor(screen, it) }

            ChatAppearanceMenuAction.CHANGE_ASSISTANT_BUBBLE_COLOR -> pickColor(
                title = "Assistant bubble color",
                current = ChatAppearancePrefs.getAssistantBubbleColor(screen),
            ) { ChatAppearancePrefs.setAssistantBubbleColor(screen, it) }

            ChatAppearanceMenuAction.CHOOSE_WALLPAPER -> pickWallpaper.launch(arrayOf("image/*"))

            ChatAppearanceMenuAction.REMOVE_WALLPAPER -> {
                ChatAppearancePrefs.clearWallpaper(screen)
                onChanged()
                toast("Wallpaper removed")
            }

            ChatAppearanceMenuAction.RESET_APPEARANCE -> {
                ChatAppearancePrefs.reset(screen)
                onChanged()
                toast("Chat appearance reset")
            }

            ChatAppearanceMenuAction.CHANGE_MODEL -> onChangeModel()
        }
    }

    private fun pickColor(title: String, current: Int, save: (Int) -> Unit) {
        val labels = BUBBLE_COLORS.map { (name, color) -> if (color == current) "$name (Current)" else name }
        AlertDialog.Builder(screen.activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                save(BUBBLE_COLORS[which].second)
                onChanged()
            }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(screen, text, Toast.LENGTH_SHORT).show()

    private companion object {
        val BUBBLE_COLORS = listOf(
            "Cyan" to 0xFF00E5FF.toInt(),
            "Ocean" to 0xFF1F8AFA.toInt(),
            "Forest" to 0xFF2E7D32.toInt(),
            "Amber" to 0xFFFFB300.toInt(),
            "Coral" to 0xFFFF6F61.toInt(),
            "Slate" to 0xFF455A64.toInt(),
            "Rose" to 0xFFE91E63.toInt(),
            "Purple" to 0xFF7E57C2.toInt(),
        )
    }
}
