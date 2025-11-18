package com.pozyalov.ai_advent_challenge.network.mcp

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.CompatibilityCallToolResult
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import kotlin.concurrent.thread

/**
 * Represents a single MCP tool exposed by the server.
 *
 * @property name Unique identifier of the tool.
 * @property title Optional human friendly name (annotations/title takes precedence when present).
 * @property description Tool description provided by the server.
 * @property argumentNames The names of the available arguments declared by the schema.
 * @property requiredArguments Arguments marked as required by the schema.
 */
data class McpToolDescriptor(
    val name: String,
    val title: String?,
    val description: String?,
    val inputSchema: Tool.Input,
    val outputSchema: Tool.Output?,
    val annotations: ToolAnnotations?,
) {
    val argumentNames: Set<String> = inputSchema.properties.keys
    val requiredArguments: List<String> = inputSchema.required ?: emptyList()
    val displayName: String = title ?: name
}

/**
 * Minimal client that connects to an MCP server over stdio, asks it for the list of tools
 * and optionally prints the result.
 */
class McpToolInspector(
    private val clientInfo: Implementation = Implementation(
        name = "ai-advent-challenge-client",
        version = "1.0.0",
    ),
) {

    /**
     * Connects to an MCP server command (e.g. ["python3", "server.py"]) and returns the tools it exposes.
     *
     * @param serverCommand A full command line to execute. Use [guessCommandForScript] if you only have a script path.
     * @param workingDirectory Optional directory for launching the server command.
     * @param environment Environment variables that should be appended to the process.
     * @param stderrLogger Callback for streaming server stderr output (helps debugging connection issues).
     */
    suspend fun listTools(
        serverCommand: List<String>,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        stderrLogger: (String) -> Unit = { line -> println("[mcp-server] $line") },
    ): List<McpToolDescriptor> = withClient(
        serverCommand = serverCommand,
        workingDirectory = workingDirectory,
        environment = environment,
        stderrLogger = stderrLogger,
    ) { client ->
        client.listTools().tools.map(Tool::toDescriptor)
    }

    /**
     * Same as [listTools] but immediately prints the result to stdout so you can quickly verify the connection.
     */
    suspend fun printTools(
        serverCommand: List<String>,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
    ) {
        val tools = listTools(
            serverCommand = serverCommand,
            workingDirectory = workingDirectory,
            environment = environment,
        )

        if (tools.isEmpty()) {
            println("MCP server did not expose any tools.")
            return
        }

        println("MCP server exposed ${tools.size} tool(s):")
        tools.forEachIndexed { index, tool ->
            println("${index + 1}. ${tool.displayName}")
            tool.description?.takeIf { it.isNotBlank() }?.let { println("   $it") }
            if (tool.argumentNames.isNotEmpty()) {
                println("   Arguments: ${tool.argumentNames.joinToString()}")
            }
            if (tool.requiredArguments.isNotEmpty()) {
                println("   Required: ${tool.requiredArguments.joinToString()}")
            }
        }
    }

    suspend fun callTool(
        serverCommand: List<String>,
        toolName: String,
        arguments: JsonObject,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        stderrLogger: (String) -> Unit = { line -> println("[mcp-server] $line") },
    ): CallToolResult? = withClient(
        serverCommand = serverCommand,
        workingDirectory = workingDirectory,
        environment = environment,
        stderrLogger = stderrLogger,
    ) { client ->
        client.callTool(name = toolName, arguments = arguments)?.toStandardResult()
    }

    private suspend fun <T> withClient(
        serverCommand: List<String>,
        workingDirectory: File?,
        environment: Map<String, String>,
        stderrLogger: (String) -> Unit,
        block: suspend (Client) -> T,
    ): T {
        require(serverCommand.isNotEmpty()) { "serverCommand must not be empty" }

        val processBuilder = ProcessBuilder(serverCommand)
            .redirectErrorStream(false)
        workingDirectory?.let(processBuilder::directory)
        val env = processBuilder.environment()
        environment.forEach { (key, value) -> env[key] = value }

        val process = processBuilder.start()

        val stderrForwarder = thread(
            start = true,
            isDaemon = true,
            name = "mcp-server-stderr-${process.pid()}",
        ) {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach(stderrLogger)
            }
        }

        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
        )
        val client = Client(clientInfo = clientInfo)

        return try {
            client.connect(transport)
            block(client)
        } finally {
            runCatching { client.close() }
            process.destroy()
            stderrForwarder.join(500)
        }
    }

    companion object {
        /**
         * Builds a full command for a script by guessing the interpreter from the file extension.
         * Falls back to running the script directly if the extension is unknown.
         */
        fun guessCommandForScript(scriptPath: String): List<String> {
            val extension = scriptPath.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)
            val prefix = when (extension) {
                "js", "mjs", "cjs" -> listOf("node")
                "py" -> listOf(if (isWindows) "python" else "python3")
                "jar" -> listOf("java", "-jar")
                "sh", "bash" -> listOf(if (isWindows) "bash" else "bash")
                else -> emptyList()
            }
            return prefix + scriptPath
        }
    }
}

private fun Tool.toDescriptor(): McpToolDescriptor = McpToolDescriptor(
    name = name,
    title = annotations?.title ?: title,
    description = description,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    annotations = annotations,
)

private fun CallToolResultBase.toStandardResult(): CallToolResult = when (this) {
    is CallToolResult -> this
    is CompatibilityCallToolResult -> CallToolResult(
        content = content,
        structuredContent = structuredContent,
        isError = isError,
        _meta = _meta,
    )
}
