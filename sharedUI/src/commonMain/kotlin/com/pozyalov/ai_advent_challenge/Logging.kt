package com.pozyalov.ai_advent_challenge

import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier

fun initLogs() {
    Napier.base(DebugAntilog())
}

fun appLog(message: String) {
    Napier.d(message = "AI🚀 : $message")
}
