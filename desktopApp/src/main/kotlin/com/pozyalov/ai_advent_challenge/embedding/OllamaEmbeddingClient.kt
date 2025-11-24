package com.pozyalov.ai_advent_challenge.embedding

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class OllamaEmbeddingsRequest(
    val model: String = "nomic-embed-text",
    val prompt: String
)

@Serializable
data class OllamaEmbeddingsResponse(
    val embedding: List<Double>
)

class OllamaEmbeddingClient(
    private val baseUrl: String = "http://127.0.0.1:11434",
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // важно: сериализуем поле model по умолчанию
    }
) {
    private val http = HttpClient.newBuilder().build()

    fun embed(text: String): FloatArray {
        val payload = json.encodeToString(OllamaEmbeddingsRequest(prompt = text))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/embeddings"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()
        if (response.statusCode() !in 200..299) {
            error("Ollama embeddings HTTP ${response.statusCode()}: $body")
        }
        val element: JsonElement = json.parseToJsonElement(body)
        val arr = element.jsonObject["embedding"]?.jsonArray
            ?: error("Ollama response missing embedding")
        return arr.map { it.jsonPrimitive.double }.map { it.toFloat() }.toFloatArray()
    }
}
