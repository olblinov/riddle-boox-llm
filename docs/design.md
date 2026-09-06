# BOOX review for Codex

Codex desktop owns reasoning and conversation. Note Air2 Plus (Android 11) displays durable pages and captures handwritten feedback. Send returns the annotated image through an MCP tool result in the same task. No separate model API/key and no simulated user-message injection.

## Architecture

Node.js local HTTP bridge stores review sessions on disk. Stdio MCP adapter talks to that bridge. Android native app pairs using bridge URL and token, fetches current review, displays a PNG page, draws pen strokes on an overlay, submits composite PNG plus original strokes. Browser client provides an install-free fallback and integration test surface.

Use bounded waits (up to 45 seconds) with resumable review IDs instead of one unbounded tool call. Pending is never approval. Skill continues waiting until feedback, cancellation, or technical failure. Pages remain visible; submission is explicit, never triggered by idle time.

Images are rendered on desktop from constrained text/diagram objects or accepted PNG, so Android has one robust image format. No arbitrary HTML execution. Preserve original image, composite, and strokes separately. One active review per tablet; submitting duplicate feedback is idempotent and stale review IDs cannot overwrite current work.

Local trusted-network pairing token, no public hosting. Runtime secrets and page data ignored by git. Bridge API authenticated; restrictive origin checks and size limits. HTTP over trusted LAN initially; document that traffic is unencrypted, with USB adb reverse/localhost option.

## Shared API contract v1

Bearer token on every /api request. JSON unless image endpoint. Error: {error:string}.
GET /api/health -> {ok:true}
GET /api/reviews/current -> {review:null|{id,title,width,height,status,createdAt,imageUrl}}
GET /api/reviews/:id/image -> image/png (imageUrl is relative URL of this endpoint)
POST /api/reviews -> {title,imageBase64,width,height} -> review metadata (201); only one pending review; conflict 409.
GET /api/reviews/:id -> review metadata
POST /api/reviews/:id/feedback -> {submissionId,compositeBase64,strokes:[{points:[{x,y,pressure}],width}],note?:string} -> {ok:true,reviewId,status:"submitted"}
GET /api/reviews/:id/feedback -> {status:"pending"} or {status:"submitted",reviewId,compositeBase64,strokes,note}
POST /api/reviews/:id/cancel -> {ok:true,status:"cancelled"}
Coordinates are source image pixels. PNG dimensions match review. Browser/native app must persist drafts across reload/restart, retain ink on failed submission, support undo/clear and pan/zoom without drawing by finger. Tablet fetch poll every 2 seconds while idle; never replace active ink silently.

## Delivery and validation

Build native APK; test HTTP lifecycle, auth, stale/duplicate submissions, MCP image result, browser annotation roundtrip. Physical BOOX validation requires tablet connected/reachable. Keep this document and README current. User delegated design decisions and authorized creating tasks and private repository.

## Markdown reviews

`boox_present` accepts `markdown_path` and `expected_sha256`. By default, one review contains every rendered page. Each page preserves its source line range; all share an immutable source snapshot and SHA-256. Optional `markdown_page` retains the legacy single-page workflow.

Android and browser keep per-page ink across navigation and restart. Previous/Next allows review of all pages before one document Send. Submission includes every page exactly once, freezes for identical retries, and commits atomically. Codex reads all returned images and checks current source against the snapshot before editing Markdown. Source remains Markdown; PNG is only the review surface.

## Display decisions

Note Air2 Plus is 1404 × 1872 at 227 ppi, confirmed against [BOOX specifications](https://shop.boox.com/products/noteair2promo) and connected device pixel dimensions. Pages already use the device’s 3:4 aspect ratio. The old full-page fit reduced them to make room for controls, creating side gutters and resampling text.

Default viewing now uses full width with vertical panning for remaining content. The native client filters fractional zooms and keeps the background white. Markdown uses 36-pixel body text, 96-pixel margins on all four sides, and interline annotation space instead of a 240-pixel comment column. Physical e-ink quality still depends on device refresh mode; pixel-perfect screenshots cannot measure ghosting or perceived panel grain.

## Document API extension

See [bridge.md](bridge.md) for the implemented document contract. A document adds a `pages` array to creation and metadata; page images use `image?page=N`. Feedback carries an ordered `pages` array under one `submissionId`. Existing single-page persisted reviews and requests remain supported. The 24 MiB request bound applies to the whole document; oversized documents fail rather than silently truncating or submitting only part.

## Native pen rendering

Version 0.4.0 targets reported pen-down delay. The user approved the app-local Android 11 compatibility exception; native geometry initializes on the connected tablet. Exact scope and validation limits: [native-ink-status.md](native-ink-status.md). Standard Android Canvas redraws do not use BOOX’s direct ink path. Integrate the official TouchHelper SDK for transient panel ink, while finalized points remain in the existing per-page model for persistence and export. Native ink must pause for dialogs, focus loss, locked submissions, page changes and coordinate transforms. Unsupported firmware retains Canvas fallback. Validate activation and lifecycle on Note Air2 Plus; subjective latency requires real pen use because screenshots do not measure panel response.

Margin-note refinement: use an unruled 96-pixel border, about 11 mm at 227 ppi, around generated Markdown and text pages. No dedicated comments section or box. Keep 36-pixel body text; repaginate instead of scaling down. Keep the source/page footer near the bottom edge. Existing review images remain immutable so saved ink stays aligned. Explicit image and scene coordinates remain unchanged; their author reserves the same border.

Sent-document history uses a separate read-only tablet viewer and local image cache. Default retention is 90 days after submission, with pending drafts excluded. Mac history endpoint lists metadata separately from full feedback downloads. See history.md.
