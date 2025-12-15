package com.pozyalov.ai_advent_challenge.personalization

import com.pozyalov.ai_advent_challenge.chat.personalization.PersonalizationProvider
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersonalizationConfig(
    val name: String? = null,
    val bio: String? = null,
    val tone: String? = null,
    val habits: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    val timezone: String? = null,
    val workHours: String? = null,
    val shortcuts: Map<String, String> = emptyMap()
) {
    fun asPrompt(): String = buildString {
        name?.takeIf { it.isNotBlank() }?.let { appendLine("Имя пользователя: $it") }
        bio?.takeIf { it.isNotBlank() }?.let { appendLine("Контекст: $it") }
        tone?.takeIf { it.isNotBlank() }?.let { appendLine("Предпочтительный тон: $it") }
        timezone?.takeIf { it.isNotBlank() }?.let { appendLine("Часовой пояс: $it") }
        workHours?.takeIf { it.isNotBlank() }?.let { appendLine("График работы: $it") }
        appendList("Привычки", habits)
        appendList("Интересы", interests)
        appendList("Цели", goals)
        appendList("Избегать", avoid)
        if (shortcuts.isNotEmpty()) {
            appendLine("Шорткаты / предпочтительные формулировки:")
            shortcuts.forEach { (key, value) ->
                appendLine("- $key = $value")
            }
        }
    }.trim()

    private fun StringBuilder.appendList(title: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine("$title:")
        items.filter { it.isNotBlank() }.forEach { item ->
            appendLine("- $item")
        }
    }
}

class FilePersonalizationProvider(
    private val prompt: String?
) : PersonalizationProvider {
    override fun personalPrompt(): String? = prompt?.takeIf { it.isNotBlank() }
}

object PersonalizationConfigLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun loadPrompt(): String? {
        val file = resolveConfigFile() ?: return null
        val content = runCatching { file.readText() }.getOrElse { error ->
            println("Не удалось прочитать файл персонализации: ${error.message}")
            return null
        }.trim()
        if (content.isBlank()) return null

        // Если файл похож на JSON, пробуем распарсить как структуру, иначе используем как plain-text.
        val parsed = runCatching {
            json.decodeFromString(PersonalizationConfig.serializer(), content)
        }.getOrNull()

        return parsed?.asPrompt() ?: content
    }

    private fun resolveConfigFile(): File? {
        val explicitPath = System.getProperty("ai.advent.profile.path")
            ?: System.getenv("AI_ADVENT_PROFILE_PATH")
        val candidate = explicitPath?.let { File(it) }
        val defaultFile = File(File(System.getProperty("user.home"), ".ai_advent"), "personal_agent.json")
        val file = when {
            candidate != null && candidate.exists() -> candidate
            candidate != null && candidate.isAbsolute -> candidate // keep absolute even if missing to avoid silent use
            defaultFile.exists() -> defaultFile
            else -> defaultFile.takeIf { it.parentFile?.exists() == true }
        }
        return file?.takeIf { it.exists() }
    }
}
