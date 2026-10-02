package dev.watchnotes;

/** Plain-text ACTION_SEND interchange used by Joplin Android. */
public final class ShareNotes {
    private ShareNotes() { }

    public static Note receive(String text, String subject) throws Exception {
        if (text == null) text = "";
        text = text.replace("\r\n", "\n");
        if (text.length() > 200000 + 1002) throw new IllegalArgumentException("Shared note is too long");
        if (text.startsWith("---\n")) return MarkdownNotes.decode(text, "Shared.md");
        Note note = new Note();
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
