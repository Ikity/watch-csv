package dev.watchcsv.viewer;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;

/** One navigation path for legacy hardware Back and Android 13+ system Back. */
public abstract class BackActivity extends Activity {
    private Runnable unregisterBack;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) unregisterBack = ModernBack.register(this, this::navigateBack);
    }

    @Override public void onBackPressed() { navigateBack(); }

    protected void navigateBack() { finish(); }

    @Override protected void onDestroy() {
        if (unregisterBack != null) unregisterBack.run();
        super.onDestroy();
    }

    // Keep newer framework types out of the base activity's fields/signatures
    // so the same APK continues to load on Android 11/12 Wear OS versions.
    private static final class ModernBack {
        static Runnable register(Activity activity, Runnable action) {
            android.window.OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            android.window.OnBackInvokedCallback callback = action::run;
            // Default priority lets system dialogs and the keyboard handle Back first.
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return () -> dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }
}
