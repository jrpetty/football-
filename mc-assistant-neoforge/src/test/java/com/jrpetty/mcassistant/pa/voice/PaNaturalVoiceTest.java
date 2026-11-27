package com.jrpetty.mcassistant.pa.voice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [pa35] The natural voice on by default and never the robotic one: the engine chain (SAPI and macOS say only when the
 * player picks the basic system voice), the download (pinned, checked by SHA-256, resumed where it stopped, cancelled
 * cleanly, a bad file never used, the offline pack), the words made speakable, the phonemes from its own dictionaries,
 * and the sound's finishing (loudness to -16 LUFS, never clipping, no clicks), all on synthetic data: no network, no
 * native code.
 */
class PaNaturalVoiceTest {

    // ------------------------------------------------------------------ the engine chain

    private static final List<PaTts.VoiceInfo> WINDOWS = PaTts.parseWindows("sapi", "Microsoft Hazel Desktop~en-GB~Female|Microsoft David Desktop~en-US~Male");
    private static final List<PaTts.VoiceInfo> WIN11 = new ArrayList<>(PaTts.parseWindows("winrt", "Microsoft Hazel~en-GB~Female|Microsoft Sonia Online (Natural)~en-GB~Female"));

    @Test
    void neverTheBasicVoiceUnlessPicked() {
        for (PaTts.Kind k : PaTts.Kind.values()) {
            for (String key : new String[]{"", "sapi:Microsoft Hazel Desktop", "mac:Daniel", "espeak:en-gb", "winrt:Microsoft Hazel"}) {
                String e = PaTts.engineFor(key, false, false, false, WINDOWS, k);
                assertTrue(!e.equals("sapi") && !e.equals("mac") && !e.equals("espeak"), k + " " + key + " -> " + e);
                assertEquals("", e, "no natural voice and basic not picked: words only (" + k + ", " + key + ")");
            }
        }
        assertEquals("sapi", PaTts.engineFor("", true, false, false, WINDOWS, PaTts.Kind.WINDOWS), "picked: the basic voice");
        assertEquals("mac", PaTts.engineFor("", true, false, false, List.of(), PaTts.Kind.MAC));
        assertEquals("", PaTts.engineFor("", true, false, false, List.of(), PaTts.Kind.NONE));
    }

    @Test
    void naturalFirstInOrder() {
        assertEquals("kokoro", PaTts.engineFor("", false, true, true, WIN11, PaTts.Kind.WINDOWS));
        assertEquals("piper", PaTts.engineFor("", false, false, true, WIN11, PaTts.Kind.WINDOWS));
        assertEquals("winrt", PaTts.engineFor("", false, false, false, WIN11, PaTts.Kind.WINDOWS), "a Windows 11 Natural voice, if installed");
        assertEquals("", PaTts.engineFor("", false, false, false, PaTts.parseWindows("winrt", "Microsoft Hazel~en-GB~Female"), PaTts.Kind.WINDOWS),
            "a plain OneCore voice is not a natural one");
        assertEquals("kokoro", PaTts.engineFor("", true, true, false, WINDOWS, PaTts.Kind.WINDOWS), "basic is the fallback for no natural voice, not a replacement for one");
        assertEquals("sapi", PaTts.engineFor("sapi:Microsoft Hazel Desktop", true, true, false, WINDOWS, PaTts.Kind.WINDOWS), "unless a basic voice is picked by name");
        assertEquals("kokoro", PaTts.engineFor("kokoro:Emma", false, true, false, List.of(), PaTts.Kind.WINDOWS));
        assertTrue(WIN11.get(1).natural() && !WIN11.get(0).natural());
        assertEquals("Natural", new PaTts.VoiceInfo("kokoro", "George", "en-GB", "male", "natural").badge());
        assertEquals("Basic", WINDOWS.get(0).badge());
    }

    @Test
    void defaultVoicesByAccent() {
        assertEquals("bm_george", PaTts.kokoroVoice("", PaAccent.UK, false).id());
        assertEquals("bf_emma", PaTts.kokoroVoice("", PaAccent.UK, true).id());
        assertEquals("am_michael", PaTts.kokoroVoice("", PaAccent.US, false).id());
        assertEquals("af_heart", PaTts.kokoroVoice("", PaAccent.US, true).id());
        assertEquals("bm_fable", PaTts.kokoroVoice("kokoro:Fable", PaAccent.US, false).id());
        assertEquals(PaAccent.UK, PaAccent.resolve("auto", "en_gb", java.util.Locale.US), "British for a UK game language");
        assertEquals(PaAccent.UK, PaAccent.resolve("auto", "", java.util.Locale.UK), "British for a UK computer");
    }

