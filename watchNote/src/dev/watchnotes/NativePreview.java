package dev.watchnotes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.Spanned;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Wear OS preview: framework TextView/Html, never a WebView or a WebView provider. */
final class NativePreview {
    static final class Prepared {
        final String html;
        final Map<String, Bitmap> images;
        Prepared(String html, Map<String, Bitmap> images) { this.html=html; this.images=images; }
    }

    private NativePreview() { }

    /** Called on the activity's worker; all network and decoding stays off the main thread. */
    static Prepared prepare(Note note, boolean showImages) {
        Document doc = Jsoup.parse(MarkdownPreview.html(note, showImages));
        // Android's Html.fromHtml does not lay out HTML tables. Keep their cell boundaries readable.
        for (Element table : doc.select("table")) {
            Element list = new Element("div");
            for (Element row : table.select("tr")) {
                StringBuilder text = new StringBuilder();
                for (Element cell : row.select("th,td")) {
                    if (text.length() > 0) text.append(" | ");
                    text.append(cell.text());
                }
                list.appendElement("p").text(text.toString());
            }
            table.replaceWith(list);
        }
        Map<String, Bitmap> loaded = new HashMap<>();
        int attempted = 0;
        if (showImages) for (Element image : doc.select("img[src]")) {
            if (attempted >= 4) break;
            String src = image.attr("src");
            if (loaded.containsKey(src)) continue;
            attempted++;
            try {
                byte[] bytes;
                if (src.startsWith("data:image/")) {
                    bytes = Base64.decode(src.substring(src.indexOf(',') + 1), Base64.DEFAULT);
                    if (bytes.length > 160000) continue;
                } else if (src.startsWith("https://")) {
                    bytes = download(src);
                } else continue;
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue;
                BitmapFactory.Options sample = new BitmapFactory.Options();
                while ((bounds.outWidth / sample.inSampleSize > 512 || bounds.outHeight / sample.inSampleSize > 512)
                        && sample.inSampleSize < 4096) sample.inSampleSize *= 2;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sample);
                if (bitmap != null) loaded.put(src, bitmap);
            } catch (Exception ignored) { /* The text preview stays available when an image cannot load. */ }
        }
        return new Prepared(doc.body().html(), loaded);
    }

    private static byte[] download(String source) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(source).openConnection();
        connection.setConnectTimeout(2500); connection.setReadTimeout(2500);
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        try {
            if (connection.getResponseCode() != 200 || connection.getContentType() == null
                    || !connection.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("image/"))
                throw new IOException("Image unavailable");
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > 256000) throw new IOException("Image too large for watch preview");
                    bytes.write(buffer, 0, n);
                }
                return bytes.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    /** Called on the UI thread; uses only previously loaded, downsampled bitmaps. */
    static Spanned render(Context context, Prepared prepared) {
        int maxWidth = Math.max(1, Math.min(context.getResources().getDisplayMetrics().widthPixels - 40,
            (int)(context.getResources().getDisplayMetrics().density * 300)));
        return Html.fromHtml(prepared.html, Html.FROM_HTML_MODE_COMPACT, src -> {
            Bitmap bitmap = prepared.images.get(src);
            if (bitmap == null) {
                Drawable placeholder = new ColorDrawable(0xff555555);
                placeholder.setBounds(0, 0, 24, 24); return placeholder;
            }
            Drawable drawable = new BitmapDrawable(context.getResources(), bitmap);
            int width = Math.max(1, Math.min(maxWidth, bitmap.getWidth()));
            int height = Math.max(1, (int)((long)bitmap.getHeight() * width / bitmap.getWidth()));
            drawable.setBounds(0, 0, width, height);
            return drawable;
        }, null);
    }
}
