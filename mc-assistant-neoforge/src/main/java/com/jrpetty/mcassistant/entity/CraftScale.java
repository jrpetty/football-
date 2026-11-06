package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [roles-b] The crafts at city scale: how many hands a town keeps at each craft, the benches they work at, and the
 * workshop each works in.
 * <ul>
 * <li><b>Hands.</b> A craft's first hand comes with its slot (Villages.SLOTS); after that, one more for every sixty
 *     folk, up to the craft's most (the bank a clerk at a hundred and twenty): a city of a hundred and fifty wants
 *     three smiths, three tailors, three brewers.</li>
 * <li><b>Benches.</b> A craft worked at a bench (the smith's anvil, the tailor's loom, the brewer's stand, the
 *     enchanter's table) keeps no more hands than its workshops have benches. When the town wants more hands than
 *     that, a hand at its work makes another bench out of the stores, by the game's own recipe (Bench), and sets it
 *     against a wall of its workshop. A workshop holds a second bench in the Iron Age, a third in the Diamond Age and
 *     a fourth in the Nether Age.</li>
 * <li><b>Workshops.</b> The enchanter has a tower of its own (blueprints/enchanter.txt) and works at the library till
 *     it stands, carrying its table over when it does. The beekeeper moves into the apiary once it stands (Apiary).</li>
 * </ul>
 * Cost: a craft's benches are counted once a minute, when one of its hands is at its work (a few hundred block reads in
 * its workshops), and the count is kept in the ledger so a restart does not forget it. A bench is tried for at most
 * once a minute per craft.
 */
public final class CraftScale {

    private CraftScale() {}

    /** One more hand of a craft for every this many folk, after the first. */
    public static final int FOLK_PER_HAND = 60;
    /** The bank's second hand (a clerk at the counter) at this many folk. */
    public static final int FOLK_PER_CLERK = 120;
    /** The enchanter's tower (blueprints/enchanter.txt). */
    public static final String TOWER = "enchanter";
    /** Where the enchanter works till its tower stands. */
    static final String LIBRARY = "library";
    /** How often a craft's benches are counted again, and another tried for (ticks). */
    static final long RECOUNT = 1200L;
    /** The ledger's note of a craft's benches ("crafts.benches.SMITH"). */
    private static final String NOTE = "crafts.benches.";

    /** A craft's bench: what counts as one, what it is made as, the most hands the craft ever keeps, and its name. */
    public record Station(Predicate<BlockState> is, Item item, Block block, int most, String words) {}

    /** The crafts worked at a bench. Data: a craft's line is its bench. */
    static final Map<StationTask, Station> STATIONS = Map.of(
        StationTask.SMITH, new Station(s -> s.is(BlockTags.ANVIL), Items.ANVIL, Blocks.ANVIL, 4, "anvil"),
        StationTask.TAILOR, new Station(s -> s.is(Blocks.LOOM), Items.LOOM, Blocks.LOOM, 4, "loom"),
        StationTask.BREW, new Station(s -> s.is(Blocks.BREWING_STAND), Items.BREWING_STAND, Blocks.BREWING_STAND, 4, "brewing stand"),
        StationTask.ENCHANT, new Station(s -> s.is(Blocks.ENCHANTING_TABLE), Items.ENCHANTING_TABLE, Blocks.ENCHANTING_TABLE, 3, "enchanting table"));

    /** The crafts with no bench to share, and the most hands each keeps: the beekeepers (each to its hives), the bank. */
    static final Map<StationTask, Integer> UNBENCHED = Map.of(StationTask.BEEKEEP, 3, StationTask.BANK, 2);

    /** A count of a craft's benches, and when it was made (game time; MIN_VALUE: from the ledger, to be made again). */
    private record Counted(int benches, long at) {}

    private static final Map<String, Counted> COUNTED = new ConcurrentHashMap<>();
    /** When another bench was last tried for, by village and craft. */
    private static final Map<String, Long> TRIED = new ConcurrentHashMap<>();
    /** A workshop's footprint (min x, min z, max x, max z), by its anchor: the drawing never changes. */
    private static final Map<Long, int[]> FOOT = new ConcurrentHashMap<>();
    /** Told the town once a day that a workshop is full. */
    private static final Map<String, Long> TOLD_FULL = new ConcurrentHashMap<>();

