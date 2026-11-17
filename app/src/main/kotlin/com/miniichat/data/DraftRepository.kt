package com.miniichat.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide holder of all chat drafts (unsent text + attachments), persisted via [DraftStore].
 * Lives outside any screen/ViewModel so background work (transcription service) can write
 * transcripts into the right draft even when no chat is on screen.
 */
object DraftRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var store: DraftStore? = null
    private val _drafts = MutableStateFlow<Map<String, Draft>>(emptyMap())
    val drafts: StateFlow<Map<String, Draft>> = _drafts.asStateFlow()
    private var saveJob: Job? = null

    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        val s = DraftStore(context)
        store = s
        _drafts.value = s.loadAll()
    }

    val lastActiveId: String? get() = store?.lastActiveId
    fun setLastActiveId(id: String?) { store?.lastActiveId = id }

    fun get(key: String): Draft = _drafts.value[key] ?: Draft()

    @Synchronized
    fun update(key: String, transform: (Draft) -> Draft) {
        val cur = _drafts.value
        _drafts.value = cur + (key to transform(cur[key] ?: Draft()))
        saveJob?.cancel()
        saveJob = scope.launch { delay(300); store?.saveAll(_drafts.value) }
    }

    @Synchronized
    fun remove(key: String) {
        _drafts.value = _drafts.value - key
        flush()
    }

    fun flush() { saveJob?.cancel(); store?.saveAll(_drafts.value) }
}
