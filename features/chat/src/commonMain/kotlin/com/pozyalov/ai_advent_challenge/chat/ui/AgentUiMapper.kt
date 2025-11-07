package com.pozyalov.ai_advent_challenge.chat.ui

import com.pozyalov.ai_advent_challenge.chat.domain.AgentStructuredResponse
import kotlin.math.roundToInt

fun AgentStructuredResponse.formatForDisplay(): String {
    val sections = buildList {
        add(title.ifBlank { "(без заголовка)" })
        if (summary.isNotBlank()) add(summary)
        if (confidence > 0.0) {
            add("Уверенность: ${confidence.formatDecimal()}")
        }
    }
    return sections.joinToString(separator = "\n\n")
}

private fun Double.formatDecimal(decimals: Int = 2): String {
    val scale = when {
        decimals <= 0 -> 1.0
        else -> 10.0.pow(decimals)
    }
    val rounded = (this * scale).roundToInt() / scale
    val raw = rounded.toString()
    return if (raw.contains('.')) {
        raw.trimEnd('0').trimEnd('.')
    } else {
        raw
    }
}

private fun Double.pow(exp: Int): Double {
    var result = 1.0
    repeat(exp) { result *= this }
    return result
}
