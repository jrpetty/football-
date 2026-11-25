package com.jrpetty.mcassistant.pa.voice;

import java.io.InputStream;
import java.lang.reflect.Method;
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
 * <p>[voice37] Now everything below ships inside the release jar instead and is unpacked, not downloaded (PaVoiceAssets);
 * the list and checks are the same. [pa35] The first time an assistant is summoned on a computer (unless the player says
 * not to), these are downloaded once into the config folder, in the background, from GitHub and Maven Central (reachable
 * where Hugging Face often isn't), each resumed where it stopped and checked by its SHA-256 before use: the int8 model
 * (about 92 MB) and the voice pack (about 28 MB) from kokoro-onnx's model-files-v1.0 release, its vocabulary, misaki's
 * British and American pronouncing dictionaries (about 13 MB; PaG2p reads them, so no other program is needed), and ONNX
 * Runtime (about 75 MB; [voice37] 1.23.2, shared with the expressive voice; [voice42] 1.25.1, and 1.23.2 for Intel Macs). An offline pack (a zip in the config folder, tools/voice/build_voice_pack.py) does the same
 * with no network. espeak-ng, if the player happens to have it, only helps with words the dictionaries lack.
 *
 * <p>Not run here: downloading and running native code needs the player's go-ahead on their own computer. The paths,
 * the voice pack reader, the tokens, the style row, the speed, the per-look picks and the failure messages are
 * unit-tested; the sound itself is checked on a real PC.
 */
public final class PaKokoro {

    public static final String RELEASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/";
    public static final String MODEL_FILE = "kokoro-v1.0.int8.onnx", VOICES_FILE = "voices-v1.0.bin", CONFIG_FILE = "config.json";
    /** [pa35] Pinned to a commit (never a moving branch), so the file and its checksum never drift. */
    public static final String CONFIG_URL = "https://raw.githubusercontent.com/thewh1teagle/kokoro-onnx/3596b26764286a7de9d90c363e988d50578918e5/src/kokoro_onnx/config.json";
    /** [pa35] misaki's pronouncing dictionaries (Apache-2.0), the ones Kokoro was trained with, pinned to a commit. */
    public static final String LEXICON_URL = "https://raw.githubusercontent.com/hexgrad/misaki/fba1236595f2d2bf21d414ba6e57d25256afada3/misaki/data/";
    /**
     * [voice42] 1.25.1, shared with the expressive voice (new enough for its 4-bit models). [voice37] had 1.23.2, whose int8
     * convolutions come out wrong on CPUs with Intel AMX, so Kokoro gave NaN, silence and clicks there (PaOrt.JAR). Intel
     * Macs (no AMX, and no 1.24+ build) keep 1.23.2 as a second, cut-down jar.
     */
    public static final String ORT_VERSION = "1.25.1", ORT_INTEL_MAC_VERSION = "1.23.2";
    public static final String ORT_URL = ortUrl(ORT_VERSION), ORT_INTEL_MAC_URL = ortUrl(ORT_INTEL_MAC_VERSION);
    /** Sizes as published (bytes), to check a download and to tell the player before they agree. */
    public static final long MODEL_BYTES = 92_361_271L, VOICES_BYTES = 28_214_398L, ORT_BYTES = 41_804_437L, ORT_INTEL_MAC_BYTES = 75_255_976L;

    static String ortUrl(String version) {
        return "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/" + version + "/onnxruntime-" + version + ".jar";
    }
    public static final int SAMPLE_RATE = 24_000, MAX_TOKENS = 510, STYLE = 256;

    /** [pa35] One download: what, from where, the file, its size and SHA-256 (checked before it's used), its licence. */
    public record Part(String what, String url, String file, long bytes, String sha256, String licence) {}

    public static final List<Part> PARTS = List.of(
        new Part("Kokoro voice model (int8)", RELEASE + MODEL_FILE, MODEL_FILE, MODEL_BYTES, "6e742170d309016e5891a994e1ce1559c702a2ccd0075e67ef7157974f6406cb", "Apache-2.0"),
        new Part("Kokoro voices", RELEASE + VOICES_FILE, VOICES_FILE, VOICES_BYTES, "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d", "Apache-2.0"),
        new Part("Kokoro vocabulary", CONFIG_URL, CONFIG_FILE, 2_351L, "5abb01e2403b072bf03d04fde160443e209d7a0dad49a423be15196b9b43c17f", "MIT (kokoro-onnx)"),
        new Part("British pronouncing dictionary", LEXICON_URL + "gb_gold.json", "gb_gold.json", 2_838_552L, "29e62f4b60261c88f7f3c2c7811ca3825978948090b72d2b27d565b729282f71", "Apache-2.0 (misaki)"),
        new Part("British pronouncing dictionary (more words)", LEXICON_URL + "gb_silver.json", "gb_silver.json", 3_663_898L, "48131e2d92ccc41655f4543e87e0f938e71463eb5a54be7f0693bb712ebb6bce", "Apache-2.0 (misaki)"),
        new Part("American pronouncing dictionary", LEXICON_URL + "us_gold.json", "us_gold.json", 3_000_469L, "dc414872a49a28ae6c141463d502fd945f3b2fde040484fdc47d00cc4612686f", "Apache-2.0 (misaki)"),
        new Part("American pronouncing dictionary (more words)", LEXICON_URL + "us_silver.json", "us_silver.json", 3_099_517L, "de8f67be911bb6c659187b4a65fd966b6a30e56350e0f790d763210b053ac475", "Apache-2.0 (misaki)"),
        new Part("ONNX Runtime for Java", ORT_URL, PaOrt.JAR, ORT_BYTES, "749793ebed63743fec853d093da7987a86ea5cd592d54fba898cd3233100c381", "MIT"),
        new Part("ONNX Runtime for Java (Intel Macs)", ORT_INTEL_MAC_URL, PaOrt.JAR_INTEL_MAC, ORT_INTEL_MAC_BYTES, "e0ab4a1af57d2da09097202f2dfd691e390c82e81183314788ccde4cf7c3cc38", "MIT"));

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
    /** [pa35] What it downloads (the real list; a test gives its own small one). */
    private final List<Part> parts;

    public PaKokoro(Path dir) {
        this(dir, PARTS);
    }

    public PaKokoro(Path dir, List<Part> parts) {
        this.dir = dir;
        this.parts = parts;
    }

    public Path dir() { return dir; }

    public Path file(Part p) { return dir.resolve(p.file()); }

    /** The marker written once every file has passed its checksum (so 220 MB isn't hashed at every start). */
    static final String VERIFIED = "verified.sha256";

    /** Every file there, at its size, checked once by its SHA-256 (the marker remembers it). */
    public boolean downloaded() {
        try {
            for (Part p : parts) {
                Path f = file(p);
                if (!Files.isRegularFile(f) || Files.size(f) != p.bytes()) return false;
            }
            Path m = dir.resolve(VERIFIED);
            return Files.isRegularFile(m) && Files.readString(m).trim().equals(marker());
        } catch (Exception e) {
            return false;
        }
    }

    String marker() {
        StringBuilder b = new StringBuilder();
        for (Part p : parts) b.append(p.sha256()).append(' ').append(p.file()).append('\n');
        return b.toString().trim();
    }

    /** [pa35] How a download reaches the network: one GET for the bytes from an offset (tests put a fake in its place). */
    public interface Fetcher {
        /** Status (200 whole, 206 from the offset), the length of what follows (-1 unknown), and the bytes. */
        record Reply(int status, long length, InputStream body) {}

        Reply get(java.net.URI uri, long from) throws Exception;
    }

    // [voice37] No network fetcher any more: the files come from inside the mod jar (PaVoiceAssets.fromJar) or an offline pack.

    /** [pa35] Progress as it goes: a fraction of the whole (by bytes) and a line to show. */
    public interface Progress {
        void at(double fraction, String line);
    }

    private volatile boolean cancel;
    /** [voice37] What these files are, in messages ("natural voice"; the expressive voice reuses this downloader). */
    private String what = "natural voice";

    public PaKokoro named(String what) { this.what = what; return this; }

    /** Stop a download under way (it carries on from where it was next time). */
    public void cancel() { cancel = true; }

    public boolean install(Fetcher net, Progress progress) {
        cancel = false;
        try {
            Files.createDirectories(dir);
            long total = 0, done = 0;
            for (Part p : parts) total += p.bytes();
            for (Part p : parts) {
                Path to = file(p);
                if (Files.isRegularFile(to) && Files.size(to) == p.bytes() && sha256(to).equals(p.sha256())) { done += p.bytes(); continue; }
                Files.deleteIfExists(to);
                Path part = dir.resolve(p.file() + ".part");
                long have = Files.isRegularFile(part) ? Files.size(part) : 0;
                if (have > p.bytes()) { Files.delete(part); have = 0; }
                for (int attempt = 0; attempt < 3 && have < p.bytes(); attempt++) {
                    if (cancel) { why = "Download paused. It picks up where it left off."; return false; }
                    Fetcher.Reply r = net.get(java.net.URI.create(p.url()), have);
                    boolean append = r.status() == 206 && have > 0;
                    if (r.status() != 200 && r.status() != 206) { try { r.body().close(); } catch (Exception ignored) { } throw new IllegalStateException("HTTP " + r.status() + " from " + java.net.URI.create(p.url()).getHost()); }
                    if (!append) have = 0;
                    try (InputStream in = r.body(); java.io.OutputStream out = Files.newOutputStream(part, append ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND}
                            : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE})) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        long lastShown = 0;
                        while ((n = in.read(buf)) > 0) {
                            if (cancel) { why = "Download paused. It picks up where it left off."; return false; }
                            out.write(buf, 0, n);
                            have += n;
                            if (have - lastShown > (1 << 20) || have >= p.bytes()) {
                                lastShown = have;
                                progress.at(Math.min(1.0, (done + have) / (double) total), (p.url().startsWith("jar:") ? "Unpacking " : "Downloading ") + p.what() + ": " + Math.round(have / 1_000_000.0) + " of " + Math.max(1, Math.round(p.bytes() / 1_000_000.0)) + " MB");
                            }
                        }
                    } catch (java.io.IOException e) {
                        if (attempt == 2) throw e;                          // a dropped line: tried again from where it got to
                    }
                    have = Files.size(part);
                }
                if (Files.size(part) != p.bytes()) throw new IllegalStateException(p.what() + " came down the wrong size");
                if (!sha256(part).equals(p.sha256())) { Files.delete(part); throw new IllegalStateException(p.what() + " failed its checksum, so it wasn't used. Try again later."); }
                Files.move(part, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                done += p.bytes();
                progress.at(done / (double) total, "Got " + p.what() + ".");
            }
            Files.writeString(dir.resolve(VERIFIED), marker());
            why = downloaded() ? "" : "The download didn't give what was expected.";
            return downloaded();
        } catch (Throwable e) {
            why = "Couldn't get the " + what + " ready: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    /** A file's SHA-256, in hex. */
    public static String sha256(Path f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest()) b.append(String.format("%02x", x));
        return b.toString();
    }

    /**
     * [pa35] The offline voice pack (tools/voice/build_voice_pack.py): a zip dropped in the config folder (anything named
     * like "*voice*pack*.zip" in config/ or config/mc_assistant/voice/) is unpacked here at start, each file checked by its
     * SHA-256 against the list above; a file that fails is left out (and downloaded as usual). True when it made it ready.
     */
    public boolean importPack(Path... folders) {
        if (downloaded()) return false;
        for (Path folder : folders) {
            if (folder == null || !Files.isDirectory(folder)) continue;
            try (java.util.stream.Stream<Path> st = Files.list(folder)) {
                for (Path z : st.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*voice.*pack.*\\.zip")).toList()) {
                    Files.createDirectories(dir);
                    try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(z))) {
                        ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            if (e.isDirectory()) continue;
                            String name = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                            Part want = null;
                            for (Part p : parts) if (p.file().equals(name)) want = p;
                            if (want == null || (Files.isRegularFile(file(want)) && Files.size(file(want)) == want.bytes())) continue;
                            Path tmp = dir.resolve(name + ".pack");
                            Files.copy(zin, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            if (Files.size(tmp) == want.bytes() && sha256(tmp).equals(want.sha256())) Files.move(tmp, file(want), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            else Files.deleteIfExists(tmp);
                        }
                    }
                    boolean all = true;
                    for (Part p : parts) if (!(Files.isRegularFile(file(p)) && Files.size(file(p)) == p.bytes() && sha256(file(p)).equals(p.sha256()))) all = false;
                    if (all) { Files.writeString(dir.resolve(VERIFIED), marker()); return true; }
                }
            } catch (Exception ignored) {
                // a damaged zip: the usual download covers it
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ [pa35] words to phonemes, with no other program

    private final Map<PaAccent, PaG2p> g2p = new java.util.EnumMap<>(PaAccent.class);

    /** The phonemiser for an accent: the dictionaries downloaded with the voices, espeak-ng for a word they lack (if installed). */
    public synchronized PaG2p g2p(PaAccent accent, java.util.function.Function<String, String> fallback) throws Exception {
        PaG2p g = g2p.get(accent);
        if (g != null) return g;
        String pre = accent == PaAccent.UK ? "gb" : "us";
        Map<String, String> gold, silver;
        try (InputStream in = Files.newInputStream(dir.resolve(pre + "_gold.json"))) { gold = PaG2p.read(in); }
        try (InputStream in = Files.newInputStream(dir.resolve(pre + "_silver.json"))) { silver = PaG2p.read(in); }
        g = new PaG2p(gold, silver, accent == PaAccent.UK, fallback);
        g2p.put(accent, g);
        return g;
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

    /** The style row for a sentence of so many tokens: row n - 1 for n tokens (as Kokoro and kokoro-onnx 0.6 take it). [pa35] It was off by one. */
    public static float[] style(float[][] table, int tokens) {
        return table[Math.max(0, Math.min(table.length, tokens) - 1)];
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
            ort = PaOrt.loader(PaOrt.jar(dir));                       // [voice37] one runtime for both neural voices ([voice42] this computer's)
            Class<?> envC = ort.loadClass("ai.onnxruntime.OrtEnvironment");
            env = PaOrt.env(ort);
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

    // ------------------------------------------------------------------ [voice42] never clicks or broken silence

    /** A checked piece: its samples (null when it wasn't speech even after the retry), and what was wrong first, if anything. */
    public record Said(float[] samples, String fault, boolean retried) {
        public boolean ok() { return samples != null; }
    }

    /**
     * [voice42] One piece's phonemes as speech, checked (PaAudio.fault): when what comes back isn't speech (NaN, absurd
     * samples, near-silence, a spike), it is made once more another way that changes every length inside the model: split
     * in two at the word nearest the middle, or, for a single word, a touch slower. Still not speech: samples null, so the
     * caller can use the basic voice instead of playing clicks or silence.
     */
    public Said speak(String phonemes, Voice v, float speed) throws Exception {
        float[] raw = synth(phonemes, v, speed);
        String fault = PaAudio.fault(raw, SAMPLE_RATE, tokenCount(phonemes));
        if (fault == null) return new Said(raw, null, false);
        String[] halves = halves(phonemes);
        if (halves == null) {
            float[] again = synth(phonemes, v, speed * 0.94f);
            return new Said(PaAudio.fault(again, SAMPLE_RATE, tokenCount(phonemes)) == null ? again : null, fault, true);
        }
        float[] a = synth(halves[0], v, speed), b = synth(halves[1], v, speed);
        if (PaAudio.fault(a, SAMPLE_RATE, tokenCount(halves[0])) != null || PaAudio.fault(b, SAMPLE_RATE, tokenCount(halves[1])) != null) return new Said(null, fault, true);
        float[] both = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return new Said(both, fault, true);
    }

    /** Phonemes cut in two at the space nearest the middle (each half keeps its own words whole); null for a single word. */
    public static String[] halves(String phonemes) {
        String p = phonemes.trim();
        int mid = p.length() / 2, cut = -1;
        for (int d = 0; d <= mid && cut < 0; d++) {
            if (mid - d > 0 && p.charAt(mid - d) == ' ') cut = mid - d;
            else if (mid + d < p.length() - 1 && p.charAt(mid + d) == ' ') cut = mid + d;
        }
        if (cut < 0) return null;
        String a = p.substring(0, cut).trim(), b = p.substring(cut + 1).trim();
        return a.isEmpty() || b.isEmpty() ? null : new String[]{a, b};
    }

    /** How many tokens the model gets for these phonemes (what the vocabulary knows; a guess before it's loaded). */
    public int tokenCount(String phonemes) {
        Map<Integer, Integer> v = vocab;
        return v == null ? phonemes.codePointCount(0, phonemes.length()) : tokens(phonemes, v).length;
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
