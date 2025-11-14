@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.data.memory

import com.pozyalov.ai_advent_challenge.core.database.chat.dao.AgentMemoryDao
import com.pozyalov.ai_advent_challenge.core.database.chat.model.AgentMemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlin.time.ExperimentalTime

data class AgentMemoryEntry(
    val id: Long,
    val threadId: Long,
    val title: String,
    val content: String,
    val type: String,
    val createdAt: Instant
)

interface AgentMemoryStore {
    fun observe(threadId: Long): Flow<List<AgentMemoryEntry>>
    suspend fun saveSummary(threadId: Long, title: String, content: String, createdAt: Instant)
}

class RoomAgentMemoryStore(
    private val dao: AgentMemoryDao
) : AgentMemoryStore {
    override fun observe(threadId: Long): Flow<List<AgentMemoryEntry>> =
        dao.observeMemories(threadId).map { list -> list.map { it.toEntry() } }

    override suspend fun saveSummary(threadId: Long, title: String, content: String, createdAt: Instant) {
        dao.insert(
            AgentMemoryEntity(
                threadId = threadId,
                title = title,
                content = content,
                type = MEMORY_TYPE_SUMMARY,
                metadata = null,
                createdAtEpochMillis = createdAt.toEpochMilliseconds()
            )
        )
    }

    private fun AgentMemoryEntity.toEntry(): AgentMemoryEntry =
        AgentMemoryEntry(
            id = id,
            threadId = threadId,
            title = title,
            content = content,
            type = type,
            createdAt = Instant.fromEpochMilliseconds(createdAtEpochMillis)
        )

    private companion object {
        const val MEMORY_TYPE_SUMMARY = "summary_chunk"
    }
}
