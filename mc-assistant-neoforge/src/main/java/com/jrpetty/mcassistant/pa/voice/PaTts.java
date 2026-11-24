package com.jrpetty.mcassistant.pa.voice;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * [pa33] The Player AI Assistant's voice: its replies spoken with the most natural voice the computer has, never on
 * the game thread. [pa33b] Windows: the modern voices (Windows.Media.SpeechSynthesis, "OneCore": Hazel, George and
 * Susan for en-GB; Aria, Jenny and Guy where installed) through one long-lived PowerShell, with SSML for pacing and
 * mood; the classic desktop voices (System.Speech) when those aren't there. macOS: {@code say}, the Enhanced and Premium
 * voices first. Linux: {@code espeak-ng}/{@code espeak}/{@code spd-say}, the last resort. Kokoro (offline neural,
 * PaKokoro, first choice) and Piper (PaPiper) when the player has downloaded them. With none it is silent and says so (the words always show). Plain Java.
 */
public final class PaTts {

    public enum Kind { WINDOWS, MAC, ESPEAK_NG, ESPEAK, SPD, NONE }

    /** A voice the picker shows: its engine ("winrt", "sapi", "mac", "espeak", "piper"), name, language, gender, quality. */
    public record VoiceInfo(String engine, String name, String lang, String gender, String quality) {
        public String key() { return engine + ":" + name; }

        public String group() {
            return switch (engine) {
                case "expressive" -> "Expressive";                       // [voice37]
                case "kokoro" -> "Natural (Kokoro)";
                case "piper" -> "Natural (Piper)";
                case "winrt" -> "Windows (modern)";
                case "sapi" -> "Windows (classic)";
                case "mac" -> quality.isEmpty() ? "macOS" : "macOS (" + quality + ")";
                default -> "Basic";
            };
        }

        /** [pa35] Natural (Kokoro, Piper, a Windows 11 Natural voice) or Basic (the old desktop voices, macOS say, espeak). */
        public boolean natural() {
            return engine.equals("expressive") || engine.equals("kokoro") || engine.equals("piper") || engine.equals("winrt") && naturalName(name);
        }

        public String badge() { return PaVoiceTier.badge(engine, natural()); }   // [voice37] Expressive, Natural or Basic

        public String label() {
            String l = lang.toLowerCase(Locale.ROOT).replace('_', '-');
            String accent = l.startsWith("en-gb") ? "British" : l.startsWith("en-us") ? "American" : l.startsWith("en-au") ? "Australian"
                : l.startsWith("en-ie") ? "Irish" : l.startsWith("en-in") ? "Indian" : l.startsWith("en") ? "English" : lang;
            return name + "  ·  " + (gender.isEmpty() ? "" : gender.toLowerCase(Locale.ROOT) + ", ") + accent;
        }
    }

    /** What each line is said with (the player's choices, the mood, where the player stands for "forty blocks east"). */
    public record Opts(String voiceKey, int rate, int pitch, int volume, double gloom, double fun, PaAccent accent, double px, double pz, boolean basic, boolean female) {
        public Opts(String voiceKey, int rate, int pitch, int volume, double gloom, double fun, PaAccent accent, double px, double pz) {
            this(voiceKey, rate, pitch, volume, gloom, fun, accent, px, pz, false, false);
        }
    }

