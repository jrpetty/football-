package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featbuild] The quarry: the stone supply the town's buildings draw on, out past the land it will grow into.
 *
 * <p><b>Where.</b> Once a town of ten has its storehouse, the council picks a quarry site out past its growth line (the plan's
 * reach a ring beyond the town as it stands, as econ-51's land use reckons it: LandUse.growthReach), off the radial roads,
 * off the farmland and the mine's ground, on dry ground no steeper than six blocks across: a rock outcrop if there is one
 * in reach (the more bare stone at the surface the better), else the flattest ground, dug down. Its stone yard (StoneYard)
 * goes on the town side of it, out past the line as well.
 *
 * <p><b>The pit.</b> A square nine across to begin with, cut in terraces: every cell is cut a block deeper than the one
 * outside it, so no step in it is more than a block and nothing is left a sheer drop; four deep at nine across, six at most.
 * An outcrop over the rim's level is cut down to it first. Nothing is cut next to water or lava, nor where the floor
 * would open into a cave, nor anybody's work. A fence goes round the rim with a gate toward the town and a lantern at each
 * corner and every six posts. Worked out, the pit is opened two wider each way (twenty-one across at most), never into the
 * growth line, the yard, the roads or the farmland; when it can grow no more, or the town grows out to it, it is closed
 * (its fence and lanterns back into the stores, the terraces left as safe steps) and a new site chosen further out.
 *
 * <p><b>The quarrymen.</b> The quarry's hands (one, and one more for every twenty folk, three at most) are taken from the
 * miners first (unless the age is waiting on the mine), else from whoever the town can spare. By day each walks out to the
 * face, cuts a block every so often with its pick (the sound and the dust of it), and when it has a load (sixteen) walks it
 * to the yard and stacks it; when the town's stores are low on stone it carts a load on to them. The yard's mason dresses
 * the stone while a quarryman is there. Nobody is ever put anywhere: they walk, and a load that cannot be walked to the
 * yard is left at the quarry's pile for the yard to fetch. Ore it cuts (coal, iron) goes to the stores with its load.
 */
public final class Quarry {

    private Quarry() {}

    static final String SITE = "quarry.site", MEN = "quarry.men", CUT = "quarry.cut";
    public static final int START = 9, WIDEST = 21, DEEPEST = 6;
    /** Folk a town has before it opens a quarry. */
    static final int FOLK = 10;
    /** A quarryman's load. */
    static final int LOAD = 16;
    /** Ticks between blocks cut (with a stone pick; quicker with better). */
    static final int CUT_EVERY = 24;
    /** Ticks a quarryman tries to get to a spot before it gives that spot up. */
    static final int TRY = 600;

    private static volatile boolean standDown;
    private static volatile boolean readyForTests;

    public static void standDownForTests(boolean down) {
        standDown = down;
    }

    /** Stood down for the other features' tests (the yard too). */
    static boolean stoodDown() {
        return standDown;
    }

    public static void readyForTests(boolean on) {
        readyForTests = on;
    }

    public static void resetForTests() {
        MEN_STATE.clear();
        LOOKED.clear();
        CURSOR.clear();
        FENCES.clear();
        UNREACHABLE.clear();
        readyForTests = false;
        StoneYard.resetForTests();
    }

    // ------------------------------------------------------------------ the site

    /** The quarry's middle (its rim's level), its size across. */
    public record Site(BlockPos centre, int size, boolean outcrop) {
        int half() {
            return size / 2;
        }

        /** How deep this cell (offset from the middle) is to be cut: a block deeper than the cell outside it. */
        int depth(int dx, int dz) {
            int d = half() - Math.max(Math.abs(dx), Math.abs(dz));
            if (d < 0) return 0;
            return Math.min(d + 1, Math.min(DEEPEST, half()));
        }
    }

    @Nullable
    public static Site site(UUID town) {
        String s = Ledger.note(town, SITE);
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length < 5) return null;
        try {
            return new Site(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), Integer.parseInt(p[3]), "1".equals(p[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void save(UUID town, Site s) {
        Ledger.note(town, SITE, s.centre().getX() + "," + s.centre().getY() + "," + s.centre().getZ() + "," + s.size() + "," + (s.outcrop() ? 1 : 0));
    }

    /** The town's growth line, blocks from the heart: the plan's reach a ring past the town as it stands (as econ-51's
     *  LandUse.growthReach reckons it, so the quarry keeps off the land the town will want). */
    public static int growthReach(UUID town) {
        int b = 0;
        for (String s : Villages.builtList(town)) if (!"colony".equals(s)) b++;
        int rings = b <= 14 ? 1 : b <= 44 ? 2 : CityGrowth.rings(town);
        int line = TownPlan.reach(Math.min(TownPlan.MAX_RINGS, rings + 1)) + 2;
        com.jrpetty.mcassistant.village.WallPlan.Sides wall = CastleWall.begun(town) ? CastleWall.sides(town) : null;
        if (wall != null) line = Math.max(line, Math.max(Math.max(wall.n(), wall.e()), Math.max(wall.s(), wall.w())) + com.jrpetty.mcassistant.village.WallPlan.BAND + 4);
        return line;
    }

    /** Is a square this big round this spot clear of what the town needs: its growth room, the roads out, its farmland, its
     *  mine's ground, its yard? */
    static boolean clearOfTown(UUID town, BlockPos heart, int cx, int cz, int half, @Nullable StoneYard.Site yard) {
        int dx = cx - heart.getX(), dz = cz - heart.getZ();
        int near = Math.max(Math.abs(dx), Math.abs(dz)) - half - 1;
        if (near <= growthReach(town)) return false;
        // The radial roads (the avenues run out north, east, south and west from the heart).
        if (Math.abs(dx) - half - 1 <= TownPlan.AVENUE + 6 || Math.abs(dz) - half - 1 <= TownPlan.AVENUE + 6) return false;
        if (Villages.onFarmland(town, dx, dz, half + 1, half + 1)) return false;
        if (MineGround.meets(town, cx - half - 2, cz - half - 2, cx + half + 2, cz + half + 2)) return false;
        if (yard != null) {
            int[] b = StoneYard.box(yard);
            if (cx + half + 2 >= b[0] && cx - half - 2 <= b[2] && cz + half + 2 >= b[1] && cz - half - 2 <= b[3]) return false;
        }
        return true;
    }

    /** The surface (top solid block's y) at a column. */
    static int surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
        while (y > level.getMinBuildHeight() + 1) {
            BlockState st = level.getBlockState(p.setY(y));
            if (CastleWall.natural(st) && st.getFluidState().isEmpty()) return y;
            if (!st.getFluidState().isEmpty()) return Integer.MIN_VALUE;          // water: no quarry here
            y--;
        }
        return Integer.MIN_VALUE;
    }

    static boolean rock(BlockState st) {
        return st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.CALCITE);
    }

    /** Pick the site: an outcrop out past the growth line if there is one in reach, else the flattest dry ground. */
    @Nullable
    static Site choose(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        int reach = growthReach(id);
        int half = START / 2;
        Site best = null, earth = null;
        double bestScore = -1e9, earthScore = -1e9;
        for (int ring : new int[]{ 22, 34, 48 }) {
            int d = reach + ring;
            for (int k = 0; k < 16; k++) {
                double a = Math.PI * 2 * k / 16.0 + Math.PI / 16.0;
                int cx = heart.getX() + (int) Math.round(Math.cos(a) * d), cz = heart.getZ() + (int) Math.round(Math.sin(a) * d);
                if (!level.hasChunk(cx >> 4, cz >> 4) || !level.hasChunk((cx + 12) >> 4, (cz + 12) >> 4) || !level.hasChunk((cx - 12) >> 4, (cz - 12) >> 4)) continue;
                if (!clearOfTown(id, heart, cx, cz, half, null)) continue;
                int rocky = 0, deep = 0, cells = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                List<Integer> heights = new ArrayList<>();
                boolean wet = false, built = false;
                for (int x = cx - half - 1; x <= cx + half + 1 && !wet && !built; x++) {
                    for (int z = cz - half - 1; z <= cz + half + 1; z++) {
                        int y = surface(level, x, z);
                        if (y == Integer.MIN_VALUE) { wet = true; break; }
                        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        for (int up = y + 1; up < top; up++) {
                            BlockPos p = new BlockPos(x, up, z);
                            BlockState st = level.getBlockState(p);
                            if (!st.isAir() && !CastleWall.wild(level, p, st) && !CastleWall.natural(st)) { built = true; break; }
                        }
                        cells++;
                        heights.add(y);
                        lo = Math.min(lo, y);
                        hi = Math.max(hi, y);
                        if (rock(level.getBlockState(new BlockPos(x, y, z)))) rocky++;
                        // And the rock under it within the pit's depth (a dig down that only ever meets sand gives no stone).
                        if (((x + z) & 1) == 0) for (int dd = 1; dd <= DEEPEST - 1; dd++) if (rock(level.getBlockState(new BlockPos(x, y - dd, z)))) deep++;
                    }
                }
                if (wet || built || cells == 0 || hi - lo > 6) continue;
                heights.sort(Integer::compare);
                int rim = heights.get(heights.size() / 2);
                // Water anywhere near the pit's deepest cut: no.
                if (waterNear(level, cx, rim, cz, half + 2, DEEPEST + 1)) continue;
                double score = rocky * 3.0 + deep * 0.5 - (hi - lo) * 2.0 - ring * 0.3;
                // Too little stone within reach (sand, clay or earth all the way down) is only a last resort.
                if (deep * 2 < cells) {
                    if (score > earthScore) { earthScore = score; earth = new Site(new BlockPos(cx, rim, cz), START, false); }
                    continue;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = new Site(new BlockPos(cx, rim, cz), START, rocky * 4 >= cells);
                }
            }
            if (best != null && best.outcrop()) break;
        }
        return best != null ? best : earth;
    }

    static boolean waterNear(ServerLevel level, int cx, int y0, int cz, int half, int deep) {
        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                for (int y = y0 - deep; y <= y0 + 1; y++) {
                    if (!level.getFluidState(new BlockPos(x, y, z)).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /** The yard's place: on the town side of the pit, past the fence, the gate facing the town. */
    static StoneYard.Site yardFor(ServerLevel level, Villages.Village v, Site q) {
        BlockPos heart = v.centre();
        int dx = heart.getX() - q.centre().getX(), dz = heart.getZ() - q.centre().getZ();
        Direction toTown = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        int off = WIDEST / 2 + 3 + StoneYard.HALF_D;                       // past the widest the pit will open, on that side
        BlockPos c = q.centre().relative(toTown, off);
        // A step the other way along if the yard's middle stands on a road out.
        int y = surface(level, c.getX(), c.getZ());
        if (y == Integer.MIN_VALUE) y = q.centre().getY();
        return new StoneYard.Site(new BlockPos(c.getX(), y + 1, c.getZ()), toTown);
    }

    // ------------------------------------------------------------------ the cutting

    /** Where each town's look for the next cut got to, in the pit's order (course by course, row by row). */
    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();

    /**
     * The next block to cut, top down: the outcrop over the rim, then each terrace's course; null when the pit is cut. The
     * look goes on from where it last found one, and round once more from the top before the pit is called cut (a block
     * held back for a terrace beside it comes free when that is cut).
     */
    @Nullable
    static BlockPos nextCut(ServerLevel level, UUID town, Site s) {
        int h = s.half(), n = s.size();
        BlockPos c = s.centre();
        int rim = c.getY();
        int deepest = Math.min(DEEPEST, h);
        int top = rim + 6, layers = top - (rim - deepest + 1) + 1, all = layers * n * n;
        int from = Math.floorMod(CURSOR.getOrDefault(town, 0), Math.max(1, all));
        Map<Long, Long> skipped = UNREACHABLE.getOrDefault(town, Map.of());
        for (int k = 0; k < all; k++) {
            int i = (from + k) % all;
            int y = top - i / (n * n), rem = i % (n * n);
            int dx = rem / n - h, dz = rem % n - h;
            int depth = s.depth(dx, dz);
            if (depth <= 0 || y < rim - depth + 1) continue;
            BlockPos p = new BlockPos(c.getX() + dx, y, c.getZ() + dz);
            if (skipped.containsKey(p.asLong())) continue;
            if (cuttable(level, p, y == rim - depth + 1) && terraced(level, p, rim)) {
                CURSOR.put(town, i);
                return p;
            }
        }
        CURSOR.put(town, 0);
        return null;
    }

    /** May this block be cut: the world's own ground or what grows on it, nothing wet beside it, the floor under the last
     *  course sound (no cave opened)? */
    static boolean cuttable(ServerLevel level, BlockPos p, boolean floor) {
        BlockState st = level.getBlockState(p);
        if (st.isAir() || st.is(Blocks.BEDROCK) || st.hasBlockEntity()) return false;
        if (!st.getFluidState().isEmpty()) return false;
        if (!CastleWall.natural(st) && !CastleWall.wild(level, p, st)) return false;
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN && !floor) continue;
            if (!level.getFluidState(p.relative(d)).isEmpty()) return false;
        }
        if (floor) {
            BlockState under = level.getBlockState(p.below());
            if (under.isAir() || !under.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    /**
     * Would cutting this block leave every step into its cell a block at most: the block over it already open (cut top
     * down), and no column beside it standing more than a block over the floor it leaves? (A cell left standing, by water
     * or by somebody's work, keeps the cells round it a step higher, and so on in: terraces, never a drop.)
     */
    static boolean terraced(ServerLevel level, BlockPos p, int rim) {
        BlockState over = level.getBlockState(p.above());
        if (!over.isAir() && !CastleWall.wild(level, p.above(), over)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            if (top(level, n.getX(), n.getZ(), rim + 8, rim - DEEPEST - 2) > p.getY()) return false;
        }
        return true;
    }

    /** The highest block of ground (not air, not a plant) in a column, between these heights. */
    static int top(ServerLevel level, int x, int z, int from, int to) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, from, z);
        for (int y = from; y >= to; y--) {
            BlockState st = level.getBlockState(m.setY(y));
            if (st.isAir() || CastleWall.wild(level, m, st) && !st.is(BlockTags.LOGS)) continue;
            if (st.is(BlockTags.FENCES) || st.is(BlockTags.FENCE_GATES) || st.is(Blocks.LANTERN) || st.is(Blocks.TORCH)) continue;
            return y;
        }
        return to;
    }

    /** Cut one block: what it gives (the stone by what a pick gets of it; ore, earth and the rest). */
    static List<ItemStack> cut(ServerLevel level, BlockPos p, @Nullable VillageFolkEntity by) {
        BlockState st = level.getBlockState(p);
        ItemStack pick = by != null && by.getMainHandItem().getItem() instanceof PickaxeItem ? by.getMainHandItem() : new ItemStack(Items.STONE_PICKAXE);
        List<ItemStack> drops = Block.getDrops(st, level, p, null, by, pick);
        level.levelEvent(2001, p, Block.getId(st));                       // the dust and the sound of it
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        // Snow or a plant over the course goes with it.
        BlockState over = level.getBlockState(p.above());
        if (!over.isAir() && CastleWall.wild(level, p.above(), over) && !over.is(BlockTags.LOGS)) level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
        return drops;
    }

    /** Is it earth (spoil, left on the heap), not stone? */
    static boolean spoil(ItemStack s) {
        Item it = s.getItem();
        return it == Items.DIRT || it == Items.GRAVEL || it == Items.SAND || it == Items.RED_SAND
            || it == Items.SNOWBALL || it == Items.FLINT || it == Items.WHEAT_SEEDS || it == Items.SHORT_GRASS || it == Items.COARSE_DIRT
            || it == Items.ROOTED_DIRT || it == Items.MUD;
    }

    // ------------------------------------------------------------------ the fence and the lights

    /** The fence's posts round the rim: a ring a block outside the pit, the gate toward the town, a lantern every six. */
    private static final Map<String, List<BlockPos[]>> FENCES = new ConcurrentHashMap<>();

    static List<BlockPos[]> fence(ServerLevel level, Villages.Village v, Site s) {
        String key = v.id() + "/" + s.centre().asLong() + "/" + s.size();
        List<BlockPos[]> kept = FENCES.get(key);
        if (kept != null) return kept;
        List<BlockPos[]> made = fenceNow(level, v, s);
        if (FENCES.size() > 64) FENCES.clear();
        FENCES.put(key, made);
        return made;
    }

    private static List<BlockPos[]> fenceNow(ServerLevel level, Villages.Village v, Site s) {
        List<BlockPos[]> out = new ArrayList<>();
        int r = s.half() + 1;
        BlockPos c = s.centre();
        Direction toTown = toTown(v, s);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = c.getX() + dx, z = c.getZ() + dz;
                int y = surface(level, x, z);
                if (y == Integer.MIN_VALUE) continue;
                BlockPos post = new BlockPos(x, y + 1, z);
                boolean gate = dx == toTown.getStepX() * r && dz == 0 && toTown.getAxis() == Direction.Axis.X
                    || dz == toTown.getStepZ() * r && dx == 0 && toTown.getAxis() == Direction.Axis.Z;
                boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                boolean light = corner || (Math.floorMod(dx + dz, 6) == 0 && !gate);
                out.add(new BlockPos[]{ post, gate ? post : null, light && !gate ? post.above() : null });
            }
        }
        return out;
    }

    static Direction toTown(Villages.Village v, Site s) {
        int dx = v.centre().getX() - s.centre().getX(), dz = v.centre().getZ() - s.centre().getZ();
        return Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /** The next post or lantern the fence wants, or null when it is up. */
    @Nullable
    static BlockPos[] fenceWanted(ServerLevel level, Villages.Village v, Site s) {
        return fenceWanted(level, v, s, null);
    }

    /** As above, the nearest to this spot first (a quarryman goes round the rim, not back and forth across it). */
    @Nullable
    static BlockPos[] fenceWanted(ServerLevel level, Villages.Village v, Site s, @Nullable BlockPos near) {
        BlockPos[] best = null;
        double bestD = Double.MAX_VALUE;
        Map<Long, Long> skipped = UNREACHABLE.getOrDefault(v.id(), Map.of());
        for (BlockPos[] f : fence(level, v, s)) {
            if (!wantsWork(level, f)) continue;
            if (near != null && skipped.containsKey(f[0].asLong())) continue;
            if (near == null) return f;
            double d = f[0].distSqr(near);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    private static boolean wantsWork(ServerLevel level, BlockPos[] f) {
        {
            BlockState st = level.getBlockState(f[0]);
            boolean postUp = st.is(BlockTags.FENCES) || st.is(BlockTags.FENCE_GATES);
            if (!postUp && (st.isAir() || st.canBeReplaced())) return true;
            if (postUp && f[2] != null) {
                BlockState l = level.getBlockState(f[2]);
                if (!(l.is(Blocks.LANTERN) || l.is(Blocks.TORCH)) && (l.isAir() || l.canBeReplaced())) return true;
            }
        }
        return false;
    }

    /** Put up a post (or its lantern) out of the stores, unless {@code free}. */
    static boolean putFence(ServerLevel level, Villages.Village v, Site s, BlockPos[] f, boolean free) {
        BlockState st = level.getBlockState(f[0]);
        boolean postUp = st.is(BlockTags.FENCES) || st.is(BlockTags.FENCE_GATES);
        if (!postUp) {
            // Never a post under somebody's feet (they would be left standing on top of it).
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(f[0]).deflate(0.3, 0.0, 0.3)).isEmpty()) return false;
            boolean gate = f[1] != null;
            if (!free && !TownWork.take(level, v, x -> x.is(gate ? ItemTags.FENCE_GATES : ItemTags.WOODEN_FENCES), 1)
                    && !TownWork.take(level, v, x -> x.is(ItemTags.PLANKS), gate ? 4 : 2)) return false;
            BlockState put = gate ? Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, toTown(v, s).getClockWise())
                : Blocks.OAK_FENCE.defaultBlockState();
            level.setBlock(f[0], put, 3);
            return true;
        }
        if (f[2] == null) return false;
        boolean lantern = free || TownWork.take(level, v, x -> x.is(Items.LANTERN), 1);
        if (!lantern && !TownWork.take(level, v, x -> x.is(Items.TORCH), 1)) return false;
        level.setBlock(f[2], lantern ? Blocks.LANTERN.defaultBlockState() : Blocks.TORCH.defaultBlockState(), 3);
        return true;
    }

    /** The fence and lanterns of a pit taken down into the stores (opened wider, or closed). */
    static void takeDownFence(ServerLevel level, Villages.Village v, Site s) {
        for (BlockPos[] f : fence(level, v, s)) {
            for (BlockPos p : new BlockPos[]{ f[2], f[0] }) {
                if (p == null) continue;
                BlockState st = level.getBlockState(p);
                Item back = st.is(Blocks.LANTERN) ? Items.LANTERN : st.is(Blocks.TORCH) ? Items.TORCH : st.is(BlockTags.FENCE_GATES) ? Items.OAK_FENCE_GATE
                    : st.is(BlockTags.FENCES) ? Items.OAK_FENCE : null;
                if (back == null) continue;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                Crafts.store(level, v, new ItemStack(back));
            }
        }
    }

    // ------------------------------------------------------------------ opened wider, closed, moved

    /** The pit cut out: opened two wider each way if the land allows; else closed and a new site chosen further out. */
    static void workedOut(ServerLevel level, Villages.Village v, Site s) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        StoneYard.Site yard = StoneYard.site(id);
        int wider = s.size() + 2;
        if (wider <= WIDEST && clearOfTown(id, v.centre(), s.centre().getX(), s.centre().getZ(), wider / 2, yard)
                && !waterNear(level, s.centre().getX(), s.centre().getY(), s.centre().getZ(), wider / 2 + 1, DEEPEST + 1)) {
            takeDownFence(level, v, s);
            Site next = new Site(s.centre(), wider, s.outcrop());
            save(id, next);
            Villages.tell(id, day, "the quarry was opened wider, " + wider + " blocks across");
            return;
        }
        close(level, v, s, "worked out as far as the land allows");
    }

    static void close(ServerLevel level, Villages.Village v, Site s, String why) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        takeDownFence(level, v, s);
        Ledger.forget(id, SITE);
        Ledger.note(id, "quarry.old", Ledger.note(id, "quarry.old") == null ? s.centre().toShortString() : Ledger.note(id, "quarry.old") + ";" + s.centre().toShortString());
        Villages.tell(id, day, "the quarry was closed (" + why + "); its terraces are left as safe steps, and a new one will be opened further out");
    }

    // ------------------------------------------------------------------ the town's round (every second)

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** Blocks a quarryman could not get to lately, by town: when given up. */
    private static final Map<UUID, Map<Long, Long>> UNREACHABLE = new ConcurrentHashMap<>();

    /** Does the town want a quarry now? */
    static boolean wanted(UUID town) {
        if (standDown) return false;
        if (readyForTests) return true;
        if (Villages.headcount(town) < FOLK) return false;
        return Villages.hasBuilt(town, "storage") || Villages.hasBuilt(town, "storehouse");
    }

    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (standDown || !level.isLoaded(v.centre())) return;
        long now = level.getGameTime();
        Long last = LOOKED.get(id);
        if (last != null && now - last < 200L && now >= last) return;
        LOOKED.put(id, now);
        Site s = site(id);
        if (s == null) {
            if (!wanted(id)) return;
            Site chosen = choose(level, v);
            if (chosen == null) return;
            open(level, v, chosen);
            return;
        }
        // The town grown out to it: closed, and a new one further out.
        int dx = s.centre().getX() - v.centre().getX(), dz = s.centre().getZ() - v.centre().getZ();
        if (!readyForTests && Math.max(Math.abs(dx), Math.abs(dz)) - s.half() - 1 <= growthReach(id)) {
            close(level, v, s, "the town has grown out to it");
            return;
        }
        UNREACHABLE.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).values().removeIf(t -> now - t > 2400L || now < t);
        men(level, v);
        gate(level, v, s);
    }

    /** The quarry's gate open by day for its hands and their loads, shut at night (and the yard's with it). */
    static void gate(ServerLevel level, Villages.Village v, Site s) {
        long t = Math.floorMod(level.getDayTime(), 24000L);
        boolean open = t >= 900L && t < 12000L && !Raids.underAlarm(v.id());
        List<BlockPos> gates = new ArrayList<>();
        for (BlockPos[] f : fence(level, v, s)) if (f[1] != null) gates.add(f[0]);
        StoneYard.Site y = StoneYard.site(v.id());
        if (y != null) gates.add(StoneYard.ground(StoneYard.at(y, 0, -StoneYard.HALF_D), y));
        for (BlockPos g : gates) {
            if (!level.isLoaded(g)) continue;
            BlockState st = level.getBlockState(g);
            if (st.getBlock() instanceof FenceGateBlock && st.getValue(FenceGateBlock.OPEN) != open) {
                level.setBlock(g, st.setValue(FenceGateBlock.OPEN, open), 3);
            }
        }
    }

    static void open(ServerLevel level, Villages.Village v, Site s) {
        UUID id = v.id();
        save(id, s);
        Ledger.note(id, CUT, Ledger.note(id, CUT) == null ? "0" : Ledger.note(id, CUT));
        if (StoneYard.site(id) == null) {
            StoneYard.Site y = yardFor(level, v, s);
            StoneYard.place(id, y.centre(), y.gate());
        }
        long day = level.getDayTime() / 24000L;
        int dist = (int) Math.round(Math.sqrt(s.centre().distSqr(v.centre())));
        Villages.tell(id, day, "a quarry was opened " + dist + " blocks out, " + (s.outcrop() ? "at a rock outcrop" : "dug down into the ground")
            + ", with a stone yard beside it");
        FeatHints.hint(level, id, v.centre(), "quarry", "New: " + Villages.name(id) + " has opened a quarry and a stone yard. The board's Stone page shows the stock and the flow.");
    }

    // ------------------------------------------------------------------ the quarrymen

    /** How many hands the quarry wants. */
    static int handsWanted(UUID town) {
        return Math.max(1, Math.min(3, 1 + Villages.headcount(town) / 20));
    }

    /** The quarrymen, chosen again when one has gone: miners first (unless the age waits on the mine), else a spare hand. */
    static List<VillageFolkEntity> men(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<VillageFolkEntity> out = new ArrayList<>();
        String was = Ledger.note(id, MEN);
        if (was != null && !was.isEmpty()) {
            for (String u : was.split(",")) {
                try {
                    if (level.getEntity(UUID.fromString(u)) instanceof VillageFolkEntity f && f.isAlive() && id.equals(f.ownerId()) && !f.isBaby()) out.add(f);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        int want = handsWanted(id);
        if (out.size() < want) {
            List<VillageFolkEntity> pool = new ArrayList<>();
            boolean mineKept = MineHands.kept(level, id);
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || out.contains(f) || f.isHired()) continue;
                AssistantEntity.StationTask t = f.stationTask();
                if (t == AssistantEntity.StationTask.MINE && !mineKept) pool.add(0, f);
                else if (t == AssistantEntity.StationTask.NONE || t == AssistantEntity.StationTask.HAUL || f.workedOut() && !t.isCraft()
                    && t != AssistantEntity.StationTask.GUARD && t != AssistantEntity.StationTask.STORE) pool.add(f);
            }
            for (VillageFolkEntity f : pool) {
                if (out.size() >= want) break;
                out.add(f);
                f.brain("taken on at the quarry");
                Villages.tell(id, level.getDayTime() / 24000L, f.displayNameCap() + " went to work at the quarry");
            }
        }
        List<String> ids = new ArrayList<>();
        for (VillageFolkEntity f : out) ids.add(f.getStringUUID());
        Ledger.note(id, MEN, String.join(",", ids));
        return out;
    }

    static boolean isMan(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return false;
        String was = Ledger.note(id, MEN);
        return was != null && was.contains(f.getStringUUID());
    }

    enum Job { FENCE, CUT, TO_YARD, TO_STORES }

    private static final class Man {
        Job job = Job.CUT;
        @Nullable BlockPos at;
        @Nullable BlockPos[] fence;
        Map<Item, Integer> load = new LinkedHashMap<>();
        List<ItemStack> extra = new ArrayList<>();
        @Nullable ItemStack cart;
        long sent, lastCut, held;
        double bestDist = Double.MAX_VALUE;
        String doing = "";
    }

    private static final Map<UUID, Man> MEN_STATE = new ConcurrentHashMap<>();

    /** Is this folk at the quarry's work just now (between hold's looks)? */
    public static boolean busy(VillageFolkEntity f) {
        Man m = MEN_STATE.get(f.getUUID());
        return m != null && f.level().getGameTime() - m.held <= 8;
    }

    /** What it is doing there, for its card and talk; null when it is not. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        Man m = MEN_STATE.get(f.getUUID());
        return m == null || !busy(f) ? null : m.doing;
    }

    /** Is a quarryman at the yard just now (the mason works while one is)? */
    static boolean atYard(ServerLevel level, Villages.Village v) {
        StoneYard.Site y = StoneYard.site(v.id());
        if (y == null) return false;
        for (VillageFolkEntity f : men(level, v)) if (f.blockPosition().closerThan(y.centre(), 8)) return true;
        return false;
    }

    /**
     * From the folk's tick: a quarryman's day at the quarry. True while it holds the folk (by day, the quarry open, no bell,
     * no town's work calling it).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (standDown || f.isBaby() || f.isSleeping() || f.ownerId() == null || f.isHired()) return release(f);
        if (!isMan(f)) return release(f);
        UUID id = f.ownerId();
        long t = Math.floorMod(level.getDayTime(), 24000L);
        if (t < 1000L || t > 11500L || Raids.underAlarm(id) || TownJobs.busy(f) || Weather.stormy(level)) return release(f);
        if (t >= 5900L && t < 6900L) return release(f);                          // the noon bell: the midday meal with everybody
        if (RestDay.now(id, level.getDayTime()) != null) return release(f);      // the day of rest
        Villages.Village v = Villages.get(id);
        Site s = v == null ? null : site(id);
        StoneYard.Site yard = StoneYard.site(id);
        if (v == null || s == null || yard == null || !level.isLoaded(s.centre())) return release(f);
        Man m = MEN_STATE.computeIfAbsent(f.getUUID(), k -> new Man());
        long now = level.getGameTime();
        m.held = now;
        f.hobbyNow = m.doing;
        f.lastLeisureTick = f.tickCount;
        // The yard laid out first, the first morning: the quarry's own hands do it out of the stores.
        if (Ledger.note(id, "yard.laid") == null) {
            if (!walkTo(f, m, yard.centre(), 3.0, now, "laying out the stone yard")) return true;
            StoneYard.layOut(level, v, yard, false);
            FolkTalk.speak(f, "There: a yard for the stone. Now to fill it.");
            return true;
        }
        switch (m.job) {
            case FENCE, CUT -> {
                ensurePick(level, v, f);
                // The fence and its lanterns before the cutting.
                BlockPos[] fw = fenceWanted(level, v, s, f.blockPosition());
                if (fw != null) {
                    // Worked from the pit's side of the rim, a step in, never standing on the line itself.
                    Direction in = Direction.getNearest(s.centre().getX() - fw[0].getX(), 0, s.centre().getZ() - fw[0].getZ());
                    BlockPos stand = fw[0].relative(in, 2);
                    stand = new BlockPos(stand.getX(), top(level, stand.getX(), stand.getZ(), s.centre().getY() + 8, s.centre().getY() - DEEPEST - 2) + 1, stand.getZ());
                    if (f.blockPosition().equals(fw[0]) || !walkTo(f, m, stand, 2.0, now, "putting up the quarry's fence")) {
                        if (f.blockPosition().equals(fw[0])) Incidents.walk(f, stand, 0.8D);
                        return true;
                    }
                    f.getLookControl().setLookAt(fw[0].getX() + 0.5, fw[0].getY() + 0.5, fw[0].getZ() + 0.5);
                    if (now - m.lastCut >= CUT_EVERY / 2) {
                        m.lastCut = now;
                        f.swing(InteractionHand.MAIN_HAND);
                        if (!putFence(level, v, s, fw, false)) skip(id, fw[0], now);   // short of it, or somebody in the way: another post first
                    }
                    return true;
                }
                if (loadSize(m) >= LOAD) { m.job = Job.TO_YARD; m.bestDist = Double.MAX_VALUE; m.sent = now; return true; }
                BlockPos cutAt = m.at != null && cuttable(level, m.at, false) && m.job == Job.CUT ? m.at : nextCut(level, id, s);
                if (cutAt == null) {
                    if (loadSize(m) > 0) { m.job = Job.TO_YARD; m.sent = now; m.bestDist = Double.MAX_VALUE; return true; }
                    workedOut(level, v, s);
                    return true;
                }
                if (!cutAt.equals(m.at)) { m.at = cutAt; m.sent = now; m.bestDist = Double.MAX_VALUE; }
                m.job = Job.CUT;
                double dist = Math.sqrt(f.distanceToSqr(cutAt.getX() + 0.5, cutAt.getY() + 0.5, cutAt.getZ() + 0.5));
                if (dist > 4.2 || Math.abs(f.getY() - cutAt.getY()) > 3.5) {
                    if (!walkToward(f, m, cutAt.above(), now, "walking out to the quarry face")) {
                        skip(id, cutAt, now);
                        m.at = null;
                    }
                    return true;
                }
                f.getNavigation().stop();
                f.getLookControl().setLookAt(cutAt.getX() + 0.5, cutAt.getY() + 0.5, cutAt.getZ() + 0.5);
                m.doing = "cutting stone at the quarry";
                f.brain(m.doing);
                if (now - m.lastCut < cutEvery(f)) {
                    if ((now - m.lastCut) % 6 == 0) f.swing(InteractionHand.MAIN_HAND);
                    return true;
                }
                m.lastCut = now;
                f.swing(InteractionHand.MAIN_HAND);
                for (ItemStack d : cut(level, cutAt, f)) {
                    if (StoneYard.isYardStone(d.getItem())) m.load.merge(d.getItem(), d.getCount(), Integer::sum);
                    else if (!spoil(d)) m.extra.add(d);
                }
                Ledger.note(id, CUT, Long.toString(cutTotal(id) + 1));
                m.at = null;
                return true;
            }
            case TO_YARD -> {
                BlockPos spot = StoneYard.ground(StoneYard.at(yard, 0, -1), yard);
                if (!walkTo(f, m, spot, 2.5, now, "carting stone to the yard")) {
                    if (now - m.sent > TRY * 3) {
                        // Never got there: left at the quarry's pile, for the yard to fetch.
                        StoneYard.receive(level, id, m.load);
                        m.load.clear();
                        m.job = Job.CUT;
                    }
                    return true;
                }
                level.playSound(null, f.blockPosition(), SoundEvents.STONE_PLACE, SoundSource.NEUTRAL, 0.8F, 0.9F);
                f.swing(InteractionHand.MAIN_HAND);
                StoneYard.receive(level, id, m.load);
                m.load.clear();
                for (ItemStack e : m.extra) Crafts.store(level, v, e);
                m.extra.clear();
                ItemStack due = StoneYard.deliveryDue(level, v);
                if (due != null && StoneYard.load(id, due)) {
                    m.cart = due;
                    m.job = Job.TO_STORES;
                    m.sent = now;
                    m.bestDist = Double.MAX_VALUE;
                    FolkTalk.speak(f, "The stores are low on " + due.getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + " — I'll take a load in.");
                } else {
                    m.job = Job.CUT;
                }
                return true;
            }
            case TO_STORES -> {
                BlockPos stores = storesSpot(level, v);
                if (!walkTo(f, m, stores, 3.0, now, "carting stone to the town's stores")) {
                    if (now - m.sent > TRY * 4 && m.cart != null) {
                        StoneYard.deliver(level, v, m.cart);         // handed on to a courier on the way
                        m.cart = null;
                        m.job = Job.CUT;
                    }
                    return true;
                }
                if (m.cart != null) StoneYard.deliver(level, v, m.cart);
                m.cart = null;
                m.job = Job.CUT;
                return true;
            }
        }
        return true;
    }

    private static boolean release(VillageFolkEntity f) {
        Man m = MEN_STATE.get(f.getUUID());
        if (m != null && !m.load.isEmpty() && f.level() instanceof ServerLevel level && f.ownerId() != null) {
            StoneYard.receive(level, f.ownerId(), m.load);                // the day's last load stacked at the yard
            m.load.clear();
        }
        if (m != null && m.cart != null && f.level() instanceof ServerLevel level && f.ownerId() != null) {
            Villages.Village v = Villages.get(f.ownerId());
            if (v != null) StoneYard.deliver(level, v, m.cart);
            m.cart = null;
        }
        return false;
    }

    static long cutTotal(UUID town) {
        try {
            String s = Ledger.note(town, CUT);
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int loadSize(Man m) {
        int n = 0;
        for (int c : m.load.values()) n += c;
        return n;
    }

    private static int cutEvery(VillageFolkEntity f) {
        Item it = f.getMainHandItem().getItem();
        if (it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) return CUT_EVERY / 2;
        if (it == Items.IRON_PICKAXE) return CUT_EVERY * 3 / 4;
        return CUT_EVERY;
    }

    private static void skip(UUID town, BlockPos p, long now) {
        UNREACHABLE.computeIfAbsent(town, k -> new ConcurrentHashMap<>()).put(p.asLong(), now);
    }

    /** A pick in its hand: its own, or one of the stores'. */
    private static void ensurePick(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (f.getMainHandItem().getItem() instanceof PickaxeItem) return;
        for (ItemStack s : f.getInventoryItems()) {
            if (s.getItem() instanceof PickaxeItem) {
                ItemStack hand = f.getMainHandItem().copy();
                ItemStack pick = s.copyWithCount(1);
                if (f.removeMatching(x -> x.getItem() instanceof PickaxeItem, 1) == 1) {
                    f.setItemSlot(EquipmentSlot.MAINHAND, pick);
                    if (!hand.isEmpty()) { ItemStack left = f.insertItem(hand); if (!left.isEmpty()) Crafts.store(level, v, left); }
                }
                return;
            }
        }
        ItemStack p = Crafts.takeOne(level, v, s -> s.getItem() instanceof PickaxeItem);
        if (p.isEmpty()) return;
        ItemStack hand = f.getMainHandItem().copy();
        f.setItemSlot(EquipmentSlot.MAINHAND, p);
        if (!hand.isEmpty()) { ItemStack left = f.insertItem(hand); if (!left.isEmpty()) Crafts.store(level, v, left); }
    }

    /** Walk to within so far of a spot; true once there. */
    private static boolean walkTo(VillageFolkEntity f, Man m, BlockPos to, double within, long now, String doing) {
        m.doing = doing;
        double d = Math.sqrt(f.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5));
        if (d <= within) return true;
        Incidents.walk(f, to, 0.8D);
        f.brain(doing);
        return false;
    }

    /** Walk toward a spot; false when it has not got nearer in a while (the spot given up). */
    private static boolean walkToward(VillageFolkEntity f, Man m, BlockPos to, long now, String doing) {
        m.doing = doing;
        double d = f.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (d < m.bestDist - 1.0) { m.bestDist = d; m.sent = now; }
        if (now - m.sent > TRY) return false;
        Incidents.walk(f, to, 0.8D);
        f.brain(doing);
        return true;
    }

    private static BlockPos storesSpot(ServerLevel level, Villages.Village v) {
        List<BlockPos> chests = Villages.storeChests(level, v.id());
        if (!chests.isEmpty()) return chests.get(0);
        BlockPos sh = Villages.builtAt(v.id(), "storehouse");
        if (sh != null) return sh;
        BlockPos st = Villages.builtAt(v.id(), "storage");
        return st != null ? st : v.centre();
    }

    // ------------------------------------------------------------------ the board

    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag c = new CompoundTag();
        UUID id = v.id();
        Site s = site(id);
        c.putBoolean("has", s != null);
        c.putInt("growth", growthReach(id));
        c.putBoolean("wanted", wanted(id));
        c.putInt("folk", Villages.headcount(id));
        if (s != null) {
            c.putIntArray("at", new int[]{ s.centre().getX(), s.centre().getY(), s.centre().getZ() });
            c.putInt("size", s.size());
            c.putInt("deep", Math.min(DEEPEST, s.half()));
            c.putBoolean("outcrop", s.outcrop());
            c.putInt("dist", (int) Math.round(Math.sqrt(s.centre().distSqr(v.centre()))));
            c.putLong("cut", cutTotal(id));
            BlockPos[] fw = fenceWanted(level, v, s);
            c.putBoolean("fenced", fw == null);
            ListTag men = new ListTag();
            for (VillageFolkEntity f : men(level, v)) {
                CompoundTag m = new CompoundTag();
                m.putString("name", f.displayNameCap());
                m.putString("trade", JobMarket.noun(f.stationTask()));
                String d = doing(f);
                long t = Math.floorMod(level.getDayTime(), 24000L);
                m.putString("doing", d != null ? d : t < 1000L || t > 11500L ? "home for the night" : "at something else just now");
                men.add(m);
            }
            c.put("men", men);
        }
        String old = Ledger.note(id, "quarry.old");
        if (old != null) c.putString("old", old);
        return c;
    }

    // ------------------------------------------------------------------ the tests

    @Nullable
    public static Site chooseForTests(ServerLevel level, Villages.Village v) {
        return choose(level, v);
    }

    public static void openForTests(ServerLevel level, Villages.Village v, Site s) {
        open(level, v, s);
    }

    /** Tests: so many blocks cut now (no walking), into the yard; returns how many were. */
    public static int cutForTests(ServerLevel level, Villages.Village v, int n) {
        Site s = site(v.id());
        if (s == null) return 0;
        Map<Item, Integer> load = new HashMap<>();
        int done = 0;
        for (int i = 0; i < n; i++) {
            BlockPos p = nextCut(level, v.id(), s);
            if (p == null) {
                workedOut(level, v, s);
                s = site(v.id());
                if (s == null) break;
                continue;
            }
            for (ItemStack d : cut(level, p, null)) if (StoneYard.isYardStone(d.getItem())) load.merge(d.getItem(), d.getCount(), Integer::sum);
            done++;
        }
        StoneYard.receive(level, v.id(), load);
        Ledger.note(v.id(), CUT, Long.toString(cutTotal(v.id()) + done));
        return done;
    }

    public static void fenceForTests(ServerLevel level, Villages.Village v) {
        Site s = site(v.id());
        if (s == null) return;
        for (int i = 0; i < 400; i++) {
            BlockPos[] f = fenceWanted(level, v, s);
            if (f == null || !putFence(level, v, s, f, true)) break;
        }
    }

    public static List<VillageFolkEntity> menForTests(ServerLevel level, Villages.Village v) {
        return men(level, v);
    }

    @Nullable
    public static String doingForTests(VillageFolkEntity f) {
        Man m = MEN_STATE.get(f.getUUID());
        return m == null ? null : m.job + " " + m.doing;
    }
}
