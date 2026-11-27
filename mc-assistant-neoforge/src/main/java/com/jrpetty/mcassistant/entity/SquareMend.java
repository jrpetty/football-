package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [life28] A town's square made good once, after its founding: the columns of it the levelling held off (for the
 * founders' own camp, read as somebody's building: FoundingCamp; for anything else that has since gone) brought to the
 * square's level now nothing built stands on them, and the wall round it laid again on the level ground.
 *
 * <p>Towns founded before the camp was taken up while their ground was levelled (Ironmouth: a pit of some six hundred
 * columns in the square's north-west quarter, from y76 to y84 in a square at y86, and the wall round it standing on that
 * broken ground, its posts and towers starting at every height from 76 to 87) are mended by it on their first land
 * round: the square's level is what most of the square stands at; each of its columns off that level, with nothing
 * built on it, is cut or filled to it, as the founding would have; and each column of the wall whose courses stand on
 * ground off the level is lifted off, its ground made level, and its courses set back on it as they were, so the wall's
 * foot and its top run level all the way round. Done once a town (Ledger note "square.mended"), a few columns a round.
 * The square's level is kept (Ledger note "ground.level"), and the town's slow levelling by hand (Land.level) levels to
 * it: it used to level to the height of the ground the heart stood on before the square was made, and kept the pit.
 */
public final class SquareMend {

    private SquareMend() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The ledger's note of the square's level (its top block's y), and of the mend done. */
    public static final String LEVEL = "ground.level", DONE = "square.mended";
    /** How far in from the heart the square's columns are mended (inside the wall), and the wall's ring. */
    static final int INSIDE = TownPlan.PLAZA - 1, WALL = TownPlan.PLAZA;
    /** How far off the level a column may be and still be mended (a deep hole or a hill is left as it is). */
    static final int MOST = 14;
    /** How many buildings a town has before its square is made good (a camp of a few days is left to grow). */
    static final int GROWN = 8;
    /** Columns a round. */
    static final int PER_ROUND = 32;
    /** How high a wall column's courses may stand. */
    static final int COURSES = 10;

    private static final class Job {
        final int level;
        final Block surface;
        final List<long[]> inside = new ArrayList<>(), wall = new ArrayList<>();
        int cursor, mended, relaid, left;

        Job(int level, Block surface) {
            this.level = level;
            this.surface = surface;
        }
    }

    private static final Map<UUID, Job> JOBS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SWEEPS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        JOBS.clear();
    }

    /** The town's square's level (its top block), as noted; or the height of the ground at its heart's foot if none is. */
    public static int level(Villages.Village v) {
        String n = Ledger.note(v.id(), LEVEL);
        if (n != null && !n.isEmpty()) {
            try {
                return Integer.parseInt(n.trim());
            } catch (NumberFormatException ignored) { }
        }
        return v.centre().getY() - 1;
    }

    /** Its square levelled (the founding, or /village level): its level noted for the town's levelling by hand. */
    static void levelled(UUID village, int level) {
        Ledger.note(village, LEVEL, Integer.toString(level));
    }

    /** From the land's round for each town (Land): once, after its founding has settled, its square made good. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Ledger.note(id, DONE) != null && !Ledger.note(id, DONE).isEmpty()) {
            // Now and then, the loose things set down on its square since taken up into the stores.
            if (Ledger.note(id, DONE).startsWith("mended") && SWEEPS.merge(id, 1, Integer::sum) % 12 == 0
                    && Land.areaLoaded(level, v.centre(), WALL)) SquareDress.sweep(level, v, level(v));
            return;
        }
        // (A terraced town's square is level at its heart's terrace like any other: inside the wall it is mended too.)
        if (Founding.near(level, v.centre(), 64) || Founding.settling(level, v.centre(), 64)) return;
        Job job = JOBS.get(id);
        if (job == null) {
            if (!Land.areaLoaded(level, v.centre(), WALL + 2)) return;
            // A town grown up enough to have a square at all (its buildings round it), not a camp of a few days.
            if (Ledger.buildings(id).size() < GROWN && !Villages.hasBuilt(id, "fortify")) return;
            job = survey(level, v);
            if (job == null) return;
            JOBS.put(id, job);
        }
        work(level, v, job);
    }

    /** Tests: the whole mend now; what the ledger says of it. */
    public static String nowForTests(ServerLevel level, Villages.Village v) {
        Ledger.forget(v.id(), DONE);
        JOBS.remove(v.id());
        Job job = survey(level, v);
        if (job == null) return Ledger.note(v.id(), DONE);
        for (int i = 0; i < 1000 && Ledger.note(v.id(), DONE) == null; i++) work(level, v, job);
        return Ledger.note(v.id(), DONE);
    }

