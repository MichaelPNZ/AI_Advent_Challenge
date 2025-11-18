package com.pozyalov.ai_advent_challenge.mcp.worldbank

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable

private const val TOOL_NAME = "worldbank_list_countries"

private val REGION_CODE_ALIASES = mapOf(
    "ECA" to "ECS", // Europe & Central Asia
    "EAP" to "EAS", // East Asia & Pacific
    "LAC" to "LCN", // Latin America & Caribbean
    "SSA" to "SSF", // Sub-Saharan Africa
    "MNA" to "MEA", // Middle East & North Africa
)

private val REGION_NAME_ALIASES = mapOf(
    "EUROPE & CENTRAL ASIA" to "ECS",
    "EUROPE AND CENTRAL ASIA" to "ECS",
    "EAST ASIA & PACIFIC" to "EAS",
    "EAST ASIA AND PACIFIC" to "EAS",
    "LATIN AMERICA & CARIBBEAN" to "LCN",
    "LATIN AMERICA AND CARIBBEAN" to "LCN",
    "SUB-SAHARAN AFRICA" to "SSF",
    "MIDDLE EAST & NORTH AFRICA" to "MEA",
    "MIDDLE EAST AND NORTH AFRICA" to "MEA",
)

private val VALID_REGION_CODES = setOf("EAS", "ECS", "LCN", "MEA", "NAC", "SAS", "SSF")

fun main() = runBlocking<Unit> {
    val api = WorldBankApi()

    val server = Server(
        serverInfo = Implementation(
            name = "world-bank-mcp-server",
            version = "0.1.0",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = "Сервер предоставляет доступ к открытым данным World Bank API.",
    ) {
        registerCountryTool(api)
    }

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking {
                api.close()
                server.close()
            }
        },
    )

    server.createSession(transport)
    awaitCancellation()
}

private fun Server.registerCountryTool(api: WorldBankApi) {
    val properties = buildJsonObject {
        put(
            "region",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Код региона (например, EAS, ECS, LCN)."))
            },
        )
        put(
            "incomeLevel",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Код уровня дохода (например, HIC, LIC, MIC)."))
            },
        )
        put(
            "limit",
            buildJsonObject {
                put("type", JsonPrimitive("number"))
                put("description", JsonPrimitive("Количество стран в выдаче (1..20)."))
                put("minimum", JsonPrimitive(1))
                put("maximum", JsonPrimitive(20))
            },
        )
    }

    addTool(
        name = TOOL_NAME,
        title = "Список стран World Bank",
        description = "Возвращает список стран с фильтрами по региону и уровню дохода.",
        inputSchema = Tool.Input(
            properties = properties,
            required = emptyList(),
        ),
    ) { request ->
        println(
            "[WorldBank MCP] Запрос worldbank_list_countries: " +
                "region=${request.arguments["region"]?.jsonPrimitive?.contentOrNull}, " +
                "income=${request.arguments["incomeLevel"]?.jsonPrimitive?.contentOrNull}, " +
                "limit=${request.arguments["limit"]?.jsonPrimitive?.intOrNull}"
        )
        val region = normalizeRegionCode(request.arguments["region"]?.jsonPrimitive?.contentOrNull)
        val income = normalizeIncomeLevel(request.arguments["incomeLevel"]?.jsonPrimitive?.contentOrNull)
        val limit = request.arguments["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 20) ?: 5

        runCatching {
            api.fetchCountries(region = region, incomeLevel = income, limit = limit)
        }.fold(
            onSuccess = { countries ->
                println("[WorldBank MCP] Найдено ${countries.size} стран")
                val summary = formatCountries(countries, region, income, limit)
                val structured = buildJsonObject {
                    put(
                        "countries",
                        buildJsonArray {
                            countries.forEach { add(it.toJson()) }
                        },
                    )
                }
                CallToolResult(
                    content = listOf(TextContent(text = summary)),
                    structuredContent = structured,
                )
            },
            onFailure = { throwable ->
                println("[WorldBank MCP] Ошибка при получении стран: ${throwable.message}")
                CallToolResult(
                    content = listOf(
                        TextContent(
                            text = "Не удалось получить данные World Bank API: ${throwable.message ?: throwable::class.simpleName}",
                        ),
                    ),
                    isError = true,
                )
            },
        )
    }
}

private fun formatCountries(
    countries: List<Country>,
    region: String?,
    income: String?,
    limit: Int,
): String = buildString {
    if (countries.isEmpty()) {
        append("Страны по заданным фильтрам не найдены.")
        return@buildString
    }
    appendLine("Страны (${countries.size} из ограничителя $limit):")
    countries.forEachIndexed { index, country ->
        val regionValue = country.region?.value ?: "не указан"
        val incomeValue = country.incomeLevel?.value ?: "не указан"
        val capital = country.capitalCity.takeIf { it.isNotBlank() } ?: "без столицы"
        appendLine("${index + 1}. ${country.name} — регион: $regionValue, доход: $incomeValue, столица: $capital")
    }

    if (!region.isNullOrBlank()) appendLine("Фильтр по региону: $region")
    if (!income.isNullOrBlank()) append("Фильтр по уровню дохода: $income")
}

private fun normalizeRegionCode(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val upper = value.uppercase()
    REGION_CODE_ALIASES[upper]?.let { return it }
    REGION_NAME_ALIASES[upper]?.let { return it }
    return when {
        upper in VALID_REGION_CODES -> upper
        else -> value
    }
}

private fun normalizeIncomeLevel(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return value.uppercase()
}

private class WorldBankApi : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            retryOnExceptionIf { _, _ -> true }
            delayMillis { 300 }
        }
    }

    suspend fun fetchCountries(region: String?, incomeLevel: String?, limit: Int): List<Country> {
        val response: JsonElement = client.get("https://api.worldbank.org/v2/country") {
            parameter("format", "json")
            parameter("per_page", limit.coerceIn(1, 50))
            region?.takeIf { it.isNotBlank() }?.let { parameter("region", it) }
            incomeLevel?.takeIf { it.isNotBlank() }?.let { parameter("incomeLevel", it) }
        }.body()

        val dataArray = (response as? JsonArray)?.getOrNull(1)?.jsonArray ?: return emptyList()
        return dataArray.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<Country>(element) }.getOrNull()
        }
    }

    override fun close() {
        client.close()
    }
}

@Serializable
private data class Country(
    val id: String,
    val iso2Code: String,
    val name: String,
    val region: NamedValue? = null,
    val incomeLevel: NamedValue? = null,
    val capitalCity: String = "",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("iso2Code", JsonPrimitive(iso2Code))
        put("name", JsonPrimitive(name))
        region?.value?.let { put("region", JsonPrimitive(it)) }
        incomeLevel?.value?.let { put("incomeLevel", JsonPrimitive(it)) }
        if (capitalCity.isNotBlank()) put("capitalCity", JsonPrimitive(capitalCity))
    }
}

@Serializable
private data class NamedValue(
    val id: String? = null,
    @SerialName("value")
    val value: String? = null,
)
