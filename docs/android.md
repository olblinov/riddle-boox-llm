# Android companion

Native Java Android app for Note Air2 Plus, Android 11. Minimum Android 9/API 28. No model keys or third-party runtime dependencies. Uses standard Android stylus events and Canvas; physical BOOX pen latency and e-ink refresh quality are not yet measured. No proprietary BOOX SDK is bundled.

## Use

Install `outputs/boox-review-debug.apk`. Open BOOX Review, tap Pair, enter the bridge URL and token. Connect through trusted Wi-Fi, or use `adb reverse tcp:PORT tcp:PORT` and `http://127.0.0.1:PORT`. HTTP LAN traffic is unencrypted. Token is stored in app-private preferences; backups are disabled.

Write with stylus. Fingers pan, pinch zoom. Width restores full-width view from the top. Pages retain their source resolution; vertically pan to reach content below the toolbar. Fractional zoom uses bitmap filtering; a 1404-pixel-wide page displays at native scale on the 1404-pixel-wide tablet. White canvas removes gray side gutters. Eraser end removes intersecting strokes. Undo removes last stroke; Clear asks before removing ink. Previous and Next browse the document with a page indicator. Ink is independent per page. Visit every page; Send document becomes available from any page once all pages have been visited and submits every page once, in order, as PNG composites plus original strokes. Legacy single-page reviews still use Send. On network failure, draft remains locked to the exact submitted content; Send retries with the same submission ID, including after restart. This prevents an uncertain network result from silently changing feedback. Draft persists after each completed stroke, before/after page navigation, and when app pauses. Current page and visited-page state survive process restart. Original PNGs cache on disk; only current page bitmap is decoded for display. The first submission serializes a frozen request to app-private submission.json for unchanged retries. Requests larger than 24 MiB are rejected locally with the draft retained and editable. Other known preflight failures also unlock when no frozen request or prior uncertain submission exists; network uncertainty keeps the exact request locked. Clear also offers Discard review, which cancels the server review before deleting local draft.

## Build

Set `JAVA_HOME` to JDK 17 and `ANDROID_SDK_ROOT` to an SDK containing `platforms;android-35` and `build-tools;35.0.0`. Run `bash android/build.sh`. It also discovers project-local tools under `work/android-tools`. Output is a development-signed APK, not a Play Store release. Build uses aapt2, javac, d8, zipalign and apksigner directly, avoiding Gradle dependency downloads.

## Device verification

Basic physical pairing, Markdown rendering, pen capture and Send round trip passed. Whole-document navigation, vertical pan and draft restart recovery also passed with injected stylus events as recorded below. Further checks remain for eraser, palm rejection, pinch zoom, failed submission recovery, and refresh ghosting. Standard Android rendering may lag BOOX Notes. USB debugging must be enabled by device owner before adb installation.

## Validation completed

Development APK built successfully using JDK 17, Android platform 35 and build-tools 35.0.0. `apksigner verify --verbose` passed with v3 signing; `aapt2 dump badging` confirmed package `com.booxreview`, minimum SDK 28, target SDK 30 and launchable activity. Initial build had no connected device; subsequent physical installation and basic pen checks passed as recorded below.

Review fixes: stylus input tracks pointer IDs independently of pointer index. Palm/finger gestures are suppressed throughout a pen stroke and until remaining contacts lift. Pointer-up and cancellation end stroke state; Width/Undo/Clear/Send cannot alter an active stroke. Failed discard preserves an uncertain submission's lock. Activity destruction cancels handlers, shuts down its worker, disconnects active HTTP, and ignores late UI callbacks. Java source formatted with google-java-format. Rebuilt APK passed signature verification after these changes; physical multi-pointer behavior remains untested.

Physical Note Air2 Plus install, launch, USB pairing, Markdown display and real pen submission passed on 2026-09-06. BOOX froze the initial install; `adb shell pm enable com.booxreview` resolved launch. Enable USB Debug Mode through Apps > top-right menu > App Management. `adb reverse tcp:4317 tcp:4317` connects tablet localhost to Mac; repeat forwarding after USB reconnection.

## Document update 0.3.0

Version code 3 adds whole-document browsing and full-width rendering. Existing legacy draft.json/page.png and submission ID are read without deleting or replacing the pending review. Navigation is blocked during active stylus input and network submission. If draft restoration fails, automatic saving stays disabled to preserve the stored draft.

`bash android/build.sh --test` builds `work/boox-review-test.apk` with package `com.booxreview.test` and independent preferences/drafts for side-by-side QA. Production remains `com.booxreview` at `outputs/boox-review-debug.apk`; test build never overwrites that APK. Both 0.3.0 variants passed APK signature and manifest/version verification. Physical three-page acceptance passed in the isolated test app: explicit synthetic stylus strokes on each page survived navigation and force-stop/relaunch, then one Send from page one returned all three 1404 × 1872 composites with one 32-point stroke each. Full-width screenshot and vertical finger pan verified readable layout and reachable footer. This verifies native event handling and persistence; it does not measure handwriting latency or panel grain.
