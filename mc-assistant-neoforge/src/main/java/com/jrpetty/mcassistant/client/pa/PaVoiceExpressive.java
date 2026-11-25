package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.pa.PaClientConfig;
import com.jrpetty.mcassistant.pa.PaLines;
import com.jrpetty.mcassistant.pa.voice.PaExpressive;
import com.jrpetty.mcassistant.pa.voice.PaOrt;
import com.jrpetty.mcassistant.pa.voice.PaTts;
import com.jrpetty.mcassistant.pa.voice.PaVoiceAssets;
import com.jrpetty.mcassistant.pa.voice.PaVoiceTier;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * [voice37] The expressive voice on the player's computer: its tier (Settings > Voice: Auto, Expressive, Natural, Basic),
 * its files (inside the mod jar; unpacked into the game folder only when the tier wants it: on Auto, when the natural
 * voice's timing says this computer is quick enough), its benchmark (a computer too slow keeps the natural voice and
 * the models are let go), a warm-up when the player logs in, the common short lines made ahead for every voice, and its
 * personality (corner, humour, expressiveness, bond, look) handed to PaTts with every line. Client-side only.
 */
public final class PaVoiceExpressive {

    private PaVoiceExpressive() {}

    static volatile boolean installing, failed, benchmarking;
    static volatile double fraction;
    static volatile String line = "";
    static volatile long endedAt;
    private static boolean started, warmed;
    private static PaTts tts;
    private static boolean bundled;

