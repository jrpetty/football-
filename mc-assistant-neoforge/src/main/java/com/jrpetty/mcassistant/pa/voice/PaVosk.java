package com.jrpetty.mcassistant.pa.voice;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * [pa33] Offline speech recognition with Vosk (Apache 2.0), on every OS. [voice37] It ships inside the release jar (the
 * small English model, about 40 MB, and the Vosk release jar from Maven Central with its libraries for Windows, Linux
 * and macOS, both pinned and checked by SHA-256 when the jar is built): when the player turns listening on, the two
 * are unpacked from the mod into the game folder (PaVoiceAssets), checked again, and the model and this computer's
 * library taken out of them. Nothing is downloaded. Called through JNA, which the game already carries. Each piece fails
 * gracefully: no model, no library, no JNA, all end in a plain message, and typing goes on working.
 *
 * <p>Two recognisers run on what was heard: one held to the assistant's own words (far more accurate for orders),
 * and one free (for names and questions); PaStt keeps the better reading.
 */
public final class PaVosk {

    /*
     * [pa33b] The model, for British speakers. The Vosk project publishes English models for US English (small 0.15,
     * about 40 MB; the "lgraph" 0.22, about 128 MB; the large 0.22 and GigaSpeech 0.42, 1.8-2.3 GB) and Indian
     * English, all Apache 2.0 as far as we know (the list could not be re-checked from here: alphacephei.com is not
     * reachable in this build's network). There is no British English model. So the small US model stays the
     * download, and for a British accent the assistant leans on the held recogniser: Vosk's phrase-list grammar, set to
     * the assistant's own words and the British sayings (PaStt.words(), PaUnderstand.grammar()), which copes with an
     * accent far better than free dictation; the recogniser's usual slips on British vowels are put right after
     * (PaWords.voiceFix). The small and lgraph models take a runtime grammar; the large ones do not.
     */

    public static final String MODEL = "vosk-model-small-en-us-0.15";
    public static final String LIB_JAR = "vosk-0.3.45.jar", MODEL_ZIP = MODEL + ".zip";

    /** [voice37] What the release jar carries for listening (the build fetches them from these pinned sources). */
    public static final List<PaKokoro.Part> PARTS = List.of(
        new PaKokoro.Part("Speech recognition library (Vosk)", "https://repo1.maven.org/maven2/com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar", LIB_JAR, 25_861_916L,
            "9e38e96e4448a41d889bb254b2f8424945554945c6e77e1cd97a3d8633fac2ba", "Apache-2.0"),
        new PaKokoro.Part("Speech recognition model (small English)", "https://alphacephei.com/vosk/models/" + MODEL_ZIP, MODEL_ZIP, 41_205_931L,
            "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", "Apache-2.0"));

    private final Path dir;
    private final PaKokoro files;
    private volatile String status = "Not set up.";
    private Object lib;           // the JNA binding, once loaded (Object, so this class loads without JNA)
    private Object model;

    public PaVosk(Path dir) {
        this(dir, PARTS);
    }

    /** [voice37] With the files as the jar carries them (PaVoiceAssets.parts(pack, "stt")). */
    public PaVosk(Path dir, List<PaKokoro.Part> parts) {
        this.dir = dir;
        this.files = new PaKokoro(dir, parts).named("speech model");
    }

    public String status() { return status; }

    public Path modelDir() { return dir.resolve(MODEL); }

