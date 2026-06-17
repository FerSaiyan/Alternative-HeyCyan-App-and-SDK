package com.fersaiyan.cyanbridge.localagent.userfacts

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TranscriptCandidateFactsAppender {
    private const val GLASSES_SYNC_CAPTURE_SOURCE = "GLASSES_SYNC_P2P"

    fun appendFromTranscript(
        context: Context,
        session: CaptureSession,
        transcript: String,
    ): Int {
        val facts = transcriptToCandidateFacts(session, transcript)
        if (facts.isEmpty()) return 0

        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(session.startedAt))
        CandidateUserFactsStorage.append(context, date, facts)
        return facts.size
    }

    private fun transcriptToCandidateFacts(session: CaptureSession, transcript: String): List<String> {
        val cleaned = transcript
            .replace('\r', '\n')
            .replace(Regex("[\\t ]+"), " ")
            .trim()
        if (cleaned.isBlank()) return emptyList()

        val timeLabel = SimpleDateFormat("HH:mm", Locale.US).format(Date(session.startedAt))
        val prefix = if (session.captureSource.equals(GLASSES_SYNC_CAPTURE_SOURCE, ignoreCase = true)) {
            "Glasses audio"
        } else {
            "Transcribed audio"
        }

        val out = ArrayList<String>()
        val seen = LinkedHashSet<String>()

        val segments = cleaned
            .split(Regex("\\n+|(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length >= 14 }

        for (segment in segments) {
            val normalized = segment
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (normalized.length < 10 || !seen.add(normalized)) continue

            val clipped = if (segment.length > 190) segment.take(187).trimEnd() + "..." else segment
            out += "$prefix $timeLabel: $clipped"
            if (out.size >= 8) break
        }

        return out
    }
}
