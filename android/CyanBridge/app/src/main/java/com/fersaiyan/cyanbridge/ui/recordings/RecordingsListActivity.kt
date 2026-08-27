package com.fersaiyan.cyanbridge.ui.recordings

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.transcription.AutomaticTranscriptionEngine
import com.fersaiyan.cyanbridge.ai.transcription.DefaultTranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionProgress
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionService
import com.fersaiyan.cyanbridge.shared.recordings.MeetingRecordingUiState as SharedMeetingRecordingUiState
import com.fersaiyan.cyanbridge.shared.recordings.RecordingItem
import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptDialogUiState as SharedTranscriptDialogUiState
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptionProgressUiState as SharedTranscriptionProgressUiState
import com.fersaiyan.cyanbridge.shared.settings.CaptureSource
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.audio.MeetingCaptureService
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.localagent.userfacts.TranscriptCandidateFactsAppender
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.ui.recordings.RecordingsScreen
import com.fersaiyan.cyanbridge.ui.ChatThreadActivity
import com.fersaiyan.cyanbridge.ui.CommunityPluginsActivity
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.SettingsActivity
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

class RecordingsListActivity : AppCompatActivity() {

    private val uiScope = MainScope()
    private var sessionsJob: Job? = null
    private var recentMediaJob: Job? = null

    private var sessions by mutableStateOf<List<CaptureSession>>(emptyList())
    private var isLoadingSessions by mutableStateOf(true)
    private var recentSyncedMedia by mutableStateOf<List<SyncedMediaItem>>(emptyList())
    private var meetingRecording by mutableStateOf(SharedMeetingRecordingUiState())
    private var currentlyPlayingId by mutableStateOf<Long?>(null)
    private var transcribingId by mutableStateOf<Long?>(null)
    private var transcriptionProgress by mutableStateOf<SharedTranscriptionProgressUiState?>(null)
    private var transcriptDialog by mutableStateOf<SharedTranscriptDialogUiState?>(null)

    private var mediaPlayer: MediaPlayer? = null
    private val ephemeralTranscripts = mutableMapOf<Long, String>()
    private var meetingStateReceiverRegistered = false

