package com.pozyalov.ai_advent_challenge.mcp.proxy

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.event.Level

/**
 * HTTP прокси для MCP серверов.
 * Использует прямые запуски процессов для каждого вызова (без кэширования клиентов).
 */
class McpHttpProxyServer(
    private val mcpServers: Map<String, ServerConfig>,
    private val port: Int = 8080,
    private val host: String = "0.0.0.0"
) {

    @Serializable
    data class ServerConfig(
        val command: List<String>,
        val displayName: String
    )

    @Serializable
    data class ToolDefinitionResponse(
        val name: String,
        val description: String?,
        val inputSchema: JsonObject
    )

    @Serializable
    data class ToolExecutionRequest(
        val arguments: JsonObject
    )

    @Serializable
    data class ToolExecutionResponse(
        val success: Boolean,
        val text: String,
        val structured: JsonObject? = null,
        val error: String? = null
    )

    @Serializable
    data class HealthResponse(
        val status: String,
        val servers: Map<String, ServerStatus>
    )

    @Serializable
    data class ServerStatus(
        val available: Boolean,
        val toolCount: Int
    )

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

    // Временное решение: используем свой inspector вместо постоянного клиента
    private val inspector = com.pozyalov.ai_advent_challenge.network.mcp.McpToolInspector()

    fun start() {
        embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) {
                json(json)
            }

            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Options)
                allowHeader(HttpHeaders.ContentType)
            }

            install(CallLogging) {
                level = Level.INFO
                filter { call -> call.request.path().startsWith("/mcp") }
            }

            routing {
                // Health check
                get("/health") {
                    val statuses = mcpServers.mapValues { (serverName, config) ->
                        try {
                            val tools = inspector.listTools(config.command)
                            ServerStatus(available = true, toolCount = tools.size)
                        } catch (e: Exception) {
                            ServerStatus(available = false, toolCount = 0)
                        }
                    }
                    call.respond(HealthResponse(status = "ok", servers = statuses))
                }

                // Список всех серверов
                get("/mcp/servers") {
                    call.respond(mcpServers.keys.toList())
                }

                // Список инструментов сервера
                get("/mcp/{serverName}/tools") {
                    val serverName = call.parameters["serverName"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, "Missing serverName")
                        return@get
                    }

                    val config = mcpServers[serverName]
                    if (config == null) {
                        call.respond(HttpStatusCode.NotFound, "Server '$serverName' not found")
                        return@get
                    }

                    try {
                        val tools = inspector.listTools(config.command)

                        val response = tools.map { tool ->
                            ToolDefinitionResponse(
                                name = tool.name,
                                description = tool.description,
                                inputSchema = tool.inputSchema.toJsonObject()
                            )
                        }

                        call.respond(response)
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ToolExecutionResponse(
                                success = false,
                                text = "",
                                error = "Failed to list tools: ${e.message}"
                            )
                        )
                    }
                }

                // Выполнить инструмент
                post("/mcp/{serverName}/tool/{toolName}") {
                    val serverName = call.parameters["serverName"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, "Missing serverName")
                        return@post
                    }

                    val toolName = call.parameters["toolName"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, "Missing toolName")
                        return@post
                    }

                    val config = mcpServers[serverName]
                    if (config == null) {
                        call.respond(HttpStatusCode.NotFound, "Server '$serverName' not found")
                        return@post
                    }

                    try {
                        val request = call.receive<ToolExecutionRequest>()

                        val result = inspector.callTool(
                            serverCommand = config.command,
                            toolName = toolName,
                            arguments = request.arguments
                        )

                        if (result == null) {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ToolExecutionResponse(
                                    success = false,
                                    text = "",
                                    error = "Tool returned null result"
                                )
                            )
                            return@post
                        }

                        val textContent = result.content.joinToString("\n") { content ->
                            when (content) {
                                is io.modelcontextprotocol.kotlin.sdk.TextContent -> content.text.orEmpty()
                                else -> content.toString()
                            }
                        }

                        // Пытаемся распарсить structured content
                        val structuredContent = try {
                            json.decodeFromString<JsonObject>(textContent)
                        } catch (e: Exception) {
                            null
                        }

                        call.respond(
                            ToolExecutionResponse(
                                success = result.isError != true,
                                text = textContent,
                                structured = structuredContent,
                                error = if (result.isError == true) textContent else null
                            )
                        )
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ToolExecutionResponse(
                                success = false,
                                text = "",
                                error = "Tool execution failed: ${e.message}"
                            )
                        )
                    }
                }
            }
        }.start(wait = true)
    }

    private fun io.modelcontextprotocol.kotlin.sdk.Tool.Input.toJsonObject(): JsonObject {
        // Конвертируем Tool.Input в JsonObject
        return json.encodeToJsonElement(
            io.modelcontextprotocol.kotlin.sdk.Tool.Input.serializer(),
            this
        ) as JsonObject
    }
}
