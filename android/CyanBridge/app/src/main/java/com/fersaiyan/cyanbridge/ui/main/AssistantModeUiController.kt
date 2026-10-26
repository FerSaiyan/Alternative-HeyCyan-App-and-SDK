package com.fersaiyan.cyanbridge.ui.main

import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.ai.image.DefaultAssistantResolver
import com.fersaiyan.cyanbridge.ai.image.ImageAutomationTarget
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveVisionPreferences
import com.fersaiyan.cyanbridge.ai.questions.GlassesQuestions
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode

/** The dashboard's assistant card: mode buttons, image-question settings and the test buttons' state. */
internal class AssistantModeUiController(
    host: MainScreenHost,
    private val questions: GlassesQuestions,
) : MainScreenController(host) {
    fun refreshAiQueryButtonsState() {
        binding.btnTestHijackImage.isEnabled = true
        binding.btnTestHijackImage.alpha = 1f
        binding.btnTestHijackImage.text = "Test Image AI description"
        updateDashboardState { state ->
            state.copy(
                imageQueryEnabled = true,
                imageQueryLabel = "Test image AI description",
            )
        }
    }

    fun refreshImageThumbnailQuality() {
        val quality = ImageQuestionPreferences.thumbnailQuality(activity)
        questions.thumbnailQuality = quality
        updateDashboardState { state ->
            state.copy(
                imageThumbnailQualitySdkValue = quality.sdkValue,
                imageThumbnailQualityLabel = quality.label,
            )
        }
    }

    fun refreshGeminiLiveImageDelay() {
        val seconds = GeminiLiveVisionPreferences.imageDelaySeconds(activity)
        updateDashboardState { state ->
            state.copy(
                showGeminiLiveImageDelay = true,
                geminiLiveImageDelaySeconds = seconds,
            )
        }
    }

    fun refreshAiModeButtons() {
        val activeColor = ContextCompat.getColor(activity, R.color.cyan_accent)
        val inactiveColor = ContextCompat.getColor(activity, R.color.text_secondary)

        val mode = questions.assistantMode
        val phoneAssistantSelected = mode == GlassesAssistantMode.PHONE_ASSISTANT
        binding.btnModeGemini.setTextColor(if (phoneAssistantSelected) activeColor else inactiveColor)
        binding.btnModeChatgpt.setTextColor(if (phoneAssistantSelected) activeColor else inactiveColor)
        binding.btnModeTasker.setTextColor(if (!phoneAssistantSelected) activeColor else inactiveColor)
        updateDashboardState { state -> state.copy(assistantMode = mode) }
        refreshAiQueryButtonsState()
    }

    fun selectPhoneAssistant() {
        questions.assistantMode = GlassesAssistantMode.PHONE_ASSISTANT
        AutomationPrefs.setGlassesAssistantMode(activity, GlassesAssistantMode.PHONE_ASSISTANT)
        refreshAiModeButtons()
        val assistantPackage = DefaultAssistantResolver.packageName(activity)
        val result = when (questions.external.selectedImageAutomationTarget()) {
            ImageAutomationTarget.GEMINI -> "Gemini external image automation"
            ImageAutomationTarget.CHATGPT -> "ChatGPT external image automation"
            ImageAutomationTarget.NONE -> "voice only; external image automation unavailable"
        }
        Toast.makeText(
            activity,
            "Phone Assistant: ${assistantPackage ?: "not detected"}. $result",
            Toast.LENGTH_LONG,
        ).show()
    }

    fun selectCustomAiProvider() {
        questions.assistantMode = GlassesAssistantMode.CUSTOM_AI_PROVIDER
        AutomationPrefs.setGlassesAssistantMode(activity, GlassesAssistantMode.CUSTOM_AI_PROVIDER)
        refreshAiModeButtons()
        val provider = AutomationPrefs.getProviderType(activity).label
        Toast.makeText(activity, "Local / Pro / Tasker: $provider", Toast.LENGTH_SHORT).show()
    }
}
