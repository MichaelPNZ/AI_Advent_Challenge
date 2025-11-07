package com.pozyalov.ai_advent_challenge.di

import com.pozyalov.ai_advent_challenge.BuildKonfig
import com.pozyalov.ai_advent_challenge.chat.ChatAgent
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.data.ChatRepositoryImpl
import com.pozyalov.ai_advent_challenge.chat.data.RoomChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRepository
import com.pozyalov.ai_advent_challenge.chat.domain.GenerateChatReplyUseCase
import com.pozyalov.ai_advent_challenge.core.database.di.chatDatabaseModule
import com.pozyalov.ai_advent_challenge.network.di.networkModule
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

private val sharedModule = module {
    includes(networkModule(apiKey = BuildKonfig.OPENAI_API_KEY))
    factory<ChatRepository> { ChatRepositoryImpl(api = get()) }
    includes(chatDatabaseModule)
    single<ChatHistoryDataSource> { RoomChatHistoryDataSource(dao = get()) }
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
