package com.jrpetty.mcassistant.pa.voice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [voice37] The expressive voice without its model: the fallback chain (expressive, then natural, the basic voice only
 * when picked), tier selection (Auto by the benchmark, its guess from the natural voice's timing), the personality's
 * sounds (Dry understated, Daft bigger, Sincere warm, Serious straight, never a sound on a warning), the downloader with
 * a fake network, the tokeniser, the voice files, the sampler, the line cache, and the shared normaliser.
 */
class PaExpressiveVoiceTest {

    // ------------------------------------------------------------------ the fallback chain and the tiers

    @Test
    void fallbackChain() {
        PaVoiceTier.Setting auto = PaVoiceTier.Setting.AUTO;
        assertEquals(List.of("expressive", "kokoro"), PaVoiceTier.chain(auto, true, false, 0.6, true, false), "Auto on a computer that keeps up");
        assertEquals(List.of("kokoro"), PaVoiceTier.chain(auto, true, false, 1.8, true, false), "Auto on a slow computer: the natural voice");
        assertEquals(List.of("kokoro"), PaVoiceTier.chain(auto, true, false, PaVoiceTier.UNKNOWN, true, false), "not timed yet: the natural voice");
        assertEquals(List.of("kokoro"), PaVoiceTier.chain(auto, true, true, 0.6, true, false), "it failed this session: the natural voice at once");
        assertEquals(List.of("kokoro"), PaVoiceTier.chain(auto, false, false, 0.6, true, false), "not downloaded");
        assertEquals(List.of("expressive", "kokoro"), PaVoiceTier.chain(PaVoiceTier.Setting.EXPRESSIVE, true, false, 3.0, true, false), "picked: used even when slow");
        assertEquals(List.of("kokoro"), PaVoiceTier.chain(PaVoiceTier.Setting.NATURAL, true, false, 0.3, true, false));
        assertEquals(List.of("basic"), PaVoiceTier.chain(PaVoiceTier.Setting.BASIC, true, false, 0.3, true, false), "Basic only when the player picks it");
        assertEquals(List.of(), PaVoiceTier.chain(auto, false, false, -1, false, false), "nothing ready: words only, never the robotic voice");
        assertEquals(List.of("basic"), PaVoiceTier.chain(auto, false, false, -1, false, true), "the basic voice as the player's own fallback");
        for (PaVoiceTier.Setting s : PaVoiceTier.Setting.values())
            if (s != PaVoiceTier.Setting.BASIC) assertFalse(PaVoiceTier.chain(s, true, false, 0.5, true, false).contains("basic"), s + " never says it robotically");
    }

    @Test
    void tierSelection() {
        assertEquals(PaVoiceTier.Setting.AUTO, PaVoiceTier.Setting.of(null));
        assertEquals(PaVoiceTier.Setting.AUTO, PaVoiceTier.Setting.of("nonsense"));
        assertEquals(PaVoiceTier.Setting.EXPRESSIVE, PaVoiceTier.Setting.of("Expressive"));
        assertEquals(PaVoiceTier.Setting.EXPRESSIVE, PaVoiceTier.Setting.AUTO.next());
        assertEquals(PaVoiceTier.Setting.AUTO, PaVoiceTier.Setting.BASIC.next(), "the button goes round");
        assertEquals("natural", PaVoiceTier.Setting.NATURAL.key());
        // the guess from the natural voice's timing, then the real benchmark
        assertTrue(PaVoiceTier.offerExpressive(PaVoiceTier.Setting.AUTO, 0.2, PaVoiceTier.UNKNOWN), "a quick computer is offered it");
        assertFalse(PaVoiceTier.offerExpressive(PaVoiceTier.Setting.AUTO, 0.9, PaVoiceTier.UNKNOWN), "a slow one isn't");
        assertFalse(PaVoiceTier.offerExpressive(PaVoiceTier.Setting.AUTO, PaVoiceTier.UNKNOWN, PaVoiceTier.UNKNOWN), "not timed: no guess, no download");
        assertFalse(PaVoiceTier.offerExpressive(PaVoiceTier.Setting.AUTO, 0.2, 2.0), "the real benchmark wins over the guess");
        assertTrue(PaVoiceTier.offerExpressive(PaVoiceTier.Setting.EXPRESSIVE, 0.9, 2.0), "picked by the player");
        assertFalse(PaVoiceTier.offerExpressive(PaVoiceTier.Setting.NATURAL, 0.1, 0.1));
        assertEquals(0.6, PaVoiceTier.predict(0.2), 1e-9);
        // fetched by itself only with an assistant, not declined, the natural voice there, and quick enough
        assertTrue(PaVoiceTier.autoDownload(true, false, "auto", PaVoiceTier.Setting.AUTO, true, false, false, 0.2, -1));
        assertFalse(PaVoiceTier.autoDownload(false, false, "auto", PaVoiceTier.Setting.AUTO, true, false, false, 0.2, -1), "no assistant");
        assertFalse(PaVoiceTier.autoDownload(true, true, "auto", PaVoiceTier.Setting.AUTO, true, false, false, 0.2, -1), "once");
        assertFalse(PaVoiceTier.autoDownload(true, false, "declined", PaVoiceTier.Setting.AUTO, true, false, false, 0.2, -1), "one-click opt-out kept");
        assertFalse(PaVoiceTier.autoDownload(true, false, "auto", PaVoiceTier.Setting.AUTO, false, false, false, 0.2, -1), "natural voice first");
        assertFalse(PaVoiceTier.autoDownload(true, false, "auto", PaVoiceTier.Setting.AUTO, true, true, false, 0.2, -1), "already there");
        assertFalse(PaVoiceTier.autoDownload(true, false, "auto", PaVoiceTier.Setting.AUTO, true, false, true, 0.2, -1), "already coming");
        assertFalse(PaVoiceTier.autoDownload(true, false, "auto", PaVoiceTier.Setting.AUTO, true, false, false, 0.8, -1), "a weak computer keeps Kokoro");
        assertTrue(PaVoiceTier.verdict(0.5, -1).contains("keeps up"));
        assertTrue(PaVoiceTier.verdict(2.5, -1).contains("natural voice"));
        assertEquals("Expressive", new PaTts.VoiceInfo("expressive", "Harriet", "en-GB", "female", "expressive").badge());
        assertEquals("Expressive", new PaTts.VoiceInfo("expressive", "Harriet", "en-GB", "female", "expressive").group());
    }

    @Test
    void namedVoicesAreNeverExpressive() {
        PaTts t = PaTts.none("test");
        t.expressive(Path.of("does-not-exist-expressive"), Path.of("does-not-exist-cache"));
        t.prefs(() -> new PaTts.Prefs(PaVoiceTier.Setting.EXPRESSIVE, 0, 50, 0.5));
        assertFalse(t.expressiveReady(), "nothing downloaded");
        assertFalse(t.expressiveFirst("", false), "not ready: the natural voice");
        assertFalse(t.expressiveFirst("kokoro:George", false), "a natural voice picked by name");
        assertFalse(t.expressiveFirst("", true), "the basic voice picked");
    }

    // ------------------------------------------------------------------ the personality's sounds

    static final String JOKE = "What's a skeleton's least favourite room? The living room. Sorry. I've had that one since the world was flat.";
    static final String ROAST = "I'd roast your build, but I'd have to find it first. Is that a house, or did a creeper get there before you?";

    static int tags(String s) {
        int n = 0;
        for (String t : PaVoiceStyle.TAGS) for (int i = s.indexOf(t); i >= 0; i = s.indexOf(t, i + 1)) n++;
        return n;
    }

    @Test
    void dryIsUnderstated() {
        PaVoiceStyle.Style s = PaVoiceStyle.perform(JOKE, PaVoiceStyle.Kind.JOKE, 0.1, 0.6, PaVoiceStyle.DRY, 50, false);
        assertEquals("What's a skeleton's least favourite room? The living room. [chuckle] Sorry. I've had that one since the world was flat.", s.text(),
            "a chuckle, after the punchline (the answer, not the question)");
        for (PaVoiceStyle.Kind k : PaVoiceStyle.Kind.values())
            for (int e = 0; e <= 100; e += 25)
                assertTrue(tags(PaVoiceStyle.perform(JOKE, k, 0.6, 0.6, PaVoiceStyle.DRY, e, false).text()) <= 1, "Dry: one sound at most (" + k + ", " + e + ")");
        assertFalse(PaVoiceStyle.perform(JOKE, PaVoiceStyle.Kind.JOKE, 0.1, 0.6, PaVoiceStyle.DRY, 100, false).text().contains("[laugh]"), "Dry never laughs out loud");
        assertEquals(0, tags(PaVoiceStyle.perform("Lava. It's orange for a reason.", PaVoiceStyle.Kind.DEATH, 0.1, 0.5, PaVoiceStyle.DRY, 50, false).text()),
            "a death, deadpan");
    }

    @Test
    void daftIsBiggerSincereIsWarm() {
        String daft = PaVoiceStyle.perform(JOKE, PaVoiceStyle.Kind.JOKE, 0.1, 0.7, PaVoiceStyle.DAFT, 60, false).text();
        assertTrue(daft.contains("The living room. [laugh]"), daft);
        String roast = PaVoiceStyle.perform(ROAST, PaVoiceStyle.Kind.ROAST, 0.1, 0.7, PaVoiceStyle.DAFT, 60, false).text();
        assertTrue(roast.startsWith("I'd roast your build, but I'd have to find it first. [laugh] Is that"), roast);
        assertEquals(0, tags(PaVoiceStyle.perform(ROAST, PaVoiceStyle.Kind.ROAST, 0.1, 0.7, PaVoiceStyle.SINCERE, 80, false).text()), "Sincere doesn't mock");
        assertTrue(PaVoiceStyle.perform("You fell. Are you all right?", PaVoiceStyle.Kind.DEATH, 0.1, 0.4, PaVoiceStyle.SINCERE, 60, false).text().startsWith("[gasp]"),
            "Sincere: concern at a death");
        float dry = PaVoiceStyle.perform(JOKE, PaVoiceStyle.Kind.JOKE, 0.1, 0.7, PaVoiceStyle.DRY, 60, false).temperature();
        float big = PaVoiceStyle.perform(JOKE, PaVoiceStyle.Kind.JOKE, 0.1, 0.7, PaVoiceStyle.DAFT, 60, false).temperature();
        assertTrue(big > dry, "Daft reads more freely");
        for (int e = 0; e <= 100; e += 10)
            assertTrue(tags(PaVoiceStyle.perform(JOKE + " " + ROAST, PaVoiceStyle.Kind.JOKE, 0.9, 0.9, PaVoiceStyle.DAFT, e, true).text()) <= 2, "Daft: two at most");
    }

    @Test
    void gloomSighsWarningsNever() {
        String g = PaVoiceStyle.perform("Oh. You're back. Nothing's improved while you were away. I checked.", PaVoiceStyle.Kind.GREETING, 0.8, 0.1, PaVoiceStyle.DRY, 50, true).text();
        assertTrue(g.startsWith("[sigh] Oh."), g);
        for (int h = 0; h < 3; h++)
            for (int e = 0; e <= 100; e += 20) {
                PaVoiceStyle.Style w = PaVoiceStyle.perform("Stop. There's a creeper right behind you.", PaVoiceStyle.Kind.WARNING, 0.9, 0.9, h, e, true);
                assertEquals("Stop. There's a creeper right behind you.", w.text(), "never a sound on a warning");
                assertTrue(w.temperature() <= 0.8f, "a warning read steadily");
            }
        assertEquals(0, tags(PaVoiceStyle.perform(JOKE, PaVoiceStyle.Kind.JOKE, 0.05, 0.1, PaVoiceStyle.DRY, 50, false).text()), "Serious says it straight");
        assertEquals(JOKE, PaVoiceStyle.perform(JOKE, PaVoiceStyle.Kind.JOKE, 0.5, 0.9, PaVoiceStyle.DAFT, 0, true).text(), "Expression 0: the words only");
        assertEquals(PaVoiceStyle.Kind.JOKE, PaVoiceStyle.kindOf("joke"));
        assertEquals(PaVoiceStyle.Kind.DEATH, PaVoiceStyle.kindOf("death.lava@gloomy"));
        assertEquals(PaVoiceStyle.Kind.ROAST, PaVoiceStyle.kindOf("stranger.roast.clean"));
        assertEquals(PaVoiceStyle.Kind.WARNING, PaVoiceStyle.kindOf("quip.low_health"));
        assertEquals(PaVoiceStyle.Kind.AFFECTION, PaVoiceStyle.kindOf("say.bond.gift@serious"));
        assertEquals(PaVoiceStyle.Kind.LAUGH, PaVoiceStyle.kindOf("comeback.laugh"));
        assertEquals(PaVoiceStyle.Kind.GREETING, PaVoiceStyle.kindOf("say.greet@gloomy"));
        assertEquals(PaVoiceStyle.Kind.PLAIN, PaVoiceStyle.kindOf(null));
        assertEquals(PaVoiceStyle.Kind.WARNING, PaVoiceStyle.guess("Careful, there's lava under you."));
    }

    @Test
    void linesFoundInTheBanks() {
        assertEquals("death.lava", PaVoiceStyle.keyOf("Lava. It's orange for a reason."));
        String roast = PaVoiceStyle.keyOf("Alex, I'd roast your build, but I'd have to find it first.");
        assertNotNull(roast, "a line with a {player} in it");
        assertEquals(PaVoiceStyle.Kind.ROAST, PaVoiceStyle.kindOf(roast));
        assertEquals(PaVoiceStyle.Kind.JOKE, PaVoiceStyle.kindOf(PaVoiceStyle.keyOf("What's a skeleton's least favourite room. The living room.")));
        assertNull(PaVoiceStyle.keyOf("Something nobody ever wrote down in a phrase bank, surely."));
    }

    @Test
    void piecesAndTheSharedNormaliser() {
        assertEquals(List.of("Kept these for you. Don't make it weird. [chuckle]"), PaVoiceStyle.chunks("Kept these for you. Don't make it weird. [chuckle]"),
            "short sentences joined; a sound on its own goes with the sentence before");
        List<String> p = PaVoiceStyle.chunks("[sigh] Oh. You're back. Nothing's improved while you were away, not the weather, not the creepers, not the dirt hut. I checked. Twice. "
            + "Then I checked again, because there was nothing else to do around here.");
        assertTrue(p.size() >= 2 && p.get(0).length() < p.get(1).length(), "a short first piece, then fuller ones: " + p);
        assertEquals(String.join(" ", p).replaceAll("\\s+", " "), ("[sigh] Oh. You're back. Nothing's improved while you were away, not the weather, not the creepers, not the dirt hut. I checked. Twice. "
            + "Then I checked again, because there was nothing else to do around here."), "nothing lost or reordered");
        // the same words for both voices: the normaliser's output, performed, then stripped, is what Kokoro says
        String said = PaSpeech.speakable("[anim:shrug] Lava. *It's* orange for a reason. Your things are at -142 63 801.", 0, 0);
        String performed = PaVoiceStyle.perform(said, PaVoiceStyle.Kind.DEATH, 0.8, 0.1, PaVoiceStyle.DRY, 60, false).text();
        assertTrue(performed.startsWith("[sigh] "), performed);
        assertFalse(performed.contains("anim") || performed.contains("*") || performed.contains("-142"), performed);
        assertEquals(said.replaceAll("\\s+", " ").trim(), PaVoiceStyle.strip(performed), "the fallback says exactly the same words");
    }

    // ------------------------------------------------------------------ the download, with a fake network

    private static String sha(byte[] b) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }

    @Test
    void downloaderWithAFakeFetch(@TempDir Path dir) throws Exception {
        byte[] a = new byte[300_000], b = new byte[70_000];
        new Random(1).nextBytes(a);
        new Random(2).nextBytes(b);
        Map<String, byte[]> served = new LinkedHashMap<>();
        served.put("https://example.invalid/lm.onnx_data", a);
        served.put("https://example.invalid/tokenizer.json", b);
        List<PaKokoro.Part> parts = List.of(
            new PaKokoro.Part("speech model", "https://example.invalid/lm.onnx_data", "lm.onnx_data", a.length, sha(a), "MIT"),
            new PaKokoro.Part("words", "https://example.invalid/tokenizer.json", "tokenizer.json", b.length, sha(b), "MIT"));
        AtomicInteger calls = new AtomicInteger();
        List<Long> offsets = new ArrayList<>();
        int[] cutAfter = {120_000};
        PaKokoro.Fetcher net = (uri, from) -> {
            calls.incrementAndGet();
            offsets.add(from);
            byte[] all = served.get(uri.toString());
            byte[] rest = java.util.Arrays.copyOfRange(all, (int) from, all.length);
            if (cutAfter[0] > 0 && uri.toString().endsWith("onnx_data")) {          // the line drops part-way, once
                int n = cutAfter[0];
                cutAfter[0] = 0;
                return new PaKokoro.Fetcher.Reply(from > 0 ? 206 : 200, rest.length, new InputStream() {
                    int i;
                    @Override public int read() throws java.io.IOException { if (i >= n) throw new java.io.IOException("connection reset"); return rest[i++] & 0xff; }
                });
            }
            return new PaKokoro.Fetcher.Reply(from > 0 ? 206 : 200, rest.length, new ByteArrayInputStream(rest));
        };
        PaExpressive e = new PaExpressive(dir, parts);
        assertFalse(e.downloaded());
        List<Double> progress = new ArrayList<>();
        assertTrue(e.files().install(net, (f, l) -> progress.add(f)), e.files().why());
        assertTrue(e.downloaded());
        assertTrue(offsets.contains(120_000L), "resumed from where the line dropped: " + offsets);
        assertEquals(1.0, progress.get(progress.size() - 1), 1e-9);
        assertArrayEquals(a, Files.readAllBytes(dir.resolve("lm.onnx_data")));
        // a file that fails its checksum is never used, and the message names the expressive voice
        Path dir2 = dir.resolve("again");
        PaExpressive bad = new PaExpressive(dir2, List.of(new PaKokoro.Part("words", "https://example.invalid/tokenizer.json", "tokenizer.json", b.length, sha(a), "MIT")));
        assertFalse(bad.files().install(net, (f, l) -> { }));
        assertFalse(bad.downloaded());
        assertFalse(Files.exists(dir2.resolve("tokenizer.json")));
        assertTrue(bad.files().why().contains("expressive voice") && bad.files().why().contains("checksum"), bad.files().why());
        assertTrue(PaExpressive.totalMb() > 500 && PaExpressive.totalMb() < 650, PaExpressive.totalMb() + " MB said before the player agrees");
        for (PaKokoro.Part p : PaExpressive.PARTS) {
            assertTrue(p.url().startsWith(PaExpressive.REPO) && PaExpressive.REPO.matches(".*/resolve/[0-9a-f]{40}/"), "pinned to one revision: " + p.url());
            assertTrue(p.sha256().matches("[0-9a-f]{64}") && p.licence().startsWith("MIT"));
        }
    }

    // ------------------------------------------------------------------ the tokeniser, the voices, the sampler

    @Test
    void tokeniserSmall() throws Exception {
        String json = "{\"added_tokens\":[{\"id\":9,\"content\":\"<|endoftext|>\"},{\"id\":10,\"content\":\"[laugh]\"}],"
            + "\"model\":{\"type\":\"BPE\",\"vocab\":{\"h\":0,\"i\":1,\"\\u0120\":2,\"hi\":3,\"\\u0120hi\":4,\"!\":5,\"o\":6},"
            + "\"merges\":[[\"h\",\"i\"],[\"\\u0120\",\"hi\"]]}}";
        PaBpe b = PaBpe.read(new ByteArrayInputStream(padVocab(json).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertArrayEquals(new long[]{3, 4, 5, 9, 9}, b.encode("hi hi!"), "merged by rank, a space as Ġ, two end tokens");
        assertArrayEquals(new long[]{3, 10, 4, 9, 9}, b.encode("hi[laugh] hi"), "a tag is one token, matched whole");
        assertTrue(b.isTag("[laugh]") && !b.isTag("<|endoftext|>"));
        assertEquals('\u0120', PaBpe.byteChars()[' '], "GPT-2's byte map: a space is Ġ");
    }

    /** The reader insists on a real vocabulary: pad the toy one past 256 entries. */
    private static String padVocab(String json) {
        StringBuilder extra = new StringBuilder();
        for (int i = 0; i < 260; i++) extra.append(",\"x").append(i).append("\":").append(100 + i);
        return json.replace("\"o\":6}", "\"o\":6" + extra + "}");
    }

    @Test
    void tokeniserMatchesTheReference() throws Exception {
        String path = System.getProperty("mca.test.tokenizer", System.getenv().getOrDefault("MCA_TEST_TOKENIZER", ""));
        org.junit.jupiter.api.Assumptions.assumeTrue(!path.isEmpty() && Files.isRegularFile(Path.of(path)), "the real tokenizer.json isn't here (set MCA_TEST_TOKENIZER)");
        PaBpe b;
        try (InputStream in = Files.newInputStream(Path.of(path))) { b = PaBpe.read(in); }
        // ids from the reference (Hugging Face tokenizers) for the model's tokenizer.json
        assertArrayEquals(new long[]{15496, 612, 13, 50256, 50256}, b.encode("Hello there."));
        assertArrayEquals(new long[]{2061, 338, 257, 18328, 338, 1551, 12507, 2119, 30, 383, 2877, 2119, 13, 220, 50275, 19061, 13, 50256, 50256},
            b.encode("What's a skeleton's least favourite room? The living room. [laugh] Sorry."));
        assertArrayEquals(new long[]{50268, 3966, 13, 921, 821, 736, 13, 50256, 50256}, b.encode("[sigh] Oh. You're back."));
        assertArrayEquals(new long[]{40191, 25181, 11, 220, 8093, 290, 42151, 851, 12876, 0, 50256, 50256}, b.encode("minus 142,  63 and Caf\u00e9 \u2014 ok!"));
        assertArrayEquals(new long[]{220, 9029, 220, 220, 994, 198, 50256, 50256}, b.encode("  spaces   here\n"));
    }

    @Test
    void voiceFiles() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write("MCAV".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        o.write(le(1));
        array(o, 0, new int[]{1, 2, 3}, new double[]{0.5, -1, 2, 0, 0.25, 3});
        array(o, 1, new int[]{1, 3}, new double[]{25, 6537, 4299});
        array(o, 0, new int[]{1, 2}, new double[]{1, -1});
        array(o, 0, new int[]{1, 2, 2}, new double[]{-11.5, 1.5, 0, 0});
        PaExpressive.Cond c = PaExpressive.readCond(new ByteArrayInputStream(o.toByteArray()));
        assertArrayEquals(new float[]{0.5f, -1, 2, 0, 0.25f, 3}, c.emb());
        assertArrayEquals(new long[]{1, 2, 3}, c.embShape());
        assertArrayEquals(new long[]{25, 6537, 4299}, c.tokens());
        assertArrayEquals(new long[]{1, 2, 2}, c.featShape());
        for (PaExpressive.Voice v : PaExpressive.VOICES) {
            try (InputStream in = PaExpressive.class.getResourceAsStream("/assets/mc_assistant/voice/expressive/" + v.id() + ".mcav")) {
                assertNotNull(in, "the voice " + v.label() + " ships in the jar");
                PaExpressive.Cond real = PaExpressive.readCond(in);
                assertEquals(1024, real.embShape()[2]);
                assertEquals(192, real.spk().length);
                assertEquals(80, real.featShape()[2]);
                for (long t : real.tokens()) assertTrue(t >= 0 && t < 6561, "speech tokens only");
            }
        }
        long men = PaExpressive.VOICES.stream().filter(v -> !v.female()).count(), women = PaExpressive.VOICES.stream().filter(PaExpressive.Voice::female).count();
        assertTrue(men >= 2 && women >= 2, "two men's and two women's voices");
        assertEquals("Harriet", PaExpressive.pick("", true).label());
        assertEquals("Arthur", PaExpressive.pick("", false).label());
        assertEquals("Elsie", PaExpressive.pick("expressive:Elsie", false).label(), "picked by name");
        assertTrue(PaExpressive.ATTRIBUTION.contains("CC BY 4.0") && PaExpressive.ATTRIBUTION.contains("MIT"));
    }

    private static byte[] le(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static void array(ByteArrayOutputStream o, int kind, int[] shape, double[] values) throws Exception {
        o.write(kind);
        o.write(shape.length);
        for (int d : shape) o.write(le(d));
        for (double v : values) {
            short s = kind == 0 ? Float.floatToFloat16((float) v) : (short) v;
            o.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(s).array());
        }
    }

    @Test
    void sampler() {
        float[] logits = new float[50];
        logits[7] = 10;
        logits[3] = 9.5f;
        assertEquals(7, PaExpressive.sample(logits, List.of(6561), 0f, new Random(1)), "greedy at no temperature");
        assertEquals(3, PaExpressive.sample(logits, List.of(7, 7, 7), 0f, new Random(1)), "said already: the repetition penalty moves it on");
        int[] seen = new int[50];
        Random r = new Random(5);
        for (int i = 0; i < 400; i++) seen[PaExpressive.sample(logits, List.of(), 0.8f, r)]++;
        assertTrue(seen[7] > seen[3] && seen[3] > 0, "sampled, the likelier more often");
        int others = 0;
        for (int i = 0; i < 50; i++) if (i != 7 && i != 3) others += seen[i];
        assertEquals(0, others, "outside the top 95%: never");
        assertEquals(PaExpressive.sample(logits, List.of(), 0.8f, new Random(9)), PaExpressive.sample(logits, List.of(), 0.8f, new Random(9)), "a seed repeats");
        assertTrue(PaExpressive.maxTokens(10) >= 200 && PaExpressive.maxTokens(10_000) == 1000 && PaExpressive.maxTokens(0) >= 75);
        assertEquals(3, PaOrt.threads(4), "leaves the game a core");
        assertEquals(6, PaOrt.threads(8));
        assertEquals(1, PaOrt.threads(1));
    }

    // ------------------------------------------------------------------ the line cache

    @Test
    void lineCache(@TempDir Path dir) throws Exception {
        PaLineCache c = new PaLineCache(dir, 40_000);
        float[] a = new float[8000];
        for (int i = 0; i < a.length; i++) a[i] = (float) Math.sin(i / 10.0) * 0.5f;
        assertNull(c.get("gb_m_p226", "Right then.", 0.7f));
        c.put("gb_m_p226", "Right then.", 0.7f, a);
        float[] back = c.get("gb_m_p226", "Right then.", 0.7f);
        assertNotNull(back);
        assertEquals(a.length, back.length);
        for (int i = 0; i < a.length; i += 97) assertEquals(a[i], back[i], 1.0 / 16000);
        assertNull(c.get("gb_f_p228", "Right then.", 0.7f), "another voice is another line");
        assertNull(c.get("gb_m_p226", "Right then. [chuckle]", 0.7f), "another performance too");
        c.put("gb_m_p226", "x".repeat(PaLineCache.MAX_CHARS + 1), 0.7f, a);
        assertNull(c.get("gb_m_p226", "x".repeat(PaLineCache.MAX_CHARS + 1), 0.7f), "long lines aren't kept");
        Files.setLastModifiedTime(dir.resolve(PaLineCache.key("gb_m_p226", "Right then.", 0.7f) + ".pcm"), java.nio.file.attribute.FileTime.fromMillis(1000));
        c.put("gb_m_p226", "On my way.", 0.7f, a);
        c.put("gb_m_p226", "Done.", 0.7f, a);
        assertNull(c.get("gb_m_p226", "Right then.", 0.7f), "over the limit: the least recently used went");
        assertNotNull(c.get("gb_m_p226", "Done.", 0.7f));
    }
}
