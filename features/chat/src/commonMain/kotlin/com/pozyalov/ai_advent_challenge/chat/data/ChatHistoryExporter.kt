@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.data

import com.pozyalov.ai_advent_challenge.chat.component.ConversationMessage
import com.pozyalov.ai_advent_challenge.chat.data.memory.AgentMemoryEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class ChatHistoryExporter(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    },
    private val baseDirectory: Path = DEFAULT_BASE
) {

    suspend fun export(
        threadId: Long,
        messages: List<ConversationMessage>,
        memories: List<AgentMemoryEntry>,
        directoryOverride: String? = null
    ): ExportResult {
        val base = resolveBaseDirectory(directoryOverride)
        fileSystem.createDirectories(base)
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val fileName = "thread_${threadId}_$timestamp.json"
        val target = base / fileName
        val payload = ExportPayload(
            threadId = threadId,
            exportedAt = timestamp,
            messageCount = messages.size,
            messages = messages.map { it.toExportMessage() },
            memories = memories.map { it.toExport() }
        )
        fileSystem.sink(target, mustCreate = true).buffer().use { sink ->
            sink.writeUtf8(json.encodeToString(payload))
        }
        return ExportResult(path = target.toString(), exportedAtMillis = timestamp)
    }

    @Serializable
    private data class ExportPayload(
        val threadId: Long,
        val exportedAt: Long,
        val messageCount: Int,
        val messages: List<ExportMessage>,
        val memories: List<ExportMemory>
    )

    @Serializable
    private data class ExportMessage(
        val id: Long,
        val author: String,
        val text: String,
        val timestamp: Long,
        val isSummary: Boolean,
        val metadata: MessageMetadata
    )

    @Serializable
    private data class MessageMetadata(
        val modelId: String?,
        val roleId: String?,
        val temperature: Double?,
        val metrics: MessageMetrics?
    )

    @Serializable
    private data class MessageMetrics(
        val responseTimeMillis: Long?,
        val promptTokens: Long?,
        val completionTokens: Long?,
        val totalTokens: Long?,
        val costUsd: Double?
    )

    @Serializable
    private data class ExportMemory(
        val id: Long,
        val title: String,
        val content: String,
        val createdAt: Long
    )

    data class ExportResult(
        val path: String,
        val exportedAtMillis: Long
    )

    private fun ConversationMessage.toExportMessage(): ExportMessage =
        ExportMessage(
            id = id,
            author = author.name,
            text = text,
            timestamp = timestamp.toEpochMilliseconds(),
            isSummary = isSummary,
            metadata = MessageMetadata(
                modelId = modelId,
                roleId = roleId,
                temperature = temperature,
                metrics = MessageMetrics(
                    responseTimeMillis = responseTimeMillis,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                    costUsd = costUsd
                )
            )
        )

    private fun AgentMemoryEntry.toExport(): ExportMemory =
        ExportMemory(
            id = id,
            title = title,
            content = content,
            createdAt = createdAt.toEpochMilliseconds()
        )

    private fun resolveBaseDirectory(overridePath: String?): Path {
        val normalized = overridePath?.trim().takeIf { !it.isNullOrEmpty() }
        if (normalized != null) {
            runCatching { normalized.toPath() }
                .onSuccess { return it }
        }
        return baseDirectory
    }

    private companion object {
        val DEFAULT_BASE: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ai_advent_exports"
    }
}
