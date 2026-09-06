package com.booxreview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only queue watcher. It never activates a review or changes a draft. */
public final class ReviewNotificationService extends Service {
  private static final String PREFERENCES = "ReviewNotifications";
  private static final String ENABLED = "notifications_enabled";
  private static final String STOP = "com.booxreview.STOP_REVIEW_NOTIFICATIONS";
  private static final String WATCH_CHANNEL = "review_watcher";
  private static final String READY_CHANNEL = "review_ready";
  private static final int WATCH_NOTIFICATION = 7101;
  private static final int READY_NOTIFICATION = 7102;
  private static final long POLL_SECONDS = 15;
  private static final int MAX_SEEN = 4096;
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
  private volatile boolean stopped;
  private volatile HttpURLConnection connection;
  private boolean scheduled;
  private long retrySeconds = POLL_SECONDS;
  private NotificationManager notifications;

  private static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
  }

  private static SharedPreferences pairing(Context context) {
    // Activity.getPreferences() uses the activity's local class name.
    return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
  }

  public static boolean isEnabled(Context context) {
    return preferences(context).getBoolean(ENABLED, true);
  }

  public static void setEnabled(Context context, boolean enabled) {
    preferences(context).edit().putBoolean(ENABLED, enabled).apply();
    if (enabled) start(context);
    else stop(context);
  }

  /** A persisted disabled setting is respected by pairing and activity startup hooks. */
  public static void start(Context context) {
    SharedPreferences pair = pairing(context);
    if (!isEnabled(context)
        || pair.getString("base", "").isEmpty()
        || pair.getString("token", "").isEmpty()) return;
    try {
      context.startForegroundService(new Intent(context, ReviewNotificationService.class));
    } catch (IllegalStateException | SecurityException error) {
      // Newer Android versions can reject background starts. Opening the app retries.
      Log.w("BOOX Review", "Review notifications could not start");
    }
  }

  /** Stop watching without changing the user's enabled setting. */
  public static void stop(Context context) {
    context.stopService(new Intent(context, ReviewNotificationService.class));
  }

  @Override
  public void onCreate() {
    super.onCreate();
    notifications = getSystemService(NotificationManager.class);
    NotificationChannel watcher =
        new NotificationChannel(WATCH_CHANNEL, "Review connection", NotificationManager.IMPORTANCE_LOW);
    watcher.setDescription("Keeps the desktop review connection available in the background");
    watcher.setShowBadge(false);
    notifications.createNotificationChannel(watcher);
    NotificationChannel ready =
        new NotificationChannel(READY_CHANNEL, "New reviews", NotificationManager.IMPORTANCE_DEFAULT);
    ready.setDescription("A new document is ready for your pen comments");
    notifications.createNotificationChannel(ready);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            2,
            new Intent(this, ReviewNotificationService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    startForeground(
        WATCH_NOTIFICATION,
        new Notification.Builder(this, WATCH_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("BOOX review notifications")
            .setContentText("Watching for new documents")
            .setContentIntent(openDocuments())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null, "Stop notifications", stop).build())
            .build());
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && STOP.equals(intent.getAction())) {
      preferences(this).edit().putBoolean(ENABLED, false).apply();
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!isEnabled(this) || pairing(this).getString("token", "").isEmpty()) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!scheduled) {
      scheduled = true;
      worker.execute(this::poll);
    }
    return START_STICKY;
  }

  private PendingIntent openDocuments() {
    Intent intent =
        new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_documents", true);
    return PendingIntent.getActivity(
        this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private void poll() {
    if (stopped) return;
    SharedPreferences pair = pairing(this);
    String base = pair.getString("base", "");
    String token = pair.getString("token", "");
    if (!isEnabled(this) || base.isEmpty() || token.isEmpty()) {
      stopSelf();
      return;
    }
    long delay = POLL_SECONDS;
    try {
      JSONArray queue = readQueue(base, token);
      // Ignore a response from an old pairing or a watcher stopped during the request.
      if (stopped
          || !isEnabled(this)
          || !token.equals(pairing(this).getString("token", ""))) return;
      if (notifications.areNotificationsEnabled()) notifyNew(queue, token);
      retrySeconds = POLL_SECONDS;
    } catch (Exception error) {
      delay = retrySeconds;
      retrySeconds = Math.min(300, retrySeconds * 2);
      // No repeated alerts or credential-bearing log output for a disconnected desktop.
    } finally {
      if (!stopped && !worker.isShutdown()) {
        try {
          worker.schedule(this::poll, delay, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
          // onDestroy may race with scheduling.
        }
      }
    }
  }

  private JSONArray readQueue(String base, String token) throws Exception {
    URL url = new URL(base.replaceAll("/+$", "") + "/api/queue");
    if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol()))
      throw new IOException("Unsupported bridge protocol");
    HttpURLConnection current = (HttpURLConnection) url.openConnection();
    connection = current;
    current.setConnectTimeout(7000);
    current.setReadTimeout(10000);
    current.setInstanceFollowRedirects(false);
    current.setRequestProperty("Authorization", "Bearer " + token);
    try {
      if (stopped) throw new IOException("Watcher stopped");
      if (current.getResponseCode() != 200) throw new IOException("Bridge unavailable");
      long deadline = SystemClock.elapsedRealtime() + 30000;
      try (InputStream in = current.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int length;
        while ((length = in.read(buffer)) != -1) {
          if (stopped) throw new IOException("Watcher stopped");
          if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Queue read timed out");
          if (out.size() + length > 2 * 1024 * 1024) throw new IOException("Queue too large");
          out.write(buffer, 0, length);
        }
        return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("reviews");
      }
    } finally {
      current.disconnect();
      if (connection == current) connection = null;
    }
  }

  private void notifyNew(JSONArray queue, String token) throws Exception {
    String key = "seen_" + fingerprint(token);
    JSONArray stored = new JSONArray(preferences(this).getString(key, "[]"));
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (int i = 0; i < stored.length(); i++) seen.add(stored.getString(i));
    List<String> titles = new ArrayList<>();
    for (int i = 0; i < Math.min(queue.length(), MAX_SEEN); i++) {
      JSONObject review = queue.optJSONObject(i);
      if (review == null || !"pending".equals(review.optString("status", "pending"))) continue;
      String id = review.optString("id", "");
      if (!id.matches("[A-Za-z0-9_-]{1,128}") || !seen.add(id)) continue;
      String title = review.optString("title", "Untitled document");
      titles.add(title.substring(0, Math.min(title.length(), 120)));
    }
    if (titles.isEmpty()) return;
    while (seen.size() > MAX_SEEN) seen.remove(seen.iterator().next());
    JSONArray persisted = new JSONArray();
    for (String id : seen) persisted.put(id);
    // Persist before notifying: restarting the process must not repeat prior alerts.
    if (!preferences(this).edit().putString(key, persisted.toString()).commit()) return;
    if (stopped || !isEnabled(this)) return;
    String heading = titles.size() == 1 ? "Review ready" : titles.size() + " new reviews ready";
    String text = titles.size() == 1 ? titles.get(0) : String.join(" · ", titles);
    notifications.notify(
        READY_NOTIFICATION,
        new Notification.Builder(this, READY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(openDocuments())
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build());
  }

  private static String fingerprint(String token) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder();
    for (byte value : digest) result.append(String.format("%02x", value & 255));
    return result.toString();
  }

  @Override
  public void onDestroy() {
    stopped = true;
    HttpURLConnection current = connection;
    if (current != null) current.disconnect();
    worker.shutdownNow();
    stopForeground(true);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
