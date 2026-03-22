package com.fersaiyan.cyanbridge.ui.recordings

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.data.repository.CyanBridgeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class RecordingItem(
    val session: CaptureSession,
    val isPlaying: Boolean = false,
    val isTranscribing: Boolean = false,
    val transcription: String? = null,
)

data class RecordingsState(
    val recordings: List<RecordingItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

class RecordingsViewModel(
    private val repository: CyanBridgeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingsState())
    val uiState: StateFlow<RecordingsState> = _uiState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingId: Long? = null

    init {
        loadSessions()
    }

    private fun loadSessions() {
        viewModelScope.launch {
            repository.getAllCaptureSessions().collect { sessions ->
                val current = _uiState.value.recordings.associateBy { it.session.id }
                val items = sessions.map { session ->
                    current[session.id] ?: RecordingItem(session = session)
                }
                _uiState.value = _uiState.value.copy(
                    recordings = items,
                    isLoading = false,
                )
            }
        }
    }

    fun onPlayClick(session: CaptureSession) {
        val path = session.audioPath
        if (path.isBlank() || !File(path).exists()) {
            _uiState.value = _uiState.value.copy(error = "Audio file not found")
            return
        }

        if (currentlyPlayingId == session.id) {
            stopPlayback()
            return
        }

        stopPlayback()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mp = MediaPlayer()
                mediaPlayer = mp
                currentlyPlayingId = session.id
                mp.setDataSource(path)
                mp.setOnCompletionListener {
                    viewModelScope.launch(Dispatchers.Main) {
                        stopPlayback()
                    }
                }
                mp.prepare()
                mp.start()

                updateRecordingState(session.id, isPlaying = true)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(error = "Failed to play audio: ${e.message}")
                    stopPlayback()
                }
            }
        }
    }

    fun onTranscribeClick(session: CaptureSession) {
        val path = session.audioPath
        if (path.isBlank() || !File(path).exists()) {
            _uiState.value = _uiState.value.copy(error = "Audio file not found")
            return
        }

        updateRecordingState(session.id, isTranscribing = true)
        _uiState.value = _uiState.value.copy(error = null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiKey = BuildConfig.OPENAI_API_KEY
                val result = if (apiKey.isNotBlank()) {
                    transcribeWithOpenAI(session, apiKey)
                } else {
                    transcribeOffline(session)
                }

                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { text ->
                            updateRecordingState(session.id, isTranscribing = false, transcription = text)
                        },
                        onFailure = { err ->
                            updateRecordingState(session.id, isTranscribing = false)
                            _uiState.value = _uiState.value.copy(error = "Transcription failed: ${err.message}")
                        },
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateRecordingState(session.id, isTranscribing = false)
                    _uiState.value = _uiState.value.copy(error = "Transcription failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun transcribeWithOpenAI(session: CaptureSession, apiKey: String): Result<String> {
        return try {
            val provider = com.fersaiyan.cyanbridge.ai.transcription.OpenAIWhisperTranscriptionProvider(apiKey = apiKey)
            val service = com.fersaiyan.cyanbridge.ai.transcription.DefaultTranscriptionService(
                context = com.fersaiyan.cyanbridge.ui.MyApplication.CONTEXT,
                repository = repository,
                provider = provider,
                chunker = com.fersaiyan.cyanbridge.ai.transcription.Mp4AudioChunker(com.fersaiyan.cyanbridge.ui.MyApplication.CONTEXT),
            )
            val result = service.transcribe(
                session = session,
                options = com.fersaiyan.cyanbridge.ai.transcription.TranscriptionService.Options(chunkDurationSec = 60),
                onProgress = {},
            )
            when (result) {
                is com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult.Success -> Result.success(result.text)
                is com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult.Failure -> Result.failure(Exception(result.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun transcribeOffline(session: CaptureSession): Result<String> {
        return try {
            val provider = com.fersaiyan.cyanbridge.ai.transcription.moonshine.MoonshineTranscriptionProvider(
                com.fersaiyan.cyanbridge.ui.MyApplication.CONTEXT,
                com.fersaiyan.cyanbridge.ai.transcription.moonshine.MoonshineModelManager.modelDir(
                    com.fersaiyan.cyanbridge.ui.MyApplication.CONTEXT,
                    com.fersaiyan.cyanbridge.ai.transcription.moonshine.MoonshineModelManager.chooseDefault(languageHint = null),
                ),
                com.fersaiyan.cyanbridge.ai.transcription.moonshine.MoonshineModelManager.chooseDefault(languageHint = null).modelArch,
            )
            val service = com.fersaiyan.cyanbridge.ai.transcription.DefaultTranscriptionService(
                context = com.fersaiyan.cyanbridge.ui.MyApplication.CONTEXT,
                repository = repository,
                provider = provider,
                chunker = com.fersaiyan.cyanbridge.ai.transcription.NoOpAudioChunker(),
            )
            val result = service.transcribe(
                session = session,
                options = com.fersaiyan.cyanbridge.ai.transcription.TranscriptionService.Options(chunkDurationSec = 60),
                onProgress = {},
            )
            when (result) {
                is com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult.Success -> Result.success(result.text)
                is com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult.Failure -> Result.failure(Exception(result.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun onViewTranscription(session: CaptureSession) {
        viewModelScope.launch(Dispatchers.IO) {
            val record = repository.getTranscriptionByCaptureSessionId(session.id)
            val textToShow = record?.transcriptText ?: _uiState.value.recordings
                .find { it.session.id == session.id }?.transcription

            withContext(Dispatchers.Main) {
                if (textToShow.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(error = "No transcription available yet")
                } else {
                    _showTranscriptionDialog.value = textToShow
                }
            }
        }
    }

    private val _showTranscriptionDialog = MutableStateFlow<String?>(null)
    val showTranscriptionDialog: StateFlow<String?> = _showTranscriptionDialog.asStateFlow()

    fun dismissTranscriptionDialog() {
        _showTranscriptionDialog.value = null
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun updateRecordingState(
        sessionId: Long,
        isPlaying: Boolean? = null,
        isTranscribing: Boolean? = null,
        transcription: String? = null,
    ) {
        _uiState.value = _uiState.value.copy(
            recordings = _uiState.value.recordings.map { item ->
                if (item.session.id == sessionId) {
                    item.copy(
                        isPlaying = isPlaying ?: false,
                        isTranscribing = isTranscribing ?: item.isTranscribing,
                        transcription = transcription ?: item.transcription,
                    )
                } else {
                    item.copy(isPlaying = false)
                }
            },
        )
    }

    private fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        if (currentlyPlayingId != null) {
            updateRecordingState(currentlyPlayingId!!, isPlaying = false)
        }
        currentlyPlayingId = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }

    class Factory(private val repository: CyanBridgeRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RecordingsViewModel(repository) as T
        }
    }
}
