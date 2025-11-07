@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.data.local

import com.pozyalov.ai_advent_challenge.chat.ConversationError
import com.pozyalov.ai_advent_challenge.chat.ConversationMessage
import com.pozyalov.ai_advent_challenge.chat.MessageAuthor
import com.pozyalov.ai_advent_challenge.chat.domain.AgentStructuredResponse
import com.pozyalov.ai_advent_challenge.core.database.chat.ChatMessageEntity
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

fun ChatMessageEntity.toDomain(): ConversationMessage {
    val resolvedAuthor = runCatching { MessageAuthor.valueOf(author) }.getOrElse { MessageAuthor.Agent }
    val resolvedError = error?.let { runCatching { ConversationError.valueOf(it) }.getOrNull() }
    return ConversationMessage(
        id = id,
        author = resolvedAuthor,
        text = text,
        structured = toStructuredResponse(),
        error = resolvedError,
        timestamp = Instant.fromEpochMilliseconds(timestampEpochMillis),
        modelId = modelId
    )
}

fun ConversationMessage.toEntity(): ChatMessageEntity =
    ChatMessageEntity(
        id = id,
        author = author.name,
        text = text,
        structuredTitle = structured?.title,
        structuredSummary = structured?.summary,
        structuredConfidence = structured?.confidence,
        error = error?.name,
        timestampEpochMillis = timestamp.toEpochMilliseconds(),
        modelId = modelId
    )

private fun ChatMessageEntity.toStructuredResponse(): AgentStructuredResponse? {
    if (structuredTitle == null && structuredSummary == null && structuredConfidence == null) {
        return null
    }
    return AgentStructuredResponse(
        title = structuredTitle.orEmpty(),
        summary = structuredSummary.orEmpty(),
        confidence = structuredConfidence ?: 0.0
    )
}
