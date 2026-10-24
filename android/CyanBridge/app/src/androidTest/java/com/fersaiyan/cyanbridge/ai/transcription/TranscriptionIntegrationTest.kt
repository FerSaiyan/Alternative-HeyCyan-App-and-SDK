package com.fersaiyan.cyanbridge.ai.transcription

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fersaiyan.cyanbridge.data.local.AppDatabase
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.data.local.entity.TranscriptionRecord
import com.fersaiyan.cyanbridge.data.repository.CyanBridgeRepository
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TranscriptionIntegrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val repo = CyanBridgeRepository(db)

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun transcribeWithStorage(enabled: Boolean, transcript: String): Pair<TranscriptionResult, TranscriptionRecord?> {
        PrivacyPrefs.setTranscriptStorageEnabled(context, enabled)
        val audioFile = File(context.cacheDir, "fake_audio_$transcript.m4a").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3))
        }
        val unsaved = CaptureSession(
            startedAt = 1L,
            endedAt = 2L,
            durationSec = 1L,
            deviceClass = "GENERIC_AUDIO",
            captureSource = "PHONE_MIC",
            audioPath = audioFile.absolutePath,
            timerDurationSec = null,
            stopReason = "user",
            error = null,
        )
        val session = unsaved.copy(id = db.captureSessionDao().insert(unsaved))
        val service = DefaultTranscriptionService(
            context = context,
            repository = repo,
            provider = FakeTranscriptionProvider(transcript),
            chunker = NoOpAudioChunker(),
        )
        return service.transcribe(session) to repo.getTranscriptionByCaptureSessionId(session.id)
    }

    @Test
    fun fakeProvider_transcriptStorageOn_persistsTranscriptText() = runBlocking {
        val (result, record) = transcribeWithStorage(enabled = true, transcript = "EXPECTED")

        assertEquals(TranscriptionResult.Success("EXPECTED", "fake"), result)
        assertNotNull(record)
        assertEquals("SUCCEEDED", record!!.status)
        assertEquals("EXPECTED", record.transcriptText)
    }

    @Test
    fun fakeProvider_transcriptStorageOff_doesNotPersistTranscriptText() = runBlocking {
        val (result, record) = transcribeWithStorage(enabled = false, transcript = "EXPECTED2")

        // Still returns the transcript to the caller (for immediate UI), but does not persist.
        assertEquals(TranscriptionResult.Success("EXPECTED2", "fake"), result)
        assertNotNull(record)
        assertEquals("SUCCEEDED", record!!.status)
        assertNull(record.transcriptText)
    }
}
