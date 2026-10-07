package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;

import java.util.ArrayList;
import java.util.List;

/**
 * How a folk's nature suits its work. The same two hands go further at a trade that fits
 * who they are: a hardworking folk is quicker at anything, a curious one loves the mine
 * and the library, a sociable one is made for the shop and the café and finds a long day
 * alone at the river a drag, a shy one is happiest at the hives, the loom and the water,
 * a grumpy one keeps a stern watch but sours a counter, a generous one tends fields and
 * flocks and kitchens with care, a cheerful one lightens any job, and an easygoing one
 * takes its time. Up to a fifth quicker, or slower, than most — on top of its level,
 * its tools, its mood and how the village is doing (AssistantEntity.workTicksFor).
 */
public final class Skill {

    private Skill() {}

    /** The most a nature adds to, or takes off, the pace of work. */
    public static final int MOST = 20;

    /** What one trait does for one trade, in percent of pace, and why. */
    record Fit(int percent, String why) {}

    static Fit fit(Social.Trait trait, StationTask trade) {
        return switch (trait) {
            case HARDWORKING -> trade == StationTask.SCOUT || trade == StationTask.HUNT ? new Fit(6, "covers the ground")
                : new Fit(12, "puts its back into everything");
            case EASYGOING -> switch (trade) {
                case FISH, BEEKEEP -> new Fit(0, "unhurried, which suits the water and the hives");
                case SCOUT -> new Fit(-10, "dawdles on the road");
                case CARTOGRAPHER -> new Fit(-6, "dawdles on its rounds");          // [cartographer]
                case HUNT -> new Fit(4, "patient enough to wait for the game to come to it");
                default -> new Fit(-8, "takes its time");
            };
            case SOCIABLE -> switch (trade) {
                case SHOP, COOK, STORE, HAUL -> new Fit(12, "good with people, made for this");
                case CAVE -> new Fit(-6, "misses company down in the dark");          // [caves]
                case FIREWORKS -> new Fit(6, "loves the crowd's cheer when the rockets go up");   // [fireworks]
                case EMERALD -> new Fit(14, "can talk any villager into a better bargain");   // [emerald]
                case NETHER -> new Fit(4, "keeps the team's spirits up in the heat");   // [nether]
                case SCOUT -> new Fit(6, "talks to everybody it meets on the road");
                case HUNT -> new Fit(-8, "can't keep quiet long enough to get near anything");
                case MINE, FISH -> new Fit(-5, "misses company down there");
                default -> new Fit(0, "");
            };
            case SHY -> switch (trade) {
                case FISH, BEEKEEP, ENCHANT, TAILOR -> new Fit(10, "quiet, careful work suits it");
                case FLETCHER -> new Fit(10, "quiet, careful work at the table suits it");   // [fletcher]
                case SHOP, COOK -> new Fit(-8, "finds serving folk hard going");
                case SCOUT -> new Fit(4, "happy on its own out on the land");
                case CAVE -> new Fit(6, "happy on its own in the dark");               // [caves]
                case FIREWORKS -> new Fit(8, "quiet, careful hands with the powder");     // [fireworks]
                case CARTOGRAPHER -> new Fit(6, "happy alone with a sheet and the land");   // [cartographer]
                case EMERALD -> new Fit(-8, "finds haggling with strangers hard going");     // [emerald]
                case HUNT -> new Fit(12, "quiet as the woods: the game never hears it coming");
                default -> new Fit(0, "");
            };
            case CHEERFUL -> switch (trade) {
                case COOK, SHOP -> new Fit(10, "brightens the counter and the kitchen");
                case FIREWORKS -> new Fit(12, "born to put on a show");                    // [fireworks]
                case EMERALD -> new Fit(8, "the villagers are always glad to see it");      // [emerald]
                default -> new Fit(5, "whistles while it works");
            };
            case GRUMPY -> switch (trade) {
                case GUARD -> new Fit(8, "nothing gets past a scowl like that");
                case CAVE -> new Fit(6, "takes it out on the rock, and on what lives in it");   // [caves]
                case NETHER -> new Fit(6, "glowers right back at a ghast");                    // [nether]
                case MINE, SMITH -> new Fit(4, "takes it out on the stone");
                case GOLEMS -> new Fit(4, "has a lot in common with an iron golem");        // [golems]
                case SHOP, COOK, STORE -> new Fit(-6, "puts the customers off");
                case EMERALD -> new Fit(-6, "the villagers grumble back at it");             // [emerald]
                default -> new Fit(0, "");
            };
            case GENEROUS -> switch (trade) {
                case FARM, RANCH, COOK -> new Fit(8, "tends things with care");
                default -> new Fit(0, "");
            };
            case CURIOUS -> switch (trade) {
                case MINE, ENCHANT, BREW, SMITH -> new Fit(10, "loves finding out how things work");
                case SCOUT -> new Fit(18, "born to see what's over the next hill");
                case EMERALD -> new Fit(8, "learns every villager's wares by heart");       // [emerald]
                case CAVE -> new Fit(14, "can't pass a dark passage without seeing where it goes");   // [caves]
                case FIREWORKS -> new Fit(10, "always trying a new star to see what it does");      // [fireworks]
                case CARTOGRAPHER -> new Fit(16, "has to know what's round the next bend, and draw it");   // [cartographer]
                case NETHER -> new Fit(12, "wants to see what's past the next lava fall");             // [nether]
                case HUNT -> new Fit(8, "reads the tracks like a book");
                case HAUL, STORE -> new Fit(-4, "wanders off to look at things");
                default -> new Fit(3, "always learning something");
            };
        };
    }

    /** How much quicker (or, below nought, slower) than most this folk is at its trade. */
    public static int percent(VillageFolkEntity f) {
        if (f.isBaby() || f.stationTask() == StationTask.NONE || !f.life().rolled()) return 0;
        int sum = 0;
        for (Social.Trait t : Social.Trait.values()) {
            if (f.life().has(t)) sum += fit(t, f.stationTask()).percent();
        }
        return Math.max(-MOST, Math.min(MOST, sum));
    }

    /** Why, trait by trait: "hardworking: puts its back into everything (+12%)". */
    public static List<String> reasons(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        if (f.stationTask() == StationTask.NONE || !f.life().rolled()) return out;
        for (Social.Trait t : Social.Trait.values()) {
            if (!f.life().has(t)) continue;
            Fit fit = fit(t, f.stationTask());
            if (fit.percent() == 0 && fit.why().isEmpty()) continue;
            out.add(t.label + ": " + fit.why() + (fit.percent() == 0 ? "" : " (" + (fit.percent() > 0 ? "+" : "") + fit.percent() + "%)"));
        }
        return out;
    }

    /** In a word or two, for its card: "a natural (+22%)", "steady", "a slow hand (−8%)". */
    public static String word(int percent) {
        return percent >= 15 ? "a natural" : percent >= 8 ? "very good at it" : percent >= 3 ? "good at it"
            : percent > -3 ? "steady" : percent > -8 ? "a little slow" : "not cut out for it";
    }

    /** One line for a player: what its nature makes of its trade. */
    public static String line(VillageFolkEntity f) {
        if (f.isBaby()) return "Too young for a trade.";
        if (f.stationTask() == StationTask.NONE) return "No trade yet.";
        int p = percent(f);
        List<String> why = reasons(f);
        return capital(word(p)) + (p == 0 ? "" : " — works " + Math.abs(p) + "% " + (p > 0 ? "faster" : "slower") + " than most")
            + (why.isEmpty() ? "." : ": " + String.join("; ", why) + ".");
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
