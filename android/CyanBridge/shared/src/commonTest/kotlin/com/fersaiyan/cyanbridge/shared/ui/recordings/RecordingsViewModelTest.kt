package com.fersaiyan.cyanbridge.shared.ui.recordings

import com.fersaiyan.cyanbridge.shared.recordings.MeetingRecordingUiState
import com.fersaiyan.cyanbridge.shared.recordings.RecordingItem
import com.fersaiyan.cyanbridge.shared.recordings.SyncedMediaItem
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptDialogUiState
import com.fersaiyan.cyanbridge.shared.recordings.TranscriptionProgressUiState
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModelTest {
    private val item = RecordingItem(1, "Meeting", "60s", null, 60, "PHONE_MIC", "", 0)
    private val backend = FakeRecordings()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun oneTranscriptionRunsAtATimeAndItsTextCanBeViewed() = runTest {
        val vm = RecordingsViewModel(backend)
        vm.transcribe(item)
        assertEquals(1L, vm.transcribingId)
        assertEquals("Chunking", vm.transcriptionProgress?.message)
        vm.transcribe(item)

        backend.result.complete(RecordingTranscription.Success("hello", noteSaved = true))
        assertNull(vm.transcribingId)
        assertNull(vm.transcriptionProgress)
        assertEquals(
            listOf("Already transcribing...", "Transcription complete. Saved to Notes in the Chats tab."),
            vm.effects.take(2).toList().map { (it as RecordingsEffect.Message).message.text },
        )

        vm.viewTranscript(item)
        assertEquals(TranscriptDialogUiState("Transcription (stored)", "stored text"), vm.transcriptDialog)
    }

    @Test
    fun playbackTogglesAndReportsMissingAudio() = runTest {
        val vm = RecordingsViewModel(backend)
        vm.togglePlayback(item)
        assertEquals(1L, vm.playingId)
        vm.togglePlayback(item)
        assertNull(vm.playingId)

        backend.problem = AudioProblem.NOT_FOUND
        vm.togglePlayback(item)
        assertNull(vm.playingId)
        assertEquals(RecordingsEffect.Message(UiMessage("Audio file not found", long = true)), vm.effects.first())
    }

    private class FakeRecordings : RecordingsBackend {
        val result = CompletableDeferred<RecordingTranscription>()
        var problem: AudioProblem? = null

        override fun sessions() = flowOf(emptyList<RecordingItem>())
        override fun meetingState() = MeetingRecordingUiState()
        override fun meetingUpdates() = emptyFlow<MeetingRecordingUiState>()
        override suspend fun recentMedia() = emptyList<SyncedMediaItem>()
        override fun stopMeetingCapture() = Unit
        override fun audioProblem(id: Long) = problem
        override fun startPlayback(id: Long, onFinished: () -> Unit) = Unit
        override fun stopPlayback() = Unit

        override suspend fun transcribe(id: Long, onProgress: (TranscriptionProgressUiState) -> Unit): RecordingTranscription {
            onProgress(TranscriptionProgressUiState("Transcribing", "Chunking", 10))
            return result.await()
        }

        override suspend fun storedTranscript(id: Long) = "stored text"
        override suspend fun delete(ids: List<Long>) = ids.toSet()
    }
}
