package com.pozyalov.ai_advent_challenge.core.database.chat.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.pozyalov.ai_advent_challenge.core.database.chat.dao.ChatMessageDao
import com.pozyalov.ai_advent_challenge.core.database.chat.dao.ChatThreadDao
import com.pozyalov.ai_advent_challenge.core.database.chat.model.ChatMessageEntity
import com.pozyalov.ai_advent_challenge.core.database.chat.model.ChatThreadEntity

@Database(
    entities = [ChatMessageEntity::class, ChatThreadEntity::class],
    version = 3,
    exportSchema = true
)

@ConstructedBy(AppDatabaseConstructor::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatThreadDao(): ChatThreadDao
}

// The Room compiler generates the `actual` implementations.
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<ChatDatabase> {
    override fun initialize(): ChatDatabase
}
