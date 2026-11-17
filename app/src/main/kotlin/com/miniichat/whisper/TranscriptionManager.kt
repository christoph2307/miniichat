package com.miniichat.whisper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.miniichat.TranscriptionUi
import com.miniichat.audio.AudioDecoder
import com.miniichat.audio.DictaphoneRecorder
import com.miniichat.audio.WavIO
import com.miniichat.data.Attachment
import com.miniichat.data.DraftRepository
import com.miniichat.data.SettingsRepository
import com.miniichat.util.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * App-wide transcription queue, independent of any screen.
 *  - jobs are persisted, so a queue interrupted by the system resumes on the next start
 *  - while work is pending a foreground [TranscriptionService] keeps the process alive
 *    (screen off / app in background) and shows progress as a notification
 *  - results are written straight into the owning chat draft via [DraftRepository]
 */
object TranscriptionManager {
    @Serializable
    data class TxJob(val draftKey: String, val attachment: Attachment? = null, val importUri: String? = null)

    sealed class Event {
        data class Toast(val text: String) : Event()
        data class Error(val text: String) : Event()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var app: Context
    private lateinit var settings: SettingsRepository
    private val transcriber = Transcriber()

    private val queue = ArrayDeque<TxJob>()
    private var worker: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow<TranscriptionUi?>(null)
    val state: StateFlow<TranscriptionUi?> = _state.asStateFlow()

