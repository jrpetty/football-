package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/** [tp47] The nearest dry place to stand on beside a stretch of water, however high its bank (AssistantEntity.climbOutOfTheWater). */
final class StepOutOfWater {

    private StepOutOfWater() {}

    /** The nearest place within {@code r} blocks across and {@code up} above, a block or two under the water's top at the
     *  lowest, a folk can stand on: dry feet and head, firm ground under it, no water under it. Null if none. */
    @Nullable
    static BlockPos nearestBank(Level level, BlockPos here, int r, int up) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double flat = dx * dx + dz * dz;
                if (flat > r * r || flat >= bestD) continue;
                for (int dy = -1; dy <= up; dy++) {
                    q.set(here.getX() + dx, here.getY() + dy, here.getZ() + dz);
                    if (!level.isLoaded(q) || !level.getFluidState(q).isEmpty()) continue;
                    if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) continue;
                    BlockPos head = q.above(), under = q.below();
                    if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty() || !level.getFluidState(head).isEmpty()) continue;
                    if (!level.getFluidState(under).isEmpty() || !level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) continue;
                    double d = flat + dy * dy * 0.25;
                    if (d < bestD) { bestD = d; best = q.immutable(); }
                    break;
                }
            }
        }
        return best;
    }
}
