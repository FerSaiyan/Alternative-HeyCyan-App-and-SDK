package com.fersaiyan.cyanbridge.hil

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.download.ModelDownloadForegroundService
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtNpuSupport
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The 2.5.1 download flow on a real phone, end to end through the app's own service: catalog download from
 * Hugging Face (with this phone's NPU runtime first, for NPU models), then "Optimizing <model> for this phone"
 * (one load + unload that fills the engine cache), then chat's first load and first spoken answer.
 * Logs every distinct notification text and service state with its time. Not part of CI: skips unless df_model
 * names a catalog id (gemma4-e2b-it-litert | gemma4-e2b-it-npu). df_out = results JSONL.
 */
@RunWith(AndroidJUnit4::class)
class DownloadPrepareFlowTest {
    @Test fun downloadPrepareFlow() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val catalogId = args.getString("df_model").orEmpty()
        assumeTrue("Requires df_model", LocalModelCatalogRepository.findById(catalogId) != null)
        // getExternalFilesDir creates the app's external folder (Device Run clears app storage before the run).
        val external = checkNotNull(context.getExternalFilesDir(null))
        val out = File(args.getString("df_out") ?: File(external, "litert-eval/download_flow.jsonl").path)
        out.parentFile?.mkdirs()
        val t0 = System.currentTimeMillis()
        fun log(map: Map<String, Any?>) {
            val line = JSONObject(map + ("t_s" to (System.currentTimeMillis() - t0) / 1000.0)).toString()
            println("DF $line")
            out.appendText(line + "\n")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val npu = LiteRtNpuSupport.status(context)
        log(mapOf("event" to "device", "model" to Build.MODEL, "soc" to npu.socModel, "npu" to npu.reason, "catalog" to catalogId))

        val finished = CompletableDeferred<Pair<Boolean, String?>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                finished.complete(intent.getBooleanExtra(ModelDownloadForegroundService.EXTRA_SUCCESS, false) to
                    intent.getStringExtra(ModelDownloadForegroundService.EXTRA_ERROR))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ModelDownloadForegroundService.BROADCAST_DOWNLOAD_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ModelDownloadForegroundService.startDownload(context, catalogId, null)

        // Watch the notification and the service state until the download and its preparation are both done.
        val nm = context.getSystemService(NotificationManager::class.java)
        var lastText: String? = null
        var lastState: String? = null
        var doneAt = 0L
        val deadline = System.currentTimeMillis() + 35 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val text = nm.activeNotifications.firstOrNull { it.id == ModelDownloadForegroundService.NOTIFICATION_ID }
                ?.notification?.extras?.getCharSequence("android.text")?.toString()
                ?.replace(Regex("\\d+%"), "N%")
            if (text != null && text != lastText) { log(mapOf("event" to "notification", "text" to text)); lastText = text }
            val state = "downloading=${ModelDownloadForegroundService.isDownloading} preparing=${ModelDownloadForegroundService.isPreparing}"
            if (state != lastState) { log(mapOf("event" to "state", "state" to state)); lastState = state }
            if (finished.isCompleted && !ModelDownloadForegroundService.isDownloading && !ModelDownloadForegroundService.isPreparing) {
                if (doneAt == 0L) doneAt = System.currentTimeMillis()
                if (System.currentTimeMillis() - doneAt > 4_000L) break
            }
            delay(250L)
        }
        val (ok, error) = withTimeoutOrNull(1_000L) { finished.await() } ?: (false to "timeout")
        runCatching { context.unregisterReceiver(receiver) }
        log(mapOf("event" to "finished", "success" to ok, "error" to error, "npu_after" to LiteRtNpuSupport.status(context).reason))
        assumeTrue("download failed: $error", ok)

        // Chat's first use: load as the provider does, then one spoken question (recording from the test assets).
        val installed = checkNotNull(LocalModelStorageRepository.findByCatalogId(context, catalogId)) { "not installed" }
        LocalModelStorageRepository.setSelectedModelId(context, installed.id)
        val loadStart = System.currentTimeMillis()
        val details = LocalChatSessionManager.ensureModelLoaded(
            context, installed, LocalModelCatalogRepository.findById(catalogId), LocalModelSettingsRepository.getForModel(context, installed.id),
        )
        log(mapOf("event" to "first_load", "load_s" to (System.currentTimeMillis() - loadStart) / 1000.0,
            "backend" to details.activeBackend.name, "fallback" to details.fallbackReason, "file" to installed.fileName))
        val wav = File(context.cacheDir, "df_question.wav").also { f ->
            instrumentation.context.assets.open("glasses_questions/en_capital_city__piper-lessac.wav").use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        val asked = System.currentTimeMillis()
        var firstToken = 0L
        val reply = runCatching {
            LocalModelsProvider().streamChat(
                context = context,
                messages = listOf(mapOf("role" to "System", "content" to "Answer the spoken question in one short sentence."), mapOf("role" to "User", "content" to "")),
                audioPath = wav.path,
                maxTokens = 32,
                onToken = { if (firstToken == 0L && it.isNotEmpty()) firstToken = System.currentTimeMillis() },
            )
        }
        log(mapOf("event" to "first_answer", "ttft_s" to if (firstToken > 0) (firstToken - asked) / 1000.0 else null,
            "total_s" to (System.currentTimeMillis() - asked) / 1000.0, "reply" to reply.getOrNull()?.take(120),
            "error" to reply.exceptionOrNull()?.toString()))
        LocalChatSessionManager.unload()
    }
}
