package com.jrpetty.mcassistant.pa.voice;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * [pa33] The Player AI Assistant's voice: its replies spoken with the computer's own text-to-speech, never on the game
 * thread. Windows: the built-in speech synthesiser (System.Speech, through one long-lived PowerShell, so a line starts
 * speaking at once); macOS: {@code say}; Linux: {@code espeak-ng}, {@code espeak} or {@code spd-say}, whichever is there.
 * With none of them it is silent and says so (the words still show in the bubble and the talk log). Plain Java.
 */
public final class PaTts {

    public enum Kind { WINDOWS, MAC, ESPEAK_NG, ESPEAK, SPD, NONE }

    private final Kind kind;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mca-assistant-voice");
        t.setDaemon(true);
        return t;
    });
    private volatile List<String> voices = List.of();
    private volatile String status;
    private Process windows;
    private Writer windowsIn;
    private Process current;

    private PaTts(Kind kind, String status) {
        this.kind = kind;
        this.status = status;
    }

    /** Pick the backend for this OS, by what is installed ({@code onPath} answers "is this program there?"). */
    public static Kind pick(String osName, Predicate<String> onPath) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return onPath.test("powershell") ? Kind.WINDOWS : Kind.NONE;
        if (os.contains("mac") || os.contains("darwin")) return onPath.test("say") ? Kind.MAC : Kind.NONE;
        if (onPath.test("espeak-ng")) return Kind.ESPEAK_NG;
        if (onPath.test("espeak")) return Kind.ESPEAK;
        if (onPath.test("spd-say")) return Kind.SPD;
        return Kind.NONE;
    }

    /** The voice for this computer. Never throws. */
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

    /** A voice that never speaks (tests, and a computer with none). */
    public static PaTts none(String why) {
        return new PaTts(Kind.NONE, why);
    }

    static String label(Kind k) {
        return switch (k) {
            case WINDOWS -> "Windows' own voice";
            case MAC -> "macOS's own voice";
            case ESPEAK_NG -> "espeak-ng";
            case ESPEAK -> "espeak";
            case SPD -> "speech-dispatcher";
            case NONE -> "nothing";
        };
    }

    public Kind kind() { return kind; }

    public boolean available() { return kind != Kind.NONE; }

    public String status() { return status; }

    /** The computer's voices, by name (may be empty while they are being listed). */
    public List<String> voices() { return voices; }

    /** Is a program on the PATH (or, on Windows, PowerShell in its usual place)? */
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

    /** Say a line: queued on the voice's own thread; a new line cuts off the one before. Never blocks, never throws. */
    public void say(String text, int voice, int rate, int volume) {
        if (kind == Kind.NONE || text == null || text.isBlank()) return;
        String line = clean(text);
        try {
            worker.submit(() -> speak(line, voice, rate, volume));
        } catch (Throwable ignored) {
            // the worker has gone: stay silent
        }
    }

    /** Stop speaking now. */
    public void hush() {
        try {
            worker.submit(this::stopCurrent);
        } catch (Throwable ignored) {
            // silent anyway
        }
    }

    /** What goes to the speech engine: no emoji or symbols it would read out, one line. */
    static String clean(String text) {
        String t = text.replaceAll("[\\p{So}\\p{Cn}\\u2600-\\u27BF]", " ").replace('\n', ' ').replace('"', ' ').replaceAll("\\s+", " ").trim();
        return t.length() > 400 ? t.substring(0, 400) : t;
    }

    private void speak(String line, int voice, int rate, int volume) {
        try {
            switch (kind) {
                case WINDOWS -> windows(line, voice, rate, volume);
                case MAC -> run(voiceName(voice) == null ? List.of("say", "-r", Integer.toString(175 + rate * 25), line)
                    : List.of("say", "-v", voiceName(voice), "-r", Integer.toString(175 + rate * 25), line));
                case ESPEAK_NG, ESPEAK -> {
                    List<String> cmd = new ArrayList<>(List.of(kind == Kind.ESPEAK_NG ? "espeak-ng" : "espeak", "-s", Integer.toString(165 + rate * 20),
                        "-a", Integer.toString(Math.max(0, Math.min(200, volume * 2)))));
                    if (voiceName(voice) != null) { cmd.add("-v"); cmd.add(voiceName(voice)); }
                    cmd.add(line);
                    run(cmd);
                }
                case SPD -> run(List.of("spd-say", "-r", Integer.toString(rate * 20), "-i", Integer.toString(volume * 2 - 100), line));
                default -> { }
            }
        } catch (Throwable e) {
            status = "Text-to-speech failed: " + e.getMessage();
        }
    }

    private String voiceName(int i) {
        List<String> v = voices;
        return i > 0 && i < v.size() ? v.get(i) : null;
    }

    private void run(List<String> cmd) throws Exception {
        stopCurrent();
        current = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private void stopCurrent() {
        if (current != null && current.isAlive()) current.destroy();
        current = null;
        if (windowsIn != null) {
            try {
                windowsIn.write("#stop\n");
                windowsIn.flush();
            } catch (Exception ignored) {
                // it has gone
            }
        }
    }

    /** One PowerShell for the whole session, reading lines: "#voice n", "#rate n", "#volume n", "#stop", or a line to say. */
    private void windows(String line, int voice, int rate, int volume) throws Exception {
        if (windows == null || !windows.isAlive()) {
            String script = "Add-Type -AssemblyName System.Speech;"
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                + "$v=@($s.GetInstalledVoices()|%{$_.VoiceInfo.Name});"
                + "[Console]::Out.WriteLine('VOICES:'+($v -join '|'));[Console]::Out.Flush();"
                + "while(($l=[Console]::In.ReadLine()) -ne $null){"
                + "if($l -eq '#stop'){$s.SpeakAsyncCancelAll();continue};"
                + "if($l.StartsWith('#voice ')){$i=[int]$l.Substring(7);if($i -gt 0 -and $i -lt $v.Count){try{$s.SelectVoice($v[$i])}catch{}};continue};"
                + "if($l.StartsWith('#rate ')){$s.Rate=[int]$l.Substring(6);continue};"
                + "if($l.StartsWith('#volume ')){$s.Volume=[int]$l.Substring(8);continue};"
                + "$s.SpeakAsyncCancelAll();$null=$s.SpeakAsync($l)}";
            windows = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
                .redirectErrorStream(true).start();
            windowsIn = new OutputStreamWriter(windows.getOutputStream(), StandardCharsets.UTF_8);
            BufferedReader out = new BufferedReader(new InputStreamReader(windows.getInputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try {
                    String l;
                    while ((l = out.readLine()) != null) {
                        if (l.startsWith("VOICES:")) voices = List.of(l.substring(7).split("\\|"));
                    }
                } catch (Exception ignored) {
                    // it has gone
                }
            }, "mca-assistant-voice-out");
            reader.setDaemon(true);
            reader.start();
        }
        windowsIn.write("#voice " + voice + "\n#rate " + Math.max(-10, Math.min(10, rate * 2)) + "\n#volume " + Math.max(0, Math.min(100, volume)) + "\n");
        windowsIn.write(line + "\n");
        windowsIn.flush();
    }

    private void loadVoices() {
        try {
            switch (kind) {
                case WINDOWS -> windows("", 0, 0, 0);
                case MAC -> voices = withDefault(lines(List.of("say", "-v", "?")), l -> l.split("\\s{2,}")[0].trim());
                case ESPEAK_NG, ESPEAK -> voices = withDefault(lines(List.of(kind == Kind.ESPEAK_NG ? "espeak-ng" : "espeak", "--voices=en")),
                    l -> {
                        String[] f = l.trim().split("\\s+");
                        return f.length > 4 && !f[0].equals("Pty") ? f[4] : null;
                    });
                default -> { }
            }
        } catch (Throwable ignored) {
            // the default voice only
        }
    }

    private static List<String> withDefault(List<String> lines, java.util.function.Function<String, String> name) {
        List<String> out = new ArrayList<>();
        out.add("Default");
        for (String l : lines) {
            String n = name.apply(l);
            if (n != null && !n.isEmpty() && !out.contains(n)) out.add(n);
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
}
