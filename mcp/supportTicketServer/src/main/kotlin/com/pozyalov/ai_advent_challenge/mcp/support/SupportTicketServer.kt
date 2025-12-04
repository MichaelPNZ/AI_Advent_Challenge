package com.pozyalov.ai_advent_challenge.mcp.support

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID

private const val TOOL_CREATE = "support_ticket_create"
private const val TOOL_GET = "support_ticket_get"
private const val TOOL_UPDATE = "support_ticket_update"
private const val TOOL_LIST = "support_ticket_list"
private const val TOOL_ADD_COMMENT = "support_ticket_add_comment"
private const val TOOL_STATS = "support_ticket_stats"

fun main() = runSupportTicketServer()

fun runSupportTicketServer() = runBlocking {
    val storage = SupportTicketStore(defaultStorageFile())

    val server = Server(
        Implementation(name = "support-ticket", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    ) {
        registerCreateTicketTool(storage)
        registerGetTicketTool(storage)
        registerUpdateTicketTool(storage)
        registerListTicketsTool(storage)
        registerAddCommentTool(storage)
        registerStatsTool(storage)
    }

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}

private fun defaultStorageFile(): File {
    val home = System.getenv("HOME").orEmpty().ifBlank { System.getProperty("user.home", ".") }
    val directory = File(home, ".ai_advent")
    if (!directory.exists()) directory.mkdirs()
    return File(directory, "support_tickets.json")
}

private fun Server.registerCreateTicketTool(storage: SupportTicketStore) {
    addTool(
        name = TOOL_CREATE,
        title = "Создать тикет поддержки",
        description = "Создаёт новый тикет поддержки с описанием проблемы пользователя.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("userId") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Идентификатор пользователя"))
                }
                putJsonObject("title") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Краткое описание проблемы"))
                }
                putJsonObject("description") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Подробное описание проблемы"))
                }
                putJsonObject("category") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Категория: auth, payment, bug, feature, other"))
                }
                putJsonObject("priority") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Приоритет: low, normal, high, critical"))
                }
            },
            required = listOf("userId", "title", "description")
        )
    ) { request ->
        val userId = request.arguments["userId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'userId' обязательно.")),
                isError = true
            )
        val title = request.arguments["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'title' обязательно.")),
                isError = true
            )
        val description = request.arguments["description"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'description' обязательно.")),
                isError = true
            )
        val category = request.arguments["category"]?.jsonPrimitive?.contentOrNull ?: "other"
        val priority = request.arguments["priority"]?.jsonPrimitive?.contentOrNull ?: "normal"

        val ticket = storage.createTicket(
            userId = userId,
            title = title,
            description = description,
            category = category,
            priority = priority
        )
        CallToolResult(
            content = listOf(TextContent("Создан тикет #${ticket.id}: ${ticket.title}\nСтатус: ${ticket.status}")),
            structuredContent = ticket.toJson()
        )
    }
}

private fun Server.registerGetTicketTool(storage: SupportTicketStore) {
    addTool(
        name = TOOL_GET,
        title = "Получить тикет",
        description = "Возвращает детальную информацию о тикете по ID.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("ticketId") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Идентификатор тикета"))
                }
            },
            required = listOf("ticketId")
        )
    ) { request ->
        val ticketId = request.arguments["ticketId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'ticketId' обязательно.")),
                isError = true
            )
        val ticket = storage.getTicket(ticketId)
        if (ticket == null) {
            CallToolResult(
                content = listOf(TextContent("Тикет #$ticketId не найден.")),
                isError = true
            )
        } else {
            val text = buildString {
                appendLine("Тикет #${ticket.id}")
                appendLine("Пользователь: ${ticket.userId}")
                appendLine("Заголовок: ${ticket.title}")
                appendLine("Категория: ${ticket.category}")
                appendLine("Приоритет: ${ticket.priority}")
                appendLine("Статус: ${ticket.status}")
                appendLine()
                appendLine("Описание:")
                appendLine(ticket.description)
                if (ticket.comments.isNotEmpty()) {
                    appendLine()
                    appendLine("Комментарии (${ticket.comments.size}):")
                    ticket.comments.forEach { comment ->
                        appendLine("- [${comment.author}] ${comment.text}")
                    }
                }
            }
            CallToolResult(
                content = listOf(TextContent(text.trim())),
                structuredContent = ticket.toJson()
            )
        }
    }
}