    private val meetingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != MeetingCaptureService.ACTION_STATE) return
            val source = intent.getStringExtra(MeetingCaptureService.EXTRA_SOURCE)
                ?.let { runCatching { CaptureSource.valueOf(it) }.getOrNull() }
            meetingRecording = SharedMeetingRecordingUiState(
                isRecording = intent.getBooleanExtra(MeetingCaptureService.EXTRA_IS_RECORDING, false),
                sourceLabel = source?.let { src ->
                    when (src) {
                        CaptureSource.BLUETOOTH_MIC -> "Bluetooth mic"
                        CaptureSource.PHONE_MIC -> "Phone mic"
                    }
                },
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncMeetingRecordingState()

        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                val recordingItems = sessions.map { it.toRecordingItem() }
                RecordingsScreen(
                    sessions = recordingItems,
                    isLoading = isLoadingSessions,
                    recentSyncedMedia = recentSyncedMedia,
                    playingSessionId = currentlyPlayingId,
                    transcribingSessionId = transcribingId,
                    meetingRecording = meetingRecording,
                    transcriptionProgress = transcriptionProgress,
                    transcriptDialog = transcriptDialog,
                    formatTimestamp = { ms -> java.text.DateFormat.getDateTimeInstance().format(java.util.Date(ms)) },
                    loadThumbnail = { uriString -> loadThumbnailForShared(uriString) },
                    onOpenSyncedMedia = {
                        startActivity(Intent(this, SyncedMediaGalleryActivity::class.java))
                    },
                    onOpenSyncedMediaItem = ::openSyncedMediaItem,
                    onPlay = { item -> sessions.firstOrNull { it.id == item.id }?.let(::onPlayClicked) },
                     onTranscribe = { item -> sessions.firstOrNull { it.id == item.id }?.let(::onTranscribeClicked) },
                     onViewTranscript = { item -> sessions.firstOrNull { it.id == item.id }?.let(::onViewTranscriptionClicked) },
                     onStopMeetingCapture = { MeetingCaptureService.stop(this) },
                     onDeleteItems = ::deleteMeetingCaptures,
                     onDismissTranscript = { transcriptDialog = null },
                     onDestinationSelected = ::navigateTo,
                 )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerMeetingStateReceiver()
        syncMeetingRecordingState()

        sessionsJob?.cancel()
        isLoadingSessions = true
        sessionsJob = uiScope.launch {
            MyApplication.repository.getAllCaptureSessions().collect { captureSessions ->
                sessions = captureSessions
                isLoadingSessions = false
            }
        }
        loadRecentSyncedPhotos()
    }

    override fun onStop() {
        super.onStop()
        unregisterMeetingStateReceiver()
        sessionsJob?.cancel()
        sessionsJob = null
        recentMediaJob?.cancel()
        recentMediaJob = null
        stopPlayback()
    }

    override fun onDestroy() {
        uiScope.cancel()
        stopPlayback()
        super.onDestroy()
    }

    private fun registerMeetingStateReceiver() {
        if (meetingStateReceiverRegistered) return
        LocalBroadcastManager.getInstance(this).registerReceiver(
            meetingStateReceiver,
            IntentFilter(MeetingCaptureService.ACTION_STATE),
        )
        meetingStateReceiverRegistered = true
    }

    private fun unregisterMeetingStateReceiver() {
        if (!meetingStateReceiverRegistered) return
        LocalBroadcastManager.getInstance(this).unregisterReceiver(meetingStateReceiver)
        meetingStateReceiverRegistered = false
    }

    private fun syncMeetingRecordingState() {
        val state = MeetingCapturePrefs.getState(this)
        meetingRecording = SharedMeetingRecordingUiState(
            isRecording = state.isRecording,
            sourceLabel = state.source?.let { src ->
                when (src) {
                    CaptureSource.BLUETOOTH_MIC -> "Bluetooth mic"
                    CaptureSource.PHONE_MIC -> "Phone mic"
                }
            },
        )
    }

    private fun loadRecentSyncedPhotos() {
        recentMediaJob?.cancel()
        recentMediaJob = uiScope.launch {
            recentSyncedMedia = withContext(Dispatchers.IO) {
                SyncedMediaQuery.query(
                    context = this@RecordingsListActivity,
                    imagesOnly = true,
                    limit = 4,
                )
            }
        }
    }

    private fun openSyncedMediaItem(item: SyncedMediaItem) {
        val uri = android.net.Uri.parse(item.contentUriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, getString(R.string.synced_media_open_failed), Toast.LENGTH_SHORT).show()
            }
    }

    private fun navigateTo(destination: AppDestination) {
        val target = when (destination) {
            AppDestination.GLASSES -> Intent(this, MainActivity::class.java)
            AppDestination.CHATS -> buildRecentChatIntent()
            AppDestination.MEDIA -> return
            AppDestination.PLUGINS -> Intent(this, CommunityPluginsActivity::class.java)
            AppDestination.SETTINGS -> Intent(this, SettingsActivity::class.java)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(target)
    }

    private fun buildRecentChatIntent(): Intent {
        val last = ChatStore.listNonEmptyThreads().firstOrNull()
        val lastUserAt = last?.let { thread ->
            ChatStore.listMessages(thread.id)
                .lastOrNull { it.role == ChatRole.USER }
                ?.createdAt
        } ?: 0L
        val openChatId = last?.id?.takeIf {
            lastUserAt > 0L && System.currentTimeMillis() - lastUserAt < 30 * 60 * 1_000
        }
        return Intent(this, ChatThreadActivity::class.java).apply {
            if (openChatId != null) {
                putExtra(ChatThreadActivity.EXTRA_CHAT_ID, openChatId)
            }
        }
    }

    private fun onPlayClicked(session: CaptureSession) {
        val path = session.audioPath
        if (path.isBlank()) {
            Toast.makeText(this, "Missing audio path", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_LONG).show()
            return
        }
        if (currentlyPlayingId == session.id) {
            stopPlayback()
            return
        }

        stopPlayback()
        val player = MediaPlayer()
        mediaPlayer = player
        currentlyPlayingId = session.id
        runCatching {
            player.setDataSource(path)
            player.setOnCompletionListener { stopPlayback() }
            player.prepare()
            player.start()
        }.onFailure {
            Toast.makeText(this, "Failed to play audio: ${it.message}", Toast.LENGTH_LONG).show()
            stopPlayback()
        }
    }

    private fun onTranscribeClicked(session: CaptureSession) {
        if (transcribingId != null) {
            Toast.makeText(this, "Already transcribing...", Toast.LENGTH_SHORT).show()
            return
        }
        val path = session.audioPath
        if (path.isBlank() || !File(path).exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_LONG).show()
            return
        }

        startTranscription(session)
    }

    private fun startTranscription(session: CaptureSession) {
        if (transcribingId != null) {
            Toast.makeText(this, "Already transcribing...", Toast.LENGTH_SHORT).show()
            return
        }
        val engine = AutomaticTranscriptionEngine.select(applicationContext)

        transcribingId = session.id
        transcriptionProgress = SharedTranscriptionProgressUiState(
            title = "Transcribing (${engine.route.label})",
            message = "Preparing...",
            progress = 0,
        )

        uiScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val service: TranscriptionService = DefaultTranscriptionService(
                        context = applicationContext,
                        repository = MyApplication.repository,
                        provider = engine.provider,
                        chunker = engine.chunker,
                    )
                    service.transcribe(
                        session = session,
                        options = TranscriptionService.Options(
                            chunkDurationSec = engine.chunkDurationSec,
                        ),
                        onProgress = { progress ->
                            runOnUiThread {
                                transcriptionProgress = progress.toUiState(
                                    title = "Transcribing (${engine.route.label})",
                                    showIndeterminate = progress.stage == TranscriptionProgress.Stage.TRANSCRIBING,
                                )
                            }
                        },
                    )
                }

                when (result) {
                    is TranscriptionResult.Success -> {
                        ephemeralTranscripts[session.id] = result.text
                        withContext(Dispatchers.IO) {
                            runCatching {
                                TranscriptCandidateFactsAppender.appendFromTranscript(
                                    context = applicationContext,
                                    session = session,
                                    transcript = result.text,
                                )
                            }
                        }
                        Toast.makeText(this@RecordingsListActivity, "Transcription complete", Toast.LENGTH_SHORT).show()
                    }

                    is TranscriptionResult.Failure -> {
                        Log.e("RecordingsListActivity", "Transcription failed: ${result.message}")
                        if (DebugLogSupport.isLocalRuntimeIssue(result.message)) {
                            DebugLogSupport.showSupportOptionsDialog(
                                activity = this@RecordingsListActivity,
                                title = "Local runtime issue",
                                issueType = "Local runtime issue",
                                description = "Transcription failed while using a local runtime. Sending logs can help diagnose LiteRT or GPU issues.",
                                extraInfo = linkedMapOf(
                                    "screen" to "recordings",
                                    "transcription_engine" to "local_multimodal_litert",
                                ),
                            )
                        }
                        Toast.makeText(
                            this@RecordingsListActivity,
                            "Transcription failed: ${result.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (throwable: Throwable) {
                Log.e("RecordingsListActivity", "Transcription threw an exception", throwable)
                if (DebugLogSupport.isLocalRuntimeIssue(throwable.message, throwable)) {
                    DebugLogSupport.showSupportOptionsDialog(
                        activity = this@RecordingsListActivity,
                        title = "Local runtime issue",
                        issueType = "Local runtime issue",
                        description = "Transcription crashed while using a local runtime. Sending logs can help diagnose LiteRT or GPU issues.",
                        extraInfo = linkedMapOf(
                            "screen" to "recordings",
                            "transcription_engine" to "local_multimodal_litert",
                        ),
                    )
                }
                Toast.makeText(
                    this@RecordingsListActivity,
                    "Transcription failed: ${throwable.message}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                transcriptionProgress = null
                transcribingId = null
            }
        }
    }

    private fun onViewTranscriptionClicked(session: CaptureSession) {
        uiScope.launch {
            val record = withContext(Dispatchers.IO) {
                MyApplication.repository.getTranscriptionByCaptureSessionId(session.id)
            }
            val storedText = record?.transcriptText
            val text = storedText ?: ephemeralTranscripts[session.id]
            if (text.isNullOrBlank()) {
                Toast.makeText(this@RecordingsListActivity, "No transcription available yet", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val stored = !storedText.isNullOrBlank()
            val storageEnabled = PrivacyPrefs.isTranscriptStorageEnabled(applicationContext)
            transcriptDialog = SharedTranscriptDialogUiState(
                title = if (stored) "Transcription (stored)" else "Transcription",
                text = if (!stored && !storageEnabled) {
                    "(Transcript storage is OFF in Settings; this text may not be persisted.)\n\n$text"
                } else {
                    text
                },
            )
        }
    }

    private suspend fun deleteMeetingCaptures(items: List<RecordingItem>): Set<Long> {
        val targets = items.mapNotNull { item ->
            sessions.firstOrNull { session -> session.id == item.id }
        }
        if (targets.any { it.id == currentlyPlayingId }) {
            stopPlayback()
        }

        return withContext(Dispatchers.IO) {
            targets.mapNotNull { session ->
                runCatching {
                    if (!MyApplication.repository.deleteCaptureSession(session.id)) {
                        error("Capture session ${session.id} was not found")
                    }

                    val audioFile = File(session.audioPath)
                    if (audioFile.exists() && !audioFile.delete()) {
                        Log.w("RecordingsListActivity", "Could not remove audio file ${audioFile.absolutePath}")
                    }
                    session.id
                }.onFailure { throwable ->
                    Log.e(
                        "RecordingsListActivity",
                        "Failed to delete capture session ${session.id}",
                        throwable,
                    )
                }.getOrNull()
            }.toSet()
        }
    }

    private fun stopPlayback() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        currentlyPlayingId = null
    }

    private fun TranscriptionProgress.toUiState(
        title: String,
        showIndeterminate: Boolean,
    ): SharedTranscriptionProgressUiState {
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
        return SharedTranscriptionProgressUiState(
            title = title,
            message = message,
            progress = if (showIndeterminate) null else percent.coerceIn(0, 100),
        )
    }

    private suspend fun loadThumbnailForShared(uriString: String): androidx.compose.ui.graphics.ImageBitmap? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = android.net.Uri.parse(uriString)
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(uri, android.util.Size(256, 256), null)
                } else {
                    val inputStream = contentResolver.openInputStream(uri)
                    inputStream?.use { android.graphics.BitmapFactory.decodeStream(it) }
                }
                bitmap?.asImageBitmap()
            }.getOrNull()
        }
    }
}

internal fun CaptureSession.toRecordingItem(): RecordingItem {
    val timestamp = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(startedAt))
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
