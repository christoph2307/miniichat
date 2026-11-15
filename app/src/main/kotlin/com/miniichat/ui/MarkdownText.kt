package com.miniichat.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichat.wiki.Frontmatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Callbacks that make Markdown interactive inside the wiki and chat.
 * All optional — without them the renderer is a plain (read-only) Markdown view.
 */
data class MarkdownActions(
    val onWikiLink: ((target: String, heading: String?) -> Unit)? = null,
    /** true if a `[[target]]` resolves to an existing note (unresolved links are dimmed). */
    val linkExists: (String) -> Boolean = { true },
    val onTag: ((String) -> Unit)? = null,
    /** Resolve `![[name]]` / `![alt](path)` to a local file. */
    val resolveAsset: ((String) -> File?)? = null,
    /** Toggle `- [ ]` at the given 0-based source line. */
    val onToggleTask: ((line: Int, checked: Boolean) -> Unit)? = null
)

/**
 * Markdown renderer (block + inline), no external dependency. Supported syntax is listed
 * in docs/MARKDOWN.md — CommonMark core, GFM tables/task lists/strikethrough/autolinks and
 * the Obsidian extensions (wikilinks, embeds, tags, callouts, highlights, frontmatter).
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    actions: MarkdownActions = MarkdownActions(),
    showFrontmatter: Boolean = true
) {
    val blocks = remember(text) { parseBlocks(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val ctx = InlineCtx(
        link = linkColor,
        unresolved = dim,
        tag = MaterialTheme.colorScheme.tertiary.takeIf { it != Color.Unspecified } ?: linkColor,
        highlight = Color(0x66FFD54F),
        codeBg = MaterialTheme.colorScheme.surfaceVariant,
        actions = actions
    )
    Column(modifier = modifier) {
        blocks.forEachIndexed { idx, block ->
            if (idx > 0) Spacer(Modifier.height(if (block is Block.ListItem && blocks[idx - 1] is Block.ListItem) 2.dp else 8.dp))
            RenderBlock(block, color, ctx, showFrontmatter)
        }
    }
}

@Composable
private fun RenderBlock(block: Block, color: Color, ctx: InlineCtx, showFrontmatter: Boolean) {
    val body = MaterialTheme.typography.bodyLarge
    when (block) {
        is Block.Properties -> if (showFrontmatter && block.fields.isNotEmpty()) PropertiesBlock(block.fields, ctx)
        is Block.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp)
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp)
            }
            Text(parseInline(block.text, ctx), color = color, style = style)
        }
        is Block.Paragraph -> Text(parseInline(block.text, ctx), color = color, style = body)
        is Block.ListItem -> {
            Row(modifier = Modifier.fillMaxWidth().padding(start = (block.indent * 16).dp)) {
                when {
                    block.task != null -> {
                        val checked = block.task == true
                        Icon(
                            if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 2.dp, end = 6.dp)
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .then(
                                    if (ctx.actions.onToggleTask != null)
                                        Modifier.clickable { ctx.actions.onToggleTask?.invoke(block.line, !checked) }
                                    else Modifier
                                )
                        )
                        Text(
                            parseInline(block.text, ctx), color = if (checked) color.copy(alpha = 0.6f) else color,
                            style = if (checked) body.copy(textDecoration = TextDecoration.LineThrough) else body
                        )
                    }
                    block.number != null -> {
                        Text("${block.number}. ", color = color, style = body)
                        Text(parseInline(block.text, ctx), color = color, style = body)
                    }
                    else -> {
                        Text(if (block.indent % 2 == 0) "•  " else "◦  ", color = color, style = body)
                        Text(parseInline(block.text, ctx), color = color, style = body)
                    }
                }
            }
        }
        is Block.Quote -> {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                Spacer(Modifier.width(10.dp))
                Text(
                    parseInline(block.text, ctx),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = body.copy(fontStyle = FontStyle.Italic)
                )
            }
        }
        is Block.Callout -> CalloutBlock(block, color, ctx, showFrontmatter)
        is Block.Hr -> Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        is Block.CodeBlock -> CodeBlockView(block.lang, block.code, color)
        is Block.Table -> TableBlock(block, color, ctx)
        is Block.Embed -> EmbedBlock(block, ctx)
    }
}

@Composable
private fun PropertiesBlock(fields: List<Pair<String, String>>, ctx: InlineCtx) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for ((k, v) in fields) {
            Row {
                Text(
                    k, modifier = Modifier.width(84.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val shown = if (k == "tags") v.split(',').filter { it.isNotBlank() }.joinToString(" ") { "#" + it.trim() } else v
                Text(parseInline(shown, ctx), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun CalloutBlock(block: Block.Callout, color: Color, ctx: InlineCtx, showFrontmatter: Boolean) {
    val (accent, icon) = calloutStyle(block.kind)
    var open by rememberSaveable(block.hashCode()) { mutableStateOf(block.fold != '-') }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (block.fold != null) Modifier.fillMaxWidth().clickable { open = !open } else Modifier.fillMaxWidth()
        ) {
            Text(icon + "  ", style = MaterialTheme.typography.bodyLarge)
            Text(
                parseInline(block.title.ifBlank { block.kind.replaceFirstChar { it.uppercase() } }, ctx),
                style = MaterialTheme.typography.titleMedium, color = accent, modifier = Modifier.weight(1f)
            )
            if (block.fold != null) Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = accent)
        }
        if (open && block.body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            MarkdownText(block.body, color = color, actions = ctx.actions, showFrontmatter = showFrontmatter)
        }
    }
}

private fun calloutStyle(kind: String): Pair<Color, String> = when (kind.lowercase()) {
    "warning", "caution", "attention" -> Color(0xFFF59E0B) to "⚠️"
    "danger", "error", "bug", "failure", "fail", "missing" -> Color(0xFFEF4444) to "⛔"
    "tip", "hint", "important" -> Color(0xFF10B981) to "💡"
    "success", "check", "done" -> Color(0xFF22C55E) to "✅"
    "question", "help", "faq" -> Color(0xFFF97316) to "❓"
    "example" -> Color(0xFF8B5CF6) to "🧪"
    "quote", "cite" -> Color(0xFF9CA3AF) to "❝"
    "abstract", "summary", "tldr" -> Color(0xFF06B6D4) to "📋"
    "todo" -> Color(0xFF3B82F6) to "☑️"
    else -> Color(0xFF3B82F6) to "ℹ️" // note, info
}

@Composable
private fun CodeBlockView(lang: String, code: String, color: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
    ) {
        if (lang.isNotBlank()) {
            Text(
                lang,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Box(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                code, color = color,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            )
        }
    }
}

@Composable
private fun TableBlock(block: Block.Table, color: Color, ctx: InlineCtx) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, outline, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            val all = listOf(block.header) + block.rows
            all.forEachIndexed { r, row ->
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min)
                        .background(if (r == 0) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                ) {
                    for (c in block.header.indices) {
                        val cell = row.getOrElse(c) { "" }
                        Box(
                            modifier = Modifier.widthIn(min = 72.dp, max = 260.dp).fillMaxHeight()
                                .border(0.5.dp, outline)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = when (block.align.getOrNull(c)) {
                                'c' -> Alignment.TopCenter; 'r' -> Alignment.TopEnd; else -> Alignment.TopStart
                            }
                        ) {
                            Text(
                                parseInline(cell, ctx), color = color,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbedBlock(block: Block.Embed, ctx: InlineCtx) {
    val target = block.target
    val isImage = Regex("(?i)\\.(png|jpe?g|webp|gif|bmp|heic)$").containsMatchIn(target)
    val file = ctx.actions.resolveAsset?.invoke(target)
    if (isImage && file != null && file.exists()) {
        val bmp by produceState<ImageBitmap?>(null, file.absolutePath) {
            value = withContext(Dispatchers.IO) { decodeSampled(file, 1280) }
        }
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b, contentDescription = block.alt,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        }
    } else {
        // note embed or missing file → render as link chip
        Text(
            parseInline(if (isImage) "🖼 ${block.target}" else "[[${block.target}]]", ctx),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun decodeSampled(file: File, maxDim: Int): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, o)
    var sample = 1
    while (o.outWidth / (sample * 2) >= maxDim || o.outHeight / (sample * 2) >= maxDim) sample *= 2
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

// ───────────────────────────────── block parser ─────────────────────────────────

private sealed class Block {
    data class Properties(val fields: List<Pair<String, String>>) : Block()
    data class Heading(val level: Int, val text: String) : Block()
    data class Paragraph(val text: String) : Block()
    data class ListItem(val indent: Int, val text: String, val number: Int? = null, val task: Boolean? = null, val line: Int = 0) : Block()
    data class Quote(val text: String) : Block()
    data class Callout(val kind: String, val title: String, val fold: Char?, val body: String) : Block()
    data class CodeBlock(val lang: String, val code: String) : Block()
    data class Table(val header: List<String>, val align: List<Char>, val rows: List<List<String>>) : Block()
    data class Embed(val target: String, val alt: String) : Block()
    object Hr : Block()
}

private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

private fun splitRow(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.substring(1)
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
    // split on unescaped pipes that are not inside [[ ]] (wikilink aliases)
    val cells = mutableListOf<String>()
    val sb = StringBuilder()
    var depth = 0
    var i = 0
    while (i < t.length) {
        val c = t[i]
        when {
            c == '\\' && i + 1 < t.length && t[i + 1] == '|' -> { sb.append('|'); i++ }
            t.startsWith("[[", i) -> { depth++; sb.append("[["); i++ }
            t.startsWith("]]", i) && depth > 0 -> { depth--; sb.append("]]"); i++ }
            c == '|' && depth == 0 -> { cells.add(sb.toString().trim()); sb.setLength(0) }
            else -> sb.append(c)
        }
        i++
    }
    cells.add(sb.toString().trim())
    return cells
}

private fun parseBlocks(input: String): List<Block> {
    val result = mutableListOf<Block>()
    // frontmatter → properties block
    val fm = Frontmatter.split(input)
    val lineOffset = if (fm.hadFrontmatter) input.replace("\r\n", "\n").substringBefore(fm.body).count { it == '\n' } else 0
    if (fm.hadFrontmatter) {
        val shown = fm.fields.filterKeys { it !in setOf("id", "source") }.map { (k, v) ->
            k to if (v is List<*>) v.joinToString(", ") else v.toString()
        }
        result.add(Block.Properties(shown))
    }
    val lines = fm.body.split("\n")
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) result.add(Block.Paragraph(paragraph.toString().trim()))
        paragraph.setLength(0)
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // fenced code (``` or ~~~), also $$ math blocks
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~") || trimmed.startsWith("$$")) {
            flushParagraph()
            val fence = trimmed.take(if (trimmed.startsWith("$$")) 2 else 3)
            val lang = if (fence == "$$") "math" else trimmed.removePrefix(fence).trim()
            val inlineMath = fence == "$$" && trimmed.length > 4 && trimmed.endsWith("$$")
            if (inlineMath) {
                result.add(Block.CodeBlock("math", trimmed.removeSurrounding("$$").trim())); i++; continue
            }
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(fence)) { code.append(lines[i]).append('\n'); i++ }
            result.add(Block.CodeBlock(lang, code.toString().trimEnd('\n')))
            if (i < lines.size) i++
            continue
        }
        if (Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed.replace(" ", ""))) {
            flushParagraph(); result.add(Block.Hr); i++; continue
        }
        val headingMatch = Regex("^(#{1,6})\\s+(.+?)\\s*#*$").matchEntire(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            result.add(Block.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++; continue
        }
        // setext headings
        if (paragraph.isEmpty() && line.isNotBlank() && i + 1 < lines.size &&
            Regex("^(=+|-+)\\s*$").matches(lines[i + 1]) && !trimmed.startsWith("-") && !trimmed.startsWith(">")
        ) {
            result.add(Block.Heading(if (lines[i + 1].trim().startsWith("=")) 1 else 2, trimmed)); i += 2; continue
        }
        // block embed ![[x]] or image ![alt](src) alone on a line
        val wikiEmbed = Regex("^!\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]*))?]]$").matchEntire(trimmed)
        val mdImage = Regex("^!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)$").matchEntire(trimmed)
        if (wikiEmbed != null) {
            flushParagraph()
            result.add(Block.Embed(wikiEmbed.groupValues[1].trim(), wikiEmbed.groupValues[2]))
            i++; continue
        }
        if (mdImage != null && !mdImage.groupValues[2].contains("://")) {
            flushParagraph()
            val src = runCatching { java.net.URLDecoder.decode(mdImage.groupValues[2], "UTF-8") }.getOrDefault(mdImage.groupValues[2])
            result.add(Block.Embed(src.substringAfterLast('/'), mdImage.groupValues[1]))
            i++; continue
        }
        // tables
        if (trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])) {
            flushParagraph()
            val header = splitRow(trimmed)
            val align = splitRow(lines[i + 1]).map { c ->
                val t = c.trim(); when { t.startsWith(":") && t.endsWith(":") -> 'c'; t.endsWith(":") -> 'r'; else -> 'l' }
            }
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows.add(splitRow(lines[i])); i++ }
            result.add(Block.Table(header, align, rows))
            continue
        }
        // quotes & callouts (multi-line)
        if (trimmed.startsWith(">")) {
            flushParagraph()
            val q = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                q.add(lines[i].trimStart().removePrefix(">").removePrefix(" ")); i++
            }
            val head = Regex("^\\[!([A-Za-z-]+)]([+-])?\\s*(.*)$").matchEntire(q.first().trim())
            if (head != null) {
                result.add(Block.Callout(head.groupValues[1], head.groupValues[3], head.groupValues[2].firstOrNull(), q.drop(1).joinToString("\n")))
            } else {
                result.add(Block.Quote(q.joinToString("\n").trim()))
            }
            continue
        }
        // list items (bullets, numbers, tasks) with indentation
        val li = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(?:\\[([ xX])]\\s+)?(.*)$").matchEntire(line)
        if (li != null) {
            flushParagraph()
            val indent = li.groupValues[1].replace("\t", "    ").length / 2
            val marker = li.groupValues[2]
            val task = li.groupValues[3].takeIf { it.isNotEmpty() }?.let { it.equals("x", true) }
            result.add(
                Block.ListItem(
                    indent = indent.coerceAtMost(6), text = li.groupValues[4],
                    number = marker.dropLast(1).toIntOrNull()?.takeIf { marker.last() in ".)" },
                    task = task, line = i + lineOffset
                )
            )
            i++; continue
        }
        if (line.isBlank()) { flushParagraph(); i++; continue }
        // hard line break: two trailing spaces or backslash
        if (paragraph.isNotEmpty()) paragraph.append(if (paragraph.endsWith("  ") || paragraph.endsWith("\\")) "\n" else " ")
        paragraph.append(line.trim().let { if (line.endsWith("  ")) "$it  " else it })
        i++
    }
    flushParagraph()
    return result
}

// ───────────────────────────────── inline parser ─────────────────────────────────

private class InlineCtx(
    val link: Color,
    val unresolved: Color,
    val tag: Color,
    val highlight: Color,
    val codeBg: Color,
    val actions: MarkdownActions
)

private val TAG_RE = Regex("^#([\\p{L}\\p{N}_\\-/]*[\\p{L}_\\-/][\\p{L}\\p{N}_\\-/]*)")
private val URL_RE = Regex("^https?://[^\\s<>()]+[^\\s<>().,;:!?\"'\\]]")

private fun parseInline(text: String, ctx: InlineCtx): AnnotatedString = buildAnnotatedString {
    val t = text.replace("  \n", "\n").replace("\\\n", "\n")
    var i = 0
    while (i < t.length) {
        val rest = t.substring(i)
        val c = t[i]
        // escapes
        if (c == '\\' && i + 1 < t.length && t[i + 1] in "\\`*_{}[]()#+-.!|=~$>") { append(t[i + 1]); i += 2; continue }
        if (c == '`') {
            val end = t.indexOf('`', i + 1)
            if (end > i) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = ctx.codeBg, fontSize = 14.sp)) {
                    append(t.substring(i + 1, end))
                }
                i = end + 1; continue
            }
        }
        // inline math $x$ (shown monospace)
        if (c == '$' && i + 1 < t.length && t[i + 1] != ' ' && t[i + 1] != '$') {
            val end = t.indexOf('$', i + 1)
            if (end > i + 1 && t[end - 1] != ' ') {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Italic)) { append(t.substring(i + 1, end)) }
                i = end + 1; continue
            }
        }
        // ![[embed]] / [[wikilink#heading|alias]]
        if (rest.startsWith("[[") || rest.startsWith("![[")) {
            val start = if (c == '!') i + 3 else i + 2
            val end = t.indexOf("]]", start)
            if (end > start) {
                val inner = t.substring(start, end)
                val target = inner.substringBefore('|').substringBefore('#').trim()
                val heading = inner.substringBefore('|').substringAfter('#', "").trim().ifBlank { null }
                val label = inner.substringAfter('|', "").ifBlank { if (heading != null) "$target › $heading" else target }
                val exists = ctx.actions.linkExists(target)
                val style = SpanStyle(
                    color = if (exists) ctx.link else ctx.unresolved,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (exists) null else TextDecoration.Underline
                )
                val cb = ctx.actions.onWikiLink
                if (cb != null) {
                    withLink(LinkAnnotation.Clickable("wiki:$target", TextLinkStyles(style)) { cb(target, heading) }) {
                        append(if (c == '!') "↳ $label" else label)
                    }
                } else withStyle(style) { append(label) }
                i = end + 2; continue
            }
        }
        if (rest.startsWith("***")) {
            val end = t.indexOf("***", i + 3)
            if (end > i + 3) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(parseInline(t.substring(i + 3, end), ctx)) }
                i = end + 3; continue
            }
        }
        if (rest.startsWith("**") || rest.startsWith("__")) {
            val d = rest.substring(0, 2)
            val end = t.indexOf(d, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(parseInline(t.substring(i + 2, end), ctx)) }
                i = end + 2; continue
            }
        }
        if (rest.startsWith("~~")) {
            val end = t.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(parseInline(t.substring(i + 2, end), ctx)) }
                i = end + 2; continue
            }
        }
        if (rest.startsWith("==")) {
            val end = t.indexOf("==", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(background = ctx.highlight)) { append(parseInline(t.substring(i + 2, end), ctx)) }
                i = end + 2; continue
            }
        }
        if ((c == '*' || c == '_') && !rest.startsWith("**") && !rest.startsWith("__")) {
            val wordBefore = i > 0 && t[i - 1].isLetterOrDigit()
            val end = t.indexOf(c, i + 1)
            if (end > i + 1 && !(c == '_' && wordBefore) && t[i + 1] != ' ' &&
                (end + 1 >= t.length || t[end + 1] != c) && !(c == '_' && end + 1 < t.length && t[end + 1].isLetterOrDigit())
            ) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(parseInline(t.substring(i + 1, end), ctx)) }
                i = end + 1; continue
            }
        }
        // footnote reference [^1]
        if (rest.startsWith("[^")) {
            val end = t.indexOf(']', i)
            if (end > i + 2) {
                withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 11.sp, color = ctx.link)) {
                    append(t.substring(i + 2, end))
                }
                i = end + 1; continue
            }
        }
        // [label](url) and inline images ![alt](src)
        if (c == '[' || (c == '!' && rest.startsWith("!["))) {
            val o = if (c == '!') i + 1 else i
            val close = t.indexOf(']', o + 1)
            if (close > o && close + 1 < t.length && t[close + 1] == '(') {
                val urlEnd = t.indexOf(')', close + 2)
                if (urlEnd > close + 1) {
                    val label = t.substring(o + 1, close)
                    val url = t.substring(close + 2, urlEnd).trim().substringBefore(' ')
                    val style = TextLinkStyles(SpanStyle(color = ctx.link, textDecoration = TextDecoration.Underline))
                    val isRelativeMd = !url.contains("://") && (url.endsWith(".md") || url.contains(".md#"))
                    if (isRelativeMd && ctx.actions.onWikiLink != null) {
                        val target = java.net.URLDecoder.decode(url.substringBefore('#').substringAfterLast('/').removeSuffix(".md"), "UTF-8")
                        val heading = url.substringAfter('#', "").ifBlank { null }
                        withLink(LinkAnnotation.Clickable("md:$url", style) { ctx.actions.onWikiLink?.invoke(target, heading) }) {
                            append(label.ifBlank { target })
                        }
                    } else if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                        withLink(LinkAnnotation.Url(url, style)) { append(if (c == '!') "🖼 ${label.ifBlank { url }}" else label.ifBlank { url }) }
                    } else {
                        withStyle(style.style ?: SpanStyle()) { append(if (c == '!') "🖼 $label" else label) }
                    }
                    i = urlEnd + 1; continue
                }
            }
        }
        // <https://autolink>
        if (c == '<') {
            val end = t.indexOf('>', i)
            val inner = if (end > i) t.substring(i + 1, end) else ""
            if (inner.startsWith("http://") || inner.startsWith("https://")) {
                withLink(LinkAnnotation.Url(inner, TextLinkStyles(SpanStyle(color = ctx.link, textDecoration = TextDecoration.Underline)))) { append(inner) }
                i = end + 1; continue
            }
        }
        // bare URLs (GFM autolink)
        if ((c == 'h') && (i == 0 || !t[i - 1].isLetterOrDigit())) {
            val m = URL_RE.find(rest)
            if (m != null) {
                withLink(LinkAnnotation.Url(m.value, TextLinkStyles(SpanStyle(color = ctx.link, textDecoration = TextDecoration.Underline)))) { append(m.value) }
                i += m.value.length; continue
            }
        }
        // #tags (not headings — those are handled at block level)
        if (c == '#' && (i == 0 || t[i - 1].isWhitespace() || t[i - 1] == '(')) {
            val m = TAG_RE.find(rest)
            if (m != null) {
                val tag = m.groupValues[1]
                val style = SpanStyle(color = ctx.tag, background = ctx.tag.copy(alpha = 0.12f))
                val cb = ctx.actions.onTag
                if (cb != null) withLink(LinkAnnotation.Clickable("tag:$tag", TextLinkStyles(style)) { cb(tag) }) { append(m.value) }
                else withStyle(style) { append(m.value) }
                i += m.value.length; continue
            }
        }
        // ignore HTML comments %% obsidian comments %%
        if (rest.startsWith("%%")) {
            val end = t.indexOf("%%", i + 2)
            if (end > 0) { i = end + 2; continue }
        }
        append(c); i++
    }
}
