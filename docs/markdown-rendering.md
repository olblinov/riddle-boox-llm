# Markdown rendering

`renderMarkdownFile(filePath, { width = 1404, height = 1872, expectedSha256 } = {})` in `src/markdown.js` returns:

```js
{
  source: { path: "/absolute/source.md", sha256: "…" },
  pages: [{
    imageBase64, width, height,
    sourceStartLine, sourceEndLine,
    pageIndex: 1, pageCount: 3
  }]
}
```

Uses markdown-it parsing and sharp/Pango text layout. No browser, HTML execution, or network fetching. Headings, bold/italic/strikethrough, inline code, ordered/bullet/nested lists, block quotes, horizontal rules, fenced/indented code and GFM tables render with distinct typography. PNG pages use black text, subtle code/header backgrounds, 96-pixel margins on all four sides at native width and a filename/source-lines/page footer. Body text is 36 pixels at 1404-pixel width, up from 28; line spacing leaves room for ink without a reserved comment column. Source line ranges are inclusive and one-based; wrapped paragraphs may share ranges across pages. Code lines retain exact source ranges; table body rows map to source rows.

Long lines wrap, including code and URLs. Code indentation remains visible. Long code blocks and table rows split across pages without truncating text; wide tables split into labeled column bands. Table column bands may revisit earlier source line numbers. Cells and code preserve their text; source tabs display as four spaces. Links show their destination as text.

Images are visibly replaced by `[Image not rendered: alt (source)]`, including local images. Raw HTML stays literal text. Math, Mermaid, footnotes, task-list checkbox widgets and custom Markdown extensions have no specialized renderer: their source syntax stays visible, with fenced diagrams shown as code. No syntax highlighting, external CSS, or embedded assets. Empty documents show an explicit empty-document message.

Source must be regular UTF-8 file, at most 256 KiB. Reads are bounded even if file grows. Dimensions must be 600–4096 by 800–4096 and at most 16 million pixels. Maximum 200 pages, with a 40-second cooperative render deadline checked during measurement and rasterization. Large/slow inputs fail explicitly; nothing is silently truncated. Caller should split oversized documents. One native image operation can complete after deadline before next check.

SHA-256 covers exact source bytes. `expectedSha256` mismatch fails before layout. Rendering never writes source. Coordinator handles snapshot persistence and applying comments; renderer does not edit or interpret annotations.

Tests verify real PNG dimensions, Markdown styles, source immutability/hash, long code character preservation/source ranges, long/wide table preservation, inert HTML/images/links, limits/deadline, and pixel-level code indentation. Preview fixture and PNGs live under ignored `outputs/markdown-preview/` and were visually inspected.
