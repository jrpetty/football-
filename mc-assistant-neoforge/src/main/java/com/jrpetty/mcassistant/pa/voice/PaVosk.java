package com.jrpetty.mcassistant.pa.voice;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * [pa33] Offline speech recognition with Vosk (Apache 2.0), on every OS. Nothing of it ships in the mod jar: when the
 * player turns listening on and agrees, the small English model (about 40 MB, from the Vosk project) and the Vosk
 * library for their OS (from the project's own release on Maven Central) are downloaded once into the config folder.
 * Called through JNA, which the game already carries. Each piece fails gracefully: no model, no library, no JNA,
 * a failed download, all end in a plain message, and typing goes on working.
 *
 * <p>Two recognisers run on what was heard: one held to the assistant's own words (far more accurate for orders),
 * and one free (for names and questions); PaStt keeps the better reading.
 */
public final class PaVosk {

    public static final String MODEL = "vosk-model-small-en-us-0.15";
    public static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL + ".zip";
    public static final String LIB_URL = "https://repo1.maven.org/maven2/com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar";

    private final Path dir;
    private volatile String status = "Not set up.";
    private Object lib;           // the JNA binding, once loaded (Object, so this class loads without JNA)
    private Object model;

    public PaVosk(Path dir) {
        this.dir = dir;
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

    /** Download what is missing (the player agreed first). Runs on the caller's thread; reports progress. */
    public boolean install(Consumer<String> progress) {
        try {
            Files.createDirectories(dir);
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
            if (!Files.isRegularFile(dir.resolve(libName()))) {
                progress.accept("Downloading the speech library...");
                Path jar = download(http, LIB_URL, dir.resolve("vosk.jar.part"));
                unzip(jar, dir, platform() + "/", true);
                Files.deleteIfExists(jar);
            }
            if (!Files.isDirectory(modelDir().resolve("am"))) {
                progress.accept("Downloading the speech model (about 40 MB)...");
                Path zip = download(http, MODEL_URL, dir.resolve("model.zip.part"));
                progress.accept("Unpacking the speech model...");
                unzip(zip, dir, "", false);
                Files.deleteIfExists(zip);
            }
            status = installed() ? "Installed." : "The download didn't give what was expected.";
            return installed();
        } catch (Throwable e) {
            status = "Couldn't download the speech model: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    private static Path download(HttpClient http, String url, Path to) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).header("User-Agent", "minecraft-mod").build();
        HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(to));
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + " from " + URI.create(url).getHost());
        return to;
    }

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
            status = "The speech model isn't downloaded yet.";
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
