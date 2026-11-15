package com.miniichat.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a bundle as a ZIP: Markdown files in folders + assets/ (an Obsidian vault). */
object ZipWriter {
    fun write(bundle: ExportBundle, out: OutputStream, progress: ExportProgress = ExportProgress { _, _, _ -> }) {
        ZipOutputStream(out).use { zip ->
            val total = bundle.documents.size + bundle.assets.size
            var done = 0
            for (d in bundle.documents) {
                zip.putNextEntry(ZipEntry(d.relativePath))
                zip.write(d.markdown.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                progress.update(++done, total, d.title)
            }
            for (a in bundle.assets) {
                if (!a.file.exists()) continue
                zip.putNextEntry(ZipEntry(a.relativePath))
                a.file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                progress.update(++done, total, a.name)
            }
        }
    }
}

/**
 * Reference implementation of a remote target: POSTs the selection as JSON to any URL
 * (n8n, Zapier, Make, Power Automate, a small Flask/FastAPI service, …).
 * Body: {"source":"miniichat-lab","exported_at":…,"documents":[…],"assets":[…]}.
 */
class WebhookExportTarget : ExportTarget {
    override val id = "webhook"
    override val name = "Webhook (HTTP POST JSON)"
    override val description = "Sends selected notes as JSON to a URL — works with n8n, Zapier, Make or your own service."
    override val capabilities = ExportCapabilities(assets = true, folders = true, preferredLinkStyle = LinkStyle.WIKILINK, maxBatch = 50)
    override val fields = listOf(
        ConfigField("url", "Endpoint URL", FieldType.URL, "https://example.org/hooks/wiki", required = true),
        ConfigField("auth_header", "Auth header name", FieldType.TEXT, "Authorization", default = "Authorization"),
        ConfigField("token", "Token / secret", FieldType.SECRET, "Sent as 'Bearer <token>' unless it contains a space"),
        ConfigField("include_assets", "Embed assets (base64)", FieldType.BOOLEAN, default = "false"),
        ConfigField("one_request_per_note", "One request per note", FieldType.BOOLEAN, default = "false")
    )

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun request(config: Map<String, String>, json: String): Request {
        val b = Request.Builder().url(config["url"]!!.trim()).post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val token = config["token"].orEmpty().trim()
        if (token.isNotEmpty()) {
            val header = config["auth_header"].orEmpty().ifBlank { "Authorization" }
            b.header(header, if (header.equals("Authorization", true) && !token.contains(' ')) "Bearer $token" else token)
        }
        return b.header("User-Agent", "MiniiChat-Lab-Export/1").build()
    }

    override suspend fun test(config: Map<String, String>): ExportResult = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request(config, """{"source":"miniichat-lab","test":true}""")).execute().use {
                ExportResult(it.isSuccessful, "HTTP ${it.code}")
            }
        }.getOrElse { ExportResult(false, it.message ?: "Request failed") }
    }

    override suspend fun export(bundle: ExportBundle, config: Map<String, String>, state: ExportState, progress: ExportProgress): ExportResult =
        withContext(Dispatchers.IO) {
            val withAssets = config["include_assets"] == "true"
            val perNote = config["one_request_per_note"] == "true"
            val batches = if (perNote) bundle.documents.map { listOf(it) } else bundle.documents.chunked(capabilities.maxBatch)
            val results = mutableListOf<ItemResult>()
            var done = 0
            for (batch in batches) {
                val assets = batch.flatMap { it.assets }.distinctBy { it.name }
                val payload = buildJsonObject {
                    put("source", "miniichat-lab")
                    put("exported_at", Instant.ofEpochMilli(bundle.createdAt).toString())
                    put("link_style", bundle.options.linkStyle.name)
                    put("documents", buildJsonArray { batch.forEach { add(docJson(it)) } })
                    put("assets", buildJsonArray {
                        assets.forEach { a ->
                            add(buildJsonObject {
                                put("name", a.name); put("path", a.relativePath); put("mime", a.mimeType)
                                if (withAssets && a.file.length() < 15L * 1024 * 1024) {
                                    put("base64", Base64.getEncoder().encodeToString(a.file.readBytes()))
                                }
                            })
                        }
                    })
                }
                val r = runCatching {
                    http.newCall(request(config, payload.toString())).execute().use { resp ->
                        resp.isSuccessful to "HTTP ${resp.code}"
                    }
                }.getOrElse { false to (it.message ?: "failed") }
                batch.forEach { results.add(ItemResult(it.id, r.first, r.second)) }
                done += batch.size
                progress.update(done, bundle.documents.size, batch.last().title)
            }
            val failed = results.count { !it.ok }
            ExportResult(failed == 0, if (failed == 0) "Exported ${results.size} notes" else "$failed of ${results.size} failed", results)
        }

    private fun docJson(d: ExportDocument) = buildJsonObject {
        put("id", d.id); put("title", d.title); put("folder", d.folder); put("path", d.relativePath)
        put("tags", JsonArray(d.tags.map { JsonPrimitive(it) }))
        put("aliases", JsonArray(d.aliases.map { JsonPrimitive(it) }))
        put("created", Instant.ofEpochMilli(d.created).toString())
        put("updated", Instant.ofEpochMilli(d.updated).toString())
        put("links_to", JsonArray(d.linksTo.map { JsonPrimitive(it) }))
        put("linked_from", JsonArray(d.linkedFrom.map { JsonPrimitive(it) }))
        put("assets", JsonArray(d.assets.map { JsonPrimitive(it.relativePath) }))
        put("markdown", d.markdown)
    }
}

/**
 * Placeholder for tools that will be added later. Shows up as "planned" so the UI,
 * config storage and selection flow can already be exercised.
 */
class PlannedExportTarget(
    override val id: String,
    override val name: String,
    override val description: String,
    override val fields: List<ConfigField> = emptyList(),
    override val capabilities: ExportCapabilities = ExportCapabilities()
) : ExportTarget {
    override val available = false
    override suspend fun export(bundle: ExportBundle, config: Map<String, String>, state: ExportState, progress: ExportProgress) =
        ExportResult(false, "$name export is not implemented yet")
}

object ExportRegistry {
    /** Local ZIP/share export is handled by the export screen itself (needs Android SAF). */
    val targets: List<ExportTarget> = listOf(
        NextcloudExportTarget(),
        OutlineExportTarget(),
        WebhookExportTarget()
    )

    fun byId(id: String) = targets.firstOrNull { it.id == id }
}
