package com.miniichat.whisper

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Callback invoked from native code during transcription (same thread as the call). */
interface WhisperListener {
    fun onProgress(percent: Int)
    fun onSegment(text: String)
}

/**
 * Thin JNI wrapper around whisper.cpp (see app/src/main/cpp/whisper_jni.cpp).
 *
 * Three native builds of the same code exist; exactly one is loaded per process:
 *  - `whisper_jni_gpu`  – ARMv8.2 + OpenCL GPU backend (Adreno-optimised). Only when the user
 *                          enabled "Use GPU" and the vendor libOpenCL.so is available.
 *  - `whisper_jni_v82`  – ARMv8.2-A dot-product + FP16 CPU build (modern 64-bit phones).
 *  - `whisper_jni`      – portable baseline (older / 32-bit CPUs).
 * Changing the GPU setting therefore needs an app restart.
 */
object WhisperLib {
    private const val PREFS = "whisper_engine"
    private const val KEY_GPU = "gpu"
    private const val KEY_GPU_PENDING = "gpu_pending"

    @Volatile private var prefs: SharedPreferences? = null

    /** true if the previous GPU run crashed and GPU was switched off automatically. */
    var gpuCrashRecovered: Boolean = false
        private set

    /** Must be called (with the application context) before the library is first used. */
    fun configure(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        if (p.getBoolean(KEY_GPU_PENDING, false)) {
            // the process died while the GPU backend was initialising/running → disable it
            p.edit().putBoolean(KEY_GPU, false).putBoolean(KEY_GPU_PENDING, false).commit()
            gpuCrashRecovered = true
        }
    }

    var gpuRequested: Boolean
        get() = prefs?.getBoolean(KEY_GPU, false) == true
        set(v) { prefs?.edit()?.putBoolean(KEY_GPU, v)?.commit() }

    /** Human-readable name of the loaded build. */
    var variant: String = "none"
        private set

    /** true if the GPU build is loaded (whether a GPU device was actually found is in [devices]). */
    var gpuBuildLoaded: Boolean = false
        private set

    val loadError: Throwable? by lazy { load() }
    val isAvailable: Boolean get() = loadError == null

    private fun tryLoad(name: String): Boolean = runCatching { System.loadLibrary(name) }.isSuccess

    private fun load(): Throwable? {
        val v82 = cpuSupportsV82()
        if (v82 && gpuRequested && tryLoad("whisper_jni_gpu")) {
            variant = "GPU (OpenCL) + armv8.2"
            gpuBuildLoaded = true
            return null
        }
        if (v82 && tryLoad("whisper_jni_v82")) {
            variant = "armv8.2 dotprod+fp16"
            return null
        }
        return runCatching { System.loadLibrary("whisper_jni") }
            .onSuccess { variant = "baseline" }
            .exceptionOrNull()
    }

    /** ARMv8.2 features the optimised builds need: FP16 arithmetic (fphp+asimdhp) and dot product (asimddp). */
    fun cpuSupportsV82(): Boolean = runCatching {
        val features = File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") }
            .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
        // big.LITTLE: every core must support it, as threads can be scheduled anywhere
        features.isNotEmpty() && features.all { f -> "fphp" in f && "asimdhp" in f && "asimddp" in f }
    }.getOrDefault(false)

    /** Compute devices as (type, name, description); empty if the library isn't loaded. */
    fun devices(): List<Triple<String, String, String>> =
        if (!isAvailable) emptyList() else runCatching {
            listDevices().lines().filter { it.isNotBlank() }.map {
                val p = it.split('\t'); Triple(p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
            }
        }.getOrDefault(emptyList())

    fun hasGpuDevice(): Boolean = gpuBuildLoaded && devices().any { it.first == "GPU" || it.first == "IGPU" }

    /** Wrap GPU work so that a native crash disables the GPU on the next start. */
    inline fun <T> guardGpu(useGpu: Boolean, block: () -> T): T {
        if (useGpu) markGpuPending(true)
        try { return block() } finally { if (useGpu) markGpuPending(false) }
    }

    fun markGpuPending(pending: Boolean) { prefs?.edit()?.putBoolean(KEY_GPU_PENDING, pending)?.commit() }

    // ── persisted stats of the last transcription (shown in Settings) ──
    var lastRunSummary: String?
        get() = prefs?.getString("last_run", null)
        set(v) { prefs?.edit()?.putString("last_run", v)?.apply() }

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
    /** One line per compute device: "TYPE\tname\tdescription". */
    external fun listDevices(): String
    /** true if this native build contains the OpenCL GPU backend. */
    external fun hasGpuBackend(): Boolean
    external fun detectedLanguage(ptr: Long): String
}
