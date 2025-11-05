package com.pozyalov.ai_advent_challenge.network

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI

class AiApi(
    apiKey: String,
    private val model: ModelId = ModelId("gpt-4o-mini"),
    private val systemPrompt: String? = null
) {
    private val openAiClient = apiKey.takeIf { it.isNotBlank() }?.let { OpenAI(token = it) }

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
                    maxCompletionTokens = 1024,
                    temperature = 0.2
                )
            )

            completion.choices
                .firstOrNull()
                ?.message
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error("Model did not return a response")
        }
    }

    fun close() {
        openAiClient?.close()
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
