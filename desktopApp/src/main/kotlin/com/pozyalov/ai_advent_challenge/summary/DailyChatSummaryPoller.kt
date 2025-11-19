@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.summary

import com.pozyalov.ai_advent_challenge.chat.component.ConversationMessage
import com.pozyalov.ai_advent_challenge.chat.component.MessageAuthor
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.core.database.chat.data.ChatThreadDataSource
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import java.lang.System.getProperty
import kotlin.time.Instant

private const val DAILY_SUMMARY_TOOL = "chat_daily_summaries"

class DailyChatSummaryPoller(
    private val taskToolClient: TaskToolClient,
    private val chatHistory: ChatHistoryDataSource,
    private val chatThreads: ChatThreadDataSource,
    pollIntervalMinutes: Long = getProperty("ai.advent.chat.summary.poll.minutes")?.toLongOrNull() ?: 1440L
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val delayDuration = pollIntervalMinutes.coerceAtLeast(1).minutes

    init {
        scope.launch { loop() }
    }

    suspend fun stop() {
        scope.cancel()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                if (taskToolClient.toolDefinitions.any { it.function.name == DAILY_SUMMARY_TOOL }) {
                    fetchAndPost()
                }
            } catch (error: Throwable) {
                println("[DailySummaryPoller] Failed to fetch summaries: ${error.message}")
            }
            delay(delayDuration)
        }
    }

    private suspend fun fetchAndPost() {
        val result = taskToolClient.execute(DAILY_SUMMARY_TOOL, JsonObject(emptyMap())) ?: return
        val structured = result.structured ?: return
        val summaries = structured["summaries"]?.jsonArray ?: return
        summaries.forEach { element ->
            runCatching { element.jsonObject }.getOrNull()?.let { postSummary(it) }
        }
    }

    private suspend fun postSummary(payload: JsonObject) {
        val threadId = payload["threadId"]?.jsonPrimitive?.longOrNull ?: return
        val thread = chatThreads.getThread(threadId) ?: return
        val text = payload["text"]?.jsonPrimitive?.contentOrNull ?: return
        val createdAt = payload["createdAt"]?.jsonPrimitive?.longOrNull
        val timestamp = createdAt?.let { Instant.fromEpochMilliseconds(it) } ?: Clock.System.now()
        val message = ConversationMessage(
            threadId = threadId,
            id = Random.nextLong(),
            author = MessageAuthor.Agent,
            text = "[Daily Summary]\n\n$text",
            isSummary = true,
            timestamp = timestamp,
            modelId = "chat-daily-summary"
        )
        chatHistory.saveMessage(message)
        chatThreads.updateThread(
            threadId = threadId,
            title = thread.title,
            lastMessagePreview = text.take(120)
        )
    }
}
