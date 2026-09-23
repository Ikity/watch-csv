package dev.watchcsv.viewer;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Streaming CSV reader: quoted delimiters/newlines, escaped quotes, BOMs and CRLF. */
public final class CsvReader implements Closeable {
    private final PushbackReader reader;
    private final char delimiter;
    private long record;

    public CsvReader(InputStream input, String encoding, char requestedDelimiter) throws IOException {
        PushbackInputStream bytes = new PushbackInputStream(input, 3);
        byte[] bom = new byte[3];
        int n = 0, b;
        while (n < 3 && (b = bytes.read()) != -1) bom[n++] = (byte)b;
        int skip = 0;
        if (n >= 3 && (bom[0] & 255) == 239 && (bom[1] & 255) == 187 && (bom[2] & 255) == 191) { encoding = "UTF-8"; skip = 3; }
        else if (n >= 2 && (bom[0] & 255) == 255 && (bom[1] & 255) == 254) { encoding = "UTF-16LE"; skip = 2; }
        else if (n >= 2 && (bom[0] & 255) == 254 && (bom[1] & 255) == 255) { encoding = "UTF-16BE"; skip = 2; }
        if (n > skip) bytes.unread(bom, skip, n - skip);
        CharsetDecoder decoder = Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        BufferedReader buffered = new BufferedReader(new InputStreamReader(bytes, decoder), 32768);
        delimiter = requestedDelimiter == 0 ? detect(buffered) : requestedDelimiter;
        reader = new PushbackReader(buffered, 1);
    }

    private static char detect(BufferedReader r) throws IOException {
        r.mark(65537);
        char[] candidates = {',', ';', '\t', '|'};
        int[] counts = new int[4];
        boolean quoted = false;
        for (int i = 0; i < 65536; i++) {
            int c = r.read();
            if (c == -1) break;
            if (c == '"') quoted = !quoted;
            if (!quoted && (c == '\r' || c == '\n')) break;
            if (!quoted) for (int j = 0; j < 4; j++) if (c == candidates[j]) counts[j]++;
        }
        r.reset();
        int best = 0;
        for (int i = 1; i < 4; i++) if (counts[i] > counts[best]) best = i;
        return candidates[best];
    }

    public List<String> next() throws IOException {
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false, started = false;
        while (true) {
            int c = reader.read();
            if (c == -1) {
                if (quoted) throw error("Unclosed quoted field");
                if (!started && row.isEmpty() && field.length() == 0) return null;
                row.add(field.toString()); record++; return row;
            }
            started = true;
            if (quoted) {
                if (c == '"') {
                    int next = reader.read();
                    if (next == '"') field.append('"');
                    else { quoted = false; closed = true; if (next != -1) reader.unread(next); }
                } else field.append((char)c);
            } else if (c == delimiter || c == '\n' || c == '\r') {
                row.add(field.toString()); field.setLength(0); closed = false;
                if (row.size() > 256) throw error("More than 256 columns");
                if (c != delimiter) {
                    if (c == '\r') { int next = reader.read(); if (next != '\n' && next != -1) reader.unread(next); }
                    record++; return row;
                }
            } else if (closed) {
                if (c != ' ' && c != '\t') throw error("Unexpected character after closing quote");
            } else if (c == '"' && field.length() == 0) quoted = true;
            else field.append((char)c);
            if (field.length() > 1_000_000) throw error("A cell exceeds 1 million characters");
        }
    }

    private IOException error(String text) { return new IOException(text + " near CSV record " + (record + 1)); }
    public void close() throws IOException { reader.close(); }
}
