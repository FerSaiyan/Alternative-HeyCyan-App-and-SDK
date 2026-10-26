package com.fersaiyan.cyanbridge.ai.questions

import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSource
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLocalHotspot
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaItem
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaSync
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaType
import com.fersaiyan.cyanbridge.glasses.GlassesPhotoCapture
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Meta Ray-Ban: a DAT photo once the camera session is ready; no ask window during the transfer. */
internal class MetaQuestionPhoto(
    private val manager: () -> MetaRaybanManager,
    private val ensureCameraReady: (action: () -> Unit) -> Unit,
    private val showError: (operation: String, message: String) -> Unit,
) : QuestionPhotoSource {
    private val busy = AtomicBoolean(false)
    override val inProgress: Boolean get() = busy.get()

    override fun capture(requests: QuestionPhotoRequests, sourceTag: String, offerSpokenQuestion: Boolean) {
        ensureCameraReady {
            if (!busy.compareAndSet(false, true)) {
                Log.i("AIHijack", "[$sourceTag] Meta photo capture already in progress")
                return@ensureCameraReady
            }

            val manager = manager()
            requests.scope.launch(Dispatchers.IO) {
                runCatching {
                    val photo = manager.capturePhotoOnce()
                    manager.savePhotoForProcessing(photo, "META_AI_$sourceTag")
                }.onSuccess { file ->
                    withContext(Dispatchers.Main) {
                        requests.onPhotoReady(
                            imagePath = file.absolutePath,
                            source = ImageQuestionSource.HIGH_QUALITY,
                            transferDurationMs = System.currentTimeMillis() - requests.captureStartedAtMs,
                        )
                    }
                }.onFailure { error ->
                    requests.clearPendingQuestion(sourceTag)
                    withContext(Dispatchers.Main) {
                        showError(
                            "AI photo capture ($sourceTag)",
                            error.message ?: "capture failed",
                        )
                    }
                }
                busy.set(false)
            }
        }
    }
}

/**
 * The common shape of a native AI photo: refuse while one runs, open the ask window, transfer on
 * IO and hand the file over (or clear the question and explain the failure).
 */
private fun QuestionPhotoSource.captureNative(
    busy: AtomicBoolean,
    requests: QuestionPhotoRequests,
    sourceTag: String,
    offerSpokenQuestion: Boolean,
    family: String,
    foregroundStatus: String,
    filePrefix: String,
    imageSource: ImageQuestionSource,
    failureMessage: (Exception) -> String,
    takePhoto: suspend () -> ByteArray,
) {
    if (!busy.compareAndSet(false, true)) {
        Log.i("AIHijack", "[$sourceTag] $family AI photo capture already in progress")
        return
    }

    requests.prepareForLockScreen()
    requests.beginForegroundWork(foregroundStatus)
    requests.startAskWindow(offerSpokenQuestion)
    val startedAt = System.currentTimeMillis()
    val context = requests.context
    requests.scope.launch(Dispatchers.IO) {
        try {
            val imageBytes = takePhoto()
            val imageFile = File(context.cacheDir, "${filePrefix}_${System.currentTimeMillis()}.jpg")
            imageFile.writeBytes(imageBytes)
            withContext(Dispatchers.Main) {
                requests.onPhotoReady(
                    imagePath = imageFile.absolutePath,
                    source = imageSource,
                    transferDurationMs = System.currentTimeMillis() - startedAt,
                )
            }
        } catch (error: Exception) {
            Log.e("AIHijack", "[$sourceTag] $family AI photo failed", error)
            withContext(Dispatchers.Main) {
                requests.clearPendingQuestion(sourceTag)
                requests.finishForegroundWork()
                Toast.makeText(context, failureMessage(error), Toast.LENGTH_LONG).show()
            }
        } finally {
            busy.set(false)
        }
    }
}

/** Eyevue: the AI-photo command (0x31), streamed over AA15. */
internal class EyevueQuestionPhoto(private val manager: () -> EyevueManager) : QuestionPhotoSource {
    private val busy = AtomicBoolean(false)
    override val inProgress: Boolean get() = busy.get()

    override fun capture(requests: QuestionPhotoRequests, sourceTag: String, offerSpokenQuestion: Boolean) {
        val manager = manager()
        if (!manager.isConnected()) {
            requests.clearPendingQuestion(sourceTag)
            Toast.makeText(requests.context, "Connect Eyevue glasses first.", Toast.LENGTH_SHORT).show()
            return
        }
        captureNative(
            busy, requests, sourceTag, offerSpokenQuestion,
            family = "Eyevue",
            foregroundStatus = "Capturing image from Eyevue glasses",
            filePrefix = "Eyevue_AI",
            imageSource = ImageQuestionSource.HIGH_QUALITY,
            failureMessage = { "Eyevue did not return a photo. Please try again." },
            // Eyevue's AI-photo path streams over AA15 only for the 0x31 command.
            takePhoto = { manager.capturePhotoForAi() },
        )
    }
}

/**
 * TuneBuds: Detailed = the Wi-Fi high-resolution photo (slow, via the phone hotspot); all other
 * levels = the fast BLE transfer (documented 0xE1 0x01 -> 0xE3).
 */
