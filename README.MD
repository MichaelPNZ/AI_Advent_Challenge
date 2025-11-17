# AI_Advent_Challenge_#4

### Android
To run the application on android device/emulator:  
 - open project in Android Studio and run imported android run configuration  

To build the application bundle:  
 - run `./gradlew :androidApp:assembleDebug`  
 - find `.apk` file in `androidApp/build/outputs/apk/debug/androidApp-debug.apk`  

### Desktop
Run the desktop application: `./gradlew :desktopApp:run`  
Run the desktop **hot reload** application: `./gradlew :desktopApp:hotRun --auto`  

### iOS
To run the application on iPhone device/simulator:  
 - Open `iosApp/iosApp.xcproject` in Xcode and run standard configuration  
 - Or use [Kotlin Multiplatform Mobile plugin](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform-mobile) for Android Studio  

### MCP tooling
The `core/network` module ships with `McpToolInspector`, a helper that can connect to any MCP server which communicates via stdio and print the tools it exposes.  
Provide the path to your MCP server entry point through the `MCP_SERVER_SCRIPT` environment variable and the desktop client will automatically run `printTools(...)` on startup.  
If the variable is omitted, the app looks for `mcp/github-server/run-github-server.sh` — place your wrapper script there or override the env var.

```kotlin
import com.pozyalov.ai_advent_challenge.network.mcp.McpToolInspector
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val inspector = McpToolInspector()
    inspector.printTools(
        serverCommand = McpToolInspector.guessCommandForScript(
            System.getenv("MCP_SERVER_SCRIPT") ?: error("Set MCP_SERVER_SCRIPT first")
        ),
        environment = mapOf("ANTHROPIC_API_KEY" to System.getenv("ANTHROPIC_API_KEY") ?: "")
    )
}
```

`printTools` executes the server process, runs the MCP handshake, and dumps each tool (with description + arguments) to stdout.  
Switch to `listTools` if you need the raw `List<McpToolDescriptor>` for custom UI or behavior.
