package com.pozyalov.ai_advent_challenge.review

import com.aallam.openai.api.model.ModelId
import com.pozyalov.ai_advent_challenge.chat.domain.ChatMessage
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRole
import com.pozyalov.ai_advent_challenge.chat.domain.GenerateChatReplyUseCase
import com.pozyalov.ai_advent_challenge.embedding.EmbeddingIndexService
import com.pozyalov.ai_advent_challenge.network.mcp.GitTaskToolClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Сервис для автоматического ревью Pull Request с использованием RAG.
 *
 * Анализирует изменения в PR, находит релевантный контекст из документации и кода,
 * и генерирует структурированное ревью с замечаниями.
 */
class PrReviewService(
    private val gitClient: GitTaskToolClient,
    private val indexService: EmbeddingIndexService,
    private val generateReply: GenerateChatReplyUseCase
) {

    /**
     * Выполняет ревью PR против базовой ветки.
     *
     * @param base Базовая ветка для сравнения (по умолчанию origin/main)
     * @param modelId ID модели для генерации ревью (по умолчанию gpt-4o)
     * @param temperature Температура для генерации
     * @param topK Количество релевантных чанков для контекста
     * @param minScore Минимальный порог релевантности
     * @return Результат ревью или ошибка
     */
    suspend fun reviewPullRequest(
        base: String = "origin/main",
        modelId: ModelId = ModelId("gpt-4o"),
        temperature: Double = 0.3,
        topK: Int = 5,
        minScore: Double = 0.25
    ): Result<PrReviewResult> = runCatching {
        // 1. Получаем diff через GitTaskToolClient
        val diffArgs = buildJsonObject {
            put("base", base)
        }
        val diffResult = gitClient.execute("git_diff", diffArgs)
            ?: throw IllegalStateException("Не удалось получить diff")

        if (diffResult.text.contains("Diff пуст.")) {
            return Result.failure(IllegalStateException("Нет изменений для ревью"))
        }

        val diff = diffResult.text

        // 2. Анализируем diff и извлекаем ключевые части для поиска контекста
        val changedFiles = extractChangedFiles(diff)
        val diffSummary = summarizeDiff(diff)

        // 3. Используем RAG для поиска релевантного контекста
        val searchQuery = buildString {
            appendLine("Изменения в PR:")
            appendLine(diffSummary)
            appendLine("\nИзменённые файлы:")
            changedFiles.forEach { appendLine("- $it") }
        }

        val contextChunks = indexService.searchScored(
            query = searchQuery,
            topK = topK,
            minScore = minScore
        ).getOrElse { emptyList() }

        // 4. Формируем промпт для LLM с контекстом
        val contextText = contextChunks.joinToString("\n\n") { scored ->
            "Источник: ${scored.record.file} (релевантность: ${(scored.score * 100).toInt()}%)\n${scored.record.text}"
        }

        val reviewPrompt = buildReviewPrompt(diff, contextText, changedFiles)

        // 5. Генерируем ревью
        val history = listOf(ChatMessage(role = ChatRole.User, content = reviewPrompt))
        val reply = generateReply(
            history = history,
            model = modelId,
            temperature = temperature,
            systemPrompt = SYSTEM_PROMPT,
            reasoningEffort = "high"
        ).getOrThrow()

        // 6. Возвращаем результат
        PrReviewResult(
            summary = reply.structured.summary,
            confidence = reply.structured.confidence,
            contextFiles = contextChunks.map { it.record.file }.distinct(),
            changedFiles = changedFiles,
            metrics = PrReviewMetrics(
                modelId = reply.metrics.modelId,
                durationMillis = reply.metrics.durationMillis,
                tokensUsed = reply.metrics.totalTokens ?: 0,
                chunksUsed = contextChunks.size
            )
        )
    }

    /**
     * Выполняет быстрое ревью без использования RAG (для тестирования).
     */
    suspend fun quickReview(
        base: String = "origin/main",
        modelId: ModelId = ModelId("gpt-4o"),
        temperature: Double = 0.3
    ): Result<PrReviewResult> = runCatching {
        val diffArgs = buildJsonObject {
            put("base", base)
        }
        val diffResult = gitClient.execute("git_diff", diffArgs)
            ?: throw IllegalStateException("Не удалось получить diff")

        if (diffResult.text.contains("Diff пуст.")) {
            return Result.failure(IllegalStateException("Нет изменений для ревью"))
        }

        val diff = diffResult.text
        val changedFiles = extractChangedFiles(diff)

        val reviewPrompt = buildString {
            appendLine("Проанализируй следующий diff и предоставь код ревью:")
            appendLine()
            appendLine("Изменения:")
            appendLine("```diff")
            appendLine(diff.take(8000)) // Ограничиваем размер для контекста
            appendLine("```")
            appendLine()
            appendLine("Предоставь:")
            appendLine("1. **Краткую сводку изменений**")
            appendLine("2. **Потенциальные проблемы и баги** (если найдены)")
            appendLine("3. **Советы по улучшению** (если применимо)")
            appendLine("4. **Проверка на типичные ошибки** (null safety, error handling, security)")
            appendLine()
            appendLine("Формат: структурированный markdown с чёткими заголовками.")
        }

        val history = listOf(ChatMessage(role = ChatRole.User, content = reviewPrompt))
        val reply = generateReply(
            history = history,
            model = modelId,
            temperature = temperature,
            systemPrompt = SYSTEM_PROMPT_QUICK,
            reasoningEffort = "medium"
        ).getOrThrow()

        PrReviewResult(
            summary = reply.structured.summary,
            confidence = reply.structured.confidence,
            contextFiles = emptyList(),
            changedFiles = changedFiles,
            metrics = PrReviewMetrics(
                modelId = reply.metrics.modelId,
                durationMillis = reply.metrics.durationMillis,
                tokensUsed = reply.metrics.totalTokens ?: 0,
                chunksUsed = 0
            )
        )
    }

    private fun extractChangedFiles(diff: String): List<String> {
        val regex = """^diff --git a/(.*) b/""".toRegex(RegexOption.MULTILINE)
        return regex.findAll(diff)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    private fun summarizeDiff(diff: String): String {
        val lines = diff.lines()
        val additions = lines.count { it.startsWith("+") && !it.startsWith("+++") }
        val deletions = lines.count { it.startsWith("-") && !it.startsWith("---") }
        return "$additions добавлений, $deletions удалений"
    }

    private fun buildReviewPrompt(
        diff: String,
        contextText: String,
        changedFiles: List<String>
    ): String = buildString {
        appendLine("Ты опытный код-ревьюер. Проанализируй Pull Request с учётом контекста проекта.")
        appendLine()
        appendLine("## Контекст проекта:")
        if (contextText.isNotBlank()) {
            appendLine(contextText)
        } else {
            appendLine("(Релевантный контекст не найден в индексе)")
        }
        appendLine()
        appendLine("## Изменённые файлы:")
        changedFiles.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Diff:")
        appendLine("```diff")
        appendLine(diff.take(12000)) // Ограничиваем размер
        appendLine("```")
        appendLine()
        appendLine("## Задача:")
        appendLine("Предоставь детальное ревью, включающее:")
        appendLine("1. **Краткую сводку изменений** - что было сделано и зачем")
        appendLine("2. **Потенциальные проблемы и баги** - найденные или возможные проблемы")
        appendLine("3. **Нарушения архитектуры** - несоответствия паттернам проекта")
        appendLine("4. **Проблемы безопасности** - уязвимости или небезопасные практики")
        appendLine("5. **Советы по улучшению** - рекомендации по рефакторингу или оптимизации")
        appendLine()
        appendLine("Используй markdown форматирование и будь конкретным, указывая файлы и строки.")
        appendLine("При упоминании концепций из контекста, ссылайся на источник: [Источник: <имя файла>]")
    }

    companion object {
        private const val SYSTEM_PROMPT = """
Ты опытный код-ревьюер, специализирующийся на Kotlin Multiplatform проектах.
Твоя задача - находить потенциальные проблемы, предлагать улучшения и следить за соблюдением best practices.
Будь конструктивным и конкретным. Фокусируйся на важных вещах: баги, security, архитектура, производительность.
Игнорируй мелкие стилистические замечания, если они не влияют на качество кода.
"""

        private const val SYSTEM_PROMPT_QUICK = """
Ты опытный код-ревьюер. Анализируй код на предмет багов, проблем безопасности и архитектурных нарушений.
Будь кратким и конкретным. Фокусируйся только на важных проблемах.
"""
    }
}

/**
 * Результат ревью Pull Request.
 */
data class PrReviewResult(
    val summary: String,
    val confidence: Double,
    val contextFiles: List<String>,
    val changedFiles: List<String>,
    val metrics: PrReviewMetrics
)

/**
 * Метрики выполнения ревью.
 */
data class PrReviewMetrics(
    val modelId: String,
    val durationMillis: Long,
    val tokensUsed: Long,
    val chunksUsed: Int
)
