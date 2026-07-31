package harness;

import java.util.HashMap;
import java.util.Map;

/** Deterministic fake world shared by both engine builds. */
public final class World {
    public static final int MIN_Y = -64, MAX_Y = 320;
    public static final StringBuilder LOG = new StringBuilder();

    /** water amount (1-8) per packed BlockPos; absent == 0 */
    public static final Map<Long, Integer> WATER = new HashMap<>();
    /** highest y holding water per packed column */
    private static final Map<Long, Integer> COL_TOP = new HashMap<>();

    public static boolean raining = true;

    public static void reset() {
        WATER.clear();
        COL_TOP.clear();
        LOG.setLength(0);
        raining = true;
        // a lake basin: solid floor at 55, filled to 60
        for (int x = -30; x <= -6; x++) {
            for (int z = 4; z <= 28; z++) {
                if (!inLake(x, z)) continue;
                for (int y = 56; y <= 60; y++) setWaterRaw(x, y, z, 8);
            }
        }
    }

    static boolean inLake(int x, int z) {
        int dx = x + 18, dz = z - 16;
        return dx * dx + dz * dz <= 144;
    }

    public static int solidTop(int x, int z) {
        if (inLake(x, z)) return 55;
        double h = 64
                + 6 * Math.sin(x * 0.07)
                + 5 * Math.cos(z * 0.05)
                + 3 * Math.sin((x + z) * 0.11)
                + 2 * Math.cos((x - 2 * z) * 0.031);
        return (int) Math.floor(h);
    }

    public static boolean leafTop(int x, int z) { return Math.floorMod(x * 31 + z, 97) == 0; }
    public static boolean sheltered(int x, int z) { return Math.floorMod(x * 17 + z * 13, 89) == 0; }
    public static boolean noRainBiome(int x, int z) { return x >= 100; }
    public static boolean chunkLoaded(int cx, int cz) { return cx >= -8 && cx <= 8 && cz >= -8 && cz <= 8; }

    public static long key(int x, int y, int z) { return net.minecraft.core.BlockPos.asLong(x, y, z); }
    static long col(int x, int z) { return net.minecraft.core.BlockPos.asLong(x, 0, z); }

    public static int water(int x, int y, int z) {
        Integer v = WATER.get(key(x, y, z));
        return v == null ? 0 : v;
    }

    private static void setWaterRaw(int x, int y, int z, int amount) {
        long k = key(x, y, z);
        if (amount <= 0) {
            WATER.remove(k);
            long c = col(x, z);
            Integer top = COL_TOP.get(c);
            if (top != null && top == y) {
                int ny = Integer.MIN_VALUE;
                for (int yy = y - 1; yy > solidTop(x, z); yy--) {
                    if (water(x, yy, z) > 0) { ny = yy; break; }
                }
                if (ny == Integer.MIN_VALUE) COL_TOP.remove(c); else COL_TOP.put(c, ny);
            }
        } else {
            WATER.put(k, amount);
            long c = col(x, z);
            Integer top = COL_TOP.get(c);
            if (top == null || y > top) COL_TOP.put(c, y);
        }
    }

    /** Implements the Flowing Fluids write. Returns true when the level actually changed. */
    public static boolean setWater(int x, int y, int z, int amount) {
        int cur = water(x, y, z);
        boolean changed = cur != amount;
        if (changed) setWaterRaw(x, y, z, amount);
        LOG.append("SET ").append(x).append(' ').append(y).append(' ').append(z)
           .append(" -> ").append(amount).append(" (was ").append(cur).append(", changed=")
           .append(changed).append(")\n");
        return changed;
    }

    /** MOTION_BLOCKING: topmost solid or fluid block in the column. */
    public static int motionBlockingTop(int x, int z) {
        int s = solidTop(x, z);
        Integer w = COL_TOP.get(col(x, z));
        return (w != null && w > s) ? w : s;
    }
}
