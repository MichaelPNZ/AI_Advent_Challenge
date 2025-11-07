package com.pozyalov.ai_advent_challenge.core.database.factory

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.pozyalov.ai_advent_challenge.core.database.chat.db.ChatDatabase

actual fun createChatDatabase(
    androidContext: Any?,
    name: String,
    filePath: String?,
    fallbackToDestructiveMigration: Boolean,
): ChatDatabase {
    val targetPath = filePath ?: name
    val builder = Room.databaseBuilder<ChatDatabase>(name = targetPath)
        .setDriver(BundledSQLiteDriver())
    if (fallbackToDestructiveMigration) {
        builder.fallbackToDestructiveMigration(dropAllTables = true)
    }
    return builder.build()
}
