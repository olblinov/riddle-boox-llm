# Android companion

Native Java Android app for Note Air2 Plus, Android 11. Minimum Android 9/API 28. No model keys or third-party runtime dependencies. Uses standard Android stylus events and Canvas; physical BOOX pen latency and e-ink refresh quality are not yet measured. No proprietary BOOX SDK is bundled.

## Use

Install `outputs/boox-review-debug.apk`. Open BOOX Review, tap Pair, enter the bridge URL and token. Connect through trusted Wi-Fi, or use `adb reverse tcp:PORT tcp:PORT` and `http://127.0.0.1:PORT`. HTTP LAN traffic is unencrypted. Token is stored in app-private preferences; backups are disabled.

Write with stylus. Fingers pan, pinch zoom. Fit restores whole-page view. Eraser end removes intersecting strokes. Undo removes last stroke; Clear asks before removing ink. Send explicitly submits a PNG composite and original strokes, then waits for next page. On network failure, draft remains locked to the exact submitted content; Send retries with the same submission ID, including after restart. This prevents an uncertain network result from silently changing feedback. Draft persists after each completed stroke and when app pauses. Clear also offers Discard review, which cancels the server review before deleting local draft.

## Build

Set `JAVA_HOME` to JDK 17 and `ANDROID_SDK_ROOT` to an SDK containing `platforms;android-35` and `build-tools;35.0.0`. Run `bash android/build.sh`. It also discovers project-local tools under `work/android-tools`. Output is a development-signed APK, not a Play Store release. Build uses aapt2, javac, d8, zipalign and apksigner directly, avoiding Gradle dependency downloads.

## Device verification

Basic physical pairing, Markdown rendering, pen capture and Send round trip passed. Further checks remain for eraser, palm rejection, pan/zoom, draft restart recovery, failed submission recovery, and refresh ghosting. Standard Android rendering may lag BOOX Notes. USB debugging must be enabled by device owner before adb installation.

## Validation completed

Development APK built successfully using JDK 17, Android platform 35 and build-tools 35.0.0. `apksigner verify --verbose` passed with v3 signing; `aapt2 dump badging` confirmed package `com.booxreview`, minimum SDK 28, target SDK 30 and launchable activity. Initial build had no connected device; subsequent physical installation and basic pen checks passed as recorded below.

Review fixes: stylus input tracks pointer IDs independently of pointer index. Palm/finger gestures are suppressed throughout a pen stroke and until remaining contacts lift. Pointer-up and cancellation end stroke state; Fit/Undo/Clear/Send cannot alter an active stroke. Failed discard preserves an uncertain submission's lock. Activity destruction cancels handlers, shuts down its worker, disconnects active HTTP, and ignores late UI callbacks. Java source formatted with google-java-format. Rebuilt APK passed signature verification after these changes; physical multi-pointer behavior remains untested.

Physical Note Air2 Plus install, launch, USB pairing, Markdown display and real pen submission passed on 2026-09-06. BOOX froze the initial install; `adb shell pm enable com.booxreview` resolved launch. Enable USB Debug Mode through Apps > top-right menu > App Management. `adb reverse tcp:4317 tcp:4317` connects tablet localhost to Mac; repeat forwarding after USB reconnection.