    /** The square read: its level, and what is off it. Null if it is not a levelled square at all (noted, never again). */
    @Nullable
    private static Job survey(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        Map<Integer, Integer> heights = new HashMap<>();
        Map<Block, Integer> tops = new HashMap<>();
        int n = 0;
        int[][] h = new int[2 * WALL + 1][2 * WALL + 1];
        for (int dx = -WALL; dx <= WALL; dx++) {
            for (int dz = -WALL; dz <= WALL; dz++) {
                int x = heart.getX() + dx, z = heart.getZ() + dz;
                h[dx + WALL][dz + WALL] = Math.max(Math.abs(dx), Math.abs(dz)) == WALL ? foot(level, x, z) : top(level, x, z);
                if (Math.max(Math.abs(dx), Math.abs(dz)) > INSIDE - 1) continue;
                int y = h[dx + WALL][dz + WALL];
                if (y == Integer.MIN_VALUE || built(level, v, new BlockPos(x, y + 1, z))) continue;
                n++;
                heights.merge(y, 1, Integer::sum);
            }
        }
        int best = Integer.MIN_VALUE, most = 0;
        for (Map.Entry<Integer, Integer> e : heights.entrySet()) if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
        // A levelled square: a third of it or more at one height (Ironmouth's: 224 of 529 at y86, the rest its pit).
        if (n < 150 || most * 3 < n) {
            Ledger.note(id, DONE, "not a levelled square (" + most + " of " + n + " at one height)");
            LOG.info("[MCA-FOUND] [life28] {}: its square is not a levelled one ({} of {} at one height: {}): left as it is", Villages.name(id),
                most, n, new java.util.TreeMap<>(heights));
            return null;
        }
        for (int dx = -INSIDE; dx <= INSIDE; dx++) {
            for (int dz = -INSIDE; dz <= INSIDE; dz++) {
                int y = h[dx + WALL][dz + WALL];
                if (y != best) continue;
                BlockState t = level.getBlockState(new BlockPos(heart.getX() + dx, y, heart.getZ() + dz));
                if (Terraform.earth(t)) tops.merge(t.getBlock(), 1, Integer::sum);
            }
        }
        Block surface = Blocks.GRASS_BLOCK;
        int sm = 0;
        for (Map.Entry<Block, Integer> e : tops.entrySet()) if (e.getValue() > sm) { sm = e.getValue(); surface = e.getKey(); }
        Job job = new Job(best, surface);
        for (int dx = -WALL; dx <= WALL; dx++) {
            for (int dz = -WALL; dz <= WALL; dz++) {
                int y = h[dx + WALL][dz + WALL];
                // (A block off may be the square's paving, or the wall stood on it: only what is clearly off the level is mended.)
                if (y == Integer.MIN_VALUE || Math.abs(y - best) < 2 || Math.abs(y - best) > MOST) continue;
                long[] c = { heart.getX() + dx, heart.getZ() + dz, y };
                if (Math.max(Math.abs(dx), Math.abs(dz)) == WALL) job.wall.add(c);
                else job.inside.add(c);
            }
        }
        levelled(id, best);
        LOG.info("[MCA-FOUND] [life28] {}: its square stands at y={} ({} of {} columns); {} columns inside the wall and {} of the wall"
            + " off it, to be made good", Villages.name(id), best, most, n, job.inside.size(), job.wall.size());
        return job;
    }

