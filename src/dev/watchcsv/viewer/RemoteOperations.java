package dev.watchcsv.viewer;

import android.content.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Watch-side metadata endpoint and persisted, idempotent command receipts. */
public final class RemoteOperations {
    private static String activeId;
    private static final ExecutorService receipts = Executors.newSingleThreadExecutor();
    private RemoteOperations() {}
    private static SharedPreferences ledger(Context context) { return context.getSharedPreferences("remote-receipts", Context.MODE_PRIVATE); }
    public static CsvStore.Info info(Context context, String id) throws Exception {
        if (!RemoteProtocol.validFile(id)) throw new IOException("Invalid watch file ID");
        File file = new File(new File(context.getFilesDir(), "datasets"), id);
        if (!file.isFile()) throw new IOException("This CSV is no longer imported on the watch. Reload the watch file list.");
        return CsvStore.readInfo(file);
    }
    public static ViewSpec savedView(Context context, CsvStore.Info info) {
        try { return ViewSpec.from(new JSONObject(context.getSharedPreferences("views", Context.MODE_PRIVATE).getString("last:" + info.file.getName(), "{}")), info.headers.length); }
        catch (Exception ignored) { return new ViewSpec(info.headers.length); }
    }
    public static synchronized void recover(Context context) {
        SharedPreferences prefs = ledger(context);
        for (Map.Entry<String, ?> item : prefs.getAll().entrySet()) if (item.getKey().startsWith("result:")) {
            try {
                JSONObject value = new JSONObject((String)item.getValue());
                if ("accepted".equals(value.optString("status")) && !value.getString("id").equals(activeId)) {
                    value.put("status", "failed").put("message", "The watch restarted before confirming this command. Reload its view before retrying.");
                    prefs.edit().putString(item.getKey(), value.toString()).commit();
                }
            } catch (Exception ignored) {}
        }
    }
    public static JSONObject handle(Context context, JSONObject request) throws Exception {
        RemoteProtocol.validate(request);
        String operation = request.getString("op");
        SharedPreferences views = context.getSharedPreferences("views", Context.MODE_PRIVATE);
        if ("list".equals(operation)) {
            JSONArray files = new JSONArray();
            for (CsvStore.Info info : CsvStore.library(new File(context.getFilesDir(), "datasets")))
                files.put(new JSONObject().put("id", info.file.getName()).put("name", info.name).put("rows", info.rows).put("columns", info.headers.length));
            return RemoteProtocol.reply(request, "done", "Watch library loaded", new JSONObject().put("files", files).put("active", views.getString("activeFile", "")));
        }
        if ("get".equals(operation)) {
            CsvStore.Info info = info(context, request.getString("file")); JSONArray headers = new JSONArray();
            for (String header : info.headers) headers.put(header);
            return RemoteProtocol.reply(request, "done", "Watch view loaded", new JSONObject().put("file", info.file.getName()).put("name", info.name)
                .put("headers", headers).put("rows", info.rows).put("view", savedView(context, info).json()).put("search", views.getString("search:" + info.file.getName(), "")));
        }
        if ("status".equals(operation)) {
            synchronized (RemoteOperations.class) {
                String id = request.getString("command"), value = ledger(context).getString("result:" + id, null);
                if (value == null) throw new IOException("No receipt found for that command. Reload the watch view before sending it again.");
                JSONObject result = new JSONObject(value);
                if ("accepted".equals(result.getString("status")) && !id.equals(activeId))
                    result.put("status", "failed").put("message", "The watch could not confirm completion. Reload its view before retrying.");
                return RemoteProtocol.reply(request, "done", "Command status", new JSONObject().put("result", result));
            }
        }
        CsvStore.Info info = info(context, request.getString("file"));
        if ("apply".equals(operation)) RemoteProtocol.view(request.getJSONObject("view"), info.headers.length);
        return submit(context.getApplicationContext(), request);
    }
    private static synchronized JSONObject submit(Context context, JSONObject request) throws Exception {
        String id = request.getString("id"); SharedPreferences prefs = ledger(context);
        String previous = prefs.getString("result:" + id, null);
        if (previous != null) return new JSONObject(previous);
        if (activeId != null) throw new IOException("The watch is still applying another phone command. Wait for its confirmation.");
        JSONObject accepted = RemoteProtocol.reply(request, "accepted", "The watch is processing your command…", null);
        JSONArray history;
        try { history = new JSONArray(prefs.getString("history", "[]")); } catch (JSONException e) { history = new JSONArray(); }
        SharedPreferences.Editor edit = prefs.edit(); JSONArray recent = new JSONArray();
        for (int i = 0; i < history.length(); i++) {
            if (i < history.length() - 15) edit.remove("result:" + history.getString(i)); else recent.put(history.getString(i));
        }
        recent.put(id);
        if (!edit.putString("result:" + id, accepted.toString()).putString("history", recent.toString()).commit()) throw new IOException("Cannot save command receipt on watch");
        activeId = id;
        RemoteBridge.dispatch(request).whenCompleteAsync((data, failure) -> {
            synchronized (RemoteOperations.class) {
                try {
                    JSONObject result = failure == null ? RemoteProtocol.reply(request, "done", data.optString("message", "Applied on watch"), data)
                        : RemoteProtocol.reply(request, "failed", TransferService.describe(failure), null);
                    prefs.edit().putString("result:" + id, result.toString()).commit();
                } catch (JSONException ignored) {}
                finally { if (id.equals(activeId)) activeId = null; }
            }
        }, receipts);
        return accepted;
    }
}
