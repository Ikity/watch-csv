package dev.watchnotes;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** All writers (UI and Wear listener) share one transaction lock. History also acts as the durable outbox. */
public final class NotesStore implements AutoCloseable {
    static final Object LOCK = new Object();
    private final SQLiteDatabase db;
    public NotesStore(Context context) {
        this(context, "notes.db");
    }
    NotesStore(Context context, String databaseName) {
        synchronized (LOCK) {
            db = context.openOrCreateDatabase(databaseName, 0, null);
            db.execSQL("CREATE TABLE IF NOT EXISTS revisions (revision TEXT PRIMARY KEY, note_id TEXT NOT NULL, clock INTEGER NOT NULL, payload TEXT NOT NULL, sent INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS note_history ON revisions(note_id, clock)");
            db.execSQL("PRAGMA user_version=1");
        }
    }
    public List<Note> revisions(boolean pending) throws Exception {
        synchronized (LOCK) {
            List<Note> result = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT payload FROM revisions" + (pending ? " WHERE sent=0" : "") + " ORDER BY clock,revision", null)) {
                while (c.moveToNext()) result.add(Note.from(new JSONObject(c.getString(0))));
            }
            return result;
        }
    }
    public List<Note> current() throws Exception {
        synchronized (LOCK) {
            List<Note> result = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT r.payload FROM revisions r WHERE NOT EXISTS (SELECT 1 FROM revisions s WHERE s.note_id=r.note_id AND (s.clock>r.clock OR (s.clock=r.clock AND s.revision>r.revision)))", null)) {
                while (c.moveToNext()) result.add(Note.from(new JSONObject(c.getString(0))));
            }
            result.sort((a,b) -> Long.compare(b.updated, a.updated));
            return result;
        }
    }
    public Note save(Note draft) throws Exception {
        synchronized (LOCK) {
            Note n = draft.copy();
            long max = 0;
            try (Cursor c = db.rawQuery("SELECT MAX(clock) FROM revisions WHERE note_id=?", new String[]{n.id})) {
                if (c.moveToFirst()) max = c.getLong(0);
            }
            n.parent = draft.revision; n.revision = Note.uuid(); n.clock = Math.max(max, draft.clock) + 1;
            n.updated = System.currentTimeMillis(); insert(n, false); return n;
        }
    }
    public void insert(Note note, boolean received) throws Exception {
        synchronized (LOCK) {
            note.validate();
            ContentValues v = new ContentValues();
            v.put("revision", note.revision); v.put("note_id", note.id); v.put("clock", note.clock);
            v.put("payload", note.json().toString()); v.put("sent", received ? 1 : 0);
            db.insertWithOnConflict("revisions", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }
    public void importNotes(List<Note> notes) throws Exception {
        synchronized (LOCK) {
            db.beginTransaction();
            try { for (Note n : notes) insert(n, false); db.setTransactionSuccessful(); }
            finally { db.endTransaction(); }
        }
    }
    /** Read-only validation before merging a Watch Notes SQLite backup into the local history. */
    public static List<Note> readBackup(File file) throws Exception {
        try (SQLiteDatabase backup = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
            if (backup.getVersion() != 1) throw new IOException("Unsupported Watch Notes database version");
            try (Cursor check = backup.rawQuery("PRAGMA quick_check", null)) {
                if (!check.moveToFirst() || !"ok".equals(check.getString(0))) throw new IOException("Database integrity check failed");
            }
            List<Note> notes = new ArrayList<>(); long total = 0;
            try (Cursor c = backup.rawQuery("SELECT payload FROM revisions", null)) {
                while (c.moveToNext()) {
                    String payload = c.getString(0); total += payload.length();
                    if (total > 16 * 1024 * 1024 || notes.size() >= 10000) throw new IOException("Backup too large to import (16 MiB of note text / 10,000 revisions)");
                    notes.add(Note.from(new JSONObject(payload)));
                }
            }
            return notes;
        }
    }
    public void sent(String revision) {
        synchronized (LOCK) { db.execSQL("UPDATE revisions SET sent=1 WHERE revision=?", new Object[]{revision}); }
    }
    public void resendAll() { synchronized (LOCK) { db.execSQL("UPDATE revisions SET sent=0"); } }
    public void snapshot(File target) throws Exception {
        synchronized (LOCK) {
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace snapshot");
            db.execSQL("VACUUM INTO ?", new Object[]{target.getAbsolutePath()});
        }
    }
    @Override public void close() { synchronized (LOCK) { db.close(); } }
}
