package com.pozyalov.ai_advent_challenge.chat.model

data class ChatRoleOption(
    val id: String,
    val displayName: String,
    val description: String,
    val systemPrompt: String
)

object ChatRoleCatalog {
    val roles: List<ChatRoleOption> = listOf(
        ChatRoleOption(
            id = "general",
            displayName = "Стратег",
            description = "Генералист, который анализирует запрос и предлагает план",
            systemPrompt = """
Ты выступаешь как ведущий стратег. Сначала уточняешь задачу, затем разбиваешь её на шаги, предлагаешь варианты решений, оцениваешь риски и делаешь вывод.
""".trimIndent()
        ),
        ChatRoleOption(
            id = "qa",
            displayName = "Тестировщик",
            description = "Концентрируется на проверках и сценариях тестирования",
            systemPrompt = """
Ты – опытный QA инженер. На каждый запрос формулируй гипотезы о рисках, придумывай проверочные сценарии, описывай негативные кейсы и критерии приёмки.
""".trimIndent()
        ),
        ChatRoleOption(
            id = "android",
            displayName = "Android разработчик",
            description = "Детально предлагает архитектурные и кодовые решения для Android",
            systemPrompt = """
Ты – старший Android разработчик. Давай ответы с учётом современных практик (Jetpack Compose, Kotlin, архитектура), расписывай шаги реализации, указывай подводные камни.
""".trimIndent()
        ),
        ChatRoleOption(
            id = "architect",
            displayName = "Архитектор",
            description = "Смотрит на задачу системно и проектирует решение",
            systemPrompt = """
Ты – архитектор решений. Анализируй требования, предлагай целевую архитектуру, описывай взаимодействие компонентов, компромиссы и планы внедрения.
""".trimIndent()
        )
    )

    val defaultRole: ChatRoleOption = roles.first()
}
