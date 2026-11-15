package com.miniichat.export

import com.miniichat.wiki.Frontmatter
import com.miniichat.wiki.WikiIndex
import com.miniichat.wiki.WikiNote
import com.miniichat.wiki.WikiSyntax
import java.io.File

/*
 * ─────────────────────────────── Export API ───────────────────────────────
 *
 * Every export destination (Obsidian vault, Notion, eLabFTW, Nextcloud/WebDAV,
 * Git, Confluence, …) is an [ExportTarget]. The UI only knows this interface:
 *
 *   1. The user ticks notes in the export list.
 *   2. [ExportPreparer.prepare] turns them into an [ExportBundle] — links rewritten to the
 *      target's [LinkStyle], unique relative paths, referenced assets collected.
 *   3. The target's [ExportTarget.export] receives the bundle and its saved config
 *      (values of its [ConfigField]s, edited in Settings → Export targets).
 *
 * Adding a tool = implement [ExportTarget] and register it in [ExportRegistry.targets].
 * See docs/EXPORT_API.md for the contract and a template.
 */

/** How `[[wikilinks]]` are written in the exported Markdown. */
enum class LinkStyle(val label: String) {
    /** Keep `[[Title]]` / `![[image.png]]` — Obsidian, Logseq, Foam, SiYuan. */
    WIKILINK("[[Wikilinks]] (Obsidian)"),
    /** `[Title](Folder/Title.md)` relative paths — GitHub, GitLab, MkDocs, most static sites. */
    MARKDOWN_RELATIVE("[Text](relative/path.md)"),
    /** Plain text, links removed — for tools without cross-references. */
    PLAIN("Plain text")
}

data class ExportOptions(
    val linkStyle: LinkStyle = LinkStyle.WIKILINK,
    val includeFrontmatter: Boolean = true,
    val includeAssets: Boolean = true,
    /** Re-create virtual folders as directories (otherwise all files flat). */
    val folderLayout: Boolean = true,
    /** Append a "Backlinks" section listing notes that link here. */
    val includeBacklinks: Boolean = false
)

data class ExportAsset(val name: String, val relativePath: String, val file: File, val mimeType: String)

data class ExportDocument(
    val id: String,
    val title: String,
    val folder: String,
    val tags: List<String>,
    val aliases: List<String>,
    val created: Long,
    val updated: Long,
    /** e.g. "Projects/Laser/Run 3.md" */
    val relativePath: String,
    /** Final Markdown (frontmatter + body), links already converted. */
    val markdown: String,
    /** Body only, links converted, no frontmatter. */
    val body: String,
    /** Titles of notes this document links to (resolved, inside or outside the selection). */
    val linksTo: List<String>,
    val linkedFrom: List<String>,
    val assets: List<ExportAsset>
)

data class ExportBundle(
    val documents: List<ExportDocument>,
    val assets: List<ExportAsset>,
    val options: ExportOptions,
    val createdAt: Long = System.currentTimeMillis()
)

enum class FieldType { TEXT, MULTILINE, URL, SECRET, BOOLEAN, CHOICE, NUMBER }

/** Declarative config field — the settings UI is generated from these. */
data class ConfigField(
    val key: String,
    val label: String,
    val type: FieldType = FieldType.TEXT,
    val hint: String = "",
    val required: Boolean = false,
    val default: String = "",
    val choices: List<Pair<String, String>> = emptyList()   // value to label (CHOICE)
)

data class ExportCapabilities(
    val assets: Boolean = true,
    val folders: Boolean = true,
    val preferredLinkStyle: LinkStyle = LinkStyle.WIKILINK,
    /** Upper bound per call; the runner batches if needed (0 = unlimited). */
    val maxBatch: Int = 0
)

data class ItemResult(val id: String, val ok: Boolean, val message: String = "", val remoteUrl: String? = null)

data class ExportResult(
    val ok: Boolean,
    val message: String,
    val items: List<ItemResult> = emptyList(),
    val location: String? = null
)

fun interface ExportProgress { fun update(done: Int, total: Int, current: String) }

interface ExportTarget {
    /** Stable id used as storage key for config ("webhook", "notion", …). */
    val id: String
    val name: String
    val description: String
    val fields: List<ConfigField>
    val capabilities: ExportCapabilities get() = ExportCapabilities()
    /** false → shown as "planned" in the UI (no export possible yet). */
    val available: Boolean get() = true

    /** Optional connectivity/auth check used by the "Test" button. */
    suspend fun test(config: Map<String, String>): ExportResult = ExportResult(true, "No test available")

    suspend fun export(bundle: ExportBundle, config: Map<String, String>, progress: ExportProgress): ExportResult

    fun missingRequired(config: Map<String, String>): List<ConfigField> =
        fields.filter { it.required && config[it.key].isNullOrBlank() && it.default.isBlank() }
}

/** Turns wiki notes into an export bundle. Pure Kotlin, no Android dependencies. */
object ExportPreparer {

    fun fileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { "Untitled" } + ".md"

