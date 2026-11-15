package com.miniichat.export

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.exportDataStore: DataStore<Preferences> by preferencesDataStore(name = "export_targets")

/** Per-target configuration (values of each target's [ConfigField]s), stored app-private. */
class ExportConfigStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))
    private val key = stringPreferencesKey("configs")

    val configs: Flow<Map<String, Map<String, String>>> = context.exportDataStore.data.map { p ->
        p[key]?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyMap()
    }

    suspend fun get(targetId: String): Map<String, String> {
        val target = ExportRegistry.byId(targetId)
        val saved = configs.first()[targetId].orEmpty()
        val defaults = target?.fields?.associate { it.key to it.default }?.filterValues { it.isNotEmpty() }.orEmpty()
        return defaults + saved
    }

    suspend fun set(targetId: String, values: Map<String, String>) {
        context.exportDataStore.edit { p ->
            val cur = p[key]?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyMap()
            p[key] = json.encodeToString(ser, cur + (targetId to values))
        }
    }
}
