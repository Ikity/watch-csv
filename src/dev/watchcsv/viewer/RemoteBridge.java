package dev.watchcsv.viewer;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;

/** Routes commands to the existing viewer, without launching activities from the background. */
public final class RemoteBridge {
    public interface Target { void remoteCommand(JSONObject request, CompletableFuture<JSONObject> result); }
    private static WeakReference<Target> current = new WeakReference<>(null);
    private RemoteBridge() {}
    public static void attach(Target target) { current = new WeakReference<>(target); }
    public static void detach(Target target) { if (current.get() == target) current.clear(); }
    public static CompletableFuture<JSONObject> dispatch(JSONObject request) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            Target target = current.get();
            if (target == null) result.completeExceptionally(new IOException("Open the CSV viewer on the watch, then retry. Keep the receive session running."));
            else {
                try { target.remoteCommand(request, result); }
                catch (Exception e) { result.completeExceptionally(e); }
            }
        });
        return result;
    }
}
