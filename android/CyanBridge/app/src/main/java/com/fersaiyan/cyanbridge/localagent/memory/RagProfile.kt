package com.fersaiyan.cyanbridge.localagent.memory

/** Controls personal-memory retrieval for latency-sensitive assistant entry points. */
enum class RagProfile {
    /** No persona, facts, summaries, notes, or retrieval hits. */
    NONE,

    /** Small core-memory context without imported note/Obsidian retrieval. */
    LIGHT,

    /** Full bounded context and eligible imported knowledge. */
    FULL,
}
