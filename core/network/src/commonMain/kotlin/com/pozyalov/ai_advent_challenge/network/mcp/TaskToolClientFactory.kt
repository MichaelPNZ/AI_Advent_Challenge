package com.pozyalov.ai_advent_challenge.network.mcp

import io.ktor.client.*

/**
 * Platform-specific конфигурация для создания TaskToolClient.
 */
data class McpClientConfig(
    /**
     * Режим работы:
     * - LOCAL_STDIO: прямой запуск MCP серверов через stdio (только JVM)
     * - HTTP_PROXY: подключение к MCP HTTP Proxy серверу
     */
    val mode: Mode,

    /**
     * URL MCP HTTP Proxy (для режима HTTP_PROXY).
     * Примеры:
     * - Локальный запуск: "http://localhost:8080"
     * - Android эмулятор: "http://10.0.2.2:8080"
     * - iOS симулятор: "http://localhost:8080"
     * - Реальное устройство: "http://192.168.1.100:8080"
     * - VPS: "https://your-server.com"
     */
    val proxyUrl: String? = null
) {
    enum class Mode {
        LOCAL_STDIO,  // Только JVM Desktop
        HTTP_PROXY    // Все платформы
    }
}

/**
 * Expect/actual фабрика для создания platform-specific TaskToolClient.
 */
expect object TaskToolClientFactory {
    /**
     * Создает TaskToolClient в зависимости от платформы и конфигурации.
     *
     * @param config Конфигурация клиента
     * @param httpClient HttpClient для HTTP режима (должен быть сконфигурирован с ContentNegotiation)
     */
    suspend fun create(config: McpClientConfig, httpClient: HttpClient): TaskToolClient
}
