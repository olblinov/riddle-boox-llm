package com.booxreview;

import java.io.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Immutable sent-document cache. Its pruning is confined to the history subdirectory. */
final class HistoryCache {
  static final long RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000;
  private final File root;
  private static final Object LOCK = new Object();

  static final class Entry {
    final String id, title, submittedAt;
    final int pageCount;
    final String[] hashes;

    Entry(String id, String title, String submittedAt, int pageCount, String[] hashes) {
      this.id = id;
      this.title = title;
      this.submittedAt = submittedAt;
      this.pageCount = pageCount;
      this.hashes = hashes;
    }
  }

  HistoryCache(File appFiles) {
    root = new File(appFiles, "history");
  }

  static boolean recent(String timestamp, long now) {
    try {
      return Instant.parse(timestamp).toEpochMilli() >= now - RETENTION_MILLIS;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private File directory(String id) throws IOException {
    if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("Invalid history ID");
    return new File(root, id);
  }

  Entry get(String id) throws IOException {
    synchronized (LOCK) {
      File dir = directory(id);
      Properties p = new Properties();
      try (InputStream in = new FileInputStream(new File(dir, "metadata.properties"))) {
        p.load(in);
      }
      int count;
      try {
        count = Integer.parseInt(p.getProperty("pageCount"));
        Instant.parse(p.getProperty("submittedAt"));
      } catch (RuntimeException malformed) {
        throw new IOException("Invalid history metadata", malformed);
      }
      if (count < 1 || count > 200) throw new IOException("Invalid history page count");
      String[] hashes = new String[count];
      for (int i = 1; i <= count; i++) {
        if (!new File(dir, "page-" + i + ".png").isFile())
          throw new IOException("Incomplete history document");
        hashes[i - 1] = p.getProperty("pageHash." + i);
        if (hashes[i - 1] == null) throw new IOException("History integrity metadata missing");
      }
      return new Entry(
          id, p.getProperty("title", "Review"), p.getProperty("submittedAt"), count, hashes);
    }
  }

  void put(String id, String title, String submittedAt, List<byte[]> pages) throws IOException {
    synchronized (LOCK) {
      if (pages.isEmpty() || pages.size() > 200) throw new IOException("Invalid history pages");
      Instant.parse(submittedAt);
      File destination = directory(id);
      if (destination.exists()) {
        try {
          verify(id);
          return;
        } catch (Exception corrupt) {
          /* Stage replacement before touching old data. */
        }
      }
      if (!root.isDirectory() && !root.mkdirs())
        throw new IOException("Cannot create history cache");
      File staging = new File(root, id + ".tmp");
      removeTree(staging);
      if (!staging.mkdir()) throw new IOException("Cannot stage history cache");
      try {
        for (int i = 0; i < pages.size(); i++) {
          try (FileOutputStream out =
              new FileOutputStream(new File(staging, "page-" + (i + 1) + ".png"))) {
            out.write(pages.get(i));
            out.getFD().sync();
          }
        }
        Properties p = new Properties();
        p.setProperty("title", title);
        p.setProperty("submittedAt", submittedAt);
        p.setProperty("pageCount", Integer.toString(pages.size()));
        for (int i = 0; i < pages.size(); i++)
          p.setProperty("pageHash." + (i + 1), digest(pages.get(i)));
        try (FileOutputStream out =
            new FileOutputStream(new File(staging, "metadata.properties"))) {
          p.store(out, "BOOX sent document");
          out.getFD().sync();
        }
        File backup = new File(root, id + ".repair");
        if (destination.exists()) {
          removeTree(backup);
          if (!destination.renameTo(backup))
            throw new IOException("Cannot replace corrupt history");
        }
        if (!staging.renameTo(destination)) {
          if (backup.exists()) backup.renameTo(destination);
          throw new IOException("Cannot commit history cache");
        }
        removeTree(backup);
      } finally {
        removeTree(staging);
      }
    }
  }

  List<Entry> list(long now) throws IOException {
    synchronized (LOCK) {
      prune(now);
      ArrayList<Entry> entries = new ArrayList<>();
      File[] dirs = root.listFiles();
      if (dirs != null)
        for (File dir : dirs) {
          if (!dir.isDirectory() || dir.getName().endsWith(".tmp")) continue;
          try {
            entries.add(get(dir.getName()));
          } catch (Exception malformed) {
            /* Keep unknown data for recovery. */
          }
        }
      entries.sort((a, b) -> Instant.parse(b.submittedAt).compareTo(Instant.parse(a.submittedAt)));
      return entries;
    }
  }

  void prune(long now) throws IOException {
    synchronized (LOCK) {
      File[] dirs = root.listFiles();
      if (dirs == null) return;
      for (File dir : dirs) {
        if (!dir.isDirectory() || dir.getName().endsWith(".tmp")) continue;
        try {
          Entry entry = get(dir.getName());
          long sent = Instant.parse(entry.submittedAt).toEpochMilli();
          if (sent < now - RETENTION_MILLIS) removeTree(dir);
        } catch (IllegalArgumentException malformed) {
          /* Unknown timestamp is retained. */
        } catch (IOException malformed) {
          /* Incomplete cache is retained for recovery. */
        }
      }
    }
  }

  File page(String id, int pageIndex) throws IOException {
    synchronized (LOCK) {
      Entry entry = get(id);
      if (pageIndex < 1 || pageIndex > entry.pageCount)
        throw new IOException("Invalid history page");
      return verifyPage(entry, pageIndex);
    }
  }

  Entry verify(String id) throws IOException {
    synchronized (LOCK) {
      Entry entry = get(id);
      for (int index = 1; index <= entry.pageCount; index++) verifyPage(entry, index);
      return entry;
    }
  }

  private File verifyPage(Entry entry, int index) throws IOException {
    File image = new File(directory(entry.id), "page-" + index + ".png");
    if (!digest(Files.readAllBytes(image.toPath())).equals(entry.hashes[index - 1]))
      throw new IOException("Corrupt cached page; Refresh history to repair");
    return image;
  }

  private static String digest(byte[] bytes) throws IOException {
    try {
      byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder();
      for (byte b : hash) result.append(String.format("%02x", b & 255));
      return result.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IOException(impossible);
    }
  }

  private void removeTree(File path) throws IOException {
    if (!path.exists()) return;
    if (Files.isSymbolicLink(path.toPath())) throw new IOException("Refusing history symlink");
    File[] children = path.listFiles();
    if (children != null) for (File child : children) removeTree(child);
    if (!path.delete()) throw new IOException("Cannot remove expired history cache");
  }
}
