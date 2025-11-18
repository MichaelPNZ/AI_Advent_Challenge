package com.pozyalov.ai_advent_challenge.network.mcp

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.modelcontextprotocol.kotlin.sdk.TextContent
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

        val structured = callResult.structuredContent
            ?.takeIf { it.isNotEmpty() }
            ?.let { json.encodeToString(JsonObject.serializer(), it) }

        val payload = buildString {
            if (summary.isNotBlank()) append(summary)
            structured?.let {
                if (isNotEmpty()) append("\n")
                append("structured: ")
                append(it)
            }
        }.ifBlank { "Инструмент $toolName вернул пустой ответ." }

        return TaskToolResult(text = payload)
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
