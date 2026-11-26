package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [featcivic] The leader's personality made into the town's policy: what it does with the taxes, what it would have built
 * first, how it pays. Worked out from what the one in office cares for (Values) and its nature (Social traits), and, in a
 * representative democracy, its party's line; a planned state's committee and a general have their own ways. It is what
 * the board's Policy page shows ("Taxes: leaning lower, because a coin left in a purse is spent at the market"), and it
 * does what it says: the council's tax votes lean the leader's way (TaxBook.judge), the council's choice of what goes up
 * first counts the leader's wish twice over (Council.vote), and the wages are paid a little more or less generously
 * (Ethos.wage; in a democracy or under a general). A new leader's policy is told in the chronicle the morning it takes office.
 */
public final class Policy {

    private Policy() {}

    /** The policy: taxes -1 lower, 0 hold, 1 raise; what goes up first (a Council choice); wages -1 lean, 0 steady, 1 generous. */
    public record Stance(String who, int tax, String taxWhy, String build, String buildWhy, int wage, String wageWhy, String style) {}

    /** The leader's policy now, or null with nobody (or a player) in office. */
    @Nullable
    public static Stance of(@Nullable UUID village) {
        if (village == null || Identity.neutral() || Identity.known(village) == null) return null;
        VillageFolkEntity f = Identity.leader(village);
        if (f == null || f.isBaby()) return null;
        return of(village, f);
    }

    static Stance of(UUID village, VillageFolkEntity f) {
        Government.Form form = Government.of(village);
        int[] w = Values.of(f);
        Social.Life life = f.life();
        int prog = w[Values.Value.PROGRESS.ordinal()], homes = w[Values.Value.HOMES.ordinal()], safety = w[Values.Value.SAFETY.ordinal()],
            wealth = w[Values.Value.WEALTH.ordinal()], leisure = w[Values.Value.LEISURE.ordinal()], food = w[Values.Value.FOOD.ordinal()],
            trad = w[Values.Value.TRADITION.ordinal()];
        // Taxes: what the leader would pay for, against what it would leave in folk's purses.
        double t = (prog + homes + safety) / 3.0 - (wealth + leisure) / 2.0;
        if (life.has(Social.Trait.GENEROUS)) t += 8;
        if (life.has(Social.Trait.HARDWORKING)) t += 3;
        if (life.has(Social.Trait.EASYGOING)) t -= 6;
        if (form == Government.Form.DEMOCRACY) {
            Factions.Party p = Factions.partyOf(f);
            t += p.tax * 10;
        }
        int tax = t > 8 ? 1 : t < -8 ? -1 : 0;
        Values.Value top = Values.top(f);
        String taxWhy = switch (tax) {
            case 1 -> switch (top) {
                case PROGRESS -> "the next age will not pay for itself";
                case HOMES -> "houses do not build themselves";
                case SAFETY -> "the watch and the walls want paying for";
                default -> "the town's works want paying for";
            };
            case -1 -> top == Values.Value.LEISURE ? "folk work hard enough for what they keep" : "a coin left in a purse is spent at the market";
            default -> "the treasury and the purses both get by as they are";
        };
        // What goes up first.
        String build = switch (top) {
            case PROGRESS -> life.has(Social.Trait.CURIOUS) ? "library" : "school";
            case HOMES -> "school";
            case SAFETY -> "smithy";
            case WEALTH -> "shop";
            case LEISURE -> "tavern";
            case FOOD -> "cafe";
            case TRADITION -> food >= homes ? "brewery" : "graveyard";
        };
        String buildWhy = switch (top) {
            case PROGRESS -> "learning first: the next age starts in the head";
            case HOMES -> "families first: the children need their letters";
            case SAFETY -> "tools and arms for the watch";
            case WEALTH -> "trade first: a shop brings coin in";
            case LEISURE -> "somewhere to rest at the end of the day";
            case FOOD -> "a hot meal for every hand";
            case TRADITION -> "what the town has always had";
        };
        // Wages.
        int wage = top == Values.Value.WEALTH || life.has(Social.Trait.GENEROUS) ? 1
            : (top == Values.Value.SAFETY || top == Values.Value.PROGRESS) && life.has(Social.Trait.HARDWORKING) ? -1 : 0;
        String wageWhy = wage > 0 ? "a fair day's pay keeps good hands" : wage < 0 ? "the treasury comes first while there is work to fund" : "the going rate";
        String style = trad >= 45 ? "cautious" : prog >= 45 ? "bold" : life.has(Social.Trait.SOCIABLE) ? "consulting" : "practical";
        // The forms with their own ways.
        if (form == Government.Form.PLANNED) {
            tax = 0; taxWhy = "set by the plan: no toll, no profits tax, three in twenty of every wage";
            wage = 0; wageWhy = "one wage for every hand, set by the committee";
            style = "planned";
        } else if (form == Government.Form.JUNTA) {
            tax = Math.max(0, tax); taxWhy = tax > 0 ? taxWhy : "the watch's levy is kept, war or not";
            build = "smithy"; buildWhy = "the watch's arms before anything";
            style = "strict";
        } else if (form == Government.Form.COMMUNE) {
            wage = 0; wageWhy = "everybody paid the same";
        } else if (form != Government.Form.DEMOCRACY) {
            // Only a democracy's mayor (and a general) leans the wages; elsewhere they are paid as the town has always paid.
            wage = 0; wageWhy = "the going rate, as the town has always paid";
        }
        return new Stance(f.displayNameCap(), tax, taxWhy, build, buildWhy, wage, wageWhy, style);
    }