private fun Server.registerUpdateTicketTool(storage: SupportTicketStore) {
    addTool(
        name = TOOL_UPDATE,
        title = "Обновить статус тикета",
        description = "Изменяет статус тикета (open, in_progress, resolved, closed).",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("ticketId") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Идентификатор тикета"))
                }
                putJsonObject("status") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Новый статус: open, in_progress, resolved, closed"))
                }
            },
            required = listOf("ticketId", "status")
        )
    ) { request ->
        val ticketId = request.arguments["ticketId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'ticketId' обязательно.")),
                isError = true
            )
        val status = request.arguments["status"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'status' обязательно.")),
                isError = true
            )
        val updated = storage.updateTicketStatus(ticketId, status)
        if (updated == null) {
            CallToolResult(
                content = listOf(TextContent("Тикет #$ticketId не найден.")),
                isError = true
            )
        } else {
            CallToolResult(
                content = listOf(TextContent("Статус тикета #$ticketId изменён на '${updated.status}'.")),
                structuredContent = updated.toJson()
            )
        }
    }
}

private fun Server.registerListTicketsTool(storage: SupportTicketStore) {
    addTool(
        name = TOOL_LIST,
        title = "Список тикетов",
        description = "Возвращает список тикетов с фильтрацией по пользователю, статусу или категории.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("userId") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Фильтр по пользователю (опционально)"))
                }
                putJsonObject("status") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Фильтр по статусу (опционально)"))
                }
                putJsonObject("category") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Фильтр по категории (опционально)"))
                }
                putJsonObject("limit") {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive("Максимум результатов (1..50)"))
                }
            },
            required = emptyList()
        )
    ) { request ->
        val userId = request.arguments["userId"]?.jsonPrimitive?.contentOrNull
        val status = request.arguments["status"]?.jsonPrimitive?.contentOrNull
        val category = request.arguments["category"]?.jsonPrimitive?.contentOrNull
        val limit = request.arguments["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 50) ?: 20

        val tickets = storage.listTickets(userId, status, category, limit)

        val text = if (tickets.isEmpty()) {
            "Тикеты не найдены."
        } else {
            buildString {
                appendLine("Найдено тикетов: ${tickets.size}")
                tickets.forEach { ticket ->
                    appendLine("• #${ticket.id} [${ticket.status}] ${ticket.title} (${ticket.category}, ${ticket.userId})")
                }
            }
        }

        CallToolResult(
            content = listOf(TextContent(text.trim())),
            structuredContent = buildJsonObject {
                putJsonArray("tickets") {
                    tickets.forEach { add(it.toJson()) }
                }
            }
        )
    }
}

private fun Server.registerAddCommentTool(storage: SupportTicketStore) {
    addTool(
        name = TOOL_ADD_COMMENT,
        title = "Добавить комментарий",
        description = "Добавляет комментарий к существующему тикету.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("ticketId") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Идентификатор тикета"))
                }
                putJsonObject("author") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Автор комментария (support, user, system)"))
                }
                putJsonObject("text") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Текст комментария"))
                }
            },
            required = listOf("ticketId", "author", "text")
        )
    ) { request ->
        val ticketId = request.arguments["ticketId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'ticketId' обязательно.")),
                isError = true
            )
        val author = request.arguments["author"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'author' обязательно.")),
                isError = true
            )
        val text = request.arguments["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'text' обязательно.")),
                isError = true
            )

        val updated = storage.addComment(ticketId, author, text)
        if (updated == null) {
            CallToolResult(
                content = listOf(TextContent("Тикет #$ticketId не найден.")),
                isError = true
            )
        } else {
            CallToolResult(
                content = listOf(TextContent("Комментарий добавлен к тикету #$ticketId."))
            )
        }
    }
}

private fun Server.registerStatsTool(storage: SupportTicketStore) {
    addTool(
        name = TOOL_STATS,
        title = "Статистика тикетов",
        description = "Возвращает общую статистику по тикетам поддержки.",
        inputSchema = Tool.Input(properties = buildJsonObject { }, required = emptyList())
    ) { _ ->
        val stats = storage.getStats()
        val text = buildString {
            appendLine("Статистика тикетов поддержки:")
            appendLine("Всего: ${stats.total}")
            appendLine("Открытых: ${stats.open}")
            appendLine("В работе: ${stats.inProgress}")
            appendLine("Решённых: ${stats.resolved}")
            appendLine("Закрытых: ${stats.closed}")
            appendLine()
            appendLine("По категориям:")
            stats.byCategory.forEach { (category, count) ->
                appendLine("  $category: $count")
            }
        }
        CallToolResult(
            content = listOf(TextContent(text.trim())),
            structuredContent = stats.toJson()
        )
    }
}

