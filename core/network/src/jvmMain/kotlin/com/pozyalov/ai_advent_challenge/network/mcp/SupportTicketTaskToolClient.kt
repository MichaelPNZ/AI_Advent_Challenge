package com.pozyalov.ai_advent_challenge.network.mcp

import com.aallam.openai.api.chat.Tool
import java.io.File
import kotlinx.serialization.json.JsonObject

class SupportTicketTaskToolClient(
    scriptPath: String? = System.getProperty("ai.advent.support.mcp.script")
        ?: "mcp/support-ticket-server/run-support-ticket-server.sh",
    storageFile: File = SupportTicketStore.defaultFile(),
    usersFile: File? = SupportUserStore.defaultFile()
) : TaskToolClient {

    private val scriptedClient = scriptPath?.let {
        ScriptedTaskToolClient(
            displayName = "Support Ticket",
            scriptPath = it,
            missingServerMessage = "MCP сервер Support Ticket не найден: укажите путь через ai.advent.support.mcp.script или соберите mcp/supportTicketServer."
        )
    }

    private val localClient = LocalSupportTicketToolClient(
        ticketStore = SupportTicketStore(storageFile),
        userStore = SupportUserStore(usersFile)
    )

    override val toolDefinitions: List<Tool>
        get() {
            val scripted = scriptedClient?.toolDefinitions.orEmpty()
            if (scripted.isEmpty()) return localClient.toolDefinitions
            val scriptedNames = scripted.map { it.function.name }.toSet()
            val localExtras = localClient.toolDefinitions.filterNot { scriptedNames.contains(it.function.name) }
            return scripted + localExtras
        }

    override suspend fun execute(toolName: String, arguments: JsonObject) =
        when {
            scriptedClient?.toolDefinitions.orEmpty().any { it.function.name == toolName } ->
                scriptedClient?.execute(toolName, arguments)
                    ?: TaskToolResult("Инструмент $toolName недоступен.")
            localClient.toolDefinitions.any { it.function.name == toolName } ->
                localClient.execute(toolName, arguments)
            else -> TaskToolResult("Инструмент $toolName не найден.")
        }
}
