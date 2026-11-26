package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * [featcivic] The town's parties (in a representative democracy) and its factions (anywhere): folk drawn together by what
 * they care for (Values) and their nature (Social traits). Six, each with a plain name, a colour, a line on the taxes,
 * what it would build first and what it promises:
 * <ul>
 * <li><b>the Hearth Party</b>: the larder and a roof for every family (Providers, Homemakers; the generous, the cheerful);</li>
 * <li><b>the Ledger League</b>: lower taxes and a busy market (Merchants; the sociable, the hard-working);</li>
 * <li><b>the Lantern Party</b>: schooling, workshops and the next age (Visionaries; the curious);</li>
 * <li><b>the Watch Party</b>: the watch on every road and sound walls (Guardians; the grumpy);</li>
 * <li><b>the Old Road</b>: keep what works (Traditionalists; the shy);</li>
 * <li><b>the Greenwood</b>: rest days, the commons kept green, a merrier town (Free Spirits; the easygoing).</li>
 * </ul>
 * In a democracy each party with a following puts up its most esteemed member for mayor (Elections.nominate), and the
 * votes cast for each party's candidate share out the seats on the town council by the highest-averages rule. The council
 * votes the laws (LawBook.propose), its members mostly on the party line; its members wear the councillor's sash (Regalia).
 */
public final class Factions {

    private Factions() {}

    public enum Party {
        HEARTH("the Hearth Party", "Hearth", DyeColor.ORANGE, Values.Value.FOOD, Values.Value.HOMES, 0, "school",
            "a full larder and a roof for every family"),
        LEDGER("the Ledger League", "Ledger", DyeColor.YELLOW, Values.Value.WEALTH, Values.Value.LEISURE, -1, "shop",
            "lower taxes and a busy market"),
        LANTERN("the Lantern Party", "Lantern", DyeColor.CYAN, Values.Value.PROGRESS, Values.Value.WEALTH, 1, "library",
            "schooling, workshops and the next age"),
        WATCH("the Watch Party", "Watch", DyeColor.BLUE, Values.Value.SAFETY, Values.Value.TRADITION, 1, "smithy",
            "the watch on every road, and sound walls"),
        OLD_ROAD("the Old Road", "Old Road", DyeColor.PURPLE, Values.Value.TRADITION, Values.Value.SAFETY, 0, "graveyard",
            "keep what works, and mind the old customs"),
        GREENWOOD("the Greenwood", "Greenwood", DyeColor.GREEN, Values.Value.LEISURE, Values.Value.FOOD, -1, "tavern",
            "rest days, the commons kept green, and a merrier town");

        public final String words, shortName, pledge, build;
        public final DyeColor colour;
        public final Values.Value cares, second;
        /** Its line on a tax going up: -1 against, 0 open, 1 for. */
        public final int tax;

        Party(String words, String shortName, DyeColor colour, Values.Value cares, Values.Value second, int tax, String build, String pledge) {
            this.words = words;
            this.shortName = shortName;
            this.colour = colour;
            this.cares = cares;
            this.second = second;
            this.tax = tax;
            this.build = build;
            this.pledge = pledge;
        }

        @Nullable
        public static Party named(String s) {
            try { return valueOf(s); } catch (IllegalArgumentException | NullPointerException e) { return null; }
        }
    }

    public static void resetForTests() { }

    // ------------------------------------------------------------------ who belongs where

    /** The party a folk's cares and nature draw it to. */
    public static Party partyOf(VillageFolkEntity f) {
        int[] w = Values.of(f);
        Party best = Party.HEARTH;
        double top = Double.NEGATIVE_INFINITY;
        for (Party p : Party.values()) {
            double s = w[p.cares.ordinal()] + 0.5 * w[p.second.ordinal()] + bonus(p, f.life()) + Math.floorMod(Objects.hash(f.getUUID(), p), 5);
            if (s > top) { top = s; best = p; }
        }
        return best;
    }

