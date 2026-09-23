package dev.watchcsv.viewer;

import android.app.*;
import android.content.*;
import android.content.ClipboardManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends BackActivity implements RemoteBridge.Target {
    private static final int PICK_CSV = 41;
    private static final int BG = Color.rgb(8, 15, 19), CARD = Color.rgb(23, 37, 43);
    private static final int INK = Color.rgb(236, 245, 246), MUTED = Color.rgb(166, 185, 192), ACCENT = Color.rgb(120, 223, 202);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AtomicBoolean cancel = new AtomicBoolean();
    private volatile boolean dead;
    private boolean busy;
    private ScrollView scroll;
    private LinearLayout body;
    private TextView progress;
    private String screen = "library";
    private CsvStore store;
    private ViewSpec spec, draft;
    private List<CsvStore.Row> page = new ArrayList<>();
    private long pageStart;
    private String searchText = "";
    private TextSearch.Hit hit;
    private CsvStore.Row detailRow;
    private float downX, downY;
    private int resultsScroll, columnOrderScroll;
    private String columnOrderParent = "settings";
    private android.content.SharedPreferences prefs;
    private CompletableFuture<JSONObject> remoteResult;
    private PowerManager.WakeLock remoteWakeLock;

    private interface Work<T> { T run(AtomicBoolean cancelled, CsvStore.Progress progress) throws Exception; }
    private interface Done<T> { void accept(T result); }
    private interface Opener { InputStream open() throws Exception; }
    private static final class Source {
        final String name; final Opener opener;
        Source(String name, Opener opener) { this.name = name; this.opener = opener; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("views", MODE_PRIVATE);
        if (getPackageManager().hasSystemFeature("android.hardware.type.watch")) RemoteBridge.attach(this);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        if (getIntent().getData() != null && Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            try { showImportOptions(Collections.singletonList(uriSource(getIntent().getData()))); }
            catch (Exception e) { showLibrary(); error(e); }
        } else if (getIntent().getBooleanExtra("openInbox", false)) showInbox();
        else showLibrary();
    }

    private File databases() { return new File(getFilesDir(), "datasets"); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.getBooleanExtra("openInbox", false)) {
            if (busy) toast("Wait for the current viewer operation before opening Inbox."); else showInbox();
        }
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(18)); return d;
    }
    private void begin(String where, String title) {
        if (scroll != null && "rows".equals(screen)) resultsScroll = scroll.getScrollY();
        if (scroll != null && "column-order".equals(screen)) columnOrderScroll = scroll.getScrollY();
        screen = where;
        scroll = new ScrollView(this); scroll.setBackgroundColor(BG); scroll.setFillViewport(true);
        scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(27), dp(34), dp(27), dp(42));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        TextView heading = text(title, 19, ACCENT); heading.setTypeface(null, Typeface.BOLD); heading.setGravity(Gravity.CENTER);
        space(9);
    }
    private void restoreScroll(int y) {
        ScrollView target = scroll;
        target.post(() -> { if (scroll == target) target.scrollTo(0, y); });
    }
    private TextView text(CharSequence value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0, dp(5), 0, dp(5)); body.addView(t, new LinearLayout.LayoutParams(-1, -2)); return t;
    }
    private void space(int height) { View v = new View(this); body.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private Button button(String title, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(INK);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setGravity(Gravity.CENTER);
        b.setPadding(dp(9), dp(9), dp(9), dp(9)); b.setBackground(background(CARD));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(6);
        body.addView(b, p); b.setOnClickListener(v -> action.run()); return b;
    }
    private EditText edit(String value, String hint) {
        EditText e = new EditText(this); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setTextSize(15);
        e.setSingleLine(true); e.setSelectAllOnFocus(false);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value); e.setHint(hint); e.setMinHeight(dp(48));
        body.addView(e, new LinearLayout.LayoutParams(-1, -2)); return e;
    }
    private CheckBox check(String title, boolean selected) {
        CheckBox c = new CheckBox(this); c.setText(title); c.setTextColor(INK); c.setTextSize(14);
        c.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT)); c.setChecked(selected); c.setMinHeight(dp(48));
        body.addView(c, new LinearLayout.LayoutParams(-1, -2)); return c;
    }
    private Spinner spinner(String[] choices, int selected) {
        Spinner s = new Spinner(this, Spinner.MODE_DIALOG);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter); s.setSelection(selected); s.setMinimumHeight(dp(48));
        body.addView(s, new LinearLayout.LayoutParams(-1, -2)); return s;
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void error(Exception e) {
        if (dead) return;
        if (e instanceof InterruptedIOException) { toast("Cancelled"); return; }
        if (e instanceof SecurityException) {
            new AlertDialog.Builder(this).setTitle("File access needed")
                .setMessage("Android did not allow access to this file, or the file grant expired. Tap Choose file, then select the CSV to grant read access. No access to other files is needed.")
                .setPositiveButton("Choose file", (d, w) -> pickFiles()).setNegativeButton("Cancel", null).show();
            return;
        }
        String message = e.getMessage();
        if (e instanceof java.nio.charset.CharacterCodingException) message = "Cannot decode this file. Export as CSV UTF-8, or choose the correct encoding in import options.";
        new AlertDialog.Builder(this).setTitle("Could not complete").setMessage(message == null ? e.toString() : message).setPositiveButton("OK", null).show();
    }
    private <T> void job(String title, Work<T> work, Done<T> done, Runnable failure) {
        if (busy || dead) return;
        busy = true; AtomicBoolean token = new AtomicBoolean(); cancel = token;
        begin("busy", title); progress = text("Please wait…", 15, INK); progress.setGravity(Gravity.CENTER);
        ProgressBar indicator = new ProgressBar(this); body.addView(indicator, new LinearLayout.LayoutParams(-1, dp(36)));
        button("Cancel", () -> { token.set(true); progress.setText("Cancelling…"); });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            try {
                T result = work.run(token, value -> runOnUiThread(() -> { if (!dead && busy && cancel == token) progress.setText(value); }));
                runOnUiThread(() -> {
                    if (dead) return;
                    busy = false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); done.accept(result);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (dead) return;
                    busy = false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); failure.run(); error(e);
                });
            }
        });
    }

    private void showLibrary() {
        job("Your CSV files", (token, update) -> {
            if (store != null) { store.close(); store = null; }
            prefs.edit().remove("activeFile").apply();
            return CsvStore.library(databases());
        }, this::renderLibrary, () -> renderLibrary(new ArrayList<>()));
    }
    private void renderLibrary(List<CsvStore.Info> items) {
        begin("library", "Watch CSV");
        text("Offline • " + items.size() + " files", 13, MUTED).setGravity(Gravity.CENTER);
        button("＋ Import CSV", this::showImportMenu);
        button(getPackageManager().hasSystemFeature("android.hardware.type.watch") ? "Receive from phone" : "Send CSV to watch",
            () -> startActivity(new Intent(this, TransferActivity.class)));
        if (items.isEmpty()) text("Import a CSV once. Then pick columns, filter, and search without loading the whole file on screen.", 14, INK);
        for (CsvStore.Info info : items) {
            Button b = button(info.name + "\n" + info.rows + " rows · " + info.headers.length + " columns", () -> open(info));
            b.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle("Delete imported file?").setMessage(info.name + "\nThe original CSV will be kept.")
                    .setNegativeButton("Keep", null).setPositiveButton("Delete", (d, w) -> delete(info)).show(); return true;
            });
        }
        if (!items.isEmpty()) text("Hold a file to delete its imported copy.", 12, MUTED);
    }
    private void delete(CsvStore.Info info) {
        job("Deleting", (token, update) -> {
            if (!android.database.sqlite.SQLiteDatabase.deleteDatabase(info.file)) throw new IOException("Could not delete the imported copy");
            prefs.edit().remove("last:" + info.file.getName()).remove("presets:" + info.file.getName()).remove("search:" + info.file.getName()).apply();
            return CsvStore.library(databases());
        }, this::renderLibrary, this::showLibrary);
    }
    private void open(CsvStore.Info info) {
        job("Opening CSV", (token, update) -> {
            if (store != null) { store.close(); store = null; }
            CsvStore opened = new CsvStore(info);
            try {
                ViewSpec selected;
                try { selected = ViewSpec.from(new JSONObject(prefs.getString("last:" + info.file.getName(), "{}")), info.headers.length); }
                catch (Exception ignored) { selected = new ViewSpec(info.headers.length); }
                opened.apply(selected, token, update);
                List<CsvStore.Row> rows = opened.page(0, selected);
                store = opened; spec = selected; return rows;
            } catch (Exception e) { opened.close(); throw e; }
        }, rows -> {
            page = rows; pageStart = 0; resultsScroll = 0; searchText = prefs.getString("search:" + info.file.getName(), ""); hit = null;
            prefs.edit().putString("activeFile", info.file.getName()).apply(); showRows();
        }, this::showLibrary);
    }

    private void showRows() {
        if (store == null) { showLibrary(); return; }
        begin("rows", store.info.name);
        text(store.matches + " / " + store.info.rows + " rows", 14, ACCENT).setGravity(Gravity.CENTER);
        text("Swipe ← for columns & filters", 12, MUTED).setGravity(Gravity.CENTER);
        button("Columns & filters", this::startSettings);
        button("Search text", this::showSearch);
        if (page.isEmpty()) text("No rows match. Change or clear the column filters.", 15, INK);
        else {
            text("Rows " + (pageStart + 1) + "–" + Math.min(pageStart + CsvStore.PAGE_SIZE, store.matches), 13, MUTED);
            if (pageStart > 0) button("↑ Previous page", () -> loadPage(pageStart - CsvStore.PAGE_SIZE));
            for (CsvStore.Row row : page) {
                StringBuilder label = new StringBuilder("#").append(row.id);
                int shown = 0;
                for (int i : spec.order) if (spec.visible[i]) {
                    if (shown++ == 4) { label.append("\n… tap for all selected columns"); break; }
                    label.append('\n').append(shorten(store.info.headers[i], 35)).append(": ").append(shorten(row.cells[i], 110));
                }
                Button b = button(label.toString(), () -> showDetail(row)); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                if (hit != null && hit.position == row.position) b.setTextColor(ACCENT);
            }
            if (pageStart + CsvStore.PAGE_SIZE < store.matches) button("↓ Next page", () -> loadPage(pageStart + CsvStore.PAGE_SIZE));
            button("Go to result row…", this::jumpToRow);
        }
        button("All files", this::showLibrary);
        restoreScroll(resultsScroll);
    }
    private String shorten(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "…"; }
    private void loadPage(long start) {
        long safe = Math.max(0, start);
        job("Loading rows", (token, update) -> store.page(safe, spec), rows -> { pageStart = safe; page = rows; resultsScroll = 0; showRows(); }, this::showRows);
    }
    private void jumpToRow() {
        begin("jump", "Go to result row");
        text("Position in the filtered, sorted results (1–" + store.matches + ")", 14, MUTED);
        EditText e = edit("", "Row number"); e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        button("Go", () -> {
            try {
                long n = Long.parseLong(e.getText().toString());
                if (n < 1 || n > store.matches) throw new NumberFormatException();
                hideKeyboard(e); loadPage(((n - 1) / CsvStore.PAGE_SIZE) * CsvStore.PAGE_SIZE);
            } catch (NumberFormatException ex) { toast("Enter a row from 1 to " + store.matches); }
        });
        button("Back", this::showRows);
    }
    private void showDetail(CsvStore.Row row) {
        detailRow = row; begin("detail", "Row #" + row.id);
        TextView matchView = null;
        text("Result " + (row.position + 1) + " / " + store.matches, 13, MUTED);
        button("Back to results", this::showRows);
        if (!searchText.isEmpty()) {
            text("Find: " + searchText, 13, ACCENT);
            button("← Previous occurrence", () -> find(false, searchText));
            button("Next occurrence →", () -> find(true, searchText));
        }
        for (int i : spec.order) if (spec.visible[i]) {
            final int col = i;
            space(8); text(store.info.headers[i], 14, ACCENT).setTypeface(null, Typeface.BOLD);
            String value = row.cells[i];
            boolean current = hit != null && hit.position == row.position && hit.column == i;
            // Keep giant cells out of the layout; a full selectable cell has its own screen.
            int from = current ? Math.max(0, hit.start - 100) : 0;
            int to = Math.min(value.length(), current ? Math.max(from + 500, hit.end + 100) : 700);
            String prefix = from > 0 ? "…" : "";
            String excerpt = prefix + value.substring(from, to) + (to < value.length() ? "…" : "");
            SpannableString styled = new SpannableString(excerpt.isEmpty() ? "(empty)" : excerpt);
            if (current && hit.end <= to) {
                int a = prefix.length() + hit.start - from, b = prefix.length() + hit.end - from;
                styled.setSpan(new BackgroundColorSpan(ACCENT), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new ForegroundColorSpan(BG), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            TextView cell = text(styled, 16, INK); cell.setTextIsSelectable(true);
            if (current) {
                matchView = cell;
                text("▲ Current occurrence", 12, ACCENT);
                button("← Previous occurrence", () -> find(false, searchText));
                button("Next occurrence →", () -> find(true, searchText));
            }
            button("Copy " + shorten(store.info.headers[i], 22), () -> copy(value));
            if (from > 0 || to < value.length()) button("Read full cell", () -> fullCell(row, col, 0));
        }
        button("Copy selected row", () -> {
            StringBuilder value = new StringBuilder();
            for (int i : spec.order) if (spec.visible[i]) value.append(store.info.headers[i]).append(": ").append(row.cells[i]).append('\n');
            copy(value.toString());
        });
        button("Back to results", this::showRows);
        if (matchView != null) {
            final TextView target = matchView; final ScrollView detailScroll = scroll;
            detailScroll.post(() -> { if (scroll == detailScroll) detailScroll.scrollTo(0, Math.max(0, target.getTop() - dp(26))); });
        }
    }
    private void fullCell(CsvStore.Row row, int column, int offset) {
        begin("cell", store.info.headers[column]);
        String value = row.cells[column]; int end = Math.min(value.length(), offset + 3000);
        text("Characters " + (offset + 1) + "–" + end + " / " + value.length(), 12, MUTED);
        text(value.substring(offset, end), 16, INK).setTextIsSelectable(true);
        if (offset > 0) button("Previous part", () -> fullCell(row, column, Math.max(0, offset - 3000)));
        if (end < value.length()) button("Next part", () -> fullCell(row, column, end));
        button("Copy full cell", () -> copy(value)); button("Back to row", () -> showDetail(row));
    }

    private void startSettings() { draft = spec.copy(); showSettings(); }
    private void showSettings() {
        begin("settings", "View settings");
        text("Select columns and filter their contents. Filters combine with AND.", 13, MUTED);
        button("Choose columns · " + draft.visibleCount(), this::showColumns);
        button("Reorder columns", () -> openColumnOrder("settings"));
        button("Column filters", this::showFilters);
        button("Sort order", this::showSort);
        button("Saved presets", this::showPresets);
        button("Apply view", () -> apply(draft.copy()));
        button("Reset view", () -> { draft = new ViewSpec(store.info.headers.length); showSettings(); });
        button("Discard changes", this::showRows);
    }
    private void showColumns() {
        begin("columns", "Visible columns");
        text("Only selected columns appear in rows and text search. Hidden columns can still filter results.", 13, MUTED);
        button("Reorder columns", () -> openColumnOrder("columns"));
        button("Select all", () -> { Arrays.fill(draft.visible, true); showColumns(); });
        button("Clear selection", () -> { Arrays.fill(draft.visible, false); showColumns(); });
        for (int i : draft.order) {
            final int col = i;
            check(store.info.headers[i], draft.visible[i]).setOnCheckedChangeListener((b, selected) -> draft.visible[col] = selected);
        }
        button("Done", () -> { if (draft.visibleCount() == 0) toast("Choose at least one column"); else showSettings(); });
    }
    private void openColumnOrder(String parent) {
        columnOrderParent = parent; columnOrderScroll = 0; showColumnOrder();
    }
    private void leaveColumnOrder() {
        if ("columns".equals(columnOrderParent)) showColumns(); else showSettings();
    }
    private void showColumnOrder() {
        begin("column-order", "Reorder columns");
        text("Tap a column to move it. This changes display and search order, not the CSV. Then tap Apply view in settings.", 13, MUTED);
        button("Done", this::leaveColumnOrder);
        button("Restore file order", () -> { draft.resetOrder(); showColumnOrder(); });
        for (int position = 0; position < draft.order.length; position++) {
            final int col = draft.order[position];
            button((position + 1) + ". " + store.info.headers[col] + (draft.visible[col] ? "" : "\nHidden"), () -> showColumnPosition(col));
        }
        restoreScroll(columnOrderScroll);
    }
    private void showColumnPosition(int column) {
        begin("column-position", store.info.headers[column]);
        int position = draft.positionOf(column);
        text("Position " + (position + 1) + " of " + draft.order.length, 15, ACCENT);
        text("Moves are kept in the draft. Use Back to return, then Apply view in settings.", 12, MUTED);
        Button up = button("↑ Move up", () -> moveColumn(column, draft.positionOf(column) - 1));
        up.setEnabled(position > 0); up.setAlpha(position > 0 ? 1f : 0.4f);
        Button down = button("↓ Move down", () -> moveColumn(column, draft.positionOf(column) + 1));
        down.setEnabled(position < draft.order.length - 1); down.setAlpha(position < draft.order.length - 1 ? 1f : 0.4f);
        button("Move to first", () -> moveColumn(column, 0));
        button("Move to last", () -> moveColumn(column, draft.order.length - 1));
        EditText destination = edit(Integer.toString(position + 1), "Position");
        destination.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        button("Move to position", () -> {
            try {
                int n = Integer.parseInt(destination.getText().toString());
                if (n < 1 || n > draft.order.length) throw new NumberFormatException();
                hideKeyboard(destination); moveColumn(column, n - 1);
            } catch (NumberFormatException e) { toast("Enter a position from 1 to " + draft.order.length); }
        });
        button("Done", this::showColumnOrder);
    }
    private void moveColumn(int column, int destination) {
        int y = scroll.getScrollY();
        draft.moveColumn(column, destination); showColumnPosition(column); restoreScroll(y);
    }
    private void showFilters() {
        begin("filters", "Column filters");
        text("Contains text or regex. Case-insensitive. Empty = no filter.", 13, MUTED);
        for (int i : draft.order) {
            final int col = i;
            button(store.info.headers[i] + (draft.filters[i].isEmpty() ? "\nNo filter" : "\n" + (draft.regex[i] ? "Regex: " : "Contains: ") + shorten(draft.filters[i], 65)), () -> editFilter(col));
        }
        button("Clear all filters", () -> { Arrays.fill(draft.filters, ""); Arrays.fill(draft.regex, false); showFilters(); });
        button("Done", this::showSettings);
    }
    private void editFilter(int column) {
        begin("filter-edit", store.info.headers[column]);
        EditText e = edit(draft.filters[column], "Text or regex");
        CheckBox regex = check("Use regular expression", draft.regex[column]);
        text("Regex examples: ^ABC = starts with ABC; red|blue = either; ^$ = empty cell.", 12, MUTED);
        clipboardButtons(e);
        button("Save filter", () -> {
            String value = e.getText().toString();
            try {
                if (regex.isChecked() && !value.isEmpty()) java.util.regex.Pattern.compile(value);
                draft.filters[column] = value; draft.regex[column] = regex.isChecked(); hideKeyboard(e); showFilters();
            } catch (java.util.regex.PatternSyntaxException ex) { toast("Invalid regex: " + ex.getDescription()); }
        });
        button("Clear field", () -> e.setText("")); button("Cancel", this::showFilters);
    }
    private void showSort() {
        begin("sort", "Sort rows");
        text("Text order, A–Z or Z–A. For example, 10 sorts before 2. One sort column at a time.", 13, MUTED);
        String[] columns = new String[store.info.headers.length + 1]; columns[0] = "Original file order";
        for (int i = 0; i < draft.order.length; i++) columns[i + 1] = store.info.headers[draft.order[i]];
        Spinner s = spinner(columns, draft.sort < 0 ? 0 : draft.positionOf(draft.sort) + 1);
        CheckBox reverse = check("Descending · Z–A", draft.descending);
        button("Done", () -> { int selected = s.getSelectedItemPosition(); draft.sort = selected == 0 ? -1 : draft.order[selected - 1]; draft.descending = reverse.isChecked(); showSettings(); });
        button("Cancel", this::showSettings);
    }
    private void apply(ViewSpec selected) {
        if (selected.visibleCount() == 0) { toast("Choose at least one column"); return; }
        try { selected.validateOrder(); selected.patterns(); } catch (Exception e) { error(e); return; }
        job("Applying view", (token, update) -> {
            store.apply(selected, token, update);
            return true;
        }, applied -> {
            spec = selected; pageStart = 0; page = new ArrayList<>(); hit = null;
            try { prefs.edit().putString("last:" + store.info.file.getName(), selected.json().toString()).apply(); }
            catch (JSONException e) { error(e); }
            loadPage(0);
        }, this::showSettings);
    }
    private JSONObject presets() {
        try { return new JSONObject(prefs.getString("presets:" + store.info.file.getName(), "{}")); }
        catch (JSONException e) { return new JSONObject(); }
    }
    private void savePresets(JSONObject values) { prefs.edit().putString("presets:" + store.info.file.getName(), values.toString()).apply(); }
    private void showPresets() {
        begin("presets", "Saved presets");
        text("Saved per CSV file. Includes column selection and order, filters, regex flags, and row sorting.", 13, MUTED);
        button("Save current settings…", this::namePreset);
        JSONObject saved = presets(); List<String> names = new ArrayList<>();
        Iterator<String> it = saved.keys(); while (it.hasNext()) names.add(it.next()); Collections.sort(names);
        for (String name : names) {
            button(name, () -> {
                try { draft = ViewSpec.from(saved.getJSONObject(name), store.info.headers.length); showSettings(); toast("Preset loaded. Tap Apply view."); }
                catch (Exception e) { error(e); }
            }).setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle("Delete preset?").setMessage(name).setNegativeButton("Keep", null)
                    .setPositiveButton("Delete", (d, w) -> { saved.remove(name); savePresets(saved); showPresets(); }).show(); return true;
            });
        }
        if (!names.isEmpty()) text("Hold a preset to delete it.", 12, MUTED);
        button("Back", this::showSettings);
    }
    private void namePreset() {
        begin("preset-name", "Save preset"); EditText e = edit("", "Preset name");
        button("Save", () -> {
            String name = e.getText().toString().trim();
            if (name.isEmpty()) { toast("Enter a preset name"); return; }
            if (draft.visibleCount() == 0) { toast("Choose at least one column first"); return; }
            Runnable save = () -> {
                try { JSONObject values = presets(); values.put(name, draft.json()); savePresets(values); hideKeyboard(e); showPresets(); toast("Preset saved"); }
                catch (Exception ex) { error(ex); }
            };
            if (presets().has(name)) new AlertDialog.Builder(this).setTitle("Replace preset?").setMessage(name).setNegativeButton("Cancel", null).setPositiveButton("Replace", (d, w) -> save.run()).show();
            else save.run();
        });
        button("Cancel", this::showPresets);
    }

    private void showSearch() {
        begin("search", "Find text");
        text("Search visible columns in the filtered, sorted view. Previous/next visits every occurrence, including repeats in one cell.", 13, MUTED);
        EditText e = edit(searchText, "Search text"); clipboardButtons(e);
        button("Next occurrence →", () -> { hideKeyboard(e); find(true, e.getText().toString()); });
        button("← Previous occurrence", () -> { hideKeyboard(e); find(false, e.getText().toString()); });
        button("Start at beginning", () -> {
            String value = e.getText().toString(); if (value.isEmpty()) { toast("Enter search text"); return; }
            hideKeyboard(e); find(true, value, true);
        });
        button("Back to results", this::showRows);
    }
    private void find(boolean forward, String value) {
        find(forward, value, false);
    }
    private void find(boolean forward, String value, boolean fromBeginning) {
        if (value.isEmpty()) { toast("Enter search text"); return; }
        if (fromBeginning || !value.equals(searchText)) hit = null;
        searchText = value;
        prefs.edit().putString("search:" + store.info.file.getName(), value).apply();
        long initial = fromBeginning ? 0 : (forward ? pageStart : Math.min(store.matches - 1, pageStart + CsvStore.PAGE_SIZE - 1));
        job("Finding text", (token, update) -> {
            TextSearch.Hit result = store.search(value, hit, initial, forward, spec, token);
            if (result == null) return null;
            long start = (result.position / CsvStore.PAGE_SIZE) * CsvStore.PAGE_SIZE;
            List<CsvStore.Row> rows = store.page(start, spec);
            return new Object[]{result, rows, start};
        }, found -> {
            if (found == null) { showSearch(); toast(forward ? "No later occurrence. Use Start at beginning to search again." : "No earlier occurrence."); return; }
            hit = (TextSearch.Hit)found[0];
            @SuppressWarnings("unchecked") List<CsvStore.Row> rows = (List<CsvStore.Row>)found[1];
            page = rows; pageStart = (Long)found[2]; resultsScroll = 0;
            for (CsvStore.Row row : rows) if (row.position == hit.position) { showDetail(row); return; }
            showRows();
        }, this::showSearch);
    }
    private void copy(String value) {
        try { ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Watch CSV", value)); toast("Copied"); }
        catch (Exception e) { error(e); }
    }
    private void clipboardButtons(EditText field) {
        button("Copy field", () -> copy(field.getText().toString()));
        button("Paste", () -> {
            try {
                ClipboardManager manager = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = manager.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) { toast("Clipboard is empty on this watch"); return; }
                CharSequence value = clip.getItemAt(0).coerceToText(this);
                if (value == null) { toast("Clipboard has no text"); return; }
                int start = Math.max(0, field.getSelectionStart()), end = Math.max(0, field.getSelectionEnd());
                field.getText().replace(Math.min(start, end), Math.max(start, end), value); field.requestFocus();
            } catch (Exception e) { error(e); }
        });
    }
    private void hideKeyboard(View field) {
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private void showImportMenu() {
        begin("import-menu", "Import CSV");
        text("Export your spreadsheet as CSV UTF-8. Multiple files stay separate in the library.", 14, INK);
        text("File access: choose a CSV in Android's picker to grant read access to that file only. Broad storage permission is not required.", 12, MUTED);
        button("Choose files…", this::pickFiles);
        button(getPackageManager().hasSystemFeature("android.hardware.type.watch") ? "Receive from phone" : "Send CSV to watch",
            () -> startActivity(new Intent(this, TransferActivity.class)));
        button("Import from Inbox", this::showInbox);
        text("If Wear OS has no system file picker, transfer CSVs to the app's Inbox and import from there.", 12, MUTED);
        button("Back to files", this::showLibrary);
    }
    private void pickFiles() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(picker, PICK_CSV); }
        catch (ActivityNotFoundException e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { startActivityForResult(fallback, PICK_CSV); }
            catch (ActivityNotFoundException ignored) {
                new AlertDialog.Builder(this).setTitle("No file picker installed").setMessage("This watch has no app that provides a file picker. Use Import from Inbox, or install a compatible file picker.").setPositiveButton("Inbox", (d, w) -> showInbox()).setNegativeButton("Back", null).show();
            }
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_CSV || result != RESULT_OK || data == null) return;
        try {
            List<Source> sources = new ArrayList<>(); Set<Uri> seen = new HashSet<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri(); if (uri != null && seen.add(uri)) sources.add(uriSource(uri));
            }
            if (data.getData() != null && seen.add(data.getData())) sources.add(uriSource(data.getData()));
            if (!sources.isEmpty()) showImportOptions(sources);
        } catch (Exception e) { error(e); }
    }
    private Source uriSource(Uri uri) {
        String name = "Imported CSV";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (Exception ignored) { if (uri.getLastPathSegment() != null) name = uri.getLastPathSegment(); }
        return new Source(name, () -> {
            InputStream input = getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException("Cannot open the selected file"); return input;
        });
    }
    private void showInbox() {
        begin("inbox", "CSV Inbox");
        File external = getExternalFilesDir(null);
        if (external == null) { text("External storage is unavailable.", 14, INK); button("Back", this::showImportMenu); return; }
        File inbox = new File(external, "Inbox");
        if (!inbox.isDirectory() && !inbox.mkdirs()) { text("Cannot create Inbox.", 14, INK); button("Back", this::showImportMenu); return; }
        text("Use Receive from phone to send files here from the companion. Then tap Refresh, select a CSV, and choose import options.", 13, MUTED);
        button("Phone transfer", () -> startActivity(new Intent(this, TransferActivity.class)));
        text("Inbox location:", 12, MUTED);
        text(inbox.getAbsolutePath(), 12, INK).setTextIsSelectable(true);
        File[] files = inbox.listFiles(f -> f.isFile() && (f.getName().toLowerCase(Locale.ROOT).endsWith(".csv") || f.getName().toLowerCase(Locale.ROOT).endsWith(".tsv") || f.getName().toLowerCase(Locale.ROOT).endsWith(".txt")));
        List<Source> sources = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) sources.add(new Source(file.getName(), () -> new FileInputStream(file)));
        }
        if (sources.isEmpty()) text("Inbox is empty.", 15, INK);
        else {
            if (sources.size() > 1) button("Import all · " + sources.size(), () -> showImportOptions(sources));
            for (Source source : sources) button(source.name, () -> showImportOptions(Collections.singletonList(source)));
        }
        button("Refresh", this::showInbox); button("Back", this::showImportMenu);
    }
    private void showImportOptions(List<Source> sources) {
        begin("import-options", "Import options");
        text(sources.size() == 1 ? sources.get(0).name : sources.size() + " files", 15, INK);
        text("Encoding (BOM overrides this)", 13, MUTED);
        String[] encodings = {"UTF-8", "windows-1251", "windows-1252", "UTF-16LE", "UTF-16BE"};
        Spinner encoding = spinner(encodings, 0);
        text("Column separator", 13, MUTED);
        Spinner delimiter = spinner(new String[]{"Auto detect", "Comma ,", "Semicolon ;", "Tab", "Pipe |"}, 0);
        CheckBox header = check("First row has column names", true);
        text("Import is streamed to a local database. The source file is kept. All selected files use these options.", 12, MUTED);
        button("Import", () -> {
            String charset = encodings[encoding.getSelectedItemPosition()];
            char separator = new char[]{0, ',', ';', '\t', '|'}[delimiter.getSelectedItemPosition()];
            boolean firstHeader = header.isChecked();
            job("Importing CSV", (token, update) -> {
                List<CsvStore.Info> imported = new ArrayList<>();
                for (Source source : sources) {
                    CsvStore.check(token); update.update(source.name);
                    String lower = source.name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) throw new IOException("Export " + source.name + " as CSV UTF-8 in Excel first. This app imports CSV, not Excel workbooks.");
                    imported.add(CsvStore.importFile(databases(), source.opener.open(), source.name, charset, separator, firstHeader, token,
                        message -> update.update(source.name + "\n" + message)));
                }
                return imported;
            }, imported -> { if (imported.size() == 1) open(imported.get(0)); else showLibrary(); }, this::showLibrary);
        });
        button("Cancel", this::showImportMenu);
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); }
        else if (event.getActionMasked() == MotionEvent.ACTION_UP && !busy && "rows".equals(screen)
                 && downX - event.getX() > dp(65) && Math.abs(downY - event.getY()) < dp(45)) {
            MotionEvent cancelled = MotionEvent.obtain(event); cancelled.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancelled); cancelled.recycle(); startSettings(); return true;
        }
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) && scroll != null) {
            scroll.smoothScrollBy(0, (int)(-event.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(48))); return true;
        }
        return super.onGenericMotionEvent(event);
    }
    @Override protected void navigateBack() {
        if (busy) { cancel.set(true); progress.setText("Cancelling…"); return; }
        switch (screen) {
            case "library": super.navigateBack(); break;
            case "rows": showLibrary(); break;
            case "settings": case "search": case "jump": case "detail": showRows(); break;
            case "cell": showDetail(detailRow); break;
            case "filter-edit": showFilters(); break;
            case "column-position": showColumnOrder(); break;
            case "column-order": leaveColumnOrder(); break;
            case "columns": case "filters": case "sort": case "presets": showSettings(); break;
            case "preset-name": showPresets(); break;
            case "import-options": case "inbox": showImportMenu(); break;
            default: showLibrary();
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (getPackageManager().hasSystemFeature("android.hardware.type.watch")) RemoteBridge.attach(this);
    }
    private static final class RemoteViewResult {
        ViewSpec view;
        List<CsvStore.Row> rows;
        TextSearch.Hit found;
        String query;
        long start;
        JSONObject receipt;
    }
    @Override public void remoteCommand(JSONObject request, CompletableFuture<JSONObject> result) {
        if (dead || busy) { result.completeExceptionally(new IOException("The watch viewer is busy. Wait for its current operation, then retry.")); return; }
        if (Arrays.asList("settings", "columns", "column-order", "column-position", "filters", "filter-edit", "sort", "presets", "preset-name").contains(screen)) {
            result.completeExceptionally(new IOException("Finish or discard the settings being edited on the watch, then apply the phone settings.")); return;
        }
        final String file, operation;
        try { RemoteProtocol.validate(request); file = request.getString("file"); operation = request.getString("op"); }
        catch (Exception e) { result.completeExceptionally(e); return; }
        remoteResult = result;
        remoteWakeLock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchCSV:RemoteView");
        remoteWakeLock.setReferenceCounted(false); remoteWakeLock.acquire(15 * 60_000L);
        final boolean sameFile = store != null && file.equals(store.info.file.getName());
        final ViewSpec existing = sameFile ? spec.copy() : null;
        final TextSearch.Hit previousHit = sameFile ? hit : null;
        final String previousQuery = sameFile ? searchText : "";
        final long previousPage = sameFile ? pageStart : 0;
        job("Phone controls", (token, update) -> {
            try {
                CsvStore.Info info = RemoteOperations.info(this, file);
                ViewSpec selected = "apply".equals(operation) ? RemoteProtocol.view(request.getJSONObject("view"), info.headers.length)
                    : existing != null ? existing : RemoteOperations.savedView(this, info);
                boolean rebuild = !sameFile || "apply".equals(operation);
                if (rebuild) {
                    if (store != null) { store.close(); store = null; }
                    store = new CsvStore(info); store.apply(selected, token, update);
                }
                CsvStore.check(token);
                RemoteViewResult answer = new RemoteViewResult(); answer.view = selected;
                answer.query = request.has("search") ? request.getString("search") : prefs.getString("search:" + file, "");
                boolean performSearch = request.has("search") && !answer.query.isEmpty();
                answer.start = rebuild ? 0 : previousPage;
                String message = "View applied on watch";
                if (performSearch) {
                    String direction = request.optString("direction", "first"); boolean forward = !"previous".equals(direction);
                    boolean continuing = !rebuild && !"first".equals(direction) && previousQuery.equals(answer.query);
                    TextSearch.Hit after = continuing ? previousHit : null;
                    long initial = "first".equals(direction) ? 0 : forward ? answer.start : Math.min(store.matches - 1, answer.start + CsvStore.PAGE_SIZE - 1);
                    answer.found = store.search(answer.query, after, initial, forward, selected, token);
                    if (answer.found != null) { answer.start = (answer.found.position / CsvStore.PAGE_SIZE) * CsvStore.PAGE_SIZE; message = "Match selected in watch viewer"; }
                    else {
                        message = "No " + ("first".equals(direction) ? "matching" : forward ? "later" : "earlier") + " occurrence in the watch's visible columns";
                        if (continuing) answer.found = previousHit;
                    }
                    answer.receipt = new JSONObject().put("found", !message.startsWith("No "));
                } else {
                    message = "apply".equals(operation) ? "View applied on watch" : "Search cleared on watch";
                    answer.receipt = new JSONObject().put("found", false);
                }
                answer.rows = store.page(answer.start, selected); CsvStore.check(token);
                if (!prefs.edit().putString("last:" + file, selected.json().toString()).putString("search:" + file, answer.query).putString("activeFile", file).commit())
                    throw new IOException("Cannot save view settings on watch");
                answer.receipt.put("message", message).put("file", file).put("name", info.name).put("matches", store.matches).put("rows", info.rows);
                if (answer.found != null) answer.receipt.put("position", answer.found.position + 1).put("column", info.headers[answer.found.column]);
                return answer;
            } catch (Exception e) {
                if (store != null) { store.close(); store = null; }
                releaseRemoteWakeLock();
                result.completeExceptionally(e); throw e;
            }
        }, answer -> {
            spec = answer.view; draft = null; page = answer.rows; pageStart = answer.start; resultsScroll = 0;
            searchText = answer.query; hit = answer.found;
            boolean shown = false;
            if (answer.receipt.optBoolean("found") && hit != null) for (CsvStore.Row row : page) if (row.position == hit.position) { showDetail(row); shown = true; break; }
            if (!shown) showRows();
            releaseRemoteWakeLock();
            result.complete(answer.receipt); remoteResult = null;
        }, () -> { remoteResult = null; showLibrary(); });
    }
    private synchronized void releaseRemoteWakeLock() {
        if (remoteWakeLock != null && remoteWakeLock.isHeld()) remoteWakeLock.release();
    }
    @Override protected void onDestroy() {
        dead = true; cancel.set(true);
        RemoteBridge.detach(this);
        releaseRemoteWakeLock();
        if (remoteResult != null) remoteResult.completeExceptionally(new IOException("The watch viewer closed before confirming the command. Reopen it and reload its view."));
        worker.execute(() -> { if (store != null) { store.close(); store = null; } }); worker.shutdown();
        super.onDestroy();
    }
}
