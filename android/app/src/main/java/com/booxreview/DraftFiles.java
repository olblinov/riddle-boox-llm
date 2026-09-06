package com.booxreview;

import java.io.*;
import java.nio.file.Files;

/** Per-review durable drafts; active draft stays backward compatible with earlier releases. */
final class DraftFiles {
  private final File root;

  DraftFiles(File root) {
    this.root = root;
  }

  private static String checked(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]+"))
      throw new IllegalArgumentException("Invalid review ID");
    return id;
  }

  File draft(String id) {
    return new File(root, "draft-" + checked(id) + ".json");
  }

  File activeSnapshot(String id) {
    File saved = draft(id);
    return saved.exists() ? saved : new File(root, "draft.json");
  }

  File frozen(String id) {
    return new File(root, "submission-" + checked(id) + ".json");
  }

  void write(File file, byte[] bytes) throws IOException {
    File temp = new File(file.getPath() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(temp)) {
      out.write(bytes);
      out.getFD().sync();
    }
    if (!temp.renameTo(file)) throw new IOException("Could not save draft");
  }

  void saveActive(String id, byte[] bytes) throws IOException {
    write(draft(id), bytes);
    write(new File(root, "draft.json"), bytes);
  }

  void migrateFrozen(String id) throws IOException {
    File old = new File(root, "submission.json");
    if (!old.exists()) return;
    if (frozen(id).exists()
        && !java.util.Arrays.equals(
            Files.readAllBytes(old.toPath()), Files.readAllBytes(frozen(id).toPath())))
      throw new IOException("Conflicting pending submission; draft retained");
    if (!frozen(id).exists()) write(frozen(id), Files.readAllBytes(old.toPath()));
    if (!old.delete()) throw new IOException("Could not migrate pending submission");
  }

  boolean hasDrafts() {
    File[] files =
        root.listFiles((dir, name) -> name.startsWith("draft-") && name.endsWith(".json"));
    return files != null && files.length > 0;
  }

  void clear(String id) throws IOException {
    // Remove active pointer first so interruption cannot restore a deleted draft.
    remove(new File(root, "draft.json"));
    remove(draft(id));
    remove(frozen(id));
  }

  private void remove(File file) throws IOException {
    if (file.exists() && !file.delete()) throw new IOException("Could not remove completed draft");
  }
}
