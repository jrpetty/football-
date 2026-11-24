package com.jrpetty.mcassistant.pa.voice;

import com.jrpetty.mcassistant.pa.PaUnderstand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33] The voice path: recognised words to orders, and every backend failing gracefully when it is not there. */
class PaVoiceTest {

    /** What recognisers hand back (lower case, no punctuation, numbers as words, a wake word sometimes), and the order. */
    static final String[][] HEARD = {
        {"follow me", "FOLLOW"}, {"hey pip follow me", "FOLLOW"}, {"pip come here", "COME"}, {"stay here", "STAY"},
        {"get me sixteen logs", "GATHER:logs:16"}, {"gather thirty two stone", "GATHER:stone:32"}, {"chop sixty four logs", "GATHER:logs:64"},
        {"mine iron until you have twenty", "MINE_ORE:iron:20"}, {"mine for diamonds", "MINE_ORE:diamond"},
        {"build me a small house here", "BUILD:house"}, {"build a shelter here", "BUILD:shelter"}, {"build a farm here", "FARM"},
        {"craft a stone pickaxe", "CRAFT:stone pickaxe"}, {"make sixteen torches", "CRAFT:torches:16"}, {"smelt eight iron", "SMELT:iron:8"},
        {"put everything in that chest", "LOGI"}, {"bring me food", "BRING_FOOD"}, {"i am hungry", "BRING_FOOD"},
        {"guard me", "GUARD_ME"}, {"attack that", "FIGHT"}, {"kill the zombies", "FIGHT"}, {"farm this field", "FARM"},
        {"stop", "STOP"}, {"undo that", "UNDO"}, {"pause", "PAUSE"}, {"carry on", "RESUME"}, {"go home", "GO_HOME"},
        {"what are you doing", "DOING"}, {"how are you", "STATUS"}, {"light up the area", "LIGHT_AREA"},
        {"sort the chests", "LOGI"}, {"catch five fish", "FISH:fish:5"}, {"hunt three cows", "HUNT:cows:3"},
        {"dig a mine down to level twelve", "MINE_DOWN"}, {"how do i start a business", "QUESTION"},
    };

    @Test
    void heardWordsBecomeOrders() {
        int ok = 0;
        StringBuilder miss = new StringBuilder();
        for (String[] h : HEARD) {
            // both readings agree here; choose() must keep an order that reads
            String text = PaStt.choose(h[0], h[0]);
            PaUnderstand.Reading r = PaUnderstand.read(text, "pip");
            if (r.understood() && r.brief().startsWith(h[1])) ok++;
            else miss.append(h[0]).append(" -> ").append(r.brief()).append("; ");
        }
        System.out.printf("[pa33] voice text to order: %d/%d%n", ok, HEARD.length);
        assertTrue(ok >= HEARD.length - 1, "voice text to order: " + ok + "/" + HEARD.length + " " + miss);
    }

    /** [pa33b] What a recogniser hears from a British speaker: the slips on British vowels, and British phrasings. */
    static final String[][] BRITISH_HEARD = {
        {"get me sixteen lugs", "GATHER:logs:16"}, {"chop thirty two lags", "GATHER:logs:32"}, {"fallow me", "FOLLOW"},
        {"fellow me mate", "FOLLOW"}, {"gather sixteen stun", "GATHER:stone:16"}, {"mine ion until you have ten", "MINE_ORE:iron:10"},
        {"mind iron", "MINE_ORE:iron"}, {"god me", "GUARD_ME"}, {"gourd me", "GUARD_ME"}, {"fam this field", "FARM"},
        {"build a fam here", "FARM"}, {"put everything in the chess", "LOGI"}, {"get some would", "GATHER:logs"},
        {"stay hair", "STAY"}, {"come hair", "COME"}, {"fetch us some lugs", "GATHER:logs"}, {"bung it in the chess", "LOGI"},
        {"nip over here", "COME"}, {"cheers mate", "THANKS"}, {"ta", "THANKS"}, {"hang on a sec", "PAUSE"}, {"crack on", "RESUME"},
        {"right fallow me", "FOLLOW"}, {"have a go at mining ion", "MINE_ORE:iron"}, {"go and get sixteen lugs", "GATHER:logs:16"},
        {"pop it in the chest", "LOGI"}, {"what are you up to", "DOING"}, {"you alright", "STATUS"}, {"pack it in", "STOP"},
        {"grab us thirty two stun", "GATHER:stone:32"},
    };

