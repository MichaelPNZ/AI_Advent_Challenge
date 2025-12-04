# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AI Advent Challenge is a Kotlin Multiplatform chat assistant application with Model Context Protocol (MCP) tool integration, RAG (Retrieval Augmented Generation), technical support assistant, and multi-platform support (Android, iOS, Desktop). The app enables conversations with LLMs while accessing external tools via MCP servers for weather data, reminders, document processing, support tickets, and more.

## Build & Development Commands

### Building the project
```bash
# Build all modules
./gradlew build

# Build Android APK
./gradlew :androidApp:assembleDebug
# Find the APK at: androidApp/build/outputs/apk/debug/androidApp-debug.apk

# Install MCP server distributions (required for MCP functionality)
./gradlew :mcp:weatherServer:installDist
./gradlew :mcp:reminderServer:installDist
./gradlew :mcp:chatSummaryServer:installDist
./gradlew :mcp:docPipelineServer:installDist
./gradlew :mcp:supportTicketServer:installDist

# Build MCP HTTP Proxy (for multiplatform MCP access)
./gradlew :mcp:proxyServer:installDist
```

### Running the application

**Desktop (JVM):**
```bash
# Option 1: Local stdio mode (direct MCP server access, default)
./gradlew :desktopApp:run

# Option 2: HTTP proxy mode (requires running proxy server)
# First, start the MCP HTTP Proxy:
./mcp/proxyServer/run-proxy-server.sh
# Then in another terminal:
./gradlew :desktopApp:run -Dmcp.mode=http

# Desktop app with hot reload (auto-restart on changes)
./gradlew :desktopApp:hotRun --auto
```

**Android:**
```bash
# 1. Start MCP HTTP Proxy (required for Android):
./mcp/proxyServer/run-proxy-server.sh

# 2. Open project in Android Studio and run the android configuration
# Android emulator will automatically connect to http://10.0.2.2:8080
# For real devices, configure proxy URL in AndroidModules.kt
```

**iOS:**
```bash
# 1. Start MCP HTTP Proxy (required for iOS):
./mcp/proxyServer/run-proxy-server.sh

# 2. Open iosApp/iosApp.xcproject in Xcode and run standard configuration
# Or use the Kotlin Multiplatform Mobile plugin for Android Studio
# iOS simulator will connect to http://localhost:8080
```

### Testing
```bash
# Run all tests
./gradlew allTests

# Run JVM tests only
./gradlew jvmTest

# Run iOS simulator tests
./gradlew iosSimulatorArm64Test

# Run Android connected device tests
./gradlew connectedDebugAndroidTest

# Run specific module tests
./gradlew :core:network:test
./gradlew :features:chat:test
```

### Linting
```bash
# Run lint checks
./gradlew lint

# Run lint and apply safe fixes
./gradlew lintFix
```

## Architecture Overview

### Module Structure

**Platform Apps:**
- `androidApp/` - Android application entry point
- `iosApp/` - iOS application entry point (Xcode project)
- `desktopApp/` - Desktop (JVM) application entry point with platform-specific features:
  - Embedding indexing (RAG) via Ollama
  - MCP server integration and configuration
  - File system access for document indexing

**Shared Code:**
- `sharedUI/` - Shared UI layer with Compose Multiplatform
  - `App.kt` - Root composable
  - `di/` - Koin dependency injection modules (platform-specific)
  - `root/` - Root navigation component
  - `theme/` - Shared theme definitions

**Feature Modules:**
- `features/chat/` - Chat screen feature
  - `component/ChatComponent.kt` - Chat business logic, message handling, LLM interaction
  - `ui/ChatScreen.kt` - Chat UI with message list, input field, settings
  - RAG integration with document retrieval and source citations
  - MCP tool execution integration

- `features/chatlist/` - Chat list/threads management
  - `component/ChatListComponent.kt` - Thread list logic, indexing UI
  - Document indexing trigger (folder selection)

**Core Modules:**
- `core/network/` - Networking and MCP client (multiplatform)
  - `commonMain/` - Shared network logic and HTTP-based MCP client
    - `network/mcp/TaskToolClient.kt` - Platform-agnostic interface for MCP tool integration
    - `network/mcp/HttpTaskToolClient.kt` - HTTP client for MCP servers (works on all platforms)
    - `network/mcp/TaskToolClientFactory.kt` - Platform-specific factory (expect/actual)
  - `jvmMain/` - JVM-specific implementations
    - `network/mcp/McpToolInspector.kt` - Helper for MCP server introspection (stdio transport)
    - Stdio-based MCP tool clients (WeatherTaskToolClient, ReminderTaskToolClient, etc.)
  - `androidMain/` - Android-specific factory (uses HTTP proxy)
  - `iosMain/` - iOS-specific factory (uses HTTP proxy)

