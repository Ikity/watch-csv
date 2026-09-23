package dev.watchcsv.viewer;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Android backend integration: isolated files/preferences and a controlled viewer endpoint. */
public final class RemoteStoreTest {
    private static int assertions;
    private static void eq(Object expected, Object actual) {
        assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void run(Context app) throws Exception {
        String prefix = "remote-test-" + UUID.randomUUID(); File root = new File(app.getCacheDir(), prefix);
        if (!root.mkdir()) throw new IOException("Cannot create remote test directory");
        Set<String> preferences = new HashSet<>();
        Context context = new ContextWrapper(app) {
            public File getFilesDir() { return root; }
            public Context getApplicationContext() { return this; }
            public SharedPreferences getSharedPreferences(String name, int mode) {
                synchronized (preferences) { preferences.add(prefix + name); }
                return app.getSharedPreferences(prefix + name, mode);
            }
        };
        File datasets = new File(root, "datasets");
        CsvStore.Info info = CsvStore.importFile(datasets, new ByteArrayInputStream("Name;Code;Notes\nAlice;A01;cat\n".getBytes("UTF-8")), "remote.csv", "UTF-8", ';', true, new AtomicBoolean(), text -> {});
        AtomicInteger executions = new AtomicInteger(); AtomicReference<CompletableFuture<JSONObject>> command = new AtomicReference<>();
        RemoteBridge.Target endpoint = (request, result) -> { executions.incrementAndGet(); command.set(result); };
        try {
            ViewSpec view = new ViewSpec(3); view.moveColumn(2, 0); view.visible[1] = false; view.filters[1] = "^A"; view.regex[1] = true;
            context.getSharedPreferences("views", Context.MODE_PRIVATE).edit().putString("last:" + info.file.getName(), view.json().toString())
                .putString("activeFile", info.file.getName()).putString("search:" + info.file.getName(), "кот").commit();
            JSONObject library = RemoteOperations.handle(context, RemoteProtocol.request("list")).getJSONObject("data");
            eq(1, library.getJSONArray("files").length()); eq(info.file.getName(), library.getString("active"));
            JSONObject state = RemoteOperations.handle(context, RemoteProtocol.request("get").put("file", info.file.getName())).getJSONObject("data");
            eq("remote.csv", state.getString("name")); eq(3, state.getJSONArray("headers").length()); eq("кот", state.getString("search"));
            ViewSpec loaded = ViewSpec.from(state.getJSONObject("view"), 3); eq("[2, 0, 1]", Arrays.toString(loaded.order)); eq(false, loaded.visible[1]); eq("^A", loaded.filters[1]);
            RemoteBridge.attach(endpoint);
            JSONObject apply = RemoteProtocol.request("apply").put("file", info.file.getName()).put("view", view.json());
            eq("accepted", RemoteOperations.handle(context, apply).getString("status"));
            long deadline = System.currentTimeMillis() + 10000;
            while (command.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
            eq(true, command.get() != null); eq(1, executions.get());
            eq("accepted", RemoteOperations.handle(context, apply).getString("status")); eq(1, executions.get());
            boolean blocked = false;
            try { RemoteOperations.handle(context, RemoteProtocol.request("search").put("file", info.file.getName()).put("search", "cat")); }
            catch (IOException expected) { blocked = true; }
            eq(true, blocked);
            command.get().complete(new JSONObject().put("message", "Applied test view").put("matches", 1).put("rows", 1));
            deadline = System.currentTimeMillis() + 10000;
            JSONObject poll = RemoteProtocol.request("status").put("command", apply.getString("id")), receipt;
            do {
                receipt = RemoteOperations.handle(context, poll).getJSONObject("data").getJSONObject("result");
                if (!"accepted".equals(receipt.getString("status"))) break;
                Thread.sleep(10);
            } while (System.currentTimeMillis() < deadline);
            eq("done", receipt.getString("status")); eq("Applied test view", receipt.getString("message"));
            eq("done", RemoteOperations.handle(context, apply).getString("status")); eq(1, executions.get());
            JSONObject interrupted = RemoteProtocol.request("search").put("file", info.file.getName()).put("search", "cat");
            context.getSharedPreferences("remote-receipts", Context.MODE_PRIVATE).edit()
                .putString("result:" + interrupted.getString("id"), RemoteProtocol.reply(interrupted, "accepted", "unfinished", null).toString()).commit();
            RemoteOperations.recover(context);
            JSONObject recovered = RemoteOperations.handle(context, RemoteProtocol.request("status").put("command", interrupted.getString("id"))).getJSONObject("data").getJSONObject("result");
            eq("failed", recovered.getString("status")); eq(true, recovered.getString("message").contains("restarted"));
            boolean invalid = false;
            try { RemoteOperations.handle(context, RemoteProtocol.request("get").put("file", "../" + info.file.getName())); }
            catch (JSONException expected) { invalid = true; }
            eq(true, invalid);
            System.out.println("PASS: " + assertions + " Android remote metadata/receipt integration assertions.");
        } finally {
            RemoteBridge.detach(endpoint);
            if (command.get() != null && !command.get().isDone()) command.get().completeExceptionally(new IOException("Test ended"));
            SQLiteDatabase.deleteDatabase(info.file); datasets.delete(); root.delete();
            synchronized (preferences) { for (String name : preferences) app.deleteSharedPreferences(name); }
        }
    }
}
