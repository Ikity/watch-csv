package dev.watchcsv.viewer;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import com.google.android.gms.common.*;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.*;
import java.util.*;
import java.util.concurrent.*;

/** Phone companion and the watch's explicit receive-session screen. */
public final class TransferActivity extends BackActivity {
    private static final int PICK = 70, NOTIFICATIONS = 71;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ArrayList<Uri> files = new ArrayList<>();
    private String nodeId, nodeName, pendingAction;
    private boolean watch, dead, finding;
    private LinearLayout body;
    private TextView status, selectedFiles, selectedWatch;
    private ProgressBar progress;
    private Button start;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (dead) return;
            TransferService.Status snapshot = TransferService.status;
            String count = snapshot.total > 0 ? "\n" + size(snapshot.done) + " / " + size(snapshot.total)
                : snapshot.done > 0 ? "\n" + size(snapshot.done) : "";
            status.setText(snapshot.message + count);
            progress.setIndeterminate(snapshot.active && snapshot.total <= 0);
            if (snapshot.total > 0) progress.setProgress((int)Math.min(100, snapshot.done * 100 / snapshot.total));
            else if (!snapshot.active) progress.setProgress(0);
            start.setEnabled(!snapshot.active);
            handler.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        watch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
        if (saved != null) {
            ArrayList<Uri> restored = saved.getParcelableArrayList("files"); if (restored != null) files = restored;
            nodeId = saved.getString("node"); nodeName = saved.getString("nodeName"); pendingAction = saved.getString("pending");
        }
        render();
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(Color.rgb(230, 242, 244)); t.setTextSize(size);
        t.setPadding(0, dp(9), 0, dp(9)); body.addView(t, new LinearLayout.LayoutParams(-1, -2)); return t;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setMinHeight(dp(48));
        body.addView(b, new LinearLayout.LayoutParams(-1, -2)); b.setOnClickListener(v -> action.run()); return b;
    }
    private void render() {
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(Color.rgb(8, 15, 19)); scroll.setFillViewport(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(watch ? 27 : 22), dp(watch ? 36 : 40), dp(watch ? 27 : 22), dp(watch ? 42 : 32));
        scroll.addView(body); setContentView(scroll);
        text(watch ? "Receive from phone" : "Watch CSV Companion", watch ? 19 : 25);
        if (watch) {
            text("Tap Start receiving for files and phone controls. For remote filters/search, use Back to viewer afterward; the session keeps running.", 14);
            start = button("Start receiving", () -> requestStart(TransferService.RECEIVE));
        } else {
            text("1. On the watch, open Receive from phone → Start receiving.\n2. Keep Galaxy Wearable connected and tap Find watch here.\n3. Send CSV files, or use Control watch view to edit filters, columns, order, and search.", 16);
            button("Choose CSV files…", this::pick);
            selectedFiles = text("", 15); showFiles();
            button("Find watch", this::findWatch);
            selectedWatch = text(nodeName == null ? "No watch selected." : "Watch: " + nodeName, 15);
            button("Control watch view", () -> {
                if (nodeId == null) { message("Tap Find watch and select the watch first."); return; }
                if (TransferService.status.active) { message("Finish the file transfer first, then open watch controls."); return; }
                startActivity(new Intent(this, RemoteActivity.class).putExtra("node", nodeId).putExtra("nodeName", nodeName));
            });
            start = button("Send to watch", () -> {
                if (files.isEmpty()) { message("Choose one or more CSV files first."); return; }
                if (nodeId == null) { message("Tap Find watch and select your watch first."); return; }
                requestStart(TransferService.SEND);
            });
        }
        text("Transfer status", 17);
        status = text(TransferService.status.message, 15);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        body.addView(progress, new LinearLayout.LayoutParams(-1, dp(16)));
        button(watch ? "Stop receiving" : "Cancel transfer", () -> {
            if (TransferService.status.active) startService(new Intent(this, TransferService.class).setAction(TransferService.STOP));
        });
        if (watch) {
            button("Open Inbox", () -> startActivity(new Intent(this, MainActivity.class).putExtra("openInbox", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            text("Received files appear in Inbox after verification. Choose the encoding and separator when importing. Receive mode closes after 10 minutes idle.", 13);
            button("Back to viewer", () -> { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); });
        } else {
            button("Open CSV viewer on phone", () -> startActivity(new Intent(this, MainActivity.class)));
            text("Files are streamed over Wear OS Data Layer. No IP addresses, ADB, or direct access to Android/data is needed. Original encoding is preserved. Each file is limited to 256 MiB.", 14);
            text("File access is granted through Android's picker. Notification access is requested before a transfer so progress can stay visible while the screen is off. No Nearby Devices, Bluetooth, or broad storage permission is needed by this app.", 14);
        }
    }
    private void message(String value) { new AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK", null).show(); }
    private boolean playServicesReady() {
        GoogleApiAvailability availability = GoogleApiAvailability.getInstance();
        int code = availability.isGooglePlayServicesAvailable(this);
        if (code == ConnectionResult.SUCCESS) return true;
        if (availability.isUserResolvableError(code)) {
            Dialog dialog = availability.getErrorDialog(this, code, 72); if (dialog != null) dialog.show();
        } else message("Google Play services for Wear OS are unavailable (code " + code + "). Update Google Play services and Galaxy Wearable, then try again.");
        return false;
    }
    private void requestStart(String action) {
        if (TransferService.status.active || !playServicesReady()) return;
        pendingAction = action;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !getPreferences(MODE_PRIVATE).getBoolean("notificationsAsked", false)) {
            new AlertDialog.Builder(this).setTitle("Transfer progress notifications")
                .setMessage("Allow notifications to see file-transfer progress and a Stop button while the app is in the background. If you decline, you can still transfer and view progress here.")
                .setPositiveButton("Continue", (d, w) -> {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("notificationsAsked", true).apply();
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
                }).setNegativeButton("Cancel", (d, w) -> pendingAction = null).show();
        } else beginTransfer();
    }
    private void beginTransfer() {
        if (pendingAction == null) return;
        Intent intent = new Intent(this, TransferService.class).setAction(pendingAction); pendingAction = null;
        if (TransferService.SEND.equals(intent.getAction())) {
            intent.putExtra("node", nodeId); intent.putParcelableArrayListExtra("uris", new ArrayList<>(files));
        }
        try { startForegroundService(intent); }
        catch (RuntimeException e) { message("Could not start transfer: " + TransferService.describe(e)); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATIONS) {
            if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) Toast.makeText(this, "Notifications are off. Transfer progress is available in this screen.", Toast.LENGTH_LONG).show();
            beginTransfer();
        }
    }
    private void pick() {
        if (TransferService.status.active) { message("Finish or cancel the current transfer first."); return; }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(picker, PICK); }
        catch (ActivityNotFoundException e) { message("No Android document picker is available on this phone. Enable the system Files/Documents app, then try again."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null) return;
        LinkedHashSet<Uri> selected = new LinkedHashSet<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
            Uri uri = data.getClipData().getItemAt(i).getUri(); if (uri != null) selected.add(uri);
        }
        if (data.getData() != null) selected.add(data.getData());
        if (selected.isEmpty()) return;
        // Release selections no longer needed; retain just this explicit selection.
        for (Uri old : files) if (!selected.contains(old)) {
            try { getContentResolver().releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
        }
        files = new ArrayList<>(selected);
        for (Uri uri : files) if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
        }
        showFiles();
    }
    private String filename(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {}
        return "Selected file";
    }
    private void showFiles() {
        if (selectedFiles == null) return;
        StringBuilder label = new StringBuilder(files.size() + " file(s) selected");
        for (Uri file : files) label.append('\n').append(filename(file)); selectedFiles.setText(label);
    }
    private void findWatch() {
        if (finding || TransferService.status.active || !playServicesReady()) return;
        finding = true; nodeId = nodeName = null; selectedWatch.setText("Looking for a receiving watch…");
        worker.execute(() -> {
            try {
                CapabilityInfo info = Tasks.await(Wearable.getCapabilityClient(this).getCapability(TransferProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE), 20, TimeUnit.SECONDS);
                List<Node> nodes = new ArrayList<>(info.getNodes()); nodes.sort(Comparator.comparing(Node::getDisplayName));
                runOnUiThread(() -> {
                    finding = false; if (dead) return;
                    if (nodes.isEmpty()) {
                        nodeId = nodeName = null; selectedWatch.setText("No receiving watch found.");
                        message("On the watch, open Watch CSV → Receive from phone → Start receiving. Confirm the watch is connected in Galaxy Wearable, then tap Find watch again. Install the latest APKs on both devices."); return;
                    }
                    String[] names = new String[nodes.size()]; for (int i = 0; i < names.length; i++) names[i] = nodes.get(i).getDisplayName();
                    if (nodes.size() == 1) selectNode(nodes.get(0));
                    else new AlertDialog.Builder(this).setTitle("Choose receiving watch").setItems(names, (d, index) -> selectNode(nodes.get(index))).setNegativeButton("Cancel", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finding = false;
                    if (!dead) {
                        selectedWatch.setText("Watch discovery failed.");
                        message(TransferService.describe(e) + "\n\nOn the watch, open Receive from phone → Start receiving. Confirm Galaxy Wearable shows the watch connected, then retry Find watch.");
                    }
                });
            }
        });
    }
    private void selectNode(Node node) { nodeId = node.getId(); nodeName = node.getDisplayName(); selectedWatch.setText("Watch: " + nodeName); }
    private static String size(long bytes) { return String.format(Locale.getDefault(), "%.1f MiB", bytes / (1024.0 * 1024.0)); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putParcelableArrayList("files", files); state.putString("node", nodeId); state.putString("nodeName", nodeName); state.putString("pending", pendingAction);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { dead = true; handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy(); }
}
