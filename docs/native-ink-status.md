# Native pen rendering 0.4.0

Version 0.4.0 is installed on the Note Air2 Plus, Android 11. It uses the official BOOX TouchHelper SDK for live panel ink and retains raw stroke points for document annotations. Canvas remains the fallback when native capability checks fail.

## Compatibility scope

The user explicitly approved an app-local compatibility exception on 2026-09-06. Before SDK initialization, BooxApplication applies pinned HiddenApiBypass 4.3 on BOOX Android 11 or later. Exemptions cover exactly four classes: android.onyx.ViewUpdateHelper, android.view.View, android.view.Surface, and android.view.SurfaceControl. Each prefix permits hidden members of that class. There is no wildcard exemption, root, device-wide policy change, target-SDK reduction, or change to another app.

[BOOX's official DemoApplication](https://github.com/onyx-intl/OnyxAndroidDemo/blob/689ff7f8c4ea971b1b436e5c967c921dc8596f3a/app/OnyxPenDemo/src/main/java/com/onyx/android/eink/pen/demo/DemoApplication.java) uses a broader empty-prefix exception. [HiddenApiBypass documentation](https://github.com/LSPosed/AndroidHiddenApiBypass#usage) describes prefix scoping. This integration depends on firmware-private APIs and may need adjustment after firmware updates.

## Evidence and limits

Twenty automated bridge, MCP, browser and Markdown tests pass. RawStrokeBufferTest passes rapid consecutive strokes, authoritative final points, copied arrays, transform metadata, pause flushing and duplicate-end cases.

The connected tablet reports successful app-local compatibility, nonempty mapped native geometry, an invertible coordinate transform and maximum pressure 4095. Isolated three-page navigation, pairing dialog dismissal and process restart preserve page/visited state and restore native geometry without an AndroidRuntime crash. The production upgrade restores the existing document; persisted bridge state is unchanged.

Actual physical-pen raw callbacks, eraser appearance, palm behavior and optical pen latency remain unverified in 0.4.0. Injected Android MotionEvents do not reach BOOX's native raw reader and cannot establish these results. The SDK logs its first physical raw callback separately from geometry readiness. Real handwriting is the next acceptance check.
