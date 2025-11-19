package com.pozyalov.ai_advent_challenge.network.mcp

class ReminderTaskToolClient(
    scriptPath: String? = System.getProperty("ai.advent.reminder.mcp.script")
        ?: "mcp/reminder-server/run-reminder-server.sh"
) : TaskToolClient by ScriptedTaskToolClient(
    displayName = "Reminder",
    scriptPath = scriptPath,
    missingServerMessage = "MCP сервер Reminder не найден: укажите путь через ai.advent.reminder.mcp.script или соберите mcp/reminder-server."
)
