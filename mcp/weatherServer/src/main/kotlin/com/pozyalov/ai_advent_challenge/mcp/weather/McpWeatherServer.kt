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
import kotlinx.serialization.json.putJsonArray
import kotlin.math.abs

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

    // Register a simple location search tool (offline, small curated list)
    server.addTool(
        name = "search_location",
        description = "Поиск города/локации и получение координат для прогноза.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Название города или локации")
                }
            },
            required = listOf("query")
        )
    ) { request ->
        val query = request.arguments["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (query.isBlank()) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'query' обязательно.")),
                isError = true
            )
        }
        val matches = LOCATION_INDEX
            .map { location ->
                val score = matchScore(query, location)
                location to score
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(5)
            .map { (location, score) -> location.copy(score = score) }

        if (matches.isEmpty()) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Совпадений не найдено для '$query'.")),
                isError = true
            )
        }

        val text = buildString {
            appendLine("Найденные локации:")
            matches.forEachIndexed { index, m ->
                appendLine("${index + 1}. ${m.name} (${m.country}) — lat=${m.latitude}, lon=${m.longitude}")
            }
        }.trim()

        CallToolResult(
            content = listOf(TextContent(text)),
            structuredContent = buildJsonObject {
                putJsonArray("matches") {
                    matches.forEach { match ->
                        add(match.toJson())
                    }
                }
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

private data class LocationEntry(
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val score: Double = 0.0
) {
    fun toJson() = buildJsonObject {
        put("name", name)
        put("country", country)
        put("latitude", latitude)
        put("longitude", longitude)
        put("score", score)
    }
}

// Minimal offline index to avoid сетевые запросы
private val LOCATION_INDEX = listOf(
    LocationEntry("Москва", "RU", 55.7558, 37.6173),
    LocationEntry("Санкт-Петербург", "RU", 59.9311, 30.3609),
    LocationEntry("Новосибирск", "RU", 55.0084, 82.9357),
    LocationEntry("Екатеринбург", "RU", 56.8389, 60.6057),
    LocationEntry("Казань", "RU", 55.7963, 49.1088),
    LocationEntry("Сочи", "RU", 43.5855, 39.7231),
    LocationEntry("Минск", "BY", 53.9006, 27.5590),
    LocationEntry("Алматы", "KZ", 43.2220, 76.8512),
    LocationEntry("Астана", "KZ", 51.1605, 71.4704),
    LocationEntry("Дубай", "AE", 25.2048, 55.2708),
    LocationEntry("Стамбул", "TR", 41.0082, 28.9784),
    LocationEntry("Анталья", "TR", 36.8969, 30.7133),
    LocationEntry("Лондон", "GB", 51.5072, -0.1276),
    LocationEntry("Нью-Йорк", "US", 40.7128, -74.0060),
    LocationEntry("Лос-Анджелес", "US", 34.0522, -118.2437),
    LocationEntry("Токио", "JP", 35.6764, 139.6500),
    LocationEntry("Париж", "FR", 48.8566, 2.3522),
    LocationEntry("Берлин", "DE", 52.5200, 13.4050),
    LocationEntry("Барселона", "ES", 41.3851, 2.1734),
    LocationEntry("Рим", "IT", 41.9028, 12.4964)
)

private fun matchScore(query: String, location: LocationEntry): Double {
    val q = query.lowercase()
    val name = location.name.lowercase()
    val country = location.country.lowercase()
    val exact = if (name == q) 1.0 else 0.0
    val startsWith = if (name.startsWith(q)) 0.8 else 0.0
    val contains = if (name.contains(q)) 0.6 else 0.0
    val countryBonus = if (country.contains(q)) 0.3 else 0.0
    return maxOf(exact, startsWith, contains) + countryBonus - 0.001 * abs(q.length - name.length)
}
