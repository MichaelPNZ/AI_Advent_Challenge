package com.pozyalov.ai_advent_challenge.di

import com.pozyalov.ai_advent_challenge.BuildKonfig
import com.pozyalov.ai_advent_challenge.chat.di.chatFeatureModule
import com.pozyalov.ai_advent_challenge.features.chatlist.di.chatListFeatureModule
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module


fun initKoin(
    appModule: Module = module { },
) = startKoin {
    allowOverride(true)
    modules(
        listOf(
            chatFeatureModule(apiKey = BuildKonfig.OPENAI_API_KEY),
            chatListFeatureModule,
            appModule
        )
    )
}