@Serializable
private data class SupportTicket(
    val id: String,
    val userId: String,
    val title: String,
    val description: String,
    val category: String,
    val priority: String,
    val status: String,
    val comments: List<TicketComment> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("userId", JsonPrimitive(userId))
        put("title", JsonPrimitive(title))
        put("description", JsonPrimitive(description))
        put("category", JsonPrimitive(category))
        put("priority", JsonPrimitive(priority))
        put("status", JsonPrimitive(status))
        putJsonArray("comments") {
            comments.forEach { add(it.toJson()) }
        }
        put("createdAt", JsonPrimitive(createdAt))
        put("updatedAt", JsonPrimitive(updatedAt))
    }
}

@Serializable
private data class TicketComment(
    val author: String,
    val text: String,
    val timestamp: Long
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("author", JsonPrimitive(author))
        put("text", JsonPrimitive(text))
        put("timestamp", JsonPrimitive(timestamp))
    }
}

private data class TicketStats(
    val total: Int,
    val open: Int,
    val inProgress: Int,
    val resolved: Int,
    val closed: Int,
    val byCategory: Map<String, Int>
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("total", JsonPrimitive(total))
        put("open", JsonPrimitive(open))
        put("inProgress", JsonPrimitive(inProgress))
        put("resolved", JsonPrimitive(resolved))
        put("closed", JsonPrimitive(closed))
        putJsonObject("byCategory") {
            byCategory.forEach { (cat, count) ->
                put(cat, JsonPrimitive(count))
            }
        }
    }
}

private class SupportTicketStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var tickets: List<SupportTicket> = load()

    private fun load(): List<SupportTicket> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<SupportTicket>>(file.readText()) }
            .getOrElse { emptyList() }
    }

    private suspend fun persist() {
        file.writeText(json.encodeToString(tickets))
    }

    suspend fun createTicket(
        userId: String,
        title: String,
        description: String,
        category: String,
        priority: String
    ): SupportTicket = mutex.withLock {
        val now = System.currentTimeMillis()
        val ticket = SupportTicket(
            id = UUID.randomUUID().toString().take(8),
            userId = userId,
            title = title,
            description = description,
            category = category,
            priority = priority,
            status = "open",
            comments = emptyList(),
            createdAt = now,
            updatedAt = now
        )
        tickets = tickets + ticket
        persist()
        ticket
    }

    suspend fun getTicket(ticketId: String): SupportTicket? = mutex.withLock {
        tickets.firstOrNull { it.id == ticketId }
    }

    suspend fun updateTicketStatus(ticketId: String, status: String): SupportTicket? = mutex.withLock {
        val ticket = tickets.firstOrNull { it.id == ticketId } ?: return@withLock null
        val updated = ticket.copy(status = status, updatedAt = System.currentTimeMillis())
        tickets = tickets.map { if (it.id == ticketId) updated else it }
        persist()
        updated
    }

    suspend fun addComment(ticketId: String, author: String, text: String): SupportTicket? = mutex.withLock {
        val ticket = tickets.firstOrNull { it.id == ticketId } ?: return@withLock null
        val comment = TicketComment(author = author, text = text, timestamp = System.currentTimeMillis())
        val updated = ticket.copy(
            comments = ticket.comments + comment,
            updatedAt = System.currentTimeMillis()
        )
        tickets = tickets.map { if (it.id == ticketId) updated else it }
        persist()
        updated
    }

    suspend fun listTickets(
        userId: String?,
        status: String?,
        category: String?,
        limit: Int
    ): List<SupportTicket> = mutex.withLock {
        tickets
            .filter { userId == null || it.userId == userId }
            .filter { status == null || it.status.equals(status, ignoreCase = true) }
            .filter { category == null || it.category.equals(category, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
            .take(limit)
    }

    suspend fun getStats(): TicketStats = mutex.withLock {
        TicketStats(
            total = tickets.size,
            open = tickets.count { it.status == "open" },
            inProgress = tickets.count { it.status == "in_progress" },
            resolved = tickets.count { it.status == "resolved" },
            closed = tickets.count { it.status == "closed" },
            byCategory = tickets.groupingBy { it.category }.eachCount()
        )
    }
}
