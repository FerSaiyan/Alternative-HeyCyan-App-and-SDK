package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.fersaiyan.cyanbridge.ui.navigation.MainNavScreen
import com.fersaiyan.cyanbridge.ui.navigation.Routes
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

private const val PREFS = "cyanbridge_prefs"
private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
private const val THEME_PREFS = "theme_prefs"
private const val KEY_DARK_THEME = "dark_theme"
private const val KEY_ACCENT_INDEX = "accent_color_index"

class ComposeMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val isOnboarded = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

        setContent {
            val themePrefs = remember { getSharedPreferences(THEME_PREFS, MODE_PRIVATE) }
            var isDarkTheme by remember {
                mutableStateOf(themePrefs.getBoolean(KEY_DARK_THEME, true))
            }
            var accentIndex by remember {
                mutableIntStateOf(themePrefs.getInt(KEY_ACCENT_INDEX, 0))
            }

            DisposableEffect(Unit) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                    when (key) {
                        KEY_DARK_THEME -> isDarkTheme = sp.getBoolean(KEY_DARK_THEME, true)
                        KEY_ACCENT_INDEX -> accentIndex = sp.getInt(KEY_ACCENT_INDEX, 0)
                    }
                }
                themePrefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { themePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            CyanBridgeTheme(darkTheme = isDarkTheme, accentIndex = accentIndex) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainNavScreen(
                        startDestination = if (isOnboarded) Routes.GLASSES else Routes.WELCOME,
                    )
                }
            }
        }
    }
}
