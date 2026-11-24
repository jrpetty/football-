package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.pa.PaClientConfig;
import com.jrpetty.mcassistant.pa.PaLines;
import com.jrpetty.mcassistant.pa.voice.PaExpressive;
import com.jrpetty.mcassistant.pa.voice.PaTts;
import com.jrpetty.mcassistant.pa.voice.PaVoiceTier;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * [voice37] The expressive voice on the player's computer: its tier (Settings > Voice: Auto, Expressive, Natural, Basic),
 * its download (by itself on Auto only when this computer is quick enough, a card with a progress bar and a one-click
 * "Don't download", resumed and checked like the natural voice's), its benchmark, and its personality (corner, humour,
 * expressiveness) handed to PaTts with every line. Client-side only; a server never starts or stops it.
 */
public final class PaVoiceExpressive {

    private PaVoiceExpressive() {}

    static volatile boolean installing, failed, benchmarking;
    static volatile double fraction;
    static volatile String line = "";
    static volatile long endedAt;
    private static boolean started;
    private static PaTts tts;

    /** Wire the expressive voice into the speech engine (once, when it is made). */
    static void attach(PaTts t, Path voiceDir) {
        tts = t;
        t.expressive(voiceDir.resolve("expressive"), voiceDir.resolve("cache"));
        t.kokoroRtf(PaClientConfig.get(PaClientConfig.KOKORORTF, -1.0));
        t.onKokoroRtf(r -> Minecraft.getInstance().execute(() -> PaClientConfig.set(PaClientConfig.KOKORORTF, Math.round(r * 1000) / 1000.0)));
        t.prefs(PaVoiceExpressive::prefs);
    }

    public static PaVoiceTier.Setting tier() {
        return PaVoiceTier.Setting.of(PaClientConfig.get(PaClientConfig.VOICETIER, "auto"));
    }

    /** The Basic tier: the computer's own voice, as if "System voice (basic)" were on. */
    static boolean basicTier() {
        return tier() == PaVoiceTier.Setting.BASIC;
    }

    static double rtf() {
        return PaClientConfig.get(PaClientConfig.EXPRESSIVERTF, -1.0);
    }

    /** What PaTts asks before each line: the tier, its humour (from the server's settings), the slider, the benchmark. */
    static PaTts.Prefs prefs() {
        int humour = 0;
        try {
            var set = PaClient.state.getCompound("set");
            humour = set.getInt("humour");
        } catch (Throwable ignored) {
            // no state yet: Dry
        }
        return new PaTts.Prefs(tier(), humour, PaClientConfig.get(PaClientConfig.EXPRESSIVENESS, 50), rtf());
    }

    static boolean ready() {
        return tts != null && tts.expressiveReady();
    }

    /** An offline expressive pack in the config folder: unpacked and checked (off the game thread, with the natural one's). */
    static void importPack(Path... folders) {
        if (tts == null || tts.expressive() == null) return;
        if (tts.expressive().files().importPack(folders) && rtf() < 0) benchmark(false);
    }

    /** On Auto, fetch it by itself when this computer is quick enough (the natural voice's timing is the guess). */
    static void maybeAutoDownload(boolean hasAssistant) {
        if (tts == null || tts.expressive() == null) return;
        if (tts.expressiveReady() && rtf() < 0 && !benchmarking) { benchmark(false); return; }
        boolean go = PaVoiceTier.autoDownload(hasAssistant, started, PaClientConfig.get(PaClientConfig.EXPRESSIVEPACK, "auto"), tier(), tts.kokoroReady(),
            tts.expressive().downloaded(), installing, tts.kokoroRtf(), rtf());
        if (!go) return;
        install();
        try {
            net.minecraft.client.gui.components.toasts.SystemToast.add(Minecraft.getInstance().getToasts(),
                net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                net.minecraft.network.chat.Component.literal("Fetching your assistant's expressive voice"),
                net.minecraft.network.chat.Component.literal("About " + PaExpressive.totalMb() + " MB, once. Your computer is quick enough for it."));
        } catch (Throwable ignored) {
            // the card still shows
        }
    }

