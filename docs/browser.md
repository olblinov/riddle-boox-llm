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
