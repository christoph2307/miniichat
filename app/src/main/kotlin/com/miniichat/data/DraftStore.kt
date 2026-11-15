package com.miniichat.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Unsent composer content of one chat. */
@Serializable
data class Draft(
    val text: String = "",
    val attachments: List<Attachment> = emptyList()
) {
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()
}

/**
 * Persists drafts (per conversation) and the last open conversation, so nothing typed or
 * recorded is lost when navigating, picking a photo, or when Android kills the app in standby.
 * SharedPreferences (not DataStore) so it can be read synchronously at start-up.
 */
class DraftStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drafts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val ser = MapSerializer(String.serializer(), Draft.serializer())

    fun loadAll(): Map<String, Draft> =
        prefs.getString("all", null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyMap()

    fun saveAll(map: Map<String, Draft>) {
        prefs.edit().putString("all", json.encodeToString(ser, map.filterValues { !it.isEmpty })).apply()
    }

    var lastActiveId: String?
        get() = prefs.getString("last_active", null)
        set(v) { prefs.edit().putString("last_active", v).apply() }

    companion object { const val NEW_CHAT = "__new__" }
}
