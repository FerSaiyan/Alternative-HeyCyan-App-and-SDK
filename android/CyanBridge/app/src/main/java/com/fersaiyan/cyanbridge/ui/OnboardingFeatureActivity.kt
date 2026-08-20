package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AgentPrefs
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.shared.ui.onboarding.FeatureOnboardingScreen
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import com.hjq.permissions.OnPermissionCallback

class OnboardingFeatureActivity : AppCompatActivity() {

    private var featureIndex = 0
    private var localAgentAutomationEnabled by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var glassesConnectionPermissionGranted by mutableStateOf(false)

    data class OnboardingFeature(
        val iconRes: Int,
        val titleRes: Int,
        val descriptionRes: Int,
        val detailsRes: Int,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        featureIndex = intent.getIntExtra(EXTRA_FEATURE_INDEX, 0)
        setupFeatureScreen()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
        glassesConnectionPermissionGranted = hasBluetooth(this)
    }

    private fun setupFeatureScreen() {
        val feature = FEATURES.getOrNull(featureIndex) ?: run {
            finishOnboarding()
            return
        }

        val isAccessibilityFeature = featureIndex == SCREEN_MEMORY_FEATURE_INDEX
        localAgentAutomationEnabled = AgentPrefs.isLocalAgentAutomationEnabled(this)
        accessibilityEnabled = hasAccessibilityServicePermission(this)
        glassesConnectionPermissionGranted = hasBluetooth(this)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                FeatureOnboardingScreen(
                    title = getString(feature.titleRes),
                    description = getString(feature.descriptionRes),
                    details = getString(feature.detailsRes),
                    showGlassesConnectionPermission = featureIndex == GLASSES_CONNECTION_FEATURE_INDEX,
                    glassesConnectionPermissionGranted = glassesConnectionPermissionGranted,
                    // CyanBridge uses app-private storage, MediaStore and SAF; All Files Access is not requested.
                    showStoragePermission = false,
                    storagePermissionGranted = true,
                    // This disclosure now refers to AutoInput's accessibility access. CyanBridge itself
                    // no longer declares an AccessibilityService in the Android manifest.
                    showAccessibilityDisclosure = isAccessibilityFeature,
                    showOpenSourceContribution = featureIndex == OPEN_SOURCE_FEATURE_INDEX,
                    accessibilityEnabled = accessibilityEnabled,
                    localAgentAutomationEnabled = localAgentAutomationEnabled,
                    backLabel = getString(
                        if (featureIndex == 0) R.string.onboarding_skip_all else R.string.onboarding_back,
                    ),
                    nextLabel = getString(
                        if (featureIndex == FEATURES.lastIndex) R.string.onboarding_get_started else R.string.onboarding_next,
                    ),
                    onRequestGlassesConnectionPermission = {
                        requestBluetoothPermission(this, OnPermissionCallback { _, allGranted ->
                            glassesConnectionPermissionGranted = allGranted && hasBluetooth(this)
                        })
                    },
                    onRequestStoragePermission = {},
                    onLocalAgentAutomationChange = {
                        localAgentAutomationEnabled = it
                        AgentPrefs.setLocalAgentAutomationEnabled(this, it)
                        if (it) LocalAgentMemoryStore.ensureSeedFiles(this)
                    },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenSourceRepository = {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_REPOSITORY_URL)))
                        }
                    },
                    onBack = {
                        if (featureIndex == 0) skipAllOnboarding() else goToFeature(featureIndex - 1)
                    },
                    onNext = {
                        if (featureIndex == FEATURES.lastIndex) finishOnboarding() else goToFeature(featureIndex + 1)
                    },
                )
            }
        }
    }

    private fun refreshAccessibilityStatus() {
        accessibilityEnabled = hasAccessibilityServicePermission(this)
    }

    private fun goToFeature(index: Int) {
        startActivity(Intent(this, OnboardingFeatureActivity::class.java).apply {
            putExtra(EXTRA_FEATURE_INDEX, index)
        })
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        finish()
    }

    private fun finishOnboarding() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", true)
            .apply()

        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    private fun skipAllOnboarding() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", true)
            .apply()

        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    companion object {
        private const val EXTRA_FEATURE_INDEX = "feature_index"
        private const val PREFS = "cyanbridge_prefs"
        private const val GLASSES_CONNECTION_FEATURE_INDEX = 0
        private const val SCREEN_MEMORY_FEATURE_INDEX = 1
        private const val OPEN_SOURCE_FEATURE_INDEX = 2
        private const val GITHUB_REPOSITORY_URL = "https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK"

        private val FEATURES = listOf(
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_glasses_connection_title,
                descriptionRes = R.string.onboarding_glasses_connection_desc,
                detailsRes = R.string.onboarding_glasses_connection_details,
            ),
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_optional_features_title,
                descriptionRes = R.string.onboarding_optional_features_desc,
                detailsRes = R.string.onboarding_optional_features_details,
            ),
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_open_source_title,
                descriptionRes = R.string.onboarding_open_source_desc,
                detailsRes = R.string.onboarding_open_source_details,
            ),
        )

        fun launchIfNeeded(activity: AppCompatActivity) {
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("onboarding_completed", false)) return
            activity.startActivity(Intent(activity, OnboardingFeatureActivity::class.java))
        }
    }
}
