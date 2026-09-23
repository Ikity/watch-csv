package dev.watchcsv.viewer;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class CoreTest {
    private static int assertions;
    private static void eq(Object expected, Object actual) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static CsvReader csv(String s) throws Exception {
        return new CsvReader(new ByteArrayInputStream(s.getBytes("UTF-8")), "UTF-8", (char)0);
    }
    private static void row(CsvReader r, String... fields) throws Exception { eq(Arrays.asList(fields), r.next()); }
    private static void malformed(String text) throws Exception {
        boolean failed = false;
        try (CsvReader r = csv(text)) { while (r.next() != null) {} }
        catch (IOException expected) { failed = true; }
        eq(true, failed);
    }
    public static void main(String[] args) throws Exception {
        try (CsvReader r = csv("name,notes,empty\r\nAlice,\"a,b and \"\"quote\"\"\",\r\nBob,\"line1\r\nline2\",last")) {
            row(r, "name", "notes", "empty"); row(r, "Alice", "a,b and \"quote\"", ""); row(r, "Bob", "line1\r\nline2", "last"); eq(null, r.next());
        }
        try (CsvReader r = csv("\uFEFFимя;город;цена\nИван;Москва;1,23\n")) { row(r, "имя", "город", "цена"); row(r, "Иван", "Москва", "1,23"); eq(null, r.next()); }
        try (CsvReader r = csv("\"a,b\";c\r\nx;y\r\n")) { row(r, "a,b", "c"); row(r, "x", "y"); }
        try (CsvReader r = csv("a\tb\tc\n1\t\t3\n")) { row(r, "a", "b", "c"); row(r, "1", "", "3"); }
        try (CsvReader r = csv("a|b\n1|2")) { row(r, "a", "b"); row(r, "1", "2"); }
        try (CsvReader r = csv("a,b\n,,\n")) { row(r, "a", "b"); row(r, "", "", ""); eq(null, r.next()); }
        try (CsvReader r = csv("")) { eq(null, r.next()); }
        try (CsvReader r = csv("x\r\n\r\ny\r")) { row(r, "x"); row(r, ""); row(r, "y"); eq(null, r.next()); }
        try (CsvReader r = csv("a,b\n\"\",\"last\"")) { row(r, "a", "b"); row(r, "", "last"); eq(null, r.next()); }
        try (CsvReader r = new CsvReader(new ByteArrayInputStream("one;two\n1;2".getBytes("UTF-8")), "UTF-8", ',')) { row(r, "one;two"); row(r, "1;2"); }
        for (String encoding : new String[]{"UTF-16LE", "UTF-16BE"}) {
            byte[] bytes = ("\uFEFFимя,город\nИван,Москва").getBytes(encoding);
            try (CsvReader r = new CsvReader(new ByteArrayInputStream(bytes), "UTF-8", (char)0)) { row(r, "имя", "город"); row(r, "Иван", "Москва"); }
        }
        byte[] cyrillic = "имя;город\nИван;Москва".getBytes("windows-1251");
        try (CsvReader r = new CsvReader(new ByteArrayInputStream(cyrillic), "windows-1251", (char)0)) { row(r, "имя", "город"); row(r, "Иван", "Москва"); }
        boolean invalidEncoding = false;
        try (CsvReader r = new CsvReader(new ByteArrayInputStream(cyrillic), "UTF-8", (char)0)) { r.next(); }
        catch (CharacterCodingException expected) { invalidEncoding = true; }
        eq(true, invalidEncoding);
        malformed("a,b\n\"unclosed"); malformed("a,b\n\"closed\"junk,x");

        // Generated stream is larger than 11 MB and is never materialized in memory.
        final byte[] line = "12345,\"quoted, value\",some searchable long text,Москва\n".getBytes("UTF-8");
        final int count = 240_000;
        InputStream generated = new InputStream() {
            long remaining = (long)line.length * count; int offset;
            public int read() { if (remaining == 0) return -1; remaining--; int b = line[offset++] & 255; if (offset == line.length) offset = 0; return b; }
            public int read(byte[] out, int off, int len) {
                if (remaining == 0) return -1;
                int n = (int)Math.min(remaining, len);
                for (int i = 0; i < n; i++) out[off + i] = (byte)read(); return n;
            }
        };
        try (CsvReader r = new CsvReader(generated, "UTF-8", (char)0)) {
            int n = 0; List<String> fields;
            while ((fields = r.next()) != null) { if (n == 0 || n == count - 1) eq(Arrays.asList("12345", "quoted, value", "some searchable long text", "Москва"), fields); n++; }
            eq(count, n);
        }

        AtomicBoolean cancelled = new AtomicBoolean(); Pattern needle = TextSearch.literal("cat");
        TextSearch.Hit a = TextSearch.inCell(needle, "Cat cat CAT", 2, 3, null, true, cancelled); eq(0, a.start); eq(3, a.end);
        TextSearch.Hit b = TextSearch.inCell(needle, "Cat cat CAT", 2, 3, a, true, cancelled); eq(4, b.start);
        TextSearch.Hit c = TextSearch.inCell(needle, "Cat cat CAT", 2, 3, b, true, cancelled); eq(8, c.start);
        eq(null, TextSearch.inCell(needle, "Cat cat CAT", 2, 3, c, true, cancelled));
        eq(4, TextSearch.inCell(needle, "Cat cat CAT", 2, 3, c, false, cancelled).start);
        eq(0, TextSearch.inCell(needle, "Cat cat CAT", 2, 3, b, false, cancelled).start);
        eq(null, TextSearch.inCell(needle, "Cat cat CAT", 2, 3, a, false, cancelled));
        eq(8, TextSearch.inCell(needle, "Cat cat CAT", 2, 3, null, false, cancelled).start);
        eq(0, TextSearch.inCell(needle, "cat", 2, 4, c, true, cancelled).start);
        eq(null, TextSearch.inCell(needle, "cat", 2, 2, c, true, cancelled));
        eq(0, TextSearch.inCell(needle, "cat", 3, 0, c, true, cancelled).start);
        eq(0, TextSearch.inCell(needle, "cat", 1, 4, a, false, cancelled).start);
        eq(2, TextSearch.inCell(TextSearch.literal("МОСКВА"), "x Москва", 0, 0, null, true, cancelled).start);
        eq(1, TextSearch.inCell(TextSearch.literal("[a].*"), "x[a].*z", 0, 0, null, true, cancelled).start);
        // Turkish dotted I has a multi-character lowercase mapping; offsets must remain original.
        eq(2, TextSearch.inCell(TextSearch.literal("cat"), "İ cat", 0, 0, null, true, cancelled).start);
        cancelled.set(true); boolean stopped = false;
        try { TextSearch.inCell(needle, "cat", 0, 0, null, true, cancelled); } catch (InterruptedIOException expected) { stopped = true; }
        eq(true, stopped);
        System.out.println("PASS: " + assertions + " assertions; streamed " + ((long)line.length * count) + " bytes / " + count + " rows under a 32 MB Java heap.");
    }
}
