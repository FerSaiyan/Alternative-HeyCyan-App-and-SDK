package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import com.fersaiyan.cyanbridge.ui.localization.AppLanguage
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences

class WelcomeDestination : NavScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val visionPreferences = visionAssistancePreferences()
        val offerVisionAssistance = VisionAssistancePreferences.shouldOffer(
            visionPreferences,
            screenReaderRunning = ScreenReaderDetector.isRunning(this),
        )

        if (isOnboardingCompleted(this)) {
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
        setContent {
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
                    startActivity(AppRoute.BATTERY_GUIDE.intent(this))
                    finish()
                },
            )
        }
    }

    private fun showVisionAssistanceOffer(visionPreferences: PlatformPreferences) {
        setContent {
            VisionAssistanceScreen(
                screenReaderDetected = true,
                showSetupProgress = false,
                onConfirm = { enabled ->
                    VisionAssistancePreferences.setEnabled(visionPreferences, enabled)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                },
            )
        }
    }

    companion object {
        /**
         * Whether the launcher shows this screen first: setup is not finished, or a screen reader
         * was turned on since (vision assistance is offered once).
         */
        fun isNeeded(context: Context): Boolean =
            !isOnboardingCompleted(context) || VisionAssistancePreferences.shouldOffer(
                context.visionAssistancePreferences(),
                screenReaderRunning = ScreenReaderDetector.isRunning(context),
            )

        private fun isOnboardingCompleted(context: Context): Boolean {
            val prefs = context.getSharedPreferences("cyanbridge_prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("onboarding_completed", false)
        }
    }
}
