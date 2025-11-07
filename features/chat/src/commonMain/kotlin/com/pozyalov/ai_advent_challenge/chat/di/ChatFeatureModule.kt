package com.pozyalov.ai_advent_challenge.chat.di

import com.arkivanov.decompose.ComponentContext
import com.pozyalov.ai_advent_challenge.chat.component.ChatAgent
import com.pozyalov.ai_advent_challenge.chat.component.ChatComponent
import com.pozyalov.ai_advent_challenge.chat.component.ChatComponentImpl
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.data.ChatRepositoryImpl
import com.pozyalov.ai_advent_challenge.chat.data.RoomChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRepository
import com.pozyalov.ai_advent_challenge.chat.domain.GenerateChatReplyUseCase
import com.pozyalov.ai_advent_challenge.core.database.chat.data.ChatThreadDataSource
import com.pozyalov.ai_advent_challenge.core.database.chat.data.RoomChatThreadDataSource
import com.pozyalov.ai_advent_challenge.core.database.di.chatDatabaseModule
import com.pozyalov.ai_advent_challenge.network.di.networkModule
import org.koin.core.module.Module
import org.koin.dsl.module

fun chatFeatureModule(
    apiKey: String
): Module = module {
    includes(networkModule(apiKey))
    includes(chatDatabaseModule)

    single<ChatHistoryDataSource> { RoomChatHistoryDataSource(dao = get()) }
    single<ChatThreadDataSource> { RoomChatThreadDataSource(dao = get()) }

    factory<ChatRepository> { ChatRepositoryImpl(api = get()) }
    factory { GenerateChatReplyUseCase(repository = get()) }
    factory { ChatAgent(generateReply = get()) }

    factory {
        ChatComponentFactory(
            agentProvider = { get() },
            chatHistory = get(),
            chatThreads = get()
        )
    }
}

class ChatComponentFactory(
    private val agentProvider: () -> ChatAgent,
    private val chatHistory: ChatHistoryDataSource,
    private val chatThreads: ChatThreadDataSource
) {
    fun create(
        componentContext: ComponentContext,
        threadId: Long,
        onClose: () -> Unit
    ): ChatComponent = ChatComponentImpl(
        componentContext = componentContext,
        chatAgent = agentProvider(),
        chatHistory = chatHistory,
        chatThreads = chatThreads,
        threadId = threadId,
        onClose = onClose
    )
}
