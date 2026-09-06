package com.booxreview;

public final class CanvasGestureTest {
  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    int[][] sizes = {{1404, 1872}, {4096, 100}, {3900, 3900}, {1, 1}, {4000, 4000}, {3073, 3071}};
    for (int[] size : sizes) {
      CanvasBounds b = CanvasBounds.around(size[0], size[1]);
      b.validate(size[0], size[1]);
      check(b.contains(0, 0) && b.contains(size[0], size[1]), "source enclosed");
      check((long) b.width * b.height <= 16000000, "area limit");
      check(b.x >= -512 && b.y >= -512, "left/top limit");
    }
    CanvasBounds b = CanvasBounds.around(1404, 1872);
    check(b.x == -512 && b.y == -512, "negative source origin");
    check(b.contains(-100, 1900), "margin writable");
    check(!b.contains(-513, 0), "outside rejected");
    check(-100 - b.x == 412, "composite translation");
    boolean rejected = false;
    try {
      new CanvasBounds(-513, 0, 2000, 2000).validate(1404, 1872);
    } catch (IllegalArgumentException e) {
      rejected = true;
    }
    check(rejected, "invalid border rejected");
    PageSwipe swipe = new PageSwipe();
    swipe.begin(600, 300, 0, true);
    check(swipe.finish(200, 310, 500, 150) == 1, "left next");
    swipe.begin(200, 300, 0, true);
    check(swipe.finish(600, 310, 500, 150) == -1, "right previous");
    swipe.begin(600, 300, 0, false);
    check(swipe.finish(200, 310, 500, 150) == 0, "zoomed pan");
    swipe.begin(600, 300, 0, true);
    swipe.cancel();
    check(swipe.finish(200, 310, 500, 150) == 0, "pinch palm cancel");
    swipe.begin(600, 300, 0, true);
    check(!swipe.horizontal(610, 400), "vertical pan");
    check(swipe.finish(200, 310, 500, 150) == 0, "vertical stays pan");
    swipe.begin(600, 300, 0, true);
    check(swipe.finish(200, 310, 1000, 150) == 0, "slow drag");
    swipe.begin(600, 300, 0, true);
    check(swipe.finish(500, 300, 300, 150) == 0, "short drag");
    System.out.println("CanvasGestureTest passed");
  }
}
