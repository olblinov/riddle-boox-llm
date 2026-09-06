package com.booxreview;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.HashSet;

/** Discovers advertised bridges only; never scans address ranges or retrieves credentials. */
final class WifiDiscovery {
  interface Listener {
    void found(String name, String url);

    void status(String text);
  }

  private final NsdManager manager;
  private final Listener listener;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ArrayDeque<NsdServiceInfo> waiting = new ArrayDeque<>();
  private final HashSet<String> seen = new HashSet<>();
  private boolean stopped, resolving, started;

  WifiDiscovery(Context context, Listener listener) {
    manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    this.listener = listener;
  }

  void start() {
    try {
      manager.discoverServices("_boox-review._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
    } catch (RuntimeException error) {
      listener.status("Discovery unavailable. Enter your Mac's Wi-Fi URL.");
    }
  }

  void stop() {
    stopped = true;
    main.removeCallbacksAndMessages(null);
    try {
      if (started) manager.stopServiceDiscovery(discovery);
    } catch (RuntimeException ignored) {
    }
  }

  void next() {
    if (stopped || resolving || waiting.isEmpty()) return;
    resolving = true;
    NsdServiceInfo service = waiting.remove();
    try {
      manager.resolveService(
          service,
          new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo info, int code) {
              main.post(
                  () -> {
                    resolving = false;
                    next();
                  });
            }

            public void onServiceResolved(NsdServiceInfo info) {
              main.post(
                  () -> {
                    resolving = false;
                    if (!stopped && info.getHost() != null) {
                      String host = info.getHost().getHostAddress();
                      if (host.contains(":")) host = "[" + host + "]";
                      listener.found(
                          info.getServiceName(), "http://" + host + ":" + info.getPort());
                    }
                    next();
                  });
            }
          });
    } catch (RuntimeException error) {
      resolving = false;
      next();
    }
  }

  private final NsdManager.DiscoveryListener discovery =
      new NsdManager.DiscoveryListener() {
        public void onDiscoveryStarted(String type) {
          main.post(
              () -> {
                started = true;
                if (stopped) {
                  try {
                    manager.stopServiceDiscovery(this);
                  } catch (RuntimeException ignored) {
                  }
                }
              });
        }

        public void onDiscoveryStopped(String type) {}

        public void onStartDiscoveryFailed(String type, int code) {
          main.post(
              () -> {
                if (!stopped)
                  listener.status("No discovery connection. Enter your Mac's Wi-Fi URL.");
              });
        }

        public void onStopDiscoveryFailed(String type, int code) {}

        public void onServiceLost(NsdServiceInfo info) {}

        public void onServiceFound(NsdServiceInfo info) {
          main.post(
              () -> {
                if (!stopped && seen.add(info.getServiceName())) {
                  waiting.add(info);
                  next();
                }
              });
        }
      };
}
