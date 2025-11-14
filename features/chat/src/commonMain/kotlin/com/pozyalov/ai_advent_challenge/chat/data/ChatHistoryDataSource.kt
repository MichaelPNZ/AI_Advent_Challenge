@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.data

import com.pozyalov.ai_advent_challenge.chat.component.ConversationMessage
import com.pozyalov.ai_advent_challenge.core.database.chat.dao.ChatMessageDao
import com.pozyalov.ai_advent_challenge.chat.data.local.toDomain
import com.pozyalov.ai_advent_challenge.chat.data.local.toEntity
import com.pozyalov.ai_advent_challenge.chat.data.memory.AgentMemoryStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ExperimentalTime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

interface ChatHistoryDataSource {
    fun observeHistory(threadId: Long): Flow<List<ConversationMessage>>
    suspend fun saveMessage(message: ConversationMessage)
    suspend fun saveMessages(messages: List<ConversationMessage>) {
        messages.forEach { saveMessage(it) }
    }
    suspend fun clear(threadId: Long)
}

class InMemoryChatHistoryDataSource : ChatHistoryDataSource {
    private val mutex = Mutex()
    private val messages = mutableListOf<ConversationMessage>()
    private val summarizer = ChatHistorySummarizer()

    override fun observeHistory(threadId: Long): Flow<List<ConversationMessage>> =
        flow {
            emit(mutex.withLock {
                messages.filter { it.threadId == threadId }.sortedBy { it.timestamp }
            })
        }

    override suspend fun saveMessage(message: ConversationMessage) {
        mutex.withLock {
            val index = messages.indexOfFirst { it.id == message.id }
            if (index >= 0) {
                messages[index] = message
            } else {
                messages += message
            }
            compressLocked(message.threadId)
        }
    }

    override suspend fun saveMessages(messages: List<ConversationMessage>) {
        mutex.withLock {
            messages.forEach { message ->
                val index = this.messages.indexOfFirst { it.id == message.id }
                if (index >= 0) {
                    this.messages[index] = message
                } else {
                    this.messages += message
                }
            }
            messages.map { it.threadId }.distinct().forEach { threadId ->
                compressLocked(threadId)
            }
        }
    }

    override suspend fun clear(threadId: Long) {
        mutex.withLock { messages.removeAll { it.threadId == threadId } }
    }

    private fun compressLocked(threadId: Long) {
        while (true) {
            val chunk = messages
                .filter { it.threadId == threadId && !it.isSummary && !it.isThinking && !it.isArchived }
                .sortedBy { it.timestamp }
                .take(HistoryCompressionDefaults.CHUNK_SIZE)
            if (chunk.size < HistoryCompressionDefaults.CHUNK_SIZE) {
                return
            }
            val summary = summarizer.buildSummary(threadId, chunk)
            val archivedIds = chunk.map { it.id }.toSet()
            val updated = messages.map { message ->
                if (archivedIds.contains(message.id)) message.copy(isArchived = true) else message
            }
            messages.clear()
            messages.addAll(updated)
            messages += summary
        }
    }
}

class RoomChatHistoryDataSource internal constructor(
    private val dao: ChatMessageDao,
    private val memoryStore: AgentMemoryStore? = null,
    private val compressor: ChatHistoryCompressor = ChatHistoryCompressor(
        dao = dao,
        memoryStore = memoryStore
    )
) : ChatHistoryDataSource {

    override fun observeHistory(threadId: Long): Flow<List<ConversationMessage>> =
        dao.observeMessages(threadId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveMessage(message: ConversationMessage) {
        dao.upsert(message.toEntity())
        compressor.compact(message.threadId)
    }

    override suspend fun saveMessages(messages: List<ConversationMessage>) {
        if (messages.isEmpty()) return
        dao.upsert(messages.map { it.toEntity() })
        messages.map { it.threadId }.distinct().forEach { threadId ->
            compressor.compact(threadId)
        }
    }

    override suspend fun clear(threadId: Long) {
        dao.clear(threadId)
    }
}
