package com.jrpetty.mcassistant.pa.voice;

import com.jrpetty.mcassistant.pa.PaMemoryParse;
import com.jrpetty.mcassistant.pa.PaQuips;
import com.jrpetty.mcassistant.pa.PaUnderstand;
import com.jrpetty.mcassistant.pa.PaWords;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [pa38] Voice orders end to end, with no microphone: each order is spoken in British voices by the mod's own Kokoro
 * (PaKokoro, in process, through its own British dictionaries), finished as the mod finishes speech (PaAudio), brought
 * down to 16 kHz, heard by Vosk exactly as the mod hears (PaVosk: one recogniser held to the mod's grammar, PaStt.words(),
 * and one free; PaStt.choose keeps the reading that makes an order), put right as the server puts spoken words right
 * (PaWords.voiceFix), and read as the server reads it (the hush words, the memory reader, then PaUnderstand). It measures
 * how much is heard word for word and how much reads as the right order.
 *
 * <p>Runs only where the models are: MCA_TEST_KOKORO (a folder with kokoro-v1.0.int8.onnx, voices-v1.0.bin, config.json,
 * gb_gold.json, gb_silver.json and onnxruntime.jar) and MCA_TEST_VOSK (a folder with the Vosk library and
 * vosk-model-small-en-us-0.15). Skipped otherwise. MCA_TEST_VOICE_OUT, if set, keeps each utterance as a WAV.
 */
class PaVoiceEndToEndTest {

    static final String[] VOICES = {"bm_george", "bf_emma", "bm_lewis", "bf_isabella"};

    record Result(String said, String voice, String heard, String read, boolean heardRight, boolean readRight) {}

    /** What the server would make of these words: the hush words first, then its memory, then the reader. */
    static String reading(String text) {
        PaQuips.Mute m = PaQuips.mute(text, false);
        if (m == null) m = PaQuips.mute(text, true);
        if (m == PaQuips.Mute.ALL) return "?quiet";
        if (m == PaQuips.Mute.JOKES) return "?jokes";
        if (m == PaQuips.Mute.ON) return "?talk";
        PaMemoryParse.Heard h = PaMemoryParse.read(text, "pip");
        if (h != null && h.op() != PaMemoryParse.Op.NONE && h.op().name().startsWith("RECALL")) return "?memory";
        PaUnderstand.Reading r = PaUnderstand.read(text, "pip");
        if (r.clarify() != null || r.steps().isEmpty()) return "?";
        return r.steps().get(0).kind().name();
    }

