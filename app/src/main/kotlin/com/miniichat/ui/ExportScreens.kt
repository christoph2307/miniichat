package com.miniichat.ui

import android.content.Intent
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.miniichat.ExportRun
import com.miniichat.R
import com.miniichat.export.ConfigField
import com.miniichat.export.ExportBundle
import com.miniichat.export.ExportConfigStore
import com.miniichat.export.ExportOptions
import com.miniichat.export.ExportRegistry
import com.miniichat.export.ExportTarget
import com.miniichat.export.FieldType
import com.miniichat.export.LinkStyle
import com.miniichat.export.ZipWriter
import com.miniichat.wiki.WikiIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportSort(val label: String) {
    UPDATED_DESC("Updated ↓"), UPDATED_ASC("Updated ↑"), CREATED_DESC("Created ↓"),
    TITLE_ASC("Title A–Z"), TITLE_DESC("Title Z–A"), FOLDER("Folder"), LINKS("Most linked")
}

@Composable
fun ExportScreen(
    index: WikiIndex,
    preselected: Set<String>,
    exportRun: ExportRun?,
    onBack: () -> Unit,
    buildBundle: (List<String>, ExportOptions) -> ExportBundle,
    onExportToTarget: (String, List<String>, ExportOptions) -> Unit,
    onClearRun: () -> Unit,
    onOpenTargets: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(preselected.toList()) }
    var sort by rememberSaveable { mutableStateOf(ExportSort.UPDATED_DESC) }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var linkStyle by rememberSaveable { mutableStateOf(LinkStyle.WIKILINK) }
    var frontmatter by rememberSaveable { mutableStateOf(true) }
    var assets by rememberSaveable { mutableStateOf(true) }
    var folders by rememberSaveable { mutableStateOf(true) }
    var backlinks by rememberSaveable { mutableStateOf(false) }
    var targetMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val options = ExportOptions(linkStyle, frontmatter, assets, folders, backlinks)

    val visible = remember(index, sort, folderFilter, query) {
        index.notes.filter { n ->
            (folderFilter == null || n.folder == folderFilter || n.folder.startsWith("$folderFilter/")) &&
                (query.isBlank() || n.title.contains(query, true) || n.folder.contains(query, true) ||
                    index.allTags(n).any { it.contains(query.removePrefix("#"), true) })
        }.let { l ->
            when (sort) {
                ExportSort.UPDATED_DESC -> l.sortedByDescending { it.updated }
                ExportSort.UPDATED_ASC -> l.sortedBy { it.updated }
                ExportSort.CREATED_DESC -> l.sortedByDescending { it.created }
                ExportSort.TITLE_ASC -> l.sortedBy { it.title.lowercase() }
                ExportSort.TITLE_DESC -> l.sortedByDescending { it.title.lowercase() }
                ExportSort.FOLDER -> l.sortedWith(compareBy({ it.folder.lowercase() }, { it.title.lowercase() }))
                ExportSort.LINKS -> l.sortedByDescending { index.backlinks[it.id].orEmpty().size }
            }
        }
    }
    val visibleIds = visible.map { it.id }
    val selSet = selected.toSet()
    val allState = when {
        visibleIds.isEmpty() || visibleIds.none { it in selSet } -> ToggleableState.Off
        visibleIds.all { it in selSet } -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    // keep the user's sort order for the exported files list
    val orderedSelection = visibleIds.filter { it in selSet } + selected.filter { it !in visibleIds }

    val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val b = buildBundle(orderedSelection, options)
                    ctx.contentResolver.openOutputStream(uri)!!.use { ZipWriter.write(b, it) }
                    b.documents.size
                }
            }
            busy = false
            android.widget.Toast.makeText(ctx, r.fold({ "Saved $it notes" }, { "Export failed: ${it.message}" }), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun shareZip() = scope.launch {
        busy = true
        val r = runCatching {
            withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "wiki-export-$stamp.zip")
                f.outputStream().use { ZipWriter.write(buildBundle(orderedSelection, options), it) }
                f
            }
        }
        busy = false
        r.onSuccess { f ->
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, null))
        }.onFailure { android.widget.Toast.makeText(ctx, "Export failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }

    fun shareMarkdown() = scope.launch {
        val b = withContext(Dispatchers.IO) { buildBundle(orderedSelection, options) }
        val text = b.documents.joinToString("\n\n---\n\n") { it.markdown }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"; putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, b.documents.singleOrNull()?.title ?: "Wiki export (${b.documents.size})")
        }, null))
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        WikiTopBar(stringResource(R.string.export_title, selected.size), onBack) {
            IconButton(onClick = onOpenTargets) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.export_targets)) }
        }
        // toolbar: select all · search · folder · sort
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(state = allState, onClick = {
                selected = if (allState == ToggleableState.On) selected.filter { it !in visibleIds }
                else (selected + visibleIds).distinct()
            })
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.wiki_search_hint)) },
                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
            )
            Box {
                IconButton(onClick = { folderMenu = true }) { Icon(Icons.Default.FilterList, contentDescription = "folder") }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_all_folders)) }, onClick = { folderFilter = null; folderMenu = false })
                    index.folders().forEach { f ->
                        DropdownMenuItem(text = { Text(f) }, onClick = { folderFilter = f; folderMenu = false })
                    }
                }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    ExportSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label, color = if (s == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                            onClick = { sort = s; sortMenu = false }
                        )
                    }
                }
            }
        }
        Text(
            listOfNotNull(sort.label, folderFilter?.let { "📁 $it" }, "${visible.size} notes").joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.id }) { n ->
                val checked = n.id in selSet
                Row(
                    Modifier.fillMaxWidth().clickable { selected = if (checked) selected - n.id else selected + n.id }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = { c -> selected = if (c) selected + n.id else selected - n.id })
                    Column(Modifier.weight(1f)) {
                        Text(n.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(n.folder.ifBlank { null }, formatDate(n.updated),
                                index.allTags(n).take(3).joinToString(" ") { "#$it" }.ifBlank { null }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                        )
                    }
                }
            }
        }

        // ── bottom panel: options + actions ──
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(WindowInsets.navigationBars.asPaddingValues()).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(Modifier.fillMaxWidth().clickable { optionsOpen = !optionsOpen }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.export_options) + ": " + linkStyle.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(if (optionsOpen) Icons.Default.ExpandMore else Icons.Default.ExpandLess, null)
            }
            if (optionsOpen) {
                LinkStyle.entries.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { linkStyle = s }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = linkStyle == s, onClick = { linkStyle = s })
                        Text(s.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OptionSwitch(stringResource(R.string.export_opt_frontmatter), frontmatter) { frontmatter = it }
                OptionSwitch(stringResource(R.string.export_opt_assets), assets) { assets = it }
                OptionSwitch(stringResource(R.string.export_opt_folders), folders) { folders = it }
                OptionSwitch(stringResource(R.string.export_opt_backlinks), backlinks) { backlinks = it }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            val enabled = selected.isNotEmpty() && !busy
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shareZip() }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_share_zip)) }
                OutlinedButton(onClick = { saveZip.launch("wiki-export-$stamp.zip") }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.export_save_zip))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shareMarkdown() }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.export_share_md))
                }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { targetMenu = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.export_send_to))
                    }
                    DropdownMenu(expanded = targetMenu, onDismissRequest = { targetMenu = false }) {
                        ExportRegistry.targets.forEach { t ->
                            DropdownMenuItem(
                                enabled = t.available,
                                text = { Text(if (t.available) t.name else "${t.name} (${stringResource(R.string.export_planned)})") },
                                onClick = {
                                    targetMenu = false
                                    onExportToTarget(t.id, orderedSelection, options.copy(linkStyle = linkStyle))
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    exportRun?.let { run ->
        AlertDialog(
            onDismissRequest = { if (run.result != null) onClearRun() },
            title = { Text(run.targetName) },
            text = {
                Column {
                    if (run.result == null) {
                        LinearProgressIndicator(progress = { if (run.total > 0) run.done.toFloat() / run.total else 0f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text("${run.done}/${run.total} ${run.current}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(run.result.message, color = if (run.result.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        run.result.items.filter { !it.ok }.take(10).forEach { Text("• ${index.byId[it.id]?.title ?: it.id}: ${it.message}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { if (run.result != null) TextButton(onClick = onClearRun) { Text(stringResource(R.string.ok)) } },
            dismissButton = {
                val loc = run.result?.location
                if (loc != null && loc.startsWith("http")) TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loc))) }
                    onClearRun()
                }) { Text(stringResource(R.string.export_open)) }
            }
        )
    }
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ───────────────────────────── Export target settings ─────────────────────────────

/** Config UI generated from each target's [ConfigField]s. */
@Composable
fun ExportTargetsScreen(store: ExportConfigStore, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        WikiTopBar(stringResource(R.string.export_targets), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.export_targets_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExportRegistry.targets.forEach { t -> SectionCard { TargetCard(t, store) } }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun TargetCard(t: ExportTarget, store: ExportConfigStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var values by remember(t.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember(t.id) { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    LaunchedEffect(t.id) { values = store.get(t.id); loaded = true }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!t.available) Text(stringResource(R.string.export_planned), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val login = t.login
        if (loaded && login != null) {
            var status by remember { mutableStateOf(login.describe(values)) }
            var pending by remember { mutableStateOf(false) }
            LaunchedEffect(values) { if (!pending) status = login.describe(values) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !pending, onClick = {
                    scope.launch {
                        pending = true
                        try {
                            store.set(t.id, values) // persist server URL first
                            val start = login.begin(values)
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(start.browserUrl)))
                            status = ctx.getString(R.string.export_login_waiting)
                            var creds: Map<String, String>? = null
                            val deadline = System.currentTimeMillis() + 15 * 60_000L
                            while (creds == null && System.currentTimeMillis() < deadline) {
                                kotlinx.coroutines.delay(2500)
                                creds = login.poll(start)
                            }
                            if (creds != null) {
                                store.update(t.id, creds)
                                values = store.get(t.id)
                                status = login.describe(values) ?: "OK"
                            } else status = ctx.getString(R.string.export_login_timeout)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            status = "✗ " + (e.message ?: "Login failed")
                        } finally { pending = false }
                    }
                }) { Text(login.label) }
                if (!pending && login.credentialKeys.all { !values[it].isNullOrBlank() }) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        scope.launch { store.clear(t.id, login.credentialKeys); values = store.get(t.id) }
                    }) { Text(stringResource(R.string.export_logout)) }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (loaded) t.fields.forEach { f -> FieldEditor(f, values[f.key] ?: f.default) { v -> values = values + (f.key to v) } }
        if (loaded && t.fields.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    store.set(t.id, values)
                    android.widget.Toast.makeText(ctx, "Saved", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.save)) }
            OutlinedButton(enabled = t.available && !testing, onClick = {
                scope.launch {
                    testing = true
                    val r = runCatching { t.test(values) }.getOrElse { com.miniichat.export.ExportResult(false, it.message ?: "failed") }
                    testing = false
                    android.widget.Toast.makeText(ctx, (if (r.ok) "✓ " else "✗ ") + r.message, android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.export_test)) }
        }
    }
}

@Composable
private fun FieldEditor(f: ConfigField, value: String, onChange: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    when (f.type) {
        FieldType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.label, style = MaterialTheme.typography.bodyLarge)
                if (f.hint.isNotBlank()) Text(f.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = value == "true", onCheckedChange = { onChange(it.toString()) })
        }
        FieldType.CHOICE -> {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedTextField(
                    value = f.choices.firstOrNull { it.first == value }?.second ?: value, onValueChange = {}, readOnly = true,
                    label = { Text(f.label) }, modifier = Modifier.fillMaxWidth().clickable { open = true },
                    trailingIcon = { IconButton(onClick = { open = true }) { Icon(Icons.Default.ExpandMore, null) } }
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    f.choices.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onChange(v); open = false }) }
                }
            }
        }
        else -> OutlinedTextField(
            value = value, onValueChange = onChange,
            label = { Text(f.label + if (f.required) " *" else "") },
            placeholder = { if (f.hint.isNotBlank()) Text(f.hint) },
            singleLine = f.type != FieldType.MULTILINE,
            minLines = if (f.type == FieldType.MULTILINE) 3 else 1,
            visualTransformation = if (f.type == FieldType.SECRET) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when (f.type) {
                FieldType.URL -> KeyboardType.Uri; FieldType.NUMBER -> KeyboardType.Number
                FieldType.SECRET -> KeyboardType.Password; else -> KeyboardType.Text
            }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
