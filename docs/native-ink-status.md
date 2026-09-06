# Native pen latency: blocked on compatibility approval

Current production tablet and released APK remain 0.3.0. The 0.4.0 native-ink candidate is not released. The user reported noticeable pen-down delay; standard Android Canvas redraw is the current display path.

Prepared: official BOOX TouchHelper integration, per-page source-coordinate capture, an immutable queue for consecutive raw strokes, gesture/lifecycle guards, pinned SDK artifacts and native libraries, and Canvas fallback. Twenty existing tests plus the production RawStrokeBuffer regression pass.

Physical Note Air2 Plus, Android 11: SDK classes/native libraries load, but Android denies reflection into required vendor APIs such as android.onyx.ViewUpdateHelper.mapToRawTouchPoint and getEpdToViewMatrix. The SDK reports an empty mapped drawing region. An earlier "opened" log was insufficient evidence. The candidate now checks mapped geometry and falls back instead of consuming pen events with an unusable native engine. Native callback delivery and optical latency are NOT verified.

## Proposed approval

Permit an app-local hidden-API compatibility exception needed by the BOOX pen SDK. Try the narrowest working vendor-class scope first. The vendor demo uses a broad exception that relaxes restrictions for the whole app process; a working minimum for this firmware is not yet verified. No root, global hidden-API policy change, permissions grant, or modification of other apps is proposed. This relaxes Android's unsupported-API restrictions inside BOOX Review and may be firmware-sensitive.

[BOOX's official DemoApplication](https://github.com/onyx-intl/OnyxAndroidDemo/blob/689ff7f8c4ea971b1b436e5c967c921dc8596f3a/app/OnyxPenDemo/src/main/java/com/onyx/android/eink/pen/demo/DemoApplication.java) uses HiddenApiBypass on Android 11 and later. Its example applies a broad empty-prefix exception. [The library documentation](https://github.com/LSPosed/AndroidHiddenApiBypass#usage) supports narrower class/package prefixes. A working minimum for this firmware has not been established.

Automatic approval review rejected adding an exception because user authorization did not explicitly cover its security impact. No exception has been installed or executed on the tablet. Briefly prepared exemption source/dependency was removed; current source and safe test build contain none. Continue only after explicit approval, then validate raw callbacks, rapid strokes, eraser/render reconciliation, dialogs, pause/resume, page changes and actual pen latency before releasing.
