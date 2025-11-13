package com.pozyalov.ai_advent_challenge.core.database.chat.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    val threadId: Long,
    @PrimaryKey val id: Long,
    val author: String,
    val text: String,
    @ColumnInfo(defaultValue = "0") val isSummary: Boolean,
    @ColumnInfo(defaultValue = "0") val isThinking: Boolean,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean,
    val structuredTitle: String?,
    val structuredSummary: String?,
    val structuredConfidence: Double?,
    val error: String?,
    val timestampEpochMillis: Long,
    val modelId: String?,
    val roleId: String?,
    val temperature: Double?,
    val responseTimeMillis: Long?,
    val promptTokens: Long?,
    val completionTokens: Long?,
    val totalTokens: Long?,
    val costUsd: Double?
)
