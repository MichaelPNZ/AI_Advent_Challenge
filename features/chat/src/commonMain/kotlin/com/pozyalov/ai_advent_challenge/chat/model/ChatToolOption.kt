package com.pozyalov.ai_advent_challenge.chat.model

data class ChatToolOption(
    val id: String,
    val title: String,
    val description: String?,
    val toolNames: List<String>,
    val isAvailable: Boolean,
    val isEnabled: Boolean
)
