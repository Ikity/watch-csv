package dev.watchcsv.viewer;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class RemoteProtocolTest {
    private static int assertions;
    private static final String FILE = "11111111-2222-3333-4444-555555555555.db";
    private interface Checked { void run() throws Exception; }
    private static void eq(Object expected, Object actual) {
        assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void rejects(Checked work) throws Exception {
        boolean rejected = false; try { work.run(); } catch (IOException | JSONException | IllegalArgumentException expected) { rejected = true; }
        eq(true, rejected);
    }
    private static JSONObject roundTrip(JSONObject request, RemoteProtocol.Handler handler) throws Exception {
        PipedInputStream watchIn = new PipedInputStream(65536), phoneIn = new PipedInputStream(65536);
        PipedOutputStream phoneOut = new PipedOutputStream(watchIn), watchOut = new PipedOutputStream(phoneIn);
        ExecutorService server = Executors.newSingleThreadExecutor();
        Future<?> done = server.submit(() -> {
            try { RemoteProtocol.serve(watchIn, watchOut, handler); }
            catch (Exception e) { throw new RuntimeException(e); }
            finally { try { watchIn.close(); watchOut.close(); } catch (IOException ignored) {} }
        });
        try {
            JSONObject response = RemoteProtocol.exchange(phoneIn, phoneOut, request);
            done.get(10, TimeUnit.SECONDS); return response;
        } finally { phoneIn.close(); phoneOut.close(); watchIn.close(); watchOut.close(); server.shutdownNow(); }
    }
    private static byte[] frame(byte[] data, int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(length); out.write(data); return bytes.toByteArray();
    }
    private static byte[] frame(JSONObject json) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); RemoteProtocol.write(bytes, json); return bytes.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        eq(true, RemoteProtocol.validFile(FILE)); eq(false, RemoteProtocol.validFile("../" + FILE));
        eq(false, RemoteProtocol.validFile(FILE + "/anything")); eq(false, RemoteProtocol.validFile("file.csv"));
        eq(false, RemoteProtocol.validId("")); eq(false, RemoteProtocol.validId(null));
        ViewSpec v = new ViewSpec(4); v.moveColumn(3, 0); v.moveColumn(1, 1); v.visible[2] = false;
        v.filters[0] = "Москва; центр"; v.filters[2] = "^ABC|XYZ$"; v.regex[2] = true; v.sort = 3; v.descending = true;
        JSONObject apply = RemoteProtocol.request("apply").put("file", FILE).put("view", v.json()).put("search", "Иван 😀").put("direction", "first");
        JSONObject accepted = roundTrip(apply, request -> {
            eq("apply", request.getString("op")); eq(FILE, request.getString("file"));
            ViewSpec received = RemoteProtocol.view(request.getJSONObject("view"), 4);
            eq("[3, 1, 0, 2]", Arrays.toString(received.order)); eq(false, received.visible[2]);
            eq("Москва; центр", received.filters[0]); eq("^ABC|XYZ$", received.filters[2]); eq(true, received.regex[2]);
            eq(3, received.sort); eq(true, received.descending); eq("Иван 😀", request.getString("search"));
            return RemoteProtocol.reply(request, "accepted", "Working", null);
        });
        eq("accepted", accepted.getString("status")); eq(apply.getString("id"), accepted.getString("id"));
        JSONObject status = RemoteProtocol.request("status").put("command", apply.getString("id"));
        JSONObject completed = RemoteProtocol.reply(apply, "done", "Applied", new JSONObject().put("matches", 12).put("rows", 100));
        JSONObject result = roundTrip(status, request -> RemoteProtocol.reply(request, "done", "Receipt", new JSONObject().put("result", completed)));
        eq(status.getString("id"), result.getString("id")); eq(apply.getString("id"), result.getJSONObject("data").getJSONObject("result").getString("id"));
        eq("done", result.getJSONObject("data").getJSONObject("result").getString("status"));
        eq(12, result.getJSONObject("data").getJSONObject("result").getJSONObject("data").getInt("matches"));
        JSONObject failed = roundTrip(RemoteProtocol.request("get").put("file", FILE), request -> { throw new IOException("File was deleted"); });
        eq("failed", failed.getString("status")); eq("File was deleted", failed.getString("message"));
        for (String direction : new String[]{"first", "next", "previous"}) {
            JSONObject search = RemoteProtocol.request("search").put("file", FILE).put("search", "a[b].*").put("direction", direction);
            JSONObject response = roundTrip(search, request -> {
                eq(direction, request.getString("direction")); eq("a[b].*", request.getString("search"));
                return RemoteProtocol.reply(request, "done", "Search", null);
            });
            eq("done", response.getString("status"));
        }
        RemoteProtocol.validate(RemoteProtocol.request("search").put("file", FILE).put("search", "")); assertions++;
        JSONObject legacy = v.json(); legacy.remove("order"); eq("[0, 1, 2, 3]", Arrays.toString(RemoteProtocol.view(legacy, 4).order));
        ViewSpec unfinished = new ViewSpec(2); Arrays.fill(unfinished.visible, false); unfinished.regex[0] = true; unfinished.filters[0] = "[";
        ViewSpec restoredDraft = ViewSpec.fromDraft(new JSONObject(unfinished.json().toString()), 2);
        eq(0, restoredDraft.visibleCount()); eq("[", restoredDraft.filters[0]);
        rejects(() -> RemoteProtocol.view(restoredDraft.json(), 2));
        restoredDraft.visible[0] = true; rejects(() -> RemoteProtocol.view(restoredDraft.json(), 2));
        JSONObject malformed = v.json().put("order", new JSONArray("[0,0,1,2]")); rejects(() -> RemoteProtocol.view(malformed, 4));
        rejects(() -> RemoteProtocol.view(v.json(), 3)); rejects(() -> RemoteProtocol.view(v.json(), 0));
        rejects(() -> RemoteProtocol.validate(RemoteProtocol.request("list").put("v", 2)));
        rejects(() -> RemoteProtocol.validate(RemoteProtocol.request("delete")));
        rejects(() -> RemoteProtocol.validate(RemoteProtocol.request("list").put("id", "bad")));
        rejects(() -> RemoteProtocol.validate(RemoteProtocol.request("get").put("file", "../" + FILE)));
        rejects(() -> RemoteProtocol.validate(RemoteProtocol.request("apply").put("file", FILE)));
        rejects(() -> RemoteProtocol.validate(RemoteProtocol.request("status").put("command", "bad")));
        rejects(() -> RemoteProtocol.validate(RemoteProtocol.request("search").put("file", FILE).put("search", "x").put("direction", "around")));
        rejects(() -> RemoteProtocol.validate(RemoteProtocol.request("search").put("file", FILE).put("search", String.join("", Collections.nCopies(4097, "x")))));
        ViewSpec longFilter = new ViewSpec(1); longFilter.filters[0] = String.join("", Collections.nCopies(8193, "x"));
        rejects(() -> RemoteProtocol.view(longFilter.json(), 1));
        byte[] valid = "{}".getBytes(StandardCharsets.UTF_8);
        rejects(() -> RemoteProtocol.read(new ByteArrayInputStream(frame(valid, 0))));
        rejects(() -> RemoteProtocol.read(new ByteArrayInputStream(frame(valid, -1))));
        rejects(() -> RemoteProtocol.read(new ByteArrayInputStream(frame(valid, RemoteProtocol.MAX_FRAME + 1))));
        rejects(() -> RemoteProtocol.read(new ByteArrayInputStream(frame(valid, 20))));
        rejects(() -> RemoteProtocol.read(new ByteArrayInputStream(frame(new byte[]{(byte)0xff}, 1))));
        rejects(() -> RemoteProtocol.read(new ByteArrayInputStream(frame(new byte[]{'['}, 1))));
        String huge = String.join("", Collections.nCopies(RemoteProtocol.MAX_FRAME, "x"));
        rejects(() -> RemoteProtocol.write(new ByteArrayOutputStream(), new JSONObject().put("text", huge)));
        rejects(() -> RemoteProtocol.validate(RemoteProtocol.request("list").put("text", huge)));
        JSONObject tooLarge = roundTrip(RemoteProtocol.request("list"), request -> RemoteProtocol.reply(request, "done", "metadata", new JSONObject().put("text", huge)));
        eq("failed", tooLarge.getString("status")); eq(true, tooLarge.getString("message").contains("512 KiB"));
        JSONObject get = RemoteProtocol.request("get").put("file", FILE);
        JSONObject wrongId = RemoteProtocol.reply(get, "done", "wrong", null).put("id", UUID.randomUUID().toString());
        rejects(() -> RemoteProtocol.exchange(new ByteArrayInputStream(frame(wrongId)), new ByteArrayOutputStream(), get));
        JSONObject wrongVersion = RemoteProtocol.reply(get, "done", "wrong", null).put("v", 9);
        rejects(() -> RemoteProtocol.exchange(new ByteArrayInputStream(frame(wrongVersion)), new ByteArrayOutputStream(), get));
        JSONObject wrongState = RemoteProtocol.reply(get, "maybe", "wrong", null);
        rejects(() -> RemoteProtocol.exchange(new ByteArrayInputStream(frame(wrongState)), new ByteArrayOutputStream(), get));
        System.out.println("PASS: " + assertions + " remote protocol, settings, draft, validation, and receipt assertions.");
    }
}
