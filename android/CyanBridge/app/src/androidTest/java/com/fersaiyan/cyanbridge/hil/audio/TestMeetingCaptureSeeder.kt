package com.fersaiyan.cyanbridge.hil.audio

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.ui.MyApplication
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * Seeds a [CaptureSession] from a deterministic audio asset without touching the microphone.
 *
 * This bypasses MediaRecorder / AudioRecord entirely and writes a real DB row so
 * MeetingNotesWorker, AutomaticTranscriptionEngine, and TranscriptionService can run
 * exactly as they do for a real glasses/phone recording.
 */
object TestMeetingCaptureSeeder {

    data class SeededSession(
        val session: CaptureSession,
        val sessionId: Long,
        val audioFile: File,
    )

    suspend fun seedFromAsset(
        context: Context = TestAudioAssets.targetContext(),
        assetName: String = TestAudioAssets.MEETING_M4A,
        deviceClass: String = "GENERIC_AUDIO",
        captureSource: String = "PHONE_MIC",
    ): SeededSession {
        val audioFile = TestAudioAssets.copyAssetToFiles(assetName, "test_meeting_${System.currentTimeMillis()}.m4a")
        // Ensure file is non-empty and readable.
        check(audioFile.isFile && audioFile.length() > 0) { "Seeded audio file missing: ${audioFile.absolutePath}" }

        val now = System.currentTimeMillis()
        val session = CaptureSession(
            startedAt = now - 25_000,
            endedAt = now,
            durationSec = 25,
            deviceClass = deviceClass,
            captureSource = captureSource,
            audioPath = audioFile.absolutePath,
            timerDurationSec = null,
            stopReason = "test_seed",
            error = null,
        )
        val id = MyApplication.repository.insertCaptureSession(session)
        return SeededSession(session.copy(id = id), id, audioFile)
    }

    suspend fun seedFromWavAsset(
        assetWav: String = TestAudioAssets.MEETING_WAV,
        deviceClass: String = "GENERIC_AUDIO",
    ): SeededSession = seedFromAsset(assetName = assetWav, deviceClass = deviceClass)

    suspend fun clearLatestTestSessions() {
        // Best-effort: remove seeded sessions whose stopReason == test_seed
        val all = MyApplication.repository.getAllCaptureSessionsOnce()
        for (session in all) {
            if (session.stopReason == "test_seed") {
                MyApplication.repository.deleteCaptureSession(session.id)
                runCatching { File(session.audioPath).delete() }
            }
        }
    }
}

suspend fun com.fersaiyan.cyanbridge.data.repository.CyanBridgeRepository.getAllCaptureSessionsOnce(): List<CaptureSession> =
    this.getAllCaptureSessions().first()