    @Test
    void britishSpeechBecomesOrders() {
        int ok = 0;
        StringBuilder miss = new StringBuilder();
        for (String[] h : BRITISH_HEARD) {
            String text = com.jrpetty.mcassistant.pa.PaWords.voiceFix(PaStt.choose(h[0], h[0]));
            PaUnderstand.Reading r = PaUnderstand.read(text, "pip");
            if (r.understood() && r.brief().startsWith(h[1])) ok++;
            else miss.append(h[0]).append(" -> ").append(r.brief()).append("; ");
        }
        System.out.printf("[pa33b] British speech to order: %d/%d%n", ok, BRITISH_HEARD.length);
        assertTrue(ok >= BRITISH_HEARD.length - 1, "British speech to order: " + ok + "/" + BRITISH_HEARD.length + " " + miss);
    }

    @Test
    void misHearsOnlyWhereSafe() {
        // typed words are left alone; a real word in another sense is not turned into an order
        assertEquals("thanks fam", com.jrpetty.mcassistant.pa.PaWords.voiceFix("thanks fam"));
        assertEquals("i would like that", com.jrpetty.mcassistant.pa.PaWords.voiceFix("i would like that"));
        assertEquals("oh my god", com.jrpetty.mcassistant.pa.PaWords.voiceFix("oh my god"));
        assertEquals("mind the lava", com.jrpetty.mcassistant.pa.PaWords.voiceFix("mind the lava"));
    }

    @Test
    void accentFollowsTheSettingTheGameAndTheComputer() {
        assertEquals(PaAccent.UK, PaAccent.resolve("auto", "en_gb", java.util.Locale.US));
        assertEquals(PaAccent.UK, PaAccent.resolve("auto", "en_us", java.util.Locale.UK));
        assertEquals(PaAccent.US, PaAccent.resolve("auto", "en_us", java.util.Locale.US));
        assertEquals(PaAccent.UK, PaAccent.resolve("uk", "en_us", java.util.Locale.US));
        assertEquals(PaAccent.US, PaAccent.resolve("us", "en_gb", java.util.Locale.UK));
        assertEquals("uk", PaAccent.next("auto"));
        assertEquals("auto", PaAccent.next("us"));
    }

    @Test
    void aBritishVoiceWhenThereIsOne() {
        assertEquals("Microsoft Hazel Desktop", PaAccent.preferredVoice(PaAccent.UK, List.of("Microsoft David Desktop", "Microsoft Hazel Desktop", "Microsoft Zira Desktop")));
        assertEquals("Daniel", PaAccent.preferredVoice(PaAccent.UK, List.of("Default", "Alex", "Daniel", "Samantha")));
        assertEquals(null, PaAccent.preferredVoice(PaAccent.UK, List.of("Default", "Microsoft David Desktop")));
        assertEquals("en-gb", PaAccent.UK.espeakVoice());
    }

    @Test
    void windowsSaysWhichRecogniserAndHowToAddTheUkOne() {
        String withUk = PaAccent.windowsAdvice(PaAccent.UK, "en-GB", List.of("en-GB", "en-US"));
        assertTrue(withUk.contains("United Kingdom") && !withUk.contains("add"), withUk);
        String noUk = PaAccent.windowsAdvice(PaAccent.UK, "en-US", List.of("en-US"));
        assertTrue(noUk.contains("United States") && noUk.contains("Time & language > Speech") && noUk.contains("English (United Kingdom)"), noUk);
        String none = PaAccent.windowsAdvice(PaAccent.UK, "", List.of());
        assertTrue(none.contains("no speech recogniser"), none);
        // the script asks for en-GB first, then en-US, then any English
        String ps = PaStt.recognizerFor(PaAccent.UK);
        assertTrue(ps.indexOf("'en-GB'") < ps.indexOf("'en-US'") && ps.contains("InstalledRecognizers()"), ps);
    }

    @Test
    void theGrammarHasTheBritishSayings() {
        assertTrue(PaUnderstand.grammar().contains("bung it in the chest") && PaStt.words().contains("cheers") && PaStt.words().contains("us"));
    }

    @Test
    void chooseKeepsTheReadingThatMakesAnOrder() {
        assertEquals("get me sixteen logs", PaStt.choose("get me sixteen logs", "get me sixty logs dog"));
        assertEquals("how do i start a business", PaStt.choose("how do i start a [unk]", "how do i start a business"));
        assertEquals("follow me", PaStt.choose("", "follow me"));
        assertEquals("", PaStt.choose("", ""));
    }

