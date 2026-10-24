package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

data class MeetingSummary(
    val id: String,
    val timestampMs: Long,
    val title: String,
    val summary: String,
    val actionItems: List<String>,
    val participants: List<String>,
    val durationMinutes: Int,
    val audioPath: String?,
)