- `core/database/` - Room database for chat history
  - Multiplatform Room setup with chat threads and messages
  - `ChatThreadDataSource` and `ChatHistoryDataSource` abstractions

**MCP Infrastructure:**
- `mcp/proxyServer/` - **MCP HTTP Proxy** (Ktor server)
  - Translates HTTP requests → stdio MCP calls
  - Enables Android/iOS to use MCP servers
  - REST API: `/health`, `/mcp/servers`, `/mcp/{server}/tools`, `/mcp/{server}/tool/{tool}`
  - Can run locally, in Docker, or on VPS
  - See [docs/MCP_HTTP_PROXY.md](docs/MCP_HTTP_PROXY.md) for details

**MCP Servers (Kotlin/JVM):**
- `mcp/weatherServer/` - US National Weather Service API wrapper (`weather_get_forecast` tool)
- `mcp/reminderServer/` - Task/reminder storage
- `mcp/chatSummaryServer/` - Daily chat digests
- `mcp/docPipelineServer/` - Document search and summarization
- `mcp/supportTicketServer/` - Technical support tickets management (create, get, update, list, add_comment, stats)
- Each has a `run-*.sh` script in corresponding `-server/` directory that launches the compiled JAR

### Key Architectural Patterns

**Dependency Injection:**
- Uses Koin for DI across all platforms
- Platform-specific modules in `di/*Modules.kt` files
- `desktopApp/src/main/kotlin/com/pozyalov/ai_advent_challenge/di/DesktopModules.kt` registers MCP tool clients as `MultiTaskToolClient` with toggleable tool entries

**Component-Based Architecture:**
- Decompose library for navigation and lifecycle
- Each feature has a `Component` interface/implementation for business logic
- Components are platform-agnostic and testable

**MCP Integration (Multiplatform):**
- **Two modes of operation:**
  - **LOCAL_STDIO** (JVM only): Direct stdio communication with MCP servers
  - **HTTP_PROXY** (all platforms): HTTP requests to MCP HTTP Proxy server
- **Desktop (JVM):**
  - Default: LOCAL_STDIO mode (direct MCP server access via `McpToolInspector`)
  - Optional: HTTP_PROXY mode (set `mcp.mode=http`)
  - Locates server scripts via system properties or defaults
- **Android/iOS:**
  - Always use HTTP_PROXY mode (stdio not available)
  - Android emulator: connects to `http://10.0.2.2:8080`
  - iOS simulator: connects to `http://localhost:8080`
  - Real devices: configure IP address in platform DI modules
- **MCP HTTP Proxy (`mcp/proxyServer/`):**
  - Ktor server that wraps stdio MCP servers with HTTP API
  - Manages MCP server lifecycle (process spawn/cleanup)
  - Converts MCP Tool definitions to OpenAI Tool format
  - Run locally: `./mcp/proxyServer/run-proxy-server.sh`
  - Docker: `docker-compose up`
  - VPS: deploy with nginx + SSL
- **Tool execution flow:**
  1. LLM generates tool call (OpenAI function calling format)
  2. `TaskToolClient.execute()` is invoked
  3. JVM (stdio): direct call via `McpToolInspector` → stdio → MCP server
  4. Android/iOS (HTTP): HTTP POST to `/mcp/{server}/tool/{tool}` → proxy → stdio → MCP server
  5. Result returned to LLM for next reasoning step

**RAG (Retrieval Augmented Generation):**
- Desktop-only feature using Ollama with `nomic-embed-text` model
- `desktopApp/.../embedding/EmbeddingIndexService.kt` - indexes documents (txt, md, pdf, docx, code files)
- `desktopApp/.../embedding/RagComparisonService.kt` - cosine similarity search over indexed chunks
- Index stored at `~/.ai_advent/embedding_index/index.jsonl` (append-only)
- RAG toggle in chat settings enables source-cited responses
- Retrieves top-K (3) chunks filtered by relevance threshold (configurable)

**State Management:**
- Kotlin StateFlow for reactive state
- Immutable data models for UI state
- Coroutines for async operations

## Development Workflow

### Adding a New MCP Tool

