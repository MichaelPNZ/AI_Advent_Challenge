package com.pozyalov.ai_advent_challenge.di

import com.pozyalov.ai_advent_challenge.core.database.chat.createChatDatabase
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

fun iosAppModule(): Module = module {
    single {
        createChatDatabase(
            filePath = iosChatDatabasePath(),
            fallbackToDestructiveMigration = true
        )
    }
}

private fun iosChatDatabasePath(): String {
    val fileManager = NSFileManager.defaultManager
    val documentsUrl = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first()
        as? NSURL
    val directory = documentsUrl?.path ?: NSTemporaryDirectory()
    if (!fileManager.fileExistsAtPath(directory)) {
        fileManager.createDirectoryAtPath(directory, true, null, null)
    }
    return directory.trimEnd('/') + "/chat_history.db"
}
