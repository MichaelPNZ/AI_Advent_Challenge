package com.pozyalov.ai_advent_challenge.pipeline

import com.pozyalov.ai_advent_challenge.chat.pipeline.RagComparisonExecutor
import com.pozyalov.ai_advent_challenge.chat.pipeline.RagComparisonResult
import com.pozyalov.ai_advent_challenge.embedding.RagComparisonService

class DesktopRagComparisonExecutor(
    private val service: RagComparisonService
) : RagComparisonExecutor {
    override val isAvailable: Boolean = true
    override suspend fun compare(
        question: String,
        topK: Int
    ): Result<RagComparisonResult> = runCatching {
        service.compare(question, topK)
    }
}
