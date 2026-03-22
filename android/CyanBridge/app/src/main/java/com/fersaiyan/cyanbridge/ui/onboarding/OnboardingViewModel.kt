package com.fersaiyan.cyanbridge.ui.onboarding

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "cyanbridge_prefs"
private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
private const val KEY_BATTERY_OPT_COMPLETED = "battery_opt_guide_completed"
private const val KEY_BATTERY_OPT_SUPPRESS = "battery_opt_guide_suppress"

data class OnboardingState(
    val isOnboardingCompleted: Boolean = false,
    val isBatteryOptIgnored: Boolean = false,
    val isBatteryOptGuideCompleted: Boolean = false,
)

class OnboardingViewModel(private val context: Context) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingState())
    val uiState: StateFlow<OnboardingState> = _uiState.asStateFlow()

    init {
        checkState()
    }

    fun checkState() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
            val batteryOptIgnored = isBatteryOptimizationIgnored()
            val batteryOptCompleted = prefs.getBoolean(KEY_BATTERY_OPT_COMPLETED, false)

            _uiState.value = OnboardingState(
                isOnboardingCompleted = onboardingCompleted,
                isBatteryOptIgnored = batteryOptIgnored,
                isBatteryOptGuideCompleted = batteryOptCompleted,
            )
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch(Dispatchers.IO) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ONBOARDING_COMPLETED, true)
                .apply()
            _uiState.value = _uiState.value.copy(isOnboardingCompleted = true)
        }
    }

    fun completeBatteryOptGuide() {
        viewModelScope.launch(Dispatchers.IO) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BATTERY_OPT_COMPLETED, true)
                .apply()
            _uiState.value = _uiState.value.copy(isBatteryOptGuideCompleted = true)
        }
    }

    fun skipBatteryOptGuide() {
        viewModelScope.launch(Dispatchers.IO) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BATTERY_OPT_SUPPRESS, true)
                .putBoolean(KEY_BATTERY_OPT_COMPLETED, true)
                .apply()
            _uiState.value = _uiState.value.copy(isBatteryOptGuideCompleted = true)
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OnboardingViewModel(context.applicationContext) as T
        }
    }
}
