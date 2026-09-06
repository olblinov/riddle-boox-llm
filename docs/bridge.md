# Local bridge and MCP

Node 22+ required. Run `npm ci`, then `npm start`. Entry files are `src/bridge.js` and `src/mcp.js`. Bridge defaults to `127.0.0.1:4317`. To reach it over trusted LAN, set `BOOX_HOST=0.0.0.0`; HTTP is unencrypted. `BOOX_PORT` changes port. Never expose it to public networks.

Runtime directory defaults to project `.runtime`; set `BOOX_DATA_DIR` to override. Pairing token is generated once at `token` with mode 0600; review state and original/composite PNGs are persisted in `state.json` with mode 0600. Runtime directory is ignored by git. Stop bridge before backing up state. Keep one bridge process per data directory.

Stdio MCP: `npm run mcp` or preferably `node /absolute/project/src/mcp.js`. Configure `BOOX_BRIDGE_URL` when not using `http://127.0.0.1:4317`, and `BOOX_TOKEN_PATH` when token isn't in project `.runtime/token`. Stdout belongs exclusively to MCP protocol. HTTP bridge must already be running.

## Tools

- `boox_present`: `title`, and exactly one of `image_path`, `markdown_path`, `text`, `scene`. Markdown defaults to one whole-document review containing every rendered page. The user browses/annotates all pages and presses Send once. Explicit `markdown_page` (1-based) retains legacy single-page selection; `expected_sha256` pins the source version. Optional `width` and `height` default to 1404×1872. Returns review metadata including `id`. PNG input uses its actual dimensions. Text wraps and refuses overflow rather than silently clipping.
- `boox_wait_feedback`: `review_id`, `wait_seconds` from 0 to 45, default 0. Returns pending/cancelled status, or submitted metadata and PNG image content. Document results include one labelled metadata block and annotated image per page, in order; base64 never appears in text blocks. Pending checks return immediately by default. Repeat only for an explicitly requested live review session. Pending never means consent.
- `boox_cancel`: `review_id`; cancels pending review and every page, preserving them. Submitted pages cannot be cancelled.
- `boox_status`: bridge health, last tablet/browser current-review polling timestamp, current review. Timestamp shows a recent client poll, not proof of physical device readiness.

Scene is an array of up to 500 objects. Coordinates use image pixels. Types: `text` with x,y,text,size; `rect` with x,y,width,height; `line` or `arrow` with x,y,x2,y2. Black ink and white background. Text is XML-escaped. No raw SVG, HTML, scripts, or external image references are accepted.

API follows design.md. Health additionally returns `lastTabletPollAt`. All `/api` endpoints need Bearer authorization, including images and health. Feedback validates PNG dimensions and stroke coordinates. POST bodies max 24 MiB; PNG base64 max 16 MiB; pages max 16 million pixels and 4096 on either edge. Legacy feedback matches original dimensions. Expanded feedback carries canvasBounds {x,y,width,height}; the source sits at (-x,-y) inside the composite, while signed stroke coordinates remain relative to the source. Each extra edge is bounded to 512 pixels; total image bounds still apply. Feedback duplicate IDs are accepted only with identical content. Mutations are serialized and atomically persisted. Up to 100 pending documents form a persistent FIFO queue. Creation never replaces the active review; Send or cancellation advances to the next. Submitted feedback remains retrievable by review ID.

`npm test` covers authenticated lifecycle, persistence, malformed images/ink, duplicate and stale feedback, MCP pending/image roundtrip, and text rendering. Physical pen latency and Android compatibility require separate device validation.

## Markdown source context

A Markdown review returns `source` with `kind`, `path`, `sha256`, `snapshotPath`, `pageIndex`, `pageCount`, `startLine`, and `endLine`. The bridge persists this metadata and includes it in submitted feedback. Immutable source snapshots live in the private runtime `documents/` directory. Every document page carries its own source range. Explicit single-page continuation should carry `expected_sha256`; source changes fail explicitly. The skill uses page annotations to adapt the original file after review; the bridge never writes that original file.


## Whole-document HTTP contract

Legacy single-image request bodies and persisted reviews remain supported unchanged. New document requests use:

```json
{
  "title": "Design review",
  "pages": [
    {"imageBase64": "…", "width": 1404, "height": 1872},
    {"imageBase64": "…", "width": 1404, "height": 1872}
  ]
}
```

`source` is optional; when present it contains Markdown source metadata described above. A document contains 1–200 pages. Metadata returns `pageCount` and `pages`, each with one-based `pageIndex`, dimensions, image URL and optional source. Top-level dimensions, image URL and source still describe page 1. Images are not embedded in metadata. `GET /api/reviews/:id/image?page=N` fetches a page; omitted page means 1. Invalid/out-of-range indices fail with 400.

Send document feedback once:

```json
{
  "submissionId": "stable-unique-id",
  "note": "Overall comments",
  "pages": [
    {"pageIndex": 1, "compositeBase64": "…", "strokes": [], "note": "Page comment"},
    {"pageIndex": 2, "compositeBase64": "…", "strokes": [], "note": ""}
  ]
}
```

Every page must appear exactly once, in order, even when unannotated. Each composite and stroke set is validated against that page's own dimensions. Missing/reordered/invalid pages leave the entire review pending; no partial submission is saved. Store commits all pages atomically. A repeated submission ID is accepted only when its normalized payload is identical; clients must retain/freeze the original payload if acknowledgement is lost. Legacy reviews continue to require the original top-level `compositeBase64`/`strokes` feedback body.

Feedback retrieval returns `pages` with composites, strokes, notes and server-owned source metadata. The 24 MiB JSON body cap applies to the whole document, including images and strokes. Oversized fixed-length or chunked uploads return an explicit 413 limit error, never silently omit pages. MCP checks request size before upload. Split oversized documents into separate source files/reviews.

GET /api/queue lists pending review metadata and positions. GET /api/feedback-inbox lists unread submitted review IDs, source and optional originating task/workspace. POST /api/reviews/:id/acknowledge requires the exact submissionId and marks it read without deleting history. Reading feedback alone does not acknowledge it. No supported idle-desktop wake connection is currently available.

POST /api/reviews/:id/activate requires expectedCurrentReviewId, either the last observed active ID or null when none. Only pending reviews can activate. Stale switching returns409; retrying the already active target succeeds. Activation never deletes or submits a review. Queue positions retain insertion order; activeReviewId identifies selection.

When no pending review remains, GET /api/reviews/current returns {review:null} and /api/queue returns an empty list. Startup normalizes terminal current pointers from earlier releases; submitted history remains retrievable.
