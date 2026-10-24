package com.fersaiyan.cyanbridge.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

/**
 * Makes a themed [ComposeView] the Activity's only content view.
 * Use this for screens whose state and effects no longer live in an XML View tree.
 */
internal fun AppCompatActivity.setThemedComposeScreen(
    content: @Composable () -> Unit,
): ComposeView = ComposeView(this).also { composeView ->
    setContentView(composeView)
    setThemedComposeContent(composeView, content)
}

internal fun AppCompatActivity.setThemedComposeContent(
    composeView: ComposeView,
    content: @Composable () -> Unit,
) {
    val appearancePreferences = AppearancePreferences(this)
    composeView.setContent {
        val appearance by rememberAppearanceSettings(appearancePreferences)
        CyanBridgeTheme(appearance, content)
    }
}