    // ------------------------------------------------------------------ the download, mocked

    private static String sha(byte[] b) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        new java.util.Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void downloadsResumesChecksAndIsReady(@TempDir Path dir) throws Exception {
        byte[] a = bytes(300_000, 1), b = bytes(5_000, 2);
        List<PaKokoro.Part> parts = List.of(new PaKokoro.Part("model", "https://example.invalid/a.onnx", "a.onnx", a.length, sha(a), "test"),
            new PaKokoro.Part("words", "https://example.invalid/b.json", "b.json", b.length, sha(b), "test"));
        Map<String, byte[]> served = Map.of("a.onnx", a, "b.json", b);
        AtomicInteger calls = new AtomicInteger();
        List<Long> offsets = new ArrayList<>();
        // the first request for the big file drops the line half way; the next resumes with a Range request
        PaKokoro.Fetcher net = (uri, from) -> {
            calls.incrementAndGet();
            String name = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            byte[] all = served.get(name);
            offsets.add(from);
            if (name.equals("a.onnx") && from == 0 && offsets.stream().filter(x -> x == 0L).count() == 1) {
                return new PaKokoro.Fetcher.Reply(200, all.length, new InputStream() {
                    int i;
                    @Override public int read() throws IOException { if (i >= 120_000) throw new IOException("connection reset"); return all[i++] & 0xff; }
                });
            }
            byte[] rest = java.util.Arrays.copyOfRange(all, (int) from, all.length);
            return new PaKokoro.Fetcher.Reply(from > 0 ? 206 : 200, rest.length, new ByteArrayInputStream(rest));
        };
        PaKokoro k = new PaKokoro(dir, parts);
        assertFalse(k.downloaded());
        List<Double> seen = new ArrayList<>();
        assertTrue(k.install(net, (f, l) -> seen.add(f)), k.why());
        assertTrue(k.downloaded());
        assertTrue(offsets.stream().anyMatch(o -> o > 100_000), "resumed from where it dropped: " + offsets);
        assertTrue(!seen.isEmpty() && seen.get(seen.size() - 1) >= 0.999, "progress to the end: " + seen);
        for (int i = 1; i < seen.size(); i++) assertTrue(seen.get(i) >= seen.get(i - 1) - 1e-9, "progress never goes backwards");
        int before = calls.get();
        assertTrue(k.install(net, (f, l) -> { }));
        assertEquals(before, calls.get(), "already there and checked: nothing fetched again");
    }

    @Test
    void aBadFileIsNeverUsedAndCancelKeepsWhatCame(@TempDir Path dir) throws Exception {
        byte[] a = bytes(50_000, 3);
        List<PaKokoro.Part> parts = List.of(new PaKokoro.Part("model", "https://example.invalid/a.onnx", "a.onnx", a.length, sha(a), "test"));
        byte[] tampered = a.clone();
        tampered[100] ^= 1;
        PaKokoro k = new PaKokoro(dir, parts);
        assertFalse(k.install((uri, from) -> new PaKokoro.Fetcher.Reply(200, a.length, new ByteArrayInputStream(tampered)), (f, l) -> { }));
        assertTrue(k.why().contains("checksum"), k.why());
        assertFalse(Files.exists(dir.resolve("a.onnx")), "a file that failed its checksum is never put in place");
        assertFalse(k.downloaded());
        assertFalse(k.install((uri, from) -> new PaKokoro.Fetcher.Reply(404, 0, new ByteArrayInputStream(new byte[0])), (f, l) -> { }));
        assertTrue(k.why().contains("404"), k.why());
        // cancelled part way: stopped, the part kept for next time
        PaKokoro c = new PaKokoro(dir, parts);
        assertFalse(c.install((uri, from) -> new PaKokoro.Fetcher.Reply(200, a.length, new InputStream() {
            int i;
            @Override public int read() { if (i == 20_000) c.cancel(); return i < a.length ? a[i++] & 0xff : -1; }
            @Override public int read(byte[] b, int off, int len) { int n = 0; while (n < Math.min(len, 1024)) { int v = read(); if (v < 0) break; b[off + n++] = (byte) v; } return n == 0 ? -1 : n; }
        }), (f, l) -> { }));
        assertTrue(c.why().contains("paused"), c.why());
        assertTrue(Files.exists(dir.resolve("a.onnx.part")), "what came down is kept, to resume");
    }

