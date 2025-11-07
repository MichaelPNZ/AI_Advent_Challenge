@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.core.database.chat.data

import com.pozyalov.ai_advent_challenge.core.database.chat.dao.ChatThreadDao
import com.pozyalov.ai_advent_challenge.core.database.chat.model.ChatThreadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class StoredChatThread(
    val id: Long,
    val title: String,
    val lastMessagePreview: String?,
    val updatedAt: Instant
)

interface ChatThreadDataSource {
    fun observeThreads(): Flow<List<StoredChatThread>>
    suspend fun getThread(threadId: Long): StoredChatThread?
    suspend fun createThread(title: String = "Новый чат"): StoredChatThread
    suspend fun updateThread(
        threadId: Long,
        title: String? = null,
        lastMessagePreview: String? = null,
        updatedAt: Instant = Clock.System.now()
    )
    suspend fun deleteThread(threadId: Long)
}

class InMemoryChatThreadDataSource : ChatThreadDataSource {
    private val mutex = Mutex()
    private val threads = mutableMapOf<Long, StoredChatThread>()
    private val state = MutableStateFlow<List<StoredChatThread>>(emptyList())

    override fun observeThreads(): Flow<List<StoredChatThread>> = state.asStateFlow()

    override suspend fun getThread(threadId: Long): StoredChatThread? = mutex.withLock {
        threads[threadId]
    }

    override suspend fun createThread(title: String): StoredChatThread = mutex.withLock {
        val thread = StoredChatThread(
            id = Random.nextLong(),
            title = title,
            lastMessagePreview = null,
            updatedAt = Clock.System.now()
        )
        threads[thread.id] = thread
        state.value = threads.values.sortedByDescending { it.updatedAt }
        thread
    }

    override suspend fun updateThread(
        threadId: Long,
        title: String?,
        lastMessagePreview: String?,
        updatedAt: Instant
    ) {
        mutex.withLock {
            val current = threads[threadId] ?: StoredChatThread(
                id = threadId,
                title = title ?: "Новый чат",
                lastMessagePreview = null,
                updatedAt = updatedAt
            )
            threads[threadId] = current.copy(
                title = title ?: current.title,
                lastMessagePreview = lastMessagePreview ?: current.lastMessagePreview,
                updatedAt = updatedAt
            )
            state.value = threads.values.sortedByDescending { it.updatedAt }
        }
    }

    override suspend fun deleteThread(threadId: Long) {
        mutex.withLock {
            threads.remove(threadId)
            state.value = threads.values.sortedByDescending { it.updatedAt }
        }
    }
}

class RoomChatThreadDataSource(
    private val dao: ChatThreadDao
) : ChatThreadDataSource {

    override fun observeThreads(): Flow<List<StoredChatThread>> =
        dao.observeThreads().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getThread(threadId: Long): StoredChatThread? =
        dao.getThread(threadId)?.toDomain()

    override suspend fun createThread(title: String): StoredChatThread {
        val thread = StoredChatThread(
            id = Random.nextLong(),
            title = title,
            lastMessagePreview = null,
            updatedAt = Clock.System.now()
        )
        dao.upsert(thread.toEntity())
        return thread
    }

    override suspend fun updateThread(
        threadId: Long,
        title: String?,
        lastMessagePreview: String?,
        updatedAt: Instant
    ) {
        val current = dao.getThread(threadId) ?: StoredChatThread(
            id = threadId,
            title = title ?: "Новый чат",
            lastMessagePreview = null,
            updatedAt = updatedAt
        ).toEntity()
        val next = current.copy(
            title = title ?: current.title,
            lastMessagePreview = lastMessagePreview ?: current.lastMessagePreview,
            updatedAtEpochMillis = updatedAt.toEpochMilliseconds()
        )
        dao.upsert(next)
    }

    override suspend fun deleteThread(threadId: Long) {
        dao.deleteThread(threadId)
    }
}

private fun ChatThreadEntity.toDomain(): StoredChatThread =
    StoredChatThread(
        id = id,
        title = title,
        lastMessagePreview = lastMessagePreview,
        updatedAt = Instant.fromEpochMilliseconds(updatedAtEpochMillis)
    )

private fun StoredChatThread.toEntity(): ChatThreadEntity =
    ChatThreadEntity(
        id = id,
        title = title,
        lastMessagePreview = lastMessagePreview,
        updatedAtEpochMillis = updatedAt.toEpochMilliseconds()
    )
