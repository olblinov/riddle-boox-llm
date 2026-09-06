package com.booxreview;

/** Swipe policy independent of Android dispatch; pinch/palm/zoomed-in gestures never turn pages. */
final class PageSwipe {
  private float startX, startY;
  private long startTime;
  private boolean eligible;

  void begin(float x, float y, long time, boolean pageView) {
    startX = x;
    startY = y;
    startTime = time;
    eligible = pageView;
  }

  void cancel() {
    eligible = false;
  }

  boolean horizontal(float x, float y) {
    if (Math.abs(y - startY) > 24 && Math.abs(y - startY) >= Math.abs(x - startX)) eligible = false;
    return eligible && Math.abs(x - startX) > 24 && Math.abs(x - startX) > 2 * Math.abs(y - startY);
  }

  int finish(float x, float y, long time, float threshold) {
    boolean swipe =
        horizontal(x, y) && Math.abs(x - startX) >= threshold && time - startTime <= 900;
    eligible = false;
    return swipe ? (x < startX ? 1 : -1) : 0;
  }
}
