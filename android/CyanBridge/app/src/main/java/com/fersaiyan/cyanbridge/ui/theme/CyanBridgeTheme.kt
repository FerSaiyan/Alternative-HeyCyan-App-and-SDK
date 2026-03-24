package com.fersaiyan.cyanbridge.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val CyanAccent = Color(0xFF00E5FF)
val CyanAccentDark = Color(0xFF00B8D4)
val CardBackground = Color(0xFF161B22)
val BgDark = Color(0xFF0B0F14)
val TextPrimary = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8B949E)
val Danger = Color(0xFFFF5252)
val SurfaceVariant = Color(0xFF1C2128)

data class ColorPreset(
    val name: String,
    val accent: Color,
    val accentDark: Color,
)

val COLOR_PRESETS = listOf(
    ColorPreset("Cyan", Color(0xFF00E5FF), Color(0xFF00B8D4)),
    ColorPreset("Rose", Color(0xFFFFB6C1), Color(0xFFFF8FAB)),
    ColorPreset("Mint", Color(0xFF98FB98), Color(0xFF66D9A0)),
    ColorPreset("Lavender", Color(0xFFDDA0DD), Color(0xFFC78CC7)),
    ColorPreset("Peach", Color(0xFFFFDAB9), Color(0xFFFFC89A)),
    ColorPreset("Sky", Color(0xFF87CEEB), Color(0xFF6BB5D9)),
)

/** Blend this color toward [other] by [fraction] (0..1). */
fun Color.mix(other: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * f,
        green = green + (other.green - green) * f,
        blue = blue + (other.blue - blue) * f,
        alpha = alpha,
    )
}

fun darkColorScheme(accent: Color, accentDark: Color) = darkColorScheme(
    primary = accent,
    onPrimary = BgDark,
    primaryContainer = CardBackground,
    onPrimaryContainer = accent,
    secondary = accent,
    onSecondary = BgDark,
    secondaryContainer = CardBackground,
    onSecondaryContainer = accent,
    tertiary = accentDark,
    onTertiary = BgDark,
    tertiaryContainer = SurfaceVariant,
    onTertiaryContainer = accent,
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Danger,
    background = BgDark,
    onBackground = TextPrimary,
    surface = BgDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF21262D),
    scrim = Color.Black,
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = BgDark,
    inversePrimary = accentDark,
)

fun lightColorScheme(accent: Color, accentDark: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2EBF2).mix(accent, 0.15f),
    onPrimaryContainer = Color(0xFF00363D),
    secondary = accentDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F7FA).mix(accent, 0.10f),
    onSecondaryContainer = Color(0xFF00363D),
    tertiary = accentDark,
    onTertiary = Color.White,
    background = Color.White.mix(accent, 0.08f),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White.mix(accent, 0.06f),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE7E0EC).mix(accent, 0.08f),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = Color(0xFF79747E),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = accent,
)

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = BgDark,
    primaryContainer = CardBackground,
    onPrimaryContainer = CyanAccent,
    secondary = CyanAccent,
    onSecondary = BgDark,
    secondaryContainer = CardBackground,
    onSecondaryContainer = CyanAccent,
    tertiary = CyanAccentDark,
    onTertiary = BgDark,
    tertiaryContainer = SurfaceVariant,
    onTertiaryContainer = CyanAccent,
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Danger,
    background = BgDark,
    onBackground = TextPrimary,
    surface = BgDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF21262D),
    scrim = Color.Black,
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = BgDark,
    inversePrimary = CyanAccentDark,
)

private val LightColorScheme = lightColorScheme(
    primary = CyanAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2EBF2),
    onPrimaryContainer = Color(0xFF00363D),
    secondary = CyanAccentDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F7FA),
    onSecondaryContainer = Color(0xFF00363D),
    tertiary = CyanAccentDark,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = Color(0xFF79747E),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = CyanAccent,
)

val CyanBridgeTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun CyanBridgeTheme(
    darkTheme: Boolean = true,
    accentIndex: Int = 0,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val preset = COLOR_PRESETS.getOrElse(accentIndex) { COLOR_PRESETS[0] }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(preset.accent, preset.accentDark)
        else -> lightColorScheme(preset.accent, preset.accentDark)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CyanBridgeTypography,
        content = content,
    )
}
