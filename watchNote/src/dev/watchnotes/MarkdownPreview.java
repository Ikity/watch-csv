package dev.watchnotes;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.nodes.Node;
import org.jsoup.safety.Safelist;

/** CommonMark plus Joplin's tables/tasks/links/footnotes, sanitized for an offline WebView. */
public final class MarkdownPreview {
    private static final List<Extension> EXTENSIONS = Arrays.asList(TablesExtension.create(),
        TaskListItemsExtension.create(), StrikethroughExtension.create(),
        AutolinkExtension.create(), FootnotesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    // Joplin's default is hard line breaks within paragraphs.
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).softbreak("<br>").build();
    private static final Pattern HTTPS = Pattern.compile("https://[^\\s,\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARK = Pattern.compile("==([^=\\n]+)==");

    private MarkdownPreview() { }

    static String escape(String value) { return org.jsoup.nodes.Entities.escape(value); }

    static String allowedImage(String uri) {
        if (uri == null) return null;
        if (uri.matches("data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=]+")) return uri;
        try {
            URI parsed = new URI(uri);
            if ("https".equalsIgnoreCase(parsed.getScheme()) && parsed.getHost() != null
                    && !uri.matches(".*[\\s\"'<>].*")) return uri;
        } catch (Exception ignored) { }
        return null;
    }

    static String imageFallback(String srcset) {
        if (srcset == null) return null;
        Matcher candidates = HTTPS.matcher(srcset);
        while (candidates.find()) {
            String url = allowedImage(candidates.group());
            if (url != null) return url;
        }
        return null;
    }

    private static void highlights(Node node) {
        for (Node child : new java.util.ArrayList<>(node.childNodes())) {
            if (child instanceof TextNode && !(node instanceof Element && ("code".equals(((Element)node).tagName()) || "pre".equals(((Element)node).tagName())))) {
                Matcher matches = MARK.matcher(((TextNode)child).getWholeText());
                int end = 0;
                while (matches.find()) {
                    if (matches.start() > end) child.before(new TextNode(((TextNode)child).getWholeText().substring(end, matches.start())));
                    child.before(new Element("mark").text(matches.group(1)));
                    end = matches.end();
                }
                if (end != 0) {
                    String original = ((TextNode)child).getWholeText();
                    if (end < original.length()) child.before(new TextNode(original.substring(end)));
                    child.remove();
                }
            } else highlights(child);
        }
    }

    private static void tableOfContents(Document fragment) {
        java.util.List<Element> headings = fragment.select("h1,h2,h3,h4,h5,h6");
        for (int i = 0; i < headings.size(); i++) headings.get(i).attr("id", "section-" + (i + 1));
        for (Element paragraph : fragment.select("p")) {
            String token = paragraph.text().trim().toLowerCase(java.util.Locale.ROOT);
            if (!token.equals("[toc]") && !token.equals("[[toc]]") && !token.equals("[[_toc_]]") && !token.equals("${toc}")) continue;
            Element nav = new Element("nav"); nav.appendElement("strong").text("Contents");
            Element list = nav.appendElement("ul");
            for (Element heading : headings) {
                Element entry = list.appendElement("li");
                entry.appendElement("a").attr("href", "#" + heading.id()).text(heading.text());
            }
            paragraph.replaceWith(nav);
        }
    }

    public static String html(Note note, boolean images) {
        Document fragment = Jsoup.parseBodyFragment(RENDERER.render(PARSER.parse(note.body)));
        highlights(fragment.body());
        tableOfContents(fragment);
        // The share only contains :/ID references, not the corresponding private Joplin files.
        // Shared HTML sometimes includes public HTTPS alternatives in srcset.
        for (Element img : fragment.select("img")) {
            String source = allowedImage(img.attr("src"));
            if (source == null) source = imageFallback(img.attr("srcset"));
            String label = img.hasAttr("alt") && !img.attr("alt").isEmpty() ? img.attr("alt") : "Image";
            if (images && source != null) {
                img.clearAttributes(); img.attr("src", source); img.attr("alt", label);
            } else {
                img.replaceWith(new Element("span").text("[image: " + label + (source == null ? " (resource unavailable)" : " (hidden)") + "]"));
            }
        }
        for (Element task : fragment.select("input[type=checkbox]")) task.attr("disabled", "");
        Safelist allowed = Safelist.relaxed()
            .addTags("s", "del", "mark", "ins", "sub", "sup", "hr", "input", "section", "div", "span", "details", "summary", "figure", "figcaption", "nav")
            .addAttributes("input", "type", "checked", "disabled")
            .addProtocols("img", "src", "https", "data");
        for (int level = 1; level <= 6; level++) allowed.addAttributes("h" + level, "id");
        // Disable remote resource loading for everything except validated <img src>.
        allowed.removeTags("video", "audio");
        allowed.removeAttributes("a", "rel");
        allowed.addProtocols("a", "href", "https", "#");
        String safe = Jsoup.clean(fragment.body().html(), "", allowed, new Document.OutputSettings().prettyPrint(false));
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{font:16px sans-serif;line-height:1.5;padding:12px;overflow-wrap:anywhere;color:#eee;background:#202124}"
            + "img{max-width:100%;height:auto}a{color:#90caf9}pre,table{max-width:100%;overflow-x:auto}pre{white-space:pre-wrap}"
            + "blockquote{border-left:3px solid #999;padding-left:10px}td,th{border:1px solid #666;padding:4px}table{border-collapse:collapse}"
            + "code{white-space:pre-wrap}mark{background:#665a20;color:white}</style></head><body><h1>"
            + escape(note.title) + "</h1>" + safe + "</body></html>";
    }
}
