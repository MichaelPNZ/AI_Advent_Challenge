package com.pozyalov.ai_advent_challenge.di

import com.pozyalov.ai_advent_challenge.core.database.factory.createChatDatabase
import com.pozyalov.ai_advent_challenge.network.mcp.MultiTaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.ToolClientEntry
import com.pozyalov.ai_advent_challenge.network.mcp.ToolSelector
import com.pozyalov.ai_advent_challenge.network.mcp.WeatherTaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.WorldBankTaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.ReminderTaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.ChatSummaryTaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.DocPipelineTaskToolClient
import com.pozyalov.ai_advent_challenge.di.AgentPollerConfig
import com.pozyalov.ai_advent_challenge.chat.pipeline.DocPipelineExecutor
import com.pozyalov.ai_advent_challenge.pipeline.DesktopDocPipelineExecutor
import com.pozyalov.ai_advent_challenge.reminder.ReminderNotificationPoller
import com.pozyalov.ai_advent_challenge.summary.DailyChatSummaryPoller
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
    single {
        MultiTaskToolClient(
            entries = listOf(
                ToolClientEntry(
                    id = "worldbank",
                    title = "World Bank (страны)",
                    description = "Список стран по данным World Bank API.",
                    client = WorldBankTaskToolClient(),
                    defaultEnabled = true
                ),
                ToolClientEntry(
                    id = "weather",
                    title = "Weather.gov (прогноз)",
                    description = "Краткий прогноз погоды для указанных координат.",
                    client = WeatherTaskToolClient(),
                    defaultEnabled = true
                ),
                ToolClientEntry(
                    id = "reminder",
                    title = "Reminder (задачи)",
                    description = "Хранение задач и сводки по напоминаниям.",
                    client = ReminderTaskToolClient(),
                    defaultEnabled = true
                ),
                ToolClientEntry(
                    id = "chat-summary",
                    title = "Дневные сводки чатов",
                    description = "Ежедневные дайджесты по каждому чату.",
                    client = ChatSummaryTaskToolClient(),
                    defaultEnabled = true
                ),
                ToolClientEntry(
                    id = "doc-pipeline",
                    title = "Документы (поиск/сводка)",
                    description = "Поиск по документам, суммаризация и сохранение результата.",
                    client = DocPipelineTaskToolClient(),
                    defaultEnabled = true
                )
            )
        )
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
