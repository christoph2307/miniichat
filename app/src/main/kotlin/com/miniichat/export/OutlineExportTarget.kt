package com.miniichat.export

import com.miniichat.wiki.WikiSyntax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Outline (getoutline.com or self-hosted) via its RPC API.
 *  • Auth: API key (Settings → API & Apps in Outline), sent as Bearer token.
 *  • Notes → documents in one collection; virtual folders → nested parent documents.
 *  • `[[wikilinks]]` become links to the corresponding Outline documents; images are uploaded
 *    as attachments. Remote ids are remembered, so a re-export updates instead of duplicating.
 */
class OutlineExportTarget(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .build()
) : ExportTarget {
    override val id = "outline"
    override val name = "Outline"
    override val description = "Creates/updates documents in an Outline collection, with folders as nested documents and working links."
    override val capabilities = ExportCapabilities(assets = true, folders = true, preferredLinkStyle = LinkStyle.MARKDOWN_RELATIVE)
    override val fields = listOf(
        ConfigField("base_url", "Outline URL", FieldType.URL, "https://app.getoutline.com", default = "https://app.getoutline.com", required = true),
        ConfigField("api_key", "API key", FieldType.SECRET, "Outline → Settings → API & Apps → New API key", required = true),
        ConfigField("collection", "Collection", FieldType.TEXT, "Name or id — created if it doesn't exist", default = "Lab Wiki"),
        ConfigField("nest_folders", "Folders as nested documents", FieldType.BOOLEAN, default = "true"),
        ConfigField("publish", "Publish documents", FieldType.BOOLEAN, "Off = drafts", default = "true")
    )

    private val JSON = "application/json; charset=utf-8".toMediaType()

    class OutlineException(val status: Int, message: String) : RuntimeException(message)

    private fun base(config: Map<String, String>) =
        config["base_url"].orEmpty().trim().trimEnd('/').removeSuffix("/api").ifBlank { "https://app.getoutline.com" }
            .let { if (it.startsWith("http")) it else "https://$it" }

    /** POST /api/<method>, returns `data`. */
    private fun call(config: Map<String, String>, method: String, body: JsonObject): JsonElement {
        val req = Request.Builder().url("${base(config)}/api/$method")
            .post(body.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer ${config["api_key"].orEmpty().trim()}")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!r.isSuccessful) {
                val msg = (o?.get("message") as? JsonPrimitive)?.contentOrNull ?: (o?.get("error") as? JsonPrimitive)?.contentOrNull
                throw OutlineException(r.code, "$method: HTTP ${r.code}${msg?.let { " — $it" } ?: ""}")
            }
            return o?.get("data") ?: JsonObject(emptyMap())
        }
    }

    private fun JsonElement.str(k: String) = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.contentOrNull

    override suspend fun test(config: Map<String, String>): ExportResult = withContext(Dispatchers.IO) {
        runCatching {
            val d = call(config, "auth.info", JsonObject(emptyMap()))
            val user = (d as? JsonObject)?.get("user")?.str("name")
            val team = (d as? JsonObject)?.get("team")?.str("name")
            ExportResult(true, "Connected" + (user?.let { " as $it" } ?: "") + (team?.let { " ($it)" } ?: ""))
        }.getOrElse { ExportResult(false, it.message ?: "Connection failed") }
    }

    private fun resolveCollection(config: Map<String, String>, state: ExportState): String {
        val wanted = config["collection"].orEmpty().trim().ifBlank { "Lab Wiki" }
        state.get("collection:$wanted")?.let { return it }
        val list = call(config, "collections.list", buildJsonObject { put("limit", 100) }) as? JsonArray ?: JsonArray(emptyList())
        val hit = list.firstOrNull { it.str("id") == wanted || it.str("name").equals(wanted, ignoreCase = true) }
        val id = hit?.str("id") ?: call(config, "collections.create", buildJsonObject { put("name", wanted) }).str("id")
            ?: throw RuntimeException("Could not create collection $wanted")
        state.put("collection:$wanted", id)
        return id
    }

    /** Returns (id, url) of a document, creating it if needed; re-creates if it was deleted remotely. */
    private fun upsertDoc(
        config: Map<String, String>, state: ExportState, key: String,
        title: String, text: String, collectionId: String, parentId: String?, publish: Boolean
    ): Pair<String, String> {
        val known = state.get(key)?.split('|')
        if (known != null && known.size == 2) {
            try {
                val d = call(config, "documents.update", buildJsonObject {
                    put("id", known[0]); put("title", title); put("text", text); put("publish", publish)
                })
                val pair = known[0] to (d.str("url") ?: known[1])
                state.put(key, pair.first + "|" + pair.second)
                return pair
            } catch (e: OutlineException) {
                if (e.status != 404 && e.status != 400 && e.status != 403) throw e
                state.remove(key) // deleted/moved remotely → create again
            }
        }
        val d = call(config, "documents.create", buildJsonObject {
            put("title", title); put("text", text); put("collectionId", collectionId); put("publish", publish)
            if (parentId != null) put("parentDocumentId", parentId)
        })
        val id = d.str("id") ?: throw RuntimeException("documents.create returned no id")
        val pair = id to (d.str("url") ?: "")
        state.put(key, pair.first + "|" + pair.second)
        return pair
    }

    private fun uploadAsset(config: Map<String, String>, state: ExportState, a: java.io.File, mime: String, documentId: String?): String? {
        val key = "asset:${a.name}:${a.length()}"
        state.get(key)?.let { return it }
        val d = call(config, "attachments.create", buildJsonObject {
            put("name", a.name); put("contentType", mime); put("size", a.length())
            put("preset", "documentAttachment")
            if (documentId != null) put("documentId", documentId)
        })
        val uploadUrl = d.str("uploadUrl") ?: return null
        val form = (d as JsonObject)["form"] as? JsonObject
        val attachmentUrl = d["attachment"]?.str("url") ?: return null
        val absolute = if (uploadUrl.startsWith("http")) uploadUrl else base(config) + uploadUrl
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
        form?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { mb.addFormDataPart(k, it) } }
        mb.addFormDataPart("file", a.name, a.asRequestBody(mime.toMediaType()))
        val rb = Request.Builder().url(absolute).post(mb.build())
        if (absolute.startsWith(base(config))) rb.header("Authorization", "Bearer ${config["api_key"].orEmpty().trim()}")
        http.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 204) throw RuntimeException("Image upload ${a.name} failed (HTTP ${r.code})")
        }
        val full = if (attachmentUrl.startsWith("http")) attachmentUrl else base(config) + attachmentUrl
        state.put(key, full)
        return full
    }

    override suspend fun export(bundle: ExportBundle, config: Map<String, String>, state: ExportState, progress: ExportProgress): ExportResult =
        withContext(Dispatchers.IO) {
            val publish = config["publish"] != "false"
            val nest = config["nest_folders"] != "false"
            val collectionId = try { resolveCollection(config, state) } catch (e: Exception) {
                return@withContext ExportResult(false, e.message ?: "Collection error")
            }
            val total = bundle.documents.size * 2
            var done = 0

            // 1) folder documents (Outline has no folders; nested docs are its equivalent)
            val folderIds = HashMap<String, String>()
            fun folderDoc(path: String): String? {
                if (!nest || path.isBlank()) return null
                folderIds[path]?.let { return it }
                val parent = folderDoc(path.substringBeforeLast('/', ""))
                val (id, _) = upsertDoc(config, state, "folder:$collectionId:$path", path.substringAfterLast('/'), "",
                    collectionId, parent, publish)
                folderIds[path] = id
                return id
            }

            // 2) create/refresh every selected doc so all ids/urls exist before links are written
            val ids = HashMap<String, Pair<String, String>>()
            val results = mutableListOf<ItemResult>()
            for (d in bundle.documents) {
                val r = runCatching {
                    val parent = folderDoc(d.folder)
                    val key = "doc:$collectionId:${d.id}"
                    ids[d.id] = if (state.get(key) == null)
                        upsertDoc(config, state, key, d.title, "…", collectionId, parent, publish)
                    else state.get(key)!!.split('|').let { it[0] to it[1] }
                }
                if (r.isFailure) results.add(ItemResult(d.id, false, r.exceptionOrNull()?.message ?: "failed"))
                progress.update(++done, total, d.title)
            }

            // 3) write content with links resolved to Outline URLs
            for (d in bundle.documents) {
                val pair = ids[d.id] ?: continue
                val r = runCatching {
                    val text = toOutlineMarkdown(d, bundle, config, state, collectionId, pair.first)
                    val (_, url) = upsertDoc(config, state, "doc:$collectionId:${d.id}", d.title, text, collectionId,
                        folderDoc(d.folder), publish)
                    url
                }
                results.add(ItemResult(d.id, r.isSuccess, r.exceptionOrNull()?.message ?: "",
                    r.getOrNull()?.let { if (it.startsWith("http")) it else base(config) + it }))
                progress.update(++done, total, d.title)
            }
            val failed = results.count { !it.ok }
            ExportResult(failed == 0,
                if (failed == 0) "Exported ${bundle.documents.size} notes to Outline" else "$failed of ${results.size} failed",
                results, results.firstOrNull { it.ok }?.remoteUrl ?: base(config))
        }

    /** Wikilinks → Outline doc links, embeds → uploaded attachments, drop duplicate H1. */
    internal fun toOutlineMarkdown(
        d: ExportDocument, bundle: ExportBundle, config: Map<String, String>, state: ExportState,
        collectionId: String, docId: String
    ): String {
        var text = d.sourceBody.trim()
        // Outline shows the title separately → remove a leading "# Title"
        Regex("^#\\s+(.+)\\n+").find(text)?.let { m -> if (m.groupValues[1].trim().equals(d.title, true)) text = text.substring(m.range.last + 1) }
        val links = WikiSyntax.links(text).sortedByDescending { it.range.first }
        for (l in links) {
            val replacement = if (l.embed) {
                val f = bundle.assetFile(l.target)
                val url = if (f != null && f.exists()) runCatching {
                    uploadAsset(config, state, f, ExportPreparer.mimeOf(f.name), docId)
                }.getOrNull() else null
                if (url != null) "![${l.alias ?: ""}]($url)" else "*(missing: ${l.target})*"
            } else {
                val label = l.alias ?: (if (l.heading != null) "${l.target} › ${l.heading}" else l.target)
                val noteId = bundle.resolveNoteId(l.target)
                val remote = noteId?.let { state.get("doc:$collectionId:$it") }?.split('|')?.getOrNull(1)
                if (remote.isNullOrBlank()) label
                else "[$label](${if (remote.startsWith("http")) remote else base(config) + remote})"
            }
            text = text.replaceRange(l.range, replacement)
        }
        val tags = d.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" }
        return if (tags != null) "$text\n\n---\n$tags\n" else text
    }
}
