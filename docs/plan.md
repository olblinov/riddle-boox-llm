# Implementation plan

- Coordinator: shared contract, Codex skill/setup, private repository, end-to-end verification, current documentation.
- Android task: native pen companion, durable drafts, APK build, device-specific limitations.
- Bridge task: authenticated durable HTTP lifecycle, MCP tools, image rendering, integration tests.
- Browser task: paired browser fallback, pen/mouse annotation, explicit submission, draft recovery.

Completion means locally verified round trip, built installable artifacts, configured Codex integration, private source repository. Physical BOOX validation is separately recorded and cannot be claimed without connected hardware.

Design authority was explicitly delegated by user. No extra design approval gate. Missing optional writing-plans skill does not block this concrete plan.

## Delivery status

Software implementation, native APK build, browser regression, and live Codex MCP image-feedback acceptance complete. Skill and local login service installed. Physical Note Air2 Plus USB, rendering, real pen feedback, clarification, and Markdown adaptation verified. Private repository and prerelease hold source and APK.

Markdown extension complete: paginated rendering, source snapshots/checksums, feedback mapping, skill adaptation workflow, and live source-edit acceptance. The original Markdown extension reused the single-page Android app.

## Display and document update

0.3.0 addresses actual-use feedback: native full-width view, larger Markdown text, narrow margins, and whole-document navigation with one submission. Renderer, bridge/MCP, native client, browser and skill updated together. Legacy drafts remain compatible. Twenty automated tests and real browser multipage acceptance pass; connected BOOX acceptance is recorded in validation.md.

## Native pen latency follow-up

Native SDK candidate and raw capture regression prepared. Actual Android 11 blocks required private vendor APIs; automatic approval review rejected compatibility exception. No exception deployed, production stays0.3.0. Exact status and proposed scope: [native-ink-status.md](native-ink-status.md).
