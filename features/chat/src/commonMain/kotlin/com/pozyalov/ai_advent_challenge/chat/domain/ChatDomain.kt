package com.pozyalov.ai_advent_challenge.chat.domain

import com.aallam.openai.api.model.ModelId

data class AgentStructuredResponse(
    val title: String,
    val summary: String,
    val confidence: Double
)

enum class ChatRole {
    System,
    User,
    Assistant
}

data class ChatMessage(
    val role: ChatRole,
    val content: String
)

interface ChatRepository {
    val isConfigured: Boolean
    suspend fun generateReply(
        history: List<ChatMessage>,
        model: ModelId,
        temperature: Double
    ): Result<AgentStructuredResponse>
    fun close()
}

class GenerateChatReplyUseCase(
    private val repository: ChatRepository
) {
    val isConfigured: Boolean get() = repository.isConfigured

    suspend operator fun invoke(
        history: List<ChatMessage>,
        model: ModelId,
        temperature: Double
    ): Result<AgentStructuredResponse> = repository.generateReply(history, model, temperature)

    fun close() = repository.close()
}
