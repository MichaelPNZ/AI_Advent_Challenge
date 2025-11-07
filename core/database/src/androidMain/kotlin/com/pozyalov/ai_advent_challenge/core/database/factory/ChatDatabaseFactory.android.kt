package com.pozyalov.ai_advent_challenge.core.database.factory

import android.content.Context
import androidx.room.Room
import com.pozyalov.ai_advent_challenge.core.database.chat.db.ChatDatabase

actual fun createChatDatabase(
    androidContext: Any?,
    name: String,
    filePath: String?,
    fallbackToDestructiveMigration: Boolean,
): ChatDatabase {
    require(androidContext is Context) {
        "Android context must be provided when creating the chat database on Android."
    }
    val builder = Room.databaseBuilder(
        androidContext,
        ChatDatabase::class.java,
        name
    )
    if (fallbackToDestructiveMigration) {
        builder.fallbackToDestructiveMigration()
    }
    return builder.build()
}
