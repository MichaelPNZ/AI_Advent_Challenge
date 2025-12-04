package com.pozyalov.ai_advent_challenge.network.api

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatResponseFormat
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.Effort
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolChoice
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.exception.OpenAIAPIException
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.ProxyConfig
import com.aallam.openai.client.RetryStrategy
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import kotlinx.coroutines.CancellationException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
                socket = SOCKET_TIMEOUT,
                request = REQUEST_TIMEOUT,
                connect = CONNECT_TIMEOUT
            ),
            proxy = proxy,
            retry = RetryStrategy(0),
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val isConfigured: Boolean get() = openAiClient != null

    suspend fun chatCompletion(
        messages: List<AiMessage>,
        model: ModelId? = null,
        temperature: Double? = null,
        reasoningEffort: String? = null,
        toolClient: TaskToolClient = TaskToolClient.None,
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
            val conversation = requestMessages.toMutableList()
            val effort = reasoningEffort?.let(::Effort)
            val declaredTools = toolClient.toolDefinitions.takeIf { it.isNotEmpty() }
            val usageAggregator = UsageAggregator()
            var totalDuration = 0L
            var lastModelId = targetModel.id

            var iterationCount = 0
            while (true) {
                iterationCount++
                println("[AiApi] Tool calling iteration #$iterationCount, conversation size: ${conversation.size}")
                val timer = TimeSource.Monotonic.markNow()
                val completion = client.chatCompletion(
                    ChatCompletionRequest(
                        model = targetModel,
                        messages = conversation,
                        reasoningEffort = when {
                            !targetModel.supportsReasoningEffort() -> null
                            effort != null -> effort
                            else -> DEFAULT_REASONING_EFFORT
                        },
                        temperature = targetTemperature,
                        responseFormat = ChatResponseFormat.JsonObject,
                        tools = declaredTools,
                        toolChoice = declaredTools?.let { ToolChoice.Auto },
                    )
                )
                val elapsed = timer.elapsedNow().inWholeMilliseconds
                totalDuration += elapsed
                usageAggregator.add(completion.usage?.toAiUsage())
                lastModelId = completion.model.id

                val choice = completion.choices.firstOrNull()
                    ?: error("Model returned no choices")
                val assistantMessage = choice.message
                val toolCalls = assistantMessage.toolCalls.orEmpty()

                if (declaredTools != null && toolCalls.isNotEmpty()) {
                    println("[AiApi] Model requested ${toolCalls.size} tool calls")
                    conversation += ChatMessage(
                        role = ChatRole.Assistant,
                        content = assistantMessage.content,
                        toolCalls = toolCalls,
                    )
                    val toolResponses = processToolCalls(toolCalls, toolClient)
                    println("[AiApi] Tool calls completed, got ${toolResponses.size} responses. Continuing conversation...")
                    conversation.addAll(toolResponses)
                    continue
                }

                val content = assistantMessage.content
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: error(
                        "Model did not return a response (finish reason: ${choice.finishReason?.value ?: "unknown"})"
                    )

                println("[AiApi] Received final response from model (${content.length} chars). Finishing...")
                return@runCatching AiCompletionResult(
                    content = content,
                    modelId = lastModelId,
                    durationMillis = totalDuration,
                    usage = usageAggregator.result(),
                )
            }
            error("Model did not return a final response")
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

    private suspend fun processToolCalls(
        toolCalls: List<ToolCall>,
        toolClient: TaskToolClient,
    ): List<ChatMessage> = buildList {
        toolCalls.filterIsInstance<ToolCall.Function>().forEach { call ->
            val toolName = call.function.nameOrNull ?: return@forEach
            val arguments = parseToolArguments(call)
            println("[AiApi] Executing tool: $toolName with args: ${arguments.toString().take(100)}")
            val responseText = runCatching {
                toolClient.execute(toolName, arguments)
            }.fold(
                onSuccess = { result ->
                    val response = result?.text?.takeIf { it.isNotBlank() }
                        ?: "Инструмент $toolName вернул пустой ответ."
                    println("[AiApi] Tool $toolName returned: ${response.take(200)}")
                    response
                },
                onFailure = { error ->
                    val errorMsg = "Ошибка вызова инструмента $toolName: ${error.message ?: error::class.simpleName}"
                    println("[AiApi] Tool $toolName failed: $errorMsg")
                    errorMsg
                }
            )
            add(ChatMessage.Tool(content = responseText, toolCallId = call.id))
        }
    }

    private fun parseToolArguments(call: ToolCall.Function): JsonObject {
        val rawArguments = call.function.argumentsOrNull?.takeIf { it.isNotBlank() } ?: return buildJsonObject { }
        return runCatching {
            json.parseToJsonElement(rawArguments).jsonObject
        }.getOrElse {
            buildJsonObject {
                put("rawArguments", JsonPrimitive(rawArguments))
                put("parseError", JsonPrimitive(it.message ?: it::class.simpleName.orEmpty()))
            }
        }
    }

    fun close() {
        openAiClient?.close()
    }

    companion object {
        private val SOCKET_TIMEOUT = 120.seconds
        private val REQUEST_TIMEOUT = 120.seconds
        private val CONNECT_TIMEOUT = 60.seconds
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
            is CancellationException -> message?.contains("timed out", ignoreCase = true) == true
            else -> {
                val nameHasTimeout = this::class.simpleName
                    ?.contains("timeout", ignoreCase = true) == true
                val messageHasTimeout = message
                    ?.contains("timed out", ignoreCase = true) == true
                val next = cause
                messageHasTimeout || nameHasTimeout || (next != null && next !== this && next.isTimeoutError())
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

private class UsageAggregator {
    private var promptTokens: Long? = null
    private var completionTokens: Long? = null
    private var totalTokens: Long? = null

    fun add(usage: AiCompletionUsage?) {
        promptTokens = promptTokens.merge(usage?.promptTokens)
        completionTokens = completionTokens.merge(usage?.completionTokens)
        totalTokens = totalTokens.merge(usage?.totalTokens)
    }

    fun result(): AiCompletionUsage? {
        if (promptTokens == null && completionTokens == null && totalTokens == null) return null
        return AiCompletionUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )
    }

    private fun Long?.merge(other: Long?): Long? = when {
        this == null -> other
        other == null -> this
        else -> this + other
    }
}

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
