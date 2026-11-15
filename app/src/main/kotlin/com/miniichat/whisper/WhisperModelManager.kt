package com.miniichat.whisper

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class DownloadProgress(val bytes: Long, val total: Long, val error: String? = null) {
    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Downloads / imports / deletes ggml models stored in filesDir/whisper. */
class WhisperModelManager(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val dir: File get() = File(app.filesDir, "whisper").apply { mkdirs() }

    private val _installed = MutableStateFlow(scanInstalled())
    /** File names (ggml-*.bin) present on disk. */
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun fileFor(fileName: String): File = File(dir, fileName)

    private fun scanInstalled(): Set<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.map { it.name }?.toSet() ?: emptySet()

    fun refresh() { _installed.value = scanInstalled() }

    fun download(fileName: String, url: String) {
        if (jobs[fileName]?.isActive == true) return
        jobs[fileName] = scope.launch {
            val target = fileFor(fileName)
            val part = File(dir, "$fileName.part")
            try {
                val existing = if (part.exists()) part.length() else 0L
                val req = Request.Builder().url(url).apply {
                    if (existing > 0) header("Range", "bytes=$existing-")
                }.build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val body = resp.body ?: throw RuntimeException("Empty body")
                    val append = resp.code == 206
                    val start = if (append) existing else 0L
                    val total = body.contentLength().let { if (it > 0) it + start else -1L }
                    var done = start
                    _downloads.value = _downloads.value + (fileName to DownloadProgress(done, total))
                    FileOutputStream(part, append).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(256 * 1024)
                            var lastEmit = 0L
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastEmit > 250) {
                                    _downloads.value = _downloads.value + (fileName to DownloadProgress(done, total))
                                    lastEmit = now
                                }
                            }
                        }
                    }
                    if (!isActive) return@launch
                    if (total > 0 && done < total) throw RuntimeException("Download incomplete")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw RuntimeException("Could not move model file")
                _downloads.value = _downloads.value - fileName
                refresh()
            } catch (e: Exception) {
                if (isActive) {
                    _downloads.value = _downloads.value +
                        (fileName to DownloadProgress(0, 0, e.message ?: "Download failed"))
                }
            } finally {
                jobs.remove(fileName)
            }
        }
    }

    fun cancel(fileName: String) {
        jobs.remove(fileName)?.cancel()
        File(dir, "$fileName.part").delete()
        _downloads.value = _downloads.value - fileName
    }

    fun clearError(fileName: String) {
        if (_downloads.value[fileName]?.error != null) _downloads.value = _downloads.value - fileName
    }

    fun delete(fileName: String) {
        fileFor(fileName).delete()
        refresh()
    }

    /** Copy a user-supplied ggml .bin (e.g. a fine-tuned model) into the model dir. */
    suspend fun import(uri: Uri, displayName: String): String = withContext(Dispatchers.IO) {
        var name = displayName.substringAfterLast('/').ifBlank { "custom.bin" }
        if (!name.endsWith(".bin")) name += ".bin"
        if (!name.startsWith("ggml-")) name = "ggml-$name"
        val target = fileFor(name)
        app.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open file" }
            FileOutputStream(target).use { input.copyTo(it, 256 * 1024) }
        }
        refresh()
        name
    }
}