    @Test
    void ttsWithNothingInstalledIsSilentAndSaysSo() {
        assertEquals(PaTts.Kind.NONE, PaTts.pick("Linux", p -> false));
        assertEquals(PaTts.Kind.NONE, PaTts.pick("Windows 11", p -> false));
        assertEquals(PaTts.Kind.WINDOWS, PaTts.pick("Windows 11", p -> p.equals("powershell")));
        assertEquals(PaTts.Kind.MAC, PaTts.pick("Mac OS X", p -> p.equals("say")));
        assertEquals(PaTts.Kind.ESPEAK, PaTts.pick("Linux", p -> p.equals("espeak")));
        PaTts none = PaTts.none("nothing here");
        assertFalse(none.available());
        assertDoesNotThrow(() -> none.say("hello", 0, 0, 80));
        assertDoesNotThrow(() -> PaTts.create().status());
        assertEquals("Hello there", PaTts.clean("Hello 🎤 there"));
    }

    @Test
    void sttWithNoModelSaysSoAndNeverThrows(@TempDir Path dir) throws Exception {
        PaStt stt = new PaStt(dir, "auto", "Linux", p -> false);
        assertEquals(PaStt.Engine.NONE, stt.engine());
        assertFalse(stt.ready());
        assertTrue(stt.status().contains("model"), stt.status());
        assertDoesNotThrow(stt::begin);
        assertDoesNotThrow(() -> stt.feed(new byte[3200]));
        PaStt.Heard h = stt.end(new byte[32000]).get(10, TimeUnit.SECONDS);
        assertTrue(h.text().isEmpty() && !h.error().isEmpty(), "an empty hearing, with a reason: " + h);
        PaStt.Heard tooShort = stt.end(new byte[100]).get(10, TimeUnit.SECONDS);
        assertTrue(tooShort.error().contains("short"), tooShort.error());
        PaVosk v = new PaVosk(dir.resolve("vosk"));
        assertFalse(v.installed());
        assertFalse(v.load());
        assertTrue(v.status().contains("downloaded"), v.status());
        assertTrue(v.session(List.of("follow")) == null);
    }

    @Test
    void voskPieces() {
        String g = PaVosk.grammar(List.of("follow", "me", "Bad!", "get"));
        assertEquals("[\"follow\",\"me\",\"get\",\"[unk]\"]", g);
        assertEquals("follow me", PaVosk.field("{\n  \"text\" : \"follow me\"\n}", "text"));
        assertEquals("get me", PaVosk.field("{ \"partial\" : \"get me\" }", "partial"));
        assertTrue(PaStt.words().contains("pickaxe") && PaStt.words().contains("sixteen"));
    }

    @Test
    void wavRoundTrip() {
        byte[] pcm = new byte[640];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        byte[] wav = PaWav.wav(pcm, 16000);
        assertEquals(44 + 640, wav.length);
        assertEquals(640, PaWav.pcm(wav).length);
        assertEquals(pcm[100], PaWav.pcm(wav)[100]);
    }

    @Test
    void micWithoutAMicrophoneFailsGracefully() {
        PaMic mic = new PaMic();
        boolean started = assertDoesNotThrow(() -> mic.start(null));
        if (!started) assertFalse(mic.error().isEmpty());
        assertDoesNotThrow(mic::stop);
        assertTrue(PaMic.rms(new byte[]{0, 0, 0, 0}, 4) == 0F);
    }

    // ------------------------------------------------------------------ [pa33b] natural voices

    @Test
    void linesAreShapedToBeSpoken() {
        String s = PaSpeech.speakable("Your things are at 140 64 -30. They'll vanish in about five minutes.", 100, 0);
        assertTrue(s.contains("about fifty blocks north-east") && !s.contains("140"), s);
        assertTrue(PaSpeech.speakable("Heading there.  \u00D72", Double.NaN, Double.NaN).equals("Heading there."));
        assertEquals("Watch the nether-ite and the ender man", PaSpeech.speakable("Watch the netherite and the Enderman", 0, 0).replace("Watch the nether-ite and the ender man", "Watch the nether-ite and the ender man"));
        assertTrue(PaSpeech.speakable("Hello Dev_123", 0, 0).contains("Dev one twenty-three"), "[pa35] its digits said as a number");
        assertEquals(List.of("One.", "Two?", "Three."), PaSpeech.sentences("One. Two? Three."));
    }

