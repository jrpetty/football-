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
                case "kokoro" -> "Natural (Kokoro)";
                case "piper" -> "Natural (Piper)";
                case "winrt" -> "Windows (modern)";
                case "sapi" -> "Windows (classic)";
                case "mac" -> quality.isEmpty() ? "macOS" : "macOS (" + quality + ")";
                default -> "Basic";
            };
        }

        public String label() {
            String l = lang.toLowerCase(Locale.ROOT).replace('_', '-');
            String accent = l.startsWith("en-gb") ? "British" : l.startsWith("en-us") ? "American" : l.startsWith("en-au") ? "Australian"
                : l.startsWith("en-ie") ? "Irish" : l.startsWith("en-in") ? "Indian" : l.startsWith("en") ? "English" : lang;
            return name + "  ·  " + (gender.isEmpty() ? "" : gender.toLowerCase(Locale.ROOT) + ", ") + accent;
        }
    }

    /** What each line is said with (the player's choices, the mood, where the player stands for "forty blocks east"). */
    public record Opts(String voiceKey, int rate, int pitch, int volume, double gloom, double fun, PaAccent accent, double px, double pz) {}

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

    public PaKokoro kokoro() { return kokoro; }

    /** [pa33b] Kokoro is ready to speak: downloaded, and espeak-ng there to read the words. */
    public boolean kokoroReady() {
        return kokoro != null && kokoro.downloaded() && PaKokoro.espeak(System.getProperty("os.name"), PaTts::onPath, Files::isRegularFile) != null;
    }

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
        if (text == null || text.isBlank()) return;
        String line = clean(PaSpeech.speakable(text, o.px(), o.pz()));
        if (line.isEmpty()) return;
        try {
            worker.submit(() -> speak0(line, o));
        } catch (Throwable ignored) {
            // the worker has gone: stay silent
        }
    }

    public void hush() {
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
        String[] order = {"kokoro", "piper", "winrt", "sapi", "mac"};
        for (String eng : order) {
            VoiceInfo best = null;
            for (VoiceInfo v : all) {
                if (!v.engine().equals(eng) || !v.lang().toLowerCase(Locale.ROOT).replace('_', '-').startsWith(want)) continue;
                if (best == null || score(v) > score(best)) best = v;
            }
            if (best != null) return best;
        }
        return null;
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

    private void speak0(String line, Opts o) {
        try {
            VoiceInfo v = find(o.voiceKey());
            if (v == null) v = auto(o.accent());
            String engine = v != null ? v.engine() : kind == Kind.WINDOWS ? "sapi" : kind == Kind.MAC ? "mac" : kind == Kind.NONE ? "" : "espeak";
            int[] p = PaSpeech.prosody(o.gloom(), o.fun());
            switch (engine) {
                case "kokoro" -> kokoro(line, v, o);
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

    private void kokoro(String line, VoiceInfo v, Opts o) throws Exception {
        String os = System.getProperty("os.name");
        PaKokoro.Voice kv = PaKokoro.byLabel(v.name());
        String espeak = PaKokoro.espeak(os, PaTts::onPath, Files::isRegularFile);
        if (kokoro == null || kv == null || !kokoro.downloaded()) { status = "That natural voice isn't downloaded."; return; }
        if (espeak == null) { status = PaKokoro.espeakAdvice(os); return; }
        if (!kokoro.load()) { status = kokoro.why(); return; }
        // the words as phonemes, clause by clause, joined by the punctuation Kokoro pauses at; spoken a sentence at a time
        List<String> chunks = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (String[] c : PaKokoro.clauses(line)) {
            String ph = PaKokoro.cleanPhonemes(String.join(" ", lines(PaKokoro.phonemeCommand(espeak, o.accent(), c[0]))));
            if (ph.isEmpty()) continue;
            if (b.length() + ph.length() > 400) { chunks.add(b.toString().trim()); b.setLength(0); }
            b.append(ph).append(c[1]).append(' ');
        }
        if (b.length() > 0) chunks.add(b.toString().trim());
        float speed = PaKokoro.speed(o.gloom(), o.rate());
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        for (String ch : chunks) pcm.write(PaKokoro.pcm16(kokoro.synth(ch, kv, speed), o.volume()));
        if (pcm.size() == 0) return;
        stopCurrent();
        javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(PaKokoro.SAMPLE_RATE, 16, 1, true, false);
        javax.sound.sampled.Clip c = javax.sound.sampled.AudioSystem.getClip();
        byte[] data = pcm.toByteArray();
        c.open(fmt, data, 0, data.length);
        c.start();
        clip = c;
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
