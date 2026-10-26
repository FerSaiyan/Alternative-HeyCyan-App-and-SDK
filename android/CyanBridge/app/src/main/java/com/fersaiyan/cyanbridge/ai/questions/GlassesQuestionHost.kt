package com.fersaiyan.cyanbridge.ai.questions

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSource
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.ai.router.AssistantTestKind
import kotlinx.coroutines.CoroutineScope

/**
 * What the glasses questions use from the main screen: permission prompts, dialogs, the lock
 * screen and the selected family's devices. Absent when a question starts in the background
 * ([GlassesQuestions.askByVoiceInBackground]).
 */
internal interface GlassesQuestionHost {
    val activity: AppCompatActivity

    /** Anchor of the Gemini Live stop snackbar. */
    val rootView: View

    val isAiHijackEnabled: Boolean

    /** The selected family's own photo transport, or null for the HeyCyan SDK path. */
    fun questionPhoto(): QuestionPhotoSource?

    /** A family photo that blocks voice questions is being taken. */
    fun questionPhotoBlocksVoice(): Boolean

    /** Shows the setup the current route still needs; true when it did (the question does not start). */
    fun showAssistantSetupIfNeeded(kind: AssistantTestKind): Boolean

    /** A Wi-Fi (high-quality) image for the question, through the HeyCyan media sync. */
    fun requestHighQualityImageForQuestion(sourceTag: String)

    val highQualityImageRequestActive: Boolean
}

/** What every part of the question flows shares (implemented by [GlassesQuestions]). */
internal interface QuestionEnvironment {
    val appContext: Context

    /** The main screen when it exists (strings follow its locale, dialogs need it), else the application. */
    val context: Context

    val host: GlassesQuestionHost?

    /** Question coroutines; cancelled with the main screen, like its lifecycle scope used to be. */
    val scope: CoroutineScope

    fun runOnUiThread(block: () -> Unit)
}

/** A glasses family's own photo transport for image questions (HeyCyan's SDK path is [ImageQuestionCapture]). */
internal interface QuestionPhotoSource {
    /** True while a photo is being taken. */
    val inProgress: Boolean

    /** A photo in progress also refuses voice questions (TuneBuds' never did). */
    val blocksVoiceQuestions: Boolean get() = true

    fun capture(requests: QuestionPhotoRequests, sourceTag: String, offerSpokenQuestion: Boolean)
}

/** The image-question steps a [QuestionPhotoSource] runs around its transfer. */
internal interface QuestionPhotoRequests {
    val context: Context
    val scope: CoroutineScope
    val thumbnailQuality: ImageThumbnailQuality

    /** When the button press started this capture. */
    val captureStartedAtMs: Long

    fun prepareForLockScreen()

    fun beginForegroundWork(status: String)

    fun finishForegroundWork()

    /** Opens the spoken-question window while the photo transfers. */
    fun startAskWindow(offerSpokenQuestion: Boolean)

    fun clearPendingQuestion(sourceTag: String)

    fun onPhotoReady(imagePath: String, source: ImageQuestionSource, transferDurationMs: Long)
}
