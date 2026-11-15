package com.miniichat.whisper

/** Callback invoked from native code during transcription (same thread as the call). */
interface WhisperListener {
    fun onProgress(percent: Int)
    fun onSegment(text: String)
}

/** Thin JNI wrapper around whisper.cpp (see app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    val loadError: Throwable? = runCatching { System.loadLibrary("whisper_jni") }.exceptionOrNull()
    val isAvailable: Boolean get() = loadError == null

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
