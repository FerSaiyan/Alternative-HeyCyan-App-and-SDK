package com.fersaiyan.cyanbridge.memoryvault

import android.content.Context
import java.io.File

object MemoryRefMapper {
    fun forFile(context: Context, file: File): String {
        val base = File(context.filesDir, "local_agent_memory")
        val rel = runCatching {
            file.relativeTo(base).invariantSeparatorsPath
        }.getOrElse {
            file.absolutePath
        }
        return "file:$rel"
    }

    fun forMemoryChunk(id: Long): String = "memory_chunk:$id"
}
