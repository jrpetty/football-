package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * [bridges18] How a crossing or a flight of steps the folk built looks and walks, counted: the way a folk walks from one
 * end to the other (the cheapest walk, swimming only where it must), and along it what makes a bridge or a stair look
 * scattered rather than built.
 * <ul>
 * <li><b>mixed</b>: materials beyond the first among the made blocks in the box (spruce planks and spruce logs are one
 *     timber; stone bricks and their stairs one stone; lights not counted);</li>
 * <li><b>gaps</b>: places the walk has to swim;</li>
 * <li><b>rails</b>: places on the walk over a drop of more than a block with no railing on that side;</li>
 * <li><b>uneven</b>: changes of height along the walk over the water;</li>
 * <li><b>fullSteps</b>: a block up or down the walk onto a full block (no stair or slab to take it);</li>
 * <li><b>wrongStairs</b>: a stair on the walk that does not face the way up;</li>
 * <li><b>landings</b>: flights of more than six steps with no level place;</li>
 * <li><b>unlit</b>: places on the walk over the water more than seven blocks from a light.</li>
 * </ul>
 */
public final class BridgeStairAudit {

    private BridgeStairAudit() {}

    public static final class Counts {
        public int mixed, gaps, rails, uneven, fullSteps, wrongStairs, landings, unlit, walk, overWater;
        public boolean walked;
        public final List<String> families = new ArrayList<>();
        /** Where the first few faults are, to find them by. */
        public final List<String> where = new ArrayList<>();

        void at(String what, BlockPos p) {
            if (where.size() < 6) where.add(what + "@" + p.toShortString());
        }

        public int faults() { return mixed + gaps + rails + uneven + fullSteps + wrongStairs + landings + unlit; }

        public void add(Counts o) {
            mixed += o.mixed; gaps += o.gaps; rails += o.rails; uneven += o.uneven; fullSteps += o.fullSteps;
            wrongStairs += o.wrongStairs; landings += o.landings; unlit += o.unlit; walk += o.walk; overWater += o.overWater;
            walked &= o.walked;
        }

        @Override
        public String toString() {
            return "mixed " + mixed + " " + families + ", gaps " + gaps + ", rails " + rails + ", uneven " + uneven + ", fullSteps " + fullSteps
                + ", wrongStairs " + wrongStairs + ", landings " + landings + ", unlit " + unlit + " (walk " + walk + ", over water " + overWater
                + ", dry " + walked + ")" + (where.isEmpty() ? "" : " " + where);
        }
    }

    /** The material a made block is of, by its name: "spruce", "stone_brick", "cobblestone"; null for a light or the world's own. */
    static String family(BlockState st) {
        if (st.isAir() || st.getBlock() instanceof LanternBlock || st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) return null;
        if (!BridgeKit.made(st)) return null;
        String n = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        n = n.replace("stripped_", "");
        for (String end : new String[]{ "_planks", "_log", "_wood", "_fence_gate", "_fence", "_stairs", "_slab", "_wall" }) {
            if (n.endsWith(end)) { n = n.substring(0, n.length() - end.length()); break; }
        }
        if (n.endsWith("bricks")) n = n.substring(0, n.length() - 1);
        return n;
    }

    private static boolean passable(ServerLevel level, BlockPos p) {
        return Footways.passable(level, p);
    }

    private static boolean wet(ServerLevel level, BlockPos p) {
        return level.getFluidState(p).is(FluidTags.WATER);
    }

    /** Can a folk stand here dry: room, and a floor under it. */
    private static boolean stand(ServerLevel level, BlockPos feet) {
        return passable(level, feet) && passable(level, feet.above()) && !wet(level, feet) && Footways.floor(level, feet.below());
    }

    private static boolean overWater(ServerLevel level, BlockPos feet) {
        for (int d = 1; d <= 6; d++) {
            BlockPos q = feet.below(d);
            if (wet(level, q)) return true;
            BlockState st = level.getBlockState(q);
            if (BridgeKit.earth(st)) return false;
        }
        return false;
    }

    private static boolean railing(BlockState st) {
        return st.is(BlockTags.FENCES) || st.is(BlockTags.WALLS) || st.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
            || st.is(BlockTags.LOGS) || st.is(BlockTags.PLANKS) || st.is(BlockTags.STONE_BRICKS) || st.is(Blocks.COBBLESTONE);
    }

