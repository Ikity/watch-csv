package dev.watchnotes;

import java.util.regex.*;

/** Render a bounded Markdown subset as escaped HTML. Only HTTPS and inline PNG/JPEG/GIF/WebP images load. */
public final class MarkdownPreview {
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]\\n]*)\\]\\(([^)\\s]+)\\)|<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE = Pattern.compile("\\bsrc\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]+)\\]\\((https://[^)\\s]+)\\)");
    private static final Pattern EMPHASIS = Pattern.compile("(\\*\\*|__)(.+?)\\1|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|`([^`\\n]+)`");
    private MarkdownPreview() { }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    static String allowedImage(String uri) {
        if (uri.startsWith("https://") && !uri.matches(".*[\"'<>\\s].*")) return uri;
        if (uri.matches("data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=]+")) return uri;
        return null;
    }
    private static String inline(String line, boolean images) {
        StringBuilder out = new StringBuilder(); Matcher m = IMAGE.matcher(line); int position = 0;
        while (m.find()) {
            out.append(formatText(line.substring(position, m.start())));
            String alt = m.group(1) == null ? "Image" : m.group(1);
            String uri = m.group(2);
            if (uri == null) {
                Matcher src = SOURCE.matcher(m.group()); if (src.find()) uri = src.group(2);
                Matcher description = Pattern.compile("\\balt\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE).matcher(m.group());
                if (description.find()) alt = description.group(2);
            }
            String safe = uri == null ? null : allowedImage(uri);
            if (images && safe != null) out.append("<img loading='lazy' alt='").append(escape(alt)).append("' src='").append(escape(safe)).append("'>");
            else out.append("<span class='image'>[image: ").append(escape(alt)).append(safe == null ? " (resource unavailable)" : " (hidden)").append("]</span>");
            position = m.end();
        }
        out.append(formatText(line.substring(position))); return out.toString();
    }
    private static String formatText(String text) {
        // Apply Markdown only after escaping HTML; links open in an external browser via WebViewClient.
        String escaped = escape(text);
        Matcher links = LINK.matcher(escaped); StringBuffer linked = new StringBuffer();
        while (links.find()) links.appendReplacement(linked, Matcher.quoteReplacement("<a href='" + links.group(2).replace("&#39;", "") + "'>" + links.group(1) + "</a>"));
        links.appendTail(linked);
        Matcher em = EMPHASIS.matcher(linked.toString()); StringBuffer result = new StringBuffer();
        while (em.find()) {
            String replacement = em.group(2) != null ? "<strong>" + em.group(2) + "</strong>"
                : em.group(3) != null ? "<em>" + em.group(3) + "</em>" : "<code>" + em.group(4) + "</code>";
            em.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        em.appendTail(result); return result.toString();
    }
    public static String html(Note note, boolean images) {
        StringBuilder out = new StringBuilder("<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'><style>")
            .append("body{font:16px sans-serif;line-height:1.5;padding:12px;overflow-wrap:anywhere;color:#eee;background:#202124}")
            .append("img{max-width:100%;height:auto}a{color:#90caf9}pre{white-space:pre-wrap}blockquote{border-left:3px solid #999;padding-left:10px}")
            .append("</style></head><body><h1>").append(escape(note.title)).append("</h1>");
        boolean code = false, list = false, table = false;
        for (String line : note.body.split("\n", -1)) {
            if (line.startsWith("```")) {
                if (list) { out.append("</ul>"); list=false; }
                if (table) { out.append("</table>"); table=false; }
                out.append(code ? "</code></pre>" : "<pre><code>"); code = !code; continue;
            }
            if (code) { out.append(escape(line)).append('\n'); continue; }
            if (line.startsWith("|") && line.endsWith("|")) {
                if (!table) { if (list) { out.append("</ul>"); list=false; } out.append("<table border='1' cellspacing='0' cellpadding='4'>"); table=true; }
                if (line.replaceAll("[|: -]", "").isEmpty()) continue;
                out.append("<tr>");
                for (String cell : line.substring(1,line.length()-1).split("\\|",-1))
                    out.append("<td>").append(inline(cell.trim(),images)).append("</td>");
                out.append("</tr>"); continue;
            }
            if (table) { out.append("</table>"); table=false; }
            if (line.matches("^[-*+] .*$")) {
                if (!list) { out.append("<ul>"); list=true; }
                String item = line.substring(2);
                if (item.startsWith("[ ] ")) item="☐ " + item.substring(4);
                else if (item.matches("(?i)^\\[x\\] .*")) item="☑ " + item.substring(4);
                out.append("<li>").append(inline(item,images)).append("</li>"); continue;
            }
            if (list) { out.append("</ul>"); list=false; }
            if (line.isEmpty()) { out.append("<br>"); continue; }
            Matcher heading=Pattern.compile("^(#{1,6}) +(.+)$").matcher(line);
            if (heading.matches()) {
                int level=heading.group(1).length(); out.append("<h").append(level).append('>').append(inline(heading.group(2),images)).append("</h").append(level).append('>');
            } else if (line.startsWith("> ")) out.append("<blockquote>").append(inline(line.substring(2),images)).append("</blockquote>");
            else out.append("<div>").append(inline(line,images)).append("</div>");
        }
        if (list) out.append("</ul>"); if (table) out.append("</table>"); if (code) out.append("</code></pre>");
        return out.append("</body></html>").toString();
    }
}
