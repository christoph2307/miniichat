package com.miniichat.wiki

/**
 * Helpers for pages that grow over several chats/recordings:
 *  - sections of embeds (## Images, ## Recordings) that only ever get added to
 *  - a "Sources" callout listing every chat that contributed
 *  - merging an LLM-rewritten page with the previous version without losing embeds or sources
 */
object WikiMerge {
    private val AUDIO = Regex("(?i)\\.(wav|m4a|mp3|ogg|opus|flac|aac)$")
    private val IMAGE = Regex("(?i)\\.(png|jpe?g|webp|gif|bmp|heic)$")

    fun isAudio(name: String) = AUDIO.containsMatchIn(name)
    fun isImage(name: String) = IMAGE.containsMatchIn(name)

    /** Append [lines] under `## heading` (creating it at the end if missing), skipping duplicates. */
    fun addToSection(body: String, heading: String, lines: List<String>, alsoMatch: List<String> = emptyList()): String {
        val add = lines.filter { l -> l.isNotBlank() && !body.contains(l.trim()) }.distinct()
        if (add.isEmpty()) return body
        val names = (listOf(heading) + alsoMatch).joinToString("|") { Regex.escape(it) }
        val m = Regex("^## ($names)\\s*$", RegexOption.MULTILINE).find(body)
        val block = add.joinToString("\n")
        return if (m != null) {
            val after = body.substring(m.range.last + 1)
            val next = Regex("^#{1,2} |^> \\[!", RegexOption.MULTILINE).find(after)?.range?.first ?: after.length
            val section = after.substring(0, next).trimEnd()
            body.substring(0, m.range.last + 1) + section + "\n" + block + "\n" +
                after.substring(next).let { if (it.isNotEmpty()) "\n$it" else "" }
        } else {
            // keep the Sources callout last
            val src = sourcesRange(body)
            if (src != null) {
                body.substring(0, src.first).trimEnd() + "\n\n## $heading\n$block\n\n" + body.substring(src.first)
            } else body.trimEnd() + "\n\n## $heading\n$block\n"
        }
    }

    private val SOURCES_HEAD = Regex("^> \\[!info]-? ?Sources?\\s*$", RegexOption.MULTILINE)

    /** Character range of the Sources callout (header + following `>` lines). */
    fun sourcesRange(body: String): IntRange? {
        val m = SOURCES_HEAD.find(body) ?: return null
        var end = m.range.last + 1
        while (end < body.length) {
            val lineEnd = body.indexOf('\n', end + 1).let { if (it < 0) body.length else it }
            val line = body.substring(end, lineEnd).trimStart('\n')
            if (!line.startsWith(">")) break
            end = lineEnd
        }
        return m.range.first until end
    }

    fun sourceLines(body: String): List<String> {
        val r = sourcesRange(body) ?: return emptyList()
        return body.substring(r).lines().drop(1).map { it.removePrefix(">").trim() }
            .map { it.removePrefix("- ") }.filter { it.isNotBlank() }
    }

    /** Add a source entry (e.g. "Chat: [Title](miniichat://chat/id) · date") to the Sources callout. */
    fun addSource(body: String, entry: String): String {
        val existing = sourceLines(body)
        val key = Regex("\\((miniichat://[^)]+)\\)").find(entry)?.groupValues?.get(1)
        if (existing.any { it == entry || (key != null && it.contains(key)) }) return body
        val all = existing + entry
        val callout = "> [!info]- Sources\n" + all.joinToString("\n") { "> - $it" } + "\n"
        val r = sourcesRange(body)
        return if (r != null) {
            body.substring(0, r.first) + callout.trimEnd('\n') + body.substring(r.last + 1).let { if (it.isBlank()) "\n" else it }
        } else body.trimEnd() + "\n\n" + callout
    }

    /**
     * Merge an LLM-rewritten page with the previous version: every embed (images, recordings)
     * and every source that existed before is kept, even if the model dropped it.
     */
    fun mergeUpdate(oldBody: String, newBody: String): String {
        var out = newBody.trimEnd() + "\n"
        val oldEmbeds = WikiSyntax.links(oldBody).filter { it.embed }.map { it.target }.distinct()
        val newEmbeds = WikiSyntax.links(out).filter { it.embed }.map { it.target }.toSet()
        val missing = oldEmbeds.filter { it !in newEmbeds }
        val missingImg = missing.filter { !isAudio(it) }.map { "![[$it]]" }
        val missingAudio = missing.filter { isAudio(it) }.map { "![[$it]]" }
        // the model may have copied the old Sources callout verbatim → rebuild it from both versions
        val sources = (sourceLines(oldBody) + sourceLines(out)).distinct()
        sourcesRange(out)?.let { r -> out = out.removeRange(r).trimEnd() + "\n" }
        if (missingImg.isNotEmpty()) out = addToSection(out, "Images", missingImg, listOf("Bilder", "Fotos", "Photos"))
        if (missingAudio.isNotEmpty()) out = addToSection(out, "Recordings", missingAudio, listOf("Aufnahmen", "Audio"))
        for (s in sources) out = addSource(out, s)
        return out
    }

    /** System-prompt addendum for a chat that updates an existing page. */
    fun updatePrompt(note: WikiNote): String = buildString {
        append("\n\n---\nYou are UPDATING an existing wiki page (shown below between <page> tags). ")
        append("Merge the new information from the user's latest message (dictated recordings, photos, notes) into it. ")
        append("Return the COMPLETE updated page as one Markdown document with the same YAML frontmatter title. ")
        append("Keep all existing facts, numbers, [[links]] and embeds (![[...]]) unless the user explicitly corrects them; ")
        append("put new observations into the fitting sections (or a new dated section) and mark contradictions with [?]. ")
        append("Do not add the Sources callout — the app maintains it.\n\n<page>\n")
        append(Frontmatter.write(note.copy(source = "")).trim())
        append("\n\n")
        append(note.body.trim())
        append("\n</page>\n")
    }
}
