package com.booxreview;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class HistoryCacheTest {
  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    File app = Files.createTempDirectory("boox-history-test").toFile();
    try {
      File draft = new File(app, "draft.json");
      Files.write(draft.toPath(), "pending".getBytes("UTF-8"));
      File source = new File(app, "page.png");
      Files.write(source.toPath(), new byte[] {9, 8, 7});
      HistoryCache cache = new HistoryCache(app);
      long now = Instant.parse("2026-09-06T12:00:00Z").toEpochMilli();
      List<byte[]> images = Arrays.asList(new byte[] {1, 2}, new byte[] {3, 4});
      cache.put("recent", "Two pages", "2026-09-06T11:00:00Z", images);
      cache.put(
          "expired",
          "Old",
          Instant.ofEpochMilli(now - HistoryCache.RETENTION_MILLIS - 1).toString(),
          images);
      cache.put(
          "boundary",
          "Still here",
          Instant.ofEpochMilli(now - HistoryCache.RETENTION_MILLIS).toString(),
          images);
      List<HistoryCache.Entry> list = cache.list(now);
      check(list.size() == 2, "Only older-than-90-days documents pruned");
      check(list.get(0).id.equals("recent"), "Newest document first");
      check(cache.get("recent").pageCount == 2, "All pages cached");
      check(
          Arrays.equals(Files.readAllBytes(cache.page("recent", 2).toPath()), new byte[] {3, 4}),
          "Page order preserved");
      // Reopen without any server; pending source deletion cannot erase history bytes.
      HistoryCache reopened = new HistoryCache(app);
      source.delete();
      check(reopened.get("recent").pageCount == 2, "Offline reopen survives original cleanup");
      reopened.put("recent", "Changed", "2026-09-06T12:00:00Z", Arrays.asList(new byte[] {99}));
      check(reopened.get("recent").pageCount == 2, "Duplicate success is immutable/idempotent");
      check(
          new String(Files.readAllBytes(draft.toPath()), "UTF-8").equals("pending"),
          "Pending draft untouched");
      boolean rejected = false;
      try {
        cache.put("../draft", "Bad", "2026-09-06T00:00:00Z", images);
      } catch (IOException expected) {
        rejected = true;
      }
      check(rejected, "Cache cannot escape its own directory");
      check(HistoryCache.recent("invalid", now) == false, "Invalid remote timestamp ignored");
      Files.write(cache.page("recent", 1).toPath(), new byte[] {99});
      boolean corrupt = false;
      try {
        cache.verify("recent");
      } catch (IOException expected) {
        corrupt = true;
      }
      check(corrupt, "Changed cached image detected");
      cache.put("recent", "Repaired", "2026-09-06T11:00:00Z", images);
      check(
          Arrays.equals(Files.readAllBytes(cache.page("recent", 1).toPath()), images.get(0)),
          "Corrupt document repaired atomically");
      java.util.concurrent.ExecutorService writers =
          java.util.concurrent.Executors.newFixedThreadPool(2);
      java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Void> write =
          () -> {
            start.await();
            new HistoryCache(app).put("concurrent", "Same", "2026-09-06T11:00:00Z", images);
            return null;
          };
      java.util.concurrent.Future<Void> one = writers.submit(write), two = writers.submit(write);
      start.countDown();
      one.get();
      two.get();
      writers.shutdown();
      check(
          cache.get("concurrent").pageCount == 2,
          "Two instances cannot delete each other's staging");
      System.out.println("HistoryCache tests passed");
    } finally {
      try (java.util.stream.Stream<Path> paths = Files.walk(app.toPath())) {
        paths
            .sorted(Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    Files.delete(path);
                  } catch (IOException error) {
                    throw new UncheckedIOException(error);
                  }
                });
      }
    }
  }
}
