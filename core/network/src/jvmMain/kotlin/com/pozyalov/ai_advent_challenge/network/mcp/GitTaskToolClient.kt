package com.pozyalov.ai_advent_challenge.network.mcp

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Локальный инструмент для получения текущей git-ветки в рабочем каталоге приложения.
 * Не требует отдельного MCP сервера: выполняет `git branch --show-current`.
 */
class GitTaskToolClient : TaskToolClient {

    override val toolDefinitions: List<Tool> = listOf(
        Tool.function(
            name = "git_branch",
            description = "Возвращает название текущей git-ветки в рабочем каталоге приложения.",
            parameters = Parameters(
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { })
                    put("required", kotlinx.serialization.json.buildJsonArray { })
                }
            )
        )
    )

    override suspend fun execute(toolName: String, arguments: JsonObject): TaskToolResult? {
        if (toolName != "git_branch") return null
        return runCatching {
            val process = ProcessBuilder("git", "branch", "--show-current")
                .directory(java.io.File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            if (exit != 0 || output.isBlank()) {
                TaskToolResult("Не удалось получить ветку git (код $exit).")
            } else {
                TaskToolResult(text = "Текущая ветка: $output")
            }
        }.getOrElse { error ->
            TaskToolResult("Ошибка при получении ветки git: ${error.message}")
        }
    }
}
