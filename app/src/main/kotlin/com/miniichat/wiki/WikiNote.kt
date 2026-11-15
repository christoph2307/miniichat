package com.miniichat.wiki

import java.time.Instant

/**
 * One wiki page. Stored on disk as `<id>.md` with a YAML frontmatter block.
 * [folder] is a *virtual* folder path ("Projects/Laser/2026"), independent of the
 * on-disk layout, so notes can be moved without renaming files or breaking links.
 */
data class WikiNote(
    val id: String,
    val title: String,
    val folder: String = "",
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    /** Where the note came from, e.g. "chat:<conversationId>/<messageId>". */
    val source: String = "",
    /** Any frontmatter keys we don't model explicitly are preserved verbatim. */
    val extra: Map<String, String> = emptyMap(),
    /** Markdown body without the frontmatter block. */
    val body: String = ""
) {
    val path: String get() = if (folder.isBlank()) title else "$folder/$title"

    /** Short plain-text summary used in the index digest for the LLM. */
    fun summary(maxChars: Int = 160): String {
        val plain = body.replace(Regex("```[\\s\\S]*?(```|\\z)"), " ").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("```") && !it.startsWith("![[") }
            .joinToString(" ")
            .replace(Regex("\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?]]")) { it.groupValues[2].ifBlank { it.groupValues[1] } }
            .replace(Regex("[*_`>=~]"), "")
            .replace(Regex("\\s+"), " ")
        return if (plain.length <= maxChars) plain else plain.take(maxChars - 1).trimEnd() + "…"
    }

    fun toMarkdownFile(): String = Frontmatter.write(this) + body.trimEnd() + "\n"

    companion object {
        fun normFolder(f: String): String =
            f.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/")

        /** Characters that would break [[wikilinks]] or file names. */
        fun sanitizeTitle(t: String): String =
            t.replace(Regex("[\\[\\]|#^\\\\/:*?\"<>\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim()
                .ifBlank { "Untitled" }.take(120)
    }
}

/** Tiny YAML-frontmatter reader/writer: scalars, inline lists `[a, b]` and block lists `- a`. */
object Frontmatter {
    data class Parsed(val fields: Map<String, Any>, val body: String, val hadFrontmatter: Boolean)

    fun split(text: String): Parsed {
        val normalized = text.replace("\r\n", "\n").trimStart('﻿')
        if (!normalized.startsWith("---\n") && normalized.trimEnd() != "---") return Parsed(emptyMap(), normalized, false)
        val end = Regex("^---\\s*$", RegexOption.MULTILINE).findAll(normalized).drop(1).firstOrNull()
            ?: return Parsed(emptyMap(), normalized, false)
        val yaml = normalized.substring(4, end.range.first)
        val body = normalized.substring(minOf(normalized.length, end.range.last + 1)).removePrefix("\n")
        return Parsed(parseYaml(yaml), body, true)
    }

    fun parseYaml(yaml: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        var listKey: String? = null
        for (raw in yaml.lines()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val item = Regex("^\\s*-\\s+(.*)$").matchEntire(raw)
            if (item != null && listKey != null) {
                @Suppress("UNCHECKED_CAST")
                (out.getOrPut(listKey) { mutableListOf<String>() } as? MutableList<String>)?.add(unquote(item.groupValues[1]))
                continue
            }
            val kv = Regex("^([A-Za-z0-9_\\-]+)\\s*:\\s*(.*)$").matchEntire(raw.trim()) ?: continue
            val key = kv.groupValues[1]
            val v = kv.groupValues[2].trim()
            listKey = null
            when {
                v.isEmpty() -> { listKey = key; out[key] = mutableListOf<String>() }
                v.startsWith("[") && v.endsWith("]") ->
                    out[key] = v.substring(1, v.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }.toMutableList()
                else -> out[key] = unquote(v)
            }
        }
        return out
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\''))))
            t.substring(1, t.length - 1).replace("\\\"", "\"") else t
    }

    private fun quote(s: String): String =
        if (s.isEmpty() || s.any { it in ":#[]{},&*!|>'\"%@`" } || s != s.trim()) "\"" + s.replace("\"", "\\\"") + "\"" else s

    private fun list(l: List<String>) = l.joinToString(", ", "[", "]") { quote(it) }

    fun write(n: WikiNote): String = buildString {
        append("---\n")
        append("id: ").append(n.id).append('\n')
        append("title: ").append(quote(n.title)).append('\n')
        if (n.folder.isNotBlank()) append("folder: ").append(quote(n.folder)).append('\n')
        if (n.tags.isNotEmpty()) append("tags: ").append(list(n.tags)).append('\n')
        if (n.aliases.isNotEmpty()) append("aliases: ").append(list(n.aliases)).append('\n')
        append("created: ").append(Instant.ofEpochMilli(n.created)).append('\n')
        append("updated: ").append(Instant.ofEpochMilli(n.updated)).append('\n')
        if (n.source.isNotBlank()) append("source: ").append(quote(n.source)).append('\n')
        for ((k, v) in n.extra) append(k).append(": ").append(quote(v)).append('\n')
        append("---\n\n")
    }

    private val KNOWN = setOf("id", "title", "folder", "tags", "tag", "aliases", "alias", "created", "updated", "source")

    private fun asList(v: Any?): List<String> = when (v) {
        is List<*> -> v.mapNotNull { it?.toString()?.trim()?.removePrefix("#") }.filter { it.isNotEmpty() }
        is String -> v.split(',', ' ').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun time(v: Any?): Long? = (v as? String)?.let { s ->
        runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(s.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Build a note from markdown text (file on disk or LLM output). */
    fun toNote(text: String, fallbackId: String, fallbackTitle: String): WikiNote {
        val p = split(text)
        val f = p.fields
        val body = p.body
        val h1 = Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)?.trim()
        val title = WikiNote.sanitizeTitle((f["title"] as? String)?.takeIf { it.isNotBlank() } ?: h1 ?: fallbackTitle)
        val now = System.currentTimeMillis()
        return WikiNote(
            id = (f["id"] as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_\\-]{4,64}")) } ?: fallbackId,
            title = title,
            folder = WikiNote.normFolder((f["folder"] as? String).orEmpty()),
            tags = (asList(f["tags"]) + asList(f["tag"])).distinct(),
            aliases = (asList(f["aliases"]) + asList(f["alias"])).distinct(),
            created = time(f["created"]) ?: now,
            updated = time(f["updated"]) ?: now,
            source = (f["source"] as? String).orEmpty(),
            extra = f.filterKeys { it !in KNOWN }.mapValues { (_, v) -> if (v is List<*>) v.joinToString(", ") else v.toString() },
            body = body
        )
    }
}