    @Test
    void ssmlCarriesTheMood() {
        String gloomy = PaSpeech.ssml("Oh good. More logs.", "en-GB", 1.0, 0.0, 0, 0);
        assertTrue(gloomy.contains("xml:lang=\"en-GB\"") && gloomy.contains("rate=\"-22%\"") && gloomy.contains("pitch=\"-14%\""), gloomy);
        String joke = PaSpeech.ssml("Why did the creeper cross the road. To get to the other ssssside.", "en-GB", 0, 1, 0, 0);
        assertTrue(joke.contains("<break time=\"600ms\"/>To get"), joke);
        assertTrue(PaSpeech.ssml("Fish & chips <now>", "en-US", 0, 0, 0, 0).contains("Fish &amp; chips &lt;now&gt;"));
        assertTrue(PaSpeech.ssml("Well... fine.", "en-GB", 0, 0, 0, 0).contains("<break time=\"500ms\"/>"));
    }

    @Test
    void voiceListsAreRead() {
        List<PaTts.VoiceInfo> w = PaTts.parseWindows("winrt", "Microsoft Hazel~en-GB~Female|Microsoft George~en-GB~Male|Microsoft David~en-US~Male");
        assertEquals(3, w.size());
        assertEquals("Microsoft Hazel  \u00B7  female, British", w.get(0).label());
        assertEquals("Windows (modern)", w.get(0).group());
        List<PaTts.VoiceInfo> m = PaTts.parseMac(List.of("Daniel (Enhanced)  en_GB    # Hello! My name is Daniel.", "Alex                en_US    # Most people recognize me by my voice.",
            "Thomas              fr_FR    # Bonjour", "Kate (Premium)      en_GB    # Hello"));
        assertEquals(3, m.size());
        assertEquals("Enhanced", m.get(0).quality());
        List<PaTts.VoiceInfo> e = PaTts.parseEspeak(List.of("Pty Language       Age/Gender VoiceName          File                 Other Languages",
            " 2  en-gb           --/M      English_(Great_Britain) gmw/en               (en 2)"));
        assertEquals("en-gb", e.get(0).name());
        assertTrue(PaTts.parseWindows("sapi", "").isEmpty());
    }

    @Test
    void piperIsDesignedButNeverRequired(@TempDir Path dir) {
        assertEquals("piper_windows_amd64.zip", PaPiper.archiveFor("Windows 11", "amd64"));
        assertEquals("piper_macos_aarch64.tar.gz", PaPiper.archiveFor("Mac OS X", "aarch64"));
        assertEquals("piper_linux_x86_64.tar.gz", PaPiper.archiveFor("Linux", "amd64"));
        PaPiper p = new PaPiper(dir);
        PaPiper.Voice alan = PaPiper.byId("en_GB-alan-medium");
        assertFalse(p.installed("Linux", alan), "nothing installed: nothing claimed");
        assertTrue(p.installedVoices("Linux").isEmpty());
        assertTrue(alan.modelUrl().endsWith("/en/en_GB/alan/medium/en_GB-alan-medium.onnx"), alan.modelUrl());
        List<String> cmd = p.command("Linux", alan, dir.resolve("o.wav").toFile(), PaPiper.lengthScale(1.0, 0));
        assertTrue(cmd.contains("--model") && cmd.contains("1.25"), cmd.toString());
        long british = PaPiper.CATALOGUE.stream().filter(v -> v.id().startsWith("en_GB") && v.offered()).count();
        assertTrue(british >= 4, "British voices first: " + british);
    }

    @Test
    void everyEngineFailsGracefully() {
        PaTts none = PaTts.none("nothing here");
        assertDoesNotThrow(() -> none.speak("Hello there", new PaTts.Opts("winrt:Nobody", 0, 0, 80, 0.5, 0.5, PaAccent.UK, 0, 0)));
        assertDoesNotThrow(() -> none.speak("", new PaTts.Opts("", 0, 0, 80, 0, 0, PaAccent.US, Double.NaN, Double.NaN)));
        assertTrue(none.catalogue().isEmpty());
        assertEquals("Cloud voice: off (the default).", PaTts.cloudStatus(false, "abc"));
        assertTrue(PaTts.cloudStatus(true, "").contains("nothing is sent"));
        assertTrue(PaTts.cloudStatus(true, "key").contains("nothing is sent"));
    }

    // ------------------------------------------------------------------ [pa33b] Kokoro, the natural voices (no download, no native code here)