1. Create new module under `mcp/` (e.g., `mcp/newToolServer/`)
2. Implement MCP server main class following Model Context Protocol stdio spec
3. Add Gradle module to `settings.gradle.kts`
4. Create `run-new-tool-server.sh` script in `mcp/new-tool-server/`
5. Implement `NewToolTaskToolClient` in `core/network/src/jvmMain/kotlin/`
6. Register in `desktopApp/.../di/DesktopModules.kt` as new `ToolClientEntry`
7. Add script property configuration in `desktopApp/src/main/kotlin/main.kt`
8. Run `./gradlew :mcp:newToolServer:installDist`

### Debugging MCP Servers

- Set `MCP_SERVER_SCRIPT` environment variable to override default script path
- `McpToolInspector().printTools()` dumps available tools to console on startup
- Check that `./gradlew :mcp:<serverName>:installDist` has been run
- Verify script is executable and paths are correct in run script

### Working with RAG

**Prerequisites:**
- Ensure `ollama serve` is running
- Download model: `ollama pull nomic-embed-text`

**Indexing Documents:**
1. Launch desktop app
2. Click "Индексировать" in chat list
3. Select folder to index (supports txt/md/pdf/docx/kt/kts/java/xml/json/yaml/yml/gradle/properties/csv/sh)
4. Index appends to `~/.ai_advent/embedding_index/index.jsonl`

**Using RAG in Chat:**
1. Open chat settings
2. Toggle RAG mode
3. Set relevance threshold (0.0–1.0, recommend 0.25)
4. Questions will retrieve relevant chunks and include `[Источник: <filename>]` citations

### Testing Strategy

- Unit tests in `*Test.kt` files within each module's test source set
- Feature modules should have tests for component logic
- Network module should mock MCP responses
- Use `runBlocking` for suspending function tests
- Prefer testing components over UI composables

## PR Review Automation

### Overview

The project includes an automated PR review system that combines AI (OpenAI GPT-4) with RAG for code analysis:
- Analyzes PR changes using git diff via GitTaskToolClient
- Uses RAG to find relevant context from documentation and code
- Generates structured reviews with findings, bugs, and improvement suggestions
- Automatically posts results as PR comments via GitHub Actions

### Running PR Review

**Locally:**
```bash
# Quick review without RAG
export OPENAI_API_KEY="your-key"
./gradlew :desktopApp:runPrReview -Pbase=origin/main -PuseRag=false

# Full review with RAG (requires Ollama with nomic-embed-text)
ollama serve
ollama pull nomic-embed-text
./gradlew :desktopApp:runPrReview -Pbase=origin/main -PuseRag=true -PminScore=0.25
```

**Parameters:**
- `base` - base branch for comparison (default: origin/main)
- `useRag` - enable RAG context (default: true)
- `model` - OpenAI model ID (default: gpt-4o)
- `minScore` - RAG relevance threshold (default: 0.25)
- `outputFormat` - text|markdown|json (default: markdown)

**Environment variables:**
- `PR_REVIEW_BASE`, `PR_REVIEW_USE_RAG`, `PR_REVIEW_MODEL`, `PR_REVIEW_MIN_SCORE`, `PR_REVIEW_OUTPUT_FORMAT`
- `OPENAI_API_KEY` - required for OpenAI API (same key as used in main app)

### GitHub Actions Integration

**Workflow:** `.github/workflows/pr-review.yml`
- Triggers on PR open/update/reopen
- Installs Ollama and nomic-embed-text model
- Builds project and runs review with RAG
- Posts results as PR comment (updates existing comment if present)
- Saves artifacts: review report and logs

**Required secret:** `OPENAI_API_KEY` in repository settings (same key as used for main app)

### Architecture

**PrReviewService** (`desktopApp/.../review/PrReviewService.kt`)
- Main service coordinating review process
- `reviewPullRequest()` - full review with RAG context
- `quickReview()` - fast review without RAG
- Uses GitTaskToolClient for diff, EmbeddingIndexService for context, GenerateChatReplyUseCase for analysis

**PrReviewRunner** (`desktopApp/.../review/PrReviewRunner.kt`)
- CLI entry point for review execution
- Parses command-line args and environment variables
- Formats output (text/markdown/json)

**Integration points:**
- Registered in `desktopApp/.../di/DesktopModules.kt`
- Gradle task: `runPrReview` in `desktopApp/build.gradle.kts`
- Git operations via GitTaskToolClient (git_diff, git_show_file, git_branch)

