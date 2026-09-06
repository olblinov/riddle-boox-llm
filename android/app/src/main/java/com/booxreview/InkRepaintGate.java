package com.booxreview;

/** Rejects compositor work for a frame older than the latest pen input. */
final class InkRepaintGate {
  private long revision;
  private boolean drawing, pending;

  synchronized void begin() {
    drawing = true;
    revision++;
  }

  synchronized void end() {
    drawing = false;
  }

  synchronized long changed() {
    pending = true;
    return ++revision;
  }

  synchronized long revision() {
    return revision;
  }

  synchronized boolean ready(long frame) {
    return pending && !drawing && frame == revision;
  }

  synchronized void completed(long frame) {
    if (ready(frame)) pending = false;
  }
}
