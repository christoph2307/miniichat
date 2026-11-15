package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.miniichat.ChatViewModel
import com.miniichat.R
import com.miniichat.data.ProviderConfig
import kotlinx.coroutines.launch

private enum class Screen { Chat, Settings, Providers, ProviderEdit, Assistants, Speech, Wiki, Note, Export, ExportTargets }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: ChatViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }

    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    androidx.activity.compose.BackHandler(enabled = showModelPicker) {
        showModelPicker = false
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Settings) {
        screen = Screen.Chat
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Providers) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.ProviderEdit) {
        screen = Screen.Providers
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Assistants) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Speech) {
        screen = Screen.Settings
    }

    // ── wiki navigation state ──
    var noteId by rememberSaveable { mutableStateOf<String?>(null) }
    var noteBackStack by rememberSaveable { mutableStateOf(listOf<String>()) }
    var noteReturn by rememberSaveable { mutableStateOf(Screen.Wiki) }
    var wikiFolder by rememberSaveable { mutableStateOf("") }
    var wikiQuery by rememberSaveable { mutableStateOf("") }
    var exportPreselect by rememberSaveable { mutableStateOf(listOf<String>()) }
    var exportReturn by rememberSaveable { mutableStateOf(Screen.Wiki) }
    var exportTargetsReturn by rememberSaveable { mutableStateOf(Screen.Export) }

    fun openNote(id: String, from: Screen) {
        if (screen == Screen.Note && noteId != null && noteId != id) noteBackStack = noteBackStack + noteId!!
        else if (screen != Screen.Note) { noteBackStack = emptyList(); noteReturn = from }
        noteId = id
        screen = Screen.Note
    }
    fun closeNote() {
        val prev = noteBackStack.lastOrNull()
        if (prev != null) { noteBackStack = noteBackStack.dropLast(1); noteId = prev }
        else screen = noteReturn
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Wiki) { screen = Screen.Chat }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Note) { closeNote() }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Export) { screen = exportReturn }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.ExportTargets) { screen = exportTargetsReturn }

    val conversations by vm.conversations.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val settings by vm.settings.collectAsState()
    val providers by vm.providers.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val streamingOverlay by vm.streamingOverlay.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val fetchingId by vm.fetchingModelsFor.collectAsState()
    val transcription by vm.transcription.collectAsState()
    val pendingTranscript by vm.pendingTranscript.collectAsState()
    val attachmentUpdate by vm.attachmentUpdates.collectAsState()
    val installedModels by vm.modelManager.installed.collectAsState()
    val hasSpeechModel = settings.whisperModel.isNotBlank() && settings.whisperModel in installedModels
    val wikiIndex by vm.wikiIndex.collectAsState()
    val wikiFolders by vm.wikiFolders.collectAsState()
    val aiLink by vm.aiLink.collectAsState()
    val exportRun by vm.exportRun.collectAsState()
    val openNoteRequest by vm.openNote.collectAsState()
    LaunchedEffect(openNoteRequest) {
        openNoteRequest?.let { id ->
            vm.consumeOpenNote()
            openNote(id, if (screen == Screen.Note) noteReturn else screen)
        }
    }
    val chatMarkdownActions = MarkdownActions(
        onWikiLink = { target, _ -> wikiIndex.resolve(target)?.let { openNote(it.id, Screen.Chat) } },
        linkExists = { wikiIndex.resolve(it) != null },
        onTag = { t -> wikiQuery = "#$t"; screen = Screen.Wiki },
        resolveAsset = { vm.wikiStore.assetFile(it) }
    )

    val activeConv = conversations.firstOrNull { it.id == activeId }
    val activeProvider = providers.firstOrNull { it.id == settings.activeProviderId }
    val assistants by vm.assistants.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.clearToast()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (screen) {
            Screen.Chat -> {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        GlassDrawer(
                            conversations = conversations,
                            activeId = activeId,
                            onSelect = { id ->
                                vm.selectConversation(id)
                                scope.launch { drawerState.close() }
                            },
                            onNew = {
                                vm.newConversation()
                                scope.launch { drawerState.close() }
                            },
                            onDelete = { vm.deleteConversation(it) },
                            onRename = { id, t -> vm.renameConversation(id, t) },
                            onOpenSettings = {
                                screen = Screen.Settings
                                scope.launch { drawerState.close() }
                            },
                            onOpenWiki = {
                                screen = Screen.Wiki
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                ) {
                    ChatScreen(
                        conversation = activeConv,
                        settings = settings,
                        activeProvider = activeProvider,
                        isStreaming = isStreaming,
                        streamingOverlay = streamingOverlay,
                        onMenu = { scope.launch { drawerState.open() } },
                        onSend = { text, atts -> vm.sendMessage(text, atts) },
                        onStop = { vm.stopStreaming() },
                        onRegenerate = { vm.regenerate() },
                        onRegenerateFrom = { msgId -> vm.regenerateFrom(msgId) },
                        onDeleteMessage = { msgId -> vm.deleteMessage(msgId) },
                        onEditMessage = { msgId, newText -> vm.editMessage(msgId, newText) },
                        onNew = { vm.newConversation() },
                        onOpenSettings = { screen = Screen.Settings },
                        onPickModel = {
                            if (providers.isEmpty()) {
                                editingProvider = null
                                screen = Screen.ProviderEdit
                            } else showModelPicker = true
                        },
                        transcription = transcription,
                        pendingTranscript = pendingTranscript,
                        onConsumeTranscript = { vm.consumePendingTranscript() },
                        attachmentUpdate = attachmentUpdate,
                        onConsumeAttachmentUpdate = { vm.consumeAttachmentUpdate() },
                        hasSpeechModel = hasSpeechModel,
                        onRecordingFinished = { rec, tr, add -> vm.onRecordingFinished(rec, tr, add) },
                        onImportAudio = { uri, add -> vm.importAudio(uri, add) },
                        onTranscribe = { vm.transcribe(it) },
                        onCancelTranscription = { vm.cancelTranscription() },
                        onSaveToWiki = { vm.saveMessageToWiki(it) },
                        markdownActions = chatMarkdownActions,
                        onOpenWiki = { screen = Screen.Wiki }
                    )
                }
            }
            Screen.Settings -> {
                SettingsScreen(
                    settings = settings,
                    providers = providers,
                    assistants = assistants,
                    onBack = { screen = Screen.Chat },
                    onChange = { vm.updateSettings(it) },
                    onOpenProviders = { screen = Screen.Providers },
                    onOpenAssistants = { screen = Screen.Assistants },
                    onOpenSpeech = { screen = Screen.Speech },
                    onOpenExportTargets = { exportTargetsReturn = Screen.Settings; screen = Screen.ExportTargets }
                )
            }
            Screen.Wiki -> {
                WikiScreen(
                    index = wikiIndex,
                    explicitFolders = wikiFolders,
                    folder = wikiFolder,
                    onFolderChange = { wikiFolder = it },
                    query = wikiQuery,
                    onQueryChange = { wikiQuery = it },
                    onBack = { screen = Screen.Chat },
                    onOpenNote = { openNote(it, Screen.Wiki) },
                    onCreateNote = { t, f -> vm.createNote(t, f) },
                    onCreateFolder = { vm.createFolder(it) },
                    onRenameFolder = { a, b -> vm.renameFolder(a, b) },
                    onDeleteFolder = { vm.deleteFolder(it) },
                    onMoveNote = { id, f -> vm.moveNote(id, f) },
                    onDeleteNote = { vm.deleteNote(it) },
                    onAutoLinkAll = { vm.autoLinkAll() },
                    onExport = { exportPreselect = emptyList(); exportReturn = Screen.Wiki; screen = Screen.Export }
                )
            }
            Screen.Note -> {
                val note = noteId?.let { wikiIndex.byId[it] }
                if (note == null) {
                    LaunchedEffect(noteId, wikiIndex) {
                        // note may still be loading right after creation; give up if it vanished
                        kotlinx.coroutines.delay(400)
                        if (noteId?.let { vm.wikiStore.get(it) } == null) closeNote()
                    }
                } else {
                    NoteScreen(
                        note = note,
                        index = wikiIndex,
                        explicitFolders = wikiFolders,
                        assetResolver = { vm.wikiStore.assetFile(it) },
                        aiLink = aiLink,
                        onBack = { closeNote() },
                        onSave = { vm.saveNote(it) },
                        onOpenNote = { openNote(it, noteReturn) },
                        onCreateLinkedNote = { t -> vm.createNote(t, note.folder) },
                        onTag = { t -> wikiQuery = "#$t"; screen = Screen.Wiki },
                        onToggleTask = { line, c -> vm.toggleTask(note.id, line, c) },
                        onAutoLink = { vm.autoLinkNote(note.id) },
                        onAiLinks = { vm.requestAiLinks(note.id) },
                        onDismissAi = { vm.dismissAiLinks() },
                        onApplyAi = { acc, tg, fo, al, st -> vm.applyAiLinks(acc, tg, fo, al, st) },
                        onDelete = { vm.deleteNote(note.id); closeNote() },
                        onExport = { exportPreselect = listOf(note.id); exportReturn = Screen.Note; screen = Screen.Export }
                    )
                }
            }
            Screen.Export -> {
                ExportScreen(
                    index = wikiIndex,
                    preselected = exportPreselect.toSet(),
                    exportRun = exportRun,
                    onBack = { screen = exportReturn },
                    buildBundle = { ids, o -> vm.buildExportBundle(ids, o) },
                    onExportToTarget = { t, ids, o -> vm.exportToTarget(t, ids, o) },
                    onClearRun = { vm.clearExportRun() },
                    onOpenTargets = { exportTargetsReturn = Screen.Export; screen = Screen.ExportTargets }
                )
            }
            Screen.ExportTargets -> {
                ExportTargetsScreen(store = vm.exportConfig, onBack = { screen = exportTargetsReturn })
            }
            Screen.Speech -> {
                SpeechSettingsScreen(
                    settings = settings,
                    manager = vm.modelManager,
                    onBack = { screen = Screen.Settings },
                    onChange = { vm.updateSettings(it) },
                    recordingsUsage = { vm.recordingsUsage() },
                    onDeleteUnusedRecordings = { vm.deleteUnusedRecordings() }
                )
            }
            Screen.Assistants -> {
                AssistantsScreen(
                    assistants = assistants,
                    activeId = settings.activeAssistantId,
                    onBack = { screen = Screen.Settings },
                    onSelect = { vm.selectAssistant(it) },
                    onUpsert = { vm.upsertAssistant(it) },
                    onDelete = { vm.deleteAssistant(it) }
                )
            }
            Screen.Providers -> {
                ProvidersScreen(
                    providers = providers,
                    fetchingId = fetchingId,
                    activeProviderId = settings.activeProviderId,
                    activeModel = settings.activeModel,
                    onBack = { screen = Screen.Settings },
                    onCreate = {
                        editingProvider = null
                        screen = Screen.ProviderEdit
                    },
                    onEdit = { p ->
                        editingProvider = p
                        screen = Screen.ProviderEdit
                    },
                    onDelete = { vm.deleteProvider(it) },
                    onFetchModels = { vm.fetchModels(it) },
                    onAddManualModel = { id, m -> vm.addManualModel(id, m) },
                    onRemoveModel = { id, m -> vm.removeModel(id, m) },
                    onSelectModel = { id, m -> vm.selectModel(id, m) }
                )
            }
            Screen.ProviderEdit -> {
                ProviderEditorScreen(
                    initial = editingProvider,
                    onCancel = { screen = Screen.Providers },
                    onSave = { p ->
                        vm.upsertProvider(p)
                        screen = Screen.Providers
                    }
                )
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.fillMaxSize())
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = providers,
            activeProviderId = settings.activeProviderId,
            activeModel = settings.activeModel,
            onPick = { pid, m -> vm.selectModel(pid, m) },
            onDismiss = { showModelPicker = false }
        )
    }

    error?.let { msg ->
        val needsProviderFix = msg.contains("provider", ignoreCase = true)
            || msg.contains("api key", ignoreCase = true)
            || msg.contains("model", ignoreCase = true)
            || msg.contains("401")
            || msg.contains("403")
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            confirmButton = {
                if (needsProviderFix) {
                    TextButton(onClick = {
                        vm.clearError()
                        screen = Screen.Providers
                    }) { Text(stringResource(R.string.error_open_providers)) }
                } else {
                    TextButton(onClick = { vm.clearError() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = if (needsProviderFix) {
                {
                    TextButton(onClick = { vm.clearError() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            } else null,
            title = { Text(stringResource(R.string.error_dialog_title)) },
            text = { Text(msg) }
        )
    }
}
