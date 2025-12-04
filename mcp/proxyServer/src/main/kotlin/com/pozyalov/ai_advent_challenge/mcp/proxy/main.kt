package com.pozyalov.ai_advent_challenge.mcp.proxy

import java.io.File

fun main() {
    println("🚀 Starting MCP HTTP Proxy Server...")

    val projectRoot = findProjectRoot()
    println("📁 Project root: $projectRoot")

    val servers = mapOf(
        "weather" to McpHttpProxyServer.ServerConfig(
            command = listOf("sh", "$projectRoot/mcp/weather-server/run-weather-server.sh"),
            displayName = "Weather.gov Forecast"
        ),
        "reminder" to McpHttpProxyServer.ServerConfig(
            command = listOf("sh", "$projectRoot/mcp/reminder-server/run-reminder-server.sh"),
            displayName = "Reminder Tasks"
        ),
        "chat-summary" to McpHttpProxyServer.ServerConfig(
            command = listOf("sh", "$projectRoot/mcp/chat-summary-server/run-chat-summary-server.sh"),
            displayName = "Chat Summary"
        ),
        "doc-pipeline" to McpHttpProxyServer.ServerConfig(
            command = listOf("sh", "$projectRoot/mcp/doc-pipeline-server/run-doc-pipeline-server.sh"),
            displayName = "Document Pipeline"
        ),
        "support-ticket" to McpHttpProxyServer.ServerConfig(
            command = listOf("sh", "$projectRoot/mcp/support-ticket-server/run-support-ticket-server.sh"),
            displayName = "Support Tickets"
        )
    )

    val port = System.getenv("MCP_PROXY_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("MCP_PROXY_HOST") ?: "0.0.0.0"

    println("🔧 Configured ${servers.size} MCP servers:")
    servers.forEach { (name, config) ->
        println("   • $name (${config.displayName})")
    }

    println()
    println("🌐 Starting server on $host:$port")
    println("📋 Available endpoints:")
    println("   GET  /health                           - Health check")
    println("   GET  /mcp/servers                      - List all servers")
    println("   GET  /mcp/{serverName}/tools           - List tools for server")
    println("   POST /mcp/{serverName}/tool/{toolName} - Execute tool")
    println()
    println("✅ Server ready!")
    println()

    McpHttpProxyServer(servers, port, host).start()
}

private fun findProjectRoot(): String {
    val currentDir = File(".").absoluteFile
    var dir: File? = currentDir

    while (dir != null) {
        if (File(dir, "settings.gradle.kts").exists() && File(dir, "mcp").exists()) {
            return dir.absolutePath
        }
        dir = dir.parentFile
    }

    // Fallback: если запускаем из IDE, текущая директория уже может быть root
    if (File(currentDir, "mcp/weather-server").exists()) {
        return currentDir.absolutePath
    }

    error("Could not find project root. Please run from project directory or set MCP_PROJECT_ROOT env var.")
}
