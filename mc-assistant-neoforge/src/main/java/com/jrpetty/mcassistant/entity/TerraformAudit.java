package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.FoundingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [terra10] The founding's ground looked over once the levelling is done: everything a player walking it
 * would call a fault, counted (TerraformGameTests, and /village groundcheck on a live server). heldOff: columns of the
 * square the plan itself kept off the level (the bank of kept water, the ground round something built).
 *
 * <p>The square is every column the plan levelled; the edge every column of the band it cut or built up.
 * Columns the plan left as they were on purpose (water it kept, ground round anything built, a village of
 * villagers' own) are not counted against it. Holes: air, water or lava within {@link #DEEP} of the top of a
 * worked column; a crust is a column with any. A pit is a column two or more lower than all four of its
 * neighbours. A sheer edge is a step of more than two between neighbouring columns where either was worked, and
 * steeper than the land had it there.
 * Floating leaves are a wild tree's leaves more than six from any log (they would rot away and drop their
 * saplings); a floating log is a run of a tree's logs standing on nothing; a stump a log or two with no
 * crown. Odd tops: the square's top anything but the town's soil, and a worked edge column topped in bare
 * earth, rock, ore or gravel. Junk: cobble in the top of a worked column. Water left flowing, lava, sand or
 * gravel over a hollow, and loose items lying about, anywhere in the ground worked.
 */
public final class TerraformAudit {

    private TerraformAudit() {}

    /** How deep under a worked column's top a hollow counts. */
    public static final int DEEP = 12;

    /** A founding's ground as planned, kept for the audit after the founding is done (not saved). */
    public record Done(BlockPos heart, int level, int radius, int outer, long seed, net.minecraft.world.level.block.Block surface,
                       FoundingPlan.Ground ground) {}

    private static final java.util.List<Done> DONE = new java.util.ArrayList<>();

    /** Kept when a founding finishes (the last few only). */
    static synchronized void remember(Done d) {
        DONE.removeIf(o -> o.heart.equals(d.heart));
        DONE.add(d);
        while (DONE.size() > 6) DONE.remove(0);
    }

    /** The founding finished nearest here (its heart within this range), or null. */
    public static synchronized Done near(BlockPos at, int range) {
        Done best = null;
        long bd = Long.MAX_VALUE;
        for (Done d : DONE) {
            long dx = d.heart.getX() - at.getX(), dz = d.heart.getZ() - at.getZ();
            long dd = dx * dx + dz * dz;
            if (dd <= (long) range * range && dd < bd) { bd = dd; best = d; }
        }
        return best;
    }

    /** Where the first of each fault was found by the last scan (for the log). */
    public static final Map<String, String> FIRST = new LinkedHashMap<>();

    private static void at(Map<String, Integer> c, String key, int x, int y, int z, BlockState s) {
        c.merge(key, 1, Integer::sum);
        FIRST.putIfAbsent(key, x + "," + y + "," + z + (s == null ? "" : " " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath()));
    }

