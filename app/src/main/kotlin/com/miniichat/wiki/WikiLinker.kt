package com.miniichat.wiki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Linking strategy (see docs/WIKI.md):
 *  1. **Local index** (always on, offline, deterministic): `[[links]]`, backlinks, tags and
 *     unlinked mentions are computed from the notes themselves by [WikiIndex].
 *  2. **LLM librarian** (on demand, button "AI links"): we send the compact index digest
 *     plus one note; the model returns JSON suggestions which the user reviews and applies.
 *  The LLM only *proposes* — it never rewrites the note text, it only names exact anchor
 *  phrases, so it cannot corrupt measurements or wording.
 */
object WikiLinker {

    data class LinkSuggestion(
        val target: String,
        val anchor: String?,
        val reason: String = "",
        val isNew: Boolean = false
    )

    data class Result(
        val links: List<LinkSuggestion> = emptyList(),
        val tags: List<String> = emptyList(),
        val folder: String? = null,
        val aliases: List<String> = emptyList(),
        val summary: String? = null
    )

    const val SYSTEM_PROMPT = """You are the librarian of a scientist's personal research wiki (Obsidian-style Markdown).
You receive an INDEX of the existing notes (title, folder, tags, one-line summary) and one NOTE.
Your job: decide how the NOTE should be connected to the rest of the wiki.

Rules
- Link only to titles that appear in the INDEX, spelled exactly as in the INDEX.
- Exception: you may propose at most 3 NEW notes for central concepts (a sample, device, method, project,
  person) that clearly deserve their own page; mark them with "new": true.
- For each link give "anchor": a phrase copied VERBATIM from the NOTE body (case-sensitive, 1–6 words)
  where the link belongs. Use null if no phrase fits; such links go into a "Related" section.
- Never link the note to itself. Prefer specific notes (same sample, same setup, previous run of the
  same experiment, the project it belongs to) over generic ones. At most 15 links.
- "tags": up to 6 lowercase kebab-case tags without '#', reuse existing tags from the INDEX when possible.
- "folder": the best virtual folder path for the NOTE ("Projects/Name/Experiments"); reuse existing folders.
- "aliases": other names/abbreviations the NOTE is likely referred to by (max 3).
- "summary": one sentence describing the NOTE, in the NOTE's language.
- "reason": max 12 words why the link is relevant.

Reply with JSON only — no Markdown fences, no prose — exactly this shape:
{"links":[{"target":"Exact Title","anchor":"phrase from note or null","reason":"...","new":false}],
 "tags":["..."],"folder":"...","aliases":["..."],"summary":"..."}"""

    fun userPrompt(note: WikiNote, index: WikiIndex, extraFolders: Collection<String>, maxBodyChars: Int = 12_000): String = buildString {
        append("## FOLDERS\n")
        index.folders(extraFolders).forEach { append("- ").append(it).append('\n') }
        append("\n## INDEX\n")
        append(index.digest(maxNotes = 400, excludeId = note.id))
        append("\n## NOTE\n")
        append("Title: ").append(note.title).append('\n')
        append("Folder: ").append(note.folder.ifBlank { "(none)" }).append('\n')
        append("Tags: ").append(note.tags.joinToString(", ").ifBlank { "(none)" }).append("\n\n")
        append(note.body.take(maxBodyChars))
    }

