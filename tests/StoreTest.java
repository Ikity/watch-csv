package dev.watchcsv.viewer;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Integration test using real Android SQLite in the instrumentation app context. */
public final class StoreTest {
    private static int assertions;
    private static void eq(Object expected, Object actual) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static InputStream input(String value) throws Exception { return new ByteArrayInputStream(value.getBytes("UTF-8")); }
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0], "test-" + UUID.randomUUID());
        AtomicBoolean token = new AtomicBoolean(); CsvStore.Progress progress = text -> {};
        StringBuilder csv = new StringBuilder("Name;Code;Notes;Name\r\nAlice;A-01;cat cat;first\r\nBob;B-02;dog;second\r\nalice;A-03;Cat;third\r\nZoe;Z-04;\"two;parts\nnext line\";fourth\r\n");
        for (int i = 0; i < 30; i++) csv.append("Row").append(i).append(";X-").append(i).append(";extra;tail\n");
        CsvStore.Info info = CsvStore.importFile(dir, input(csv.toString()), "test.csv", "UTF-8", (char)0, true, token, progress);
        eq(34L, info.rows); eq("Name (2)", info.headers[3]); eq("test.csv", info.name);
        eq(1, CsvStore.library(dir).size());
        try (CsvStore store = new CsvStore(info)) {
            ViewSpec all = new ViewSpec(4); store.apply(all, token, progress); eq(34L, store.matches);
            List<CsvStore.Row> first = store.page(0, all); eq(12, first.size()); eq(1L, first.get(0).id);
            eq("two;parts\nnext line", first.get(3).cells[2]);
            eq(13L, store.page(12, all).get(0).id); eq(10, store.page(24, all).size()); eq(0, store.page(34, all).size());
            ViewSpec reordered = all.copy(); reordered.moveColumn(2, 0); reordered.visible[3] = false;
            reordered.filters[1] = "A-01"; store.apply(reordered, token, progress);
            eq(1L, store.matches);
            CsvStore.Row orderedRow = store.page(0, reordered).get(0);
            eq("Alice", orderedRow.cells[0]); eq("A-01", orderedRow.cells[1]); eq("cat cat", orderedRow.cells[2]); eq(null, orderedRow.cells[3]);
            TextSearch.Hit n1 = store.search("a", null, 0, true, reordered, token); eq(2, n1.column); eq(1, n1.start);
            TextSearch.Hit n2 = store.search("a", n1, 0, true, reordered, token); eq(2, n2.column); eq(5, n2.start);
            TextSearch.Hit n3 = store.search("a", n2, 0, true, reordered, token); eq(0, n3.column);
            TextSearch.Hit n4 = store.search("a", n3, 0, true, reordered, token); eq(1, n4.column);
            eq(null, store.search("a", n4, 0, true, reordered, token));
            eq(0, store.search("a", n4, 0, false, reordered, token).column);
            eq(5, store.search("a", n3, 0, false, reordered, token).start);
            eq(null, store.search("a", n1, 0, false, reordered, token));
            ViewSpec filtered = new ViewSpec(4); filtered.visible = new boolean[]{false, false, true, false};
            filtered.filters[0] = "ALICE"; filtered.filters[1] = "^A-0[13]$"; filtered.regex[1] = true;
            filtered.sort = 1; filtered.descending = true;
            ViewSpec restored = ViewSpec.from(new JSONObject(filtered.json().toString()), 4);
            eq(1, restored.visibleCount()); eq(true, restored.regex[1]); eq("ALICE", restored.filters[0]); eq(1, restored.sort); eq(true, restored.descending);
            store.apply(restored, token, progress); eq(2L, store.matches);
            List<CsvStore.Row> rows = store.page(0, restored); eq(3L, rows.get(0).id); eq(1L, rows.get(1).id); eq(null, rows.get(0).cells[0]);
            TextSearch.Hit a = store.search("cat", null, 0, true, restored, token); eq(0L, a.position); eq(2, a.column); eq(0, a.start);
            TextSearch.Hit b = store.search("cat", a, 0, true, restored, token); eq(1L, b.position); eq(0, b.start);
            TextSearch.Hit c = store.search("cat", b, 0, true, restored, token); eq(1L, c.position); eq(4, c.start);
            eq(null, store.search("cat", c, 0, true, restored, token));
            eq(0, store.search("cat", c, 0, false, restored, token).start);
            eq(0L, store.search("cat", b, 0, false, restored, token).position);
            eq(null, store.search("Alice", null, 0, true, restored, token));
            token.set(true); boolean stopped = false;
            try { store.apply(all, token, progress); } catch (InterruptedIOException expected) { stopped = true; }
            eq(true, stopped); eq(2L, store.matches); eq(3L, store.page(0, restored).get(0).id);
            token.set(false); ViewSpec invalid = restored.copy(); invalid.filters[1] = "[";
            boolean badRegex = false;
            try { store.apply(invalid, token, progress); } catch (java.util.regex.PatternSyntaxException expected) { badRegex = true; }
            eq(true, badRegex); eq(2L, store.matches);
            ViewSpec none = all.copy(); none.filters[0] = "not present anywhere"; store.apply(none, token, progress);
            eq(0L, store.matches); eq(0, store.page(0, none).size()); eq(null, store.search("cat", null, 0, true, none, token));
        }
        boolean extraCells = false;
        try { CsvStore.importFile(dir, input("a,b\n1,2,3"), "bad.csv", "UTF-8", ',', true, token, progress); }
        catch (IOException expected) { extraCells = true; }
        eq(true, extraCells); eq(1, CsvStore.library(dir).size());
        token.set(true); boolean cancelled = false;
        try { CsvStore.importFile(dir, input("a,b\n1,2"), "cancel.csv", "UTF-8", ',', true, token, progress); }
        catch (InterruptedIOException expected) { cancelled = true; }
        eq(true, cancelled); eq(1, CsvStore.library(dir).size()); token.set(false);
        CsvStore.Info noHeader = CsvStore.importFile(dir, input("1,2\n3"), "numbers.csv", "UTF-8", ',', false, token, progress);
        eq(2L, noHeader.rows); eq("Column 1", noHeader.headers[0]); eq(2, CsvStore.library(dir).size());
        try (CsvStore store = new CsvStore(noHeader)) {
            ViewSpec v = new ViewSpec(2); store.apply(v, token, progress); eq("", store.page(0, v).get(1).cells[1]);
        }
        // Regression: the reported Windows-1251 + explicit semicolon import must
        // reach database creation, survive rename/reopen, and preserve Cyrillic.
        byte[] encoded = "Имя;Город\r\nИван;Москва\r\nАнна;\"Санкт-Петербург; центр\"\r\n".getBytes("windows-1251");
        CsvStore.Info cyrillic = CsvStore.importFile(dir, new ByteArrayInputStream(encoded), "1251.csv", "windows-1251", ';', true, token, progress);
        eq(2L, cyrillic.rows); eq("Имя", cyrillic.headers[0]); eq(3, CsvStore.library(dir).size());
        try (CsvStore store = new CsvStore(CsvStore.readInfo(cyrillic.file))) {
            ViewSpec v = new ViewSpec(2); store.apply(v, token, progress);
            List<CsvStore.Row> rows = store.page(0, v);
            eq("Иван", rows.get(0).cells[0]); eq("Москва", rows.get(0).cells[1]);
            eq("Санкт-Петербург; центр", rows.get(1).cells[1]);
            v.filters[1] = "москва"; store.apply(v, token, progress);
            eq(1L, store.matches); eq("Иван", store.page(0, v).get(0).cells[0]);
        }
        for (File file : Objects.requireNonNull(dir.listFiles())) android.database.sqlite.SQLiteDatabase.deleteDatabase(file);
        if (!dir.delete()) throw new IOException("Cannot remove test directory");
        System.out.println("PASS: " + assertions + " Android SQLite integration assertions.");
    }
}
