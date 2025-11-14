package com.pozyalov.ai_advent_challenge.core.database.chat.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.pozyalov.ai_advent_challenge.core.database.chat.dao.AgentMemoryDao
import com.pozyalov.ai_advent_challenge.core.database.chat.dao.ChatMessageDao
import com.pozyalov.ai_advent_challenge.core.database.chat.dao.ChatThreadDao
import com.pozyalov.ai_advent_challenge.core.database.chat.model.AgentMemoryEntity
import com.pozyalov.ai_advent_challenge.core.database.chat.model.ChatMessageEntity
import com.pozyalov.ai_advent_challenge.core.database.chat.model.ChatThreadEntity

@Database(
    entities = [ChatMessageEntity::class, ChatThreadEntity::class, AgentMemoryEntity::class],
    version = 8,
    exportSchema = true
)

@ConstructedBy(AppDatabaseConstructor::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatThreadDao(): ChatThreadDao
    abstract fun agentMemoryDao(): AgentMemoryDao
}

// The Room compiler generates the `actual` implementations.
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<ChatDatabase> {
    override fun initialize(): ChatDatabase
}
