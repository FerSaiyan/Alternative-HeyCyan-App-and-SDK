package com.fersaiyan.cyanbridge.shared.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fersaiyan.cyanbridge.shared.appearance.AccentProfile
import com.fersaiyan.cyanbridge.shared.appearance.AccentProfiles
import com.fersaiyan.cyanbridge.shared.appearance.AppearanceSettings
import com.fersaiyan.cyanbridge.shared.appearance.ThemeMode

private val LightBackground = Color(0xFFF8FAFB)
private val LightSurface = Color(0xFFFFFFFF)
private val LightOnSurface = Color(0xFF171C1E)
private val LightOnSurfaceVariant = Color(0xFF40484B)
private val DarkBackground = Color(0xFF0D1114)
private val DarkSurface = Color(0xFF151A1D)
private val DarkOnSurface = Color(0xFFE1E3E4)
private val DarkOnSurfaceVariant = Color(0xFFC1C7C9)

val CyanBridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val CyanBridgeTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

fun resolveDarkTheme(settings: AppearanceSettings, systemInDarkTheme: Boolean): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Builds the curated scheme shared by Android, iOS, and every shared screen. */
fun cyanBridgeColorScheme(
    profile: AccentProfile,
    darkTheme: Boolean,
    highContrast: Boolean,
): ColorScheme {
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(profile.darkPrimaryArgb),
            onPrimary = Color(0xFF002023),
            primaryContainer = Color(profile.darkContainerArgb),
            onPrimaryContainer = Color.White,
            secondary = Color(profile.darkSecondaryArgb),
            onSecondary = Color(0xFF002023),
            secondaryContainer = Color(profile.darkSecondaryContainerArgb),
            onSecondaryContainer = Color.White,
            tertiary = Color(profile.darkTertiaryArgb),
            onTertiary = Color(0xFF002023),
            tertiaryContainer = Color(profile.darkTertiaryContainerArgb),
            onTertiaryContainer = Color.White,
            background = DarkBackground,
            onBackground = DarkOnSurface,
            surface = DarkSurface,
            onSurface = DarkOnSurface,
            surfaceVariant = Color(0xFF202629),
            onSurfaceVariant = DarkOnSurfaceVariant,
            surfaceDim = Color(0xFF0D1114),
            surfaceBright = Color(0xFF343B3E),
            surfaceContainerLowest = Color(0xFF090D0F),
            surfaceContainerLow = Color(0xFF111619),
            surfaceContainer = Color(0xFF181D20),
            surfaceContainerHigh = Color(0xFF202629),
            surfaceContainerHighest = Color(0xFF293033),
            outline = Color(0xFF899294),
            outlineVariant = Color(0xFF3F484A),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
        )
    } else {
        lightColorScheme(
            primary = Color(profile.lightPrimaryArgb),
            onPrimary = Color.White,
            primaryContainer = Color(profile.lightContainerArgb),
            onPrimaryContainer = Color(0xFF001F24),
            secondary = Color(profile.lightSecondaryArgb),
            onSecondary = Color.White,
            secondaryContainer = Color(profile.lightSecondaryContainerArgb),
            onSecondaryContainer = Color(0xFF001F24),
            tertiary = Color(profile.lightTertiaryArgb),
            onTertiary = Color.White,
            tertiaryContainer = Color(profile.lightTertiaryContainerArgb),
            onTertiaryContainer = Color(0xFF001F24),
            background = LightBackground,
            onBackground = LightOnSurface,
            surface = LightSurface,
            onSurface = LightOnSurface,
            surfaceVariant = Color(0xFFE7EBEC),
            onSurfaceVariant = LightOnSurfaceVariant,
            surfaceDim = Color(0xFFD9DEDF),
            surfaceBright = Color.White,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF5F7F8),
            surfaceContainer = Color(0xFFEFF2F3),
            surfaceContainerHigh = Color(0xFFE9EDEE),
            surfaceContainerHighest = Color(0xFFE3E7E8),
            outline = Color(0xFF70797B),
            outlineVariant = Color(0xFFBFC8CA),
            error = Color(0xFFBA1A1A),
            onError = Color.White,
        )
    }
    return if (highContrast) highContrastColorScheme(scheme, darkTheme) else scheme
}

fun highContrastColorScheme(scheme: ColorScheme, darkTheme: Boolean): ColorScheme = if (darkTheme) {
    scheme.copy(
        background = Color.Black,
        surface = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color.White,
        surfaceVariant = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black,
        outline = Color.White,
        outlineVariant = Color.White,
    )
} else {
    scheme.copy(
        background = Color.White,
        surface = Color.White,
        onBackground = Color.Black,
        onSurface = Color.Black,
        onSurfaceVariant = Color.Black,
        surfaceVariant = Color.White,
        surfaceDim = Color.White,
        surfaceBright = Color.White,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color.White,
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Color.White,
        outline = Color.Black,
        outlineVariant = Color.Black,
    )
}

/** Theme wrapper for shared and iOS Compose content, where dynamic color is unavailable. */
@Composable
fun CyanBridgeMaterialTheme(
    settings: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(settings, isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = cyanBridgeColorScheme(
            profile = AccentProfiles.find(settings.accentProfileId),
            darkTheme = darkTheme,
            highContrast = settings.highContrast,
        ),
        typography = CyanBridgeTypography,
        shapes = CyanBridgeShapes,
        content = content,
    )
}
