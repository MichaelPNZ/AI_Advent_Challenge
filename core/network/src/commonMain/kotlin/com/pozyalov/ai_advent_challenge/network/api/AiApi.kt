package com.pozyalov.ai_advent_challenge.network.api

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatResponseFormat
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.Effort
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.exception.OpenAIAPIException
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.ProxyConfig
import com.aallam.openai.client.RetryStrategy
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class AiApi(
    apiKey: String,
    private val defaultModel: ModelId = ModelId("gpt-5-mini"),
    private val systemPrompt: String? = null,
    private val proxy: ProxyConfig? = null,
) {
    private val openAiClient = apiKey.takeIf { it.isNotBlank() }?.let {
        OpenAI(
            token = it,
            logging = LoggingConfig(
                logLevel = LogLevel.Body
            ),
            timeout = Timeout(
                socket = 60.seconds,
                request = 90.seconds,
                connect = 30.seconds
            ),
            proxy = proxy,
            retry = RetryStrategy(0),
        )
    }

    val isConfigured: Boolean get() = openAiClient != null

    suspend fun chatCompletion(
        messages: List<AiMessage>,
        model: ModelId? = null,
        temperature: Double? = null,
        reasoningEffort: String? = null,
    ): Result<AiCompletionResult> {
        val client = openAiClient
            ?: return Result.failure(IllegalStateException("OpenAI API key is missing"))
        val targetModel = model ?: defaultModel
        val targetTemperature = temperature?.coerceIn(0.0, 2.0) ?: DEFAULT_TEMPERATURE

        val sanitized = messages.filter { it.text.isNotBlank() }
        val requestMessages = buildList {
            if (!systemPrompt.isNullOrBlank() && sanitized.none { it.role == AiRole.System }) {
                add(ChatMessage(role = ChatRole.System, content = systemPrompt))
            }
            sanitized.forEach { add(it.toChatMessage()) }
        }

        return runCatching {
            val effort = reasoningEffort?.let(::Effort)
            val timer = TimeSource.Monotonic.markNow()
            val completion = client.chatCompletion(
                ChatCompletionRequest(
                    model = targetModel,
                    messages = requestMessages,
                    reasoningEffort = when {
                        !targetModel.supportsReasoningEffort() -> null
                        effort != null -> effort
                        else -> DEFAULT_REASONING_EFFORT
                    },
                    temperature = targetTemperature,
                    responseFormat = ChatResponseFormat.JsonObject,
                )
            )
            val elapsed = timer.elapsedNow().inWholeMilliseconds

            val choice = completion.choices.firstOrNull()
                ?: error("Model returned no choices")

            val content = choice.message.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: error(
                "Model did not return a response (finish reason: ${choice.finishReason?.value ?: "unknown"})"
            )

            AiCompletionResult(
                content = content,
                modelId = completion.model.id,
                durationMillis = elapsed,
                usage = completion.usage?.toAiUsage()
            )
        }.recoverCatching { throwable ->
            when {
                throwable is OpenAIAPIException && throwable.statusCode == 429 -> {
                    println("[AiApi] throwable is OpenAIAPIException && throwable.statusCode == 429")
                    val detail = throwable.error.detail
                    throw RateLimitExceededException(
                        code = detail?.code,
                        type = detail?.type,
                        message = detail?.message ?: "Запрос превышает лимиты (HTTP 429)",
                        cause = throwable
                    )
                }

                throwable.isTimeoutError() -> throw IllegalStateException("Время ожидания ответа от OpenAI истекло. Попробуйте повторить запрос позже.")
                else -> throw throwable
            }
        }
    }

    fun close() {
        openAiClient?.close()
    }

    companion object {
        val DEFAULT_REASONING_EFFORT: Effort = Effort("low")
        const val DEFAULT_TEMPERATURE: Double = 0.8
    }

    private fun ModelId.supportsReasoningEffort(): Boolean {
        val lowercaseId = id.lowercase()
        return !lowercaseId.startsWith("gpt-4")
    }

    private fun Throwable?.isTimeoutError(): Boolean {
        if (this == null) return false
        return when (this) {
            is HttpRequestTimeoutException -> true
            is TimeoutCancellationException -> true
            else -> {
                val nameHasTimeout = this::class.simpleName
                    ?.contains("timeout", ignoreCase = true) == true
                val next = cause
                nameHasTimeout || (next != null && next !== this && next.isTimeoutError())
            }
        }
    }

    private fun Throwable?.isContextLengthError(): Boolean {
        if (this == null) return false
        val message = message?.lowercase()?.trim() ?: ""
        if (message.contains("maximum context length")) return true
        if (message.contains("context_length_exceeded")) return true
        val next = cause
        return next != null && next !== this && next.isContextLengthError()
    }
}

enum class AiRole {
    System,
    User,
    Assistant
}

data class AiMessage(
    val role: AiRole,
    val text: String,
)

private fun AiMessage.toChatMessage(): ChatMessage {
    val role = when (role) {
        AiRole.System -> ChatRole.System
        AiRole.User -> ChatRole.User
        AiRole.Assistant -> ChatRole.Assistant
    }
    return ChatMessage(role = role, content = text)
}

data class AiCompletionResult(
    val content: String,
    val modelId: String,
    val durationMillis: Long,
    val usage: AiCompletionUsage?,
)

data class AiCompletionUsage(
    val promptTokens: Long?,
    val completionTokens: Long?,
    val totalTokens: Long?,
)

private fun Usage.toAiUsage(): AiCompletionUsage =
    AiCompletionUsage(
        promptTokens = promptTokens?.toLong(),
        completionTokens = completionTokens?.toLong(),
        totalTokens = totalTokens?.toLong()
    )

class ContextLengthExceededException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class RateLimitExceededException(
    val code: String?,
    val type: String?,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)