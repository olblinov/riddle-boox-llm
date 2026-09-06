# BOOX review for Codex

Read Codex output and diagrams on a BOOX Note Air2 Plus, write comments with the pen, and press Send. Codex receives the annotated image in the same conversation and continues.

The bridge never calls a model. Codex does the reasoning. Feedback arrives as an MCP tool result, not a synthetic user message. A skill directs Codex to use the tablet for substantive communication while tablet mode is active.

## Start

Requires Node.js 22 or newer. From this repository:

```sh
npm ci
node scripts/install.mjs
```

On macOS the installer registers the `boox` MCP server, links the skill into `~/.agents/skills`, and starts a login service listening on port 4317. It requires access to your Codex configuration and LaunchAgents directory. Restart/reload Codex if the tools do not appear. The repository also exposes the skill through `.agents/skills`.

Download APK from [private releases](https://github.com/olblinov/riddle-boox-llm/releases), or use locally built `outputs/boox-review-debug.apk`. Install it on BOOX. Pair using this Mac's LAN address, port 4317, and the token in `.runtime/token`. Run `node scripts/pairing.mjs` to create a local `outputs/pairing.html` with the address and token. Keep that file private. Both devices need the same trusted network. Browser fallback uses the same bridge URL and token. HTTP LAN traffic is unencrypted; keep it on a trusted network. For USB-only access use `adb reverse tcp:4317 tcp:4317` and `http://127.0.0.1:4317` on Android.

Start a Codex task with:

> Use $boox-review to communicate through my BOOX. Present your proposal and wait for my pen comments.

Pen draws. Fingers navigate. Undo removes the last stroke. Send submits explicitly. Drafts survive interruption; failed requests retain comments. Codex waits in bounded calls and resumes using the same review ID.

## Manual service

```sh
BOOX_HOST=0.0.0.0 npm start
```

Default manual bind is loopback; `BOOX_HOST=0.0.0.0` allows tablet access on LAN. Runtime state lives under `.runtime/` and is excluded from git. Avoid running a second bridge against the same data directory.

## Development

```sh
npm test
bash android/build.sh
```

Native app build instructions and device limitations: [docs/android.md](docs/android.md). Browser behavior: [docs/browser.md](docs/browser.md). Concept and API contract: [docs/design.md](docs/design.md). Verification evidence: [docs/validation.md](docs/validation.md).

The Android client uses platform stylus events and Canvas. BOOX-specific low-latency rendering is not yet integrated; physical-device testing must establish pen latency and refresh quality. No reMarkable takeover code is needed.

## Remove local installation

```sh
launchctl bootout "gui/$(id -u)/com.olblinov.boox-review"
codex mcp remove boox
```

Then remove `~/Library/LaunchAgents/com.olblinov.boox-review.plist` and the `~/.agents/skills/boox-review` symlink. This preserves the repository and private review history.

Source: private repository `olblinov/riddle-boox-llm`.
