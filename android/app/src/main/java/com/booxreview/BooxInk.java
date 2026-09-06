package com.booxreview;

import android.content.*;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;
import java.util.ArrayList;
import java.util.Collections;

/** Owns the BOOX raw ink plane; application strokes remain the export source of truth. */
final class BooxInk {
  private final MainActivity activity;
  private final MainActivity.InkView view;
  private TouchHelper helper;
  private boolean failed, fingerGesture, panelOpen, closed;
  private volatile boolean enabled;
  private volatile boolean capabilityReady, observedRawCallbacks;
  private boolean screenOff;
  private boolean backingRepaintPending;
  private volatile boolean drawing;
  private final RawStrokeBuffer buffer = new RawStrokeBuffer();
  private float strokeScale, strokeDx, strokeDy, maxPressure = 4096;

  BooxInk(MainActivity activity, MainActivity.InkView view) {
    this.activity = activity;
    this.view = view;
    IntentFilter filter = new IntentFilter();
    filter.addAction("com.android.systemui.SYSTEM_UI_DIALOG_OPEN_ACTION");
    filter.addAction("com.android.systemui.SYSTEM_UI_DIALOG_CLOSE_ACTION");
    filter.addAction(Intent.ACTION_SCREEN_OFF);
    filter.addAction(Intent.ACTION_SCREEN_ON);
    activity.registerReceiver(receiver, filter);
  }

  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
          String action = intent.getAction();
          if (Intent.ACTION_SCREEN_OFF.equals(action)) screenOff = true;
          else if (Intent.ACTION_SCREEN_ON.equals(action)) screenOff = false;
          else panelOpen = !"com.android.systemui.SYSTEM_UI_DIALOG_CLOSE_ACTION".equals(action);
          if (panelOpen || screenOff) suspend();
          else refresh();
        }
      };

  boolean activeStroke() {
    return drawing || buffer.hasPending();
  }

  boolean available() {
    return helper != null && !failed && capabilityReady;
  }

  String label() {
    return available() ? "BOOX native ink" : "Canvas ink";
  }

  void refresh() {
    if (closed || failed || activity.destroyed) return;
    if (activity.stopped
        || !activity.hasWindowFocus()
        || activity.dialogOpen
        || panelOpen
        || screenOff
        || view.page == null
        || view.locked
        || fingerGesture) {
      suspend();
      return;
    }
    if (view.getWidth() == 0 || view.getHeight() == 0 || drawing) return;
    try {
      if (helper == null) {
        if (!(Build.MANUFACTURER + " " + Build.BRAND).toLowerCase().matches(".*(onyx|boox).*")) {
          failed = true;
          return;
        }
        if (!BooxApplication.compatibilityReady
            || EpdController.getEpdWidth() <= 0
            || EpdController.getEpdHeight() <= 0)
          throw new IllegalStateException("Vendor display APIs unavailable");
        helper = TouchHelper.create(view, TouchHelper.FEATURE_SF_TOUCH_RENDER, callback);
        helper.setTouchListenerEnabled(false);
        helper.setPostInputEvent(false);
        helper.enableFingerTouch(false);
        helper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN);
        helper.setStrokeColor(android.graphics.Color.BLACK);
        maxPressure = EpdController.getMaxTouchPressure();
        if (maxPressure <= 0) maxPressure = 4096;
        helper.setPenUpRefreshEnabled(false);
      }
      Rect visible =
          new Rect(
              Math.max(0, (int) Math.ceil(view.dx + view.bounds.x * view.scale)),
              Math.max(0, (int) Math.ceil(view.dy + view.bounds.y * view.scale)),
              Math.min(
                  view.getWidth(),
                  (int) Math.floor(view.dx + (view.bounds.x + view.bounds.width) * view.scale)),
              Math.min(
                  view.getHeight(),
                  (int) Math.floor(view.dy + (view.bounds.y + view.bounds.height) * view.scale)));
      if (visible.isEmpty()) {
        suspend();
        return;
      }
      RectF mapped = EpdController.mapToRawTouchPoint(view, new RectF(visible));
      android.graphics.Matrix rawMatrix = EpdController.getRawTouchPointToScreenMatrix();
      if (rawMatrix == null || !rawMatrix.invert(new android.graphics.Matrix()))
        throw new IllegalStateException("Native ink coordinate matrix unavailable");
      if (mapped == null
          || mapped.isEmpty()
          || !Float.isFinite(mapped.left)
          || !Float.isFinite(mapped.top)
          || !Float.isFinite(mapped.right)
          || !Float.isFinite(mapped.bottom))
        throw new IllegalStateException("Native ink mapped region empty");
      helper.setLimitRect(visible, Collections.emptyList());
      if (!helper.isRawDrawingCreated()) {
        helper.openRawDrawing();
        if (!helper.isRawDrawingCreated())
          throw new IllegalStateException("Native reader did not open");
        Log.i(
            "BooxInk",
            "Native geometry ready, awaiting raw callback; region="
                + mapped
                + " maxPressure="
                + maxPressure);
      }
      helper.setStrokeWidth(3f * view.scale);
      capabilityReady = true;
      helper.setRawDrawingRenderEnabled(true);
      if (!enabled) {
        helper.setRawDrawingEnabled(true);
        enabled = true;
      }
    } catch (Throwable error) {
      fail(error);
    }
  }

  void suspend() {
    if (helper == null) {
      flush();
      return;
    }
    try {
      helper.setRawDrawingEnabled(false);
      enabled = false;
      // Preserve a partially received stroke if focus disappears before pen-up.
      if (drawing) commit();
      flush();
    } catch (Throwable error) {
      fail(error);
    }
  }

  private void fail(Throwable error) {
    failed = true;
    capabilityReady = false;
    enabled = false;
    Log.e("BooxInk", "Native ink unavailable; using Canvas", error);
    try {
      if (helper != null) helper.closeRawDrawing();
    } catch (Throwable ignored) {
    }
    helper = null;
    if (drawing) commit();
    activity.updateUi(
        () -> activity.status.setText("Canvas ink fallback: " + error.getClass().getSimpleName()));
  }

  void close() {
    suspend();
    closed = true;
    try {
      if (helper != null) helper.closeRawDrawing();
    } catch (Throwable ignored) {
    }
    helper = null;
    try {
      activity.unregisterReceiver(receiver);
    } catch (IllegalArgumentException ignored) {
    }
  }

  /** True means SDK owns the stylus; never also append Android pen events. */
  boolean handleTouch(MotionEvent event) {
    if (!available()) return false;
    boolean pen = false, finger = false;
    for (int i = 0; i < event.getPointerCount(); i++) {
      int tool = event.getToolType(i);
      finger |= tool == MotionEvent.TOOL_TYPE_FINGER;
      pen |= tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      view.swipe.cancel();
      suspend();
      fingerGesture = false;
      view.suppressNavigation = false;
      view.post(this::refresh);
      return true;
    }
    if (drawing) {
      if (finger) view.swipe.cancel();
      if (finger) view.suppressNavigation = event.getActionMasked() != MotionEvent.ACTION_UP;
      return true;
    }
    if (pen) return true;
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      fingerGesture = true;
      suspend();
    }
    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
      fingerGesture = false;
      view.post(this::refresh);
    }
    return false; // ordinary fingers use the application's existing pan/pinch path
  }

  private synchronized void begin(TouchPoint point, boolean erasing) {
    if (!enabled || closed || view.locked || activity.stopped || activity.dialogOpen) return;
    if (!observedRawCallbacks) {
      observedRawCallbacks = true;
      Log.i("BooxInk", "First physical raw callback observed");
    }
    if (drawing) commit();
    drawing = true;
    strokeScale = view.scale;
    strokeDx = view.dx;
    strokeDy = view.dy;
    buffer.begin(erasing, strokeScale);
    append(point);
  }

  private void append(TouchPoint point) {
    if (point == null || view.page == null) return;
    float x = (point.x - strokeDx) / strokeScale, y = (point.y - strokeDy) / strokeScale;
    if (!view.bounds.contains(x, y)) return;
    buffer.add(new float[] {x, y, Math.max(.1f, Math.min(1f, point.pressure / maxPressure))});
  }

  private synchronized void move(TouchPoint point) {
    if (drawing) append(point);
  }

  private synchronized void replace(TouchPointList list) {
    if (!drawing || list == null || list.isEmpty() || view.page == null) return;
    ArrayList<float[]> points = new ArrayList<>();
    for (TouchPoint point : list.getPoints()) {
      float x = (point.x - strokeDx) / strokeScale, y = (point.y - strokeDy) / strokeScale;
      if (view.bounds.contains(x, y))
        points.add(new float[] {x, y, Math.max(.1f, Math.min(1f, point.pressure / maxPressure))});
    }
    buffer.replace(points);
  }

  private synchronized void commit() {
    if (!drawing) return;
    buffer.finish();
    drawing = false;
    activity.updateUi(this::flush);
  }

  private void flush() {
    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
      activity.updateUi(this::flush);
      return;
    }
    RawStrokeBuffer.Stroke captured;
    boolean changed = false, erased = false;
    while ((captured = buffer.poll()) != null) {
      final RawStrokeBuffer.Stroke result = captured;
      if (result.erase) {
        erased = true;
        float radius = 22f / result.scale;
        view.strokes.removeIf(
            stroke -> {
              for (float[] existing : stroke.points)
                for (float[] point : result.points)
                  if (Math.hypot(existing[0] - point[0], existing[1] - point[1]) < radius)
                    return true;
              return false;
            });
      } else if (!result.points.isEmpty()) {
        MainActivity.Stroke stroke = new MainActivity.Stroke();
        stroke.width = 3f;
        stroke.points.addAll(result.points);
        view.strokes.add(stroke);
      }
      changed = true;
      Log.d(
          "BooxInk",
          "Committed raw stroke points=" + result.points.size() + " eraser=" + result.erase);
    }
    if (!changed) return;
    activity.queueDraftSave();
    // Raw plane already contains these pixels. Keep its input active across rapid strokes.
    // Explicit navigation/undo/dialog/zoom suspends it and redraws this retained model.
    if (erased) backingRepaintPending = true;
    if (backingRepaintPending || !enabled) view.invalidate();
  }

  void onBackingDrawn() {
    if (!backingRepaintPending || drawing || helper == null) return;
    // Wait until the retained Canvas frame has finished before requesting panel repaint.
    view.postOnAnimation(
        () -> {
          if (!backingRepaintPending
              || drawing
              || helper == null
              || closed
              || !enabled
              || activity.stopped
              || !activity.hasWindowFocus()
              || activity.dialogOpen
              || panelOpen
              || screenOff) return;
          try {
            EpdController.handwritingRepaint(
                view, new Rect(0, 0, view.getWidth(), view.getHeight()));
            backingRepaintPending = false;
          } catch (Throwable error) {
            fail(error);
          }
        });
  }

  private final RawInputCallback callback =
      new RawInputCallback() {
        public void onBeginRawDrawing(boolean b, TouchPoint p) {
          begin(p, false);
        }

        public void onEndRawDrawing(boolean b, TouchPoint p) {
          commit();
        }

        public void onRawDrawingTouchPointMoveReceived(TouchPoint p) {
          move(p);
        }

        public void onRawDrawingTouchPointListReceived(TouchPointList p) {
          replace(p);
        }

        public void onBeginRawErasing(boolean b, TouchPoint p) {
          try {
            if (helper != null) helper.setRawDrawingRenderEnabled(false);
          } catch (Throwable error) {
            fail(error);
          }
          begin(p, true);
        }

        public void onEndRawErasing(boolean b, TouchPoint p) {
          commit();
          try {
            if (helper != null) helper.setRawDrawingRenderEnabled(true);
          } catch (Throwable error) {
            fail(error);
          }
        }

        public void onRawErasingTouchPointMoveReceived(TouchPoint p) {
          move(p);
        }

        public void onRawErasingTouchPointListReceived(TouchPointList p) {
          replace(p);
        }

        public void onPenUpRefresh(RectF area) {
          // The final list/end callbacks own commit. Never refresh ahead of that commit.
        }
      };
}
