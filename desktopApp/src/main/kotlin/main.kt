import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pozyalov.ai_advent_challenge.App
import com.pozyalov.ai_advent_challenge.di.desktopAppModule
import com.pozyalov.ai_advent_challenge.di.initKoin
import com.pozyalov.ai_advent_challenge.initLogs
import com.pozyalov.ai_advent_challenge.network.mcp.McpToolInspector
import java.awt.Dimension
import java.io.File
import kotlinx.coroutines.runBlocking

private val MCP_SERVER_SCRIPT: String? =
    System.getenv("MCP_SERVER_SCRIPT")?.takeIf { it.isNotBlank() }
        ?: "mcp/world-bank-server/run-world-bank-server.sh"

fun main() {
    runBlocking { printMcpToolsIfConfigured() }
    initLogs()
    initKoin(appModule = desktopAppModule())

    application {
        Window(
            title = "AI_Advent_Challenge_#4",
            state = rememberWindowState(width = 1600.dp, height = 1200.dp),
            onCloseRequest = ::exitApplication,
        ) {
            window.minimumSize = Dimension(800, 600)
            App()
        }
    }
}

private suspend fun printMcpToolsIfConfigured() {
    val configuredPath = MCP_SERVER_SCRIPT?.takeIf { it.isNotBlank() } ?: return
    val scriptFile = resolveScriptFile(configuredPath)
    if (scriptFile == null || !scriptFile.exists()) {
        println("MCP server script not found. Checked path: ${scriptFile?.absolutePath ?: configuredPath}")
        return
    }

    runCatching {
        McpToolInspector()
            .printTools(
                serverCommand = McpToolInspector.guessCommandForScript(scriptFile.absolutePath),
            )
    }.onFailure { error ->
        println("Failed to list MCP tools: ${error.message}")
    }
    System.setProperty("ai.advent.mcp.script", scriptFile.absolutePath)
}

private fun resolveScriptFile(path: String): File? {
    val initial = File(path)
    if (initial.isAbsolute) return initial

    val searchRoots = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { current ->
        current.parentFile?.takeIf { it.exists() }
    }.take(5) // avoid traversing the entire filesystem

    searchRoots.forEach { root ->
        val candidate = File(root, path)
        if (candidate.exists()) return candidate
    }

    return File(System.getProperty("user.dir"), path)
}
