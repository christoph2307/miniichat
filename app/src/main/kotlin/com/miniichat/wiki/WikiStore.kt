package com.miniichat.wiki

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * File-backed wiki:
 *   <root>/notes/<id>.md     one Markdown file per note (frontmatter holds title/folder/tags)
 *   <root>/assets/<name>     embedded images and other files (`![[name]]`)
 *   <root>/folders.txt       explicitly created (possibly empty) virtual folders
 * The directory is a valid Obsidian vault "flat" layout; export can re-create folders.
 */
class WikiStore(val root: File) {
    val notesDir: File get() = File(root, "notes").apply { mkdirs() }
    val assetsDir: File get() = File(root, "assets").apply { mkdirs() }
    private val foldersFile: File get() = File(root, "folders.txt")
    private val mutex = Mutex()

    private val _notes = MutableStateFlow<List<WikiNote>>(emptyList())
    val notes: StateFlow<List<WikiNote>> = _notes.asStateFlow()

    private val _folders = MutableStateFlow<Set<String>>(emptySet())
    /** Explicit folders (folders implied by notes come from [WikiIndex.folders]). */
    val folders: StateFlow<Set<String>> = _folders.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.mkdirs()
            val list = notesDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().mapNotNull { f ->
                runCatching {
                    Frontmatter.toNote(f.readText(), f.nameWithoutExtension, f.nameWithoutExtension)
                        .let { if (it.id != f.nameWithoutExtension) it.copy(id = f.nameWithoutExtension) else it }
                }.getOrNull()
            }
            _notes.value = list.sortedByDescending { it.updated }
            _folders.value = if (foldersFile.exists())
                foldersFile.readLines().map { WikiNote.normFolder(it) }.filter { it.isNotEmpty() }.toSet() else emptySet()
        }
    }

    fun get(id: String): WikiNote? = _notes.value.firstOrNull { it.id == id }
    fun findBySource(source: String): WikiNote? = _notes.value.firstOrNull { it.source == source && source.isNotBlank() }

    /** Pick a title not used by another note ("Title", "Title 2", …). */
    fun uniqueTitle(title: String, exceptId: String? = null): String {
        val base = WikiNote.sanitizeTitle(title)
        val taken = _notes.value.filter { it.id != exceptId }.map { WikiIndex.key(it.title) }.toSet()
        if (WikiIndex.key(base) !in taken) return base
        var i = 2
        while (WikiIndex.key("$base $i") in taken) i++
        return "$base $i"
    }

    suspend fun save(note: WikiNote, touch: Boolean = true): WikiNote = withContext(Dispatchers.IO) {
        mutex.withLock {
            val n = note.copy(
                title = WikiNote.sanitizeTitle(note.title),
                folder = WikiNote.normFolder(note.folder),
                tags = note.tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct(),
                updated = if (touch) System.currentTimeMillis() else note.updated
            )
            writeAtomic(File(notesDir, "${n.id}.md"), n.toMarkdownFile())
            _notes.value = (listOf(n) + _notes.value.filter { it.id != n.id }).sortedByDescending { it.updated }
            n
        }
    }

    suspend fun create(title: String, folder: String = "", body: String = "", tags: List<String> = emptyList(), source: String = ""): WikiNote =
        save(WikiNote(id = newId(), title = uniqueTitle(title), folder = folder, body = body, tags = tags, source = source))

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            File(notesDir, "$id.md").delete()
            _notes.value = _notes.value.filter { it.id != id }
        }
    }

    /**
     * Rename a note and rewrite `[[Old Title…]]` links in every other note (like Obsidian does).
     * Returns the number of notes whose links were updated.
     */
    suspend fun rename(id: String, newTitle: String): Int {
        val old = get(id) ?: return 0
        val title = uniqueTitle(newTitle, exceptId = id)
        if (title == old.title) return 0
        save(old.copy(title = title))
        var changed = 0
        for (n in _notes.value) {
            if (n.id == id) continue
            val body = rewriteLinks(n.body, old.title, title)
            if (body != n.body) { save(n.copy(body = body), touch = false); changed++ }
        }
        return changed
    }

    suspend fun move(id: String, folder: String) {
        val n = get(id) ?: return
        save(n.copy(folder = folder), touch = false)
    }

    suspend fun createFolder(path: String) = withContext(Dispatchers.IO) {
        val p = WikiNote.normFolder(path)
        if (p.isEmpty()) return@withContext
        mutex.withLock {
            _folders.value = _folders.value + p
            writeAtomic(foldersFile, _folders.value.sorted().joinToString("\n"))
        }
    }

    /** Rename/move a virtual folder (and all sub-folders). */
    suspend fun renameFolder(from: String, to: String) {
        val a = WikiNote.normFolder(from)
        val b = WikiNote.normFolder(to)
        if (a.isEmpty() || a == b) return
        fun remap(f: String) = when {
            f == a -> b
            f.startsWith("$a/") -> WikiNote.normFolder(b + f.removePrefix(a))
            else -> f
        }
        for (n in _notes.value) if (n.folder == a || n.folder.startsWith("$a/")) save(n.copy(folder = remap(n.folder)), touch = false)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _folders.value = _folders.value.map { remap(it) }.filter { it.isNotEmpty() }.toSet()
                writeAtomic(foldersFile, _folders.value.sorted().joinToString("\n"))
            }
        }
    }

    /** Remove a folder; its notes move to the parent folder. */
    suspend fun deleteFolder(path: String) {
        val p = WikiNote.normFolder(path)
        val parent = p.substringBeforeLast('/', "")
        renameFolder(p, parent)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _folders.value = _folders.value.filter { it != p && !it.startsWith("$p/") }.toSet()
                writeAtomic(foldersFile, _folders.value.sorted().joinToString("\n"))
            }
        }
    }

    /** Link-safe asset file name. */
    fun cleanAssetName(preferredName: String): String =
        preferredName.substringAfterLast('/').replace(Regex("[\\[\\]|#^\\\\:*?\"<>\\s]+"), "_").ifBlank { "file" }

    /** Copy a file into assets with a unique, link-safe name and return that name. */
    suspend fun addAsset(input: InputStream, preferredName: String): String = withContext(Dispatchers.IO) {
        val clean = cleanAssetName(preferredName)
        val stem = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "").let { if (it.isNotEmpty() && it != clean) ".$it" else "" }
        var name = clean
        var i = 1
        while (File(assetsDir, name).exists()) name = "$stem-${i++}$ext"
        File(assetsDir, name).outputStream().use { input.copyTo(it) }
        name
    }

    fun assetFile(name: String): File = File(assetsDir, name.substringAfterLast('/'))

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        fun rewriteLinks(body: String, oldTitle: String, newTitle: String): String =
            WikiSyntax.LINK.replace(body) { m ->
                if (WikiIndex.key(m.groupValues[2]) == WikiIndex.key(oldTitle)) {
                    val embed = m.groupValues[1]
                    val head = m.groupValues[3].let { if (it.isNotEmpty()) "#$it" else "" }
                    val alias = m.groupValues[4].let { if (it.isNotEmpty()) "|$it" else "" }
                    "$embed[[$newTitle$head$alias]]"
                } else m.value
            }
    }
}
