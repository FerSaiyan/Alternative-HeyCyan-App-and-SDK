package com.fersaiyan.cyanbridge.shared.ui.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface TranscriptionDebugEvent {
    data class Started(val chunks: Int, val provider: String) : TranscriptionDebugEvent
    data class Progress(val percent: Int, val message: String) : TranscriptionDebugEvent
    data class Completed(val transcript: String, val provider: String, val persisted: Boolean) : TranscriptionDebugEvent
    data class Failed(val message: String, val canRetry: Boolean) : TranscriptionDebugEvent
}

/** Capture sessions and the chunked transcription pipeline the diagnostics screen exercises. */
interface TranscriptionDebugBackend {
    suspend fun latestSession(): CaptureSession?
    fun audioExists(path: String): Boolean

    /** Transcribes the session's audio with the HTTP endpoint, or the fake backend when [endpointUrl] is null. */
    fun transcribe(session: CaptureSession, endpointUrl: String?, apiKey: String?): Flow<TranscriptionDebugEvent>
}

class TranscriptionDebugViewModel(private val backend: TranscriptionDebugBackend) : ViewModel() {
    private val endpointPrefs = AssistantPrefs.TranscriptionEndpoint
    private var latestSession: CaptureSession? = null

    var endpointUrl by mutableStateOf(endpointPrefs.endpointUrl.get().orEmpty())
    var apiKey by mutableStateOf(endpointPrefs.apiKey.get().orEmpty())
    var useHttp by mutableStateOf(false)
    var transcriptStorageEnabled by mutableStateOf(MemoryPrefs.Privacy.transcriptStorageEnabled.get())
        private set
    var latestSessionInfo by mutableStateOf("(no session loaded)")
        private set
    var isTranscribing by mutableStateOf(false)
        private set
    var progressPercent by mutableStateOf(0)
        private set
    var progressText by mutableStateOf("")
        private set
    var persistedText by mutableStateOf("")
        private set
    var output by mutableStateOf("")
        private set

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    init {
        loadLatestSession()
    }

    fun setTranscriptStorage(enabled: Boolean) {
        transcriptStorageEnabled = enabled
        MemoryPrefs.Privacy.transcriptStorageEnabled.set(enabled)
    }

    fun saveEndpoint() {
        endpointPrefs.endpointUrl.set(endpointUrl.takeIf { it.isNotBlank() }?.trim())
        endpointPrefs.apiKey.set(apiKey.takeIf { it.isNotBlank() })
        messageChannel.trySend(UiMessage("Saved endpoint config"))
    }

    fun loadLatestSession() {
        viewModelScope.launch {
            val s = backend.latestSession().also { latestSession = it }
            latestSessionInfo = if (s == null) {
                "No capture sessions found yet. Record a meeting first."
            } else {
                "Latest session id=${s.id}\nstartedAt=${s.startedAt}\ndurationSec=${s.durationSec}\naudioPath=${s.audioPath}"
            }
        }
    }

    fun transcribe() {
        val session = latestSession
            ?: return run { messageChannel.trySend(UiMessage("No capture session available")) }
        if (!backend.audioExists(session.audioPath)) {
            messageChannel.trySend(UiMessage("Audio file missing: ${session.audioPath}", long = true))
            return
        }
        val events = backend.transcribe(
            session,
            endpointUrl = if (useHttp) endpointUrl.trim() else null,
            apiKey = apiKey.trim().takeUnless { it.isBlank() },
        )
        progressPercent = 0
        progressText = "Starting…"
        output = ""
        persistedText = ""
        isTranscribing = true
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    is TranscriptionDebugEvent.Started ->
                        progressText = "Started (${event.chunks} chunks) provider=${event.provider}"
                    is TranscriptionDebugEvent.Progress -> {
                        progressPercent = event.percent
                        progressText = "${event.percent}% — ${event.message}"
                    }
                    is TranscriptionDebugEvent.Completed -> {
                        progressPercent = 100
                        progressText = "Done (provider=${event.provider})"
                        output = event.transcript
                        persistedText = if (event.persisted) {
                            "Transcript persisted to DB (capture_transcripts)."
                        } else {
                            "Transcript NOT persisted (storage disabled or missing session id)."
                        }
                    }
                    is TranscriptionDebugEvent.Failed -> {
                        progressText = "Failed: ${event.message}"
                        persistedText = if (event.canRetry) "Retryable" else "Not retryable"
                    }
                }
                isTranscribing = false
            }
        }
    }
}
