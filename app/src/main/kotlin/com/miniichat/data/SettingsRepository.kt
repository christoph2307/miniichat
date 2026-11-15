package com.miniichat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val activeProviderId: String = "",
    val activeModel: String = "",
    val activeAssistantId: String = "default",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f,
    val stream: Boolean = true,
    val language: String = "system",          // system | en | zh
    val dynamicColor: Boolean = true,
    val themeMode: String = "system",         // system | light | dark
    // --- Dictaphone / local speech recognition (whisper.cpp) ---
    val whisperModel: String = "",            // file name in filesDir/whisper, e.g. ggml-base-q5_1.bin
    val whisperLanguage: String = "auto",
    val whisperThreads: Int = 0,              // 0 = automatic
    val whisperTranslate: Boolean = false,    // translate speech to English
    val whisperPrompt: String = "",           // vocabulary / glossary hint
    val autoTranscribe: Boolean = true,       // transcribe right after a recording stops
    val sendAudioToModel: Boolean = false,    // also attach WAV as input_audio for audio-capable LLMs
    // --- Wiki ---
    val wikiContextInChat: Boolean = true,    // tell the LLM about existing notes (titles) + frontmatter format
    val wikiAutoLinkOnSave: Boolean = true,   // local [[link]] insertion for mentioned note titles
    val wikiDefaultFolder: String = "Inbox",
    val wikiSaveImages: Boolean = true,       // copy photos of the prompt into the note
    val wikiSaveTranscript: Boolean = false,  // keep the raw transcript as a collapsed callout
    val wikiSaveAudio: Boolean = true         // copy recordings into the note (## Recordings, playable)
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val PROVIDER = stringPreferencesKey("active_provider_id")
        val MODEL = stringPreferencesKey("active_model")
        val ASSISTANT = stringPreferencesKey("active_assistant_id")
        val SYSTEM = stringPreferencesKey("system_prompt")
        val TEMP = floatPreferencesKey("temperature")
        val STREAM = booleanPreferencesKey("stream")
        val LANG = stringPreferencesKey("language")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme_mode")
        val W_MODEL = stringPreferencesKey("whisper_model")
        val W_LANG = stringPreferencesKey("whisper_language")
        val W_THREADS = intPreferencesKey("whisper_threads")
        val W_TRANSLATE = booleanPreferencesKey("whisper_translate")
        val W_PROMPT = stringPreferencesKey("whisper_prompt")
        val AUTO_TRANSCRIBE = booleanPreferencesKey("auto_transcribe")
        val SEND_AUDIO = booleanPreferencesKey("send_audio_to_model")
        val WIKI_CTX = booleanPreferencesKey("wiki_context_in_chat")
        val WIKI_AUTOLINK = booleanPreferencesKey("wiki_autolink_on_save")
        val WIKI_FOLDER = stringPreferencesKey("wiki_default_folder")
        val WIKI_IMAGES = booleanPreferencesKey("wiki_save_images")
        val WIKI_TRANSCRIPT = booleanPreferencesKey("wiki_save_transcript")
        val WIKI_AUDIO = booleanPreferencesKey("wiki_save_audio")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p -> read(p) }

    private fun read(p: Preferences) = AppSettings(
        activeProviderId = p[Keys.PROVIDER] ?: "",
        activeModel = p[Keys.MODEL] ?: "",
        activeAssistantId = p[Keys.ASSISTANT] ?: "default",
        systemPrompt = p[Keys.SYSTEM] ?: "You are a helpful assistant.",
        temperature = p[Keys.TEMP] ?: 0.7f,
        stream = p[Keys.STREAM] ?: true,
        language = p[Keys.LANG] ?: "system",
        dynamicColor = p[Keys.DYNAMIC] ?: true,
        themeMode = p[Keys.THEME] ?: "system",
        whisperModel = p[Keys.W_MODEL] ?: "",
        whisperLanguage = p[Keys.W_LANG] ?: "auto",
        whisperThreads = p[Keys.W_THREADS] ?: 0,
        whisperTranslate = p[Keys.W_TRANSLATE] ?: false,
        whisperPrompt = p[Keys.W_PROMPT] ?: "",
        autoTranscribe = p[Keys.AUTO_TRANSCRIBE] ?: true,
        sendAudioToModel = p[Keys.SEND_AUDIO] ?: false,
        wikiContextInChat = p[Keys.WIKI_CTX] ?: true,
        wikiAutoLinkOnSave = p[Keys.WIKI_AUTOLINK] ?: true,
        wikiDefaultFolder = p[Keys.WIKI_FOLDER] ?: "Inbox",
        wikiSaveImages = p[Keys.WIKI_IMAGES] ?: true,
        wikiSaveTranscript = p[Keys.WIKI_TRANSCRIPT] ?: false,
        wikiSaveAudio = p[Keys.WIKI_AUDIO] ?: true
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { p ->
            val next = transform(read(p))
            p[Keys.PROVIDER] = next.activeProviderId
            p[Keys.MODEL] = next.activeModel
            p[Keys.ASSISTANT] = next.activeAssistantId
            p[Keys.SYSTEM] = next.systemPrompt
            p[Keys.TEMP] = next.temperature
            p[Keys.STREAM] = next.stream
            p[Keys.LANG] = next.language
            p[Keys.DYNAMIC] = next.dynamicColor
            p[Keys.THEME] = next.themeMode
            p[Keys.W_MODEL] = next.whisperModel
            p[Keys.W_LANG] = next.whisperLanguage
            p[Keys.W_THREADS] = next.whisperThreads
            p[Keys.W_TRANSLATE] = next.whisperTranslate
            p[Keys.W_PROMPT] = next.whisperPrompt
            p[Keys.AUTO_TRANSCRIBE] = next.autoTranscribe
            p[Keys.SEND_AUDIO] = next.sendAudioToModel
            p[Keys.WIKI_CTX] = next.wikiContextInChat
            p[Keys.WIKI_AUTOLINK] = next.wikiAutoLinkOnSave
            p[Keys.WIKI_FOLDER] = next.wikiDefaultFolder
            p[Keys.WIKI_IMAGES] = next.wikiSaveImages
            p[Keys.WIKI_TRANSCRIPT] = next.wikiSaveTranscript
            p[Keys.WIKI_AUDIO] = next.wikiSaveAudio
        }
    }
}
