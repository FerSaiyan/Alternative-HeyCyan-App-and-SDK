package com.fersaiyan.cyanbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Index

@Entity(
    tableName = "messages",
    indices = [Index(value = ["chatId"])]
)
data class Message(
    @PrimaryKey
    val id: String,
    val chatId: String,
    val role: String,
    val content: String,
    val createdAt: Long
)
