package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.SiteScore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * [found23] Where to found a town, near where the player stands: the ground read every four blocks round about (only
 * what is loaded: nothing is generated for it), every spot on a grid scored for a town's heart (village/SiteScore:
 * flat, dry, by water and wood, solid underfoot, above the sea, out of other towns' way), and "here" with the best
 * three that beat it, spread apart. The ground read goes back too, for the founding screen's map.
 */
public final class SiteFinder {

    private SiteFinder() {}

    /** The ground read every so many blocks; candidates every so many. */
    public static final int STEP = 4, GRID = 24;
    /** How far round a spot its square and first ring reach (the town with no homes yet), and how far water and wood are looked for. */
    public static final int CORE = FoundingPlan.BARE, AROUND = 64;

    /** What the map shows of a column. */
    public static final byte UNKNOWN = 0, GRASS = 1, WATER = 2, TREE = 3, SAND = 4, STONE = 5, SNOW = 6, LAVA = 7;

    /** One spot offered: where, its score and reason, why it may not be founded on (empty if it may), and the way from the player. */
    public record Pick(BlockPos at, int score, String words, String no, boolean here, int distance, String bearing) {}

    /** A town on the map. */
    public record Mark(int x, int z, String name) {}

    /** What was found: the picks ("here" first), and the ground read, a column every STEP blocks from (x0, z0), size by size. */
    public record Result(List<Pick> picks, int x0, int z0, int size, byte[] kinds, byte[] heights, int midY, List<Mark> towns) {}

    /** Look round {@code centre}, {@code reach} blocks. */
    public static Result find(ServerLevel level, BlockPos centre, int reach) {
        return find(level, centre, reach, false);
    }

