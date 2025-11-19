@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.mcp.reminder

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val TOOL_ADD = "reminder_add_task"
private const val TOOL_LIST = "reminder_list_tasks"
private const val TOOL_COMPLETE = "reminder_complete_task"
private const val TOOL_SUMMARY = "reminder_summary"
private const val TOOL_FETCH_NOTIFICATION = "reminder_next_notification"

fun main() = runReminderServer()

fun runReminderServer() = runBlocking {
    val storage = ReminderTaskStore(defaultStorageFile())
    val notificationStore = ReminderNotificationStore(defaultNotificationFile())
    val notifier = ReminderNotificationScheduler(storage, notificationStore)
    val notifierJob = launch(Dispatchers.Default + SupervisorJob()) {
        notifier.run()
    }
    val server = Server(
        Implementation(name = "reminder", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    ) {
        registerAddTaskTool(storage)
        registerListTaskTool(storage)
        registerCompleteTaskTool(storage)
        registerSummaryTool(storage)
        registerNotificationTool(notificationStore)
    }

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
    notifierJob.cancelAndJoin()
}

private fun defaultStorageFile(): File {
    val home = System.getenv("HOME").orEmpty().ifBlank { System.getProperty("user.home", ".") }
    val directory = File(home, ".ai_advent")
    if (!directory.exists()) directory.mkdirs()
    return File(directory, "reminder_tasks.json")
}

private fun defaultNotificationFile(): File {
    val home = System.getenv("HOME").orEmpty().ifBlank { System.getProperty("user.home", ".") }
    val directory = File(home, ".ai_advent")
    if (!directory.exists()) directory.mkdirs()
    return File(directory, "reminder_notifications.json")
}

private fun Server.registerAddTaskTool(storage: ReminderTaskStore) {
    addTool(
        name = TOOL_ADD,
        title = "Добавить задачу",
        description = "Добавляет новую задачу в список напоминаний.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("title") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Название задачи"))
                }
                putJsonObject("description") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Опциональное описание"))
                }
                putJsonObject("dueDate") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Срок в формате YYYY-MM-DD"))
                }
                putJsonObject("priority") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Приоритет (low, normal, high)"))
                }
            },
            required = listOf("title")
        )
    ) { request ->
        val title = request.arguments["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'title' обязательно.")),
                isError = true
            )
        val description = request.arguments["description"]?.jsonPrimitive?.contentOrNull
        val dueDate = request.arguments["dueDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val priority = request.arguments["priority"]?.jsonPrimitive?.contentOrNull

        val task = storage.addTask(title = title, description = description, dueDate = dueDate, priority = priority)
        CallToolResult(
            content = listOf(TextContent("Добавлена задача '${task.title}' (id=${task.id}).")),
            structuredContent = task.toJson()
        )
    }
}

private fun Server.registerListTaskTool(storage: ReminderTaskStore) {
    addTool(
        name = TOOL_LIST,
        title = "Список задач",
        description = "Возвращает задачи с возможностью фильтрации по статусу.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("status") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Фильтр по статусу (open/done)"))
                }
                putJsonObject("limit") {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive("Максимальное количество задач (1..100)"))
                    put("minimum", JsonPrimitive(1))
                    put("maximum", JsonPrimitive(100))
                }
            },
            required = emptyList()
        )
    ) { request ->
        val status = request.arguments["status"]?.jsonPrimitive?.contentOrNull
        val limit = request.arguments["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100)
        val tasks = storage.listTasks(status).let { list -> limit?.let { list.take(it) } ?: list }

        val body = if (tasks.isEmpty()) {
            "Список задач пуст."
        } else {
            buildString {
                appendLine("Задачи (${tasks.size}):")
                tasks.forEachIndexed { index, task ->
                    appendLine("${index + 1}. ${task.title} [${task.status}]" +
                        task.dueDate?.let { " — срок до $it" }.orEmpty())
                }
            }
        }

        CallToolResult(
            content = listOf(TextContent(body.trim())),
            structuredContent = buildJsonObject {
                putJsonArray("tasks") {
                    tasks.forEach { add(it.toJson()) }
                }
            }
        )
    }
}

private fun Server.registerCompleteTaskTool(storage: ReminderTaskStore) {
    addTool(
        name = TOOL_COMPLETE,
        title = "Завершить задачу",
        description = "Помечает задачу как выполненную.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("taskId") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Идентификатор задачи"))
                }
            },
            required = listOf("taskId")
        )
    ) { request ->
        val taskId = request.arguments["taskId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Поле 'taskId' обязательно.")),
                isError = true
            )
        val result = storage.completeTask(taskId)
        if (result == null) {
            CallToolResult(
                content = listOf(TextContent("Задача $taskId не найдена.")),
                isError = true
            )
        } else {
            CallToolResult(
                content = listOf(TextContent("Задача '${result.title}' завершена."))
            )
        }
    }
}

