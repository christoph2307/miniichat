package com.miniichat.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Nextcloud via WebDAV.
 *  • Login: Nextcloud **Login Flow v2** — the user signs in in the browser (incl. 2FA/SSO) and the
 *    server hands us an app password; no password is ever typed into this app.
 *  • Export: Markdown files in the vault folder layout (+ assets/), or one ZIP file.
 */
class NextcloudExportTarget(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .build()
) : ExportTarget {
    override val id = "nextcloud"
    override val name = "Nextcloud"
    override val description = "Uploads notes (Markdown + images, or a ZIP) to a folder in your Nextcloud via WebDAV."
    override val capabilities = ExportCapabilities(assets = true, folders = true, preferredLinkStyle = LinkStyle.WIKILINK)
    override val fields = listOf(
        ConfigField("server_url", "Server URL", FieldType.URL, "https://cloud.example.org", required = true),
        ConfigField("login_name", "User", FieldType.TEXT, "filled in by “Log in with Nextcloud”", required = true),
        ConfigField("app_password", "App password", FieldType.SECRET, "filled in by login (or create one under Settings → Security)", required = true),
        ConfigField("folder", "Target folder", FieldType.TEXT, "MiniiChat Wiki", default = "MiniiChat Wiki"),
        ConfigField("mode", "Upload as", FieldType.CHOICE, default = "files",
            choices = listOf("files" to "Markdown files + assets", "zip" to "Single ZIP file"))
    )

    override val login = object : InteractiveLogin {
        override val label = "Log in with Nextcloud"
        override val credentialKeys = listOf("login_name", "app_password")

        override suspend fun begin(config: Map<String, String>): LoginStart = withContext(Dispatchers.IO) {
            val server = normalizeServer(config["server_url"] ?: throw IllegalArgumentException("Enter the server URL first"))
            val req = Request.Builder().url("$server/index.php/login/v2")
                .post(ByteArray(0).toRequestBody(null))
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw RuntimeException("Login flow not available (HTTP ${r.code}). Is this a Nextcloud ≥ 16 server?")
                val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
                val poll = o["poll"]!!.jsonObject
                LoginStart(
                    browserUrl = (o["login"] as JsonPrimitive).content,
                    pollUrl = (poll["endpoint"] as JsonPrimitive).content,
                    token = (poll["token"] as JsonPrimitive).content
                )
            }
        }

        override suspend fun poll(start: LoginStart): Map<String, String>? = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(start.pollUrl)
                .post(FormBody.Builder().add("token", start.token).build())
                .header("User-Agent", USER_AGENT).build()
            http.newCall(req).execute().use { r ->
                when (r.code) {
                    200 -> {
                        val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
                        mapOf(
                            "server_url" to (o["server"] as JsonPrimitive).content.trimEnd('/'),
                            "login_name" to (o["loginName"] as JsonPrimitive).content,
                            "app_password" to (o["appPassword"] as JsonPrimitive).content
                        )
                    }
                    404 -> null // not yet authorised
                    else -> throw RuntimeException("Login failed (HTTP ${r.code})")
                }
            }
        }

        override fun describe(config: Map<String, String>): String? =
            config["login_name"]?.takeIf { it.isNotBlank() && !config["app_password"].isNullOrBlank() }
                ?.let { "Logged in as $it" }
    }

    private fun auth(config: Map<String, String>) =
        Credentials.basic(config["login_name"].orEmpty(), config["app_password"].orEmpty())

    override suspend fun test(config: Map<String, String>): ExportResult = withContext(Dispatchers.IO) {
        runCatching {
            val server = normalizeServer(config["server_url"].orEmpty())
            val req = Request.Builder().url("$server/ocs/v1.php/cloud/user?format=json")
                .header("OCS-APIRequest", "true").header("Authorization", auth(config))
                .header("User-Agent", USER_AGENT).get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use ExportResult(false, "HTTP ${r.code} — check user/app password")
                val name = runCatching {
                    Json.parseToJsonElement(r.body!!.string()).jsonObject["ocs"]!!.jsonObject["data"]!!.jsonObject["display-name"]
                        .let { (it as JsonPrimitive).content }
                }.getOrNull()
                ExportResult(true, "Connected" + (name?.let { " as $it" } ?: ""))
            }
        }.getOrElse { ExportResult(false, it.message ?: "Connection failed") }
    }

    override suspend fun export(bundle: ExportBundle, config: Map<String, String>, state: ExportState, progress: ExportProgress): ExportResult =
        withContext(Dispatchers.IO) {
            val server = normalizeServer(config["server_url"].orEmpty())
            val user = config["login_name"].orEmpty()
            val folder = config["folder"].orEmpty().ifBlank { "MiniiChat Wiki" }.trim('/')
            val davRoot = "$server/remote.php/dav/files/".toHttpUrl().newBuilder().addPathSegment(user).build()
            val authHeader = auth(config)
            val created = HashSet<String>()

            fun url(path: String): HttpUrl {
                val b = davRoot.newBuilder()
                path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
                return b.build()
            }

            fun mkdirs(dir: String) {
                val parts = dir.split('/').filter { it.isNotEmpty() }
                for (i in 1..parts.size) {
                    val p = parts.take(i).joinToString("/")
                    if (!created.add(p)) continue
                    val req = Request.Builder().url(url(p)).method("MKCOL", null)
                        .header("Authorization", authHeader).header("User-Agent", USER_AGENT).build()
                    http.newCall(req).execute().use { r ->
                        // 201 created, 405 already exists
                        if (r.code != 201 && r.code != 405) throw RuntimeException("Cannot create folder $p (HTTP ${r.code})")
                    }
                }
            }

            fun put(path: String, body: okhttp3.RequestBody) {
                val req = Request.Builder().url(url(path)).put(body)
                    .header("Authorization", authHeader).header("User-Agent", USER_AGENT).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("Upload of ${path.substringAfterLast('/')} failed (HTTP ${r.code})")
                }
            }

            val webUrl = "$server/apps/files/?dir=" + java.net.URLEncoder.encode("/$folder", "UTF-8").replace("+", "%20")
            try {
                mkdirs(folder)
                if (config["mode"] == "zip") {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(bundle.createdAt))
                    val tmp = File.createTempFile("wiki-export", ".zip")
                    try {
                        tmp.outputStream().use { ZipWriter.write(bundle, it, progress) }
                        put("$folder/wiki-export-$stamp.zip", tmp.asRequestBody("application/zip".toMediaType()))
                    } finally { tmp.delete() }
                    return@withContext ExportResult(true, "Uploaded ZIP with ${bundle.documents.size} notes to /$folder",
                        bundle.documents.map { ItemResult(it.id, true) }, webUrl)
                }

                val results = mutableListOf<ItemResult>()
                val total = bundle.documents.size + bundle.assets.size
                var done = 0
                if (bundle.assets.isNotEmpty()) mkdirs("$folder/assets")
                for (a in bundle.assets) {
                    if (!a.file.exists()) continue
                    // skip unchanged assets on re-export
                    val sig = "${a.file.length()}:${a.file.lastModified()}"
                    if (state.get("asset:$folder:${a.relativePath}") != sig) {
                        put("$folder/${a.relativePath}", a.file.asRequestBody(a.mimeType.toMediaType()))
                        state.put("asset:$folder:${a.relativePath}", sig)
                    }
                    progress.update(++done, total, a.name)
                }
                for (d in bundle.documents) {
                    val r = runCatching {
                        d.relativePath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { mkdirs("$folder/$it") }
                        // note moved/renamed since last export → remove the old file
                        val prev = state.get("path:$folder:${d.id}")
                        if (prev != null && prev != d.relativePath) {
                            runCatching {
                                http.newCall(Request.Builder().url(url("$folder/$prev")).delete()
                                    .header("Authorization", authHeader).build()).execute().close()
                            }
                        }
                        put("$folder/${d.relativePath}", d.markdown.toRequestBody("text/markdown; charset=utf-8".toMediaType()))
                        state.put("path:$folder:${d.id}", d.relativePath)
                    }
                    results.add(ItemResult(d.id, r.isSuccess, r.exceptionOrNull()?.message ?: "", webUrl))
                    progress.update(++done, total, d.title)
                }
                val failed = results.count { !it.ok }
                ExportResult(failed == 0,
                    if (failed == 0) "Uploaded ${results.size} notes to /$folder" else "$failed of ${results.size} failed",
                    results, webUrl)
            } catch (e: Exception) {
                ExportResult(false, e.message ?: "Upload failed", location = webUrl)
            }
        }

    companion object {
        const val USER_AGENT = "MiniiChat Lab (Android)"
        fun normalizeServer(s: String): String {
            var u = s.trim().trimEnd('/')
            require(u.isNotEmpty()) { "Server URL missing" }
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u.removeSuffix("/index.php").removeSuffix("/remote.php/webdav")
        }
    }
}
