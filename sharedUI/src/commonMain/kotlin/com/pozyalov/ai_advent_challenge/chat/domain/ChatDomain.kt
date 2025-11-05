package com.pozyalov.ai_advent_challenge.chat.domain

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
    suspend fun generateReply(history: List<ChatMessage>): Result<AgentStructuredResponse>
    fun close()
}

class GenerateChatReplyUseCase(
    private val repository: ChatRepository
) {
    val isConfigured: Boolean get() = repository.isConfigured

    suspend operator fun invoke(history: List<ChatMessage>): Result<AgentStructuredResponse> =
        repository.generateReply(history)

    fun close() = repository.close()
}
