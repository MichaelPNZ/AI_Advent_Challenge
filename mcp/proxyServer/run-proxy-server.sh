#!/usr/bin/env bash

# Скрипт для запуска MCP HTTP Proxy Server локально (без Docker)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "🚀 MCP HTTP Proxy Server"
echo "📁 Project root: $PROJECT_ROOT"
echo ""

# Собираем proxy server (если еще не собран)
if [ ! -d "$PROJECT_ROOT/mcp/proxyServer/build/install/proxyServer" ]; then
    echo "🔨 Building proxy server..."
    cd "$PROJECT_ROOT"
    ./gradlew :mcp:proxyServer:installDist
    echo "✅ Build complete"
    echo ""
fi

# Собираем MCP серверы (если нужно)
echo "🔧 Checking MCP servers..."
MCP_SERVERS=("weather" "reminder" "chatSummary" "docPipeline" "supportTicket")
for server in "${MCP_SERVERS[@]}"; do
    SERVER_DIR="$PROJECT_ROOT/mcp/${server}Server/build/install/${server}Server"
    if [ ! -d "$SERVER_DIR" ]; then
        echo "  ⚠️  Building $server server..."
        cd "$PROJECT_ROOT"
        ./gradlew ":mcp:${server}Server:installDist"
    else
        echo "  ✓ $server server ready"
    fi
done

echo ""
echo "🌐 Starting MCP HTTP Proxy on http://localhost:8080"
echo "📋 Endpoints:"
echo "   GET  http://localhost:8080/health"
echo "   GET  http://localhost:8080/mcp/servers"
echo "   GET  http://localhost:8080/mcp/{server}/tools"
echo "   POST http://localhost:8080/mcp/{server}/tool/{tool}"
echo ""
echo "💡 Для Android эмулятора используйте: http://10.0.2.2:8080"
echo "💡 Для реального устройства: http://<your-local-ip>:8080"
echo ""
echo "Press Ctrl+C to stop"
echo ""

cd "$PROJECT_ROOT"
exec "$PROJECT_ROOT/mcp/proxyServer/build/install/proxyServer/bin/proxyServer"
