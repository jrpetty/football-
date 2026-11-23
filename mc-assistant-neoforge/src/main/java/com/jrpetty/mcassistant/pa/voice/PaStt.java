package com.jrpetty.mcassistant.pa.voice;

import com.jrpetty.mcassistant.pa.PaUnderstand;
import com.jrpetty.mcassistant.pa.PaWords;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * [pa33] Listening: what the player said, as text, by whichever offline recogniser this computer has. On Windows the
 * built-in one (System.Speech, through PowerShell: nothing to download) when it is there, else Vosk (all OSes, once
 * its model is downloaded with the player's agreement). Only the recognised text ever leaves the player's computer,
 * as the same order a typed one is. Every failure is a message, never a crash; typing keeps working.
 */
public final class PaStt {

    public enum Engine { WINDOWS, VOSK, NONE }

    /** What was heard: the text, by which recogniser, and its two readings (held to the orders' words, and free). */
    public record Heard(String text, Engine by, String held, String free, String error) {}

    private final Path dir;
    private final String preference;
    private final boolean windowsHere;
    private final PaVosk vosk;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mca-assistant-listen");
        t.setDaemon(true);
        return t;
    });
    private volatile PaVosk.Session held, free;

    public PaStt(Path dir, String preference, String osName, Predicate<String> onPath) {
        this.dir = dir;
        this.preference = preference == null ? "auto" : preference.toLowerCase(Locale.ROOT);
        this.windowsHere = (osName == null ? "" : osName.toLowerCase(Locale.ROOT)).contains("win") && onPath.test("powershell");
        this.vosk = new PaVosk(dir.resolve("vosk"));
    }

    public PaVosk vosk() { return vosk; }

    /** Which recogniser would hear, given what is installed. */
    public Engine engine() {
        boolean v = vosk.installed();
        return switch (preference) {
            case "windows" -> windowsHere ? Engine.WINDOWS : v ? Engine.VOSK : Engine.NONE;
            case "vosk" -> v ? Engine.VOSK : windowsHere ? Engine.WINDOWS : Engine.NONE;
            default -> windowsHere ? Engine.WINDOWS : v ? Engine.VOSK : Engine.NONE;
        };
    }

    /** Is there any recogniser at all? */
    public boolean ready() { return engine() != Engine.NONE; }

    public String status() {
        return switch (engine()) {
            case WINDOWS -> "Listening with Windows' own speech recognition (offline).";
            case VOSK -> "Listening with the offline speech model.";
            case NONE -> windowsHere ? "Windows' speech recognition isn't available here." : "No speech model yet: turn listening on in the About tab to download it (about 40 MB, once).";
        };
    }

    /** The key went down: start sessions that hear as it goes (Vosk gives a live transcript). */
    public void begin() {
        held = null;
        free = null;
        if (engine() == Engine.VOSK) {
            try {
                held = vosk.session(words());
                free = vosk.session(null);
            } catch (Throwable ignored) {
                held = null;
                free = null;
            }
        }
    }

    /** A chunk of sound while the key is held (from the microphone's thread). */
    public void feed(byte[] pcm) {
        PaVosk.Session h = held, f = free;
        try {
            if (h != null) h.feed(pcm);
            if (f != null) f.feed(pcm);
        } catch (Throwable ignored) {
            // the live transcript is a nicety; the whole recording is heard again at the end
        }
    }

    /** The live transcript, while the key is held ("" when the recogniser can't tell as it goes). */
    public String partial() {
        PaVosk.Session f = free;
        try {
            return f == null ? "" : f.partial();
        } catch (Throwable e) {
            return "";
        }
    }

    /** The key came up: what was said, off the game thread. */
    public CompletableFuture<Heard> end(byte[] pcm) {
        PaVosk.Session h = held, f = free;
        held = null;
        free = null;
        return CompletableFuture.supplyAsync(() -> hear(pcm, h, f), worker);
    }

    private Heard hear(byte[] pcm, PaVosk.Session h, PaVosk.Session f) {
        if (pcm.length < 16000 * 2 / 5) return new Heard("", Engine.NONE, "", "", "That was too short to hear: hold the key while you speak.");
        try {
            return switch (engine()) {
                case VOSK -> {
                    String a = h != null ? h.finish() : "";
                    String b = f != null ? f.finish() : "";
                    if (h == null && f == null) {
                        PaVosk.Session h2 = vosk.session(words()), f2 = vosk.session(null);
                        if (h2 == null) yield new Heard("", Engine.VOSK, "", "", vosk.status());
                        h2.feed(pcm);
                        f2.feed(pcm);
                        a = h2.finish();
                        b = f2.finish();
                    }
                    yield new Heard(choose(a, b), Engine.VOSK, a, b, "");
                }
                case WINDOWS -> {
                    String t = windows(pcm);
                    yield new Heard(t, Engine.WINDOWS, t, t, t.isEmpty() ? "I didn't catch that. Try again, a little slower?" : "");
                }
                case NONE -> new Heard("", Engine.NONE, "", "", status());
            };
        } catch (Throwable e) {
            return new Heard("", Engine.NONE, "", "", "Listening failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** Of the two readings, the one that makes an order (held to the orders' words), else a question (free), else the free one. */
    public static String choose(String held, String free) {
        held = held == null ? "" : held.trim();
        free = free == null ? "" : free.trim();
        PaUnderstand.Reading f = free.isEmpty() ? null : PaUnderstand.read(free, "");
        if (f != null && f.understood() && f.steps().get(0).kind() == com.jrpetty.mcassistant.pa.PaIntent.Kind.QUESTION) return free;
        PaUnderstand.Reading h = held.isEmpty() ? null : PaUnderstand.read(held, "");
        if (h != null && h.understood()) return held;
        if (f != null && f.understood()) return free;
        return !free.isEmpty() ? free : held;
    }

    /** The words the held recogniser listens for: everything the reader knows, and numbers. */
    public static Set<String> words() {
        Set<String> w = new LinkedHashSet<>();
        w.addAll(PaWords.VOCAB);
        w.addAll(PaWords.COMMON);
        for (String e : PaUnderstand.EXAMPLES) for (String s : e.split(" ")) w.add(s);
        for (String n : List.of("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty",
            "sixty", "seventy", "eighty", "ninety", "hundred", "a", "an", "the", "of", "to", "in", "on", "at", "by", "my", "me", "i",
            "it", "is", "please", "hey", "some", "stack", "stacks", "half", "dozen", "couple", "few", "them", "all", "for", "up", "out",
            "iron", "pip", "assistant", "small", "house", "chest", "your", "are", "do", "can", "you", "what", "how", "until", "have",
            "level", "minus", "negative", "x", "area", "logs", "wood", "stone", "cobblestone")) w.add(n);
        w.removeIf(s -> s.isEmpty() || !s.matches("[a-z']+"));
        return w;
    }

    // ------------------------------------------------------------------ Windows' own recogniser

    private String windows(byte[] pcm) throws Exception {
        Files.createDirectories(dir);
        Path wav = dir.resolve("heard.wav");
        Files.write(wav, PaWav.wav(pcm, 16000));
        Path phrases = dir.resolve("phrases.txt");
        List<String> lines = new ArrayList<>(PaUnderstand.EXAMPLES);
        Files.write(phrases, lines, StandardCharsets.UTF_8);
        String script = "Add-Type -AssemblyName System.Speech;"
            + "$r=New-Object System.Speech.Recognition.SpeechRecognitionEngine;"
            + "$r.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar));"
            + "$c=New-Object System.Speech.Recognition.Choices;"
            + "foreach($p in Get-Content -LiteralPath '" + phrases.toAbsolutePath().toString().replace("'", "''") + "'){if($p){$c.Add($p)}};"
            + "$g=New-Object System.Speech.Recognition.GrammarBuilder;$g.Append($c);"
            + "$r.LoadGrammar((New-Object System.Speech.Recognition.Grammar($g)));"
            + "$r.SetInputToWaveFile('" + wav.toAbsolutePath().toString().replace("'", "''") + "');"
            + "$t=@();while($x=$r.Recognize()){$t+=$x.Text};[Console]::Out.WriteLine('HEARD:'+($t -join ' '))";
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
            .redirectErrorStream(true).start();
        String heard = "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) if (l.startsWith("HEARD:")) heard = l.substring(6).trim();
        }
        if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
        return heard.toLowerCase(Locale.ROOT);
    }
}
