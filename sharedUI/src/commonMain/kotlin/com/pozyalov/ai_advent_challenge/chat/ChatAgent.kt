package com.pozyalov.ai_advent_challenge.chat

import com.pozyalov.ai_advent_challenge.network.AiApi
import com.pozyalov.ai_advent_challenge.network.AiMessage
import com.pozyalov.ai_advent_challenge.network.AiRole
import kotlin.random.Random

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
    val error: ConversationError? = null
) {
    val isError: Boolean get() = error != null
}

class ChatAgent(
    private val api: AiApi
) {
    val isConfigured: Boolean get() = api.isConfigured

    suspend fun reply(history: List<ConversationMessage>): Result<String> {
        val requestHistory = history
            .filterNot { it.error != null && it.author == MessageAuthor.Agent }
            .map { it.toAiMessage() }

        return api.chatCompletion(requestHistory)
    }

    fun close() {
        api.close()
    }

    private fun ConversationMessage.toAiMessage(): AiMessage {
        val role = when (author) {
            MessageAuthor.User -> AiRole.User
            MessageAuthor.Agent -> AiRole.Assistant
        }
        return AiMessage(role = role, text = text)
    }
}
