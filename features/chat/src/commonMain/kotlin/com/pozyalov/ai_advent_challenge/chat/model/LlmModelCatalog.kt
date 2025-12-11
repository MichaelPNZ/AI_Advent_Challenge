package com.pozyalov.ai_advent_challenge.chat.model

import com.pozyalov.ai_advent_challenge.network.api.OllamaTuning

data class LlmModelOption(
    val id: String,
    val displayName: String,
    val description: String,
    val temperature: Double,
    val temperatureLocked: Boolean = false,
    val promptPricePer1KTokensUsd: Double? = null,
    val completionPricePer1KTokensUsd: Double? = null,
    val ollamaTuning: OllamaTuning? = null
)

object LlmModelCatalog {
    const val DefaultModelId: String = "ollama:llama3.2:1b-instruct-q4_K_M"

    val models: List<LlmModelOption> = listOf(
        LlmModelOption(
            id = "ollama:llama3.2:1b-instruct-q4_K_M",
            displayName = "Llama 3.2 1B instruct q4_K_M (Ollama на VPS)",
            description = "VPS Ollama: `http://208.123.185.229:11434`, квантованная `llama3.2:1b-instruct-q4_K_M` для быстрого ответа на 2 vCPU.",
            temperature = 0.55,
            promptPricePer1KTokensUsd = null,
            completionPricePer1KTokensUsd = null,
            ollamaTuning = OllamaTuning(
                // Базовый профиль под слабый VPS: умеренный контекст и короткие ответы
                numCtx = 4096,
                numPredict = 256,
                topP = 0.92,
                topK = 60,
                repeatPenalty = 1.05,
                temperature = 0.55
            )
        ),
        LlmModelOption(
            id = "gpt-5-mini",
            displayName = "GPT-5 Mini",
            description = "Универсальная модель для продвинутых диалогов и проектирования",
            temperature = 1.0,
            temperatureLocked = true,
            promptPricePer1KTokensUsd = 0.01,
            completionPricePer1KTokensUsd = 0.03
        ),
        LlmModelOption(
            id = "gpt-5-nano",
            displayName = "GPT-5 Nano",
            description = "Максимально экономичный вариант пятого поколения для быстрых подсказок",
            temperature = 1.0,
            temperatureLocked = true,
            promptPricePer1KTokensUsd = 0.004,
            completionPricePer1KTokensUsd = 0.012
        ),
        LlmModelOption(
            id = "gpt-4.1",
            displayName = "GPT-4.1",
            description = "Флагманская точность для сложных требований и рассуждений",
            temperature = 0.4,
            promptPricePer1KTokensUsd = 0.015,
            completionPricePer1KTokensUsd = 0.06
        ),
        LlmModelOption(
            id = "gpt-4.1-mini",
            displayName = "GPT-4.1 Mini",
            description = "Облегчённая версия GPT-4.1 с оптимальным балансом цены и качества",
            temperature = 0.4,
            promptPricePer1KTokensUsd = 0.003,
            completionPricePer1KTokensUsd = 0.012
        ),
        LlmModelOption(
            id = "gpt-4.1-nano",
            displayName = "GPT-4.1 Nano",
            description = "Самый доступный представитель линейки 4.1 для быстрых итераций",
            temperature = 0.4,
            promptPricePer1KTokensUsd = 0.0015,
            completionPricePer1KTokensUsd = 0.006
        ),
        LlmModelOption(
            id = "gpt-4o-mini",
            displayName = "GPT-4o Mini",
            description = "Мультимодальная модель с быстрым откликом и умеренной стоимостью",
            temperature = 0.4,
            promptPricePer1KTokensUsd = 0.003,
            completionPricePer1KTokensUsd = 0.012
        )
    )

    fun firstOrDefault(modelId: String?): LlmModelOption =
        models.firstOrNull { it.id == modelId } ?: models.first { it.id == DefaultModelId }

    fun isLocalModel(modelId: String?): Boolean =
        modelId?.startsWith("ollama:", ignoreCase = true) == true

    fun tuningFor(modelId: String?): OllamaTuning? =
        models.firstOrNull { it.id == modelId }?.ollamaTuning
}