private fun Server.registerSummaryTool(storage: ReminderTaskStore) {
    addTool(
        name = TOOL_SUMMARY,
        title = "Сводка по задачам",
        description = "Возвращает краткую сводку по активным напоминаниям.",
        inputSchema = Tool.Input(properties = buildJsonObject { }, required = emptyList())
    ) { _ ->
        val summary = storage.buildSummary()
        CallToolResult(
            content = listOf(TextContent(summary.summaryText)),
            structuredContent = summary.toJson()
        )
    }
}

private fun Server.registerNotificationTool(notificationStore: ReminderNotificationStore) {
    addTool(
        name = TOOL_FETCH_NOTIFICATION,
        title = "Получить новое уведомление",
        description = "Возвращает следующее автоматическое уведомление Reminder (если есть).",
        inputSchema = Tool.Input(properties = buildJsonObject { }, required = emptyList())
    ) { _ ->
        val next = notificationStore.popNext()
        if (next == null) {
            CallToolResult(content = listOf(TextContent("Новых уведомлений нет.")))
        } else {
            CallToolResult(
                content = listOf(TextContent(next.text)),
                structuredContent = next.toJson()
            )
        }
    }
}

private class ReminderTaskStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var tasks: List<ReminderTask> = load()

    private fun load(): List<ReminderTask> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<ReminderTask>>(file.readText()) }
            .getOrElse { emptyList() }
    }

    private suspend fun persist() {
        file.writeText(json.encodeToString(tasks))
    }

    suspend fun addTask(title: String, description: String?, dueDate: String?, priority: String?): ReminderTask = mutex.withLock {
        val task = ReminderTask(
            id = UUID.randomUUID().toString(),
            title = title,
            description = description,
            dueDate = dueDate,
            priority = priority,
            status = "open",
            createdAt = currentInstantMillis(),
            completedAt = null
        )
        tasks = tasks + task
        persist()
        task
    }

    suspend fun listTasks(status: String?): List<ReminderTask> = mutex.withLock {
        tasks.filter { status == null || it.status.equals(status, ignoreCase = true) }
            .sortedWith(compareBy({ it.status }, { it.dueDate.orEmpty() }, { it.createdAt }))
    }

    suspend fun completeTask(taskId: String): ReminderTask? = mutex.withLock {
        val task = tasks.firstOrNull { it.id == taskId } ?: return@withLock null
        val updated = task.copy(status = "done", completedAt = currentInstantMillis())
        tasks = tasks.map { if (it.id == taskId) updated else it }
        persist()
        updated
    }

    suspend fun buildSummary(): ReminderSummary = mutex.withLock {
        ReminderSummary.fromTasks(tasks)
    }
}

@Serializable
private data class ReminderTask(
    val id: String,
    val title: String,
    val description: String? = null,
    val dueDate: String? = null,
    val priority: String? = null,
    val status: String,
    val createdAt: Long,
    val completedAt: Long? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("title", JsonPrimitive(title))
        description?.let { put("description", JsonPrimitive(it)) }
        dueDate?.let { put("dueDate", JsonPrimitive(it)) }
        priority?.let { put("priority", JsonPrimitive(it)) }
        put("status", JsonPrimitive(status))
        put("createdAt", JsonPrimitive(createdAt))
        completedAt?.let { put("completedAt", JsonPrimitive(it)) }
    }
}

private data class ReminderSummary(
    val summaryText: String,
    val overdue: List<ReminderTask>,
    val dueToday: List<ReminderTask>,
    val upcoming: List<ReminderTask>
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("summary", JsonPrimitive(summaryText))
        putJsonArray("overdue") { overdue.forEach { add(it.toJson()) } }
        putJsonArray("dueToday") { dueToday.forEach { add(it.toJson()) } }
        putJsonArray("upcoming") { upcoming.forEach { add(it.toJson()) } }
    }

    companion object {
        fun fromTasks(tasks: List<ReminderTask>): ReminderSummary {
            val timezone = TimeZone.currentSystemDefault()
            val today = currentLocalDate(timezone)

            val overdue = mutableListOf<ReminderTask>()
            val dueToday = mutableListOf<ReminderTask>()
            val upcoming = mutableListOf<ReminderTask>()

            val upcomingThreshold = today.plus(DatePeriod(days = 7))
            tasks.filter { it.status == "open" }.forEach { task ->
                val due = parseDate(task.dueDate)
                if (due != null) {
                    when {
                        due < today -> overdue += task
                        due == today -> dueToday += task
                        due <= upcomingThreshold -> upcoming += task
                    }
                }
            }

            val text = buildString {
                appendLine("Сводка задач")
                appendLine("Просрочено: ${overdue.size}")
                appendLine("На сегодня: ${dueToday.size}")
                appendLine("Ближайшие 7 дней: ${upcoming.size}")
                if (overdue.isNotEmpty()) {
                    appendLine()
                    appendLine("Просроченные:")
                    overdue.take(3).forEach { appendLine("• ${it.title} (до ${it.dueDate})") }
                }
                if (dueToday.isNotEmpty()) {
                    appendLine()
                    appendLine("Сегодня:")
                    dueToday.take(3).forEach { appendLine("• ${it.title}") }
                }
                if (upcoming.isNotEmpty()) {
                    appendLine()
                    appendLine("Скоро:")
                    upcoming.take(3).forEach { appendLine("• ${it.title} (до ${it.dueDate})") }
                }
            }.trim()

            return ReminderSummary(text.ifBlank { "Активных задач со сроками нет." }, overdue, dueToday, upcoming)
        }

        private fun parseDate(value: String?): LocalDate? = runCatching { value?.let { LocalDate.parse(it) } }.getOrNull()
    }
}

