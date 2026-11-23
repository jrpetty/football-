package com.jrpetty.mcassistant.pa.voice;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * [pa33b] The optional neural voice, first choice where installed: Kokoro (82M, Apache-2.0), run in the game's own
 * process through ONNX Runtime for Java (com.microsoft.onnxruntime, MIT). Its British voices (George, Lewis, Daniel,
 * Fable, Emma, Isabella, Alice, Lily) are the most human-sounding offline voices there are for the price.
 *
 * <p>Why in-process (onnxruntime Java) and not a helper program: there is no ready-made Kokoro program for Windows
 * (kokoro-onnx is a Python package), so a helper would mean asking a player to install Python and pip, or shipping a
 * frozen 200 MB+ interpreter. ONNX Runtime's jar carries its own Windows, macOS and Linux libraries, loads like the
 * speech library already does (its own class loader, so nothing joins the mod's classpath), and fails into a message.
 *
 * <p>Nothing ships in the mod jar. Only when the player picks "Get natural British voices" and agrees are these
 * downloaded once into the config folder, from GitHub and Maven Central (reachable where Hugging Face often isn't):
 * the int8 model (about 92 MB) and the voice pack (about 28 MB) from the kokoro-onnx project's model-files-v1.0
 * release, its vocabulary (config.json, 2 KB), and ONNX Runtime (about 93 MB). Kokoro reads phonemes, not letters: the
 * phonemes come from espeak-ng (GPL-3) as a separate program the player installs themselves (on Windows its installer
 * from the espeak-ng GitHub releases), run out of process so its licence never mixes with the mod's. Without it the
 * voice says so and the computer's own voice speaks.
 *
 * <p>Not run here: downloading and running native code needs the player's go-ahead on their own computer. The paths,
 * the voice pack reader, the tokens, the style row, the speed, the per-look picks and the failure messages are
 * unit-tested; the sound itself is checked on a real PC.
 */
public final class PaKokoro {

    public static final String RELEASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/";
    public static final String MODEL_FILE = "kokoro-v1.0.int8.onnx", VOICES_FILE = "voices-v1.0.bin", CONFIG_FILE = "config.json";
    public static final String CONFIG_URL = "https://raw.githubusercontent.com/thewh1teagle/kokoro-onnx/main/src/kokoro_onnx/config.json";
    public static final String ORT_VERSION = "1.20.0";
    public static final String ORT_URL = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/" + ORT_VERSION + "/onnxruntime-" + ORT_VERSION + ".jar";
    /** Sizes as published (bytes), to check a download and to tell the player before they agree. */
    public static final long MODEL_BYTES = 92_361_271L, VOICES_BYTES = 28_214_398L, ORT_BYTES = 92_987_839L;
    public static final int SAMPLE_RATE = 24_000, MAX_TOKENS = 510, STYLE = 256;

    /** One download for the consent screen: what, from where, how big, under what licence. */
    public record Part(String what, String url, String file, long bytes, String licence) {}

    public static final List<Part> PARTS = List.of(
        new Part("Kokoro voice model (int8)", RELEASE + MODEL_FILE, MODEL_FILE, MODEL_BYTES, "Apache-2.0"),
        new Part("Kokoro voices", RELEASE + VOICES_FILE, VOICES_FILE, VOICES_BYTES, "Apache-2.0"),
        new Part("Kokoro vocabulary", CONFIG_URL, CONFIG_FILE, 0, "MIT (kokoro-onnx)"),
        new Part("ONNX Runtime for Java", ORT_URL, "onnxruntime.jar", ORT_BYTES, "MIT"));

    /** The download, in megabytes, said before the player agrees. */
    public static int totalMb() {
        long b = 0;
        for (Part p : PARTS) b += p.bytes();
        return (int) Math.round(b / 1_000_000.0);
    }

    /** A Kokoro voice: its id in the voice pack (bf_emma: b = British, f = female), the name shown, accent, gender. */
    public record Voice(String id, String label, String accent, String gender) {
        public String lang() { return id.startsWith("b") ? "en-GB" : "en-US"; }
    }

    public static final List<Voice> CATALOGUE = List.of(
        new Voice("bm_george", "George", "British", "male"),
        new Voice("bm_lewis", "Lewis", "British", "male"),
        new Voice("bm_daniel", "Daniel", "British", "male"),
        new Voice("bm_fable", "Fable", "British", "male"),
        new Voice("bf_emma", "Emma", "British", "female"),
        new Voice("bf_isabella", "Isabella", "British", "female"),
        new Voice("bf_alice", "Alice", "British", "female"),
        new Voice("bf_lily", "Lily", "British", "female"),
        new Voice("af_heart", "Heart", "American", "female"),
        new Voice("af_bella", "Bella", "American", "female"),
        new Voice("am_michael", "Michael", "American", "male"),
        new Voice("am_adam", "Adam", "American", "male"));

    public static Voice byId(String id) {
        for (Voice v : CATALOGUE) if (v.id().equals(id)) return v;
        return null;
    }

    public static Voice byLabel(String label) {
        for (Voice v : CATALOGUE) if (v.label().equals(label)) return v;
        return null;
    }

    /**
     * The voice that suits a look (PaPersona.LOOK_KEYS order): George or Lewis for the knight, Fable for the butler,
     * Daniel for the skeleton; Emma or Isabella where the player wants a woman's voice. American voices for US.
     */
    public static Voice forLook(int look, PaAccent accent, boolean female) {
        if (accent == PaAccent.US) return byId(female ? "af_heart" : (look == 4 || look == 7 ? "am_adam" : "am_michael"));
        if (female) return byId(switch (look) {
            case 3, 8, 9, 12 -> "bf_isabella";        // scholar, butler, wizard, scientist: crisp
            case 2, 5, 10 -> "bf_lily";               // ranger, farmer, chef: warm
            case 14, 15, 17 -> "bf_alice";            // skeleton, zombie, enderman
            default -> "bf_emma";
        });
        return byId(switch (look) {
            case 4 -> "bm_george";                    // knight
            case 7, 11, 16 -> "bm_lewis";             // pirate, lumberjack, creeper hoodie
            case 8, 9 -> "bm_fable";                  // butler, wizard
            case 14, 15, 17 -> "bm_daniel";           // skeleton, zombie, enderman
            case 3, 12 -> "bm_fable";                 // scholar, scientist
            default -> "bm_george";
        });
    }

    // ------------------------------------------------------------------ files

    private final Path dir;

    public PaKokoro(Path dir) {
        this.dir = dir;
    }

    public Path dir() { return dir; }

    public Path file(Part p) { return dir.resolve(p.file()); }

    /** Every file there, at its published size (where one is published). */
    public boolean downloaded() {
        try {
            for (Part p : PARTS) {
                Path f = file(p);
                if (!Files.isRegularFile(f)) return false;
                if (p.bytes() > 0 && Files.size(f) != p.bytes()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Download what is missing (only after the player agreed), off the game thread; each file checked by its size. */
    public boolean install(java.util.function.Consumer<String> progress) {
        try {
            Files.createDirectories(dir);
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(20)).build();
            for (Part p : PARTS) {
                Path to = file(p);
                if (Files.isRegularFile(to) && (p.bytes() == 0 || Files.size(to) == p.bytes())) continue;
                progress.accept("Downloading " + p.what() + (p.bytes() > 0 ? " (about " + Math.round(p.bytes() / 1_000_000.0) + " MB)" : "") + "...");
                Path part = dir.resolve(p.file() + ".part");
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(p.url())).timeout(java.time.Duration.ofMinutes(15))
                    .header("User-Agent", "minecraft-mod").build();
                java.net.http.HttpResponse<Path> res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofFile(part));
                if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + " from " + java.net.URI.create(p.url()).getHost());
                if (p.bytes() > 0 && Files.size(part) != p.bytes()) { Files.deleteIfExists(part); throw new IllegalStateException(p.what() + " came down the wrong size"); }
                Files.move(part, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            why = downloaded() ? "" : "The download didn't give what was expected.";
            return downloaded();
        } catch (Throwable e) {
            why = "Couldn't download the natural voices: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    /** espeak-ng, which turns words into the phonemes Kokoro reads: on the PATH, or where its Windows installer puts it. */
    public static String espeak(String osName, Predicate<String> onPath, Predicate<Path> exists) {
        if (onPath.test("espeak-ng")) return "espeak-ng";
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            for (String root : new String[]{"C:\\Program Files\\eSpeak NG\\", "C:\\Program Files (x86)\\eSpeak NG\\"}) {
                Path p = Path.of(root + "espeak-ng.exe");
                if (exists.test(p)) return p.toString();
            }
        }
        if (os.contains("mac")) for (String p : new String[]{"/opt/homebrew/bin/espeak-ng", "/usr/local/bin/espeak-ng"}) if (exists.test(Path.of(p))) return p;
        return null;
    }

    /** What to tell the player when espeak-ng is missing, in plain words for their computer. */
    public static String espeakAdvice(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "The natural voices need one more free program, eSpeak NG, to read words aloud. Get the installer "
            + "(espeak-ng-X64.msi) from the espeak-ng releases page on GitHub, run it, then restart Minecraft.";
        if (os.contains("mac")) return "The natural voices need eSpeak NG to read words: install Homebrew, then run \"brew install espeak-ng\" in Terminal.";
        return "The natural voices need espeak-ng: install it with your package manager (for example \"sudo apt install espeak-ng\").";
    }

    // ------------------------------------------------------------------ voice pack (an .npz: a zip of .npy arrays)

    /** Read the voice pack: each voice's style table, 510 rows of 256 numbers (one row per sentence length). */
    public static Map<String, float[][]> readVoices(InputStream npz) throws Exception {
        Map<String, float[][]> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(npz)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".npy")) continue;
                out.put(name.substring(0, name.length() - 4), npy(z.readAllBytes()));
            }
        }
        return out;
    }

    /** One .npy of little-endian float32 (any shape whose last axis is the style width) as rows. */
    static float[][] npy(byte[] b) {
        if (b.length < 10 || b[0] != (byte) 0x93 || b[1] != 'N' || b[2] != 'U') throw new IllegalArgumentException("not a voice table");
        int major = b[6];
        int hlen = major == 1 ? (b[8] & 0xff) | (b[9] & 0xff) << 8 : ByteBuffer.wrap(b, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int start = major == 1 ? 10 : 12;
        String header = new String(b, start, hlen, StandardCharsets.ISO_8859_1);
        if (!header.contains("'<f4'")) throw new IllegalArgumentException("voice table is not float32: " + header.trim());
        if (header.contains("'fortran_order': True")) throw new IllegalArgumentException("voice table is in Fortran order");
        Matcher m = Pattern.compile("'shape': \\(([^)]*)\\)").matcher(header);
        if (!m.find()) throw new IllegalArgumentException("voice table has no shape");
        List<Integer> dims = new ArrayList<>();
        for (String d : m.group(1).split(",")) if (!d.isBlank()) dims.add(Integer.parseInt(d.trim()));
        int width = dims.get(dims.size() - 1), total = 1;
        for (int d : dims) total *= d;
        int rows = total / width;
        FloatBuffer fb = ByteBuffer.wrap(b, start + hlen, total * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[][] out = new float[rows][width];
        for (int r = 0; r < rows; r++) fb.get(out[r]);
        return out;
    }

    /** The style row for a sentence of so many tokens (as kokoro-onnx: the row at the token count). */
    public static float[] style(float[][] table, int tokens) {
        return table[Math.max(0, Math.min(table.length - 1, tokens))];
    }

    // ------------------------------------------------------------------ text to tokens

    /** The vocabulary out of config.json ("vocab": {"a": 43, ...}). */
    public static Map<Integer, Integer> vocab(String json) {
        Map<Integer, Integer> v = new HashMap<>();
        int at = json.indexOf("\"vocab\"");
        if (at < 0) return v;
        int open = json.indexOf('{', at), close = json.indexOf('}', open);
        Matcher m = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*(\\d+)").matcher(json.substring(open + 1, close));
        while (m.find()) {
            String k = unescape(m.group(1));
            if (k.codePointCount(0, k.length()) == 1) v.put(k.codePointAt(0), Integer.parseInt(m.group(2)));
        }
        return v;
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            if (n == 'u' && i + 4 < s.length()) { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
            else b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
        }
        return b.toString();
    }

    /** Phonemes to token ids (what the vocabulary doesn't know is dropped), at most 510. */
    public static long[] tokens(String phonemes, Map<Integer, Integer> vocab) {
        List<Long> out = new ArrayList<>();
        phonemes.codePoints().forEach(cp -> {
            Integer id = vocab.get(cp);
            if (id != null && out.size() < MAX_TOKENS) out.add((long) id);
        });
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** The tokens as the model takes them: a 0 at each end. */
    public static long[] padded(long[] tokens) {
        long[] p = new long[tokens.length + 2];
        System.arraycopy(tokens, 0, p, 1, tokens.length);
        return p;
    }

    /** Text split at its punctuation, so the phonemes can be joined with the punctuation Kokoro uses for pauses. */
    public static List<String[]> clauses(String text) {
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("([^.,;:!?…—]+)([.,;:!?…—]*)").matcher(text);
        while (m.find()) {
            String words = m.group(1).trim();
            if (!words.isEmpty()) out.add(new String[]{words, m.group(2).isEmpty() ? "" : m.group(2).substring(0, 1)});
        }
        return out;
    }

    /** The espeak-ng command for one clause's phonemes (IPA with stress marks, no sound). */
    public static List<String> phonemeCommand(String espeak, PaAccent accent, String clause) {
        return List.of(espeak, "-q", "--ipa", "-b", "1", "-v", accent == PaAccent.UK ? "en-gb" : "en-us", clause);
    }

    /** espeak-ng's output cleaned to one line of phonemes. */
    public static String cleanPhonemes(String out) {
        return out.replace('\n', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Kokoro's speed for the mood and the player's rate (-5 .. 5): gloomy slower, a higher rate quicker. */
    public static float speed(double gloom, int rate) {
        double s = (1.0 + 0.07 * rate) * (1.0 - 0.18 * gloom);
        return (float) Math.max(0.6, Math.min(1.5, s));
    }

    /** Samples (-1 .. 1) to 16-bit little-endian PCM at the player's volume (0 .. 100). */
    public static byte[] pcm16(float[] audio, int volume) {
        double g = Math.max(0, Math.min(100, volume)) / 100.0;
        byte[] b = new byte[audio.length * 2];
        for (int i = 0; i < audio.length; i++) {
            int s = (int) Math.round(Math.max(-1.0, Math.min(1.0, audio[i] * g)) * 32767);
            b[2 * i] = (byte) s;
            b[2 * i + 1] = (byte) (s >> 8);
        }
        return b;
    }

    // ------------------------------------------------------------------ the model (ONNX Runtime, by reflection)

    private Object env, session;
    private String tokensName = "tokens";
    private Map<Integer, Integer> vocab;
    private Map<String, float[][]> voices;
    private ClassLoader ort;
    private String why = "";

    public String why() { return why; }

    /** Load ONNX Runtime, the model, the vocabulary and the voices (once). False with why() when it can't. */
    public synchronized boolean load() {
        if (session != null) return true;
        if (!downloaded()) { why = "The natural voices aren't downloaded."; return false; }
        try {
            vocab = vocab(Files.readString(dir.resolve(CONFIG_FILE)));
            if (vocab.size() < 100) { why = "The voice vocabulary looks wrong."; return false; }
            try (InputStream in = Files.newInputStream(dir.resolve(VOICES_FILE))) {
                voices = readVoices(in);
            }
            ort = new URLClassLoader(new URL[]{dir.resolve("onnxruntime.jar").toUri().toURL()}, PaKokoro.class.getClassLoader());
            Class<?> envC = ort.loadClass("ai.onnxruntime.OrtEnvironment");
            env = envC.getMethod("getEnvironment").invoke(null);
            Class<?> optsC = ort.loadClass("ai.onnxruntime.OrtSession$SessionOptions");
            Object opts = optsC.getConstructor().newInstance();
            session = envC.getMethod("createSession", String.class, optsC).invoke(env, dir.resolve(MODEL_FILE).toString(), opts);
            @SuppressWarnings("unchecked")
            java.util.Set<String> names = (java.util.Set<String>) session.getClass().getMethod("getInputNames").invoke(session);
            tokensName = names.contains("input_ids") ? "input_ids" : "tokens";   // older exports named it input_ids
            return true;
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            why = "The natural voice couldn't start: " + (c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
            session = null;
            return false;
        }
    }

    /** One clause's phonemes as samples at 24 kHz. */
    public synchronized float[] synth(String phonemes, Voice v, float speed) throws Exception {
        if (!load()) throw new IllegalStateException(why);
        float[][] table = voices.get(v.id());
        if (table == null) throw new IllegalStateException("No voice called " + v.label() + " in the voice pack.");
        long[] tok = tokens(phonemes, vocab);
        if (tok.length == 0) return new float[0];
        Class<?> envC = ort.loadClass("ai.onnxruntime.OrtEnvironment");
        Class<?> tensorC = ort.loadClass("ai.onnxruntime.OnnxTensor");
        Method ofLongs = tensorC.getMethod("createTensor", envC, LongBuffer.class, long[].class);
        Method ofFloats = tensorC.getMethod("createTensor", envC, FloatBuffer.class, long[].class);
        long[] in = padded(tok);
        Object t = ofLongs.invoke(null, env, LongBuffer.wrap(in), new long[]{1, in.length});
        Object s = ofFloats.invoke(null, env, FloatBuffer.wrap(style(table, tok.length)), new long[]{1, STYLE});
        Object sp = ofFloats.invoke(null, env, FloatBuffer.wrap(new float[]{speed}), new long[]{1});
        Map<String, Object> feeds = new HashMap<>();
        feeds.put(tokensName, t);
        feeds.put("style", s);
        feeds.put("speed", sp);
        Object result = session.getClass().getMethod("run", Map.class).invoke(session, feeds);
        try {
            Object first = result.getClass().getMethod("get", int.class).invoke(result, 0);
            Object value = first.getClass().getMethod("getValue").invoke(first);
            return flatten(value);
        } finally {
            ((AutoCloseable) result).close();
            for (Object o : new Object[]{t, s, sp}) ((AutoCloseable) o).close();
        }
    }

    static float[] flatten(Object value) {
        if (value instanceof float[] f) return f;
        if (value instanceof float[][] ff) {
            int n = 0;
            for (float[] r : ff) n += r.length;
            float[] out = new float[n];
            int at = 0;
            for (float[] r : ff) { System.arraycopy(r, 0, out, at, r.length); at += r.length; }
            return out;
        }
        throw new IllegalStateException("Unexpected voice output");
    }
}
