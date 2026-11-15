package com.miniichat.wiki

/** A parsed `[[Target#Heading|Alias]]` link (or `![[embed]]`). */
data class WikiLink(val target: String, val heading: String?, val alias: String?, val embed: Boolean, val range: IntRange)

object WikiSyntax {
    val LINK = Regex("(!?)\\[\\[([^\\[\\]|#\\n]+)(?:#([^\\[\\]|\\n]*))?(?:\\|([^\\[\\]\\n]*))?]]")
    val TAG = Regex("(?<![\\w/&#])#([\\p{L}\\p{N}_\\-/]*[\\p{L}_\\-/][\\p{L}\\p{N}_\\-/]*)")
    private val FENCE = Regex("```[\\s\\S]*?(```|\\z)")
    private val INLINE_CODE = Regex("`[^`\\n]*`")

    /** Ranges that must not be scanned for links/tags/mentions (code, existing links, URLs). */
    fun protectedRanges(text: String): List<IntRange> {
        val r = mutableListOf<IntRange>()
        FENCE.findAll(text).forEach { r.add(it.range) }
        INLINE_CODE.findAll(text).forEach { r.add(it.range) }
        LINK.findAll(text).forEach { r.add(it.range) }
        Regex("\\[[^\\]\\n]*]\\([^)\\n]*\\)").findAll(text).forEach { r.add(it.range) }
        Regex("https?://\\S+").findAll(text).forEach { r.add(it.range) }
        Regex("^#{1,6} .*$", RegexOption.MULTILINE).findAll(text).forEach { r.add(it.range) }
        return r
    }

    private fun inside(ranges: List<IntRange>, i: Int) = ranges.any { i in it }

    fun links(text: String): List<WikiLink> {
        val code = FENCE.findAll(text).map { it.range }.toList() + INLINE_CODE.findAll(text).map { it.range }
        return LINK.findAll(text).filter { m -> !inside(code, m.range.first) }.map { m ->
            WikiLink(
                target = m.groupValues[2].trim(),
                heading = m.groupValues[3].takeIf { it.isNotBlank() }?.trim(),
                alias = m.groupValues[4].takeIf { it.isNotBlank() }?.trim(),
                embed = m.groupValues[1] == "!",
                range = m.range
            )
        }.toList()
    }

    fun inlineTags(text: String): List<String> {
        val prot = FENCE.findAll(text).map { it.range }.toList() + INLINE_CODE.findAll(text).map { it.range } +
            Regex("https?://\\S+").findAll(text).map { it.range } +
            Regex("^#{1,6} ", RegexOption.MULTILINE).findAll(text).map { it.range }
        return TAG.findAll(text).filter { !inside(prot, it.range.first) }.map { it.groupValues[1].trimEnd('/') }.distinct().toList()
    }
}

/**
 * In-memory link graph of the whole wiki. Cheap to rebuild (pure string work),
 * so it is recomputed whenever the note list changes.
 */
class WikiIndex(val notes: List<WikiNote>) {
    private val byKey: Map<String, WikiNote> = buildMap {
        // aliases first so real titles win on collisions
        for (n in notes) for (a in n.aliases) putIfAbsent(key(a), n)
        for (n in notes) put(key(n.title), n)
        for (n in notes) put(key(n.path), n)
    }
    val byId: Map<String, WikiNote> = notes.associateBy { it.id }

    val outgoing: Map<String, List<WikiLink>> = notes.associate { it.id to WikiSyntax.links(it.body) }

