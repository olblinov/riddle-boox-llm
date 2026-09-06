# Review with your pen

Preferred setup: BOOX and Mac on the same trusted Wi-Fi network. Keep BOOX Review open. Open More > Connect desktop and discover the Mac on Wi-Fi, or enter its LAN address. The existing token verifies the connection; USB remains an alternative. Continue using this Codex conversation; BOOX becomes its reading and handwriting surface.

## 1. Ask for a tablet review

In Codex, say: "Send this Markdown document to BOOX for review." For a visual idea, ask for a diagram to annotate.

Codex prepares readable pages. Text stays large. Blank margins on all four sides hold your short notes; no separate comments section.

## 2. Read and mark the pages

Use Previous and Next, or swipe left/right with one finger in Page view, to browse. Write beside relevant text, circle a value, cross out a sentence, or sketch an alternative. Use arrows to connect notes to their targets.

Example: circle "30 days" and write "90 days" beside it. For a diagram, cross out a connection and draw the replacement.

Pen draws. Opening a document and changing pages uses Fit page, showing the whole source page. Manual zoom lasts until the next page change. Fingers pan and zoom; when zoomed in, horizontal movement pans. More > Fit width restores full-width detail. Zoom out to add notes beyond the original page edges within the surrounding writing area. These notes are included when you send. Undo removes your latest stroke. Each page keeps its own ink when you move between pages.

## 3. Send the whole document

Visit every page first. Then tap Send document once, from any page. Single-page legacy reviews show Send.

You can return to earlier pages before sending. Send includes every page and its annotations. If delivery is uncertain, retry the same submission; your draft stays preserved.

## 4. Continue the conversation

After Send, the Mac stores your submitted pages. While Codex is actively waiting, those pages return to this task. A wait call lasts at most 45 seconds; its timeout does not expire or delete your document. If Codex has stopped waiting, say "Collect my BOOX feedback" in this conversation. Codex reads your ink in context and applies clear requested changes to the original Markdown or diagram source.

Unclear handwriting gets a focused clarification. A revised document can come back for another review. New documents join a queue instead of replacing your current work. Documents shows waiting reviews. Tap any entry to open it; current ink and page position are saved for your return. Sending or discarding a review opens the next waiting one. Retry an unconfirmed Send before switching. Drawing on BOOX alone does not edit the source file; Codex makes that edit after Send.

# What happens underneath

## Preparing the review

The Markdown file stays on your Mac. The local renderer turns it into numbered PNG pages at the tablet's resolution, with 96-pixel margins. It preserves a source snapshot, checksum and line ranges so feedback can be matched to the exact version you saw.

A local bridge stores a persistent queue of up to 100 pending reviews. BOOX fetches those pages through the paired connection. Wi-Fi discovery finds the Mac on the same network. USB forwarding remains an alternative.

## Capturing your pen

The Android app caches downloaded page images and draft annotations locally. Once pages are downloaded, you can review them offline. If either device is offline when you send, the app keeps the exact submission for retry when the connection returns. After the Mac confirms receipt, the app clears the active draft; the Mac retains the source pages and submitted feedback. On supported BOOX firmware, the vendor pen SDK draws live ink and reports stroke points. Canvas is the fallback when native initialization fails.

The app stores positions and pressure relative to each source page. Outside-page notes can have negative coordinates. Expanded image bounds tell Codex exactly where the original page sits, so these notes stay aligned. Navigation changes your view without moving saved ink. Version 0.4 delivered all four handwritten pages in this review. Your feedback on pen response was "works great"; optical latency was not measured.

## Returning feedback

Send uploads all annotated page images and stroke data as one document submission. A submission ID makes retries repeat the same request without creating another turn.

The MCP connection returns these images to the waiting Codex task. Technically this is a tool result, not a new typed chat message, but it supplies your next conversational input. Send does not independently wake an idle Codex task; if no task is waiting, ask this conversation to collect BOOX feedback.

## Applying edits

Codex inspects every returned page, including drawings and spatial references. It checks whether the source changed since rendering, then reconciles your instructions with that source. Ambiguous marks need clarification; blank pages mean no page-specific comments.

The tablet runs no LLM and needs no model key. Review files pass through your Mac; annotated images enter the Codex model conversation when feedback is collected. Pairing uses a token. Wi-Fi bridge traffic currently uses unencrypted HTTP.

## Empty inbox and notifications

After the final queued document is sent or discarded, Empty inbox replaces the editor. Submitted copies remain in History. Empty inbox means no pending tablet review, not that Codex has already processed every submission.

New review notifications are available while the background listener runs and the tablet can reach the Mac. More lets you pause or enable the listener. Notifications open Documents without replacing an unfinished draft. A powered-off tablet, force-stopped app, or BOOX battery restrictions can delay alerts until the app runs again.

## Reopen a sent document

Tap More > Submitted history on BOOX to browse sent documents, newest first. Open an entry to read its annotated pages with Previous and Next. History is read-only; Back returns to your active review. Cached entries work offline.

Sent history is kept for 90 days by default. Unsent drafts are excluded from expiry. Original Markdown files and explicit archives remain separate. Older submissions start their retention window when first migrated because earlier versions did not record send time.

## Current limits

One review is active at a time; up to 100 documents can wait in the queue. Finish or discard the active document to advance. An oversized document must be split into review batches.

Local Markdown raster images and Mermaid diagrams render as visible figures. Local images must stay inside the document folder. Remote images, SVG, and table-cell images remain labelled placeholders. Mermaid needs Chrome installed on the Mac. The original diagram source stays with the task for later changes.
