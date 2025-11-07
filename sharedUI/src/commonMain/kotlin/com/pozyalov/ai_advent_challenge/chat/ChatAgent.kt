@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat

import com.aallam.openai.api.model.ModelId
import com.pozyalov.ai_advent_challenge.appLog
import com.pozyalov.ai_advent_challenge.chat.domain.AgentStructuredResponse
import com.pozyalov.ai_advent_challenge.chat.domain.ChatMessage
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRole
import com.pozyalov.ai_advent_challenge.chat.domain.GenerateChatReplyUseCase
import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.random.Random
import kotlin.time.ExperimentalTime

enum class MessageAuthor {
    User,
    Agent
}

enum class ConversationError {
    MissingApiKey,
    Failure
}

data class ConversationMessage(
    val id: Long = Random.nextLong(),
    val author: MessageAuthor,
    val text: String,
    val structured: AgentStructuredResponse? = null,
    val error: ConversationError? = null,
    val timestamp: Instant = Clock.System.now(),
    val modelId: String? = null
) {
    val isError: Boolean get() = error != null
}

class ChatAgent(
    private val generateReply: GenerateChatReplyUseCase
) {
    val isConfigured: Boolean get() = generateReply.isConfigured

    suspend fun reply(
        history: List<ConversationMessage>,
        model: ModelId,
        temperature: Double
    ): Result<AgentStructuredResponse> {
        val domainHistory = history
            .filterNot { it.error != null && it.author == MessageAuthor.Agent }
            .map { it.toDomainMessage() }

        val lastUserMessagePreview = history
            .lastOrNull { it.author == MessageAuthor.User }
            ?.text
            ?.take(120)

        appLog(
            "Sending request with ${domainHistory.size} messages via model ${model.id} (temperature=$temperature). Last user message preview: ${
                lastUserMessagePreview.orEmpty()
            }"
        )

        return generateReply(domainHistory, model, temperature)
            .onSuccess { appLog("Parsed structured response: $it") }
            .onFailure { failure ->
                appLog("Failed to get structured response: ${failure.message.orEmpty()}")
            }
    }

    fun close() {
        generateReply.close()
    }

    private fun ConversationMessage.toDomainMessage(): ChatMessage {
        val role = when (author) {
            MessageAuthor.User -> ChatRole.User
            MessageAuthor.Agent -> ChatRole.Assistant
        }
        return ChatMessage(role = role, content = text)
    }
}
