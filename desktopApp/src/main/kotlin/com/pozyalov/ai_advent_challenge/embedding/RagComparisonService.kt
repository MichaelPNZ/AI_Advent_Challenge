package com.pozyalov.ai_advent_challenge.embedding

import com.aallam.openai.api.model.ModelId
import com.pozyalov.ai_advent_challenge.chat.domain.ChatMessage
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRole
import com.pozyalov.ai_advent_challenge.chat.domain.GenerateChatReplyUseCase
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelCatalog
import com.pozyalov.ai_advent_challenge.chat.pipeline.RagComparisonResult

class RagComparisonService(
    private val indexService: EmbeddingIndexService,
    private val generateReply: GenerateChatReplyUseCase
) {
    suspend fun compare(question: String, topK: Int = 3, minScore: Double? = null): RagComparisonResult {
        val model = LlmModelCatalog.models.first()
        val modelId = ModelId(model.id)

        // Без RAG
        val noRagHistory = listOf(ChatMessage(role = ChatRole.User, content = question))
        val noRagAnswer = generateReply(
            history = noRagHistory,
            model = modelId,
            temperature = model.temperature,
            systemPrompt = "Ты помощник, отвечай кратко и по существу.",
            reasoningEffort = "medium"
        ).getOrElse { throw it }.structured.summary.ifBlank { "Ответ недоступен" }

        // С RAG
        val search = indexService.search(question, topK = topK)
        val chunks = search.getOrElse { emptyList() }.take(topK)
        val contextText = chunks.joinToString("\n\n") { chunk ->
            "Источник: ${chunk.file}\n${chunk.text}"
        }
        val ragPrompt = buildString {
            appendLine("Используй предоставленный контекст для ответа.")
            appendLine("Если в контексте нет ответа, скажи, что не нашёл в документах.")
            appendLine()
            appendLine("Контекст:")
            appendLine(contextText)
            appendLine()
            appendLine("Вопрос: $question")
        }
        val ragHistory = listOf(ChatMessage(role = ChatRole.User, content = ragPrompt))
        val ragAnswer = generateReply(
            history = ragHistory,
            model = modelId,
            temperature = model.temperature,
            systemPrompt = "Ты ассистент, отвечай по контексту. Если информации недостаточно — скажи об этом.",
            reasoningEffort = "medium"
        ).getOrElse { throw it }.structured.summary.ifBlank { "Ответ недоступен" }

        val threshold = minScore ?: FILTER_THRESHOLD
        val scored = indexService.searchScored(question, topK = topK, minScore = threshold).getOrElse { emptyList() }
        val filteredContext = scored.map { RagComparisonResult.ContextChunk(file = it.record.file, text = it.record.text) }
        val filteredText = filteredContext.joinToString("\n\n") { chunk ->
            "Источник: ${chunk.file}\n${chunk.text}"
        }
        val ragFilteredPrompt = buildString {
            appendLine("Используй предоставленный контекст для ответа.")
            appendLine("Если в контексте нет ответа, скажи, что не нашёл в документах.")
            appendLine()
            appendLine("Контекст:")
            appendLine(filteredText)
            appendLine()
            appendLine("Вопрос: $question")
        }
        val ragFilteredHistory = listOf(ChatMessage(role = ChatRole.User, content = ragFilteredPrompt))
        val ragFilteredAnswer = generateReply(
            history = ragFilteredHistory,
            model = modelId,
            temperature = model.temperature,
            systemPrompt = "Ты ассистент, отвечай по контексту. Если информации недостаточно — скажи об этом.",
            reasoningEffort = "medium"
        ).getOrElse { throw it }.structured.summary.ifBlank { "Ответ недоступен" }

        val context = chunks.map { RagComparisonResult.ContextChunk(file = it.file, text = it.text) }
        return RagComparisonResult(
            withoutRag = noRagAnswer,
            withRag = ragAnswer,
            contextChunks = context,
            withRagFiltered = ragFilteredAnswer,
            filteredChunks = filteredContext
        )
    }

    private companion object {
        const val FILTER_THRESHOLD = 0.25
    }
}
