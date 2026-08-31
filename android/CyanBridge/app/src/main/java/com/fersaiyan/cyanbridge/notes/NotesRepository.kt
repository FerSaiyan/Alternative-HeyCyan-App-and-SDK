package com.fersaiyan.cyanbridge.notes

import com.fersaiyan.cyanbridge.data.local.entity.Note
import kotlinx.coroutines.flow.Flow

/**
 * Chapter 7: Notes repository.
 */
interface NotesRepository {
    fun getAllNotes(): Flow<List<Note>>
    suspend fun getNoteById(id: Long): Note?

    suspend fun saveMarkdownNote(
        id: Long? = null,
        title: String,
        markdown: String,
        tagsCsv: String? = null,
    ): Long

    suspend fun createFromTranscript(
        transcript: String,
        hintTitle: String? = null,
        deviceClass: String? = null,
        durationSec: Long? = null,
        tagsCsv: String? = null,
        storeTranscript: Boolean = true,
    ): Long
}
