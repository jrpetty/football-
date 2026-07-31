package com.jrpetty.mazerunner.config;

/**
 * The Glade's natural terrain — pure geometry, no Minecraft classes.
 *
 * <p>All positions derive from the config's Glade block bounds, so the terrain
 * scales automatically with the play cell size. Features (rolling elevation, a
 * corner lake with a mixed bed, a forest quarter, foliage) are expressed as
 * fractions of the Glade span so they stay proportionate when the map shrinks.
 */
public final class GladeTerrain {

    public static final int MAX_RAISE = 3;      // gentle hills (scaled down for the minigame)
    public static final int LAKE_MAX_DEPTH = 4;

    /** Bed materials. */
    public static final int BED_DIRT = 0, BED_SAND = 1, BED_CLAY = 2, BED_GRAVEL = 3;
    public static final int OAK = 0, BIRCH = 1, DARK_OAK = 2, APPLE_OAK = 3;

    private static int cachedMin = Integer.MIN_VALUE, cachedMax, cachedCenter, cachedSpan;

    private GladeTerrain() {}

    private static void ensure() {
        if (cachedMin != Integer.MIN_VALUE) return;
        MazeConfigData cfg = MazeConfigs.get();
        cachedMin = cfg.gladeBlockMin;
        cachedMax = cfg.gladeBlockMax;
        cachedCenter = (cachedMin + cachedMax) / 2;
        cachedSpan = cachedMax - cachedMin + 1;
    }

    public static int min() { ensure(); return cachedMin; }
    public static int max() { ensure(); return cachedMax; }
    public static int center() { ensure(); return cachedCenter; }
    public static int span() { ensure(); return cachedSpan; }

    public static boolean inGlade(int x, int z) {
        ensure();
        return x >= cachedMin && x <= cachedMax && z >= cachedMin && z <= cachedMax;
    }

    // ------------------------------------------------------------- lake (SW corner)

    private static double lakeCX() { return min() + span() * 0.26; }
    private static double lakeCZ() { return min() + span() * 0.76; }
    private static double lakeRX() { return span() * 0.20; }
    private static double lakeRZ() { return span() * 0.16; }

    /** Noise-warped radial field; ≤1 is water (lobed, natural shoreline). */
    public static double lakeField(int x, int z) {
        double dx = (x - lakeCX()) / lakeRX();
        double dz = (z - lakeCZ()) / lakeRZ();
        double base = Math.sqrt(dx * dx + dz * dz);
        double warp = (Noise.fbm2(x, z, Math.max(6, span() * 0.09), 0x1A4E) - 0.5) * 0.55;
        return base + warp;
    }

    public static boolean inLake(int x, int z) {
        return lakeField(x, z) <= 1.0;
    }

    public static int lakeDepth(int x, int z) {
        double f = lakeField(x, z);
        if (f > 1.0) return 0;
        double basin = Math.pow(1.0 - f, 0.75);
        double lumps = (Noise.fbm2(x, z, Math.max(4, span() * 0.06), 0x0DEE) - 0.5) * 0.9;
        double d = basin * LAKE_MAX_DEPTH + lumps * LAKE_MAX_DEPTH * 0.35;
        return Math.max(1, Math.min(LAKE_MAX_DEPTH, (int) Math.round(d)));
    }

    public static int bedMaterial(int x, int z) {
        double n = Noise.fbm2(x, z, Math.max(4, span() * 0.05), 0x5A9D);
        double g = Noise.value2(x, z, Math.max(3, span() * 0.03), 0x6AC0);
        boolean deep = lakeField(x, z) < 0.55;
        if (g > 0.80) return BED_GRAVEL;
        if (deep && n > 0.62) return BED_CLAY;
        if (n < 0.42) return BED_SAND;
        return BED_DIRT;
    }

    public static boolean isSandy(int x, int z) {
        double f = lakeField(x, z);
        if (f > 1.28) return false;
        if (f > 1.0) return Noise.value2(x, z, Math.max(4, span() * 0.04), 0x5A9D) > 0.35;
        return bedMaterial(x, z) == BED_SAND;
    }

    // ------------------------------------------------------------- forest (NE quarter)

    private static double forestCX() { return min() + span() * 0.74; }
    private static double forestCZ() { return min() + span() * 0.26; }
    private static double forestR() { return span() * 0.36; }

