# Export API

Export is split into **selection → preparation → target**:

```
ExportScreen (checkbox list, sort, filter, options)
     │ ids + ExportOptions
     ▼
ExportPreparer.prepare()  → ExportBundle(documents, assets, options)
     │                       • links converted to the target's LinkStyle
     │                       • unique relative paths (Folder/Title.md)
     │                       • referenced assets collected (assets/…)
     ▼
ExportTarget.export(bundle, config, progress) → ExportResult(ok, message, items[])
```

Built in:

| Target | Status | Notes |
| --- | --- | --- |
| Share ZIP / Save ZIP | ✅ | Obsidian-vault layout: `Folder/Note.md` + `assets/` (handled by the screen, needs Android SAF) |
| Share Markdown | ✅ | selected notes concatenated, through the Android share sheet |
| `nextcloud` | ✅ | **Login Flow v2** (browser login incl. 2FA/SSO → app password), WebDAV upload as Markdown files + `assets/` or a single ZIP; moved/renamed notes delete their old file; unchanged images are skipped |
| `outline` | ✅ | API key; notes → documents in a collection (created if missing); virtual folders → nested parent documents; `[[links]]` → links to the Outline documents; images → attachments; re-export **updates** (remote ids kept in `files/export_state/outline.json`) |
| `webhook` | ✅ | POST JSON to any URL (n8n, Zapier, Make, own service), Bearer token, batching, optional base64 assets |

## Contract (`app/src/main/kotlin/com/miniichat/export/ExportApi.kt`)

```kotlin
interface ExportTarget {
    val id: String                       // storage key for the config
    val name: String
    val description: String
    val fields: List<ConfigField>        // settings UI is generated from these
    val capabilities: ExportCapabilities // assets? folders? preferred LinkStyle? maxBatch?
    val available: Boolean               // false → listed as "planned"
    suspend fun test(config: Map<String, String>): ExportResult
    val login: InteractiveLogin?          // optional browser login (Nextcloud Login Flow v2)
    suspend fun export(bundle: ExportBundle, config: Map<String, String>,
                       state: ExportState, progress: ExportProgress): ExportResult
}
```

`ExportDocument` gives each target: `id, title, folder, tags, aliases, created, updated,
relativePath, markdown (with frontmatter), body (without), linksTo, linkedFrom, assets`.

Configure targets in **Settings → Export targets**. `ConfigField` types are `TEXT`, `MULTILINE`, `URL`, `SECRET` (masked), `BOOLEAN`, `CHOICE`
and `NUMBER`. Values are stored per target in an app-private DataStore (`ExportConfigStore`).

`LinkStyle` options:
- `WIKILINK`: keeps `[[…]]`. For Obsidian, Logseq, Foam.
- `MARKDOWN_RELATIVE`: `[text](../Folder/Note.md#heading)`. For Git, MkDocs and wikis.
- `PLAIN`: links become plain text. For tools without cross-references.

## Adding a tool

```kotlin
class MyToolTarget : ExportTarget {
    override val id = "mytool"
    override val name = "My Tool"
    override val description = "Pushes notes as pages to My Tool"
    override val capabilities = ExportCapabilities(assets = true, folders = false,
        preferredLinkStyle = LinkStyle.MARKDOWN_RELATIVE, maxBatch = 20)
    override val fields = listOf(
        ConfigField("base_url", "Server URL", FieldType.URL, required = true),
        ConfigField("token", "API token", FieldType.SECRET, required = true),
        ConfigField("space", "Space / project", FieldType.TEXT)
    )
    override suspend fun test(config: Map<String, String>) = /* GET /me */ ExportResult(true, "OK")
    override suspend fun export(bundle: ExportBundle, config: Map<String, String>, progress: ExportProgress): ExportResult {
        val items = bundle.documents.mapIndexed { i, d ->
            // upload d.assets first, then create/update the page from d.markdown or d.body
            progress.update(i + 1, bundle.documents.size, d.title)
            ItemResult(d.id, ok = true, remoteUrl = "…")
        }
        return ExportResult(items.all { it.ok }, "Exported ${items.size}", items)
    }
}
```

Then register it in `ExportRegistry.targets`. It appears automatically in **Send to…** and
in **Settings → Wiki → Export targets**.

`ExportState` is a small persistent key/value store per target, kept in
`files/export_state/<id>.json`. Targets keep remote ids there, so a second export updates the
existing remote pages instead of creating duplicates. Configuration, including app passwords
and API keys, is stored in **EncryptedSharedPreferences** (AES-256, key in the Android
Keystore). In-app links (`miniichat://chat/…`) are reduced to their label on export.

Candidates to choose from: Obsidian via Git or Syncthing folder, Nextcloud or WebDAV, Notion,
Confluence, eLabFTW, OneNote (Graph API), Google Drive, Zotero notes, BookStack, Outline,
GitHub/GitLab wiki.
