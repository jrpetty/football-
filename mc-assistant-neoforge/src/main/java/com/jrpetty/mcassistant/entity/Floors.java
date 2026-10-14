package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [tidy] No grass inside a building. A house is a floor of boards over its footing, never the meadow it went up on:
 * no grass block, dirt, podzol, mycelium or moss where its drawing lays a block, and nothing growing wild (grass,
 * ferns, flowers) standing on its floor.
 *
 * <p>The builder lays the floor over the ground now (BuildGoal.earthUnderfoot): a grass block is no soft cell, so it
 * used to count as built, and every house raised on a meadow kept the meadow for its floor, the tall grass coming up
 * through it again with the random ticks. The town's older buildings are put right on the tidy crew's round (Tidy):
 * a few buildings looked over a visit, and any earth found where the drawing wants a block is lifted (into the
 * stores) and floored with what the rest of that floor is laid in, out of the stores; any plant standing on the
 * floor is taken up. A building with earth in its drawing (a flower box, an orchard's or allotment's beds) keeps it,
 * and a park keeps its lawn.
 */
public final class Floors {

    private Floors() {}

    /** One thing to put right inside a building: earth where a block is drawn ({@code lay}), or a plant on its floor. */
    public record Fix(BlockPos pos, boolean lay, Blueprints.Style style, String building) {}

    /** What the round last found inside each building (by village, by anchor): the looks are measured off this, so no
     *  tick has to go over every building of a big town at once. */
    private static final Map<UUID, Map<Long, Integer>> KNOWN = new ConcurrentHashMap<>();

