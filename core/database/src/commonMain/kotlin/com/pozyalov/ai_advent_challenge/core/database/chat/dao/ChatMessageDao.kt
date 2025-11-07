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
}
