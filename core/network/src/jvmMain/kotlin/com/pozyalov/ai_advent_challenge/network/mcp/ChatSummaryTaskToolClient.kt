package com.pozyalov.ai_advent_challenge.network.mcp

class ChatSummaryTaskToolClient(
    scriptPath: String? = System.getProperty("ai.advent.chatSummary.mcp.script")
        ?: "mcp/chat-summary-server/run-chat-summary-server.sh"
) : TaskToolClient by ScriptedTaskToolClient(
    displayName = "Chat Daily Summaries",
    scriptPath = scriptPath,
    missingServerMessage = "MCP сервер Chat Summary не найден: укажите путь через ai.advent.chatSummary.mcp.script или соберите mcp/chat-summary-server."
)
