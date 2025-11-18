package com.pozyalov.ai_advent_challenge.mcp.weather

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Starts an MCP server that provides weather-related tools for fetching active weather alerts by state and weather forecasts by latitude/longitude.
 */
fun runMcpServer() {
    // Create an HTTP client with a default request configuration and JSON content negotiation
    val httpClient = HttpClient {
        install(Logging) { level = LogLevel.INFO }
        defaultRequest {
            headers {
                append("Accept", "application/json")
                append("User-Agent", "WeatherApiClient/1.0")
            }
            contentType(ContentType.Application.Json)
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                },
            )
        }
    }

    // Create the MCP Server instance with a basic implementation
    val server = Server(
        Implementation(
            name = "weather", // Tool name is "weather"
            version = "1.0.0", // Version of the implementation
        ),
        ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )

    // Register a tool to fetch weather alerts by state
    server.addTool(
        name = "get_alerts",
        description = """
            Get weather alerts for a US state. Input is Two-letter US state code (e.g. CA, NY)
        """.trimIndent(),
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("state") {
                    put("type", "string")
                    put("description", "Two-letter US state code (e.g. CA, NY)")
                }
            },
            required = listOf("state"),
        ),
    ) { request ->
        val state = request.arguments["state"]?.jsonPrimitive?.content ?: return@addTool CallToolResult(
            content = listOf(TextContent("The 'state' parameter is required.")),
        )
        println("[Weather MCP] get_alerts state=$state")
        runCatching {
            httpClient.getAlerts(state)
        }.fold(
            onSuccess = { alerts ->
                println("[Weather MCP] Получено ${alerts.size} предупреждений для $state")
                CallToolResult(content = alerts.map { TextContent(it) })
            },
            onFailure = { error ->
                val reason = error.message ?: "unknown"
                println("[Weather MCP] Ошибка get_alerts: $reason")
                CallToolResult(
                    content = listOf(TextContent("Не удалось получить предупреждения: $reason")),
                    isError = true
                )
            }
        )
    }

    // Register a tool to fetch weather forecast by latitude and longitude
    server.addTool(
        name = "get_forecast",
        description = """
            Get weather forecast for a specific latitude/longitude
        """.trimIndent(),
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("latitude") {
                    put("type", "number")
                }
                putJsonObject("longitude") {
                    put("type", "number")
                }
            },
            required = listOf("latitude", "longitude"),
        ),
    ) { request ->
        val latitude = request.arguments["latitude"]?.jsonPrimitive?.doubleOrNull
        val longitude = request.arguments["longitude"]?.jsonPrimitive?.doubleOrNull
        if (latitude == null || longitude == null) {
            return@addTool CallToolResult(
                content = listOf(TextContent("The 'latitude' and 'longitude' parameters are required.")),
            )
        }
        println("[Weather MCP] get_forecast lat=$latitude lon=$longitude")
        runCatching {
            httpClient.getForecast(latitude, longitude)
        }.fold(
            onSuccess = { forecast ->
                println("[Weather MCP] Получено ${forecast.size} периодов прогноза")
                CallToolResult(content = forecast.map { TextContent(it) })
            },
            onFailure = { error ->
                val reason = error.message ?: "unknown"
                println("[Weather MCP] Ошибка get_forecast: $reason")
                CallToolResult(
                    content = listOf(TextContent("Не удалось получить прогноз: $reason")),
                    isError = true
                )
            }
        )
    }

    // Create a transport using standard IO for server communication
    val transport = StdioServerTransport(
        System.`in`.asInput(),
        System.out.asSink().buffered(),
    )

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose {
            done.complete()
        }
        done.join()
    }
}
