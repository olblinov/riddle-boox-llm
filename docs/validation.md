# Verification

Verified 2026-09-06. Software loop works. Physical BOOX installation and stylus/refresh acceptance remain pending because no device is connected.

## Evidence

- Private GitHub repository visibility verified `PRIVATE`.
- Skill validator passed. Independent instruction review covered repeated pending responses and ambiguous ink; no concrete flaw found.
- Five bridge tests passed: auth/origin restrictions; page lifecycle and persistence; duplicate/stale feedback; malformed PNG/stroke rejection; bounded MCP wait with returned image; text escaping/overflow; real stdio MCP subprocess. Tests group related checks.
- Browser tested through actual UI against isolated bridge: pair, draw, note, reload draft, Send, receive original-size composite. Disconnection preserved draft; reconnect/retry succeeded.
- Browser regression runs actual app code with simulated lost response after server acceptance: editor locks, exact request survives reload, newer page cannot replace it, identical retry succeeds.
- Native APK compiled for minimum Android 9/API 28, target Android 11/API 30. `apksigner` verified signature. Native palm-pointer handling and uncertain-send locking reviewed and rebuilt; physical runtime remains untested.
- Installed macOS bridge responds `200` to authenticated health request. Global `boox` MCP command and skill symlink verified.
- Live Codex desktop acceptance task discovered actual installed MCP tools, presented a Client/Database diagram, waited once with pending result, then received synthetic annotated PNG through another wait call. It correctly read “Add a replica below Database” from the image without a text note. No shell substitute or simulated MCP response used. Review ID `0cbf6d01-8196-4170-940c-5fcceaed7238`.

## Markdown extension

Combined suite: 12 tests passed; dependency audit reported zero vulnerabilities.

- Rendered Markdown verified with headings, emphasis, lists, table, YAML code, quote, page/source-line footer, and annotation margin. Pixel regression covers visible code indentation.
- Multi-page MCP integration preserves original path, immutable snapshot, line range and checksum through feedback and bridge restart. Later-page presentation rejects changed source versions.
- Live Codex desktop review of `work/markdown-acceptance.md` used actual `markdown_path` tool input, then received synthetic annotations solely through the image result. It verified source checksum and changed retention from 30 to 7 days in prose, table and YAML. Weekly restore tests and all other bytes stayed unchanged. Review `4cb8a9d4-82eb-499d-a9e8-88bf70f4cfa3`.
- Markdown renderer never writes source. Codex adapts the file after interpreting submitted page feedback. Embedded images remain labelled placeholders; Mermaid stays fenced source. See renderer documentation for exact support.

## Remaining hardware step

Connect Note Air2 Plus by USB with USB debugging enabled and authorize this Mac, or install APK manually and pair over trusted LAN. Validate real pen pressure/eraser, palm rejection, pan/zoom, draft recovery, Send, latency, and ghosting. The implementation uses standard Android Canvas; BOOX vendor fast-ink support is not included.

## Sources

- Codex MCP configuration and default tool timeout: https://learn.chatgpt.com/docs/extend/mcp?surface=cli
- Skill discovery through repository/user `.agents/skills`: https://learn.chatgpt.com/docs/build-skills
- BOOX SDK reference for future native ink optimization: https://github.com/onyx-intl/OnyxAndroidDemo
