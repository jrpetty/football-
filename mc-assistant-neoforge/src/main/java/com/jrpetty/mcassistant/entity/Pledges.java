package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * [player-civic] What a player standing for leader can promise the town, and how the town holds them to it.
 *
 * <p>The promises on offer are the town's own wants, as they stand on the day: the next buildings on its list
 * ("build the schoolhouse within ten days"), a lower tithe or better wages, more guards (once it keeps a watch),
 * peace with a neighbour it is at odds or at war with, food for everyone, a festival every season, and a bed for
 * everyone while somebody sleeps on the ground. Each is something a leader can actually bring about with the
 * powers the town gives it (PlayerLeader): the next building chosen, the tithe and the wages set, the town's plan
 * pointed at the watch or the fields, an envoy received. And each is something the folk care about (Values): a
 * Visionary hears "the schoolhouse", a Merchant "a lower tithe", a Guardian "more guards".
 *
 * <p>In office each promise is written down with the day it was made, its deadline, and how things stood (the
 * guards on the watch, the buildings of its kind, the beds wanted), and every morning the town looks at it: kept,
 * still to come, or broken. A promise to keep the tithe low or the wages high is kept on the day it is done, and
 * broken if it is undone in the same term. A festival every season is judged at each season's end.
 */
public final class Pledges {

    private Pledges() {}

    /** The kinds of promise: what the folk hear in it, and the days a leader has to keep it. */
    public enum Kind {
        BUILD(Values.Value.PROGRESS, 10),
        TITHE(Values.Value.WEALTH, 3),
        WAGES(Values.Value.WEALTH, 3),
        GUARDS(Values.Value.SAFETY, 8),
        PEACE(Values.Value.SAFETY, 10),
        FEED(Values.Value.FOOD, 7),
        FESTIVAL(Values.Value.LEISURE, Seasons.DAYS),
        HOMES(Values.Value.HOMES, 10);

        public final Values.Value value;
        public final int days;

        Kind(Values.Value value, int days) {
            this.value = value;
            this.days = days;
        }
    }

    public enum State { OPEN, KEPT, BROKEN }

    /** At most so many promises to a candidacy: a platform, not a shopping list. */
    public static final int MOST = 3;
    /** The tithe the town has always paid, in the hundred (Market.tithe: one coin in ten). */
    public static final int TITHE_USUAL = 10;

    /** A promise: of what kind, and about what (a building, a neighbour's id, or nothing). */
    public record Pledge(Kind kind, String arg) {

        /** "build:school", "tithe". */
        public String key() {
            return kind.name().toLowerCase(Locale.ROOT) + (arg.isEmpty() ? "" : ":" + arg);
        }

        /** What the folk care for in it: a hall or a chapel is the old ways, a tavern rest, a wall safety. */
        public Values.Value value() {
            if (kind != Kind.BUILD) return kind.value;
            return switch (arg) {
                case "tavern", "cafe", "park", "pitch", "theatre", "fountain", "inn" -> Values.Value.LEISURE;
                case "fortify", "watchtower", "archery" -> Values.Value.SAFETY;
                case "hall", "townhall", "court", "chapel", "graveyard", "belltower" -> Values.Value.TRADITION;
                case "windmill", "bakery", "orchard", "allotments", "pen", "farm" -> Values.Value.FOOD;
                case "house", "manor", "flats", "guesthouse" -> Values.Value.HOMES;
                case "market", "shop", "store", "bank" -> Values.Value.WEALTH;
                default -> Values.Value.PROGRESS;
            };
        }

        /** As a promise is put: "build the schoolhouse within 10 days". */
        public String words(@Nullable UUID village) {
            return switch (kind) {
                case BUILD -> "build " + Villages.spoken(arg) + " within " + kind.days + " days";
                case TITHE -> "lower the tithe";
                case WAGES -> "better wages";
                case GUARDS -> "more guards";
                case PEACE -> "peace with " + town(arg);
                case FEED -> "feed everyone";
                case FESTIVAL -> "a festival every season";
                case HOMES -> "a bed for everyone";
            };
        }

        /** As a candidate offers it: "the schoolhouse within ten days", "a lower tithe". */
        public String noun() {
            return switch (kind) {
                case BUILD -> Villages.spoken(arg) + " within ten days";
                case TITHE -> "a lower tithe";
                case WAGES -> "better wages";
                case GUARDS -> "more guards on the walls";
                case PEACE -> "peace with " + town(arg);
                case FEED -> "food for everyone";
                case FESTIVAL -> "a festival every season";
                case HOMES -> "a bed for everyone";
            };
        }
    }

