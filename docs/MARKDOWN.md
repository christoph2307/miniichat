# Supported Markdown

MiniiChat Lab stores every wiki note as a plain `.md` file with YAML frontmatter, compatible
with Obsidian. The table says what the in-app renderer shows, and what export does with it.

Legend: ✅ rendered · ◐ partly rendered (shown in a simpler form) · 📄 kept as text and exported unchanged

## CommonMark core

| Syntax | Example | Status |
| --- | --- | --- |
| ATX headings | `# H1` … `###### H6` | ✅ |
| Setext headings | `Title` + `===` / `---` | ✅ |
| Paragraphs, soft wrap | | ✅ |
| Hard line break | two trailing spaces, or `\` | ✅ |
| Bold / italic / bold-italic | `**b**` `__b__` `*i*` `_i_` `***bi***` | ✅ |
| Inline code | `` `code` `` | ✅ |
| Fenced code blocks | ```` ```python ```` / `~~~` | ✅ monospace, language label (no syntax colors) |
| Indented code blocks (4 spaces) | | 📄 shown as paragraph |
| Block quotes, multi-line | `> text` | ✅ |
| Bullet lists, nested | `-` `*` `+`, indent 2 spaces | ✅ |
| Ordered lists | `1.` `1)` | ✅ |
| Links | `[text](https://…)` | ✅ tappable, opens browser |
| Relative links to notes | `[text](Folder/Note.md#heading)` | ✅ opens the note |
| Images | `![alt](assets/photo.jpg)` | ✅ local asset · ◐ remote URLs become a link |
| Autolinks | `<https://…>` | ✅ |
| Horizontal rule | `---` `***` `___` | ✅ |
| Backslash escapes | `\*not italic\*` | ✅ |
| HTML blocks / inline HTML | `<br>`, `<details>` | 📄 shown as text |
| Reference-style links | `[text][id]` + `[id]: url` | 📄 |

## GitHub Flavored Markdown (GFM)

| Syntax | Example | Status |
| --- | --- | --- |
| Tables with alignment | `\| a \| b \|` / `\|:--\|--:\|` | ✅ scrolls sideways |
| Task lists | `- [ ] todo` / `- [x] done` | ✅ tap to toggle in notes |
| Strikethrough | `~~text~~` | ✅ |
| Bare URL autolinks | `https://example.org` | ✅ |
| Footnote references | `text[^1]` | ◐ superscript marker |
| Footnote definitions | `[^1]: note` | 📄 shown as paragraph |

## Obsidian extensions

| Syntax | Example | Status |
| --- | --- | --- |
| YAML frontmatter | `---` `title:` `folder:` `tags:` `aliases:` `---` | ✅ properties box, used for the index |
| Wikilinks | `[[Note]]` | ✅ tappable · dimmed + underlined if the note doesn't exist (tap creates it) |
| Wikilink with alias | `[[Note\|shown text]]` | ✅ |
| Link to heading | `[[Note#Heading]]` | ✅ opens the note |
| Link to block | `[[Note#^block-id]]` | ◐ opens the note |
| Embeds | `![[photo.jpg]]` | ✅ images from `assets/` |
| Note embeds | `![[Other note]]` | ◐ link to the note (no transclusion) |
| Tags, nested | `#optics` `#project/laser` | ✅ tappable → tag search; collected in index |
| Callouts | `> [!note] Title` · `> [!warning]-` (collapsed) · `[!tip]+` | ✅ colored, collapsible |
| Highlights | `==important==` | ✅ |
| Comments | `%% hidden %%` | ✅ hidden |
| Inline math | `$E=mc^2$` | ◐ monospace italic (no TeX) |
| Math blocks | `$$ … $$` | ◐ monospace block |
| Mermaid / Dataview code blocks | ```` ```mermaid ```` | 📄 shown as code |

## Frontmatter keys the app reads/writes

| Key | Meaning |
| --- | --- |
| `id` | stable note id (file name), never changes |
| `title` | note title = link target of `[[…]]` |
| `folder` | virtual folder path, e.g. `Projects/Laser/Runs` |
| `tags` | list or comma string; merged with inline `#tags` |
| `aliases` | alternative names that `[[…]]` and auto-linking also resolve |
| `created`, `updated` | ISO-8601 timestamps |
| `source` | `chat:<conversation>/<message>` for notes saved from a chat |
| anything else | preserved verbatim |

## What the LLM is asked to return

When "Tell the AI about the wiki" is on, every chat system prompt ends with a fragment
asking for **one Markdown document with frontmatter** (`title`, `folder`, `tags`) that uses
`[[Exact Title]]` only for notes from the provided index (see `WikiLinker.chatContext`).
