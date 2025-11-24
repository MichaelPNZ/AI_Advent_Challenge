package com.pozyalov.ai_advent_challenge.pipeline

import com.pozyalov.ai_advent_challenge.chat.pipeline.EmbeddingIndexExecutor
import com.pozyalov.ai_advent_challenge.embedding.EmbeddingIndexService
import java.io.File

class DesktopEmbeddingIndexExecutor(
    private val service: EmbeddingIndexService
) : EmbeddingIndexExecutor {
    override val isAvailable: Boolean = true

    override suspend fun buildIndex(path: String, outputPath: String?): Result<String> = runCatching {
        val target = File(path)
        val outFile = outputPath?.let { File(it) }
        val result = service.buildIndex(target, outFile).getOrThrow()
        result.absolutePath
    }
}
