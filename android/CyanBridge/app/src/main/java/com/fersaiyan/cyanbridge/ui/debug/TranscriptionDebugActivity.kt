package com.fersaiyan.cyanbridge.ui.debug

import android.content.Context
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.ai.transcription.ChunkingTranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionEvent
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionRequest
import com.fersaiyan.cyanbridge.ai.transcription.backend.FakeTranscriptionBackend
import com.fersaiyan.cyanbridge.ai.transcription.backend.HttpTranscriptionBackend
import com.fersaiyan.cyanbridge.ai.transcription.storage.RoomTranscriptStore
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.shared.ui.debug.TranscriptionDebugBackend
import com.fersaiyan.cyanbridge.shared.ui.debug.TranscriptionDebugEvent
import com.fersaiyan.cyanbridge.shared.ui.debug.TranscriptionDebugScreen
import com.fersaiyan.cyanbridge.shared.ui.debug.TranscriptionDebugViewModel
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.setThemedComposeScreen
import com.fersaiyan.cyanbridge.ui.showToast
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Minimal manual POC entry point for Chapter 6.
 *
 * Launch via:
 *  adb shell am start -n com.fersaiyan.cyanbridge/.ui.debug.TranscriptionDebugActivity
 */
class TranscriptionDebugActivity : AppCompatActivity() {
    private val viewModel by viewModels<TranscriptionDebugViewModel> {
        viewModelFactory { initializer { TranscriptionDebugViewModel(ChunkedTranscriptionDebug(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { viewModel.messages.collect { showToast(it) } }
        setThemedComposeScreen {
            val vm = viewModel
            TranscriptionDebugScreen(
                endpointUrl = vm.endpointUrl,
                apiKey = vm.apiKey,
                useHttp = vm.useHttp,
                transcriptStorageEnabled = vm.transcriptStorageEnabled,
                latestSessionInfo = vm.latestSessionInfo,
                isTranscribing = vm.isTranscribing,
                progress = vm.progressPercent,
                progressText = vm.progressText,
                persistedText = vm.persistedText,
                output = vm.output,
                onEndpointUrlChange = { vm.endpointUrl = it },
                onApiKeyChange = { vm.apiKey = it },
                onUseHttpChange = { vm.useHttp = it },
                onStorageEnabledChange = vm::setTranscriptStorage,
                onSaveEndpoint = vm::saveEndpoint,
                onLoadLatest = vm::loadLatestSession,
                onTranscribe = vm::transcribe,
            )
        }
    }
}

private class ChunkedTranscriptionDebug(private val context: Context) : TranscriptionDebugBackend {
    override suspend fun latestSession(): CaptureSession? =
        MyApplication.database.captureSessionDao().getAllSessions().first().firstOrNull()

    override fun audioExists(path: String): Boolean = File(path).exists()

    override fun transcribe(session: CaptureSession, endpointUrl: String?, apiKey: String?) =
        ChunkingTranscriptionService(
            backend = endpointUrl?.let { HttpTranscriptionBackend(endpointUrl = it, apiKey = apiKey) }
                ?: FakeTranscriptionBackend(fixedText = null),
            transcriptStore = RoomTranscriptStore(context = context, dao = MyApplication.database.captureTranscriptDao()),
        ).transcribe(
            TranscriptionRequest(audioFile = File(session.audioPath), captureSessionId = session.id, languageHint = null),
        ).map { event ->
            when (event) {
                is TranscriptionEvent.Started -> TranscriptionDebugEvent.Started(event.totalChunks, event.provider)
                is TranscriptionEvent.Progress -> TranscriptionDebugEvent.Progress(event.percent, event.message)
                is TranscriptionEvent.Completed ->
                    TranscriptionDebugEvent.Completed(event.transcript, event.provider, event.persisted)
                is TranscriptionEvent.Failed -> TranscriptionDebugEvent.Failed(event.error.debugMessage, event.canRetry)
            }
        }
}
