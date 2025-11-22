package com.pozyalov.ai_advent_challenge.network.mcp

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.modelcontextprotocol.kotlin.sdk.TextContent
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ScriptedTaskToolClient(
    private val displayName: String,
    scriptPath: String?,
    private val missingServerMessage: String,
    private val inspector: McpToolInspector = McpToolInspector(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : TaskToolClient {

    private val serverCommand: List<String>? = scriptPath
        ?.takeIf { it.isNotBlank() }
        ?.let(::resolveScriptCommand)

    private val descriptors: List<McpToolDescriptor> = loadDescriptors()

    override val toolDefinitions: List<Tool>
        get() = descriptors.map { it.toOpenAiTool() }

    override suspend fun execute(toolName: String, arguments: JsonObject): TaskToolResult? {
        val command = serverCommand
            ?: return TaskToolResult(missingServerMessage)

        val callResult = runCatching {
            inspector.callTool(
                serverCommand = command,
                toolName = toolName,
                arguments = arguments,
            )
        }.getOrElse { error ->
            return TaskToolResult(
                "Ошибка при вызове инструмента $toolName ($displayName): ${error.message ?: error::class.simpleName}"
            )
        } ?: return TaskToolResult("Инструмент $toolName вернул пустой ответ.")

        val summary = callResult.content.joinToString(separator = "\n") { content ->
            when (content) {
                is TextContent -> content.text.orEmpty()
                else -> content.toString()
            }
        }.trim()

        val structuredObject = callResult.structuredContent
            ?.takeIf { it.isNotEmpty() }
        val structuredHumanReadable = structuredObject
            ?.let { formatStructuredResult(it) }

        val payload = when {
            structuredHumanReadable?.isNotBlank() == true -> structuredHumanReadable
            summary.isNotBlank() -> summary
            else -> "Инструмент $toolName вернул пустой ответ."
        }

        return TaskToolResult(text = payload, structured = structuredObject)
    }

    private fun formatStructuredResult(structured: JsonObject): String? {
        formatReminderTask(structured)?.let { return it }
        val prettySummary = formatReminderSummary(structured)
        if (prettySummary != null) return prettySummary
        return runCatching { json.encodeToString(JsonObject.serializer(), structured) }.getOrNull()
            ?.let { "Структура ответа:\n$it" }
    }

    private fun formatReminderSummary(structured: JsonObject): String? {
        val overdue = structured["overdue"]?.asTaskList()?.dedupByTitle() ?: return null
        val dueToday = structured["dueToday"]?.asTaskList()?.dedupByTitle() ?: return null
        val upcoming = structured["upcoming"]?.asTaskList()?.dedupByTitle() ?: return null
        val later = structured["later"]?.asTaskList()?.dedupByTitle().orEmpty()
        val sections = buildList {
            add(buildString {
                appendLine("Сводка задач")
                appendLine("Просрочено: ${overdue.size}")
                appendLine("На сегодня: ${dueToday.size}")
                appendLine("Ближайшие 7 дней: ${upcoming.size}")
                appendLine("Дальше: ${later.size}")
            }.trim())
            add(formatTaskSection("Просрочено", overdue))
            add(formatTaskSection("Сегодня", dueToday))
            add(formatTaskSection("Ближайшие 7 дней", upcoming))
            add(formatTaskSection("Дальше", later))
        }.filter { it.isNotBlank() }
        return sections.joinToString(separator = "\n\n").ifBlank { null }
    }

    private fun JsonElement.asTaskList(): List<ReminderTaskDisplay>? =
        runCatching {
            jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ReminderTaskDisplay(
                    title = title,
                    dueDate = obj["dueDate"]?.jsonPrimitive?.contentOrNull,
                    description = obj["description"]?.jsonPrimitive?.contentOrNull,
                    priority = obj["priority"]?.jsonPrimitive?.contentOrNull
                )
            }
        }.getOrNull()

    private fun formatTaskSection(title: String, tasks: List<ReminderTaskDisplay>): String =
        if (tasks.isEmpty()) {
            "$title: нет задач."
        } else buildString {
            appendLine(title)
            tasks.forEach { task ->
                append("• ")
                append(task.title)
                val details = listOfNotNull(
                    task.description?.takeIf { it.isNotBlank() },
                    task.dueDate?.takeIf { it.isNotBlank() }?.let { "до $it" },
                    task.priority?.takeIf { it.isNotBlank() }?.let { "приоритет: $it" }
                )
                if (details.isNotEmpty()) {
                    append(" — ")
                    append(details.joinToString(separator = ", "))
                }
                appendLine()
            }
        }.trimEnd()

    private data class ReminderTaskDisplay(
        val title: String,
        val dueDate: String?,
        val description: String?,
        val priority: String?
    )

    private fun List<ReminderTaskDisplay>.dedupByTitle(): List<ReminderTaskDisplay> =
        this.groupBy { it.title }
            .map { (_, tasks) ->
                tasks.minByOrNull { task ->
                    task.dueDate?.takeIf { it.isNotBlank() } ?: "9999-12-31"
                } ?: tasks.first()
            }

    private fun formatReminderTask(structured: JsonObject): String? {
        val title = structured["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val due = structured["dueDate"]?.jsonPrimitive?.contentOrNull
        val status = structured["status"]?.jsonPrimitive?.contentOrNull
        val desc = structured["description"]?.jsonPrimitive?.contentOrNull
        val parts = buildList {
            desc?.takeIf { it.isNotBlank() }?.let { add(it) }
            due?.takeIf { it.isNotBlank() }?.let { add("до $it") }
            status?.takeIf { it.isNotBlank() }?.let { add("статус: $it") }
        }
        return if (parts.isEmpty()) {
            "Задача: $title"
        } else {
            "Задача: $title — ${parts.joinToString(", ")}"
        }
    }

    private fun resolveScriptCommand(path: String): List<String>? {
        val file = File(path).takeIf { it.isAbsolute } ?: locateRelativePath(path)
        if (!file.exists()) {
            println("MCP server script not found for $displayName: ${file.absolutePath}")
            return null
        }
        return McpToolInspector.guessCommandForScript(file.absolutePath)
    }

    private fun locateRelativePath(path: String): File {
        val userDir = File(System.getProperty("user.dir")).canonicalFile
        var current: File? = userDir
        repeat(6) {
            current ?: return@repeat
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        return File(userDir, path)
    }

    private fun loadDescriptors(): List<McpToolDescriptor> {
        val command = serverCommand ?: return emptyList()
        return runBlocking {
            runCatching {
                inspector.listTools(serverCommand = command)
            }.onFailure { error ->
                println("Failed to load MCP tools for $displayName: ${error.message ?: error::class.simpleName}")
            }.getOrDefault(emptyList())
        }
    }

    private fun McpToolDescriptor.toOpenAiTool(): Tool {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", inputSchema.properties)
            inputSchema.required?.takeIf { it.isNotEmpty() }?.let { required ->
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        }
        val descriptionText = description?.takeIf { it.isNotBlank() }
            ?: "Инструмент MCP $name"
        return Tool.function(
            name = name,
            description = descriptionText,
            parameters = Parameters(schema),
        )
    }
}
