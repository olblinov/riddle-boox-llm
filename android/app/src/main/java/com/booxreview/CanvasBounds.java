package com.booxreview;

/** Source-space writable rectangle. Source (0,0) never moves when exported into extra paper. */
final class CanvasBounds {
  final int x, y, width, height;

  CanvasBounds(int x, int y, int width, int height) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
  }

  static CanvasBounds around(int sourceWidth, int sourceHeight) {
    if (sourceWidth < 1
        || sourceHeight < 1
        || sourceWidth > 4096
        || sourceHeight > 4096
        || (long) sourceWidth * sourceHeight > 16000000)
      throw new IllegalArgumentException("Unsupported source dimensions");
    int extraWidth = Math.min(1024, 4096 - sourceWidth),
        extraHeight = Math.min(1024, 4096 - sourceHeight);
    while ((long) (sourceWidth + extraWidth) * (sourceHeight + extraHeight) > 16000000) {
      if (extraWidth > 0
          && (extraHeight == 0 || sourceWidth + extraWidth >= sourceHeight + extraHeight))
        extraWidth--;
      else extraHeight--;
    }
    return new CanvasBounds(
        -extraWidth / 2, -extraHeight / 2, sourceWidth + extraWidth, sourceHeight + extraHeight);
  }

  boolean contains(float px, float py) {
    return px >= x && py >= y && px <= x + width && py <= y + height;
  }

  void validate(int sourceWidth, int sourceHeight) {
    if (x > 0
        || y > 0
        || x < -512
        || y < -512
        || width < 1
        || height < 1
        || width > 4096
        || height > 4096
        || (long) width * height > 16000000
        || x + width < sourceWidth
        || y + height < sourceHeight
        || x + width - sourceWidth > 512
        || y + height - sourceHeight > 512)
      throw new IllegalArgumentException("Invalid writable canvas");
  }
}
