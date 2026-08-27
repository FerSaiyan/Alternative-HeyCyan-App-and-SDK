package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.transcription.AutomaticTranscriptionEngine
import com.fersaiyan.cyanbridge.ai.transcription.DefaultTranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionStatus
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationPrefs
import com.fersaiyan.cyanbridge.integrations.knowledge.SafKnowledgeRepository
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import com.fersaiyan.cyanbridge.ui.MyApplication
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Transcribes the latest completed meeting, summarizes it in a second model call, and saves it. */
class MeetingNotesWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        MeetingSparkNotesNotificationHelper.ensureChannel(applicationContext)
        setForeground(createForegroundInfo("Preparing the latest meeting recording..."))

        if (MeetingCapturePrefs.getState(applicationContext).isRecording) {
            return failure("Stop the meeting recording before summarizing it")
        }
        val session = MyApplication.repository.getLatestCaptureSession()
            ?: return failure("No completed meeting recording was found")
        val audioFile = File(session.audioPath)
        if (!audioFile.isFile || audioFile.length() == 0L) {
            return failure("The latest meeting audio file is missing or empty")
        }

        val priorSessionId = MeetingSparkNotesPreferences.getLastSummarizedSessionId(applicationContext)
        val priorNoteId = MeetingSparkNotesPreferences.getLastSavedNoteId(applicationContext)
        if (priorSessionId == session.id && priorNoteId > 0L) {
            val existingNote = MyApplication.notesRepository.getNoteById(priorNoteId)
            if (existingNote != null) {
                val obsidianResult = mirrorToObsidian(
                    noteTitle = existingNote.title,
                    noteMarkdown = MeetingNoteMarkdown.render(
                        summaryMarkdown = existingNote.summary,
                        captureSessionId = session.id,
                        createdAtMs = existingNote.createdAt,
                    ),
                    createdAtMs = existingNote.createdAt,
                    captureSessionId = session.id,
                )
                val status = when {
                    obsidianResult.isFailure -> "Meeting note exists; Obsidian export failed"
                    obsidianResult.getOrNull() == true -> "Meeting note saved in CyanBridge and Obsidian"
                    else -> "Meeting note already saved in CyanBridge"
                }
                showStatus("$status: ${existingNote.title}", ongoing = false)
                return Result.success(
                    Data.Builder()
                        .putLong(KEY_NOTE_ID, priorNoteId)
                        .putLong(KEY_CAPTURE_SESSION_ID, session.id)
                        .putBoolean(KEY_OBSIDIAN_SAVED, obsidianResult.getOrNull() == true)
                        .apply {
                            obsidianResult.exceptionOrNull()?.message?.let { putString(KEY_WARNING, it) }
                        }
                        .build(),
                )
            }
        }

        val storedTranscription = MyApplication.repository
            .getTranscriptionByCaptureSessionId(session.id)
            ?.takeIf {
                it.status == TranscriptionStatus.SUCCEEDED.name && !it.transcriptText.isNullOrBlank()
            }
            ?.transcriptText

        val transcript = storedTranscription ?: run {
            val engine = AutomaticTranscriptionEngine.select(applicationContext)
            showStatus("Transcribing meeting (${engine.route.label})...", ongoing = true)
            val service: TranscriptionService = DefaultTranscriptionService(
                context = applicationContext,
                repository = MyApplication.repository,
                provider = engine.provider,
                chunker = engine.chunker,
            )
            when (val result = service.transcribe(
                session = session,
                options = TranscriptionService.Options(chunkDurationSec = engine.chunkDurationSec),
                onProgress = { progress ->
                    showStatus(
                        "Transcribing meeting: ${progress.percent}% ${progress.detail.orEmpty()}".trim(),
                        ongoing = true,
                    )
                },
            )) {
                is TranscriptionResult.Success -> result.text
                is TranscriptionResult.Failure -> return failure("Transcription failed: ${result.message}")
            }
        }.trim()

        if (transcript.isBlank()) return failure("No speech was found in the latest meeting recording")

        showStatus("Creating bullet-point meeting notes...", ongoing = true)
        val noteId = runCatching {
            MyApplication.notesRepository.createFromTranscript(
                transcript = transcript,
                hintTitle = null,
                deviceClass = session.deviceClass,
                durationSec = session.durationSec,
                tagsCsv = "meeting,meeting-spark-notes",
                storeTranscript = PrivacyPrefs.isTranscriptStorageEnabled(applicationContext),
            )
        }.getOrElse { error ->
            Log.e(TAG, "Meeting summary model call failed", error)
            return failure(error.message ?: "Meeting summary model call failed")
        }
        val note = MyApplication.notesRepository.getNoteById(noteId)
            ?: return failure("The meeting note could not be loaded after saving")

        val obsidianResult = mirrorToObsidian(
            noteTitle = note.title,
            noteMarkdown = MeetingNoteMarkdown.render(
                summaryMarkdown = note.summary,
                captureSessionId = session.id,
                createdAtMs = note.createdAt,
            ),
            createdAtMs = note.createdAt,
            captureSessionId = session.id,
        )
        MeetingSparkNotesPreferences.setLastSavedNote(applicationContext, session.id, noteId)

        val status = when {
            obsidianResult.isFailure -> "Note saved in CyanBridge; Obsidian export failed"
            obsidianResult.getOrNull() == true -> "Meeting note saved in CyanBridge and Obsidian"
            else -> "Meeting note saved in CyanBridge"
        }
        showStatus("$status: ${note.title}", ongoing = false)
        GlassesBridge.showCard(DisplayCommand.Card(note.title, note.summary.take(800)))
        val output = Data.Builder()
            .putLong(KEY_NOTE_ID, noteId)
            .putLong(KEY_CAPTURE_SESSION_ID, session.id)
            .putBoolean(KEY_OBSIDIAN_SAVED, obsidianResult.getOrNull() == true)
            .apply {
                obsidianResult.exceptionOrNull()?.message?.let { putString(KEY_WARNING, it) }
            }
            .build()
        return Result.success(output)
    }

    private fun mirrorToObsidian(
        noteTitle: String,
        noteMarkdown: String,
        createdAtMs: Long,
        captureSessionId: Long,
    ): kotlin.Result<Boolean> = runCatching {
        val vault = KnowledgeIntegrationPrefs.obsidianVault(applicationContext)
            ?: return@runCatching false
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(createdAtMs))
        SafKnowledgeRepository.saveObsidianNote(
            context = applicationContext,
            treeUri = vault.permissionTreeUri,
            rootDocumentId = vault.rootDocumentId,
            title = "$noteTitle - $timestamp - $captureSessionId",
            markdown = noteMarkdown,
        )
        true
    }

    private fun failure(message: String): Result {
        Log.e(TAG, message)
        showStatus(message, ongoing = false)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    private fun createForegroundInfo(content: String): ForegroundInfo {
        val notification = buildNotification(content, ongoing = true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                WORK_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(WORK_NOTIFICATION_ID, notification)
        }
    }

    private fun showStatus(content: String, ongoing: Boolean) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(WORK_NOTIFICATION_ID, buildNotification(content, ongoing))
    }

    private fun buildNotification(content: String, ongoing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(
            applicationContext,
            MeetingSparkNotesNotificationHelper.CHANNEL_ID,
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Meeting Spark Notes")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(ongoing)
            .setOngoing(ongoing)
            .setCategory(if (ongoing) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
        if (ongoing) {
            builder.addAction(
                0,
                "Cancel",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "MeetingNotesWorker"
        private const val UNIQUE_WORK = "meeting_spark_notes_latest_recording"
        private const val WORK_NOTIFICATION_ID = 77423

        const val KEY_NOTE_ID = "note_id"
        const val KEY_CAPTURE_SESSION_ID = "capture_session_id"
        const val KEY_OBSIDIAN_SAVED = "obsidian_saved"
        const val KEY_WARNING = "warning"
        const val KEY_ERROR = "error"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<MeetingNotesWorker>().build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

internal object MeetingNoteMarkdown {
    fun render(summaryMarkdown: String, captureSessionId: Long, createdAtMs: Long): String {
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(createdAtMs))
        return buildString {
            appendLine("---")
            appendLine("source: cyanbridge")
            appendLine("type: meeting-note")
            appendLine("capture_session_id: $captureSessionId")
            appendLine("created: \"$created\"")
            appendLine("tags: [meeting, meeting-spark-notes]")
            appendLine("---")
            appendLine()
            append(summaryMarkdown.trim())
            appendLine()
        }
    }
}
