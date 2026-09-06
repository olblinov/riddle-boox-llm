# Verification

Verified 2026-09-06. Software loop and physical Note Air2 Plus USB/pen round trip verified. Detailed latency, eraser and ghosting measurements remain unmeasured.

## Evidence

- Private GitHub repository visibility verified `PRIVATE`.
- Skill validator passed. Independent instruction review covered repeated pending responses and ambiguous ink; no concrete flaw found.
- Five bridge tests passed: auth/origin restrictions; page lifecycle and persistence; duplicate/stale feedback; malformed PNG/stroke rejection; bounded MCP wait with returned image; text escaping/overflow; real stdio MCP subprocess. Tests group related checks.
- Browser tested through actual UI against isolated bridge: pair, draw, note, reload draft, Send, receive original-size composite. Disconnection preserved draft; reconnect/retry succeeded.
- Browser regression runs actual app code with simulated lost response after server acceptance: editor locks, exact request survives reload, newer page cannot replace it, identical retry succeeds.
- Native APK compiled for minimum Android 9/API 28, target Android 11/API 30. `apksigner` verified signature. Native palm-pointer handling and uncertain-send locking reviewed and rebuilt; initial physical runtime was tested later as recorded below.
- Installed macOS bridge responds `200` to authenticated health request. Global `boox` MCP command and skill symlink verified.
- Live Codex desktop acceptance task discovered actual installed MCP tools, presented a Client/Database diagram, waited once with pending result, then received synthetic annotated PNG through another wait call. It correctly read “Add a replica below Database” from the image without a text note. No shell substitute or simulated MCP response used. Review ID `0cbf6d01-8196-4170-940c-5fcceaed7238`.

## Markdown extension

Combined suite: 12 tests passed; dependency audit reported zero vulnerabilities.

- Rendered Markdown verified with headings, emphasis, lists, table, YAML code, quote, page/source-line footer, and annotation margin. Pixel regression covers visible code indentation.
- Multi-page MCP integration preserves original path, immutable snapshot, line range and checksum through feedback and bridge restart. Later-page presentation rejects changed source versions.
- Live Codex desktop review of `work/markdown-acceptance.md` used actual `markdown_path` tool input, then received synthetic annotations solely through the image result. It verified source checksum and changed retention from 30 to 7 days in prose, table and YAML. Weekly restore tests and all other bytes stayed unchanged. Review `4cb8a9d4-82eb-499d-a9e8-88bf70f4cfa3`.
- Markdown renderer never writes source. Codex adapts the file after interpreting submitted page feedback. Embedded images remain labelled placeholders; Mermaid stays fenced source. See renderer documentation for exact support.

## Physical BOOX acceptance

Note Air2 Plus detected over authorized USB debugging. APK installed, app unfrozen with `pm enable`, and USB `adb reverse tcp:4317 tcp:4317` paired successfully. Device screenshot verified rendered Markdown on physical display. First real submission contained eight strokes/1,334 points, with a circle around 30 days and handwritten “too much.” Clarification returned handwritten “90 days.” Codex updated prose, table, and YAML to 90 days and sent revised Markdown back to tablet. No AndroidRuntime crash observed.

## Remaining device measurements

Basic rendering, real pen capture, explicit Send, clarification, and source-edit round trip passed. Further tests can measure eraser, palm rejection, pan/zoom, restart recovery, latency, and ghosting. These initial checks used standard Android Canvas. Version 0.4.0 adds vendor native ink; its separate limits are recorded below.

## Sources

- Codex MCP configuration and default tool timeout: https://learn.chatgpt.com/docs/extend/mcp?surface=cli
- Skill discovery through repository/user `.agents/skills`: https://learn.chatgpt.com/docs/build-skills
- BOOX SDK reference: https://github.com/onyx-intl/OnyxAndroidDemo

## Whole-document update 0.3.0

Twenty automated tests passed with local socket access: ten bridge/MCP cases, five browser state/retry/memory cases, and five Markdown rendering cases. Document tests verify complete ordered submission, atomic rejection of missing pages, per-page dimensions, source metadata after restart, and labelled MCP images. Both fixed-length and chunked oversized requests return an explicit 24 MiB error without partial state.

Actual browser acceptance on isolated port 4319 covered two annotated pages, backward navigation, reload, and one Send. Server received both composite images and their separate notes/strokes.

