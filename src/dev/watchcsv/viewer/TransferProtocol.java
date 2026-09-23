package dev.watchcsv.viewer;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Bounded-memory, byte-preserving CSV transfer with a receiver receipt. No Android dependencies. */
public final class TransferProtocol {
    public static final String CAPABILITY = "watchcsv_receiver_v1";
    public static final String PATH = "/watchcsv/transfer/v1/";
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAGIC = 0x57435356, RESPONSE = 0x5741434B, END = 0x57454E44, CONFIRM = 0x57444F4E;
    private static final int READY = 1, SAVED = 2, ERROR = 3;
    public interface Control { void check() throws IOException; }
    public interface Progress { void update(String phase, String name, long done, long total); }
    public static final class Staged implements Closeable {
        public final File file;
        public final String name;
        public final long size;
        public final byte[] digest;
        private Staged(File file, String name, long size, byte[] digest) { this.file = file; this.name = name; this.size = size; this.digest = digest; }
        public void close() { file.delete(); }
    }
    public static final class ReceiptLostException extends IOException {
        public final File saved;
        ReceiptLostException(File saved, IOException cause) { super("Saved to Inbox, but phone acknowledgement was interrupted: " + saved.getName(), cause); this.saved = saved; }
    }
    private TransferProtocol() {}
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String safeName(String original) {
        String value = original == null ? "data.csv" : original;
        value = value.replace('\\', '/'); value = value.substring(value.lastIndexOf('/') + 1);
        value = value.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..")) value = "data.csv";
        String lower = value.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt"))) value += ".csv";
        int dot = value.lastIndexOf('.'); String stem = value.substring(0, dot), extension = value.substring(dot);
        StringBuilder shortened = new StringBuilder(); int bytes = extension.length();
        for (int at = 0; at < stem.length();) {
            int cp = stem.codePointAt(at), count = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + count > 180) break;
            shortened.appendCodePoint(cp); bytes += count; at += Character.charCount(cp);
        }
        return (shortened.length() == 0 ? "data" : shortened.toString()) + extension;
    }
    public static Staged stage(InputStream input, File directory, String name, Control control, Progress progress) throws IOException {
        try (InputStream source = input) {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create transfer cache");
            File temp = File.createTempFile("outgoing-", ".partial", directory); boolean success = false;
            try {
                long size = 0; byte[] buffer = new byte[65536]; MessageDigest digest = sha256();
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    while (true) {
                        control.check(); int n = source.read(buffer); if (n == -1) break; if (n == 0) continue;
                        size += n; if (size > MAX_BYTES) throw new IOException("A transfer is limited to 256 MiB per file");
                        out.write(buffer, 0, n); digest.update(buffer, 0, n); progress.update("Preparing", name, size, -1);
                    }
                }
                control.check(); success = true;
                return new Staged(temp, safeName(name), size, digest.digest());
            } finally { if (!success) temp.delete(); }
        }
    }
    private static void reply(DataOutputStream out, int status, String text) throws IOException {
        out.writeInt(RESPONSE); out.writeByte(status); out.writeUTF(text.length() > 1500 ? text.substring(0, 1500) : text); out.flush();
    }
    private static String expect(DataInputStream in, int expected) throws IOException {
        if (in.readInt() != RESPONSE) throw new IOException("Incompatible transfer reply. Update both apps.");
        int status = in.readUnsignedByte(); String message = in.readUTF();
        if (status == ERROR) throw new IOException("Watch: " + message);
        if (status != expected) throw new IOException("Unexpected transfer state. Retry with both apps updated.");
        return message;
    }
    public static String send(Staged staged, InputStream input, OutputStream output, Control control, Progress progress) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(input, 8192));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output, 65536));
        control.check(); out.writeInt(MAGIC); out.writeInt(1); out.writeUTF(staged.name); out.writeLong(staged.size); out.write(staged.digest); out.flush();
        progress.update("Waiting for watch", staged.name, 0, staged.size); expect(in, READY);
        try (InputStream file = new FileInputStream(staged.file)) {
            byte[] buffer = new byte[65536]; long done = 0;
            while (done < staged.size) {
                control.check(); int n = file.read(buffer, 0, (int)Math.min(buffer.length, staged.size - done));
                if (n < 0) throw new EOFException("Prepared file was truncated");
                out.write(buffer, 0, n); done += n; progress.update("Sending", staged.name, done, staged.size);
            }
        }
        control.check(); out.writeInt(END); out.flush();
        progress.update("Verifying on watch", staged.name, staged.size, staged.size);
        String savedName = expect(in, SAVED);
        // Receiving SAVED is the success condition. A lost final courtesy confirmation
        // must not make the sender claim that a verified, saved file failed.
        try { out.writeInt(CONFIRM); out.flush(); } catch (IOException ignored) {}
        return savedName;
    }
    public static File receive(InputStream input, OutputStream output, File inbox, Control control, Progress progress) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(input, 65536));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output, 8192));
        File temporary = null, saved = null;
        try {
            control.check();
            if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("Incompatible transfer. Update both apps.");
            String name = safeName(in.readUTF()); long size = in.readLong(); byte[] expected = new byte[32]; in.readFully(expected);
            if (size < 0 || size > MAX_BYTES) throw new IOException("File size is invalid or exceeds 256 MiB");
            if (!inbox.isDirectory() && !inbox.mkdirs()) throw new IOException("Cannot create watch Inbox");
            long free = inbox.getUsableSpace();
            if (free > 0 && free < size + 2L * 1024 * 1024) throw new IOException("Not enough free storage on the watch");
            temporary = File.createTempFile("incoming-", ".partial", inbox);
            reply(out, READY, "");
            MessageDigest digest = sha256(); byte[] buffer = new byte[65536]; long done = 0;
            progress.update("Receiving", name, 0, size);
            try (FileOutputStream file = new FileOutputStream(temporary)) {
                while (done < size) {
                    control.check(); int n = in.read(buffer, 0, (int)Math.min(buffer.length, size - done));
                    if (n < 0) throw new EOFException("Connection ended before the complete file arrived");
                    if (n == 0) continue;
                    file.write(buffer, 0, n); digest.update(buffer, 0, n); done += n;
                    progress.update("Receiving", name, done, size);
                }
                if (in.readInt() != END) throw new IOException("Missing file-completion marker");
                if (!MessageDigest.isEqual(expected, digest.digest())) throw new IOException("Checksum mismatch; incomplete file discarded. Please retry.");
                control.check(); file.getFD().sync();
            }
            control.check(); saved = publish(temporary, inbox, name);
            progress.update("Saved to Inbox", saved.getName(), size, size);
            reply(out, SAVED, saved.getName());
            // The phone closes this exchange after its receipt. EOF also means it
            // has gone away; the already verified file remains available either way.
            try { in.readInt(); } catch (IOException ignored) {}
            return saved;
        } catch (IOException e) {
            if (saved != null) throw new ReceiptLostException(saved, e);
            try { reply(out, ERROR, e.getMessage() == null ? "Transfer failed" : e.getMessage()); } catch (IOException ignored) {}
            throw e;
        } finally { if (temporary != null && temporary.exists()) temporary.delete(); }
    }
    private static synchronized File publish(File temporary, File inbox, String name) throws IOException {
        int dot = name.lastIndexOf('.'); String stem = name.substring(0, dot), extension = name.substring(dot);
        for (int i = 1; i <= 10000; i++) {
            File target = new File(inbox, i == 1 ? name : stem + " (" + i + ")" + extension);
            try { Files.move(temporary.toPath(), target.toPath()); return target; }
            catch (FileAlreadyExistsException collision) { /* Preserve existing files. */ }
        }
        throw new IOException("Too many files with the same name in Inbox");
    }
}
