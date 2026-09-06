# Browser pen companion

Open the bridge URL and enter its pairing token. Browser fallback uses the same authenticated API as Android. Token stays in session storage; page metadata, original-coordinate strokes, typed note, and submission ID persist in local storage. The original PNG is reloaded from bridge after restart.

Pen draws. Finger scrolls without drawing; mouse drawing requires the checkbox. Zoom changes display size without changing source coordinates. Undo removes the last stroke. Clear asks before removing all ink. Send submits full-resolution composite PNG plus original vector strokes and optional typed note. Failed sends preserve draft and submission ID for idempotent retry.

A new review never silently replaces unsent ink. User can explicitly discard and open current page. Successful submission leaves annotated page visible until another review arrives.

Uses vanilla browser APIs, no remote dependencies. Native BOOX app remains preferred for pen latency. Browser pressure and pointer delivery depend on browser/device firmware. HTTP pairing is for trusted local networks; use USB localhost forwarding when appropriate. Local browser storage is not encrypted and should only be used on the user's device.

Physical BOOX stylus latency and palm rejection require device testing. Browser supports finger scrolling, not pinch zoom; explicit zoom selector provides enlargement.

## Verified 2026-09-06

Actual in-app browser against isolated bridge on port 4318: pairing, review rendering, mouse-driven ink capture, typed comment, page reload restoring ink/comment, explicit submission. Server received one stroke with nine points and a 1000 × 1200 PNG matching source dimensions. Stopping bridge caused Send failure without losing comment; restarting bridge and retrying succeeded. Test fixtures live under ignored work/browser-test-runtime, separate from user runtime.

## Uncertain submission recovery

Before the first feedback POST, persist the exact JSON request body, including composite PNG and submission ID. If storage cannot persist it, do not send. Once attempted, pen, note, undo, clear, and page replacement remain locked until delivery is confirmed; the Send control becomes Retry same comments. Retrying sends identical bytes, including after browser reload. A newer current review cannot replace this unresolved submission. After confirmed success, the next review may open normally.

Regression simulation runs actual app code against a mock DOM/fetch: server accepts first body but loses ACK; editor locks, frozen body survives reload, newer review does not replace it, identical retry succeeds. This supplements the actual browser round-trip and connection-loss tests above.

## Whole-document review

Reviews with `pages` show Previous, Next, page number, and visited count. Ink and typed comments belong to each page and survive navigation/reload. The final Send all pages action stays disabled until every page has been opened. It submits every page in source order, including pages without annotations, in one request. Legacy single-page reviews keep their original payload shape.

Viewing a page marks it visited; this does not claim the user read every word. The document remains one review. Unresolved frozen submissions cannot be replaced by a newer review, even after reload. A 24 MiB UTF-8 request-size check rejects oversized documents before sending and preserves editable comments. Browser storage capacity may impose a smaller limit; inability to persist the exact request prevents submission safely.

Viewport uses full width with white background and no side gutters. Pages fit width, not whole-page height; vertical scrolling and explicit zoom preserve readability. Ink canvas uses at least source resolution and increases to physical display resolution on high-DPI screens. Export renders strokes directly in original image coordinates. Browser cannot restore detail absent from the source PNG.

Automated browser-state tests cover per-page ink/note isolation and reload, visiting all pages before send, ordered one-request submission, high-DPI backing resolution, oversized payload rejection, and exact lost-ACK retry for both legacy and multipage reviews.

Decoded image cache retains at most three pages and revokes evicted object URLs while protecting the displayed page. Submission loads pages sequentially. The visible ink canvas caps its backing raster near eight million pixels, even at high zoom/DPI; original-coordinate export remains unchanged.

Actual multipage browser acceptance passed against isolated port 4319: annotated page one and page two, navigated back, reloaded, verified both drafts, then sent once. Server returned submitted with ordered pages 1 and 2, one stroke each, matching per-page comments, and two 1000 × 1200 composite PNGs. Physical BOOX browser stylus behavior is still untested.
