package com.booxreview;

import android.app.Application;
import android.os.Build;
import android.util.Log;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/** App-process compatibility for vendor display extensions, approved by the device owner. */
public final class BooxApplication extends Application {
  static boolean compatibilityReady = true;

  public void onCreate() {
    super.onCreate();
    if (Build.VERSION.SDK_INT >= 30
        && (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase().matches(".*(onyx|boox).*")) {
      try {
        // Vendor SDK reflects BOOX additions on these framework display classes.
        // Restrict the exemption to those classes instead of the demo's empty-prefix wildcard.
        compatibilityReady =
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/onyx/ViewUpdateHelper;",
                "Landroid/view/View;",
                "Landroid/view/Surface;",
                "Landroid/view/SurfaceControl;");
        // Vendor-demo initializers only retain the application context (verified SDK bytecode).
        com.onyx.android.sdk.utils.ResManager.init(this);
        com.onyx.android.sdk.rx.RxBaseAction.init(this);
        Log.i("BooxInk", "App-local display API compatibility=" + compatibilityReady);
      } catch (Throwable error) {
        compatibilityReady = false;
        Log.e("BooxInk", "Vendor display compatibility unavailable; Canvas fallback", error);
      }
    }
  }
}
