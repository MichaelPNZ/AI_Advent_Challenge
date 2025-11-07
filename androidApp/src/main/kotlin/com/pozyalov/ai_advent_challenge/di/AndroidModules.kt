package com.pozyalov.ai_advent_challenge.di

import android.content.Context
import com.pozyalov.ai_advent_challenge.core.database.chat.createChatDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

fun androidAppModule(appContext: Context): Module = module {
    single<Context> { appContext }
    single {
        createChatDatabase(
            androidContext = appContext,
            name = "chat_history.db",
            fallbackToDestructiveMigration = true
        )
    }
}
