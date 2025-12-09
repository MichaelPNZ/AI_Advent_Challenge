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

### Локальная LLM (офлайн, Ollama)
- Установите и запустите Ollama: `brew install ollama && ollama serve`
- Скачайте модель: `ollama pull llama3.2`
- Запустите десктоп: `./gradlew :desktopApp:run` (модель по умолчанию — локальная `ollama:llama3.2`, OpenAI ключ не нужен)
- При желании переключайтесь на другие модели в настройках чата.

### MCP tooling
The `core/network` module ships with `McpToolInspector`, a helper that can connect to any MCP server which communicates via stdio and print the tools it exposes.  
Provide the path to your MCP server entry point through the `MCP_SERVER_SCRIPT` environment variable and the desktop client will automatically run `printTools(...)` on startup.  
If the variable is omitted, the app looks for `mcp/world-bank-server/run-world-bank-server.sh` — place your wrapper script there or override the env var.

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

В настройках чата есть блок «Инструменты MCP»: это список переключателей. Можно держать активным сразу несколько серверов (например, World Bank + Weather) — их функции будут объявлены модели одновременно. Выключите все переключатели, если хотите, чтобы агент отвечал только силой LLM без дополнительных данных.

#### World Bank API

В репозитории ранее был пример MCP сервера World Bank, сейчас он удалён.

1. Соберите бинарь с помощью `./gradlew :mcp:worldBankServer:installDist` (нужно один раз).
2. При необходимости запустите `./mcp/world-bank-server/run-world-bank-server.sh`, чтобы убедиться, что сервер отвечает (никаких токенов не требуется).
3. Укажите путь до скрипта через переменную `MCP_SERVER_SCRIPT` **или** просто оставьте значение по умолчанию (`mcp/world-bank-server/run-world-bank-server.sh`) — десктопный клиент найдёт его автоматически и сохранит в `ai.advent.mcp.script`.
4. Инструмент World Bank удалён; доступные инструменты см. в конфигурации десктопного приложения.

#### Weather API (api.weather.gov)

Ещё один пример MCP сервера находится в `mcp/weatherServer`. Он повторяет референс из [modelcontextprotocol/sdk](https://github.com/modelcontextprotocol/sdk) и оборачивает открытый API Национальной метеослужбы США (NWS).

1. Один раз соберите бинарь: `./gradlew :mcp:weatherServer:installDist`.
2. Для запуска используйте вспомогательный скрипт `./mcp/weather-server/run-weather-server.sh` или команду `java -jar mcp/weatherServer/build/libs/weatherServer-1.0.0-all.jar` (если соберёте теневой JAR).
3. Инструмент, который отдаёт сервер — `weather_get_forecast`. Аргументы:
   - `latitude` (обязательно) — широта в градусах;
   - `longitude` (обязательно) — долгота;
   - `periods` (опционально) — сколько периодов прогноза вернуть (1..8, по умолчанию 4).
4. Чтобы подключить сервер к нашему агенту, укажите `MCP_SERVER_SCRIPT=./mcp/weather-server/run-weather-server.sh` перед запуском десктопного клиента. После этого LLM увидит новый инструмент и сможет автоматически запрашивать прогноз по приведённой схеме (например: «Погода в Сиэтле завтра?» — модель сама подставит координаты и вызовет MCP).
