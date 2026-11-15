package com.miniichat.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichat.R
import com.miniichat.data.AppSettings
import com.miniichat.util.AttachmentLoader
import com.miniichat.whisper.Transcriber
import com.miniichat.whisper.WhisperLib
import com.miniichat.whisper.WhisperModelManager
import com.miniichat.whisper.WhisperModels
import kotlinx.coroutines.launch

@Composable
fun SpeechSettingsScreen(
    settings: AppSettings,
    manager: WhisperModelManager,
    onBack: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    recordingsUsage: () -> Pair<Int, Long>,
    onDeleteUnusedRecordings: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by manager.installed.collectAsState()
    val downloads by manager.downloads.collectAsState()
    var glossary by rememberSaveable { mutableStateOf(settings.whisperPrompt) }
    var usage by remember { mutableStateOf(recordingsUsage()) }

    LaunchedEffect(Unit) { manager.refresh() }

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val (name, _) = AttachmentLoader.queryNameSize(ctx.contentResolver, uri)
            runCatching { manager.import(uri, name) }
                .onSuccess { f ->
                    onChange { it.copy(whisperModel = f) }
                    android.widget.Toast.makeText(ctx, "Imported $f", android.widget.Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    android.widget.Toast.makeText(ctx, "Import failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(title = stringResource(R.string.speech_recognition), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.speech_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            if (!WhisperLib.isAvailable) {
                Text(
                    "Native library not loaded: ${WhisperLib.loadError?.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // ---------- Models ----------
            SectionHeader(stringResource(R.string.speech_models))
            SectionCard {
                Column {
                    val known = WhisperModels.catalog.map { it.fileName }.toSet()
                    val custom = installed.filter { it !in known }.sorted()
                    val rows = WhisperModels.catalog.map { Triple(it.fileName, it.label, it) } +
                        custom.map { Triple(it, it.removePrefix("ggml-").removeSuffix(".bin"), null) }
                    rows.forEachIndexed { i, (file, label, model) ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                        val isInstalled = file in installed
                        val selected = settings.whisperModel == file
                        val dl = downloads[file]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isInstalled) { onChange { it.copy(whisperModel = file) } }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (selected && isInstalled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isInstalled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                    val sub = buildList {
                                        if (model != null) add("${model.sizeMb} MB")
                                        if (model?.englishOnly == true) add("English only")
                                        if (!model?.note.isNullOrBlank()) add(model!!.note)
                                        if (model == null) {
                                            add(AttachmentLoader.formatBytes(manager.fileFor(file).length()))
                                            add("imported")
                                        }
                                    }.joinToString(" · ")
                                    Text(sub, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                when {
                                    dl != null && dl.error == null -> IconButton(onClick = { manager.cancel(file) }) {
                                        Icon(Icons.Default.Close, contentDescription = "cancel")
                                    }
                                    isInstalled -> IconButton(onClick = {
                                        manager.delete(file)
                                        if (selected) onChange { it.copy(whisperModel = "") }
                                    }) { Icon(Icons.Default.Delete, contentDescription = "delete") }
                                    model != null -> IconButton(onClick = {
                                        manager.clearError(file)
                                        manager.download(file, model.url)
                                        if (settings.whisperModel.isBlank()) onChange { it.copy(whisperModel = file) }
                                    }) { Icon(Icons.Default.Download, contentDescription = "download") }
                                }
                            }
                            if (dl != null) {
                                Spacer(Modifier.height(6.dp))
                                if (dl.error != null) {
                                    Text("Download failed: ${dl.error}", color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                } else {
                                    if (dl.total > 0) LinearProgressIndicator(progress = { dl.fraction }, modifier = Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text(
                                        "${AttachmentLoader.formatBytes(dl.bytes)} / ${AttachmentLoader.formatBytes(dl.total)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { importModel.launch("*/*") }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.speech_import_model), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.speech_import_model_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ---------- Recognition options ----------
            SectionHeader(stringResource(R.string.speech_options))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(stringResource(R.string.speech_language), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    LanguagePicker(settings.whisperLanguage) { code -> onChange { it.copy(whisperLanguage = code) } }

                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.speech_glossary), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.speech_glossary_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    OutlinedFieldBox {
                        BasicTextField(
                            value = glossary,
                            onValueChange = {
                                glossary = it
                                onChange { s -> s.copy(whisperPrompt = it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                color = LocalContentColor.current, fontSize = 14.sp, lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            minLines = 2,
                            maxLines = 5
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    val autoThreads = Transcriber.defaultThreads()
                    val threads = if (settings.whisperThreads > 0) settings.whisperThreads else autoThreads
                    Text(
                        stringResource(R.string.speech_threads, threads) +
                            if (settings.whisperThreads == 0) " (auto)" else "",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = threads.toFloat(),
                        onValueChange = { v -> onChange { it.copy(whisperThreads = v.toInt()) } },
                        valueRange = 1f..Runtime.getRuntime().availableProcessors().coerceAtLeast(2).toFloat(),
                        steps = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(0)
                    )

                    ToggleRow(
                        title = stringResource(R.string.speech_auto_transcribe),
                        subtitle = stringResource(R.string.speech_auto_transcribe_hint),
                        checked = settings.autoTranscribe
                    ) { v -> onChange { it.copy(autoTranscribe = v) } }
                    ToggleRow(
                        title = stringResource(R.string.speech_translate),
                        subtitle = stringResource(R.string.speech_translate_hint),
                        checked = settings.whisperTranslate
                    ) { v -> onChange { it.copy(whisperTranslate = v) } }
                    ToggleRow(
                        title = stringResource(R.string.speech_send_audio),
                        subtitle = stringResource(R.string.speech_send_audio_hint),
                        checked = settings.sendAudioToModel
                    ) { v -> onChange { it.copy(sendAudioToModel = v) } }
                }
            }

            // ---------- Engine / GPU ----------
            SectionHeader(stringResource(R.string.speech_engine))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    val v82 = remember { WhisperLib.cpuSupportsV82() }
                    var gpuWanted by remember { mutableStateOf(WhisperLib.gpuRequested) }
                    val devices = remember { WhisperLib.devices() }
                    if (WhisperLib.gpuCrashRecovered) {
                        Text(stringResource(R.string.speech_gpu_crashed), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(stringResource(R.string.speech_engine_active, WhisperLib.variant),
                        style = MaterialTheme.typography.bodyLarge)
                    devices.forEach { (type, name, desc) ->
                        Text("• $type: $desc ($name)", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (WhisperLib.gpuBuildLoaded && devices.none { it.first == "GPU" || it.first == "IGPU" }) {
                        Text(stringResource(R.string.speech_gpu_none), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    ToggleRow(
                        title = stringResource(R.string.speech_gpu),
                        subtitle = if (v82) stringResource(R.string.speech_gpu_hint) else stringResource(R.string.speech_gpu_unsupported),
                        checked = gpuWanted
                    ) { v -> if (v82) { gpuWanted = v; WhisperLib.gpuRequested = v } }
                    if (gpuWanted != WhisperLib.gpuBuildLoaded && v82) {
                        TextButton(onClick = {
                            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (i != null) ctx.startActivity(i)
                            Runtime.getRuntime().exit(0)
                        }) { Text(stringResource(R.string.speech_restart)) }
                    }
                    WhisperLib.lastRunSummary?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.speech_last_run, it), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---------- Storage ----------
            SectionHeader(stringResource(R.string.speech_storage))
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.speech_recordings), style = MaterialTheme.typography.bodyLarge)
                        Text("${usage.first} · ${AttachmentLoader.formatBytes(usage.second)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        onDeleteUnusedRecordings()
                        scope.launch {
                            kotlinx.coroutines.delay(500)
                            usage = recordingsUsage()
                        }
                    }) { Text(stringResource(R.string.speech_delete_unused)) }
                }
            }

            if (WhisperLib.isAvailable) {
                Text(
                    runCatching { WhisperLib.systemInfo() }.getOrDefault(""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = WhisperModels.languages.firstOrNull { it.first == selected }?.second ?: selected
    Box {
        OutlinedFieldBox {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { open = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            WhisperModels.languages.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(if (code == "auto") name else "$name ($code)") },
                    onClick = { onSelect(code); open = false }
                )
            }
        }
    }
}