    /** The cheapest walk from one place to another inside the box: a step costs one, a place in the water twenty. */
    static List<BlockPos> walk(ServerLevel level, BlockPos from, BlockPos to, int x0, int x1, int z0, int z1) {
        BlockPos start = Footways.standNear(level, from, 4, 6), goal = Footways.standNear(level, to, 4, 6);
        if (start == null || goal == null) return List.of();
        Map<BlockPos, Integer> cost = new HashMap<>();
        Map<BlockPos, BlockPos> came = new HashMap<>();
        PriorityQueue<long[]> q = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        cost.put(start, 0);
        q.add(new long[]{ 0, start.asLong() });
        int seen = 0;
        while (!q.isEmpty() && seen < 60000) {
            long[] top = q.poll();
            BlockPos at = BlockPos.of(top[1]);
            if (top[0] > cost.getOrDefault(at, Integer.MAX_VALUE)) continue;
            seen++;
            if (at.equals(goal)) break;
            boolean head = passable(level, at.above(2));
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = at.relative(d);
                if (n.getX() < x0 || n.getX() > x1 || n.getZ() < z0 || n.getZ() > z1) continue;
                List<BlockPos> next = new ArrayList<>();
                if (head && (stand(level, n.above()) || wet(level, n.above()) && passable(level, n.above(2)))) next.add(n.above());
                if (passable(level, n) && passable(level, n.above())) {
                    for (int dy = 0; dy <= 3; dy++) {
                        BlockPos m = n.below(dy);
                        if (!passable(level, m)) break;
                        if (stand(level, m) || wet(level, m)) { next.add(m); break; }
                    }
                }
                for (BlockPos m : next) {
                    int c = (int) top[0] + (wet(level, m) ? 20 : 1);
                    if (c < cost.getOrDefault(m, Integer.MAX_VALUE)) {
                        cost.put(m, c);
                        came.put(m, at);
                        q.add(new long[]{ c, m.asLong() });
                    }
                }
            }
        }
        if (!cost.containsKey(goal)) return List.of();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p = goal; p != null; p = came.get(p)) out.add(p);
        Collections.reverse(out);
        return out;
    }

    /** A crossing or a flight audited: the walk from one end to the other inside the box, and the made blocks in it. */
    public static Counts audit(ServerLevel level, BlockPos from, BlockPos to, int x0, int x1, int y0, int y1, int z0, int z1) {
        Counts c = new Counts();
        // The materials.
        Map<String, Integer> fams = new HashMap<>();
        List<BlockPos> lights = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof LanternBlock || st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) lights.add(p.immutable());
            String f = family(st);
            if (f != null) fams.merge(f, 1, Integer::sum);
        }
        // A handful of a material is trim (a door's frame, a signpost): a material is counted from four blocks.
        for (Map.Entry<String, Integer> e : fams.entrySet()) if (e.getValue() >= 4) c.families.add(e.getKey() + "=" + e.getValue());
        c.mixed = Math.max(0, c.families.size() - 1);
        // The walk.
        List<BlockPos> w = walk(level, from, to, x0, x1, z0, z1);
        c.walk = w.size();
        c.walked = !w.isEmpty();
        int run = 0;
        for (int i = 0; i < w.size(); i++) {
            BlockPos p = w.get(i);
            if (wet(level, p)) { c.gaps++; c.walked = false; c.at("gap", p); continue; }
            boolean water = overWater(level, p);
            if (water) {
                c.overWater++;
                boolean lit = false;
                for (BlockPos l : lights) if (Math.abs(l.getX() - p.getX()) + Math.abs(l.getZ() - p.getZ()) <= 7 && Math.abs(l.getY() - p.getY()) <= 4) { lit = true; break; }
                if (!lit) { c.unlit++; c.at("dark", p); }
            }
            if (i == 0) continue;
            BlockPos prev = w.get(i - 1);
            int dy = p.getY() - prev.getY();
            Direction d = Direction.getNearest(p.getX() - prev.getX(), 0, p.getZ() - prev.getZ());
            if (dy != 0 && water && overWater(level, prev)) { c.uneven++; c.at("uneven", p); }
            if (dy != 0) {
                run++;
                if (run == 7) { c.landings++; c.at("landing", p); }
                if (Math.abs(dy) == 1) {
                    BlockPos hiFloor = (dy > 0 ? p : prev).below();
                    Direction up = dy > 0 ? d : d.getOpposite();
                    BlockState f = level.getBlockState(hiFloor);
                    if (f.getBlock() instanceof StairBlock) {
                        if (f.getValue(StairBlock.HALF) != net.minecraft.world.level.block.state.properties.Half.BOTTOM || f.getValue(StairBlock.FACING) != up) { c.wrongStairs++; c.at("facing", hiFloor); }
                    } else {
                        boolean eased = f.getBlock() instanceof SlabBlock && f.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
                        BlockState lo = level.getBlockState((dy > 0 ? prev : p).below());
                        if (lo.getBlock() instanceof SlabBlock && lo.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) eased = true;
                        if (!eased) { c.fullSteps++; c.at("step " + f.getBlock().getDescriptionId().replace("block.minecraft.", ""), hiFloor); }
                    }
                }
            } else {
                run = 0;
            }
            // A drop beside the walk with no railing (where the walk goes straight on).
            if (dy == 0 && i + 1 < w.size()) {
                for (Direction side : new Direction[]{ d.getClockWise(), d.getCounterClockWise() }) {
                    BlockPos s = p.relative(side);
                    if (stand(level, s) || stand(level, s.below()) || stand(level, s.above())) continue;
                    if (!passable(level, s) && !wet(level, s)) continue;                 // a wall or a railing there
                    boolean drop = passable(level, s.below()) || wet(level, s.below());
                    if (!drop) continue;
                    if (railing(level.getBlockState(s)) || railing(level.getBlockState(s.above()))) continue;
                    c.rails++;
                    c.at("rail", s);
                }
            }
        }
        return c;
    }
}
