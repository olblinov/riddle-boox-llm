package com.booxreview;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public final class DraftFilesTest {
  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  static byte[] bytes(String s) throws Exception {
    return s.getBytes("UTF-8");
  }

  static void equal(File file, byte[] expected) throws Exception {
    check(
        Arrays.equals(Files.readAllBytes(file.toPath()), expected),
        "Exact bytes: " + file.getName());
  }

  public static void main(String[] args) throws Exception {
    File root = Files.createTempDirectory("drafts-test").toFile();
    DraftFiles store = new DraftFiles(root);
    byte[] first = bytes("{draft A: strokes, pagePosition:2, canvasBounds:-512}"),
        second = bytes("{draft B: strokes, pagePosition:0}");
    store.saveActive("A", first);
    store.saveActive("B", second);
    equal(store.draft("A"), first);
    equal(store.draft("B"), second);
    equal(new File(root, "draft.json"), second);
    store.saveActive("A", Files.readAllBytes(store.draft("A").toPath()));
    equal(new File(root, "draft.json"), first);
    byte[] frozen = bytes("{\n  exactPayload:1.000, signature:\"unchanged\"\n}");
    Files.write(new File(root, "submission.json").toPath(), frozen);
    store.migrateFrozen("A");
    equal(store.frozen("A"), frozen);
    check(!new File(root, "submission.json").exists(), "legacy moved after copy");
    store.write(store.frozen("B"), bytes("B frozen"));
    store.clear("A");
    check(!store.draft("A").exists() && !store.frozen("A").exists(), "clear only A");
    equal(store.draft("B"), second);
    equal(store.frozen("B"), bytes("B frozen"));
    check(store.hasDrafts(), "saved drafts remain");
    Files.write(new File(root, "submission.json").toPath(), frozen);
    boolean rejected = false;
    try {
      store.migrateFrozen("B");
    } catch (IOException e) {
      rejected = true;
    }
    check(rejected, "conflict rejected");
    equal(new File(root, "submission.json"), frozen);
    equal(store.frozen("B"), bytes("B frozen"));
    rejected = false;
    try {
      store.draft("../escape");
    } catch (IllegalArgumentException e) {
      rejected = true;
    }
    check(rejected, "path traversal rejected");
    // A crash after writing per-review data but before active-pointer update leaves old active
    // intact.
    store.saveActive("B", second);
    store.write(store.draft("C"), bytes("staged C"));
    equal(new File(root, "draft.json"), second);
    byte[] latest = bytes("B newer ink after interrupted active write");
    store.write(store.draft("B"), latest);
    equal(new File(root, "draft.json"), second);
    equal(store.activeSnapshot("B"), latest);
    store.saveActive("B", Files.readAllBytes(store.activeSnapshot("B").toPath()));
    equal(store.draft("B"), latest);
    equal(new File(root, "draft.json"), latest);
    System.out.println("DraftFilesTest passed");
  }
}
