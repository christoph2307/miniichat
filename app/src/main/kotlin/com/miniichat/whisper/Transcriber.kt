package com.miniichat.whisper

import com.miniichat.audio.WavIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class TranscribeOptions(
    val language: String = "auto",
    val threads: Int = 0,
    val translate: Boolean = false,
    val initialPrompt: String = ""
)

/**
 * Keeps one whisper context loaded and transcribes 16 kHz WAV files in chunks
 * (so hour-long dictaphone recordings don't need GBs of RAM).
 */
class Transcriber {
    private val mutex = Mutex()
    private var ctxPtr = 0L
    private var loadedPath: String? = null

    private suspend fun ensureLoaded(modelFile: File) {
        if (ctxPtr != 0L && loadedPath == modelFile.absolutePath) return
        release()
        if (!WhisperLib.isAvailable) {
            throw IllegalStateException("Native whisper library failed to load: ${WhisperLib.loadError?.message}")
        }
        if (!modelFile.exists()) throw IllegalStateException("Model not found: ${modelFile.name}")
        val gpu = WhisperLib.gpuBuildLoaded
        val ptr = withContext(Dispatchers.IO) {
            WhisperLib.guardGpu(gpu) { WhisperLib.initContext(modelFile.absolutePath, gpu) }
        }
        if (ptr == 0L) throw IllegalStateException("Failed to load model ${modelFile.name} (corrupt file?)")
        ctxPtr = ptr
        loadedPath = modelFile.absolutePath
    }

    fun release() {
        if (ctxPtr != 0L) WhisperLib.freeContext(ctxPtr)
        ctxPtr = 0L
        loadedPath = null
    }

    fun abort() { if (WhisperLib.isAvailable) WhisperLib.requestAbort() }

    /**
     * @param onProgress overall progress 0..1
     * @param onPartial accumulated text so far
     */
    suspend fun transcribeWav(
        modelFile: File,
        wav: File,
        opts: TranscribeOptions,
        onProgress: (Float) -> Unit,
        onPartial: (String) -> Unit
    ): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            ensureLoaded(modelFile)
            val info = WavIO.readInfo(wav) ?: throw IllegalArgumentException("Unsupported audio file")
            if (info.sampleRate != WavIO.SAMPLE_RATE || info.bits != 16) {
                throw IllegalArgumentException("Expected 16 kHz PCM16 WAV, got ${info.sampleRate} Hz / ${info.bits} bit")
            }
            val totalSamples = info.dataBytes / (2 * info.channels)
            val threads = if (opts.threads > 0) opts.threads else defaultThreads()
            val sb = StringBuilder()
            var start = 0L
            while (start < totalSamples) {
                ensureActive()
                var chunk = WavIO.readFloats(wav, info, start, CHUNK_SAMPLES)
                if (chunk.isEmpty()) break
                // Cut at the quietest 20 ms window in the last 3 s so we don't split words.
                if (start + chunk.size < totalSamples && chunk.size > SEARCH_SAMPLES * 2) {
                    val cut = quietestCut(chunk)
                    chunk = chunk.copyOf(cut)
                }
                val chunkStart = start
                val chunkLen = chunk.size
                val prefix = sb.toString()
                val listener = object : WhisperListener {
                    val local = StringBuilder()
                    override fun onProgress(percent: Int) {
                        val done = chunkStart + chunkLen * (percent / 100f)
                        onProgress((done / totalSamples).coerceIn(0f, 1f))
                    }
                    override fun onSegment(text: String) {
                        local.append(text)
                        onPartial((prefix + local).trim())
                    }
                }
                // Feed previous text as context for consistent spelling across chunks.
                val prompt = buildString {
                    if (opts.initialPrompt.isNotBlank()) append(opts.initialPrompt.trim()).append(' ')
                    append(prefix.takeLast(200))
                }.trim().ifBlank { null }
                val text = WhisperLib.guardGpu(WhisperLib.gpuBuildLoaded) {
                    WhisperLib.transcribe(ctxPtr, chunk, opts.language, threads, opts.translate, prompt, listener)
                }
                ensureActive()
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(text.trim())
                }
                onPartial(sb.toString())
                start += chunkLen
                onProgress((start.toFloat() / totalSamples).coerceIn(0f, 1f))
            }
            sb.toString().trim()
        }
    }

    private fun quietestCut(chunk: FloatArray): Int {
        val win = 320 // 20 ms
        var bestIdx = chunk.size
        var best = Float.MAX_VALUE
        var i = chunk.size - SEARCH_SAMPLES
        while (i + win <= chunk.size) {
            var e = 0f
            for (j in i until i + win) e += chunk[j] * chunk[j]
            if (e < best) { best = e; bestIdx = i + win / 2 }
            i += win
        }
        return bestIdx
    }

    companion object {
        /** 5-minute chunks keep native memory small while giving whisper plenty of context. */
        const val CHUNK_SAMPLES = WavIO.SAMPLE_RATE * 60 * 5
        const val SEARCH_SAMPLES = WavIO.SAMPLE_RATE * 3

        fun defaultThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                cores >= 8 -> 6
                cores >= 4 -> 4
                else -> cores.coerceAtLeast(1)
            }
        }
    }
}
