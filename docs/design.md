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

`boox_present` accepts `markdown_path`, optional 1-based `markdown_page`, and `expected_sha256`. Desktop renderer produces numbered PNG pages with annotation whitespace. Each review persists source path, SHA-256, immutable source snapshot path, page count/index, and source line range. Feedback returns that metadata alongside ink and composite.

Long documents use one explicit Send per page on existing clients. Codex collects all page feedback before modifying the original Markdown, then checks current source against the reviewed snapshot to avoid losing concurrent edits. This requires no Android update. Source remains Markdown; PNG is only the review surface. Rendering never executes document HTML or fetches remote resources.
