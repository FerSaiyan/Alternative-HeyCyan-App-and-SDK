package com.fersaiyan.cyanbridge.ai.questions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSource
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSourcePolicy
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.glasses.BackgroundGlassesCommandPermit
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.glasses.HeyCyanThumbnailReceiver
import com.fersaiyan.cyanbridge.ui.main.GlassesSessionGuard
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission as HjqPermission
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Takes the photo of an image question: the glasses' AI-photo button (or the dashboard test, a
 * wake word, a decide-once camera route) through the selected family's [QuestionPhotoSource], or the
 * HeyCyan SDK path (vision mode + shutter, then the 0x02 photo-ready notification and the BLE
 * thumbnail or the Wi-Fi high-quality image). The photo goes to [ImageQuestionFlow].
 */
internal class ImageQuestionCapture(
    private val env: QuestionEnvironment,
    private val guard: GlassesSessionGuard,
    private val flow: () -> ImageQuestionFlow,
    private val foreground: QuestionForegroundWork,
) : QuestionPhotoRequests {
    val imageCaptureAwaitingNotification = AtomicBoolean(false)
    val thumbnailRequestInProgress = AtomicBoolean(false)
    private val pendingCapturePermit = AtomicReference<BackgroundGlassesCommandPermit?>(null)

    @Volatile var pendingCaptureSourceTag: String? = null
    var pendingImageQuestionSource: ImageQuestionSource = ImageQuestionSourcePolicy.defaultSource()

    /** The dashboard's thumbnail quality; a button press may override it for one capture. */
    var pendingThumbnailQuality: ImageThumbnailQuality = ImageQuestionSourcePolicy.defaultThumbnailQuality()
    var pendingCaptureStartedAtMs: Long = 0L

    /** The button offered a spoken question with the photo; the photo-ready step opens the window. */
    var pendingOfferSpokenQuestion = false

    override val context: Context get() = env.context
    override val scope: CoroutineScope get() = env.scope
    override val thumbnailQuality: ImageThumbnailQuality get() = pendingThumbnailQuality
    override val captureStartedAtMs: Long get() = pendingCaptureStartedAtMs

    override fun prepareForLockScreen() = foreground.prepareForLockScreen()

    override fun beginForegroundWork(status: String) = foreground.begin(status)

    override fun finishForegroundWork() = foreground.finish()

    override fun startAskWindow(offerSpokenQuestion: Boolean) {
        pendingOfferSpokenQuestion = false
        flow().startParallelAudioQuestionIfEligible(offerSpokenQuestion)
    }

    override fun clearPendingQuestion(sourceTag: String) = flow().clearPendingVoiceImageQuestion(sourceTag)

    override fun onPhotoReady(imagePath: String, source: ImageQuestionSource, transferDurationMs: Long) =
        flow().onImageReadyForQuestion(imagePath, source, transferDurationMs)

    /**
     * Starts an image question. [triggerCapture] takes a new photo (dashboard, wake word); false
     * means the glasses already took it (HeyCyan's hardware AI-photo button). [photo] is the
     * selected family's transport, null for the HeyCyan SDK.
     */
    fun handleGlassesImageButtonPressed(
        triggerCapture: Boolean,
        sourceTag: String,
        source: ImageQuestionSource = ImageQuestionSourcePolicy.defaultSource(),
        thumbnailQuality: ImageThumbnailQuality = ImageQuestionSourcePolicy.defaultThumbnailQuality(),
        offerSpokenQuestion: Boolean = true,
        photo: QuestionPhotoSource? = env.host?.questionPhoto(),
    ) {
        Log.i(
            "ImageQuestionTransfer",
            "[$sourceTag] Image request triggerCapture=$triggerCapture source=${source.wireName} " +
                "thumbnailQuality=${thumbnailQuality.label}/${thumbnailQuality.sdkValue} " +
                "offerSpokenQuestion=$offerSpokenQuestion connected=${BleOperateManager.getInstance().isConnected} " +
                "activeSession=${GlassesSessionCoordinator.currentSession()}",
        )
        val context = env.context
        if (
            offerSpokenQuestion &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            val retry = { offer: Boolean ->
                handleGlassesImageButtonPressed(triggerCapture, sourceTag, source, thumbnailQuality, offer, photo)
            }
            val activity = env.host?.activity
            if (activity == null) {
                // No screen to ask for the microphone: answer the photo's default question.
                microphoneNeededToast(context)
                retry(false)
                return
            }
            XXPermissions.with(activity)
                .permission(HjqPermission.RECORD_AUDIO)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                        retry(all)
                    }

                    override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                        microphoneNeededToast(activity)
                        retry(false)
                    }
                })
            return
        }
        pendingImageQuestionSource = source
        pendingThumbnailQuality = thumbnailQuality
        pendingCaptureStartedAtMs = System.currentTimeMillis()
        pendingOfferSpokenQuestion = offerSpokenQuestion
        if (GlassesQuestionTestHooks.photoActive) {
            captureTestPhoto(sourceTag, offerSpokenQuestion)
            return
        }
        if (photo != null) {
            photo.capture(this, sourceTag, offerSpokenQuestion)
            return
        }

        if (guard.isGlassesCommandBlocked("AI image capture")) {
            flow().clearPendingVoiceImageQuestion(sourceTag)
            return
        }
        if (!BleOperateManager.getInstance().isConnected) {
            flow().clearPendingVoiceImageQuestion(sourceTag)
            env.runOnUiThread {
                Toast.makeText(
                    env.context,
                    "Glasses are not connected. Connect first to use image query.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        foreground.prepareForLockScreen()
        foreground.begin("Capturing image from glasses")

        if (triggerCapture) {
            val permit = guard.acquireBackgroundGlassesCommand("AI image capture") ?: return
            if (thumbnailRequestInProgress.get() ||
                !imageCaptureAwaitingNotification.compareAndSet(false, true)
            ) {
                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                Log.i("AIHijack", "[$sourceTag] Image capture already in progress")
                return
            }

            pendingCaptureSourceTag = sourceTag
            pendingCapturePermit.set(permit)
            Toast.makeText(context, "Triggering glasses camera…", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val thumbnailSize = pendingThumbnailQuality.sdkValue.toByte()
                    Log.i(
                        "AIHijack",
                        "[$sourceTag] Requesting BLE AI capture at ${pendingThumbnailQuality.label} " +
                            "(${pendingThumbnailQuality.sdkValue})",
                    )
                    // Match the official AI-chat sequence: configure the quality-aware vision
                    // mode first, then use the normal shutter command to take the fresh photo.
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x06, thumbnailSize, thumbnailSize),
                    ) { _, response ->
                        Log.i(
                            "ImageQuestionTransfer",
                            "[$sourceTag] AI vision command response dataType=${response.dataType} " +
                                "error=${response.errorCode}",
                        )
                    }
                    delay(250)
                    check(GlassesSessionCoordinator.isBackgroundCommandActive(permit)) {
                        "Image capture ownership expired"
                    }
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x01),
                    ) { _, response ->
                        Log.i(
                            "ImageQuestionTransfer",
                            "[$sourceTag] AI shutter command response dataType=${response.dataType} " +
                                "error=${response.errorCode}",
                        )
                    }

                    // The device's 0x02 notification is the authoritative signal that its
                    // image is ready. Never request a thumbnail without it: that could return
                    // an earlier capture and cause the AI to analyze the wrong image.
                    delay(4_000)
                    if (imageCaptureAwaitingNotification.compareAndSet(true, false)) {
                        pendingCaptureSourceTag = null
                        flow().clearPendingVoiceImageQuestion(sourceTag)
                        foreground.finish()
                        Log.w("AIHijack", "[$sourceTag] No AI photo-ready signal; refusing stale thumbnail fallback")
                        env.runOnUiThread {
                            Toast.makeText(
                                env.context,
                                "Glasses did not confirm a new photo. Please try again.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } catch (error: Exception) {
                    imageCaptureAwaitingNotification.set(false)
                    pendingCaptureSourceTag = null
                    foreground.finish()
                    Log.e("AIHijack", "[$sourceTag] Failed to trigger glasses camera: ${error.message}", error)
                } finally {
                    if (pendingCapturePermit.compareAndSet(permit, null)) {
                        GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                    }
                }
            }
        } else {
            requestSelectedImageSource(sourceTag)
        }
    }

    private fun microphoneNeededToast(context: Context) {
        Toast.makeText(
            context,
            "Microphone access is needed to ask about the image before its description.",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** HeyCyan's photo is ready on the glasses: open the ask window and fetch it. */
    fun requestSelectedImageSource(sourceTag: String) {
        val offerSpokenQuestion = pendingOfferSpokenQuestion
        pendingOfferSpokenQuestion = false
        Log.i(
            "ImageQuestionAudio",
            "[$sourceTag] Photo ready; starting parallel question window after the 500 ms settling delay " +
                "offerSpokenQuestion=$offerSpokenQuestion",
        )
        flow().startParallelAudioQuestionIfEligible(offerSpokenQuestion)
        when (pendingImageQuestionSource) {
            ImageQuestionSource.HIGH_QUALITY -> env.host?.requestHighQualityImageForQuestion(sourceTag)
            ImageQuestionSource.FAST_PREVIEW -> requestImageThumbnailForQuestion(sourceTag)
        }
    }

    /** Debug-build stand-in for a glasses photo: same Ask window and handoff as EyeVue's AI photo. */
    private fun captureTestPhoto(sourceTag: String, offerSpokenQuestion: Boolean) {
        foreground.begin("Capturing test photo")
        pendingOfferSpokenQuestion = false
        flow().startParallelAudioQuestionIfEligible(offerSpokenQuestion)
        val startedAt = System.currentTimeMillis()
        env.scope.launch(Dispatchers.IO) {
            delay(TEST_PHOTO_TRANSFER_MS)
            val imageFile = GlassesQuestionTestHooks.takePhoto(env.appContext.cacheDir)
            withContext(Dispatchers.Main) {
                if (imageFile == null) {
                    flow().clearPendingVoiceImageQuestion(sourceTag)
                    foreground.finish()
                } else {
                    flow().onImageReadyForQuestion(imageFile.absolutePath, ImageQuestionSource.HIGH_QUALITY, System.currentTimeMillis() - startedAt)
                }
            }
        }
    }

    fun requestImageThumbnailForQuestion(sourceTag: String) {
        if (guard.isGlassesCommandBlocked("AI thumbnail request")) return
        val permit = pendingCapturePermit.getAndSet(null)
            ?: guard.acquireBackgroundGlassesCommand("AI thumbnail request")
            ?: return
        if (!thumbnailRequestInProgress.compareAndSet(false, true)) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            Log.i("AIHijack", "[$sourceTag] Thumbnail request already in progress")
            return
        }

        val context = env.context
        val outDir = context.getExternalFilesDir("DCIM") ?: context.filesDir
        val file = File(outDir, "AI_Thumb_${sourceTag}_${System.currentTimeMillis()}.jpg")
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
        }

        val startedAtMs = System.currentTimeMillis()
        Log.i(
            "ImageQuestionTransfer",
            "[$sourceTag] Starting thumbnail transfer file=${file.absolutePath} " +
                "quality=${pendingThumbnailQuality.label}/${pendingThumbnailQuality.sdkValue}",
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    FileOutputStream(file, false).use { }
                }.onFailure { error ->
                    Log.e("AIHijack", "[$sourceTag] Failed to prepare thumbnail file: ${error.message}", error)
                }

                if (receivePictureThumbnail(file, sourceTag, permit)) {
                    Log.i(
                        "AIHijack",
                        "[$sourceTag] Thumbnail transfer complete: ${file.absolutePath} (${file.length()} bytes)",
                    )
                    withContext(Dispatchers.Main) {
                        flow().onImageReadyForQuestion(
                            imagePath = file.absolutePath,
                            source = ImageQuestionSource.FAST_PREVIEW,
                            transferDurationMs = System.currentTimeMillis() - startedAtMs,
                        )
                    }
                    return@launch
                }

                flow().clearPendingVoiceImageQuestion(sourceTag)
                foreground.finish()
                Log.w("AIHijack", "[$sourceTag] BLE preview did not complete")
                env.runOnUiThread {
                    Toast.makeText(
                        env.context,
                        "Fast preview transfer failed. Please try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (error: Exception) {
                file.delete()
                flow().clearPendingVoiceImageQuestion(sourceTag)
                foreground.finish()
                Log.w("ImageQuestionTransfer", "[$sourceTag] Thumbnail transfer failed", error)
                env.runOnUiThread {
                    Toast.makeText(env.context, "Fast preview transfer failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            } finally {
                thumbnailRequestInProgress.set(false)
                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            }
        }
    }

    private suspend fun receivePictureThumbnail(
        file: File,
        sourceTag: String,
        permit: BackgroundGlassesCommandPermit,
    ): Boolean {
        check(GlassesSessionCoordinator.isBackgroundCommandActive(permit))
        val image = HeyCyanThumbnailReceiver(
            valid = { GlassesSessionCoordinator.isBackgroundCommandActive(permit) },
        ).receive()
        file.writeBytes(image)
        Log.i("ImageQuestionTransfer", "[$sourceTag] Ordered thumbnail fully decoded bytes=${image.size}")
        return true
    }

    /**
     * HeyCyan's 0x02 notification: the AI photo is ready on the glasses, after a capture the app
     * requested or a hardware AI-photo press. [payload] is the notification, for the log.
     */
    fun onHeyCyanPhotoReady(payload: ByteArray, isAiHijackEnabled: Boolean, showImageRequirementsWarning: () -> Boolean) {
        val appRequestedCapture = imageCaptureAwaitingNotification.get()
        // Live's dedicated listener delivers manual events even when this Activity
        // is stopped. A requested MainActivity capture retains precedence.
        if (!appRequestedCapture &&
            com.fersaiyan.cyanbridge.ai.live.GeminiLiveImageButtonRouter.isRegistered()
        ) return
        val sourceTag = pendingCaptureSourceTag ?: "hardware_image_button"
        Log.i(
            "ImageQuestionTransfer",
            "AI photo-ready notify source=$sourceTag awaiting=${imageCaptureAwaitingNotification.get()} " +
                "thumbnailInProgress=${thumbnailRequestInProgress.get()} " +
                "captureAgeMs=${System.currentTimeMillis() - pendingCaptureStartedAtMs} " +
                "payload=${payload.joinToString { (it.toInt() and 0xFF).toString() }}",
        )
        if (isAiHijackEnabled) {
            env.runOnUiThread {
                if (showImageRequirementsWarning()) {
                    imageCaptureAwaitingNotification.set(false)
                    pendingCaptureSourceTag = null
                    return@runOnUiThread
                }
                imageCaptureAwaitingNotification.set(false)
                pendingCaptureSourceTag = null
                if (appRequestedCapture) {
                    requestSelectedImageSource(sourceTag)
                } else {
                    // A hardware AI-photo press starts a complete image-question
                    // turn. It must not fall through to the 0x03 voice route.
                    handleGlassesImageButtonPressed(
                        triggerCapture = false,
                        sourceTag = sourceTag,
                        source = ImageQuestionSourcePolicy.defaultSource(),
                        thumbnailQuality = ImageQuestionSourcePolicy.defaultThumbnailQuality(),
                        offerSpokenQuestion = true,
                    )
                }
            }
        }
    }

    private companion object {
        const val TEST_PHOTO_TRANSFER_MS = 1_500L
    }
}
