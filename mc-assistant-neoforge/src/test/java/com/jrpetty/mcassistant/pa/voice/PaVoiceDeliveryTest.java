package com.jrpetty.mcassistant.pa.voice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [voice37] How the voice is delivered: the whole tag-mapping matrix (every kind of line, corner, humour, expressiveness
 * and bond: one sound at most, none on a plain answer or a warning, Dry never laughs out loud, Sincere never mocks), the
 * queue (never two at once, a warning cuts in, stale and superseded lines dropped), the true-peak ceiling, and the
 * voices inside the jar (the manifest, unpacking with a small fake asset, resuming, a bad file refused, old versions
 * cleaned up, and the bundled list kept the same as the code's).
 */
class PaVoiceDeliveryTest {

    static final String LINE = "Right. That went well. Mostly. I'll fetch the rest.";

    static int sounds(String s) {
        int n = 0;
        for (String t : PaVoiceStyle.TAGS) for (int i = s.indexOf(t); i >= 0; i = s.indexOf(t, i + 1)) n++;
        return n;
    }

    // ------------------------------------------------------------------ the tag-mapping matrix

    @Test
    void tagMatrix() {
        double[][] corners = {{0, 0}, {0, 1}, {1, 0}, {0.1, 0.6}, {0.6, 0.1}, {0.4, 0.4}, {0.33, 0.33}};
        int cases = 0;
        Map<String, Integer> used = new HashMap<>();
        for (PaVoiceStyle.Kind k : PaVoiceStyle.Kind.values())
            for (double[] c : corners)
                for (int h = 0; h < 3; h++)
                    for (int e = 0; e <= 100; e += 10)
                        for (int bond = 0; bond <= 4; bond++)
                            for (boolean gl : new boolean[]{false, true}) {
                                PaVoiceStyle.Style st = PaVoiceStyle.perform(LINE, k, c[0], c[1], h, e, gl, bond);
                                String why = k + " gloom " + c[0] + " fun " + c[1] + " humour " + h + " e " + e + " bond " + bond + " gloomyLine " + gl + ": " + st.text();
                                assertTrue(sounds(st.text()) <= 1, "one sound at most: " + why);
                                if (k == PaVoiceStyle.Kind.PLAIN || k == PaVoiceStyle.Kind.WARNING || e < 15) assertEquals(0, sounds(st.text()), "none: " + why);
                                if (h == PaVoiceStyle.DRY) assertFalse(st.text().contains("[laugh]") || st.text().contains("[groan]"), "Dry is understated: " + why);
                                if (h == PaVoiceStyle.SINCERE && (k == PaVoiceStyle.Kind.ROAST || k == PaVoiceStyle.Kind.QUIP))
                                    assertFalse(st.text().contains("[chuckle]") || st.text().contains("[laugh]"), "Sincere never mocks: " + why);
                                if (k == PaVoiceStyle.Kind.AFFECTION && bond == 0) assertEquals(0, sounds(st.text()), "no fondness for a stranger: " + why);
                                assertEquals(LINE, PaVoiceStyle.strip(st.text()), "the words never change: " + why);
                                assertTrue(st.temperature() >= 0.5f && st.temperature() <= 0.95f && st.gapScale() >= 0.8 && st.gapScale() <= 1.3, why);
                                for (String t : PaVoiceStyle.TAGS) if (st.text().contains(t)) used.merge(t, 1, Integer::sum);
                                cases++;
                            }
        assertTrue(cases > 20_000, cases + " cases");
        for (String t : List.of("[laugh]", "[chuckle]", "[sigh]", "[gasp]", "[groan]")) assertTrue(used.getOrDefault(t, 0) > 0, t + " is reachable");
        assertTrue(used.get("[chuckle]") > used.get("[laugh]"), "a chuckle is the usual sound, a laugh the rarer: " + used);
        // Daft is bigger than Dry, Sincere warmer at a death, and gloom lingers between sentences
        assertTrue(PaVoiceStyle.perform(LINE, PaVoiceStyle.Kind.JOKE, 0.1, 0.7, PaVoiceStyle.DAFT, 60, false, 2).text().contains("[laugh]"));
        assertTrue(PaVoiceStyle.perform(LINE, PaVoiceStyle.Kind.JOKE, 0.1, 0.7, PaVoiceStyle.DRY, 60, false, 2).text().contains("[chuckle]"));
        assertTrue(PaVoiceStyle.perform(LINE, PaVoiceStyle.Kind.AFFECTION, 0.1, 0.5, PaVoiceStyle.DRY, 60, false, 3).text().endsWith("[chuckle]"), "Best Mate: Dry lets it show");
        assertEquals(0, sounds(PaVoiceStyle.perform(LINE, PaVoiceStyle.Kind.AFFECTION, 0.1, 0.5, PaVoiceStyle.DRY, 60, false, 1).text()), "a Mate: Dry keeps it to itself");
        assertTrue(PaVoiceStyle.perform(LINE, PaVoiceStyle.Kind.QUIP, 0.8, 0.1, PaVoiceStyle.DRY, 50, true, 1).gapScale() > 1.0, "gloom lingers");
    }

    // ------------------------------------------------------------------ the queue

    @Test
    void queueNeverOverlapsAndDropsStale() {
        PaSpeechQueue<String> q = new PaSpeechQueue<>();
        assertEquals(PaSpeechQueue.Action.WAIT, q.offer(new PaSpeechQueue.Item<>("Heading to the mine.", null, false, 0)));
        assertEquals(PaSpeechQueue.Action.WAIT, q.offer(new PaSpeechQueue.Item<>("Mined 10 of 64.", "say.step", false, 100)));
        assertEquals(PaSpeechQueue.Action.WAIT, q.offer(new PaSpeechQueue.Item<>("Mined 20 of 64.", "say.step", false, 200)));
        assertEquals(2, q.size(), "the newer progress report replaced the older one still waiting");
        assertEquals("Heading to the mine.", q.next(300).line(), "in order: one at a time");
        assertEquals("Mined 20 of 64.", q.next(300).line());
        assertNull(q.next(300));
        q.offer(new PaSpeechQueue.Item<>("Nice day for it.", null, false, 1000));
        assertNull(q.next(1000 + PaSpeechQueue.MAX_WAIT_MS + 1), "a remark that waited too long goes unsaid");
        for (int i = 0; i < 6; i++) q.offer(new PaSpeechQueue.Item<>("chat " + i, null, false, 5000 + i));
        assertEquals(PaSpeechQueue.MAX_WAITING, q.size(), "never more than three wait");
        assertEquals("chat 3", q.next(5010).line(), "the oldest went first");
        assertEquals(PaSpeechQueue.Action.INTERRUPT, q.offer(new PaSpeechQueue.Item<>("Creeper behind you!", "quip.creeper", true, 5020)), "a warning cuts in");
        assertEquals(1, q.size(), "and clears the chatter");
        assertEquals("Creeper behind you!", q.next(5021).line());
        q.offer(new PaSpeechQueue.Item<>("Done.", "say.done", false, 6000));
        q.clear();
        assertNull(q.next(6000), "hush clears it");
        assertTrue(q.dropped() >= 7);
        assertEquals("say.step", PaSpeechQueue.family("say.step@gloomy"));
        assertNull(PaSpeechQueue.family(null));
    }

    // ------------------------------------------------------------------ loudness

    @Test
    void truePeakAtOrUnderMinusOne() {
        float[] a = new float[24000];
        Random r = new Random(3);
        for (int i = 0; i < a.length; i++) a[i] = (float) (0.6 * Math.sin(i * 2 * Math.PI * 5300 / 24000.0 + 0.7) + 0.05 * r.nextGaussian());
        float[] done = PaAudio.finish(a, 24000, 1.0);
        assertTrue(PaAudio.truePeakDb(done) <= PaAudio.CEILING_DB + 0.01, "true peak " + PaAudio.truePeakDb(done));
        float[] square = new float[4800];
        for (int i = 0; i < square.length; i++) square[i] = (i / 6) % 2 == 0 ? 0.891f : -0.891f;   // sample peaks at -1 dBFS, overshoots between
        assertTrue(PaAudio.truePeakDb(square) > -1.0, "the overshoot between samples is seen: " + PaAudio.truePeakDb(square));
        assertTrue(PaAudio.truePeakDb(PaAudio.truePeakCeiling(square, -1.0)) <= -1.0);
        assertEquals(Double.NEGATIVE_INFINITY, PaAudio.truePeakDb(new float[100]));
    }

    // ------------------------------------------------------------------ the voices inside the jar

    static String sha(byte[] b) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }

    @Test
    void unpackFromAFakeJar(@TempDir Path game) throws Exception {
        byte[] model = new byte[250_000], words = "{\"model\":{}}".getBytes(StandardCharsets.UTF_8);
        new Random(7).nextBytes(model);
        Map<String, byte[]> jar = new HashMap<>();
        jar.put("/assets/mc_assistant/voicepack/expressive/lm.onnx_data", model);
        jar.put("/assets/mc_assistant/voicepack/expressive/tokenizer.json", words);
        String manifest = "# group\tfile\tbytes\tsha256\nexpressive\tlm.onnx_data\t" + model.length + "\t" + sha(model) + "\nexpressive\ttokenizer.json\t" + words.length + "\t" + sha(words) + "\n";
        jar.put(PaVoiceAssets.MANIFEST, manifest.getBytes(StandardCharsets.UTF_8));
        java.util.function.Function<String, InputStream> open = p -> jar.containsKey(p) ? new ByteArrayInputStream(jar.get(p)) : null;
        List<PaVoiceAssets.Entry> pack = PaVoiceAssets.bundled(open);
        assertEquals(2, pack.size());
        assertEquals(model.length + words.length, PaVoiceAssets.bytes(pack, "expressive"));
        Path home = PaVoiceAssets.home(game, "0.400.0", pack);
        assertTrue(home.startsWith(game.resolve("mc_assistant-cache").resolve("voice")) && home.getFileName().toString().startsWith("0.400.0-"));
        // an older version's folder, and a half-unpacked file from a game closed part-way
        Path old = game.resolve("mc_assistant-cache/voice/0.399.0-abcd/expressive");
        Files.createDirectories(old);
        Files.write(old.resolve("lm.onnx_data"), new byte[10]);
        Files.createDirectories(home.resolve("expressive"));
        Files.write(home.resolve("expressive/lm.onnx_data.part"), java.util.Arrays.copyOf(model, 100_000));
        PaExpressive e = new PaExpressive(home.resolve("expressive"), PaVoiceAssets.parts(pack, "expressive"));
        assertFalse(e.downloaded());
        List<String> said = new ArrayList<>();
        assertTrue(e.files().install(PaVoiceAssets.fromJar(open), (f, l) -> said.add(l)), e.files().why());
        assertTrue(e.downloaded(), "unpacked and checked");
        assertTrue(said.stream().anyMatch(l -> l.startsWith("Unpacking")), "it says unpacking, not downloading: " + said);
        assertEquals(sha(model), sha(Files.readAllBytes(home.resolve("expressive/lm.onnx_data"))), "resumed from the part file, whole and right");
        assertEquals(1, PaVoiceAssets.clean(home), "the older version's voices removed");
        assertFalse(Files.exists(game.resolve("mc_assistant-cache/voice/0.399.0-abcd")));
        assertTrue(Files.exists(home.resolve("expressive/lm.onnx_data")), "this version's kept");
        // a damaged file in the jar is never used
        jar.put("/assets/mc_assistant/voicepack/expressive/tokenizer.json", "tampered".getBytes(StandardCharsets.UTF_8));
        PaExpressive again = new PaExpressive(game.resolve("other"), PaVoiceAssets.parts(pack, "expressive"));
        assertFalse(again.files().install(PaVoiceAssets.fromJar(open), (f, l) -> { }));
        assertFalse(Files.exists(game.resolve("other/tokenizer.json")));
        // nothing goes online: only "jar:" voicepack resources are read
        try {
            PaVoiceAssets.fromJar(open).get(java.net.URI.create("https://example.com/model.onnx"), 0);
            throw new AssertionError("fetched from the network");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("not a bundled"));
        }
        assertTrue(PaVoiceAssets.bundled(p -> null).isEmpty(), "a development build has no voicepack");
    }

    /** Listening's model and library come out of the jar (a fake one here): the archives checked, this computer's library and the model taken out. */
    @Test
    void speechModelFromAFakeJar(@TempDir Path game) throws Exception {
        String plat = PaVosk.platform(), lib = PaVosk.libName();
        java.io.ByteArrayOutputStream jarBytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(jarBytes)) {
            for (String n : new String[]{plat + "/" + lib, "other-os/libvosk.so", "org/vosk/LibVosk.class"}) { z.putNextEntry(new java.util.zip.ZipEntry(n)); z.write(n.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        }
        java.io.ByteArrayOutputStream modelBytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(modelBytes)) {
            for (String n : new String[]{PaVosk.MODEL + "/am/final.mdl", PaVosk.MODEL + "/conf/model.conf", "../escape.txt"}) { z.putNextEntry(new java.util.zip.ZipEntry(n)); z.write(1); z.closeEntry(); }
        }
        byte[] jb = jarBytes.toByteArray(), mb = modelBytes.toByteArray();
        Map<String, byte[]> jar = new HashMap<>();
        jar.put("/assets/mc_assistant/voicepack/stt/" + PaVosk.LIB_JAR, jb);
        jar.put("/assets/mc_assistant/voicepack/stt/" + PaVosk.MODEL_ZIP, mb);
        jar.put(PaVoiceAssets.MANIFEST, ("stt\t" + PaVosk.LIB_JAR + "\t" + jb.length + "\t" + sha(jb) + "\nstt\t" + PaVosk.MODEL_ZIP + "\t" + mb.length + "\t" + sha(mb) + "\n").getBytes(StandardCharsets.UTF_8));
        java.util.function.Function<String, InputStream> open = p -> jar.containsKey(p) ? new ByteArrayInputStream(jar.get(p)) : null;
        List<PaVoiceAssets.Entry> pack = PaVoiceAssets.bundled(open);
        Path home = PaVoiceAssets.home(game, "0.400.0", pack);
        PaVosk v = new PaVosk(home.resolve("stt"), PaVoiceAssets.parts(pack, "stt"));
        assertFalse(v.installed());
        assertTrue(v.unpack(PaVoiceAssets.fromJar(open), s -> { }), v.status());
        assertTrue(v.installed(), "model and library out");
        assertTrue(Files.isRegularFile(home.resolve("stt").resolve(lib)) && Files.isDirectory(v.modelDir().resolve("am")));
        assertFalse(Files.exists(home.resolve("stt/libvosk.so")) && !lib.equals("libvosk.so"), "only this computer's library");
        assertFalse(Files.exists(game.resolve("mc_assistant-cache/voice/escape.txt")) || Files.exists(home.resolve("escape.txt")), "a zip slip never written");
        assertTrue(v.unpack(PaVoiceAssets.fromJar(p -> null), s -> { }), "already there: nothing read again");
        // a tampered archive is refused
        jar.put("/assets/mc_assistant/voicepack/stt/" + PaVosk.MODEL_ZIP, "tampered".getBytes(StandardCharsets.UTF_8));
        PaVosk bad = new PaVosk(game.resolve("bad/stt"), PaVoiceAssets.parts(pack, "stt"));
        assertFalse(bad.unpack(PaVoiceAssets.fromJar(open), s -> { }));
        assertFalse(bad.installed());
    }

    /** Nothing in the mod fetches anything: no HTTP client left in the voice code (the build's download is in build.gradle only). */
    @Test
    void noNetworkInTheVoiceCode() throws Exception {
        for (String f : new String[]{"PaKokoro", "PaExpressive", "PaVosk", "PaVoiceAssets", "PaTts", "PaStt"}) {
            String src = Files.readString(Path.of("src/main/java/com/jrpetty/mcassistant/pa/voice/" + f + ".java"));
            assertFalse(src.contains("HttpClient") || src.contains("openConnection") || src.contains("HttpURLConnection"), f + " reaches the network");
        }
        for (String f : new String[]{"PaVoice", "PaVoiceExpressive"}) {
            String src = Files.readString(Path.of("src/main/java/com/jrpetty/mcassistant/client/pa/" + f + ".java"));
            assertFalse(src.contains("PaKokoro.HTTP") || src.contains("HttpClient"), f + " reaches the network");
        }
    }

    /** The folder older versions downloaded Kokoro into goes on first unpack, but only when it is plainly that folder. */
    @Test
    void oldKokoroFolder(@TempDir Path cfg) throws Exception {
        List<String> known = new ArrayList<>();
        for (PaKokoro.Part p : PaKokoro.PARTS) known.add(p.file());
        Path k = cfg.resolve("mc_assistant/voice/kokoro");
        Files.createDirectories(k);
        for (String n : new String[]{"kokoro-v1.0.int8.onnx", "voices-v1.0.bin", "gb_gold.json", "onnxruntime.jar.part", "verified.sha256"}) Files.write(k.resolve(n), new byte[3]);
        Path mine = cfg.resolve("mc_assistant/voice/kokoro2");
        Files.createDirectories(mine);
        Files.write(mine.resolve("kokoro-v1.0.int8.onnx"), new byte[3]);
        assertFalse(PaVoiceAssets.removeOldKokoro(mine, known), "only the folder named kokoro");
        Path other = cfg.resolve("x/kokoro");
        Files.createDirectories(other);
        Files.write(other.resolve("kokoro-v1.0.int8.onnx"), new byte[3]);
        Files.write(other.resolve("my-notes.txt"), new byte[3]);
        assertFalse(PaVoiceAssets.removeOldKokoro(other, known), "something not ours in it: left alone");
        assertTrue(Files.exists(other.resolve("my-notes.txt")));
        Path empty = cfg.resolve("y/kokoro");
        Files.createDirectories(empty);
        Files.write(empty.resolve("verified.sha256"), new byte[3]);
        assertFalse(PaVoiceAssets.removeOldKokoro(empty, known), "not the old layout (no model or voice pack)");
        assertTrue(PaVoiceAssets.removeOldKokoro(k, known), "the old download, all known files");
        assertFalse(Files.exists(k));
        assertTrue(Files.exists(cfg.resolve("mc_assistant/voice")), "nothing above it touched");
    }

    @Test
    void manifestRefusesPathTricks() {
        for (String bad : new String[]{"kokoro\t../evil.dll\t10\t" + "a".repeat(64), "kokoro\tx/y.onnx\t10\t" + "a".repeat(64), "kokoro\tm.onnx\t10\tnot-a-sum"}) {
            try {
                PaVoiceAssets.read(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8)));
                throw new AssertionError("accepted " + bad);
            } catch (java.io.IOException expected) {
                // refused
            }
        }
    }

    /** The build bundles exactly what the code expects: parts.tsv lists PaKokoro.PARTS and PaExpressive.PARTS, file for file. */
    @Test
    void bundledListMatchesTheCode() throws Exception {
        List<String> tsv = new ArrayList<>();
        try (InputStream in = PaVoiceDeliveryTest.class.getResourceAsStream("/assets/mc_assistant/voice/parts.tsv")) {
            assertNotNull(in, "parts.tsv ships with the sources");
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (l.isBlank() || l.startsWith("#")) continue;
                String[] f = l.strip().split("\t");
                assertTrue(f.length == 6 || f.length == 7 && f[6].startsWith("https://") && !f[6].equals(f[5]), "a source and at most one mirror: " + l);
                tsv.add(String.join("\t", java.util.Arrays.copyOf(f, 6)));
            }
        }
        List<String> code = new ArrayList<>();
        for (PaKokoro.Part p : PaKokoro.PARTS) code.add(String.join("\t", "kokoro", p.file(), Long.toString(p.bytes()), p.sha256(), p.licence(), p.url()));
        for (PaKokoro.Part p : PaExpressive.PARTS) code.add(String.join("\t", "expressive", p.file(), Long.toString(p.bytes()), p.sha256(), p.licence(), p.url()));
        for (PaKokoro.Part p : PaVosk.PARTS) code.add(String.join("\t", "stt", p.file(), Long.toString(p.bytes()), p.sha256(), p.licence(), p.url()));   // listening too
        assertEquals(code, tsv);
        long total = 0;
        for (String l : tsv) total += Long.parseLong(l.split("\t")[2]);
        assertTrue(total < 1_000_000_000L, "under a gigabyte before trimming: " + total);
        for (String l : tsv) {
            String url = l.split("\t")[5];
            assertTrue(url.startsWith("https://") && !url.contains("/main/") && !url.contains("/master/"), "pinned, never a moving branch: " + url);
        }
        // the build script's wiring: off unless asked for, the check after the jar
        String gradle = Files.readString(Path.of("build.gradle"));
        assertTrue(gradle.contains("project.findProperty('bundleVoice') in ['true', true]"));
        assertTrue(gradle.contains("tasks.register('voiceAssets')") && gradle.contains("tasks.register('checkVoiceBundle')"));
        assertTrue(gradle.contains("assets/mc_assistant/voicepack/manifest.tsv"));
        assertTrue(gradle.contains("['kokoro', 'expressive', 'stt']") && gradle.contains("n < 17"), "the jar check covers all three groups");
    }
}
