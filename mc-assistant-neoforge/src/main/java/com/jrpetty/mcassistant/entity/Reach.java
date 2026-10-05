package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The ground a village can walk to from its heart: every column out to {@link #R} blocks that
 * can be got to on foot, a step of one block up or down at a time — the way the folk's own
 * pathfinder goes — round the trees, the houses and the cliffs.
 *
 * <p>A field is walked to and from every day, and the pathfinder will not plan a route longer
 * than its search: on a mountain map the village's farmland was marked out over a ridge and
 * across a pond, a hundred and more blocks off, and its farmers spent the whole of every day
 * "walking back to the plot", swimming in the pond below the heart. A field goes where this
 * says the farmer can walk. Read from the chunks' own height maps — a few milliseconds — and
 * kept for five minutes a village; ground not loaded yet is ground not known.
 */
public final class Reach {

    /** How far out the survey goes, in blocks, each way. */
    public static final int R = 128;
    private static final int SIZE = 2 * R + 1;
    /** How long a survey is good for, in game ticks. */
    private static final long FRESH = 6000;
    /** Fewer walkable columns than this and the survey says nothing (a heart on a rooftop). */
    private static final int TOO_FEW = 400;

    private static final Map<UUID, Reach> SURVEYS = new HashMap<>();

    private final BlockPos heart;
    private final long made;
    private final BitSet walked;
    private final int count;

    private Reach(BlockPos heart, long made, BitSet walked, int count) {
        this.heart = heart;
        this.made = made;
        this.walked = walked;
        this.count = count;
    }

    /** The village's walkable ground, surveyed afresh when the last survey is old; null when
     *  there is too little of it known to go by (its chunks still coming in). */
    @Nullable
    public static Reach of(ServerLevel level, UUID village, BlockPos heart) {
        long now = level.getGameTime();
        Reach r = SURVEYS.get(village);
        if (r == null || !r.heart.equals(heart) || now - r.made >= FRESH || now < r.made) {
            r = survey(level, heart, now);
            SURVEYS.put(village, r);
        }
        return r.count < TOO_FEW ? null : r;
    }

    /** Forget every survey (a world closing or opening, a test). */
    public static void reset() {
        SURVEYS.clear();
    }

    /** How many columns can be walked to. */
    public int count() {
        return count;
    }

    /** Can the folk walk to this column, or to one within {@code slack} blocks of it? */
    public boolean reaches(int x, int z, int slack) {
        int cx = x - heart.getX() + R, cz = z - heart.getZ() + R;
        for (int dx = -slack; dx <= slack; dx += 2) {
            for (int dz = -slack; dz <= slack; dz += 2) {
                int ix = cx + dx, iz = cz + dz;
                if (ix < 0 || iz < 0 || ix >= SIZE || iz >= SIZE) continue;
                if (walked.get(ix * SIZE + iz)) return true;
            }
        }
        return false;
    }

    public boolean reaches(BlockPos p, int slack) {
        return reaches(p.getX(), p.getZ(), slack);
    }

    private static Reach survey(ServerLevel level, BlockPos heart, long now) {
        int[] h = new int[SIZE * SIZE];
        java.util.Arrays.fill(h, Integer.MIN_VALUE);
        int x0 = heart.getX() - R, z0 = heart.getZ() - R;
        for (int cx = x0 >> 4; cx <= (heart.getX() + R) >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= (heart.getZ() + R) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (int lx = 0; lx < 16; lx++) {
                    int ix = (cx << 4) + lx - x0;
                    if (ix < 0 || ix >= SIZE) continue;
                    for (int lz = 0; lz < 16; lz++) {
                        int iz = (cz << 4) + lz - z0;
                        if (iz < 0 || iz >= SIZE) continue;
                        h[ix * SIZE + iz] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
                    }
                }
            }
        }
        BitSet walked = new BitSet(SIZE * SIZE);
        int[] queue = new int[SIZE * SIZE];
        int head = 0, tail = 0;
        // Out from the heart's own ground: the square round it, whatever stands there.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int i = (R + dx) * SIZE + (R + dz);
                if (h[i] == Integer.MIN_VALUE || walked.get(i)) continue;
                walked.set(i);
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int ix = i / SIZE, iz = i % SIZE, hi = h[i];
            for (int k = 0; k < 4; k++) {
                int nx = ix + (k == 0 ? 1 : k == 1 ? -1 : 0);
                int nz = iz + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) continue;
                int n = nx * SIZE + nz;
                if (walked.get(n) || h[n] == Integer.MIN_VALUE || Math.abs(h[n] - hi) > 1) continue;
                walked.set(n);
                queue[tail++] = n;
            }
        }
        return new Reach(heart.immutable(), now, walked, tail);
    }
}
