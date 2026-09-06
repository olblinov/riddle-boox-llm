package com.booxreview;

import java.util.Arrays;

public final class RawStrokeBufferTest {
  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    RawStrokeBuffer buffer = new RawStrokeBuffer();
    buffer.begin(false, 1f);
    buffer.add(new float[] {1, 2, .5f});
    buffer.add(new float[] {3, 4, .6f});
    float[] last = {5, 6, .7f};
    buffer.replace(Arrays.asList(new float[] {1, 2, .5f}, last));
    last[0] = 999;
    buffer.finish();
    // Another physical gesture arrives before the UI gets to drain the first.
    buffer.begin(false, 2f);
    buffer.add(new float[] {8, 9, .8f});
    buffer.finish();
    check(buffer.hasPending(), "Pending commits must block navigation/export");
    RawStrokeBuffer.Stroke first = buffer.poll(), second = buffer.poll();
    check(first.points.size() == 2, "Final cumulative list replaces provisional moves");
    check(first.points.get(1)[0] == 5, "SDK-owned arrays must be copied");
    check(second.points.size() == 1 && second.points.get(0)[0] == 8, "Rapid stroke not dropped");
    check(first.scale == 1 && second.scale == 2, "Transform metadata stays with stroke");
    check(!buffer.hasPending(), "Queue drains exactly once");
    buffer.begin(true, 1.5f);
    buffer.add(new float[] {10, 11, .4f});
    buffer.finish(); // pause flush before final-list callback
    buffer.finish(); // duplicate end after suspend
    RawStrokeBuffer.Stroke paused = buffer.poll();
    check(paused.erase && paused.points.size() == 1, "Pause preserves provisional ink");
    check(buffer.poll() == null, "Late end cannot duplicate paused stroke");
    System.out.println("RawStrokeBuffer tests passed");
  }
}