    /**
     * 24 kHz to the recogniser's 16 kHz as a sound card would: low-passed below the new Nyquist first (a windowed sinc),
     * so the voice's top octave doesn't fold back down into the words (plain picking of samples aliases, and Vosk
     * heard nothing at all of a third of the orders).
     */
    static float[] to16k(float[] a) {
        double step = 24000.0 / 16000.0, fc = 0.95 * 16000.0 / 24000.0;
        int half = 24, n = (int) Math.floor((a.length - 1) / step);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i * step, acc = 0;
            int c = (int) Math.floor(t);
            for (int k = c - half + 1; k <= c + half; k++) {
                if (k < 0 || k >= a.length) continue;
                double x = t - k, sinc = Math.abs(x) < 1e-9 ? 1.0 : Math.sin(Math.PI * fc * x) / (Math.PI * fc * x);
                double w = 0.42 + 0.5 * Math.cos(Math.PI * x / half) + 0.08 * Math.cos(2 * Math.PI * x / half);   // Blackman
                acc += a[k] * fc * sinc * w;
            }
            out[i] = (float) acc;
        }
        return out;
    }

    /** A 16-bit mono WAV back to samples (the cache an earlier run wrote). */
    static float[] read24(Path f) throws java.io.IOException {
        byte[] b = Files.readAllBytes(f);
        int at = 44;
        float[] out = new float[(b.length - at) / 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) ((b[at + 2 * i] & 0xFF) | (b[at + 2 * i + 1] << 8)) / 32768F;
        return out;
    }

    static String words(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
    }

    @Test
    void spokenOrdersAreHeardAndUnderstood() throws Exception {
        String kd = System.getenv("MCA_TEST_KOKORO"), vd = System.getenv("MCA_TEST_VOSK");
        Assumptions.assumeTrue(kd != null && vd != null && Files.isDirectory(Path.of(kd)) && Files.isDirectory(Path.of(vd)),
            "the voice models aren't here (MCA_TEST_KOKORO, MCA_TEST_VOSK): skipped");
        Path kdir = Path.of(kd);
        List<PaKokoro.Part> parts = new ArrayList<>();
        for (String f : new String[]{PaKokoro.MODEL_FILE, PaKokoro.VOICES_FILE, PaKokoro.CONFIG_FILE, "gb_gold.json", "gb_silver.json", "onnxruntime.jar"})
            parts.add(new PaKokoro.Part(f, "", f, Files.size(kdir.resolve(f)), "local", "test"));
        PaKokoro kokoro = new PaKokoro(kdir, parts);
        Files.writeString(kdir.resolve(PaKokoro.VERIFIED), kokoro.marker());
        assertTrue(kokoro.load(), "Kokoro loads: " + kokoro.why());
        PaG2p g2p = kokoro.g2p(PaAccent.UK, null);
        PaVosk vosk = new PaVosk(Path.of(vd));
        assertTrue(vosk.load(), "Vosk loads: " + vosk.status());
        String out = System.getenv("MCA_TEST_VOICE_OUT"), cache = System.getenv("MCA_TEST_VOICE_IN");

        List<Result> results = new ArrayList<>();
        int i = 0;
        for (String line : Files.readAllLines(Path.of("src/test/resources/pa/voice_orders.txt"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\\|\\|");
            String said = f[0].trim(), want = f[1].trim();
            for (int v = 0; v < 2; v++) {
                PaKokoro.Voice voice = PaKokoro.byId(VOICES[(i + v) % VOICES.length]);
                String name = String.format("%02d_%s", i, voice.id());
                float[] all;
                Path cached = cache == null ? null : Path.of(cache).resolve(name + "_24k.wav");
                if (cached != null && Files.isRegularFile(cached)) all = read24(cached);          // made by this same code on an earlier run
                else {
                    float[] raw = kokoro.synth(g2p.phonemize(said), voice, 1.0F);
                    float[] done = PaAudio.finish(raw, PaKokoro.SAMPLE_RATE, 1.0);
                    float[] pad = PaAudio.silence(PaKokoro.SAMPLE_RATE, 300);
                    all = new float[pad.length * 2 + done.length];
                    System.arraycopy(done, 0, all, pad.length, done.length);
                    if (out != null) Files.write(Path.of(out).resolve(name + "_24k.wav"), PaWav.wav(PaKokoro.pcm16(all, 100), PaKokoro.SAMPLE_RATE));
                }
                byte[] pcm = PaKokoro.pcm16(to16k(all), 100);
                if (out != null) Files.write(Path.of(out).resolve(name + ".wav"), PaWav.wav(pcm, 16000));
                PaVosk.Session held = vosk.session(PaStt.words()), free = vosk.session(null);
                held.feed(pcm);
                free.feed(pcm);
                String hs = held.finish(), fs = free.finish();
                String heard = PaStt.choose(hs, fs);
                if (System.getenv("MCA_TEST_VOICE_DEBUG") != null) System.out.printf("[pa38] dbg \"%s\" (%s): held \"%s\" free \"%s\"%n", said, voice.id(), hs, fs);
                String read = reading(PaWords.voiceFix(heard));
                results.add(new Result(said, voice.id(), heard, read, words(heard).equals(words(said)), read.equals(want)));
            }
            i++;
        }
        int heardRight = 0, readRight = 0;
        StringBuilder misses = new StringBuilder();
        for (Result r : results) {
            if (r.heardRight()) heardRight++;
            if (r.readRight()) readRight++;
            else misses.append(String.format("%n  \"%s\" (%s) heard \"%s\" read %s", r.said(), r.voice(), r.heard(), r.read()));
            if (!r.heardRight() && r.readRight()) System.out.printf("[pa38] near miss, still understood: \"%s\" (%s) heard \"%s\"%n", r.said(), r.voice(), r.heard());
        }
        double hr = 100.0 * heardRight / results.size(), rr = 100.0 * readRight / results.size();
        System.out.printf("[pa38] voice end to end: %d utterances (%d orders x 2 British voices); heard word for word %d (%.1f%%), understood %d (%.1f%%)%s%n",
            results.size(), i, heardRight, hr, readRight, rr, misses);
        assertTrue(i >= 40, "forty or more orders: " + i);
        assertTrue(rr >= 95.0, "understood: " + rr + "%" + misses);
    }
}
