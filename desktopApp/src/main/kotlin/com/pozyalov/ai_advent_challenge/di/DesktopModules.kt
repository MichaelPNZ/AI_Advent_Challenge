package com.pozyalov.ai_advent_challenge.di

import com.pozyalov.ai_advent_challenge.core.database.factory.createChatDatabase
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

fun desktopAppModule(): Module = module {
    single {
        createChatDatabase(
            filePath = desktopChatDatabasePath(),
            fallbackToDestructiveMigration = true
        )
    }
}

private fun desktopChatDatabasePath(): String {
    val userHome = System.getProperty("user.home").orEmpty().ifBlank { "." }
    val directory = File(userHome, ".ai_advent")
    if (!directory.exists()) {
        directory.mkdirs()
    }
    return File(directory, "chat_history.db").absolutePath
}
