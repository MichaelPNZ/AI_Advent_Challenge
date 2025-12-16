package com.pozyalov.ai_advent_challenge.chat.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.core.FileKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.prefs.Preferences
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import com.pozyalov.ai_advent_challenge.chat.voice.VoskLite

@Composable
internal actual fun VoiceInputButton(
    enabled: Boolean,
    onRecordingStateChange: (Boolean) -> Unit,
    onPartialText: (String) -> Unit,
    onResultText: (String) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true } }

    var isRecording by remember { mutableStateOf(false) }
    var isLoadingModel by remember { mutableStateOf(false) }
    var elapsedMillis by remember { mutableStateOf(0L) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val preferences = remember { Preferences.userRoot().node("ai.advent") }
    var modelPath by remember { mutableStateOf(loadModelPath(preferences)) }

    var voskModel by remember { mutableStateOf<VoskLite.ModelHandle?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var stopFlag by remember { mutableStateOf<AtomicBoolean?>(null) }

    fun stopRecording() {
        stopFlag?.set(true)
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("OK") }
            },
            title = { Text("Голосовой ввод") },
            text = { Text(errorMessage!!) }
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        if (isRecording) {
            Text(
                text = formatElapsed(elapsedMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
        }

        IconButton(
            onClick = {
                if (!enabled) return@IconButton

                if (isRecording) {
                    stopRecording()
                    return@IconButton
                }

                if (recordingJob != null) return@IconButton

                if (modelPath.isNullOrBlank()) {
                    recordingJob = scope.launch {
                        val picked = FileKit.pickDirectory()
                        if (picked == null) {
                            recordingJob = null
                            return@launch
                        }
                        val path = picked.path
                        preferences.put(PREF_VOSK_MODEL_PATH, path)
                        modelPath = path
                        voskModel?.close()
                        voskModel = null
                        recordingJob = null
                    }
                    return@IconButton
                }

                val audioFormat = AudioFormat(16_000f, 16, 1, true, false)
                val lineInfo = DataLine.Info(TargetDataLine::class.java, audioFormat)
                if (!AudioSystem.isLineSupported(lineInfo)) {
                    errorMessage = "Микрофон недоступен для формата $audioFormat. Проверьте разрешения на микрофон в системе."
                    return@IconButton
                }

                val localStop = AtomicBoolean(false)
                stopFlag = localStop
                isRecording = true
                elapsedMillis = 0L
                onRecordingStateChange(true)

                recordingJob = scope.launch {
                    val partialRef = AtomicReference("")
                    val start = System.nanoTime()
                    val ticker = launch {
                        var lastSent = ""
                        while (isActive && !localStop.get()) {
                            elapsedMillis = (System.nanoTime() - start) / 1_000_000L
                            val current = partialRef.get()
                            if (current.isNotBlank() && current != lastSent) {
                                onPartialText(current)
                                lastSent = current
                            }
                            delay(150)
                        }
                    }

                    try {
                        val resolvedModelPath = modelPath
                            ?: error(
                                "Путь к модели Vosk не задан. " +
                                    "Нажмите на микрофон и выберите папку модели, " +
                                    "или задайте VOSK_MODEL_PATH / -Dai.advent.vosk.modelPath=..."
                            )
                        val model = voskModel ?: run {
                            isLoadingModel = true
                            val loaded = withContext(Dispatchers.IO) {
                                VoskLite.setLogLevel(0)
                                VoskLite.loadModel(resolvedModelPath)
                            }
                            voskModel = loaded
                            isLoadingModel = false
                            loaded
                        }

                        val transcript = withContext(Dispatchers.IO) {
                            recordOnce(
                                model = model,
                                audioFormat = audioFormat,
                                lineInfo = lineInfo,
                                stop = localStop,
                                json = json,
                                partialRef = partialRef,
                            )
                        }

                        if (transcript.isNotBlank()) onResultText(transcript)
                    } catch (t: Throwable) {
                        isLoadingModel = false
                        errorMessage = t.message ?: t::class.simpleName
                    } finally {
                        localStop.set(true)
                        ticker.cancelAndJoin()
                        isRecording = false
                        stopFlag = null
                        recordingJob = null
                        onRecordingStateChange(false)
                    }
                }
            },
            enabled = enabled,
        ) {
            when {
                isLoadingModel -> CircularProgressIndicator(strokeWidth = 2.dp)
                isRecording -> Icon(Icons.Default.Stop, contentDescription = "Остановить запись", tint = Color.Red)
                else -> Icon(Icons.Default.Mic, contentDescription = "Записать голос")
            }
        }
    }
}

private const val PREF_VOSK_MODEL_PATH = "vosk.modelPath"

private fun loadModelPath(preferences: Preferences): String? {
    val env = System.getenv("VOSK_MODEL_PATH")?.trim().orEmpty()
    if (env.isNotBlank()) return env
    val prop = System.getProperty("ai.advent.vosk.modelPath")?.trim().orEmpty()
    if (prop.isNotBlank()) return prop
    val stored = preferences.get(PREF_VOSK_MODEL_PATH, "")?.trim().orEmpty()
    return stored.ifBlank { null }
}

private fun recordOnce(
    model: VoskLite.ModelHandle,
    audioFormat: AudioFormat,
    lineInfo: DataLine.Info,
    stop: AtomicBoolean,
    json: Json,
    partialRef: AtomicReference<String>,
): String {
    val line = (AudioSystem.getLine(lineInfo) as TargetDataLine)
    line.open(audioFormat)
    line.start()

    VoskLite.newRecognizer(model, audioFormat.sampleRate).use { recognizer ->
        return try {
            val buffer = ByteArray(4096)
            var lastPartialUpdateNs = 0L
            while (!stop.get()) {
                val bytesRead = line.read(buffer, 0, buffer.size)
                if (bytesRead > 0) VoskLite.acceptWaveform(recognizer, buffer, bytesRead)

                val now = System.nanoTime()
                if (bytesRead > 0 && now - lastPartialUpdateNs >= 200_000_000L) {
                    lastPartialUpdateNs = now
                    val partialJson = VoskLite.partialResult(recognizer)
                    val partial = runCatching {
                        json.parseToJsonElement(partialJson)
                            .jsonObject["partial"]
                            ?.jsonPrimitive
                            ?.content
                            ?.trim()
                            .orEmpty()
                    }.getOrDefault("")
                    if (partial.isNotBlank()) partialRef.set(partial)
                }
            }
            val finalJson = VoskLite.finalResult(recognizer)
            return json.parseToJsonElement(finalJson)
                .jsonObject["text"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                .orEmpty()
        } finally {
            line.stop()
            line.close()
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
