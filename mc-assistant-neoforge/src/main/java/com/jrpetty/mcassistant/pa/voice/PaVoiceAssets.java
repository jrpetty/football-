package com.jrpetty.mcassistant.pa.voice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * [voice37] The voices inside the mod jar. A release jar (built with -PbundleVoice=true) carries every voice file under
 * assets/mc_assistant/voicepack/&lt;group&gt;/ ("kokoro": the natural voice and ONNX Runtime; "expressive": Chatterbox-Turbo)
 * with a manifest of their sizes and SHA-256 sums. Nothing is fetched from the network: on first use the mod unpacks the
 * files it needs into the game folder (mc_assistant-cache/voice/&lt;version&gt;/, so ONNX Runtime can map them from disk),
 * each checked by its SHA-256 as it comes out, resumed if the game closed part-way, and removes the folders of older
 * versions. A development build has no voicepack (words only, or an offline voice pack zip in config/).
 */
public final class PaVoiceAssets {

    private PaVoiceAssets() {}

    public static final String ROOT = "/assets/mc_assistant/voicepack/";
    public static final String MANIFEST = ROOT + "manifest.tsv";

    /** One bundled file: its group ("kokoro" or "expressive"), name, size and SHA-256 as packed. */
    public record Entry(String group, String file, long bytes, String sha256) {
        public String resource() { return ROOT + group + "/" + file; }
    }

    /** The manifest: one file a line, "group TAB file TAB bytes TAB sha256"; '#' lines are notes. */
    public static List<Entry> read(InputStream in) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.strip();
                if (l.isEmpty() || l.startsWith("#")) continue;
                String[] f = l.split("\t");
                if (f.length < 4 || !f[3].matches("[0-9a-f]{64}") || f[1].contains("/") || f[1].contains("\\") || f[1].startsWith(".")) throw new IOException("bad voice manifest line: " + l);
                out.add(new Entry(f[0], f[1], Long.parseLong(f[2]), f[3]));
            }
        }
        return out;
    }

    /** What this jar carries (empty for a development build). */
    public static List<Entry> bundled() {
        return bundled(PaVoiceAssets.class::getResourceAsStream);
    }

    public static List<Entry> bundled(Function<String, InputStream> open) {
        try (InputStream in = open.apply(MANIFEST)) {
            return in == null ? List.of() : read(in);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A group's files as the downloader's parts (their "url" is the resource inside the jar). */
    public static List<PaKokoro.Part> parts(List<Entry> all, String group) {
        List<PaKokoro.Part> out = new ArrayList<>();
        for (Entry e : all) if (e.group().equals(group)) out.add(new PaKokoro.Part(e.file(), "jar:" + e.resource(), e.file(), e.bytes(), e.sha256(), "bundled"));
        return out;
    }

    /** Bytes in a group (what unpacking it puts on disk). */
    public static long bytes(List<Entry> all, String group) {
        long b = 0;
        for (Entry e : all) if (e.group().equals(group)) b += e.bytes();
        return b;
    }

    /**
     * The "network" the downloader reads from when unpacking: the jar's own resources, from an offset (so an unpack the
     * game closed part-way carries on). Anything that isn't a "jar:" resource is refused: nothing goes online.
     */
    public static PaKokoro.Fetcher fromJar(Function<String, InputStream> open) {
        return (uri, from) -> {
            String s = uri.toString();
            if (!s.startsWith("jar:/assets/mc_assistant/voicepack/")) throw new IOException("not a bundled voice file: " + s);
            InputStream in = open.apply(s.substring(4));
            if (in == null) throw new IOException("the mod jar has no " + s.substring(s.lastIndexOf('/') + 1));
            long skipped = 0;
            while (skipped < from) {
                long n = in.skip(from - skipped);
                if (n <= 0) { if (in.read() < 0) break; n = 1; }
                skipped += n;
            }
            return new PaKokoro.Fetcher.Reply(from > 0 ? 206 : 200, -1, in);
        };
    }

    public static PaKokoro.Fetcher fromJar() {
        return fromJar(PaVoiceAssets.class::getResourceAsStream);
    }

    /** Where this version unpacks: the game folder's mc_assistant-cache/voice/&lt;version&gt;-&lt;first 8 of the manifest's hash&gt;. */
    public static Path home(Path gameDir, String version, List<Entry> all) {
        StringBuilder b = new StringBuilder();
        for (Entry e : all) b.append(e.sha256());
        String tag = Integer.toHexString(b.toString().hashCode());
        return gameDir.resolve("mc_assistant-cache").resolve("voice").resolve((version == null || version.isBlank() ? "dev" : version.replaceAll("[^A-Za-z0-9._-]", "_")) + "-" + tag);
    }

    /**
     * The natural voice as older versions downloaded it (config/mc_assistant/voice/kokoro), unused now the voices come in
     * the jar: removed, but only when it is plainly that folder (its model and voice pack there, and nothing in it but
     * the files that download wrote: the known names, their ".part" leftovers and the check marker). Anything else in
     * it and it is left alone. True when it was removed.
     */
    public static boolean removeOldKokoro(Path dir, java.util.Collection<String> known) {
        try {
            if (dir == null || !Files.isDirectory(dir) || !dir.getFileName().toString().equals("kokoro")) return false;
            if (!Files.isRegularFile(dir.resolve("kokoro-v1.0.int8.onnx")) && !Files.isRegularFile(dir.resolve("voices-v1.0.bin"))) return false;
            List<Path> files;
            try (java.util.stream.Stream<Path> st = Files.list(dir)) { files = st.toList(); }
            for (Path f : files) {
                String n = f.getFileName().toString();
                String base = n.endsWith(".part") ? n.substring(0, n.length() - 5) : n.endsWith(".pack") ? n.substring(0, n.length() - 5) : n;
                if (!Files.isRegularFile(f) || !(known.contains(base) || n.equals("verified.sha256"))) return false;   // not only ours: leave it
            }
            for (Path f : files) Files.deleteIfExists(f);
            Files.deleteIfExists(dir);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Remove the unpacked voices of other versions (only folders beside this one, in mc_assistant-cache/voice). */
    public static int clean(Path home) {
        Path parent = home.getParent();
        if (parent == null || !parent.getFileName().toString().equals("voice") || !Files.isDirectory(parent)) return 0;
        int removed = 0;
        try (java.util.stream.Stream<Path> st = Files.list(parent)) {
            for (Path old : st.filter(p -> Files.isDirectory(p) && !p.equals(home)).toList()) {
                try (java.util.stream.Stream<Path> walk = Files.walk(old)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
                removed++;
            }
        } catch (IOException ignored) {
            // in use or locked: tried again next start
        }
        return removed;
    }
}
