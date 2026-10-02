package dev.watchnotes;

import java.util.regex.Pattern;

/** Plain-text ACTION_SEND interchange used by Joplin Android. */
public final class ShareNotes {
    private ShareNotes() { }

    public static Note receive(String text, String subject) throws Exception {
        if (text == null) text = "";
        text = text.replace("\r\n", "\n");
        if (text.length() > 200000 + 1100) throw new IllegalArgumentException("Shared note is too long");
        // Joplin's mobile share puts its 32-hex note identifier on a line before
        // the human-readable title. The ID is not a title or part of the body.
        int first = text.indexOf('\n');
        if (first >= 0 && Pattern.matches("(?i)[0-9a-f]{32}", text.substring(0, first)))
            text = text.substring(first + 1).replaceFirst("^\n", "");
        if (text.startsWith("---\n")) return MarkdownNotes.decode(text, "Shared.md");
        Note note = new Note();
        if (subject != null && Pattern.matches("(?i)[0-9a-f]{32}", subject.trim())) subject = null;
        if (subject != null && !subject.trim().isEmpty()) {
            note.title = subject.trim();
            note.body = text;
            // Some senders, including Joplin, also repeat the title as line one.
            if (text.startsWith(note.title + "\n")) note.body = text.substring(note.title.length() + 1).replaceFirst("^\n", "");
        } else {
            int end = text.indexOf('\n');
            if (end < 0) { note.title = text.trim(); note.body = ""; }
            else { note.title = text.substring(0, end).trim(); note.body = text.substring(end + 1).replaceFirst("^\n", ""); }
        }
        note.validate(); return note;
    }

    public static String send(Note note) {
        return note.title + "\n\n" + note.body;
    }
}
