package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The park's ground, and its fountain's water (entity/Park).
 *
 * <p>A park is a lawn, and a lawn is level. A house on a hillside stands on a stone footing on its low
 * side with its back in the slope (BuildGoal fills under a building's floor, no more); a park set down
 * that way had its benches and its lamps standing out over the drop, and the low side of its fountain's
 * basin over nothing, so that its water ran out of the bottom of it, over the benches and down the hill.
 * So now:
 *
 * <ul>
 * <li>its lot is chosen for flat ground (Villages.siteFor asks {@link #roughness}: earth to cut, and much
 *     more for the edge of a drop), and its lawn is laid at the middle height of the ground there
 *     ({@link #floorFor}), so that as little is cut and filled as can be;</li>
 * <li>before a stone of it is laid, its builder cuts the earth above the lawn's level away, from the top
 *     down, and fills the hollows under it, the top of the fill in earth (what it cut off the high side)
 *     and not stone, so the grass comes back over it; the high ground round the lot is eased down to a
 *     step of one block, then of two, so the park does not sit in a pit ({@link #addTo});</li>
 * <li>its keepers bank up the low ground round it the same way, a step of one and then of two, and turf
 *     any bare stone or sand in the lawn, with earth out of the stores ({@link #bank});</li>
 * <li>its fountain stands on a footing of stone (blueprints/park.txt), and water goes into it only where
 *     it will stay ({@link #holds}: a floor under it, and a wall or more of the basin's own water on every
 *     side; the spring on the pillar only over a whole basin, its spill falling clear into it). A fountain
 *     that stops holding (a stone of its rim knocked out) is emptied at once, before its water can run
 *     anywhere ({@link #watch}, {@link #onBreak}), and its keepers put the stone back out of the stores
 *     ({@link #mend}) and fill it again.</li>
 * </ul>
 *
 * <p>Earth and rock only are ever cut, and a column with anything somebody built in it is left as it is.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class ParkGround {

    private ParkGround() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Half the park's lot: it is eleven blocks across and eleven deep. */
    public static final int HALF = 5;
    /** How far round the lot its edges are eased: a step of one block, then of two. */
    public static final int EDGE = 2;
    /** Half the fountain: its rim is five blocks across. */
    public static final int BASIN = 2;
    /** How high over the lawn everything the world put there is cleared off the lot, all at once (levelNow). */
    static final int CUT_HIGH = 10;
    /** How high over the lawn its builder cuts earth away: as high as it reaches working off its scaffolding,
     *  so nothing is left hanging over the cut (a lot so steep is one the plan seldom gives a park: roughness). */
    static final int CUT_TALLEST = 24;
    /** How deep a hollow round the lot its keepers bank up: a deeper one is a drop, and is left one. */
    static final int BANK_DEEP = 8;

    static boolean isPark(@Nullable String project) {
        return Park.STRUCTURE.equals(project);
    }

    /** How many steps out from the lot a column is: 0 on it, 1 and 2 round it. */
    public static int ring(BlockPos centre, int x, int z) {
        return Math.max(0, Math.max(Math.abs(x - centre.getX()), Math.abs(z - centre.getZ())) - HALF);
    }

    static boolean underFountain(BlockPos centre, int x, int z) {
        return Math.abs(x - centre.getX()) <= BASIN && Math.abs(z - centre.getZ()) <= BASIN;
    }

    /** Earth or rock the world put there, that a spade or a pick takes away. */
    static boolean cuts(BlockState s) {
        return Terraform.earth(s) && !s.is(Blocks.BEDROCK);
    }

    /** Ground to stand on and build on: firm, and not a tree, a plant, snow or water. */
    static boolean firm(BlockState s) {
        return s.blocksMotion() && !Terraform.growth(s);
    }

    /** Is anything somebody built in this column, from this height up to the sky? Then it is nobody's to dig or fill. */
    static boolean built(BlockGetter level, int x, int z, int top, int fromY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = top; y >= fromY; y--) {
            if (!Terraform.natural(level.getBlockState(m.set(x, y, z)))) return true;
        }
        return false;
    }

    /** The highest firm block in a column, from {@code fromY} down to {@code toY}; MIN_VALUE if there is none. */
    static int firmTop(BlockGetter level, int x, int z, int fromY, int toY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = fromY; y >= toY; y--) {
            if (firm(level.getBlockState(m.set(x, y, z)))) return y;
        }
        return Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ the lot

    /**
     * The height of a park's lawn on a lot (the first free block over it): the middle of the ground's
     * heights there, half the lot higher and half lower, so that as little is cut and filled as can be.
     * Any other building keeps the height it was given.
     */
    public static BlockPos floorFor(Level level, @Nullable String project, BlockPos ground) {
        if (!isPark(project)) return ground;
        int[] tops = new int[(2 * HALF + 1) * (2 * HALF + 1)];
        int i = 0;
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                int x = ground.getX() + dx, z = ground.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) return ground;
                tops[i++] = BuildGoal.groundTop(level, x, z);
            }
        }
        Arrays.sort(tops);
        return new BlockPos(ground.getX(), tops[tops.length / 2], ground.getZ());
    }

    /**
     * What a lot asks of a park's builder besides the fill under its lawn (which Villages.siteFor counts
     * already): the earth above the lawn and its first step to cut away, and twelve times as much again for
     * every block of a hollow deeper than three under them, the edge of a drop, where a fill would stand on
     * nothing. Nothing for any other building.
     */
    public static int roughness(Level level, @Nullable String project, BlockPos floor) {
        if (!isPark(project)) return 0;
        int cut = 0, drop = 0;
        for (int dx = -HALF - 1; dx <= HALF + 1; dx++) {
            for (int dz = -HALF - 1; dz <= HALF + 1; dz++) {
                int x = floor.getX() + dx, z = floor.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = BuildGoal.groundTop(level, x, z);
                int k = ring(floor, x, z);
                if (top > floor.getY() + k) cut += top - floor.getY() - k;
                int under = floor.getY() - k - top;
                if (under > 3) drop += under - 3;
            }
        }
        return cut + 12 * drop;
    }

    // ------------------------------------------------------------------ the builder's ground work

    /**
     * The ground work a park's builder does before it lays a stone of it (BuildGoal, for a park): the earth
     * above the lawn's level dug away, from the top down (what it gives goes into the pack, and fills the
     * hollows); the high ground round the lot eased down to a step of one, then of two; and the top of the
     * fill under the lawn (BuildGoal's own) laid in earth rather than stone, so the grass comes back over it
     * (under the fountain, stone). The digging is a clearing marked as earth ({@link #digging}).
     */
    public static void addTo(Level level, BlockPos base, Direction facing, List<BuildGoal.Placement> plan) {
        Park.layout(base, facing);                                     // known from now, for its water (mayPour)
        for (int i = 0; i < plan.size(); i++) {
            BuildGoal.Placement p = plan.get(i);
            if (p.part() == BuildGoal.Part.BLOCK && p.style() == Blueprints.Style.FOUNDATION && p.pos().getY() == base.getY() - 1
                    && ring(base, p.pos().getX(), p.pos().getZ()) == 0 && !underFountain(base, p.pos().getX(), p.pos().getZ())) {
                plan.set(i, new BuildGoal.Placement(p.pos(), BuildGoal.Part.BLOCK, Blueprints.Style.SOIL, Blueprints.Way.UP));
            }
        }
        int reach = HALF + EDGE, dug = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = base.getX() + dx, z = base.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int from = base.getY() + ring(base, x, z);
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                if (top <= from || built(level, x, z, top, from)) continue;
                for (int y = Math.min(top, base.getY() + CUT_TALLEST); y >= from; y--) {
                    BlockPos c = new BlockPos(x, y, z);
                    if (cuts(level.getBlockState(c))) {
                        plan.add(new BuildGoal.Placement(c, BuildGoal.Part.CLEAR, Blueprints.Style.SOIL, Blueprints.Way.UP));
                        dug++;
                    }
                }
            }
        }
        if (dug > 0) LOG.info("[MCA-PARK] the park's ground at {}: {} blocks of earth to cut away", base.toShortString(), dug);
    }

    /** A cell of a park's ground work that is earth to dig away (a clearing marked as earth: BuildGoal). */
    public static boolean digging(BuildGoal.Placement p) {
        return p.part() == BuildGoal.Part.CLEAR && p.style() == Blueprints.Style.SOIL;
    }

    /** Is there still earth to dig in this cell of a park's ground work? */
    public static boolean dig(BuildGoal.Placement p, BlockState st) {
        return digging(p) && cuts(st);
    }

    // ------------------------------------------------------------------ the keepers' ground work

    /**
     * The hollows round the park (and any left in its lawn) to bank up, nearest first and each column from
     * the bottom: the lawn's columns to their top, the first step round it to a block under that, the
     * second to two under. A hollow deeper than {@link #BANK_DEEP} is a drop and is left; so is one with
     * water standing in it (a pond, a stream) or anything somebody set there.
     */
    static List<BlockPos> hollows(Level level, BlockPos centre) {
        List<BlockPos> out = new ArrayList<>();
        for (int k = 0; k <= EDGE; k++) {
            for (int dx = -HALF - k; dx <= HALF + k; dx++) {
                for (int dz = -HALF - k; dz <= HALF + k; dz++) {
                    int x = centre.getX() + dx, z = centre.getZ() + dz;
                    if (ring(centre, x, z) != k || !level.hasChunk(x >> 4, z >> 4)) continue;
                    int lawn = centre.getY() - k;                       // the first free block, when it is done
                    int firm = firmTop(level, x, z, lawn - 1, lawn - 1 - BANK_DEEP);
                    if (firm == Integer.MIN_VALUE || firm >= lawn - 1) continue;
                    // Round the lot, only over the world's own ground (or this bank's rough stone): not over a street, a field or a floor.
                    BlockState under = level.getBlockState(new BlockPos(x, firm, z));
                    if (k > 0 && !cuts(under) && !under.is(Blocks.COBBLESTONE) && !under.is(Blocks.COBBLED_DEEPSLATE)) continue;
                    boolean open = true;
                    for (int y = firm + 1; y <= lawn - 1 && open; y++) {
                        BlockState s = level.getBlockState(new BlockPos(x, y, z));
                        open = s.canBeReplaced() && s.getFluidState().isEmpty();
                    }
                    if (!open) continue;
                    for (int y = firm + 1; y <= lawn - 1; y++) out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    /** The lawn's top where it is bare stone or sand (the builder's fill, a rock), not earth: turfed by its keepers. */
    static List<BlockPos> bare(Level level, Park.Layout l) {
        Set<BlockPos> paths = new HashSet<>(l.paths());
        List<BlockPos> out = new ArrayList<>();
        BlockPos c = l.centre();
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                if (Math.abs(dx) <= BASIN && Math.abs(dz) <= BASIN) continue;
                BlockPos p = c.offset(dx, -1, dz);
                if (paths.contains(p)) continue;
                BlockState s = level.getBlockState(p);
                if (s.is(BlockTags.DIRT) || s.is(Blocks.DIRT_PATH)) continue;
                if (s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE) || cuts(s)) out.add(p);
            }
        }
        return out;
    }

    private static final Predicate<ItemStack> EARTH = s -> s.is(Items.DIRT) || s.is(Items.COARSE_DIRT) || s.is(Items.ROOTED_DIRT);
    private static final Predicate<ItemStack> RUBBLE = s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);

    /**
     * A visit's work on the park's ground, by a hand of the village out of the stores: the low ground round
     * it (or a hollow left in its lawn) banked up in earth, or rough stone if the stores have no earth; else
     * a bare patch of its lawn turfed with earth, what came up going into the stores. Returns what was done,
     * or null if nothing was.
     */
    @Nullable
    static String bank(ServerLevel level, Villages.Village v, Park.Layout l, int budget) {
        List<BlockPos> low = hollows(level, l.centre());
        boolean earth = Market.stock(level, v.id(), EARTH) > 0;
        if (!low.isEmpty() && (earth || Market.stock(level, v.id(), RUBBLE) > 0)) {
            if (!TownJobs.atWork(level, v, "park", low.get(0).above(), "banking up the park's edges")) return null;
            int n = 0;
            for (BlockPos p : low) {
                if (n >= budget * 2) break;
                ItemStack one = Crafts.takeOne(level, v, EARTH);
                if (one.isEmpty()) one = Crafts.takeOne(level, v, RUBBLE);
                if (one.isEmpty()) break;
                level.setBlock(p, Block.byItem(one.getItem()).defaultBlockState(), 3);
                n++;
            }
            if (n > 0) {
                level.playSound(null, low.get(0), SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
                return "banked up the park's edges";
            }
        }
        List<BlockPos> bare = earth ? bare(level, l) : List.of();
        if (!bare.isEmpty()) {
            if (!TownJobs.atWork(level, v, "park", bare.get(0).above(), "turfing the park")) return null;
            int n = 0;
            for (BlockPos p : bare) {
                if (n >= budget) break;
                ItemStack one = Crafts.takeOne(level, v, EARTH);
                if (one.isEmpty()) break;
                BlockState was = level.getBlockState(p);
                Item up = was.is(Blocks.STONE) ? Items.COBBLESTONE : was.is(Blocks.DEEPSLATE) ? Items.COBBLED_DEEPSLATE : was.getBlock().asItem();
                if (up != Items.AIR) Crafts.store(level, v, new ItemStack(up));
                level.setBlock(p, Block.byItem(one.getItem()).defaultBlockState(), 3);
                n++;
            }
            if (n > 0) {
                level.playSound(null, bare.get(0), SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
                return "turfed the park";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ all at once, for nothing

    /**
     * The park's ground made level at once and for nothing (the photographs, the tests: Park.now), as its
     * builder and its keepers leave it in the end: on the lot everything the world put above the lawn taken
     * away (earth, a tree, a bush, a puddle) and the hollows under it filled, turf on top; round it, earth
     * above the steps cut away and hollows under them banked up. A column with anything somebody built in it
     * is left as it is. Returns the blocks changed.
     */
    public static int levelNow(ServerLevel level, BlockPos centre) {
        BlockState air = Blocks.AIR.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState();
        int n = 0, reach = HALF + EDGE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = centre.getX() + dx, z = centre.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int k = ring(centre, x, z);
                int lawn = centre.getY() - k;
                int deep = k == 0 ? 16 : BANK_DEEP;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                if (built(level, x, z, Math.max(top, lawn), lawn - 1 - deep)) continue;
                // Cut: on the lot, all the world put over the lawn; round it, the earth above its step (and what grew on it).
                int stepTop = centre.getY() + k;
                for (int y = Math.max(top, centre.getY() + CUT_HIGH); y >= (k == 0 ? centre.getY() : stepTop); y--) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (s.isAir()) continue;
                    boolean go = k == 0 ? Terraform.natural(s)
                        : cuts(s) || (Terraform.growth(s) && !s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES) && cuts(level.getBlockState(m.below())));
                    if (go) {
                        level.setBlock(m, air, 2);
                        n++;
                    }
                }
                // Fill: the hollow under the lawn, or under the step round it, earth with turf on top.
                int firm = firmTop(level, x, z, lawn - 1, lawn - 1 - deep);
                if (firm == Integer.MIN_VALUE) {
                    if (k > 0) continue;                                  // a drop beside the park is left a drop
                    firm = lawn - 1 - deep;
                }
                for (int y = firm + 1; y <= lawn - 1; y++) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (k > 0 && !s.getFluidState().isEmpty()) break;     // a pond beside it is left
                    level.setBlock(m, y == lawn - 1 ? grass : dirt, 2);
                    n++;
                }
                // The lawn turfed where it was bare rock or sand.
                if (k == 0) {
                    BlockState s = level.getBlockState(m.set(x, lawn - 1, z));
                    if (cuts(s) && !s.is(BlockTags.DIRT)) {
                        level.setBlock(m, grass, 2);
                        n++;
                    }
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ the fountain's water

    /** The fountain's basin: its water at the lawn's level. */
    static Set<BlockPos> basin(Park.Layout l) {
        Set<BlockPos> out = new HashSet<>();
        for (BlockPos p : l.water()) if (p.getY() == l.centre().getY()) out.add(p);
        return out;
    }

    /** The spring on top of its pillar, or null. */
    @Nullable
    static BlockPos spring(Park.Layout l) {
        for (BlockPos p : l.water()) if (p.getY() > l.centre().getY()) return p;
        return null;
    }

    /** Does this keep water out of it: stone, earth, wood, a stair (not air, a plant, a lantern or water)? */
    static boolean wall(BlockGetter level, BlockPos p) {
        return level.getBlockState(p).blocksMotion();
    }

    /** Where the fountain must have a wall for its water to stay in it: under every cell of the basin, on every side
     *  of it that is not more of the basin, and the pillar under the spring. */
    static Set<BlockPos> walls(Park.Layout l) {
        Set<BlockPos> basin = basin(l);
        Set<BlockPos> out = new LinkedHashSet<>();
        for (BlockPos p : basin) {
            out.add(p.below());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = p.relative(d);
                if (!basin.contains(q)) out.add(q);
            }
        }
        BlockPos s = spring(l);
        if (s != null) out.add(s.below());
        return out;
    }

    /** Is the basin tight: a floor under every cell of it, and a wall or more of it on every side? */
    public static boolean sound(BlockGetter level, Park.Layout l) {
        Set<BlockPos> basin = basin(l);
        for (BlockPos p : basin) {
            if (!wall(level, p.below())) return false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = p.relative(d);
                if (!basin.contains(q) && !wall(level, q)) return false;
            }
        }
        return true;
    }

    /**
     * Would water poured here stay in the fountain? A cell of the basin, if the basin is tight. The spring,
     * if the basin under it is tight, its pillar stands, and its spill (a block each way, falling) falls
     * clear into the basin: nothing in the way to turn it aside onto the rim and over.
     */
    public static boolean holds(BlockGetter level, Park.Layout l, BlockPos p) {
        Set<BlockPos> basin = basin(l);
        if (basin.contains(p)) return sound(level, l);
        BlockPos s = spring(l);
        if (s == null || !s.equals(p) || !sound(level, l) || !wall(level, s.below())) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos fall = s.relative(d);
            if (!basin.contains(fall.atY(l.centre().getY()))) return false;
            for (BlockPos c = fall.below(); c.getY() > l.centre().getY(); c = c.below()) {
                if (wall(level, c)) return false;
            }
        }
        return true;
    }

    /** May the builder pour a bucket here (BuildGoal)? Anywhere for any other building; into a park's fountain only where it holds. */
    public static boolean mayPour(Level level, @Nullable String building, BlockPos pos) {
        if (!isPark(building)) return true;
        Park.Layout l = Park.layoutWithWater(pos);
        return l != null && holds(level, l, pos);
    }

    /** Every second (Quarters' clock): any of the village's fountains that no longer holds, emptied. */
    public static void watch(ServerLevel level, Villages.Village v) {
        for (Ledger.Building b : Park.parks(v.id())) {
            if (!level.isLoaded(b.anchor().offset(-BASIN - 1, 0, -BASIN - 1)) || !level.isLoaded(b.anchor().offset(BASIN + 1, 0, BASIN + 1))) continue;
            int n = stopLeaks(level, Park.layout(b));
            if (n > 0) LOG.info("[MCA-PARK] the fountain at {} would not hold its water: {} of it taken up till it is mended",
                b.anchor().toShortString(), n);
        }
    }

    /**
     * A fountain that would not hold, emptied: the whole of it if its basin leaks (water left in any of it
     * would find the hole), or the spring alone if only the spring's fall is spoiled. The water that would
     * have run away is simply gone; nothing is made. Returns the cells emptied.
     */
    public static int stopLeaks(Level level, Park.Layout l) {
        int n = 0;
        if (!sound(level, l)) {
            for (BlockPos p : l.water()) n += empty(level, p);
        } else {
            BlockPos s = spring(l);
            if (s != null && !level.getFluidState(s).isEmpty() && !holds(level, l, s)) n += empty(level, s);
        }
        return n;
    }

    private static int empty(Level level, BlockPos p) {
        if (level.getFluidState(p).isEmpty() || !(level.getBlockState(p).getBlock() instanceof LiquidBlock)) return 0;
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        return 1;
    }

    /**
     * A stone of a fountain broken (a pick, a player's): the fountain emptied first, so that not a drop of
     * it runs out of the gap. Its keepers put the stone back and fill it again.
     */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE);
        if (v == null) return;
        for (Ledger.Building b : Park.parks(v.id())) {
            BlockPos c = b.anchor();
            if (Math.abs(pos.getX() - c.getX()) > BASIN + 1 || Math.abs(pos.getZ() - c.getZ()) > BASIN + 1) continue;
            if (pos.getY() < c.getY() - 1 || pos.getY() > c.getY() + 2) continue;
            Park.Layout l = Park.layout(b);
            if (!walls(l).contains(pos)) continue;
            int n = 0;
            for (BlockPos p : l.water()) n += empty(level, p);
            if (n > 0) LOG.info("[MCA-PARK] a stone of the fountain at {} broken: its water taken up first ({})", c.toShortString(), n);
        }
    }

    /**
     * A visit's mending of the park's fountain, out of the stores: a stone of its floor, its rim or its
     * pillar that is gone put back (stone bricks, or cobble). Returns what was done, or null if nothing was.
     */
    @Nullable
    static String mend(ServerLevel level, Villages.Village v, Park.Layout l, int budget) {
        List<BlockPos> gaps = new ArrayList<>();
        for (BlockPos p : walls(l)) {
            if (wall(level, p)) continue;
            BlockState s = level.getBlockState(p);
            if (s.canBeReplaced() || !s.getFluidState().isEmpty()) gaps.add(p);
        }
        if (gaps.isEmpty()) return null;
        Predicate<ItemStack> bricks = s -> s.is(Items.STONE_BRICKS);
        if (Market.stock(level, v.id(), bricks.or(RUBBLE)) < 1) return null;
        if (!TownJobs.atWork(level, v, "park", gaps.get(0), "mending the park's fountain")) return null;
        // Emptied first, if it still has water in it, so that none runs out while the stones go back.
        stopLeaks(level, l);
        int n = 0;
        for (BlockPos p : gaps) {
            if (n >= budget) break;
            ItemStack one = Crafts.takeOne(level, v, bricks);
            if (one.isEmpty()) one = Crafts.takeOne(level, v, RUBBLE);
            if (one.isEmpty()) break;
            level.setBlock(p, Block.byItem(one.getItem()).defaultBlockState(), 3);
            n++;
        }
        if (n == 0) return null;
        level.playSound(null, gaps.get(0), SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        return "mended the park's fountain";
    }
}
