package com.pozyalov.ai_advent_challenge.embedding

import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.XWPFDocument
import kotlin.collections.map

@Serializable
data class EmbeddingIndexRecord(
    val id: String,
    val file: String,
    val chunk: Int,
    val text: String,
    val embedding: List<Float>
)

class EmbeddingIndexService(
    private val client: OllamaEmbeddingClient,
    private val indexDir: File = File(File(System.getProperty("user.home"), ".ai_advent"), "embedding_index"),
    private val json: Json = Json { encodeDefaults = false; prettyPrint = false }
) {
    private val indexFile: File = File(indexDir, "index.jsonl")

    fun buildIndex(
        path: File,
        outputFile: File? = null,
        chunkSize: Int = 400,
        overlap: Int = 40
    ): Result<File> = runCatching {
        require(path.exists()) { "Путь ${path.absolutePath} не найден." }
        if (!indexDir.exists()) indexDir.mkdirs()
        val target = outputFile ?: indexFile
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val append = target.exists()
        java.io.FileOutputStream(target, append).bufferedWriter().use { writer ->
            val files = if (path.isDirectory) {
                path.walkTopDown().filter { it.isFile && it.extension.lowercase() in allowedExtensions }.toList()
            } else {
                listOf(path).filter { it.isFile && it.extension.lowercase() in allowedExtensions }
            }
            require(files.isNotEmpty()) { "В выбранной папке нет поддерживаемых файлов (${allowedExtensions.joinToString()})." }
            var added = 0
            files.forEach { file ->
                val text = extractText(file)
                splitChunks(text, chunkSize, overlap).forEachIndexed { idx, rawChunk ->
                    val chunk = sanitize(rawChunk).take(MAX_PROMPT_CHARS)
                    if (chunk.isEmpty()) return@forEachIndexed
                    val emb = embedWithRetry(chunk, file.name, idx) ?: return@forEachIndexed
                    val record = EmbeddingIndexRecord(
                        id = UUID.randomUUID().toString(),
                        file = file.absolutePath,
                        chunk = idx,
                        text = chunk,
                        embedding = emb.toList()
                    )
                    writer.appendLine(json.encodeToString(record))
                    added++
                }
            }
            require(added > 0) { "Не удалось проиндексировать файлы: подходящий текст не найден или все чанки отброшены." }
        }
        target
    }

    fun search(query: String, topK: Int = 5): Result<List<EmbeddingIndexRecord>> = runCatching {
        require(indexFile.exists()) { "Индекс не найден: ${indexFile.absolutePath}" }
        val qVec = client.embed(query)
        val records: List<EmbeddingIndexRecord> = indexFile.readLines().map { line ->
            json.decodeFromString(EmbeddingIndexRecord.serializer(), line)
        }
        val scored = records.map { record ->
            ScoredRecord(record, cosine(qVec, record.embedding.toFloatArray()))
        }
        scored.sortedByDescending { it.score }
            .take(topK)
            .map { it.record }
    }

    fun searchScored(query: String, topK: Int = 5, minScore: Double? = null): Result<List<ScoredRecord>> =
        runCatching {
            require(indexFile.exists()) { "Индекс не найден: ${indexFile.absolutePath}" }
            val qVec = client.embed(query)
            val records: List<EmbeddingIndexRecord> = indexFile.readLines().map { line ->
                json.decodeFromString(EmbeddingIndexRecord.serializer(), line)
            }
            records.asSequence()
                .map { record ->
                    val score = cosine(qVec, record.embedding.toFloatArray())
                    ScoredRecord(record, score)
                }
                .sortedByDescending { it.score }
                .let { seq -> minScore?.let { thr -> seq.filter { it.score >= thr } } ?: seq }
                .take(topK)
                .toList()
        }

    private fun splitChunks(text: String, size: Int, overlap: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + size).coerceAtMost(text.length)
            chunks += text.substring(start, end)
            if (end == text.length) break
            start = end - overlap
        }
        return chunks
    }

    private fun extractText(file: File): String {
        return when (file.extension.lowercase()) {
            "pdf" -> extractPdfText(file)
            "docx" -> extractDocxText(file)
            else -> runCatching { file.readText() }.getOrElse {
                throw IllegalStateException("Не удалось прочитать файл ${file.name}: ${it.message}")
            }
        }
    }

    private fun extractPdfText(file: File): String =
        Loader.loadPDF(file).use { document ->
            PDFTextStripper().apply {
                startPage = 1
                endPage = document.numberOfPages
            }.getText(document)
        }

    private fun extractDocxText(file: File): String =
        File(file.absolutePath).inputStream().use { input ->
            XWPFDocument(input).use { doc ->
                doc.paragraphs.joinToString("\n") { it.text }
            }
        }

    // Расширили список под полный проект: текст, код, конфиги
    private val allowedExtensions = setOf(
        "txt", "md", "pdf", "docx",
        "kt", "kts", "java", "xml", "json", "yaml", "yml",
        "gradle", "properties", "csv", "sh"
    )

    private companion object {
        const val MAX_PROMPT_CHARS = 2000
        const val MAX_RETRIES = 2
        const val RETRY_SLEEP_MS = 500L
    }

    private fun sanitize(text: String): String {
        // Убираем управляющие символы кроме пробелов/табов/переводов строк
        return text
            .map { ch ->
                when (ch) {
                    '\t' -> ' '
                    '\n', '\r' -> ' '
                    else -> if (ch.isISOControl()) ' ' else ch
                }
            }
            .joinToString("")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun embedWithRetry(chunk: String, fileName: String, chunkIndex: Int): FloatArray? {
        var lastError: Throwable? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                return client.embed(chunk)
            } catch (error: Throwable) {
                lastError = error
                println("Embedding failed (attempt ${attempt + 1}/${MAX_RETRIES + 1}) for $fileName chunk=$chunkIndex len=${chunk.length}: ${error.message}")
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(RETRY_SLEEP_MS)
                }
            }
        }
        println("Skip chunk after retries: $fileName chunk=$chunkIndex len=${chunk.length} error=${lastError?.message}")
        return null
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        val limit = minOf(a.size, b.size)
        for (i in 0 until limit) {
            dot += (a[i] * b[i]).toDouble()
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    data class ScoredRecord(
        val record: EmbeddingIndexRecord,
        val score: Double
    )
}
