package dev.watchcsv.viewer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TransferTest {
    private static int assertions;
    private static final TransferProtocol.Control OK = () -> {};
    private static final TransferProtocol.Progress QUIET = (phase, name, done, total) -> {};
    private static void eq(Object expected, Object actual) {
        assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static byte[] digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536];
        try (InputStream in = new FileInputStream(file)) { int n; while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n); }
        return hash.digest();
    }
    private static File exchange(TransferProtocol.Staged staged, File inbox, boolean shouldFail) throws Exception {
        PipedInputStream watchIn = new PipedInputStream(131072), phoneIn = new PipedInputStream(131072);
        PipedOutputStream phoneOut = new PipedOutputStream(watchIn), watchOut = new PipedOutputStream(phoneIn);
        ExecutorService receiver = Executors.newSingleThreadExecutor();
        Future<File> saved = receiver.submit(() -> {
            try { return TransferProtocol.receive(watchIn, watchOut, inbox, OK, QUIET); }
            finally { watchIn.close(); watchOut.close(); }
        });
        try {
            String name = null; IOException sendError = null;
            try { name = TransferProtocol.send(staged, phoneIn, phoneOut, OK, QUIET); }
            catch (IOException error) { sendError = error; }
            if (shouldFail) {
                eq(true, sendError != null);
                eq(true, sendError.getMessage().contains("Checksum"));
                try { saved.get(20, TimeUnit.SECONDS); throw new AssertionError("Receiver accepted corruption"); }
                catch (ExecutionException expected) { eq(true, expected.getCause() instanceof IOException); }
                return null;
            }
            if (sendError != null) throw sendError;
            File file = saved.get(20, TimeUnit.SECONDS); eq(name, file.getName());
            eq(staged.size, file.length()); eq(true, Arrays.equals(staged.digest, digest(file))); return file;
        } finally {
            phoneIn.close(); phoneOut.close(); watchIn.close(); watchOut.close(); receiver.shutdownNow();
        }
    }
    private static byte[] packet(String name, long size, byte[] data, boolean checksumValid, boolean footerValid) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x57435356); out.writeInt(1); out.writeUTF(name); out.writeLong(size);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data); if (!checksumValid) digest[0] ^= 1;
        out.write(digest); out.write(data); out.writeInt(footerValid ? 0x57454E44 : 0); out.writeInt(0x57444F4E); return bytes.toByteArray();
    }
    private static void rejection(byte[] packet, File inbox, TransferProtocol.Control control) throws Exception {
        int before = Objects.requireNonNull(inbox.list()).length; boolean failed = false;
        try { TransferProtocol.receive(new ByteArrayInputStream(packet), new ByteArrayOutputStream(), inbox, control, QUIET); }
        catch (IOException expected) { failed = true; }
        eq(true, failed); eq(before, Objects.requireNonNull(inbox.list()).length);
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory(Paths.get(args[0]), "transfer-test-").toFile();
        File cache = new File(root, "cache"), inbox = new File(root, "Inbox");
        if (!cache.mkdir() || !inbox.mkdir()) throw new IOException("Cannot create test directories");
        eq("file.csv", TransferProtocol.safeName("../../file.csv"));
        eq("file.csv", TransferProtocol.safeName("C:\\secret\\file.csv"));
        eq("data.csv", TransferProtocol.safeName(".."));
        eq("notes.csv", TransferProtocol.safeName("notes"));
        eq("a_b.csv", TransferProtocol.safeName("a\nb.csv"));
        String longName = String.join("", Collections.nCopies(200, "文😀")) + ".csv";
        String safe = TransferProtocol.safeName(longName);
        eq(true, safe.getBytes(StandardCharsets.UTF_8).length <= 180); eq(true, safe.endsWith(".csv"));
        byte[] cyrillic = "Имя;Город\r\nИван;Москва\r\nАнна;\"Санкт-Петербург; центр\"\r\n".getBytes("windows-1251");
        try (TransferProtocol.Staged staged = TransferProtocol.stage(new ByteArrayInputStream(cyrillic), cache, "данные.csv", OK, QUIET)) {
            File first = exchange(staged, inbox, false), second = exchange(staged, inbox, false);
            eq("данные.csv", first.getName()); eq("данные (2).csv", second.getName());
            eq(true, Arrays.equals(cyrillic, Files.readAllBytes(first.toPath())));
            eq(true, Arrays.equals(cyrillic, Files.readAllBytes(second.toPath())));
            eq(true, first.exists());
        }
        try (TransferProtocol.Staged empty = TransferProtocol.stage(new ByteArrayInputStream(new byte[0]), cache, "empty.csv", OK, QUIET)) {
            eq(0L, exchange(empty, inbox, false).length());
        }
        try (TransferProtocol.Staged corrupt = TransferProtocol.stage(new ByteArrayInputStream(cyrillic), cache, "corrupt.csv", OK, QUIET)) {
            try (RandomAccessFile f = new RandomAccessFile(corrupt.file, "rw")) { f.writeByte(0); }
            int count = Objects.requireNonNull(inbox.list()).length;
            exchange(corrupt, inbox, true); eq(count, Objects.requireNonNull(inbox.list()).length);
        }
        rejection(packet("bad.csv", -1, new byte[0], true, true), inbox, OK);
        rejection(packet("bad.csv", TransferProtocol.MAX_BYTES + 1, new byte[0], true, true), inbox, OK);
        rejection(packet("bad.csv", 1000, cyrillic, true, true), inbox, OK);
        rejection(packet("bad.csv", cyrillic.length, cyrillic, false, true), inbox, OK);
        rejection(packet("bad.csv", cyrillic.length, cyrillic, true, false), inbox, OK);
        rejection(new byte[]{0, 0, 0, 0}, inbox, OK);
        final int[] checks = {0};
        rejection(packet("cancel.csv", cyrillic.length, cyrillic, true, true), inbox, () -> { if (++checks[0] >= 3) throw new InterruptedIOException("cancel"); });
        AtomicBoolean closed = new AtomicBoolean();
        InputStream source = new ByteArrayInputStream(cyrillic) { @Override public void close() { closed.set(true); } };
        boolean stageStopped = false;
        try { TransferProtocol.stage(source, cache, "cancel.csv", () -> { throw new InterruptedIOException("cancel"); }, QUIET); }
        catch (InterruptedIOException expected) { stageStopped = true; }
        eq(true, stageStopped); eq(true, closed.get()); eq(0, Objects.requireNonNull(cache.list()).length);

        // Larger than the requested 11 MB, without ever holding the complete payload in RAM.
        final long total = 14_640_000L;
        InputStream generated = new InputStream() {
            long position;
            public int read() { return position == total ? -1 : (int)(position++ % 251); }
            public int read(byte[] out, int off, int len) {
                if (position == total) return -1; int count = (int)Math.min(len, total - position);
                for (int i = 0; i < count; i++) out[off + i] = (byte)(position++ % 251); return count;
            }
        };
        try (TransferProtocol.Staged large = TransferProtocol.stage(generated, cache, "large.csv", OK, QUIET)) {
            eq(total, large.size); eq(total, exchange(large, inbox, false).length());
        }
        eq(0, Objects.requireNonNull(cache.list()).length);
        for (File file : Objects.requireNonNull(inbox.listFiles())) { eq(false, file.getName().endsWith(".partial")); Files.delete(file.toPath()); }
        Files.delete(inbox.toPath()); Files.delete(cache.toPath()); Files.delete(root.toPath());
        System.out.println("PASS: " + assertions + " transfer assertions, including a verified 14,640,000-byte duplex stream under a 32 MB Java heap.");
    }
}
