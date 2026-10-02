package dev.watchnotes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;
import org.json.JSONArray;

/** Joplin's Markdown + Front Matter interchange. No archive paths are extracted. */
public final class MarkdownNotes {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    public static String encode(Note n) {
        StringBuilder s = new StringBuilder("---\n");
        s.append("title: ").append(quote(n.title)).append("\ncreated: ").append(DATE.format(Instant.ofEpochMilli(n.created)))
            .append("\nupdated: ").append(DATE.format(Instant.ofEpochMilli(n.updated))).append("\n");
        s.append("notebook: ").append(quote(n.category)).append("\n");
        if (n.todo) s.append("completed?: ").append(n.done ? "yes" : "no").append("\n");
        if (!n.tags.trim().isEmpty()) {
            s.append("tags:\n");
            for (String tag : n.tags.split(",")) if (!tag.trim().isEmpty()) s.append("  - ").append(quote(tag.trim())).append("\n");
        }
        return s.append("---\n\n").append(n.body).toString();
    }
    static String quote(String s) { return org.json.JSONObject.quote(s); }
    private static String scalar(String value) throws Exception {
        value = value.trim();
        if (value.startsWith("\"")) return new JSONArray("[" + value + "]").getString(0);
        if (value.startsWith("'") && value.endsWith("'")) return value.substring(1, value.length()-1).replace("''", "'");
        return value;
    }
    private static long date(String value) throws Exception {
        String s = scalar(value).replace(' ', 'T');
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); }
        catch (Exception ignored) { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli(); }
    }
    public static Note decode(String text, String filename) throws Exception {
        if (text.startsWith("\ufeff")) text = text.substring(1);
        text = text.replace("\r\n", "\n");
        Note n = new Note();
        String path = filename.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        n.title = path.substring(slash + 1).replaceFirst("(?i)\\.(md|markdown|txt)$", "");
        n.category = slash < 0 ? "" : path.substring(0, slash);
        n.body = text;
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---\n", 3);
            if (end < 0) throw new IOException("Unclosed YAML front matter in " + filename);
            String header = end == 3 ? "" : text.substring(4, end);
            n.body = text.substring(end + 5);
            if (n.body.startsWith("\n")) n.body = n.body.substring(1);
            boolean tagList = false;
            List<String> tags = new ArrayList<>();
            for (String line : header.split("\n")) {
                String trimmed = line.trim();
                if (tagList && trimmed.startsWith("- ")) { tags.add(scalar(trimmed.substring(2))); continue; }
                tagList = false;
                int colon = line.indexOf(':'); if (colon < 0) continue;
                String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
                switch (key) {
                    case "title": n.title = scalar(value); break;
                    case "notebook": n.category = scalar(value); break;
                    case "created": n.created = date(value); break;
                    case "updated": n.updated = date(value); break;
                    case "completed?":
                    case "completed":
                        n.todo = true;
                        String completed = scalar(value);
                        n.done = "true".equalsIgnoreCase(completed) || "yes".equalsIgnoreCase(completed);
                        break;
                    case "tags":
                        tagList = value.isEmpty();
                        if (value.startsWith("[")) {
                            // Joplin emits block lists; also accept simple YAML flow lists.
                            String contents = value.substring(1, value.length()-1).trim();
                            if (!contents.isEmpty()) for (String t : contents.split(",")) tags.add(scalar(t));
                        } else if (!value.isEmpty()) tags.add(scalar(value));
                        break;
                    default: break;
                }
            }
            n.tags = String.join(", ", tags);
        }
        n.validate(); return n;
    }
    public static void exportZip(List<Note> notes, OutputStream output) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Note n : notes) {
                if (n.deleted) continue;
                String folder = n.category.isEmpty() ? "Notes" : safe(n.category);
                zip.putNextEntry(new ZipEntry(folder + "/" + safe(n.title) + "-" + n.id + ".md"));
                zip.write(encode(n).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
    }
    public static String safe(String s) {
        String name = s.replaceAll("[^\\p{L}\\p{N} ._-]", "_").replace("..", "_").trim();
        if (name.isEmpty() || name.equals(".")) name = "Untitled";
        return name.substring(0, Math.min(name.length(), 80));
    }
    public static List<Note> importZip(InputStream input) throws Exception {
        List<Note> notes = new ArrayList<>(); long total = 0; int entries = 0;
        Map<String,String> images = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 3000) throw new IOException("Archive exceeds 3,000 entries");
                String name = entry.getName();
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                if (name.startsWith("/") || name.replace('\\','/').matches("(^|.*/)\\.\\.(/.*|$)")) throw new IOException("Unsafe archive path");
                // Consume every entry with bounds, including attachments, to limit zip bombs.
                byte[] bytes = read(zip, 1500000);
                total += bytes.length; if (total > 16 * 1024 * 1024) throw new IOException("Import exceeds 16 MiB; split the archive");
                if (name.toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|txt)$")) {
                    notes.add(decode(new String(bytes, StandardCharsets.UTF_8), name));
                } else if (bytes.length <= 120000 && name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|gif|webp)$")) {
                    String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
                    String id=name.substring(name.lastIndexOf('/')+1,name.lastIndexOf('.'));
                    if (id.matches("[a-fA-F0-9]{32}"))
                        images.put(id.toLowerCase(Locale.ROOT), "data:image/" + (extension.equals("jpg") ? "jpeg" : extension)
                            + ";base64," + Base64.getEncoder().encodeToString(bytes));
                }
                zip.closeEntry();
            }
        }
        if (notes.isEmpty()) throw new IOException("Archive contains no Markdown notes");
        for (Note note : notes) {
            for (Map.Entry<String,String> image : images.entrySet()) {
                // Resource IDs in Joplin mobile shares and exported Markdown use :/ID.
                String candidate = note.body.replace(":/" + image.getKey(), image.getValue());
                if (candidate.length() <= 200000) note.body = candidate;
            }
        }
        return notes;
    }
    static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > max) throw new IOException("File exceeds import limit");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
