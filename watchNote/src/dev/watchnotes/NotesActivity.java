package dev.watchnotes;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.view.*;
import android.widget.*;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Shared offline-first UI, with round-screen padding on Wear OS and document tools on phone. */
public final class NotesActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout layout;
    private TextView status;
    private Note editing;
    private EditText title, body, category, tags;
    private CheckBox todo, done;
    private String search = "", filter = "", screen = "list", exportKind = "", exportNote = "";
    private boolean trash, busy, watch;
    private int page;
    private Runnable unregisterBack;
    private final List<Note> visible = new ArrayList<>();
    private final Set<String> categories = new TreeSet<>();
    private interface Work { String run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); watch = NotesSync.watch(this);
        if (Build.VERSION.SDK_INT >= 33) unregisterBack = ModernBack.register(this, this::back);
        if (state != null) {
            exportKind = state.getString("exportKind", ""); exportNote = state.getString("exportNote", "");
            search = state.getString("search", ""); filter = state.getString("filter", ""); trash = state.getBoolean("trash");
        }
        Intent incoming = getIntent();
        if ((Intent.ACTION_SEND.equals(incoming.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(incoming.getAction())) && state == null) {
            screen="share"; base("Import shared note"); acceptShare(incoming);
        } else {
        String draft = getSharedPreferences("draft", 0).getString("note", "");
        if (!draft.isEmpty()) {
            try { editor(Note.from(new JSONObject(draft))); }
            catch (Exception e) { library(); message("Could not restore draft: " + e.getMessage()); }
        } else library();
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); acceptShare(intent);
    }
    private void acceptShare(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        if (busy) { new Handler(Looper.getMainLooper()).postDelayed(() -> { if (!isDestroyed()) acceptShare(intent); }, 200); return; }
        // Obtain URIs before handing off the Intent. Android grants access for this receiving activity.
        ArrayList<Uri> streams = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> selected = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (selected != null) streams.addAll(selected);
        } else {
            Uri selected = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (selected != null) streams.add(selected);
        }
        String text = intent.getStringExtra(Intent.EXTRA_TEXT), subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (text == null && streams.isEmpty()) { message("No note text or image was shared"); return; }
        final Note[] imported = new Note[1];
        job(() -> {
            Note note = ShareNotes.receive(text, subject);
            if (!streams.isEmpty()) {
                for (Uri uri : streams) {
                    String type = getContentResolver().getType(uri);
                    if (type == null && intent.getType() != null && intent.getType().startsWith("image/")) type = intent.getType();
                    if (type != null && type.startsWith("image/")) note.body += "\n\n" + embedImage(uri, type);
                    else if (type != null && (type.startsWith("text/") || type.equals("application/octet-stream"))) {
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            Note file = MarkdownNotes.decode(new String(NotesSync.read(in, 220000), StandardCharsets.UTF_8), "Shared.md");
                            if (text == null) note = file; else note.body += "\n\n" + file.body;
                        }
                    } else throw new IOException("Unsupported shared attachment: " + type);
                    note.validate();
                }
            }
            imported[0] = note;
            return null;
        }, () -> editor(imported[0]));
    }
    private String embedImage(Uri uri, String type) throws IOException {
        if (!type.equals("image/png") && !type.equals("image/jpeg") && !type.equals("image/gif") && !type.equals("image/webp"))
            throw new IOException("Supported images: PNG, JPEG, GIF, WebP");
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            byte[] bytes = NotesSync.read(in, 120000);
            String result = "![Shared image](data:" + type + ";base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) + ")";
            if (result.length() > 190000) throw new IOException("Image exceeds note limit");
            return result;
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (screen.equals("list") && !busy) library();
    }
    @Override protected void onPause() { persistDraft(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        persistDraft(); state.putString("exportKind", exportKind); state.putString("exportNote", exportNote);
        state.putString("search", search); state.putString("filter", filter); state.putBoolean("trash", trash);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (unregisterBack != null) unregisterBack.run();
        worker.shutdown(); super.onDestroy();
    }
    private void persistDraft() {
        if (editing == null || !screen.equals("editor")) return;
        try { collect(); getSharedPreferences("draft", 0).edit().putString("note", editing.json().toString()).commit(); }
        catch (Exception e) { message("Draft could not be saved: " + e.getMessage()); }
    }
    private void clearDraft() { getSharedPreferences("draft", 0).edit().remove("note").commit(); }
    private void base(String heading) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(watch ? 28 : 16); layout.setPadding(padding, padding, padding, padding);
        scroll.addView(layout); setContentView(scroll);
        TextView label = text(heading); label.setTextSize(22);
        status = text(""); status.setTextSize(12);
    }
    private int dp(int n) { return (int) (getResources().getDisplayMetrics().density * n); }
    private TextView text(String value) { TextView t = new TextView(this); t.setText(value); t.setTextSize(16); layout.addView(t); return t; }
    private Button button(String value, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setMinHeight(dp(48));
        layout.addView(b); b.setOnClickListener(v -> { if (!busy) action.run(); }); return b;
    }
    private EditText field(String hint, String value, int lines) {
        EditText e = new EditText(this); e.setHint(hint); e.setContentDescription(hint); e.setText(value);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            | (lines > 1 ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        e.setMinLines(lines); layout.addView(e); return e;
    }
    private CheckBox check(String label, boolean checked) {
        CheckBox box = new CheckBox(this); box.setText(label); box.setChecked(checked); layout.addView(box); return box;
    }
    private void message(String message) {
        if (!isDestroyed()) { if (status != null) status.setText(message); Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    }
    private void job(Work action, Runnable success) {
        if (busy) return;
        busy = true; status.setText("Working…"); enable(layout, false);
        worker.execute(() -> {
            String result = null; Exception failure = null;
            try { result = action.run(); } catch (Exception e) { failure = e; }
            final String message = result; final Exception error = failure;
            runOnUiThread(() -> {
                busy = false; if (isDestroyed()) return; enable(layout, true);
                if (error == null) { if (success != null) success.run(); if (message != null) message(message); }
                else message("Failed: " + (error.getMessage() == null ? error.toString() : error.getMessage()));
            });
        });
    }
    private void enable(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup)v;
            for (int i=0; i<g.getChildCount(); i++) enable(g.getChildAt(i), enabled);
        }
    }
    private void library() {
        editing = null; screen = "list"; base(trash ? "Trash" : "Watch Notes");
        job(() -> {
            try (NotesStore store = new NotesStore(this)) {
                List<Note> all = store.current();
                visible.clear(); categories.clear();
                String query = search.toLowerCase(Locale.ROOT);
                for (Note n : all) {
                    if (!n.category.isEmpty() && !n.deleted) categories.add(n.category);
                    if (n.deleted == trash && (filter.isEmpty() || filter.equals(n.category))
                        && (n.title + "\n" + n.body + "\n" + n.tags).toLowerCase(Locale.ROOT).contains(query)) visible.add(n);
                }
            }
            return null;
        }, this::renderLibrary);
    }
    private void renderLibrary() {
        base(trash ? "Trash" : "Watch Notes");
        String error = getSharedPreferences("notes-status", 0).getString("error", "");
        if (!error.isEmpty()) status.setText("Sync needs retry: " + error);
        button("+ New note", () -> editor(new Note()));
        EditText query = field("Search notes or tags", search, 1);
        button("Search", () -> { search = query.getText().toString(); page = 0; library(); });
        button("Category: " + (filter.isEmpty() ? "All" : filter), () -> {
            List<String> names = new ArrayList<>(); names.add("All"); names.addAll(categories);
            new AlertDialog.Builder(this).setTitle("Filter category").setItems(names.toArray(new String[0]), (d,i) -> {
                filter = i == 0 ? "" : names.get(i); page = 0; library();
            }).show();
        });
        button("Sync now", () -> job(() -> NotesSync.sync(this, true), this::library));
        button("Files & backups", this::files);
        button(trash ? "Back to notes" : "Trash", () -> { trash = !trash; page = 0; library(); });
        text(visible.size() + " note(s) · swipe right: category · left: actions");
        int pages = Math.max(1, (visible.size() + 19)/20); page = Math.min(page, pages-1);
        for (int i=page*20; i<Math.min(visible.size(), (page+1)*20); i++) {
            Note n = visible.get(i);
            String label = (n.todo ? (n.done ? "☑ " : "☐ ") : "") + (n.title.isEmpty() ? "Untitled" : n.title)
                + "\n" + (n.category.isEmpty() ? "Uncategorized" : n.category);
            Button row = button(label, () -> editor(n)); row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setOnLongClickListener(v -> { if (!busy) actions(n); return true; });
            row.setOnTouchListener(new View.OnTouchListener() {
                float x, y; boolean swiping;
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    if (busy) return true;
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { x=e.getX(); y=e.getY(); swiping=false; }
                    float dx=e.getX()-x, dy=e.getY()-y;
                    if (e.getActionMasked() == MotionEvent.ACTION_MOVE && Math.abs(dx)>dp(24) && Math.abs(dx)>Math.abs(dy)*2) {
                        swiping=true; v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && swiping && Math.abs(dx)>dp(55)) {
                        v.setPressed(false); if (dx>0) assignCategory(n); else actions(n); return true;
                    }
                    return false;
                }
            });
        }
        if (page>0) button("Previous page", () -> { page--; renderLibrary(); });
        if (page+1<pages) button("Next page", () -> { page++; renderLibrary(); });
        button("Refresh", this::library);
    }
    private void editor(Note n) {
        try { editing = n.copy(); } catch (Exception e) { message(e.toString()); return; }
        screen="editor"; base(n.deleted ? "Deleted note" : "Edit note");
        title = field("Title", n.title, 1); title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        body = field("Note (Markdown)", n.body, watch ? 4 : 10); body.setGravity(Gravity.TOP);
        body.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200000)});
        category = field("Category / notebook", n.category, 1);
        category.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        tags = field("Tags, comma separated", n.tags, 1); tags.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        todo = check("To-do", n.todo); done = check("Completed", n.done);
        button(n.deleted ? "Restore and save" : "Save", () -> {
            collect(); editing.deleted=false; Note draft=editing;
            job(() -> { try (NotesStore store = new NotesStore(this)) { store.save(draft); } return "Saved on this device"; }, () -> {
                clearDraft(); library(); queueSync();
            });
        });
        button("Export this note (.md)", () -> {
            try { collect(); exportNote = editing.json().toString(); createFile("note", "text/markdown", MarkdownNotes.safe(editing.title) + ".md"); }
            catch (Exception e) { message(e.toString()); }
        });
        button("Preview Markdown", () -> { collect(); persistDraft(); preview(editing); });
        button("Share to Joplin / other apps", () -> { collect(); persistDraft(); share(editing); });
        button("Add image", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(pick, 12); } catch (ActivityNotFoundException e) { message("No image picker available"); }
        });
        button("Revision history", () -> { persistDraft(); history(n); });
        if (!n.deleted) button("Move to trash", () -> new AlertDialog.Builder(this).setMessage("Move this note to trash?")
            .setNegativeButton("Cancel", null).setPositiveButton("Move", (d,w) -> { collect(); editing.deleted=true; saveChange(editing); }).show());
        button("Back", this::back);
        persistDraft();
    }
    private void collect() {
        editing.title=title.getText().toString(); editing.body=body.getText().toString();
        editing.category=category.getText().toString().trim(); editing.tags=tags.getText().toString().trim();
        editing.todo=todo.isChecked(); editing.done=done.isChecked();
    }
    private void share(Note n) {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, n.title);
        intent.putExtra(Intent.EXTRA_TEXT, ShareNotes.send(n));
        try { startActivity(Intent.createChooser(intent, "Share note to Joplin")); }
        catch (ActivityNotFoundException e) { message("No app available to receive notes"); }
    }
    private void preview(Note n) {
        screen="preview"; base("Markdown preview");
        boolean images = getSharedPreferences("display", 0).getBoolean("images", true);
        text("Long-press the preview to turn image rendering " + (images ? "off" : "on") + ". Joplin :/ images need their resource files.");
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(false);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setBlockNetworkLoads(!images);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("about:blank#") || url.startsWith("#")) return false;
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (ActivityNotFoundException ignored) { }
                }
                return true;
            }
        });
        web.loadDataWithBaseURL(null, MarkdownPreview.html(n, images), "text/html", "UTF-8", null);
        web.setMinimumHeight(dp(watch ? 240 : 480));
        layout.addView(web);
        web.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setTitle("Image rendering")
                .setItems(new String[]{"Show images", "Hide images"}, (dialog, which) -> {
                    getSharedPreferences("display", 0).edit().putBoolean("images", which == 0).apply();
                    preview(n);
                }).show();
            return true;
        });
        button("Back to editor", () -> editor(n));
    }
    private void saveChange(Note n) {
        job(() -> { try (NotesStore store = new NotesStore(this)) { store.save(n); } return "Saved"; }, () -> { clearDraft(); library(); queueSync(); });
    }
    private void queueSync() {
        Context app = getApplicationContext();
        worker.execute(() -> {
            try { NotesSync.sync(app, false); app.getSharedPreferences("notes-status",0).edit().remove("error").apply(); }
            catch (Exception e) {
                app.getSharedPreferences("notes-status",0).edit().putString("error", e.toString()).apply();
                runOnUiThread(() -> { if (!isDestroyed()) message("Saved locally; sync pending. Use Sync now to retry."); });
            }
        });
    }
    private void assignCategory(Note n) {
        EditText input = new EditText(this); input.setHint("Category (empty = uncategorized)"); input.setText(n.category);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        new AlertDialog.Builder(this).setTitle("Set category").setView(input).setNeutralButton("Existing", (d,w) -> {
            String[] names=categories.toArray(new String[0]);
            new AlertDialog.Builder(this).setItems(names, (dd,i) -> { n.category=names[i]; saveChange(n); }).show();
        }).setNegativeButton("Cancel", null).setPositiveButton("Save", (d,w) -> { n.category=input.getText().toString().trim(); saveChange(n); }).show();
    }
    private void actions(Note n) {
        new AlertDialog.Builder(this).setTitle(n.title).setItems(new String[]{"Edit", "Preview Markdown", "Show images", "Hide images", "Share to Joplin", "Set category", "Toggle to-do completion", "Revision history", n.deleted ? "Restore" : "Move to trash"}, (d,i) -> {
            switch(i) {
                case 0: editor(n); break;
                case 1: preview(n); break;
                case 2: case 3: getSharedPreferences("display", 0).edit().putBoolean("images", i == 2).apply(); preview(n); break;
                case 4: share(n); break;
                case 5: assignCategory(n); break;
                case 6: n.todo=true; n.done=!n.done; saveChange(n); break;
                case 7: history(n); break;
                case 8: n.deleted=!n.deleted; saveChange(n); break;
            }
        }).show();
    }
    private void history(Note n) {
        final List<Note> versions = new ArrayList<>();
        job(() -> { try (NotesStore store = new NotesStore(this)) {
            for (Note r : store.revisions(false)) if (r.id.equals(n.id)) versions.add(r);
        } return null; }, () -> {
            screen="history"; base("Revision history");
            text("Every saved edit is retained, including concurrent offline edits. Open a revision and Save to restore it.");
            Collections.reverse(versions);
            for (Note r : versions) button(new java.util.Date(r.updated) + " · " + r.title + (r.deleted ? " (trash)" : "") + "\n" + r.revision.substring(0,8), () -> editor(r));
            button("Back to note", () -> editor(editing != null ? editing : n));
        });
    }
    private void files() {
        screen="files"; base("Files & backups");
        button("Export notes for Joplin (.zip)", () -> createFile("zip", "application/zip", "watch-notes-markdown.zip"));
        button("Export local database", () -> createFile("db", "application/vnd.sqlite3", "notes.db"));
        if (watch) button("Send database to phone", () -> job(() -> { NotesSync.sendDatabase(this); return "Database queued for phone. On phone: Sync now → Files & backups → Save watch database."; }, null));
        else button("Save watch database", () -> {
            if (!new File(getFilesDir(), "watch-notes.db").exists()) { message("On watch, select Send database to phone, then Sync now here."); return; }
            createFile("watchdb", "application/vnd.sqlite3", "watch-notes.db");
        });
        button("Import Markdown / ZIP / database", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(pick, 10); } catch (ActivityNotFoundException e) { message("No file picker. Import on the phone, then sync."); }
        });
        text("Joplin: export Markdown + Front Matter, zip the exported folder, and import here. To import into Joplin, unzip our export and choose Markdown + Front Matter (Directory). Attachments and JEX are not supported.");
        button("Back", this::library);
    }
    private void createFile(String kind, String mime, String name) {
        exportKind=kind;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name);
        try { startActivityForResult(intent, 11); }
        catch (ActivityNotFoundException e) { message("No document provider. Sync to the phone and export there."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (result!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if (request==12) {
            if (editing == null || !screen.equals("editor")) return;
            final String[] image = new String[1];
            job(() -> { image[0] = embedImage(uri, getContentResolver().getType(uri)); return null; }, () -> {
                if (body.length() + image[0].length() + 2 > 200000) { message("Image exceeds note limit"); return; }
                body.append("\n\n" + image[0]); persistDraft();
            });
            return;
        }
        if (request==10) job(() -> {
            String name="note.md";
            try (Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                if (c!=null && c.moveToFirst()) name=c.getString(0);
            }
            List<Note> notes;
            try (InputStream in=getContentResolver().openInputStream(uri)) {
                if (in==null) throw new IOException("Cannot open selected file");
                if (name.toLowerCase(Locale.ROOT).endsWith(".db")) {
                    File tmp=File.createTempFile("notes-import-", ".db", getCacheDir());
                    try {
                        try (FileOutputStream out=new FileOutputStream(tmp)) {
                            byte[] buffer=new byte[65536]; int count; long total=0;
                            while ((count=in.read(buffer))!=-1) {
                                total+=count; if (total>256L*1024*1024) throw new IOException("Database exceeds 256 MiB");
                                out.write(buffer,0,count);
                            }
                        }
                        notes=NotesStore.readBackup(tmp);
                    } finally { tmp.delete(); }
                }
                else if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) notes=MarkdownNotes.importZip(in);
                else if (name.toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|txt)$")) notes=Collections.singletonList(MarkdownNotes.decode(new String(NotesSync.read(in,1500000),StandardCharsets.UTF_8),name));
                else throw new IOException("Choose .md, .txt, .zip or a Watch Notes .db file (JEX is not supported)");
            }
            try (NotesStore store=new NotesStore(this)) { store.importNotes(notes); }
            return "Imported " + notes.size() + " note/revision record(s). Markdown creates new notes; database backups merge history.";
        }, () -> { library(); queueSync(); });
        if (request==11) {
            final String kind=exportKind, note=exportNote;
            job(() -> {
                try (OutputStream out=getContentResolver().openOutputStream(uri,"wt")) {
                    if (out==null) throw new IOException("Cannot write selected file");
                    if (kind.equals("note")) out.write(MarkdownNotes.encode(Note.from(new JSONObject(note))).getBytes(StandardCharsets.UTF_8));
                    else if (kind.equals("zip")) { try (NotesStore store=new NotesStore(this)) { MarkdownNotes.exportZip(store.current(),out); } }
                    else if (kind.equals("db")) {
                        File tmp=File.createTempFile("notes-export-",".db",getCacheDir());
                        try { try (NotesStore store=new NotesStore(this)) { store.snapshot(tmp); } copy(tmp,out); }
                        finally { tmp.delete(); }
                    } else if (kind.equals("watchdb")) {
                        synchronized (NotesStore.LOCK) { copy(new File(getFilesDir(),"watch-notes.db"),out); }
                    } else throw new IOException("Unknown export format");
                }
                return "Export complete";
            },null);
        }
    }
    private static void copy(File file, OutputStream out) throws IOException {
        try (InputStream in=new FileInputStream(file)) { byte[] b=new byte[65536]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n); }
    }
    private void back() {
        if (busy) return;
        if (screen.equals("editor")) {
            new AlertDialog.Builder(this).setMessage("Save changes before leaving?").setPositiveButton("Save", (d,w) -> {
                collect(); saveChange(editing);
            }).setNegativeButton("Discard draft", (d,w) -> { clearDraft(); library(); }).setNeutralButton("Keep editing",null).show();
        } else if (screen.equals("preview") && editing != null) editor(editing);
        else if (screen.equals("history") && editing != null) editor(editing);
        else if (!screen.equals("list")) library(); else finish();
    }
    @Override public void onBackPressed() { back(); }
    private static final class ModernBack {
        static Runnable register(Activity a, Runnable action) {
            android.window.OnBackInvokedCallback callback=action::run;
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);
            return () -> a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        }
    }
}