    /** Wire the expressive voice into the speech engine (once, when it is made); home: where this version unpacks. */
    static void attach(PaTts t, Path home, List<PaVoiceAssets.Entry> pack) {
        tts = t;
        bundled = !pack.isEmpty();
        PaExpressive e = bundled ? new PaExpressive(home.resolve("expressive"), PaVoiceAssets.parts(pack, "expressive")) : new PaExpressive(home.resolve("expressive"));
        t.expressive(e, PaVoice.dir().resolve("cache"));
        t.kokoroRtf(PaClientConfig.get(PaClientConfig.KOKORORTF, -1.0));
        t.onKokoroRtf(r -> Minecraft.getInstance().execute(() -> PaClientConfig.set(PaClientConfig.KOKORORTF, Math.round(r * 1000) / 1000.0)));
        t.prefs(PaVoiceExpressive::prefs);
        t.sinks(PaVoiceSound.SINKS);                                    // from the assistant, through the game's sound system
        PaExpressive.coreMl(PaClientConfig.get(PaClientConfig.COREML, false));
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

    /** What PaTts asks before each line (from any thread): the tier, humour, slider, benchmark, bond and look. */
    static PaTts.Prefs prefs() {
        int humour = 0, bond = 1, look = -1;
        try {
            var st = PaClient.state;
            humour = st.getCompound("set").getInt("humour");
            if (st.contains("bondTier")) bond = st.getInt("bondTier");
            if (st.contains("look")) look = st.getInt("look");
        } catch (Throwable ignored) {
            // no state yet: Dry, a Mate, no look
        }
        return new PaTts.Prefs(tier(), humour, PaClientConfig.get(PaClientConfig.EXPRESSIVENESS, 50), rtf(), bond, look);
    }

    static boolean ready() {
        return tts != null && tts.expressiveReady();
    }

    /** An offline expressive pack in the config folder (a development build's way in): unpacked and checked. */
    static void importPack(Path... folders) {
        if (tts == null || tts.expressive() == null || bundled) return;
        tts.expressive().files().importPack(folders);
    }

    /**
     * Asked whenever the state comes: on login, warm it up (or time it, the first time); and when the tier wants the
     * expressive voice and it isn't unpacked yet, unpack it from the jar.
     */
    static void maybeAutoDownload(boolean hasAssistant) {
        if (tts == null || tts.expressive() == null || !hasAssistant) return;
        boolean wants = PaVoiceTier.offerExpressive(tier(), tts.kokoroRtf(), rtf());
        if (tts.expressiveReady()) {
            if (!wants) return;                                         // lazy: never loaded unless its tier would use it
            if (rtf() < 0 && !benchmarking) benchmark(false);
            else if (!warmed && rtf() > 0) warm();
            return;
        }
        boolean go = PaVoiceTier.autoDownload(true, started, PaClientConfig.get(PaClientConfig.EXPRESSIVEPACK, "auto"), tier(), tts.kokoroReady(),
            tts.expressive().downloaded(), installing, tts.kokoroRtf(), rtf()) && bundled;
        if (!go) return;
        install();
        try {
            net.minecraft.client.gui.components.toasts.SystemToast.add(Minecraft.getInstance().getToasts(),
                net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                net.minecraft.network.chat.Component.literal("Unpacking your assistant's expressive voice"),
                net.minecraft.network.chat.Component.literal("Once, from the mod itself: your computer is quick enough for it."));
        } catch (Throwable ignored) {
            // the card still shows
        }
    }

    /** Unpack it now (Settings' button, or Auto), then time it on this computer. Never from the network. */
    static void install() {
        if (installing || tts == null || tts.expressive() == null) return;
        if (!bundled) { PaVoice.say("This build of the mod has no expressive voice inside it."); return; }
        started = true;
        installing = true;
        failed = false;
        fraction = 0;
        line = "Expressive voice: unpacking...";
        Thread t = new Thread(() -> {
            boolean ok = tts.expressive().files().install(PaVoiceAssets.fromJar(), (f, l) -> { fraction = f; line = l; });
            boolean cancelled = !installing;
            installing = false;
            endedAt = System.currentTimeMillis();
            if (cancelled) return;
            if (!ok) { failed = true; line = tts.expressive().files().why(); PaVoice.say(line); return; }
            fraction = 1;
            line = "Expressive voice unpacked. Timing it on this computer...";
            benchmark(true);
        }, "mca-assistant-voice-expressive-unpack");
        t.setDaemon(true);
        t.start();
    }

    /** "Not now": stops now (what's out is kept) and never starts by itself again. */
    static void decline() {
        PaClientConfig.set(PaClientConfig.EXPRESSIVEPACK, "declined");
        if (tts != null && tts.expressive() != null) tts.expressive().files().cancel();
        installing = false;
        endedAt = System.currentTimeMillis();
        line = "Expressive voice: not unpacking. Settings > Voice can unpack it any time.";
    }

    /**
     * Time it (a warm-up line and one more) and keep the figure. Too slow for Auto: the models are let go (their memory
     * freed) and the natural voice speaks. Quick enough: announced in its new voice, and the common lines made ahead.
     */
    static void benchmark(boolean announce) {
        if (benchmarking || tts == null) return;
        benchmarking = true;
        Thread t = new Thread(() -> {
            try {
                if (!tts.expressive().load(PaOrt.jar(tts.kokoro().dir()))) { line = tts.expressive().why(); failed = true; return; }
                double r = tts.expressive().benchmark(() -> false);
                Minecraft.getInstance().execute(() -> PaClientConfig.set(PaClientConfig.EXPRESSIVERTF, Math.round(r * 1000) / 1000.0));
                line = PaVoiceTier.verdict(r, tts.kokoroRtf());
                endedAt = System.currentTimeMillis();
                boolean use = tier() == PaVoiceTier.Setting.EXPRESSIVE || tier() == PaVoiceTier.Setting.AUTO && r <= PaVoiceTier.EXPRESSIVE_RTF;
                if (!use) { tts.expressive().unload(); return; }
                warmed = true;
                if (announce) {
                    Minecraft.getInstance().execute(() -> { PaClientConfig.set(PaClientConfig.VOICEREADYSAID, false); PaVoice.readyNow(); });
                }
                Minecraft.getInstance().execute(PaVoiceExpressive::prewarm);
            } catch (Throwable e) {                                     // out of memory included: the natural voice carries on
                failed = true;
                line = "Couldn't time the expressive voice: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                try { tts.expressive().unload(); } catch (Throwable ignored) { }
            } finally {
                benchmarking = false;
            }
        }, "mca-assistant-voice-benchmark");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** At login: load the models and make one short line, so the first real line isn't kept waiting. */
    static void warm() {
        warmed = true;
        Thread t = new Thread(() -> {
            try {
                if (!tts.expressive().load(PaOrt.jar(tts.kokoro().dir()))) return;
                tts.expressive().synth("Right.", PaExpressive.VOICES.get(0), new PaExpressive.Read(0.7f, 1), () -> false);
                Minecraft.getInstance().execute(PaVoiceExpressive::prewarm);
            } catch (Throwable ignored) {
                // the first line pays instead
            }
        }, "mca-assistant-voice-warm");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** The most common short lines (acknowledgements, greetings), made ahead for every voice, the player's own first. */
    static void prewarm() {
        if (tts == null) return;
        List<String> lines = new ArrayList<>();
        for (String key : new String[]{"say.ok", "say.follow", "say.stay", "say.come", "say.stop", "say.done", "say.thanks", "say.greet", "say.guard", "say.bye"}) {
            int n = 0;
            for (String l : PaLines.get(key)) if (!l.contains("{") && l.length() <= 60 && n < 2) { lines.add(l); n++; }
        }
        var set = PaClient.state.getCompound("set");
        var tri = com.jrpetty.mcassistant.pa.PaPersona.Tri.of(set.contains("funny") ? set.getFloat("funny") : 0.5, set.contains("gloomy") ? set.getFloat("gloomy") : 0.2);
        boolean woman = PaClientConfig.get(PaClientConfig.VOICEWOMAN, false);
        String mine = PaClientConfig.get(PaClientConfig.VOICEKEY, "");
        List<String> keys = new ArrayList<>();
        keys.add(mine.startsWith("expressive:") ? mine : "expressive:" + PaExpressive.pick(mine, woman, prefs().look()).label());
        for (PaExpressive.Voice v : PaExpressive.VOICES) if (!keys.contains("expressive:" + v.label())) keys.add("expressive:" + v.label());
        for (String k : keys) {
            tts.prewarm(lines, new PaTts.Opts(k, 0, PaClientConfig.get(PaClientConfig.PITCH, 0), PaClientConfig.get(PaClientConfig.VOLUME, 80), tri.gloomy(), tri.funny(),
                PaVoice.accent(), Double.NaN, Double.NaN, false, woman));
        }
    }

    /** The card is up (unpacking, or a few seconds after). */
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
        if (!bundled) return "This build has no expressive voice inside it (a release build does).";
        return PaVoiceTier.verdict(-1, tts.kokoroRtf());
    }

    static boolean bundled() { return bundled; }
}
