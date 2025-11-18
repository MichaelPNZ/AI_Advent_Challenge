package com.pozyalov.ai_advent_challenge.network.mcp

import com.aallam.openai.api.chat.Tool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

data class ToolClientEntry(
    val id: String,
    val title: String,
    val description: String?,
    val client: TaskToolClient,
    val alwaysAvailable: Boolean = false,
    val defaultEnabled: Boolean = false,
)

class MultiTaskToolClient(
    entries: List<ToolClientEntry>
) : TaskToolClient, ToolSelector {

    init {
        require(entries.isNotEmpty()) { "MultiTaskToolClient requires at least one entry." }
    }

    private val entries: List<ToolClientEntry> = entries.toList()
    private val entryLookup: Map<String, ToolClientEntry> = this.entries.associateBy { it.id }
    private val enabledIds: MutableSet<String> = this.entries
        .filter { it.defaultEnabled }
        .mapTo(mutableSetOf()) { it.id }

    private val _state = MutableStateFlow(buildState())
    override val state: StateFlow<ToolSelectorState> = _state

    override val toolDefinitions: List<Tool>
        get() = entries
            .filter { enabledIds.contains(it.id) }
            .flatMap { it.client.toolDefinitions }

    override suspend fun execute(toolName: String, arguments: JsonObject): TaskToolResult? {
        val owningEntry = entries
            .filter { enabledIds.contains(it.id) }
            .firstOrNull { entry ->
                entry.client.toolDefinitions.any { it.function.name == toolName }
            }
        if (owningEntry == null) {
            return TaskToolResult(
                "Инструмент $toolName не активирован. Включите соответствующий MCP сервер в настройках."
            )
        }
        return owningEntry.client.execute(toolName, arguments)
    }

    override fun setToolEnabled(optionId: String, enabled: Boolean) {
        if (!entryLookup.containsKey(optionId)) return
        val changed = if (enabled) enabledIds.add(optionId) else enabledIds.remove(optionId)
        if (!changed) return
        _state.value = buildState()
    }

    private fun buildState(): ToolSelectorState =
        ToolSelectorState(
            options = entries.map { entry ->
                entry.toOption(
                    enabled = enabledIds.contains(entry.id)
                )
            }
        )

    private fun ToolClientEntry.toOption(enabled: Boolean): ToolSelectorOption =
        ToolSelectorOption(
            id = id,
            title = title,
            description = description,
            toolNames = client.toolDefinitions.map { it.function.name },
            isAvailable = alwaysAvailable || client.toolDefinitions.isNotEmpty(),
            isEnabled = enabled
        )
}