    /**
     * Prompt fragment appended to the chat system prompt so notes written by the LLM already
     * come with frontmatter and links to existing pages.
     */
    fun chatContext(index: WikiIndex, extraFolders: Collection<String>, maxNotes: Int = 150): String = buildString {
        append("\n\n---\nThe user keeps an Obsidian-style wiki. When you produce documentation, return ONE Markdown document that ")
        append("starts with YAML frontmatter:\n---\ntitle: <concise title>\nfolder: <virtual folder, reuse one below>\ntags: [tag-a, tag-b]\n---\n")
        append("Reference existing notes with [[Exact Title]] (or [[Exact Title|text]]) where relevant; do not invent links to notes that are not listed.\n")
        val folders = index.folders(extraFolders)
        if (folders.isNotEmpty()) append("Folders: ").append(folders.joinToString(", ")).append('\n')
        if (index.notes.isNotEmpty()) {
            append("Existing notes:\n")
            append(index.digest(maxNotes = maxNotes, withSummary = false))
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse the model's reply; tolerant of ```json fences and leading/trailing prose. */
    fun parse(reply: String): Result {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalArgumentException("The model did not return JSON")
        val obj = json.parseToJsonElement(reply.substring(start, end + 1)) as? JsonObject
            ?: throw IllegalArgumentException("Unexpected JSON")
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        fun strList(k: String) = (obj[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }.orEmpty()
        val links = (obj["links"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val target = str(o, "target")?.let { WikiNote.sanitizeTitle(it) } ?: return@mapNotNull null
            LinkSuggestion(
                target = target,
                anchor = str(o, "anchor"),
                reason = str(o, "reason").orEmpty(),
                isNew = (o["new"] as? JsonPrimitive)?.contentOrNull == "true"
            )
        }.distinctBy { WikiIndex.key(it.target) }
        return Result(
            links = links,
            tags = strList("tags").map { it.removePrefix("#").lowercase().replace(' ', '-') }.distinct().take(8),
            folder = str(obj, "folder")?.let { WikiNote.normFolder(it) },
            aliases = strList("aliases").take(5),
            summary = str(obj, "summary")
        )
    }

    /** Drop suggestions that point at the note itself or at unknown titles not flagged as new. */
    fun validate(result: Result, note: WikiNote, index: WikiIndex): Result = result.copy(
        links = result.links.mapNotNull { s ->
            val resolved = index.resolve(s.target)
            when {
                resolved?.id == note.id -> null
                resolved != null -> s.copy(target = resolved.title, isNew = false)
                s.isNew -> s
                else -> null
            }
        }
    )

    /**
     * Apply accepted suggestions: inline links at their anchors, the rest in a "## Related" section.
     * Returns the new note plus titles of NEW notes that should be created as stubs.
     */
    fun apply(
        note: WikiNote,
        result: Result,
        acceptedLinks: Set<String>,
        applyTags: Boolean,
        applyFolder: Boolean,
        applyAliases: Boolean
    ): Pair<WikiNote, List<String>> {
        var body = note.body
        val related = mutableListOf<String>()
        val newNotes = mutableListOf<String>()
        for (s in result.links) {
            if (s.target !in acceptedLinks) continue
            if (s.isNew) newNotes.add(s.target)
            val anchor = s.anchor
            val r = anchor?.let { a ->
                val prot = WikiSyntax.protectedRanges(body)
                // exact match first, then case-insensitive
                Regex(Regex.escape(a)).findAll(body).map { it.range }.firstOrNull { rr -> prot.none { rr.first in it } }
                    ?: WikiIndex.findMention(body, a, prot)
            }
            if (r != null) {
                val found = body.substring(r)
                val link = if (WikiIndex.key(found) == WikiIndex.key(s.target)) "[[${s.target}]]" else "[[${s.target}|$found]]"
                body = body.replaceRange(r, link)
            } else related.add(s.target)
        }
        if (related.isNotEmpty()) body = addRelated(body, related)
        val updated = note.copy(
            body = body,
            tags = if (applyTags) (note.tags + result.tags).distinct() else note.tags,
            folder = if (applyFolder && !result.folder.isNullOrBlank()) result.folder else note.folder,
            aliases = if (applyAliases) (note.aliases + result.aliases).distinct() else note.aliases
        )
        return updated to newNotes
    }

    /** Add `- [[X]]` lines to an existing "## Related" section, or append one. */
    fun addRelated(body: String, titles: List<String>): String {
        val existing = WikiSyntax.links(body).map { WikiIndex.key(it.target) }.toSet()
        val add = titles.filter { WikiIndex.key(it) !in existing }.distinct()
        if (add.isEmpty()) return body
        val lines = add.joinToString("\n") { "- [[$it]]" }
        val m = Regex("^## (Related|Verwandt|Siehe auch|See also)\\s*$", RegexOption.MULTILINE).find(body)
        return if (m != null) {
            // insert after the last list line of that section
            val after = body.substring(m.range.last + 1)
            val nextHeading = Regex("^#{1,6} ", RegexOption.MULTILINE).find(after)?.range?.first ?: after.length
            val section = after.substring(0, nextHeading).trimEnd()
            body.substring(0, m.range.last + 1) + section + "\n" + lines + "\n" + after.substring(nextHeading).let { if (it.isNotEmpty()) "\n$it" else "" }
        } else body.trimEnd() + "\n\n## Related\n" + lines + "\n"
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
