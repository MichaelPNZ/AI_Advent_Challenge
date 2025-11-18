package com.pozyalov.ai_advent_challenge.mcp.weather

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

suspend fun HttpClient.getAlerts(state: String): List<String> {
    val uri = "https://api.weather.gov/alerts/active/area/$state"
    val alerts = this.get(uri).body<AlertResponse>()
    return alerts.features.map { feature ->
        """
            Event: ${feature.properties.event}
            Area: ${feature.properties.areaDesc}
            Severity: ${feature.properties.severity}
            Description: ${feature.properties.description}
            Instruction: ${feature.properties.instruction.orEmpty()}
        """.trimIndent()
    }
}

suspend fun HttpClient.getForecast(latitude: Double, longitude: Double): List<String> {
    val response: OpenMeteoResponse = this.get("https://api.open-meteo.com/v1/forecast") {
        parameter("latitude", latitude)
        parameter("longitude", longitude)
        parameter("current_weather", true)
        parameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_sum")
        parameter("timezone", "auto")
    }.body()

    val segments = mutableListOf<String>()
    response.currentWeather?.let { current ->
        segments += buildString {
            appendLine("Текущая погода:")
            current.temperature?.let { appendLine("Температура: $it °C") }
            current.windSpeed?.let { speed ->
                append("Ветер: $speed м/с")
                current.windDirection?.let { dir -> append(" ($dir°)") }
                appendLine()
            }
            current.weatherCode?.let { code -> appendLine("Код состояния: $code") }
        }.trim()
    }

    response.daily?.let { daily ->
        val count = daily.time.size
        if (count > 0) {
            val builder = StringBuilder()
            builder.appendLine("Прогноз на ближайшие дни:")
            for (index in 0 until count) {
                val date = daily.time.getOrNull(index).orEmpty()
                val max = daily.temperatureMax.getOrNull(index)?.let { "$it °C" } ?: "—"
                val min = daily.temperatureMin.getOrNull(index)?.let { "$it °C" } ?: "—"
                val precip = daily.precipitationSum.getOrNull(index)?.let { "$it мм" } ?: "—"
                builder.appendLine(
                    "$date: max $max / min $min, осадки: $precip"
                )
            }
            segments += builder.toString().trim()
        }
    }

    if (segments.isEmpty()) {
        error("Open-Meteo API не вернуло данных для указанных координат")
    }
    return segments
}

@Serializable
private data class AlertResponse(
    val features: List<AlertFeature> = emptyList()
)

@Serializable
private data class AlertFeature(
    val properties: AlertProperties
)

@Serializable
private data class AlertProperties(
    val event: String,
    val areaDesc: String,
    val severity: String,
    val description: String,
    val instruction: String? = null,
)

@Serializable
private data class OpenMeteoResponse(
    @SerialName("current_weather") val currentWeather: CurrentWeather? = null,
    val daily: Daily? = null
)

@Serializable
private data class CurrentWeather(
    val temperature: Double? = null,
    @SerialName("windspeed") val windSpeed: Double? = null,
    @SerialName("winddirection") val windDirection: Double? = null,
    @SerialName("weathercode") val weatherCode: Int? = null,
)

@Serializable
private data class Daily(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m_max") val temperatureMax: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val temperatureMin: List<Double?> = emptyList(),
    @SerialName("precipitation_sum") val precipitationSum: List<Double?> = emptyList(),
)
