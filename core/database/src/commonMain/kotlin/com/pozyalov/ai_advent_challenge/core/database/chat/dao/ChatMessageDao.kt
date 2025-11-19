package com.pozyalov.ai_advent_challenge.core.database.chat.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pozyalov.ai_advent_challenge.core.database.chat.model.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY timestampEpochMillis ASC")
    fun observeMessages(threadId: Long): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE threadId = :threadId")
    suspend fun clear(threadId: Long)

    @Query(
        "SELECT * FROM chat_messages WHERE threadId = :threadId AND isSummary = 0 AND isThinking = 0 AND isArchived = 0 ORDER BY timestampEpochMillis ASC LIMIT :limit"
    )
    suspend fun getOldestNonSummary(threadId: Long, limit: Int): List<ChatMessageEntity>

    @Query("UPDATE chat_messages SET isArchived = 1 WHERE id IN (:ids)")
    suspend fun markArchived(ids: List<Long>)

    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY timestampEpochMillis ASC")
    suspend fun getMessages(threadId: Long): List<ChatMessageEntity>
}
