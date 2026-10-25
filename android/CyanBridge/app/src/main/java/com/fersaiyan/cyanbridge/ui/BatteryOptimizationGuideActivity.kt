package com.fersaiyan.cyanbridge.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.prefs.store
import com.fersaiyan.cyanbridge.shared.ui.battery.BatteryOptimizationGuideViewModel
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.onboarding.BatteryOptimizationGuideScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

class BatteryOptimizationGuideActivity : AppCompatActivity() {
    private val viewModel by viewModels<BatteryOptimizationGuideViewModel> {
        viewModelFactory {
            initializer {
                val context = applicationContext
                BatteryOptimizationGuideViewModel(AppPrefs.Main.store(context)) { isBatteryOptimizationIgnored(context) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.refresh()
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                BatteryOptimizationGuideScreen(
                    optimizationIgnored = viewModel.optimizationIgnored,
                    onDisableOptimization = ::openDisableBatteryOptimizationFlow,
                    onOpenAppInfo = ::openAppInfo,
                    onOpenOptimizationList = ::openBatteryOptimizationList,
                    onContinue = { navigateToNext(viewModel.onContinue()) },
                    onRemindLater = { navigateToNext(viewModel.onRemindLater()) },
                    onDontShowAgain = { navigateToNext(viewModel.onDontShowAgain()) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun navigateToNext(onboardingCompleted: Boolean) {
        if (onboardingCompleted) {
            finish()
            return
        }
        // After battery optimization, continue to feature onboarding screens
        startActivity(Intent(this, OnboardingFeatureActivity::class.java))
        overridePendingTransition(R.anim.onboarding_fade_in, R.anim.onboarding_fade_out)
        finish()
    }

    private fun openDisableBatteryOptimizationFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, getString(R.string.battery_opt_not_supported), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: ActivityNotFoundException) {
            openBatteryOptimizationList()
        }
    }

    private fun openBatteryOptimizationList() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            openAppInfo()
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: ActivityNotFoundException) {
            // Best-effort fallback.
            Toast.makeText(this, getString(R.string.battery_opt_cant_open_settings), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun launchIfNeeded(activity: AppCompatActivity) {
            val shouldShow = BatteryOptimizationGuideViewModel.shouldShow(AppPrefs.Main.store(activity)) {
                isBatteryOptimizationIgnored(activity)
            }
            if (!shouldShow) return
            activity.startActivity(Intent(activity, BatteryOptimizationGuideActivity::class.java))
        }

        private fun isBatteryOptimizationIgnored(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
}
