package com.pozyalov.ai_advent_challenge.chat.data

import com.pozyalov.ai_advent_challenge.chat.domain.AgentStructuredResponse
import com.pozyalov.ai_advent_challenge.chat.domain.ChatMessage
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRepository
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRole
import com.pozyalov.ai_advent_challenge.network.AiApi
import com.pozyalov.ai_advent_challenge.network.AiMessage
import com.pozyalov.ai_advent_challenge.network.AiRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

class ChatRepositoryImpl(
    private val api: AiApi,
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

    override suspend fun generateReply(history: List<ChatMessage>): Result<AgentStructuredResponse> {
        val sanitizedHistory = history.filter { it.content.isNotBlank() }

        val requestMessages = buildList {
            add(AiMessage(role = AiRole.System, text = RESPONSE_PROMPT))
            sanitizedHistory.forEach { add(it.toAiMessage()) }
        }

        return api.chatCompletion(requestMessages).mapCatching { raw ->
            parseStructuredResponse(raw)
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
        val normalized = stripMarkdownFences(raw)
        require(normalized.isNotBlank()) {
            "Agent returned an empty response"
        }

        val element = parseJson(normalized)

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

        if (element !is JsonObject) {
            throw IllegalStateException("Agent response is not a JSON object")
        }

        val payload = element.toAgentPayload()
        return payload.toDomain().sanitized()
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

    private fun AgentResponsePayload.toDomain(): AgentStructuredResponse =
        AgentStructuredResponse(
            title = title,
            summary = summary,
            confidence = confidence
        )

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

    private companion object {
        private val JsonPrimitive.contentOrNull: String?
            get() = try {
                content
            } catch (_: IllegalStateException) {
                null
            }

        private val RESPONSE_PROMPT: String = """
Ты — AI-продуктолог, который ведёт диалог с пользователем, чтобы подготовить техзадание на 
Android-приложение и выдать рекомендации по технологическому стеку.

ЦЕЛЬ ДИАЛОГА:
1. Выяснить назначение приложения, целевую аудиторию, ключевые пользовательские сценарии.
2. Собрать функциональные требования (обязательные фичи, интеграции, офлайн, аналитика, монетизация,
 безопасность).
3. Зафиксировать нефункциональные требования и ограничения: производительность, масштабируемость,
 сроки, бюджет, состав команды, предпочтения по технологиям.

ПРОМЕЖУТОЧНЫЕ ОТВЕТЫ:
- После каждого шага возвращай один JSON-объект:
  {
    "title": "тема текущего вопроса (до 120 символов)",
    "answer": "один конкретный вопрос для уточнения требований",
    "confidence": 0.0
  }
- Каждый ответ содержит только один новый вопрос. Не задавай несколько вопросов одновременно.
- Не повторяй ранее собранные факты, уточняй только пробелы.
- В промежуточном ответе всегда присылай поле `confidence`, равное 0.0.

ГЛАВНАЯ ЗАДАЧА:
- Когда данных достаточно для рекомендаций по стеку, прекращай опрос самостоятельно и переходи к финалу.
- Если пользователь просит остановиться, немедленно дай финальный ответ и объясни причину.

ФИНАЛЬНЫЙ ОТВЕТ:
- Верни один JSON-объект в кодировке UTF-8 без дополнительного текста:
  {
    "title": "краткое название итогового ТЗ (до 120 символов)",
    "answer": "подробная рекомендация по стеку: архитектура, языки, фреймворки, библиотеки, 
    инфраструктура. Объясни выбор, опираясь на собранные требования.",
    "confidence": число от 0 до 1
  }
- Не добавляй новых полей и не удаляй обязательные.
- Если данных недостаточно, в `answer` перечисли, что ещё требуется, и оцени уверенность.
- Для финального ответа устанавливай `confidence` в диапазон (0, 1], отражая свою уверенность.

ГЛАВНОЕ: в каждом ответе строго соблюдай JSON-формат, задавай вопросы по одному, а финальный ответ
давай только после выполнения главной задачи.
        """.trimIndent()
    }
}
