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
  TextView status;
  Button send;
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
    status.setTextSize(17);
    status.setPadding(14, 12, 14, 12);
    status.setText("Pair with desktop to receive a page");
    root.addView(status);
    LinearLayout bar = new LinearLayout(this);
    button(bar, "Pair", v -> pair());
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
            new AlertDialog.Builder(this)
                .setMessage("Clear your annotations?")
                .setPositiveButton("Clear", (d, w) -> ink.clear())
                .setNeutralButton("Discard review", (d, w) -> discard())
                .setNegativeButton("Keep", null)
                .show();
        });
    button(bar, "Fit", v -> ink.fit());
    send = button(bar, "Send", v -> submit());
    send.setEnabled(false);
    root.addView(bar);
    ink = new InkView(this);
    root.addView(ink, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    restore();
    if (base.isEmpty()) pair();
  }

  Button button(LinearLayout bar, String label, View.OnClickListener action) {
    Button b = new Button(this);
    b.setText(label);
    b.setOnClickListener(action);
    bar.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    return b;
  }

  protected void onResume() {
    super.onResume();
    stopped = false;
    handler.post(poll);
  }

  protected void onPause() {
    super.onPause();
    stopped = true;
    handler.removeCallbacks(poll);
    save();
  }

  protected void onDestroy() {
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
    new AlertDialog.Builder(this)
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
            byte[] png = request("/api/reviews/" + id + "/image", null);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
            if (bounds.outWidth <= 0
                || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > 16000000)
              throw new IOException("Unsupported page size");
            Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
            updateUi(
                () -> {
                  reviewId = id;
                  submissionId = UUID.randomUUID().toString();
                  submissionAttempted = false;
                  ink.setPage(bmp);
                  try {
                    try (FileOutputStream out = openFileOutput("page.png", 0)) {
                      out.write(png);
                    }
                  } catch (Exception e) {
                    status.setText("Cannot save page: " + e.getMessage());
                  }
                  save();
                  status.setText(
                      r.optString("title", "Review") + " • Write with pen, move/zoom with fingers");
                  send.setEnabled(true);
                });
          } catch (Exception e) {
            updateUi(() -> status.setText("Connection failed. Draft kept. " + e.getMessage()));
            nextPoll = System.currentTimeMillis() + 8000;
          } finally {
            updateUi(() -> busy = false);
          }
        });
  }

  void discard() {
    if (busy || reviewId.isEmpty()) return;
    busy = true;
    ink.locked = true;
    worker.execute(
        () -> {
          try {
            request("/api/reviews/" + reviewId + "/cancel", new JSONObject());
            updateUi(
                () -> {
                  reviewId = "";
                  submissionId = "";
                  deleteFile("draft.json");
                  deleteFile("page.png");
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
                });
          } finally {
            updateUi(() -> busy = false);
          }
        });
  }

  void submit() {
    if (busy || reviewId.isEmpty() || ink.page == null || ink.stylusPointerId != -1) return;
    busy = true;
    send.setEnabled(false);
    ink.locked = true;
    status.setText("Sending annotations…");
    submissionAttempted = true;
    save();
    final String id = reviewId;
    final Bitmap composite = ink.composite();
    final JSONArray strokes = ink.json();
    worker.execute(
        () -> {
          try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            composite.compress(Bitmap.CompressFormat.PNG, 100, out);
            JSONObject body =
                new JSONObject()
                    .put("submissionId", submissionId)
                    .put(
                        "compositeBase64",
                        android.util.Base64.encodeToString(
                            out.toByteArray(), android.util.Base64.NO_WRAP))
                    .put("strokes", strokes);
            request("/api/reviews/" + id + "/feedback", body);
            updateUi(
                () -> {
                  reviewId = "";
                  submissionId = "";
                  deleteFile("draft.json");
                  deleteFile("page.png");
                  status.setText("Sent to Codex. Waiting for next page");
                  ink.locked = true;
                });
          } catch (Exception e) {
            updateUi(
                () -> {
                  status.setText(
                      "Send not confirmed. Draft kept unchanged. Tap Send to retry. "
                          + e.getMessage());
                  send.setEnabled(true);
                  ink.locked = true;
                });
          } finally {
            composite.recycle();
            updateUi(() -> busy = false);
          }
        });
  }

  void save() {
    if (reviewId.isEmpty() || ink == null) return;
    try {
      JSONObject d =
          new JSONObject()
              .put("reviewId", reviewId)
              .put("submissionId", submissionId)
              .put("submissionAttempted", submissionAttempted)
              .put("strokes", ink.json());
      File tmp = new File(getFilesDir(), "draft.tmp");
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        out.write(d.toString().getBytes("UTF-8"));
        out.getFD().sync();
      }
      if (!tmp.renameTo(new File(getFilesDir(), "draft.json")))
        throw new IOException("Draft rename failed");
    } catch (Exception e) {
      status.setText("Draft save failed: " + e.getMessage());
    }
  }

  void restore() {
    try {
      File file = new File(getFilesDir(), "draft.json");
      if (!file.exists()) return;
      byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
      JSONObject d = new JSONObject(new String(bytes, "UTF-8"));
      Bitmap bmp = BitmapFactory.decodeFile(new File(getFilesDir(), "page.png").toString());
      if (bmp == null) return;
      reviewId = d.getString("reviewId");
      submissionId = d.getString("submissionId");
      ink.setPage(bmp);
      ink.load(d.getJSONArray("strokes"));
      submissionAttempted = d.optBoolean("submissionAttempted", false);
      ink.locked = submissionAttempted;
      send.setEnabled(true);
      status.setText(
          submissionAttempted
              ? "Restored pending submission. Tap Send to retry unchanged feedback"
              : "Restored annotations. Continue writing or Send");
    } catch (Exception e) {
      status.setText("Cannot restore draft: " + e.getMessage());
    }
  }

  class InkView extends View {
    Bitmap page;
    ArrayList<Stroke> strokes = new ArrayList<>();
    Stroke active;
    Paint paint = new Paint(3);
    float scale = 1, dx = 0, dy = 0, lastX, lastY;
    boolean locked = false;
    int stylusPointerId = -1;
    boolean erasing = false, suppressNavigation = false;
    ScaleGestureDetector zoom;

    InkView(Context c) {
      super(c);
      setBackgroundColor(Color.LTGRAY);
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
      if (stylusPointerId != -1) return;
      if (page != null && getWidth() > 0) {
        scale =
            Math.min((float) getWidth() / page.getWidth(), (float) getHeight() / page.getHeight());
        dx = (getWidth() - page.getWidth() * scale) / 2;
        dy = (getHeight() - page.getHeight() * scale) / 2;
        invalidate();
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
      c.drawBitmap(page, 0, 0, null);
      drawInk(c);
      c.restore();
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
      if (locked || stylusPointerId != -1) return;
      if (!strokes.isEmpty()) strokes.remove(strokes.size() - 1);
      save();
      invalidate();
    }

    void clear() {
      if (locked || stylusPointerId != -1) return;
      strokes.clear();
      save();
      invalidate();
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
