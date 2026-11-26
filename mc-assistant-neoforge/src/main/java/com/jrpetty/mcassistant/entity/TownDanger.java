package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.DangerLevel;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featbuild] The town's danger, read from what it has been through, and the stage of wall it has come to (the arithmetic
 * is village/DangerLevel's).
 *
 * <p>Every half minute the council's reckoning is made again from the town's own books: the alarms rung in the last thirty
 * days (counted here as Raids raises them: a raiding band or a raid, or anything else), the monsters the watch and the
 * golem killed over the last week (WatchClears' tally), how near war it stands (Wars.footing; a neighbour in a feud), the
 * treasury's coin and its folk. The stage it calls for is kept with the world ("danger.stage") and never falls back: a
 * town that once had to build in stone keeps its stone. Each new stage goes into the chronicle with why, and the wall's
 * works (CastleWall) follow it: no wall in an open town; a log palisade with its gatehouses; the age's stone; then towers
 * along the sides and a portcullis over each gate.
 *
 * <p>A wall already begun before the danger was reckoned (an older world) is taken to have come to the towers' stage, so
 * nothing standing is ever refaced back into logs.
 */
public final class TownDanger {

    private TownDanger() {}

    static final String STAGE = "danger.stage", ALARMS = "danger.alarms";
    /** How often the reckoning is made again (ticks). */
    static final long EVERY = 600L;
    /** How long an alarm counts (days). */
    static final int MEMORY = 30;

    private record Cached(long at, DangerLevel.Reading reading, int stage) {}

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();
    /** Tests: each town's stage set by hand (live staging for that town). */
    private static final Map<UUID, Integer> FORCED = new ConcurrentHashMap<>();
    /** Tests: the staging off (the wall by its age alone, as before), for the other features' wall tests. */
    private static volatile boolean bypass;

    public static void resetForTests() {
        CACHE.clear();
        FORCED.clear();
        bypass = true;
    }

    /** Tests: this town's stage set by hand (and the staging live for it). */
    public static void stageForTests(UUID town, int stage) {
        FORCED.put(town, stage);
        CACHE.remove(town);
    }

    /** Tests: the staging live again for every town (and nothing forced). */
    public static void liveForTests() {
        bypass = false;
        FORCED.clear();
        CACHE.clear();
    }

    /** The reckoning forgotten (made again at the next look). */
    static void forget(UUID town) {
        CACHE.remove(town);
    }

    static boolean staged(UUID town) {
        return FORCED.containsKey(town) || !bypass;
    }

    // ------------------------------------------------------------------ the alarms

    /** An alarm rung (Raids.raise): a raid or a band is a raid; anything else another alarm. Kept thirty days. */
    public static void alarm(@Nullable UUID town, String why, long day) {
        if (town == null) return;
        boolean raid = why != null && (why.startsWith("raiders") || why.contains("a raid") || why.contains("raiding"));
        List<String> kept = new ArrayList<>();
        String was = Ledger.note(town, ALARMS);
        if (was != null && !was.isEmpty()) {
            for (String s : was.split(",")) {
                int c = s.indexOf(':');
                if (c <= 0) continue;
                try {
                    if (day - Long.parseLong(s.substring(0, c)) <= MEMORY) kept.add(s);
                } catch (NumberFormatException ignored) { }
            }
        }
        kept.add(day + ":" + (raid ? "r" : "a"));
        while (kept.size() > 40) kept.remove(0);
        Ledger.note(town, ALARMS, String.join(",", kept));
        CACHE.remove(town);
    }

    /** {raids, other alarms} in the last thirty days. */
    static int[] alarms(UUID town, long day) {
        int[] n = new int[2];
        String was = Ledger.note(town, ALARMS);
        if (was == null || was.isEmpty()) return n;
        for (String s : was.split(",")) {
            int c = s.indexOf(':');
            if (c <= 0) continue;
            try {
                if (day - Long.parseLong(s.substring(0, c)) > MEMORY) continue;
            } catch (NumberFormatException e) {
                continue;
            }
            if (s.endsWith("r")) n[0]++; else n[1]++;
        }
        return n;
    }

    // ------------------------------------------------------------------ the reckoning

    /** The danger as the council reckons it now (worked out again every half minute at most). */
    public static DangerLevel.Reading reading(ServerLevel level, UUID town) {
        return look(level, town).reading();
    }

    private static Cached look(ServerLevel level, UUID town) {
        long now = level.getGameTime();
        Cached c = CACHE.get(town);
        if (c != null && now - c.at() < EVERY && now >= c.at()) return c;
        long day = level.getDayTime() / 24000L;
        int[] a = alarms(town, day);
        int[] w = WatchClears.week(town, day - 6);
        int war = switch (Wars.footing(town)) {
            case WAR -> 3;
            case TENSION -> 2;
            default -> uneasy(town) ? 1 : 0;
        };
        DangerLevel.Reading r = DangerLevel.read(new DangerLevel.Inputs(a[0], a[1], w[0] + w[1], war, Ledger.coins(town),
            Villages.headcount(town)));
        int stage = keep(level, town, r);
        c = new Cached(now, r, stage);
        CACHE.put(town, c);
        return c;
    }

    private static boolean uneasy(UUID town) {
        Villages.Village v = Villages.get(town);
        if (v == null) return false;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(town) || !Diplomacy.neighbours(v, o) || !Ledger.knowEachOther(town, o.id())) continue;
            Diplomacy.Terms t = Diplomacy.terms(town, o.id());
            if (t == Diplomacy.Terms.UNEASY || t == Diplomacy.Terms.FEUD) return true;
        }
        return false;
    }

    /** The stage kept with the world, raised to what the danger calls for (told in the chronicle when it rises). */
    private static int keep(ServerLevel level, UUID town, DangerLevel.Reading r) {
        Integer forced = FORCED.get(town);
        if (forced != null) return forced;
        String was = Ledger.note(town, STAGE);
        int before;
        if (was == null) {
            // An older world's wall, begun before the danger was reckoned: kept as it was meant to be.
            before = Ledger.note(town, CastleWall.BEGUN) != null ? DangerLevel.TOWERS : DangerLevel.OPEN;
            Ledger.note(town, STAGE, Integer.toString(before));
        } else {
            try {
                before = Integer.parseInt(was.trim());
            } catch (NumberFormatException e) {
                before = DangerLevel.OPEN;
            }
        }
        int now = DangerLevel.kept(before, r.stage());
        if (now > before) {
            Ledger.note(town, STAGE, Integer.toString(now));
            long day = level.getDayTime() / 24000L;
            String why = String.join(", ", r.reasons().subList(0, Math.min(3, r.reasons().size())));
            Villages.tell(town, day, "the council voted for " + DangerLevel.stageName(now) + ", the danger being " + DangerLevel.word(r.score())
                + " (" + why + ")");
            Villages.Village v = Villages.get(town);
            if (v != null) {
                FeatHints.hint(level, town, v.centre(), "walls", "New: " + Villages.name(town) + " is building " + DangerLevel.stageName(now)
                    + ". The board's Defences page shows the danger, the wall and the night watch.");
            }
        }
        return now;
    }

    /** The stage the town's wall has come to (0 an open town .. 3 towers and a portcullis); the towers' stage when the
     *  staging is off (the tests of the other features, which want the wall as its age has it). */
    public static int stage(ServerLevel level, UUID town) {
        if (!staged(town)) return DangerLevel.TOWERS;
        return look(level, town).stage();
    }

    /** The stage as last reckoned, without reckoning again (CastleWall's hot paths); the kept one when none is cached. */
    static int stageNow(UUID town) {
        if (!staged(town)) return DangerLevel.TOWERS;
        Integer forced = FORCED.get(town);
        if (forced != null) return forced;
        Cached c = CACHE.get(town);
        if (c != null) return c.stage();
        String was = Ledger.note(town, STAGE);
        if (was == null) return Ledger.note(town, CastleWall.BEGUN) != null ? DangerLevel.TOWERS : DangerLevel.OPEN;
        try {
            return Integer.parseInt(was.trim());
        } catch (NumberFormatException e) {
            return DangerLevel.OPEN;
        }
    }

    /** Every second with the watch's look round (CastleWall.keep): the reckoning made again when it is due. */
    static void tick(ServerLevel level, Villages.Village v) {
        if (!staged(v.id())) return;
        look(level, v.id());
    }

    // ------------------------------------------------------------------ what the wall makes of it

    /** Does the danger call for a wall at all? */
    static boolean wallWanted(UUID town) {
        return stageNow(town) >= DangerLevel.PALISADE;
    }

    /** The wall's stone at the town's stage (its age's tier when the staging is off). */
    static int tier(UUID town, int ageTier) {
        if (!staged(town)) return ageTier;
        return DangerLevel.tier(stageNow(town), ageTier);
    }

    /** Towers between the gatehouses and the corners? */
    static boolean midTowers(UUID town) {
        return !staged(town) || DangerLevel.midTowers(stageNow(town));
    }

    /** A portcullis over the gates (from the Iron Age's stone, as before, when the staging is off)? */
    static boolean portcullis(UUID town, int tier) {
        if (!staged(town)) return tier >= CastleWall.PORTCULLIS;
        return DangerLevel.portcullis(stageNow(town), tier);
    }

    /** The danger and the wall's stage in a few lines, for the board's Defences page. */
    public static List<String> lines(ServerLevel level, UUID town) {
        List<String> out = new ArrayList<>();
        DangerLevel.Reading r = reading(level, town);
        int stage = stage(level, town);
        out.add("Danger " + r.score() + "/100 (" + DangerLevel.word(r.score()) + "): " + String.join(", ", r.reasons()) + ".");
        out.add("The wall's stage: " + DangerLevel.stageName(stage) + (stage >= DangerLevel.TOWERS ? " (the last stage)."
            : r.stage() > stage ? "." : "; next at " + r.next() + "."));
        return out;
    }
}
