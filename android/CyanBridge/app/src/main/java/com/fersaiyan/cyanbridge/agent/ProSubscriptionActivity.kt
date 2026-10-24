package com.fersaiyan.cyanbridge.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.SoundEffectConstants
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.billing.LaunchStorePurchase
import com.fersaiyan.cyanbridge.shared.billing.OpenCheckoutUrl
import com.fersaiyan.cyanbridge.shared.billing.OpenEmailVerificationLink
import com.fersaiyan.cyanbridge.shared.billing.OpenProSettings
import com.fersaiyan.cyanbridge.shared.billing.OpenStoreSubscriptions
import com.fersaiyan.cyanbridge.shared.billing.PlayClickSound
import com.fersaiyan.cyanbridge.shared.billing.PromptAccountEmail
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionLaunch
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionUiState
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionViewModel
import com.fersaiyan.cyanbridge.shared.billing.ReportActivated
import com.fersaiyan.cyanbridge.shared.billing.ShowDonationIntro
import com.fersaiyan.cyanbridge.shared.ui.pro.ProSubscriptionScreen
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.collectScreenEffects
import com.fersaiyan.cyanbridge.ui.sharedViewModelFactory
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers

/** Hosts the shared Pro checkout screen; [ProSubscriptionViewModel] owns its state and flows. */
class ProSubscriptionActivity : AppCompatActivity() {
    private val viewModel: ProSubscriptionViewModel by viewModels {
        sharedViewModelFactory {
            ProSubscriptionViewModel(AndroidProAccountPlatform(this), PlayStoreBilling(this), Dispatchers.IO)
        }
    }

    internal val state: ProSubscriptionUiState get() = viewModel.state.value.ui
    private lateinit var composeView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = ComposeView(this).also(::setContentView)
        collectScreenEffects(viewModel.effectFlow, ::handleEffect)

        val launch = ProSubscriptionLaunch(
            callbackMessage = takeCallbackMessage(intent),
            initialPlan = intent?.getStringExtra(EXTRA_INITIAL_PLAN),
            changePlan = intent?.getBooleanExtra(EXTRA_CHANGE_PLAN, false) == true,
            emailVerificationReturn = SubscriptionCheckoutPolicy.isEmailVerificationReturn(intent?.data),
        )
        if (!viewModel.onCreate(launch)) return

