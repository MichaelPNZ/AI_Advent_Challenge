package com.pozyalov.ai_advent_challenge.chat.pipeline

interface DocPipelineExecutor {
    val isAvailable: Boolean
    suspend fun search(query: String): Result<List<Match>>
    suspend fun summarize(match: Match, outputName: String? = null): Result<ResultPayload>

    data class Match(
        val filePath: String,
        val fileName: String,
        val snippet: String
    )

    data class ResultPayload(
        val summary: String,
        val savedPath: String?
    )

    object None : DocPipelineExecutor {
        override val isAvailable: Boolean = false
        override suspend fun search(query: String): Result<List<Match>> =
            Result.failure(IllegalStateException("Doc pipeline executor unavailable"))

        override suspend fun summarize(match: Match, outputName: String?): Result<ResultPayload> =
            Result.failure(IllegalStateException("Doc pipeline executor unavailable"))
    }
}