    static double bonus(Party p, Social.Life l) {
        return switch (p) {
            case HEARTH -> (l.has(Social.Trait.GENEROUS) ? 8 : 0) + (l.has(Social.Trait.CHEERFUL) ? 4 : 0);
            case LEDGER -> (l.has(Social.Trait.SOCIABLE) ? 6 : 0) + (l.has(Social.Trait.HARDWORKING) ? 4 : 0);
            case LANTERN -> l.has(Social.Trait.CURIOUS) ? 10 : 0;
            case WATCH -> l.has(Social.Trait.GRUMPY) ? 8 : 0;
            case OLD_ROAD -> l.has(Social.Trait.SHY) ? 8 : 0;
            case GREENWOOD -> l.has(Social.Trait.EASYGOING) ? 10 : 0;
        };
    }

    /** The party's name a folk votes with, for its card (only where parties stand: a democracy), or "". */
    public static String partyName(@Nullable UUID village, VillageFolkEntity f) {
        if (village == null || Government.of(village) != Government.Form.DEMOCRACY) return "";
        return partyOf(f).words;
    }

    /** How many of the town's grown folk lean to each party (most first). */
    public static LinkedHashMap<Party, Integer> following(UUID village) {
        Map<Party, Integer> n = new EnumMap<>(Party.class);
        for (VillageFolkEntity f : Identity.grown(village)) n.merge(partyOf(f), 1, Integer::sum);
        List<Map.Entry<Party, Integer>> e = new ArrayList<>(n.entrySet());
        e.sort((a, b) -> b.getValue() - a.getValue());
        LinkedHashMap<Party, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Party, Integer> x : e) out.put(x.getKey(), x.getValue());
        return out;
    }

    // ------------------------------------------------------------------ the candidates

    /**
     * A democracy's candidates: each party with two or more followers among the voters puts up its most esteemed member
     * (four parties at most, the biggest first), standing for the party's cares with its pledge.
     */
    static List<Elections.Candidate> nominate(ServerLevel level, UUID village, List<VillageFolkEntity> voters, long day) {
        Map<Party, List<VillageFolkEntity>> by = new EnumMap<>(Party.class);
        for (VillageFolkEntity f : voters) by.computeIfAbsent(partyOf(f), k -> new ArrayList<>()).add(f);
        Map<UUID, Integer> esteem = esteem(voters);
        List<Party> order = new ArrayList<>(by.keySet());
        order.sort((a, b) -> by.get(b).size() - by.get(a).size());
        List<Elections.Candidate> out = new ArrayList<>();
        int most = voters.size() < 10 ? 3 : 4;
        for (Party p : order) {
            List<VillageFolkEntity> m = by.get(p);
            if (m.size() < 2 || out.size() >= most) continue;
            m.sort(Comparator.comparingInt((VillageFolkEntity f) -> -esteem.getOrDefault(f.getUUID(), 0)
                - (f.getUUID().equals(Villages.elder(village)) ? 40 : 0) - (f.life().has(Social.Trait.SHY) ? -25 : 0)));
            VillageFolkEntity c = m.get(0);
            out.add(new Elections.Candidate(c.getUUID(), c.displayNameCap(), p.cares, p.second, p.pledge));
        }
        return out;
    }

    /** What the town thinks of each: the sum of everybody else's liking for it. */
    static Map<UUID, Integer> esteem(List<VillageFolkEntity> folk) {
        Map<UUID, Integer> out = new HashMap<>();
        for (VillageFolkEntity f : folk) {
            int s = 0;
            for (VillageFolkEntity o : folk) if (o != f) s += o.life().affinity(f.getUUID());
            out.put(f.getUUID(), s);
        }
        return out;
    }

    /** The party a candidate stands for (a folk's own; a player stands as an independent: null). */
    @Nullable
    public static Party partyOfCandidate(UUID village, UUID candidate) {
        VillageFolkEntity f = Elections.loaded(village, candidate);
        return f == null ? null : partyOf(f);
    }

    // ------------------------------------------------------------------ the seats

    /** Seats on a democracy's town council: five, seven, or nine in a town of thirty voters and more. */
    public static int seatsFor(int voters) {
        return voters < 15 ? 5 : voters < 30 ? 7 : 9;
    }

    /**
     * After the count (Elections): the votes for each party's candidate share out the seats by the highest averages, and
     * each party's seats go to its most esteemed followers. Returns the seats, party to number.
     */
    static LinkedHashMap<Party, Integer> allocate(UUID village, Map<UUID, Integer> votesByCandidate, @Nullable UUID mayor) {
        Map<Party, Integer> votes = new EnumMap<>(Party.class);
        for (Map.Entry<UUID, Integer> e : votesByCandidate.entrySet()) {
            Party p = partyOfCandidate(village, e.getKey());
            if (p != null) votes.merge(p, e.getValue(), Integer::sum);
        }
        LinkedHashMap<Party, Integer> seats = new LinkedHashMap<>();
        List<VillageFolkEntity> voters = Elections.voters(village);
        int n = seatsFor(voters.size());
        if (votes.isEmpty()) return seats;
        Map<Party, Integer> got = new EnumMap<>(Party.class);
        for (int i = 0; i < n; i++) {
            Party best = null;
            double top = -1;
            for (Map.Entry<Party, Integer> e : votes.entrySet()) {
                double q = e.getValue() / (double) (got.getOrDefault(e.getKey(), 0) + 1);
                if (q > top || q == top && best != null && e.getKey().ordinal() < best.ordinal()) { top = q; best = e.getKey(); }
            }
            if (best == null || top <= 0) break;
            got.merge(best, 1, Integer::sum);
        }
        List<Map.Entry<Party, Integer>> order = new ArrayList<>(got.entrySet());
        order.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<Party, Integer> e : order) seats.put(e.getKey(), e.getValue());
        // The members: each party's most esteemed followers, the mayor apart.
        Map<UUID, Integer> esteem = esteem(voters);
        if (mayor == null) mayor = Villages.elder(village);
        List<String> members = new ArrayList<>();
        for (Map.Entry<Party, Integer> e : seats.entrySet()) {
            List<VillageFolkEntity> m = new ArrayList<>();
            for (VillageFolkEntity f : voters) if (partyOf(f) == e.getKey() && !f.getUUID().equals(mayor)) m.add(f);
            m.sort(Comparator.comparingInt((VillageFolkEntity f) -> -esteem.getOrDefault(f.getUUID(), 0)));
            for (int i = 0; i < Math.min(e.getValue(), m.size()); i++) members.add(m.get(i).getUUID().toString());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Party, Integer> e : seats.entrySet()) sb.append(sb.length() == 0 ? "" : ",").append(e.getKey().name()).append('=').append(e.getValue());
        Ledger.note(village, "gov.seats", sb.toString());
        Ledger.note(village, "gov.members", String.join(",", members));
        StringBuilder vs = new StringBuilder();
        for (Map.Entry<Party, Integer> e : votes.entrySet()) vs.append(vs.length() == 0 ? "" : ",").append(e.getKey().name()).append('=').append(e.getValue());
        Ledger.note(village, "gov.partyvotes", vs.toString());
        return seats;
    }

    /** The seats as they stand, party to number (most first). */
    public static LinkedHashMap<Party, Integer> seats(UUID village) {
        return parse(Ledger.note(village, "gov.seats"));
    }

    /** The votes each party's candidate won at the last count. */
    public static LinkedHashMap<Party, Integer> partyVotes(UUID village) {
        return parse(Ledger.note(village, "gov.partyvotes"));
    }

    static LinkedHashMap<Party, Integer> parse(@Nullable String s) {
        LinkedHashMap<Party, Integer> out = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return out;
        for (String one : s.split(",")) {
            String[] p = one.split("=");
            Party party = p.length == 2 ? Party.named(p[0]) : null;
            if (party == null) continue;
            try { out.put(party, Integer.parseInt(p[1])); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** The members of the town council (a democracy's), as their ids. */
    public static List<UUID> members(@Nullable UUID village) {
        List<UUID> out = new ArrayList<>();
        if (village == null) return out;
        String s = Ledger.note(village, "gov.members");
        if (s == null || s.isEmpty()) return out;
        for (String one : s.split(",")) {
            try { out.add(UUID.fromString(one)); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    public static boolean member(@Nullable UUID village, UUID folk) {
        return members(village).contains(folk);
    }

    /** The members, as loaded. */
    public static List<VillageFolkEntity> memberFolk(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        List<UUID> ids = members(village);
        for (VillageFolkEntity f : Identity.grown(village)) if (ids.contains(f.getUUID())) out.add(f);
        return out;
    }

    /** The governing side: the mayor's party, and a partner if it has no majority of its own (the nearest in its cares). */
    public static List<Party> governing(UUID village) {
        List<Party> out = new ArrayList<>();
        VillageFolkEntity mayor = Identity.leader(village);
        LinkedHashMap<Party, Integer> seats = seats(village);
        if (seats.isEmpty()) return out;
        Party own = mayor != null ? partyOf(mayor) : seats.keySet().iterator().next();
        out.add(own);
        int total = 0;
        for (int n : seats.values()) total += n;
        int have = seats.getOrDefault(own, 0);
        if (have * 2 > total) return out;
        Party partner = null;
        int best = -1;
        for (Map.Entry<Party, Integer> e : seats.entrySet()) {
            if (e.getKey() == own) continue;
            int near = (e.getKey().tax == own.tax ? 2 : 0) + (e.getKey().cares == own.second || e.getKey().second == own.cares ? 2 : 0);
            int score = near * 10 + e.getValue();
            if (score > best) { best = score; partner = e.getKey(); }
        }
        if (partner != null) out.add(partner);
        return out;
    }

    /** Forget the seats (the form changed). */
    static void reset(UUID village) {
        Ledger.note(village, "gov.seats", "");
        Ledger.note(village, "gov.members", "");
        Ledger.note(village, "gov.partyvotes", "");
    }

    // ------------------------------------------------------------------ the party line on the laws

    /** Each party's line on a law going up a step: 1 for, -1 against, 0 a free vote. */
    static int line(Party p, LawBook.Law l) {
        return switch (l) {
            case CURFEW -> p == Party.WATCH ? 1 : p == Party.GREENWOOD || p == Party.LEDGER ? -1 : 0;
            case WEAPONS -> p == Party.WATCH || p == Party.OLD_ROAD ? 1 : 0;
            case BORDERS -> p == Party.WATCH || p == Party.OLD_ROAD ? 1 : p == Party.LEDGER || p == Party.GREENWOOD ? -1 : 0;
            case CONSCRIPTION -> p == Party.WATCH ? 1 : p == Party.GREENWOOD ? -1 : 0;
            case TITHE -> p == Party.LANTERN || p == Party.HEARTH ? 1 : p == Party.LEDGER || p == Party.GREENWOOD ? -1 : 0;
            case TARIFF -> p == Party.HEARTH ? 1 : p == Party.LEDGER ? -1 : 0;
            case DRINK, SABBATH -> p == Party.OLD_ROAD ? 1 : p == Party.GREENWOOD ? -1 : 0;
            case APPRENTICE -> p == Party.LANTERN ? 1 : 0;
            case HOUSES -> p == Party.HEARTH ? -1 : 0;
            case HUNTING -> p == Party.OLD_ROAD ? 1 : p == Party.GREENWOOD ? -1 : 0;
        };
    }

    /** Does the party line turn this member's own vote round (LawBook.propose)? */
    static boolean whipAgainst(UUID village, VillageFolkEntity f, LawBook.Law l, int value) {
        if (Government.of(village) != Government.Form.DEMOCRACY || !member(village, f.getUUID())) return false;
        int line = line(partyOf(f), l);
        if (line == 0) return false;
        int dir = Integer.signum(value - LawBook.value(village, l));
        if (dir == 0) return false;
        boolean partyAye = line * dir > 0;
        boolean own = LawBook.judge(f.level() instanceof ServerLevel s ? s : null, f, l, value).aye();
        return own != partyAye;
    }
}
