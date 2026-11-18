package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [city9] Water the town puts down stays where it is put.
 *
 * <p>A town of the hills had its allotments on a slope, the channel down the back of them a block over thin air, and
 * every day a bucket went into it and ran down the hillside between the houses, a stream of the town's own making. So:
 * <ul>
 * <li>Water goes into a cell only where it will stay ({@link #holds}): something firm under it (or water), and on
 *     every side something firm, water, farmland or another cell of the same basin. The allotments' channel, a field's
 *     water hole, a building's own water (a fountain, a well, a basin) and a farmer's bucket all ask first.</li>
 * <li>A channel with no bed under it, or a side open, is lined first out of the stores' cobblestone ({@link #line}),
 *     then filled; with no stone to line it, it waits.</li>
 * <li>On the town's rounds, one building a visit, any water in a building's water cells that would not stay is taken
 *     up ({@link #sweep}), so what ran before stops running: an older town's stream dries up.</li>
 * </ul>
 */
public final class CityWater {

    private CityWater() {}

    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CURSOR.clear();
    }

    /** Would water here stay put: firm (or water) under it, and firm, water, farmland or one of {@code basin} each side? */
    public static boolean holds(ServerLevel level, BlockPos at, Collection<BlockPos> basin) {
        return holds(level, at, basin::contains);
    }

    /** As holds, a side open into {@code ok} (a building's own basin, its own floor) allowed: a fountain's spring runs into
     *  its own bowl. Never over nothing: its bed is always asked. */
    public static boolean holds(ServerLevel level, BlockPos at, java.util.function.Predicate<BlockPos> ok) {
        BlockState under = level.getBlockState(at.below());
        if (!firm(under) && !(under.getFluidState().is(FluidTags.WATER) && under.getFluidState().isSource())) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = at.relative(d);
            if (ok.test(n)) continue;
            BlockState st = level.getBlockState(n);
            if (firm(st) || st.getBlock() instanceof FarmBlock) continue;
            if (st.getFluidState().is(FluidTags.WATER) && st.getFluidState().isSource()) continue;
            return false;
        }
        return true;
    }

    /** Something water does not run through or off. */
    static boolean firm(BlockState st) {
        return st.blocksMotion() && st.getFluidState().isEmpty() || st.isSolid() && st.getFluidState().isEmpty();
    }

    /**
     * Line a basin cell out of the stores' cobblestone so it will hold: its bed, and any side open that is not another
     * cell of the basin. True if it holds now (lined, or already firm); false if the stores could not line it.
     */
    public static boolean line(ServerLevel level, Villages.Village v, BlockPos at, Collection<BlockPos> basin) {
        List<BlockPos> open = new ArrayList<>();
        BlockState under = level.getBlockState(at.below());
        if (!firm(under) && !under.getFluidState().is(FluidTags.WATER)) open.add(at.below());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = at.relative(d);
            if (basin.contains(n)) continue;
            BlockState st = level.getBlockState(n);
            if (firm(st) || st.getBlock() instanceof FarmBlock || st.getFluidState().is(FluidTags.WATER) && st.getFluidState().isSource()) continue;
            if (!st.canBeReplaced()) return false;                         // a fence, a flower pot...: not ours to wall over
            open.add(n);
        }
        if (open.isEmpty()) return true;
        if (Market.stock(level, v.id(), s -> s.is(Items.COBBLESTONE)) < open.size()) return false;
        for (BlockPos p : open) if (!TownBlocks.allows(level, v, p, TownBlocks.Why.REPAIR) && !TownBlocks.allows(level, v, p, TownBlocks.Why.FIELD)) return false;
        if (!Crafts.take(level, v, s -> s.is(Items.COBBLESTONE), open.size())) return false;
        for (BlockPos p : open) {
            level.setBlockAndUpdate(p, Blocks.COBBLESTONE.defaultBlockState());
            TownBlocks.traced(v, p, TownBlocks.Why.REPAIR);
        }
        return holds(level, at, basin);
    }

    /** Where a building's own water may run: its water cells, and (but for the allotments, whose channel is all its
     *  water) anywhere inside its footing, a fountain's bowl round its spring. */
    public static java.util.function.Predicate<BlockPos> own(Ledger.Building b, Collection<BlockPos> cells) {
        Set<BlockPos> basin = new java.util.HashSet<>(cells);
        if (b.structure().equals(TownLook.ALLOTMENTS)) return basin::contains;
        int[] g = BuildGoal.footprint(b.structure());
        boolean turned = b.facing().getAxis() == Direction.Axis.X;
        int hx = turned ? g[1] : g[0], hz = turned ? g[0] : g[1];
        BlockPos a = b.anchor();
        return q -> basin.contains(q) || (Math.abs(q.getX() - a.getX()) < hx && Math.abs(q.getZ() - a.getZ()) < hz && q.getY() >= a.getY() - 1);
    }

    /** A building's own water cells (its drawing's water), where they are in the world. */
    static List<BlockPos> waterCells(Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        if (b.structure().equals(TownLook.ALLOTMENTS)) {
            for (int[] c : Allotments.CHANNEL) out.add(TownLook.cell(b, c[0], -1, c[1]));
            return out;
        }
        if (!com.jrpetty.mcassistant.entity.goal.Blueprints.has(b.structure())) return out;
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.WATER) out.add(p.pos());
        }
        return out;
    }

    /**
     * One building a visit: water standing in its water cells that would not stay is taken up (it was running off over
     * the town's ground). Returns how many cells were dried.
     */
    public static int sweep(ServerLevel level, Villages.Village v) {
        List<Ledger.Building> all = Ledger.buildings(v.id());
        if (all.isEmpty()) return 0;
        int i = Math.floorMod(CURSOR.merge(v.id(), 1, Integer::sum), all.size());
        Ledger.Building b = all.get(i);
        if (!level.isLoaded(b.anchor())) return 0;
        List<BlockPos> cells = waterCells(b);
        if (cells.isEmpty()) return 0;
        java.util.function.Predicate<BlockPos> ok = own(b, cells);
        int n = 0;
        for (BlockPos p : cells) {
            if (!level.getFluidState(p).is(FluidTags.WATER) || holds(level, p, ok)) continue;
            if (PlayerBlocks.placed(level, p)) continue;
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            n++;
        }
        if (n > 0) com.jrpetty.mcassistant.Guard.run("city9 water", () ->
            Villages.tell(v.id(), level.getDayTime() / 24000L, "the water running off " + Villages.spoken(b.structure()) + " was stopped"));
        return n;
    }

    /** Tests and the audit: cells of flowing water (not a source) at the top of the ground within this reach of the heart. */
    public static int flowing(ServerLevel level, BlockPos heart, int reach) {
        int n = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = heart.getX() + dx, z = heart.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                var f = level.getFluidState(new BlockPos(x, y, z));
                if (f.is(FluidTags.WATER) && !f.isSource()) n++;
            }
        }
        return n;
    }
}
