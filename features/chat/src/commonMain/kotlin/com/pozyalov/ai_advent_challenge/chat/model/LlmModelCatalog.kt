package com.pozyalov.ai_advent_challenge.chat.model


data class LlmModelOption(
    val id: String,
    val displayName: String,
    val description: String,
    val temperature: Double,
    val temperatureLocked: Boolean = false
)

object LlmModelCatalog {
    const val DefaultModelId: String = "gpt-5-mini"

    val models: List<LlmModelOption> = listOf(
        LlmModelOption(
            id = "gpt-5-mini",
            displayName = "GPT-5 Mini",
            description = "Универсальная модель для продвинутых диалогов и проектирования",
            temperature = 1.0,
            temperatureLocked = true
        ),
        LlmModelOption(
            id = "gpt-5-nano",
            displayName = "GPT-5 Nano",
            description = "Максимально экономичный вариант пятого поколения для быстрых подсказок",
            temperature = 1.0,
            temperatureLocked = true
        ),
        LlmModelOption(
            id = "gpt-4.1",
            displayName = "GPT-4.1",
            description = "Флагманская точность для сложных требований и рассуждений",
            temperature = 0.4
        ),
        LlmModelOption(
            id = "gpt-4.1-mini",
            displayName = "GPT-4.1 Mini",
            description = "Облегчённая версия GPT-4.1 с оптимальным балансом цены и качества",
            temperature = 0.4
        ),
        LlmModelOption(
            id = "gpt-4.1-nano",
            displayName = "GPT-4.1 Nano",
            description = "Самый доступный представитель линейки 4.1 для быстрых итераций",
            temperature = 0.4
        ),
        LlmModelOption(
            id = "gpt-4o-mini",
            displayName = "GPT-4o Mini",
            description = "Мультимодальная модель с быстрым откликом и умеренной стоимостью",
            temperature = 0.4
        )
    )

    fun firstOrDefault(modelId: String?): LlmModelOption =
        models.firstOrNull { it.id == modelId } ?: models.first { it.id == DefaultModelId }
}
