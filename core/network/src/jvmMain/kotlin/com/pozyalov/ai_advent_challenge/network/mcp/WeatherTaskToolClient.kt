package com.pozyalov.ai_advent_challenge.network.mcp

class WeatherTaskToolClient(
    scriptPath: String? = System.getProperty("ai.advent.weather.mcp.script")
        ?: "mcp/weather-server/run-weather-server.sh"
) : TaskToolClient by ScriptedTaskToolClient(
    displayName = "Weather",
    scriptPath = scriptPath,
    missingServerMessage = "MCP сервер Weather.gov не найден: укажите путь через ai.advent.weather.mcp.script или соберите mcp/weather-server."
)
