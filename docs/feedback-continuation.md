# Feedback continuation

BOOX Send commits feedback durably. While Codex is waiting, the MCP tool returns that feedback to the same task. A bounded wait timeout does not cancel the review or discard comments. An uncollected inbox lets a later turn recover feedback by review ID.

Automatic wake of an idle desktop task is out of scope by user decision. Default collection is an immediate check on a later user-triggered turn; repeated MCP waiting is disabled unless explicitly requested. The current stdio MCP connection has no verified mechanism to start a host turn. Local inspection found no default app-server control socket; the desktop's separate IPC socket is not a documented substitute. No alternate daemon, private IPC protocol, or fabricated user message is used.

The [official app-server protocol](https://learn.chatgpt.com/docs/app-server) supports `turn/start` with empty `input` and `toolOutput`. It can start generation, or queue tool output into an active regular turn. The installed CLI exposes `app-server proxy` for an existing control socket, but attachment to this desktop instance remains unverified. A separate app-server process is not proof of attachment to the live desktop task.

## Out-of-scope adapter reference

- Persist the originating host ID, thread ID, workspace path, and review ID when presenting a review. Never route by latest task, title, or current tablet page.
- Create a durable delivery record keyed by review ID and submission ID after explicit Send commits. Keep delivery acknowledgement separate from feedback collection and application of edits.
- Connect only to a verified existing supported endpoint. Read the exact thread and verify its workspace before sending actual tool output; preserve model and permission settings.
- Include a stable delivery marker and the matching review/source identity. Fetch annotated images through the authenticated bridge; never log pairing credentials.
- Record the accepted turn ID. After an ambiguous transport failure, reconcile the marker before retrying. The reviewed protocol documents no server idempotency key, so exactly-once delivery cannot be promised.
- Keep feedback recoverable when attachment is unavailable. Do not silently start another daemon, use private desktop IPC, or mark uncertain delivery successful.

[Scheduled tasks](https://learn.chatgpt.com/docs/automations?surface=app) provide polling when explicitly configured, not a general BOOX webhook. Local scheduled work also requires the computer and desktop app to remain running.
