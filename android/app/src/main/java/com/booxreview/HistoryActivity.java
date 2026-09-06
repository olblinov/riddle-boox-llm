package com.booxreview;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Separate read-only screen. Never creates a BOOX raw drawing helper or accesses draft files. */
public final class HistoryActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService localReader = Executors.newSingleThreadExecutor();
  private int viewGeneration;
  private volatile boolean destroyed;
  private volatile HttpURLConnection connection;
  private HistoryCache cache;
  private TextView status, indicator;
  private LinearLayout content, navigation;
  private Button previous, next;
  private String selectedId;
  private int page = 1;
  private boolean syncing;
  private ImagePage image;

  public void onCreate(Bundle state) {
    super.onCreate(state);
    cache = new HistoryCache(getFilesDir());
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(1);
    root.setBackgroundColor(Color.WHITE);
    LinearLayout bar = new LinearLayout(this);
    button(bar, "Back to review", v -> finish());
    button(
        bar,
        "Documents",
        v -> {
          selectedId = null;
          showList();
        });
    button(bar, "Refresh", v -> sync());
    root.addView(bar);
    status = new TextView(this);
    status.setTextSize(16);
    status.setPadding(12, 8, 12, 8);
    root.addView(status);
    navigation = new LinearLayout(this);
    previous = button(navigation, "Previous", v -> showPage(page - 1));
    indicator = new TextView(this);
    indicator.setGravity(Gravity.CENTER);
    navigation.addView(indicator, new LinearLayout.LayoutParams(0, -1, 1));
    next = button(navigation, "Next", v -> showPage(page + 1));
    root.addView(navigation);
    content = new LinearLayout(this);
    content.setOrientation(1);
    root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    showList();
    if (state != null && state.getString("selectedId") != null) {
      selectedId = state.getString("selectedId");
      showPage(state.getInt("page", 1));
    }
    sync();
  }

  Button button(LinearLayout parent, String title, View.OnClickListener action) {
    Button b = new Button(this);
    b.setText(title);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setOnClickListener(action);
    parent.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    return b;
  }

  void ui(Runnable task) {
    runOnUiThread(
        () -> {
          if (!destroyed) task.run();
        });
  }

  void showList() {
    final int generation = ++viewGeneration;
    releaseImage();
    content.removeAllViews();
    navigation.setVisibility(View.GONE);
    status.setText("Loading cached history…");
    localReader.execute(
        () -> {
          try {
            List<HistoryCache.Entry> entries = cache.list(System.currentTimeMillis());
            ArrayList<String> titles = new ArrayList<>();
            for (HistoryCache.Entry entry : entries)
              titles.add(
                  entry.title
                      + "\n"
                      + entry.submittedAt.substring(0, 10)
                      + " • "
                      + entry.pageCount
                      + " pages");
            ui(
                () -> {
                  if (generation != viewGeneration || selectedId != null) return;
                  ListView list = new ListView(this);
                  list.setAdapter(
                      new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titles));
                  list.setOnItemClickListener(
                      (p, v, pos, id) -> {
                        selectedId = entries.get(pos).id;
                        showPage(1);
                      });
                  content.removeAllViews();
                  content.addView(list, new LinearLayout.LayoutParams(-1, -1));
                  status.setText(
                      entries.isEmpty()
                          ? "No cached sent documents. Refresh when connected."
                          : "Sent documents • available offline for 90 days");
                });
          } catch (Exception error) {
            ui(
                () -> {
                  if (generation == viewGeneration)
                    status.setText("Cannot read history: " + error.getMessage());
                });
          }
        });
  }

  void showPage(int number) {
    if (selectedId == null) return;
    final String id = selectedId;
    final int generation = ++viewGeneration;
    previous.setEnabled(false);
    next.setEnabled(false);
    status.setText("Opening cached page…");
    localReader.execute(
        () -> {
          try {
            HistoryCache.Entry entry = cache.get(id);
            if (number < 1 || number > entry.pageCount)
              throw new IOException("Invalid page number");
            File file = cache.page(id, number);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.toString(), bounds);
            if (bounds.outWidth <= 0
                || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > 16000000)
              throw new IOException("Unsupported cached image");
            Bitmap bitmap = BitmapFactory.decodeFile(file.toString());
            if (bitmap == null) throw new IOException("Cached image unreadable");
            runOnUiThread(
                () -> {
                  if (destroyed || generation != viewGeneration || !id.equals(selectedId)) {
                    bitmap.recycle();
                    return;
                  }
                  releaseImage();
                  content.removeAllViews();
                  image = new ImagePage(bitmap);
                  content.addView(image, new LinearLayout.LayoutParams(-1, -1));
                  page = number;
                  navigation.setVisibility(View.VISIBLE);
                  previous.setEnabled(page > 1);
                  next.setEnabled(page < entry.pageCount);
                  indicator.setText("Page " + page + " / " + entry.pageCount);
                  status.setText(entry.title + " • read-only");
                });
          } catch (Exception error) {
            ui(
                () -> {
                  if (generation == viewGeneration) {
                    status.setText("Cannot open history: " + error.getMessage());
                    previous.setEnabled(false);
                    next.setEnabled(false);
                  }
                });
          }
        });
  }

  byte[] get(String path) throws Exception {
    SharedPreferences prefs = getSharedPreferences("MainActivity", MODE_PRIVATE);
    String base = prefs.getString("base", "");
    if (base.isEmpty()) throw new IOException("Not paired");
    HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
    connection = c;
    c.setConnectTimeout(5000);
    c.setReadTimeout(15000);
    c.setInstanceFollowRedirects(false);
    c.setRequestProperty("Authorization", "Bearer " + prefs.getString("token", ""));
    try {
      if (destroyed) throw new IOException("History closed");
      int code = c.getResponseCode();
      if (code != 200) throw new IOException("HTTP " + code);
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
          out.write(b, 0, n);
          if (out.size() > 32 * 1024 * 1024) throw new IOException("History response too large");
        }
        return out.toByteArray();
      }
    } finally {
      c.disconnect();
      if (connection == c) connection = null;
    }
  }

  void sync() {
    if (syncing) return;
    syncing = true;
    status.setText("Refreshing history… Cached copies remain available.");
    worker.execute(
        () -> {
          try {
            JSONObject result = new JSONObject(new String(get("/api/history"), "UTF-8"));
            JSONArray reviews = result.getJSONArray("reviews");
            for (int i = 0; i < reviews.length() && !destroyed; i++) {
              JSONObject metadata = reviews.getJSONObject(i);
              String id = metadata.getString("id"), date = metadata.getString("submittedAt");
              if (!id.matches("[A-Za-z0-9_-]{1,128}")
                  || !HistoryCache.recent(date, System.currentTimeMillis())) continue;
              try {
                cache.verify(id);
                continue;
              } catch (IOException absent) {
              }
              JSONObject feedback =
                  new JSONObject(new String(get("/api/reviews/" + id + "/feedback"), "UTF-8"));
              if (!"submitted".equals(feedback.optString("status"))) continue;
              cacheFeedback(cache, id, metadata.optString("title", "Review"), date, feedback);
            }
            ui(
                () -> {
                  if (selectedId == null) showList();
                });
          } catch (Exception error) {
            ui(
                () ->
                    status.setText(
                        "Offline history available. Refresh failed: " + error.getMessage()));
          } finally {
            ui(() -> syncing = false);
          }
        });
  }

  static void cacheFeedback(
      HistoryCache cache, String id, String title, String submittedAt, JSONObject feedback)
      throws Exception {
    JSONArray pages = feedback.optJSONArray("pages");
    ArrayList<byte[]> images = new ArrayList<>();
    if (pages == null)
      images.add(
          android.util.Base64.decode(
              feedback.getString("compositeBase64"), android.util.Base64.DEFAULT));
    else
      for (int i = 0; i < pages.length(); i++) {
        JSONObject p = pages.getJSONObject(i);
        if (p.getInt("pageIndex") != i + 1) throw new IOException("History pages out of order");
        images.add(
            android.util.Base64.decode(
                p.getString("compositeBase64"), android.util.Base64.DEFAULT));
      }
    for (byte[] png : images) {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(png, 0, png.length, options);
      if (options.outWidth <= 0
          || options.outHeight <= 0
          || (long) options.outWidth * options.outHeight > 16000000)
        throw new IOException("Unsupported history image");
      Bitmap decoded = BitmapFactory.decodeByteArray(png, 0, png.length);
      if (decoded == null) throw new IOException("Corrupt history image");
      decoded.recycle();
    }
    cache.put(id, title, submittedAt, images);
    cache.prune(System.currentTimeMillis());
  }

  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("selectedId", selectedId);
    out.putInt("page", page);
  }

  public void onBackPressed() {
    if (selectedId != null) {
      selectedId = null;
      showList();
    } else super.onBackPressed();
  }

  void releaseImage() {
    if (image != null) {
      image.bitmap.recycle();
      image = null;
    }
  }

  protected void onDestroy() {
    destroyed = true;
    worker.shutdownNow();
    localReader.shutdownNow();
    viewGeneration++;
    if (connection != null) connection.disconnect();
    releaseImage();
    super.onDestroy();
  }

  final class ImagePage extends View {
    final Bitmap bitmap;
    final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    float scale, dx, dy, lastX, lastY;
    final ScaleGestureDetector zoom;

    ImagePage(Bitmap bitmap) {
      super(HistoryActivity.this);
      this.bitmap = bitmap;
      setBackgroundColor(Color.WHITE);
      zoom =
          new ScaleGestureDetector(
              HistoryActivity.this,
              new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                public boolean onScale(ScaleGestureDetector d) {
                  float old = scale;
                  scale = Math.max(.1f, Math.min(6f, scale * d.getScaleFactor()));
                  dx = d.getFocusX() - (d.getFocusX() - dx) * scale / old;
                  dy = d.getFocusY() - (d.getFocusY() - dy) * scale / old;
                  invalidate();
                  return true;
                }
              });
    }

    protected void onSizeChanged(int w, int h, int ow, int oh) {
      scale = (float) w / bitmap.getWidth();
      dx = dy = 0;
    }

    protected void onDraw(Canvas canvas) {
      canvas.save();
      canvas.translate(dx, dy);
      canvas.scale(scale, scale);
      paint.setFilterBitmap(Math.abs(scale - Math.round(scale)) > .0001f);
      canvas.drawBitmap(bitmap, 0, 0, paint);
      canvas.restore();
    }

    public boolean onTouchEvent(MotionEvent e) {
      zoom.onTouchEvent(e);
      if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
        lastX = e.getX();
        lastY = e.getY();
      } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
        if (e.getPointerCount() == 1 && !zoom.isInProgress()) {
          dx += e.getX() - lastX;
          dy += e.getY() - lastY;
          invalidate();
        }
        lastX = e.getX();
        lastY = e.getY();
      }
      return true;
    }
  }
}
