package com.miniichat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichat.api.ChatMessage
import com.miniichat.api.LlmClient
import com.miniichat.data.AppSettings
import com.miniichat.data.Assistant
import com.miniichat.data.AssistantStore
import com.miniichat.data.Conversation
import com.miniichat.data.ConversationStore
import com.miniichat.data.Message
import com.miniichat.data.ProviderConfig
import com.miniichat.data.ProviderStore
import com.miniichat.data.SettingsRepository
import com.miniichat.util.PromptVars
import com.miniichat.util.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.miniichat.audio.AudioDecoder
import com.miniichat.audio.DictaphoneRecorder
import com.miniichat.audio.FinishedRecording
import com.miniichat.data.Attachment
import com.miniichat.whisper.TranscribeOptions
import com.miniichat.whisper.Transcriber
import com.miniichat.whisper.WhisperModelManager
import java.io.File

/** UI state of an in-flight local transcription. */
data class TranscriptionUi(
    val attachmentUri: String,
    val label: String,
    val progress: Float = 0f,
    val partial: String = "",
    val phase: String = "transcribing"   // "decoding" | "loading" | "transcribing"
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConversationStore(app)
    val settingsRepo = SettingsRepository(app)
    val providerStore = ProviderStore(app)
    val assistantStore = AssistantStore(app)
    private val client = LlmClient()

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val providers: StateFlow<List<ProviderConfig>> = providerStore.providersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val assistants: StateFlow<List<Assistant>> = assistantStore.assistantsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val conversations: StateFlow<List<Conversation>> = store.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** In-memory overlay for the message currently being streamed.
     *  Avoids a DataStore write on every token while keeping the UI live. */
    private val _streamingOverlay = MutableStateFlow<Pair<String, String>?>(null)
    val streamingOverlay: StateFlow<Pair<String, String>?> = _streamingOverlay.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _fetchingModelsFor = MutableStateFlow<String?>(null)
    val fetchingModelsFor: StateFlow<String?> = _fetchingModelsFor.asStateFlow()

    private var streamingJob: Job? = null

    // ---------------- Dictaphone + local speech recognition ----------------

    val modelManager = WhisperModelManager(app)
    private val transcriber = Transcriber()
    private var transcribeJob: Job? = null

    private val _transcription = MutableStateFlow<TranscriptionUi?>(null)
    val transcription: StateFlow<TranscriptionUi?> = _transcription.asStateFlow()

    /** Finished transcript waiting to be merged into the chat input by the UI. */
    private val _pendingTranscript = MutableStateFlow<Pair<String, String>?>(null) // uri to text
    val pendingTranscript: StateFlow<Pair<String, String>?> = _pendingTranscript.asStateFlow()
    fun consumePendingTranscript() { _pendingTranscript.value = null }

    /** Attachment updates (e.g. transcript filled in) for attachments still in the composer. */
    private val _attachmentUpdates = MutableStateFlow<Attachment?>(null)
    val attachmentUpdates: StateFlow<Attachment?> = _attachmentUpdates.asStateFlow()
    fun consumeAttachmentUpdate() { _attachmentUpdates.value = null }

    fun recordingToAttachment(rec: FinishedRecording): Attachment = Attachment(
        type = "audio",
        uri = android.net.Uri.fromFile(rec.file).toString(),
        mimeType = "audio/wav",
        name = rec.file.name,
        sizeBytes = rec.sizeBytes,
        durationMs = rec.durationMs
    )

    private fun activeModelFile(): File? {
        val name = settings.value.whisperModel
        if (name.isBlank()) return null
        val f = modelManager.fileFor(name)
        return if (f.exists()) f else null
    }

    fun hasSpeechModel(): Boolean = activeModelFile() != null

    /** Transcribe an audio attachment (16 kHz WAV) with the selected local whisper model. */
    fun transcribe(att: Attachment) {
        if (transcribeJob?.isActive == true) {
            _toast.value = "A transcription is already running"
            return
        }
        val model = activeModelFile()
        if (model == null) {
            _error.value = "No speech model installed. Open Settings → Speech recognition and download a Whisper model."
            return
        }
        val path = android.net.Uri.parse(att.uri).path ?: return
        val wav = File(path)
        val s = settings.value
        transcribeJob = viewModelScope.launch {
            _transcription.value = TranscriptionUi(att.uri, att.name, phase = "loading")
            try {
                val text = transcriber.transcribeWav(
                    modelFile = model,
                    wav = wav,
                    opts = TranscribeOptions(
                        language = s.whisperLanguage,
                        threads = s.whisperThreads,
                        translate = s.whisperTranslate,
                        initialPrompt = s.whisperPrompt
                    ),
                    onProgress = { p ->
                        _transcription.value = _transcription.value?.copy(progress = p, phase = "transcribing")
                    },
                    onPartial = { t ->
                        _transcription.value = _transcription.value?.copy(partial = t, phase = "transcribing")
                    }
                )
                if (text.isBlank()) {
                    _toast.value = "No speech recognised"
                } else {
                    _attachmentUpdates.value = att.copy(transcript = text)
                    _pendingTranscript.value = att.uri to text
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Transcription failed: ${e.message}"
            } finally {
                _transcription.value = null
            }
        }
    }

    fun cancelTranscription() {
        transcriber.abort()
        transcribeJob?.cancel()
        transcribeJob = null
        _transcription.value = null
    }

    /** Called by the UI when the dictaphone stops. */
    fun onRecordingFinished(rec: FinishedRecording, autoTranscribe: Boolean, add: (Attachment) -> Unit) {
        val att = recordingToAttachment(rec)
        add(att)
        if (autoTranscribe && settings.value.autoTranscribe) {
            if (hasSpeechModel()) transcribe(att)
            else _toast.value = "Recording saved. Install a Whisper model to transcribe it."
        }
    }

    /** Import an existing audio file: decode to 16 kHz WAV, attach it, optionally transcribe. */
    fun importAudio(uri: android.net.Uri, add: (Attachment) -> Unit) {
        if (transcribeJob?.isActive == true) {
            _toast.value = "Please wait for the running transcription"
            return
        }
        val app = getApplication<Application>()
        transcribeJob = viewModelScope.launch {
            val (name, _) = com.miniichat.util.AttachmentLoader.queryNameSize(app.contentResolver, uri)
            _transcription.value = TranscriptionUi(uri.toString(), name, phase = "decoding")
            try {
                val stem = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
                val out = File(DictaphoneRecorder.recordingsDir(app),
                    "import_${System.currentTimeMillis()}_$stem.wav")
                val durationMs = withContext(Dispatchers.IO) {
                    AudioDecoder.decodeToWav(app, uri, out) { p ->
                        _transcription.value = _transcription.value?.copy(progress = p)
                    }
                }
                val att = recordingToAttachment(FinishedRecording(out, durationMs, out.length()))
                    .copy(name = name)
                add(att)
                _transcription.value = null
                transcribeJob = null
                if (settings.value.autoTranscribe && hasSpeechModel()) transcribe(att)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Could not import audio: ${e.message}"
                _transcription.value = null
            }
        }
    }

    fun recordingsUsage(): Pair<Int, Long> {
        val files = DictaphoneRecorder.recordingsDir(getApplication()).listFiles()?.toList().orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    /** Deletes recordings not referenced by any saved conversation. */
    fun deleteUnusedRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            val used = store.snapshot().flatMap { c -> c.messages.flatMap { m -> m.attachments } }
                .mapNotNull { android.net.Uri.parse(it.uri).path }.toSet()
            var n = 0
            DictaphoneRecorder.recordingsDir(getApplication()).listFiles()?.forEach { f ->
                if (f.absolutePath !in used && DictaphoneRecorder.state.value.file != f) {
                    if (f.delete()) n++
                }
            }
            _toast.value = "Deleted $n unused recordings"
        }
    }

    override fun onCleared() {
        cancelTranscription()
        transcriber.release()
        super.onCleared()
    }

    fun selectConversation(id: String?) { _activeId.value = id }

    fun newConversation(): String {
        val id = newId()
        _activeId.value = id
        return id
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            store.delete(id)
            if (_activeId.value == id) _activeId.value = null
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { store.rename(id, title) }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        _isStreaming.value = false
    }

    fun clearError() { _error.value = null }
    fun clearToast() { _toast.value = null }

    fun activeProvider(): ProviderConfig? =
        providers.value.firstOrNull { it.id == settings.value.activeProviderId }

    fun activeAssistant(): Assistant? =
        assistants.value.firstOrNull { it.id == settings.value.activeAssistantId }

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeProviderId = providerId, activeModel = model) }
        }
    }

    fun selectAssistant(id: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeAssistantId = id) }
        }
    }

    fun upsertAssistant(a: Assistant) {
        viewModelScope.launch { assistantStore.upsert(a) }
    }

    fun deleteAssistant(id: String) {
        viewModelScope.launch {
            assistantStore.delete(id)
            if (settings.value.activeAssistantId == id) {
                val remaining = assistantStore.snapshot()
                settingsRepo.update {
                    it.copy(activeAssistantId = remaining.firstOrNull()?.id ?: "default")
                }
            }
        }
    }

    fun upsertProvider(p: ProviderConfig) {
        viewModelScope.launch {
            providerStore.upsert(p)
            val all = providerStore.snapshot()
            if (settings.value.activeProviderId.isBlank() && all.isNotEmpty()) {
                val target = all.firstOrNull { it.id == p.id } ?: all.first()
                val firstModel = target.models.firstOrNull() ?: ""
                settingsRepo.update {
                    it.copy(activeProviderId = target.id, activeModel = firstModel)
                }
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            providerStore.delete(id)
            if (settings.value.activeProviderId == id) {
                val remaining = providerStore.snapshot()
                val nextProvider = remaining.firstOrNull()
                settingsRepo.update {
                    it.copy(
                        activeProviderId = nextProvider?.id ?: "",
                        activeModel = nextProvider?.models?.firstOrNull() ?: ""
                    )
                }
            }
        }
    }

    fun fetchModels(providerId: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            _fetchingModelsFor.value = providerId
            try {
                val models = client.listModels(provider)
                if (models.isEmpty()) {
                    _toast.value = "No models returned from /models"
                } else {
                    val updated = provider.copy(models = (provider.models + models).distinct().sorted())
                    providerStore.upsert(updated)
                    _toast.value = "Fetched ${models.size} models"
                }
            } catch (e: Exception) {
                _error.value = "Fetch models failed: ${e.message}"
            } finally {
                _fetchingModelsFor.value = null
            }
        }
    }

    fun addManualModel(providerId: String, model: String) {
        viewModelScope.launch {
            val trimmed = model.trim()
            if (trimmed.isEmpty()) return@launch
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            val updated = provider.copy(models = (provider.models + trimmed).distinct().sorted())
            providerStore.upsert(updated)
        }
    }

    fun removeModel(providerId: String, model: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            val updated = provider.copy(models = provider.models - model)
            providerStore.upsert(updated)
            if (settings.value.activeProviderId == providerId && settings.value.activeModel == model) {
                settingsRepo.update {
                    it.copy(activeModel = updated.models.firstOrNull() ?: "")
                }
            }
        }
    }

    fun sendMessage(text: String, attachments: List<com.miniichat.data.Attachment> = emptyList()) {
        // Reject re-entrant sends while a stream is in flight (debounce double-tap)
        if (_isStreaming.value || streamingJob?.isActive == true) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return

        val current = settings.value
        val assistant = activeAssistant()

        // Resolve effective provider and model: assistant override > settings active
        val provider = assistant?.preferredProviderId
            ?.let { id -> providers.value.firstOrNull { it.id == id } }
            ?: activeProvider()
        if (provider == null) {
            _error.value = "No provider configured."
            return
        }

        val model = assistant?.preferredModel?.takeIf { it.isNotBlank() }
            ?: current.activeModel
        if (model.isBlank()) {
            _error.value = "No model selected."
            return
        }

        if (provider.apiKey.isBlank()
            && !provider.baseUrl.contains("localhost")
            && !provider.baseUrl.contains("10.0.2.2")
        ) {
            _error.value = "API key is empty for ${provider.name}."
            return
        }

        val temperature = assistant?.temperature ?: current.temperature

        val systemPromptRaw = assistant?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: current.systemPrompt
        val systemPrompt = PromptVars.render(
            template = systemPromptRaw,
            model = model,
            provider = provider.name,
            assistant = assistant?.name ?: ""
        )

        viewModelScope.launch {
            val activeId = _activeId.value ?: newId().also { _activeId.value = it }
            val existing = store.snapshot().firstOrNull { it.id == activeId }
            val baseTitle = trimmed.take(30).replace("\n", " ")

            val userMsg = Message(
                id = newId(),
                role = "user",
                content = trimmed,
                attachments = attachments
            )
            val assistantId = newId()
            val assistantPlaceholder = Message(id = assistantId, role = "assistant", content = "")

            val updated = (existing ?: Conversation(id = activeId, title = baseTitle, messages = emptyList()))
                .let { conv ->
                    conv.copy(
                        title = if (conv.messages.isEmpty()) baseTitle else conv.title,
                        messages = conv.messages + userMsg + assistantPlaceholder,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            store.upsert(updated)

            val historyForApi = mutableListOf<ChatMessage>()
            if (systemPrompt.isNotBlank()) {
                historyForApi.add(ChatMessage("system", systemPrompt))
            }
            updated.messages
                .filter { !(it.role == "assistant" && it.content.isEmpty()) }
                .forEach { msg ->
                    if (msg.role == "user" && msg.attachments.isNotEmpty()) {
                        historyForApi.add(buildUserMessage(msg, current.sendAudioToModel))
                    } else {
                        historyForApi.add(ChatMessage(msg.role, msg.content))
                    }
                }

            _isStreaming.value = true
            val builder = StringBuilder()

            // Use settings.copy with assistant temperature override
            val effectiveSettings = current.copy(temperature = temperature)

            streamingJob = launch {
                var lastFlush = 0L
                val flushIntervalMs = 250L
                _streamingOverlay.value = assistantId to ""
                try {
                    client.chatStream(provider, effectiveSettings, model, historyForApi)
                        .catch { e ->
                            _error.value = e.message ?: "Request failed"
                            val finalContent = if (builder.isEmpty()) "(error: ${e.message})" else builder.toString()
                            appendAssistant(activeId, assistantId, finalContent)
                        }
                        .collect { delta ->
                            builder.append(delta)
                            // 1) update in-memory overlay every token (no IO)
                            _streamingOverlay.value = assistantId to builder.toString()
                            // 2) throttled DataStore flush
                            val now = System.currentTimeMillis()
                            if (now - lastFlush >= flushIntervalMs) {
                                appendAssistant(activeId, assistantId, builder.toString())
                                lastFlush = now
                            }
                        }
                } finally {
                    if (builder.isNotEmpty()) {
                        appendAssistant(activeId, assistantId, builder.toString())
                    }
                    _streamingOverlay.value = null
                    _isStreaming.value = false
                    streamingJob = null
                }
            }
        }
    }

    /**
     * Build an API message from a user message with attachments:
     * images → image_url parts, audio → transcript text (+ optional input_audio part),
     * other files → filename references.
     */
    private fun buildUserMessage(msg: Message, sendAudio: Boolean): ChatMessage {
        val resolver = getApplication<Application>().contentResolver
        val parts = mutableListOf<com.miniichat.api.ChatPart>()
        val notes = mutableListOf<String>()

        for (att in msg.attachments) {
            when (att.type) {
                "image" -> {
                    val loaded = runCatching {
                        com.miniichat.util.AttachmentLoader.load(
                            resolver, android.net.Uri.parse(att.uri), att.mimeType.ifBlank { "image/jpeg" }
                        )
                    }.getOrNull()
                    if (loaded != null) {
                        parts.add(com.miniichat.api.ChatPart(
                            type = "image_url",
                            imageUrl = com.miniichat.api.ChatPart.ImageUrl(
                                url = "data:${loaded.mimeType};base64,${loaded.base64}"
                            )
                        ))
                    } else notes.add("[image unavailable: ${att.name}]")
                }
                "audio" -> {
                    val dur = com.miniichat.util.formatDuration(att.durationMs)
                    // Only add the transcript if the user didn't already paste it into the message.
                    val t = att.transcript?.trim().orEmpty()
                    if (t.isNotEmpty() && !msg.content.contains(t.take(60))) {
                        notes.add("[voice recording ${att.name}, $dur — transcript]:\n$t")
                    } else {
                        notes.add("[voice recording ${att.name}, $dur]")
                    }
                    if (sendAudio) {
                        val loaded = runCatching {
                            com.miniichat.util.AttachmentLoader.load(
                                resolver, android.net.Uri.parse(att.uri), "audio/wav"
                            )
                        }.getOrNull()
                        if (loaded != null) {
                            parts.add(com.miniichat.api.ChatPart(
                                type = "input_audio",
                                inputAudio = com.miniichat.api.ChatPart.InputAudio(data = loaded.base64, format = "wav")
                            ))
                        } else notes.add("(audio too large to upload, transcript only)")
                    }
                }
                else -> notes.add("[file: ${att.name} (${att.mimeType})]")
            }
        }

        val text = listOf(msg.content, notes.joinToString("\n\n"))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        if (parts.isEmpty()) return ChatMessage(msg.role, text)
        val all = mutableListOf<com.miniichat.api.ChatPart>()
        if (text.isNotBlank()) all.add(com.miniichat.api.ChatPart(type = "text", text = text))
        all.addAll(parts)
        return ChatMessage(msg.role, text, all)
    }

    private suspend fun appendAssistant(convId: String, msgId: String, content: String) {
        val list = store.snapshot()
        val conv = list.firstOrNull { it.id == convId } ?: return
        val newMsgs = conv.messages.map { if (it.id == msgId) it.copy(content = content) else it }
        store.upsert(conv.copy(messages = newMsgs, updatedAt = System.currentTimeMillis()))
    }

    fun regenerate() {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val msgs = conv.messages
            val lastUserIdx = msgs.indexOfLast { it.role == "user" }
            if (lastUserIdx < 0) return@launch
            val lastUser = msgs[lastUserIdx]
            val trimmed = msgs.subList(0, lastUserIdx + 1)
            store.upsert(conv.copy(messages = trimmed, updatedAt = System.currentTimeMillis()))
            sendMessage(lastUser.content, lastUser.attachments)
        }
    }

    /** Branch from a specific user message — drops everything after it and re-sends. */
    fun regenerateFrom(messageId: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val msgs = conv.messages
            val idx = msgs.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch
            val target = msgs[idx]
            if (target.role != "user") return@launch
            val trimmed = msgs.subList(0, idx + 1)
            store.upsert(conv.copy(messages = trimmed, updatedAt = System.currentTimeMillis()))
            sendMessage(target.content, target.attachments)
        }
    }

    /** Delete a single message in the current conversation. */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val newMsgs = conv.messages.filterNot { it.id == messageId }
            store.upsert(conv.copy(messages = newMsgs, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Edit a message's text content (no resend). */
    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val newMsgs = conv.messages.map {
                if (it.id == messageId) it.copy(content = newContent) else it
            }
            store.upsert(conv.copy(messages = newMsgs, updatedAt = System.currentTimeMillis()))
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }
}
