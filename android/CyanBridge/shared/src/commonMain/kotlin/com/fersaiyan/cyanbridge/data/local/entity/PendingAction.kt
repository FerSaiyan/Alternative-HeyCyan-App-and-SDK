package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_actions")
data class PendingAction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ts: Long,
    val source: String,
    val actionJson: String,
    var status: String,
    var result: String? = null
)
