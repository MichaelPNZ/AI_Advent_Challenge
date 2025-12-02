package com.pozyalov.ai_advent_challenge.network.mcp

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        ),
        Tool.function(
            name = "git_diff",
            description = "Возвращает git diff против базовой ветки (по умолчанию origin/main).",
            parameters = Parameters(
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("base", buildJsonObject {
                            put("type", "string")
                            put("description", "Базовая ветка или ревизия (по умолчанию origin/main)")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Опциональный путь для ограничения diff")
                        })
                    })
                    put("required", kotlinx.serialization.json.buildJsonArray { })
                }
            )
        ),
        Tool.function(
            name = "git_show_file",
            description = "Показывает содержимое файла на указанной ревизии (по умолчанию HEAD).",
            parameters = Parameters(
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Путь к файлу относительно корня репозитория")
                        })
                        put("rev", buildJsonObject {
                            put("type", "string")
                            put("description", "Ревизия/ветка (по умолчанию HEAD)")
                        })
                    })
                    put("required", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("path"))
                    })
                }
            )
        )
    )

    override suspend fun execute(toolName: String, arguments: JsonObject): TaskToolResult? {
        return when (toolName) {
            "git_branch" -> handleBranch()
            "git_diff" -> handleDiff(arguments)
            "git_show_file" -> handleShow(arguments)
            else -> null
        }
    }

    private fun handleBranch(): TaskToolResult =
        runCatching {
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

    private fun handleDiff(args: JsonObject): TaskToolResult =
        runCatching {
            val base = args["base"]?.jsonPrimitive?.content?.ifBlank { null } ?: "origin/main"
            val path = args["path"]?.jsonPrimitive?.content?.ifBlank { null }
            val command = buildList {
                add("git")
                add("diff")
                add("$base...HEAD")
                if (path != null) add(path)
            }
            val process = ProcessBuilder(command)
                .directory(java.io.File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            if (exit != 0) {
                TaskToolResult("Не удалось получить diff (код $exit).")
            } else {
                TaskToolResult(text = output.ifBlank { "Diff пуст." })
            }
        }.getOrElse { error ->
            TaskToolResult("Ошибка при получении diff: ${error.message}")
        }

    private fun handleShow(args: JsonObject): TaskToolResult {
        val path = args["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return TaskToolResult("Параметр 'path' обязателен.")
        val rev = args["rev"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "HEAD"
        return runCatching {
            val process = ProcessBuilder("git", "show", "$rev:$path")
                .directory(java.io.File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) {
                TaskToolResult("Не удалось показать файл $path на $rev (код $exit).")
            } else {
                TaskToolResult(text = output.ifBlank { "Файл пуст или не найден." })
            }
        }.getOrElse { error ->
            TaskToolResult("Ошибка git_show_file: ${error.message}")
        }
    }
}
