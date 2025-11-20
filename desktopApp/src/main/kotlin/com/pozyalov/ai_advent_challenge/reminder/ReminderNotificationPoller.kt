@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.reminder

import com.pozyalov.ai_advent_challenge.chat.component.ConversationMessage
import com.pozyalov.ai_advent_challenge.chat.component.MessageAuthor
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.core.database.chat.data.ChatThreadDataSource
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val REMINDER_THREAD_TITLE = "Reminder Bot"
private const val REMINDER_NOTIFICATION_TOOL = "reminder_next_notification"

class ReminderNotificationPoller(
    private val taskToolClient: TaskToolClient,
    private val chatHistory: ChatHistoryDataSource,
    private val chatThreads: ChatThreadDataSource,
    pollIntervalSeconds: Long = 120L
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val delayDuration = pollIntervalSeconds.coerceAtLeast(20).seconds
    private var cachedThreadId: Long? = null

    init {
        scope.launch { loop() }
    }

    suspend fun stop() {
        scope.cancel()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                if (taskToolClient.toolDefinitions.any { it.function.name == REMINDER_NOTIFICATION_TOOL }) {
                    fetchAndPost()
                }
            } catch (error: Throwable) {
                println("[ReminderPoller] Failed to fetch notification: ${error.message}")
            }
            delay(delayDuration)
        }
    }

    private suspend fun fetchAndPost() {
        val result = taskToolClient.execute(REMINDER_NOTIFICATION_TOOL, JsonObject(emptyMap())) ?: return
        val structured = result.structured ?: return
        val text = formatSummary(structured)
            ?: structured["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: result.text.takeIf { it.isNotBlank() }
            ?: return
        val receivedAt = kotlin.time.Clock.System.now()
        val formattedTime = formatTimestamp(receivedAt)
        val threadId = ensureThreadId()
        val message = ConversationMessage(
            threadId = threadId,
            author = MessageAuthor.Agent,
            text = "[Reminder]\n\n$text\n\nПолучено: $formattedTime",
            modelId = "reminder",
            timestamp = receivedAt
        )
        chatHistory.saveMessage(message)
        chatThreads.updateThread(
            threadId = threadId,
            title = REMINDER_THREAD_TITLE,
            lastMessagePreview = text.take(120)
        )
    }

    private suspend fun ensureThreadId(): Long {
        cachedThreadId?.let { return it }
        val threads = chatThreads.observeThreads().first()
        val existing = threads.firstOrNull { it.title == REMINDER_THREAD_TITLE }
        val id = existing?.id ?: chatThreads.createThread(REMINDER_THREAD_TITLE).id
        cachedThreadId = id
        return id
    }

    private fun formatSummary(structured: JsonObject): String? {
        val overdue = structured.arrayOrEmpty("overdue").mapNotNull { it.jsonObjectOrNull()?.toTaskDisplay() }
        val dueToday = structured.arrayOrEmpty("dueToday").mapNotNull { it.jsonObjectOrNull()?.toTaskDisplay() }
        val upcoming = structured.arrayOrEmpty("upcoming").mapNotNull { it.jsonObjectOrNull()?.toTaskDisplay() }
        if (overdue.isEmpty() && dueToday.isEmpty() && upcoming.isEmpty()) {
            return structured["summary"]?.jsonPrimitive?.contentOrNull
        }
        val sections = buildList {
            add(
                buildString {
                    appendLine("Сводка задач")
                    appendLine("Просрочено: ${overdue.size}")
                    appendLine("На сегодня: ${dueToday.size}")
                    appendLine("Ближайшие 7 дней: ${upcoming.size}")
                }.trim()
            )
            add(formatSection("Просрочено", overdue))
            add(formatSection("Сегодня", dueToday))
            add(formatSection("Ближайшие 7 дней", upcoming))
        }.filter { it.isNotBlank() }
        return sections.joinToString(separator = "\n\n").ifBlank { null }
    }

    private fun JsonObject.toTaskDisplay(): ReminderTaskDisplay? {
        val title = this["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val description = this["description"]?.jsonPrimitive?.contentOrNull
        val dueDate = this["dueDate"]?.jsonPrimitive?.contentOrNull
        val priority = this["priority"]?.jsonPrimitive?.contentOrNull
        return ReminderTaskDisplay(title, dueDate, description, priority)
    }

    private fun formatSection(title: String, tasks: List<ReminderTaskDisplay>): String =
        if (tasks.isEmpty()) "$title: нет задач."
        else buildString {
            appendLine(title)
            tasks.forEach { task ->
                append("• ")
                append(task.title)
                appendLine()
                task.description?.takeIf { it.isNotBlank() }?.let {
                    appendLine("  • Описание: $it")
                }
                task.dueDate?.takeIf { it.isNotBlank() }?.let {
                    appendLine("  • Срок: $it")
                }
                task.priority?.takeIf { it.isNotBlank() }?.let {
                    appendLine("  • Приоритет: $it")
                }
            }
        }.trimEnd()

    private data class ReminderTaskDisplay(
        val title: String,
        val dueDate: String?,
        val description: String?,
        val priority: String?
    )

    private fun JsonObject.arrayOrEmpty(key: String): JsonArray =
        this[key]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun formatTimestamp(timestamp: Instant): String {
        val local = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
        val date = local.date.toString()
        val time = "%02d:%02d".format(local.time.hour, local.time.minute)
        val zone = TimeZone.currentSystemDefault().id
        return "$date $time ($zone)"
    }
}
