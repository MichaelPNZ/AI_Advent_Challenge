package com.pozyalov.ai_advent_challenge.core.database.chat.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pozyalov.ai_advent_challenge.core.database.chat.model.AgentMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMemoryDao {
    @Query("SELECT * FROM agent_memories WHERE threadId = :threadId ORDER BY createdAtEpochMillis ASC")
    fun observeMemories(threadId: Long): Flow<List<AgentMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: AgentMemoryEntity)
}
