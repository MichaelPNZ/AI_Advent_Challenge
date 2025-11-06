package com.pozyalov.ai_advent_challenge.network

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ChatResponseFormat
import com.aallam.openai.api.chat.Effort
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.ProxyConfig

class AiApi(
    apiKey: String,
    private val model: ModelId = ModelId("gpt-5-mini"),
    private val systemPrompt: String? = null,
    private val proxy: ProxyConfig? = null,
) {
    private val openAiClient = apiKey.takeIf { it.isNotBlank() }?.let {
        OpenAI(
            token = it,
            logging = LoggingConfig(
                logLevel = LogLevel.Body
            ),
            proxy = proxy
        )
    }

    val isConfigured: Boolean get() = openAiClient != null

    suspend fun chatCompletion(messages: List<AiMessage>): Result<String> {
        val client = openAiClient ?: return Result.failure(IllegalStateException("OpenAI API key is missing"))

        val sanitized = messages.filter { it.text.isNotBlank() }
        val requestMessages = buildList {
            if (!systemPrompt.isNullOrBlank() && sanitized.none { it.role == AiRole.System }) {
                add(ChatMessage(role = ChatRole.System, content = systemPrompt))
            }
            sanitized.forEach { add(it.toChatMessage()) }
        }

        return runCatching {
            val completion = client.chatCompletion(
                ChatCompletionRequest(
                    model = model,
                    messages = requestMessages,
                    reasoningEffort = DEFAULT_REASONING_EFFORT,
                    temperature = 1.0,
                    maxCompletionTokens = 2048,
                    responseFormat = ChatResponseFormat.JsonObject,
                )
            )

            val choice = completion.choices.firstOrNull()
                ?: error("Model returned no choices")

            val content = choice.message.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            content ?: error(
                "Model did not return a response (finish reason: ${choice.finishReason?.value ?: "unknown"})"
            )
        }
    }

    fun close() {
        openAiClient?.close()
    }

    private companion object {
        val DEFAULT_REASONING_EFFORT: Effort = Effort("low")
    }
}

enum class AiRole {
    System,
    User,
    Assistant
}

data class AiMessage(
    val role: AiRole,
    val text: String
)

private fun AiMessage.toChatMessage(): ChatMessage {
    val role = when (role) {
        AiRole.System -> ChatRole.System
        AiRole.User -> ChatRole.User
        AiRole.Assistant -> ChatRole.Assistant
    }
    return ChatMessage(role = role, content = text)
}