    /** The OS's library folder inside the Vosk release jar. */
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "win32-x86-64";
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        return arch.contains("aarch64") || arch.contains("arm64") ? "linux-aarch64" : "linux-x86-64";
    }

    static String libName() {
        String p = platform();
        return p.startsWith("win") ? "libvosk.dll" : p.equals("darwin") ? "libvosk.dylib" : "libvosk.so";
    }

    /** Is everything on disk (model and library)? */
    public boolean installed() {
        return Files.isDirectory(modelDir().resolve("am")) && Files.isRegularFile(dir.resolve(libName()));
    }

    /**
     * [voice37] Unpack what is missing from the mod jar (never the network: the fetcher is PaVoiceAssets.fromJar), each
     * archive checked by its SHA-256, then the model and this computer's library taken out. Runs on the caller's thread.
     */
    public boolean unpack(PaKokoro.Fetcher fromJar, Consumer<String> progress) {
        try {
            if (installed()) { status = "Installed."; return true; }
            Files.createDirectories(dir);
            if (!files.downloaded() && !files.install(fromJar, (f, l) -> progress.accept(l))) { status = files.why(); return false; }
            if (!Files.isRegularFile(dir.resolve(libName()))) unzip(dir.resolve(LIB_JAR), dir, platform() + "/", true);
            if (!Files.isDirectory(modelDir().resolve("am"))) { progress.accept("Unpacking the speech model..."); unzip(dir.resolve(MODEL_ZIP), dir, "", false); }
            status = installed() ? "Installed." : "This computer has no Vosk library (" + platform() + "): Windows' own recogniser or typing instead.";
            return installed();
        } catch (Throwable e) {
            status = "Couldn't unpack the speech model: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    public PaKokoro files() { return files; }

    /** Unzip entries under {@code under} (flattened when asked), never outside the target folder. */
    private static void unzip(Path zip, Path to, String under, boolean flatten) throws Exception {
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.startsWith(under) || e.isDirectory()) continue;
                String rel = flatten ? name.substring(name.lastIndexOf('/') + 1) : name;
                if (rel.isEmpty()) continue;
                Path out = to.resolve(rel).normalize();
                if (!out.startsWith(to.normalize())) continue;            // a zip slip: never written
                Files.createDirectories(out.getParent());
                Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ------------------------------------------------------------------ recognising

    /** Load the library and the model (once). False with {@link #status()} when it can't. */
    public synchronized boolean load() {
        if (model != null) return true;
        if (!installed()) {
            status = "The speech model isn't unpacked yet.";
            return false;
        }
        try {
            Binding b = Binding.load(dir.resolve(libName()).toAbsolutePath().toString());
            b.quiet();
            Object m = b.model(modelDir().toAbsolutePath().toString());
            if (m == null) {
                status = "The speech model couldn't be read.";
                return false;
            }
            lib = b;
            model = m;
            status = "Ready (offline).";
            return true;
        } catch (Throwable e) {
            status = "The speech library couldn't be loaded: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    /** A recogniser held to these words (null: free speech). Feed it chunks; ask for the text. */
    public Session session(Collection<String> words) {
        if (model == null && !load()) return null;
        return new Session((Binding) lib, model, words);
    }

    /** One listening, chunk by chunk. */
    public static final class Session {
        private final Binding b;
        private final Object rec;

        Session(Binding b, Object model, Collection<String> words) {
            this.b = b;
            this.rec = words == null ? b.recognizer(model, 16000F) : b.recognizer(model, 16000F, grammar(words));
        }

        public void feed(byte[] pcm) {
            if (rec != null && pcm.length > 0) b.accept(rec, pcm);
        }

        /** What it has so far (for the live transcript). */
        public String partial() {
            return rec == null ? "" : field(b.partial(rec), "partial");
        }

        /** The whole of it, once the key is let go; the session is closed. */
        public String finish() {
            if (rec == null) return "";
            String t = field(b.result(rec), "text");
            b.free(rec);
            return t.replace("[unk]", "").replaceAll("\\s+", " ").trim();
        }
    }

    static String grammar(Collection<String> words) {
        StringBuilder sb = new StringBuilder("[");
        for (String w : words) {
            if (w.isEmpty() || !w.matches("[a-z' ]+")) continue;
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(w).append('"');
        }
        if (sb.length() > 1) sb.append(',');
        sb.append("\"[unk]\"]");
        return sb.toString();
    }

    private static final Pattern FIELD = Pattern.compile("\"(text|partial)\"\\s*:\\s*\"([^\"]*)\"");

    static String field(String json, String key) {
        if (json == null) return "";
        Matcher m = FIELD.matcher(json);
        while (m.find()) if (m.group(1).equals(key)) return m.group(2);
        return "";
    }

    /** The Vosk C library through JNA, reached by reflection so nothing here needs JNA unless it is used. */
    static final class Binding {
        interface Lib extends com.sun.jna.Library {
            void vosk_set_log_level(int level);
            com.sun.jna.Pointer vosk_model_new(String path);
            com.sun.jna.Pointer vosk_recognizer_new(com.sun.jna.Pointer model, float rate);
            com.sun.jna.Pointer vosk_recognizer_new_grm(com.sun.jna.Pointer model, float rate, String grammar);
            int vosk_recognizer_accept_waveform(com.sun.jna.Pointer rec, byte[] data, int length);
            String vosk_recognizer_partial_result(com.sun.jna.Pointer rec);
            String vosk_recognizer_final_result(com.sun.jna.Pointer rec);
            void vosk_recognizer_free(com.sun.jna.Pointer rec);
        }

        private final Lib lib;

        private Binding(Lib lib) {
            this.lib = lib;
        }

        static Binding load(String path) {
            if (!new File(path).isFile()) throw new IllegalStateException("no " + path);
            File here = new File(path).getParentFile();
            // [voice37] on Windows its own runtime libraries first, from beside it (Windows wouldn't look there by itself)
            for (String dep : new String[]{"libwinpthread-1.dll", "libgcc_s_seh-1.dll", "libstdc++-6.dll"}) {
                File f = new File(here, dep);
                if (f.isFile()) { try { System.load(f.getAbsolutePath()); } catch (Throwable ignored) { /* JNA may still find it */ } }
            }
            return new Binding(com.sun.jna.Native.load(path, Lib.class));
        }

        void quiet() { lib.vosk_set_log_level(-1); }

        Object model(String path) { return lib.vosk_model_new(path); }

        Object recognizer(Object model, float rate) { return lib.vosk_recognizer_new((com.sun.jna.Pointer) model, rate); }

        Object recognizer(Object model, float rate, String grammar) {
            return lib.vosk_recognizer_new_grm((com.sun.jna.Pointer) model, rate, grammar);
        }

        void accept(Object rec, byte[] pcm) { lib.vosk_recognizer_accept_waveform((com.sun.jna.Pointer) rec, pcm, pcm.length); }

        String partial(Object rec) { return lib.vosk_recognizer_partial_result((com.sun.jna.Pointer) rec); }

        String result(Object rec) { return lib.vosk_recognizer_final_result((com.sun.jna.Pointer) rec); }

        void free(Object rec) { lib.vosk_recognizer_free((com.sun.jna.Pointer) rec); }
    }
}
