package dev.watchcsv.viewer;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Phone editor for a watch-owned CSV. Drafts are never confused with applied watch state. */
public final class RemoteActivity extends BackActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private RemoteClient client;
    private SharedPreferences receipts;
    private String node, watchName, file, fileName, query = "", pendingId, screen = "files", notice = "";
    private String[] headers;
    private long rowCount;
    private ViewSpec draft;
    private JSONArray files = new JSONArray();
    private boolean dead, resumed, networkBusy, verified;
    private int filterColumn;
    private ScrollView scroll;
    private LinearLayout body;
    private TextView status;
    private final Runnable poll = this::checkStatus;
    private interface Done { void accept(JSONObject reply) throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        node = getIntent().getStringExtra("node"); watchName = getIntent().getStringExtra("nodeName");
        client = new RemoteClient(this); receipts = getSharedPreferences("phone-control", MODE_PRIVATE);
        if (node == null) { finish(); return; }
        pendingId = receipts.getString("pending:" + node, null);
        if (state != null) {
            try {
                files = new JSONArray(state.getString("files", "[]")); notice = state.getString("notice", "");
                String saved = state.getString("draft");
                if (saved != null) {
                    JSONObject model = new JSONObject(saved); file = model.getString("file"); fileName = model.getString("name"); rowCount = model.getLong("rows");
                    JSONArray labels = model.getJSONArray("headers"); headers = new String[labels.length()];
                    for (int i = 0; i < headers.length; i++) headers[i] = labels.getString(i);
                    draft = ViewSpec.fromDraft(model.getJSONObject("view"), headers.length); query = model.getString("search");
                    filterColumn = state.getInt("filterColumn", 0); String where = state.getString("screen", "editor");
                    switch (where) {
                        case "columns": columns(-1, 0); break;
                        case "filters": filters(); break;
                        case "filter": filter(filterColumn); break;
                        case "sort": sort(); break;
                        case "files": showFiles(); break;
                        default: editor();
                    }
                    return;
                }
            } catch (Exception ignored) { file = null; draft = null; }
        }
        showFiles(); loadLibrary(true);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void begin(String where, String title) {
        screen = where; scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(8, 15, 19));
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(38), dp(20), dp(32));
        scroll.addView(body); setContentView(scroll);
        text(title, 23); text("Watch: " + (watchName == null ? "connected device" : watchName), 13);
        status = text(notice, 14); status.setTextColor(Color.rgb(120, 223, 202));
    }
    private TextView text(CharSequence value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(234, 244, 246));
        view.setPadding(0, dp(7), 0, dp(7)); body.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private Button button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setMinHeight(dp(48));
        body.addView(button, new LinearLayout.LayoutParams(-1, -2)); button.setOnClickListener(v -> action.run()); return button;
    }
    private EditText edit(String value, String hint) {
        EditText field = new EditText(this); field.setTextSize(16); field.setTextColor(Color.WHITE); field.setHintTextColor(Color.LTGRAY);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(true); field.setText(value); field.setHint(hint); field.setMinHeight(dp(48)); body.addView(field); return field;
    }
    private interface Changed { void value(String text); }
    private void changes(EditText field, Changed changed) {
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) { changed.value(text.toString()); }
            public void afterTextChanged(Editable text) {}
        });
    }
    private void say(String message) { notice = message; if (status != null) status.setText(message); }
    private void error(Exception e) { say(TransferService.describe(e)); }
    private void request(JSONObject request, Done done) {
        if (dead || networkBusy) { say("Wait for the current watch request to finish."); return; }
        networkBusy = true;
        worker.execute(() -> {
            try {
                if (!verified) { client.verify(node); verified = true; }
                JSONObject response = client.call(node, request);
                runOnUiThread(() -> {
                    networkBusy = false; if (dead) return;
                    try { done.accept(response); } catch (Exception e) { error(e); }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    networkBusy = false; if (!dead) {
                        error(e);
                        if (pendingId != null) say(notice + "\nCommand confirmation is unknown. Use Check command status before sending again.");
                    }
                });
            }
        });
    }
    private JSONObject data(JSONObject response) throws Exception {
        if ("failed".equals(response.getString("status"))) throw new IOException(response.getString("message"));
        return response.getJSONObject("data");
    }
    private void loadLibrary(boolean openActive) {
        if (networkBusy) return;
        say("Loading imported CSV files from watch…");
        try {
            request(RemoteProtocol.request("list"), response -> {
                JSONObject data = data(response); files = data.getJSONArray("files");
                if (!"files".equals(screen)) return;
                say(files.length() + " imported file(s) on watch"); showFiles();
                String active = data.optString("active", "");
                if (openActive && !active.isEmpty()) for (int i = 0; i < files.length(); i++) if (active.equals(files.getJSONObject(i).getString("id"))) { loadFile(active); break; }
            });
        } catch (Exception e) { error(e); }
    }
    private void showFiles() {
        begin("files", "Control watch view");
        text("Choose a CSV already imported on the watch. Keep its Receive from phone session running. The CSV viewer must have been opened on the watch; Back to viewer returns to it.", 15);
        button("Refresh watch files", () -> loadLibrary(false));
        button("Check command status", this::checkStatus);
        if (files.length() == 0) text("No files loaded yet. Transfer and import a CSV on the watch first, then refresh this list.", 15);
        try {
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.getJSONObject(i); String id = entry.getString("id");
                button(entry.getString("name") + "\n" + entry.getLong("rows") + " rows · " + entry.getInt("columns") + " columns", () -> loadFile(id));
            }
        } catch (JSONException e) { error(e); }
        if (draft != null) button("Return to phone draft", this::editor);
        button("Back to companion", this::finish);
    }
    private void loadFile(String id) {
        if (networkBusy) { say("Wait for the current watch request to finish."); return; }
        if (pendingId != null) { say("Wait for the pending command, or use Check command status, before reloading a view."); checkStatus(); return; }
        say("Loading watch columns and applied settings…");
        begin("loading", "Loading watch view");
        button("Back to file list", this::showFiles);
        if (draft != null) button("Return to phone draft", this::editor);
        try {
            request(RemoteProtocol.request("get").put("file", id), response -> {
                if (!"loading".equals(screen)) return;
                JSONObject value = data(response); JSONArray labels = value.getJSONArray("headers");
                String[] names = new String[labels.length()]; for (int i = 0; i < names.length; i++) names[i] = labels.getString(i);
                ViewSpec selected = RemoteProtocol.view(value.getJSONObject("view"), names.length);
                file = value.getString("file"); fileName = value.getString("name"); rowCount = value.getLong("rows");
                headers = names; draft = selected; query = value.optString("search", "");
                say("Loaded applied watch settings. Edits remain on the phone until you send them."); editor();
            });
        } catch (Exception e) { error(e); }
    }
    private void editor() {
        begin("editor", fileName);
        text(rowCount + " rows · " + headers.length + " columns", 14);
        button("Choose watch CSV", this::showFiles);
        button("Columns & order · " + draft.visibleCount() + " selected", () -> columns(-1, 0));
        button("Column filters", this::filters);
        button("Row sorting", this::sort);
        button("Apply columns & filters to watch", () -> apply(false));
        button("Reload from watch (discard phone draft)", () -> loadFile(file));
        text("Search text for watch", 18);
        EditText search = edit(query, "Text to find"); changes(search, value -> query = value);
        button("Paste search text", () -> paste(search));
        button("Apply view & search", () -> apply(true));
        button("Send search text only", () -> search("first"));
        button("← Previous match on watch", () -> search("previous"));
        button("Next match on watch →", () -> search("next"));
        text("Search-only actions use the watch's applied columns and filters. Apply view & search sends this draft first. Empty search text clears the watch search.", 14);
        button("Check command status", this::checkStatus);
        text("The watch saves received view settings as its last applied view. You can save them as a named preset on the watch.", 14);
    }
    private void columns(int anchor, int screenOffset) {
        begin("columns", "Watch columns & order");
        text("Select visible columns. Use ↑/↓ to move them. Hold an arrow to move the column to first/last. Hidden columns keep their place.", 15);
        button("Done", this::editor);
        button("Select all", () -> { Arrays.fill(draft.visible, true); columns(-1, 0); });
        button("Clear selection", () -> { Arrays.fill(draft.visible, false); columns(-1, 0); });
        button("Restore file order", () -> { draft.resetOrder(); columns(-1, 0); });
        View anchorView = null;
        for (int position = 0; position < draft.order.length; position++) {
            final int col = draft.order[position]; final LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            body.addView(row, new LinearLayout.LayoutParams(-1, -2));
            CheckBox check = new CheckBox(this); check.setText((position + 1) + ". " + headers[col]); check.setTextColor(Color.WHITE);
            check.setMinHeight(dp(48)); check.setChecked(draft.visible[col]); row.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
            check.setOnCheckedChangeListener((b, selected) -> draft.visible[col] = selected);
            Button up = new Button(this); up.setText("↑"); up.setContentDescription("Move " + headers[col] + " up"); row.addView(up, new LinearLayout.LayoutParams(dp(52), dp(52)));
            Button down = new Button(this); down.setText("↓"); down.setContentDescription("Move " + headers[col] + " down"); row.addView(down, new LinearLayout.LayoutParams(dp(52), dp(52)));
            up.setEnabled(position > 0); down.setEnabled(position < draft.order.length - 1);
            up.setOnClickListener(v -> move(col, draft.positionOf(col) - 1, row));
            down.setOnClickListener(v -> move(col, draft.positionOf(col) + 1, row));
            up.setOnLongClickListener(v -> { move(col, 0, row); return true; });
            down.setOnLongClickListener(v -> { move(col, draft.order.length - 1, row); return true; });
            if (col == anchor) anchorView = row;
        }
        if (anchorView != null) {
            View target = anchorView; ScrollView current = scroll;
            current.post(() -> { if (scroll == current) current.scrollTo(0, Math.max(0, target.getTop() - screenOffset)); });
        }
    }
    private void move(int column, int destination, View row) {
        if (destination < 0 || destination >= draft.order.length) return;
        int offset = row.getTop() - scroll.getScrollY(); draft.moveColumn(column, destination); columns(column, offset);
    }
    private void filters() {
        begin("filters", "Watch column filters");
        text("Contains text or regular expression, ignoring case. All active filters combine with AND, including filters on hidden columns.", 15);
        button("Done", this::editor);
        button("Clear all filters", () -> { Arrays.fill(draft.filters, ""); Arrays.fill(draft.regex, false); filters(); });
        for (int column : draft.order) {
            String value = draft.filters[column]; if (value.length() > 120) value = value.substring(0, 120) + "…";
            button(headers[column] + "\n" + (value.isEmpty() ? "No filter" : (draft.regex[column] ? "Regex: " : "Contains: ") + value), () -> filter(column));
        }
    }
    private void filter(int column) {
        filterColumn = column; begin("filter", headers[column]);
        EditText field = edit(draft.filters[column], "Filter text or regex"); changes(field, value -> draft.filters[column] = value);
        CheckBox regex = new CheckBox(this); regex.setText("Use regular expression"); regex.setTextColor(Color.WHITE); regex.setChecked(draft.regex[column]); body.addView(regex);
        regex.setOnCheckedChangeListener((b, checked) -> draft.regex[column] = checked);
        text("Examples: ^ABC starts with ABC; red|blue matches either; ^$ matches empty cells. Empty filter means no restriction.", 15);
        button("Paste filter", () -> paste(field));
        button("Copy filter", () -> copy(field.getText().toString()));
        button("Clear filter", () -> field.setText(""));
        button("Done", this::filters);
        text("Edits are retained in the phone draft. Nothing is sent until Apply.", 14);
    }
    private void sort() {
        begin("sort", "Watch row sorting");
        String[] labels = new String[headers.length + 1]; labels[0] = "Original row order";
        for (int i = 0; i < draft.order.length; i++) labels[i + 1] = headers[draft.order[i]];
        Spinner spinner = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        spinner.setSelection(draft.sort < 0 ? 0 : draft.positionOf(draft.sort) + 1); body.addView(spinner);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { draft.sort = position == 0 ? -1 : draft.order[position - 1]; }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        CheckBox descending = new CheckBox(this); descending.setText("Descending · Z–A"); descending.setTextColor(Color.WHITE); descending.setChecked(draft.descending); body.addView(descending);
        descending.setOnCheckedChangeListener((b, checked) -> draft.descending = checked);
        text("Text sorting, not numeric sorting: 10 comes before 2. Column display order is configured separately.", 15);
        button("Done", this::editor);
    }
    private void apply(boolean find) {
        try {
            if (draft.visibleCount() == 0) { say("Select at least one visible column before applying."); return; }
            for (int column = 0; column < draft.filters.length; column++) if (draft.regex[column] && !draft.filters[column].isEmpty()) {
                try { java.util.regex.Pattern.compile(draft.filters[column]); }
                catch (java.util.regex.PatternSyntaxException e) { say("Invalid regex in " + headers[column] + ": " + e.getDescription()); return; }
            }
            RemoteProtocol.view(draft.json(), headers.length);
            JSONObject command = RemoteProtocol.request("apply").put("file", file).put("view", draft.json());
            if (find) command.put("search", query).put("direction", "first");
            submit(command);
        } catch (Exception e) { error(e); }
    }
    private void search(String direction) {
        try { submit(RemoteProtocol.request("search").put("file", file).put("search", query).put("direction", direction)); }
        catch (Exception e) { error(e); }
    }
    private void submit(JSONObject command) throws Exception {
        if (networkBusy || pendingId != null) { say("Wait for confirmation of the previous command. Use Check command status if needed."); return; }
        RemoteProtocol.validate(command);
        pendingId = command.getString("id"); receipts.edit().putString("pending:" + node, pendingId).apply();
        say("Sending settings to watch…");
        request(command, this::commandReply);
    }
    private void commandReply(JSONObject reply) throws Exception {
        if (pendingId == null || !pendingId.equals(reply.getString("id"))) throw new IOException("The receipt belongs to a different command. Check command status again.");
        String state = reply.getString("status");
        say(reply.getString("message"));
        if ("accepted".equals(state)) {
            if (resumed) { handler.removeCallbacks(poll); handler.postDelayed(poll, 1200); }
            return;
        }
        pendingId = null; receipts.edit().remove("pending:" + node).apply(); handler.removeCallbacks(poll);
        if ("done".equals(state)) {
            JSONObject result = reply.getJSONObject("data");
            String details = result.has("matches") ? "\n" + result.getLong("matches") + " / " + result.getLong("rows") + " rows" : "";
            if (result.optBoolean("found")) details += "\nResult " + result.getLong("position") + " · " + result.getString("column");
            say(reply.getString("message") + details);
        }
    }
    private void checkStatus() {
        if (pendingId == null) { if (!networkBusy) say("No phone command is awaiting confirmation."); return; }
        if (networkBusy) { if (resumed) handler.postDelayed(poll, 1200); return; }
        try {
            request(RemoteProtocol.request("status").put("command", pendingId), response -> {
                if ("failed".equals(response.getString("status"))) {
                    pendingId = null; receipts.edit().remove("pending:" + node).apply(); say(response.getString("message")); return;
                }
                commandReply(data(response).getJSONObject("result"));
            });
        } catch (Exception e) { error(e); }
    }
    private void paste(EditText field) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            ClipData data = clipboard.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) { say("Clipboard is empty."); return; }
            CharSequence text = data.getItemAt(0).coerceToText(this); if (text == null) return;
            int start = Math.max(0, field.getSelectionStart()), end = Math.max(0, field.getSelectionEnd());
            field.getText().replace(Math.min(start, end), Math.max(start, end), text);
        } catch (Exception e) { error(e); }
    }
    private void copy(String text) {
        try { ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("CSV filter", text)); say("Copied filter."); }
        catch (Exception e) { error(e); }
    }
    @Override protected void navigateBack() {
        switch (screen) {
            case "filter": filters(); break;
            case "columns": case "filters": case "sort": editor(); break;
            case "editor": showFiles(); break;
            case "loading": if (draft != null) editor(); else showFiles(); break;
            default: finish();
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("files", files.toString()); state.putString("notice", notice); state.putString("screen", screen); state.putInt("filterColumn", filterColumn);
        if (draft != null) try {
            JSONArray labels = new JSONArray(); for (String header : headers) labels.put(header);
            state.putString("draft", new JSONObject().put("file", file).put("name", fileName).put("rows", rowCount).put("headers", labels)
                .put("view", draft.json()).put("search", query).toString());
        } catch (JSONException ignored) {}
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; if (pendingId != null) handler.postDelayed(poll, 1000); }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { dead = true; handler.removeCallbacksAndMessages(null); client.close(); worker.shutdownNow(); super.onDestroy(); }
}