    /** A neighbour's name, by its id ("Kingsgate"). */
    static String town(String id) {
        try {
            return Villages.name(UUID.fromString(id));
        } catch (RuntimeException e) {
            return "our neighbours";
        }
    }

    @Nullable
    public static Pledge parse(String key) {
        if (key == null || key.isEmpty()) return null;
        String[] p = key.split(":", 2);
        try {
            return new Pledge(Kind.valueOf(p[0].toUpperCase(Locale.ROOT)), p.length > 1 ? p[1] : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ what is on offer

    /** What the town wants just now, as promises a candidate could make: its next buildings first. */
    public static List<Pledge> offered(ServerLevel level, UUID village) {
        List<Pledge> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : Villages.projectsWanted(village)) {
            if (s.equals("house") || s.equals("colony") || s.equals("storage") || s.equals("storehouse") || !seen.add(s)) continue;
            out.add(new Pledge(Kind.BUILD, s));
            if (seen.size() >= 3) break;
        }
        out.add(new Pledge(Kind.TITHE, ""));
        out.add(new Pledge(Kind.WAGES, ""));
        if (Villages.wants(village, AssistantEntity.StationTask.GUARD)) out.add(new Pledge(Kind.GUARDS, ""));
        for (Villages.Village n : Diplomacy.neighboursOf(village)) {
            if (Wars.atWar(village, n.id()) || Ledger.relation(village, n.id()) < Diplomacy.UNEASY) out.add(new Pledge(Kind.PEACE, n.id().toString()));
        }
        out.add(new Pledge(Kind.FEED, ""));
        out.add(new Pledge(Kind.FESTIVAL, ""));
        if (bedless(village) > 0) out.add(new Pledge(Kind.HOMES, ""));
        return out;
    }

    /** Which of the promises on offer these words make ("I promise to lower the tithe", "a school"), or null. */
    @Nullable
    public static Pledge named(ServerLevel level, UUID village, String text) {
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ").replaceAll("\\s+", " ") + " ";
        for (Pledge p : offered(level, village)) {
            boolean hit = switch (p.kind()) {
                case BUILD -> t.contains(" " + p.arg()) || t.contains(Villages.spoken(p.arg()).toLowerCase(Locale.ROOT).replace("the ", ""));
                case TITHE -> t.contains("tithe") || t.contains("tax");
                case WAGES -> t.contains("wage") || t.contains(" pay ");
                case GUARDS -> t.contains("guard") || t.contains(" watch");
                case PEACE -> t.contains("peace");
                case FEED -> t.contains("feed") || t.contains(" food") || t.contains("hungry") || t.contains("larder");
                case FESTIVAL -> t.contains("festival") || t.contains("feast") || t.contains("season");
                case HOMES -> t.contains(" bed") || t.contains("homes") || t.contains("house everyone");
            };
            if (hit) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ in office

    /** A promise in office: when it was made and is due, how things stood when it was made, and how it stands. */
    public static final class Promise {
        public final Pledge pledge;
        public long made, due;
        public int base;
        public State state = State.OPEN;
        /** A festival every season: the seasons kept so far. */
        public int seasons;

        Promise(Pledge pledge) {
            this.pledge = pledge;
        }

        /** "build the schoolhouse within 10 days — by day 40" and how it stands. */
        public String line(UUID village) {
            String w = pledge.words(village);
            return switch (state) {
                case KEPT -> w + " — kept";
                case BROKEN -> w + " — broken";
                case OPEN -> w + (pledge.kind() == Kind.FESTIVAL ? " — this season ends day " + due
                    + (seasons > 0 ? " (" + seasons + (seasons == 1 ? " season" : " seasons") + " kept)" : "")
                    : pledge.kind() == Kind.TITHE || pledge.kind() == Kind.WAGES ? " — to be done by day " + due
                    : " — by day " + due);
            };
        }
    }

    /** A promise made on taking office today: its deadline, and how things stand now to judge it by. */
    public static Promise start(ServerLevel level, UUID village, Pledge pledge, long day) {
        Promise p = new Promise(pledge);
        p.made = day;
        p.due = pledge.kind() == Kind.FESTIVAL ? seasonEnd(village, day) : day + pledge.kind().days;
        p.base = switch (pledge.kind()) {
            case BUILD -> builtCount(village, pledge.arg());
            case GUARDS -> guards(village);
            case HOMES -> bedless(village);
            default -> 0;
        };
        return p;
    }

    /** The last day of the season that holds {@code day}. */
    static long seasonEnd(UUID village, long day) {
        return day + (Seasons.DAYS - 1 - Seasons.dayInSeason(village, day));
    }

    /**
     * The morning's look at a promise: KEPT or BROKEN once it is settled, OPEN while there is still time. A
     * festival every season stays open from season to season while each is kept.
     */
    public static State judge(ServerLevel level, UUID village, Promise p, long day) {
        if (p.state != State.OPEN) {
            // Kept, and then undone in the same term: the tithe put back up, the wages cut again.
            if (p.state == State.KEPT && (p.pledge.kind() == Kind.TITHE && PlayerLeader.titheRate(village) >= TITHE_USUAL
                    || p.pledge.kind() == Kind.WAGES && PlayerLeader.wageRate(village) <= 100)) return State.BROKEN;
            return p.state;
        }
        boolean late = day > p.due;
        return switch (p.pledge.kind()) {
            case BUILD -> builtCount(village, p.pledge.arg()) > p.base ? State.KEPT : late ? State.BROKEN : State.OPEN;
            case TITHE -> PlayerLeader.titheRate(village) < TITHE_USUAL ? State.KEPT : late ? State.BROKEN : State.OPEN;
            case WAGES -> PlayerLeader.wageRate(village) > 100 ? State.KEPT : late ? State.BROKEN : State.OPEN;
            case GUARDS -> guards(village) >= p.base + (Villages.headcount(village) >= 20 ? 2 : 1) ? State.KEPT : late ? State.BROKEN : State.OPEN;
            case PEACE -> {
                UUID other;
                try { other = UUID.fromString(p.pledge.arg()); } catch (RuntimeException e) { yield State.KEPT; }
                boolean peace = !Wars.atWar(village, other) && Ledger.relation(village, other) >= 0;
                yield peace ? State.KEPT : late ? State.BROKEN : State.OPEN;
            }
            case FEED -> {
                Leader.Plan plan = Leader.plan(village);
                if (plan == Leader.Plan.FAMINE) yield State.BROKEN;              // nobody goes hungry on a promise of food
                yield late ? (plan == Leader.Plan.SHORT ? State.BROKEN : State.KEPT) : State.OPEN;
            }
            case FESTIVAL -> {
                if (!late) yield State.OPEN;
                long from = p.due - Seasons.DAYS + 1;
                if (!festivalBetween(village, Math.max(from, p.made), p.due)) yield State.BROKEN;
                p.seasons++;
                p.due = seasonEnd(village, p.due + 1);                          // and the next season's
                yield State.OPEN;
            }
            case HOMES -> bedless(village) == 0 ? State.KEPT : late ? State.BROKEN : State.OPEN;
        };
    }

    /** Was a festival kept, or a feast held at somebody's expense, between these days? */
    static boolean festivalBetween(UUID village, long from, long to) {
        for (Festivals.Feast f : Festivals.Feast.values()) {
            long on = Festivals.keptOn(village, f);
            if (on >= from && on <= to) return true;
        }
        for (long d = from; d <= to; d++) if (Gatherings.sponsored(village, d)) return true;
        return false;
    }

    // ------------------------------------------------------------------ the measures

    static int builtCount(UUID village, String structure) {
        int n = 0;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(structure)) n++;
        return n;
    }

    static int guards(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (!a.isBaby() && a.stationTask() == AssistantEntity.StationTask.GUARD) n++;
        return n;
    }

    static int bedless(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (!a.isBaby() && a.bedPos() == null) n++;
        return n;
    }

    // ------------------------------------------------------------------ kept with the town

    static String save(List<Promise> list) {
        StringBuilder sb = new StringBuilder();
        for (Promise p : list) {
            if (sb.length() > 0) sb.append(';');
            sb.append(p.pledge.key()).append('@').append(p.made).append('@').append(p.due).append('@').append(p.base)
                .append('@').append(p.state.name()).append('@').append(p.seasons);
        }
        return sb.toString();
    }

    static List<Promise> load(@Nullable String saved) {
        List<Promise> out = new ArrayList<>();
        if (saved == null || saved.isEmpty()) return out;
        for (String one : saved.split(";")) {
            String[] q = one.split("@");
            Pledge pl = parse(q[0]);
            if (pl == null || q.length < 6) continue;
            try {
                Promise p = new Promise(pl);
                p.made = Long.parseLong(q[1]);
                p.due = Long.parseLong(q[2]);
                p.base = Integer.parseInt(q[3]);
                p.state = State.valueOf(q[4]);
                p.seasons = Integer.parseInt(q[5]);
                out.add(p);
            } catch (RuntimeException ignored) {
                // a promise written by an older hand: dropped
            }
        }
        return out;
    }
}
