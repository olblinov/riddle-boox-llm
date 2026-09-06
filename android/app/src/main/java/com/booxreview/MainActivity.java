package com.booxreview;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class MainActivity extends Activity {
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final Handler handler = new Handler(Looper.getMainLooper());
  InkView ink;
  BooxInk nativeInk;
  boolean dialogOpen;
  TextView status;
  Button send, previous, next;
  TextView pageIndicator;
  final ArrayList<PageState> pages = new ArrayList<>();
  int pagePosition = 0;
  boolean documentReview = false;
  boolean restoreFailed = false;
  String reviewTitle = "Review";
  String base = "", token = "", reviewId = "", submissionId = "";
  boolean busy = false, stopped = false, submissionAttempted = false;
  volatile boolean destroyed = false;
  volatile HttpURLConnection activeConnection;
  long nextPoll = 0;
  final Runnable poll =
      new Runnable() {
        public void run() {
          if (!stopped) {
            if (!busy && !base.isEmpty() && System.currentTimeMillis() >= nextPoll) fetch();
            handler.postDelayed(this, 2000);
          }
        }
      };

  public void onCreate(Bundle b) {
    super.onCreate(b);
    base = getPreferences(0).getString("base", "");
    token = getPreferences(0).getString("token", "");
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(1);
    root.setBackgroundColor(Color.WHITE);
    status = new TextView(this);
    status.setTextSize(13);
    status.setMaxLines(2);
    status.setPadding(10, 4, 10, 4);
    status.setText("Pair with desktop to receive a page");
    root.addView(status);
    LinearLayout bar = new LinearLayout(this);
    button(bar, "Pair", v -> pair());
    button(
        bar,
        "History",
        v -> {
          if (busy || penActive()) return;
          nativeInk.suspend();
          if (!save()) {
            ink.post(() -> nativeInk.refresh());
            return;
          }
          startActivity(new Intent(this, HistoryActivity.class));
        });
    button(
        bar,
        "Undo",
        v -> {
          if (!busy) ink.undo();
        });
    button(
        bar,
        "Clear",
        v -> {
          if (!busy)
            dialogBuilder()
                .setMessage("Clear your annotations?")
                .setPositiveButton("Clear", (d, w) -> ink.clear())
                .setNeutralButton("Discard review", (d, w) -> discard())
                .setNegativeButton("Keep", null)
                .show();
        });
    button(bar, "Width", v -> ink.fit());
    send = button(bar, "Send", v -> submit());
    send.setEnabled(false);
    root.addView(bar);
    LinearLayout navigation = new LinearLayout(this);
    previous = button(navigation, "Previous", v -> navigate(-1));
    pageIndicator = new TextView(this);
    pageIndicator.setTextSize(15);
    pageIndicator.setGravity(Gravity.CENTER);
    navigation.addView(pageIndicator, new LinearLayout.LayoutParams(0, -1, 1));
    next = button(navigation, "Next", v -> navigate(1));
    root.addView(navigation);
    ink = new InkView(this);
    nativeInk = new BooxInk(this, ink);
    root.addView(ink, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    restore();
    updateNavigation();
    if (base.isEmpty()) pair();
  }

  Button button(LinearLayout bar, String label, View.OnClickListener action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setPadding(4, 2, 4, 2);
    b.setOnClickListener(action);
    bar.addView(
        b,
        new LinearLayout.LayoutParams(
            0, (int) (42 * getResources().getDisplayMetrics().density), 1));
    return b;
  }

  AlertDialog.Builder dialogBuilder() {
    dialogOpen = true;
    if (nativeInk != null) nativeInk.suspend();
    return new AlertDialog.Builder(this)
        .setOnDismissListener(
            dialog -> {
              dialogOpen = false;
              ink.invalidate();
              ink.post(() -> nativeInk.refresh());
            });
  }

  boolean penActive() {
    return ink.stylusPointerId != -1 || (nativeInk != null && nativeInk.activeStroke());
  }

  final Runnable draftSave =
      new Runnable() {
        public void run() {
          if (destroyed) return;
          if (penActive()) {
            handler.postDelayed(this, 200);
            return;
          }
          save();
        }
      };

  void queueDraftSave() {
    handler.removeCallbacks(draftSave);
    handler.postDelayed(draftSave, 200);
  }

  public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (nativeInk != null) {
      if (!focused) nativeInk.suspend();
      else ink.post(() -> nativeInk.refresh());
    }
  }

  protected void onResume() {
    super.onResume();
    stopped = false;
    handler.post(poll);
    ink.post(() -> nativeInk.refresh());
  }

  protected void onPause() {
    super.onPause();
    stopped = true;
    nativeInk.suspend();
    handler.removeCallbacks(draftSave);
    handler.removeCallbacks(poll);
    if (ink.stylusPointerId != -1) ink.finishStylus(false);
    save();
  }

  protected void onDestroy() {
    nativeInk.close();
    destroyed = true;
    stopped = true;
    handler.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    HttpURLConnection connection = activeConnection;
    if (connection != null) connection.disconnect();
    super.onDestroy();
  }

  void updateUi(Runnable action) {
    if (destroyed) return;
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action.run();
      return;
    }
    handler.post(
        () -> {
          if (!destroyed) action.run();
        });
  }

  void pair() {
    if (busy) return;
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(1);
    form.setPadding(24, 12, 24, 12);
    EditText url = new EditText(this);
    url.setHint("http://192.168.1.10:port");
    url.setSingleLine();
    url.setText(base);
    EditText key = new EditText(this);
    key.setHint("Pairing token");
    key.setSingleLine();
    key.setInputType(129);
    key.setText(token);
    form.addView(url);
    form.addView(key);
    dialogBuilder()
        .setTitle("Connect to desktop")
        .setMessage("Use a trusted Wi-Fi network or USB. Local HTTP traffic is unencrypted.")
        .setView(form)
        .setPositiveButton(
            "Connect",
            (d, w) -> {
              try {
                String candidate = url.getText().toString().trim().replaceAll("/+$", "");
                URI u = new URI(candidate);
                if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme()))
                    || u.getHost() == null
                    || u.getRawUserInfo() != null
                    || u.getRawQuery() != null
                    || u.getRawFragment() != null
                    || !(u.getPath() == null || u.getPath().isEmpty()))
                  throw new Exception("Use server URL without path");
                String newToken = key.getText().toString().trim();
                if (newToken.isEmpty()) throw new Exception("Token required");
                if (!reviewId.isEmpty() && (!candidate.equals(base) || !newToken.equals(token)))
                  throw new Exception("Finish current review before changing connection");
                base = candidate;
                token = newToken;
                getPreferences(0).edit().putString("base", base).putString("token", token).apply();
                nextPoll = 0;
              } catch (Exception e) {
                status.setText(e.getMessage());
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  byte[] request(String path, JSONObject body) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
    activeConnection = c;
    if (destroyed) {
      c.disconnect();
      throw new IOException("Activity closed");
    }
    c.setConnectTimeout(7000);
    c.setReadTimeout(15000);
    c.setInstanceFollowRedirects(false);
    c.setRequestProperty("Authorization", "Bearer " + token);
    try {
      if (body != null) {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] data = body.toString().getBytes("UTF-8");
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream out = c.getOutputStream()) {
          out.write(data);
        }
      }
      int code = c.getResponseCode();
      if (code < 200 || code >= 300) throw new IOException("Desktop returned HTTP " + code);
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
          out.write(buf, 0, n);
          if (out.size() > 32 * 1024 * 1024) throw new IOException("Page too large");
        }
        return out.toByteArray();
      }
    } finally {
      c.disconnect();
      if (activeConnection == c) activeConnection = null;
    }
  }

  void fetch() {
    busy = true;
    worker.execute(
        () -> {
          try {
            JSONObject response =
                new JSONObject(new String(request("/api/reviews/current", null), "UTF-8"));
            JSONObject r = response.optJSONObject("review");
            if (r == null) {
              updateUi(
                  () -> {
                    if (reviewId.isEmpty()) status.setText("Connected. Waiting for Codex page");
                  });
              return;
            }
            String id = r.getString("id");
            if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid review ID");
            if (id.equals(reviewId)) return;
            if (!reviewId.isEmpty()) {
              updateUi(
                  () ->
                      status.setText(
                          "Saved draft kept. Finish current page before opening another."));
              return;
            }
            if (!"pending".equals(r.optString("status"))) return;
            JSONArray metadata = r.optJSONArray("pages");
            boolean isDocument = metadata != null;
            if (metadata == null)
              metadata =
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("pageIndex", 1)
                              .put("width", r.getInt("width"))
                              .put("height", r.getInt("height")));
            if (metadata.length() == 0 || metadata.length() > 200)
              throw new IOException("Unsupported page count");
            final ArrayList<PageState> downloaded = new ArrayList<>();
            for (int i = 0; i < metadata.length(); i++) {
              JSONObject m = metadata.getJSONObject(i);
              if (m.getInt("pageIndex") != i + 1) throw new IOException("Invalid page order");
              byte[] png =
                  request(
                      "/api/reviews/" + id + "/image" + (isDocument ? "?page=" + (i + 1) : ""),
                      null);
              BitmapFactory.Options bounds = new BitmapFactory.Options();
              bounds.inJustDecodeBounds = true;
              BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
              if (bounds.outWidth != m.getInt("width")
                  || bounds.outHeight != m.getInt("height")
                  || bounds.outWidth <= 0
                  || bounds.outHeight <= 0
                  || (long) bounds.outWidth * bounds.outHeight > 16000000)
                throw new IOException("Unsupported page size");
              PageState page = new PageState();
              page.file = id + "-page-" + (i + 1) + ".png";
              atomicWrite(page.file, png);
              downloaded.add(page);
              final int loaded = i + 1, total = metadata.length();
              updateUi(() -> status.setText("Loading document " + loaded + " / " + total));
            }
            final boolean doc = isDocument;
            updateUi(
                () -> {
                  reviewId = id;
                  reviewTitle = r.optString("title", "Review");
                  submissionId = UUID.randomUUID().toString();
                  submissionAttempted = false;
                  documentReview = doc;
                  pages.clear();
                  pages.addAll(downloaded);
                  pagePosition = 0;
                  try {
                    showPage(0);
                    save();
                    status.setText(reviewTitle);
                  } catch (Exception e) {
                    status.setText("Cannot open page: " + e.getMessage());
                  }
                  updateNavigation();
                });
          } catch (Exception e) {
            updateUi(() -> status.setText("Connection failed. Draft kept. " + e.getMessage()));
            nextPoll = System.currentTimeMillis() + 8000;
          } finally {
            updateUi(
                () -> {
                  busy = false;
                  updateNavigation();
                });
          }
        });
  }

  void discard() {
    if (busy || reviewId.isEmpty() || penActive()) return;
    busy = true;
    ink.locked = true;
    nativeInk.suspend();
    worker.execute(
        () -> {
          try {
            request("/api/reviews/" + reviewId + "/cancel", new JSONObject());
            updateUi(
                () -> {
                  clearReviewFiles();
                  ink.page = null;
                  ink.strokes.clear();
                  ink.invalidate();
                  send.setEnabled(false);
                  status.setText("Review discarded. Waiting for next page");
                });
          } catch (Exception e) {
            updateUi(
                () -> {
                  status.setText("Cannot discard. Draft kept. " + e.getMessage());
                  ink.locked = submissionAttempted;
                  ink.post(() -> nativeInk.refresh());
                });
          } finally {
            updateUi(
                () -> {
                  busy = false;
                  updateNavigation();
                });
          }
        });
  }

  void updateNavigation() {
    boolean hasReview = !restoreFailed && !reviewId.isEmpty() && !pages.isEmpty();
    boolean allVisited = hasReview;
    for (PageState page : pages) allVisited &= page.visited;
    previous.setEnabled(hasReview && pagePosition > 0 && !busy);
    next.setEnabled(hasReview && pagePosition + 1 < pages.size() && !busy);
    pageIndicator.setText(
        hasReview ? "Page " + (pagePosition + 1) + " / " + pages.size() : "No document");
    send.setText(documentReview ? "Send document" : "Send");
    send.setEnabled(hasReview && !busy && (submissionAttempted || allVisited));
  }

  void captureCurrentPage() {
    if (!pages.isEmpty() && ink.page != null) pages.get(pagePosition).strokes = ink.json();
  }

  void showPage(int index) throws Exception {
    nativeInk.suspend();
    PageState state = pages.get(index);
    Bitmap bitmap = BitmapFactory.decodeFile(new File(getFilesDir(), state.file).toString());
    if (bitmap == null) throw new IOException("Cached page missing");
    Bitmap old = ink.page;
    ink.setPage(bitmap);
    ink.load(state.strokes);
    ink.locked = submissionAttempted;
    ink.post(() -> nativeInk.refresh());
    pagePosition = index;
    state.visited = true;
    if (old != null && old != bitmap) old.recycle();
  }

  void navigate(int delta) {
    int target = pagePosition + delta;
    if (busy || penActive() || target < 0 || target >= pages.size()) return;
    if (!save()) return;
    try {
      showPage(target);
      save();
      status.setText(reviewTitle + (submissionAttempted ? " • Submission locked for retry" : ""));
    } catch (Exception e) {
      status.setText("Page unchanged: " + e.getMessage());
    }
    updateNavigation();
  }

  void clearReviewFiles() {
    for (PageState page : pages) deleteFile(page.file);
    deleteFile("draft.json");
    deleteFile("submission.json");
    reviewId = "";
    submissionId = "";
    pages.clear();
    pagePosition = 0;
    updateNavigation();
  }

  void submit() {
    if (busy || reviewId.isEmpty() || ink.page == null || penActive()) return;
    if (!submissionAttempted) {
      for (PageState page : pages) if (!page.visited) return;
    }
    if (!save()) return;
    final boolean previousAttempt = submissionAttempted;
    busy = true;
    ink.locked = true;
    nativeInk.suspend();
    submissionAttempted = true;
    if (!save()) {
      submissionAttempted = previousAttempt;
      ink.locked = previousAttempt;
      ink.post(() -> nativeInk.refresh());
      busy = false;
      updateNavigation();
      return;
    }
    updateNavigation();
    status.setText("Sending document…");
    final String id = reviewId;
    final ArrayList<PageState> snapshot = new ArrayList<>(pages);
    worker.execute(
        () -> {
          File frozen = new File(getFilesDir(), "submission.json");
          boolean keepFrozen = previousAttempt || frozen.exists();
          boolean serverAccepted = false;
          try {
            JSONObject body;
            if (frozen.exists()) {
              body =
                  new JSONObject(
                      new String(java.nio.file.Files.readAllBytes(frozen.toPath()), "UTF-8"));
            } else {
              JSONArray feedback = new JSONArray();
              long payloadEstimate = 128;
              for (int i = 0; i < snapshot.size(); i++) {
                PageState state = snapshot.get(i);
                Bitmap original =
                    BitmapFactory.decodeFile(new File(getFilesDir(), state.file).toString());
                if (original == null) throw new IOException("Cached page missing");
                Bitmap composite = original.copy(Bitmap.Config.ARGB_8888, true);
                original.recycle();
                try {
                  drawStoredInk(new Canvas(composite), state.strokes);
                  ByteArrayOutputStream out = new ByteArrayOutputStream();
                  composite.compress(Bitmap.CompressFormat.PNG, 100, out);
                  payloadEstimate +=
                      ((out.size() + 2L) / 3L) * 4L
                          + state.strokes.toString().getBytes("UTF-8").length
                          + 128;
                  if (payloadEstimate > 24 * 1024 * 1024)
                    throw new IOException("Document exceeds 24 MiB; draft kept");
                  feedback.put(
                      new JSONObject()
                          .put("pageIndex", i + 1)
                          .put(
                              "compositeBase64",
                              android.util.Base64.encodeToString(
                                  out.toByteArray(), android.util.Base64.NO_WRAP))
                          .put("strokes", state.strokes));
                } finally {
                  composite.recycle();
                }
              }
              body = new JSONObject().put("submissionId", submissionId);
              if (documentReview) body.put("pages", feedback);
              else
                body.put("compositeBase64", feedback.getJSONObject(0).getString("compositeBase64"))
                    .put("strokes", feedback.getJSONObject(0).getJSONArray("strokes"));
              byte[] encoded = body.toString().getBytes("UTF-8");
              if (encoded.length > 24 * 1024 * 1024)
                throw new IOException(
                    "Document exceeds 24 MiB. Draft kept; ask desktop to split review");
              atomicWrite("submission.json", encoded);
            }
            keepFrozen = true;
            byte[] acknowledgement = request("/api/reviews/" + id + "/feedback", body);
            serverAccepted = true;
            JSONObject accepted = new JSONObject(new String(acknowledgement, "UTF-8"));
            String submittedAt =
                accepted.optString("submittedAt", java.time.Instant.now().toString());
            // Cache every annotated page durably before deleting the only pending-draft copy.
            try {
              HistoryActivity.cacheFeedback(
                  new HistoryCache(getFilesDir()), id, reviewTitle, submittedAt, body);
            } catch (Exception cacheError) {
              throw new IOException(
                  "Sent to desktop, but offline history save failed. Retry Send to save the"
                      + " retained copy. "
                      + cacheError.getMessage(),
                  cacheError);
            }
            updateUi(
                () -> {
                  clearReviewFiles();
                  status.setText("Document sent to Codex. Waiting for next review");
                  ink.locked = true;
                  nativeInk.suspend();
                });
          } catch (Exception e) {
            final boolean retainLock = keepFrozen || frozen.exists();
            final boolean sent = serverAccepted;
            updateUi(
                () -> {
                  submissionAttempted = retainLock;
                  ink.locked = retainLock;
                  ink.post(() -> nativeInk.refresh());
                  if (!retainLock) save();
                  status.setText(
                      (sent
                              ? ""
                              : retainLock
                                  ? "Send not confirmed. Draft kept unchanged. Retry Send. "
                                  : "Nothing sent. Draft remains editable. ")
                          + e.getMessage());
                });
          } finally {
            updateUi(
                () -> {
                  busy = false;
                  updateNavigation();
                });
          }
        });
  }

  static void drawStoredInk(Canvas canvas, JSONArray strokes) throws JSONException {
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(Color.BLACK);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStyle(Paint.Style.STROKE);
    for (int i = 0; i < strokes.length(); i++) {
      JSONObject stroke = strokes.getJSONObject(i);
      JSONArray points = stroke.getJSONArray("points");
      float width = (float) stroke.getDouble("width");
      for (int j = 0; j < points.length(); j++) {
        JSONObject point = points.getJSONObject(j);
        float x = (float) point.getDouble("x"), y = (float) point.getDouble("y");
        paint.setStrokeWidth(width * (.4f + .6f * (float) point.getDouble("pressure")));
        if (j == 0) canvas.drawPoint(x, y, paint);
        else {
          JSONObject before = points.getJSONObject(j - 1);
          canvas.drawLine(
              (float) before.getDouble("x"), (float) before.getDouble("y"), x, y, paint);
        }
      }
    }
  }

  void atomicWrite(String name, byte[] bytes) throws IOException {
    File tmp = new File(getFilesDir(), name + ".tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(bytes);
      out.getFD().sync();
    }
    if (!tmp.renameTo(new File(getFilesDir(), name)))
      throw new IOException("Could not save " + name);
  }

  boolean save() {
    if (restoreFailed) return false;
    if (reviewId.isEmpty() || ink == null) return true;
    try {
      captureCurrentPage();
      JSONArray saved = new JSONArray();
      for (PageState page : pages)
        saved.put(
            new JSONObject()
                .put("file", page.file)
                .put("strokes", page.strokes)
                .put("visited", page.visited));
      JSONObject draft =
          new JSONObject()
              .put("version", 2)
              .put("reviewId", reviewId)
              .put("submissionId", submissionId)
              .put("submissionAttempted", submissionAttempted)
              .put("documentReview", documentReview)
              .put("title", reviewTitle)
              .put("pagePosition", pagePosition)
              .put("pages", saved);
      atomicWrite("draft.json", draft.toString().getBytes("UTF-8"));
      return true;
    } catch (Exception e) {
      status.setText("Draft save failed: " + e.getMessage());
      return false;
    }
  }

  void restore() {
    try {
      File file = new File(getFilesDir(), "draft.json");
      if (!file.exists()) return;
      JSONObject draft =
          new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8"));
      reviewId = draft.getString("reviewId");
      submissionId = draft.getString("submissionId");
      submissionAttempted = draft.optBoolean("submissionAttempted", false);
      documentReview = draft.optBoolean("documentReview", false);
      reviewTitle = draft.optString("title", "Review");
      JSONArray saved = draft.optJSONArray("pages");
      pages.clear();
      if (saved == null) {
        // Preserve the original page.png and submission ID when upgrading a pending legacy draft.
        PageState legacy = new PageState();
        legacy.file = "page.png";
        legacy.strokes = draft.getJSONArray("strokes");
        legacy.visited = true;
        pages.add(legacy);
      } else {
        for (int i = 0; i < saved.length(); i++) {
          JSONObject entry = saved.getJSONObject(i);
          PageState page = new PageState();
          page.file = entry.getString("file");
          if (!page.file.matches("[A-Za-z0-9_.-]+"))
            throw new IOException("Invalid cached page name");
          page.strokes = entry.getJSONArray("strokes");
          page.visited = entry.optBoolean("visited", false);
          pages.add(page);
        }
      }
      if (pages.isEmpty()) throw new IOException("Draft has no pages");
      showPage(Math.max(0, Math.min(pages.size() - 1, draft.optInt("pagePosition", 0))));
      status.setText(
          submissionAttempted
              ? "Restored pending submission. Send retries unchanged document"
              : "Restored document annotations");
    } catch (Exception e) {
      restoreFailed = true;
      status.setText("Cannot restore draft: " + e.getMessage());
    }
  }

  static class PageState {
    String file;
    JSONArray strokes = new JSONArray();
    boolean visited;
  }

  class InkView extends View {
    Bitmap page;
    ArrayList<Stroke> strokes = new ArrayList<>();
    Stroke active;
    Paint paint = new Paint(3);
    Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    float scale = 1, dx = 0, dy = 0, lastX, lastY;
    boolean locked = false;
    int stylusPointerId = -1;
    boolean erasing = false, suppressNavigation = false;
    ScaleGestureDetector zoom;

    InkView(Context c) {
      super(c);
      setBackgroundColor(Color.WHITE);
      zoom =
          new ScaleGestureDetector(
              c,
              new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                public boolean onScale(ScaleGestureDetector d) {
                  float old = scale;
                  scale = Math.max(.1f, Math.min(6, scale * d.getScaleFactor()));
                  dx = d.getFocusX() - (d.getFocusX() - dx) * scale / old;
                  dy = d.getFocusY() - (d.getFocusY() - dy) * scale / old;
                  invalidate();
                  return true;
                }
              });
    }

    void setPage(Bitmap b) {
      page = b;
      strokes.clear();
      locked = false;
      post(() -> fit());
    }

    void fit() {
      if (penActive()) return;
      if (nativeInk != null) nativeInk.suspend();
      if (page != null && getWidth() > 0) {
        scale = (float) getWidth() / page.getWidth();
        dx = 0;
        dy = 0;
        invalidate();
        postOnAnimation(() -> post(() -> nativeInk.refresh()));
      }
    }

    protected void onSizeChanged(int w, int h, int ow, int oh) {
      fit();
    }

    protected void onDraw(Canvas c) {
      super.onDraw(c);
      if (page == null) return;
      c.save();
      c.translate(dx, dy);
      c.scale(scale, scale);
      c.clipRect(0, 0, page.getWidth(), page.getHeight());
      bitmapPaint.setFilterBitmap(Math.abs(scale - Math.round(scale)) > .0001f);
      c.drawBitmap(page, 0, 0, bitmapPaint);
      drawInk(c);
      c.restore();
      if (nativeInk != null) nativeInk.onBackingDrawn();
    }

    void drawInk(Canvas c) {
      paint.setColor(Color.BLACK);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStyle(Paint.Style.STROKE);
      for (Stroke s : strokes) {
        for (int i = 0; i < s.points.size(); i++) {
          float[] p = s.points.get(i);
          paint.setStrokeWidth(s.width * (.4f + .6f * p[2]));
          if (i == 0) c.drawPoint(p[0], p[1], paint);
          else {
            float[] prev = s.points.get(i - 1);
            c.drawLine(prev[0], prev[1], p[0], p[1], paint);
          }
        }
      }
    }

    boolean isPen(MotionEvent event, int index) {
      int tool = event.getToolType(index);
      return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
    }

    void finishStylus(boolean cancelled) {
      if (cancelled && active != null) strokes.remove(active);
      active = null;
      stylusPointerId = -1;
      erasing = false;
      save();
      invalidate();
    }

    public boolean onTouchEvent(MotionEvent event) {
      if (page == null) return true;
      if (nativeInk != null && nativeInk.handleTouch(event)) return true;
      int action = event.getActionMasked();
      int actionIndex = event.getActionIndex();
      if (action == MotionEvent.ACTION_CANCEL) {
        if (stylusPointerId != -1) finishStylus(true);
        suppressNavigation = false;
        zoom.onTouchEvent(event);
        return true;
      }
      if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
          && isPen(event, actionIndex)) {
        requestUnbufferedDispatch(event);
        suppressNavigation = true;
        // Cancel any finger gesture before freezing the transform for ink.
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        zoom.onTouchEvent(cancel);
        cancel.recycle();
        if (!locked && stylusPointerId == -1) {
          stylusPointerId = event.getPointerId(actionIndex);
          erasing = event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER;
          if (!erasing) {
            active = new Stroke();
            strokes.add(active);
          }
        }
      }
      if (stylusPointerId != -1) {
        int index = event.findPointerIndex(stylusPointerId);
        if (index < 0) {
          finishStylus(true);
          return true;
        }
        boolean ending =
            (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                && event.getPointerId(actionIndex) == stylusPointerId;
        boolean starting =
            (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                && event.getPointerId(actionIndex) == stylusPointerId;
        if (!locked && (starting || ending || action == MotionEvent.ACTION_MOVE)) {
          float x = (event.getX(index) - dx) / scale;
          float y = (event.getY(index) - dy) / scale;
          if (erasing) {
            float radius = 22 / scale;
            strokes.removeIf(
                stroke -> {
                  for (float[] point : stroke.points) {
                    if (Math.hypot(point[0] - x, point[1] - y) < radius) return true;
                  }
                  return false;
                });
          } else if (active != null) {
            for (int history = 0; history < event.getHistorySize(); history++) {
              add(
                  (event.getHistoricalX(index, history) - dx) / scale,
                  (event.getHistoricalY(index, history) - dy) / scale,
                  event.getHistoricalPressure(index, history));
            }
            add(x, y, event.getPressure(index));
          }
          invalidate();
        }
        if (ending) finishStylus(false);
        if (action == MotionEvent.ACTION_UP) suppressNavigation = false;
        return true;
      }
      // Palm contacts that remain after pen-up cannot become a pan gesture.
      if (suppressNavigation) {
        if (action == MotionEvent.ACTION_UP) suppressNavigation = false;
        return true;
      }
      for (int index = 0; index < event.getPointerCount(); index++) {
        if (isPen(event, index)) return true;
      }
      zoom.onTouchEvent(event);
      if (action == MotionEvent.ACTION_DOWN) {
        lastX = event.getX();
        lastY = event.getY();
      } else if (action == MotionEvent.ACTION_MOVE) {
        if (event.getPointerCount() == 1 && !zoom.isInProgress()) {
          dx += event.getX() - lastX;
          dy += event.getY() - lastY;
          invalidate();
        }
        lastX = event.getX();
        lastY = event.getY();
      } else if (action == MotionEvent.ACTION_POINTER_UP) {
        int remainingIndex = actionIndex == 0 ? 1 : 0;
        lastX = event.getX(remainingIndex);
        lastY = event.getY(remainingIndex);
      }
      return true;
    }

    void add(float x, float y, float p) {
      active.points.add(
          new float[] {
            Math.max(0, Math.min(page.getWidth(), x)),
            Math.max(0, Math.min(page.getHeight(), y)),
            Math.max(.1f, Math.min(1, p))
          });
    }

    void undo() {
      if (locked || penActive()) return;
      nativeInk.suspend();
      if (!strokes.isEmpty()) strokes.remove(strokes.size() - 1);
      save();
      invalidate();
      postOnAnimation(() -> post(() -> nativeInk.refresh()));
    }

    void clear() {
      if (locked || penActive()) return;
      nativeInk.suspend();
      strokes.clear();
      save();
      invalidate();
      postOnAnimation(() -> post(() -> nativeInk.refresh()));
    }

    Bitmap composite() {
      Bitmap b = page.copy(Bitmap.Config.ARGB_8888, true);
      drawInk(new Canvas(b));
      return b;
    }

    JSONArray json() {
      JSONArray a = new JSONArray();
      try {
        for (Stroke s : strokes) {
          JSONArray points = new JSONArray();
          for (float[] p : s.points)
            points.put(new JSONObject().put("x", p[0]).put("y", p[1]).put("pressure", p[2]));
          a.put(new JSONObject().put("width", s.width).put("points", points));
        }
      } catch (JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
      return a;
    }

    void load(JSONArray a) throws Exception {
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.getJSONObject(i);
        Stroke s = new Stroke();
        s.width = (float) o.getDouble("width");
        JSONArray ps = o.getJSONArray("points");
        for (int j = 0; j < ps.length(); j++) {
          JSONObject p = ps.getJSONObject(j);
          s.points.add(
              new float[] {
                (float) p.getDouble("x"), (float) p.getDouble("y"), (float) p.getDouble("pressure")
              });
        }
        strokes.add(s);
      }
      invalidate();
    }
  }

  static class Stroke {
    float width = 3;
    ArrayList<float[]> points = new ArrayList<>();
  }
}
