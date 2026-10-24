package com.fersaiyan.cyanbridge.shared.ui.recordings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs
import com.fersaiyan.cyanbridge.shared.recordings.MeetingRecordingUiState
import com.fersaiyan.cyanbridge.shared.recordings.RecordingItem
import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptDialogUiState
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptionProgressUiState
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface RecordingTranscription {
    data class Success(val text: String, val noteSaved: Boolean) : RecordingTranscription
    data class Failure(val message: String) : RecordingTranscription
}

/** Meeting captures, their audio files and the transcription pipeline. */
interface RecordingsBackend {
    fun sessions(): Flow<List<RecordingItem>>
    fun meetingState(): MeetingRecordingUiState

    /** Meeting capture start/stop updates while collected. */
    fun meetingUpdates(): Flow<MeetingRecordingUiState>
    suspend fun recentMedia(): List<SyncedMediaItem>
    fun stopMeetingCapture()

    /** Null when the recording's audio is playable, else why it is not. */
    fun audioProblem(id: Long): AudioProblem?
    fun startPlayback(id: Long, onFinished: () -> Unit)
    fun stopPlayback()

    /** Transcribes and stores the transcript and a note; reports progress on the main thread. */
    suspend fun transcribe(id: Long, onProgress: (TranscriptionProgressUiState) -> Unit): RecordingTranscription
    suspend fun storedTranscript(id: Long): String?
    suspend fun delete(ids: List<Long>): Set<Long>
}

enum class AudioProblem { MISSING_PATH, NOT_FOUND }

sealed interface RecordingsEffect {
    data class Message(val message: UiMessage) : RecordingsEffect

    /** Transcription failed ([error] is set when it threw); the host may offer to send logs for local runtime errors. */
    data class TranscriptionFailed(val message: String?, val error: Throwable?) : RecordingsEffect
}

class RecordingsViewModel(private val backend: RecordingsBackend) : ViewModel() {
    var sessions by mutableStateOf<List<RecordingItem>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var recentMedia by mutableStateOf<List<SyncedMediaItem>>(emptyList())
        private set
    var meeting by mutableStateOf(backend.meetingState())
        private set
    var playingId by mutableStateOf<Long?>(null)
        private set
    var transcribingId by mutableStateOf<Long?>(null)
        private set
    var transcriptionProgress by mutableStateOf<TranscriptionProgressUiState?>(null)
        private set
    var transcriptDialog by mutableStateOf<TranscriptDialogUiState?>(null)

    private val effectChannel = Channel<RecordingsEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private val ephemeralTranscripts = mutableMapOf<Long, String>()
    private var visibleJobs: List<Job> = emptyList()

    /** The host became visible: follow captures and meeting state, reload recent media. */
    fun onStart() {
        visibleJobs.forEach { it.cancel() }
        meeting = backend.meetingState()
        isLoading = true
        visibleJobs = listOf(
            viewModelScope.launch { backend.meetingUpdates().collect { meeting = it } },
            viewModelScope.launch {
                backend.sessions().collect {
                    sessions = it
                    isLoading = false
                }
            },
            viewModelScope.launch { recentMedia = backend.recentMedia() },
        )
    }

    fun onStop() {
        visibleJobs.forEach { it.cancel() }
        visibleJobs = emptyList()
        stopPlayback()
    }

    fun stopMeetingCapture() = backend.stopMeetingCapture()

    fun togglePlayback(item: RecordingItem) {
        when (backend.audioProblem(item.id)) {
            AudioProblem.MISSING_PATH -> return notify("Missing audio path")
            AudioProblem.NOT_FOUND -> return notify("Audio file not found", long = true)
            null -> Unit
        }
        if (playingId == item.id) return stopPlayback()
        stopPlayback()
        playingId = item.id
        runCatching { backend.startPlayback(item.id, ::stopPlayback) }.onFailure {
            notify("Failed to play audio: ${it.message}", long = true)
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        backend.stopPlayback()
        playingId = null
    }

    fun transcribe(item: RecordingItem) {
        if (transcribingId != null) return notify("Already transcribing...")
        if (backend.audioProblem(item.id) != null) return notify("Audio file not found", long = true)
        transcribingId = item.id
        viewModelScope.launch {
            try {
                when (val result = backend.transcribe(item.id) { transcriptionProgress = it }) {
                    is RecordingTranscription.Success -> {
                        ephemeralTranscripts[item.id] = result.text
                        notify(
                            if (result.noteSaved) {
                                "Transcription complete. Saved to Notes in the Chats tab."
                            } else {
                                "Transcription complete, but the note could not be saved."
                            },
                            long = true,
                        )
                    }
                    is RecordingTranscription.Failure -> {
                        PlatformLogger.e(TAG, "Transcription failed: ${result.message}")
                        effectChannel.send(RecordingsEffect.TranscriptionFailed(result.message, null))
                        notify("Transcription failed: ${result.message}", long = true)
                    }
                }
            } catch (throwable: Throwable) {
                PlatformLogger.e(TAG, "Transcription threw an exception", throwable)
                effectChannel.send(RecordingsEffect.TranscriptionFailed(throwable.message, throwable))
                notify("Transcription failed: ${throwable.message}", long = true)
            } finally {
                transcriptionProgress = null
                transcribingId = null
            }
        }
    }

    fun viewTranscript(item: RecordingItem) {
        viewModelScope.launch {
            val storedText = backend.storedTranscript(item.id)
            val text = storedText ?: ephemeralTranscripts[item.id]
            if (text.isNullOrBlank()) return@launch notify("No transcription available yet")
            val stored = !storedText.isNullOrBlank()
            transcriptDialog = TranscriptDialogUiState(
                title = if (stored) "Transcription (stored)" else "Transcription",
                text = if (!stored && !MemoryPrefs.Privacy.transcriptStorageEnabled.get()) {
                    "(Transcript storage is OFF in Settings; this text may not be persisted.)\n\n$text"
                } else {
                    text
                },
            )
        }
    }

    suspend fun delete(items: List<RecordingItem>): Set<Long> {
        val ids = items.map { it.id }.filter { id -> sessions.any { it.id == id } }
        if (playingId in ids) stopPlayback()
        return backend.delete(ids)
    }

    override fun onCleared() = backend.stopPlayback()

    private fun notify(text: String, long: Boolean = false) {
        effectChannel.trySend(RecordingsEffect.Message(UiMessage(text, long)))
    }

    private companion object {
        const val TAG = "RecordingsListActivity"
    }
}
