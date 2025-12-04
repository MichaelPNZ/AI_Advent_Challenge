package com.pozyalov.ai_advent_challenge.network.mcp

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class LocalSupportTicketToolClient(
    private val ticketStore: SupportTicketStore,
    private val userStore: SupportUserStore
) : TaskToolClient {

    override val toolDefinitions: List<Tool> = listOf(
        Tool.function(
            name = TOOL_CREATE,
            description = "Создать тикет поддержки.",
            parameters = params(
                required = listOf("userId", "title", "description")
            ) {
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
            }
        ),
        Tool.function(
            name = TOOL_GET,
            description = "Получить детальную информацию о тикете по ID.",
            parameters = params(required = listOf("ticketId")) {
                putJsonObject("ticketId") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Идентификатор тикета"))
                }
            }
        ),
        Tool.function(
            name = TOOL_UPDATE,
            description = "Обновить статус тикета (open, in_progress, resolved, closed).",
            parameters = params(required = listOf("ticketId", "status")) {
                putJsonObject("ticketId") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Идентификатор тикета"))
                }
                putJsonObject("status") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Новый статус: open, in_progress, resolved, closed"))
                }
            }
        ),
        Tool.function(
            name = TOOL_LIST,
            description = "Список тикетов с фильтрацией по пользователю/статусу/категории.",
            parameters = params {
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
            }
        ),
        Tool.function(
            name = TOOL_ADD_COMMENT,
            description = "Добавить комментарий к тикету.",
            parameters = params(required = listOf("ticketId", "author", "text")) {
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
            }
        ),
        Tool.function(
            name = TOOL_STATS,
            description = "Статистика по тикетам.",
            parameters = params { }
        ),
        Tool.function(
            name = TOOL_USER_GET,
            description = "Детали пользователя по userId.",
            parameters = params(required = listOf("userId")) {
                putJsonObject("userId") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Идентификатор пользователя"))
                }
            }
        ),
        Tool.function(
            name = TOOL_USER_LIST,
            description = "Список известных пользователей (для контекста тикетов).",
            parameters = params {
                putJsonObject("limit") {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive("Максимум пользователей (1..50)"))
                }
            }
        )
    )

    override suspend fun execute(toolName: String, arguments: JsonObject): TaskToolResult? = when (toolName) {
        TOOL_CREATE -> handleCreate(arguments)
        TOOL_GET -> handleGet(arguments)
        TOOL_UPDATE -> handleUpdate(arguments)
        TOOL_LIST -> handleList(arguments)
        TOOL_ADD_COMMENT -> handleAddComment(arguments)
        TOOL_STATS -> handleStats()
        TOOL_USER_GET -> handleUserGet(arguments)
        TOOL_USER_LIST -> handleUserList(arguments)
        else -> TaskToolResult("Инструмент $toolName не поддерживается.")
    }

    private suspend fun handleCreate(arguments: JsonObject): TaskToolResult {
        val userId = arguments["userId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Поле 'userId' обязательно.")
        val title = arguments["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Поле 'title' обязательно.")
        val description = arguments["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Поле 'description' обязательно.")
        val category = arguments["category"]?.jsonPrimitive?.contentOrNull ?: "other"
        val priority = arguments["priority"]?.jsonPrimitive?.contentOrNull ?: "normal"

        val ticket = ticketStore.createTicket(
            userId = userId,
            title = title,
            description = description,
            category = category,
            priority = priority
        )
        val text = "Создан тикет #${ticket.id}: ${ticket.title}\nСтатус: ${ticket.status}"
        return TaskToolResult(text = text, structured = ticket.toJson())
    }

    private suspend fun handleGet(arguments: JsonObject): TaskToolResult {
        val ticketId = arguments["ticketId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Поле 'ticketId' обязательно.")
        val ticket = ticketStore.getTicket(ticketId)
            ?: return TaskToolResult("Тикет #$ticketId не найден.")

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
        }.trim()

        return TaskToolResult(text = text, structured = ticket.toJson())
    }

    private suspend fun handleUpdate(arguments: JsonObject): TaskToolResult {
        val ticketId = arguments["ticketId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Поле 'ticketId' обязательно.")
        val status = arguments["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Поле 'status' обязательно.")
        val updated = ticketStore.updateTicketStatus(ticketId, status)
            ?: return TaskToolResult("Тикет #$ticketId не найден.")
        return TaskToolResult(
            text = "Статус тикета #$ticketId изменён на '${updated.status}'.",
            structured = updated.toJson()
        )
    }

    private suspend fun handleList(arguments: JsonObject): TaskToolResult {
        val userId = arguments["userId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val status = arguments["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val category = arguments["category"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val limit = arguments["limit"]?.jsonPrimitive?.doubleOrNull
            ?.toInt()
            ?.coerceIn(1, 50)
            ?: 20

        val tickets = ticketStore.listTickets(
            userId = userId,
            status = status,
            category = category,
            limit = limit
        )
        val text = if (tickets.isEmpty()) {
            "Тикеты не найдены."
        } else {
            buildString {
                appendLine("Найдено тикетов: ${tickets.size}")
                tickets.forEach { ticket ->
                    appendLine("• #${ticket.id} [${ticket.status}] ${ticket.title} (${ticket.category}, ${ticket.userId})")
                }
            }
        }.trim()

        return TaskToolResult(
            text = text,
            structured = buildJsonObject {
                putJsonArray("tickets") {
                    tickets.forEach { add(it.toJson()) }
                }
            }
        )
    }

    private suspend fun handleAddComment(arguments: JsonObject): TaskToolResult {
        val ticketId = arguments["ticketId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Поле 'ticketId' обязательно.")
        val author = arguments["author"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Поле 'author' обязательно.")
        val text = arguments["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Поле 'text' обязательно.")

        val updated = ticketStore.addComment(ticketId, author, text)
            ?: return TaskToolResult("Тикет #$ticketId не найден.")
        return TaskToolResult(
            text = "Комментарий добавлен к тикету #$ticketId.",
            structured = updated.toJson()
        )
    }

    private suspend fun handleStats(): TaskToolResult {
        val stats = ticketStore.getStats()
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
        }.trim()
        return TaskToolResult(text = text, structured = stats.toJson())
    }

    private fun handleUserGet(arguments: JsonObject): TaskToolResult {
        val userId = arguments["userId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Поле 'userId' обязательно.")
        val user = userStore.getUser(userId)
            ?: return TaskToolResult("Пользователь $userId не найден.")
        return TaskToolResult(
            text = buildUserText(user),
            structured = user.toJson()
        )
    }

    private fun handleUserList(arguments: JsonObject): TaskToolResult {
        val limit = arguments["limit"]?.jsonPrimitive?.doubleOrNull
            ?.toInt()
            ?.coerceIn(1, 50)
            ?: 20
        val users = userStore.listUsers(limit)
        val text = if (users.isEmpty()) {
            "Пользователи не найдены."
        } else {
            buildString {
                appendLine("Пользователи (${users.size}):")
                users.forEach { user ->
                    appendLine("• ${user.id}: ${user.name} (${user.plan ?: "plan: n/a"})")
                }
            }
        }.trim()
        return TaskToolResult(
            text = text,
            structured = buildJsonObject {
                putJsonArray("users") {
                    users.forEach { add(it.toJson()) }
                }
            }
        )
    }

    private fun SupportTicket.toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("userId", JsonPrimitive(userId))
        put("title", JsonPrimitive(title))
        put("description", JsonPrimitive(description))
        put("category", JsonPrimitive(category))
        put("priority", JsonPrimitive(priority))
        put("status", JsonPrimitive(status))
        putJsonArray("comments") {
            comments.forEach { comment ->
                add(buildJsonObject {
                    put("author", JsonPrimitive(comment.author))
                    put("text", JsonPrimitive(comment.text))
                    put("timestamp", JsonPrimitive(comment.timestamp))
                })
            }
        }
        put("createdAt", JsonPrimitive(createdAt))
        put("updatedAt", JsonPrimitive(updatedAt))
    }

    private fun TicketStats.toJson(): JsonObject = buildJsonObject {
        put("total", JsonPrimitive(total))
        put("open", JsonPrimitive(open))
        put("inProgress", JsonPrimitive(inProgress))
        put("resolved", JsonPrimitive(resolved))
        put("closed", JsonPrimitive(closed))
        putJsonObject("byCategory") {
            byCategory.forEach { (category, count) ->
                put(category, JsonPrimitive(count))
            }
        }
    }

    private fun SupportUser.toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("name", JsonPrimitive(name))
        put("email", email?.let(::JsonPrimitive) ?: JsonNull)
        put("plan", plan?.let(::JsonPrimitive) ?: JsonNull)
        put("company", company?.let(::JsonPrimitive) ?: JsonNull)
        put("segment", segment?.let(::JsonPrimitive) ?: JsonNull)
        put("notes", notes?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun buildUserText(user: SupportUser): String = buildString {
        appendLine("${user.id}: ${user.name}")
        user.email?.let { appendLine("email: $it") }
        user.plan?.let { appendLine("plan: $it") }
        user.company?.let { appendLine("company: $it") }
        user.segment?.let { appendLine("segment: $it") }
        user.notes?.let { appendLine("notes: $it") }
    }.trim()

    private companion object {
        private fun params(
            required: List<String> = emptyList(),
            builder: JsonObjectBuilder.() -> Unit
        ): Parameters {
            val properties = buildJsonObject { builder() }
            val requiredArray = required
                .takeIf { it.isNotEmpty() }
                ?.let { JsonArray(it.map(::JsonPrimitive)) }
            return Parameters(buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", properties)
                requiredArray?.let { put("required", it) }
            })
        }

        private const val TOOL_CREATE = "support_ticket_create"
        private const val TOOL_GET = "support_ticket_get"
        private const val TOOL_UPDATE = "support_ticket_update"
        private const val TOOL_LIST = "support_ticket_list"
        private const val TOOL_ADD_COMMENT = "support_ticket_add_comment"
        private const val TOOL_STATS = "support_ticket_stats"
        private const val TOOL_USER_GET = "support_user_get"
        private const val TOOL_USER_LIST = "support_user_list"
    }
}
