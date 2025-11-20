package com.pozyalov.ai_advent_challenge.mcp.docpipeline

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val TOOL_SEARCH = "doc_search"
private const val TOOL_SUMMARIZE = "doc_summarize"
private const val TOOL_SAVE = "doc_save"

fun main() = runDocPipelineServer()

fun runDocPipelineServer() = runBlocking {
    val docsDir = defaultDocsDirectory()
    val outputDir = defaultOutputDirectory()

    val server = Server(
        Implementation(name = "doc-pipeline", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    ) {
        registerSearchTool(docsDir)
        registerSummarizeTool()
        registerSaveTool(outputDir)
    }

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}

private fun defaultDocsDirectory(): File {
    val explicit = System.getProperty("ai.advent.docs.root")
        ?: System.getenv("AI_ADVENT_DOCS_ROOT")
    val home = System.getenv("HOME").orEmpty().ifBlank { System.getProperty("user.home", ".") }
    val downloadsCandidates = listOf("Downloads", "Загрузки")
    val baseDir = explicit?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: downloadsCandidates
            .map { File(home, it) }
            .firstOrNull { it.exists() && it.isDirectory }
        ?: File(home)
    if (!baseDir.exists()) baseDir.mkdirs()
    return baseDir
}

private fun defaultOutputDirectory(): File {
    val home = System.getenv("HOME").orEmpty().ifBlank { System.getProperty("user.home", ".") }
    val dir = File(home, ".ai_advent/pipeline_results")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun Server.registerSearchTool(docsDir: File) {
    addTool(
        name = TOOL_SEARCH,
        title = "Search docs",
        description = "Ищет совпадения по текстовым файлам в каталоге ${docsDir.absolutePath}",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Поисковый запрос"))
                })
                put("maxResults", buildJsonObject {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive("Максимум результатов (1..5)"))
                })
            },
            required = listOf("query")
        )
    ) { request ->
        val query = request.arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Пустой запрос недопустим.")),
                isError = true
            )
        }
        val limit = request.arguments["maxResults"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 5) ?: 3
        val matches = searchDocuments(docsDir, query, limit)

        if (matches.isEmpty()) {
            CallToolResult(content = listOf(TextContent("Совпадений не найдено в ${docsDir.absolutePath}.")))
        } else {
            val body = buildString {
                appendLine("Найдено ${matches.size} совпадений:")
                matches.forEachIndexed { index, result ->
                    appendLine("${index + 1}. ${result.fileName}")
                    appendLine(result.snippet)
                    appendLine()
                }
            }.trim()
            CallToolResult(
                content = listOf(TextContent(body)),
                structuredContent = buildJsonObject {
                    putJsonArray("matches") {
                        matches.forEach { add(it.toJson()) }
                    }
                }
            )
        }
    }
}

private fun Server.registerSummarizeTool() {
    addTool(
        name = TOOL_SUMMARIZE,
        title = "Summarize text",
        description = "Делает краткое резюме переданного текста без вызова внешних API.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("filePath", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Путь к файлу для суммаризации"))
                })
                put("maxSentences", buildJsonObject {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive("Максимум предложений в сводке (1..5)"))
                })
            },
            required = listOf("filePath")
        )
    ) { request ->
        val filePath = request.arguments["filePath"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (filePath.isEmpty()) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Путь к файлу обязателен.")),
                isError = true
            )
        }
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Файл $filePath не найден.")),
                isError = true
            )
        }
        val text = runCatching { file.readText() }.getOrElse {
            return@addTool CallToolResult(
                content = listOf(TextContent("Не удалось прочитать файл: ${it.message}")),
                isError = true
            )
        }
        val maxSentences = request.arguments["maxSentences"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 5) ?: 3
        val summary = summarizeText(text, maxSentences)
        CallToolResult(
            content = listOf(TextContent(summary)),
            structuredContent = buildJsonObject {
                put("summary", JsonPrimitive(summary))
            }
        )
    }
}

private fun Server.registerSaveTool(outputDir: File) {
    addTool(
        name = TOOL_SAVE,
        title = "Save summary to file",
        description = "Сохраняет текст суммаризации в файл в каталоге ${outputDir.absolutePath}",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("content", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Содержимое для сохранения"))
                })
                put("filename", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Имя файла (опционально)"))
                })
            },
            required = listOf("content")
        )
    ) { request ->
        val content = request.arguments["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Пустое содержимое нельзя сохранить.")),
                isError = true
            )
        }
        val fileName = request.arguments["filename"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            ?: "summary_${System.currentTimeMillis()}.txt"
        val file = File(outputDir, fileName)
        file.writeText(content)
        CallToolResult(
            content = listOf(TextContent("Сводка сохранена в ${file.absolutePath}")),
            structuredContent = buildJsonObject {
                put("path", JsonPrimitive(file.absolutePath))
            }
        )
    }
}

private data class SearchMatch(
    val filePath: String,
    val fileName: String,
    val snippet: String
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("filePath", JsonPrimitive(filePath))
        put("fileName", JsonPrimitive(fileName))
        put("snippet", JsonPrimitive(snippet))
    }
}

private fun searchDocuments(root: File, query: String, limit: Int): List<SearchMatch> {
    if (!root.exists()) return emptyList()
    val lower = query.lowercase()
    val matches = mutableListOf<SearchMatch>()
    root.walkTopDown()
        .filter { it.isFile && it.canRead() && it.length() <= 1_000_000 }
        .forEach { file ->
            if (file.name.lowercase().contains(lower)) {
                val snippet = runCatching {
                    file.bufferedReader().use { reader ->
                        reader.readLine()?.take(200)?.replace("\n", " ").orEmpty()
                    }
                }.getOrDefault("")
                matches += SearchMatch(
                    filePath = file.absolutePath,
                    fileName = file.name,
                    snippet = snippet
                )
            }
        }
    return matches.take(limit)
}

private fun summarizeText(text: String, maxSentences: Int): String {
    val sentences = text
        .replace("\n", " ")
        .split(Regex("(?<=[.!?])\\s+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (sentences.isEmpty()) return text.take(400)
    return sentences.take(maxSentences).joinToString(" ")
}
