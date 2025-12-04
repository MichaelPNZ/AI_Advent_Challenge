package com.pozyalov.ai_advent_challenge.network.mcp

import io.ktor.client.*

actual object TaskToolClientFactory {
    actual suspend fun create(config: McpClientConfig, httpClient: HttpClient): TaskToolClient {
        // Android поддерживает только HTTP_PROXY режим
        require(config.mode == McpClientConfig.Mode.HTTP_PROXY) {
            "Android supports only HTTP_PROXY mode. Use LOCAL_STDIO on JVM Desktop."
        }

        val proxyUrl = config.proxyUrl
            ?: throw IllegalArgumentException("proxyUrl required for HTTP_PROXY mode")

        val client = HttpMultiTaskToolClient(
            baseUrl = proxyUrl,
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
