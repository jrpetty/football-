package com.jrpetty.mcassistant.pa.voice;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [voice42] Kokoro never plays clicks or broken silence. ONNX Runtime 1.21 to 1.24 work out Kokoro int8's convolutions
 * wrongly on CPUs with Intel AMX, so many lines came out as NaN, huge samples, silence or clicks; the mod now runs 1.25.1
 * (1.23.2 only on Intel Macs, which have no AMX), and checks every piece before playing it (PaAudio.fault, PaKokoro.speak).
 * The check is tested on made-up samples; the real voice over the lines that broke runs only where the model is
 * (MCA_TEST_KOKORO: a folder with kokoro-v1.0.int8.onnx, voices-v1.0.bin, config.json, gb_gold.json, gb_silver.json and
 * onnxruntime.jar), skipped otherwise.
 */
class PaKokoroCleanTest {

    static final int RATE = PaKokoro.SAMPLE_RATE;

    /** Something shaped like speech: voiced bursts (a 140 Hz buzz with harmonics) with short pauses, peaking near 0.5. */
    static float[] speechLike(double seconds, long seed) {
        Random r = new Random(seed);
        float[] a = new float[(int) (seconds * RATE)];
        for (int i = 0; i < a.length; i++) {
            double t = i / (double) RATE, syllable = 0.5 + 0.5 * Math.sin(2 * Math.PI * 4 * t);
            double buzz = 0;
            for (int h = 1; h <= 6; h++) buzz += Math.sin(2 * Math.PI * 140 * h * t) / h;
            a[i] = (float) (0.22 * syllable * buzz + 0.01 * r.nextGaussian());
        }
        return a;
    }

    @Test
    void speechPasses() {
        for (long seed = 1; seed <= 5; seed++) assertNull(PaAudio.fault(speechLike(0.6 + seed * 0.4, seed), RATE, 12), "seed " + seed);
        assertNull(PaAudio.fault(new float[0], RATE, 0), "nothing asked, nothing made");
    }

    @Test
    void brokenOutputIsCaught() {
        float[] nan = speechLike(1.2, 7);
        nan[nan.length - 30] = Float.NaN;
        assertEquals("non-finite samples", PaAudio.fault(nan, RATE, 10));
        float[] inf = speechLike(1.2, 8);
        inf[100] = Float.POSITIVE_INFINITY;
        assertEquals("non-finite samples", PaAudio.fault(inf, RATE, 10));
        float[] huge = speechLike(1.2, 9);
        huge[5000] = 5.1f;                                           // what 1.23.2 gave for "gather a hundred stone..."
        assertTrue(PaAudio.fault(huge, RATE, 10).startsWith("samples far too loud"));
        float[] enormous = speechLike(1.2, 10);
        for (int i = 0; i < enormous.length; i++) enormous[i] *= 1e6f;
        assertTrue(PaAudio.fault(enormous, RATE, 10).startsWith("samples far too loud"));
        assertEquals("near-silence", PaAudio.fault(new float[RATE], RATE, 10));
        float[] hiss = new float[RATE];
        Random r = new Random(3);
        for (int i = 0; i < hiss.length; i++) hiss[i] = (float) (0.003 * r.nextGaussian());
        assertEquals("near-silence", PaAudio.fault(hiss, RATE, 10));
        float[] click = hiss.clone();
        for (int i = 0; i < click.length; i++) click[i] *= 6;        // quiet sound with a lone spike on it
        click[12_000] = 0.9f;
        assertEquals("a spike, not speech", PaAudio.fault(click, RATE, 10));
        float[] burst = new float[2 * RATE];                          // two seconds that are mostly silence
        System.arraycopy(speechLike(0.25, 4), 0, burst, RATE, (int) (0.25 * RATE));
        assertEquals("mostly silence", PaAudio.fault(burst, RATE, 10));
        assertEquals("far too short", PaAudio.fault(speechLike(0.1, 5), RATE, 60));
        assertEquals("no sound", PaAudio.fault(new float[0], RATE, 5));
    }

