package com.pozyalov.ai_advent_challenge.chat.pipeline

interface EmbeddingIndexExecutor {
    val isAvailable: Boolean
    suspend fun buildIndex(path: String, outputPath: String? = null): Result<String>

    object None : EmbeddingIndexExecutor {
        override val isAvailable: Boolean = false
        override suspend fun buildIndex(path: String, outputPath: String?): Result<String> =
            Result.failure(IllegalStateException("Embedding index executor unavailable"))
    }
}
