package com.fersaiyan.cyanbridge.ui.recordings

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.transcription.AutomaticTranscriptionEngine
import com.fersaiyan.cyanbridge.ai.transcription.DefaultTranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionProgress
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionService
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.audio.MeetingCaptureService
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.localagent.userfacts.TranscriptCandidateFactsAppender
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.recordings.MeetingRecordingUiState
import com.fersaiyan.cyanbridge.shared.recordings.RecordingItem
import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptionProgressUiState
import com.fersaiyan.cyanbridge.shared.settings.CaptureSource
import com.fersaiyan.cyanbridge.shared.ui.recordings.AudioProblem
import com.fersaiyan.cyanbridge.shared.ui.recordings.RecordingTranscription
import com.fersaiyan.cyanbridge.shared.ui.recordings.RecordingsBackend
import com.fersaiyan.cyanbridge.shared.ui.recordings.RecordingsEffect
import com.fersaiyan.cyanbridge.shared.ui.recordings.RecordingsScreen
import com.fersaiyan.cyanbridge.shared.ui.recordings.RecordingsViewModel
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.fersaiyan.cyanbridge.ui.main.openAppDestination
import com.fersaiyan.cyanbridge.ui.showToast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsListDestination : NavScreen() {
    private val viewModel by viewModels<RecordingsViewModel> {
        viewModelFactory { initializer { RecordingsViewModel(AndroidRecordings(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is RecordingsEffect.Message -> showToast(effect.message)
                    is RecordingsEffect.TranscriptionFailed -> offerLocalRuntimeLogs(effect)
                }
            }
        }
        setContent {
            val vm = viewModel
            RecordingsScreen(
                sessions = vm.sessions,
                isLoading = vm.isLoading,
                recentSyncedMedia = vm.recentMedia,
                playingSessionId = vm.playingId,
                transcribingSessionId = vm.transcribingId,
                meetingRecording = vm.meeting,
                transcriptionProgress = vm.transcriptionProgress,
                transcriptDialog = vm.transcriptDialog,
                formatTimestamp = { ms -> java.text.DateFormat.getDateTimeInstance().format(Date(ms)) },
                loadThumbnail = ::loadThumbnail,
                onOpenSyncedMedia = { startActivity(AppRoute.SYNCED_MEDIA.intent(this)) },
                onOpenSyncedMediaItem = ::openSyncedMediaItem,
                onPlay = vm::togglePlayback,
                onTranscribe = vm::transcribe,
                onViewTranscript = vm::viewTranscript,
                onStopMeetingCapture = vm::stopMeetingCapture,
                onDeleteItems = vm::delete,
                onDismissTranscript = { vm.transcriptDialog = null },
                onDestinationSelected = { if (it != AppDestination.MEDIA) openAppDestination(it) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStop()
    }

    private fun offerLocalRuntimeLogs(failure: RecordingsEffect.TranscriptionFailed) {
        if (!DebugLogSupport.isLocalRuntimeIssue(failure.message, failure.error)) return
        DebugLogSupport.showSupportOptionsDialog(
            activity = activity,
            title = "Local runtime issue",
            issueType = "Local runtime issue",
            description = if (failure.error != null) {
                "Transcription crashed while using a local runtime. Sending logs can help diagnose LiteRT or GPU issues."
            } else {
                "Transcription failed while using a local runtime. Sending logs can help diagnose LiteRT or GPU issues."
            },
            extraInfo = linkedMapOf("screen" to "recordings", "transcription_engine" to "local_multimodal_litert"),
        )
    }

    private fun openSyncedMediaItem(item: SyncedMediaItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(item.contentUriString), "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, getString(R.string.synced_media_open_failed), Toast.LENGTH_SHORT).show() }
    }

    private suspend fun loadThumbnail(uriString: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = android.net.Uri.parse(uriString)
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, android.util.Size(256, 256), null)
            } else {
                contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }
            bitmap?.asImageBitmap()
        }.getOrNull()
    }
}

/** Capture sessions in Room, MediaPlayer playback and the automatic transcription engine. */
private class AndroidRecordings(private val context: Context) : RecordingsBackend {
    private val repository get() = MyApplication.repository
    private val mainHandler = Handler(Looper.getMainLooper())
    private var byId: Map<Long, CaptureSession> = emptyMap()
    private var mediaPlayer: MediaPlayer? = null

    override fun sessions() = repository.getAllCaptureSessions().map { sessions ->
        byId = sessions.associateBy { it.id }
        sessions.map { it.toRecordingItem() }
    }

    override fun meetingState(): MeetingRecordingUiState {
        val state = MeetingCapturePrefs.getState(context)
        return MeetingRecordingUiState(state.isRecording, state.source?.label())
    }

    override fun meetingUpdates(): Flow<MeetingRecordingUiState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != MeetingCaptureService.ACTION_STATE) return
                val source = intent.getStringExtra(MeetingCaptureService.EXTRA_SOURCE)
                    ?.let { runCatching { CaptureSource.valueOf(it) }.getOrNull() }
                trySend(MeetingRecordingUiState(intent.getBooleanExtra(MeetingCaptureService.EXTRA_IS_RECORDING, false), source?.label()))
            }
        }
        val broadcasts = LocalBroadcastManager.getInstance(context)
        broadcasts.registerReceiver(receiver, IntentFilter(MeetingCaptureService.ACTION_STATE))
        awaitClose { broadcasts.unregisterReceiver(receiver) }
    }

    private fun CaptureSource.label() = when (this) {
        CaptureSource.BLUETOOTH_MIC -> "Bluetooth mic"
        CaptureSource.PHONE_MIC -> "Phone mic"
    }

    override suspend fun recentMedia(): List<SyncedMediaItem> =
        withContext(Dispatchers.IO) { SyncedMediaQuery.query(context = context, imagesOnly = true, limit = 4) }

    override fun stopMeetingCapture() = MeetingCaptureService.stop(context)

    override fun audioProblem(id: Long): AudioProblem? {
        val path = byId[id]?.audioPath.orEmpty()
        return when {
            path.isBlank() -> AudioProblem.MISSING_PATH
            !File(path).exists() -> AudioProblem.NOT_FOUND
            else -> null
        }
    }

    override fun startPlayback(id: Long, onFinished: () -> Unit) {
        val player = MediaPlayer()
        mediaPlayer = player
        player.setDataSource(byId.getValue(id).audioPath)
        player.setOnCompletionListener { onFinished() }
        player.prepare()
        player.start()
    }

    override fun stopPlayback() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    override suspend fun transcribe(id: Long, onProgress: (TranscriptionProgressUiState) -> Unit): RecordingTranscription {
        val session = byId.getValue(id)
        val engine = AutomaticTranscriptionEngine.select(context)
        val title = "Transcribing (${engine.route.label})"
        onProgress(TranscriptionProgressUiState(title = title, message = "Preparing...", progress = 0))
        val result = withContext(Dispatchers.IO) {
            val service: TranscriptionService = DefaultTranscriptionService(
                context = context,
                repository = repository,
                provider = engine.provider,
                chunker = engine.chunker,
            )
            service.transcribe(
                session = session,
                options = TranscriptionService.Options(chunkDurationSec = engine.chunkDurationSec),
                onProgress = { progress -> mainHandler.post { onProgress(progress.toUiState(title)) } },
            )
        }
        return when (result) {
            is TranscriptionResult.Failure -> RecordingTranscription.Failure(result.message)
            is TranscriptionResult.Success -> RecordingTranscription.Success(result.text, saveNote(session, result.text))
        }
    }

    private suspend fun saveNote(session: CaptureSession, transcript: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { TranscriptCandidateFactsAppender.appendFromTranscript(context, session, transcript) }
        runCatching {
            MyApplication.notesRepository.createFromTranscript(
                transcript = transcript,
                hintTitle = "Audio ${SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(session.startedAt))}",
                deviceClass = session.deviceClass,
                durationSec = session.durationSec,
                tagsCsv = "audio, transcription",
                storeTranscript = PrivacyPrefs.isTranscriptStorageEnabled(context),
            )
        }.onFailure { error ->
            Log.e(TAG, "Could not save transcription to Notes", error)
        }.isSuccess
    }

    override suspend fun storedTranscript(id: Long): String? =
        withContext(Dispatchers.IO) { repository.getTranscriptionByCaptureSessionId(id) }?.transcriptText

    override suspend fun delete(ids: List<Long>): Set<Long> = withContext(Dispatchers.IO) {
        ids.mapNotNull { byId[it] }.mapNotNull { session ->
            runCatching {
                if (!repository.deleteCaptureSession(session.id)) error("Capture session ${session.id} was not found")
                val audioFile = File(session.audioPath)
                if (audioFile.exists() && !audioFile.delete()) {
                    Log.w(TAG, "Could not remove audio file ${audioFile.absolutePath}")
                }
                session.id
            }.onFailure { Log.e(TAG, "Failed to delete capture session ${session.id}", it) }.getOrNull()
        }.toSet()
    }

    private fun TranscriptionProgress.toUiState(title: String): TranscriptionProgressUiState {
        val showIndeterminate = stage == TranscriptionProgress.Stage.TRANSCRIBING
        val detail = detail?.let { " · $it" }.orEmpty()
        val message = when {
            showIndeterminate -> "Transcribing with the local model...$detail"
            else -> when (stage) {
                TranscriptionProgress.Stage.PREPARING -> "Preparing... $percent%$detail"
                TranscriptionProgress.Stage.CHUNKING -> "Chunking... $percent%$detail"
                TranscriptionProgress.Stage.TRANSCRIBING -> "Transcribing... $percent%$detail"
                TranscriptionProgress.Stage.MERGING -> "Merging... $percent%$detail"
                TranscriptionProgress.Stage.SAVING -> "Saving... $percent%$detail"
                TranscriptionProgress.Stage.DONE -> "Done"
            }
        }
        return TranscriptionProgressUiState(title, message, if (showIndeterminate) null else percent.coerceIn(0, 100))
    }

    private companion object {
        const val TAG = "RecordingsListActivity"
    }
}

internal fun CaptureSession.toRecordingItem(): RecordingItem {
    val timestamp = java.text.DateFormat.getDateTimeInstance().format(Date(startedAt))
    val titlePrefix = if (captureSource == GLASSES_SYNC_CAPTURE_SOURCE) "Glasses audio" else "Meeting"
    val metadata = buildString {
        append("${durationSec}s")
        if (captureSource.isNotBlank()) append(" · $captureSource")
        if (deviceClass.isNotBlank()) append(" · $deviceClass")
    }
    return RecordingItem(
        id = id,
        title = "$titlePrefix · $timestamp",
        metadata = metadata,
        stopReason = stopReason,
        durationSec = durationSec,
        captureSource = captureSource,
        deviceClass = deviceClass,
        startedAt = startedAt,
    )
}

private const val GLASSES_SYNC_CAPTURE_SOURCE = "GLASSES_SYNC_P2P"
