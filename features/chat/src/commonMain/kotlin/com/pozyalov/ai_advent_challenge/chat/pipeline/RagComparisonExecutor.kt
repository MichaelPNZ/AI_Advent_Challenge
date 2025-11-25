package com.pozyalov.ai_advent_challenge.chat.pipeline

data class RagComparisonResult(
    val withoutRag: String,
    val withRag: String,
    val contextChunks: List<ContextChunk>
) {
    data class ContextChunk(
        val file: String,
        val text: String
    )
}

interface RagComparisonExecutor {
    val isAvailable: Boolean
    suspend fun compare(question: String, topK: Int = 3): Result<RagComparisonResult>

    object None : RagComparisonExecutor {
        override val isAvailable: Boolean = false
        override suspend fun compare(question: String, topK: Int): Result<RagComparisonResult> =
            Result.failure(IllegalStateException("RAG executor unavailable"))
    }
}
