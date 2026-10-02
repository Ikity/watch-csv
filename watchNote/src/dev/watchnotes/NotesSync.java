package dev.watchnotes;

import android.content.Context;
import android.content.pm.PackageManager;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class NotesSync {
    public static final String ROOT = "/watchnotes/v1/", REV = ROOT + "revision/", DB = ROOT + "database/";
    public static boolean watch(Context c) { return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH); }
    public static byte[] read(InputStream in, int limit) throws IOException {
        if (in == null) throw new IOException("Cannot open file");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new IOException("File too large"); out.write(b, 0, n); }
        return out.toByteArray();
    }
    public static void receive(Context context, DataItem item) throws Exception {
        String path = item.getUri().getPath();
        if (path == null || !(path.startsWith(REV) || path.startsWith(DB))) return;
        if (path.startsWith(DB) && watch(context)) return;
        Asset asset = DataMapItem.fromDataItem(item).getDataMap().getAsset("payload");
        if (asset == null) throw new IOException("Missing sync payload");
        DataClient.GetFdForAssetResponse response = Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset), 30, TimeUnit.SECONDS);
        try (InputStream in = response.getInputStream()) {
            if (path.startsWith(REV)) {
                Note n = Note.from(new JSONObject(new String(read(in, 1500000), StandardCharsets.UTF_8)));
                if (!path.equals(REV + n.revision)) throw new IOException("Revision path mismatch");
                try (NotesStore store = new NotesStore(context)) { store.insert(n, true); }
            } else {
                synchronized (NotesStore.LOCK) {
                    File tmp = new File(context.getFilesDir(), "watch-notes.partial");
                    try {
                        try (FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] b = new byte[65536]; long total = 0; int count;
                            while ((count = in.read(b)) != -1) {
                                total += count; if (total > 256L * 1024 * 1024) throw new IOException("Database exceeds 256 MiB");
                                out.write(b, 0, count);
                            }
                            out.getFD().sync();
                        }
                        try (FileInputStream check = new FileInputStream(tmp)) {
                            byte[] header = new byte[16];
                            if (check.read(header) != 16 || !new String(header, StandardCharsets.US_ASCII).equals("SQLite format 3\0"))
                                throw new IOException("Invalid SQLite snapshot");
                        }
                        java.nio.file.Files.move(tmp.toPath(), new File(context.getFilesDir(), "watch-notes.db").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } finally { tmp.delete(); }
                }
            }
        } finally { response.release(); }
    }
    public static String sync(Context context, boolean full) throws Exception {
        DataClient client = Wearable.getDataClient(context);
        DataItemBuffer items = Tasks.await(client.getDataItems(), 30, TimeUnit.SECONDS);
        try { for (DataItem item : items) receive(context, item); } finally { items.release(); }
        int count = 0;
        try (NotesStore store = new NotesStore(context)) {
            if (full) store.resendAll();
            for (Note n : store.revisions(true)) {
                PutDataMapRequest request = PutDataMapRequest.create(REV + n.revision);
                request.getDataMap().putAsset("payload", Asset.createFromBytes(n.json().toString().getBytes(StandardCharsets.UTF_8)));
                Tasks.await(client.putDataItem(request.asPutDataRequest().setUrgent()), 30, TimeUnit.SECONDS);
                store.sent(n.revision); count++;
            }
        }
        int peers = Tasks.await(Wearable.getNodeClient(context).getConnectedNodes(), 30, TimeUnit.SECONDS).size();
        return "Synced available revisions; " + count + " queued. " + peers + " connected device(s). Delivery continues when paired devices connect.";
    }
    public static void sendDatabase(Context context) throws Exception {
        File file = File.createTempFile("notes-snapshot-", ".db", context.getCacheDir());
        try {
            try (NotesStore store = new NotesStore(context)) { store.snapshot(file); }
            if (file.length() > 256L * 1024 * 1024) throw new IOException("Database exceeds 256 MiB transfer limit");
            String node = Tasks.await(Wearable.getNodeClient(context).getLocalNode(), 30, TimeUnit.SECONDS).getId();
            PutDataMapRequest request = PutDataMapRequest.create(DB + node);
            request.getDataMap().putLong("timestamp", System.currentTimeMillis());
            try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)) {
                request.getDataMap().putAsset("payload", Asset.createFromFd(fd));
                Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()), 60, TimeUnit.SECONDS);
            }
        } finally { file.delete(); }
    }
}
