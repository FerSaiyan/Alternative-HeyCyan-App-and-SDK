package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class Chat(
    @PrimaryKey
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long
)
