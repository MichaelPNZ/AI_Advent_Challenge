package com.pozyalov.ai_advent_challenge.chat.domain

import com.aallam.openai.api.model.ModelId

data class AgentStructuredResponse(
    val title: String,
    val summary: String,
    val confidence: Double
)

data class AgentResponseMetrics(
    val modelId: String,
    val durationMillis: Long,
    val promptTokens: Long?,
    val completionTokens: Long?,
    val totalTokens: Long?,
    val costUsd: Double?
)

data class AgentReply(
    val structured: AgentStructuredResponse,
    val metrics: AgentResponseMetrics
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
        temperature: Double,
        systemPrompt: String,
        reasoningEffort: String
    ): Result<AgentReply>
    fun close()
}

class GenerateChatReplyUseCase(
    private val repository: ChatRepository
) {
    val isConfigured: Boolean get() = repository.isConfigured

    suspend operator fun invoke(
        history: List<ChatMessage>,
        model: ModelId,
        temperature: Double,
        systemPrompt: String,
        reasoningEffort: String
    ): Result<AgentReply> =
        repository.generateReply(history, model, temperature, systemPrompt, reasoningEffort)

    fun close() = repository.close()
}
