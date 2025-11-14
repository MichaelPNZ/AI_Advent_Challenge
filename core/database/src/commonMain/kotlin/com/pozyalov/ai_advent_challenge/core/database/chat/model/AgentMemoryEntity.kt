package com.pozyalov.ai_advent_challenge.core.database.chat.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_memories")
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val title: String,
    val content: String,
    val type: String,
    val metadata: String?,
    val createdAtEpochMillis: Long
)
