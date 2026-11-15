package com.miniichat.whisper

import java.io.File

/** Callback invoked from native code during transcription (same thread as the call). */
interface WhisperListener {
    fun onProgress(percent: Int)
    fun onSegment(text: String)
}

/** Thin JNI wrapper around whisper.cpp (see app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    /**
     * Which native build is loaded:
     *  - "armv8.2 dotprod+fp16": optimised build from the :whisper-opt module (modern 64-bit ARM)
     *  - "baseline": portable build (older/32-bit CPUs, emulators)
     */
    var variant: String = "none"
        private set

    val loadError: Throwable? = run {
        val optimised = cpuSupportsV82()
        if (optimised && runCatching { System.loadLibrary("whisper_jni_v82") }.isSuccess) {
            variant = "armv8.2 dotprod+fp16"
            null
        } else {
            runCatching { System.loadLibrary("whisper_jni") }
                .onSuccess { variant = "baseline" }
                .exceptionOrNull()
        }
    }
    val isAvailable: Boolean get() = loadError == null

    /** ARMv8.2 features the optimised build needs: FP16 arithmetic (fphp+asimdhp) and dot product (asimddp). */
    fun cpuSupportsV82(): Boolean = runCatching {
        val features = File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") }
            .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
        // big.LITTLE: every core must support it, as threads can be scheduled anywhere
        features.isNotEmpty() && features.all { f -> "fphp" in f && "asimdhp" in f && "asimddp" in f }
    }.getOrDefault(false)

    external fun initContext(modelPath: String, useGpu: Boolean): Long
    external fun freeContext(ptr: Long)
    external fun requestAbort()
    external fun transcribe(
        ptr: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        translate: Boolean,
        initialPrompt: String?,
        listener: WhisperListener?
    ): String
    external fun systemInfo(): String
    external fun detectedLanguage(ptr: Long): String
}
