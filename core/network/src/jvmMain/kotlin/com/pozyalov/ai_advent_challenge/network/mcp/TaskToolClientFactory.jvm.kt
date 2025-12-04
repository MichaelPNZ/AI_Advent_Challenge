package com.pozyalov.ai_advent_challenge.network.mcp

import io.ktor.client.*

actual object TaskToolClientFactory {
    actual suspend fun create(config: McpClientConfig, httpClient: HttpClient): TaskToolClient {
        return when (config.mode) {
            McpClientConfig.Mode.LOCAL_STDIO -> {
                // Desktop: используем прямой stdio-доступ к MCP серверам
                createLocalStdioClient()
            }

            McpClientConfig.Mode.HTTP_PROXY -> {
                // Desktop через HTTP proxy (для тестирования или удаленного доступа)
                val proxyUrl = config.proxyUrl
                    ?: throw IllegalArgumentException("proxyUrl required for HTTP_PROXY mode")
                createHttpProxyClient(proxyUrl, httpClient)
            }
        }
    }

    private fun createLocalStdioClient(): TaskToolClient {
        return MultiTaskToolClient(
            entries = listOf(
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
                ),
                ToolClientEntry(
                    id = "git",
                    title = "Git (ветка)",
                    description = "Текущая git-ветка рабочего каталога.",
                    client = GitTaskToolClient(),
                    defaultEnabled = true,
                    alwaysAvailable = true
                ),
                ToolClientEntry(
                    id = "support-ticket",
                    title = "Support Ticket (поддержка)",
                    description = "Управление тикетами техподдержки: создание, просмотр, обновление статуса.",
                    client = SupportTicketTaskToolClient(),
                    defaultEnabled = true,
                    alwaysAvailable = true
                )
            )
        )
    }

    private suspend fun createHttpProxyClient(baseUrl: String, httpClient: HttpClient): TaskToolClient {
        val client = HttpMultiTaskToolClient(
            baseUrl = baseUrl,
            httpClient = httpClient,
            serverConfigs = listOf(
                HttpMultiTaskToolClient.ServerConfig(
                    id = "weather",
                    serverName = "weather",
                    displayName = "Weather.gov",
                    defaultEnabled = true
                ),
                HttpMultiTaskToolClient.ServerConfig(
                    id = "reminder",
                    serverName = "reminder",
                    displayName = "Reminder",
                    defaultEnabled = true
                ),
                HttpMultiTaskToolClient.ServerConfig(
                    id = "chat-summary",
                    serverName = "chat-summary",
                    displayName = "Chat Summary",
                    defaultEnabled = true
                ),
                HttpMultiTaskToolClient.ServerConfig(
                    id = "doc-pipeline",
                    serverName = "doc-pipeline",
                    displayName = "Document Pipeline",
                    defaultEnabled = true
                ),
                HttpMultiTaskToolClient.ServerConfig(
                    id = "support-ticket",
                    serverName = "support-ticket",
                    displayName = "Support Ticket",
                    defaultEnabled = true
                )
            )
        )

        // Инициализируем все серверы
        client.initialize()

        return client
    }
}
