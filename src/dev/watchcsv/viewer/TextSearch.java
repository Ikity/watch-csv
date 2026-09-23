package dev.watchcsv.viewer;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** Literal occurrence matching preserves original Unicode character offsets for highlighting. */
public final class TextSearch {
    private TextSearch() {}
    public static final class Hit {
        public final long position;
        public final int column, start, end;
        public Hit(long position, int column, int start, int end) {
            this.position = position; this.column = column; this.start = start; this.end = end;
        }
    }
    public static Pattern literal(String text) {
        if (text.isEmpty()) throw new IllegalArgumentException("Search text is empty");
        return Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
    public static Hit inCell(Pattern needle, String value, long position, int column, Hit after,
                             boolean forward, AtomicBoolean cancelled) throws InterruptedIOException {
        return inCell(needle, value, position, column, after, forward, cancelled, column, after == null ? -1 : after.column);
    }
    public static Hit inCell(Pattern needle, String value, long position, int column, Hit after,
                             boolean forward, AtomicBoolean cancelled, int displayRank, int afterRank) throws InterruptedIOException {
        Matcher m = needle.matcher(value); Hit last = null;
        while (m.find()) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
            boolean allowed = after == null || (forward ? position > after.position : position < after.position) ||
                (position == after.position && (forward
                    ? displayRank > afterRank || (column == after.column && m.start() > after.start)
                    : displayRank < afterRank || (column == after.column && m.start() < after.start)));
            if (allowed) {
                Hit hit = new Hit(position, column, m.start(), m.end());
                if (forward) return hit;
                last = hit;
            }
        }
        return last;
    }
}