    @Test
    void anOfflinePackInTheConfigFolderIsDetected(@TempDir Path dir) throws Exception {
        byte[] a = bytes(10_000, 4), b = bytes(700, 5);
        List<PaKokoro.Part> parts = List.of(new PaKokoro.Part("model", "x", "a.onnx", a.length, sha(a), "t"), new PaKokoro.Part("words", "x", "b.json", b.length, sha(b), "t"));
        Path config = Files.createDirectories(dir.resolve("config"));
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(config.resolve("mc_assistant-voice-pack.zip")))) {
            z.putNextEntry(new java.util.zip.ZipEntry("kokoro/a.onnx")); z.write(a); z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("kokoro/b.json")); z.write(b); z.closeEntry();
        }
        PaKokoro k = new PaKokoro(dir.resolve("kokoro"), parts);
        assertTrue(k.importPack(config), "unpacked and checked");
        assertTrue(k.downloaded());
    }

    // ------------------------------------------------------------------ the words

    @Test
    void wordsAreMadeSpeakable() {
        assertEquals("minus one twenty, sixty-four", PaSpeech.numbers("-120, 64"));
        assertEquals("twelve hundred", PaSpeech.spoken(1200));
        assertEquals("one hundred and five", PaSpeech.spoken(105));
        assertEquals("three thousand four hundred and twelve", PaSpeech.spoken(3412));
        assertEquals("twenty-one thousand", PaSpeech.spoken(21000));
        assertEquals("first", PaSpeech.ordinal(1));
        assertEquals("twenty-second", PaSpeech.ordinal(22));
        assertEquals("two point five", PaSpeech.numbers("2.5"));
        String s = PaSpeech.speakable("[anim:shrug] Iron's at L5, e.g. 50% of the way 😀 **there**.", Double.NaN, Double.NaN);
        assertEquals("Iron's at level five, for example fifty percent of the way there.", s);
        assertFalse(PaSpeech.speakable("§cRed§r text <b>bold</b>", Double.NaN, Double.NaN).contains("§"));
        assertTrue(PaSpeech.speakable("at 30 64 -30", 0, 0).endsWith("blocks north-east"), "coordinates as a distance and direction");
    }

    private static PaG2p tiny(boolean british) throws Exception {
        String gold = "{\"red\": \"ɹˈɛd\", \"stone\": \"stˈQn\", \"cat\": \"kˈat\", \"bus\": \"bˈʌs\", \"walk\": \"wˈɔːk\", \"wait\": \"wˈAt\","
            + " \"make\": \"mˈAk\", \"the\": \"ðə\", \"read\": {\"DEFAULT\": \"ɹˈiːd\", \"VBD\": \"ɹˈɛd\"}, \"A\": \"ˈA\", \"I\": \"ˈI\", \"apple\": \"ˈapᵊl\"}";
        Map<String, String> g = PaG2p.read(new ByteArrayInputStream(gold.getBytes(StandardCharsets.UTF_8)));
        return new PaG2p(g, Map.of(), british, null);
    }

    @Test
    void phonemesFromItsOwnDictionaries() throws Exception {
        PaG2p g = tiny(true);
        assertEquals("ɹˈiːd", g.word("read", null), "a word with several readings keeps its usual one");
        assertEquals("kˈats", g.word("cats", null), "a plural after a voiceless sound: s");
        assertEquals("bˈʌsɪz", g.word("buses", null), "after a hiss: iz");
        assertEquals("wˈɔːkt", g.word("walked", null));
        assertEquals("wˈAtɪd", g.word("waited", null));
        assertEquals("mˈAkɪŋ", g.word("making", null), "the silent e put back for the stem");
        assertEquals("ɹˈɛdstQn", g.word("redstone", null), "the game's lexicon");
        assertEquals("ɹˈɛdkˌat", g.word("redcat", null), "a compound of two known words");
        assertEquals("ði ˈapᵊl", g.phonemize("the apple"), "'the' before a vowel");
        assertEquals("ðə kˈat.", g.phonemize("The cat."));
        assertEquals("mˈInkɹɑːft", g.word("Minecraft", null), "the game's own words");
        assertTrue(g.word("Zorblax", null).contains("ˈ"), "an unknown name still gets a stress");
        assertTrue(PaG2p.rules("bake", true).contains("A"), "a silent e lengthens the vowel");
        assertEquals("I", PaG2p.fromEspeak("aɪ", true));
        assertEquals("Q", PaG2p.fromEspeak("əʊ", true));
    }

    // ------------------------------------------------------------------ the sound

    private static float[] sine(double hz, double amp, double seconds) {
        int n = (int) (24000 * seconds);
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / 24000.0));
        return a;
    }

    @Test
    void loudnessIsMeasuredTheBroadcastWay() {
        // BS.1770: a full-scale 997 Hz sine reads -3.01 LUFS
        assertEquals(-3.01, PaAudio.lufs(sine(997, 1.0, 3), 24000), 0.15);
        assertEquals(-23.01, PaAudio.lufs(sine(997, 0.1, 3), 24000), 0.15);
        assertEquals(-70, PaAudio.lufs(new float[24000], 24000), 1e-9, "silence is gated out");
    }

    @Test
    void eachLineIsLevelledNeverClippedAndClickFree() {
        for (double amp : new double[]{0.02, 0.2, 0.9}) {
            float[] speechy = sine(220, amp, 2);
            for (int i = 0; i < speechy.length; i++) speechy[i] *= (float) (0.6 + 0.4 * Math.sin(2 * Math.PI * 3 * i / 24000.0));   // a syllable rhythm
            float[] done = PaAudio.finish(speechy, 24000, 1.0);
            double l = PaAudio.lufs(done, 24000);
            assertEquals(PaAudio.TARGET_LUFS, l, 1.0, "levelled to -16 LUFS from amplitude " + amp + ": " + l);
            assertTrue(PaAudio.peak(done) <= Math.pow(10, -1.0 / 20) + 1e-6, "never over -1 dBFS: " + PaAudio.peak(done));
            assertTrue(Math.abs(done[0]) < 1e-3 && Math.abs(done[done.length - 1]) < 1e-3, "faded in and out: no click");
        }
        float[] loud = PaAudio.limit(new float[]{2f, -3f, 0.5f}, -1.0);
        for (float v : loud) assertTrue(Math.abs(v) < Math.pow(10, -1.0 / 20), "the limiter never lets a sample through over the ceiling: " + v);
        assertEquals(0.5f, loud[2], 1e-6, "under the knee: untouched");
    }

    @Test
    void trimPitchAndPcm() {
        float[] padded = new float[24000 * 2];
        float[] tone = sine(440, 0.5, 0.5);
        System.arraycopy(tone, 0, padded, 12000, tone.length);
        float[] t = PaAudio.trim(padded, 24000, 25);
        assertTrue(t.length < tone.length + 24000 * 0.06 && t.length >= tone.length, "silence off the ends: " + t.length);
        assertEquals(100, PaAudio.resample(new float[103], 1.02).length, 1);
        assertTrue(PaAudio.resample(sine(440, 0.5, 1), 1.03).length < 24000, "pitched up: shorter, quicker");
        assertTrue(PaAudio.pitchFactor(1, 0, 0) < 1 && PaAudio.pitchFactor(0, 1, 0) > 1, "gloomy lower, the joker brighter");
        byte[] pcm = PaAudio.pcm16(new float[]{1f, -1f}, 100);
        assertEquals(0x7f, pcm[1] & 0xff);
        assertEquals(24000, PaKokoro.SAMPLE_RATE, "full 24 kHz throughout");
        assertTrue(PaTts.chunks("a ".repeat(400)).stream().allMatch(c -> c.length() <= 480), "long phonemes cut to what the model takes");
    }

    @Test
    void onByDefaultAndFetchedOnceByItself() {
        assertTrue(PaVoiceDefaults.SPEAK, "spoken replies on by default");
        assertEquals("auto", PaVoiceDefaults.NATURAL, "the natural voice fetched by itself");
        assertFalse(PaVoiceDefaults.BASIC, "the robotic system voice off unless picked");
        assertTrue(PaVoiceDefaults.autoDownload(true, false, "auto", false, false), "first assistant this session: fetch it");
        assertFalse(PaVoiceDefaults.autoDownload(false, false, "auto", false, false), "no assistant: nothing");
        assertFalse(PaVoiceDefaults.autoDownload(true, true, "auto", false, false), "once a session");
        assertFalse(PaVoiceDefaults.autoDownload(true, false, "declined", false, false), "\"don't download\" is kept");
        assertFalse(PaVoiceDefaults.autoDownload(true, false, "auto", true, false), "already there");
        assertFalse(PaVoiceDefaults.autoDownload(true, false, "auto", false, true), "already coming");
    }
}
