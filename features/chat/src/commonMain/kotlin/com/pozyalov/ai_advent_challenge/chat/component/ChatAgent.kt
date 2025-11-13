@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.component

import com.aallam.openai.api.model.ModelId
import com.pozyalov.ai_advent_challenge.chat.domain.AgentReply
import com.pozyalov.ai_advent_challenge.chat.domain.AgentStructuredResponse
import com.pozyalov.ai_advent_challenge.chat.domain.ChatMessage
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRole
import com.pozyalov.ai_advent_challenge.chat.domain.GenerateChatReplyUseCase
import com.pozyalov.ai_advent_challenge.chat.util.chatLog
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
    Failure,
    ContextLimit,
    RateLimit
}

data class ConversationMessage(
    val threadId: Long,
    val id: Long = Random.nextLong(),
    val author: MessageAuthor,
    val text: String,
    val isSummary: Boolean = false,
    val isThinking: Boolean = false,
    val isArchived: Boolean = false,
    val structured: AgentStructuredResponse? = null,
    val error: ConversationError? = null,
    val timestamp: Instant = Clock.System.now(),
    val modelId: String? = null,
    val roleId: String? = null,
    val temperature: Double? = null,
    val responseTimeMillis: Long? = null,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val costUsd: Double? = null
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
        temperature: Double,
        systemPrompt: String,
        reasoningEffort: String
    ): Result<AgentReply> {
        val domainHistory = history
            .filterNot { it.error != null && it.author == MessageAuthor.Agent }
            .map { it.toDomainMessage() }

        val lastUserMessagePreview = history
            .lastOrNull { it.author == MessageAuthor.User }
            ?.text
            ?.take(120)

        chatLog(
            "Sending request with ${domainHistory.size} messages via model ${model.id} (temperature=$temperature). Last user message preview: ${
                lastUserMessagePreview.orEmpty()
            }"
        )

        return generateReply(domainHistory, model, temperature, systemPrompt, reasoningEffort)
            .onSuccess { chatLog("Parsed structured response: ${it.structured}") }
            .onFailure { failure ->
                chatLog("Failed to get structured response: ${failure.message.orEmpty()}")
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
