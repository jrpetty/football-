package com.jrpetty.mcassistant.pa.voice;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * [voice37] The expressive voice: Chatterbox-Turbo (Resemble AI, MIT, 350M), run in the game's own process through ONNX
 * Runtime for Java, the same runtime the natural voice (Kokoro) uses. It laughs, sighs, chuckles and groans where the
 * line asks (its own tags, "[laugh]"), with a British man's or woman's voice.
 *
 * <p>How a line is made: the words become GPT-2 tokens (PaBpe), a voice's conditioning (a few hundred kilobytes made
 * once from a licensed recording, shipped in the mod jar) goes in front, a 24-layer language model writes speech tokens
 * one at a time (sampled, so no two readings are quite the same), and a one-step decoder turns them into 24 kHz sound.
 *
 * <p>Nothing heavy ships in the jar. On a computer fast enough (PaVoiceTier's benchmark) and with the player's go-ahead,
 * three model files (about 570 MB) are downloaded once from the model's own page on Hugging Face, pinned to one
 * revision, resumed where they stop and checked by SHA-256 before use, or come from an offline pack. ONNX Runtime is the
 * one downloaded for Kokoro. Any failure falls back to Kokoro at once; the words always show.
 *
 * <p>The voices (conditioning made from the CSTR VCTK Corpus, CC BY 4.0; see VOICES) are of consenting corpus speakers,
 * never a celebrity or a real person by name.
 */
public final class PaExpressive {

    /** Pinned to one revision of the model's page, so a file and its checksum never drift. */
    public static final String REPO = "https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/d21799bd0354adb85e348b8a0442a8405110a2cf/";
    public static final int SAMPLE_RATE = 24_000;
    static final int START = 6561, STOP = 6562, SILENCE = 4299, HEADS = 16, HEAD_DIM = 64;

    public static final List<PaKokoro.Part> PARTS = List.of(
        new PaKokoro.Part("Expressive voice: words", REPO + "tokenizer.json", "tokenizer.json", 3_562_272L, "3f04e34bea22f9144d1a19151154095bc9ce0430bf421304f5797e716288a906", "MIT (Resemble AI)"),
        new PaKokoro.Part("Expressive voice: word sounds", REPO + "onnx/embed_tokens_q4.onnx", "embed_tokens_q4.onnx", 2_844L, "fd6ba1d22902e8f539d3dd6d7c1c44b98ebb4c84ebbb5e47fcb826ddcf667561", "MIT (Resemble AI)"),
        new PaKokoro.Part("Expressive voice: word sounds (weights)", REPO + "onnx/embed_tokens_q4.onnx_data", "embed_tokens_q4.onnx_data", 37_286_384L, "f54a51e234b509b64c3a03bb79e1149fba7e2eba6c2d9c222f18883379e1f5d8", "MIT (Resemble AI)"),
        new PaKokoro.Part("Expressive voice: speech model", REPO + "onnx/language_model_q4.onnx", "language_model_q4.onnx", 274_572L, "b39d03d3f8b943b9e60c6fce3fb41191dbc1df4589f913291db1e214eef669b1", "MIT (Resemble AI)"),
        new PaKokoro.Part("Expressive voice: speech model (weights)", REPO + "onnx/language_model_q4.onnx_data", "language_model_q4.onnx_data", 204_456_572L, "2c029dc0acf48752473d8c74c72b5ceaaad76b9886fe106eaf2022142d5b5d5e", "MIT (Resemble AI)"),
        new PaKokoro.Part("Expressive voice: sound decoder", REPO + "onnx/conditional_decoder_quantized.onnx", "conditional_decoder_quantized.onnx", 2_202_035L, "2af3b150196d9d559cd3c91e03da80eb27a466032369dc2b57ea729cddad3ebb", "MIT (Resemble AI)"),
        new PaKokoro.Part("Expressive voice: sound decoder (weights)", REPO + "onnx/conditional_decoder_quantized.onnx_data", "conditional_decoder_quantized.onnx_data", 326_548_688L, "4918ca09e05e41d2b4aa1ace6201d1cd911ffc58a42801002bab177d495cfe0a", "MIT (Resemble AI)"));

    /** The download, in megabytes, said before the player agrees. */
    public static int totalMb() {
        long b = 0;
        for (PaKokoro.Part p : PARTS) b += p.bytes();
        return (int) Math.round(b / 1_000_000.0);
    }

    /** A voice: its id (the conditioning file in the jar), the name shown, gender, accent, and where it came from. */
    public record Voice(String id, String label, String gender, String accent, String source) {
        public boolean female() { return gender.equals("female"); }
    }

    public static final String ATTRIBUTION = "Expressive voices: Chatterbox-Turbo by Resemble AI (MIT). Voice conditioning made from the "
        + "CSTR VCTK Corpus 0.92 (Centre for Speech Technology Research, University of Edinburgh), CC BY 4.0: speakers p225, p226, p227, "
        + "p228, recordings joined, trimmed and encoded, no other change.";

    public static final List<Voice> VOICES = List.of(
        new Voice("gb_m_p226", "Arthur", "male", "British", "VCTK p226 (Surrey), CC BY 4.0"),
        new Voice("gb_m_p227", "Callum", "male", "British", "VCTK p227 (Cumbria), CC BY 4.0"),
        new Voice("gb_f_p228", "Harriet", "female", "British", "VCTK p228 (southern England), CC BY 4.0"),
        new Voice("gb_f_p225", "Elsie", "female", "British", "VCTK p225 (southern England), CC BY 4.0"));

    public static Voice byId(String id) {
        for (Voice v : VOICES) if (v.id().equals(id)) return v;
        return null;
    }

    public static Voice byLabel(String label) {
        for (Voice v : VOICES) if (v.label().equals(label)) return v;
        return null;
    }

    /** The voice for a pick ("expressive:Harriet"), else the first of the wanted gender. */
    public static Voice pick(String voiceKey, boolean female) {
        if (voiceKey != null && voiceKey.startsWith("expressive:")) {
            Voice v = byLabel(voiceKey.substring(11));
            if (v != null) return v;
        }
        for (Voice v : VOICES) if (v.female() == female) return v;
        return VOICES.get(0);
    }

    // ------------------------------------------------------------------ a voice's conditioning (in the jar)

    /** The four arrays a voice brings: its prompt embedding, prompt speech tokens, speaker embedding and features. */
    public record Cond(float[] emb, long[] embShape, long[] tokens, float[] spk, float[] feat, long[] featShape) {}

    /**
     * Read a conditioning file ("MCAV", version 1, then four arrays: a kind byte (0 = float16, 1 = int16), the number of
     * dimensions, each dimension, then the values, little-endian). tools/voice/make_expressive_voice.py writes them.
     */
    public static Cond readCond(InputStream in) throws Exception {
        DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(in));
        byte[] magic = new byte[4];
        d.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("MCAV")) throw new IllegalArgumentException("not a voice file");
        int ver = Integer.reverseBytes(d.readInt());
        if (ver != 1) throw new IllegalArgumentException("voice file version " + ver);
        Object[] arr = new Object[4];
        long[][] shapes = new long[4][];
        for (int k = 0; k < 4; k++) {
            int kind = d.readUnsignedByte(), nd = d.readUnsignedByte();
            long[] shape = new long[nd];
            int n = 1;
            for (int i = 0; i < nd; i++) { shape[i] = Integer.reverseBytes(d.readInt()); n *= (int) shape[i]; }
            shapes[k] = shape;
            if (kind == 0) {
                float[] f = new float[n];
                for (int i = 0; i < n; i++) f[i] = Float.float16ToFloat(Short.reverseBytes(d.readShort()));
                arr[k] = f;
            } else {
                long[] l = new long[n];
                for (int i = 0; i < n; i++) l[i] = Short.reverseBytes(d.readShort());
                arr[k] = l;
            }
        }
        return new Cond((float[]) arr[0], shapes[0], (long[]) arr[1], (float[]) arr[2], (float[]) arr[3], shapes[3]);
    }

    private final Map<String, Cond> conds = new HashMap<>();

    Cond cond(Voice v) throws Exception {
        Cond c = conds.get(v.id());
        if (c != null) return c;
        try (InputStream in = PaExpressive.class.getResourceAsStream("/assets/mc_assistant/voice/expressive/" + v.id() + ".mcav")) {
            if (in == null) throw new IllegalStateException("The voice " + v.label() + " is missing from the mod.");
            c = readCond(in);
        }
        conds.put(v.id(), c);
        return c;
    }

    // ------------------------------------------------------------------ files

    private final Path dir;
    private final PaKokoro files;

    public PaExpressive(Path dir) {
        this(dir, PARTS);
    }

    public PaExpressive(Path dir, List<PaKokoro.Part> parts) {
        this.dir = dir;
        this.files = new PaKokoro(dir, parts).named("expressive voice");
    }

    public Path dir() { return dir; }

    /** The downloads (resume, checksums, offline pack), the same machinery as Kokoro's. */
    public PaKokoro files() { return files; }

    public boolean downloaded() { return files.downloaded(); }

    // ------------------------------------------------------------------ the model

    private ClassLoader ort;
    private Object embed, lm, decoder;
    private List<String> kvIn = List.of();
    private PaBpe bpe;
    private String why = "";
    private final Random rnd = new Random();

    public String why() { return why; }

    /** Load the three models and the tokeniser (once), on ONNX Runtime from this jar. False with why() when it can't. */
    public synchronized boolean load(Path ortJar) {
        if (lm != null) return true;
        if (!downloaded()) { why = "The expressive voice isn't downloaded."; return false; }
        try {
            if (ortJar == null || !Files.isRegularFile(ortJar)) { why = "The expressive voice needs the natural voice's runtime first."; return false; }
            try (InputStream in = Files.newInputStream(dir.resolve("tokenizer.json"))) { bpe = PaBpe.read(in); }
            ort = PaOrt.loader(ortJar);
            int threads = PaOrt.threads(Runtime.getRuntime().availableProcessors());
            boolean mac = PaOrt.mac(System.getProperty("os.name")) && Boolean.getBoolean("mca.voice.coreml");
            embed = PaOrt.session(ort, dir.resolve("embed_tokens_q4.onnx"), 1, false);
            lm = PaOrt.session(ort, dir.resolve("language_model_q4.onnx"), threads, mac);
            decoder = PaOrt.session(ort, dir.resolve("conditional_decoder_quantized.onnx"), threads, mac);
            kvIn = new ArrayList<>();
            for (String n : PaOrt.inputs(lm)) if (n.startsWith("past_key_values")) kvIn.add(n);
            if (kvIn.isEmpty()) throw new IllegalStateException("speech model has no cache inputs");
            return true;
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            why = "The expressive voice couldn't start: " + (c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
            PaOrt.close(embed); PaOrt.close(lm); PaOrt.close(decoder);
            embed = lm = decoder = null;
            return false;
        }
    }

    public boolean loaded() { return lm != null; }

    /** How a line is read: sampling temperature, and a seed (0 = fresh each time). */
    public record Read(float temperature, long seed) {
        public static final Read DEFAULT = new Read(0.8f, 0);
    }

    /** At most this many speech tokens (25 a second) for a line of so many text tokens. */
    static int maxTokens(int textTokens) {
        return Math.max(75, Math.min(1000, 60 + textTokens * 22));
    }

    /**
     * One line (with its tags) in a voice, as samples at 24 kHz. Stops early (returning what there is: nothing) when
     * cancelled says so.
     */
    public synchronized float[] synth(String text, Voice v, Read r, BooleanSupplier cancelled) throws Exception {
        if (lm == null) throw new IllegalStateException(why.isEmpty() ? "The expressive voice isn't loaded." : why);
        Cond c = cond(v);
        long[] ids = bpe.encode(text);
        float[] textEmb = embed(ids);
        int width = (int) c.embShape()[c.embShape().length - 1];
        int condLen = c.emb().length / width, textLen = textEmb.length / width, n = condLen + textLen;
        float[] x = new float[n * width];
        System.arraycopy(c.emb(), 0, x, 0, c.emb().length);
        System.arraycopy(textEmb, 0, x, c.emb().length, textEmb.length);
        Random rng = r.seed() != 0 ? new Random(r.seed()) : rnd;
        List<Integer> gen = new ArrayList<>();
        gen.add(START);
        Map<String, Object> past = new LinkedHashMap<>();
        List<Object> owned = new ArrayList<>();
        for (String k : kvIn) { Object t = PaOrt.tensor(ort, new float[0], 1, HEADS, 0, HEAD_DIM); past.put(k, t); owned.add(t); }
        AutoCloseable prev = null;
        int len = n, limit = maxTokens(ids.length);
        try {
            for (int step = 0; step < limit; step++) {
                if (cancelled != null && cancelled.getAsBoolean()) return new float[0];
                int seq = step == 0 ? n : 1;
                long[] mask = new long[len];
                java.util.Arrays.fill(mask, 1);
                long[] pos = new long[seq];
                for (int i = 0; i < seq; i++) pos[i] = len - seq + i;
                Object xe = PaOrt.tensor(ort, x, 1, seq, width), me = PaOrt.tensor(ort, mask, 1, len), pe = PaOrt.tensor(ort, pos, 1, seq);
                Map<String, Object> feeds = new HashMap<>(past);
                feeds.put("inputs_embeds", xe);
                feeds.put("attention_mask", me);
                feeds.put("position_ids", pe);
                AutoCloseable res;
                try { res = PaOrt.run(lm, feeds); } finally { PaOrt.close(xe); PaOrt.close(me); PaOrt.close(pe); }
                Object logitsT = PaOrt.output(res, 0);
                long[] ls = PaOrt.shape(logitsT);
                float[] all = PaOrt.floats(logitsT);
                int vocab = (int) ls[2];
                float[] last = java.util.Arrays.copyOfRange(all, all.length - vocab, all.length);
                int t = sample(last, gen, r.temperature(), rng);
                gen.add(t);
                // the cache for the next step is this result's "present" outputs, in input order
                for (int i = 0; i < kvIn.size(); i++) past.put(kvIn.get(i), PaOrt.output(res, i + 1));
                if (prev != null) prev.close();
                else { for (Object o : owned) PaOrt.close(o); owned.clear(); }
                prev = res;
                if (t == STOP) break;
                x = embed(new long[]{t});
                len++;
            }
        } finally {
            if (prev != null) prev.close();
            for (Object o : owned) PaOrt.close(o);
        }
        int end = gen.get(gen.size() - 1) == STOP ? gen.size() - 1 : gen.size();
        long[] speech = new long[c.tokens().length + (end - 1) + 3];
        System.arraycopy(c.tokens(), 0, speech, 0, c.tokens().length);
        for (int i = 1; i < end; i++) speech[c.tokens().length + i - 1] = gen.get(i);
        for (int i = 0; i < 3; i++) speech[speech.length - 1 - i] = SILENCE;
        if (cancelled != null && cancelled.getAsBoolean()) return new float[0];
        Object st = PaOrt.tensor(ort, speech, 1, speech.length), se = PaOrt.tensor(ort, c.spk(), 1, c.spk().length), sf = PaOrt.tensor(ort, c.feat(), c.featShape());
        Map<String, Object> feeds = new HashMap<>();
        feeds.put("speech_tokens", st);
        feeds.put("speaker_embeddings", se);
        feeds.put("speaker_features", sf);
        try (AutoCloseable res = PaOrt.run(decoder, feeds)) {
            return PaOrt.floats(PaOrt.output(res, 0));
        } finally {
            PaOrt.close(st); PaOrt.close(se); PaOrt.close(sf);
        }
    }

    private float[] embed(long[] ids) throws Exception {
        Object t = PaOrt.tensor(ort, ids, 1, ids.length);
        try (AutoCloseable res = PaOrt.run(embed, Map.of("input_ids", t))) {
            return PaOrt.floats(PaOrt.output(res, 0));
        } finally {
            PaOrt.close(t);
        }
    }

    /**
     * The next speech token: a repetition penalty of 1.2 on what's been said, then temperature, the top thousand, and the
     * smallest set holding 95% of the chance (the model's own defaults).
     */
    static int sample(float[] logits, List<Integer> said, float temperature, Random rng) {
        double[] l = new double[logits.length];
        for (int i = 0; i < l.length; i++) l[i] = logits[i];
        java.util.Set<Integer> seen = new java.util.HashSet<>(said);
        for (int id : seen) if (id >= 0 && id < l.length) l[id] = l[id] < 0 ? l[id] * 1.2 : l[id] / 1.2;
        if (temperature <= 0.01f) {
            int best = 0;
            for (int i = 1; i < l.length; i++) if (l[i] > l[best]) best = i;
            return best;
        }
        Integer[] idx = new Integer[l.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(l[b], l[a]));
        int k = Math.min(1000, idx.length);
        double max = l[idx[0]] / temperature, sum = 0;
        double[] p = new double[k];
        for (int i = 0; i < k; i++) { p[i] = Math.exp(l[idx[i]] / temperature - max); sum += p[i]; }
        double acc = 0;
        int keep = k;
        for (int i = 0; i < k; i++) { p[i] /= sum; if (acc >= 0.95) { keep = i; break; } acc += p[i]; }
        double total = 0;
        for (int i = 0; i < keep; i++) total += p[i];
        double u = rng.nextDouble() * total;
        for (int i = 0; i < keep; i++) { u -= p[i]; if (u <= 0) return idx[i]; }
        return idx[keep - 1];
    }

    /**
     * [voice37] How fast this computer makes the expressive voice: seconds of work per second of speech (the real-time
     * factor) for a fixed line, after one warm-up. Under 1 it keeps up with itself.
     */
    public double benchmark(BooleanSupplier cancelled) throws Exception {
        Voice v = VOICES.get(0);
        synth("Right then.", v, new Read(0.8f, 37), cancelled);                    // warm-up: the first run pays for setting up
        long t0 = System.nanoTime();
        float[] a = synth("Kept these for you. Don't make it weird.", v, new Read(0.8f, 37), cancelled);
        double secs = (System.nanoTime() - t0) / 1e9;
        return a.length == 0 ? Double.POSITIVE_INFINITY : secs / (a.length / (double) SAMPLE_RATE);
    }
}
