package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
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
    /** Steps from the heart's ground on foot, plus one; 0 where it cannot be walked to. */
    private final char[] dist;
    /** The standing height of each column. */
    private final short[] feet;
    private final int count;

    private Reach(BlockPos heart, long made, char[] dist, short[] feet, int count) {
        this.heart = heart;
        this.made = made;
        this.dist = dist;
        this.feet = feet;
        this.count = count;
    }

    /** The village's walkable ground, surveyed afresh when the last survey is old; null when
     *  there is too little of it known to go by (its chunks still coming in). */
    @Nullable
    public static Reach of(ServerLevel level, UUID village, BlockPos heart) {
        long now = level.getGameTime();
        Reach r = SURVEYS.get(village);
        if (r == null || !r.heart.equals(heart) || now - r.made >= FRESH || now < r.made) {
            // Old surveys nobody has asked for in a while are let go (a world of many villages).
            SURVEYS.values().removeIf(o -> now - o.made > 4 * FRESH || now < o.made);
            r = survey(level, heart, now);
            SURVEYS.put(village, r);
        }
        return r.count < TOO_FEW ? null : r;
    }

    /** The village's last survey, if it has one, without making a new one (the report line). */
    @Nullable
    public static Reach last(UUID village) {
        Reach r = SURVEYS.get(village);
        return r == null || r.count < TOO_FEW ? null : r;
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
                if (dist[ix * SIZE + iz] != 0) return true;
            }
        }
        return false;
    }

    public boolean reaches(BlockPos p, int slack) {
        return reaches(p.getX(), p.getZ(), slack);
    }

    /** The walked column nearest this one, within {@code slack} blocks; -1 if none. */
    private int nearestWalked(int x, int z, int slack) {
        int cx = x - heart.getX() + R, cz = z - heart.getZ() + R;
        for (int r = 0; r <= slack; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int ix = cx + dx, iz = cz + dz;
                    if (ix < 0 || iz < 0 || ix >= SIZE || iz >= SIZE) continue;
                    if (dist[ix * SIZE + iz] != 0) return ix * SIZE + iz;
                }
            }
        }
        return -1;
    }

    /** One step nearer the heart: a neighbouring column one step less from it; -1 at the heart. */
    private int down(int i) {
        int d = dist[i];
        if (d <= 1) return -1;
        int ix = i / SIZE, iz = i % SIZE;
        if (ix + 1 < SIZE && dist[i + SIZE] == d - 1) return i + SIZE;
        if (ix > 0 && dist[i - SIZE] == d - 1) return i - SIZE;
        if (iz + 1 < SIZE && dist[i + 1] == d - 1) return i + 1;
        if (iz > 0 && dist[i - 1] == d - 1) return i - 1;
        return -1;
    }

    private BlockPos at(int i) {
        return new BlockPos(heart.getX() - R + i / SIZE, feet[i], heart.getZ() - R + i % SIZE);
    }

    /**
     * The next stop on the walkable way from one place to another, about {@code steps} blocks
     * along it: on toward the heart from where it stands until the way meets the way out to the
     * destination, then out along that. A stop that near, on ground the survey walked, is one the
     * pathfinder always finds — where asked for the whole of a long walk round a pond or a ridge
     * at once, it gave up part way and planned a route that ended in the water. Null when either
     * end is off the walkable ground.
     */
    @Nullable
    public BlockPos waypoint(BlockPos from, BlockPos to, int steps) {
        // A mine in a hillside or a wood on a slope is not on walkable ground itself: the way goes
        // to the walkable ground nearest it, and the last few blocks are the pathfinder's.
        int f = nearestWalked(from.getX(), from.getZ(), 6), t = nearestWalked(to.getX(), to.getZ(), 20);
        if (f < 0 || t < 0) return null;
        // The way from the destination in to the heart, and where each column of it falls.
        java.util.HashMap<Integer, Integer> onWay = new java.util.HashMap<>();
        java.util.ArrayList<Integer> way = new java.util.ArrayList<>();
        for (int c = t; c >= 0; c = down(c)) {
            onWay.put(c, way.size());
            way.add(c);
        }
        // In toward the heart until on that way...
        int c = f, taken = 0;
        while (!onWay.containsKey(c)) {
            if (taken >= steps) return at(c);
            int next = down(c);
            if (next < 0) return null;
            c = next;
            taken++;
        }
        // ...then out along it.
        return at(way.get(Math.max(0, onWay.get(c) - (steps - taken))));
    }

    /**
     * How much ground can be walked to from this spot within {@code r} blocks — the measure of a
     * place to found a village on (VillageSpawner.campSite). Only ground already loaded counts.
     */
    public static int walkableAround(ServerLevel level, BlockPos at, int r) {
        int size = 2 * r + 1;
        int[] h = new int[size * size];
        for (int ix = 0; ix < size; ix++) {
            for (int iz = 0; iz < size; iz++) {
                int x = at.getX() - r + ix, z = at.getZ() - r + iz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) { h[ix * size + iz] = Integer.MIN_VALUE; continue; }
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
                h[ix * size + iz] = top - floor >= 2 ? Integer.MIN_VALUE : top;
            }
        }
        int start = r * size + r;
        if (h[start] == Integer.MIN_VALUE) return 0;
        boolean[] seen = new boolean[size * size];
        int[] queue = new int[size * size];
        int head = 0, tail = 0;
        seen[start] = true;
        queue[tail++] = start;
        while (head < tail) {
            int i = queue[head++];
            int ix = i / size, iz = i % size, hi = h[i];
            for (int k = 0; k < 4; k++) {
                int nx = ix + (k == 0 ? 1 : k == 1 ? -1 : 0);
                int nz = iz + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                int n = nx * size + nz;
                if (seen[n] || h[n] == Integer.MIN_VALUE || Math.abs(h[n] - hi) > 1) continue;
                seen[n] = true;
                queue[tail++] = n;
            }
        }
        return tail;
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
                        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
                        int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, lx, lz);
                        // Water deeper than a wade is not walked: the pathfinder swims a folk across a
                        // pond and it cannot climb out up the far bank (the mountain map's farmers).
                        h[ix * SIZE + iz] = top - floor >= 2 ? Integer.MIN_VALUE : top;
                    }
                }
            }
        }
        char[] dist = new char[SIZE * SIZE];
        short[] feet = new short[SIZE * SIZE];
        int[] queue = new int[SIZE * SIZE];
        int head = 0, tail = 0;
        // Out from the heart's own ground: the square round it at the heart's own level — not the
        // foot of the cliff beside it, which a folk can jump down to and never climb back from.
        for (int pass = 0; pass < 2 && tail == 0; pass++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    int i = (R + dx) * SIZE + (R + dz);
                    if (h[i] == Integer.MIN_VALUE || dist[i] != 0) continue;
                    if (pass == 0 && Math.abs(h[i] + 1 - heart.getY()) > 2) continue;
                    dist[i] = 1;
                    queue[tail++] = i;
                }
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int ix = i / SIZE, iz = i % SIZE, hi = h[i];
            feet[i] = (short) (hi + 1);
            char next = (char) Math.min(Character.MAX_VALUE, dist[i] + 1);
            for (int k = 0; k < 4; k++) {
                int nx = ix + (k == 0 ? 1 : k == 1 ? -1 : 0);
                int nz = iz + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) continue;
                int n = nx * SIZE + nz;
                if (dist[n] != 0 || h[n] == Integer.MIN_VALUE || Math.abs(h[n] - hi) > 1) continue;
                dist[n] = next;
                queue[tail++] = n;
            }
        }
        return new Reach(heart.immutable(), now, dist, feet, tail);
    }
}
