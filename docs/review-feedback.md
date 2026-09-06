# Feedback from the four-page guide

Review fd364458-a2ab-4efc-8e54-40981015755a, submitted 2026-09-06. All four annotated images inspected; 258 strokes. Original source and feedback archived locally under .runtime/archive with this review ID. Do not commit handwritten review data.

Confirmed: user reports native writing "works great". Current caching retains offline drafts and uncertain submissions; Mac persists confirmed submissions. Wait timeouts do not expire reviews. No automatic idle-task wakeup exists.

Implemented in 0.6.0:
- Make same-network Wi-Fi the default setup.
- Add finger left/right page swipes without conflicting with pan/zoom or pen.
- Make bottom annotation margin visible and accessible in the default tablet view.
- Support notes outside original page edges when zoomed out, including export.
- Queue multiple documents for review.
- Render Markdown images and Mermaid visibly; handwritten note requests addressing this limitation.
- Durable unread feedback inbox, exact submission acknowledgements and originating-task metadata; timeout/version matching explained.

Remaining technical limitation: automatic wake of an idle Codex desktop task. No supported control socket is exposed by this desktop session. Active waits receive feedback; resume the same task to collect saved unread submissions. See feedback-continuation.md.

Review IDs bind feedback to one document; page indices and source checksums prevent matching it to another version. Preserve this mapping when implementing a queue.
