# Wiki: how notes and links work

## Workflow

1. Dictate (🎤) and/or take photos → send to the LLM (e.g. *Lab notebook* assistant).
2. The LLM returns Markdown. Because the chat system prompt includes the **wiki index**
   (titles, folders, tags), the reply already comes with frontmatter and `[[links]]` to existing notes.
3. Decide on the reply in the bar under it:
   - **Accept → Wiki**: saves it (as below), adds a link back to the chat in a collapsed
     `> [!info]- Source` callout, **archives the chat** so the chat list stays short, and opens the note.
     Archived chats live under *Archived chats* in the drawer. Tapping the source link reopens them.
   - **Reject**: deletes the answer and regenerates it automatically from the same prompt.
     The ✎ button rejects **with feedback**. Your note is added to the prompt for this one request only.
   - (**Save to wiki** under any older reply saves without archiving.)
   Saving does the following:
   - frontmatter / first `# H1` → title, folder, tags (fallback folder: *Settings → Wiki → Default folder*)
   - photos from your prompt are copied to `assets/` and embedded as `![[photo.jpg]]`
   - optional: raw transcript kept in a collapsed `> [!quote]-` callout
   - **local auto-link**: plain-text mentions of other note titles/aliases become `[[links]]`
   - saving the same reply again updates the same note (tracked via `source:`)
4. In the note: backlinks, unlinked mentions, missing links, and the **AI links** button.

## One page, many recordings

- Several recordings can go into one message: each recording is queued, transcribed in turn,
  and inserted as `🎙 Recording N (m:ss): …`. On saving, all recordings are copied to the note
  under **## Recordings** (playable in the app, exported as attachments).
- **Add recordings / update in chat** (on any note) starts a chat bound to that page. Every
  request includes the current page, and the model returns the complete updated page. **Accept**
  updates the same note. `WikiMerge.mergeUpdate` guarantees that images, recordings and the
  **Sources** callout (one entry per contributing chat) survive even if the model drops them.
  The title stays the same, so `[[links]]` keep working.

## Decision: local index + LLM librarian (hybrid)

| | Local index only | LLM decides everything | **Hybrid (chosen)** |
| --- | --- | --- | --- |
| Works offline | ✅ | ❌ | ✅ for basics |
| Cost per note | 0 | whole wiki per call | index digest (~1 line/note) |
| Finds semantic links ("this is the follow-up of run 2") | ❌ | ✅ | ✅ on demand |
| Can damage text / numbers | no | yes (rewrites) | **no**: it only *proposes* anchors |
| Deterministic, reviewable | ✅ | ❌ | ✅ (review dialog) |

**How it works:**

- **The index is local and always correct.** `WikiIndex` is rebuilt from the notes on every
  change. It holds outgoing links, backlinks, tags, folders, unresolved links and
  "mentioned but not linked". Nothing is stored twice, so it can't go out of sync.
- **The LLM decides the meaning, on request.** The **AI links** button sends a compact digest
  of the index plus the one note. The model returns JSON: link targets with an exact anchor
  phrase, tags, a folder and aliases. You tick what you want, and the app inserts
  `[[Target|anchor]]` at those phrases, or adds them to a `## Related` section. The LLM never
  writes the note text, so it cannot alter measurements.
- **Prevention is cheaper than repair.** With *Tell the AI about the wiki* on, the model sees
  the titles while writing the note, so most links are already correct before saving.

## Prompts

### Chat context (appended to the system prompt) — `WikiLinker.chatContext`

```
The user keeps an Obsidian-style wiki. When you produce documentation, return ONE Markdown
document that starts with YAML frontmatter:
---
title: <concise title>
folder: <virtual folder, reuse one below>
tags: [tag-a, tag-b]
---
Reference existing notes with [[Exact Title]] (or [[Exact Title|text]]) where relevant;
do not invent links to notes that are not listed.
Folders: …
Existing notes:
- [[Title]] (Folder) #tags
```

### AI links (librarian) — `WikiLinker.SYSTEM_PROMPT`

System prompt (verbatim in code), user message = `## FOLDERS`, `## INDEX` (digest with
one-line summaries), `## NOTE` (title, folder, tags, body ≤ 12 000 chars). Expected reply:

```json
{"links":[{"target":"Exact Title","anchor":"phrase from note or null","reason":"…","new":false}],
 "tags":["…"],"folder":"…","aliases":["…"],"summary":"…"}
```

Validation (`WikiLinker.validate`): self-links are dropped. Targets are matched to existing
titles and aliases, case-insensitively. Unknown targets are kept only when `"new": true`,
and can then be created as stub notes.

## Storage

```
files/wiki/notes/<id>.md     frontmatter + body
files/wiki/assets/<file>     images and attachments
files/wiki/folders.txt       explicitly created (empty) virtual folders
```

Folders are **virtual**: a note's folder is just its `folder:` value, so moving a note never
renames a file and never breaks a link. Renaming a note rewrites `[[Old]]` → `[[New]]` in
every other note, the way Obsidian does.
