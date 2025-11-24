package com.pozyalov.ai_advent_challenge.chat.di

import com.arkivanov.decompose.ComponentContext
import com.pozyalov.ai_advent_challenge.chat.component.ChatAgent
import com.pozyalov.ai_advent_challenge.chat.component.ChatComponent
import com.pozyalov.ai_advent_challenge.chat.component.ChatComponentImpl
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryExporter
import com.pozyalov.ai_advent_challenge.chat.data.ChatRepositoryImpl
import com.pozyalov.ai_advent_challenge.chat.data.RoomChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.data.memory.AgentMemoryStore
import com.pozyalov.ai_advent_challenge.chat.data.memory.RoomAgentMemoryStore
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRepository
import com.pozyalov.ai_advent_challenge.chat.domain.GenerateChatReplyUseCase
import com.pozyalov.ai_advent_challenge.core.database.chat.data.ChatThreadDataSource
import com.pozyalov.ai_advent_challenge.core.database.chat.data.RoomChatThreadDataSource
import com.pozyalov.ai_advent_challenge.core.database.di.chatDatabaseModule
import com.pozyalov.ai_advent_challenge.network.di.networkModule
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.ToolSelector
import com.pozyalov.ai_advent_challenge.network.mcp.ToolSelectorStub
import com.pozyalov.ai_advent_challenge.chat.pipeline.DocPipelineExecutor
import com.pozyalov.ai_advent_challenge.chat.pipeline.TripBriefingExecutor
import com.pozyalov.ai_advent_challenge.chat.pipeline.EmbeddingIndexExecutor
import org.koin.core.module.Module
import org.koin.dsl.module

fun chatFeatureModule(
    apiKey: String
): Module = module {
    includes(networkModule(apiKey))
    includes(chatDatabaseModule)

    single<AgentMemoryStore> { RoomAgentMemoryStore(dao = get()) }
    single { ChatHistoryExporter() }
    single<ChatHistoryDataSource> { RoomChatHistoryDataSource(dao = get(), memoryStore = get()) }
    single<ChatThreadDataSource> { RoomChatThreadDataSource(dao = get()) }

    single<TaskToolClient> { TaskToolClient.None }
    single<ToolSelector> { ToolSelectorStub }
    single<DocPipelineExecutor> { DocPipelineExecutor.None }
    single<TripBriefingExecutor> { TripBriefingExecutor.None }
    single<com.pozyalov.ai_advent_challenge.chat.pipeline.EmbeddingIndexExecutor> { com.pozyalov.ai_advent_challenge.chat.pipeline.EmbeddingIndexExecutor.None }

    factory<ChatRepository> { ChatRepositoryImpl(api = get(), toolClient = get()) }
    factory { GenerateChatReplyUseCase(repository = get()) }
    factory { ChatAgent(generateReply = get()) }

    factory {
        ChatComponentFactory(
            agentProvider = { get() },
            chatHistory = get(),
            chatThreads = get(),
            memoryStore = get(),
            exporter = get(),
            toolSelector = get(),
            docPipelineExecutor = get(),
            tripBriefingExecutor = get(),
            embeddingIndexExecutor = get()
        )
    }
}

class ChatComponentFactory(
    private val agentProvider: () -> ChatAgent,
    private val chatHistory: ChatHistoryDataSource,
    private val chatThreads: ChatThreadDataSource,
    private val memoryStore: AgentMemoryStore,
    private val exporter: ChatHistoryExporter,
    private val toolSelector: ToolSelector,
    private val docPipelineExecutor: DocPipelineExecutor,
    private val tripBriefingExecutor: TripBriefingExecutor,
    private val embeddingIndexExecutor: com.pozyalov.ai_advent_challenge.chat.pipeline.EmbeddingIndexExecutor
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
        agentMemoryStore = memoryStore,
        historyExporter = exporter,
        toolSelector = toolSelector,
        docPipelineExecutor = docPipelineExecutor,
        tripBriefingExecutor = tripBriefingExecutor,
        embeddingIndexExecutor = embeddingIndexExecutor,
        threadId = threadId,
        onClose = onClose
    )
}