    /** [pa35] A Windows voice that is one of the Natural ones (Windows 11's neural voices, where an app can reach them). */
    public static boolean naturalName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("natural");
    }

    /**
     * [pa35] Which engine says a line, in order: Kokoro (natural) when downloaded; Piper (neural) if the player has it; a
     * Windows 11 Natural voice if one is installed; otherwise nothing (the words show as subtitles). The old desktop
     * voices (SAPI) and macOS {@code say}, and espeak, only when the player picked "System voice (basic)". A voice picked
     * by name is used when its engine is there. Returns "kokoro", "piper", "winrt", "sapi", "mac", "espeak" or "" (text only).
     */
    public static String engineFor(String voiceKey, boolean basic, boolean kokoroReady, boolean piperReady, List<VoiceInfo> voices, Kind kind) {
        String key = voiceKey == null ? "" : voiceKey;
        if (basic || key.startsWith("sapi:") || key.startsWith("mac:") || key.startsWith("espeak:")) {
            String nat = natural(kokoroReady, piperReady, voices);
            if (!basic) return nat;                                           // a basic voice picked by name without "System voice (basic)": not used
            if (!nat.isEmpty() && !(key.startsWith("sapi:") || key.startsWith("mac:") || key.startsWith("espeak:"))) return nat;   // basic only while no natural voice is ready, unless one was picked by name
            return switch (kind) {
                case WINDOWS -> "sapi";
                case MAC -> "mac";
                case ESPEAK, ESPEAK_NG, SPD -> "espeak";
                case NONE -> "";
            };
        }
        if (key.startsWith("kokoro:") && kokoroReady) return "kokoro";
        if (key.startsWith("piper:") && piperReady) return "piper";
        if (key.startsWith("winrt:") && naturalName(key.substring(6)) && voices.stream().anyMatch(v -> v.key().equals(key))) return "winrt";
        return natural(kokoroReady, piperReady, voices);
    }

    private static String natural(boolean kokoroReady, boolean piperReady, List<VoiceInfo> voices) {
        if (kokoroReady) return "kokoro";
        if (piperReady) return "piper";
        for (VoiceInfo v : voices) if (v.engine().equals("winrt") && naturalName(v.name())) return "winrt";
        return "";
    }

    private final Kind kind;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mca-assistant-voice");
        t.setDaemon(true);
        return t;
    });
    private volatile List<String> voices = List.of();
    private volatile List<VoiceInfo> catalogue = List.of();
    private volatile String status;
    private Process sapi, winrt;
    private Writer sapiIn, winrtIn;
    private volatile boolean winrtFailed;
    private Process current;
    private volatile java.util.function.Supplier<PaAccent> accent = () -> PaAccent.US;
    private volatile String speaking = "";
    private PaPiper piper;
    private PaKokoro kokoro;
    private volatile javax.sound.sampled.Clip clip;

    private PaTts(Kind kind, String status) {
        this.kind = kind;
        this.status = status;
    }

    public void accent(java.util.function.Supplier<PaAccent> a) {
        if (a != null) accent = a;
    }

    /** [pa33b] Where the optional neural voices live (the config folder). */
    public void piper(Path dir) {
        piper = new PaPiper(dir);
    }

    /** [pa33b] Where the Kokoro voices live, once downloaded (the config folder). */
    public void kokoro(Path dir) {
        kokoro = new PaKokoro(dir);
    }

    /** [voice37] The natural voice with its own file list (the files unpacked from the mod jar). */
    public void kokoro(PaKokoro k) {
        kokoro = k;
    }

    public PaKokoro kokoro() { return kokoro; }

    // ------------------------------------------------------------------ [voice37] the expressive voice, first where it suits

    /** The player's choices for the expressive voice: the tier, humour (0 Dry, 1 Daft, 2 Sincere), expressiveness 0..100, its benchmark. */
    public record Prefs(PaVoiceTier.Setting tier, int humour, int expressiveness, double expressiveRtf, int bondTier, int look) {
        public static final Prefs DEFAULT = new Prefs(PaVoiceTier.Setting.AUTO, 0, 50, PaVoiceTier.UNKNOWN, 1, -1);

        public Prefs(PaVoiceTier.Setting tier, int humour, int expressiveness, double expressiveRtf) {
            this(tier, humour, expressiveness, expressiveRtf, 1, -1);
        }
    }

    private PaExpressive expressive;
    private PaLineCache cache;
    private volatile java.util.function.Supplier<Prefs> prefs = () -> Prefs.DEFAULT;
    private volatile boolean expressiveFailed;
    private volatile double kokoroRtf = PaVoiceTier.UNKNOWN;
    private volatile java.util.function.DoubleConsumer onKokoroRtf = r -> { };

    /** Where the expressive voice lives once downloaded, and where its made lines are kept. */
    public void expressive(Path dir, Path cacheDir) {
        expressive(new PaExpressive(dir), cacheDir);
    }

    public void expressive(PaExpressive e, Path cacheDir) {
        expressive = e;
        cache = new PaLineCache(cacheDir, PaLineCache.DEFAULT_LIMIT);
    }

    public PaExpressive expressive() { return expressive; }

    public PaLineCache lineCache() { return cache; }

    public void prefs(java.util.function.Supplier<Prefs> p) { if (p != null) prefs = p; }

    /** Told each time the natural voice is timed faster than before (Settings keeps it for the tier's guess). */
    public void onKokoroRtf(java.util.function.DoubleConsumer c) { if (c != null) onKokoroRtf = c; }

    public double kokoroRtf() { return kokoroRtf; }

    public void kokoroRtf(double r) { kokoroRtf = r; }

    /** Downloaded, with the natural voice's runtime there too. */
    public boolean expressiveReady() {
        return expressive != null && expressive.downloaded() && kokoroReady();
    }

    /** It broke this session (the natural voice is speaking instead): why, or "". */
    public String expressiveTrouble() { return expressiveFailed ? expressive == null ? "" : expressive.why() : ""; }

    public void retryExpressive() { expressiveFailed = false; }

    /** Would this line be said by the expressive voice? Not when a natural or basic voice was picked by name. */
    public boolean expressiveFirst(String voiceKey, boolean basic) {
        String key = voiceKey == null ? "" : voiceKey;
        if (basic || !(key.isEmpty() || key.startsWith("expressive:"))) return false;
        Prefs p = prefs.get();
        PaVoiceTier.Setting tier = key.startsWith("expressive:") && p.tier() == PaVoiceTier.Setting.AUTO && expressiveReady() ? PaVoiceTier.Setting.EXPRESSIVE : p.tier();
        return PaVoiceTier.chain(tier, expressiveReady(), expressiveFailed, p.expressiveRtf(), kokoroReady(), false).contains("expressive");
    }

    /** [pa35] Kokoro is ready to speak: downloaded and checked (its own dictionaries read the words; espeak-ng no longer needed). */
    public boolean kokoroReady() {
        return kokoro != null && kokoro.downloaded();
    }

    /** [pa35] Is any natural voice ready (else it is text only, unless the player picked the basic system voice)? */
    public boolean naturalReady() {
        return !natural(kokoroReady(), piperReady(), catalogue).isEmpty();
    }

    public boolean piperReady() {
        return piper != null && !piper.installedVoices(System.getProperty("os.name")).isEmpty();
    }

    /** [pa35] Told when a line's sound starts, with its length in milliseconds (the gestures go with the words). */
    private volatile java.util.function.LongConsumer onSound = ms -> { };

    public void onSound(java.util.function.LongConsumer c) { if (c != null) onSound = c; }

    private final java.util.concurrent.atomic.AtomicInteger gen = new java.util.concurrent.atomic.AtomicInteger();
    /** [voice37] Where the sound goes: a stream of 16-bit mono PCM (the game's positional sound, or the computer's own audio). */
    public interface Sink {
        void write(byte[] pcm) throws Exception;

        /** Wait until what was written has been heard (or stop() was called). */
        void drain() throws InterruptedException;

        /** Stop at once, dropping what is still to play. */
        void stop();

        void close();
    }

    /** Opens a sink for a line at this sample rate, said by this assistant (its entity id, or -1). */
    public interface Sinks {
        Sink open(int rate, int speaker) throws Exception;
    }

    /** The computer's own audio output (Java Sound): no position, the fallback when the game's sound system isn't there. */
    public static final Sinks JAVA_SOUND = (rate, speaker) -> {
        javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(rate, 16, 1, true, false);
        javax.sound.sampled.SourceDataLine l = javax.sound.sampled.AudioSystem.getSourceDataLine(fmt);
        l.open(fmt, rate * 2 * 15);                                   // fifteen seconds of room: the next sentence is made while this one plays
        l.start();
        return new Sink() {
            public void write(byte[] pcm) { l.write(pcm, 0, pcm.length); }
            public void drain() { l.drain(); }
            public void stop() { try { l.stop(); l.flush(); } catch (Throwable ignored) { } }
            public void close() { l.close(); }
        };
    };

    private volatile Sinks sinks = JAVA_SOUND;
    private volatile Sink sink;
    private volatile int speaker = -1;

    /** [voice37] Send the sound somewhere else (the client: the game's sound system, from the assistant). */
    public void sinks(Sinks s) { if (s != null) sinks = s; }

    // [voice37] one line at a time, the stale ones dropped (PaSpeechQueue); the words classified off the game thread
    private record Req(String written, String line, Opts o, int speaker) {}
    private final PaSpeechQueue<Req> queue = new PaSpeechQueue<>();
    private final ExecutorService intake = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mca-assistant-voice-intake");
        t.setDaemon(true);
        return t;
    });

    public PaSpeechQueue<?> queue() { return queue; }

    public static Kind pick(String osName, Predicate<String> onPath) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return onPath.test("powershell") ? Kind.WINDOWS : Kind.NONE;
        if (os.contains("mac") || os.contains("darwin")) return onPath.test("say") ? Kind.MAC : Kind.NONE;
        if (onPath.test("espeak-ng")) return Kind.ESPEAK_NG;
        if (onPath.test("espeak")) return Kind.ESPEAK;
        if (onPath.test("spd-say")) return Kind.SPD;
        return Kind.NONE;
    }

    public static PaTts create() {
        try {
            Kind k = pick(System.getProperty("os.name"), PaTts::onPath);
            PaTts t = new PaTts(k, k == Kind.NONE ? "No text-to-speech found on this computer: replies show as words only."
                + (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux") ? " Install espeak-ng to hear them." : "")
                : "Speaking with " + label(k) + ".");
            t.worker.submit(t::loadVoices);
            return t;
        } catch (Throwable e) {
            return new PaTts(Kind.NONE, "Text-to-speech could not start: " + e.getMessage());
        }
    }

    public static PaTts none(String why) {
        return new PaTts(Kind.NONE, why);
    }

    static String label(Kind k) {
        return switch (k) {
            case WINDOWS -> "Windows' own voices";
            case MAC -> "macOS's own voices";
            case ESPEAK_NG -> "espeak-ng";
            case ESPEAK -> "espeak";
            case SPD -> "speech-dispatcher";
            case NONE -> "nothing";
        };
    }

    public Kind kind() { return kind; }

    public boolean available() { return kind != Kind.NONE || kokoroReady() || (piper != null && !piper.installedVoices(System.getProperty("os.name")).isEmpty()); }

    public String status() { return status; }

    /** The voices by name (old picker; index 0 is the default). */
    public List<String> voices() { return voices; }

    /** [pa33b] Every voice the picker can offer, grouped by engine, the most natural first. */
    public List<VoiceInfo> catalogue() {
        List<VoiceInfo> all = new ArrayList<>();
        if (expressiveReady()) for (PaExpressive.Voice v : PaExpressive.VOICES) all.add(new VoiceInfo("expressive", v.label(), "en-GB", v.gender(), "expressive"));   // [voice37]
        if (kokoroReady()) for (PaKokoro.Voice v : PaKokoro.CATALOGUE) all.add(new VoiceInfo("kokoro", v.label(), v.lang(), v.gender(), "natural"));
        if (piper != null) for (PaPiper.Voice v : piper.installedVoices(System.getProperty("os.name")))
            all.add(new VoiceInfo("piper", v.label(), v.id().substring(0, 5), v.gender(), v.quality()));
        all.addAll(catalogue);
        return all;
    }

    public static boolean onPath(String program) {
        String path = System.getenv("PATH");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (win && program.equals("powershell")) {
            String root = System.getenv("SystemRoot");
            if (root != null && new File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe").isFile()) return true;
        }
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            if (new File(dir, program).canExecute()) return true;
            if (win && (new File(dir, program + ".exe").canExecute())) return true;
        }
        return false;
    }

    /** Old entry point: by index, no mood. */
    public void say(String text, int voice, int rate, int volume) {
        String key = voice > 0 && voice < voices.size() ? (kind == Kind.WINDOWS ? "sapi:" : kind == Kind.MAC ? "mac:" : "espeak:") + voices.get(voice) : "";
        speak(text, new Opts(key, rate, 0, volume, 0, 0, accent.get(), Double.NaN, Double.NaN));
    }

    /** [pa33b] Say a line, shaped to sound spoken; queued on the voice's thread; a new line cuts off the one before. */
    public void speak(String text, Opts o) {
        speak(text, o, -1);
    }

    /**
     * [voice37] Say a line as this assistant (entity id; -1 none). Lines wait their turn (speech never overlaps); a
     * warning cuts in; a line gone stale while waiting is dropped (PaSpeechQueue). Never blocks the game thread.
     */
    public void speak(String text, Opts o, int speakerId) {
        if (text == null || text.isBlank()) return;
        String line = clean(PaSpeech.speakable(text, o.px(), o.pz()));
        if (line.isEmpty()) return;
        long at = System.currentTimeMillis();
        try {
            intake.submit(() -> {
                String key = PaVoiceStyle.keyOf(text);
                boolean urgent = (key != null ? PaVoiceStyle.kindOf(key) : PaVoiceStyle.guess(line)) == PaVoiceStyle.Kind.WARNING;
                if (queue.offer(new PaSpeechQueue.Item<>(new Req(text, line, o, speakerId), PaSpeechQueue.family(key), urgent, at)) == PaSpeechQueue.Action.INTERRUPT) {
                    gen.incrementAndGet();
                    Sink s = sink;
                    if (s != null) s.stop();
                }
                worker.submit(this::pump);
            });
        } catch (Throwable ignored) {
            // the worker has gone: stay silent
        }
    }

    /** The worker: the waiting lines, one after another, each to its end (or until a warning or hush cuts it). */
    private void pump() {
        PaSpeechQueue.Item<Req> it;
        while ((it = queue.next(System.currentTimeMillis())) != null) {
            Req r = it.line();
            speaker = r.speaker();
            speak0(r.written(), r.line(), r.o(), gen.get());
        }
    }

    public void hush() {
        queue.clear();                                                // [voice37] and nothing waiting either
        gen.incrementAndGet();
        Sink l = this.sink;
        if (l != null) l.stop();
        try {
            worker.submit(this::stopCurrent);
        } catch (Throwable ignored) {
            // silent anyway
        }
    }

    static String clean(String text) {
        String t = text.replaceAll("[\\p{So}\\p{Cn}\\u2600-\\u27BF]", " ").replace('\n', ' ').replace('"', ' ').replaceAll("\\s+", " ").trim();
        return t.length() > 400 ? t.substring(0, 400) : t;
    }

    /** [pa35] What it sounds like just now, for Settings: "George (natural)", "Subtitles only", "Microsoft Hazel (basic)". */
    public String voiceInUse(String key, boolean basic, PaAccent a, boolean female) {
        if (expressiveFirst(key, basic)) return PaExpressive.pick(key, female, prefs.get().look()).label() + " (expressive)";   // [voice37]
        String e = engineFor(key, basic, kokoroReady(), piperReady(), catalogue, kind);
        return switch (e) {
            case "kokoro" -> kokoroVoice(key, a, female).label() + " (natural)";
            case "piper" -> { VoiceInfo v = find(key); if (v == null) v = auto(a); yield (v == null ? "Piper" : v.name()) + " (natural)"; }
            case "winrt" -> { VoiceInfo v = find(key); if (v == null) for (VoiceInfo c : catalogue) if (naturalName(c.name())) { v = c; break; } yield (v == null ? "Windows" : v.name()) + " (natural)"; }
            case "" -> "Subtitles only, for now";
            default -> { VoiceInfo v = autoBasic(a, e); yield (v == null ? "the system voice" : v.name()) + " (basic)"; }
        };
    }

    /** The voice it speaks in: the one picked, else the accent's own when the computer has one. */
    public String voiceInUse(int picked) {
        if (kind == Kind.WINDOWS && !speaking.isEmpty()) return speaking;
        VoiceInfo v = auto(accent.get());
        if (v != null) return v.name();
        if (kind == Kind.ESPEAK || kind == Kind.ESPEAK_NG) return accent.get().espeakVoice();
        return "the computer's default";
    }

    /** With nothing picked: the most natural voice for the accent (neural, then modern Windows, then classic, then macOS Enhanced). */
    public VoiceInfo auto(PaAccent a) {
        List<VoiceInfo> all = catalogue();
        String want = a == PaAccent.UK ? "en-gb" : "en-us";
        String[] order = {"kokoro", "piper", "winrt"};                 // [pa35] natural voices only: basic ones only when picked
        for (String eng : order) {
            VoiceInfo best = null;
            for (VoiceInfo v : all) {
                if (!v.engine().equals(eng) || !v.lang().toLowerCase(Locale.ROOT).replace('_', '-').startsWith(want)) continue;
                if (eng.equals("winrt") && !naturalName(v.name())) continue;
                if (best == null || score(v) > score(best)) best = v;
            }
            if (best != null) return best;
        }
        return null;
    }

    /** [pa35] With "System voice (basic)" picked: the accent's own basic voice on this computer, or the default. */
    VoiceInfo autoBasic(PaAccent a, String engine) {
        String want = a == PaAccent.UK ? "en-gb" : "en-us";
        VoiceInfo best = null;
        for (VoiceInfo v : catalogue) {
            boolean eng = v.engine().equals(engine) || engine.equals("sapi") && v.engine().equals("winrt") && !naturalName(v.name());
            if (!eng || !v.lang().toLowerCase(Locale.ROOT).replace('_', '-').startsWith(want)) continue;
            if (best == null || score(v) > score(best)) best = v;
        }
        return best;
    }

    static int score(VoiceInfo v) {
        String q = v.quality().toLowerCase(Locale.ROOT);
        int s = q.contains("premium") ? 30 : q.contains("enhanced") ? 20 : q.contains("high") ? 15 : 0;
        String n = v.name().toLowerCase(Locale.ROOT);
        if (PaAccent.preferredVoice(PaAccent.UK, List.of(v.name())) != null || PaAccent.preferredVoice(PaAccent.US, List.of(v.name())) != null) s += 5;
        if (n.contains("compact")) s -= 10;
        return s;
    }

    private VoiceInfo find(String key) {
        if (key == null || key.isEmpty()) return null;
        for (VoiceInfo v : catalogue()) if (v.key().equals(key)) return v;
        return null;
    }

    private void speak0(String written, String line, Opts o, int mine) {
        try {
            if (expressiveFirst(o.voiceKey(), o.basic()) && expressiveLine(written, line, o, mine)) return;   // [voice37] else the natural voice says it
            String engine = engineFor(o.voiceKey(), o.basic(), kokoroReady(), piperReady(), catalogue, kind);   // [pa35] natural first, basic only if picked
            VoiceInfo v = find(o.voiceKey());
            if (v != null && !v.engine().equals(engine)) v = null;
            if (v == null && engine.equals("winrt")) for (VoiceInfo c : catalogue) if (c.engine().equals("winrt") && naturalName(c.name())
                    && (v == null || c.lang().toLowerCase(Locale.ROOT).startsWith(o.accent().culture.toLowerCase(Locale.ROOT)))) v = c;
            if (v == null && engine.equals("piper")) v = auto(o.accent());
            if (v == null && (engine.equals("sapi") || engine.equals("mac") || engine.equals("espeak"))) v = autoBasic(o.accent(), engine);
            int[] p = PaSpeech.prosody(o.gloom(), o.fun());
            switch (engine) {
                case "kokoro" -> kokoro(line, o, mine);
                case "piper" -> piper(line, v, o);
                case "winrt" -> winrt(line, v, o);
                case "sapi" -> sapi(line, v, o);
                case "mac" -> {
                    int wpm = (int) Math.round((175 + o.rate() * 25) * (1 + p[0] / 100.0));
                    run(v == null ? List.of("say", "-r", Integer.toString(wpm), line) : List.of("say", "-v", v.name(), "-r", Integer.toString(wpm), line));
                }
                case "espeak" -> {
                    if (kind == Kind.SPD) { run(List.of("spd-say", "-r", Integer.toString(o.rate() * 20 + p[0]), "-p", Integer.toString(o.pitch() * 10 + p[1]), line)); break; }
                    int speed = (int) Math.round((165 + o.rate() * 20) * (1 + p[0] / 100.0));
                    int pitch = Math.max(0, Math.min(99, 50 + o.pitch() * 6 + p[1]));
                    run(List.of(kind == Kind.ESPEAK ? "espeak" : "espeak-ng", "-v", v != null ? v.name() : o.accent().espeakVoice(), "-s", Integer.toString(speed),
                        "-p", Integer.toString(pitch), "-a", Integer.toString(Math.max(0, Math.min(200, o.volume() * 2))), line));
                }
                default -> { }
            }
        } catch (Throwable e) {
            status = "Text-to-speech failed: " + e.getMessage();
        }
    }

    private void run(List<String> cmd) throws Exception {
        stopCurrent();
        current = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private void stopCurrent() {
        Sink l = sink;                                                // [pa35] the natural voice's stream
        if (l != null) l.stop();
        if (current != null && current.isAlive()) current.destroy();
        current = null;
        javax.sound.sampled.Clip c = clip;
        if (c != null) { c.stop(); c.close(); clip = null; }
        for (Writer w : new Writer[]{sapiIn, winrtIn}) {
            if (w == null) continue;
            try {
                w.write("#stop\n");
                w.flush();
            } catch (Exception ignored) {
                // it has gone
            }
        }
    }

    // ------------------------------------------------------------------ Windows

    /** The classic voices (System.Speech): lines "#name N", "#volume n", "#stop", "#ssml <speak...>". */
    static final String SAPI_SCRIPT = "Add-Type -AssemblyName System.Speech;"
        + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
        + "$v=@($s.GetInstalledVoices()|%{$_.VoiceInfo.Name+'~'+$_.VoiceInfo.Culture.Name+'~'+$_.VoiceInfo.Gender});"
        + "[Console]::Out.WriteLine('VOICES:'+($v -join '|'));[Console]::Out.Flush();"
        + "while(($l=[Console]::In.ReadLine()) -ne $null){"
        + "if($l -eq '#stop'){$s.SpeakAsyncCancelAll();continue};"
        + "if($l.StartsWith('#name ')){try{$s.SelectVoice($l.Substring(6))}catch{};[Console]::Out.WriteLine('VOICE:'+$s.Voice.Name);[Console]::Out.Flush();continue};"
        + "if($l.StartsWith('#volume ')){$s.Volume=[int]$l.Substring(8);continue};"
        + "if($l.StartsWith('#ssml ')){$s.SpeakAsyncCancelAll();try{$null=$s.SpeakSsmlAsync($l.Substring(6))}catch{$null=$s.SpeakAsync(($l.Substring(6) -replace '<[^>]+>',''))};continue};"
        + "$s.SpeakAsyncCancelAll();$null=$s.SpeakAsync($l)}";

    /**
     * The modern voices (Windows.Media.SpeechSynthesis, OneCore), from Windows PowerShell 5.1: lists them as
     * "WVOICES:name~lang~gender|...", then for each SSML line synthesises to a stream and plays it (SoundPlayer).
     */
    static final String WINRT_SCRIPT = "$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
        + "$null=[Windows.Media.SpeechSynthesis.SpeechSynthesizer,Windows.Media.SpeechSynthesis,ContentType=WindowsRuntime];"
        + "$at=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0];"
        + "function W($op,$t){$k=$at.MakeGenericMethod($t).Invoke($null,@($op));$null=$k.Wait(-1);$k.Result};"
        + "$s=New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer;$all=[Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices;"
        + "[Console]::Out.WriteLine('WVOICES:'+(($all|%{$_.DisplayName+'~'+$_.Language+'~'+$_.Gender}) -join '|'));[Console]::Out.Flush();$p=$null;"
        + "while(($l=[Console]::In.ReadLine()) -ne $null){try{"
        + "if($l -eq '#stop'){if($p){$p.Stop()};continue};"
        + "if($l.StartsWith('#name ')){$n=$l.Substring(6);$m=$all|?{$_.DisplayName -eq $n}|select -First 1;if($m){$s.Voice=$m};[Console]::Out.WriteLine('VOICE:'+$s.Voice.DisplayName);[Console]::Out.Flush();continue};"
        + "$st=W ($s.SynthesizeSsmlToStreamAsync($l)) ([Windows.Media.SpeechSynthesis.SpeechSynthesisStream]);"
        + "$ms=New-Object System.IO.MemoryStream;[System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($st).CopyTo($ms);$ms.Position=0;"
        + "if($p){$p.Stop()};$p=New-Object System.Media.SoundPlayer($ms);$p.Play()"
        + "}catch{[Console]::Out.WriteLine('ERR:'+$_.Exception.Message);[Console]::Out.Flush()}}";

    private Writer start(String script, boolean modern) throws Exception {
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
            .redirectErrorStream(true).start();
        Writer in = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try {
                String l;
                while ((l = out.readLine()) != null) readLine(l);
            } catch (Exception ignored) {
                // it has gone
            }
            if (modern) winrtFailed = true;
        }, "mca-assistant-voice-out");
        reader.setDaemon(true);
        reader.start();
        if (modern) { winrt = p; winrtIn = in; } else { sapi = p; sapiIn = in; }
        return in;
    }

    /** A line from either PowerShell: the voice lists, the voice in use, or an error (the modern path then gives way). */
    void readLine(String l) {
        if (l.startsWith("VOICES:")) {
            List<VoiceInfo> sv = parseWindows("sapi", l.substring(7));
            List<String> names = new ArrayList<>();
            names.add("Default");
            for (VoiceInfo v : sv) names.add(v.name());
            voices = names;
            merge(sv, "sapi");
        } else if (l.startsWith("WVOICES:")) {
            merge(parseWindows("winrt", l.substring(8)), "winrt");
        } else if (l.startsWith("VOICE:")) {
            speaking = l.substring(6).trim();
        } else if (l.startsWith("ERR:")) {
            status = "Windows' modern voices failed (" + l.substring(4).trim() + "); using the classic ones.";
        }
    }

    private synchronized void merge(List<VoiceInfo> add, String engine) {
        List<VoiceInfo> all = new ArrayList<>();
        for (VoiceInfo v : catalogue) if (!v.engine().equals(engine)) all.add(v);
        all.addAll(add);
        all.sort((x, y) -> x.engine().equals(y.engine()) ? 0 : x.engine().equals("winrt") ? -1 : y.engine().equals("winrt") ? 1 : 0);
        catalogue = List.copyOf(all);
    }

    /** "Microsoft Hazel Desktop~en-GB~Female|Microsoft David Desktop~en-US~Male" into voices. */
    public static List<VoiceInfo> parseWindows(String engine, String list) {
        List<VoiceInfo> out = new ArrayList<>();
        if (list == null) return out;
        for (String part : list.split("\\|")) {
            String[] f = part.split("~", -1);
            if (f[0].isBlank()) continue;
            out.add(new VoiceInfo(engine, f[0].trim(), f.length > 1 ? f[1].trim() : "", f.length > 2 ? f[2].trim() : "", ""));
        }
        return out;
    }

    private void sapi(String line, VoiceInfo v, Opts o) throws Exception {
        Writer in = sapi != null && sapi.isAlive() ? sapiIn : start(SAPI_SCRIPT, false);
        if (v != null) in.write("#name " + v.name() + "\n");
        in.write("#volume " + Math.max(0, Math.min(100, o.volume())) + "\n");
        in.write("#ssml " + PaSpeech.ssml(line, v != null && !v.lang().isEmpty() ? v.lang() : o.accent().culture, o.gloom(), o.fun(), o.rate() * 8, o.pitch() * 5) + "\n");
        in.flush();
    }

    private void winrt(String line, VoiceInfo v, Opts o) throws Exception {
        if (winrtFailed) { sapi(line, null, o); return; }
        Writer in = winrt != null && winrt.isAlive() ? winrtIn : start(WINRT_SCRIPT, true);
        if (v != null) in.write("#name " + v.name() + "\n");
        in.write(PaSpeech.ssml(line, v != null && !v.lang().isEmpty() ? v.lang() : o.accent().culture, o.gloom(), o.fun(), o.rate() * 8, o.pitch() * 5) + "\n");
        in.flush();
    }

    // ------------------------------------------------------------------ Kokoro

    /** [pa35] The Kokoro voice for a line: the one picked, else George or Emma (British), Michael or Heart (American). */
    public static PaKokoro.Voice kokoroVoice(String voiceKey, PaAccent accent, boolean female) {
        if (voiceKey != null && voiceKey.startsWith("kokoro:")) {
            PaKokoro.Voice v = PaKokoro.byLabel(voiceKey.substring(7));
            if (v != null) return v;
        }
        return PaKokoro.byId(accent == PaAccent.UK ? (female ? "bf_emma" : "bm_george") : (female ? "af_heart" : "am_michael"));
    }

    /**
     * [pa35] Spoken a sentence at a time, so the first words start as soon as the first sentence is made: the words read by
     * its own dictionaries (PaG2p), the sound finished (PaAudio: trimmed, the mood's pitch, -16 LUFS, never clipping,
     * faded), and streamed to the speakers while the next sentence is made. A new line stops this one at once.
     */
    private void kokoro(String line, Opts o, int mine) throws Exception {
        PaKokoro.Voice kv = kokoroVoice(o.voiceKey(), o.accent(), o.female());
        if (kokoro == null || kv == null || !kokoro.downloaded()) { status = "The natural voice isn't downloaded yet."; return; }
        if (!kokoro.load()) { status = kokoro.why(); return; }
        String os = System.getProperty("os.name");
        String espeak = PaKokoro.espeak(os, PaTts::onPath, Files::isRegularFile);
        boolean british = kv.lang().equals("en-GB");
        PaG2p g2p = kokoro.g2p(british ? PaAccent.UK : PaAccent.US, espeak == null ? null : w -> {
            try { return PaG2p.fromEspeak(PaKokoro.cleanPhonemes(String.join(" ", lines(PaKokoro.phonemeCommand(espeak, british ? PaAccent.UK : PaAccent.US, w)))), british); }
            catch (Exception e) { return null; }
        });
        double pitch = PaAudio.pitchFactor(o.gloom(), o.fun(), o.pitch());
        float speed = (float) Math.max(0.5, Math.min(2.0, PaKokoro.speed(o.gloom(), o.rate()) / pitch));   // asked slower by the pitch lift, so the pace is kept
        Sink out = null;
        try {
            boolean first = true;
            for (String sentence : PaSpeech.sentences(line)) {
                if (gen.get() != mine) return;
                for (String chunk : chunks(g2p.phonemize(sentence))) {
                    if (gen.get() != mine) return;
                    long made = System.nanoTime();
                    float[] raw = kokoro.synth(chunk, kv, speed);
                    timedKokoro(System.nanoTime() - made, raw.length);           // [voice37] the tier's guess
                    if (raw.length == 0) continue;
                    float[] done = PaAudio.finish(raw, PaKokoro.SAMPLE_RATE, pitch);
                    char end = sentence.charAt(sentence.length() - 1);
                    float[] gap = PaAudio.silence(PaKokoro.SAMPLE_RATE, end == '?' || end == '!' ? 260 : end == '.' ? 220 : 120);
                    byte[] pcm = PaAudio.pcm16(concat(done, gap), o.volume());
                    if (out == null) {
                        stopCurrent();
                        out = sinks.open(PaKokoro.SAMPLE_RATE, speaker);     // [voice37] the game's sound, from the assistant
                        this.sink = out;
                    }
                    if (first) { first = false; onSound.accept(Math.round(done.length * 1000.0 / PaKokoro.SAMPLE_RATE)); }
                    out.write(pcm);
                }
            }
            if (out != null && gen.get() == mine) out.drain();
        } finally {
            if (out != null) { out.close(); if (this.sink == out) this.sink = null; }
        }
    }

    /** Phonemes cut into pieces the model takes (under 500), at a pause where there is one, else at a word. */
    static List<String> chunks(String ph) {
        List<String> out = new ArrayList<>();
        String rest = ph.trim();
        while (rest.codePointCount(0, rest.length()) > 480) {
            int cut = -1;
            for (int i = Math.min(rest.length() - 1, 470); i > 200 && cut < 0; i--) if (",;:".indexOf(rest.charAt(i)) >= 0) cut = i + 1;
            for (int i = Math.min(rest.length() - 1, 470); i > 100 && cut < 0; i--) if (rest.charAt(i) == ' ') cut = i;
            if (cut < 0) cut = 470;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    private static float[] concat(float[] a, float[] b) {
        float[] c = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    // ------------------------------------------------------------------ [voice37] the expressive voice

    /** The natural voice timed on a sentence of at least a second: the fastest kept (the tier's guess for the expressive one). */
    private void timedKokoro(long nanos, int samples) {
        if (samples < PaKokoro.SAMPLE_RATE) return;
        double rtf = nanos / 1e9 / (samples / (double) PaKokoro.SAMPLE_RATE);
        if (kokoroRtf <= 0 || rtf < kokoroRtf) { kokoroRtf = rtf; onKokoroRtf.accept(rtf); }
    }

    /**
     * A line performed by the expressive voice: its kind found from the phrase banks, its sounds placed for the personality
     * and humour (PaVoiceStyle), made a piece at a time (kept pieces from the line cache play at once) and streamed while
     * the next is made. False when it couldn't start (the natural voice then says the whole line); a failure part-way
     * stops it there. It is never tried again this session after a failure, until Settings says retry.
     */
    private boolean expressiveLine(String written, String line, Opts o, int mine) {
        Prefs p = prefs.get();
        Sink out = null;
        boolean played = false;
        try {
            if (!expressive.load(kokoro.dir().resolve("onnxruntime.jar"))) { expressiveFailed = true; status = expressive.why(); return false; }
            PaExpressive.Voice v = PaExpressive.pick(o.voiceKey(), o.female(), prefs.get().look());
            String key = PaVoiceStyle.keyOf(written);
            PaVoiceStyle.Kind kind = key != null ? PaVoiceStyle.kindOf(key) : PaVoiceStyle.guess(line);
            PaVoiceStyle.Style st = PaVoiceStyle.perform(line, kind, o.gloom(), o.fun(), p.humour(), p.expressiveness(), key != null && key.endsWith("@gloomy"), p.bondTier());
            double pitch = PaAudio.pitchFactor(0, 0, o.pitch());                   // its own performance carries the mood
            boolean first = true;
            for (String piece : PaVoiceStyle.chunks(st.text())) {
                if (gen.get() != mine) return true;
                float[] done = cache == null ? null : cache.get(v.id(), piece, st.temperature());
                if (done == null) {
                    float[] raw = expressive.synth(piece, v, new PaExpressive.Read(st.temperature(), 0), () -> gen.get() != mine);
                    if (gen.get() != mine) return true;
                    if (raw.length == 0) continue;
                    done = PaAudio.finish(raw, PaExpressive.SAMPLE_RATE, pitch);
                    if (cache != null) cache.put(v.id(), piece, st.temperature(), done);
                }
                char end = piece.charAt(piece.length() - 1);
                float[] gap = PaAudio.silence(PaExpressive.SAMPLE_RATE, (int) Math.round((end == '?' || end == '!' ? 240 : end == '.' ? 200 : 120) * st.gapScale()));
                byte[] pcm = PaAudio.pcm16(concat(done, gap), o.volume());
                if (out == null) {
                    stopCurrent();
                    out = sinks.open(PaExpressive.SAMPLE_RATE, speaker);
                    this.sink = out;
                }
                if (first) { first = false; onSound.accept(Math.round(done.length * 1000.0 / PaExpressive.SAMPLE_RATE)); }
                out.write(pcm);
                played = true;
            }
            if (out != null && gen.get() == mine) out.drain();
            return true;
        } catch (Throwable e) {
            expressiveFailed = true;
            status = "The expressive voice stopped (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "); the natural voice speaks instead.";
            return played;
        } finally {
            if (out != null) { out.close(); if (this.sink == out) this.sink = null; }
        }
    }

    /**
     * Make common lines ahead, while it's quiet, so they play at once (the line cache keeps them). Stops the moment a
     * line is asked for. Runs on its own low-priority thread.
     */
    public void prewarm(List<String> lines, Opts o) {
        if (expressive == null || cache == null || lines.isEmpty()) return;
        int startGen = gen.get();
        Thread t = new Thread(() -> {
            try {
                if (!expressiveFirst(o.voiceKey(), o.basic()) || !expressive.load(kokoro.dir().resolve("onnxruntime.jar"))) return;
                Prefs p = prefs.get();
                PaExpressive.Voice v = PaExpressive.pick(o.voiceKey(), o.female(), prefs.get().look());
                for (String l : lines) {
                    if (gen.get() != startGen) return;
                    String key = PaVoiceStyle.keyOf(l);
                    PaVoiceStyle.Style st = PaVoiceStyle.perform(clean(PaSpeech.speakable(l, Double.NaN, Double.NaN)), key == null ? PaVoiceStyle.Kind.PLAIN : PaVoiceStyle.kindOf(key),
                        o.gloom(), o.fun(), p.humour(), p.expressiveness(), key != null && key.endsWith("@gloomy"));
                    List<String> pieces = PaVoiceStyle.chunks(st.text());
                    if (pieces.size() != 1 || cache.get(v.id(), pieces.get(0), st.temperature()) != null) continue;
                    float[] raw = expressive.synth(pieces.get(0), v, new PaExpressive.Read(st.temperature(), 0), () -> gen.get() != startGen);
                    if (raw.length > 0 && gen.get() == startGen) cache.put(v.id(), pieces.get(0), st.temperature(), PaAudio.finish(raw, PaExpressive.SAMPLE_RATE, PaAudio.pitchFactor(0, 0, o.pitch())));
                }
            } catch (Throwable ignored) {
                // only a head start: the line is made when said
            }
        }, "mca-assistant-voice-prewarm");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ------------------------------------------------------------------ Piper

    private void piper(String line, VoiceInfo v, Opts o) throws Exception {
        String os = System.getProperty("os.name");
        PaPiper.Voice pv = null;
        for (PaPiper.Voice c : PaPiper.CATALOGUE) if (c.label().equals(v.name())) pv = c;
        if (piper == null || pv == null || !piper.installed(os, pv)) { status = "That natural voice isn't installed."; return; }
        stopCurrent();
        File wav = File.createTempFile("mca-voice", ".wav");
        wav.deleteOnExit();
        Process p = new ProcessBuilder(piper.command(os, pv, wav, PaPiper.lengthScale(o.gloom(), o.rate()))).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(line);
        }
        if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return; }
        try (javax.sound.sampled.AudioInputStream in = javax.sound.sampled.AudioSystem.getAudioInputStream(wav)) {
            javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
            clip.open(in);
            clip.start();
        }
    }

    // ------------------------------------------------------------------ listing

    private void loadVoices() {
        try {
            switch (kind) {
                case WINDOWS -> {
                    start(SAPI_SCRIPT, false);
                    try {
                        start(WINRT_SCRIPT, true);
                    } catch (Throwable e) {
                        winrtFailed = true;
                    }
                }
                case MAC -> {
                    List<VoiceInfo> mv = parseMac(lines(List.of("say", "-v", "?")));
                    merge(mv, "mac");
                    List<String> names = new ArrayList<>();
                    names.add("Default");
                    for (VoiceInfo v : mv) names.add(v.name());
                    voices = names;
                }
                case ESPEAK_NG, ESPEAK -> {
                    List<VoiceInfo> ev = parseEspeak(lines(List.of(kind == Kind.ESPEAK_NG ? "espeak-ng" : "espeak", "--voices=en")));
                    merge(ev, "espeak");
                    List<String> names = new ArrayList<>();
                    names.add("Default");
                    for (VoiceInfo v : ev) names.add(v.name());
                    voices = names;
                }
                default -> { }
            }
        } catch (Throwable ignored) {
            // the default voice only
        }
    }

    /** macOS {@code say -v ?}: "Daniel (Enhanced)  en_GB    # Hello..." (English voices only). */
    public static List<VoiceInfo> parseMac(List<String> lines) {
        List<VoiceInfo> out = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^(.+?)\\s{2,}([a-z]{2}_[A-Z]{2})\\s+#.*$");
        for (String l : lines) {
            java.util.regex.Matcher m = p.matcher(l.trim());
            if (!m.matches() || !m.group(2).startsWith("en")) continue;
            String name = m.group(1).trim();
            String q = name.contains("(Premium)") ? "Premium" : name.contains("(Enhanced)") ? "Enhanced" : "";
            out.add(new VoiceInfo("mac", name, m.group(2), "", q));
        }
        return out;
    }

    /** {@code espeak --voices=en}: "Pty Language Age/Gender VoiceName File ..." rows. */
    public static List<VoiceInfo> parseEspeak(List<String> lines) {
        List<VoiceInfo> out = new ArrayList<>();
        for (String l : lines) {
            String[] f = l.trim().split("\\s+");
            if (f.length < 5 || f[0].equals("Pty")) continue;
            String g = f[2].endsWith("M") ? "Male" : f[2].endsWith("F") ? "Female" : "";
            out.add(new VoiceInfo("espeak", f[1], f[1], g, ""));
        }
        return out;
    }

    private static List<String> lines(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null && out.size() < 400) out.add(l);
        }
        p.waitFor();
        return out;
    }

    /**
     * [pa33b] The optional cloud voice: a hook only, off by default, never needed. A player or operator may set
     * cloudVoice=true with their own key and endpoint in config/mc_assistant-assistant-client.toml; nothing is ever sent
     * without both. This build carries no client for any service: the hook answers that it is off.
     */
    public static String cloudStatus(boolean enabled, String key) {
        if (!enabled) return "Cloud voice: off (the default).";
        if (key == null || key.isBlank()) return "Cloud voice: on in the config, but there's no API key, so nothing is sent.";
        return "Cloud voice: configured, but this build doesn't include an online voice client; nothing is sent.";
    }
}
