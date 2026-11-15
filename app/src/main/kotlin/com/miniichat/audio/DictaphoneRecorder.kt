package com.miniichat.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

enum class RecorderStatus { Idle, Recording, Paused }

data class RecorderState(
    val status: RecorderStatus = RecorderStatus.Idle,
    val elapsedMs: Long = 0,
    /** 0..1 RMS level of the latest buffer, for the level meter. */
    val level: Float = 0f,
    val file: File? = null,
    val error: String? = null
)

data class FinishedRecording(val file: File, val durationMs: Long, val sizeBytes: Long)

/**
 * Process-wide dictaphone. Records 16 kHz mono PCM16 straight into a WAV file
 * (the exact format whisper.cpp consumes, so no re-encoding is needed later).
 * A foreground [RecordingService] keeps the process alive while recording.
 */
object DictaphoneRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var discard = false
    private var onFinished: ((FinishedRecording?) -> Unit)? = null

    fun recordingsDir(ctx: Context): File =
        File(ctx.filesDir, "recordings").apply { mkdirs() }

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start(ctx: Context): Boolean {
        if (_state.value.status != RecorderStatus.Idle) return false
        val app = ctx.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(recordingsDir(app), "rec_$stamp.wav")

        val minBuf = AudioRecord.getMinBufferSize(
            WavIO.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            _state.value = RecorderState(error = "Audio recording not supported on this device")
            return false
        }
        val bufSize = maxOf(minBuf * 2, WavIO.SAMPLE_RATE / 5 * 2) // ≥200 ms
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WavIO.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            _state.value = RecorderState(error = "Microphone unavailable")
            return false
        }

        paused = false; stopRequested = false; discard = false
        _state.value = RecorderState(status = RecorderStatus.Recording, file = file)
        startService(app)

        job = scope.launch {
            var dataBytes = 0L
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(0)
                    WavIO.writeHeader(raf, 0)
                    raf.seek(WavIO.HEADER_SIZE.toLong())
                    val buf = ShortArray(bufSize / 2)
                    val bytes = ByteArray(buf.size * 2)
                    recorder.startRecording()
                    var lastHeaderPatch = System.currentTimeMillis()
                    while (isActive && !stopRequested) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        if (paused) {
                            _state.value = _state.value.copy(level = 0f)
                            continue
                        }
                        var sum = 0.0
                        for (i in 0 until n) {
                            val s = buf[i].toInt()
                            sum += (s * s).toDouble()
                            bytes[i * 2] = (s and 0xff).toByte()
                            bytes[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
                        }
                        raf.write(bytes, 0, n * 2)
                        dataBytes += n * 2
                        val rms = sqrt(sum / n) / 32768.0
                        // perceptual-ish scaling so normal speech fills ~half the meter
                        val level = (rms * 6).coerceIn(0.0, 1.0).toFloat()
                        _state.value = _state.value.copy(
                            elapsedMs = dataBytes * 1000 / (WavIO.SAMPLE_RATE * 2),
                            level = level
                        )
                        // keep header valid every few seconds so a crash still leaves a playable file
                        val now = System.currentTimeMillis()
                        if (now - lastHeaderPatch > 5000) {
                            val pos = raf.filePointer
                            WavIO.writeHeader(raf, dataBytes)
                            raf.seek(pos)
                            lastHeaderPatch = now
                        }
                    }
                    WavIO.writeHeader(raf, dataBytes)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Recording failed")
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                stopService(app)
                val cb = onFinished
                onFinished = null
                val result = if (discard || dataBytes == 0L) {
                    file.delete(); null
                } else FinishedRecording(
                    file = file,
                    durationMs = dataBytes * 1000 / (WavIO.SAMPLE_RATE * 2),
                    sizeBytes = file.length()
                )
                _state.value = RecorderState(error = _state.value.error)
                cb?.invoke(result)
            }
        }
        return true
    }

    fun pause() {
        if (_state.value.status == RecorderStatus.Recording) {
            paused = true
            _state.value = _state.value.copy(status = RecorderStatus.Paused, level = 0f)
        }
    }

    fun resume() {
        if (_state.value.status == RecorderStatus.Paused) {
            paused = false
            _state.value = _state.value.copy(status = RecorderStatus.Recording)
        }
    }

    /** Stop and keep the file. [callback] runs on a background thread. */
    fun stop(callback: (FinishedRecording?) -> Unit) {
        if (_state.value.status == RecorderStatus.Idle) { callback(null); return }
        onFinished = callback
        stopRequested = true
    }

    /** Stop and delete the file. */
    fun cancel() {
        if (_state.value.status == RecorderStatus.Idle) return
        discard = true
        onFinished = null
        stopRequested = true
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    private fun startService(ctx: Context) {
        val i = Intent(ctx, RecordingService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private fun stopService(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, RecordingService::class.java)) }
    }
}