internal class TuneBudsQuestionPhoto(
    private val manager: () -> TuneBudsManager,
    private val ensureTransportPermissions: (feature: String, onGranted: () -> Unit) -> Unit,
) : QuestionPhotoSource {
    private val busy = AtomicBoolean(false)
    override val inProgress: Boolean get() = busy.get()

    // Voice questions never waited for a TuneBuds photo.
    override val blocksVoiceQuestions: Boolean = false

    override fun capture(requests: QuestionPhotoRequests, sourceTag: String, offerSpokenQuestion: Boolean) {
        if (requests.thumbnailQuality == ImageThumbnailQuality.DETAILED) {
            captureHighQuality(requests, sourceTag, offerSpokenQuestion)
        } else {
            captureBle(requests, sourceTag, offerSpokenQuestion)
        }
    }

    private fun captureBle(requests: QuestionPhotoRequests, sourceTag: String, offerSpokenQuestion: Boolean) {
        val manager = manager()
        if (!manager.isConnected()) {
            requests.clearPendingQuestion(sourceTag)
            Toast.makeText(requests.context, "Connect TuneBuds glasses first.", Toast.LENGTH_SHORT).show()
            return
        }
        captureNative(
            busy, requests, sourceTag, offerSpokenQuestion,
            family = "TuneBuds",
            foregroundStatus = "Capturing image from TuneBuds glasses",
            filePrefix = "TuneBuds_AI",
            imageSource = ImageQuestionSource.FAST_PREVIEW,
            failureMessage = { it.message ?: "TuneBuds photo capture failed" },
            takePhoto = { manager.capturePhotoForAi() },
        )
    }

    private fun captureHighQuality(requests: QuestionPhotoRequests, sourceTag: String, offerSpokenQuestion: Boolean) {
        val manager = manager()
        val context = requests.context
        if (!manager.isConnected()) {
            requests.clearPendingQuestion(sourceTag)
            Toast.makeText(context, "Connect TuneBuds glasses first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!busy.compareAndSet(false, true)) {
            Log.i("AIHijack", "[$sourceTag] TuneBuds AI photo capture already in progress")
            return
        }
        if (!hasBluetooth(context) || !hasWifiP2pPermission(context)) {
            busy.set(false)
            requests.clearPendingQuestion(sourceTag)
            ensureTransportPermissions("TuneBuds high-resolution capture") {
                captureHighQuality(requests, sourceTag, offerSpokenQuestion)
            }
            return
        }

        requests.prepareForLockScreen()
        requests.beginForegroundWork("Capturing high-resolution image from TuneBuds glasses")
        requests.startAskWindow(offerSpokenQuestion)
        val startedAt = System.currentTimeMillis()
        requests.scope.launch(Dispatchers.IO) {
            try {
                GlassesPhotoCapture.withCapture(DeviceClass.TUNEBUDS) { lease ->
                    var hotspot: TuneBudsLocalHotspot? = null
                    var tempDir: File? = null
                    try {
                        Log.i("AIHijack", "[$sourceTag] TuneBuds Detailed requested – using Wi-Fi high-quality path")
                        manager.takePhotoBlocking(lease)
                        Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds photo saved and camera closed")

                        hotspot = TuneBudsLocalHotspot(context)
                        tempDir = File(context.cacheDir, "tunebuds_ai_high_${System.currentTimeMillis()}").apply { mkdirs() }
                        val credentials = hotspot.start()
                        Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds hotspot started ssid=${credentials.ssid}")
                        val baseUrl = manager.startFileManager(credentials.ssid, credentials.password, credentials.channel)
                            ?: throw IOException("TuneBuds did not report its media server address")
                        Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds media endpoint $baseUrl")

                        val sync = TuneBudsMediaSync(manager, hotspot, tempDir)
                        var photoItem: TuneBudsMediaItem? = null
                        for (attempt in 0 until 12) {
                            val photos = sync.fetchManifest(baseUrl).filter { it.type == TuneBudsMediaType.PHOTO }
                            Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds manifest attempt ${attempt + 1}: ${photos.size} photos")
                            if (photos.isNotEmpty()) {
                                // Existing Detailed storage selection is newest timestamped remote name.
                                photoItem = photos.maxByOrNull { it.remoteName }
                                break
                            }
                            if (attempt < 11) delay(1000)
                        }
                        val item = photoItem ?: throw IOException("No photos found on TuneBuds after capture")
                        Log.i("ImageQuestionTransfer", "[$sourceTag] TuneBuds downloading ${item.remoteName} via Wi-Fi")
                        val downloaded = sync.downloadSingle(baseUrl, item)
                        lease.ensureActive()
                        val imageFile = File(context.cacheDir, "TuneBuds_AI_HQ_${System.currentTimeMillis()}.jpg")
                        downloaded.copyTo(imageFile, overwrite = true)
                        withContext(Dispatchers.Main) {
                            requests.onPhotoReady(
                                imagePath = imageFile.absolutePath,
                                source = ImageQuestionSource.HIGH_QUALITY,
                                transferDurationMs = System.currentTimeMillis() - startedAt,
                            )
                        }
                    } finally {
                        withContext(Dispatchers.IO + NonCancellable) {
                            runCatching { manager.finishTransferBlocking() }
                            runCatching { hotspot?.stop() }
                            tempDir?.let { runCatching { it.deleteRecursively() } }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e("AIHijack", "[$sourceTag] TuneBuds high-quality photo failed", error)
                withContext(Dispatchers.Main) {
                    requests.clearPendingQuestion(sourceTag)
                    requests.finishForegroundWork()
                    Toast.makeText(
                        context,
                        error.message ?: "TuneBuds high-resolution capture failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                busy.set(false)
            }
        }
    }
}
