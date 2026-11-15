package com.miniichat.whisper

/** A downloadable ggml whisper model. */
data class WhisperModel(
    val id: String,              // file stem, e.g. "base-q5_1" → ggml-base-q5_1.bin
    val label: String,
    val sizeMb: Int,
    val englishOnly: Boolean = false,
    val note: String = "",
    /** Download URL if the file isn't in the ggerganov/whisper.cpp repo. */
    val customUrl: String? = null
) {
    val fileName: String get() = "ggml-$id.bin"
    val url: String get() = customUrl ?: "$HF_BASE/$fileName"

    companion object {
        const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    }
}

object WhisperModels {
    /** Built by .github/workflows/german-model.yml in this repository (release "whisper-models"). */
    const val GERMAN_RELEASE = "https://github.com/christoph2307/miniichat/releases/download/whisper-models"

    val catalog: List<WhisperModel> = listOf(
        WhisperModel("large-v3-turbo-german-q5_0", "Large v3 Turbo · Deutsch (quantized)", 547,
            note = "German fine-tune (primeline) — recommended for German",
            customUrl = "$GERMAN_RELEASE/ggml-large-v3-turbo-german-q5_0.bin"),
        WhisperModel("large-v3-turbo-german-q8_0", "Large v3 Turbo · Deutsch (q8_0, GPU)", 834,
            note = "German fine-tune — best choice with “Use GPU”",
            customUrl = "$GERMAN_RELEASE/ggml-large-v3-turbo-german-q8_0.bin"),
        WhisperModel("large-v3-turbo-german", "Large v3 Turbo · Deutsch", 1620,
            note = "German fine-tune, unquantized — most accurate, slow",
            customUrl = "https://huggingface.co/cstr/whisper-large-v3-turbo-german-ggml/resolve/main/ggml-model.bin"),
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
        WhisperModel("large-v3-turbo-q8_0", "Large v3 Turbo (q8_0, GPU)", 834, note = "runs fully on the GPU"),
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