    fun prepare(
        selected: List<WikiNote>,
        index: WikiIndex,
        options: ExportOptions,
        assetResolver: (String) -> File?
    ): ExportBundle {
        // unique relative paths
        val used = HashSet<String>()
        val paths = LinkedHashMap<String, String>()
        for (n in selected) {
            val dir = if (options.folderLayout && n.folder.isNotBlank()) n.folder + "/" else ""
            var p = dir + fileName(n.title)
            var i = 2
            while (!used.add(p.lowercase())) p = dir + fileName("${n.title} $i").also { i++ }
            paths[n.id] = p
        }
        val allAssets = LinkedHashMap<String, ExportAsset>()

        val docs = selected.map { n ->
            val myPath = paths.getValue(n.id)
            val myDir = myPath.substringBeforeLast('/', "")
            val docAssets = mutableListOf<ExportAsset>()
            val body = convertLinks(n.body, index, options.linkStyle) { target, isEmbed ->
                if (isEmbed) {
                    val f = assetResolver(target)
                    if (f != null && f.exists() && options.includeAssets) {
                        val rel = "assets/" + f.name
                        val a = allAssets.getOrPut(f.name) { ExportAsset(f.name, rel, f, mimeOf(f.name)) }
                        docAssets.add(a)
                        relative(myDir, rel)
                    } else null
                } else {
                    val t = index.resolve(target) ?: return@convertLinks null
                    relative(myDir, paths[t.id] ?: ((if (options.folderLayout && t.folder.isNotBlank()) t.folder + "/" else "") + fileName(t.title)))
                }
            }.let { b ->
                if (!options.includeBacklinks) b else {
                    val back = index.backlinks[n.id].orEmpty().mapNotNull { index.byId[it]?.title }
                    if (back.isEmpty()) b else b.trimEnd() + "\n\n## Backlinks\n" + back.joinToString("\n") { "- " + linkText(it, myDir, paths, index, options) } + "\n"
                }
            }
            val md = (if (options.includeFrontmatter) Frontmatter.write(n) else "") + body.trimEnd() + "\n"
            ExportDocument(
                id = n.id, title = n.title, folder = n.folder, tags = index.allTags(n), aliases = n.aliases,
                created = n.created, updated = n.updated, relativePath = myPath,
                markdown = md, body = body,
                linksTo = index.outgoing[n.id].orEmpty().mapNotNull { index.resolve(it.target)?.title }.distinct(),
                linkedFrom = index.backlinks[n.id].orEmpty().mapNotNull { index.byId[it]?.title },
                assets = docAssets.distinct()
            )
        }
        return ExportBundle(docs, allAssets.values.toList(), options)
    }

    private fun linkText(title: String, myDir: String, paths: Map<String, String>, index: WikiIndex, o: ExportOptions): String {
        val n = index.resolve(title)
        return when (o.linkStyle) {
            LinkStyle.WIKILINK -> "[[$title]]"
            LinkStyle.PLAIN -> title
            LinkStyle.MARKDOWN_RELATIVE -> "[$title](" + encodePath(relative(myDir, paths[n?.id] ?: fileName(title))) + ")"
        }
    }

    /**
     * Rewrite `[[T#h|a]]` / `![[img]]` according to [style]. [resolvePath] returns the relative
     * path of the target (or null if it can't be resolved → plain text / kept as wikilink).
     */
    fun convertLinks(body: String, index: WikiIndex, style: LinkStyle, resolvePath: (String, Boolean) -> String?): String {
        if (style == LinkStyle.WIKILINK) {
            // still collect assets via resolvePath for embeds
            WikiSyntax.links(body).filter { it.embed }.forEach { resolvePath(it.target, true) }
            return body
        }
        val links = WikiSyntax.links(body).sortedByDescending { it.range.first }
        var out = body
        for (l in links) {
            val text = l.alias ?: (if (l.heading != null) "${l.target} › ${l.heading}" else l.target)
            val replacement = when (style) {
                LinkStyle.PLAIN -> if (l.embed) "" else text
                else -> {
                    val path = resolvePath(l.target, l.embed)
                    when {
                        path == null -> if (l.embed) "" else text
                        l.embed -> "![${l.alias ?: l.target}](${encodePath(path)})"
                        else -> "[$text](${encodePath(path)}${l.heading?.let { "#" + slug(it) } ?: ""})"
                    }
                }
            }
            out = out.replaceRange(l.range, replacement)
        }
        return out
    }

    fun relative(fromDir: String, to: String): String {
        if (fromDir.isEmpty()) return to
        val a = fromDir.split('/').filter { it.isNotEmpty() }
        val b = to.split('/').filter { it.isNotEmpty() }
        var i = 0
        while (i < a.size && i < b.size - 1 && a[i] == b[i]) i++
        return (List(a.size - i) { ".." } + b.drop(i)).joinToString("/")
    }

    fun encodePath(p: String) = p.split('/').joinToString("/") { seg ->
        seg.replace("%", "%25").replace(" ", "%20").replace("(", "%28").replace(")", "%29")
    }

    fun slug(h: String) = h.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}\\s-]"), "").replace(Regex("\\s+"), "-")

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "svg" -> "image/svg+xml"; "pdf" -> "application/pdf"; "wav" -> "audio/wav"; "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"; "csv" -> "text/csv"; "md" -> "text/markdown"; "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
