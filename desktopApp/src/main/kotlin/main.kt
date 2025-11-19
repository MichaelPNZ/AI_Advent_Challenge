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

fun main() {
    runBlocking { configureMcpScripts() }
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

private suspend fun configureMcpScripts() {
    configureScriptProperty(
        property = "ai.advent.worldbank.mcp.script",
        defaultPath = "mcp/world-bank-server/run-world-bank-server.sh",
        printTools = true
    )
    configureScriptProperty(
        property = "ai.advent.weather.mcp.script",
        defaultPath = "mcp/weather-server/run-weather-server.sh"
    )
    configureScriptProperty(
        property = "ai.advent.reminder.mcp.script",
        defaultPath = "mcp/reminder-server/run-reminder-server.sh"
    )
}

private suspend fun configureScriptProperty(
    property: String,
    defaultPath: String,
    printTools: Boolean = false
) {
    val configuredPath = System.getProperty(property)
        ?: System.getenv(property.replace('.', '_').uppercase())
        ?: defaultPath
    val scriptFile = resolveScriptFile(configuredPath)
    if (scriptFile == null || !scriptFile.exists()) {
        println("MCP server script not found for $property. Checked path: ${scriptFile?.absolutePath ?: configuredPath}")
        return
    }
    System.setProperty(property, scriptFile.absolutePath)
    if (printTools) {
        runCatching {
            McpToolInspector().printTools(
                serverCommand = McpToolInspector.guessCommandForScript(scriptFile.absolutePath)
            )
        }.onFailure { error ->
            println("Failed to list MCP tools: ${error.message}")
        }
    }
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