Official BOOX specifications confirm 1404 × 1872 at 227 ppi; adb independently reported those physical pixel dimensions. Native device screenshot verifies full-width display with 24-pixel content margins; vertical finger pan reaches page footer. Three-page navigation and current-page restoration after force-stop passed in an isolated package, preserving the production pending review. Synthetic pen round-trip passed on isolated review `eb1b87d6-d9c2-4463-b479-314ac1c9cb52`: explicit Android stylus events drew a distinct stroke on each of three pages, then force-stop/relaunch restored page one and enabled Send after all pages had been visited. One Send from page one returned three 1404 × 1872 composites, each with one 32-point stroke at its original page coordinates. These are injected test strokes on physical hardware, not new user handwriting. An earlier isolated review was cancelled unexpectedly and is excluded from acceptance evidence. Production review remained untouched by test clients. Screenshots assess raster layout, not panel ghosting or subjective grain.

Production 0.3.0 APK installed over the existing app. Accessibility state confirmed restored legacy annotations and Page 1 / 1. Bridge restarted successfully; complete persisted production state matched its pre-upgrade copy exactly and authenticated health returned 200. New typography applies to newly rendered documents; the old pending image remains unchanged to preserve annotation placement.

## Native ink update 0.4.0

Twenty automated tests and RawStrokeBufferTest pass. Production and isolated test APKs pass signature/version verification. Production APK SHA-256: `51fa76bc5f893a480a4c9547d41d782b17c4a1789df8dc7bb37a9d4f8f86c419`.

On the connected Note Air2 Plus, the approved four-class compatibility scope enables nonempty native geometry with pressure maximum 4095. Pair dialog dismissal, three-page navigation and process restart pass in the isolated test package. Page two and all visited flags restore after restart. No AndroidRuntime crash observed.

Production version 0.4.0/code 4 installed over the existing app. It restores document annotations and Page 1 / 1, initializes native geometry, and leaves complete persisted bridge state equal to its pre-upgrade copy. Existing pending review remains untouched.

No physical pen raw callback or optical latency measurement was captured in this test. Android-injected stylus events do not feed the native raw reader. Physical pen, eraser and palm acceptance remain unverified; startup logs are not substitutes.

## Sent history 0.5.0

Twenty-one automated tests pass, including 90-day expiry boundary, pending-draft protection and stable submission timestamps across identical retries. Android HistoryCache JVM tests cover offline multipage access, transactional writes, duplicate IDs, shared-instance concurrency, corruption detection/repair and confinement away from pending draft files. Independent review found no remaining blocker after moving history disk reads off the UI thread.

Production APK version 0.5.0/code 5, SHA-256 `56ea65590d3e56e7677934a2187233e5e707ca32e8bd319c26505928a4c3602c`, installed on Note Air2 Plus. History listed five previous submissions. The user's four-page annotated guide opened and navigated from page one to page two. With USB bridge forwarding removed, reopening History and navigating the same document still passed. Forwarding restored afterward. Back returned to existing Page 1 / 1 draft; complete bridge state stayed equal to its pre-install copy. No review was sent or discarded during this test.

## Handwritten feedback 0.6.0

27 Node tests pass, including FIFO queue persistence/advance, exact unread acknowledgement, signed expanded-canvas validation, real local image rendering and headless-Chrome Mermaid. Android CanvasGestureTest, RawStrokeBufferTest and HistoryCacheTest pass. Independent source audit covered routing, canvas translation, Wi-Fi migration and swipe guards.

Physical isolated app swiped from page 1/3 to 2/3 and showed current plus queued document. Screenshot confirmed visible bottom writing area. Synthetic signed strokes seeded in isolated drafts exported as three 2428 x 2896 composites with canvasBounds {-512,-512,2428,2896}; x=-100/y=-50 remained unchanged in all pages. An initial test fixture incorrectly seeded negative ink on an unexpanded page and correctly received HTTP 400; corrected fixture passed. Automatic queue advance opened the second document. Physical handwriting in expanded margins remains unmeasured.

Production 0.6.0/code 6 APK SHA-256 ee119e85e43e3d75a1a51f1bb53a98e4a77a6128a14320e0001886d816225045 installed preserving exact production state. Bonjour discovered http://192.168.2.117:4317; authenticated migration succeeded with pending review preserved. USB forwarding was removed and History remained available over the Wi-Fi setup.

Automatic idle-task wake is unavailable in this desktop's supported connection; docs/feedback-continuation.md records evidence. No private IPC or fabricated user-message workaround was used.
