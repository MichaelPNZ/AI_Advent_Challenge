package com.pozyalov.ai_advent_challenge.network.mcp

class WorldBankTaskToolClient(
    scriptPath: String? = System.getProperty("ai.advent.worldbank.mcp.script")
        ?: System.getProperty("ai.advent.mcp.script")
        ?: "mcp/world-bank-server/run-world-bank-server.sh"
) : TaskToolClient by ScriptedTaskToolClient(
    displayName = "World Bank",
    scriptPath = scriptPath,
    missingServerMessage = "MCP сервер World Bank не настроен: проверьте переменную MCP_SERVER_SCRIPT или путь ai.advent.worldbank.mcp.script."
)