    /** As find; {@code board} for a spawner's waiting board, whose own founding is not in the way of its spot. */
    public static Result find(ServerLevel level, BlockPos centre, int reach, boolean board) {
        int span = reach + AROUND;
        int size = 2 * span / STEP + 1;
        int x0 = centre.getX() - span, z0 = centre.getZ() - span;
        byte[] kinds = new byte[size * size];
        int[] ground = new int[size * size];
        String[] logs = new String[size * size];
        LevelChunk last = null;
        long lastKey = Long.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int x = x0 + i * STEP, z = z0 + j * STEP;
                long key = (((long) (x >> 4)) << 32) ^ ((z >> 4) & 0xffffffffL);
                if (key != lastKey) {
                    last = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    lastKey = key;
                }
                if (last == null) continue;
                read(level, last, x, z, i * size + j, kinds, ground, logs);
            }
        }
        // the towns about, folk's and villagers'
        List<Mark> towns = new ArrayList<>();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            if (Math.abs(v.centre().getX() - centre.getX()) > span + 200 || Math.abs(v.centre().getZ() - centre.getZ()) > span + 200) continue;
            towns.add(new Mark(v.centre().getX(), v.centre().getZ(), Villages.name(v.id())));
        }
        VanillaVillages.inTheWayOfFounding(level, centre);                       // looks the villagers' villages up round here
        int seaLevel = level.getSeaLevel();

        List<Pick> all = new ArrayList<>();
        Pick here = judge(level, centre, centre, kinds, ground, logs, size, x0, z0, seaLevel, true, board);
        for (int dx = -reach; dx <= reach; dx += GRID) {
            for (int dz = -reach; dz <= reach; dz += GRID) {
                if (dx * dx + dz * dz > reach * reach || dx * dx + dz * dz < 32 * 32) continue;
                int x = centre.getX() + dx, z = centre.getZ() + dz;
                int i = (x - x0) / STEP, j = (z - z0) / STEP;
                if (i < 0 || j < 0 || i >= size || j >= size || kinds[i * size + j] == UNKNOWN) continue;
                BlockPos at = new BlockPos(x, ground[i * size + j], z);
                Pick p = judge(level, centre, at, kinds, ground, logs, size, x0, z0, seaLevel, false, false);
                if (p != null && p.no().isEmpty()) all.add(p);
            }
        }
        all.sort(Comparator.comparingInt(Pick::score).reversed());
        List<Pick> picks = new ArrayList<>();
        picks.add(here);
        for (Pick p : all) {
            if (picks.size() >= 4) break;
            if (here.no().isEmpty() && p.score() < here.score() + 5) break;    // only spots worth the walk
            boolean apart = true;
            for (Pick q : picks) {
                if (!q.here() && q.at().distSqr(p.at()) < 72 * 72) { apart = false; break; }
            }
            if (apart) picks.add(p);
        }
        byte[] heights = new byte[size * size];
        int midY = here.at().getY();
        for (int k = 0; k < heights.length; k++) heights[k] = (byte) Math.max(-127, Math.min(127, ground[k] - midY));
        return new Result(picks, x0, z0, size, kinds, heights, midY, towns);
    }

    /** One column read: its ground (under any trunk), what it is, and the wood if a tree stands there. */
    private static void read(ServerLevel level, LevelChunk chunk, int x, int z, int k, byte[] kinds, int[] ground, String[] logs) {
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        int sky = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top - 1, z);
        BlockState s = chunk.getBlockState(p);
        boolean tree = sky > top + 1;
        String log = null;
        for (int guard = 0; guard < 32 && p.getY() > level.getMinBuildHeight()
                && (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty()); guard++) {
            if (s.is(BlockTags.LOGS)) {
                tree = true;
                log = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            }
            p.move(0, -1, 0);
            s = chunk.getBlockState(p);
        }
        ground[k] = p.getY() + 1;
        logs[k] = log;
        byte kind;
        if (s.getFluidState().is(FluidTags.WATER) || s.is(BlockTags.ICE)) kind = WATER;
        else if (s.getFluidState().is(FluidTags.LAVA)) kind = LAVA;
        else if (tree) kind = TREE;
        else if (s.is(BlockTags.SAND) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(BlockTags.TERRACOTTA)) kind = SAND;
        else if (s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.SNOW) || s.is(Blocks.POWDER_SNOW)) kind = SNOW;
        else if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL)) kind = STONE;
        else kind = GRASS;
        kinds[k] = kind;
    }

    /** The verdict on one spot. */
    private static Pick judge(ServerLevel level, BlockPos player, BlockPos at, byte[] kinds, int[] ground, String[] logs, int size,
                              int x0, int z0, int seaLevel, boolean here, boolean board) {
        SiteScore.Ground g = new SiteScore.Ground();
        int ci = (at.getX() - x0) / STEP, cj = (at.getZ() - z0) / STEP;
        int core = CORE / STEP, around = (CORE + AROUND) / STEP;
        List<Integer> hs = new ArrayList<>();
        int known = 0, cols = 0;
        int nearestWater = Integer.MAX_VALUE, nearestTree = Integer.MAX_VALUE, waterK = -1, treeK = -1;
        for (int di = -around; di <= around; di++) {
            for (int dj = -around; dj <= around; dj++) {
                int i = ci + di, j = cj + dj;
                if (i < 0 || j < 0 || i >= size || j >= size) continue;
                int d2 = di * di + dj * dj;
                if (d2 > around * around) continue;
                int k = i * size + j;
                byte kind = kinds[k];
                int d = (int) Math.round(Math.sqrt(d2) * STEP);
                if (d2 <= core * core) {
                    cols++;
                    if (kind == UNKNOWN) continue;
                    known++;
                    if (kind == WATER || kind == LAVA) g.wetColumns++;
                    else hs.add(ground[k]);
                }
                if (kind == WATER && d < nearestWater) { nearestWater = d; waterK = k; }
                if (kind == TREE && d < nearestTree) { nearestTree = d; treeK = k; }
                if (kind == TREE) g.treeColumns++;
            }
        }
        g.columns = known;
        g.heights = hs.stream().mapToInt(Integer::intValue).toArray();
        int k0 = ci >= 0 && cj >= 0 && ci < size && cj < size ? ci * size + cj : -1;
        g.centreWet = k0 >= 0 && (kinds[k0] == WATER || kinds[k0] == LAVA);
        g.centreY = k0 >= 0 && kinds[k0] != UNKNOWN ? ground[k0] : at.getY();
        g.seaLevel = seaLevel;
        Holder<Biome> biome = level.getBiome(at);
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)) g.centreWet |= g.wetColumns * 2 > Math.max(1, known);
        g.land = SiteScore.landWord(biome.unwrapKey().map(r -> r.location().toString()).orElse("plains"));
        if (waterK >= 0) {
            g.waterAt = nearestWater;
            int wi = waterK / size, wj = waterK % size;
            Holder<Biome> wb = level.getBiome(new BlockPos(x0 + wi * STEP, seaLevel, z0 + wj * STEP));
            if (wb.is(BiomeTags.IS_RIVER)) g.water = "river";
            else if (wb.is(BiomeTags.IS_OCEAN) || wb.is(BiomeTags.IS_BEACH)) g.water = "sea";
            else g.water = wetAround(kinds, size, wi, wj) < 8 ? "pond" : "lake";
        }
        if (treeK >= 0) {
            g.woodAt = nearestTree;
            g.wood = woodNear(logs, size, treeK);
        }
        // hollows: nine columns under the square looked down five blocks
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                int x = at.getX() + a * (CORE / 2), z = at.getZ() + b * (CORE / 2);
                int i = (x - x0) / STEP, j = (z - z0) / STEP;
                if (i < 0 || j < 0 || i >= size || j >= size) continue;
                int k = i * size + j;
                if (kinds[k] == UNKNOWN || kinds[k] == WATER || kinds[k] == LAVA) continue;
                LevelChunk ch = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (ch == null) continue;
                g.hollowRead++;
                for (int dy = 2; dy <= 6 && ground[k] - dy > level.getMinBuildHeight(); dy++) {
                    BlockState s = ch.getBlockState(new BlockPos(x, ground[k] - dy, z));
                    if (s.isAir() || !s.getFluidState().isEmpty()) { g.hollowColumns++; break; }
                }
            }
        }
        // the towns: ours want a clear way of twice a village's reach between hearts; the villagers' their margin
        int room = Integer.MAX_VALUE;
        String town = "";
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int d = (int) Math.sqrt(v.centre().distSqr(new BlockPos(at.getX(), v.centre().getY(), at.getZ())));
            int spare = d - (Villages.VILLAGE_RANGE * 2 + 8);
            if (spare < room) { room = spare; town = Villages.name(v.id()); }
        }
        int theirs = VanillaVillages.roomFrom(level, at);
        if (theirs != Integer.MAX_VALUE) {
            int spare = theirs + VanillaVillages.MARGIN - VanillaVillages.CLEAR - 4;
            if (spare < room) { room = spare; town = "a village of villagers"; }
        }
        if (!board && Founding.near(level, at, Villages.VILLAGE_RANGE * 2)) { room = -1; town = "a town being founded"; }
        g.townAt = room;
        g.town = town;
        SiteScore.Verdict v = SiteScore.judge(g);
        String no = v.no();
        if (no.isEmpty() && known * 10 < cols * 8) no = "the ground there has not been seen yet";
        if (no.isEmpty() && level.dimensionType().hasCeiling()) no = "a town wants open sky over it";
        int dist = (int) Math.round(Math.sqrt(player.distSqr(new BlockPos(at.getX(), player.getY(), at.getZ()))));
        BlockPos spot = new BlockPos(at.getX(), g.centreY, at.getZ());
        return new Pick(spot, v.score(), v.words(), no, here, dist, here ? "here" : Founding.bearing(player, spot));
    }

    private static int wetAround(byte[] kinds, int size, int i0, int j0) {
        int n = 0;
        for (int i = i0 - 3; i <= i0 + 3; i++) {
            for (int j = j0 - 3; j <= j0 + 3; j++) {
                if (i >= 0 && j >= 0 && i < size && j < size && kinds[i * size + j] == WATER) n++;
            }
        }
        return n;
    }

    /** The wood most of the trunks round this tree are. */
    private static String woodNear(String[] logs, int size, int k0) {
        java.util.Map<String, Integer> n = new java.util.HashMap<>();
        int i0 = k0 / size, j0 = k0 % size;
        for (int i = i0 - 6; i <= i0 + 6; i++) {
            for (int j = j0 - 6; j <= j0 + 6; j++) {
                if (i < 0 || j < 0 || i >= size || j >= size) continue;
                String l = logs[i * size + j];
                if (l != null) n.merge(l, 1, Integer::sum);
            }
        }
        String best = null;
        int most = 0;
        for (var e : n.entrySet()) if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
        return best == null ? "" : SiteScore.woodWord(best);
    }

    /** The player's own towns: those they founded, still standing, and those being founded for them. */
    public static int townsOf(ServerLevel level, UUID player) {
        int n = 0;
        for (Villages.Village v : Villages.every()) if (player.equals(Advancements.founder(v.id()))) n++;
        return n + Founding.underWayFor(level.getServer(), player);
    }
}