    @Test
    void theRetrySplitsAtTheMiddleWord() {
        assertArrayEquals(new String[]{"ɡˈæðəɹ ə", "hˈʌndɹəd stˈəʊn"}, PaKokoro.halves("ɡˈæðəɹ ə hˈʌndɹəd stˈəʊn"));
        assertArrayEquals(new String[]{"fˈɒləʊ", "mˈiː"}, PaKokoro.halves(" fˈɒləʊ mˈiː "));
        assertNull(PaKokoro.halves("stˈɒp"), "a single word can't be split");
        assertNull(PaKokoro.halves(""));
    }

    @Test
    void eachComputerLoadsItsOwnRuntime() {
        assertEquals(PaOrt.JAR_INTEL_MAC, PaOrt.jarName("Mac OS X", "x86_64"));
        assertEquals(PaOrt.JAR, PaOrt.jarName("Mac OS X", "aarch64"));
        assertEquals(PaOrt.JAR, PaOrt.jarName("Windows 11", "amd64"));
        assertEquals(PaOrt.JAR, PaOrt.jarName("Linux", "amd64"));
        assertEquals(PaOrt.JAR, PaOrt.jarName("Linux", "aarch64"));
        assertEquals(PaOrt.JAR, PaOrt.jarName(null, null));
        List<String> files = new ArrayList<>();
        for (PaKokoro.Part p : PaKokoro.PARTS) files.add(p.file());
        assertTrue(files.contains(PaOrt.JAR) && files.contains(PaOrt.JAR_INTEL_MAC), "both runtimes ship: " + files);
        assertTrue(PaKokoro.ORT_URL.contains("/1.25.1/") && PaKokoro.ORT_INTEL_MAC_URL.contains("/1.23.2/"));
    }

    // ------------------------------------------------------------------ the real voice (gated)

    static final String[] VOICES = {"bm_george", "bm_lewis", "bf_emma", "bf_isabella"};

    @Test
    void theLinesThatBrokeAreSpeechNow() throws Exception {
        String kd = System.getenv("MCA_TEST_KOKORO");
        Assumptions.assumeTrue(kd != null && Files.isRegularFile(Path.of(kd).resolve(PaKokoro.MODEL_FILE)),
            "the Kokoro model isn't here (MCA_TEST_KOKORO): skipped");
        Path dir = Path.of(kd);
        List<PaKokoro.Part> parts = new ArrayList<>();
        for (String f : new String[]{PaKokoro.MODEL_FILE, PaKokoro.VOICES_FILE, PaKokoro.CONFIG_FILE, "gb_gold.json", "gb_silver.json", PaOrt.jarName(System.getProperty("os.name"), System.getProperty("os.arch"))})
            parts.add(new PaKokoro.Part(f, "", f, Files.size(dir.resolve(f)), "local", "test"));
        PaKokoro k = new PaKokoro(dir, parts);
        Files.writeString(dir.resolve(PaKokoro.VERIFIED), k.marker());
        assertTrue(k.load(), "Kokoro loads: " + k.why());
        PaG2p g2p = k.g2p(PaAccent.UK, null);
        List<String> lines = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of("src/test/resources/pa/kokoro_bad_lines.txt"), StandardCharsets.UTF_8)) if (!l.isBlank() && !l.startsWith("#")) lines.add(l.trim());
        assertTrue(lines.size() >= 40, "forty or more lines: " + lines.size());
        List<String> broken = new ArrayList<>();
        int pieces = 0, retried = 0;
        for (String voice : VOICES) {
            PaKokoro.Voice v = PaKokoro.byId(voice);
            for (String line : lines) {
                for (String sentence : PaSpeech.sentences(line)) {
                    for (String chunk : PaTts.chunks(g2p.phonemize(sentence))) {
                        pieces++;
                        PaKokoro.Said said = k.speak(chunk, v, 1.0f);           // the way the mod says it: checked, retried once
                        if (said.fault() != null) broken.add(voice + " \"" + sentence + "\": " + said.fault());
                        assertNotNull(said.samples(), voice + " \"" + sentence + "\" isn't speech even after the retry: " + said.fault());
                        if (said.retried()) retried++;
                    }
                }
            }
        }
        System.out.printf("[voice42] Kokoro: %d pieces in %d voices, %d not speech at the first try, %d retried%n", pieces, VOICES.length, broken.size(), retried);
        assertTrue(broken.isEmpty(), broken.size() + " of " + pieces + " pieces weren't speech:\n  " + String.join("\n  ", broken));
    }
}
