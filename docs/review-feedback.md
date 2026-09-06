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

## Follow-up submitted documents

Inspected all nine pages across receipt fe751d1a-1ec6-431a-b26e-bc46a0025106, guide15314a02-8468-4b38-8151-379e18d4e15d, and samplefab644eb-0542-4be9-860c-b36f5242787a. Exact submissions acknowledged after inspection; images and metadata archived locally.

Receipt: "Great! no comments." Guide: request Android notifications for new review documents. Sample: default view has too much extra canvas padding; show source without added framing, expose extra writable space when zooming out. Final sample note: "Gestures work great!" No schedule or diagram content changes requested.

Typed follow-up requests Empty inbox when last queued document is handled, clearer submitted History, and commits. Implemented behavior and validation recorded with release0.8.

## Routing document feedback, 2026-09-07

Inspected all seven pages of review 36a92dd5-8a66-4355-a657-a2ef56d660d2, submission ff820021-28ba-4065-abd1-e7c6d9a202f1. User accepts manual continuation and shared storage; automatic wake is out of scope. Requested minimal waiting, explanation of which call waits and where the bridge lives, instructions for new tasks, queue removal, and a fix for initially missing or grey pen strokes. Page 3 contained no ink. Updated the source against its unchanged reviewed checksum and retained the original archive.
