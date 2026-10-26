package com.fersaiyan.cyanbridge.ui.voiceprofile

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.ui.onboarding.VoiceEnrollmentScreen
import com.fersaiyan.cyanbridge.ui.OnboardingFeatureDestination

/**
 * Voice enrollment: an onboarding step (with [EXTRA_STEP_INDEX]) or a standalone screen opened
 * from Settings. UI is the shared VoiceEnrollmentScreen; recording and storage are Android-only.
 */
class VoiceEnrollmentDestination : NavScreen() {
    private val viewModel: VoiceEnrollmentViewModel by viewModels()
    private var stepIndex = -1
    private var stepCount = 0
    private var micRequestedOnce = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micRequestedOnce = true
        viewModel.setMicGranted(granted)
    }

    private val onboarding get() = stepIndex >= 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stepIndex = intent.getIntExtra(EXTRA_STEP_INDEX, -1)
        stepCount = intent.getIntExtra(EXTRA_STEP_COUNT, 0)
        viewModel.setMicGranted(hasMicPermission())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        setContent {
            val state by viewModel.state.collectAsState()
            val level by viewModel.level.collectAsState()
            VoiceEnrollmentScreen(
                state = state,
                level = level,
                stepIndex = stepIndex.takeIf { onboarding },
                stepCount = stepCount.takeIf { onboarding },
                onConsentChange = viewModel::setConsent,
                onRequestMicrophone = ::requestMicrophone,
                onRecord = viewModel::recordCurrentPhrase,
                onCancelRecording = viewModel::cancelRecording,
                onSelectPhrase = viewModel::selectPhrase,
                onBack = ::goBack,
                onNext = ::goNext,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setMicGranted(hasMicPermission())
    }

    override fun onPause() {
        super.onPause()
        // Never keep the microphone open behind another screen.
        viewModel.cancelRecording()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicrophone() {
        val permanentlyDenied = micRequestedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (permanentlyDenied) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun goBack() {
        if (onboarding) navigateToStep(stepIndex - 1) else finish()
    }

    private fun goNext() {
        if (onboarding) navigateToStep(stepIndex + 1) else finish()
    }

    private fun navigateToStep(index: Int) {
        startActivity(OnboardingFeatureDestination.stepIntent(this, index))
        finish()
    }

    companion object {
        private const val EXTRA_STEP_INDEX = "step_index"
        private const val EXTRA_STEP_COUNT = "step_count"

        fun intent(context: Context) = AppRoute.VOICE_ENROLLMENT.intent(context)

        fun onboardingIntent(context: Context, stepIndex: Int, stepCount: Int) =
            intent(context).putExtra(EXTRA_STEP_INDEX, stepIndex).putExtra(EXTRA_STEP_COUNT, stepCount)
    }
}
