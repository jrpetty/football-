package com.jrpetty.mcassistant.pa;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * [featpa] The few plain words of its own for blueprints, errands and stances ("copy this building", "run an errand",
 * "be a medic"): what they route to, and the phrases its voice listens for. Pure Java: the unit test reads it.
 */
public final class PaKitWords {

    private PaKitWords() {}

    /** What its voice listens for, for these (PaUnderstand.grammar): each one a phrase PaKit.heard takes. */
    public static final List<String> VOICE = List.of("copy this building", "copy that house", "save this building", "run an errand",
        "go to the market", "do the shopping", "sell my loot", "be my shield", "shield stance", "be an archer", "archer stance",
        "be a medic", "medic stance", "show my blueprints", "open my blueprints", "combat stance");

    private static final java.util.regex.Pattern COPY = java.util.regex.Pattern.compile(
        "(?i)^(please )?(copy|blueprint|remember|save|record) (this|that|my|the) (building|house|hut|place|tower|barn|build|structure)(.*)$");
    private static final java.util.regex.Pattern ERRAND = java.util.regex.Pattern.compile(
        "(?i)^(please )?(run an errand|go to (the )?(market|town|shops?)|do (some|the) shopping|sell (my|this) (loot|stuff|things))(.*)$");
    private static final java.util.regex.Pattern STANCE = java.util.regex.Pattern.compile(
        "(?i)^(please )?(be (my|a|an|the) |go |switch to |take )?(shield|tank|archer|bowman|medic|healer)( stance| role)?( please)?[.!]?$");
    private static final java.util.regex.Pattern BLUEPRINTS = java.util.regex.Pattern.compile("(?i)^(show |open )?(my )?(blueprints|stances?|combat stance|combat roles?)( please)?[.!]?$");

    /** Which of its own the words are: "copy:<name>", "errand", "stance:SHIELD|ARCHER|MEDIC", "blueprints", "stances", or
     *  null for the order reader. Pure: the unit test reads it. */
    @Nullable
    public static String route(String text) {
        String t = text == null ? "" : text.trim();
        java.util.regex.Matcher m = COPY.matcher(t);
        if (m.find()) {
            String rest = m.group(5).trim();
            String low = rest.toLowerCase(Locale.ROOT);
            String name = low.startsWith("as ") ? rest.substring(3) : low.startsWith("called ") ? rest.substring(7) : "";
            return "copy:" + name.replaceAll("[.!?]+$", "").trim();
        }
        if (ERRAND.matcher(t).find()) return "errand";
        m = STANCE.matcher(t);
        if (m.find()) {
            String w = m.group(4).toLowerCase(Locale.ROOT);
            return "stance:" + (w.startsWith("shield") || w.equals("tank") ? "SHIELD" : w.startsWith("archer") || w.startsWith("bow") ? "ARCHER" : "MEDIC");
        }
        m = BLUEPRINTS.matcher(t);
        if (m.find()) return m.group(3).toLowerCase(Locale.ROOT).startsWith("blueprint") ? "blueprints" : "stances";
        return null;
    }

}
