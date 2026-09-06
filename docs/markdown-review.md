# Markdown review loop

Generate the real `.md` file first. Call `boox_present` with `markdown_path` and omit `markdown_page`. Desktop renders the whole document as numbered PNG pages with larger text and narrow margins. Android and browser provide Previous/Next navigation, keep separate ink for each page, and submit all pages with one Send after every page has been visited.

Feedback contains each page’s composite PNG, vector ink, note and source line range, plus original path, SHA-256 and immutable snapshot. Codex reads every returned page before adapting the original Markdown. Headings, tables, code fences and unaffected text stay intact. Ambiguous ink becomes a focused clarification. The bridge never rewrites the source.

A checksum binds every page to the same source version. Before adaptation, Codex compares current file and snapshot and reconciles concurrent changes. Blank submitted pages carry no page-specific comments.

```json
{
  "title": "Storage plan",
  "markdown_path": "/absolute/path/examples/markdown-review.md"
}
```

Keep the returned `id` for `boox_wait_feedback`. Explicit `markdown_page` remains available for single-page review and legacy clients; it is no longer the default workflow. Payloads exceeding the bridge’s 24 MiB request limit fail explicitly and require smaller document batches.

Rendering support and fallbacks: [markdown-rendering.md](markdown-rendering.md). Review instructions: [the skill](../skills/boox-review/SKILL.md).
