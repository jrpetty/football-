package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [econ51] The town's land planned so its fields never box it in.
 *
 * <p>The farmland was planned (one side of the town, a wedge of fields side by side: Villages.fieldSquares), but everything
 * else out of doors was not. A field the farmland had no room for, a pen, a wood, the hives and the hunting grounds went
 * "just outside the town as it stands", and a young town stands one ring of blocks out: they ringed it at sixty blocks, on
 * the very lots its plan would want once it grew, and every one of them was kept off for good (Villages.lotKeptOff). A long
 * run's town ran out of lots at seventy-seven folk with its fields and pastures all round it.
 *
 * <p>So, three rules:
 * <ul>
 * <li><b>Growth room.</b> Out-of-doors work is never set up inside the town's growth line: the plan's reach one ring of
 * blocks past the town as it stands ({@link #growthReach}). The fields on the farmland's own squares are the exception, out
 * past the town's first block.</li>
 * <li><b>The farmland moves out as the town grows.</b> Once the plan has five rings laid out, the farmland's rows inside the
 * plan's third-from-last ring are the town's ({@link #farmFrom}): its fields go on further out along the same wedge, which
 * reaches nine rows now, not six.</li>
 * <li><b>Bought out, not swept away.</b> A field, pen, wood or hive the growth line has come up to is bought out by the
 * council, a farm or two a day ({@link #tick}): its keeper paid out of the treasury (what it holds; the books show it), told,
 * and sent to find ground past the line, as a miner in the town's ground goes to a face of the mine. Nothing is swapped
 * or cleared: the old field stands until the builders build over it, the crops on it taken down as any growth is.</li>
 * </ul>
 */
public final class LandUse {

    private LandUse() {}

    /** Rings of the plan kept free past the town as it stands. */
    static final int BELT_RINGS = 1;
    /** The plan's rings from which the farmland's inner rows are given over to the town. */
    static final int RELEASE_FROM = 5;
    /** The farms bought out in a day at most. */
    static final int MOVES_A_DAY = 2;
    /** What the council pays a keeper for its ground, coins: so many, and one more each five levels at its trade. */
    static final int BUYOUT = 2;

    private static final java.util.Map<UUID, Integer> BUILT_RINGS_FOR_TESTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tests: the town taken to stand this many rings out (null: as it is). */
    public static void builtRingsForTests(UUID town, @Nullable Integer rings) {
        if (rings == null) BUILT_RINGS_FOR_TESTS.remove(town); else BUILT_RINGS_FOR_TESTS.put(town, rings);
    }

    /** How many rings of the plan the town stands out to (as Villages.townReach reckons it). */
    public static int builtRings(UUID town) {
        Integer forced = BUILT_RINGS_FOR_TESTS.get(town);
        if (forced != null) return forced;
        int b = 0;
        for (String s : Villages.builtList(town)) if (!"colony".equals(s)) b++;
        return b <= 14 ? 1 : b <= 44 ? 2 : CityGrowth.rings(town);
    }

    /** The town's growth line, blocks from the heart: the plan's reach a ring past the town as it stands. */
    public static int growthReach(@Nullable UUID town) {
        if (town == null) return TownPlan.reach(2) + 2;
        return TownPlan.reach(Math.min(TownPlan.MAX_RINGS, builtRings(town) + BELT_RINGS)) + 2;
    }

    /** Is a plot this big round this spot inside the town's growth line (no place for a field, pen, wood or hive)? */
    public static boolean inGrowthRoom(@Nullable UUID town, BlockPos heart, BlockPos spot, int radius) {
        if (town == null) return false;
        int d = Math.max(Math.abs(spot.getX() - heart.getX()), Math.abs(spot.getZ() - heart.getZ()));
        return d - radius <= growthReach(town);
    }

    /**
     * How far out the farmland begins, along its side (0: where it always did, past the town's first block): once five rings
     * of the plan are laid out, past the plan's third-from-last ring, its rows inside that given over to the town.
     */
    public static int farmFrom(@Nullable UUID town) {
        if (town == null) return 0;
        int rings = CityGrowth.rings(town);
        return rings < RELEASE_FROM ? 0 : TownPlan.reach(rings - 3);
    }

    /** Is this field square (its middle, along its side) one the town has taken back from the farmland? */
    public static boolean released(@Nullable UUID town, int along) {
        int from = farmFrom(town);
        return from > 0 && along - VillageFolkEntity.FIELD_MOST <= from;
    }

    /** The out-of-doors trades whose ground the town keeps its growth room clear of. */
    static boolean outdoor(StationTask t) {
        return t == StationTask.FARM || t == StationTask.RANCH || t == StationTask.BEEKEEP || t == StationTask.WOOD || t == StationTask.HUNT;
    }

    /** Is this folk's ground in the town's way: inside the growth line, and not a field on the farmland still farmed? */
    static boolean inTheWay(UUID town, BlockPos heart, VillageFolkEntity f) {
        WorkZone z = f.workZone();
        if (z == null || !outdoor(f.stationTask())) return false;
        if (TownMine.inMine(town, z.center())) return false;
        if (!inGrowthRoom(town, heart, z.center(), z.radius())) return false;
        int dx = z.center().getX() - heart.getX(), dz = z.center().getZ() - heart.getZ();
        // A field on the farmland's own squares, not yet given over to the town, stays where it is planned to be.
        return !(f.stationTask() == StationTask.FARM && Villages.onFarmland(town, dx, dz, 0, 0));
    }

    /**
     * Once a day (the town's rounds): the farms, pens, woods and hives inside the growth line bought out, the nearest the
     * heart first, a couple a day: each keeper paid what the treasury has of its price, told why, and off to find ground
     * past the line. Returns how many were bought out.
     */
    public static int tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        String was = Ledger.note(id, "land.day");
        if (Long.toString(day).equals(was)) return 0;
        Ledger.note(id, "land.day", Long.toString(day));
        return buyOut(level, v, day, MOVES_A_DAY);
    }

    /** Up to so many farms in the town's way bought out now. */
    static int buyOut(ServerLevel level, Villages.Village v, long day, int most) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        java.util.List<VillageFolkEntity> inWay = new java.util.ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.isAlive() && inTheWay(id, heart, f)) inWay.add(f);
        }
        inWay.sort(java.util.Comparator.comparingInt((VillageFolkEntity f) -> distance(heart, f.workZone().center())).thenComparing(VillageFolkEntity::getUUID));
        int moved = 0;
        for (VillageFolkEntity f : inWay) {
            if (moved >= most) break;
            BlockPos at = f.workZone().center();
            String what = switch (f.stationTask()) {
                case FARM -> "field";
                case RANCH -> "pens";
                case BEEKEEP -> "hives";
                case WOOD -> "woodlot";
                default -> "hunting ground";
            };
            int price = BUYOUT + f.tradeLevel(f.stationTask()) / 5;
            int paid = Ledger.takeCoins(id, price);
            if (paid > 0) f.earn(paid);
            f.dropPlotKeepTrade();
            f.brain("the council bought out my " + what + " for the town's houses; I'm to find ground past the town");
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The council's bought my " + what + " for houses. I'll start again further out.",
                "Houses where my " + what + " was. Progress, they call it. I'll be further out.",
                paid >= price ? "Fair price for my " + what + ", I'll give them that. Further out it is." : "They paid what the treasury had. Further out, then."));
            Villages.tell(id, day, "the council bought out " + f.displayNameCap() + "'s " + what + " " + distance(heart, at) + " blocks from the square"
                + (paid > 0 ? " for " + paid + (paid == 1 ? " coin" : " coins") : " on the town's word (the treasury was empty)")
                + ", to keep room for the town to grow; it goes further out");
            Ledger.note(id, "land.moved", Integer.toString(movedAll(id) + 1));
            com.mojang.logging.LogUtils.getLogger().info("[MCA-LAND] {} bought out {}'s {} at {} ({} blocks out, growth line {}), paid {}",
                Villages.name(id), f.displayNameCap(), what, at.toShortString(), distance(heart, at), growthReach(id), paid);
            moved++;
        }
        return moved;
    }

    /** Tests: up to so many farms in the town's way bought out now, whatever the day. */
    public static int buyOutForTests(ServerLevel level, Villages.Village v, int most) {
        return buyOut(level, v, level.getDayTime() / 24000L, most);
    }

    /** Tests: is this folk's ground in the town's way now? */
    public static boolean inTheWayForTests(UUID town, BlockPos heart, VillageFolkEntity f) {
        return inTheWay(town, heart, f);
    }

    /** The farms the council has bought out in all. */
    public static int movedAll(UUID town) {
        try {
            String n = Ledger.note(town, "land.moved");
            return n == null || n.isEmpty() ? 0 : Integer.parseInt(n.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int distance(BlockPos heart, BlockPos p) {
        return Math.max(Math.abs(p.getX() - heart.getX()), Math.abs(p.getZ() - heart.getZ()));
    }

    /**
     * The town's land in numbers, over the rings it has laid out: {lots built or spoken for, lots under fields, pens, woods,
     * hives or the mine, lots free to build on (not over water or up a cliff: only the plan's own reasons), the laid-out
     * lots in all}.
     */
    public static int[] numbers(UUID town) {
        Villages.Village v = Villages.get(town);
        int[] out = new int[4];
        if (v == null) return out;
        for (TownPlan.Lot lot : TownPlan.lots()) {
            if (lot.kind() == TownPlan.Kind.SQUARE || !CityGrowth.laidOut(town, lot)) continue;
            out[3]++;
            if (Villages.lotTaken(town, lot)) out[0]++;
            else if (Villages.lotKeptOff(town, v.centre(), lot)) out[1]++;
            else out[2]++;
        }
        return out;
    }

    /** The town's land in a line, for the board and the logs. */
    public static String line(UUID town) {
        int[] n = numbers(town);
        return "Land: " + n[0] + " lots built on, " + n[1] + " under fields, pens, woods or the mine, " + n[2] + " free, of " + n[3]
            + " laid out; growth room kept clear to " + growthReach(town) + " blocks" + (farmFrom(town) > 0 ? ", the farmland from " + farmFrom(town) : "")
            + "; " + movedAll(town) + " farms bought out and moved further out";
    }
}
