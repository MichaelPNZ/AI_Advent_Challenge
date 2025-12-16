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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Live dictation (mic -> Vosk STT -> text -> LLM -> text).
 *
 * Controls:
 * - Press Enter to start recording
 * - Press Enter again to stop and send transcript to LLM
 * - Type 'q' + Enter to quit
 */
fun main(): Unit = runBlocking {
    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: error("Set OPENAI_API_KEY environment variable")
    val modelPath = System.getenv("VOSK_MODEL_PATH")
        ?: error("Set VOSK_MODEL_PATH to the folder with a Vosk model (e.g. vosk-model-small-ru-0.22)")

    val aiApi = AiApi(apiKey = apiKey)
    check(aiApi.isConfigured) { "AiApi is not configured (missing key?)" }

    val voskModel = Model(modelPath)
    val json = Json { ignoreUnknownKeys = true }

    val audioFormat = AudioFormat(
        /* sampleRate = */ 16_000f,
        /* sampleSizeInBits = */ 16,
        /* channels = */ 1,
        /* signed = */ true,
        /* bigEndian = */ false
    )

    val lineInfo = DataLine.Info(TargetDataLine::class.java, audioFormat)
    require(AudioSystem.isLineSupported(lineInfo)) {
        "Microphone line is not supported for format $audioFormat"
    }

    val stdin = BufferedReader(InputStreamReader(System.`in`))
    println("Mic dictation ready.")
    println("Enter: start/stop recording; 'q'+Enter: quit.")

    while (true) {
        val cmd = stdin.readLine() ?: break
        if (cmd.trim().equals("q", ignoreCase = true)) break

        val stop = AtomicBoolean(false)
        val stopThread = Thread {
            stdin.readLine()
            stop.set(true)
        }.apply { isDaemon = true }

        val transcript = Recognizer(voskModel, audioFormat.sampleRate).use { recognizer ->
            val line = (AudioSystem.getLine(lineInfo) as TargetDataLine)
            line.open(audioFormat)
            line.start()

            println("Recording... (press Enter to stop)")
            stopThread.start()

            val buffer = ByteArray(4096)
            while (!stop.get()) {
                val bytesRead = line.read(buffer, 0, buffer.size)
                if (bytesRead <= 0) continue
                recognizer.acceptWaveForm(buffer, bytesRead)
            }

            line.stop()
            line.close()

            val finalJson = recognizer.finalResult
            val obj = json.parseToJsonElement(finalJson).jsonObject
            obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        }

        if (transcript.isBlank()) {
            println("Heard: (empty) — try again.")
            continue
        }

        println("Heard: $transcript")
        val completion = aiApi.chatCompletion(
            messages = listOf(AiMessage(role = AiRole.User, text = transcript)),
            model = ModelId("gpt-4o-mini"),
        ).getOrThrow()
        println("Model (${completion.modelId}): ${completion.content}")
        println()
        println("Enter: start/stop recording; 'q'+Enter: quit.")
    }

    aiApi.close()
}
