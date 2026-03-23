package com.fersaiyan.cyanbridge.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.fersaiyan.cyanbridge.ui.navigation.MainNavScreen
import com.fersaiyan.cyanbridge.ui.navigation.Routes
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

private const val PREFS = "cyanbridge_prefs"
private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

class ComposeMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isOnboarded = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETED, false)

        setContent {
            CyanBridgeTheme {
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
