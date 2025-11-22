package com.pozyalov.ai_advent_challenge.chat.pipeline

interface TripBriefingExecutor {
    val isAvailable: Boolean

    suspend fun prepareBriefing(
        locationQuery: String,
        departureDate: String? = null
    ): Result<PreparedTrip>

    suspend fun confirmTasks(prepared: PreparedTrip, saveToFile: Boolean): Result<TaskResult>

    data class TaskDraft(
        val title: String,
        val dueDate: String?
    )

    data class PreparedTrip(
        val locationName: String,
        val forecast: String,
        val departureDate: String?,
        val tasks: List<TaskDraft>
    )

    data class TaskResult(
        val createdTasks: List<String>,
        val summaryText: String,
        val savedPath: String?
    )

    object None : TripBriefingExecutor {
        override val isAvailable: Boolean = false
        override suspend fun prepareBriefing(
            locationQuery: String,
            departureDate: String?
        ): Result<PreparedTrip> =
            Result.failure(IllegalStateException("Trip briefing unavailable"))

        override suspend fun confirmTasks(
            prepared: PreparedTrip,
            saveToFile: Boolean,
        ): Result<TaskResult> = Result.failure(IllegalStateException("Trip briefing unavailable"))
    }
}
