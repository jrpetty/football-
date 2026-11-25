package com.jrpetty.mcassistant.pa.voice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * [voice37] Lines the expressive voice has already made, kept on disk (config/mc_assistant/voice/cache), so a common line
 * ("Right then.", "On my way.") plays at once the next time instead of being made again. Keyed by the voice, the exact
 * words with their sounds, and how freely it was read; 16-bit sound; the oldest-used go first past the size limit.
 */
public final class PaLineCache {

    public static final long DEFAULT_LIMIT = 64L << 20;
    /** Longer sentences are rarely repeated word for word: not kept. */
    public static final int MAX_CHARS = 160;

    private final Path dir;
    private final long limit;

    public PaLineCache(Path dir, long limitBytes) {
        this.dir = dir;
        this.limit = limitBytes;
    }

    /** The file name for a line: a SHA-256 of what decides its sound. */
    public static String key(String voice, String text, float temperature) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update((voice + "\u0000" + text + "\u0000" + Math.round(temperature * 20)).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            byte[] d = md.digest();
            for (int i = 0; i < 16; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString((voice + text).hashCode());
        }
    }

    public static boolean worthKeeping(String text) {
        return text != null && !text.isBlank() && text.length() <= MAX_CHARS;
    }

    /** The samples kept for this line, or null. */
    public float[] get(String voice, String text, float temperature) {
        Path f = dir.resolve(key(voice, text, temperature) + ".pcm");
        try {
            if (!Files.isRegularFile(f)) return null;
            byte[] b = Files.readAllBytes(f);
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            float[] a = new float[b.length / 2];
            for (int i = 0; i < a.length; i++) a[i] = bb.getShort() / 32767f;
            Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis()));
            return a;
        } catch (IOException e) {
            return null;
        }
    }

    public void put(String voice, String text, float temperature, float[] audio) {
        if (!worthKeeping(text) || audio == null || audio.length == 0) return;
        try {
            Files.createDirectories(dir);
            ByteBuffer bb = ByteBuffer.allocate(audio.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (float x : audio) bb.putShort((short) Math.round(Math.max(-1f, Math.min(1f, x)) * 32767));
            Path tmp = dir.resolve(key(voice, text, temperature) + ".tmp");
            Files.write(tmp, bb.array());
            Files.move(tmp, dir.resolve(key(voice, text, temperature) + ".pcm"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            trim();
        } catch (IOException ignored) {
            // a full disk: just not kept
        }
    }

    /** Keep under the limit: the least recently used go first. */
    void trim() throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (java.util.stream.Stream<Path> st = Files.list(dir)) {
            for (Path p : st.filter(p -> p.toString().endsWith(".pcm")).toList()) { files.add(p); total += Files.size(p); }
        }
        if (total <= limit) return;
        files.sort(Comparator.comparingLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; } }));
        for (Path p : files) {
            if (total <= limit) break;
            total -= Files.size(p);
            Files.deleteIfExists(p);
        }
    }

    /** Forget every kept line (a voice changed, or Settings asked). */
    public void clear() {
        try (java.util.stream.Stream<Path> st = Files.list(dir)) {
            for (Path p : st.toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // nothing there
        }
    }
}
