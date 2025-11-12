package com.pozyalov.ai_advent_challenge.chat.model

data class TemperatureOption(
    val id: String,
    val displayName: String,
    val description: String,
    val value: Double
)

object TemperatureCatalog {
    val options: List<TemperatureOption> = listOf(
        TemperatureOption(
            id = "temp_zero",
            displayName = "Детерминированно",
            description = "Температура 0.0 – полностью предсказуемые ответы",
            value = 0.0
        ),
        TemperatureOption(
            id = "temp_low",
            displayName = "Консервативно",
            description = "Температура 0.2 – чёткие и предсказуемые ответы",
            value = 0.2
        ),
        TemperatureOption(
            id = "temp_medium",
            displayName = "Сбалансировано",
            description = "Температура 0.7 – баланс креативности и точности",
            value = 0.7
        ),
        TemperatureOption(
            id = "temp_high",
            displayName = "Креативно",
            description = "Температура 1.2 – максимум идей и экспериментов",
            value = 1.2
        )
    )

    val default: TemperatureOption = options.first { it.id == "temp_medium" }
}

data class ReasoningOption(
    val id: String,
    val displayName: String,
    val description: String,
    val effort: String
)

object ReasoningCatalog {
    val options: List<ReasoningOption> = listOf(
        ReasoningOption(
            id = "reason_low",
            displayName = "Быстро",
            description = "Минимальные рассуждения, быстрый ответ",
            effort = "low"
        ),
        ReasoningOption(
            id = "reason_medium",
            displayName = "Стандартно",
            description = "Средний уровень рассуждений",
            effort = "medium"
        ),
        ReasoningOption(
            id = "reason_high",
            displayName = "Глубоко",
            description = "Максимальное внимание к деталям",
            effort = "high"
        )
    )

    val default: ReasoningOption = options[0]
}

data class ContextLimitOption(
    val id: String,
    val displayName: String,
    val description: String,
    val paddingTokens: Int?,
    val requiresCustomValue: Boolean = false
)

object ContextLimitCatalog {
    val options: List<ContextLimitOption> = listOf(
        ContextLimitOption(
            id = "context_none",
            displayName = "Не добавлять нагрузку",
            description = "Отправлять запрос как есть",
            paddingTokens = null
        ),
        ContextLimitOption(
            id = "context_pad_32k",
            displayName = "+8 000 токенов",
            description = "Добавить ~8k фиктивных токенов для мягкой нагрузки",
            paddingTokens = 8_000
        ),
        ContextLimitOption(
            id = "context_pad_64k",
            displayName = "+16 000 токенов",
            description = "Умеренная нагрузка для проверки лимитов",
            paddingTokens = 16_000
        ),
        ContextLimitOption(
            id = "context_pad_128k",
            displayName = "+32 000 токенов",
            description = "Агрессивная нагрузка, быстро приведёт к rate limit",
            paddingTokens = 32_000
        ),
        ContextLimitOption(
            id = "context_pad_custom",
            displayName = "Свое значение",
            description = "Укажите точное количество токенов, которое нужно добавить",
            paddingTokens = null,
            requiresCustomValue = true
        )
    )

    val default: ContextLimitOption = options.first()
}
