package com.fersaiyan.cyanbridge.shared.notes

data class NoteSummary(
    val id: Long,
    val title: String,
    val summary: String,
    val createdAt: Long,
    val source: NoteSource = NoteSource.APP,
    val externalId: String? = null,
)

enum class NoteSource {
    APP,
    MEETING,
    OBSIDIAN,
}
