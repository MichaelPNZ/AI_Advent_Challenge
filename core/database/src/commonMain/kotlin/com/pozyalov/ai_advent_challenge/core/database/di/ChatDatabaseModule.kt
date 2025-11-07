package com.pozyalov.ai_advent_challenge.core.database.di

import com.pozyalov.ai_advent_challenge.core.database.chat.db.ChatDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

val chatDatabaseModule: Module = module {
    single { get<ChatDatabase>().chatMessageDao() }
    single { get<ChatDatabase>().chatThreadDao() }
}