    public static void resetForTests() {
        COUNTED.clear();
        TRIED.clear();
        FOOT.clear();
        TOLD_FULL.clear();
    }

    // ------------------------------------------------------------------ hands

    /** Does this craft grow with the town? */
    public static boolean scales(StationTask t) {
        return STATIONS.containsKey(t) || UNBENCHED.containsKey(t);
    }

    /** The most hands this craft ever keeps, however big the town. */
    static int ceiling(StationTask t) {
        Station s = STATIONS.get(t);
        return s != null ? s.most() : UNBENCHED.getOrDefault(t, 1);
    }

    /** The hands a town of this many wants at this craft, benches or none: one, and one more each sixty folk. */
    public static int curve(StationTask t, int folk) {
        if (!scales(t)) return 1;
        int per = t == StationTask.BANK ? FOLK_PER_CLERK : FOLK_PER_HAND;
        return Math.min(ceiling(t), 1 + Math.max(0, folk) / per);
    }

    /**
     * The most hands of this craft the town keeps now (Villages.target's cap): the curve, held to the benches its
     * workshops have; never less than the slot's own most.
     */
    public static int most(@Nullable UUID village, StationTask t, int folk, int slotMax) {
        if (!scales(t)) return slotMax;
        int want = curve(t, folk);
        if (STATIONS.containsKey(t)) want = Math.min(want, benches(village, t));
        return Math.max(slotMax, want);
    }

    /** How many of a craft's benches one workshop holds as the town ages: one, a second in the Iron Age, and so on. */
    public static int roomFor(Villages.Age at) {
        return Math.max(1, at.ordinal() - Villages.Age.STONE.ordinal() + 1);
    }

    // ------------------------------------------------------------------ workshops

    /**
     * The building a craft works in, in this town now: its own (VillageFolkEntity.buildingFor), but the library for an
     * enchanter while its tower is not up, and the beekeeper's own meadow (none) till the apiary stands.
     */
    @Nullable
    public static String houseOf(@Nullable UUID village, StationTask t) {
        String own = VillageFolkEntity.buildingFor(t);
        if (village == null) return own;
        if (t == StationTask.ENCHANT && !stands(village, TOWER) && stands(village, LIBRARY)) return LIBRARY;
        if (t == StationTask.BEEKEEP && !stands(village, Apiary.STRUCTURE)) return null;
        return own;
    }

    /** Has the enchanter somewhere to work: its tower, or the library till then (Villages.craftReady)? */
    public static boolean enchanterReady(@Nullable UUID village) {
        return village != null && (stands(village, TOWER) || stands(village, LIBRARY));
    }

    static boolean stands(UUID village, String structure) {
        return Villages.hasBuilt(village, structure) || Villages.builtAt(village, structure) != null;
    }

