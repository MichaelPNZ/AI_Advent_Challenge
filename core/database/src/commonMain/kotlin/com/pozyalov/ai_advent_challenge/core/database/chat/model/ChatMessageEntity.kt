package com.pozyalov.ai_advent_challenge.core.database.chat.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: Long,
    val author: String,
    val text: String,
    val structuredTitle: String?,
    val structuredSummary: String?,
    val structuredConfidence: Double?,
    val error: String?,
    val timestampEpochMillis: Long,
    val modelId: String?
)
