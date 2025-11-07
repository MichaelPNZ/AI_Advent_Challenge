package com.pozyalov.ai_advent_challenge.chat.util

import io.github.aakira.napier.Napier

internal fun chatLog(message: String) {
    Napier.d(tag = "Chat", message = message)
}
