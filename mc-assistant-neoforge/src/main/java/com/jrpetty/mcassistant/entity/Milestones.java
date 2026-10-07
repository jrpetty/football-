package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.MilestoneBlock;
import com.jrpetty.mcassistant.block.MilestoneBlockEntity;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [workitems] The milestones along the roads between towns (Roads). From the Stone Age the road crew sets one beside the
 * road every hundred blocks (half way along each hundred), and one where the road leaves each town's avenue and wherever
 * it crosses another road: on the side away from the lamps, its lettered face to the road, saying the town each way and
 * how far it is by the road ("ALDERTOR 120"). Each stone is out of the mother town's stores, or made on the spot of their
 * cobblestone and a sign (Bench, by its recipe: the road crew's own making). A road laid before the Stone Age gets its
 * stones afterwards, one a visit (rounds). A player who right-clicks a stone is told the way and the distance to each town
 * the road leads to; one a player sets beside a road is lettered for it.
 */
public final class Milestones {

    private Milestones() {}

    /** A stone every so many blocks of road, half way along each. */
    static final int EVERY = 100;
    /** Stones a town's stores keep ready at most. */
    static final int MOST_WANTED = 4;
    /** How often a town's roads are looked over for a stone wanted (ticks). */
    static final long LOOK = 1200L;

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** What each town still wants of stones (worked out on its rounds). */
    private static final Map<UUID, Integer> WANTED = new ConcurrentHashMap<>();

    static void resetForTests() {
        LOOKED.clear();
        WANTED.clear();
    }

    // ------------------------------------------------------------------ where they go

    /** The stones a road wants, by the step of it they stand at: its two ends, every hundred, and its crossings. */
    static List<Integer> spots(List<int[]> line, UUID colony) {
        List<Integer> out = new ArrayList<>();
        int n = line.size();
        if (n < 8) return out;
        out.add(3);
        for (int i = EVERY / 2; i < n - EVERY / 4; i += EVERY) out.add(i);
        out.add(n - 4);
        // Where another road's line passes within three blocks: a crossing.
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            if (link.getKey().equals(colony)) continue;
            Villages.Village c = Villages.get(link.getKey()), m = Villages.get(link.getValue());
            if (c == null || m == null) continue;
            List<int[]> other = Roads.line(m, c);
            for (int i = 6; i < n - 6; i += 2) {
                int[] p = line.get(i);
                boolean near = false;
                for (int j = 6; j < other.size() - 6 && !near; j += 2) {
                    int[] q = other.get(j);
                    if (Math.abs(p[0] - q[0]) <= 3 && Math.abs(p[1] - q[1]) <= 3) near = true;
                }
                if (near) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    /** Where the stone for this step of a road stands: two blocks off the road on the side away from its lamps, on the
     *  ground; and which way its face looks (to the road). Null if there is no ground for it there. */
    @Nullable
    static Object[] place(ServerLevel level, List<int[]> line, int i) {
        int[] p = line.get(i);
        boolean alongX = Math.abs(line.get(line.size() - 1)[0] - line.get(0)[0]) >= Math.abs(line.get(line.size() - 1)[1] - line.get(0)[1]);
        int sx = alongX ? p[0] : p[0] - 2, sz = alongX ? p[1] - 2 : p[1];
        if (!level.hasChunk(sx >> 4, sz >> 4)) return null;
        Roads.Ground g = Roads.ground(level, sx, sz);
        if (g == null || g.water()) return null;
        // A stone set there already stops motion, so it reads as the ground: its own cell is the place, not the air on
        // top of it (or every round would count it still wanted, and look for a stone to stand on a stone).
        BlockPos top = new BlockPos(sx, g.y(), sz);
        BlockPos at = level.getBlockState(top).getBlock() instanceof MilestoneBlock ? top : top.above();
        Direction face = alongX ? Direction.SOUTH : Direction.EAST;
        return new Object[]{ at, face };
    }

    /** What the stone at this step of the road says: the mother town one way, the colony the other (and, at a crossing,
     *  the other road's two towns). */
    static List<MilestoneBlockEntity.Way> ways(Villages.Village mother, Villages.Village colony, List<int[]> line, int i) {
        List<MilestoneBlockEntity.Way> out = new ArrayList<>();
        int[] here = line.get(i);
        int[] back = line.get(Math.max(0, i - 8)), on = line.get(Math.min(line.size() - 1, i + 8));
        out.add(new MilestoneBlockEntity.Way(Villages.name(mother.id()), mother.id(), mother.centre().getX(), mother.centre().getZ(),
            Integer.signum(back[0] - here[0]), Integer.signum(back[1] - here[1]), i + Roads.EXIT));
        out.add(new MilestoneBlockEntity.Way(Villages.name(colony.id()), colony.id(), colony.centre().getX(), colony.centre().getZ(),
            Integer.signum(on[0] - here[0]), Integer.signum(on[1] - here[1]), line.size() - 1 - i + Roads.EXIT));
        return out;
    }

    // ------------------------------------------------------------------ set by the road crew

    /**
     * A step of the road just laid (Roads.step): a stone at it, if one goes there, the age has come to them, and the
     * stores can run to one (the crew is there already). True if it set one.
     */
    public static boolean onStep(ServerLevel level, Villages.Village mother, Villages.Village colony, int index) {
        if (!Tiers.allows(level, Villages.ageOf(mother.id()), WorkItems.MILESTONE_ITEM.get())) return false;
        List<int[]> line = Roads.line(mother, colony);
        if (!spots(line, colony.id()).contains(index)) return false;
        return set(level, mother, colony, line, index, true);
    }

    /** The stone at this step of the road set, out of the stores (made there if need be), lettered. */
    static boolean set(ServerLevel level, Villages.Village mother, Villages.Village colony, List<int[]> line, int i, boolean paid) {
        Object[] pl = place(level, line, i);
        if (pl == null) return false;
        BlockPos at = (BlockPos) pl[0];
        Direction face = (Direction) pl[1];
        if (level.getBlockState(at).getBlock() instanceof MilestoneBlock) return false;
        if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) return false;
        if (paid && !fromTheStores(level, mother)) return false;
        level.setBlock(at, WorkItems.MILESTONE.get().defaultBlockState().setValue(MilestoneBlock.FACING, face), 3);
        if (level.getBlockEntity(at) instanceof MilestoneBlockEntity be) be.setWays(ways(mother, colony, line, i));
        level.playSound(null, at, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0F, 0.9F);
        WorkTools.bump(mother.id(), "work.milestones");
        if (WorkTools.count(mother.id(), "work.milestones") == 1) {
            Villages.tell(mother.id(), level.getDayTime() / 24000L, "the road crew set the first milestone on the road to " + Villages.name(colony.id()));
        }
        LOG.info("[MCA-WORK] {}: a milestone at {} on the road to {} (step {} of {})", Villages.name(mother.id()), at.toShortString(),
            Villages.name(colony.id()), i, line.size());
        WANTED.remove(mother.id());
        return true;
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** A milestone out of the stores; with none put by, made there of five cobblestone and a sign (Bench, the recipe). */
    static boolean fromTheStores(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(WorkItems.MILESTONE_ITEM.get()), 1)) return true;
        Bench.Hand hand = Bench.handOf(level, v, null, null);
        Bench.Plan plan = Bench.plan(level, v, WorkItems.MILESTONE_ITEM.get(), 1, hand);
        if (!plan.ok()) return false;
        ItemStack made = Bench.make(level, v, plan, null, hand);
        return !made.isEmpty() && Crafts.take(level, v, s -> s.is(WorkItems.MILESTONE_ITEM.get()), 1);
    }

    /**
     * The town's rounds (WorkTools.rounds, a look a minute): on each road it laid (the mother lays them), the first stone
     * it still wants where the road is laid, the ground loaded and the age come, set by the road crew (TownJobs, out of
     * the stores). How many it still wants is kept for its stores' making (WorkTools.wanted).
     */
    static void rounds(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Long last = LOOKED.get(v.id());
        if (last != null && now - last < LOOK && now >= last) return;
        LOOKED.put(v.id(), now);
        if (!Tiers.allows(level, Villages.ageOf(v.id()), WorkItems.MILESTONE_ITEM.get())) {
            WANTED.put(v.id(), 0);
            return;
        }
        int wanted = 0;
        boolean setOne = false;
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            if (!link.getValue().equals(v.id())) continue;
            Villages.Village colony = Villages.get(link.getKey());
            int[] road = Ledger.road(link.getKey());
            if (colony == null || road == null || !colony.dim().equals(level.dimension())) continue;
            List<int[]> line = Roads.line(v, colony);
            int laid = road[2] == 1 ? line.size() : road[0];
            for (int i : spots(line, colony.id())) {
                if (i >= laid) continue;
                Object[] pl = place(level, line, i);
                if (pl == null) continue;
                BlockPos at = (BlockPos) pl[0];
                if (level.getBlockState(at).getBlock() instanceof MilestoneBlock) continue;
                wanted++;
                if (setOne) continue;
                if (!TownJobs.atWork(level, v, "road/" + colony.id(), at, "setting a milestone on the road to " + Villages.name(colony.id()),
                        AssistantEntity.StationTask.HAUL)) continue;
                if (set(level, v, colony, line, i, true)) {
                    setOne = true;
                    wanted--;
                }
            }
        }
        WANTED.put(v.id(), Math.min(MOST_WANTED, wanted));
    }

    /** The stones this town still wants for its roads (no more than four ready at once). */
    static int wanted(ServerLevel level, Villages.Village v) {
        return WANTED.getOrDefault(v.id(), 0);
    }

    // ------------------------------------------------------------------ a player's

    /**
     * A stone a player has set (MilestoneBlock.setPlacedBy): lettered for the road it stands by (within six blocks of
     * one), or else for the nearest town within four hundred blocks.
     */
    public static void letter(ServerLevel level, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof MilestoneBlockEntity be)) return;
        double best = 36.0;
        List<MilestoneBlockEntity.Way> ways = null;
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            Villages.Village colony = Villages.get(link.getKey()), mother = Villages.get(link.getValue());
            if (colony == null || mother == null || !mother.dim().equals(level.dimension())) continue;
            List<int[]> line = Roads.line(mother, colony);
            for (int i = 0; i < line.size(); i++) {
                int[] p = line.get(i);
                double d = (p[0] - at.getX()) * (double) (p[0] - at.getX()) + (p[1] - at.getZ()) * (double) (p[1] - at.getZ());
                if (d < best) {
                    best = d;
                    ways = ways(mother, colony, line, i);
                }
            }
        }
        if (ways == null) {
            Villages.Village v = Villages.nearest(level, at, 400);
            if (v != null) {
                int far = (int) Math.round(Math.sqrt(v.centre().atY(0).distSqr(at.atY(0))));
                ways = List.of(new MilestoneBlockEntity.Way(Villages.name(v.id()), v.id(), v.centre().getX(), v.centre().getZ(),
                    Integer.signum(v.centre().getX() - at.getX()), Integer.signum(v.centre().getZ() - at.getZ()), far));
            }
        }
        if (ways != null) be.setWays(ways);
    }

    /** What a stone tells a player who asks: the way and the distance to each town its road leads to. */
    public static List<String> tell(Level level, BlockPos at) {
        List<String> out = new ArrayList<>();
        if (!(level.getBlockEntity(at) instanceof MilestoneBlockEntity be) || be.ways().isEmpty()) {
            out.add("A milestone, its face still blank: there is no road or town near enough to letter it for.");
            return out;
        }
        out.add("§6A milestone§r, weathered, its lettering cut deep:");
        for (MilestoneBlockEntity.Way w : be.ways()) {
            BlockPos town = new BlockPos(w.x(), at.getY(), w.z());
            int straight = (int) Math.round(Math.sqrt(town.distSqr(at)));
            out.add(" " + w.name() + ": " + w.far() + " blocks by the road, " + Guide.direction(at, town) + " (" + straight + " as the crow flies).");
        }
        return out;
    }

    /** The milestones' lines for /village items work. */
    static List<String> report(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        int set = WorkTools.count(v.id(), "work.milestones"), wanted = WANTED.getOrDefault(v.id(), 0);
        if (set > 0 || wanted > 0) out.add("Milestones: " + set + " set along the town's roads" + (wanted > 0 ? ", " + wanted + " more wanted" : "") + ".");
        return out;
    }

    /** Tests: the steps of this road the stones go at. */
    public static List<Integer> spotsForTests(UUID mother, UUID colony) {
        Villages.Village m = Villages.get(mother), c = Villages.get(colony);
        return m == null || c == null ? List.of() : spots(Roads.line(m, c), colony);
    }

    /** Tests: the town's roads looked over now for a stone wanted (and one set, the crew at once). */
    public static void roundsForTests(ServerLevel level, Villages.Village v) {
        LOOKED.remove(v.id());
        rounds(level, v);
    }

    /** Tests: where the stone for this step of the road stands. */
    @Nullable
    public static BlockPos placeForTests(ServerLevel level, UUID mother, UUID colony, int i) {
        Villages.Village m = Villages.get(mother), c = Villages.get(colony);
        if (m == null || c == null) return null;
        Object[] pl = place(level, Roads.line(m, c), i);
        return pl == null ? null : (BlockPos) pl[0];
    }
}
