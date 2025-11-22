package com.pozyalov.ai_advent_challenge.pipeline

import com.pozyalov.ai_advent_challenge.chat.pipeline.TripBriefingExecutor
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopTripBriefingExecutor(
    private val taskToolClient: TaskToolClient
) : TripBriefingExecutor {

    private val toolNames: Set<String> = taskToolClient.toolDefinitions.map { it.function.name }.toSet()

    override val isAvailable: Boolean =
        listOf("search_location", "get_forecast", "reminder_add_task", "reminder_summary")
            .all { toolNames.contains(it) }

    override suspend fun prepareBriefing(
        locationQuery: String,
        departureDate: String?
    ): Result<TripBriefingExecutor.PreparedTrip> = runCatching {
        if (!isAvailable) error("Не включены нужные MCP инструменты (weather + reminder).")
        val trimmedQuery = locationQuery.trim()
        if (trimmedQuery.isBlank()) error("Укажите город/локацию.")

        val location = searchLocation(trimmedQuery)
            ?: error("Не удалось найти координаты для \"$trimmedQuery\".")

        val forecastText = fetchForecast(location, departureDate)

        val tasks = listOf(
            TripBriefingExecutor.TaskDraft("Проверить визу/паспорт", departureDate),
            TripBriefingExecutor.TaskDraft("Оформить страховку", departureDate),
            TripBriefingExecutor.TaskDraft("Заказать трансфер/такси", departureDate)
        )

        TripBriefingExecutor.PreparedTrip(
            locationName = "${location.name} (${location.country})",
            forecast = forecastText,
            departureDate = departureDate,
            tasks = tasks
        )
    }

    override suspend fun confirmTasks(
        prepared: TripBriefingExecutor.PreparedTrip,
        saveToFile: Boolean
    ): Result<TripBriefingExecutor.TaskResult> =
        runCatching {
            val created = mutableListOf<String>()
            prepared.tasks.forEach { draft ->
                val args = buildJsonObject {
                    put("title", JsonPrimitive(draft.title))
                    draft.dueDate?.let { put("dueDate", JsonPrimitive(it)) }
                }
                val response = taskToolClient.execute("reminder_add_task", args)
                response?.text?.takeIf { it.isNotBlank() }?.let { created += it }
            }
            val summary = taskToolClient.execute("reminder_summary", buildJsonObject { })
            val savedPath = if (saveToFile && toolNames.contains("doc_save")) {
                val content = buildString {
                    appendLine("Сводка для поездки — ${prepared.locationName}")
                    prepared.departureDate?.let {
                        appendLine("Дата вылета: $it")
                    }
                    appendLine()
                    appendLine("Погода:")
                    appendLine(prepared.forecast.trim())
                    appendLine()
                    appendLine("Задачи:")
                    created.forEach { appendLine("• $it") }
                    appendLine()
                    appendLine(summary?.text?.takeIf { it.isNotBlank() } ?: "")
                }.trim()
                val saveResult = taskToolClient.execute(
                    "doc_save",
                    buildJsonObject {
                        put("content", JsonPrimitive(content))
                        put("filename", JsonPrimitive("trip-summary.txt"))
                    }
                )
                saveResult?.structured?.get("path")?.jsonPrimitive?.contentOrNull
            } else null
            TripBriefingExecutor.TaskResult(
                createdTasks = created,
                summaryText = summary?.text?.takeIf { it.isNotBlank() } ?: "Сводка задач недоступна.",
                savedPath = savedPath
            )
        }

    private suspend fun searchLocation(query: String): LocationMatch? {
        val args = buildJsonObject { put("query", JsonPrimitive(query)) }
        val result = taskToolClient.execute("search_location", args)
            ?: return null
        val structured = result.structured ?: return parseLocationFromText(result.text)
        val matches = structured["matches"] as? JsonArray ?: return parseLocationFromText(result.text)
        return matches.firstOrNull()?.let { toLocation(it) }
    }

    private suspend fun fetchForecast(location: LocationMatch, departureDate: String?): String {
        val args = buildJsonObject {
            put("latitude", JsonPrimitive(location.latitude))
            put("longitude", JsonPrimitive(location.longitude))
        }
        val result = taskToolClient.execute("get_forecast", args)
            ?: return "Прогноз недоступен."
        val lines = result.text.lines().filter { it.isNotBlank() }
        val dateRegex = Regex("\\d{4}-\\d{2}-\\d{2}")
        val forecastLines = lines.filter { line -> line.take(10).matches(dateRegex) }
        val filtered = departureDate?.let { date ->
            forecastLines.dropWhile { line -> line.take(10) < date }.ifEmpty { forecastLines }
        } ?: forecastLines
        // Если по какой-то причине нет датированных строк, вернём исходный текст
        return filtered.takeIf { it.isNotEmpty() }?.take(12)?.joinToString("\n") ?: result.text
    }

    private fun toLocation(element: JsonElement): LocationMatch? {
        val obj = element as? JsonObject ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val country = obj["country"]?.jsonPrimitive?.contentOrNull ?: "—"
        val lat = obj["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = obj["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        return LocationMatch(name = name, country = country, latitude = lat, longitude = lon)
    }

    private fun parseLocationFromText(text: String): LocationMatch? {
        // Fallback: try to parse "Name (CC) — lat=X, lon=Y"
        val regex = Regex("(.+)\\((\\w{2})\\).*lat=([-\\d\\.]+).*lon=([-\\d\\.]+)", RegexOption.IGNORE_CASE)
        val match = regex.find(text) ?: return null
        val name = match.groupValues.getOrNull(1)?.trim()?.trimEnd('–', '-', '—')?.trim()
        val country = match.groupValues.getOrNull(2)?.trim().orEmpty()
        val lat = match.groupValues.getOrNull(3)?.toDoubleOrNull()
        val lon = match.groupValues.getOrNull(4)?.toDoubleOrNull()
        if (name.isNullOrBlank() || lat == null || lon == null) return null
        return LocationMatch(name, country, lat, lon)
    }

    private data class LocationMatch(
        val name: String,
        val country: String,
        val latitude: Double,
        val longitude: Double
    )

}