**Review process:**
1. Get diff via GitTaskToolClient.execute("git_diff")
2. Extract changed files and summarize changes
3. Search relevant context via EmbeddingIndexService.searchScored()
4. Build prompt with diff + context
5. Generate review via GenerateChatReplyUseCase
6. Return structured result with summary, confidence, metrics

See [docs/PR_REVIEW.md](docs/PR_REVIEW.md) for detailed documentation.

## Configuration & Environment

**System Properties (Desktop):**
- (Удалено) `ai.advent.worldbank.mcp.script`
- `ai.advent.weather.mcp.script` - Path to Weather MCP server script
- `ai.advent.reminder.mcp.script` - Path to Reminder MCP server script

**Environment Variables:**
- Can use env vars instead of system properties (convert dots to underscores and uppercase)
- Example: `AI_ADVENT_WORLDBANK_MCP_SCRIPT`

**Default Paths:**
- MCP scripts auto-discovered at `mcp/<server-name>-server/run-<server-name>-server.sh`
- Chat database: platform-specific (Android: app data, Desktop: user home, iOS: documents)
- Embedding index: `~/.ai_advent/embedding_index/index.jsonl` (Desktop only)

## Platform-Specific Notes

**Android:**
- Minimum SDK determined by gradle config
- Database stored in app internal storage
- No MCP or RAG support

**iOS:**
- Requires Xcode for building and running
- Swift interop for iOS-specific features
- No MCP or RAG support

**Desktop (JVM):**
- Java 17+ required
- Full MCP and RAG support
- File system access for document indexing
- Window size: 1600x1200 default, 800x600 minimum

## Technical Support Assistant

### Overview

AI Advent Challenge includes a fully functional technical support assistant that combines RAG and MCP tools to:
- Answer user questions based on documentation
- Create and manage support tickets
- Track issues and provide statistics

### Quick Setup

1. **Install Ollama (for RAG embeddings):**
   ```bash
   # macOS
   brew install ollama
   ollama serve
   ollama pull nomic-embed-text
   ```

2. **Build Support Ticket Server:**
   ```bash
   ./gradlew :mcp:supportTicketServer:installDist
   ```

3. **Index FAQ Documentation:**
   - Launch desktop app: `./gradlew :desktopApp:run`
   - Click "Индексировать" in chat list
   - Select `docs/support-faq` folder

4. **Configure Chat for Support:**
   - Create new chat
   - Open settings (⚙️)
   - Select role: "Техподдержка"
   - Enable RAG mode (threshold: 0.25)
   - Enable MCP tool: "Support Ticket"

### Architecture

**Components:**
- `mcp/supportTicketServer/` - MCP server for ticket management
- `core/network/src/jvmMain/.../SupportTicketTaskToolClient.kt` - Client integration
- `features/chat/.../ChatRoleCatalog.kt` - Support assistant role with specialized prompt
- `docs/support-faq/` - FAQ documentation (auth, RAG, build, MCP tools)

**Data Storage:**
- Tickets: `~/.ai_advent/support_tickets.json`
- RAG Index: `~/.ai_advent/embedding_index/index.jsonl`

**MCP Tools:**
- `support_ticket_create` - Create new ticket (userId, title, description, category, priority)
- `support_ticket_get` - Get ticket details by ID
- `support_ticket_update` - Update ticket status (open/in_progress/resolved/closed)
- `support_ticket_list` - List tickets with filters (userId, status, category, limit)
- `support_ticket_add_comment` - Add comment to ticket (author, text)
- `support_ticket_stats` - Get overall statistics

**Categories:** auth, payment, bug, feature, other
**Priorities:** low, normal, high, critical

### Usage Examples

**Answer from Documentation:**
```
User: Почему не работает авторизация?
Assistant: [Provides solution citing FAQ sources]
```

**Create Ticket:**
```
User: Создай тикет от user123 про проблему со сборкой
Assistant: ✓ Тикет создан: #abc123
```

**List Tickets:**
```
User: Покажи открытые тикеты
Assistant: Открытые тикеты (3): ...
```

### Documentation

- [Full Support Assistant Guide](docs/SUPPORT_ASSISTANT.md)
- [FAQ: Auth Issues](docs/support-faq/auth-issues.md)
- [FAQ: RAG Setup](docs/support-faq/rag-setup.md)
- [FAQ: Build Issues](docs/support-faq/build-issues.md)
- [FAQ: MCP Tools](docs/support-faq/mcp-tools.md)
