@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.features.chatlist.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class ChatListItem(
    val id: Long,
    val title: String,
    val preview: String?,
    val updatedAt: Instant
)
