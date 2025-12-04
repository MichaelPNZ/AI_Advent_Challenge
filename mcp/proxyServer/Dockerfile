FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Копируем gradle wrapper и зависимости
COPY gradle gradle
COPY gradlew .
COPY settings.gradle.kts .
COPY build.gradle.kts .
COPY gradle.properties .
COPY gradle/libs.versions.toml gradle/libs.versions.toml

# Копируем все модули (нужны для сборки)
COPY core core
COPY mcp mcp

# Собираем только proxy server
RUN ./gradlew :mcp:proxyServer:installDist --no-daemon

# Runtime image
FROM eclipse-temurin:21-jre

WORKDIR /app

# Копируем собранный дистрибутив
COPY --from=builder /app/mcp/proxyServer/build/install/proxyServer /app

# Копируем MCP серверы (собранные JAR'ы и скрипты)
COPY --from=builder /app/mcp/weather-server/build/install/weatherServer /app/mcp/weather-server
COPY --from=builder /app/mcp/reminder-server/build/install/reminderServer /app/mcp/reminder-server
COPY --from=builder /app/mcp/chat-summary-server/build/install/chatSummaryServer /app/mcp/chat-summary-server
COPY --from=builder /app/mcp/doc-pipeline-server/build/install/docPipelineServer /app/mcp/doc-pipeline-server
COPY --from=builder /app/mcp/support-ticket-server/build/install/supportTicketServer /app/mcp/support-ticket-server

# Копируем run-скрипты
COPY mcp/weather-server/run-weather-server.sh /app/mcp/weather-server/
COPY mcp/reminder-server/run-reminder-server.sh /app/mcp/reminder-server/
COPY mcp/chat-summary-server/run-chat-summary-server.sh /app/mcp/chat-summary-server/
COPY mcp/doc-pipeline-server/run-doc-pipeline-server.sh /app/mcp/doc-pipeline-server/
COPY mcp/support-ticket-server/run-support-ticket-server.sh /app/mcp/support-ticket-server/

# Делаем скрипты исполняемыми
RUN chmod +x /app/mcp/*/run-*.sh

# Создаем директорию для данных
RUN mkdir -p /root/.ai_advent

# Проверяем здоровье через curl
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

EXPOSE 8080

CMD ["/app/bin/proxyServer"]