    @Test
    void kokoroCatalogueAndLookPicks() {
        long british = PaKokoro.CATALOGUE.stream().filter(v -> v.lang().equals("en-GB")).count();
        assertEquals(8, british, "George, Lewis, Daniel, Fable, Emma, Isabella, Alice, Lily");
        for (String id : new String[]{"bm_george", "bm_lewis", "bm_daniel", "bm_fable", "bf_emma", "bf_isabella", "bf_alice", "bf_lily"})
            assertTrue(PaKokoro.byId(id) != null, id);
        assertEquals("George", PaKokoro.forLook(4, PaAccent.UK, false).label());      // knight
        assertEquals("Fable", PaKokoro.forLook(8, PaAccent.UK, false).label());       // butler
        assertEquals("Daniel", PaKokoro.forLook(14, PaAccent.UK, false).label());     // skeleton
        String f = PaKokoro.forLook(0, PaAccent.UK, true).label();
        assertTrue(f.equals("Emma") || f.equals("Isabella"), f);
        assertEquals("Isabella", PaKokoro.forLook(8, PaAccent.UK, true).label());
        assertEquals("en-US", PaKokoro.forLook(4, PaAccent.US, false).lang());
        for (int look = 0; look < 18; look++) for (boolean fem : new boolean[]{false, true}) {
            PaKokoro.Voice v = PaKokoro.forLook(look, PaAccent.UK, fem);
            assertTrue(v != null && v.lang().equals("en-GB") && v.gender().equals(fem ? "female" : "male"), look + " " + fem);
        }
    }

