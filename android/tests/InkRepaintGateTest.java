package com.booxreview;

public final class InkRepaintGateTest {
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    InkRepaintGate gate = new InkRepaintGate();
    gate.begin();
    gate.end();
    long firstFrame = gate.changed();
    check(gate.ready(firstFrame), "first completed stroke must reach retained display");
    gate.begin();
    check(!gate.ready(firstFrame), "new stroke must cancel queued repaint");
    gate.end();
    check(!gate.ready(firstFrame), "old frame remains stale after pen-up");
    long secondFrame = gate.changed();
    gate.completed(firstFrame);
    check(gate.ready(secondFrame), "stale completion cannot clear newer ink");
    gate.completed(secondFrame);
    check(!gate.ready(secondFrame), "completed frame must not repaint twice");
    gate.begin();
    long drainedDuringStroke = gate.changed();
    check(!gate.ready(drainedDuringStroke), "UI drain during raw input cannot repaint");
    gate.end();
    check(gate.ready(drainedDuringStroke), "suspended stroke can still reach backing frame");
    System.out.println("InkRepaintGateTest passed");
  }
}
