package com.pozyalov.ai_advent_challenge.network.mcp

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.core.Parameters
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * HTTP-клиент для MCP серверов через MCP HTTP Proxy.
 * Работает на всех платформах (JVM, Android, iOS).
 */
class HttpTaskToolClient(
    private val baseUrl: String,
    private val serverName: String,
    private val displayName: String,
    private val httpClient: HttpClient
) : TaskToolClient {

    @Serializable
    private data class ToolDefinitionResponse(
        val name: String,
        val description: String?,
        val inputSchema: JsonObject
    )

    @Serializable
    private data class ToolExecutionRequest(
        val arguments: JsonObject
    )

    @Serializable
    private data class ToolExecutionResponse(
        val success: Boolean,
        val text: String,
        val structured: JsonObject? = null,
        val error: String? = null
    )

    private var _toolDefinitions: List<Tool> = emptyList()

    override val toolDefinitions: List<Tool>
        get() = _toolDefinitions

    /**
     * Инициализация: загружает список инструментов с proxy-сервера.
     * Вызовите один раз при создании клиента.
     */
    suspend fun initialize() {
        try {
            val response: HttpResponse = httpClient.get("$baseUrl/mcp/$serverName/tools")

            if (!response.status.isSuccess()) {
                println("⚠️ Failed to initialize $displayName: ${response.status}")
                _toolDefinitions = emptyList()
                return
            }

            val definitions = response.body<List<ToolDefinitionResponse>>()
            _toolDefinitions = definitions.map { it.toOpenAiTool() }

            println("✅ $displayName initialized: ${_toolDefinitions.size} tools")
        } catch (e: Exception) {
            println("⚠️ Failed to initialize $displayName: ${e.message}")
            _toolDefinitions = emptyList()
        }
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): TaskToolResult? {
        try {
            val response: HttpResponse = httpClient.post("$baseUrl/mcp/$serverName/tool/$toolName") {
                contentType(ContentType.Application.Json)
                setBody(ToolExecutionRequest(arguments))
            }

            if (!response.status.isSuccess()) {
                return TaskToolResult(
                    text = "Ошибка HTTP ${response.status.value}: ${response.bodyAsText()}"
                )
            }

            val result = response.body<ToolExecutionResponse>()

            return if (result.success) {
                TaskToolResult(
                    text = result.text,
                    structured = result.structured
                )
            } else {
                TaskToolResult(
                    text = result.error ?: "Неизвестная ошибка при выполнении $toolName"
                )
            }
        } catch (e: Exception) {
            return TaskToolResult(
                text = "Ошибка выполнения $toolName ($displayName): ${e.message ?: e::class.simpleName}"
            )
        }
    }

    private fun ToolDefinitionResponse.toOpenAiTool(): Tool {
        return Tool.function(
            name = name,
            description = description,
            parameters = Parameters(inputSchema)
        )
    }
}

/**
 * Множественный HTTP-клиент для всех MCP серверов.
 * Автоматически инициализирует все серверы при создании.
 */
class HttpMultiTaskToolClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val serverConfigs: List<ServerConfig>
) : TaskToolClient, ToolSelector {

    @Serializable
    data class ServerConfig(
        val id: String,
        val serverName: String,
        val displayName: String,
        val defaultEnabled: Boolean = true
    )

    private val clients: Map<String, HttpTaskToolClient> = serverConfigs.associate { config ->
        config.id to HttpTaskToolClient(
            baseUrl = baseUrl,
            serverName = config.serverName,
            displayName = config.displayName,
            httpClient = httpClient
        )
    }

    private val enabledIds: MutableSet<String> = serverConfigs
        .filter { it.defaultEnabled }
        .mapTo(mutableSetOf()) { it.id }

    private val _state = MutableStateFlow(ToolSelectorState())
    override val state: StateFlow<ToolSelectorState> = _state

    /**
     * Инициализация всех клиентов.
     * Вызовите один раз после создания.
     */
    suspend fun initialize() {
        println("🔧 Initializing HTTP MCP clients...")
        clients.values.forEach { it.initialize() }
        println("✅ All HTTP MCP clients initialized")
        updateState()
    }

    private fun updateState() {
        val options = serverConfigs.map { config ->
            val client = clients[config.id]!!
            ToolSelectorOption(
                id = config.id,
                title = config.displayName,
                description = null,
                toolNames = client.toolDefinitions.map { it.function.name },
                isAvailable = client.toolDefinitions.isNotEmpty(),
                isEnabled = config.id in enabledIds
            )
        }
        _state.value = ToolSelectorState(options)
    }

    override val toolDefinitions: List<Tool>
        get() = clients
            .filterKeys { it in enabledIds }
            .values
            .flatMap { it.toolDefinitions }

    override suspend fun execute(toolName: String, arguments: JsonObject): TaskToolResult? {
        val owningClient = clients
            .filterKeys { it in enabledIds }
            .values
            .firstOrNull { client ->
                client.toolDefinitions.any { it.function.name == toolName }
            }

        if (owningClient == null) {
            return TaskToolResult(
                "Инструмент $toolName не найден или не активирован."
            )
        }

        return owningClient.execute(toolName, arguments)
    }

    override fun setToolEnabled(serverId: String, enabled: Boolean) {
        if (serverId !in clients) return
        if (enabled) {
            enabledIds.add(serverId)
        } else {
            enabledIds.remove(serverId)
        }
        updateState()
    }
}