    /** true from the first queued job until the queue is drained or cancelled (drives the service). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        settings = SettingsRepository(app)
        DraftRepository.init(app)
        WhisperLib.configure(app)
        // resume jobs that were pending when the process died
        val saved = runCatching {
            app.getSharedPreferences("transcription_queue", Context.MODE_PRIVATE).getString("jobs", null)
                ?.let { json.decodeFromString(ListSerializer(TxJob.serializer()), it) }
        }.getOrNull().orEmpty()
        if (saved.isNotEmpty()) {
            saved.forEach { queue.addLast(it) }
            startWorker()
        }
    }

    private fun persist() {
        val list = synchronized(queue) { queue.toList() }
        app.getSharedPreferences("transcription_queue", Context.MODE_PRIVATE).edit()
            .putString("jobs", json.encodeToString(ListSerializer(TxJob.serializer()), list)).apply()
    }

    fun queuedCount(): Int = synchronized(queue) { queue.size }

    fun isQueuedOrRunning(uri: String): Boolean =
        synchronized(queue) { queue.any { it.attachment?.uri == uri } } || _state.value?.attachmentUri == uri

    fun enqueueTranscription(att: Attachment, draftKey: String) {
        if (isQueuedOrRunning(att.uri)) return
        enqueue(TxJob(draftKey, att))
    }

    fun enqueueImport(uri: Uri, draftKey: String) = enqueue(TxJob(draftKey, null, uri.toString()))

    private fun enqueue(job: TxJob) {
        synchronized(queue) { queue.addLast(job) }
        persist()
        _state.value = _state.value?.copy(queued = queuedCount())
        startWorker()
    }

    @Synchronized
    private fun startWorker() {
        if (worker?.isActive == true) return
        _busy.value = true
        startService()
        worker = scope.launch {
            try {
                while (true) {
                    val j = synchronized(queue) { queue.firstOrNull() } ?: break
                    try {
                        processJob(j)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        WhisperLib.log("[app] transcription failed: ${e.javaClass.simpleName}: ${e.message}")
                        _events.tryEmit(Event.Error("Transcription failed: ${e.message ?: e.javaClass.simpleName}"))
                    } finally {
                        synchronized(queue) { if (queue.firstOrNull() == j) queue.removeFirst() }
                        persist()
                    }
                }
            } finally {
                _state.value = null
                _busy.value = false   // the service stops itself when it sees this
            }
        }
    }

    fun cancelAll() {
        synchronized(queue) { queue.clear() }
        persist()
        transcriber.abort()
        worker?.cancel()
        worker = null
        _state.value = null
        _busy.value = false
    }

    private suspend fun processJob(job: TxJob) {
        var att = job.attachment
        // 1) imported file → decode to 16 kHz WAV first
        if (job.importUri != null) {
            val uri = Uri.parse(job.importUri)
            val (name, _) = com.miniichat.util.AttachmentLoader.queryNameSize(app.contentResolver, uri)
            _state.value = TranscriptionUi(job.importUri, name, phase = "decoding", queued = queuedCount() - 1)
            val stem = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
            val out = File(DictaphoneRecorder.recordingsDir(app), "import_${System.currentTimeMillis()}_$stem.wav")
            val durationMs = withContext(Dispatchers.IO) {
                AudioDecoder.decodeToWav(app, uri, out) { p -> _state.value = _state.value?.copy(progress = p) }
            }
            val a = Attachment(
                type = "audio", uri = Uri.fromFile(out).toString(), mimeType = "audio/wav",
                name = name, sizeBytes = out.length(), durationMs = durationMs
            )
            att = a
            DraftRepository.update(job.draftKey) { it.copy(attachments = it.attachments + a) }
            val s = settings.settings.first()
            if (!s.autoTranscribe || modelFile(s.whisperModel) == null) return
        }
        val a = att ?: return
        val s = settings.settings.first()
        val model = modelFile(s.whisperModel)
            ?: throw IllegalStateException("No speech model installed (Settings → Speech recognition)")
        val wav = File(Uri.parse(a.uri).path ?: throw IllegalArgumentException("Bad recording path"))
        if (!wav.exists()) throw IllegalStateException("Recording file not found: ${wav.name}")

        _state.value = TranscriptionUi(a.uri, a.name, phase = "loading", queued = queuedCount() - 1)
        WhisperLib.log("[app] transcribe ${a.name} (${a.durationMs} ms) model=${model.name} threads=" +
            (if (s.whisperThreads > 0) "${s.whisperThreads}" else "auto(${Transcriber.defaultThreads()})") +
            " engine=${WhisperLib.variant} ${Transcriber.cpuLayout()}")
        val started = SystemClock.elapsedRealtime()
        val text = transcriber.transcribeWav(
            modelFile = model,
            wav = wav,
            opts = TranscribeOptions(
                language = s.whisperLanguage,
                threads = s.whisperThreads,
                translate = s.whisperTranslate,
                initialPrompt = s.whisperPrompt
            ),
            onProgress = { p -> _state.value = _state.value?.copy(progress = p, phase = "transcribing", queued = queuedCount() - 1) },
            onPartial = { t -> _state.value = _state.value?.copy(partial = t, phase = "transcribing", queued = queuedCount() - 1) }
        )
        val elapsed = SystemClock.elapsedRealtime() - started
        val audioMs = a.durationMs.takeIf { it > 0 } ?: WavIO.readInfo(wav)?.durationMs ?: 0L
        val engine = if (WhisperLib.hasGpuDevice()) "GPU" else "CPU"
        val speed = if (elapsed > 0 && audioMs > 0) "%.1f× realtime".format(audioMs.toDouble() / elapsed) else ""
        val summary = "${formatDuration(audioMs)} audio in ${formatDuration(elapsed)}" +
            (if (speed.isNotEmpty()) " · $speed" else "") + " · $engine · " + model.name.removePrefix("ggml-").removeSuffix(".bin")
        WhisperLib.lastRunSummary = summary
        WhisperLib.log("[app] done: $summary, ${text.length} chars")
        if (text.isBlank()) {
            _events.tryEmit(Event.Toast("No speech recognised ($summary)"))
            return
        }
        _events.tryEmit(Event.Toast("Transcribed: $summary"))
        DraftRepository.update(job.draftKey) { d ->
            val audios = d.attachments.filter { it.type == "audio" }
            val n = audios.indexOfFirst { it.uri == a.uri }.let { if (it < 0) audios.size + 1 else it + 1 }
            val label = "🎙 Recording $n (${formatDuration(audioMs)}):\n"
            d.copy(
                text = if (d.text.isBlank()) label + text else d.text.trimEnd() + "\n\n" + label + text,
                attachments = d.attachments.map { if (it.uri == a.uri) it.copy(transcript = text) else it }
            )
        }
        DraftRepository.flush()
    }

    private fun modelFile(name: String): File? =
        name.takeIf { it.isNotBlank() }?.let { File(File(app.filesDir, "whisper"), it) }?.takeIf { it.exists() }

    fun hasModel(modelName: String): Boolean = modelFile(modelName) != null

    // ── speed test ──
    data class BenchState(val running: Boolean, val results: List<Pair<Int, Double>>, val best: Int? = null, val error: String? = null)
    private val _bench = MutableStateFlow<BenchState?>(null)
    val bench: StateFlow<BenchState?> = _bench.asStateFlow()
    private var benchJob: kotlinx.coroutines.Job? = null

    /** Measure encoder speed for several thread counts; [onBest] receives the fastest. */
    fun runBenchmark(modelName: String, onBest: (Int) -> Unit) {
        if (_busy.value || benchJob?.isActive == true) {
            _bench.value = BenchState(false, emptyList(), error = "Busy — wait until the transcription has finished")
            return
        }
        val model = modelFile(modelName) ?: run {
            _bench.value = BenchState(false, emptyList(), error = "No speech model selected"); return
        }
        val cores = Runtime.getRuntime().availableProcessors()
        val counts = (listOf(2, 3, 4, 5, 6, 8) + Transcriber.defaultThreads()).filter { it in 1..cores }.distinct().sorted()
        _bench.value = BenchState(true, emptyList())
        benchJob = scope.launch {
            try {
                val results = transcriber.benchmark(model, counts) { t, ms ->
                    _bench.value = _bench.value?.copy(results = _bench.value!!.results + (t to ms))
                }.filter { it.second > 0 }
                val best = results.minByOrNull { it.second }?.first
                _bench.value = BenchState(false, results, best)
                if (best != null) onBest(best)
            } catch (e: CancellationException) {
                _bench.value = null
                throw e
            } catch (e: Throwable) {
                _bench.value = BenchState(false, _bench.value?.results.orEmpty(), error = e.message ?: "Benchmark failed")
            }
        }
    }

    private fun startService() {
        runCatching {
            val i = Intent(app, TranscriptionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
        }.onFailure { WhisperLib.log("[app] could not start foreground service: ${it.message}") }
    }

}
