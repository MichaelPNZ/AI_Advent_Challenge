package com.pozyalov.ai_advent_challenge.pipeline

import com.aallam.openai.api.model.ModelId
import com.pozyalov.ai_advent_challenge.chat.domain.ChatMessage
import com.pozyalov.ai_advent_challenge.chat.domain.ChatRole
import com.pozyalov.ai_advent_challenge.chat.domain.GenerateChatReplyUseCase
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelCatalog
import com.pozyalov.ai_advent_challenge.chat.pipeline.DocPipelineExecutor
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileInputStream
import org.apache.poi.xwpf.usermodel.XWPFDocument

class DesktopDocPipelineExecutor(
    private val taskToolClient: TaskToolClient,
    private val generateReply: GenerateChatReplyUseCase
) : DocPipelineExecutor {

    override val isAvailable: Boolean
        get() {
            val toolNames = taskToolClient.toolDefinitions.map { it.function.name }
            return listOf(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TOOL).all { toolNames.contains(it) }
        }

    override suspend fun search(query: String): Result<List<DocPipelineExecutor.Match>> =
        runCatching {
            val searchResult = taskToolClient.execute(
                SEARCH_TOOL,
                buildJsonObject {
                    put("query", query)
                    put("maxResults", 5)
                }
            ) ?: error("searchDocs вернул пустой ответ")

            val array = searchResult.structured?.get("matches")?.jsonArray
                ?: error("searchDocs не вернул список совпадений")
            array.map { element ->
                val obj = element.jsonObject
                DocPipelineExecutor.Match(
                    filePath = obj["filePath"]?.jsonPrimitive?.content
                        ?: error("filePath missing"),
                    fileName = obj["fileName"]?.jsonPrimitive?.content
                        ?: obj["filePath"]?.jsonPrimitive?.content?.substringAfterLast('/') ?: "unknown",
                    snippet = obj["snippet"]?.jsonPrimitive?.content.orEmpty()
                )
            }
        }

    override suspend fun summarize(
        match: DocPipelineExecutor.Match,
        outputName: String?
    ): Result<DocPipelineExecutor.ResultPayload> =
        runCatching {
            val summary = summarizeWithLlm(match.filePath)
            val saveResult = taskToolClient.execute(
                SAVE_TOOL,
                buildJsonObject {
                    put("content", summary)
                    outputName?.takeIf { it.isNotBlank() }?.let { put("filename", it) }
                }
            )
            val savedPath = saveResult?.structured?.get("path")?.jsonPrimitive?.contentOrNull
            DocPipelineExecutor.ResultPayload(summary = summary, savedPath = savedPath)
        }

    private suspend fun summarizeWithLlm(filePath: String): String {
        val text = extractText(File(filePath))
        val trimmed = text.take(MAX_CHARS)
        val history = listOf(
            ChatMessage(
                role = ChatRole.User,
                content = """
                    Сформируй краткую и информативную сводку этого документа.
                    Не копируй текст дословно, выдели смысл и ключевые факты.
                    $trimmed
                """.trimIndent()
            )
        )
        val model = LlmModelCatalog.models.first()
        val result = generateReply(
            history = history,
            model = ModelId(model.id),
            temperature = model.temperature,
            systemPrompt = "Ты помощник, который аккуратно суммирует документы. " +
                    "Сообщение должно состоять из: " +
                    "1) Заголовок обозначающий тему документа" +
                    "2) очень краткая суть всего документа, о чем он." +
                    "3) анализ документа",
            reasoningEffort = "medium"
        ).getOrElse { throw it }
        return result.structured.summary.ifBlank {
            result.structured.title.ifBlank { "Сводка недоступна" }
        }
    }

    private fun extractText(file: File): String {
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("Файл ${file.absolutePath} не найден.")
        }
        return when (file.extension.lowercase()) {
            "pdf" -> extractPdfText(file)
            "docx" -> extractDocxText(file)
            else -> runCatching { file.readText() }.getOrElse {
                throw IllegalStateException("Не удалось прочитать файл: ${it.message}")
            }
        }
    }

    private fun extractPdfText(file: File): String =
        Loader.loadPDF(file).use { document ->
            val stripper = PDFTextStripper().apply {
                startPage = 1
                endPage = minOf(document.numberOfPages, 20)
            }
            stripper.getText(document)
        }

    private fun extractDocxText(file: File): String =
        FileInputStream(file).use { input ->
            XWPFDocument(input).use { document ->
                document.paragraphs.joinToString(separator = "\n") { it.text.orEmpty() }
            }
        }

    companion object {
        private const val SEARCH_TOOL = "doc_search"
        private const val SUMMARIZE_TOOL = "doc_summarize"
        private const val SAVE_TOOL = "doc_save"
        private const val MAX_CHARS = 12_000
    }
}
