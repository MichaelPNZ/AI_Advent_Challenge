package com.pozyalov.ai_advent_challenge.core.database.chat.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_threads")
data class ChatThreadEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val lastMessagePreview: String?,
    val updatedAtEpochMillis: Long
)
