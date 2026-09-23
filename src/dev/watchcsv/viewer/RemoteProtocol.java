package dev.watchcsv.viewer;

import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Small versioned JSON RPC over a separate Wear OS channel; never sends CSV rows. */
public final class RemoteProtocol {
    public static final String PATH = "/watchcsv/control/v1/";
    public static final String CAPABILITY = "watchcsv_control_v1";
    public static final int MAX_FRAME = 512 * 1024;
    private static final int RECEIPT = 0x57435243;
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    public interface Handler { JSONObject handle(JSONObject request) throws Exception; }
    private RemoteProtocol() {}

    public static JSONObject request(String operation) throws JSONException {
        return new JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("op", operation);
    }
    public static boolean validId(String id) { return id != null && id.matches(UUID_PATTERN); }
    public static boolean validFile(String id) { return id != null && id.matches(UUID_PATTERN + "\\.db"); }
    public static void validate(JSONObject request) throws JSONException {
        if (request.toString().getBytes(StandardCharsets.UTF_8).length > MAX_FRAME) throw new JSONException("Remote settings exceed 512 KiB");
        if (request.getInt("v") != 1) throw new JSONException("Update both apps to a compatible version.");
        if (!validId(request.getString("id"))) throw new JSONException("Invalid remote request ID");
        String op = request.getString("op");
        if (!Arrays.asList("list", "get", "apply", "search", "status").contains(op)) throw new JSONException("Unknown remote operation");
        if (Arrays.asList("get", "apply", "search").contains(op) && !validFile(request.getString("file"))) throw new JSONException("Invalid watch file ID");
        if ("status".equals(op) && !validId(request.getString("command"))) throw new JSONException("Invalid command ID");
        if ("apply".equals(op)) request.getJSONObject("view");
        if ("search".equals(op) || request.has("search")) {
            if (request.getString("search").length() > 4096) throw new JSONException("Search text is limited to 4096 characters");
            String direction = request.optString("direction", "first");
            if (!Arrays.asList("first", "next", "previous").contains(direction)) throw new JSONException("Invalid search direction");
        }
    }
    public static ViewSpec view(JSONObject json, int columns) throws JSONException {
        if (columns < 1 || columns > 256) throw new JSONException("Unsupported number of columns");
        JSONArray filters = json.getJSONArray("filters");
        for (int i = 0; i < filters.length(); i++) if (filters.getString(i).length() > 8192) throw new JSONException("A remote filter is limited to 8192 characters");
        return ViewSpec.from(json, columns);
    }
    public static JSONObject reply(JSONObject request, String status, String message, JSONObject data) throws JSONException {
        return new JSONObject().put("v", 1).put("id", request.optString("id", "")).put("status", status)
            .put("message", message).put("data", data == null ? new JSONObject() : data);
    }
    public static void write(OutputStream stream, JSONObject value) throws IOException {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) throw new IOException("Remote settings or file metadata exceed 512 KiB");
        DataOutputStream out = new DataOutputStream(stream); out.writeInt(bytes.length); out.write(bytes); out.flush();
    }
    public static JSONObject read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream); int length = in.readInt();
        if (length <= 0 || length > MAX_FRAME) throw new IOException("Invalid remote message size");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return new JSONObject(text);
        } catch (JSONException e) { throw new IOException("Invalid remote JSON", e); }
    }
    public static JSONObject exchange(InputStream in, OutputStream out, JSONObject request) throws Exception {
        validate(request); write(out, request); JSONObject response = read(in);
        // Release the receiver even when the command was rejected.
        try { DataOutputStream ack = new DataOutputStream(out); ack.writeInt(RECEIPT); ack.flush(); } catch (IOException ignored) {}
        if (response.getInt("v") != 1 || !request.getString("id").equals(response.getString("id"))) throw new IOException("Remote reply did not match the request");
        if (!Arrays.asList("accepted", "done", "failed").contains(response.getString("status"))) throw new IOException("Invalid remote reply status");
        return response;
    }
    public static void serve(InputStream in, OutputStream out, Handler handler) throws Exception {
        JSONObject request = read(in), response;
        try { validate(request); response = handler.handle(request); }
        catch (Exception e) { response = reply(request, "failed", e.getMessage() == null ? "Remote command failed" : e.getMessage(), null); }
        if (response.toString().getBytes(StandardCharsets.UTF_8).length > MAX_FRAME)
            response = reply(request, "failed", "Watch metadata exceeds 512 KiB; this file cannot be edited remotely.", null);
        write(out, response);
        try { new DataInputStream(in).readInt(); } catch (IOException ignored) {}
    }
}
