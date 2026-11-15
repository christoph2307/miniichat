package com.miniichat.whisper

/** A downloadable ggml whisper model. */
data class WhisperModel(
    val id: String,              // file stem, e.g. "base-q5_1" → ggml-base-q5_1.bin
    val label: String,
    val sizeMb: Int,
    val englishOnly: Boolean = false,
    val note: String = ""
) {
    val fileName: String get() = "ggml-$id.bin"
    val url: String get() = "$HF_BASE/$fileName"

    companion object {
        const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    }
}

object WhisperModels {
    val catalog: List<WhisperModel> = listOf(
        WhisperModel("tiny-q5_1", "Tiny (quantized)", 31, note = "fastest, lowest accuracy"),
        WhisperModel("tiny", "Tiny", 75),
        WhisperModel("tiny.en", "Tiny · English", 75, englishOnly = true),
        WhisperModel("base-q5_1", "Base (quantized)", 57, note = "good default for phones"),
        WhisperModel("base", "Base", 142),
        WhisperModel("base.en", "Base · English", 142, englishOnly = true),
        WhisperModel("small-q5_1", "Small (quantized)", 181, note = "recommended for German/technical speech"),
        WhisperModel("small", "Small", 466),
        WhisperModel("small.en", "Small · English", 466, englishOnly = true),
        WhisperModel("medium-q5_0", "Medium (quantized)", 514, note = "slow on phones, high accuracy"),
        WhisperModel("large-v3-turbo-q5_0", "Large v3 Turbo (quantized)", 547, note = "best quality/speed trade-off on flagship phones"),
        WhisperModel("medium", "Medium", 1500),
        WhisperModel("large-v3-turbo", "Large v3 Turbo", 1620),
    )

    fun byId(id: String): WhisperModel? = catalog.firstOrNull { it.id == id }

    /** Languages offered in the picker (whisper code to display name). */
    val languages: List<Pair<String, String>> = listOf(
        "auto" to "Auto-detect",
        "de" to "Deutsch",
        "en" to "English",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "pt" to "Português",
        "cs" to "Čeština",
        "sv" to "Svenska",
        "da" to "Dansk",
        "fi" to "Suomi",
        "ru" to "Русский",
        "uk" to "Українська",
        "tr" to "Türkçe",
        "zh" to "中文",
        "ja" to "日本語",
        "ko" to "한국어",
    )
}
