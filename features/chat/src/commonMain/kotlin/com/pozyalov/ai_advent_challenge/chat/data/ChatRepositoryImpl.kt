package com.pozyalov.ai_advent_challenge.chat.data

import com.aallam.openai.api.model.ModelId
import com.pozyalov.ai_advent_challenge.chat.domain.AgentReply
import com.pozyalov.ai_advent_challenge.chat.domain.AgentResponseMetrics
import com.pozyalov.ai_advent_challenge.chat.domain.AgentStructuredResponse
import com.pozyalov.ai_advent_challenge.chat.domain.ChatMessage
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRepository
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRole
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelCatalog
import com.pozyalov.ai_advent_challenge.network.api.AiApi
import com.pozyalov.ai_advent_challenge.network.api.AiCompletionResult
import com.pozyalov.ai_advent_challenge.network.api.AiCompletionUsage
import com.pozyalov.ai_advent_challenge.network.api.AiMessage
import com.pozyalov.ai_advent_challenge.network.api.AiRole
import com.pozyalov.ai_advent_challenge.network.api.OllamaApi
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

class ChatRepositoryImpl(
    private val api: AiApi,
    private val localApi: OllamaApi,
    private val toolClient: TaskToolClient,
    private val json: Json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = false
    },
) : ChatRepository {

    override val isConfigured: Boolean get() = api.isConfigured

    override fun close() {
        api.close()
    }

    override suspend fun generateReply(
        history: List<ChatMessage>,
        model: ModelId,
        temperature: Double,
        systemPrompt: String,
        reasoningEffort: String,
    ): Result<AgentReply> {
        val sanitizedHistory = history.filter { it.content.isNotBlank() }
        val isLocalModel = LlmModelCatalog.isLocalModel(model.id)

        val toolPrompt = buildToolPrompt()
        val requestMessages = buildList {
            val basePrompt = systemPrompt.takeUnless { it.isBlank() } ?: ""
            val combinedPrompt = buildString {
                if (basePrompt.isNotEmpty()) {
                    append(basePrompt.trim())
                    append("\n\n")
                }
                append(RESPONSE_PROMPT)
                if (toolPrompt.isNotEmpty()) {
                    append("\n\n")
                    append(toolPrompt)
                }
            }
            add(AiMessage(role = AiRole.System, text = combinedPrompt))
            sanitizedHistory.forEach { add(it.toAiMessage()) }
        }

        println("[ChatRepo] Calling api.chatCompletion...")
        val completionResult = when {
            isLocalModel -> localApi.chatCompletion(
                messages = requestMessages,
                model = model.id,
                temperature = temperature
            )

            !api.isConfigured -> {
                Result.failure(IllegalStateException("OpenAI API key is missing"))
            }

            else -> api.chatCompletion(
                messages = requestMessages,
                model = model,
                temperature = temperature,
                reasoningEffort = reasoningEffort,
                toolClient = toolClient
            )
        }
        println("[ChatRepo] api.chatCompletion returned, mapping result...")
        return completionResult.mapCatching { result ->
            println("[ChatRepo] Inside mapCatching, parsing response...")
            val structured = parseStructuredResponse(result.content)
            println("[ChatRepo] Creating AgentReply...")
            val reply = AgentReply(
                structured = structured,
                metrics = result.toMetrics()
            )
            println("[ChatRepo] AgentReply created, returning...")
            reply
        }.also { finalResult ->
            println("[ChatRepo] Final result: ${if (finalResult.isSuccess) "SUCCESS" else "FAILURE: ${finalResult.exceptionOrNull()?.message}"}")
        }
    }

    private fun ChatMessage.toAiMessage(): AiMessage {
        val role = when (role) {
            ChatRole.System -> AiRole.System
            ChatRole.User -> AiRole.User
            ChatRole.Assistant -> AiRole.Assistant
        }
        return AiMessage(role = role, text = content)
    }

    private fun parseStructuredResponse(raw: String): AgentStructuredResponse {
        println("[ChatRepo] Parsing response, length=${raw.length}")
        val normalized = stripMarkdownFences(raw)
        println("[ChatRepo] After stripMarkdownFences, length=${normalized.length}")
        require(normalized.isNotBlank()) {
            "Agent returned an empty response"
        }

        println("[ChatRepo] Parsing JSON...")
        val element = runCatching { parseJson(normalized) }.getOrElse { cause ->
            println("[ChatRepo] Failed to parse JSON, falling back to plain text: ${cause.message}")
            return fallbackStructuredResponse(normalized, cause)
        }
        println("[ChatRepo] JSON parsed successfully, type=${element::class.simpleName}")

        if (element is JsonObject && element.containsKey("error")) {
            val error = runCatching {
                json.decodeFromJsonElement(AgentErrorPayload.serializer(), element)
            }.getOrElse { cause ->
                throw IllegalStateException(
                    "Agent returned error payload that could not be parsed",
                    cause
                )
            }
            throw IllegalStateException(error.reason.ifBlank { error.error })
        }

        val jsonObject = element as? JsonObject
            ?: return fallbackStructuredResponse(normalized, IllegalStateException("Agent response is not a JSON object"))

        println("[ChatRepo] Converting to AgentPayload...")
        val payload = jsonObject.toAgentPayload()
        println("[ChatRepo] Converting to domain model...")
        val domain = payload.toDomain()
        println("[ChatRepo] Sanitizing response...")
        val result = domain.sanitized()
        println("[ChatRepo] Response parsed successfully: title='${result.title}', confidence=${result.confidence}")
        return result
    }

    private fun AgentStructuredResponse.sanitized(): AgentStructuredResponse {
        val safeConfidence = when {
            confidence.isNaN() -> 0.0
            confidence.isInfinite() -> if (confidence > 0) 1.0 else 0.0
            else -> confidence
        }.coerceIn(0.0, 1.0)

        val trimmedTitle = title.trim().take(120)
        val trimmedSummary = summary.trim()

        return copy(
            title = trimmedTitle,
            summary = trimmedSummary,
            confidence = safeConfidence
        )
    }

    private fun JsonObject.toAgentPayload(): AgentResponsePayload {
        val title = stringOrNull("title").orEmpty()
        val summary = stringOrNull("summary")
            ?: stringOrNull("answer")
            ?: ""
        val confidence = doubleOrNull("confidence") ?: 0.0

        return AgentResponsePayload(
            title = title,
            summary = summary,
            confidence = confidence
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.doubleOrNull(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }

    private fun parseJson(text: String): JsonElement {
        return try {
            json.parseToJsonElement(text)
        } catch (failure: SerializationException) {
            throw IllegalStateException(
                "Agent returned invalid JSON: ${failure.message.orEmpty()}",
                failure
            )
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException(
                "Agent returned invalid JSON: ${failure.message.orEmpty()}",
                failure
            )
        }
    }

    private fun stripMarkdownFences(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed

        val lines = trimmed.lines()
        val startIndex = if (lines.isNotEmpty() && lines.first().startsWith("```")) 1 else 0
        val endIndex =
            lines.indexOfLast { it.trim() == "```" }.takeIf { it > startIndex } ?: lines.size
        val body = lines.subList(startIndex, endIndex)
        return body.joinToString("\n").trim()
    }

    private fun fallbackStructuredResponse(raw: String, cause: Throwable?): AgentStructuredResponse {
        val safe = raw.trim()
        val title = safe.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(120)
            ?.ifBlank { null }
            ?: "Ответ"
        val confidence = 0.15
        println("[ChatRepo] Returning fallback structured response (confidence=$confidence)")
        if (cause != null) {
            println("[ChatRepo] Fallback reason: ${cause.message}")
        }
        return AgentStructuredResponse(
            title = title,
            summary = safe,
            confidence = confidence
        )
    }

    private fun AgentResponsePayload.toDomain(): AgentStructuredResponse =
        AgentStructuredResponse(
            title = title,
            summary = summary,
            confidence = confidence
        )

    private fun AiCompletionResult.toMetrics(): AgentResponseMetrics =
        AgentResponseMetrics(
            modelId = modelId,
            durationMillis = durationMillis,
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            totalTokens = usage?.totalTokens,
            costUsd = calculateCostUsd(modelId, usage)
        )

    private fun calculateCostUsd(modelId: String, usage: AiCompletionUsage?): Double? {
        if (usage == null) return null
        val option = LlmModelCatalog.models.firstOrNull { it.id == modelId } ?: return null
        val promptRate = option.promptPricePer1KTokensUsd
        val completionRate = option.completionPricePer1KTokensUsd
        if (promptRate == null && completionRate == null) return null

        val promptCost = usage.promptTokens?.let { tokens ->
            promptRate?.let { rate -> (tokens.toDouble() / TOKENS_IN_THOUSAND) * rate }
        } ?: 0.0
        val completionCost = usage.completionTokens?.let { tokens ->
            completionRate?.let { rate -> (tokens.toDouble() / TOKENS_IN_THOUSAND) * rate }
        } ?: 0.0

        return (promptCost + completionCost)
    }

    @Serializable
    private data class AgentResponsePayload(
        val title: String,
        val summary: String,
        val confidence: Double,
    )

    @Serializable
    private data class AgentErrorPayload(
        val error: String,
        val reason: String,
    )

    private fun buildToolPrompt(): String {
        if (toolClient.toolDefinitions.isEmpty()) return ""
        val builder = StringBuilder()
        builder.appendLine("ИНСТРУМЕНТЫ:")
        toolClient.toolDefinitions.forEach { tool ->
            builder.append("- ")
            builder.append(tool.function.name)
            tool.function.description?.let { desc ->
                builder.append(": ")
                builder.append(desc)
            }
            builder.appendLine()
        }
        builder.append("Если нужно получить актуальные данные из этих источников, обязательно вызови соответствующий инструмент и используй его результат в ответе.")
        return builder.toString()
    }

    private companion object {
        private val JsonPrimitive.contentOrNull: String?
            get() = try {
                content
            } catch (_: IllegalStateException) {
                null
            }

        private val RESPONSE_PROMPT: String = """
ФОРМАТ ОТВЕТА:
- Всегда возвращай один JSON-объект в кодировке UTF-8 без дополнительного текста.
- Структура:
  {
    "title": "краткий заголовок ответа (до 120 символов)",
    "answer": "основной текст ответа",
    "confidence": число от 0 до 1
  }

ПРАВИЛА:
- `title` отражает тему ответа.
- `answer` содержит ясное объяснение или рекомендацию без лишних вопросов пользователю.
- `confidence` — десятичное число: 0 = нет уверенности, 1 = максимальная уверенность.
- Если данных мало, укажи это в `answer` и снизь `confidence`.
- Не добавляй и не удаляй поля, не используй Markdown.
        """.trimIndent()
//        private val RESPONSE_PROMPT: String = """
//Ты — ведущий фасилитатор команды виртуальных экспертов. Для каждого запроса:
//1. Сформируй группу минимум из трёх экспертов с разными ролями (например, Аналитик, Инженер, Скептик).
//2. По очереди дай каждому эксперту высказать своё решение или идею (коротко, по шагам).
//3. Объедини их выводы в финальный ответ.
//
//ФОРМАТ ОТВЕТА:
//- Всегда возвращай один JSON-объект в кодировке UTF-8 без дополнительного текста.
//- Структура:
//  {
//    "title": "краткий заголовок ответа (до 120 символов)",
//    "answer": "основной текст ответа",
//    "confidence": число от 0 до 1
//  }
//
//ПРАВИЛА:
//- `title` отражает тему ответа.
//- `answer` должен содержать:
//  • перечисление экспертов и их шагов/идей;
//  • итоговое резюме ведущего фасилитатора.
//- Всегда решай пошагово и явно указывай вклад каждого эксперта.
//- `confidence` — десятичное число: 0 = нет уверенности, 1 = максимальная уверенность.
//- Если данных мало, укажи это в `answer` и снизь `confidence`.
//- Не добавляй и не удаляй поля, не используй Markdown.
//        """.trimIndent()
        private const val TOKENS_IN_THOUSAND = 1_000.0
    }
}
