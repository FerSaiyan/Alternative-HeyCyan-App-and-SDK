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
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.onboarding.BatteryOptimizationGuideScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

class BatteryOptimizationGuideActivity : AppCompatActivity() {
    private var optimizationIgnored by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        optimizationIgnored = isBatteryOptimizationIgnored(this)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                BatteryOptimizationGuideScreen(
                    optimizationIgnored = optimizationIgnored,
                    onDisableOptimization = ::openDisableBatteryOptimizationFlow,
                    onOpenAppInfo = ::openAppInfo,
                    onOpenOptimizationList = ::openBatteryOptimizationList,
                    onContinue = {
                        if (isBatteryOptimizationIgnored(this)) {
                            markCompleted(this)
                        }
                        navigateToNext()
                    },
                    onRemindLater = {
                        deferReminder(this)
                        navigateToNext()
                    },
                    onDontShowAgain = {
                        suppressPermanently(this)
                        navigateToNext()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        optimizationIgnored = isBatteryOptimizationIgnored(this)
    }

    private fun navigateToNext() {
        if (isOnboardingCompleted(this)) {
            finish()
            return
        }
        // After battery optimization, continue to feature onboarding screens
        startActivity(Intent(this, OnboardingFeatureActivity::class.java))
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
        private const val PREFS = "cyanbridge_prefs"
        private const val KEY_COMPLETED = "battery_opt_guide_completed"
        private const val KEY_SUPPRESS = "battery_opt_guide_suppress"
        private const val KEY_REMIND_AFTER = "battery_opt_guide_remind_after"
        private const val REMINDER_DELAY_MS = 7L * 24 * 60 * 60 * 1000

        fun launchIfNeeded(activity: AppCompatActivity) {
            if (!shouldShow(activity)) return
            activity.startActivity(Intent(activity, BatteryOptimizationGuideActivity::class.java))
        }

        private fun shouldShow(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_SUPPRESS, false)) return false
            if (prefs.getBoolean(KEY_COMPLETED, false)) return false
            if (System.currentTimeMillis() < prefs.getLong(KEY_REMIND_AFTER, 0L)) return false
            return !isBatteryOptimizationIgnored(context)
        }

        private fun deferReminder(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_REMIND_AFTER, System.currentTimeMillis() + REMINDER_DELAY_MS)
                .apply()
        }

        private fun isOnboardingCompleted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("onboarding_completed", false)

        private fun markCompleted(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMPLETED, true)
                .apply()
        }

        private fun suppressPermanently(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SUPPRESS, true)
                .apply()
        }

        private fun isBatteryOptimizationIgnored(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
}