    /** The drawing's placements for a building, worked out once (they never change for an anchor). */
    private static final Map<String, List<BuildGoal.Placement>> PLANS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        PLANS.clear();
        KNOWN.clear();
        CURSOR.clear();
    }

    /** The drawing a building of the register stands to: a house given its second storey is the two-storey house. */
    @Nullable
    public static String drawing(UUID village, Ledger.Building b) {
        String s = b.structure();
        if (s.equals("house") && Ledger.grown(village, b.anchor())) s = "house2";
        if (Park.STRUCTURE.equals(s) || !Blueprints.has(s)) return null;      // a park keeps its lawn; no drawing, nothing to hold it to
        return s;
    }

    private static List<BuildGoal.Placement> plan(String drawing, Ledger.Building b) {
        String key = drawing + "@" + b.anchor().asLong() + "/" + b.facing().get2DDataValue();
        return PLANS.computeIfAbsent(key, k -> List.copyOf(BuildGoal.plan(drawing, b.anchor(), b.facing(), 13)));
    }

    /** Everything to put right inside this building now (earth under its floor, plants on it), or empty. */
    public static List<Fix> inside(Level level, UUID village, Ledger.Building b) {
        String drawing = drawing(village, b);
        if (drawing == null) return List.of();
        return inside(level, drawing, b);
    }

    /** As {@link #inside(Level, UUID, Ledger.Building)}, for a given drawing (the tests' buildings, not in any register). */
    public static List<Fix> inside(Level level, String drawing, Ledger.Building b) {
        BlockPos a = b.anchor();
        if (!level.isLoaded(a)) return List.of();
        List<BuildGoal.Placement> plan = plan(drawing, b);
        java.util.Set<BlockPos> planned = new java.util.HashSet<>();
        for (BuildGoal.Placement p : plan) planned.add(p.pos());
        List<Fix> out = new ArrayList<>();
        for (BuildGoal.Placement p : plan) {
            if (!level.isLoaded(p.pos())) continue;
            BlockState st = level.getBlockState(p.pos());
            if (BuildGoal.earthUnderfoot(p, st)) out.add(new Fix(p.pos(), true, p.style(), drawing));
            // A plant standing on a floor cell, where the drawing leaves the room open.
            if (p.part() != BuildGoal.Part.BLOCK || p.style() == Blueprints.Style.SOIL) continue;
            for (int up = 1; up <= 2; up++) {
                BlockPos q = p.pos().above(up);
                if (planned.contains(q) || !BuildGoal.isWildPlant(level.getBlockState(q))) break;
                out.add(new Fix(q.immutable(), false, p.style(), drawing));
            }
        }
        return out;
    }

    /** How much grass, earth and weeds the round last found inside the village's buildings (cheap: no building looked at). */
    public static int known(UUID village) {
        int n = 0;
        for (int k : KNOWN.getOrDefault(village, Map.of()).values()) n += k;
        return n;
    }

    /** How much grass and earth is inside this village's buildings now, every building looked at: {earth where a floor
     *  is drawn, plants on floors}. For the audit and the tests. */
    public static int[] count(Level level, UUID village) {
        int[] n = new int[2];
        for (Ledger.Building b : Ledger.buildings(village)) {
            for (Fix f : inside(level, village, b)) n[f.lay() ? 0 : 1]++;
        }
        return n;
    }

    // ------------------------------------------------------------------ putting it right

    /**
     * Put one fix right (the hand is at it): a plant on the floor taken up (into the stores), or the earth lifted
     * (into the stores) and floored with what the rest of the floor is laid in. False if the stores have nothing to
     * floor it with (it waits for them).
     */
    public static boolean put(ServerLevel level, Villages.Village v, Fix f) {
        // [integrity] The building's own cells, mended to its drawing: through the town's gate as a repair.
        Ledger.Building own = buildingAt(v.id(), f);
        if (PlayerBlocks.placed(level, f.pos())
                || own != null && !TownBlocks.mayBuild(level, v, f.pos(), own.anchor(), TownBlocks.Why.REPAIR)) return false;
        BlockState there = level.getBlockState(f.pos());
        if (!f.lay()) {
            if (!BuildGoal.isWildPlant(there)) return true;
            for (ItemStack drop : Block.getDrops(there, level, f.pos(), null)) TidyFixes.store(level, v, f.pos(), drop);
            level.setBlock(f.pos(), Blocks.AIR.defaultBlockState(), 3);
            return true;
        }
        if (!BuildGoal.bareEarth(there)) return true;
        BlockState floor = floorFor(level, v, f);
        if (floor == null) return false;
        // Anything growing on the earth comes up with the turf.
        BlockState above = level.getBlockState(f.pos().above());
        if (BuildGoal.isWildPlant(above)) level.setBlock(f.pos().above(), Blocks.AIR.defaultBlockState(), 2 | 16);
        TidyFixes.store(level, v, f.pos(), new ItemStack(Items.DIRT));
        level.setBlockAndUpdate(f.pos(), floor);
        return true;
    }

    /**
     * What to floor this cell with, out of the stores: the block the most of this building's other cells of the same
     * kind are laid in (the floor's own boards, the footing's own stone); failing that, the kind's usual (boards for a
     * floor, cobble for a footing). [styles] A theme's floor block, once themes are data, goes here first.
     */
    @Nullable
    static BlockState floorFor(ServerLevel level, Villages.Village v, Fix f) {
        Map<Block, Integer> seen = new HashMap<>();
        Ledger.Building b = buildingAt(v.id(), f);
        if (b != null) {
            for (BuildGoal.Placement p : plan(f.building(), b)) {
                if (p.style() != f.style() || p.part() != BuildGoal.Part.BLOCK) continue;
                BlockState st = level.getBlockState(p.pos());
                if (st.isAir() || BuildGoal.bareEarth(st) || !st.isCollisionShapeFullBlock(level, p.pos())) continue;
                seen.merge(st.getBlock(), 1, Integer::sum);
            }
        }
        List<Map.Entry<Block, Integer>> order = new ArrayList<>(seen.entrySet());
        order.sort((x, y) -> y.getValue() - x.getValue());
        for (Map.Entry<Block, Integer> e : order) {
            Item item = e.getKey().asItem();
            if (item == Items.AIR) continue;
            if (TownWork.take(level, v, s -> s.is(item), 1)) return e.getKey().defaultBlockState();
        }
        boolean stone = f.style() == Blueprints.Style.FOUNDATION || f.style() == Blueprints.Style.WALL_LOW
            || f.style() == Blueprints.Style.MASONRY || f.style() == Blueprints.Style.STONE_SLAB || f.style() == Blueprints.Style.STONE_STAIR;
        ItemStack got = stone
            ? Crafts.takeOne(level, v, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE_BRICKS) || s.is(Items.COBBLED_DEEPSLATE))
            : Crafts.takeOne(level, v, s -> s.is(ItemTags.PLANKS));
        if (got.isEmpty()) got = Crafts.takeOne(level, v, s -> s.is(ItemTags.PLANKS) || s.is(Items.COBBLESTONE));
        if (got.isEmpty() || !(got.getItem() instanceof BlockItem bi)) return null;
        return bi.getBlock().defaultBlockState();
    }

    @Nullable
    private static Ledger.Building buildingAt(UUID village, Fix f) {
        for (Ledger.Building b : Ledger.buildings(village)) {
            String d = drawing(village, b);
            if (d == null || !d.equals(f.building())) continue;
            int[] h = Blueprints.fullHalf(d);
            int r = Math.max(h[0], h[1]) + 1;
            if (Math.abs(f.pos().getX() - b.anchor().getX()) <= r && Math.abs(f.pos().getZ() - b.anchor().getZ()) <= r) return b;
        }
        return null;
    }

    // ------------------------------------------------------------------ the round

    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();
    /** Buildings looked over a visit. */
    static final int LOOK = 4;

    /**
     * The next fix inside the town's buildings, looking over a few buildings a visit (round and round the register,
     * so the whole town is gone over every day or so); null when those had nothing to put right.
     */
    @Nullable
    public static Fix next(ServerLevel level, Villages.Village v) {
        List<Ledger.Building> all = Ledger.buildings(v.id());
        if (all.isEmpty()) return null;
        int cursor = CURSOR.getOrDefault(v.id(), 0);
        for (int looked = 0; looked < Math.min(LOOK, all.size()); looked++) {
            Ledger.Building b = all.get(Math.floorMod(cursor, all.size()));
            if (Villages.sitesOf(v.id()).containsKey(b.structure()) && Villages.sitesOf(v.id()).get(b.structure()).anchor().equals(b.anchor())) {
                cursor++;
                continue;                                              // going up again just now: the builder's
            }
            List<Fix> fixes = inside(level, v.id(), b);
            KNOWN.computeIfAbsent(v.id(), k -> new ConcurrentHashMap<>()).put(b.anchor().asLong(), fixes.size());
            if (!fixes.isEmpty()) {
                CURSOR.put(v.id(), cursor);
                return fixes.get(0);
            }
            cursor++;
        }
        CURSOR.put(v.id(), cursor);
        return null;
    }

    /** Every building looked over now, what is inside each kept (the audit, the command, the tests). */
    static void lookOverAll(ServerLevel level, Villages.Village v) {
        Map<Long, Integer> known = KNOWN.computeIfAbsent(v.id(), k -> new ConcurrentHashMap<>());
        for (Ledger.Building b : Ledger.buildings(v.id())) known.put(b.anchor().asLong(), inside(level, v.id(), b).size());
    }

    /** Every fix inside the building this one is in, within a few blocks of it (done together, the hand once there). */
    public static List<Fix> near(ServerLevel level, Villages.Village v, Fix f, int reach) {
        Ledger.Building b = buildingAt(v.id(), f);
        if (b == null) return List.of(f);
        List<Fix> out = new ArrayList<>();
        for (Fix g : inside(level, f.building(), b)) {
            if (g.pos().distManhattan(f.pos()) <= reach) out.add(g);
        }
        if (out.isEmpty()) out.add(f);
        return out;
    }
}
