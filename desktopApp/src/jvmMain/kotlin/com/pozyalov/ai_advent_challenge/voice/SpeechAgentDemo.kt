package com.pozyalov.ai_advent_challenge.voice

import com.aallam.openai.api.model.ModelId
import com.pozyalov.ai_advent_challenge.network.api.AiApi
import com.pozyalov.ai_advent_challenge.network.api.AiMessage
import com.pozyalov.ai_advent_challenge.network.api.AiRole
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Offline speech-to-text via Vosk + text LLM chat.
 */
class SpeechAgent(
    modelPath: String,
    private val aiApi: AiApi,
) {
    private val voskModel = Model(modelPath)
    private val json = Json { ignoreUnknownKeys = true }

    fun transcribe(filePath: String): String {
        val file = File(filePath)
        require(file.exists()) { "Audio file not found: $filePath" }

        AudioSystem.getAudioInputStream(file).use { source ->
            val targetFormat = AudioFormat(16_000f, 16, 1, true, false)
            val converted: AudioInputStream = AudioSystem.getAudioInputStream(targetFormat, source)

            converted.use { audio ->
                Recognizer(voskModel, targetFormat.sampleRate).use { recognizer ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = audio.read(buffer)
                        if (n <= 0) break
                        recognizer.acceptWaveForm(buffer, n)
                    }
                    val finalJson = recognizer.finalResult
                    val jsonObj = json.parseToJsonElement(finalJson).jsonObject
                    return jsonObj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                        .ifBlank { error("Recognizer returned empty text for $filePath") }
                }
            }
        }
    }

    suspend fun askModel(
        userText: String,
        model: ModelId = ModelId("gpt-4o-mini"),
    ) = aiApi.chatCompletion(
        messages = listOf(AiMessage(role = AiRole.User, text = userText)),
        model = model
    ).getOrThrow()
}

fun main(args: Array<String>) = runBlocking {
    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: error("Set OPENAI_API_KEY environment variable before running SpeechAgent demo")
    val modelPath = System.getenv("VOSK_MODEL_PATH")
        ?: error("Set VOSK_MODEL_PATH to the folder with a Vosk model (e.g. vosk-model-small-ru-0.22)")

    val aiApi = AiApi(apiKey = apiKey)
    check(aiApi.isConfigured) { "AiApi is not configured (missing key?)" }
    val agent = SpeechAgent(modelPath = modelPath, aiApi = aiApi)

    val audioFiles = if (args.isNotEmpty()) args.toList() else listOf(
        "build/voice_samples/calc.wav",
        "build/voice_samples/definition.wav",
        "build/voice_samples/joke.wav",
    )

    audioFiles.forEach { path ->
        println("=== $path ===")
        val transcript = runCatching { agent.transcribe(path) }
            .getOrElse { error -> error("Failed to transcribe $path: ${error.message}") }
        println("Heard: $transcript")

        val completion = agent.askModel(transcript)
        println("Model (${completion.modelId}): ${completion.content}")
        println()
    }

    aiApi.close()
}
