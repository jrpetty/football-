package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * [player15] Where the town may put up a building for a player: open, near-level ground of the town's own, clear of
 * its streets, its mine and its buildings, with its door to the square. The candidates nearest the square first; a plot
 * may be nudged a block at a time or turned, and is checked again every time.
 */
public final class BuildPlots {

    private BuildPlots() {}

    /** A plot: its anchor (the building's middle at ground level), the way its front faces, and its label. */
    public record Plot(BlockPos at, Direction facing, String label) {}

    /** Up to {@code most} plots for this drawing round the town, nearest the square first. */
    public static List<Plot> find(ServerLevel level, Villages.Village v, String blueprint, int most) {
        List<Plot> out = new ArrayList<>();
        BlockPos c = v.centre();
        int[] g = BuildGoal.footprint(blueprint);
        int span = Math.max(g[0], g[1]) * 2 + 4;
        for (int r = 12; r <= 72 && out.size() < most; r += 4) {
            for (int a = 0; a < 16 && out.size() < most; a++) {
                double ang = Math.PI * 2 * (a + (r % 8 == 0 ? 0.5 : 0)) / 16.0;
                int x = c.getX() + (int) Math.round(Math.cos(ang) * r), z = c.getZ() + (int) Math.round(Math.sin(ang) * r);
                if (!level.isLoaded(new BlockPos(x, c.getY(), z))) continue;
                BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                Direction facing = toward(at, c);
                if (!whyNot(level, v, blueprint, at, facing).isEmpty()) continue;
                boolean crowded = false;
                for (Plot o : out) if (o.at().distSqr(at) < span * span) crowded = true;
                if (crowded) continue;
                out.add(new Plot(at, facing, label(c, at)));
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.at().distSqr(c)));
        return out;
    }

    /** Its front to the square. */
    public static Direction toward(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    static String label(BlockPos c, BlockPos at) {
        int dx = at.getX() - c.getX(), dz = at.getZ() - c.getZ();
        int d = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        String dir = (dz < -d / 3 ? "north" : dz > d / 3 ? "south" : "") + (dx > d / 3 ? "east" : dx < -d / 3 ? "west" : "");
        return d + " blocks " + (dir.isEmpty() ? "from" : dir + " of") + " the square";
    }

    /** Why a building may not go here ("" if it may): off the town, a street, the mine, a building in the way, steep ground. */
    public static String whyNot(ServerLevel level, @Nullable Villages.Village v, String blueprint, BlockPos at, Direction facing) {
        if (v == null) return "no town here";
        if (at.distSqr(v.centre()) > 110.0 * 110.0) return "too far out of town";
        int[] g = BuildGoal.footprint(blueprint);
        int gx = facing.getAxis() == Direction.Axis.Z ? g[0] : g[1], gz = facing.getAxis() == Direction.Axis.Z ? g[1] : g[0];
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx = -gx - 1; dx <= gx + 1; dx++) {
            for (int dz = -gz - 1; dz <= gz + 1; dz++) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                BlockPos col = new BlockPos(x, at.getY(), z);
                if (!level.isLoaded(col)) return "the ground there isn't loaded";
                if (Footways.street(v, x, z)) return "it would stand on a street";
                if (MineGround.reserved(v.id(), x, z)) return "it would stand on the mine's ground";
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
                BlockState ground = level.getBlockState(new BlockPos(x, h - 1, z));
                if (!natural(ground)) return "something stands there already";
                if (!ground.getFluidState().isEmpty()) return "it's water";
            }
        }
        if (hi - lo > 2) return "the ground is too steep";
        if (Math.abs(at.getY() - lo) > 2) return "the ground is too steep";
        for (PlayerShops.Shop s : PlayerShops.all(v.id())) {
            if (Math.abs(s.anchor.getX() - at.getX()) <= gx + 3 && Math.abs(s.anchor.getZ() - at.getZ()) <= gz + 3) return "a shop stands there";
        }
        return "";
    }

    /** Open ground: earth, grass, sand, stone of the land itself, snow; not a floor, a path or a wall. */
    static boolean natural(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(net.minecraft.world.level.block.Blocks.GRAVEL)
            || st.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK) || st.is(net.minecraft.world.level.block.Blocks.SNOW)
            || st.is(BlockTags.TERRACOTTA);
    }
}
