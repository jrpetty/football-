package com.jrpetty.mcassistant.pa.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [voice37] Which voice engine a computer gets. Settings > Voice: Auto (the default), Expressive, Natural or Basic.
 *
 * <p>Auto: a quick benchmark decides. Before the expressive voice is downloaded, the natural voice (Kokoro, already
 * there) times one sentence; the expressive voice takes about EXPRESSIVE_PER_KOKORO times as long on the same computer,
 * so a computer that would keep up is offered it. Once downloaded, it times itself (PaExpressive.benchmark) and that
 * real figure decides. A weak computer keeps the natural voice; the basic system voice is never chosen unless picked.
 * Any failure of the expressive voice falls back to the natural one at once. Pure Java.
 */
public final class PaVoiceTier {

    private PaVoiceTier() {}

    public enum Setting {
        AUTO("Auto", "The best voice this computer can keep up with"),
        EXPRESSIVE("Expressive", "Laughs, sighs, a real performance. Needs a fast computer"),
        NATURAL("Natural", "Kokoro: clear and human, light on any computer"),
        BASIC("Basic", "Your computer's own built-in voice (robotic)");

        public final String label, tip;

        Setting(String label, String tip) { this.label = label; this.tip = tip; }

        public static Setting of(String s) {
            if (s != null) for (Setting v : values()) if (v.name().equalsIgnoreCase(s.trim())) return v;
            return AUTO;
        }

        public Setting next() { return values()[(ordinal() + 1) % values().length]; }

        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /** Seconds of work per second of speech at or under which Auto uses the expressive voice (it streams a sentence at a time). */
    public static final double EXPRESSIVE_RTF = 0.9;
    /** How many times longer the expressive voice takes than Kokoro (int8) on the same computer (measured back to back on a 4-core CPU: 2.9 to 3.2). */
    public static final double EXPRESSIVE_PER_KOKORO = 3.0;
    /** Not measured yet. */
    public static final double UNKNOWN = -1;

    /** The expressive voice's speed guessed from Kokoro's (UNKNOWN when Kokoro hasn't been timed). */
    public static double predict(double kokoroRtf) {
        return kokoroRtf <= 0 ? UNKNOWN : kokoroRtf * EXPRESSIVE_PER_KOKORO;
    }

    /** Should Auto offer (fetch) the expressive voice? Only when the guess says this computer keeps up. */
    public static boolean offerExpressive(Setting s, double kokoroRtf, double expressiveRtf) {
        if (s == Setting.EXPRESSIVE) return true;
        if (s != Setting.AUTO) return false;
        if (expressiveRtf > 0) return expressiveRtf <= EXPRESSIVE_RTF;
        double p = predict(kokoroRtf);
        return p > 0 && p <= EXPRESSIVE_RTF * 1.15;                     // a little benefit of the doubt: the real benchmark follows
    }

    /**
     * The engines to try for a line, in order: "expressive", "kokoro", then "basic" only when the player picked it.
     * expressiveRtf is its own benchmark (UNKNOWN before it has run); failed: it broke this session.
     */
    public static List<String> chain(Setting s, boolean expressiveReady, boolean failed, double expressiveRtf, boolean kokoroReady, boolean basicPicked) {
        List<String> out = new ArrayList<>();
        boolean expressive = expressiveReady && !failed && switch (s) {
            case EXPRESSIVE -> true;
            case AUTO -> expressiveRtf > 0 && expressiveRtf <= EXPRESSIVE_RTF;
            default -> false;
        };
        if (s == Setting.BASIC) {
            out.add("basic");
            return out;
        }
        if (expressive) out.add("expressive");
        if (kokoroReady) out.add("kokoro");
        if (basicPicked) out.add("basic");
        return out;
    }

    /**
     * Start fetching the expressive voice by itself now? With an assistant, the natural voice ready (its runtime is
     * shared, and its timing is the guess), not declined, not there or on its way already, and Auto's guess (or the
     * Expressive tier) says this computer keeps up. Asked again whenever the state changes; started once.
     */
    public static boolean autoDownload(boolean hasAssistant, boolean started, String packSetting, Setting tier, boolean kokoroReady,
                                       boolean downloaded, boolean downloading, double kokoroRtf, double expressiveRtf) {
        return hasAssistant && !started && "auto".equals(packSetting) && kokoroReady && !downloaded && !downloading
            && offerExpressive(tier, kokoroRtf, expressiveRtf);
    }

    /** What Settings says about the benchmark, in plain words. */
    public static String verdict(double expressiveRtf, double kokoroRtf) {
        if (expressiveRtf > 0) {
            String speed = String.format(Locale.ROOT, "%.1fx", expressiveRtf);
            return expressiveRtf <= EXPRESSIVE_RTF ? "This computer keeps up with the expressive voice (" + speed + " real time)."
                : "Too slow here for the expressive voice (" + speed + " real time): the natural voice is used on Auto.";
        }
        double p = predict(kokoroRtf);
        if (p > 0) return p <= EXPRESSIVE_RTF * 1.15 ? "This computer looks quick enough for the expressive voice."
            : "This computer looks too slow for the expressive voice; the natural voice suits it better.";
        return "Not measured yet.";
    }

    /** The badge a voice shows in the picker. */
    public static String badge(String engine, boolean natural) {
        if ("expressive".equals(engine)) return "Expressive";
        return natural ? "Natural" : "Basic";
    }
}
