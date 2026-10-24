package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.ai.ChatAiService
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage
import com.fersaiyan.cyanbridge.shared.ai.VoiceAiService
import com.fersaiyan.cyanbridge.shared.persistence.NoteEntity
import com.fersaiyan.cyanbridge.shared.persistence.NotesRepository
import com.fersaiyan.cyanbridge.shared.recordings.MeetingRecordingUiState
import com.fersaiyan.cyanbridge.shared.recordings.RecordingItem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioRecorder
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.darwin.NSObject

/**
 * Meeting capture for the iOS host (Android: MeetingCaptureService). Records AAC
 * from the glasses' Bluetooth mic when available; the audio background mode keeps
 * it running while the screen is locked.
 */
@OptIn(ExperimentalForeignApi::class)
class IosMeetingRecorder(
    private val voiceAiService: VoiceAiService,
    private val chatAiService: ChatAiService,
    private val notesRepository: NotesRepository,
) : SharedRecordingsProvider {
    @Serializable
    private data class StoredRecording(
        val id: Long,
        val title: String,
        val fileName: String,
        val startedAt: Long,
        val durationSec: Long,
        val source: String,
        val transcript: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val preferences = createPlatformPreferences("cyanbridge_recordings")
    private val json = Json { ignoreUnknownKeys = true }
    private val directory get() = PlatformFilePaths.dataDirectory() + "/Meetings"

    private val _meetingState = MutableStateFlow(MeetingRecordingUiState())
    override val meetingState: StateFlow<MeetingRecordingUiState> = _meetingState.asStateFlow()
    private val _playingId = MutableStateFlow<Long?>(null)
    override val playingId: StateFlow<Long?> = _playingId.asStateFlow()

    private var recorder: AVAudioRecorder? = null
    private var current: StoredRecording? = null
    private var timerJob: Job? = null
    private var player: AVAudioPlayer? = null
    private val playerDelegate = PlayerDelegate { _playingId.value = null }

    /** Starts capture; [durationSec] null means no automatic stop. Returns an error text or null. */
    suspend fun start(durationSec: Long?): String? {
        if (recorder != null) return null
        if (!IosChatPlatform.requestMicrophonePermission()) return "Microphone access is needed for meeting capture"
        val source = IosChatPlatform.configureRecordingSession()
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val startedAt = platformCurrentTimeMillis()
        val fileName = "meeting-$startedAt.m4a"
        val audioRecorder = AVAudioRecorder(
            uRL = NSURL.fileURLWithPath("$directory/$fileName"),
            settings = IosChatPlatform.aacRecordingSettings(),
            error = null,
        )
        if (!audioRecorder.record()) return "Could not start the recorder"
        recorder = audioRecorder
        current = StoredRecording(
            id = startedAt,
            title = "Meeting ${formatDate(startedAt)}",
            fileName = fileName,
            startedAt = startedAt,
            durationSec = 0,
            source = source,
        )
        _meetingState.value = MeetingRecordingUiState(isRecording = true, sourceLabel = source)
        timerJob?.cancel()
        if (durationSec != null && durationSec > 0) {
            timerJob = scope.launch {
                delay(durationSec * 1000L)
                stopMeetingCapture()
            }
        }
        return null
    }

    override fun stopMeetingCapture() {
        val audioRecorder = recorder ?: return
        audioRecorder.stop()
        recorder = null
        timerJob?.cancel()
        timerJob = null
        current?.let { recording ->
            val duration = (platformCurrentTimeMillis() - recording.startedAt) / 1000L
            save(load() + recording.copy(durationSec = duration))
        }
        current = null
        _meetingState.value = MeetingRecordingUiState()
    }

    override suspend fun recordings(): List<RecordingItem> = load()
        .sortedByDescending { it.startedAt }
        .map { recording ->
            RecordingItem(
                id = recording.id,
                title = recording.title,
                metadata = "${formatDuration(recording.durationSec)} · ${recording.source}",
                stopReason = if (recording.transcript != null) "Transcribed" else null,
                durationSec = recording.durationSec,
                captureSource = recording.source,
                deviceClass = "",
                startedAt = recording.startedAt,
            )
        }

    override fun togglePlayback(id: Long) {
        if (_playingId.value == id) {
            player?.stop()
            player = null
            _playingId.value = null
            return
        }
        val recording = load().firstOrNull { it.id == id } ?: return
        player?.stop()
        val audioPlayer = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(pathOf(recording)), error = null)
        audioPlayer.delegate = playerDelegate
        if (audioPlayer.play()) {
            player = audioPlayer
            _playingId.value = id
        }
    }

    override suspend fun transcribe(id: Long, onProgress: (String) -> Unit): String {
        val recording = load().firstOrNull { it.id == id } ?: error("Recording not found")
        onProgress("Uploading audio for transcription…")
        val audio = IosMediaPlatform.readFile(pathOf(recording)) ?: error("Recording file is missing")
        val transcript = voiceAiService.transcribe(audio, "audio/mp4").trim()
        check(transcript.isNotEmpty()) { "The transcription came back empty" }
        save(load().map { if (it.id == id) it.copy(transcript = transcript) else it })

        onProgress("Summarizing the meeting…")
        runCatching { summarize(recording.title, transcript) }.onSuccess { summary ->
            val now = platformCurrentTimeMillis()
            notesRepository.insertNote(
                NoteEntity(
                    id = "meeting-${recording.id}",
                    title = recording.title,
                    content = summary + "\n\n---\n\n## Transcript\n\n" + transcript,
                    createdAt = now,
                    updatedAt = now,
                    source = "meeting",
                ),
            )
        }
        return transcript
    }

    override suspend fun transcript(id: Long): String? = load().firstOrNull { it.id == id }?.transcript

    override suspend fun delete(ids: List<Long>): Set<Long> {
        val remaining = mutableListOf<StoredRecording>()
        val deleted = mutableSetOf<Long>()
        load().forEach { recording ->
            if (recording.id in ids) {
                if (_playingId.value == recording.id) togglePlayback(recording.id)
                IosMediaPlatform.deleteFile(pathOf(recording))
                deleted += recording.id
            } else {
                remaining += recording
            }
        }
        save(remaining)
        return deleted
    }

    suspend fun deleteAll() {
        stopMeetingCapture()
        delete(load().map { it.id })
    }

    private suspend fun summarize(title: String, transcript: String): String {
        val prompt = """
            Summarize this meeting transcript as Markdown with these sections:
            ## Summary (5-10 bullets), ## Action items, ## Key decisions, ## Open questions.
            Write in the transcript's language. Title: $title

            $transcript
        """.trimIndent()
        return chatAiService.chat(listOf(ChatMessage("user", prompt))).message.content.trim()
    }

    private fun pathOf(recording: StoredRecording) = "$directory/${recording.fileName}"

    private fun load(): List<StoredRecording> =
        runCatching { json.decodeFromString<List<StoredRecording>>(preferences.getString(KEY, "[]")) }
            .getOrDefault(emptyList())

    private fun save(recordings: List<StoredRecording>) {
        preferences.putString(KEY, json.encodeToString(recordings))
    }

    private fun formatDuration(seconds: Long): String =
        if (seconds >= 3600) "${seconds / 3600}h ${(seconds % 3600) / 60}m" else "${seconds / 60}m ${seconds % 60}s"

    private fun formatDate(millis: Long): String {
        val formatter = platform.Foundation.NSDateFormatter()
        formatter.dateStyle = platform.Foundation.NSDateFormatterMediumStyle
        formatter.timeStyle = platform.Foundation.NSDateFormatterShortStyle
        return formatter.stringFromDate(platform.Foundation.NSDate(timeIntervalSinceReferenceDate = millis / 1000.0 - 978_307_200.0))
    }

    private companion object {
        const val KEY = "recordings_json"
    }
}

private class PlayerDelegate(private val onFinished: () -> Unit) : NSObject(), AVAudioPlayerDelegateProtocol {
    override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) = onFinished()
}
