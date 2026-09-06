# Sent-document history

BOOX History lists sent documents newest first and opens their annotated pages without editing or resubmitting them. The active review remains separate. Downloaded history is available offline.

Default retention is 90 days from submission. The Mac expires submitted review records on startup, hourly while running, and on history requests. Pending drafts never expire through this policy. Older submissions lack a recorded send time, so their first history migration starts a full 90-day window. Tablet cache follows the same submission dates. Explicit manual archives and original source files are separate from this cache and are not deleted.

GET /api/history requires the pairing token and returns retentionDays plus id, title, submittedAt and pageCount for each retained submission. Existing feedback endpoint provides annotated images and strokes. The history list omits image data; the tablet downloads documents separately. Network failure must retain cached history and pending drafts.
