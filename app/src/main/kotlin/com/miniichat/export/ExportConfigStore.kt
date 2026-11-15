package com.miniichat.export

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

private val Context.legacyExportDataStore: DataStore<Preferences> by preferencesDataStore(name = "export_targets")

/**
 * Per-target configuration (URLs, user names, app passwords, API keys).
 * Stored in EncryptedSharedPreferences (AES-256, key in the Android Keystore).
 */
class ExportConfigStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = MapSerializer(String.serializer(), String.serializer())

    private val prefs: SharedPreferences by lazy {
        runCatching {
            EncryptedSharedPreferences.create(
                "export_targets_secure",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            // Keystore broken on some devices/emulators → fall back to private prefs rather than crash
            context.getSharedPreferences("export_targets_fallback", Context.MODE_PRIVATE)
        }
    }

    private var migrated = false
    private suspend fun migrate() {
        if (migrated) return
        migrated = true
        if (prefs.getBoolean("_migrated", false)) return
        runCatching {
            val raw = context.legacyExportDataStore.data.first()[stringPreferencesKey("configs")]
            if (raw != null) {
                val all = json.decodeFromString(MapSerializer(String.serializer(), ser), raw)
                val e = prefs.edit()
                all.forEach { (k, v) -> e.putString(k, json.encodeToString(ser, v)) }
                e.apply()
            }
        }
        prefs.edit().putBoolean("_migrated", true).apply()
    }

    suspend fun get(targetId: String): Map<String, String> = withContext(Dispatchers.IO) {
        migrate()
        val target = ExportRegistry.byId(targetId)
        val saved = prefs.getString(targetId, null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty()
        val defaults = target?.fields?.associate { it.key to it.default }?.filterValues { it.isNotEmpty() }.orEmpty()
        defaults + saved
    }

    suspend fun set(targetId: String, values: Map<String, String>) = withContext(Dispatchers.IO) {
        migrate()
        prefs.edit().putString(targetId, json.encodeToString(ser, values)).apply()
    }

    /** Merge values (e.g. credentials returned by a login flow). */
    suspend fun update(targetId: String, values: Map<String, String>) = set(targetId, get(targetId) + values)

    suspend fun clear(targetId: String, keys: List<String>) = set(targetId, get(targetId) - keys.toSet())

    companion object {
        fun stateFor(context: Context, targetId: String): ExportState =
            FileExportState(File(context.filesDir, "export_state/$targetId.json"))
    }
}
