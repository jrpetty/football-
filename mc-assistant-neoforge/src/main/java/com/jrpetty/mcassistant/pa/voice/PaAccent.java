package com.jrpetty.mcassistant.pa.voice;

import java.util.List;
import java.util.Locale;

/**
 * [pa33b] Which English the assistant listens for and speaks: British (en-GB) or American (en-US). The player picks
 * it in the About tab (English (UK), English (US), or Auto). Auto follows the game's language first (en_gb is UK),
 * then the computer's own (Windows' culture, a Mac's or Linux's locale: GB or IE is UK), else US. Plain Java.
 */
public enum PaAccent {
    UK("en-GB", "English (UK)"),
    US("en-US", "English (US)");

    public final String culture, label;

    PaAccent(String culture, String label) {
        this.culture = culture;
        this.label = label;
    }

    /** The setting ("auto", "uk", "us") with the game's language code ("en_gb") and the computer's locale. */
    public static PaAccent resolve(String setting, String gameLanguage, Locale computer) {
        String s = setting == null ? "auto" : setting.toLowerCase(Locale.ROOT);
        if (s.equals("uk") || s.equals("gb") || s.equals("en-gb")) return UK;
        if (s.equals("us") || s.equals("en-us")) return US;
        String g = gameLanguage == null ? "" : gameLanguage.toLowerCase(Locale.ROOT);
        if (g.equals("en_gb") || g.equals("en_ie") || g.equals("en_nz") || g.equals("en_au")) return UK;
        String c = computer == null ? "" : computer.getCountry().toUpperCase(Locale.ROOT);
        if (computer != null && computer.getLanguage().equals("en") && (c.equals("GB") || c.equals("IE"))) return UK;
        if (g.equals("en_us") || g.equals("en_ca")) return US;
        return US;
    }

    /** The next choice round the About tab's button: auto, uk, us. */
    public static String next(String setting) {
        return switch (setting == null ? "auto" : setting) {
            case "auto" -> "uk";
            case "uk" -> "us";
            default -> "auto";
        };
    }

    /** What the About tab shows for a setting. */
    public static String settingLabel(String setting, PaAccent resolved) {
        return switch (setting == null ? "auto" : setting) {
            case "uk" -> UK.label;
            case "us" -> US.label;
            default -> "Auto: " + resolved.label;
        };
    }

    /**
     * The voice to speak in for this accent when the player has not picked one: of the computer's voices (by name), a
     * British one for UK ("Microsoft Hazel", "George", "Susan", macOS "Daniel", "Kate", ...), an American one for US.
     * Null when there is none: the computer's default is used.
     */
    public static String preferredVoice(PaAccent a, List<String> voices) {
        if (voices == null) return null;
        String[] want = a == UK
            ? new String[]{"hazel", "george", "susan", "libby", "sonia", "ryan", "daniel", "kate", "serena", "oliver", "arthur", "martha", "stephanie", "en-gb", "british", "united kingdom"}
            : new String[]{"zira", "david", "mark", "aria", "guy", "jenny", "samantha", "alex", "allison", "ava", "tom", "en-us", "american", "united states"};
        for (String w : want) {
            for (String v : voices) if (v != null && v.toLowerCase(Locale.ROOT).contains(w)) return v;
        }
        return null;
    }

    /** espeak's own voice for it ("en-gb" ships with espeak and espeak-ng). */
    public String espeakVoice() {
        return this == UK ? "en-gb" : "en-us";
    }

    /**
     * What to tell the player about Windows' recogniser: the one in use, and when a British one was wanted but is not
     * installed, how to add it, in plain words.
     */
    public static String windowsAdvice(PaAccent want, String inUse, List<String> installed) {
        String use = inUse == null || inUse.isEmpty() ? "" : inUse;
        if (installed != null && installed.isEmpty()) {
            return "Windows has no speech recogniser installed. Add one: Windows Settings > Time & language > Speech,"
                + " and add " + (want == UK ? "English (United Kingdom)" : "English (United States)") + ".";
        }
        String name = use.equalsIgnoreCase("en-GB") ? "English (United Kingdom)" : use.equalsIgnoreCase("en-US") ? "English (United States)" : use;
        String line = "Listening with Windows' speech recognition" + (name.isEmpty() ? "" : ", " + name) + ".";
        if (want == UK && !use.equalsIgnoreCase("en-GB")) {
            line += " The UK English recogniser isn't installed, so it's using " + (name.isEmpty() ? "another" : "the " + name) + " one."
                + " To add it: Windows Settings > Time & language > Speech, and add English (United Kingdom).";
        }
        return line;
    }
}
