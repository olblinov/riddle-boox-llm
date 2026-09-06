# Markdown review loop

Generate the real `.md` file first. The BOOX skill sends that file through `boox_present` with `markdown_path`, not a prose summary. Markdown renders on desktop into numbered PNG pages, so Android and browser clients need no Markdown engine or update.

Each page gets annotation space. Send returns that page's composite PNG, vector ink, note, original file path, source line range, page number, and SHA-256. Long documents are reviewed one page at a time; Codex sends the next after each explicit submission. This version has no tablet-side whole-document navigator or single submission for every page.

Codex collects page feedback, reads handwriting against the rendered source, then edits the original Markdown. Headings, tables, code fences, and unaffected text remain intact. Ambiguous ink becomes a focused clarification. The bridge itself never rewrites the file.

Source snapshots are private runtime files. A checksum binds all pages to the same version; if the file changes during review, later page rendering rejects the old checksum. Before adaptation, Codex compares current file and snapshot and merges rather than overwriting concurrent changes.

## Example

```json
{
  "title": "Storage plan",
  "markdown_path": "/absolute/path/examples/markdown-review.md",
  "markdown_page": 1
}
```

Use `boox_present`. Keep returned `id` for `boox_wait_feedback`. For page two, use the same path, `markdown_page: 2`, and `expected_sha256` from page one's `source.sha256`.

Rendering support and explicit fallbacks are recorded in [markdown-rendering.md](markdown-rendering.md). Review behavior is defined by [the skill](../skills/boox-review/SKILL.md).