    /** How the leader's policy (and in a democracy the councillor's party line) leans a councillor on a tax going up. */
    public static double taxLean(@Nullable UUID village, VillageFolkEntity councillor) {
        Stance s = of(village);
        double lean = s == null ? 0 : s.tax() * 0.6;
        if (village != null && Government.of(village) == Government.Form.DEMOCRACY && Factions.member(village, councillor.getUUID())) {
            lean += Factions.partyOf(councillor).tax * 0.6;
        }
        return lean;
    }

    /** What the wages are multiplied by: a generous leader pays six in a hundred more, a lean one six fewer. */
    public static double wageFactor(@Nullable UUID village) {
        Stance s = of(village);
        return s == null ? 1.0 : 1.0 + 0.06 * s.wage();
    }

    /** The leader's wish for what goes up first, counted twice over in the council's vote (Council.vote). */
    public static void councilVotes(UUID village, List<String> options, Map<String, Integer> tally) {
        Stance s = of(village);
        if (s == null || !tally.containsKey(s.build())) return;
        tally.merge(s.build(), Government.of(village) == Government.Form.PLANNED || Government.of(village) == Government.Form.JUNTA ? 4 : 2, Integer::sum);
    }

    /** "the tax policy: leaning lower" */
    public static String taxWords(int tax) {
        return tax > 0 ? "raise them" : tax < 0 ? "lower them" : "hold them";
    }

    public static String wageWords(int wage) {
        return wage > 0 ? "generous" : wage < 0 ? "lean" : "steady";
    }

    /** "the smithy" */
    public static String buildWords(String build) {
        return switch (build) {
            case "cafe" -> "the café";
            case "school" -> "the schoolhouse";
            default -> "the " + build;
        };
    }

    /**
     * Each morning (Politics): a leader new to office sets out its policy, in the chronicle and the morning's assembly;
     * written down with the day, for the board's "since" and its history of policies.
     */
    static void look(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        UUID leader = Villages.elder(id);
        String was = Ledger.note(id, "policy.leader");
        String now = leader == null ? "" : leader.toString();
        if (now.equals(was == null ? "" : was)) return;
        Ledger.note(id, "policy.leader", now);
        Stance s = of(id);
        if (s == null) return;
        String line = Government.title(id) + " " + s.who() + "'s policy: taxes, " + taxWords(s.tax()) + " (" + s.taxWhy() + "); "
            + buildWords(s.build()) + " to go up first (" + s.buildWhy() + "); wages " + wageWords(s.wage());
        Ledger.note(id, "policy.since", Long.toString(day));
        String hist = Ledger.note(id, "policy.history");
        List<String> h = new ArrayList<>();
        if (hist != null && !hist.isEmpty()) for (String x : hist.split(";;")) h.add(x);
        h.add("Day " + (day + 1) + ": " + Regimes.capital(line).replace(";;", ";"));
        while (h.size() > 6) h.remove(0);
        Ledger.note(id, "policy.history", String.join(";;", h));
        Villages.tell(id, day, Regimes.capital(line));
        Market.assemblyNews(id, Regimes.capital(line) + ".");
    }

    /** The policies set out, oldest first ("Day 12: Mayor Fen's policy: ..."). */
    public static List<String> history(UUID village) {
        String hist = Ledger.note(village, "policy.history");
        List<String> h = new ArrayList<>();
        if (hist != null && !hist.isEmpty()) for (String x : hist.split(";;")) h.add(x);
        return h;
    }
}
