package com.fersaiyan.cyanbridge.plugins.errandbrain

data class TaskEntry(
    val id: String,
    val timestampMs: Long,
    val title: String,
    val description: String,
    val isCompleted: Boolean,
    val priority: TaskPriority,
    val dueDate: Long?,
    val category: String,
)

data class ReminderEntry(
    val id: String,
    val timestampMs: Long,
    val title: String,
    val description: String,
    val reminderTime: Long,
    val isTriggered: Boolean,
)

enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT,
}
