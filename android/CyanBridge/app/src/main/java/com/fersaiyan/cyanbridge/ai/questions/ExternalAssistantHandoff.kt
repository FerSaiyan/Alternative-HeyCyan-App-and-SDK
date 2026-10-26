package com.fersaiyan.cyanbridge.ai.questions

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.fersaiyan.cyanbridge.ai.image.DefaultAssistantResolver
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationInspector
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationPolicy
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationSetupDestination
import com.fersaiyan.cyanbridge.ai.image.ExternalImageAutomationIntents
import com.fersaiyan.cyanbridge.ai.image.ExternalImageAutomationStage
import com.fersaiyan.cyanbridge.ai.image.ExternalImageAutomationStore
import com.fersaiyan.cyanbridge.ai.image.ImageAutomationTarget
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionBroadcast
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSource
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionRoute
import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoute
import com.fersaiyan.cyanbridge.shared.assistant.AssistantQuestionRouting
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Questions answered outside the app: the phone's own assistant (Gemini, ChatGPT) through Tasker
 * automation or a direct image share, and Tasker's voice automation.
 */
internal class ExternalAssistantHandoff(
    private val env: QuestionEnvironment,
    private val speaker: AssistantSpeaker,
    private val route: () -> GlassesAssistantRoute,
    private val stopGlassesAiAudio: (source: String) -> Unit,
    private val wakeScreen: () -> Unit,
    private val prepareForLockScreen: () -> Unit,
) {
    private val context: Context get() = env.context

    /** The assistant name sent with Tasker broadcasts. */
    fun effectiveAssistantMode(): String = when (route()) {
        GlassesAssistantRoute.PHONE_ASSISTANT -> AI_MODE_PHONE_ASSISTANT
        GlassesAssistantRoute.TASKER_EXTERNAL_UI -> AI_MODE_TASKER
        GlassesAssistantRoute.LOCAL,
        GlassesAssistantRoute.PRO -> AI_MODE_CUSTOM_AI_PROVIDER
    }

    fun usesExternalAssistantUi(): Boolean = AssistantQuestionRouting.usesExternalAssistantUi(route())

    fun selectedImageAutomationTarget(): ImageAutomationTarget =
        ImageAutomationTarget.forDefaultAssistant(DefaultAssistantResolver.packageName(context))

    fun imageAutomationUnsupportedReason(): String? {
        if (!usesExternalAssistantUi()) return null
        return ExternalAssistantAutomationPolicy.imageBlockingReason(
            ExternalAssistantAutomationInspector.inspect(context),
        )
    }

    /** Explains why the phone assistant cannot take a photo question; true when it cannot. */
    fun showImageRequirementsWarningIfNeeded(): Boolean {
        if (!usesExternalAssistantUi()) return false

        val capability = ExternalAssistantAutomationInspector.inspect(context)
        val msg = ExternalAssistantAutomationPolicy.imageBlockingReason(capability) ?: return false
        val activity = env.host?.activity
        if (capability.phoneLocked || activity == null) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            speaker.speak(msg)
            return true
        }

        AlertDialog.Builder(activity)
            .setTitle("AI image setup required")
            .setMessage(msg)
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open setup") { _, _ ->
                activity.startActivity(AppRoute.EXTERNAL_ASSISTANT_SETUP.intent(activity))
            }
            .show()

        return true
    }

    fun sendAiBroadcast(
        type: String,
        path: String? = null,
        prompt: String? = null,
        imageUri: Uri? = null,
        imageSource: ImageQuestionSource? = null,
        handoffMode: String? = null,
        callbackSession: String? = null,
        callbackToken: String? = null,
        assistantMode: String = effectiveAssistantMode(),
    ) {
        val context = context
        val payload = ImageQuestionBroadcast.Payload(
            type = type,
            imagePath = path,
            imageUri = imageUri?.toString(),
            question = prompt,
            assistant = assistantMode,
            source = imageSource,
            handoffMode = handoffMode,
            callbackAction = ExternalImageAutomationIntents.statusAction(context.packageName),
            callbackSession = callbackSession,
            callbackToken = callbackToken,
        )
        val intent = Intent(aiEventAction(context.packageName)).apply {
            payload.extras().forEach { (key, value) -> putExtra(key, value) }
            // Kept for profiles created before the explicit question extra existed.
            prompt?.let { putExtra("prompt", it) }
            setPackage(ExternalImageAutomationIntents.TASKER_PACKAGE)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        context.sendBroadcast(intent)
        Log.i(
            "AIHijack",
            "Sent Broadcast to Tasker: type=$type source=${imageSource?.wireName.orEmpty()} handoff=${handoffMode.orEmpty()}",
        )
    }

    fun triggerTaskerVoiceAutomation() {
        prepareForLockScreen()
        env.scope.launch {
            delay(350L)
            val capability = ExternalAssistantAutomationInspector.inspect(context)
            val blockingReason = ExternalAssistantAutomationPolicy.voiceBlockingReason(capability)
            if (blockingReason != null) {
                Toast.makeText(context, blockingReason, Toast.LENGTH_LONG).show()
                speaker.speak(blockingReason)
                return@launch
            }

            stopGlassesAiAudio("Tasker voice-query command")
            sendAiBroadcast(
                type = "voice",
                assistantMode = capability.target.label,
            )
        }
    }

    fun launchPhoneAssistant() {
        // Wake up screen if locked. Android's selected assistant remains the authority for
        // whether voice interaction itself is allowed over the keyguard.
        wakeScreen()

        stopGlassesAiAudio("phone-assistant voice command")

        try {
            context.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Log.e("AIHijack", "Failed to trigger phone assistant: ${e.message}")
            env.runOnUiThread {
                Toast.makeText(context, "No phone assistant is configured", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun isDeviceLocked(): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager.isDeviceLocked
        } else {
            keyguardManager.isKeyguardLocked
        }
    }

    private fun stageImageForShare(source: File): File? {
        if (!source.isFile || source.length() <= 0L) return null
        val authority = "${context.packageName}.fileprovider"
        if (runCatching { FileProvider.getUriForFile(context, authority, source) }.isSuccess) {
            return source
        }

        return runCatching {
            val dir = (context.getExternalFilesDir("image-questions") ?: File(context.filesDir, "image-questions"))
                .apply { mkdirs() }
            val extension = source.extension.ifBlank { "jpg" }
            File(dir, "AI_Share_${System.currentTimeMillis()}.$extension").also { target ->
                source.copyTo(target, overwrite = true)
            }
        }.onFailure { error ->
            Log.e("ImageQuestion", "Could not stage image for FileProvider sharing", error)
        }.getOrNull()
    }

    private fun canStartImageShare(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            setPackage(packageName)
        }
        return intent.resolveActivity(context.packageManager) != null
    }

    private fun startImageShare(
        targetPackage: String,
        imageUri: Uri,
        question: String,
    ): Boolean {
        val context = context
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            setPackage(targetPackage)
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, question)
            clipData = ClipData.newRawUri("CyanBridge image", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (env.host == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.grantUriPermission(targetPackage, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(shareIntent)
            true
        }.onFailure { error ->
            Log.w("ImageQuestion", "Direct image share failed for $targetPackage", error)
        }.getOrDefault(false)
    }

    fun handOffImage(
        imagePath: String,
        userQuestion: String?,
        source: ImageQuestionSource,
    ) {
        val context = context
        val capability = ExternalAssistantAutomationInspector.inspect(context)
        ExternalAssistantAutomationPolicy.imageBlockingReason(capability)?.let { reason ->
            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            speaker.speak(reason)
            return
        }
        val stagedFile = stageImageForShare(File(imagePath))
        if (stagedFile == null) {
            Log.e("ImageQuestion", "External handoff image is unavailable: $imagePath")
            Toast.makeText(context, "The image file is unavailable for the phone assistant.", Toast.LENGTH_LONG).show()
            return
        }
        val imageUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", stagedFile)
        }.getOrElse { error ->
            Log.e("ImageQuestion", "Could not create image content URI", error)
            Toast.makeText(context, "Could not share the image with the phone assistant.", Toast.LENGTH_LONG).show()
            return
        }
        val question = ImageQuestionPromptResolver.resolve(
            settings = ImageQuestionPreferences.get(context),
            userQuestion = userQuestion,
        ).forRoute(ImageQuestionRoute.TASKER_GEMINI)
        val session = ExternalImageAutomationStore.begin(
            context = context,
            imagePath = stagedFile.absolutePath,
            imageUri = imageUri.toString(),
            question = question,
            source = source,
        )
        val targetPackage = capability.targetPackage
        val directSharePackage = targetPackage?.takeIf {
            !isDeviceLocked() && canStartImageShare(it)
        }
        val handoffMode = if (directSharePackage != null) {
            ImageQuestionBroadcast.HANDOFF_DIRECT_SHARE
        } else {
            ImageQuestionBroadcast.HANDOFF_AUTOINPUT_FALLBACK
        }

        if (directSharePackage != null) {
            if (startImageShare(directSharePackage, imageUri, question)) {
                ExternalImageAutomationStore.recordLocalStage(context, ExternalImageAutomationStage.IMAGE_ATTACHED)
                // The selectors run only after the image has reached this explicit package.
                sendAiBroadcast(
                    type = ImageQuestionBroadcast.TYPE_IMAGE,
                    path = stagedFile.absolutePath,
                    prompt = question,
                    imageUri = imageUri,
                    imageSource = source,
                    handoffMode = handoffMode,
                    callbackSession = session.sessionId,
                    callbackToken = session.callbackToken,
                    assistantMode = selectedImageAutomationTarget().label,
                )
                ExternalImageAutomationStore.recordLocalStage(context, ExternalImageAutomationStage.PROMPT_SENT)
                Log.i("ImageQuestion", "Started direct image share to $directSharePackage")
                return
            }
        }

        sendAiBroadcast(
            type = ImageQuestionBroadcast.TYPE_IMAGE,
            path = stagedFile.absolutePath,
            prompt = question,
            imageUri = imageUri,
            imageSource = source,
            handoffMode = ImageQuestionBroadcast.HANDOFF_AUTOINPUT_FALLBACK,
            callbackSession = session.sessionId,
            callbackToken = session.callbackToken,
            assistantMode = selectedImageAutomationTarget().label,
        )

        Toast.makeText(
            context,
            "Using Tasker AutoInput fallback for ${capability.target.label}.",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** The phone assistant reported its progress (Tasker callback); shown while the screen is open. */
    fun handleImageAutomationStatus() {
        val activity = env.host?.activity ?: return
        val session = ExternalImageAutomationStore.current(activity) ?: return
        when (session.state.stage) {
            ExternalImageAutomationStage.ANSWER_READY -> {
                if (session.followUpPromptShown || activity.isFinishing || activity.isDestroyed) return
                ExternalImageAutomationStore.markFollowUpPromptShown(activity)
                Toast.makeText(
                    activity,
                    "${selectedImageAutomationTarget().label} owns response playback.",
                    Toast.LENGTH_SHORT,
                ).show()
            }

            ExternalImageAutomationStage.FAILED -> {
                session.state.error?.takeIf { it.isNotBlank() }?.let { error ->
                    Toast.makeText(activity, "Assistant automation failed: $error", Toast.LENGTH_LONG).show()
                }
            }

            else -> Unit
        }
    }

    companion object {
        const val AI_MODE_PHONE_ASSISTANT = "PhoneAssistant"
        const val AI_MODE_TASKER = "Tasker"
        const val AI_MODE_CUSTOM_AI_PROVIDER = "CustomAiProvider"

        fun aiEventAction(appPackageName: String): String = "$appPackageName.AI_EVENT"
    }
}
