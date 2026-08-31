package com.fersaiyan.cyanbridge.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.fersaiyan.cyanbridge.shared.appearance.AccentProfile
import com.fersaiyan.cyanbridge.shared.appearance.AccentProfiles
import com.fersaiyan.cyanbridge.shared.appearance.AppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.theme.CyanBridgeShapes
import com.fersaiyan.cyanbridge.shared.ui.theme.CyanBridgeTypography
import com.fersaiyan.cyanbridge.shared.ui.theme.highContrastColorScheme
import com.fersaiyan.cyanbridge.shared.ui.theme.resolveDarkTheme
import com.fersaiyan.cyanbridge.shared.ui.theme.cyanBridgeColorScheme as sharedCyanBridgeColorScheme

/** Kept as the Android-facing entry point for existing theme callers. */
fun cyanBridgeColorScheme(
    profile: AccentProfile,
    darkTheme: Boolean,
    highContrast: Boolean,
): ColorScheme = sharedCyanBridgeColorScheme(profile, darkTheme, highContrast)

@Composable
fun CyanBridgeTheme(
    settings: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(settings, isSystemInDarkTheme())
    val context = LocalContext.current
    val dynamicColor = settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val baseScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        else -> cyanBridgeColorScheme(
            profile = AccentProfiles.find(settings.accentProfileId),
            darkTheme = darkTheme,
            highContrast = settings.highContrast,
        )
    }
    val colorScheme = if (dynamicColor && settings.highContrast) {
        highContrastColorScheme(baseScheme, darkTheme)
    } else baseScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (context.findActivity() as? ComponentActivity)?.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(
                    lightScrim = colorScheme.background.toArgb(),
                    darkScrim = colorScheme.background.toArgb(),
                    detectDarkMode = { darkTheme },
                ),
                navigationBarStyle = SystemBarStyle.auto(
                    lightScrim = colorScheme.surfaceContainer.toArgb(),
                    darkScrim = colorScheme.surfaceContainer.toArgb(),
                    detectDarkMode = { darkTheme },
                ),
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CyanBridgeTypography,
        shapes = CyanBridgeShapes,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