        val appearancePreferences = AppearancePreferences(this)
        composeView.setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            val screen by viewModel.state.collectAsState()
            CyanBridgeTheme(appearance) {
                ProSubscriptionScreen(
                    state = screen.ui,
                    restoreNotFoundEmail = screen.restoreNotFoundEmail,
                    restoreLogsSending = screen.restoreLogsSending,
                    onPlanSelected = viewModel::selectPlan,
                    onStartFreeTrial = {
                        playClickSound()
                        viewModel.subscribeToSelectedPlan()
                    },
                    onSubscribeWithGooglePlay = viewModel::subscribeWithStore,
                    onSubscribeOnWebsite = viewModel::subscribeOnWebsite,
                    onCheckoutUnavailable = viewModel::showCheckoutUnavailable,
                    onRestoreExistingSubscription = viewModel::restoreExistingSubscription,
                    onDismissRestoreNotFound = viewModel::dismissRestoreNotFound,
                    onSendRestoreFailureLogs = viewModel::sendRestoreFailureLogs,
                    onDonate = {
                        playClickSound()
                        viewModel.donate()
                    },
                    onCancelSubscription = {
                        playClickSound()
                        viewModel.cancelSubscription()
                    },
                    onBack = ::finish,
                )
                screen.emailCode?.let { request ->
                    EmailVerificationCodeDialog(
                        email = request.email,
                        initialMessage = request.initialMessage,
                        verificationLinkAvailable = request.verificationUrl != null,
                        verifying = request.verifying,
                        errorMessage = request.error,
                        onCodeChanged = viewModel::onEmailCodeChanged,
                        onVerify = viewModel::verifyEmailCode,
                        onResend = viewModel::resendEmailCode,
                        onOpenLink = request.verificationUrl?.let { { viewModel.openEmailVerificationLink() } },
                        onDismissRequest = viewModel::dismissEmailCode,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.onNewIntent(
            callbackMessage = takeCallbackMessage(intent),
            emailVerificationReturn = SubscriptionCheckoutPolicy.isEmailVerificationReturn(intent.data),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CHECKED_PLAN, viewModel.checkedPlan)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // The plan picker kept its selection across recreation; onResume publishes it.
        savedInstanceState.getString(STATE_CHECKED_PLAN)?.let(viewModel::restoreCheckedPlan)
    }

    /** Reads the web-callback message once, so a recreated screen does not repeat it. */
    private fun takeCallbackMessage(intent: Intent?): String? {
        val msg = intent?.getStringExtra(EXTRA_CALLBACK_MESSAGE).orEmpty().trim()
        if (msg.isBlank()) return null
        intent?.removeExtra(EXTRA_CALLBACK_MESSAGE)
        intent?.removeExtra(EXTRA_FROM_WEB_CALLBACK)
        return msg
    }

    /** Former hidden buttons played the standard click sound before their handler; keep that feedback. */
    private fun playClickSound() {
        composeView.playSoundEffect(SoundEffectConstants.CLICK)
    }

    private fun handleEffect(effect: ScreenEffect) {
        when (effect) {
            is PromptAccountEmail -> promptForEmail(effect)
            PlayClickSound -> playClickSound()
            ReportActivated -> setResult(RESULT_OK)
            is OpenProSettings -> if (effect.deferred) {
                // Post the navigation so the screen never finishes in the middle of onCreate.
                window.decorView.post { if (!isFinishing && !isDestroyed) openProSettings() }
            } else {
                openProSettings()
            }
            is OpenCheckoutUrl -> runCatching { InAppBrowser.open(this, effect.url) }.onFailure {
                Toast.makeText(this, "Unable to open website checkout: ${it.message}", Toast.LENGTH_LONG).show()
            }
            is OpenEmailVerificationLink -> openEmailVerificationLink(effect.url)
            OpenStoreSubscriptions -> openPlaySubscriptionManagement()
            is LaunchStorePurchase -> viewModel.billing?.launchPurchase(this, effect.plan)
            is ShowDonationIntro -> AlertDialog.Builder(this)
                .setTitle(R.string.donation_intro_dialog_title)
                .setMessage(getString(R.string.donation_intro_dialog_message))
                .setPositiveButton(R.string.donation_intro_open_button) { _, _ -> effect.onOpen() }
                .setNegativeButton("Cancel") { _, _ -> effect.onCancel() }
                .show()
        }
    }

    private fun openProSettings() {
        startActivity(Intent(this, ProSubscriptionSettingsActivity::class.java))
        finish()
    }

    private fun promptForEmail(request: PromptAccountEmail) {
        val input = EditText(this).apply {
            hint = "you@example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(request.initialEmail)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(request.title)
            .setMessage(request.message)
            .setView(input)
            .setPositiveButton("Continue", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = request.accept(input.text?.toString().orEmpty())
                if (email == null) {
                    input.error = "Enter a valid email address"
                    return@setOnClickListener
                }
                dialog.dismiss()
                request.onAccepted(email)
            }
        }
        dialog.show()
    }

    private fun openEmailVerificationLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            Toast.makeText(this, "Opened verification link. Return here after confirming.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open link. Copy the code from your email.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_CALLBACK_MESSAGE = "extra_callback_message"
        const val EXTRA_FROM_WEB_CALLBACK = "extra_from_web_callback"
        const val EXTRA_INITIAL_PLAN = "extra_initial_plan"
        const val EXTRA_CHANGE_PLAN = "extra_change_plan"
        private const val STATE_CHECKED_PLAN = "checked_plan"
    }
}
