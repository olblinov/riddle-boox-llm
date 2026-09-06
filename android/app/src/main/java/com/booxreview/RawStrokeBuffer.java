package com.booxreview;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Thread-safe capture handoff. Finished strokes cannot be replaced by the next raw gesture. */
final class RawStrokeBuffer {
  static final class Stroke {
    final ArrayList<float[]> points;
    final boolean erase;
    final float scale;

    Stroke(ArrayList<float[]> points, boolean erase, float scale) {
      this.points = points;
      this.erase = erase;
      this.scale = scale;
    }
  }

  private ArrayList<float[]> current;
  private boolean erase;
  private float scale;
  private final ArrayDeque<Stroke> pending = new ArrayDeque<>();

  synchronized void begin(boolean erase, float scale) {
    finish();
    current = new ArrayList<>();
    this.erase = erase;
    this.scale = scale;
  }

  synchronized void add(float[] point) {
    if (current != null) current.add(point.clone());
  }

  synchronized void replace(List<float[]> points) {
    if (current == null) return;
    current.clear();
    for (float[] point : points) current.add(point.clone());
  }

  synchronized void finish() {
    if (current == null) return;
    pending.add(new Stroke(current, erase, scale));
    current = null;
  }

  synchronized Stroke poll() {
    return pending.poll();
  }

  synchronized boolean hasPending() {
    return !pending.isEmpty();
  }
}
