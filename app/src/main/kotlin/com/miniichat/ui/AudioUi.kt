package com.miniichat.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichat.R
import com.miniichat.TranscriptionUi
import com.miniichat.audio.DictaphoneRecorder
import com.miniichat.audio.FinishedRecording
import com.miniichat.audio.RecorderStatus
import com.miniichat.data.Attachment
import com.miniichat.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One shared MediaPlayer for previewing recordings. */
class AudioPlayback {
    var playingUri by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null

    fun toggle(ctx: android.content.Context, uri: String) {
        if (playingUri == uri) { stop(); return }
        stop()
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(ctx, Uri.parse(uri))
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            player = p
            playingUri = uri
        }.onFailure { p.release() }
    }

    fun stop() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        playingUri = null
    }
}

@Composable
fun rememberAudioPlayback(): AudioPlayback {
    val pb = remember { AudioPlayback() }
    DisposableEffect(Unit) { onDispose { pb.stop() } }
    return pb
}

/** Chip for an audio attachment: play/stop, duration, optional transcribe + remove actions. */
@Composable
fun AudioAttachmentChip(
    att: Attachment,
    playback: AudioPlayback,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurface,
    onTranscribe: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val playing = playback.playingUri == att.uri
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { playback.toggle(ctx, att.uri) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (playing) "stop" else "play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = 180.dp)) {
            Text(
                stringResource(R.string.voice_recording),
                color = content,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
            Text(
                buildString {
                    append(formatDuration(att.durationMs))
                    if (!att.transcript.isNullOrBlank()) append(" · ").append(ctx.getString(R.string.transcribed))
                },
                color = content.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                maxLines = 1
            )
        }
        if (onTranscribe != null) {
            Spacer(Modifier.width(4.dp))
            SmallIconButton(Icons.Default.Subtitles, "transcribe", content, onTranscribe)
        }
        if (onRemove != null) {
            SmallIconButton(Icons.Default.Close, "remove", content, onRemove)
        }
    }
}

@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(15.dp))
    }
}

/** Progress strip shown above the input bar while whisper is running. */
@Composable
fun TranscriptionBanner(state: TranscriptionUi, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            val label = when (state.phase) {
                "decoding" -> stringResource(R.string.decoding_audio)
                "loading" -> stringResource(R.string.loading_model)
                else -> stringResource(R.string.transcribing_pct, (state.progress * 100).toInt())
            }
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.cancel),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        if (state.phase == "loading") LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        if (state.partial.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                state.partial.takeLast(240),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                maxLines = 3
            )
        }
    }
}

/**
 * Dictaphone sheet. Recording starts as soon as it opens (permission already granted).
 * Cannot be swiped away while recording, so nothing gets lost by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderSheet(
    autoTranscribe: Boolean,
    hasModel: Boolean,
    onFinished: (FinishedRecording, transcribe: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by DictaphoneRecorder.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || DictaphoneRecorder.state.value.status == RecorderStatus.Idle }
    )
    val history = remember { mutableStateListOf<Float>() }

    LaunchedEffect(Unit) {
        if (DictaphoneRecorder.state.value.status == RecorderStatus.Idle) {
            if (!DictaphoneRecorder.start(ctx)) {
                android.widget.Toast.makeText(
                    ctx, DictaphoneRecorder.state.value.error ?: "Cannot start recording",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                DictaphoneRecorder.clearError()
                onDismiss()
            }
        }
    }
    LaunchedEffect(state.elapsedMs / 100) {
        history.add(state.level)
        while (history.size > 60) history.removeAt(0)
    }

    fun finish(transcribe: Boolean) {
        DictaphoneRecorder.stop { rec ->
            scope.launch {
                withContext(Dispatchers.Main) {
                    if (rec != null) onFinished(rec, transcribe)
                    onDismiss()
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (state.status == RecorderStatus.Idle) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val recording = state.status == RecorderStatus.Recording
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        if (recording) Color(0xFFE53935) else MaterialTheme.colorScheme.outline
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.status == RecorderStatus.Paused) stringResource(R.string.rec_paused)
                    else stringResource(R.string.rec_recording),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                formatDuration(state.elapsedMs),
                style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(16.dp))
            // level history meter
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                history.forEach { lv ->
                    val h by animateFloatAsState(lv.coerceIn(0.04f, 1f), label = "lv")
                    Box(
                        Modifier.width(3.dp).fillMaxHeight(h).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !hasModel -> stringResource(R.string.rec_hint_no_model)
                    autoTranscribe -> stringResource(R.string.rec_hint_auto)
                    else -> stringResource(R.string.rec_hint_manual)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundAction(Icons.Default.Delete, stringResource(R.string.rec_discard),
                    MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, 52) {
                    DictaphoneRecorder.cancel()
                    onDismiss()
                }
                val paused = state.status == RecorderStatus.Paused
                RoundAction(
                    if (paused) Icons.Default.Mic else Icons.Default.Pause,
                    if (paused) stringResource(R.string.rec_resume) else stringResource(R.string.rec_pause),
                    MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, 64
                ) {
                    if (paused) DictaphoneRecorder.resume() else DictaphoneRecorder.pause()
                }
                RoundAction(Icons.Default.Check, stringResource(R.string.rec_done),
                    MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, 52) {
                    finish(transcribe = autoTranscribe)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    bg: Color,
    fg: Color,
    sizeDp: Int,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(sizeDp.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size((sizeDp * 0.45f).dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Expandable transcript under an audio attachment in a sent message. */
@Composable
fun TranscriptPreview(text: String, color: Color) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .heightIn(max = if (expanded) 400.dp else 60.dp)
            .then(if (expanded) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        color = color,
        maxLines = if (expanded) Int.MAX_VALUE else 2
    )
}
