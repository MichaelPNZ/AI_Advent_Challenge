@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.data

import com.pozyalov.ai_advent_challenge.chat.component.ConversationMessage
import com.pozyalov.ai_advent_challenge.chat.component.MessageAuthor
import com.pozyalov.ai_advent_challenge.chat.data.local.toDomain
import com.pozyalov.ai_advent_challenge.chat.data.local.toEntity
import com.pozyalov.ai_advent_challenge.core.database.chat.dao.ChatMessageDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal object HistoryCompressionDefaults {
    const val CHUNK_SIZE = 10
    const val MAX_SNIPPET_CHARS = 180
    private const val LOCAL_MODEL = "local:history-summary"
    private const val LOCAL_ROLE = "history-summary"

    val whitespaceRegex = Regex("\\s+")

    fun summaryMetadata(): Pair<String, String> = LOCAL_MODEL to LOCAL_ROLE
}

internal class ChatHistorySummarizer(
    private val snippetLimit: Int = HistoryCompressionDefaults.MAX_SNIPPET_CHARS
) {
    fun buildSummary(threadId: Long, messages: List<ConversationMessage>): ConversationMessage {
        require(messages.isNotEmpty()) { "Cannot summarize empty history" }
        val (modelId, roleId) = HistoryCompressionDefaults.summaryMetadata()
        val header = "Сводка ${messages.size} сообщений"
        val body = messages.joinToString(separator = "\n") { message ->
            val authorLabel = when (message.author) {
                MessageAuthor.User -> "Пользователь"
                MessageAuthor.Agent -> "Ассистент"
            }
            val normalized = message.text
                .replace('\n', ' ')
                .replace(HistoryCompressionDefaults.whitespaceRegex, " ")
                .trim()
                .take(snippetLimit)
                .ifBlank { "(нет текста)" }
            "• $authorLabel: $normalized"
        }
        val summaryText = buildString {
            append(header)
            append(':')
            append('\n')
            append(body)
        }
        val summaryTimestamp = Clock.System.now()
        return ConversationMessage(
            threadId = threadId,
            author = MessageAuthor.Agent,
            text = summaryText,
            isSummary = true,
            timestamp = summaryTimestamp,
            modelId = modelId,
            roleId = roleId
        )
    }
}

internal class ChatHistoryCompressor(
    private val dao: ChatMessageDao,
    private val summarizer: ChatHistorySummarizer = ChatHistorySummarizer(),
    private val chunkSize: Int = HistoryCompressionDefaults.CHUNK_SIZE
) {
    private val mutex = Mutex()

    suspend fun compact(threadId: Long) {
        mutex.withLock {
            while (true) {
                val batch = dao.getOldestNonSummary(threadId = threadId, limit = chunkSize)
                if (batch.size < chunkSize) {
                    break
                }
                val summary = summarizer.buildSummary(
                    threadId = threadId,
                    messages = batch.map { it.toDomain() }
                )
                val ids = batch.map { it.id }
                if (ids.isNotEmpty()) {
                    dao.markArchived(ids)
                }
                dao.upsert(summary.toEntity())
            }
        }
    }
}
