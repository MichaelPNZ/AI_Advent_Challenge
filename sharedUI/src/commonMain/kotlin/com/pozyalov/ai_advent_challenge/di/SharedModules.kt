package com.pozyalov.ai_advent_challenge.di

import com.pozyalov.ai_advent_challenge.BuildKonfig
import com.pozyalov.ai_advent_challenge.chat.ChatAgent
import com.pozyalov.ai_advent_challenge.chat.data.ChatRepositoryImpl
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRepository
import com.pozyalov.ai_advent_challenge.chat.domain.GenerateChatReplyUseCase
import com.pozyalov.ai_advent_challenge.network.AiApi
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val sharedModule = module {
    single(named("openAiKey")) { BuildKonfig.OPENAI_API_KEY }
    factory { AiApi(apiKey = get(named("openAiKey"))) }
    factory<ChatRepository> { ChatRepositoryImpl(api = get()) }
    factory { GenerateChatReplyUseCase(repository = get()) }
    factory { ChatAgent(generateReply = get()) }
}

fun initKoin(
    appModule : Module = module { }
) = startKoin {
    modules(
        sharedModule + listOf(appModule)
    )
}