    /** Download it now (Settings' button, or Auto), then time it on this computer. */
    static void install() {
        if (installing || tts == null || tts.expressive() == null) return;
        started = true;
        installing = true;
        failed = false;
        fraction = 0;
        line = "Expressive voice: starting...";
        Thread t = new Thread(() -> {
            boolean ok = tts.expressive().files().install(com.jrpetty.mcassistant.pa.voice.PaKokoro.HTTP, (f, l) -> { fraction = f; line = l; });
            boolean cancelled = !installing;
            installing = false;
            endedAt = System.currentTimeMillis();
            if (cancelled) return;
            if (!ok) { failed = true; line = tts.expressive().files().why(); PaVoice.say(line); return; }
            fraction = 1;
            line = "Expressive voice downloaded. Timing it on this computer...";
            benchmark(true);
        }, "mca-assistant-voice-expressive-download");
        t.setDaemon(true);
        t.start();
    }

    /** "Don't download": stops now (what's down is kept) and never starts by itself again. */
    static void decline() {
        PaClientConfig.set(PaClientConfig.EXPRESSIVEPACK, "declined");
        if (tts != null && tts.expressive() != null) tts.expressive().files().cancel();
        installing = false;
        endedAt = System.currentTimeMillis();
        line = "Expressive voice: not downloading. Settings > Voice can fetch it any time.";
    }

    /** Time it (a warm-up line and one more), keep the figure, and say how it went; announce it in its new voice if it's chosen. */
    static void benchmark(boolean announce) {
        if (benchmarking || tts == null) return;
        benchmarking = true;
        Thread t = new Thread(() -> {
            try {
                if (!tts.expressive().load(tts.kokoro().dir().resolve("onnxruntime.jar"))) { line = tts.expressive().why(); failed = true; return; }
                double r = tts.expressive().benchmark(() -> false);
                Minecraft.getInstance().execute(() -> PaClientConfig.set(PaClientConfig.EXPRESSIVERTF, Math.round(r * 1000) / 1000.0));
                line = PaVoiceTier.verdict(r, tts.kokoroRtf());
                endedAt = System.currentTimeMillis();
                if (announce && (tier() == PaVoiceTier.Setting.EXPRESSIVE || r <= PaVoiceTier.EXPRESSIVE_RTF)) {
                    PaClientConfig.set(PaClientConfig.VOICEREADYSAID, false);
                    PaVoice.readyNow();
                }
                Minecraft.getInstance().execute(PaVoiceExpressive::prewarm);
            } catch (Throwable e) {
                failed = true;
                line = "Couldn't time the expressive voice: " + e.getMessage();
            } finally {
                benchmarking = false;
            }
        }, "mca-assistant-voice-benchmark");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Make its most common short lines ahead (acknowledgements, greetings), so they play at once. */
    static void prewarm() {
        if (tts == null) return;
        List<String> lines = new ArrayList<>();
        for (String key : new String[]{"say.ok", "say.follow", "say.stay", "say.come", "say.stop", "say.done", "say.thanks", "say.greet"}) {
            for (String l : PaLines.get(key)) if (!l.contains("{") && l.length() <= 60 && lines.size() < 24) { lines.add(l); break; }
        }
        var set = PaClient.state.getCompound("set");
        var tri = com.jrpetty.mcassistant.pa.PaPersona.Tri.of(set.contains("funny") ? set.getFloat("funny") : 0.5, set.contains("gloomy") ? set.getFloat("gloomy") : 0.2);
        tts.prewarm(lines, new PaTts.Opts(PaClientConfig.get(PaClientConfig.VOICEKEY, ""), 0, PaClientConfig.get(PaClientConfig.PITCH, 0),
            PaClientConfig.get(PaClientConfig.VOLUME, 80), tri.gloomy(), tri.funny(), PaVoice.accent(), Double.NaN, Double.NaN,
            PaClientConfig.get(PaClientConfig.BASICVOICE, false), PaClientConfig.get(PaClientConfig.VOICEWOMAN, false)));
    }

    /** The card is up (downloading, or a few seconds after). */
    static boolean showing() {
        return installing || endedAt > 0 && System.currentTimeMillis() - endedAt < 6000L;
    }

    /** Settings' status line. */
    static String status() {
        if (tts == null || tts.expressive() == null) return "";
        if (installing) return line;
        if (benchmarking) return "Timing it on this computer...";
        String trouble = tts.expressiveTrouble();
        if (!trouble.isEmpty()) return trouble + " The natural voice is speaking instead.";
        if (tts.expressiveReady()) return PaVoiceTier.verdict(rtf(), tts.kokoroRtf());
        if (failed) return line;
        return PaVoiceTier.verdict(-1, tts.kokoroRtf());
    }
}
