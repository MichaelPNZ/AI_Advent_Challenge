@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.mcp.chatsummary

import com.pozyalov.ai_advent_challenge.core.database.chat.dao.ChatMessageDao
import com.pozyalov.ai_advent_challenge.core.database.chat.dao.ChatThreadDao
import com.pozyalov.ai_advent_challenge.core.database.chat.db.ChatDatabase
import com.pozyalov.ai_advent_challenge.core.database.chat.model.ChatMessageEntity
import com.pozyalov.ai_advent_challenge.core.database.chat.model.ChatThreadEntity
import com.pozyalov.ai_advent_challenge.core.database.factory.createChatDatabase
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
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
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.lang.System.getProperty
import java.lang.System.getenv
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val TOOL_FETCH_DAILY = "chat_daily_summaries"

fun main() = runChatSummaryServer()

fun runChatSummaryServer() = runBlocking {
    val database = openChatDatabase()
    val summaryStore = DailySummaryStore(defaultSummaryFile())
    val scheduler = DailySummaryScheduler(
        threadDao = database.chatThreadDao(),
        messageDao = database.chatMessageDao(),
        summaryStore = summaryStore
    )
    val schedulerJob = launch(Dispatchers.Default + SupervisorJob()) { scheduler.run() }

    val server = Server(
        Implementation(name = "chat-daily-summary", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    ) {
        registerFetchTool(summaryStore, scheduler)
    }

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
    schedulerJob.cancelAndJoin()
}

private fun openChatDatabase(): ChatDatabase {
    val dbPath = getProperty("ai.advent.chat.db.path")
        ?: getenv("AI_ADVENT_CHAT_DB_PATH")
        ?: defaultChatDbPath()
    return createChatDatabase(filePath = dbPath, fallbackToDestructiveMigration = false)
}

private fun defaultChatDbPath(): String {
    val home = getenv("HOME").orEmpty().ifBlank { getProperty("user.home") ?: "." }
    val directory = File(home, ".ai_advent")
    if (!directory.exists()) directory.mkdirs()
    return File(directory, "chat_history.db").absolutePath
}

private fun defaultSummaryFile(): File {
    val home = getenv("HOME").orEmpty().ifBlank { getProperty("user.home") ?: "." }
    val directory = File(home, ".ai_advent")
    if (!directory.exists()) directory.mkdirs()
    return File(directory, "chat_daily_summaries.json")
}

private fun Server.registerFetchTool(summaryStore: DailySummaryStore, scheduler: DailySummaryScheduler) {
    addTool(
        name = TOOL_FETCH_DAILY,
        title = "Получить дневные сводки по чатам",
        description = "Возвращает подготовленные на сервере сводки за текущий день для каждого чата.",
        inputSchema = Tool.Input(properties = buildJsonObject { }, required = emptyList())
    ) { _ ->
        var summaries = summaryStore.drain()
        if (summaries.isEmpty()) {
            scheduler.produceNow()
            summaries = summaryStore.drain()
        }
        if (summaries.isEmpty()) {
            CallToolResult(content = listOf(TextContent("Новых сводок нет.")))
        } else {
            val text = buildString {
                appendLine("Свежие сводки (${summaries.size}):")
                summaries.forEach { appendLine("• ${it.threadTitle}") }
            }.trim()
            CallToolResult(
                content = listOf(TextContent(text)),
                structuredContent = buildJsonObject {
                    putJsonArray("summaries") {
                        summaries.forEach { add(it.toJson()) }
                    }
                }
            )
        }
    }
}

private class DailySummaryScheduler(
    private val threadDao: ChatThreadDao,
    private val messageDao: ChatMessageDao,
    private val summaryStore: DailySummaryStore,
    private val timezone: TimeZone = TimeZone.of("Europe/Moscow"),
    private val targetHour: Int = summaryHourPreference(),
    private val periodicIntervalMinutes: Long? = summaryIntervalMinutes(),
    private val testDelayMinutes: Long? = getProperty("ai.advent.chat.summary.testDelayMinutes")?.toLongOrNull()
        ?: getenv("AI_ADVENT_CHAT_SUMMARY_TEST_DELAY_MINUTES")?.toLongOrNull()
) {
    private val snippetLimit = 180

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val waitDuration = nextWaitDuration()
            delay(waitDuration)
            try {
                produceSummaries()
            } catch (error: Throwable) {
                println("[ChatSummaryServer] Failed to build summaries: ${error.message}")
            }
        }
    }

    suspend fun produceNow() {
        try {
            produceSummaries()
        } catch (error: Throwable) {
            println("[ChatSummaryServer] Failed to produce summaries on demand: ${error.message}")
        }
    }

    private fun nextWaitDuration(): Duration {
        periodicIntervalMinutes?.let { return it.coerceAtLeast(1).minutes }
        testDelayMinutes?.let { return it.coerceAtLeast(1).minutes }
        val now = Clock.System.now()
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

    private suspend fun produceSummaries() {
        val threads = runCatching { threadDao.listThreads() }.getOrElse {
            println("[ChatSummaryServer] Failed to load threads: ${it.message}")
            return
        }
        if (threads.isEmpty()) return

        val now = Clock.System.now()
        val today = now.toLocalDateTime(timezone).date
        val dayStart = today.atStartOfDayIn(timezone)
        val nextDayStart = today.plus(DatePeriod(days = 1)).atStartOfDayIn(timezone)
        val startMillis = dayStart.toEpochMilliseconds()
        val endMillis = nextDayStart.toEpochMilliseconds()

        threads.forEach { thread ->
            val entities = runCatching { messageDao.getMessages(thread.id) }.getOrElse { error ->
                println("[ChatSummaryServer] Failed to read messages for ${thread.title}: ${error.message}")
                return@forEach
            }
            val dailyMessages = entities.filter {
                it.timestampEpochMillis in startMillis until endMillis &&
                    it.isSummary.not() &&
                    it.isThinking.not()
            }
            if (dailyMessages.isEmpty()) {
                return@forEach
            }
            val summary = buildSummary(thread, dailyMessages, today)
            summaryStore.enqueue(summary)
        }
    }

    private fun buildSummary(
        thread: ChatThreadEntity,
        messages: List<ChatMessageEntity>,
        summaryDate: LocalDate
    ): DailySummary {
        val highlights = messages.takeLast(5).map { it.formatMessageSnippet() }
        val text = buildString {
            appendLine("Сводка дня для «${thread.title}»")
            appendLine("Сообщений за сутки: ${messages.size}")
            appendLine()
            appendLine("Основные моменты:")
            highlights.forEach { appendLine("• $it") }
        }.trim()

        val structured = buildJsonObject {
            put("threadId", JsonPrimitive(thread.id))
            put("threadTitle", JsonPrimitive(thread.title))
            put("messageCount", JsonPrimitive(messages.size))
            put("date", JsonPrimitive(summaryDate.toString()))
            putJsonArray("highlights") { highlights.forEach { add(JsonPrimitive(it)) } }
        }
        return DailySummary(
            threadId = thread.id,
            threadTitle = thread.title,
            text = text,
            structured = structured,
            createdAt = Clock.System.now().toEpochMilliseconds()
        )
    }

    private fun ChatMessageEntity.formatMessageSnippet(): String {
        val prefix = when (author) {
            "User" -> "Пользователь"
            "Agent" -> "Ассистент"
            else -> author.ifBlank { "Система" }
        }
        val normalized = text
            .replace("\n", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
        val limited = if (normalized.length > snippetLimit) {
            normalized.substring(0, snippetLimit.coerceAtMost(normalized.length)) + "…"
        } else {
            normalized
        }
        return "$prefix: ${limited.ifBlank { "(без текста)" }}"
    }
}

private fun summaryHourPreference(): Int =
    getProperty("ai.advent.chat.summary.hour")?.toIntOrNull()?.coerceIn(0, 23)
        ?: getenv("AI_ADVENT_CHAT_SUMMARY_HOUR")?.toIntOrNull()?.coerceIn(0, 23)
        ?: 20

private fun summaryIntervalMinutes(): Long? =
    getProperty("ai.advent.chat.summary.interval.minutes")?.toLongOrNull()
        ?: getenv("AI_ADVENT_CHAT_SUMMARY_INTERVAL_MINUTES")?.toLongOrNull()

@Serializable
private data class DailySummary(
    val threadId: Long,
    val threadTitle: String,
    val text: String,
    val structured: JsonObject,
    val createdAt: Long
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("threadId", JsonPrimitive(threadId))
        put("threadTitle", JsonPrimitive(threadTitle))
        put("text", JsonPrimitive(text))
        put("createdAt", JsonPrimitive(createdAt))
        put("structured", structured)
    }
}

private class DailySummaryStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var summaries: List<DailySummary> = load()

    private fun load(): List<DailySummary> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<DailySummary>>(file.readText()) }
            .getOrElse { emptyList() }
    }

    private suspend fun persist() {
        file.writeText(json.encodeToString(summaries))
    }

    suspend fun enqueue(summary: DailySummary) = mutex.withLock {
        summaries = summaries + summary
        persist()
    }

    suspend fun drain(): List<DailySummary> = mutex.withLock {
        val current = summaries
        if (current.isNotEmpty()) {
            summaries = emptyList()
            persist()
        }
        current
    }
}
