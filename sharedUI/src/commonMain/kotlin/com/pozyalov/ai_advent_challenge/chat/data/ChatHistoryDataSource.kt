@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.data

import com.pozyalov.ai_advent_challenge.chat.ConversationMessage
import com.pozyalov.ai_advent_challenge.chat.data.local.ChatMessageDao
import com.pozyalov.ai_advent_challenge.chat.data.local.toDomain
import com.pozyalov.ai_advent_challenge.chat.data.local.toEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ExperimentalTime

interface ChatHistoryDataSource {
    suspend fun loadHistory(): List<ConversationMessage>
    suspend fun saveMessage(message: ConversationMessage)
    suspend fun saveMessages(messages: List<ConversationMessage>) {
        messages.forEach { saveMessage(it) }
    }
    suspend fun clear()
}

class InMemoryChatHistoryDataSource : ChatHistoryDataSource {
    private val mutex = Mutex()
    private val messages = mutableListOf<ConversationMessage>()

    override suspend fun loadHistory(): List<ConversationMessage> = mutex.withLock {
        messages.sortedBy { it.timestamp }
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

    override suspend fun clear() {
        mutex.withLock { messages.clear() }
    }
}

class RoomChatHistoryDataSource(
    private val dao: ChatMessageDao
) : ChatHistoryDataSource {

    override suspend fun loadHistory(): List<ConversationMessage> =
        dao.getMessages().map { it.toDomain() }

    override suspend fun saveMessage(message: ConversationMessage) {
        dao.upsert(message.toEntity())
    }

    override suspend fun saveMessages(messages: List<ConversationMessage>) {
        dao.upsert(messages.map { it.toEntity() })
    }

    override suspend fun clear() {
        dao.clear()
    }
}