    private static void work(ServerLevel level, Villages.Village v, Job job) {
        int done = 0;
        int all = job.inside.size() + job.wall.size();
        while (job.cursor < all && done < PER_ROUND) {
            boolean wall = job.cursor >= job.inside.size();
            long[] c = wall ? job.wall.get(job.cursor - job.inside.size()) : job.inside.get(job.cursor);
            job.cursor++;
            done++;
            int x = (int) c[0], z = (int) c[1];
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) { job.left++; continue; }
            if (wall) {
                if (relay(level, v, chunk, x, z, job)) job.relaid++;
                else job.left++;
            } else {
                int y = top(level, x, z);
                if (y == Integer.MIN_VALUE || Math.abs(y - job.level) < 2) { job.left++; continue; }
                // A torch stood in the hole to light it: taken up, and stood again on the level ground.
                BlockPos over = new BlockPos(x, y + 1, z);
                boolean lit = level.getBlockState(over).is(Blocks.TORCH);
                if (lit) level.setBlock(over, Blocks.AIR.defaultBlockState(), 2 | 16);
                if (built(level, v, over)) {
                    if (lit) level.setBlock(over, Blocks.TORCH.defaultBlockState(), 2 | 16);
                    job.left++;
                    continue;
                }
                // The paving at the bottom of a hole is buried under the fill (a hump of it is left: it is somebody's work).
                BlockPos at = new BlockPos(x, y, z);
                if (y < job.level && !Terraform.natural(level.getBlockState(at)) && SiteWork.townPaving(level, at, level.getBlockState(at))) {
                    level.setBlock(at, Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                if (Terraform.shape(level, chunk, x, z, y, job.level, true, job.surface) > 0) {
                    job.mended++;
                    BlockPos up = new BlockPos(x, job.level + 1, z);
                    if (lit && level.getBlockState(up).isAir()) level.setBlock(up, Blocks.TORCH.defaultBlockState(), 3);
                } else {
                    if (lit && level.getBlockState(over).isAir()) level.setBlock(over, Blocks.TORCH.defaultBlockState(), 3);
                    job.left++;
                }
            }
        }
        if (job.cursor < all) return;
        JOBS.remove(v.id());
        SquareDress.dress(level, v, job.level);                                // [life28] the floor paved, the strays up, the wall whole
        Ledger.note(v.id(), DONE, "mended " + job.mended + ", wall relaid " + job.relaid + ", left " + job.left);
        LOG.info("[MCA-FOUND] [life28] {}: its square made good at y={}: {} columns levelled, {} of the wall laid again on the level,"
            + " {} left as they were (something built on them)", Villages.name(v.id()), job.level, job.mended, job.relaid, job.left);
    }

    /** One column of the square's wall: its courses lifted off, its ground made level, and the courses set back on it. */
    private static boolean relay(ServerLevel level, Villages.Village v, LevelChunk chunk, int x, int z, Job job) {
        int f = foot(level, x, z);
        if (f == Integer.MIN_VALUE || Math.abs(f - job.level) < 2) return false;
        // What stands on it: the wall's own courses (and a lantern, a slab, the arch over a gate), as they are.
        List<BlockState> states = new ArrayList<>();
        List<CompoundTag> tags = new ArrayList<>();
        boolean any = false;
        for (int dy = 1; dy <= COURSES; dy++) {
            BlockPos p = new BlockPos(x, f + dy, z);
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !SiteWork.wallStuff(s) && !Terraform.natural(s) && !s.is(BlockTags.SLABS) && !s.is(BlockTags.BANNERS)) return false;
            if (!s.isAir() && !Terraform.natural(s)) {
                Ledger.Building b = TownBlocks.buildingAt(level, v, p);
                if (b != null && !b.structure().equals("fortify")) return false;       // somebody's building against it: left
                any = true;
            }
            BlockEntity be = level.getBlockEntity(p);
            states.add(Terraform.natural(s) ? Blocks.AIR.defaultBlockState() : s);
            tags.add(be == null ? null : be.saveWithFullMetadata(level.registryAccess()));
        }
        for (int dy = COURSES; dy >= 1; dy--) {
            BlockPos p = new BlockPos(x, f + dy, z);
            if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        if (Terraform.shape(level, chunk, x, z, f, job.level, true, job.surface) < 0) {
            // The ground would not take it: the courses back where they stood.
            put(level, x, f, z, states, tags);
            return false;
        }
        if (any) put(level, x, job.level, z, states, tags);
        return true;
    }

    private static void put(ServerLevel level, int x, int ground, int z, List<BlockState> states, List<CompoundTag> tags) {
        for (int i = 0; i < states.size(); i++) {
            BlockState s = states.get(i);
            if (s.isAir()) continue;
            BlockPos p = new BlockPos(x, ground + 1 + i, z);
            level.setBlock(p, s, 2 | 16);
            CompoundTag t = tags.get(i);
            BlockEntity be = level.getBlockEntity(p);
            if (t != null && be != null) be.loadWithComponents(t, level.registryAccess());
        }
    }

    /** The top of the ground in this column (earth, a trodden path, the town's paving), under what grows; MIN_VALUE if unloaded. */
    static int top(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return Integer.MIN_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        for (int i = 0; i < 24; i++, y--) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (Terraform.earth(s) || s.is(Blocks.DIRT_PATH) || SiteWork.townPaving(level, m, s) && !SiteWork.wallStuff(s)) return y;
            if (s.isAir() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(Blocks.TORCH)) continue;
            return y;                                                     // something else: its top
        }
        return y;
    }

    /** The ground a column of the wall stands on: down through the wall's courses to earth, a path or the paving. */
    static int foot(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return Integer.MIN_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        for (int i = 0; i < 24; i++, y--) {
            BlockState s = chunk.getBlockState(m.set(x, y, z));
            if (Terraform.earth(s) || s.is(Blocks.DIRT_PATH)) return y;
            if (s.isAir() || s.canBeReplaced() || SiteWork.wallStuff(s) || s.is(BlockTags.SLABS) || s.is(BlockTags.BANNERS)
                || SiteWork.townPaving(level, m, s)) continue;
            return Integer.MIN_VALUE;                                     // somebody's building: not the wall's
        }
        return Integer.MIN_VALUE;
    }

    /** Something built on this spot (a building's cell, a block entity, anything not natural over the ground)? */
    private static boolean built(ServerLevel level, Villages.Village v, BlockPos over) {
        if (TownBlocks.buildingAt(level, v, over) != null) return true;
        BlockState s = level.getBlockState(over);
        return level.getBlockEntity(over) != null || !Terraform.natural(s) && !s.is(Blocks.TORCH);
    }
}
