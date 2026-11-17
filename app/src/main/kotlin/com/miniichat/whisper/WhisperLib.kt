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
    /**
     * GPU (OpenCL) transcription is switched off: on a Galaxy S23 (Adreno 740) the model loads
     * after the backend fix, but the first encoder run hangs the GPU so hard that the phone
     * must be rebooted. The :whisper-gpu module and all code paths stay in the repo; set this
     * to true AND add `implementation(project(":whisper-gpu"))` back to app/build.gradle.kts
     * to experiment again.
     */
    const val GPU_FEATURE_ENABLED = false

    private const val PREFS = "whisper_engine"
    private const val KEY_GPU = "gpu"
    private const val KEY_GPU_PENDING = "gpu_pending"

    @Volatile private var prefs: SharedPreferences? = null
    private var logFile: File? = null
    private var buildInfo: String = ""

    /** Engine log of the run that crashed (kept until the next crash), or null. */
    var crashLogFile: File? = null
        private set

    /** true if the previous GPU run crashed and GPU was switched off automatically. */
    var gpuCrashRecovered: Boolean = false
        private set

    /** Must be called (with the application context) before the library is first used. */
    fun configure(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        buildInfo = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName + " (" + app.getString(com.miniichat.R.string.build_sha) + ")"
        }.getOrDefault("?")
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val dir = File(app.filesDir, "engine").apply { mkdirs() }
        val log = File(dir, "engine.log")
        val crash = File(dir, "engine-crash.log")
        if (!GPU_FEATURE_ENABLED) {
            // feature off: forget any earlier GPU choice / pending marker, no crash banner
            if (p.getBoolean(KEY_GPU, false) || p.getBoolean(KEY_GPU_PENDING, false)) {
                p.edit().putBoolean(KEY_GPU, false).putBoolean(KEY_GPU_PENDING, false).commit()
            }
            File(dir, "engine-crash.log").delete()
        }
        if (p.getBoolean(KEY_GPU_PENDING, false)) {
            // the process died while the GPU backend was initialising/running → disable it
            p.edit().putBoolean(KEY_GPU, false).putBoolean(KEY_GPU_PENDING, false).commit()
            gpuCrashRecovered = true
            runCatching {
                crash.writeText("=== engine log of the crashed run ===\n" + (if (log.exists()) log.readText().takeLast(200_000) else "(empty)") +
                    "\n\n=== system exit info ===\n" + exitInfo(app))
            }
        }
        if (crash.exists()) crashLogFile = crash
        // keep the log small: start fresh each process, previous run kept as .prev
        runCatching { if (log.exists()) { File(dir, "engine.prev.log").delete(); log.renameTo(File(dir, "engine.prev.log")) } }
        logFile = log
    }

    private fun exitInfo(context: Context): String {
        if (android.os.Build.VERSION.SDK_INT < 30) return "(needs Android 11+)"
        return runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(context.packageName, 0, 3).joinToString("\n") { e ->
                "${java.time.Instant.ofEpochMilli(e.timestamp)} reason=${e.reason} status=${e.status} ${e.description ?: ""}"
            }.ifBlank { "(none)" }
        }.getOrElse { "(unavailable: ${it.message})" }
    }

    fun clearCrashLog() { crashLogFile?.delete(); crashLogFile = null; gpuCrashRecovered = false }

    /** Current engine log (this process). */
    fun currentLog(): String = runCatching { logFile?.readText()?.takeLast(100_000) }.getOrNull().orEmpty()

    /** Flash attention on the GPU (off by default — a frequent cause of OpenCL crashes). */
    var gpuFlashAttention: Boolean
        get() = prefs?.getBoolean("gpu_fa", false) == true
        set(v) { prefs?.edit()?.putBoolean("gpu_fa", v)?.commit() }

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
        if (GPU_FEATURE_ENABLED && v82 && gpuRequested && tryLoad("whisper_jni_gpu")) {
            variant = "GPU (OpenCL) + armv8.2"
            gpuBuildLoaded = true
            startLog()
            // first touch of the OpenCL driver (device probing, kernel setup) happens here → guarded
            cachedDevices = runCatching { guardGpu(true) { parseDevices() } }.getOrDefault(emptyList())
            return null
        }
        if (v82 && tryLoad("whisper_jni_v82")) {
            variant = "armv8.2 dotprod+fp16"
            startLog()
            return null
        }
        return runCatching { System.loadLibrary("whisper_jni") }
            .onSuccess { variant = "baseline"; startLog() }
            .exceptionOrNull()
    }

    private fun startLog() {
        runCatching {
            setLogFile(logFile?.absolutePath)
            logLine("[app] ${java.time.Instant.now()} build=$buildInfo variant=$variant device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}")
        }
    }

    private var cachedDevices: List<Triple<String, String, String>>? = null

    private fun parseDevices(): List<Triple<String, String, String>> =
        listDevices().lines().filter { it.isNotBlank() }.map {
            val p = it.split('\t'); Triple(p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
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
    fun devices(): List<Triple<String, String, String>> {
        if (!isAvailable) return emptyList()
        cachedDevices?.let { return it }
        return runCatching { guardGpu(gpuBuildLoaded) { parseDevices() } }.getOrDefault(emptyList()).also { cachedDevices = it }
    }

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

    external fun initContext(modelPath: String, useGpu: Boolean, flashAttn: Boolean): Long
    /** Append a line to the engine log (loads the library if needed; never throws). */
    fun log(line: String) { if (isAvailable) runCatching { logLine(line) } }

    external fun setLogFile(path: String?)
    external fun logLine(line: String)
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
