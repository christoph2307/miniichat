package com.miniichat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichat.AiLinkState
import com.miniichat.R
import com.miniichat.wiki.WikiIndex
import com.miniichat.wiki.WikiNote
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
fun formatDate(ts: Long): String = dateFmt.format(Date(ts))

@Composable
fun WikiTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
            Text(
                title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            actions()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

// ───────────────────────────── Wiki browser ─────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WikiScreen(
    index: WikiIndex,
    explicitFolders: Set<String>,
    folder: String,
    onFolderChange: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onCreateNote: (title: String, folder: String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onMoveNote: (String, String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onAutoLinkAll: () -> Unit,
    onExport: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var newNoteDialog by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var folderAction by remember { mutableStateOf<String?>(null) }
    var noteAction by remember { mutableStateOf<WikiNote?>(null) }
    var moveTarget by remember { mutableStateOf<WikiNote?>(null) }
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }

    val allFolders = remember(index, explicitFolders) { index.folders(explicitFolders) }
    val subFolders = allFolders.filter { f ->
        if (folder.isEmpty()) !f.contains('/') else f.startsWith("$folder/") && !f.removePrefix("$folder/").contains('/')
    }
    val q = query.trim()
    val notes = if (q.isNotEmpty()) {
        val tag = q.removePrefix("#").takeIf { q.startsWith("#") }
        index.notes.filter { n ->
            if (tag != null) index.allTags(n).any { it.equals(tag, true) || it.startsWith("$tag/", true) }
            else n.title.contains(q, true) || n.aliases.any { it.contains(q, true) } || n.body.contains(q, true) ||
                n.folder.contains(q, true)
        }.sortedByDescending { it.updated }
    } else index.notes.filter { it.folder == folder }.sortedByDescending { it.updated }
    fun countIn(f: String) = index.notes.count { it.folder == f || it.folder.startsWith("$f/") }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        Column(Modifier.fillMaxSize()) {
            WikiTopBar(stringResource(R.string.wiki), onBack) {
                IconButton(onClick = { searching = !searching; if (!searching) onQueryChange("") }) {
                    Icon(if (searching) Icons.Default.Close else Icons.Default.Search, contentDescription = "search")
                }
                IconButton(onClick = onExport) { Icon(Icons.Default.IosShare, contentDescription = "export") }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "more") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.wiki_new_folder)) },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { menuOpen = false; newFolderDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.wiki_autolink_all)) },
                            leadingIcon = { Icon(Icons.Default.Link, null) },
                            onClick = { menuOpen = false; onAutoLinkAll() }
                        )
                    }
                }
            }
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = onQueryChange, singleLine = true,
                    placeholder = { Text(stringResource(R.string.wiki_search_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            if (q.isEmpty()) Breadcrumb(folder, onFolderChange)

            // tag cloud (top tags) for quick filtering
            if (q.isEmpty() && folder.isEmpty() && index.tags.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    index.tags.entries.sortedByDescending { it.value.size }.take(20).forEach { (t, ids) ->
                        Text(
                            "#$t ${ids.size}",
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { searching = true; onQueryChange("#$t") }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (q.isEmpty()) {
                    items(subFolders, key = { "f:$it" }) { f ->
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { onFolderChange(f) }, onLongClick = { folderAction = f })
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(f.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("${countIn(f)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(notes, key = { it.id }) { n ->
                    NoteRow(
                        n, index, showFolder = q.isNotEmpty(),
                        onClick = { onOpenNote(n.id) }, onLongClick = { noteAction = n }
                    )
                }
                if (notes.isEmpty() && subFolders.isEmpty()) {
                    item {
                        Text(
                            if (q.isEmpty()) stringResource(R.string.wiki_empty) else stringResource(R.string.wiki_no_results),
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { newNoteDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(WindowInsets.navigationBars.asPaddingValues()).padding(20.dp)
        ) { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = stringResource(R.string.wiki_new_note)) }
    }

    if (newNoteDialog) TextPromptDialog(
        title = stringResource(R.string.wiki_new_note), label = stringResource(R.string.wiki_title),
        initial = "", onDismiss = { newNoteDialog = false }
    ) { t -> newNoteDialog = false; if (t.isNotBlank()) onCreateNote(t, folder) }

    if (newFolderDialog) TextPromptDialog(
        title = stringResource(R.string.wiki_new_folder), label = stringResource(R.string.wiki_folder_path),
        initial = if (folder.isEmpty()) "" else "$folder/", onDismiss = { newFolderDialog = false }
    ) { f -> newFolderDialog = false; if (f.isNotBlank()) { onCreateFolder(f); onFolderChange(WikiNote.normFolder(f)) } }

    folderAction?.let { f ->
        var rename by remember(f) { mutableStateOf(false) }
        if (!rename) AlertDialog(
            onDismissRequest = { folderAction = null },
            title = { Text(f) },
            text = { Text(stringResource(R.string.wiki_folder_actions_hint)) },
            confirmButton = { TextButton(onClick = { rename = true }) { Text(stringResource(R.string.rename)) } },
            dismissButton = {
                TextButton(onClick = { onDeleteFolder(f); folderAction = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        ) else TextPromptDialog(
            title = stringResource(R.string.rename), label = stringResource(R.string.wiki_folder_path),
            initial = f, onDismiss = { folderAction = null }
        ) { to -> folderAction = null; if (to.isNotBlank()) onRenameFolder(f, to) }
    }

    noteAction?.let { n ->
        AlertDialog(
            onDismissRequest = { noteAction = null },
            title = { Text(n.title) },
            text = { Text(n.folder.ifBlank { "/" }) },
            confirmButton = { TextButton(onClick = { moveTarget = n; noteAction = null }) { Text(stringResource(R.string.wiki_move)) } },
            dismissButton = {
                TextButton(onClick = { onDeleteNote(n.id); noteAction = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
    moveTarget?.let { n ->
        FolderPickerDialog(allFolders, n.folder, onDismiss = { moveTarget = null }) { f -> onMoveNote(n.id, f); moveTarget = null }
    }
}

@Composable
private fun Breadcrumb(folder: String, onFolderChange: (String) -> Unit) {
    val parts = folder.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Wiki", modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onFolderChange("") }.padding(4.dp),
            color = if (parts.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge
        )
        parts.forEachIndexed { i, p ->
            Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            val path = parts.take(i + 1).joinToString("/")
            Text(
                p, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onFolderChange(path) }.padding(4.dp),
                color = if (i == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(n: WikiNote, index: WikiIndex, showFolder: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(n.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val back = index.backlinks[n.id].orEmpty().size
            val meta = buildList {
                if (showFolder && n.folder.isNotBlank()) add(n.folder)
                add(formatDate(n.updated))
                if (back > 0) add("← $back")
                val t = index.allTags(n)
                if (t.isNotEmpty()) add(t.take(3).joinToString(" ") { "#$it" })
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun TextPromptDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(v.trim()) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun FolderPickerDialog(folders: Set<String>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var custom by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wiki_move)) },
        text = {
            Column {
                OutlinedTextField(value = custom, onValueChange = { custom = it }, singleLine = true,
                    label = { Text(stringResource(R.string.wiki_folder_path)) })
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    (listOf("") + folders.toList()).forEach { f ->
                        Text(
                            if (f.isEmpty()) "/ (root)" else f,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { custom = f }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            color = if (f == custom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(WikiNote.normFolder(custom)) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// ───────────────────────────── Note view / editor ─────────────────────────────

@Composable
fun NoteScreen(
    note: WikiNote,
    index: WikiIndex,
    explicitFolders: Set<String>,
    assetResolver: (String) -> File?,
    aiLink: AiLinkState?,
    onBack: () -> Unit,
    onSave: (WikiNote) -> Unit,
    onOpenNote: (String) -> Unit,
    onCreateLinkedNote: (String) -> Unit,
    onTag: (String) -> Unit,
    onToggleTask: (Int, Boolean) -> Unit,
    onAutoLink: () -> Unit,
    onAiLinks: () -> Unit,
    onDismissAi: () -> Unit,
    onApplyAi: (Set<String>, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onAppLink: (String) -> Unit = {}
) {
    var editing by rememberSaveable(note.id) { mutableStateOf(note.body.isBlank()) }
    var title by rememberSaveable(note.id) { mutableStateOf(note.title) }
    var folder by rememberSaveable(note.id) { mutableStateOf(note.folder) }
    var tags by rememberSaveable(note.id) { mutableStateOf(note.tags.joinToString(", ")) }
    var aliases by rememberSaveable(note.id) { mutableStateOf(note.aliases.joinToString(", ")) }
    var body by rememberSaveable(note.id) { mutableStateOf(note.body) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickFolder by remember { mutableStateOf(false) }
    var createAsk by remember { mutableStateOf<String?>(null) }

    // keep editor in sync when the note changes underneath (AI apply, task toggle) while viewing
    LaunchedEffect(note.updated, editing) {
        if (!editing) {
            title = note.title; folder = note.folder; body = note.body
            tags = note.tags.joinToString(", "); aliases = note.aliases.joinToString(", ")
        }
    }

    fun commit() {
        onSave(
            note.copy(
                title = title.ifBlank { note.title }, folder = folder, body = body,
                tags = tags.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() },
                aliases = aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            )
        )
    }

    val actions = MarkdownActions(
        onWikiLink = { target, _ ->
            val t = index.resolve(target)
            if (t != null) onOpenNote(t.id) else createAsk = target
        },
        linkExists = { index.resolve(it) != null },
        onTag = onTag,
        resolveAsset = assetResolver,
        onToggleTask = onToggleTask,
        onAppLink = onAppLink
    )

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues()).imePadding()
    ) {
        WikiTopBar(if (editing) stringResource(R.string.edit) else note.title, onBack = {
            if (editing) { commit(); editing = false } else onBack()
        }) {
            if (!editing) {
                IconButton(onClick = onAiLinks) { Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.wiki_ai_links)) }
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit)) }
            } else {
                IconButton(onClick = { commit(); editing = false }) { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save)) }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "more") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.wiki_autolink)) },
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                        onClick = { menuOpen = false; if (editing) { commit(); editing = false }; onAutoLink() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.wiki_move)) },
                        leadingIcon = { Icon(Icons.Default.Folder, null) },
                        onClick = { menuOpen = false; pickFolder = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.wiki_export_note)) },
                        leadingIcon = { Icon(Icons.Default.IosShare, null) },
                        onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; confirmDelete = true })
                }
            }
        }

        if (editing) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.wiki_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(folder, { folder = it }, label = { Text(stringResource(R.string.wiki_folder_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it }, label = { Text(stringResource(R.string.wiki_tags)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(aliases, { aliases = it }, label = { Text(stringResource(R.string.wiki_aliases)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.wiki_editor_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedFieldBox {
                    BasicTextField(
                        value = body, onValueChange = { body = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                        textStyle = LocalTextStyle.current.copy(
                            color = LocalContentColor.current, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(Modifier.height(80.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
                // header: folder · tags · dates
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(note.folder.ifBlank { "/" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(formatDate(note.updated), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val allTags = index.allTags(note)
                if (allTags.isNotEmpty() || note.aliases.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    MarkdownText(
                        (allTags.joinToString(" ") { "#$it" } + if (note.aliases.isNotEmpty()) "  ·  aka " + note.aliases.joinToString(", ") else ""),
                        actions = actions, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                MarkdownText(note.body, actions = actions, color = MaterialTheme.colorScheme.onSurface)

                // ── link panel ──
                val back = index.backlinks[note.id].orEmpty().mapNotNull { index.byId[it] }
                val mentionedBy = remember(index, note.id) { index.mentionedBy(note.id) }
                val unlinked = remember(index, note.id) { index.unlinkedMentions(note.id) }
                val missing = index.unresolved(note.id)
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LinkSection(stringResource(R.string.wiki_backlinks, back.size), back.map { it.title to it.id }, onOpenNote)
                if (mentionedBy.isNotEmpty())
                    LinkSection(stringResource(R.string.wiki_mentioned_by, mentionedBy.size), mentionedBy.map { it.title to it.id }, onOpenNote)
                if (unlinked.isNotEmpty()) {
                    LinkSection(stringResource(R.string.wiki_unlinked, unlinked.size), unlinked.map { it.title to it.id }, onOpenNote)
                    TextButton(onClick = onAutoLink) { Text(stringResource(R.string.wiki_autolink)) }
                }
                if (missing.isNotEmpty()) {
                    Text(stringResource(R.string.wiki_missing), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    missing.forEach { m ->
                        Text("+ $m", modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onCreateLinkedNote(m) }.padding(6.dp),
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onAiLinks) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.wiki_ai_links))
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete)) },
        text = { Text(note.title) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } }
    )
    if (pickFolder) FolderPickerDialog(index.folders(explicitFolders), note.folder, onDismiss = { pickFolder = false }) { f ->
        pickFolder = false; folder = f; onSave(note.copy(folder = f))
    }
    createAsk?.let { t ->
        AlertDialog(
            onDismissRequest = { createAsk = null },
            title = { Text(stringResource(R.string.wiki_create_q)) },
            text = { Text(t) },
            confirmButton = { TextButton(onClick = { createAsk = null; onCreateLinkedNote(t) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { createAsk = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (aiLink != null && aiLink.noteId == note.id) AiLinkDialog(aiLink, onDismissAi, onApplyAi)
}

@Composable
private fun LinkSection(title: String, items: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    items.forEach { (t, id) ->
        Text(t, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onOpen(id) }.padding(horizontal = 6.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Review dialog for the LLM librarian's suggestions. Nothing changes until "Apply". */
@Composable
private fun AiLinkDialog(
    state: AiLinkState,
    onDismiss: () -> Unit,
    onApply: (Set<String>, Boolean, Boolean, Boolean, Boolean) -> Unit
) {
    val r = state.result
    var accepted by remember(r) { mutableStateOf(r?.links?.map { it.target }?.toSet() ?: emptySet()) }
    var tags by remember(r) { mutableStateOf(true) }
    var folder by remember(r) { mutableStateOf(false) }
    var aliases by remember(r) { mutableStateOf(true) }
    var stubs by remember(r) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wiki_ai_links)) },
        text = {
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.wiki_ai_working))
                }
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                r != null -> Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    if (r.summary != null) Text(r.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.links.isEmpty()) Text(stringResource(R.string.wiki_ai_none))
                    r.links.forEach { s ->
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(checked = s.target in accepted, onCheckedChange = { c ->
                                accepted = if (c) accepted + s.target else accepted - s.target
                            })
                            Column(Modifier.padding(top = 10.dp)) {
                                Text((if (s.isNew) "🆕 " else "") + s.target, style = MaterialTheme.typography.bodyLarge)
                                val sub = listOfNotNull(s.anchor?.let { "„$it“" }, s.reason.takeIf { it.isNotBlank() }).joinToString(" — ")
                                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (r.tags.isNotEmpty()) CheckRow(stringResource(R.string.wiki_tags) + ": " + r.tags.joinToString(" ") { "#$it" }, tags) { tags = it }
                    if (!r.folder.isNullOrBlank()) CheckRow(stringResource(R.string.wiki_folder_path) + ": " + r.folder, folder) { folder = it }
                    if (r.aliases.isNotEmpty()) CheckRow(stringResource(R.string.wiki_aliases) + ": " + r.aliases.joinToString(), aliases) { aliases = it }
                    if (r.links.any { it.isNew }) CheckRow(stringResource(R.string.wiki_ai_stubs), stubs) { stubs = it }
                }
            }
        },
        confirmButton = {
            if (r != null) TextButton(onClick = { onApply(accepted, tags, folder, aliases, stubs) }) { Text(stringResource(R.string.wiki_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
