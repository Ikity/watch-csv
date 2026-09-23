package dev.watchcsv.viewer;

import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** All database work belongs to the activity's single background executor. */
public final class CsvStore implements Closeable {
    public interface Progress { void update(String text); }
    public static final int PAGE_SIZE = 12;
    public static final class Info {
        public File file;
        public String name;
        public String[] headers;
        public long rows;
    }
    public static final class Row {
        public long position, id;
        public String[] cells;
    }
    public final Info info;
    private final SQLiteDatabase db;
    public long matches;

    public CsvStore(Info info) {
        this.info = info;
        db = SQLiteDatabase.openDatabase(info.file.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        db.execSQL("PRAGMA temp_store=FILE");
        db.execSQL("PRAGMA cache_size=-2048");
        db.execSQL("CREATE TABLE IF NOT EXISTS view_rows (pos INTEGER PRIMARY KEY, row_id INTEGER NOT NULL UNIQUE)");
    }

    public static List<Info> library(File dir) throws Exception {
        List<Info> items = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".db"));
        if (files != null) for (File file : files) items.add(readInfo(file));
        items.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return items;
    }

    public static Info readInfo(File file) throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor c = db.rawQuery("SELECT name,headers,row_count FROM metadata", null)) {
            if (!c.moveToFirst()) throw new IOException("Incomplete import: " + file.getName());
            Info i = new Info(); i.file = file; i.name = c.getString(0); i.rows = c.getLong(2);
            JSONArray a = new JSONArray(c.getString(1));
            i.headers = new String[a.length()];
            for (int j = 0; j < a.length(); j++) i.headers[j] = a.getString(j);
            return i;
        }
    }

    public static Info importFile(File dir, InputStream input, String name, String encoding,
                                  char delimiter, boolean firstHeader, AtomicBoolean cancelled, Progress progress) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create database directory");
        String id = UUID.randomUUID().toString();
        File partial = new File(dir, id + ".partial"), target = new File(dir, id + ".db");
        boolean success = false;
        try (InputStream source = input; CsvReader reader = new CsvReader(source, encoding, delimiter)) {
            List<String> first = reader.next();
            if (first == null) throw new IOException("This CSV is empty");
            if (first.size() > 256) throw new IOException("At most 256 columns are supported");
            String[] headers = new String[first.size()];
            Set<String> used = new HashSet<>();
            for (int i = 0; i < headers.length; i++) {
                String base = firstHeader ? first.get(i).trim() : "Column " + (i + 1);
                if (base.isEmpty()) base = "Column " + (i + 1);
                String label = base; int suffix = 2;
                while (!used.add(label)) label = base + " (" + suffix++ + ")";
                headers[i] = label;
            }
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(partial, null)) {
                // journal_mode returns a result row, even when setting a value.
                // Android rejects result-returning statements passed to execSQL.
                // Advance the cursor: rawQuery is lazy. DELETE mode also ensures
                // the completed import can be renamed without a separate WAL file.
                try (Cursor mode = db.rawQuery("PRAGMA journal_mode=DELETE", null)) {
                    if (!mode.moveToFirst() || !"delete".equalsIgnoreCase(mode.getString(0)))
                        throw new IOException("Cannot configure the import database journal");
                }
                db.execSQL("PRAGMA cache_size=-2048");
                StringBuilder create = new StringBuilder("CREATE TABLE data (_id INTEGER PRIMARY KEY");
                StringBuilder insert = new StringBuilder("INSERT INTO data VALUES (?");
                for (int i = 0; i < headers.length; i++) { create.append(",c").append(i).append(" TEXT NOT NULL"); insert.append(",?"); }
                db.execSQL(create.append(')').toString());
                db.execSQL("CREATE TABLE metadata (name TEXT NOT NULL, headers TEXT NOT NULL, row_count INTEGER NOT NULL)");
                db.beginTransaction();
                try (SQLiteStatement stmt = db.compileStatement(insert.append(')').toString())) {
                    long count = 0;
                    List<String> row = firstHeader ? reader.next() : first;
                    while (row != null) {
                        check(cancelled);
                        // Ignore physically blank lines, but retain multi-column rows of empty cells.
                        if (!(row.size() == 1 && row.get(0).isEmpty() && headers.length > 1)) {
                            if (row.size() > headers.length) throw new IOException("Data row " + (count + 1) + " has " + row.size() + " cells, but the header has " + headers.length + ". Check the delimiter.");
                            int characters = 0;
                            for (String value : row) characters += value.length();
                            if (characters > 500_000) throw new IOException("Data row " + (count + 1) + " exceeds the 500,000-character row limit");
                            stmt.clearBindings(); stmt.bindLong(1, ++count);
                            for (int i = 0; i < headers.length; i++) stmt.bindString(i + 2, i < row.size() ? row.get(i) : "");
                            stmt.executeInsert();
                            if (count % 500 == 0) progress.update("Importing…\n" + count + " rows");
                        }
                        row = reader.next();
                    }
                    JSONArray json = new JSONArray(); for (String h : headers) json.put(h);
                    db.execSQL("INSERT INTO metadata VALUES (?,?,?)", new Object[]{name, json.toString(), count});
                    check(cancelled); db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
            check(cancelled);
            if (!partial.renameTo(target)) throw new IOException("Cannot finish saving the import");
            success = true; return readInfo(target);
        } finally { if (!success) SQLiteDatabase.deleteDatabase(partial); }
    }

    public void apply(ViewSpec spec, AtomicBoolean cancelled, Progress progress) throws Exception {
        Pattern[] patterns = spec.patterns();
        StringBuilder query = new StringBuilder("SELECT _id");
        List<Integer> filtered = new ArrayList<>();
        for (int i = 0; i < patterns.length; i++) if (patterns[i] != null) { filtered.add(i); query.append(",c").append(i); }
        query.append(" FROM data ORDER BY ");
        if (spec.sort >= 0) query.append('c').append(spec.sort).append(" COLLATE NOCASE ").append(spec.descending ? "DESC," : "ASC,");
        query.append("_id ASC");
        long found = 0, scanned = 0;
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM view_rows");
            try (Cursor cursor = db.rawQuery(query.toString(), null);
                 SQLiteStatement put = db.compileStatement("INSERT INTO view_rows VALUES (?,?)")) {
                while (cursor.moveToNext()) {
                    check(cancelled); boolean accepted = true;
                    for (int j = 0; j < filtered.size(); j++) if (!patterns[filtered.get(j)].matcher(cursor.getString(j + 1)).find()) { accepted = false; break; }
                    if (accepted) { put.bindLong(1, found++); put.bindLong(2, cursor.getLong(0)); put.executeInsert(); }
                    if (++scanned % 1000 == 0) progress.update("Filtering…\n" + scanned + " / " + info.rows + "\n" + found + " matches");
                }
            }
            check(cancelled); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        matches = found;
    }

    public List<Row> page(long start, ViewSpec spec) {
        StringBuilder sql = new StringBuilder("SELECT v.pos,d._id");
        for (int i = 0; i < spec.visible.length; i++) if (spec.visible[i]) sql.append(",d.c").append(i);
        sql.append(" FROM view_rows v JOIN data d ON d._id=v.row_id WHERE v.pos>=? ORDER BY v.pos LIMIT ").append(PAGE_SIZE);
        List<Row> rows = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql.toString(), new String[]{Long.toString(start)})) {
            while (c.moveToNext()) {
                Row row = new Row(); row.position = c.getLong(0); row.id = c.getLong(1);
                row.cells = new String[spec.visible.length]; int col = 2;
                for (int i = 0; i < row.cells.length; i++) if (spec.visible[i]) row.cells[i] = c.getString(col++);
                rows.add(row);
            }
        }
        return rows;
    }

    /** Literal, case-insensitive occurrence navigation, including repeated matches in a cell. */
    public TextSearch.Hit search(String text, TextSearch.Hit after, long initialPosition, boolean forward, ViewSpec spec, AtomicBoolean cancelled) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT v.pos");
        List<Integer> columns = new ArrayList<>();
        for (int i : spec.order) if (spec.visible[i]) { sql.append(",d.c").append(i); columns.add(i); }
        sql.append(" FROM view_rows v JOIN data d ON d._id=v.row_id WHERE v.pos ")
            .append(forward ? ">=? ORDER BY v.pos ASC" : "<=? ORDER BY v.pos DESC");
        Pattern needle = TextSearch.literal(text);
        long start = after == null ? initialPosition : after.position;
        int afterRank = after == null ? -1 : columns.indexOf(after.column);
        try (Cursor c = db.rawQuery(sql.toString(), new String[]{Long.toString(start)})) {
            while (c.moveToNext()) {
                check(cancelled);
                long position = c.getLong(0);
                for (int step = 0; step < columns.size(); step++) {
                    int i = forward ? step : columns.size() - 1 - step;
                    int column = columns.get(i);
                    TextSearch.Hit hit = TextSearch.inCell(needle, c.getString(i + 1), position, column, after, forward, cancelled, i, afterRank);
                    if (hit != null) return hit;
                }
            }
        }
        return null;
    }

    public static void check(AtomicBoolean cancelled) throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
    public void close() { db.close(); }
}
