@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.data

import com.pozyalov.ai_advent_challenge.chat.component.ConversationMessage
import com.pozyalov.ai_advent_challenge.core.database.chat.dao.ChatMessageDao
import com.pozyalov.ai_advent_challenge.chat.data.local.toDomain
import com.pozyalov.ai_advent_challenge.chat.data.local.toEntity
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

    override fun observeHistory(threadId: Long): Flow<List<ConversationMessage>> =
        kotlinx.coroutines.flow.flow {
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
        }
    }

    override suspend fun clear(threadId: Long) {
        mutex.withLock { messages.removeAll { it.threadId == threadId } }
    }
}

class RoomChatHistoryDataSource(
    private val dao: ChatMessageDao
) : ChatHistoryDataSource {

    override fun observeHistory(threadId: Long): Flow<List<ConversationMessage>> =
        dao.observeMessages(threadId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveMessage(message: ConversationMessage) {
        dao.upsert(message.toEntity())
    }

    override suspend fun saveMessages(messages: List<ConversationMessage>) {
        dao.upsert(messages.map { it.toEntity() })
    }

    override suspend fun clear(threadId: Long) {
        dao.clear(threadId)
    }
}