    public static boolean inForest(int x, int z) {
        double dx = x - forestCX();
        double dz = z - forestCZ();
        double r = Math.sqrt(dx * dx + dz * dz) / forestR();
        if (r > 1.15) return false;
        double n = Noise.fbm2(x, z, Math.max(6, span() * 0.11), 0xF03E57);
        return n > 0.16 + r * 0.38;
    }

    // ------------------------------------------------------------- elevation

    public static int heightAt(int x, int z) {
        if (!inGlade(x, z)) return 0;
        double s = span();
        double n = Noise.value2(x, z, Math.max(10, s * 0.22), 0x6E1A11) * 0.62
            + Noise.value2(x, z, Math.max(6, s * 0.12), 0x2C71) * 0.38;
        double h = Math.max(0, (n - 0.18) / 0.64) * MAX_RAISE;

        int edge = Math.min(Math.min(x - min(), max() - x), Math.min(z - min(), max() - z));
        double feather = Math.max(4, s * 0.09);
        if (edge < feather) h *= edge / feather;

        double cd = Math.max(Math.abs(x - center()), Math.abs(z - center()));
        if (cd < 4) h = 0;
        else if (cd < 10) h *= (cd - 4) / 6.0;

        double lf = lakeField(x, z);
        if (lf <= 1.0) h = 0;
        else if (lf < 1.6) h *= (lf - 1.0) / 0.6;

        return Math.max(0, Math.min(MAX_RAISE, (int) Math.round(h)));
    }

    // ------------------------------------------------------------- foliage

    public static int flowerAt(int x, int z) {
        if (Noise.value2(x, z, Math.max(5, span() * 0.06), 0x40F7) < 0.62) return 0;
        double d = Noise.hash2(x, z, 0x77AA);
        if (d > 0.30) return 0;
        return 1 + (int) (Noise.hash2(x, z, 0x1234) * 5) % 5;
    }

    public static boolean grassAt(int x, int z, boolean forest) {
        return Noise.hash2(x, z, 0x9C3B) < (forest ? 0.24 : 0.12);
    }

    public static boolean tallGrassAt(int x, int z) {
        return Noise.hash2(x, z, 0xD00D) < 0.02;
    }

    // ------------------------------------------------------------- trees

    /** Trunk lattice at ~5-block spacing inside the forest mask. */
    public static boolean isTrunkSite(int x, int z) {
        if (Math.floorMod(x, 5) != 2 || Math.floorMod(z, 5) != 2) return false;
        if (!inForest(x, z)) return false;
        if (inLake(x, z) || lakeField(x, z) < 1.3) return false;
        return Noise.hash2(x * 7 + 1, z * 7 + 3, 0x7EE5) < 0.6;
    }

    /** Mixed species — mostly per-tree with a mild regional lean. */
    public static int speciesAt(int x, int z) {
        double perTree = Noise.hash2(x, z, 0xA997);
        double lean = (Noise.value2(x, z, Math.max(6, span() * 0.09), 0x5EED5) - 0.5) * 0.22;
        double v = perTree + lean;
        if (v < 0.40) return OAK;
        if (v < 0.62) return BIRCH;
        if (v < 0.85) return DARK_OAK;
        return APPLE_OAK;
    }

    public static final int SIZE_SMALL = 0, SIZE_MEDIUM = 1, SIZE_LARGE = 2;

    /**
     * Tree size tier (small / medium / large). A per-tree roll blended with a
     * gentle regional lean, so heights vary tree-to-tree but still cluster into
     * natural stands of taller and shorter woods rather than one uniform size.
     */
    public static int treeSize(int x, int z) {
        double perTree = Noise.hash2(x + 5, z - 7, 0x51AB);
        double lean = (Noise.value2(x, z, Math.max(6, span() * 0.10), 0x51EE) - 0.5) * 0.30;
        double v = perTree + lean;
        if (v < 0.46) return SIZE_SMALL;
        if (v < 0.80) return SIZE_MEDIUM;
        return SIZE_LARGE;
    }

    /** Trunk height by species and size tier, plus a little per-tree jitter. */
    public static int trunkHeight(int x, int z) {
        int base = switch (speciesAt(x, z)) {
            case BIRCH -> 5;      // birches run tall and slim
            case APPLE_OAK -> 5;
            case DARK_OAK -> 4;   // dark oaks stay stout under a broad crown
            default -> 4;         // OAK
        };
        int jitter = (int) (Noise.hash2(x, z * 3 + 1, 0x71EE) * 2); // +0..1
        return base + treeSize(x, z) + jitter; // small→large adds another 0..2
    }
}