    @Test
    void kokoroDownloadIsSaidAndCheckedAndNothingIsThereYet(@TempDir Path dir) {
        assertTrue(PaKokoro.totalMb() > 200 && PaKokoro.totalMb() < 230, PaKokoro.totalMb() + " MB");
        for (PaKokoro.Part p : PaKokoro.PARTS) {
            assertTrue(p.url().startsWith("https://github.com/") || p.url().startsWith("https://raw.githubusercontent.com/")
                || p.url().startsWith("https://repo1.maven.org/"), p.url());
            assertFalse(p.licence().isEmpty());
        }
        PaKokoro k = new PaKokoro(dir);
        assertFalse(k.downloaded());
        assertFalse(k.load());
        assertEquals("The natural voices aren't downloaded.", k.why());
        for (PaKokoro.Part p : PaKokoro.PARTS) assertTrue(p.sha256().matches("[0-9a-f]{64}") && p.bytes() > 0, "[pa35] every file checked by size and SHA-256: " + p.file());
        assertTrue(PaKokoro.PARTS.stream().noneMatch(p -> p.url().contains("/main/") || p.url().contains("/master/")), "[pa35] pinned, never a moving branch");
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> k.synth("h\u0259lo\u028a", PaKokoro.byId("bm_george"), 1f)).getMessage().contains("downloaded"));
    }

    @Test
    void kokoroFindsEspeakAndSaysHowToGetIt() {
        assertEquals("espeak-ng", PaKokoro.espeak("Linux", c -> c.equals("espeak-ng"), p -> false));
        assertEquals("C:\\Program Files\\eSpeak NG\\espeak-ng.exe",
            PaKokoro.espeak("Windows 11", c -> false, p -> p.toString().startsWith("C:\\Program Files\\eSpeak")));
        assertEquals(null, PaKokoro.espeak("Windows 11", c -> false, p -> false));
        assertTrue(PaKokoro.espeakAdvice("Windows 11").contains("espeak-ng-X64.msi"));
        assertTrue(PaKokoro.espeakAdvice("Mac OS X").contains("brew install espeak-ng"));
        assertTrue(PaKokoro.espeakAdvice("Linux").contains("espeak-ng"));
        assertEquals(List.of("espeak-ng", "-q", "--ipa", "-b", "1", "-v", "en-gb", "hello there"), PaKokoro.phonemeCommand("espeak-ng", PaAccent.UK, "hello there"));
        assertEquals("en-us", PaKokoro.phonemeCommand("espeak-ng", PaAccent.US, "hi").get(6));
        assertEquals("h\u0259l\u02c8\u0259\u028a \u00f0\u02c8e\u0259", PaKokoro.cleanPhonemes(" h\u0259l\u02c8\u0259\u028a_\n\u00f0\u02c8e\u0259\n"));
    }

    @Test
    void kokoroTokensStyleSpeedAndSound() throws Exception {
        String json = "{\"n_token\": 178, \"vocab\": {\";\": 1, \",\": 3, \".\": 4, \" \": 16, \"\\u0303\": 17, \"h\": 50, \"l\": 54, "
            + "\"\u0259\": 83, \"\u028a\": 135, \"\u02c8\": 156, \"\\\"\": 11}}";
        java.util.Map<Integer, Integer> v = PaKokoro.vocab(json);
        assertEquals(11, v.size(), v.toString());
        assertEquals(17, v.get(0x0303));
        assertEquals(11, v.get((int) '"'));
        long[] t = PaKokoro.tokens("h\u0259l\u02c8\u0259\u028a, Q", v);   // Q isn't known: dropped
        assertEquals("[50, 83, 54, 156, 83, 135, 3, 16]", java.util.Arrays.toString(t));
        assertEquals("[0, 50, 83, 54, 156, 83, 135, 3, 16, 0]", java.util.Arrays.toString(PaKokoro.padded(t)));
        assertEquals(PaKokoro.MAX_TOKENS, PaKokoro.tokens("h".repeat(900), v).length);
        List<String[]> c = PaKokoro.clauses("Right. Fifty blocks north-east, by the lava... Lovely!");
        assertEquals(4, c.size());
        assertEquals("Right", c.get(0)[0]);
        assertEquals(".", c.get(0)[1]);
        assertEquals("Fifty blocks north-east", c.get(1)[0]);
        assertEquals(",", c.get(1)[1]);
        assertTrue(PaKokoro.speed(1, 0) < PaKokoro.speed(0, 0), "gloomy is slower");
        assertTrue(PaKokoro.speed(0, 5) > 1.2f && PaKokoro.speed(1, -5) >= 0.6f);
        byte[] pcm = PaKokoro.pcm16(new float[]{0f, 1f, -1f, 2f}, 100);
        assertEquals(8, pcm.length);
        assertEquals(32767, (short) ((pcm[3] << 8) | (pcm[2] & 0xff)));
        assertEquals(-32767, (short) ((pcm[5] << 8) | (pcm[4] & 0xff)));
        assertEquals(32767, (short) ((pcm[7] << 8) | (pcm[6] & 0xff)), "clipped, not wrapped");
        assertEquals(0, PaKokoro.pcm16(new float[]{0.5f}, 0)[1]);
    }

    @Test
    void kokoroReadsAVoicePack() throws Exception {
        // a voice pack as kokoro-onnx publishes it: an .npz (zip) of float32 .npy tables shaped (510, 1, 256); here (3, 1, 4)
        java.io.ByteArrayOutputStream zip = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(zip)) {
            for (String name : new String[]{"bm_george", "bf_emma"}) {
                z.putNextEntry(new java.util.zip.ZipEntry(name + ".npy"));
                z.write(npy(new int[]{3, 1, 4}, name.equals("bm_george") ? 0f : 100f));
                z.closeEntry();
            }
        }
        java.util.Map<String, float[][]> voices = PaKokoro.readVoices(new java.io.ByteArrayInputStream(zip.toByteArray()));
        assertEquals(java.util.Set.of("bm_george", "bf_emma"), voices.keySet());
        float[][] g = voices.get("bm_george");
        assertEquals(3, g.length);
        assertEquals(4, g[0].length);
        assertEquals(5f, g[1][1]);
        assertEquals(105f, voices.get("bf_emma")[1][1]);
        assertEquals(g[1], PaKokoro.style(g, 2), "[pa35] n tokens take row n - 1, as Kokoro does");
        assertEquals(g[2], PaKokoro.style(g, 999), "a long sentence takes the last row");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> PaKokoro.npy(new byte[20]));
    }

    /** A little-endian float32 .npy (v1.0) holding base + 0, 1, 2, ... */
    private static byte[] npy(int[] shape, float base) {
        StringBuilder sh = new StringBuilder();
        int n = 1;
        for (int d : shape) { sh.append(d).append(", "); n *= d; }
        String header = "{'descr': '<f4', 'fortran_order': False, 'shape': (" + sh.toString().replaceAll(", $", shape.length == 1 ? "," : "") + "), }";
        while ((10 + header.length() + 1) % 64 != 0) header += " ";
        header += "\n";
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(10 + header.length() + n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x93).put("NUMPY".getBytes()).put((byte) 1).put((byte) 0).putShort((short) header.length()).put(header.getBytes());
        for (int i = 0; i < n; i++) b.putFloat(base + i);
        return b.array();
    }
}
