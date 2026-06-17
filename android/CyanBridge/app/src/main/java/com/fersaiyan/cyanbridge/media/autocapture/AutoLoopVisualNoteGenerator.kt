package com.fersaiyan.cyanbridge.media.autocapture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelRequestPriority
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object AutoLoopVisualNoteGenerator {
    private const val TAG = "AutoLoopVisual"
    private const val MAX_IMAGE_AGE_MS = 3L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)
    private val localModelsProvider = LocalModelsProvider()

    fun enqueue(context: Context, loopIndex: Int) {
        val appContext = context.applicationContext
        if (!AutoAudioCapturePrefs.isVisualNotesEnabled(appContext)) return
        if (!inFlight.compareAndSet(false, true)) {
            Log.i(TAG, "Visual note already in progress; skipping loop=$loopIndex")
            return
        }

        scope.launch {
            try {
                captureDescribeAndAppend(appContext, loopIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "Visual note pipeline failed: ${t.message}", t)
            } finally {
                inFlight.set(false)
            }
        }
    }

    private suspend fun captureDescribeAndAppend(context: Context, loopIndex: Int) {
        if (!BleOperateManager.getInstance().isConnected) {
            Log.w(TAG, "Skipping visual note: glasses not connected")
            return
        }

        val image = captureThumbnail(context, loopIndex)
        if (image == null || !image.exists() || image.length() < 1024L) {
            Log.w(TAG, "Skipping visual note: no usable thumbnail")
            return
        }

        if (!isImageFresh(image, MAX_IMAGE_AGE_MS)) {
            Log.w(TAG, "Skipping visual note: image is stale (lastModified=${image.lastModified()})")
            showToast(context, "Thumbnail too old, skipping scene analysis")
            return
        }

        val description = describeWithGemma(context, image) ?: return
        val fact = buildFact(description)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
        CandidateUserFactsStorage.append(context, date, listOf(fact))
        Log.i(TAG, "Appended visual candidate fact for loop=$loopIndex")
    }

    private fun buildFact(description: String): String {
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis()))
        val cleaned = description
            .trim()
            .removePrefix("- ")
            .removePrefix("* ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val clipped = if (cleaned.length > 190) cleaned.take(187).trimEnd() + "..." else cleaned
        return "Glasses scene $time: $clipped"
    }

    private suspend fun describeWithGemma(context: Context, image: File): String? {
        val selected = LocalModelStorageRepository.resolveSelectedModel(context)
        if (selected == null) {
            Log.w(TAG, "Skipping visual note: no local model selected")
            return null
        }

        val settings = LocalModelSettingsRepository.getForModel(context, selected.id)
        if (settings.modelRuntime != LocalModelRuntime.LITERT) {
            Log.w(TAG, "Skipping visual note: selected model runtime is not LiteRT")
            return null
        }

        val hint = "${selected.displayName} ${selected.catalogId.orEmpty()}".lowercase(Locale.US)
        if (!hint.contains("gemma") || (!hint.contains("gemma-4") && !hint.contains("gemma4"))) {
            Log.w(TAG, "Skipping visual note: selected model is not Gemma 4")
            return null
        }

        val raw = localModelsProvider.streamChat(
            context = context,
            messages = listOf(
                mapOf(
                    "role" to "User",
                    "content" to "Describe this scene in one concise sentence focusing on meaningful context (people, activity, location, tools, text on screen).",
                ),
            ),
            imagePaths = listOf(image.absolutePath),
            requestPriority = LocalModelRequestPriority.HIGH,
        ).trim()

        if (raw.isBlank()) {
            Log.w(TAG, "Skipping visual note: empty Gemma response")
            return null
        }

        return raw
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.removePrefix("- ")
            ?.removePrefix("* ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun captureThumbnail(context: Context, loopIndex: Int): File? {
        val outDir = context.getExternalFilesDir("DCIM") ?: context.filesDir
        val file = File(outDir, "AUTO_LOOP_THUMB_${loopIndex}_${System.currentTimeMillis()}.jpg")
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
        }

        val gotChunk = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val done = CompletableDeferred<File?>()

        val thumbCallback: (Int, Boolean, ByteArray?) -> Unit = { _, isComplete, data ->
            if (data != null && data.isNotEmpty()) {
                gotChunk.set(true)
                runCatching {
                    FileOutputStream(file, true).use { out -> out.write(data) }
                }.onFailure {
                    Log.e(TAG, "Failed writing thumbnail chunk: ${it.message}", it)
                }
            }

            if (isComplete && completed.compareAndSet(false, true)) {
                if (!done.isCompleted) {
                    done.complete(if (file.exists() && file.length() >= 1024L) file else null)
                }
            }
        }

        runCatching {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02)) { _, _ -> }
        }
        delay(250)
        runCatching {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x01)) { _, _ -> }
        }
        delay(2500)

        LargeDataHandler.getInstance().getPictureThumbnails(thumbCallback)

        delay(4500)
        if (!gotChunk.get() && !completed.get()) {
            LargeDataHandler.getInstance().getPictureThumbnails(thumbCallback)
        }

        return withTimeoutOrNull(14_000) { done.await() }
    }

    private fun isImageFresh(file: File, maxAgeMs: Long): Boolean {
        val age = System.currentTimeMillis() - file.lastModified()
        return age >= 0 && age <= maxAgeMs
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
