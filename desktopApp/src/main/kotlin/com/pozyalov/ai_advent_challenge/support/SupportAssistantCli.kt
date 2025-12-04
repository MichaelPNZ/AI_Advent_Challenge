package com.pozyalov.ai_advent_challenge.support

import com.aallam.openai.api.model.ModelId
import com.pozyalov.ai_advent_challenge.chat.data.ChatRepositoryImpl
import com.pozyalov.ai_advent_challenge.chat.domain.ChatMessage
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRole
import com.pozyalov.ai_advent_challenge.chat.domain.GenerateChatReplyUseCase
import com.pozyalov.ai_advent_challenge.chat.model.ChatRoleCatalog
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelCatalog
import com.pozyalov.ai_advent_challenge.embedding.EmbeddingIndexService
import com.pozyalov.ai_advent_challenge.embedding.OllamaEmbeddingClient
import com.pozyalov.ai_advent_challenge.network.api.AiApi
import com.pozyalov.ai_advent_challenge.network.mcp.SupportTicketStore
import com.pozyalov.ai_advent_challenge.network.mcp.SupportTicketTaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.SupportUserStore
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

private data class SupportAssistantConfig(
    val question: String,
    val userId: String?,
    val contextPath: File,
    val modelId: String,
    val topK: Int,
    val minScore: Double
)

fun main(args: Array<String>) = runBlocking {
    val question = (System.getProperty("question") ?: args.joinToString(" ")).trim()
    if (question.isBlank()) {
        println("Передайте вопрос через -Pquestion=\"...\" или аргументами командной строки.")
        exitProcess(1)
    }
    val userId = System.getProperty("userId")?.takeIf { it.isNotBlank() }
        ?: System.getenv("SUPPORT_USER_ID")?.takeIf { it.isNotBlank() }
    val contextPath = System.getProperty("contextPath")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: File("docs/support-faq")
    val modelId = System.getProperty("model") ?: LlmModelCatalog.DefaultModelId
    val topK = System.getProperty("topK")?.toIntOrNull()?.coerceIn(1, 8) ?: 4
    val minScore = System.getProperty("minScore")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.25

    val config = SupportAssistantConfig(
        question = question,
        userId = userId,
        contextPath = contextPath,
        modelId = modelId,
        topK = topK,
        minScore = minScore
    )

    SupportAssistantRunner().run(config)
}

private class SupportAssistantRunner {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun run(config: SupportAssistantConfig) {
        val apiKey = System.getenv("OPENAI_API_KEY").orEmpty()
        if (apiKey.isBlank()) {
            println("OPENAI_API_KEY не задан. Экспортируйте ключ и повторите.")
            exitProcess(1)
        }

        val ticketStore = SupportTicketStore()
        val userStore = SupportUserStore()

        val toolClient = SupportTicketTaskToolClient(
            storageFile = SupportTicketStore.defaultFile(),
            usersFile = SupportUserStore.defaultFile()
        )
        val repository = ChatRepositoryImpl(
            api = AiApi(apiKey = apiKey),
            toolClient = toolClient
        )
        val useCase = GenerateChatReplyUseCase(repository)

        val rolePrompt = ChatRoleCatalog.roles.firstOrNull { it.id == "support" }?.systemPrompt
            ?: "Ты ассистент техподдержки."
        val modelOption = LlmModelCatalog.firstOrDefault(config.modelId)
        val model = ModelId(modelOption.id)

        val contextBlock = buildFaqContext(config)
        val userBlock = buildUserContext(config.userId, userStore, ticketStore)

        val prompt = buildString {
            appendLine("Ответь на вопрос пользователя, используя контекст ниже.")
            appendLine("Если информации из контекста не хватает, скажи об этом и предложи создать тикет.")
            appendLine("Когда нужны данные по тикетам, используй MCP инструменты support_ticket_* и support_user_*.")
            appendLine()
            if (userBlock.isNotBlank()) {
                appendLine("Контекст пользователя:")
                appendLine(userBlock)
                appendLine()
            }
            if (contextBlock.isNotBlank()) {
                appendLine("Контекст FAQ и документации:")
                appendLine(contextBlock)
                appendLine()
            } else {
                appendLine("Контекст FAQ и документации: недоступен, сообщи об этом, если данных недостаточно.")
                appendLine()
            }
            appendLine("Вопрос: ${config.question}")
        }

        val history = listOf(ChatMessage(role = ChatRole.User, content = prompt))
        val reply = useCase(
            history = history,
            model = model,
            temperature = modelOption.temperature,
            systemPrompt = rolePrompt,
            reasoningEffort = "medium"
        ).getOrElse { error ->
            println("Не удалось получить ответ: ${error.message}")
            repository.close()
            exitProcess(1)
        }

        repository.close()

        println("==== Поддержка ====")
        println("Заголовок: ${reply.structured.title}")
        println("Ответ: ${reply.structured.summary}")
        println("Уверенность: ${"%.2f".format(reply.structured.confidence)}")
        println("Модель: ${reply.metrics.modelId}, время: ${reply.metrics.durationMillis} мс")
        reply.metrics.costUsd?.let { println("Оценка стоимости: $it USD") }
    }

    private suspend fun buildFaqContext(config: SupportAssistantConfig): String {
        val path = config.contextPath
        if (!path.exists()) {
            println("Контекст FAQ не найден: ${path.absolutePath}. Продолжим без RAG.")
            return ""
        }

        val indexDir = File(File(System.getProperty("user.home"), ".ai_advent"), "support_index")
        val indexFile = File(indexDir, "index.jsonl")
        val embeddingClient = runCatching { OllamaEmbeddingClient() }.getOrElse {
            println("RAG недоступен: ${it.message ?: it::class.simpleName}")
            return ""
        }
        val indexService = EmbeddingIndexService(
            client = embeddingClient,
            indexDir = indexDir,
            json = json
        )
        if (!indexFile.exists()) {
            println("Строим индекс FAQ из ${path.absolutePath} ...")
            indexService.buildIndex(path).onFailure { error ->
                println("Не удалось построить индекс FAQ: ${error.message}")
                return ""
            }
        }

        val results = indexService.searchScored(
            query = config.question,
            topK = config.topK,
            minScore = config.minScore
        ).getOrElse { emptyList() }

        return results.joinToString(separator = "\n\n") { scored ->
            val fileName = File(scored.record.file).name
            "[Источник: $fileName] ${scored.record.text}"
        }
    }

    private suspend fun buildUserContext(
        userId: String?,
        userStore: SupportUserStore,
        ticketStore: SupportTicketStore
    ): String {
        if (userId.isNullOrBlank()) return ""
        val user = userStore.getUser(userId)
        val tickets = ticketStore.previewUserTickets(userId, limit = 3)
        return buildString {
            appendLine("userId=$userId")
            user?.let {
                appendLine("Имя: ${it.name}")
                it.plan?.let { plan -> appendLine("Тариф: $plan") }
                it.company?.let { company -> appendLine("Компания: $company") }
                it.segment?.let { segment -> appendLine("Сегмент: $segment") }
                it.notes?.let { notes -> appendLine("Заметки: $notes") }
            }
            if (tickets.isNotEmpty()) {
                appendLine("Последние тикеты:")
                tickets.forEach { ticket ->
                    appendLine("- #${ticket.id} [${ticket.status}] ${ticket.title} (${ticket.category})")
                }
            } else {
                appendLine("Нет известных тикетов для этого пользователя.")
            }
        }.trim()
    }
}
