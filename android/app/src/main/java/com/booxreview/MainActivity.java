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
  WifiDiscovery wifiDiscovery;
  boolean dialogOpen, notificationDocumentsPending;
  TextView status, inboxTitle, inboxHelp;
  LinearLayout emptyInbox, navigation;
  Button send, previous, next, undo;
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
            maybeOpenNotificationDocuments();
            if (!busy && !dialogOpen && !base.isEmpty() && System.currentTimeMillis() >= nextPoll)
              fetch();
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
    button(bar, "Documents", v -> showQueue());
    undo =
        button(
            bar,
            "Undo",
            v -> {
              if (!busy) ink.undo();
            });
    send = button(bar, "Send", v -> submit());
    send.setEnabled(false);
    button(bar, "More", v -> showMore());
    root.addView(bar);
    navigation = new LinearLayout(this);
    previous = button(navigation, "Previous", v -> navigate(-1));
    pageIndicator = new TextView(this);
    pageIndicator.setTextSize(15);
    pageIndicator.setGravity(Gravity.CENTER);
    navigation.addView(pageIndicator, new LinearLayout.LayoutParams(0, -1, 1));
    next = button(navigation, "Next", v -> navigate(1));
    root.addView(navigation);
    ink = new InkView(this);
    nativeInk = new BooxInk(this, ink);
    FrameLayout canvasArea = new FrameLayout(this);
    canvasArea.addView(ink, new FrameLayout.LayoutParams(-1, -1));
    emptyInbox = new LinearLayout(this);
    emptyInbox.setOrientation(1);
    emptyInbox.setGravity(Gravity.CENTER);
    emptyInbox.setPadding(48, 32, 48, 32);
    emptyInbox.setBackgroundColor(Color.WHITE);
    inboxTitle = new TextView(this);
    inboxTitle.setTextSize(30);
    inboxTitle.setTypeface(null, Typeface.BOLD);
    inboxTitle.setTextColor(Color.BLACK);
    inboxTitle.setGravity(Gravity.CENTER);
    inboxHelp = new TextView(this);
    inboxHelp.setTextSize(18);
    inboxHelp.setTextColor(Color.DKGRAY);
    inboxHelp.setGravity(Gravity.CENTER);
    inboxHelp.setPadding(0, 20, 0, 32);
    emptyInbox.addView(inboxTitle);
    emptyInbox.addView(inboxHelp);
    LinearLayout inboxActions = new LinearLayout(this);
    button(inboxActions, "Documents", v -> showQueue());
    button(inboxActions, "Sent history", v -> openHistory());
    emptyInbox.addView(inboxActions, new LinearLayout.LayoutParams(-1, -2));
    canvasArea.addView(emptyInbox, new FrameLayout.LayoutParams(-1, -1));
    root.addView(canvasArea, new LinearLayout.LayoutParams(-1, 0, 1));
    showInbox("Inbox", "Connect to your desktop to receive documents.");
    setContentView(root);
    restore();
    updateNavigation();
    notificationDocumentsPending = getIntent().getBooleanExtra("open_documents", false);
    getIntent().removeExtra("open_documents");
    if (base.isEmpty()) pair();
    else ReviewNotificationService.start(this);
  }

  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    notificationDocumentsPending = intent.getBooleanExtra("open_documents", false);
    intent.removeExtra("open_documents");
    maybeOpenNotificationDocuments();
  }

  void maybeOpenNotificationDocuments() {
    if (notificationDocumentsPending
        && !base.isEmpty()
        && !busy
        && !dialogOpen
        && !penActive()
        && !stopped) {
      notificationDocumentsPending = false;
      showQueue();
    }
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
              if (wifiDiscovery != null) {
                wifiDiscovery.stop();
                wifiDiscovery = null;
              }
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
    if (wifiDiscovery != null) wifiDiscovery.stop();
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

  void showInbox(String heading, String help) {
    if (!reviewId.isEmpty()) return;
    nativeInk.suspend();
    inboxTitle.setText(heading);
    inboxHelp.setText(help);
    emptyInbox.setVisibility(View.VISIBLE);
    ink.setVisibility(View.GONE);
  }

  void openHistory() {
    if (busy || penActive()) return;
    nativeInk.suspend();
    if (save()) startActivity(new Intent(this, HistoryActivity.class));
    else ink.post(() -> nativeInk.refresh());
  }

  void showMore() {
    if (busy || penActive()) return;
    String[] actions = {
      "Fit page",
      "Fit width",
      "Submitted history",
      "Connect desktop",
      "Clear annotations",
      ReviewNotificationService.isEnabled(this)
          ? "Pause review notifications"
          : "Enable review notifications"
    };
    dialogBuilder()
        .setTitle("More")
        .setItems(
            actions,
            (dialog, which) -> {
              dialog.dismiss();
              handler.post(
                  () -> {
                    if (which == 0) ink.fit();
                    else if (which == 1) ink.fitWidth();
                    else if (which == 2) {
                      nativeInk.suspend();
                      if (save()) startActivity(new Intent(this, HistoryActivity.class));
                      else ink.post(() -> nativeInk.refresh());
                    } else if (which == 3) pair();
                    else if (which == 5) {
                      ReviewNotificationService.setEnabled(
                          this, !ReviewNotificationService.isEnabled(this));
                      status.setText(
                          ReviewNotificationService.isEnabled(this)
                              ? "Review notifications enabled"
                              : "Review notifications paused");
                    } else
                      dialogBuilder()
                          .setMessage("Clear your annotations?")
                          .setPositiveButton("Clear", (d, w) -> ink.clear())
                          .setNeutralButton("Discard review", (d, w) -> discard())
                          .setNegativeButton("Keep", null)
                          .show();
                  });
            })
        .show();
  }

  void showQueue() {
    if (busy || penActive() || restoreFailed) return;
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(1);
    content.setPadding(24, 16, 24, 16);
    TextView message = new TextView(this);
    message.setText("Loading documents…");
    content.addView(message);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    AlertDialog dialog =
        dialogBuilder()
            .setTitle("Documents")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show();
    busy = true;
    updateNavigation();
    worker.execute(
        () -> {
          try {
            JSONObject queue = new JSONObject(new String(request("/api/queue", null), "UTF-8"));
            JSONArray reviews = queue.getJSONArray("reviews");
            String expected =
                queue.isNull("activeReviewId") ? null : queue.optString("activeReviewId", null);
            updateUi(
                () -> {
                  if (!dialog.isShowing()) return;
                  message.setText(
                      reviews.length() == 0
                          ? "No pending documents."
                          : "Select a document. Your annotations stay saved.");
                  for (int i = 0; i < reviews.length(); i++) {
                    JSONObject review = reviews.optJSONObject(i);
                    if (review == null) continue;
                    String id = review.optString("id");
                    Button item = new Button(this);
                    item.setAllCaps(false);
                    item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                    item.setText(
                        review.optString("title", "Untitled")
                            + " · "
                            + review.optInt("pageCount", 1)
                            + " pages"
                            + (id.equals(reviewId) ? " · open" : "")
                            + (id.equals(expected) && !id.equals(reviewId)
                                ? " · desktop active"
                                : ""));
                    item.setOnClickListener(
                        v -> {
                          dialog.dismiss();
                          selectDocument(id, expected);
                        });
                    LinearLayout row = new LinearLayout(this);
                    row.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
                    Button remove = new Button(this);
                    remove.setAllCaps(false);
                    remove.setText("Remove");
                    remove.setOnClickListener(v -> {
                      dialog.dismiss();
                      handler.post(() -> confirmRemove(id, review.optString("title", "Untitled")));
                    });
                    row.addView(remove, new LinearLayout.LayoutParams(-2, -2));
                    content.addView(row, new LinearLayout.LayoutParams(-1, -2));
                  }
                });
          } catch (Exception error) {
            updateUi(
                () -> {
                  if (dialog.isShowing()) message.setText(error.getMessage());
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

  void confirmRemove(String id, String title) {
    if (busy || penActive() || restoreFailed) return;
    if (submissionAttempted || drafts().frozen(id).exists()) {
      status.setText("Retry Send before removing this document");
      return;
    }
    dialogBuilder()
        .setTitle("Remove document?")
        .setMessage("Remove \"" + title + "\" from the review queue? This cancels its review without submitting comments.")
        .setNegativeButton("Keep", null)
        .setPositiveButton("Remove", (d, w) -> handler.post(() -> removeQueuedDocument(id)))
        .show();
  }

  void removeQueuedDocument(String id) {
    if (busy || penActive() || restoreFailed || submissionAttempted) return;
    if (id.equals(reviewId)) {
      discard();
      return;
    }
    if (!save()) return;
    busy = true;
    updateNavigation();
    worker.execute(() -> {
      try {
        request("/api/reviews/" + id + "/cancel", new JSONObject());
        updateUi(() -> status.setText("Document removed from queue"));
      } catch (Exception error) {
        updateUi(() -> status.setText("Cannot remove document. " + error.getMessage()));
      } finally {
        updateUi(() -> {
          busy = false;
          updateNavigation();
          if (!stopped) showQueue();
        });
      }
    });
  }

  void selectDocument(String id, String expectedCurrent) {
    if (busy || penActive() || restoreFailed || (id.equals(reviewId) && id.equals(expectedCurrent)))
      return;
    if (submissionAttempted) {
      status.setText("Retry Send before switching documents");
      return;
    }
    if (!save()) return;
    final String outgoing = reviewId;
    busy = true;
    nativeInk.suspend();
    ink.locked = true;
    updateNavigation();
    status.setText("Opening document…");
    worker.execute(
        () -> {
          try {
            JSONObject metadata =
                new JSONObject(new String(request("/api/reviews/" + id, null), "UTF-8"));
            if (!id.equals(metadata.optString("id"))
                || !"pending".equals(metadata.optString("status")))
              throw new IOException("Document no longer pending");
            prepareDocument(metadata);
            request(
                "/api/reviews/" + id + "/activate",
                new JSONObject()
                    .put(
                        "expectedCurrentReviewId",
                        expectedCurrent == null ? JSONObject.NULL : expectedCurrent));
            updateUi(
                () -> {
                  try {
                    restoreDraft(drafts().draft(id));
                    if (!save()) throw new IOException("Could not save selected document");
                    status.setText(
                        reviewTitle
                            + (submissionAttempted ? " · Submission locked for retry" : ""));
                  } catch (Exception error) {
                    try {
                      if (!outgoing.isEmpty()) {
                        restoreDraft(drafts().draft(outgoing));
                        save();
                      } else {
                        reviewId = "";
                        submissionId = "";
                        pages.clear();
                        ink.page = null;
                        ink.strokes.clear();
                        ink.invalidate();
                      }
                    } catch (Exception restoreError) {
                      restoreFailed = true;
                    }
                    status.setText(
                        "Could not open document. Draft retained. " + error.getMessage());
                  }
                });
          } catch (Exception error) {
            updateUi(
                () -> status.setText("Document unchanged. Draft retained. " + error.getMessage()));
          } finally {
            updateUi(
                () -> {
                  busy = false;
                  ink.locked = submissionAttempted;
                  ink.post(() -> nativeInk.refresh());
                  updateNavigation();
                });
          }
        });
  }

  DraftFiles drafts() {
    return new DraftFiles(getFilesDir());
  }

  void validateSavedDocument(String id) throws Exception {
    JSONObject saved =
        new JSONObject(
            new String(java.nio.file.Files.readAllBytes(drafts().draft(id).toPath()), "UTF-8"));
    if (!id.equals(saved.getString("reviewId")))
      throw new IOException("Saved draft belongs to another document");
    saved.getString("submissionId");
    JSONArray entries = saved.optJSONArray("pages");
    if (entries == null)
      entries =
          new JSONArray()
              .put(
                  new JSONObject()
                      .put("file", "page.png")
                      .put("strokes", saved.getJSONArray("strokes")));
    if (entries.length() == 0 || entries.length() > 200)
      throw new IOException("Invalid saved page count");
    for (int i = 0; i < entries.length(); i++) {
      JSONObject entry = entries.getJSONObject(i);
      String name = entry.getString("file");
      if (!name.matches("[A-Za-z0-9_.-]+") || name.equals(".") || name.equals(".."))
        throw new IOException("Invalid cached page name");
      entry.getJSONArray("strokes");
      BitmapFactory.Options size = new BitmapFactory.Options();
      size.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(new File(getFilesDir(), name).toString(), size);
      CanvasBounds.around(size.outWidth, size.outHeight);
      JSONObject bounds = entry.optJSONObject("canvasBounds");
      if (bounds != null)
        new CanvasBounds(
                bounds.getInt("x"),
                bounds.getInt("y"),
                bounds.getInt("width"),
                bounds.getInt("height"))
            .validate(size.outWidth, size.outHeight);
    }
  }

  void prepareDocument(JSONObject r) throws Exception {
    String id = r.getString("id");
    if (drafts().draft(id).exists()) {
      validateSavedDocument(id);
      return;
    }
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
    JSONArray saved = new JSONArray();
    for (int i = 0; i < metadata.length(); i++) {
      JSONObject m = metadata.getJSONObject(i);
      if (m.getInt("pageIndex") != i + 1) throw new IOException("Invalid page order");
      byte[] png =
          request("/api/reviews/" + id + "/image" + (isDocument ? "?page=" + (i + 1) : ""), null);
      BitmapFactory.Options dimensions = new BitmapFactory.Options();
      dimensions.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(png, 0, png.length, dimensions);
      if (dimensions.outWidth != m.getInt("width") || dimensions.outHeight != m.getInt("height"))
        throw new IOException("Unsupported page size");
      CanvasBounds bounds = CanvasBounds.around(dimensions.outWidth, dimensions.outHeight);
      String file = id + "-page-" + (i + 1) + ".png";
      atomicWrite(file, png);
      saved.put(
          new JSONObject()
              .put("file", file)
              .put("strokes", new JSONArray())
              .put("includeCanvas", true)
              .put("canvasBounds", boundsJson(bounds))
              .put("visited", false));
      final int loaded = i + 1, total = metadata.length();
      updateUi(() -> status.setText("Loading document " + loaded + " / " + total));
    }
    JSONObject draft =
        new JSONObject()
            .put("version", 4)
            .put("reviewId", id)
            .put("submissionId", UUID.randomUUID().toString())
            .put("submissionAttempted", false)
            .put("documentReview", isDocument)
            .put("title", r.optString("title", "Review"))
            .put("pagePosition", 0)
            .put("pages", saved);
    drafts().write(drafts().draft(id), draft.toString().getBytes("UTF-8"));
  }

  void pair() {
    if (busy) return;
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(1);
    form.setPadding(24, 12, 24, 12);
    EditText url = new EditText(this);
    url.setHint("http://Mac-Wi-Fi-address:4317");
    url.setSingleLine();
    url.setText(base);
    EditText key = new EditText(this);
    key.setHint("Pairing token");
    key.setSingleLine();
    key.setInputType(129);
    key.setText(token);
    TextView discoveryStatus = new TextView(this);
    discoveryStatus.setText("Looking for desktops on this Wi-Fi…");
    LinearLayout found = new LinearLayout(this);
    found.setOrientation(1);
    form.addView(url);
    form.addView(key);
    form.addView(discoveryStatus);
    form.addView(found);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    dialogBuilder()
        .setTitle("Connect to desktop")
        .setMessage(
            "Connect tablet and Mac to the same trusted Wi-Fi. Select your desktop below, or enter"
                + " its URL. Existing token stays saved. USB URL also works.")
        .setView(scroll)
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
                String pendingId = reviewId;
                if ((!pendingId.isEmpty() || drafts().hasDrafts()) && !newToken.equals(token))
                  throw new Exception("Finish current review before changing token");
                busy = true;
                updateNavigation();
                status.setText("Checking desktop connection…");
                worker.execute(
                    () -> {
                      try {
                        JSONObject health =
                            new JSONObject(
                                new String(
                                    requestAt(candidate, newToken, "/api/health", null), "UTF-8"));
                        if (!health.optBoolean("ok"))
                          throw new IOException("Desktop health check failed");
                        if (!pendingId.isEmpty()) {
                          JSONObject pending =
                              new JSONObject(
                                  new String(
                                      requestAt(
                                          candidate, newToken, "/api/reviews/" + pendingId, null),
                                      "UTF-8"));
                          if (!pendingId.equals(pending.optString("id")))
                            throw new IOException("Desktop does not contain current review");
                        }
                        updateUi(
                            () -> {
                              base = candidate;
                              token = newToken;
                              getPreferences(0)
                                  .edit()
                                  .putString("base", base)
                                  .putString("token", token)
                                  .apply();
                              nextPoll = 0;
                              ReviewNotificationService.start(MainActivity.this);
                              status.setText("Desktop connected");
                            });
                      } catch (Exception error) {
                        updateUi(() -> status.setText(error.getMessage()));
                      } finally {
                        updateUi(
                            () -> {
                              busy = false;
                              updateNavigation();
                            });
                      }
                    });
              } catch (Exception error) {
                status.setText(error.getMessage());
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
    wifiDiscovery =
        new WifiDiscovery(
            this,
            new WifiDiscovery.Listener() {
              public void status(String text) {
                discoveryStatus.setText(text);
              }

              public void found(String name, String address) {
                discoveryStatus.setText("Select your Mac, then Connect:");
                Button desktop = new Button(MainActivity.this);
                desktop.setText(name + "\n" + address);
                desktop.setOnClickListener(v -> url.setText(address));
                found.addView(desktop);
              }
            });
    wifiDiscovery.start();
  }

  byte[] request(String path, JSONObject body) throws Exception {
    return requestAt(base, token, path, body);
  }

  byte[] requestAt(String server, String key, String path, JSONObject body) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
    activeConnection = c;
    if (destroyed) {
      c.disconnect();
      throw new IOException("Activity closed");
    }
    c.setConnectTimeout(7000);
    c.setReadTimeout(15000);
    c.setInstanceFollowRedirects(false);
    c.setRequestProperty("Authorization", "Bearer " + key);
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
                    if (reviewId.isEmpty()) {
                      status.setText("Connected to desktop");
                      showInbox(
                          "Empty inbox",
                          "All documents handled. New documents from Codex will appear here.\n\n"
                              + "Your sent pages remain in History.");
                    }
                  });
              return;
            }
            String id = r.getString("id");
            if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid review ID");
            if (id.equals(reviewId)) return;
            if (!reviewId.isEmpty()) {
              updateUi(
                  () -> status.setText("Draft saved. Select another document from Documents."));
              return;
            }
            if (!"pending".equals(r.optString("status"))) return;
            prepareDocument(r);
            updateUi(
                () -> {
                  try {
                    restoreDraft(drafts().draft(id));
                    save();
                    status.setText(reviewTitle);
                  } catch (Exception error) {
                    status.setText("Cannot open document: " + error.getMessage());
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
                  if (!clearReviewFiles()) return;
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
    navigation.setVisibility(hasReview ? View.VISIBLE : View.GONE);
    undo.setEnabled(hasReview && !busy && !submissionAttempted);
    send.setText("Send");
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
    if (state.bounds == null)
      state.bounds =
          state.includeCanvas
              ? CanvasBounds.around(bitmap.getWidth(), bitmap.getHeight())
              : new CanvasBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
    state.bounds.validate(bitmap.getWidth(), bitmap.getHeight());
    emptyInbox.setVisibility(View.GONE);
    ink.setVisibility(View.VISIBLE);
    ink.bounds = state.bounds;
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

  boolean clearReviewFiles() {
    try {
      drafts().clear(reviewId);
    } catch (IOException error) {
      status.setText(error.getMessage());
      return false;
    }
    for (PageState page : pages) deleteFile(page.file);
    reviewId = "";
    submissionId = "";
    pages.clear();
    pagePosition = 0;
    submissionAttempted = false;
    documentReview = false;
    nativeInk.suspend();
    Bitmap completed = ink.page;
    ink.page = null;
    ink.strokes.clear();
    ink.active = null;
    ink.locked = true;
    ink.invalidate();
    if (completed != null) completed.recycle();
    nextPoll = 0;
    showInbox("Checking inbox…", "Your document is saved. Looking for the next review.");
    updateNavigation();
    return true;
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
          File frozen = drafts().frozen(id);
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
                CanvasBounds bounds =
                    state.bounds == null
                        ? new CanvasBounds(0, 0, original.getWidth(), original.getHeight())
                        : state.bounds;
                bounds.validate(original.getWidth(), original.getHeight());
                Bitmap composite;
                if (state.includeCanvas) {
                  composite =
                      Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888);
                  Canvas background = new Canvas(composite);
                  background.drawColor(Color.WHITE);
                  background.drawBitmap(original, -bounds.x, -bounds.y, null);
                } else composite = original.copy(Bitmap.Config.ARGB_8888, true);
                original.recycle();
                try {
                  Canvas outputCanvas = new Canvas(composite);
                  if (state.includeCanvas) outputCanvas.translate(-bounds.x, -bounds.y);
                  drawStoredInk(outputCanvas, state.strokes);
                  ByteArrayOutputStream out = new ByteArrayOutputStream();
                  composite.compress(Bitmap.CompressFormat.PNG, 100, out);
                  payloadEstimate +=
                      ((out.size() + 2L) / 3L) * 4L
                          + state.strokes.toString().getBytes("UTF-8").length
                          + 128;
                  if (payloadEstimate > 24 * 1024 * 1024)
                    throw new IOException("Document exceeds 24 MiB; draft kept");
                  JSONObject pageFeedback =
                      new JSONObject()
                          .put("pageIndex", i + 1)
                          .put(
                              "compositeBase64",
                              android.util.Base64.encodeToString(
                                  out.toByteArray(), android.util.Base64.NO_WRAP))
                          .put("strokes", state.strokes);
                  if (state.includeCanvas) pageFeedback.put("canvasBounds", boundsJson(bounds));
                  feedback.put(pageFeedback);
                } finally {
                  composite.recycle();
                }
              }
              body = new JSONObject().put("submissionId", submissionId);
              if (documentReview) body.put("pages", feedback);
              else
                body.put("compositeBase64", feedback.getJSONObject(0).getString("compositeBase64"))
                    .put("strokes", feedback.getJSONObject(0).getJSONArray("strokes"));
              if (!documentReview && feedback.getJSONObject(0).has("canvasBounds"))
                body.put("canvasBounds", feedback.getJSONObject(0).getJSONObject("canvasBounds"));
              byte[] encoded = body.toString().getBytes("UTF-8");
              if (encoded.length > 24 * 1024 * 1024)
                throw new IOException(
                    "Document exceeds 24 MiB. Draft kept; ask desktop to split review");
              atomicWrite("submission-" + id + ".json", encoded);
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
                  if (!clearReviewFiles()) return;
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
                .put("includeCanvas", page.includeCanvas)
                .put(
                    "canvasBounds", page.bounds == null ? JSONObject.NULL : boundsJson(page.bounds))
                .put("visited", page.visited));
      JSONObject draft =
          new JSONObject()
              .put("version", 4)
              .put("reviewId", reviewId)
              .put("submissionId", submissionId)
              .put("submissionAttempted", submissionAttempted)
              .put("documentReview", documentReview)
              .put("title", reviewTitle)
              .put("pagePosition", pagePosition)
              .put("pages", saved);
      drafts().saveActive(reviewId, draft.toString().getBytes("UTF-8"));
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
      JSONObject pointer =
          new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8"));
      String activeId = pointer.getString("reviewId");
      restoreDraft(drafts().activeSnapshot(activeId));
      if (!activeId.equals(reviewId)) throw new IOException("Active draft ID mismatch");
      drafts().migrateFrozen(reviewId);
      if (!save()) throw new IOException("Cannot save restored draft");
    } catch (Exception error) {
      restoreFailed = true;
      status.setText("Cannot restore draft: " + error.getMessage());
    }
  }

  void restoreDraft(File file) throws Exception {
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
      legacy.includeCanvas = !submissionAttempted;
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
        page.includeCanvas = entry.optBoolean("includeCanvas", !submissionAttempted);
        JSONObject b = entry.optJSONObject("canvasBounds");
        if (b != null)
          page.bounds =
              new CanvasBounds(b.getInt("x"), b.getInt("y"), b.getInt("width"), b.getInt("height"));
        pages.add(page);
      }
    }
    if (pages.isEmpty()) throw new IOException("Draft has no pages");
    showPage(Math.max(0, Math.min(pages.size() - 1, draft.optInt("pagePosition", 0))));
    status.setText(
        submissionAttempted
            ? "Restored pending submission. Send retries unchanged document"
            : "Restored document annotations");
  }

  static JSONObject boundsJson(CanvasBounds bounds) throws JSONException {
    return new JSONObject()
        .put("x", bounds.x)
        .put("y", bounds.y)
        .put("width", bounds.width)
        .put("height", bounds.height);
  }

  static class PageState {
    String file;
    CanvasBounds bounds;
    boolean includeCanvas = true;
    JSONArray strokes = new JSONArray();
    boolean visited;
  }

  class InkView extends View {
    Bitmap page;
    CanvasBounds bounds;
    final PageSwipe swipe = new PageSwipe();
    float defaultScale;
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
        scale =
            Math.min((float) getWidth() / page.getWidth(), (float) getHeight() / page.getHeight());

        defaultScale = scale;
        dx = (getWidth() - page.getWidth() * scale) / 2;
        dy = 0;
        invalidate();
        postOnAnimation(() -> post(() -> nativeInk.refresh()));
      }
    }

    void fitWidth() {
      if (page == null || penActive()) return;
      nativeInk.suspend();
      scale = (float) getWidth() / page.getWidth();
      dx = dy = 0;
      invalidate();
      postOnAnimation(() -> post(() -> nativeInk.refresh()));
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
      c.clipRect(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height);
      bitmapPaint.setFilterBitmap(Math.abs(scale - Math.round(scale)) > .0001f);
      c.drawBitmap(page, 0, 0, bitmapPaint);
      Paint edge = new Paint();
      edge.setColor(Color.LTGRAY);
      edge.setStyle(Paint.Style.STROKE);
      edge.setStrokeWidth(1 / scale);
      c.drawRect(0, 0, page.getWidth(), page.getHeight(), edge);
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
        swipe.cancel();
        if (stylusPointerId != -1) finishStylus(true);
        suppressNavigation = false;
        zoom.onTouchEvent(event);
        return true;
      }
      if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
          && isPen(event, actionIndex)) {
        requestUnbufferedDispatch(event);
        suppressNavigation = true;
        swipe.cancel();
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
        swipe.cancel();
        if (action == MotionEvent.ACTION_UP) suppressNavigation = false;
        return true;
      }
      for (int index = 0; index < event.getPointerCount(); index++) {
        if (isPen(event, index)) return true;
      }
      if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) swipe.cancel();
      zoom.onTouchEvent(event);
      if (action == MotionEvent.ACTION_DOWN) {
        swipe.begin(
            event.getX(),
            event.getY(),
            event.getEventTime(),
            Math.abs(scale / defaultScale - 1) <= .08f && !penActive());
        lastX = event.getX();
        lastY = event.getY();
      } else if (action == MotionEvent.ACTION_MOVE) {
        if (event.getPointerCount() == 1
            && !zoom.isInProgress()
            && !swipe.horizontal(event.getX(), event.getY())) {
          dx += event.getX() - lastX;
          dy += event.getY() - lastY;
          invalidate();
        }
        lastX = event.getX();
        lastY = event.getY();
      } else if (action == MotionEvent.ACTION_UP) {
        int direction =
            swipe.finish(
                event.getX(), event.getY(), event.getEventTime(), Math.max(100, getWidth() * .18f));
        if (direction != 0) navigate(direction);
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
            Math.max(bounds.x, Math.min(bounds.x + bounds.width, x)),
            Math.max(bounds.y, Math.min(bounds.y + bounds.height, y)),
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
