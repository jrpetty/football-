package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Houses that grow up with their village, so that an old village looks old.
 * <ul>
 * <li><b>Gardens and fences</b> (from the Stone Age): a fence round each house's plot with a
 *     gate to the street, and flowers either side of the path.</li>
 * <li><b>Wood, then stone, then brick.</b> In the Stone Age the timber walls are rebuilt in
 *     stone; in the Iron Age, in brick. By the Diamond Age moss has got into the old
 *     footings.</li>
 * <li><b>A second storey</b> (from the Iron Age), one house at a time, oldest first: the old
 *     roof comes off, a floor of bedrooms goes on (two more beds, a ladder up), and a new
 *     slate roof over it, with the chimney carried up. It wants planks out of the stores
 *     before it starts.</li>
 * </ul>
 * The village's builders do it a few blocks at a time, as town work.
 */
public final class Grow {

    private Grow() {}

    /** The materials of a house by its village's age. */
    static final Showcase.Palette STONE = new Showcase.Palette(Blocks.STONE_BRICKS, Blocks.SPRUCE_LOG, Blocks.DARK_OAK_STAIRS,
        Blocks.DARK_OAK_SLAB, Blocks.DARK_OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE,
        Blocks.SPRUCE_FENCE_GATE, Blocks.RED_BED, Blocks.RED_CARPET);
    static final Showcase.Palette BRICK = new Showcase.Palette(Blocks.BRICKS, Blocks.DARK_OAK_LOG, Blocks.DEEPSLATE_TILE_STAIRS,
        Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILES, Blocks.OAK_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.DARK_OAK_FENCE,
        Blocks.DARK_OAK_FENCE_GATE, Blocks.BLUE_BED, Blocks.BLUE_CARPET);

