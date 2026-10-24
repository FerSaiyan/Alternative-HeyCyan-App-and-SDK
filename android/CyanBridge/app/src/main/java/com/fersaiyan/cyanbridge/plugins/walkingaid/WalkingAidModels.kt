package com.fersaiyan.cyanbridge.plugins.walkingaid

data class SceneRecord(
    val timestampMs: Long,
    val imagePath: String,
    val description: String,
    val depthDescription: String?,
    val stateDecision: StateDecision,
)

enum class StateDecision {
    WARN,
    DESCRIBE,
    SKIP,
}

data class WalkingAidImageEntry(
    val timestampMs: Long,
    val imagePath: String,
    val description: String,
)
