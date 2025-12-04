package com.pozyalov.ai_advent_challenge.di

import com.pozyalov.ai_advent_challenge.core.database.factory.createChatDatabase
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClientFactory
import com.pozyalov.ai_advent_challenge.network.mcp.McpClientConfig
import com.pozyalov.ai_advent_challenge.network.mcp.ToolSelector
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import com.pozyalov.ai_advent_challenge.di.AgentPollerConfig
import com.pozyalov.ai_advent_challenge.chat.pipeline.DocPipelineExecutor
import com.pozyalov.ai_advent_challenge.chat.pipeline.TripBriefingExecutor
import com.pozyalov.ai_advent_challenge.pipeline.DesktopDocPipelineExecutor
import com.pozyalov.ai_advent_challenge.pipeline.DesktopTripBriefingExecutor
import com.pozyalov.ai_advent_challenge.pipeline.DesktopEmbeddingIndexExecutor
import com.pozyalov.ai_advent_challenge.pipeline.DesktopRagComparisonExecutor
import com.pozyalov.ai_advent_challenge.embedding.OllamaEmbeddingClient
import com.pozyalov.ai_advent_challenge.embedding.EmbeddingIndexService
import com.pozyalov.ai_advent_challenge.embedding.RagComparisonService
import com.pozyalov.ai_advent_challenge.reminder.ReminderNotificationPoller
import com.pozyalov.ai_advent_challenge.summary.DailyChatSummaryPoller
import com.pozyalov.ai_advent_challenge.review.PrReviewService
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import org.koin.dsl.binds

fun desktopAppModule(): Module = module {
    single {
        createChatDatabase(
            filePath = desktopChatDatabasePath(),
            fallbackToDestructiveMigration = true
        )
    }

    // HttpClient для MCP HTTP режима
    single {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
        }
    }

    // TaskToolClient с поддержкой обоих режимов
    single {
        val mcpMode = System.getProperty("mcp.mode") ?: System.getenv("MCP_MODE") ?: "local"
        val proxyUrl = System.getProperty("mcp.proxy.url") ?: System.getenv("MCP_PROXY_URL") ?: "http://localhost:8080"

        val config = when (mcpMode.lowercase()) {
            "http", "proxy" -> McpClientConfig(McpClientConfig.Mode.HTTP_PROXY, proxyUrl)
            else -> McpClientConfig(McpClientConfig.Mode.LOCAL_STDIO)
        }

        println("🔧 MCP Mode: ${config.mode}")
        if (config.mode == McpClientConfig.Mode.HTTP_PROXY) {
            println("🌐 MCP Proxy URL: ${config.proxyUrl}")
        }

        runBlocking {
            TaskToolClientFactory.create(config, get())
        }
    } binds arrayOf(TaskToolClient::class, ToolSelector::class)
    single<AgentPollerConfig> {
        AgentPollerConfig(
            reminderIntervalSeconds = System.getProperty("ai.advent.reminder.poll.interval.seconds")?.toLongOrNull()
                ?: 120L,
            chatSummaryIntervalMinutes = System.getProperty("ai.advent.chat.summary.poll.minutes")?.toLongOrNull()
                ?: 1440L
        )
    }
    single(createdAtStart = true) {
        val config: AgentPollerConfig = get()
        ReminderNotificationPoller(
            taskToolClient = get(),
            chatHistory = get(),
            chatThreads = get(),
            pollIntervalSeconds = config.reminderIntervalSeconds
        )
    }
    single(createdAtStart = true) {
        val config: AgentPollerConfig = get()
        DailyChatSummaryPoller(
            taskToolClient = get(),
            chatHistory = get(),
            chatThreads = get(),
            pollIntervalMinutes = config.chatSummaryIntervalMinutes
        )
    }
    single<DocPipelineExecutor> {
        DesktopDocPipelineExecutor(
            taskToolClient = get(),
            generateReply = get()
        )
    }
    single<TripBriefingExecutor> {
        DesktopTripBriefingExecutor(
            taskToolClient = get()
        )
    }
    single { OllamaEmbeddingClient() }
    single { EmbeddingIndexService(client = get()) }
    single<com.pozyalov.ai_advent_challenge.chat.pipeline.EmbeddingIndexExecutor> {
        DesktopEmbeddingIndexExecutor(service = get())
    }
    single { RagComparisonService(indexService = get(), generateReply = get()) }
    single<com.pozyalov.ai_advent_challenge.chat.pipeline.RagComparisonExecutor> {
        DesktopRagComparisonExecutor(service = get())
    }
    single {
        PrReviewService(
            gitClient = com.pozyalov.ai_advent_challenge.network.mcp.GitTaskToolClient(),
            indexService = get(),
            generateReply = get()
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

data class AgentPollerConfig(
    val reminderIntervalSeconds: Long,
    val chatSummaryIntervalMinutes: Long
)
