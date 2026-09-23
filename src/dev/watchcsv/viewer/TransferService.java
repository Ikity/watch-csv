package dev.watchcsv.viewer;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import com.google.android.gms.tasks.*;
import com.google.android.gms.wearable.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** User-started foreground sessions: keep receiving reliable without background-start exemptions. */
public final class TransferService extends Service {
    public static final String RECEIVE = "dev.watchcsv.RECEIVE", SEND = "dev.watchcsv.SEND", STOP = "dev.watchcsv.STOP";
    private static final int NOTIFICATION_ID = 54;
    private static final long IDLE_MS = 10 * 60_000L, STALL_MS = 3 * 60_000L, SESSION_MS = 45 * 60_000L;
    public static final class Status {
        public final boolean active, receiving;
        public final String message;
        public final long done, total;
        Status(boolean active, boolean receiving, String message, long done, long total) {
            this.active = active; this.receiving = receiving; this.message = message; this.done = done; this.total = total;
        }
    }
    public static volatile Status status = new Status(false, false, "No transfer running.", 0, 0);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(), inFlight = new AtomicBoolean();
    private final AtomicInteger queued = new AtomicInteger();
    private final Set<ChannelClient.Channel> incomingChannels = ConcurrentHashMap.newKeySet();
    private ChannelClient client;
    private volatile ChannelClient.Channel channel;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile long lastActivity, sessionStarted, lastDisplay;
    private volatile boolean receiving, started, destroyed;
    private PowerManager.WakeLock wakeLock;
    private int received;
    private final ChannelClient.ChannelCallback callback = new ChannelClient.ChannelCallback() {
        @Override public void onChannelOpened(ChannelClient.Channel incoming) {
            if (!receiving || cancelled.get() || !(incoming.getPath().startsWith(TransferProtocol.PATH) || incoming.getPath().startsWith(RemoteProtocol.PATH))) {
                client.close(incoming); return;
            }
            // A phone may open its next channel immediately after receiving a receipt,
            // before this service has finished closing the previous exchange.
            if (queued.incrementAndGet() > 4) { queued.decrementAndGet(); client.close(incoming); return; }
            incomingChannels.add(incoming);
            try { worker.execute(() -> receive(incoming)); }
            catch (RejectedExecutionException e) { client.close(incoming); incomingChannels.remove(incoming); queued.decrementAndGet(); }
        }
    };
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (destroyed || cancelled.get()) return;
            long now = SystemClock.elapsedRealtime();
            if (inFlight.get() && (now - lastActivity > STALL_MS || now - sessionStarted > SESSION_MS)) {
                stopSession("Transfer timed out. Check the phone–watch connection, reopen Receive from phone, and retry."); return;
            }
            if (receiving && !inFlight.get() && now - lastActivity > IDLE_MS) {
                stopSession("Receive session ended after 10 minutes idle. Tap Start receiving to reopen it."); return;
            }
            main.postDelayed(this, 5000);
        }
    };
    @Override public void onCreate() {
        super.onCreate(); client = Wearable.getChannelClient(this);
        wakeLock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchCSV:Transfer");
        wakeLock.setReferenceCounted(false);
        NotificationChannel notifications = new NotificationChannel("csv-transfer", "CSV transfer progress", NotificationManager.IMPORTANCE_LOW);
        notifications.setDescription("Progress while sending or receiving CSV files");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(notifications);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) { stopSession("Transfer session stopped. Completed files remain in the watch Inbox."); return START_NOT_STICKY; }
        if (started) return START_NOT_STICKY;
        started = true; receiving = RECEIVE.equals(intent.getAction());
        status = new Status(true, receiving, receiving ? "Opening receive session…" : "Preparing transfer…", 0, -1);
        startForeground(NOTIFICATION_ID, notification(status.message));
        lastActivity = sessionStarted = SystemClock.elapsedRealtime(); main.postDelayed(watchdog, 5000);
        if (receiving) {
            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
                stopSession("Receive mode is intended for the watch. Use Send files on the phone."); return START_NOT_STICKY;
            }
            worker.execute(() -> {
                try {
                    RemoteOperations.recover(this);
                    await(client.registerChannelCallback(callback)); check();
                    await(Wearable.getCapabilityClient(this).addLocalCapability(TransferProtocol.CAPABILITY)); check();
                    await(Wearable.getCapabilityClient(this).addLocalCapability(RemoteProtocol.CAPABILITY)); check();
                    update("Ready for files and phone controls. On the phone, tap Find watch.\nFor view controls, keep the watch's CSV viewer open (Back to viewer).\nThis session stays open for 10 minutes while idle.", 0, 0, true);
                } catch (Exception e) { fail(e); }
            });
        } else if (SEND.equals(intent.getAction())) {
            String node = intent.getStringExtra("node");
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra("uris");
            if (node == null || uris == null || uris.isEmpty()) { stopSession("Choose a watch and at least one CSV first."); return START_NOT_STICKY; }
            inFlight.set(true); worker.execute(() -> send(node, uris));
        } else stopSession("Unknown transfer action");
        return START_NOT_STICKY;
    }
    private <T> T await(Task<T> task) throws Exception { return Tasks.await(task, 30, TimeUnit.SECONDS); }
    private void check() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer cancelled");
    }
    private synchronized void acquire() { if (!cancelled.get()) wakeLock.acquire(SESSION_MS + 30_000L); }
    private synchronized void release() { if (wakeLock.isHeld()) wakeLock.release(); }
    private void progress(String phase, String name, long done, long total) {
        lastActivity = SystemClock.elapsedRealtime();
        update(phase + "\n" + name, done, total, done == 0 || done == total);
    }
    private void update(String message, long done, long total, boolean force) {
        if (destroyed || cancelled.get()) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastDisplay < 400) return;
        lastDisplay = now; status = new Status(true, receiving, message, done, total);
        main.post(() -> {
            if (!destroyed && !cancelled.get()) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(message));
        });
    }
    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {}
        return "data.csv";
    }
    private void send(String node, List<Uri> uris) {
        int complete = 0;
        acquire();
        try {
            for (Uri uri : uris) {
                check(); acquire(); sessionStarted = lastActivity = SystemClock.elapsedRealtime();
                String name = displayName(uri), lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) throw new IOException("Export " + name + " as CSV before sending it.");
                update("Preparing " + (complete + 1) + " / " + uris.size() + "\n" + name, 0, -1, true);
                input = getContentResolver().openInputStream(uri);
                if (input == null) throw new IOException("Cannot read " + name + ". Choose the file again to grant access.");
                try (TransferProtocol.Staged staged = TransferProtocol.stage(input, new File(getCacheDir(), "transfers"), name, this::check, this::progress)) {
                    input = null; check();
                    update("Connecting to watch…\n" + name, 0, staged.size, true);
                    channel = await(client.openChannel(node, TransferProtocol.PATH + UUID.randomUUID()));
                    check(); input = await(client.getInputStream(channel)); output = await(client.getOutputStream(channel));
                    String saved = TransferProtocol.send(staged, input, output, this::check, this::progress);
                    complete++; update("Confirmed in watch Inbox\n" + saved + "\n" + complete + " / " + uris.size() + " files", staged.size, staged.size, true);
                } finally { closeExchange(); }
            }
            finish("Sent " + complete + " file(s). The watch confirmed they are saved.\nOn the watch: Inbox → choose file → select encoding/separator → Import.");
        } catch (Exception e) {
            if (!cancelled.get()) fail(new IOException(complete + " / " + uris.size() + " files confirmed.\n" + describe(e), e));
        } finally { inFlight.set(false); closeExchange(); release(); }
    }
    private void receive(ChannelClient.Channel incoming) {
        channel = incoming; inFlight.set(true); lastActivity = sessionStarted = SystemClock.elapsedRealtime();
        acquire();
        try {
            check(); input = await(client.getInputStream(incoming)); output = await(client.getOutputStream(incoming));
            if (incoming.getPath().startsWith(RemoteProtocol.PATH)) {
                RemoteProtocol.serve(input, output, request -> { check(); return RemoteOperations.handle(this, request); });
                return;
            }
            File external = getExternalFilesDir(null);
            if (external == null) throw new IOException("Watch storage is unavailable");
            File saved = TransferProtocol.receive(input, output, new File(external, "Inbox"), this::check, this::progress);
            received++;
            update("Received " + received + " file(s)\n" + saved.getName() + "\nSaved to Inbox. Ready for another file.", saved.length(), saved.length(), true);
        } catch (TransferProtocol.ReceiptLostException e) {
            received++; update(e.getMessage() + "\nThe file is available in Inbox.", e.saved.length(), e.saved.length(), true);
        } catch (Exception e) {
            if (!cancelled.get()) update(incoming.getPath().startsWith(RemoteProtocol.PATH)
                ? "Phone control connection failed: " + describe(e) + "\nCheck command status on the phone before retrying."
                : "Receive failed: " + describe(e) + "\nRetry from the phone; incomplete files are discarded.", 0, 0, true);
        } finally {
            closeExchange(); incomingChannels.remove(incoming); queued.decrementAndGet();
            inFlight.set(false); lastActivity = SystemClock.elapsedRealtime(); release();
        }
    }
    public static String describe(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException) && error.getCause() != null) error = error.getCause();
        if (error instanceof SecurityException) return "File access was denied or expired. Choose the CSV again using the file picker.";
        if (error instanceof TimeoutException) return "Connection timed out. Confirm Galaxy Wearable is connected and Receive from phone is open on the watch.";
        String text = error.getMessage(); return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }
    private void fail(Exception error) { if (!cancelled.get()) finish("Transfer failed\n" + describe(error)); }
    private void finish(String message) {
        status = new Status(false, receiving, message, 0, 0); main.post(this::stopSelf);
    }
    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 54, new Intent(this, TransferActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 55, new Intent(this, TransferService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "csv-transfer").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(receiving ? "Watch CSV · receiving" : "Watch CSV · sending")
            .setContentText(message.replace('\n', ' ')).setStyle(new Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }
    private void closeExchange() {
        InputStream in = input; OutputStream out = output; ChannelClient.Channel old = channel;
        input = null; output = null; channel = null;
        if (old != null) client.close(old);
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
    }
    private void stopSession(String message) {
        cancelled.set(true); status = new Status(false, receiving, message, 0, 0);
        // Closing the channel releases blocked stream reads/writes.
        ChannelClient.Channel current = channel; if (current != null) client.close(current);
        for (ChannelClient.Channel queuedChannel : incomingChannels) client.close(queuedChannel);
        InputStream in = input; if (in != null) try { in.close(); } catch (IOException ignored) {}
        stopSelf();
    }
    @Override public void onTimeout(int startId, int fgsType) { stopSession("Android ended the transfer session. Open the app and retry."); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true; cancelled.set(true); main.removeCallbacksAndMessages(null);
        if (status.active) status = new Status(false, receiving, "Transfer session ended. Reopen it to retry. Completed files remain in Inbox.", 0, 0);
        ChannelClient.Channel current = channel; if (current != null) client.close(current);
        InputStream in = input; if (in != null) try { in.close(); } catch (IOException ignored) {}
        worker.execute(() -> {
            closeExchange();
            if (receiving) {
                try { await(Wearable.getCapabilityClient(this).removeLocalCapability(RemoteProtocol.CAPABILITY)); } catch (Exception ignored) {}
                try { await(Wearable.getCapabilityClient(this).removeLocalCapability(TransferProtocol.CAPABILITY)); } catch (Exception ignored) {}
                try { await(client.unregisterChannelCallback(callback)); } catch (Exception ignored) {}
            }
        });
        worker.shutdown(); release(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
