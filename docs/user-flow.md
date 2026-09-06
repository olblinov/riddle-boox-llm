# Review with your pen

Preferred setup: BOOX and Mac on the same trusted Wi-Fi network. Keep BOOX Review open. The current pairing may still use USB until its bridge URL is changed. Continue using this Codex conversation; BOOX becomes its reading and handwriting surface.

## 1. Ask for a tablet review

In Codex, say: "Send this Markdown document to BOOX for review." For a visual idea, ask for a diagram to annotate.

Codex prepares readable pages. Text stays large. Blank margins on all four sides hold your short notes; no separate comments section.

## 2. Read and mark the pages

Use Previous and Next to browse. Write beside relevant text, circle a value, cross out a sentence, or sketch an alternative. Use arrows to connect notes to their targets.

Example: circle "30 days" and write "90 days" beside it. For a diagram, cross out a connection and draw the replacement.

Pen draws. Fingers pan and zoom. Width restores full-width view. Undo removes your latest stroke. Each page keeps its own ink when you move between pages.

## 3. Send the whole document

Visit every page first. Then tap Send document once, from any page. Single-page legacy reviews show Send.

You can return to earlier pages before sending. Send includes every page and its annotations. If delivery is uncertain, retry the same submission; your draft stays preserved.

## 4. Continue the conversation

After Send, the Mac stores your submitted pages. While Codex is actively waiting, those pages return to this task. A wait call lasts at most 45 seconds; its timeout does not expire or delete your document. If Codex has stopped waiting, say "Collect my BOOX feedback" in this conversation. Codex reads your ink in context and applies clear requested changes to the original Markdown or diagram source.

Unclear handwriting gets a focused clarification. A revised document can come back for another review. Drawing on BOOX alone does not edit the source file; Codex makes that edit after Send.

# What happens underneath

## Preparing the review

The Markdown file stays on your Mac. The local renderer turns it into numbered PNG pages at the tablet's resolution, with 96-pixel margins. It preserves a source snapshot, checksum and line ranges so feedback can be matched to the exact version you saw.

A local bridge stores one pending review. BOOX fetches those pages through the paired connection. USB forwarding connects tablet localhost to the Mac; trusted Wi-Fi is another option.

## Capturing your pen

The Android app caches downloaded page images and draft annotations locally. Once pages are downloaded, you can review them offline. If either device is offline when you send, the app keeps the exact submission for retry when the connection returns. After the Mac confirms receipt, the app clears the active draft; the Mac retains the source pages and submitted feedback. On supported BOOX firmware, the vendor pen SDK draws live ink and reports stroke points. Canvas is the fallback when native initialization fails.

The app stores positions and pressure relative to each source page. Navigation changes your view without moving saved ink. Version 0.4 delivered all four handwritten pages in this review. Your feedback on pen response was "works great"; optical latency was not measured.

## Returning feedback

Send uploads all annotated page images and stroke data as one document submission. A submission ID makes retries repeat the same request without creating another turn.

The MCP connection returns these images to the waiting Codex task. Technically this is a tool result, not a new typed chat message, but it supplies your next conversational input. Send does not independently wake an idle Codex task; if no task is waiting, ask this conversation to collect BOOX feedback.

## Applying edits

Codex inspects every returned page, including drawings and spatial references. It checks whether the source changed since rendering, then reconciles your instructions with that source. Ambiguous marks need clarification; blank pages mean no page-specific comments.

The tablet runs no LLM and needs no model key. Review files pass through your Mac; annotated images enter the Codex model conversation when feedback is collected. Pairing uses a token. Wi-Fi bridge traffic currently uses unencrypted HTTP.

## Reopen a sent document

Tap History on BOOX to browse sent documents, newest first. Open an entry to read its annotated pages with Previous and Next. History is read-only; Back returns to your active review. Cached entries work offline.

Sent history is kept for 90 days by default. Unsent drafts are excluded from expiry. Original Markdown files and explicit archives remain separate. Older submissions start their retention window when first migrated because earlier versions did not record send time.

## Current limits

One review can be pending at a time. Finish or discard it before starting another. An oversized document must be split into review batches.

Markdown images are labelled placeholders and Mermaid stays code in the current renderer. For a visible diagram, request a rendered diagram page. The original diagram source stays with the task for later changes.