    private static final Block[] FLOWERS = { Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.ALLIUM,
        Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.PINK_TULIP };

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Long>> GARDENED = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Long>> STARTED = new ConcurrentHashMap<>();
    /** How many times each layer of a rising storey has been laid (anchor:y). */
    private static final Map<String, Integer> TRIED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LAST.clear();
        GARDENED.clear();
        STARTED.clear();
        TRIED.clear();
    }

    public static Showcase.Palette palette(Villages.Age age) {
        return age.ordinal() >= Villages.Age.IRON.ordinal() ? BRICK : STONE;
    }

    /** Every quarter of a minute: a little more done to the village's houses. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now - LAST.getOrDefault(id, -100000L) < 300L) return;
        LAST.put(id, now);
        work(level, v, 24);
    }

    /** Up to so many blocks of work on the houses. Returns the blocks changed. */
    public static int work(ServerLevel level, Villages.Village v, int budget) {
        UUID id = v.id();
        Villages.Age age = Villages.ageOf(id);
        if (age.ordinal() < Villages.Age.STONE.ordinal()) return 0;
        int done = 0;
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (!b.structure().equals("house") || !Land.areaLoaded(level, b.anchor(), 7)) continue;
            done += garden(level, id, b);
            if (done >= budget) break;
            done += reface(level, b, age, budget - done, Ledger.grown(id, b.anchor()));
            if (done >= budget) break;
            if (age.ordinal() >= Villages.Age.IRON.ordinal() && !Ledger.grown(id, b.anchor())) {
                done += storey(level, v, b, budget - done, palette(age));
                break;                                                   // one house at a time
            }
        }
        return done;
    }

    // ------------------------------------------------------------------ the garden

    /** A fence round the plot with a gate to the street, and flowers by the path. Once a house. */
    public static int garden(ServerLevel level, UUID village, Ledger.Building b) {
        if (!GARDENED.computeIfAbsent(village, k -> ConcurrentHashMap.newKeySet()).add(b.anchor().asLong())) return 0;
        Direction back = b.facing(), right = back.getClockWise(), front = back.getOpposite();
        int y = b.anchor().getY();
        int n = 0;
        List<BlockPos> fences = new ArrayList<>();
        for (int a = -5; a <= 5; a++) {
            for (int c = -5; c <= 5; c++) {
                if (Math.max(Math.abs(a), Math.abs(c)) != 5) continue;
                BlockPos at = b.anchor().relative(right, a).relative(back, c).atY(y);
                if (!plot(level, village, b, at)) continue;
                if (c == -5 && a == 0) {
                    level.setBlock(at, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, front), 3);
                } else if (c == -5 && Math.abs(a) == 1) {
                    continue;                                               // the path to the gate
                } else {
                    level.setBlock(at, Blocks.OAK_FENCE.defaultBlockState(), 3);
                    fences.add(at);
                }
                n++;
            }
        }
        for (BlockPos f : fences) {
            BlockState st = level.getBlockState(f);
            BlockState joined = Block.updateFromNeighbourShapes(st, level, f);
            if (joined != st) level.setBlock(f, joined, 3);
        }
        int[][] beds = { { -3, -4 }, { -2, -4 }, { 2, -4 }, { 3, -4 }, { -4, -4 }, { 4, -4 } };
        for (int[] p : beds) {
            BlockPos at = b.anchor().relative(right, p[0]).relative(back, p[1]).atY(y);
            if (!plot(level, village, b, at)) continue;
            Block flower = FLOWERS[Math.floorMod((int) (at.asLong() * 31), FLOWERS.length)];
            level.setBlock(at, Math.abs(p[0]) == 4 ? Blocks.FLOWERING_AZALEA.defaultBlockState() : flower.defaultBlockState(), 3);
            n++;
        }
        return n;
    }

    /** Somewhere in a house's plot to plant or fence: open air on plain ground, nobody else's building. */
    static boolean plot(ServerLevel level, UUID village, Ledger.Building b, BlockPos at) {
        if (!level.getBlockState(at).isAir()) return false;
        BlockState ground = level.getBlockState(at.below());
        if (!(ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.PODZOL))) return false;
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(village).entrySet()) {
            int[] half = BuildGoal.footprint(e.getKey());
            int r = Math.max(half[0], half[1]) + 1;
            BlockPos c = e.getValue().anchor();
            if (Math.abs(at.getX() - c.getX()) <= r && Math.abs(at.getZ() - c.getZ()) <= r) return false;   // going up there
        }
        for (Ledger.Building o : Ledger.buildings(village)) {
            if (o == b || o.anchor().equals(b.anchor())) continue;
            int[] half = BuildGoal.footprint(o.structure());
            int r = o.structure().equals("fortify") ? 0 : Math.max(half[0], half[1]) + 1;
            if (r > 0 && Math.abs(at.getX() - o.anchor().getX()) <= r && Math.abs(at.getZ() - o.anchor().getZ()) <= r) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ wood, stone, brick

    /** The house's timber walls rebuilt in stone (Stone Age) or brick (Iron Age on). Returns blocks changed. */
    public static int reface(ServerLevel level, Ledger.Building b, Villages.Age age, int budget, boolean grown) {
        Block want = palette(age).walls();
        boolean old = age.ordinal() >= Villages.Age.DIAMOND.ordinal();
        int n = 0;
        for (BuildGoal.Placement p : BuildGoal.plan(grown ? "house2" : "house", b.anchor(), b.facing(), 13)) {
            if (n >= budget) break;
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            BlockState now = level.getBlockState(p.pos());
            switch (p.style()) {
                case WALL -> {
                    boolean timber = now.is(BlockTags.PLANKS);
                    boolean stone = now.is(Blocks.STONE_BRICKS) && want == Blocks.BRICKS;
                    if (timber || stone) {
                        level.setBlock(p.pos(), want.defaultBlockState(), 3);
                        n++;
                    }
                }
                case FOUNDATION, WALL_LOW -> {
                    if (old && now.is(Blocks.COBBLESTONE) && Math.floorMod(p.pos().hashCode(), 3) == 0) {
                        level.setBlock(p.pos(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
                        n++;
                    }
                }
                default -> { }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ the second storey

    /** Raise the house a storey: the old roof off, then the new rooms and roof a layer at a time. */
    public static int storey(ServerLevel level, Villages.Village v, Ledger.Building b, int budget, Showcase.Palette pal) {
        UUID id = v.id();
        Set<Long> started = STARTED.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        if (!started.contains(b.anchor().asLong()) && !Ledger.raising(id, b.anchor())) {
            // The timber for it, out of the stores, before the roof comes off.
            if (!TownWork.take(level, v, s -> s.is(ItemTags.PLANKS), 32)
                    && !TownWork.take(level, v, s -> s.is(ItemTags.LOGS), 8)) return 0;
            started.add(b.anchor().asLong());
            Villages.tell(id, level.getDayTime() / 24000L, "the builders began a second storey on the house at "
                + b.anchor().getX() + ", " + b.anchor().getZ());
        }
        List<BuildGoal.Placement> was = BuildGoal.plan("house", b.anchor(), b.facing(), 13);
        List<BuildGoal.Placement> will = BuildGoal.plan("house2", b.anchor(), b.facing(), 13);
        Map<BlockPos, BuildGoal.Placement> next = new HashMap<>();
        for (BuildGoal.Placement p : will) next.put(p.pos(), p);
        // The old roof off, from the top down (once: after that, what stands there is the new storey).
        List<BuildGoal.Placement> off = new ArrayList<>();
        boolean stripped = Ledger.raising(id, b.anchor());
        for (BuildGoal.Placement p : stripped ? List.<BuildGoal.Placement>of() : was) {
            if (p.pos().getY() < b.anchor().getY() + 3 || p.part() == BuildGoal.Part.CLEAR) continue;
            BuildGoal.Placement q = next.get(p.pos());
            if (q != null && q.part() == p.part() && q.style() == p.style()) continue;
            if (level.getBlockState(p.pos()).isAir()) continue;
            off.add(p);
        }
        // Whatever stands where the new ladder goes up (the old chest): its contents into the stores.
        for (BuildGoal.Placement q : will) {
            if (q.part() != BuildGoal.Part.LADDER || q.pos().getY() >= b.anchor().getY() + 3) continue;
            BlockState there = level.getBlockState(q.pos());
            if (there.isAir() || there.is(Blocks.LADDER) || there.canBeReplaced()) continue;
            if (level.getBlockEntity(q.pos()) instanceof net.minecraft.world.Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    net.minecraft.world.item.ItemStack st = c.removeItemNoUpdate(i);
                    if (!st.isEmpty()) {
                        net.minecraft.world.item.ItemStack left = Market.intoStores(level, id, st);
                        if (!left.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, q.pos().getX() + 0.5,
                            q.pos().getY() + 0.5, q.pos().getZ() + 0.5, left);
                    }
                }
            }
            level.setBlock(q.pos(), Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        if (off.isEmpty() && !stripped) Ledger.raising(id, b.anchor(), true);
        if (!off.isEmpty()) {
            off.sort((x, y) -> y.pos().getY() - x.pos().getY());
            int n = 0;
            for (BuildGoal.Placement p : off) {
                if (n >= budget) break;
                level.setBlock(p.pos(), Blocks.AIR.defaultBlockState(), 2 | 16);
                n++;
            }
            return n;
        }
        // The new storey, a layer at a time from the bottom. A layer tried three times is done with:
        // whatever would not go in (something in the way) must not hold up the roof.
        int lowest = Integer.MAX_VALUE;
        Set<BlockPos> missing = new HashSet<>();
        for (BuildGoal.Placement p : will) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            BlockState now = level.getBlockState(p.pos());
            if (!now.isAir() && !(now.canBeReplaced() && now.getFluidState().isEmpty())) continue;
            if (TRIED.getOrDefault(b.anchor().asLong() + ":" + p.pos().getY(), 0) >= 3) continue;
            missing.add(p.pos());
            lowest = Math.min(lowest, p.pos().getY());
        }
        if (missing.isEmpty()) {
            TRIED.keySet().removeIf(k -> k.startsWith(b.anchor().asLong() + ":"));
            Ledger.grow(id, b.anchor());
            Ledger.raising(id, b.anchor(), false);
            started.remove(b.anchor().asLong());
            Villages.tell(id, level.getDayTime() / 24000L, "the house at " + b.anchor().getX() + ", " + b.anchor().getZ()
                + " got its second storey");
            return 0;
        }
        final int layer = lowest;
        TRIED.merge(b.anchor().asLong() + ":" + layer, 1, Integer::sum);
        Set<BlockPos> now = new HashSet<>();
        for (BlockPos p : missing) if (p.getY() == layer && now.size() < Math.max(budget, 12)) now.add(p);
        return BuildGoal.stampOnly(level, "house2", b.anchor(), b.facing(), 13, Showcase.painter(pal),
            p -> now.contains(p.pos()));
    }

    /** Grow a house all at once (the showcase, tests): garden, walls and storey for this age. */
    public static void now(ServerLevel level, Villages.Village v, Ledger.Building b, Villages.Age age) {
        garden(level, v.id(), b);
        if (age.ordinal() >= Villages.Age.IRON.ordinal()) {
            STARTED.computeIfAbsent(v.id(), k -> ConcurrentHashMap.newKeySet()).add(b.anchor().asLong());
            for (int i = 0; i < 40 && !Ledger.grown(v.id(), b.anchor()); i++) storey(level, v, b, 400, palette(age));
        }
        reface(level, b, age, 1000, Ledger.grown(v.id(), b.anchor()));
    }
}
