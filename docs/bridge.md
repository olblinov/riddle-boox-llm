# Local bridge and MCP

Node 22+ required. Run `npm ci`, then `npm start`. Entry files are `src/bridge.js` and `src/mcp.js`. Bridge defaults to `127.0.0.1:4317`. To reach it over trusted LAN, set `BOOX_HOST=0.0.0.0`; HTTP is unencrypted. `BOOX_PORT` changes port. Never expose it to public networks.

Runtime directory defaults to project `.runtime`; set `BOOX_DATA_DIR` to override. Pairing token is generated once at `token` with mode 0600; review state and original/composite PNGs are persisted in `state.json` with mode 0600. Runtime directory is ignored by git. Stop bridge before backing up state. Keep one bridge process per data directory.

Stdio MCP: `npm run mcp` or preferably `node /absolute/project/src/mcp.js`. Configure `BOOX_BRIDGE_URL` when not using `http://127.0.0.1:4317`, and `BOOX_TOKEN_PATH` when token isn't in project `.runtime/token`. Stdout belongs exclusively to MCP protocol. HTTP bridge must already be running.

## Tools

- `boox_present`: `title`, and exactly one of `image_path`, `text`, `scene`. Optional `width` and `height` default to 1404×1872. Returns review metadata including `id`. PNG input uses its actual dimensions. Text wraps and refuses overflow rather than silently clipping.
- `boox_wait_feedback`: `review_id`, `wait_seconds` from 0 to 45, default 40. Returns pending/cancelled status, or submitted metadata and PNG image content. Poll same ID again when pending. Pending never means consent.
- `boox_cancel`: `review_id`; cancels pending page, preserving it. Submitted pages cannot be cancelled.
- `boox_status`: bridge health, last tablet/browser current-review polling timestamp, current review. Timestamp shows a recent client poll, not proof of physical device readiness.

Scene is an array of up to 500 objects. Coordinates use image pixels. Types: `text` with x,y,text,size; `rect` with x,y,width,height; `line` or `arrow` with x,y,x2,y2. Black ink and white background. Text is XML-escaped. No raw SVG, HTML, scripts, or external image references are accepted.

API follows design.md. Health additionally returns `lastTabletPollAt`. All `/api` endpoints need Bearer authorization, including images and health. Feedback validates PNG dimensions and stroke coordinates. POST bodies max 24 MiB; PNG base64 max 16 MiB; pages max 16 million pixels and 4096 on either edge. Original and feedback image dimensions must match. Feedback duplicate IDs are accepted only with identical content. Mutations are serialized and atomically persisted. Only one review can remain pending; new review requires prior submission/cancellation. Submitted feedback remains retrievable by review ID.

`npm test` covers authenticated lifecycle, persistence, malformed images/ink, duplicate and stale feedback, MCP pending/image roundtrip, and text rendering. Physical pen latency and Android compatibility require separate device validation.
