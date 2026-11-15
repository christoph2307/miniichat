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
import com.miniichat.wiki.WikiIndex
import com.miniichat.wiki.WikiLinker
import com.miniichat.wiki.WikiNote
import com.miniichat.wiki.WikiStore
import com.miniichat.wiki.Frontmatter
import com.miniichat.export.ExportBundle
import com.miniichat.export.ExportConfigStore
import com.miniichat.export.ExportOptions
import com.miniichat.export.ExportPreparer
import com.miniichat.export.ExportProgress
import com.miniichat.export.ExportRegistry
import com.miniichat.export.ExportResult
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** State of the "AI links" librarian dialog. */
data class AiLinkState(
    val noteId: String,
    val loading: Boolean = true,
    val result: WikiLinker.Result? = null,
    val error: String? = null
)

data class ExportRun(val targetName: String, val done: Int = 0, val total: Int = 0, val current: String = "", val result: ExportResult? = null)

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

    // ─────────────────────────────── Wiki ───────────────────────────────

    val wikiStore = WikiStore(File(app.filesDir, "wiki"))
    val wikiNotes: StateFlow<List<WikiNote>> = wikiStore.notes
    val wikiFolders: StateFlow<Set<String>> = wikiStore.folders
    val wikiIndex: StateFlow<WikiIndex> = wikiStore.notes.map { WikiIndex(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WikiIndex(emptyList()))

    /** One-shot navigation request (note id) consumed by AppRoot. */
    private val _openNote = MutableStateFlow<String?>(null)
    val openNote: StateFlow<String?> = _openNote.asStateFlow()
    fun consumeOpenNote() { _openNote.value = null }
    fun requestOpenNote(id: String) { _openNote.value = id }

    private val _aiLink = MutableStateFlow<AiLinkState?>(null)
    val aiLink: StateFlow<AiLinkState?> = _aiLink.asStateFlow()
    private var aiLinkJob: Job? = null

    val exportConfig = ExportConfigStore(app)
    private val _exportRun = MutableStateFlow<ExportRun?>(null)
    val exportRun: StateFlow<ExportRun?> = _exportRun.asStateFlow()
    fun clearExportRun() { _exportRun.value = null }

    init {
        viewModelScope.launch { wikiStore.load() }
    }

    /** Unwrap a reply that the model put inside a single ```markdown fence. */
    private fun unwrapFence(md: String): String {
        val t = md.trim()
        val m = Regex("^```(?:markdown|md)?\\s*\\n([\\s\\S]*?)\\n```\\s*$").matchEntire(t)
        return m?.groupValues?.get(1) ?: md
    }

    /**
     * Save an assistant reply as a wiki note:
     * frontmatter/H1 → title/folder/tags, photos of the preceding prompt → assets + embeds,
     * optional raw transcript, then local auto-linking. Saving the same message again updates the note.
     */
    fun saveMessageToWiki(messageId: String, archive: Boolean = false) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val idx = conv.messages.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch
            val msg = conv.messages[idx]
            val prompt = conv.messages.subList(0, idx).lastOrNull { it.role == "user" }
            val s = settings.value
            val source = "chat:$convId/$messageId"
            val existing = wikiStore.findBySource(source)

            val parsed = Frontmatter.toNote(unwrapFence(msg.content), existing?.id ?: WikiStore.newId(), conv.title.ifBlank { "Note" })
            var body = parsed.body.trim()

            if (prompt != null && s.wikiSaveImages) {
                val embeds = mutableListOf<String>()
                for (att in prompt.attachments.filter { it.type == "image" }) {
                    val name = runCatching {
                        getApplication<Application>().contentResolver.openInputStream(android.net.Uri.parse(att.uri))!!.use {
                            wikiStore.addAsset(it, att.name)
                        }
                    }.getOrNull() ?: continue
                    embeds.add("![[$name]]")
                }
                if (embeds.isNotEmpty() && existing == null) body += "\n\n## Images\n" + embeds.joinToString("\n")
            }
            if (prompt != null && s.wikiSaveTranscript) {
                val tr = prompt.attachments.mapNotNull { it.transcript }.filter { it.isNotBlank() }
                if (tr.isNotEmpty() && existing == null) {
                    body += "\n\n> [!quote]- Transcript\n" + tr.joinToString("\n>\n") { t -> t.lines().joinToString("\n") { "> $it" } }
                }
            }

            var note = parsed.copy(
                id = existing?.id ?: parsed.id,
                title = if (existing != null) wikiStore.uniqueTitle(parsed.title, existing.id) else wikiStore.uniqueTitle(parsed.title),
                folder = parsed.folder.ifBlank { existing?.folder ?: s.wikiDefaultFolder },
                created = existing?.created ?: System.currentTimeMillis(),
                source = source,
                body = body
            )
            if (s.wikiAutoLinkOnSave) {
                val (linked, _) = WikiIndex(wikiStore.notes.value.filter { it.id != note.id } + note).autoLink(note.body, note.id)
                note = note.copy(body = linked)
            }
            // back-link to the originating chat (in-app link; stripped on export)
            if (!note.body.contains("miniichat://chat/$convId")) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(conv.createdAt))
                val chatTitle = conv.title.ifBlank { "Chat" }.replace("[", "(").replace("]", ")")
                note = note.copy(body = note.body.trimEnd() + "\n\n> [!info]- Source\n> Chat: [$chatTitle](miniichat://chat/$convId) · $date\n")
            }
            val saved = wikiStore.save(note)
            store.update(convId) { it.copy(wikiNoteId = saved.id, archived = it.archived || archive) }
            if (archive && _activeId.value == convId) _activeId.value = null
            _toast.value = (if (existing != null) "Updated wiki note: " else "Saved to wiki: ") + saved.title +
                if (archive) " · chat archived" else ""
            _openNote.value = saved.id
        }
    }

    /** ✓ Accept: save the reply to the wiki, link the chat, archive it and start a fresh chat. */
    fun acceptReply(messageId: String) = saveMessageToWiki(messageId, archive = true)

    /**
     * ✗ Reject: delete this answer (and anything after it) and regenerate from the prompt
     * that produced it, optionally with reviewer feedback.
     */
    fun rejectReply(messageId: String, feedback: String? = null) {
        if (_isStreaming.value) return
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val idx = conv.messages.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch
            val userIdx = conv.messages.subList(0, idx).indexOfLast { it.role == "user" }
            if (userIdx < 0) return@launch
            val prompt = conv.messages[userIdx]
            store.upsert(conv.copy(messages = conv.messages.subList(0, userIdx), updatedAt = System.currentTimeMillis()))
            sendMessage(prompt.content, prompt.attachments, feedback)
        }
    }

    fun archiveConversation(id: String, archived: Boolean = true) {
        viewModelScope.launch {
            store.update(id) { it.copy(archived = archived) }
            if (archived && _activeId.value == id) _activeId.value = null
        }
    }

    /** Open a chat (e.g. from a wiki note's source link), even if archived. */
    fun openConversation(id: String) { _activeId.value = id }

    fun saveNote(note: WikiNote, onSaved: (WikiNote) -> Unit = {}) {
        viewModelScope.launch {
            val old = wikiStore.get(note.id)
            if (old != null && old.title != note.title) {
                val n = wikiStore.rename(note.id, note.title)
                if (n > 0) _toast.value = "Updated links in $n notes"
                val renamed = wikiStore.get(note.id) ?: return@launch
                onSaved(wikiStore.save(note.copy(title = renamed.title)))
            } else onSaved(wikiStore.save(note))
        }
    }

    fun createNote(title: String, folder: String, body: String = "", open: Boolean = true) {
        viewModelScope.launch {
            val n = wikiStore.create(title, folder, body.ifBlank { "# ${WikiNote.sanitizeTitle(title)}\n\n" })
            if (open) _openNote.value = n.id
        }
    }

    fun deleteNote(id: String) { viewModelScope.launch { wikiStore.delete(id) } }
    fun moveNote(id: String, folder: String) { viewModelScope.launch { wikiStore.move(id, folder) } }
    fun createFolder(path: String) { viewModelScope.launch { wikiStore.createFolder(path) } }
    fun renameFolder(from: String, to: String) { viewModelScope.launch { wikiStore.renameFolder(from, to) } }
    fun deleteFolder(path: String) { viewModelScope.launch { wikiStore.deleteFolder(path) } }

    fun toggleTask(noteId: String, line: Int, checked: Boolean) {
        viewModelScope.launch {
            val n = wikiStore.get(noteId) ?: return@launch
            val lines = n.body.split("\n").toMutableList()
            if (line !in lines.indices) return@launch
            lines[line] = lines[line].replaceFirst(Regex("\\[[ xX]]"), if (checked) "[x]" else "[ ]")
            wikiStore.save(n.copy(body = lines.joinToString("\n")))
        }
    }

    /** Local deterministic linking for one note. */
    fun autoLinkNote(id: String) {
        viewModelScope.launch {
            val n = wikiStore.get(id) ?: return@launch
            val (body, count) = wikiIndex.value.autoLink(n.body, id)
            if (count > 0) wikiStore.save(n.copy(body = body))
            _toast.value = if (count > 0) "Added $count links" else "No unlinked mentions found"
        }
    }

    fun autoLinkAll() {
        viewModelScope.launch {
            var total = 0
            for (n in wikiStore.notes.value) {
                val (body, c) = WikiIndex(wikiStore.notes.value).autoLink(n.body, n.id)
                if (c > 0) { wikiStore.save(n.copy(body = body), touch = false); total += c }
            }
            _toast.value = "Added $total links across the wiki"
        }
    }

    /** Ask the configured LLM to propose links/tags/folder for a note (index-based). */
    fun requestAiLinks(noteId: String) {
        val note = wikiStore.get(noteId) ?: return
        val target = resolveLlm() ?: return
        aiLinkJob?.cancel()
        _aiLink.value = AiLinkState(noteId)
        aiLinkJob = viewModelScope.launch {
            try {
                val idx = wikiIndex.value
                val reply = client.complete(
                    target.first, settings.value.copy(temperature = 0.2f), target.second,
                    listOf(
                        ChatMessage("system", WikiLinker.SYSTEM_PROMPT),
                        ChatMessage("user", WikiLinker.userPrompt(note, idx, wikiFolders.value))
                    )
                )
                val result = WikiLinker.validate(WikiLinker.parse(reply), note, idx)
                _aiLink.value = AiLinkState(noteId, loading = false, result = result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiLink.value = AiLinkState(noteId, loading = false, error = e.message ?: "Request failed")
            }
        }
    }

    fun dismissAiLinks() { aiLinkJob?.cancel(); _aiLink.value = null }

    fun applyAiLinks(accepted: Set<String>, tags: Boolean, folder: Boolean, aliases: Boolean, createStubs: Boolean) {
        val st = _aiLink.value ?: return
        val result = st.result ?: return
        viewModelScope.launch {
            val note = wikiStore.get(st.noteId) ?: return@launch
            val (updated, newOnes) = WikiLinker.apply(note, result, accepted, tags, folder, aliases)
            wikiStore.save(updated)
            if (createStubs) for (t in newOnes) {
                if (wikiIndex.value.resolve(t) == null) {
                    wikiStore.create(t, updated.folder, "# $t\n\n> [!todo] Stub created from [[${updated.title}]]\n")
                }
            }
            _aiLink.value = null
            _toast.value = "Applied ${accepted.size} suggestions"
        }
    }

    /** Provider + model for background LLM calls (same resolution as the chat). */
    private fun resolveLlm(): Pair<ProviderConfig, String>? {
        val assistant = activeAssistant()
        val provider = assistant?.preferredProviderId?.let { id -> providers.value.firstOrNull { it.id == id } }
            ?: activeProvider()
        val model = assistant?.preferredModel?.takeIf { it.isNotBlank() } ?: settings.value.activeModel
        if (provider == null || model.isBlank()) {
            _error.value = "No provider/model configured. Open Settings → Providers."
            return null
        }
        return provider to model
    }

    fun buildExportBundle(ids: List<String>, options: ExportOptions): ExportBundle {
        val idx = wikiIndex.value
        val notes = ids.mapNotNull { idx.byId[it] }
        return ExportPreparer.prepare(notes, idx, options) { name -> wikiStore.assetFile(name).takeIf { it.exists() } }
    }

    fun exportToTarget(targetId: String, ids: List<String>, options: ExportOptions) {
        val target = ExportRegistry.byId(targetId) ?: return
        viewModelScope.launch {
            val cfg = exportConfig.get(targetId)
            val missing = target.missingRequired(cfg)
            if (missing.isNotEmpty()) {
                _error.value = "${target.name}: please configure ${missing.joinToString { it.label }} (Settings → Export targets)"
                return@launch
            }
            _exportRun.value = ExportRun(target.name, 0, ids.size)
            val bundle = withContext(Dispatchers.IO) { buildExportBundle(ids, options) }
            val result = runCatching {
                target.export(bundle, cfg, ExportConfigStore.stateFor(getApplication(), targetId),
                    ExportProgress { d, t, c -> _exportRun.value = ExportRun(target.name, d, t, c) })
            }.getOrElse { ExportResult(false, it.message ?: "Export failed") }
            _exportRun.value = ExportRun(target.name, ids.size, ids.size, result = result)
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
    fun showToast(msg: String) { _toast.value = msg }
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

    fun sendMessage(
        text: String,
        attachments: List<com.miniichat.data.Attachment> = emptyList(),
        /** Transient instruction for this request only (e.g. reviewer feedback after a rejection). */
        extraInstruction: String? = null
    ) {
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

        val systemWithWiki = if (current.wikiContextInChat)
            systemPrompt + WikiLinker.chatContext(wikiIndex.value, wikiFolders.value)
        else systemPrompt

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
            if (systemWithWiki.isNotBlank()) {
                historyForApi.add(ChatMessage("system", systemWithWiki))
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
            if (!extraInstruction.isNullOrBlank()) {
                val i = historyForApi.indexOfLast { it.role == "user" }
                if (i >= 0) {
                    val m = historyForApi[i]
                    val note = "\n\n[Revision request — the previous answer was rejected] " + extraInstruction.trim()
                    historyForApi[i] = if (m.isMultipart()) {
                        val parts = m.parts!!.toMutableList()
                        val t = parts.indexOfFirst { it.type == "text" }
                        if (t >= 0) parts[t] = parts[t].copy(text = (parts[t].text ?: "") + note)
                        else parts.add(0, com.miniichat.api.ChatPart(type = "text", text = note.trim()))
                        m.copy(content = m.content + note, parts = parts)
                    } else m.copy(content = m.content + note)
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
            // drop the user message too: sendMessage() appends it again (avoids duplicated prompts)
            val trimmed = msgs.subList(0, lastUserIdx)
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
            val trimmed = msgs.subList(0, idx)
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
