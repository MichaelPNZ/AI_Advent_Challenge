# MCP HTTP Proxy - Мультиплатформенный доступ к MCP серверам

## 📋 Содержание

- [Обзор](#обзор)
- [Быстрый старт](#быстрый-старт)
- [Варианты развертывания](#варианты-развертывания)
- [Конфигурация](#конфигурация)
- [API Reference](#api-reference)
- [Troubleshooting](#troubleshooting)

---

## Обзор

**MCP HTTP Proxy** — это HTTP-сервер, который позволяет всем платформам (Android, iOS, Desktop) одинаково работать с MCP серверами через HTTP API.

### Проблема

Изначально все MCP клиенты находились в `jvmMain` и работали через stdio transport:
- ❌ Не работает на Android
- ❌ Не работает на iOS
- ❌ Сложно тестировать на реальных устройствах

### Решение

**HTTP Proxy** транслирует HTTP запросы в stdio-вызовы MCP серверов:

```
┌─────────────┐      HTTP      ┌─────────────┐      stdio      ┌─────────────┐
│ Android/iOS │ ────────────────> MCP Proxy   │ ───────────────> MCP Servers │
│    App      │                │ (Ktor)      │                │ (weather,   │
└─────────────┘                └─────────────┘                │  reminder,  │
                                                               │  git, ...)  │
                                                               └─────────────┘
```

### Возможности

- ✅ Единая кодовая база на всех платформах
- ✅ Desktop может работать как локально (stdio), так и через proxy
- ✅ Android/iOS работают только через proxy
- ✅ Легко тестировать на эмуляторах и реальных устройствах
- ✅ Готово к production (с Docker/VPS)

---

## Быстрый старт

### Вариант 1: Локальный запуск (рекомендуется для начала)

#### 1. Соберите proxy и MCP серверы

```bash
cd /path/to/AI_Advent_Challenge

# Собрать proxy
./gradlew :mcp:proxyServer:installDist

# Собрать все MCP серверы (если еще не собраны)
./gradlew :mcp:weatherServer:installDist
./gradlew :mcp:reminderServer:installDist
./gradlew :mcp:chatSummaryServer:installDist
./gradlew :mcp:docPipelineServer:installDist
./gradlew :mcp:supportTicketServer:installDist
```

#### 2. Запустите proxy

```bash
./mcp/proxyServer/run-proxy-server.sh
```

Вы увидите:
```
🚀 MCP HTTP Proxy Server
📁 Project root: /Users/.../AI_Advent_Challenge
🔧 Configured 5 MCP servers:
   • weather (Weather.gov Forecast)
   • reminder (Reminder Tasks)
   • chat-summary (Chat Summary)
   • doc-pipeline (Document Pipeline)
   • support-ticket (Support Tickets)

🌐 Starting server on 0.0.0.0:8080
✅ Server ready!
```

#### 3. Проверьте, что proxy работает

```bash
curl http://localhost:8080/health
```

Ответ:
```json
{
  "status": "ok",
  "servers": {
    "weather": { "available": true, "toolCount": 1 },
    "reminder": { "available": true, "toolCount": 5 },
    ...
  }
}
```

#### 4. Запустите Desktop app

**Режим A: Локальный stdio (по умолчанию)**
```bash
./gradlew :desktopApp:run
```

Desktop app будет использовать прямой stdio доступ к MCP серверам (как раньше).

**Режим B: Через HTTP proxy**
```bash
./gradlew :desktopApp:run -Dmcp.mode=http
```

Desktop app будет подключаться к `http://localhost:8080`.

#### 5. Запустите Android app

**Android эмулятор:**
1. Запустите эмулятор
2. Убедитесь, что proxy запущен
3. Запустите app из Android Studio

Android автоматически подключится к `http://10.0.2.2:8080` (localhost эмулятора).

**Реальное Android устройство:**
1. Подключите устройство к той же WiFi сети, что и компьютер
2. Узнайте IP вашего компьютера: `ifconfig | grep "inet "` (macOS/Linux)
3. Укажите IP при запуске app:
   ```bash
   adb shell setprop debug.mcp.proxy.url "http://192.168.1.100:8080"
   ```
4. Запустите app

---

### Вариант 2: Docker Compose

Если у вас установлен Docker, можно запустить proxy в контейнере.

#### 1. Соберите все MCP серверы

```bash
./gradlew :mcp:weatherServer:installDist
./gradlew :mcp:reminderServer:installDist
./gradlew :mcp:chatSummaryServer:installDist
./gradlew :mcp:docPipelineServer:installDist
./gradlew :mcp:supportTicketServer:installDist
```

#### 2. Запустите Docker Compose

```bash
docker-compose up --build
```

Proxy будет доступен на `http://localhost:8080`.

#### 3. Запустите app

- **Desktop**: `./gradlew :desktopApp:run -Dmcp.mode=http`
- **Android эмулятор**: работает автоматически (10.0.2.2:8080)
- **Реальное устройство**: укажите IP компьютера

---

## Варианты развертывания

### 1. Локальный запуск (Development)

**Плюсы:**
- ✅ Быстро стартовать
- ✅ Легко дебажить
- ✅ Бесплатно

**Минусы:**
- ❌ Реальное Android устройство нужно подключать к той же WiFi
- ❌ Нужно каждый раз запускать proxy

**Когда использовать:**
- Разработка и отладка
- Тестирование на эмуляторах

### 2. Docker (Local Development + Team)

**Плюсы:**
- ✅ Изолированная среда
- ✅ Легко поделиться с командой
- ✅ Воспроизводимость

**Минусы:**
- ❌ Требует Docker

**Когда использовать:**
- Команда разработчиков
- CI/CD pipeline
- Стабильная среда для тестирования

### 3. VPS Server (Production)

**Плюсы:**
- ✅ Доступно откуда угодно
- ✅ Можно тестировать на любых устройствах
- ✅ Production-ready

**Минусы:**
- ❌ Стоит денег ($5-10/месяц)
- ❌ Нужна настройка сервера + SSL

**Когда использовать:**
- Публичное приложение
- Тестирование с реальными пользователями
- Production deployment

**Инструкции:**

1. Арендуйте VPS (DigitalOcean, Hetzner, AWS EC2)
2. Установите Docker:
   ```bash
   curl -fsSL https://get.docker.com | sh
   ```
3. Склонируйте проект:
   ```bash
   git clone <your-repo>
   cd AI_Advent_Challenge
   ```
4. Соберите серверы и запустите:
   ```bash
   ./gradlew :mcp:weatherServer:installDist
   ./gradlew :mcp:reminderServer:installDist
   # ... остальные

   docker-compose up -d
   ```
5. Настройте nginx + SSL (Let's Encrypt):
   ```nginx
   server {
       listen 443 ssl;
       server_name your-domain.com;

       ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
       ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;

       location / {
           proxy_pass http://localhost:8080;
       }
   }
   ```
6. В app укажите URL:
   ```kotlin
   // Android
   -Dmcp.proxy.url="https://your-domain.com"

   // Desktop
   ./gradlew :desktopApp:run -Dmcp.mode=http -Dmcp.proxy.url="https://your-domain.com"
   ```

---

## Конфигурация

### Environment Variables

| Переменная | Описание | По умолчанию |
|------------|----------|--------------|
| `MCP_PROXY_PORT` | Порт HTTP сервера | `8080` |
| `MCP_PROXY_HOST` | Host для binding | `0.0.0.0` |

Пример:
```bash
MCP_PROXY_PORT=9000 MCP_PROXY_HOST=127.0.0.1 ./mcp/proxyServer/run-proxy-server.sh
```

### Desktop App Configuration

**Переменные:**
- `mcp.mode` или `MCP_MODE`: `local` (stdio) или `http` (proxy)
- `mcp.proxy.url` или `MCP_PROXY_URL`: URL proxy сервера

**Примеры:**
```bash
# Через системные properties
./gradlew :desktopApp:run -Dmcp.mode=http -Dmcp.proxy.url="http://localhost:8080"

# Через environment variables
export MCP_MODE=http
export MCP_PROXY_URL=http://localhost:8080
./gradlew :desktopApp:run
```

### Android App Configuration

**По умолчанию:**
- Эмулятор: `http://10.0.2.2:8080`

**Для реального устройства:**
```bash
# Через adb
adb shell setprop debug.mcp.proxy.url "http://192.168.1.100:8080"

# Или в коде (AndroidModules.kt)
val proxyUrl = "http://192.168.1.100:8080"
```

### iOS App Configuration

**По умолчанию:**
- Симулятор: `http://localhost:8080`

**Для реального устройства:**
Создайте файл `iosApp/.env` (или укажите в Xcode):
```
MCP_PROXY_URL=http://192.168.1.100:8080
```

---

## API Reference

### GET /health

Health check всех MCP серверов.

**Response:**
```json
{
  "status": "ok",
  "servers": {
    "weather": {
      "available": true,
      "toolCount": 1
    },
    "reminder": {
      "available": true,
      "toolCount": 5
    }
  }
}
```

### GET /mcp/servers

Список всех доступных серверов.

**Response:**
```json
["weather", "reminder", "chat-summary", "doc-pipeline", "support-ticket"]
```

### GET /mcp/{serverName}/tools

Список инструментов для конкретного сервера.

**Response:**
```json
[
  {
    "name": "weather_get_forecast",
    "description": "Get weather forecast for coordinates",
    "inputSchema": {
      "type": "object",
      "properties": {
        "latitude": { "type": "number" },
        "longitude": { "type": "number" }
      },
      "required": ["latitude", "longitude"]
    }
  }
]
```

### POST /mcp/{serverName}/tool/{toolName}

Выполнить инструмент.

**Request:**
```json
{
  "arguments": {
    "latitude": 37.7749,
    "longitude": -122.4194
  }
}
```

**Response (success):**
```json
{
  "success": true,
  "text": "Forecast for San Francisco: Sunny, 22°C",
  "structured": {
    "temperature": 22,
    "condition": "sunny"
  },
  "error": null
}
```

**Response (error):**
```json
{
  "success": false,
  "text": "",
  "structured": null,
  "error": "Invalid coordinates"
}
```

---

## Troubleshooting

### Proxy не запускается

**Проблема:** `Address already in use: bind`

**Решение:** Порт 8080 занят. Убейте процесс или измените порт:
```bash
lsof -ti:8080 | xargs kill -9  # убить процесс
# или
MCP_PROXY_PORT=9000 ./mcp/proxyServer/run-proxy-server.sh
```

---

### Android не может подключиться (эмулятор)

**Проблема:** `Failed to connect to /10.0.2.2:8080`

**Решение:**
1. Убедитесь, что proxy запущен: `curl http://localhost:8080/health`
2. Проверьте, что эмулятор может достучаться: `adb shell curl http://10.0.2.2:8080/health`
3. Если не работает, используйте `localhost` вместо `10.0.2.2`:
   ```bash
   adb reverse tcp:8080 tcp:8080
   ```
   Теперь можно использовать `http://localhost:8080` в app.

---

### Android не может подключиться (реальное устройство)

**Проблема:** `Failed to connect to /192.168.1.100:8080`

**Решение:**
1. Убедитесь, что устройство и компьютер в одной WiFi сети
2. Проверьте IP компьютера: `ifconfig | grep "inet "` (macOS/Linux)
3. Проверьте firewall:
   ```bash
   # macOS
   sudo pfctl -d  # отключить firewall (временно)

   # Linux
   sudo ufw allow 8080
   ```
4. Убедитесь, что proxy слушает `0.0.0.0`, а не `127.0.0.1`

---

### MCP серверы не найдены

**Проблема:** `Server 'weather' not configured` или `toolCount: 0`

**Решение:**
1. Соберите MCP серверы:
   ```bash
   ./gradlew :mcp:weatherServer:installDist
   ./gradlew :mcp:reminderServer:installDist
   # ... остальные
   ```
2. Проверьте, что run-скрипты исполняемые:
   ```bash
   chmod +x mcp/*/run-*.sh
   ```
3. Проверьте пути в `main.kt`:
   ```kotlin
   "$projectRoot/mcp/weather-server/run-weather-server.sh"
   ```

---

### Desktop app не переключается в HTTP режим

**Проблема:** Desktop всё ещё использует stdio, хотя указан `-Dmcp.mode=http`

**Решение:**
1. Проверьте, что property передается:
   ```bash
   ./gradlew :desktopApp:run -Dmcp.mode=http --info | grep "MCP Mode"
   ```
2. Убедитесь, что proxy запущен перед запуском app
3. Проверьте логи DI (в консоли):
   ```
   🔧 MCP Mode: HTTP_PROXY
   🌐 MCP Proxy URL: http://localhost:8080
   ```

---

## Следующие шаги

После успешного запуска:

1. **Протестируйте функциональность**
   - Отправьте сообщение в чат с использованием MCP tool (например, "Какая погода в Москве?")
   - Проверьте, что tool выполняется успешно

2. **Настройте production deployment** (опционально)
   - Разверните на VPS
   - Настройте SSL
   - Добавьте мониторинг (например, через Prometheus)

3. **Документируйте для команды**
   - Обновите README с инструкциями по запуску
   - Добавьте troubleshooting для вашей конкретной конфигурации

---

## Дополнительные ресурсы

- [Model Context Protocol Docs](https://modelcontextprotocol.io)
- [Ktor Server Documentation](https://ktor.io/docs/server.html)
- [Kotlin Multiplatform Guide](https://kotlinlang.org/docs/multiplatform.html)
