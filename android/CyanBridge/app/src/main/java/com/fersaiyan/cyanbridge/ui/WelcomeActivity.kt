package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.accessibility.VisionAssistancePreferences
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingLanguageOption
import com.fersaiyan.cyanbridge.shared.ui.onboarding.VisionAssistanceScreen
import com.fersaiyan.cyanbridge.shared.ui.onboarding.WelcomeScreen
import com.fersaiyan.cyanbridge.ui.accessibility.ScreenReaderDetector
import com.fersaiyan.cyanbridge.ui.accessibility.visionAssistancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.localization.AppLanguage
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val visionPreferences = visionAssistancePreferences()
        val offerVisionAssistance = VisionAssistancePreferences.shouldOffer(
            visionPreferences,
            screenReaderRunning = ScreenReaderDetector.isRunning(this),
        )

        if (isOnboardingCompleted()) {
            if (offerVisionAssistance) {
                // A screen reader was turned on after onboarding: offer vision assistance once.
                showVisionAssistanceOffer(visionPreferences)
            } else {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            return
        }

        var showVisionStep by mutableStateOf(offerVisionAssistance)
        val storedLanguage = AppLanguagePreferences.selected(this)
        var selectedLanguageId by mutableStateOf(storedLanguage.name)
        var languageSelectionComplete by mutableStateOf(true)
        val languageOptions = AppLanguage.entries.map { language ->
            OnboardingLanguageOption(
                id = language.name,
                label = language.displayName(this),
            )
        }
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                if (showVisionStep) {
                    VisionAssistanceScreen(
                        screenReaderDetected = true,
                        showSetupProgress = true,
                        onConfirm = { enabled ->
                            VisionAssistancePreferences.setEnabled(visionPreferences, enabled)
                            showVisionStep = false
                        },
                    )
                } else WelcomeScreen(
                    languageOptions = languageOptions,
                    selectedLanguageId = selectedLanguageId,
                    languageSelectionComplete = languageSelectionComplete,
                    onLanguageSelected = { option ->
                        val language = AppLanguage.fromStored(option.id)
                        AppLanguagePreferences.select(this, language)
                        selectedLanguageId = language.name
                        languageSelectionComplete = true
                    },
                    onStartSetup = {
                        if (!AppLanguagePreferences.hasUserSelectedLanguage(this)) {
                            AppLanguagePreferences.select(this, AppLanguage.fromStored(selectedLanguageId))
                        }
                        startActivity(Intent(this, BatteryOptimizationGuideActivity::class.java))
                        overridePendingTransition(R.anim.onboarding_fade_in, R.anim.onboarding_fade_out)
                        finish()
                    },
                )
            }
        }
    }

    private fun showVisionAssistanceOffer(visionPreferences: PlatformPreferences) {
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                VisionAssistanceScreen(
                    screenReaderDetected = true,
                    showSetupProgress = false,
                    onConfirm = { enabled ->
                        VisionAssistancePreferences.setEnabled(visionPreferences, enabled)
                        startActivity(Intent(this, MainActivity::class.java))
                        overridePendingTransition(R.anim.onboarding_fade_in, R.anim.onboarding_fade_out)
                        finish()
                    },
                )
            }
        }
    }

    private fun isOnboardingCompleted(): Boolean {
        val prefs = getSharedPreferences("cyanbridge_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("onboarding_completed", false)
    }
}