    /** id → ids of notes linking to it */
    val backlinks: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for ((from, links) in outgoing) for (l in links) {
            val to = resolve(l.target) ?: continue
            if (to.id != from) getOrPut(to.id) { mutableListOf() }.let { if (from !in it) it.add(from) }
        }
    }

    val tags: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for (n in notes) for (t in allTags(n)) getOrPut(t) { mutableListOf() }.add(n.id)
    }

    fun allTags(n: WikiNote): List<String> = (n.tags + WikiSyntax.inlineTags(n.body)).distinct()

    fun resolve(target: String): WikiNote? = byKey[key(target)] ?: byKey[key(target.substringAfterLast('/'))]

    fun unresolved(id: String): List<String> =
        outgoing[id].orEmpty().filter { !it.embed && resolve(it.target) == null }.map { it.target }.distinct()

    fun folders(extra: Collection<String> = emptyList()): Set<String> {
        val out = sortedSetOf<String>()
        for (f in notes.map { it.folder } + extra) {
            val parts = WikiNote.normFolder(f).split('/').filter { it.isNotEmpty() }
            for (i in 1..parts.size) out.add(parts.take(i).joinToString("/"))
        }
        return out
    }

    /** Notes whose title/alias appears as plain text in [id]'s body but which are not linked yet. */
    fun unlinkedMentions(id: String): List<WikiNote> {
        val n = byId[id] ?: return emptyList()
        val linked = outgoing[id].orEmpty().mapNotNull { resolve(it.target)?.id }.toSet()
        val prot = WikiSyntax.protectedRanges(n.body)
        return notes.filter { o ->
            o.id != id && o.id !in linked && (listOf(o.title) + o.aliases).any { name ->
                findMention(n.body, name, prot) != null
            }
        }
    }

    /** Notes that mention [id]'s title somewhere without linking to it. */
    fun mentionedBy(id: String): List<WikiNote> {
        val target = byId[id] ?: return emptyList()
        val back = backlinks[id].orEmpty().toSet()
        return notes.filter { o ->
            o.id != id && o.id !in back && (listOf(target.title) + target.aliases).any { name ->
                findMention(o.body, name, WikiSyntax.protectedRanges(o.body)) != null
            }
        }
    }

    /**
     * Local, deterministic linker: wraps the first plain-text mention of every other note's
     * title/alias in `[[...]]`. Longest titles first, so "Laser Setup B" wins over "Laser".
     */
    fun autoLink(body: String, selfId: String, minLength: Int = 3): Pair<String, Int> {
        var text = body
        var count = 0
        val already = WikiSyntax.links(text).mapNotNull { resolve(it.target)?.id }.toMutableSet()
        val candidates = notes.filter { it.id != selfId }
            .flatMap { n -> (listOf(n.title) + n.aliases).map { it to n } }
            .filter { it.first.length >= minLength }
            .sortedByDescending { it.first.length }
        for ((name, note) in candidates) {
            if (note.id in already) continue
            val prot = WikiSyntax.protectedRanges(text)
            val r = findMention(text, name, prot) ?: continue
            val found = text.substring(r)
            val link = if (found == note.title) "[[${note.title}]]" else "[[${note.title}|$found]]"
            text = text.replaceRange(r, link)
            already.add(note.id)
            count++
        }
        return text to count
    }

    /** Compact table of contents sent to an LLM instead of the full wiki. */
    fun digest(maxNotes: Int = 300, withSummary: Boolean = true, excludeId: String? = null): String = buildString {
        val sorted = notes.filter { it.id != excludeId }.sortedByDescending { it.updated }.take(maxNotes)
        for (n in sorted) {
            append("- [[").append(n.title).append("]]")
            if (n.folder.isNotBlank()) append(" (").append(n.folder).append(")")
            val t = allTags(n)
            if (t.isNotEmpty()) append(" ").append(t.joinToString(" ") { "#$it" })
            if (n.aliases.isNotEmpty()) append(" aka ").append(n.aliases.joinToString(", "))
            if (withSummary) {
                val s = n.summary(140)
                if (s.isNotBlank()) append(" — ").append(s)
            }
            append('\n')
        }
    }

    companion object {
        fun key(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").removeSuffix(".md")

        fun findMention(text: String, name: String, protectedRanges: List<IntRange>): IntRange? {
            if (name.isBlank()) return null
            val re = Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(name) + "(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            return re.findAll(text).map { it.range }.firstOrNull { r -> protectedRanges.none { p -> r.first in p || r.last in p } }
        }
    }
}
