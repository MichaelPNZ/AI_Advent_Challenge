package com.pozyalov.ai_advent_challenge.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

/**
 * Minimal client for talking to a local Ollama server via /api/chat.
 * Keeps the same surface as AiApi#chatCompletion so ChatRepository can switch by model id.
 */
class OllamaApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: HttpClient = defaultClient()
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val isConfigured: Boolean get() = true

    suspend fun chatCompletion(
        messages: List<AiMessage>,
        model: String,
        temperature: Double?,
        tuning: OllamaTuning? = null,
    ): Result<AiCompletionResult> {
        val sanitized = messages.filter { it.text.isNotBlank() }
        val options = Options.from(
            userTemperature = temperature,
            tuning = tuning
        )

        val request = ChatRequest(
            model = model.substringAfter(OLLAMA_PREFIX, model),
            messages = sanitized.map { it.toOllamaMessage() },
            stream = false,
            options = options
        )

        val timer = TimeSource.Monotonic.markNow()
        return runCatching {
            val response: HttpResponse = httpClient.post("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            val rawContentType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
            val bodyText = response.bodyAsText()
            val parsed = when {
                rawContentType.contains("application/x-ndjson") ||
                    rawContentType.contains("text/event-stream") ||
                    bodyText.lineSequence().drop(1).any() ->
                    parseNdJson(bodyText)

                else -> json.decodeFromString(ChatResponse.serializer(), bodyText)
            }

            val content = parsed.message?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error("Модель не вернула ответ")

            AiCompletionResult(
                content = content,
                modelId = parsed.model ?: request.model,
                durationMillis = timer.elapsedNow().inWholeMilliseconds,
                usage = null
            )
        }
    }

    private fun AiMessage.toOllamaMessage(): OllamaMessage =
        OllamaMessage(
            role = when (role) {
                AiRole.System -> "system"
                AiRole.User -> "user"
                AiRole.Assistant -> "assistant"
            },
            content = text
        )

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<OllamaMessage>,
        val stream: Boolean = false,
        val options: Options? = null
    )

    @Serializable
    private data class OllamaMessage(
        val role: String,
        val content: String
    )

    @Serializable
    private data class Options(
        val temperature: Double? = null,
        @SerialName("num_ctx")
        val numCtx: Int? = null,
        @SerialName("num_predict")
        val numPredict: Int? = null,
        @SerialName("top_p")
        val topP: Double? = null,
        @SerialName("top_k")
        val topK: Int? = null,
        @SerialName("repeat_penalty")
        val repeatPenalty: Double? = null,
    ) {
        companion object {
            fun from(
                userTemperature: Double?,
                tuning: OllamaTuning?
            ): Options? {
                if (userTemperature == null && tuning == null) return null
                return Options(
                    temperature = userTemperature?.coerceIn(0.0, 2.0) ?: tuning?.temperature?.coerceIn(0.0, 2.0),
                    numCtx = tuning?.numCtx,
                    numPredict = tuning?.numPredict,
                    topP = tuning?.topP,
                    topK = tuning?.topK,
                    repeatPenalty = tuning?.repeatPenalty
                )
            }
        }
    }

    @Serializable
    private data class ChatResponse(
        @SerialName("model")
        val model: String? = null,
        @SerialName("message")
        val message: ChatMessagePayload? = null
    )

    @Serializable
    private data class ChatMessagePayload(
        val role: String? = null,
        val content: String? = null
    )

    companion object {
        // Подключаемся к Ollama на VPS по умолчанию
        private const val DEFAULT_BASE_URL = "http://208.123.185.229:11434"
        private const val OLLAMA_PREFIX = "ollama:"

        private fun defaultClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
            install(HttpTimeout) {
                // Генерация может занять время на холодном старте или больших моделях.
                val timeoutMillis = 300_000L
                requestTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
                connectTimeoutMillis = 30_000L
            }
        }
    }

    private fun parseNdJson(body: String): ChatResponse {
        var modelId: String? = null
        val builder = StringBuilder()
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                runCatching {
                    val element = json.parseToJsonElement(line)
                    val obj: JsonObject = element.jsonObject
                    val model = (obj["model"] as? JsonPrimitive)?.contentOrNull
                    if (model != null) modelId = model

                    val message = obj["message"] as? JsonObject
                    val content = message?.get("content") as? JsonPrimitive
                    content?.contentOrNull?.let { piece ->
                        builder.append(piece)
                    }
                }
            }

        val aggregated = builder.toString().trim()
        if (aggregated.isEmpty()) {
            error("Streaming ответ Ollama пустой или не содержит message.content")
        }
        return ChatResponse(
            model = modelId,
            message = ChatMessagePayload(role = "assistant", content = aggregated)
        )
    }
}

/**
 * Контейнер для тонкой настройки локальной Ollama-модели.
 * Добавлен отдельным типом, чтобы не размазывать логику по другим слоям.
 */
data class OllamaTuning(
    val numCtx: Int? = null,
    val numPredict: Int? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val repeatPenalty: Double? = null,
    val temperature: Double? = null
)
