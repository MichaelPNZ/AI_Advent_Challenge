package com.pozyalov.ai_advent_challenge.review

import com.aallam.openai.api.model.ModelId
import com.pozyalov.ai_advent_challenge.di.desktopAppModule
import com.pozyalov.ai_advent_challenge.di.initKoin
import com.pozyalov.ai_advent_challenge.initLogs
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.system.exitProcess

/**
 * CLI runner для автоматического ревью Pull Request.
 *
 * Использование:
 * ```bash
 * ./gradlew :desktopApp:runPrReview -Pbase=origin/main -PuseRag=true
 * ```
 *
 * Параметры:
 * - base: базовая ветка для сравнения (по умолчанию origin/main)
 * - useRag: использовать ли RAG для контекста (по умолчанию true)
 * - model: ID модели (по умолчанию claude-opus-4-20250514)
 * - minScore: минимальный порог релевантности для RAG (по умолчанию 0.25)
 * - outputFormat: формат вывода (text|markdown|json, по умолчанию markdown)
 */
fun main(args: Array<String>) {
    initLogs()
    initKoin(appModule = desktopAppModule())

    val runner = PrReviewRunnerImpl()
    val exitCode = runBlocking {
        runner.run(args)
    }
    exitProcess(exitCode)
}

class PrReviewRunnerImpl : KoinComponent {
    private val reviewService: PrReviewService by inject()

    suspend fun run(args: Array<String>): Int {
        val config = parseArgs(args)

        println("🔍 AI PR Review")
        println("=" .repeat(50))
        println("Base: ${config.base}")
        println("Model: ${config.modelId}")
        println("RAG: ${if (config.useRag) "enabled (minScore=${config.minScore})" else "disabled"}")
        println("=" .repeat(50))
        println()

        return try {
            val result = if (config.useRag) {
                reviewService.reviewPullRequest(
                    base = config.base,
                    modelId = ModelId(config.modelId),
                    temperature = 0.3,
                    topK = 5,
                    minScore = config.minScore
                )
            } else {
                reviewService.quickReview(
                    base = config.base,
                    modelId = ModelId(config.modelId),
                    temperature = 0.3
                )
            }

            result.fold(
                onSuccess = { review ->
                    when (config.outputFormat) {
                        OutputFormat.TEXT -> printTextOutput(review)
                        OutputFormat.MARKDOWN -> printMarkdownOutput(review)
                        OutputFormat.JSON -> printJsonOutput(review)
                    }
                    0 // success
                },
                onFailure = { error ->
                    System.err.println("❌ Ошибка при ревью: ${error.message}")
                    error.printStackTrace()
                    1 // failure
                }
            )
        } catch (e: Exception) {
            System.err.println("❌ Критическая ошибка: ${e.message}")
            e.printStackTrace()
            1 // failure
        }
    }

    private fun parseArgs(args: Array<String>): ReviewConfig {
        var base = System.getProperty("base") ?: "origin/main"
        var useRag = System.getProperty("useRag")?.toBoolean() ?: true
        var modelId = System.getProperty("model") ?: "gpt-4o"
        var minScore = System.getProperty("minScore")?.toDoubleOrNull() ?: 0.25
        var outputFormat = OutputFormat.fromString(System.getProperty("outputFormat") ?: "markdown")

        // Также поддерживаем переменные окружения
        System.getenv("PR_REVIEW_BASE")?.let { base = it }
        System.getenv("PR_REVIEW_USE_RAG")?.let { useRag = it.toBoolean() }
        System.getenv("PR_REVIEW_MODEL")?.let { modelId = it }
        System.getenv("PR_REVIEW_MIN_SCORE")?.let { minScore = it.toDoubleOrNull() ?: 0.25 }
        System.getenv("PR_REVIEW_OUTPUT_FORMAT")?.let { outputFormat = OutputFormat.fromString(it) }

        return ReviewConfig(base, useRag, modelId, minScore, outputFormat)
    }

    private fun printTextOutput(review: PrReviewResult) {
        println("📝 Результаты ревью:")
        println()
        println(review.summary)
        println()
        println("-" .repeat(50))
        println("📊 Метрики:")
        println("  Модель: ${review.metrics.modelId}")
        println("  Время: ${review.metrics.durationMillis}ms")
        println("  Токены: ${review.metrics.tokensUsed}")
        if (review.metrics.chunksUsed > 0) {
            println("  RAG чанки: ${review.metrics.chunksUsed}")
        }
        println("  Уверенность: ${(review.confidence * 100).toInt()}%")
    }

    private fun printMarkdownOutput(review: PrReviewResult) {
        println("# 🤖 AI Code Review")
        println()
        println("## 📝 Результаты анализа")
        println()
        println(review.summary)
        println()

        if (review.changedFiles.isNotEmpty()) {
            println("## 📁 Изменённые файлы")
            println()
            review.changedFiles.forEach { file ->
                println("- `$file`")
            }
            println()
        }

        if (review.contextFiles.isNotEmpty()) {
            println("## 📚 Использованный контекст (RAG)")
            println()
            review.contextFiles.forEach { file ->
                println("- `$file`")
            }
            println()
        }

        println("## 📊 Метрики")
        println()
        println("| Параметр | Значение |")
        println("|----------|----------|")
        println("| Модель | ${review.metrics.modelId} |")
        println("| Время выполнения | ${review.metrics.durationMillis}ms |")
        println("| Токены | ${review.metrics.tokensUsed} |")
        if (review.metrics.chunksUsed > 0) {
            println("| RAG чанки | ${review.metrics.chunksUsed} |")
        }
        println("| Уверенность | ${(review.confidence * 100).toInt()}% |")
        println()
        println("---")
        println()
        println("*Сгенерировано AI PR Review*")
    }

    private fun printJsonOutput(review: PrReviewResult) {
        // Простой JSON вывод (можно улучшить с kotlinx.serialization)
        println("""
{
  "summary": ${escapeJson(review.summary)},
  "confidence": ${review.confidence},
  "changedFiles": [${review.changedFiles.joinToString(",") { "\"$it\"" }}],
  "contextFiles": [${review.contextFiles.joinToString(",") { "\"$it\"" }}],
  "metrics": {
    "modelId": "${review.metrics.modelId}",
    "durationMillis": ${review.metrics.durationMillis},
    "tokensUsed": ${review.metrics.tokensUsed},
    "chunksUsed": ${review.metrics.chunksUsed}
  }
}
        """.trimIndent())
    }

    private fun escapeJson(text: String): String {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
    }
}

data class ReviewConfig(
    val base: String,
    val useRag: Boolean,
    val modelId: String,
    val minScore: Double,
    val outputFormat: OutputFormat
)

enum class OutputFormat {
    TEXT,
    MARKDOWN,
    JSON;

    companion object {
        fun fromString(value: String): OutputFormat {
            return when (value.lowercase()) {
                "text" -> TEXT
                "markdown", "md" -> MARKDOWN
                "json" -> JSON
                else -> MARKDOWN
            }
        }
    }
}