    /** The faults counted, by name, in a fixed order. */
    public static Map<String, Integer> scan(ServerLevel level, Done d) {
        FIRST.clear();
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String k : new String[]{ "square", "heldOff", "offLevel", "holes", "crust", "pits", "oddTop", "oddTopDirt", "oddTopRock",
                "oddTopOre", "oddTopGravel", "growthOnSquare", "floatLeaves", "floatLogs", "stumps", "floatVines", "sheer",
                "steps2", "rawEdge", "flowing", "lava", "junk", "falling", "items", "terraced", "walls", "steps", "pillars", "streetPillars", "rawFaces" }) c.put(k, 0);
        FoundingPlan.Ground g = d.ground;
        int hx = d.heart.getX(), hz = d.heart.getZ();
        int side = g.side;
        int[] top = new int[side * side];
        boolean[] worked = new boolean[side * side], sq = new boolean[side * side], counted = new boolean[side * side];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int i = 0; i < side * side; i++) {
            int dx = g.dx(i), dz = g.dz(i);
            if (FoundingPlan.reach(dx, dz) > g.outer) { top[i] = Integer.MIN_VALUE; continue; }
            int x = hx + dx, z = hz + dz;
            top[i] = Terraform.groundY(level, x, z);
            // [city26] A terrace's wall is the ground's top there (the earth's top reads through what is set on it).
            if (g.terrace != null && g.target != null
                    && top[i] != Integer.MIN_VALUE && top[i] < g.target[i] && (TerraceWalls.isWall(level.getBlockState(m.set(x, g.target[i], z)))
                    || TerraceWalls.isStep(level.getBlockState(m.set(x, g.target[i], z))))) top[i] = g.target[i];
            byte k = g.kind[i];
            boolean ours = k == FoundingPlan.LAND || k == FoundingPlan.FILL;
            boolean square = FoundingPlan.levelled(dx, dz, d.radius, d.seed);
            // [city26] A terraced column is level at its terrace's height, as the square is at the heart's.
            boolean terr = g.terrace != null && g.terrace[i] != com.jrpetty.mcassistant.village.Terraces.NONE && g.target != null
                && g.target[i] == g.terrace[i];
            sq[i] = square && ours && g.target != null && (g.target[i] == d.level || terr);
            if (square && ours && terr && g.target[i] != d.level) c.merge("terraced", 1, Integer::sum);
            if (square && ours && g.target != null && g.target[i] != d.level && !terr) c.merge("heldOff", 1, Integer::sum);   // the plan kept it off the level
            worked[i] = ours && g.target != null && (sq[i] || g.target[i] != g.ground[i]);
            counted[i] = ours;
            if (top[i] != Integer.MIN_VALUE) {
                lowest = Math.min(lowest, top[i]);
                LevelChunk ch = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (ch != null) highest = Math.max(highest, ch.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
            }
        }
        if (lowest == Integer.MAX_VALUE) return c;
        // ---- each worked column: its level, its top, what is under it
        for (int i = 0; i < side * side; i++) {
            if (!worked[i] || top[i] == Integer.MIN_VALUE) continue;
            int x = hx + g.dx(i), z = hz + g.dz(i), y = top[i];
            BlockState over = level.getBlockState(m.set(x, y + 1, z));
            if (!over.isAir() && !Terraform.natural(over)) continue;   // the town's own work on it since (a field, a path)
            if (townWork(level, m.set(x, y + 1, z))) continue;          // [city26] a building's ground or the way in to it, begun since
            if (sq[i]) {
                c.merge("square", 1, Integer::sum);
                if (y != g.target[i]) {                                          // [city26] its terrace's height (the heart's, unterraced)
                    at(c, "offLevel", x, y, z, null);
                    FIRST.putIfAbsent("offLevelWhy", "target " + g.target[i] + " ground " + g.ground[i] + " terrace " + (g.terrace == null ? "-" : g.terrace[i])
                        + " top " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(m.set(x, y, z)).getBlock()).getPath()
                        + " at target " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(m.set(x, g.target[i], z)).getBlock()).getPath());
                }
            }
            BlockState t = level.getBlockState(m.set(x, y, z));
            String odd = null;
            if (sq[i]) {
                if (TerraceWalls.isWall(t) && TerraceWalls.wanted(g, i)) c.merge("walls", 1, Integer::sum);   // [city26] a terrace's wall
                else if (TerraceWalls.isStep(t)) c.merge("steps", 1, Integer::sum);                        // [city26] a street's step
                else if (!t.is(d.surface)) odd = kindOf(t);
            } else if (t.is(Blocks.DIRT) || t.is(Blocks.COBBLESTONE) || Terraform.topOf(t) == Terraform.ROCK || t.is(Blocks.GRAVEL)) {
                odd = kindOf(t);
            }
            if (odd != null) {
                at(c, sq[i] ? "oddTop" : "rawEdge", x, y, z, t);
                if (sq[i]) c.merge("oddTop" + odd, 1, Integer::sum);
            }
            if (sq[i]) {
                for (int up = 1; up <= 12; up++) {
                    BlockState a = level.getBlockState(m.set(x, y + up, z));
                    if (a.isAir()) continue;
                    if (Terraform.growth(a) || Terraform.wet(a)) at(c, "growthOnSquare", x, y + up, z, a);
                }
            }
            boolean hollow = false;
            for (int dd = 1; dd <= DEEP && y - dd > minY; dd++) {
                BlockState b = level.getBlockState(m.set(x, y - dd, z));
                if (!Terraform.natural(b)) break;                       // something buried: not the ground's
                if (b.isAir() || !b.getFluidState().isEmpty() || Terraform.growth(b)) {
                    at(c, "holes", x, y - dd, z, b);
                    hollow = true;
                }
                if (dd <= 4 && (b.is(Blocks.COBBLESTONE) || b.is(Blocks.COBBLED_DEEPSLATE))) c.merge("junk", 1, Integer::sum);
            }
            if (t.is(Blocks.COBBLESTONE) || t.is(Blocks.COBBLED_DEEPSLATE)) c.merge("junk", 1, Integer::sum);
            if (hollow) c.merge("crust", 1, Integer::sum);
        }
        // ---- the steps between neighbours, and pits
        for (int i = 0; i < side * side; i++) {
            if (top[i] == Integer.MIN_VALUE || !counted[i]) continue;
            int x = i % side, z = i / side;
            int lowerThanAll = Integer.MAX_VALUE, wasLower = Integer.MAX_VALUE, under = 0, wasUnder = 0;
            for (int[] n : new int[][]{ {1, 0}, {0, 1}, {-1, 0}, {0, -1} }) {
                int xx = x + n[0], zz = z + n[1];
                if (xx < 0 || zz < 0 || xx >= side || zz >= side) { lowerThanAll = Integer.MIN_VALUE; continue; }
                int j = zz * side + xx;
                if (top[j] == Integer.MIN_VALUE || !counted[j]) { lowerThanAll = Integer.MIN_VALUE; continue; }
                lowerThanAll = Math.min(lowerThanAll, top[j] - top[i]);
                wasLower = Math.min(wasLower, g.ground[j] - g.ground[i]);
                if (top[i] - top[j] >= 2) under++;                       // [city26] standing two or more over it
                if (g.ground[i] - g.ground[j] >= 2) wasUnder++;
                if (n[0] < 0 || n[1] < 0) continue;                    // each pair once
                if (!worked[i] && !worked[j]) continue;
                int step = Math.abs(top[j] - top[i]);
                // [city26] A terrace or its street standing two or more over the ground beside it with its face not walled.
                int hiR = top[i] >= top[j] ? i : j, loR = hiR == i ? j : i;
                if (step >= 2 && g.terrace != null && g.terrace[hiR] != com.jrpetty.mcassistant.village.Terraces.NONE
                        && !walled(level, d, hiR, top[hiR], top[loR])) at(c, "rawFaces", d.heart().getX() + g.dx(hiR), top[hiR], d.heart().getZ() + g.dz(hiR), null);
                if (step <= Math.abs(g.ground[j] - g.ground[i])) continue;   // no worse than the land had it
                // [city26] A terrace's drop faced with its wall, the wall standing the whole height of it: built, not sheer.
                int hiI = top[i] >= top[j] ? i : j, loJ = hiI == i ? j : i;
                if (step > 2 && walled(level, d, hiI, top[hiI], top[loJ])) continue;
                if (step > 2) {
                    at(c, "sheer", d.heart().getX() + g.dx(i), top[i], d.heart().getZ() + g.dz(i), null);
                    FIRST.putIfAbsent("sheerWhy", "kind " + g.kind[i] + "/" + g.kind[j] + " ground " + g.ground[i] + "/" + g.ground[j]
                        + " target " + g.target[i] + "/" + g.target[j] + " now " + top[i] + "/" + top[j]);
                }
                else if (step == 2) c.merge("steps2", 1, Integer::sum);
            }
            // [city26] A pillar or a stub: a column two or more over three or four of its neighbours (a column the levelling
            // left standing, a block in a street), worse than the land had it.
            if (under >= 3 && wasUnder < 3 && (worked[i] || near(worked, side, i))) {
                at(c, "pillars", d.heart().getX() + g.dx(i), top[i], d.heart().getZ() + g.dz(i), null);
                int sx = d.heart().getX() + g.dx(i), sz = d.heart().getZ() + g.dz(i);
                if ((g.street != null && g.street[i]) || com.jrpetty.mcassistant.village.TownPlan.isStreet(g.dx(i), g.dz(i)))
                    at(c, "streetPillars", sx, top[i], sz, null);                // on a street or the square's ways
                StringBuilder w = new StringBuilder("kind " + g.kind[i] + " target " + (g.target == null ? "-" : g.target[i]) + " ground " + g.ground[i]
                    + " terrace " + (g.terrace == null ? "-" : g.terrace[i]) + " top " + top[i] + " block "
                    + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(m.set(d.heart().getX() + g.dx(i), top[i], d.heart().getZ() + g.dz(i))).getBlock()).getPath() + "; round:");
                for (int[] n : new int[][]{ {1, 0}, {0, 1}, {-1, 0}, {0, -1} }) {
                    int xx = x + n[0], zz = z + n[1];
                    if (xx < 0 || zz < 0 || xx >= side || zz >= side) continue;
                    int j = zz * side + xx;
                    w.append(' ').append(top[j]).append('/').append(g.target == null ? 0 : g.target[j]).append('/').append(g.ground[j]);
                }
                FIRST.putIfAbsent("pillarsWhy", w.toString());
            }
            if (worked[i] && lowerThanAll != Integer.MIN_VALUE && lowerThanAll >= 2 && lowerThanAll > wasLower) at(c, "pits", d.heart().getX() + g.dx(i), top[i], d.heart().getZ() + g.dz(i), null);
        }
        // ---- trees: leaves cut off from their logs, logs on nothing, stumps; vines hanging on nothing
        int x0 = hx - g.outer - 15, z0 = hz - g.outer - 15, w = 2 * g.outer + 31;   // (counted 8 round, held 7 more)
        int y0 = Math.max(minY, lowest - 4), hgt = Math.min(level.getMaxBuildHeight() - 1, highest + 2) - y0 + 1;
        if (hgt > 0 && (long) w * w * hgt < 40_000_000L) trees(level, c, x0, z0, y0, w, hgt);
        // ---- water left running, lava, sand over a hollow: anywhere in the worked ground, at and above its top less a few
        for (int i = 0; i < side * side; i++) {
            if (top[i] == Integer.MIN_VALUE || !(worked[i] || counted[i] && near(worked, side, i))) continue;
            int x = hx + g.dx(i), z = hz + g.dz(i);
            LevelChunk ch = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (ch == null) continue;
            int hi = ch.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
            // An untouched column beside the work counts only where the cut laid it open: above the worked column's
            // new top and within its old ground (natural springs and floating gravel elsewhere are the land's own).
            int lo = top[i] - DEEP, hi2 = hi + 1;
            if (!worked[i]) {
                lo = Integer.MAX_VALUE;
                hi2 = Integer.MIN_VALUE;
                int xi = i % side, zi = i / side;
                for (int[] n : new int[][]{ {1, 0}, {-1, 0}, {0, 1}, {0, -1} }) {
                    int xx = xi + n[0], zz = zi + n[1];
                    if (xx < 0 || zz < 0 || xx >= side || zz >= side) continue;
                    int j = zz * side + xx;
                    if (!worked[j] || top[j] == Integer.MIN_VALUE || g.ground[j] <= top[j]) continue;
                    lo = Math.min(lo, top[j] + 1);
                    hi2 = Math.max(hi2, g.ground[j]);
                }
            }
            for (int y = lo; y <= hi2; y++) {
                if (y <= minY) continue;
                BlockState b = ch.getBlockState(m.set(x, y, z));
                if (!b.getFluidState().isEmpty() && open(level, x, y, z)) {
                    if (b.getFluidState().is(FluidTags.LAVA)) at(c, "lava", x, y, z, b);
                    else if (!b.getFluidState().isSource()) at(c, "flowing", x, y, z, b);
                }
                if (b.getBlock() instanceof FallingBlock && y > minY + 1) {
                    BlockState u = ch.getBlockState(m.set(x, y - 1, z));
                    if (u.isAir() || !u.getFluidState().isEmpty()) at(c, "falling", x, y, z, b);
                }
            }
        }
        AABB box = new AABB(x0 + 7, y0, z0 + 7, x0 + w - 7, y0 + hgt + 8, z0 + w - 7);
        c.put("items", level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive()).size());
        return c;
    }

    /**
     * [city26] Is this the ground of a building the new town has begun since (its footing, its eaves, a door and the way in
     * to it)? A founding of forty takes minutes to finish its edge, and the town builds meanwhile: its builders ease the
     * ground at a door, and that is the town's work, not the levelling's.
     */
    private static boolean townWork(ServerLevel level, BlockPos p) {
        Villages.Village v = TownBlocks.townAt(level, p);
        if (v == null) return false;
        TownBlocks.Zone z = TownBlocks.zone(level, v, p);
        return z == TownBlocks.Zone.BUILDING || z == TownBlocks.Zone.EAVES || z == TownBlocks.Zone.DOORWAY
            || z == TownBlocks.Zone.APPROACH || z == TownBlocks.Zone.STAIRS;
    }

    /** [city26] Is this column faced with a terrace's wall from its top down to over the lower top beside it? */
    private static boolean walled(ServerLevel level, Done d, int i, int hi, int lo) {
        FoundingPlan.Ground g = d.ground;
        if (g.terrace == null) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int x = d.heart().getX() + g.dx(i), z = d.heart().getZ() + g.dz(i);
        for (int y = lo + 1; y <= hi; y++) if (!TerraceWalls.isWall(level.getBlockState(m.set(x, y, z)))) return false;
        return true;
    }

    /** Is this cell open to the sky, or beside a cell that is (so what is in it shows, and runs)? */
    private static boolean open(ServerLevel level, int x, int y, int z) {
        for (int[] n : new int[][]{ {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1} }) {
            int xx = x + n[0], zz = z + n[1];
            int sky = level.getHeight(Heightmap.Types.WORLD_SURFACE, xx, zz);
            if (n[0] == 0 && n[1] == 0 ? y >= sky - 1 : y >= sky) return true;
        }
        return false;
    }

    private static boolean near(boolean[] worked, int side, int i) {
        int x = i % side, z = i / side;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int xx = x + dx, zz = z + dz;
            if (xx >= 0 && zz >= 0 && xx < side && zz < side && worked[zz * side + xx]) return true;
        }
        return false;
    }

    private static String kindOf(BlockState t) {
        if (t.is(Blocks.GRAVEL)) return "Gravel";
        if (t.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)) return "Ore";
        if (t.is(BlockTags.DIRT) && !t.is(Blocks.GRASS_BLOCK)) return "Dirt";
        if (Terraform.topOf(t) == Terraform.ROCK || t.is(Blocks.COBBLESTONE)) return "Rock";
        return "";
    }

    /** Leaves, logs and vines in the box, looked over as the game's own leaf decay would. */
    private static void trees(ServerLevel level, Map<String, Integer> c, int x0, int z0, int y0, int w, int h) {
        // 0 nothing, 1 log (a tree's), 2 leaves (wild), 3 leaves (set), 4 vine, 5 other solid, 6 something built
        byte[] k = new byte[w * w * h];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = x0 >> 4; cx <= (x0 + w - 1) >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= (z0 + w - 1) >> 4; cz++) {
                LevelChunk ch = level.getChunkSource().getChunkNow(cx, cz);
                if (ch == null) continue;
                for (int x = Math.max(x0, cx << 4); x <= Math.min(x0 + w - 1, (cx << 4) + 15); x++) {
                    for (int z = Math.max(z0, cz << 4); z <= Math.min(z0 + w - 1, (cz << 4) + 15); z++) {
                        int hi = Math.min(y0 + h - 1, ch.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
                        for (int y = y0; y <= hi; y++) {
                            BlockState s = ch.getBlockState(m.set(x, y, z));
                            if (s.isAir()) continue;
                            byte v;
                            if (s.is(BlockTags.LOGS)) v = 1;
                            else if (s.is(BlockTags.LEAVES)) v = s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT) ? (byte) 3 : 2;
                            else if (s.is(Blocks.VINE)) v = 4;
                            else if (s.canBeReplaced() || !s.getFluidState().isEmpty()) continue;
                            else v = Terraform.natural(s) ? (byte) 5 : 6;
                            k[((y - y0) * w + (z - z0)) * w + (x - x0)] = v;
                        }
                    }
                }
            }
        }
        // Leaves: the distance to a log through leaves, six across at most (as LeavesBlock reckons it).
        byte[] dist = new byte[k.length];
        java.util.Arrays.fill(dist, (byte) 7);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < k.length; i++) if (k[i] == 1) { dist[i] = 0; q.add(i); }
        int[] step = { 1, -1, w, -w, w * w, -w * w };
        while (!q.isEmpty()) {
            int i = q.poll();
            if (dist[i] >= 6) continue;
            int x = i % w, z = (i / w) % w;
            for (int s = 0; s < 6; s++) {
                if (s == 0 && x == w - 1 || s == 1 && x == 0 || s == 2 && z == w - 1 || s == 3 && z == 0) continue;
                int j = i + step[s];
                if (j < 0 || j >= k.length || (k[j] != 2 && k[j] != 3) || dist[j] <= dist[i] + 1) continue;
                dist[j] = (byte) (dist[i] + 1);
                q.add(j);
            }
        }
        for (int i = 0; i < k.length; i++) if (k[i] == 2 && dist[i] > 6 && i % w >= 7 && i % w < w - 7 && (i / w) % w >= 7 && (i / w) % w < w - 7) at(c, "floatLeaves", x0 + i % w, y0 + i / (w * w), z0 + (i / w) % w, null);
        // Logs: each run of them (touching, corners too), on something or not, and with a crown or not.
        boolean[] seen = new boolean[k.length];
        for (int i0 = 0; i0 < k.length; i0++) {
            if (k[i0] != 1 || seen[i0]) continue;
            int size = 0;
            boolean grounded = false, crown = false, edge = false;
            q.add(i0);
            seen[i0] = true;
            while (!q.isEmpty()) {
                int i = q.poll();
                size++;
                int x = i % w, z = (i / w) % w, y = i / (w * w);
                if (x == 0 || z == 0 || x == w - 1 || z == w - 1 || y == 0) edge = true;
                if (y > 0) {
                    byte b = k[i - w * w];
                    if (b == 5 || b == 6) grounded = true;
                }
                for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx, yy = y + dy, zz = z + dz;
                    if (xx < 0 || zz < 0 || yy < 0 || xx >= w || zz >= w || yy >= h) continue;
                    int j = (yy * w + zz) * w + xx;
                    if (k[j] == 2 || k[j] == 3) crown = true;
                    if (k[j] == 6) edge = true;                         // something built beside it: a post, not a stump
                    if (k[j] == 1 && !seen[j]) { seen[j] = true; q.add(j); }
                }
            }
            if (edge) continue;
            int px = x0 + i0 % w, py = y0 + i0 / (w * w), pz = z0 + (i0 / w) % w;
            if (!grounded) { c.merge("floatLogs", size - 1, Integer::sum); at(c, "floatLogs", px, py, pz, null); }
            else if (!crown && size <= 3) at(c, "stumps", px, py, pz, null);
        }
        // Vines: hanging on nothing (no solid block beside or over them, and no vine over them).
        for (int i = 0; i < k.length; i++) {
            if (k[i] != 4) continue;
            int x = i % w, z = (i / w) % w, y = i / (w * w);
            boolean held = false;
            for (int s = 0; s < 6 && !held; s++) {
                if (s == 5) continue;                                   // under it holds nothing up
                if (s == 0 && x == w - 1 || s == 1 && x == 0 || s == 2 && z == w - 1 || s == 3 && z == 0 || s == 4 && y == h - 1) { held = true; continue; }
                int j = i + step[s];
                if (j >= 0 && j < k.length && (k[j] == 5 || k[j] == 6 || k[j] == 1 || k[j] == 2 || k[j] == 3 || (s == 4 && k[j] == 4))) held = true;
            }
            if (!held && x >= 7 && z >= 7 && x < w - 7 && z < w - 7) at(c, "floatVines", x0 + x, y0 + y, z0 + z, null);
        }
    }

    /** The counts in a line, for the log and the command. */
    public static String line(Map<String, Integer> c) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : c.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
