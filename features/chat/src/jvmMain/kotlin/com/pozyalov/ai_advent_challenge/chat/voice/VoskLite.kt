package com.pozyalov.ai_advent_challenge.chat.voice

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal Vosk binding that avoids optional symbols (like `vosk_recognizer_set_grm`)
 * which are missing from the bundled macOS dylib inside `com.alphacephei:vosk`.
 *
 * Uses the native library embedded in the Vosk JAR (darwin/linux/windows).
 */
internal object VoskLite {
    private val lib: VoskLib by lazy {
        val extracted = extractNativeLibrary()
        @Suppress("UNCHECKED_CAST")
        Native.load(extracted.absolutePath, VoskLib::class.java) as VoskLib
    }

    fun setLogLevel(level: Int) {
        lib.vosk_set_log_level(level)
    }

    fun loadModel(modelPath: String): ModelHandle {
        val ptr = lib.vosk_model_new(modelPath)
        require(ptr != Pointer.NULL) { "Failed to load Vosk model from: $modelPath" }
        return ModelHandle(ptr)
    }

    fun newRecognizer(model: ModelHandle, sampleRate: Float): RecognizerHandle {
        val ptr = lib.vosk_recognizer_new(model.ptr, sampleRate)
        require(ptr != Pointer.NULL) { "Failed to create recognizer (sampleRate=$sampleRate)" }
        return RecognizerHandle(ptr)
    }

    fun acceptWaveform(recognizer: RecognizerHandle, data: ByteArray, len: Int): Boolean =
        lib.vosk_recognizer_accept_waveform(recognizer.ptr, data, len)

    fun finalResult(recognizer: RecognizerHandle): String =
        lib.vosk_recognizer_final_result(recognizer.ptr) ?: ""

    fun partialResult(recognizer: RecognizerHandle): String =
        lib.vosk_recognizer_partial_result(recognizer.ptr) ?: ""

    fun freeRecognizer(recognizer: RecognizerHandle) {
        lib.vosk_recognizer_free(recognizer.ptr)
    }

    fun freeModel(model: ModelHandle) {
        lib.vosk_model_free(model.ptr)
    }

    class ModelHandle internal constructor(internal val ptr: Pointer) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) freeModel(this)
        }
    }

    class RecognizerHandle internal constructor(internal val ptr: Pointer) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) freeRecognizer(this)
        }
    }

    private interface VoskLib : Library {
        fun vosk_set_log_level(level: Int)
        fun vosk_model_new(modelPath: String): Pointer
        fun vosk_model_free(model: Pointer)

        fun vosk_recognizer_new(model: Pointer, sampleRate: Float): Pointer
        fun vosk_recognizer_accept_waveform(recognizer: Pointer, data: ByteArray, len: Int): Boolean
        fun vosk_recognizer_partial_result(recognizer: Pointer): String?
        fun vosk_recognizer_final_result(recognizer: Pointer): String?
        fun vosk_recognizer_free(recognizer: Pointer)
    }

    private fun extractNativeLibrary(): File {
        val os = System.getProperty("os.name").lowercase()
        val resourcePath = when {
            os.contains("mac") || os.contains("darwin") -> "darwin/libvosk.dylib"
            os.contains("win") -> "win32-x86-64/libvosk.dll"
            else -> "linux-x86-64/libvosk.so"
        }

        val stream = VoskLite::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: error("Vosk native library not found in classpath: $resourcePath")

        val targetDir = File(System.getProperty("java.io.tmpdir"), "ai-advent-vosklite").apply { mkdirs() }
        val out = File(targetDir, File(resourcePath).name)
        if (out.exists() && out.length() > 0) return out

        stream.use { input ->
            Files.newOutputStream(out.toPath()).use { output ->
                input.copyTo(output)
            }
        }
        out.deleteOnExit()
        return out
    }
}
