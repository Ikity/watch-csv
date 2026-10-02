package dev.watchnotes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

public final class NotesTest {
    private static int count;
    private interface Action { void run() throws Exception; }
    private static void check(boolean ok, String message) {
        count++; if (!ok) throw new AssertionError(message);
    }
    private static void fails(Action a, String message) throws Exception {
        boolean rejected=false; try { a.run(); } catch (Exception e) { rejected=true; }
        check(rejected,message);
    }
    private static byte[] archive(String name, byte[] body) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try (ZipOutputStream zip=new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry(name)); zip.write(body); zip.closeEntry(); }
        return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        Note n=new Note(); n.title="Test: \"quotes\"\nNew line"; n.body="# Heading\n\n- [ ] task\n日本語 😀\n---\n";
        n.category="Personal"; n.tags="one, two words"; n.todo=true; n.done=true;
        n.created=Instant.parse("2021-05-01T16:40:00Z").toEpochMilli(); n.updated=n.created+1000;
        Note copy=n.copy();
        check(copy.json().toString().equals(n.json().toString()),"wire roundtrip");
        copy.body="Changed"; check(!n.body.equals(copy.body),"independent copy");
        Note later=n.copy(); later.clock++;
        check(Note.compare(later,n)>0,"logical clock wins");
        Note concurrent=n.copy(); concurrent.revision="ffffffff-ffff-ffff-ffff-ffffffffffff";
        check(Note.compare(concurrent,n)>0,"deterministic concurrent winner");
        check(Note.compare(n,concurrent)<0,"comparison symmetric");
        check(Note.compare(n,n)==0,"same revision equal");
        JSONObject invalid=n.json().put("v",2); fails(() -> Note.from(invalid),"unknown protocol rejected");
        fails(() -> Note.from(n.json().put("id","../bad")),"invalid ID rejected");
        fails(() -> Note.from(n.json().put("clock",Long.MAX_VALUE)),"clock overflow rejected");
        fails(() -> Note.from(n.json().put("created",-1)),"invalid date rejected");
        char[] oversized=new char[200001]; Arrays.fill(oversized,'a');
        fails(() -> Note.from(n.json().put("body",new String(oversized))),"body limit");
        String md=MarkdownNotes.encode(n);
        check(md.contains("completed?: yes\n"),"Joplin todo field");
        check(md.contains("2021-05-01 16:40:00Z"),"Joplin UTC timestamp");
        Note imported=MarkdownNotes.decode(md,"folder/file.md");
        check(imported.title.equals(n.title),"quoted title roundtrip");
        check(imported.body.equals(n.body),"body exact roundtrip");
        check(imported.category.equals(n.category),"category roundtrip");
        check(imported.tags.equals(n.tags),"tags roundtrip");
        check(imported.todo && imported.done,"todo roundtrip");
        check(imported.created==n.created && imported.updated==n.updated,"dates roundtrip");
        check(!imported.id.equals(n.id),"Markdown import creates a new note");
        String fixture="---\ntitle: Take Home Quiz\ncreated: 2021-05-01 16:40:00Z\nupdated: 2021-06-17 23:59:00Z\ntags:\n  - school\n  - math\n  - homework\ncompleted?: no\ndue: 2021-06-18 08:00:00Z\n---\n\n**Prove or give a counter-example**\n";
        Note joplin=MarkdownNotes.decode(fixture,"School/Quiz.md");
        check(joplin.todo && !joplin.done,"official Joplin uncompleted todo");
        check(joplin.category.equals("School"),"directory becomes category");
        check(joplin.tags.equals("school, math, homework"),"official Joplin tags");
        check(joplin.created==n.created,"official Joplin created timestamp");
        check(joplin.body.equals("**Prove or give a counter-example**\n"),"official Joplin body");
        Note plain=MarkdownNotes.decode("\ufeff# Plain\r\nText", "plain.md");
        check(plain.title.equals("plain") && plain.body.equals("# Plain\nText"),"plain Markdown BOM and CRLF");
        check(MarkdownNotes.decode("---\n---\n\ntext","empty.md").body.equals("text"),"empty front matter");
        check(MarkdownNotes.decode("---\ntitle: 'It''s fine'\n---\ntext","n.md").title.equals("It's fine"),"YAML single quotes");
        check(MarkdownNotes.decode("---\ncreated: 2021-05-01 18:40:00+02:00\n---\ntext","n.md").created==n.created,"date timezone offset");
        fails(() -> MarkdownNotes.decode("---\ntitle: missing end","n.md"),"unclosed header rejected");
        fails(() -> MarkdownNotes.decode("---\ncreated: invalid\n---\ntext","n.md"),"bad date reported");
        Note deleted=n.copy(); deleted.deleted=true;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        MarkdownNotes.exportZip(Arrays.asList(n,deleted),out);
        List<Note> zip=MarkdownNotes.importZip(new ByteArrayInputStream(out.toByteArray()));
        check(zip.size()==1,"trash excluded from Markdown archive");
        check(zip.get(0).body.equals(n.body),"archive body roundtrip");
        check(zip.get(0).title.equals(n.title),"archive title roundtrip");
        check(!MarkdownNotes.safe("../../a/b").contains("/"),"export path sanitized");
        fails(() -> MarkdownNotes.importZip(new ByteArrayInputStream(archive("../escape.md","note".getBytes(StandardCharsets.UTF_8)))),"traversal entry rejected");
        fails(() -> MarkdownNotes.importZip(new ByteArrayInputStream(archive("file.bin",new byte[0]))),"empty note archive rejected");
        fails(() -> MarkdownNotes.importZip(new ByteArrayInputStream(archive("large.md",new byte[1500001]))),"compressed oversize rejected");
        String shared="ESP32-CAM\n\n## GPIO Pins\n\n<img loading=\"lazy\" src=\":/2d89393057e74743bc1354b6b045e315\" alt=\"pinout\">\n\n## [Board](:/42ffc43de46b42d19dfb84b2b270c18b)";
        Note fromJoplin=ShareNotes.receive(shared,null);
        check(fromJoplin.title.equals("ESP32-CAM"),"Joplin share first line is title");
        check(fromJoplin.body.startsWith("## GPIO Pins"),"Joplin share keeps Markdown body");
        check(fromJoplin.body.contains(":/2d89393057e74743bc1354b6b045e315"),"unknown Joplin resource retained");
        check(ShareNotes.receive(ShareNotes.send(fromJoplin),fromJoplin.title).body.equals(fromJoplin.body),"outbound share roundtrip");
        check(ShareNotes.receive("Body alone","Subject").body.equals("Body alone"),"explicit subject respected");
        check(ShareNotes.receive("One line",null).title.equals("One line"),"single-line share");
        check(ShareNotes.receive(md,null).todo,"front matter share metadata");
        String id="d272ea411fc64030846806413ca2a151";
        Note envelope=ShareNotes.receive(id + "\nESP32-CAM\n\n" + fromJoplin.body, null);
        check(envelope.title.equals("ESP32-CAM"),"Joplin envelope ID skipped before title");
        check(envelope.body.equals(fromJoplin.body),"Joplin envelope preserves Markdown body");
        check(ShareNotes.receive(id + "\nESP32-CAM\n\n" + fromJoplin.body,"ESP32-CAM").body.equals(fromJoplin.body),"Joplin subject and ID do not duplicate title");
        check(ShareNotes.receive(id + "\nESP32-CAM\n\n" + fromJoplin.body,id).title.equals("ESP32-CAM"),"Joplin ID subject is not a title");
        check(ShareNotes.receive(id + "\n" + md,null).title.equals(n.title),"front matter after Joplin ID");
        check(ShareNotes.receive("RF1000A\n\n# Обзор\n",null).title.equals("RF1000A"),"Cyrillic example title remains readable");
        check(ShareNotes.receive("Что я беру на вахту\n\n- [ ] кофта с капюшоном",null).body.contains("- [ ]"),"task list from example survives share");
        String hidden=MarkdownPreview.html(fromJoplin,false);
        check(hidden.contains("[image: pinout (resource unavailable)]"),"missing Joplin resource visible as placeholder");
        check(!hidden.contains("<img "),"hidden preview does not render image tags");
        Note image=new Note(); image.body="![Photo](https://example.org/p.png)\n<img src=\"data:image/png;base64,iVBORw0KGgo=\" alt=\"In note\">";
        String shown=MarkdownPreview.html(image,true);
        check(shown.contains("src=\"https://example.org/p.png\""),"HTTPS Markdown image rendered: " + shown);
        check(shown.contains("src=\"data:image/png;base64,iVBORw0KGgo=\""),"embedded HTML image rendered: " + shown);
        check(MarkdownPreview.html(image,false).contains("(hidden)"),"image toggle conceals embedded data");
        image.body="<script>alert(1)</script> <img src=\"javascript:alert(1)\" alt=\"bad\">";
        check(!MarkdownPreview.html(image,true).contains("<script>"),"raw HTML escaped");
        check(!MarkdownPreview.html(image,true).contains("src='javascript:"),"unsafe image scheme rejected");
        image.body="| Pin | Safe? |\n| --- | --- |\n| D0 | yes |\n- [x] Done";
        check(MarkdownPreview.html(image,true).contains("<td>D0</td>"),"Joplin Markdown table rendered");
        check(MarkdownPreview.html(image,true).contains("type=\"checkbox\""),"Markdown checkbox rendered");
        String complex="## Commands\n\n1. First\n   1. Sub\n2. Second\n\n> Quote\n\n---\n\n```bash\nif [ -f x ]; then\n  echo **literal**\nfi\n```\n\nA [link](https://joplinapp.org) and ~~removed~~ and ==highlighted==.\n\nFootnote[^1].\n\n[^1]: Explanation";
        image.body=complex;
        String rich=MarkdownPreview.html(image,true);
        check(rich.contains("<ol>") && rich.contains("<li>Sub"),"nested ordered list");
        check(rich.contains("<blockquote>"),"blockquote");
        check(rich.contains("<hr"),"horizontal rule");
        check(rich.contains("echo **literal**"),"fenced code retains Markdown syntax literally");
        check(rich.contains("<a href=\"https://joplinapp.org\""),"safe hyperlinks");
        check(rich.contains("<del>removed</del>") || rich.contains("<s>removed</s>"),"strikethrough extension");
        check(rich.contains("<mark>highlighted</mark>"),"Joplin mark plugin");
        check(rich.contains("Explanation"),"footnote definition");
        image.body="[[toc]]\n\n# First\n\n## Second";
        check(MarkdownPreview.html(image,true).contains("href=\"#section-2\""),"Joplin table of contents links headings");
        image.body="<img src=\":/2d89393057e74743bc1354b6b045e315\" srcset=\"https://example.org/thumbnail.jpg 300w, https://example.org/full.jpg 1200w\" alt=\"Radio diagram\">";
        check(MarkdownPreview.html(image,true).contains("src=\"https://example.org/thumbnail.jpg\""),"shared HTML srcset HTTPS image fallback");
        check(!MarkdownPreview.html(image,false).contains("<img "),"srcset fallback respects image toggle");
        image.body="<iframe src=\"https://example.org\"></iframe><a href=\"javascript:alert(1)\">bad</a><img src=\"data:text/html;base64,AAAA\">";
        check(!MarkdownPreview.html(image,true).contains("<iframe"),"unsafe raw HTML removed");
        check(!MarkdownPreview.html(image,true).contains("href=\"javascript:"),"unsafe link protocol removed");
        check(!MarkdownPreview.html(image,true).contains("<img "),"unsafe data URI removed");
        ByteArrayOutputStream resourceZip=new ByteArrayOutputStream();
        try (ZipOutputStream resource=new ZipOutputStream(resourceZip)) {
            resource.putNextEntry(new ZipEntry("Notebook/Shared.md")); resource.write(shared.getBytes(StandardCharsets.UTF_8)); resource.closeEntry();
            resource.putNextEntry(new ZipEntry("_resources/2d89393057e74743bc1354b6b045e315.png")); resource.write(new byte[]{1,2,3}); resource.closeEntry();
        }
        Note resolved=MarkdownNotes.importZip(new ByteArrayInputStream(resourceZip.toByteArray())).get(0);
        check(resolved.body.contains("data:image/png;base64,AQID"),"ZIP resources resolve Joplin image IDs");
        check(resolved.body.contains(":/42ffc43de46b42d19dfb84b2b270c18b"),"unmatched links preserved");
        fails(() -> MarkdownNotes.importZip(new ByteArrayInputStream(archive("../bad.png",new byte[]{1}))),"unsafe image archive path rejected");
        if (args.length > 0) for (String name : new String[]{"jnote", "ex3", "ex4", "ex5", "exBashWordSel"}) {
            File example=new File(args[0],name);
            if (!example.exists()) continue;
            String content=new String(Files.readAllBytes(example.toPath()),StandardCharsets.UTF_8);
            Note sample=ShareNotes.receive("d272ea411fc64030846806413ca2a151\n" + content,null);
            check(sample.title.equals(content.substring(0,content.indexOf('\n')).trim()),"sample title " + name);
            String rendered=MarkdownPreview.html(sample,true);
            check(rendered.contains("</html>") && !rendered.contains("<script>"),"sample Markdown preview " + name);
            if (name.equals("ex4")) check(rendered.contains("type=\"checkbox\""),"real checklist sample");
            if (name.equals("exBashWordSel")) check(rendered.contains("<pre>") && rendered.contains("#!/bin/bash"),"real Bash fence sample");
        }
        System.out.println("NotesTest: " + count + " assertions passed");
    }
}
