package com.pozyalov.ai_advent_challenge.network.mcp

import com.aallam.openai.api.chat.Tool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

interface TaskToolClient {
    val toolDefinitions: List<Tool>
    suspend fun execute(toolName: String, arguments: JsonObject): TaskToolResult?

    object None : TaskToolClient {
        override val toolDefinitions: List<Tool> = emptyList()
        override suspend fun execute(toolName: String, arguments: JsonObject): TaskToolResult? = null
    }
}

data class TaskToolResult(
    val text: String,
)

data class ToolSelectorOption(
    val id: String,
    val title: String,
    val description: String?,
    val toolNames: List<String>,
    val isAvailable: Boolean,
    val isEnabled: Boolean,
)

data class ToolSelectorState(
    val options: List<ToolSelectorOption> = emptyList(),
)

interface ToolSelector {
    val state: StateFlow<ToolSelectorState>
    fun setToolEnabled(optionId: String, enabled: Boolean)
}

object ToolSelectorStub : ToolSelector {
    private val mutableState = MutableStateFlow(ToolSelectorState())
    override val state: StateFlow<ToolSelectorState> = mutableState
    override fun setToolEnabled(optionId: String, enabled: Boolean) {
        // no-op
    }
}