private fun currentInstantMillis(): Long = System.currentTimeMillis()

private fun currentLocalDate(timeZone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(System.currentTimeMillis())
        .toLocalDateTime(timeZone)
        .date

private class ReminderNotificationStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var notifications: List<ReminderNotification> = load()

    private fun load(): List<ReminderNotification> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<ReminderNotification>>(file.readText()) }
            .getOrElse { emptyList() }
    }

    private suspend fun persist() {
        file.writeText(json.encodeToString(notifications))
    }

    suspend fun enqueue(summary: ReminderSummary): ReminderNotification = mutex.withLock {
        val notification = ReminderNotification(
            id = UUID.randomUUID().toString(),
            text = summary.summaryText,
            structured = summary.toJson(),
            createdAt = currentInstantMillis()
        )
        notifications = notifications + notification
        persist()
        notification
    }

    suspend fun popNext(): ReminderNotification? = mutex.withLock {
        val next = notifications.firstOrNull() ?: return@withLock null
        notifications = notifications.drop(1)
        persist()
        next
    }
}

@Serializable
private data class ReminderNotification(
    val id: String,
    val text: String,
    val structured: JsonObject,
    val createdAt: Long
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("text", JsonPrimitive(text))
        put("createdAt", JsonPrimitive(createdAt))
        put("structured", structured)
    }
}

private class ReminderNotificationScheduler(
    private val storage: ReminderTaskStore,
    private val notificationStore: ReminderNotificationStore,
    private val timezone: TimeZone = TimeZone.of("Europe/Moscow"),
    private val targetHour: Int = reminderSummaryHour(),
    private val periodicIntervalMinutes: Long? = reminderIntervalMinutes(),
    private val testDelayMinutes: Long? = reminderTestDelayMinutes()
) {
    suspend fun run() {
        while (coroutineContext.isActive) {
            val waitDuration = nextWaitDuration()
            delay(waitDuration)
            try {
                val summary = storage.buildSummary()
                notificationStore.enqueue(summary)
            } catch (error: Throwable) {
                println("[ReminderServer] Failed to enqueue summary: ${error.message}")
            }
        }
    }

    private fun nextWaitDuration(): Duration {
        periodicIntervalMinutes?.let { return it.coerceAtLeast(1).minutes }
        testDelayMinutes?.let { return it.coerceAtLeast(1).minutes }
        val now = kotlin.time.Clock.System.now()
        val localDateTime = now.toLocalDateTime(timezone)
        val todayTarget = localDateTime.date.atTime(targetHour, 0)
        var targetInstant = todayTarget.toInstant(timezone)
        if (targetInstant <= now) {
            val tomorrow = localDateTime.date.plus(DatePeriod(days = 1))
            targetInstant = tomorrow.atTime(targetHour, 0).toInstant(timezone)
        }
        val diffMillis = (targetInstant.toEpochMilliseconds() - now.toEpochMilliseconds()).coerceAtLeast(0)
        return diffMillis.milliseconds
    }
}

private fun reminderIntervalMinutes(): Long? =
    System.getProperty("ai.advent.reminder.interval.minutes")?.toLongOrNull()
        ?: System.getenv("AI_ADVENT_REMINDER_INTERVAL_MINUTES")?.toLongOrNull()

private fun reminderSummaryHour(): Int =
    System.getProperty("ai.advent.reminder.summary.hour")?.toIntOrNull()?.coerceIn(0, 23)
        ?: System.getenv("AI_ADVENT_REMINDER_SUMMARY_HOUR")?.toIntOrNull()?.coerceIn(0, 23)
        ?: 20

private fun reminderTestDelayMinutes(): Long? =
    System.getProperty("ai.advent.reminder.summary.testDelayMinutes")?.toLongOrNull()
        ?: System.getenv("AI_ADVENT_REMINDER_SUMMARY_TEST_DELAY_MINUTES")?.toLongOrNull()
