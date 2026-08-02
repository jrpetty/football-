package dev.structint.world;

import dev.structint.Config;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Reading the world for water-pressure purposes: how strong a block is against water, how deep the
 * water on a face is, and how much water is actually behind it.
 *
 * <p>Everything here is read-only and bounded. The expensive probes (head above a face, the volume
 * of a body) are memoised per sweep through {@link Probe}, because a dam face shares one water
 * column between every block stacked in it and one body between every column.
 */
public final class WaterPressure {

    /** The six face directions, as {dx, dy, dz}. */
    public static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0},
    };

    private WaterPressure() {
    }

    // ------------------------------------------------------------------ material

    /** Blocks of head a single block of this material holds back unbraced. */
    public static int resistanceOf(BlockState state) {
        if (state.is(HydroTags.PRESSURE_SEALING)) return Config.HYDRO_RES_SEALING.get();
        if (state.is(HydroTags.PRESSURE_BRITTLE)) return Config.HYDRO_RES_BRITTLE.get();
        if (state.is(StructuralTags.STRUCTURAL_METAL)) return Config.HYDRO_RES_METAL.get();
        if (state.is(StructuralTags.STRUCTURAL_REINFORCED)) return Config.HYDRO_RES_REINFORCED.get();
        if (state.is(StructuralTags.STRUCTURAL_STONE)) return Config.HYDRO_RES_STONE.get();
        if (state.is(StructuralTags.STRUCTURAL_WOOD)) return Config.HYDRO_RES_WOOD.get();
        if (state.is(StructuralTags.STRUCTURAL_DIRT)) return Config.HYDRO_RES_DIRT.get();
        return Config.HYDRO_RES_GENERIC.get();
    }

    public static boolean isExempt(BlockState state) {
        return state.is(HydroTags.PRESSURE_EXEMPT);
    }

    public static boolean isWater(BlockState state) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && fluid.is(FluidTags.WATER);
    }

    /**
     * True if this block forms part of a wall the water has to get through. Deliberately the same
     * notion of "load bearing" the span system uses, so the two agree about what a wall is.
     */
    public static boolean isWall(BlockState state, BlockGetter level, BlockPos pos) {
        return !isWater(state) && BlockClassifier.isLoadBearing(state, level, pos);
    }

    // ------------------------------------------------------------------ probing

    /** Per-sweep read caches. One instance per sweep, reused across every block examined. */
    public static final class Probe {

        private final ServerLevel level;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        /** packed water pos -> blocks of water at and above it */
        private final Long2IntOpenHashMap heads = new Long2IntOpenHashMap();
        /** packed water pos -> sampled volume of the body it belongs to */
        private final Long2IntOpenHashMap bodies = new Long2IntOpenHashMap();

        public Probe(ServerLevel level) {
            this.level = level;
            this.heads.defaultReturnValue(-1);
            this.bodies.defaultReturnValue(-1);
        }

        public ServerLevel level() {
            return level;
        }

        public BlockState stateAt(int x, int y, int z) {
            if (level.isOutsideBuildHeight(y)) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            return level.getBlockState(cursor.set(x, y, z));
        }

        /** Part of a wall the water has to get through, tested without allocating a position. */
        public boolean isWall(BlockState state, int x, int y, int z) {
            return !isWater(state) && BlockClassifier.isLoadBearing(state, level, cursor.set(x, y, z));
        }

        public boolean isManagedAt(int x, int y, int z) {
            return StructuralData.isManaged(level, cursor.set(x, y, z));
        }

        public boolean isLoadedAt(int x, int y, int z) {
            return StructuralData.isChunkLoaded(level, cursor.set(x, y, z));
        }

        /**
         * Blocks of water standing at and above (x, y, z), i.e. the head pressing on a face
         * touching that water block. Capped by {@code maxHead}; memoised per position, and the
         * whole column is filled in on the way down so a 30-block dam face costs one walk.
         */
        public int headAbove(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            int cached = heads.get(key);
            if (cached >= 0) return cached;

            int cap = Config.HYDRO_MAX_HEAD.get();
            int top = y;
            while (top - y < cap && isWater(stateAt(x, top + 1, z))) {
                top++;
            }
            // Walk back down filling the cache, so every block of this dam face is now free.
            for (int yy = y; yy <= top; yy++) {
                heads.put(BlockPos.asLong(x, yy, z), top - yy + 1);
            }
            return top - y + 1;
        }

        /**
         * Size of the connected body of water touching (x, y, z), sampled outward from that point
         * and stopped at {@code bodySampleCap}. This is the "up to a certain size of it, to where
         * the dam is" bound - a shoreline block never walks the whole ocean, it walks a fixed
         * budget of it and reports the cap.
         *
         * <p>Every position visited is cached, so the rest of the dam face reuses the same fill.
         */
        public int bodyVolume(int x, int y, int z) {
            long start = BlockPos.asLong(x, y, z);
            int cached = bodies.get(start);
            if (cached >= 0) return cached;

            int cap = Config.HYDRO_BODY_SAMPLE_CAP.get();
            LongOpenHashSet seen = new LongOpenHashSet();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            seen.add(start);
            queue.enqueue(start);

            while (!queue.isEmpty() && seen.size() < cap) {
                long cur = queue.dequeueLong();
                int cx = BlockPos.getX(cur);
                int cy = BlockPos.getY(cur);
                int cz = BlockPos.getZ(cur);
                for (int[] d : DIRECTIONS) {
                    int nx = cx + d[0];
                    int ny = cy + d[1];
                    int nz = cz + d[2];
                    if (level.isOutsideBuildHeight(ny)) continue;
                    long nk = BlockPos.asLong(nx, ny, nz);
                    if (seen.contains(nk)) continue;
                    // Unloaded chunks stop the fill rather than dragging them in.
                    if (!isLoadedAt(nx, ny, nz)) continue;
                    if (!isWater(stateAt(nx, ny, nz))) continue;
                    seen.add(nk);
                    if (seen.size() >= cap) break;
                    queue.enqueue(nk);
                }
            }

            int volume = seen.size();
            for (long pos : seen) {
                bodies.put(pos, volume);
            }
            return volume;
        }

        public void clear() {
            heads.clear();
            bodies.clear();
        }
    }
}
