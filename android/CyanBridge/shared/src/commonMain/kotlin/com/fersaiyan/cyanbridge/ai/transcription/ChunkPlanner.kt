package com.fersaiyan.cyanbridge.ai.transcription

/**
 * Pure chunk planning (unit-testable).
 */
object ChunkPlanner {

    data class ChunkRangeMs(
        val index: Int,
        val total: Int,
        val startMs: Long,
        val endMsExclusive: Long,
    )

    fun plan(totalDurationMs: Long?, chunkDurationMs: Long): List<ChunkRangeMs> {
        if (totalDurationMs == null) return emptyList()
        if (totalDurationMs <= 0) return emptyList()
        if (chunkDurationMs <= 0) return listOf(
            ChunkRangeMs(index = 0, total = 1, startMs = 0, endMsExclusive = totalDurationMs)
        )

        val ranges = mutableListOf<ChunkRangeMs>()
        var start = 0L
        while (start < totalDurationMs) {
            val end = (start + chunkDurationMs).coerceAtMost(totalDurationMs)
            ranges.add(ChunkRangeMs(index = ranges.size, total = -1, startMs = start, endMsExclusive = end))
            start = end
        }
        val total = ranges.size
        return ranges.map { it.copy(total = total) }
    }
}