    /** Every workshop of this craft in the town: its own kind, and the library for the enchanter. */
    static List<Ledger.Building> houses(UUID village, StationTask t) {
        String own = VillageFolkEntity.buildingFor(t);
        List<Ledger.Building> out = new ArrayList<>();
        if (own == null) return out;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (b.structure().equals(own) || t == StationTask.ENCHANT && b.structure().equals(LIBRARY)) out.add(b);
        }
        return out;
    }

    // ------------------------------------------------------------------ benches

    /** The benches this craft has in its workshops, as last counted (at least one: the hand's own). */
    public static int benches(@Nullable UUID village, StationTask t) {
        if (village == null) return 1;
        Counted c = COUNTED.get(key(village, t));
        if (c != null) return Math.max(1, c.benches());
        int kept = 1;
        String note = Ledger.note(village, NOTE + t.name());
        if (note != null && !note.isEmpty()) {
            try {
                kept = Integer.parseInt(note);
            } catch (NumberFormatException e) {
                kept = 1;
            }
        }
        COUNTED.put(key(village, t), new Counted(kept, Long.MIN_VALUE));
        return Math.max(1, kept);
    }

    /** Count the craft's benches again if the last count is a minute old (only workshops in loaded ground). */
    static int recount(ServerLevel level, Villages.Village v, StationTask t) {
        Station s = STATIONS.get(t);
        if (s == null) return 1;
        long now = level.getGameTime();
        Counted c = COUNTED.get(key(v.id(), t));
        if (c != null && c.at() != Long.MIN_VALUE && now - c.at() < RECOUNT && now >= c.at()) return Math.max(1, c.benches());
        int n = 0;
        boolean seen = false;
        for (Ledger.Building b : houses(v.id(), t)) {
            if (!Land.areaLoaded(level, b.anchor(), 8)) continue;
            seen = true;
            n += benchesIn(level, b, s).size();
        }
        if (!seen) return benches(v.id(), t);                        // nothing in sight: what it was
        remember(v.id(), t, n, now);
        return Math.max(1, n);
    }

    private static void remember(UUID village, StationTask t, int n, long now) {
        Counted was = COUNTED.put(key(village, t), new Counted(n, now));
        if (was == null || was.benches() != n) Ledger.note(village, NOTE + t.name(), Integer.toString(n));
    }

    /** The benches standing in this workshop, on its floor or one up. */
    static List<BlockPos> benchesIn(ServerLevel level, Ledger.Building b, Station s) {
        int[] f = footprint(b);
        List<BlockPos> out = new ArrayList<>();
        int y = b.anchor().getY();
        for (BlockPos p : BlockPos.betweenClosed(f[0], y - 1, f[1], f[2], y + 2, f[3])) {
            if (s.is().test(level.getBlockState(p))) out.add(p.immutable());
        }
        return out;
    }

    /** The workshop's footprint from its drawing: min x, min z, max x, max z. */
    static int[] footprint(Ledger.Building b) {
        return FOOT.computeIfAbsent(b.anchor().asLong() ^ ((long) b.structure().hashCode() << 1), k -> {
            int[] f = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
            for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
                f[0] = Math.min(f[0], p.pos().getX());
                f[1] = Math.min(f[1], p.pos().getZ());
                f[2] = Math.max(f[2], p.pos().getX());
                f[3] = Math.max(f[3], p.pos().getZ());
            }
            if (f[0] > f[2]) return new int[]{ b.anchor().getX() - 2, b.anchor().getZ() - 2, b.anchor().getX() + 2, b.anchor().getZ() + 2 };
            return f;
        });
    }

    /**
     * A craft's own business before its piece of work (Crafts.now): the enchanter carries its table into its new tower,
     * and a craft the town wants more hands at than it has benches for makes another bench. Null if neither was done.
     */
    @Nullable
    static String tend(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        StationTask t = f.stationTask();
        if (t == StationTask.ENCHANT) {
            String moved = tableToTheTower(level, v, f);
            if (moved != null) return moved;
        }
        Station s = STATIONS.get(t);
        if (s == null) return null;
        int have = recount(level, v, t);
        int want = curve(t, Villages.headcount(v.id()));
        if (want <= have) return null;
        long now = level.getGameTime();
        Long tried = TRIED.get(key(v.id(), t));
        if (tried != null && now - tried < RECOUNT && now >= tried) return null;
        TRIED.put(key(v.id(), t), now);
        return addBench(level, v, f, t, s, want);
    }

    /** Another bench for the craft, in the first of its workshops with room for it: made from the stores and set down. */
    @Nullable
    private static String addBench(ServerLevel level, Villages.Village v, VillageFolkEntity f, StationTask t, Station s, int want) {
        int room = roomFor(Villages.ageOf(v.id()));
        long day = level.getDayTime() / 24000L;
        for (Ledger.Building b : houses(v.id(), t)) {
            if (!Land.areaLoaded(level, b.anchor(), 8)) continue;
            List<BlockPos> standing = benchesIn(level, b, s);
            if (standing.size() >= room) {
                if (!TOLD_FULL.containsKey(key(v.id(), t) + "/" + day)) {
                    TOLD_FULL.put(key(v.id(), t) + "/" + day, day);
                    f.brain(Villages.spoken(b.structure()) + " has room for " + room + " " + s.words() + (room == 1 ? "" : "s")
                        + " in " + Villages.ageOf(v.id()).label + ", and the town wants " + want + " " + plural(t));
                }
                continue;
            }
            BlockPos spot = freeSpot(level, b, standing);
            if (spot == null) continue;
            Bench.Hand hand = Bench.handOf(level, v, f, b.structure());
            Bench.Plan plan = Bench.plan(level, v, s.item(), 1, hand);
            if (!plan.ok()) {
                f.brain("wants another " + s.words() + " for " + Villages.spoken(b.structure()) + ", short of " + plan.shortOf
                    + (plan.why.isEmpty() ? "" : " (" + plan.why + ")"));
                return null;
            }
            if (!Bench.take(level, v, plan, f)) return null;
            BlockState st = s.block().defaultBlockState();
            if (st.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
                st = st.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facingAwayFromWall(level, spot));
            }
            level.setBlock(spot, st, 3);
            f.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, spot, t == StationTask.SMITH ? SoundEvents.ANVIL_PLACE : SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            COUNTED.remove(key(v.id(), t));
            recount(level, v, t);                                        // the town's benches, this one with them
            int here = standing.size() + 1;
            Villages.tell(v.id(), day, f.displayNameCap() + " made " + ordinal(here) + " " + s.words() + " for "
                + Villages.spoken(b.structure()) + ": the town is big enough for " + want + " " + plural(t));
            return ordinal(here) + " " + s.words() + " for " + Villages.spoken(b.structure()) + ", out of the stores";
        }
        return null;
    }

    /**
     * Room for a bench in the workshop: a floor cell inside its walls, on the benches' level, clear of its doorway and of
     * every place its drawing keeps for something, with room above; against a wall if it can be, nearest the first bench.
     */
    @Nullable
    static BlockPos freeSpot(ServerLevel level, Ledger.Building b, List<BlockPos> standing) {
        int[] f = footprint(b);
        int y = standing.isEmpty() ? b.anchor().getY() : standing.get(0).getY();
        java.util.Set<Long> drawn = new java.util.HashSet<>();
        List<BlockPos> doors = new ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            drawn.add(p.pos().asLong());
            if (p.part() == BuildGoal.Part.DOOR) doors.add(p.pos());
        }
        BlockPos near = standing.isEmpty() ? b.anchor() : standing.get(0);
        List<BlockPos> spots = new ArrayList<>();
        for (int x = f[0] + 1; x <= f[2] - 1; x++) {
            for (int z = f[1] + 1; z <= f[3] - 1; z++) {
                BlockPos p = new BlockPos(x, y, z);
                if (drawn.contains(p.asLong()) || byADoor(p, doors)) continue;
                if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) continue;
                if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
                spots.add(p);
            }
        }
        spots.sort(Comparator.comparingInt((BlockPos p) -> againstAWall(level, p) ? 0 : 1).thenComparingDouble(p -> p.distSqr(near)));
        return spots.isEmpty() ? null : spots.get(0);
    }

    private static boolean byADoor(BlockPos p, List<BlockPos> doors) {
        for (BlockPos d : doors) if (Math.abs(d.getX() - p.getX()) <= 1 && Math.abs(d.getZ() - p.getZ()) <= 1) return true;
        return false;
    }

    private static boolean againstAWall(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            if (level.getBlockState(n).isFaceSturdy(level, n, d.getOpposite())) return true;
        }
        return false;
    }

    /** A bench against a wall faces into the room. */
    private static Direction facingAwayFromWall(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            if (level.getBlockState(n).isFaceSturdy(level, n, d.getOpposite())) return d.getOpposite();
        }
        return Direction.NORTH;
    }

    /**
     * The enchanter's tower up, with no table in it, and the old one at the library: the enchanter carries it over and
     * sets it where the tower's drawing has it, in the ring of its shelves.
     */
    @Nullable
    private static String tableToTheTower(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Ledger.Building tower = Villages.builtStructure(v.id(), TOWER);
        Ledger.Building library = Villages.builtStructure(v.id(), LIBRARY);
        if (tower == null || library == null) return null;
        if (!Land.areaLoaded(level, tower.anchor(), 8) || !Land.areaLoaded(level, library.anchor(), 8)) return null;
        Station s = STATIONS.get(StationTask.ENCHANT);
        if (!benchesIn(level, tower, s).isEmpty()) return null;
        List<BlockPos> old = benchesIn(level, library, s);
        if (old.isEmpty()) return null;
        BlockPos at = null;
        for (BuildGoal.Placement p : BuildGoal.plan(TOWER, tower.anchor(), tower.facing(), 13)) {
            if (p.part() == BuildGoal.Part.ENCHANTING && level.getBlockState(p.pos()).isAir()) { at = p.pos(); break; }
        }
        if (at == null) at = freeSpot(level, tower, List.of());
        if (at == null) return null;
        level.removeBlock(old.get(0), false);
        level.setBlock(at, Blocks.ENCHANTING_TABLE.defaultBlockState(), 3);
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, at, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 0.8F, 1.0F);
        Villages.tell(v.id(), level.getDayTime() / 24000L, f.displayNameCap() + " carried the enchanting table from the library to the new enchanter's tower");
        return "the enchanting table carried over from the library to the tower";
    }

    /** Tests: the craft's own business now, whatever the last try (a minute's wait forgotten). */
    @Nullable
    public static String tendForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        TRIED.remove(key(v.id(), f.stationTask()));
        COUNTED.remove(key(v.id(), f.stationTask()));
        return tend(level, v, f);
    }

    /** Tests: the craft's benches counted afresh. */
    public static int recountForTests(ServerLevel level, Villages.Village v, StationTask t) {
        COUNTED.remove(key(v.id(), t));
        return recount(level, v, t);
    }

    // ------------------------------------------------------------------ words

    /** The crafts of the town in lines: hands, the most wanted, benches and room, the workshop (/village crafts). */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        int folk = Villages.headcount(v.id());
        Villages.Age age = Villages.ageOf(v.id());
        out.add("Crafts of " + Villages.name(v.id()) + " (" + folk + " folk, " + age.label + "): a hand more each " + FOLK_PER_HAND
            + " folk; a workshop holds " + roomFor(age) + " bench" + (roomFor(age) == 1 ? "" : "es") + " of a kind now.");
        for (StationTask t : new StationTask[]{ StationTask.SMITH, StationTask.TAILOR, StationTask.BREW, StationTask.ENCHANT,
                StationTask.BEEKEEP, StationTask.BANK }) {
            int hands = 0;
            for (AssistantEntity a : Villages.folkOf(v.id())) if (a.stationTask() == t) hands++;
            Station s = STATIONS.get(t);
            String house = houseOf(v.id(), t);
            StringBuilder sb = new StringBuilder("  ").append(t.title).append(": ").append(hands).append(" at it, ")
                .append(curve(t, folk)).append(" wanted");
            if (s != null) sb.append(", ").append(recount(level, v, t)).append(" ").append(s.words()).append(benches(v.id(), t) == 1 ? "" : "s");
            sb.append(house == null ? (t == StationTask.BEEKEEP ? "; its own meadow" : "") : "; at " + Villages.spoken(house)
                + (stands(v.id(), house) ? "" : " (not built yet)"));
            out.add(sb.toString());
        }
        return out;
    }

    private static String plural(StationTask t) {
        return t.title.toLowerCase(Locale.ROOT) + "s";
    }

    private static String ordinal(int n) {
        return switch (n) {
            case 1 -> "a first";
            case 2 -> "a second";
            case 3 -> "a third";
            case 4 -> "a fourth";
            default -> "another";
        };
    }

    private static String key(UUID village, StationTask t) {
        return village + "/" + t.name();
    }
}
