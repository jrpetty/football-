package com.jrpetty.mazerunner.config;

/**
 * The Glade's natural terrain — pure geometry, no Minecraft classes.
 *
 * <p>Gentle flowing elevation up to +5 blocks over the flat maze floor,
 * feathered to zero at the Glade walls (so the ring corridor and doors meet
 * clean ground), flattened around the central elevator, a natural lake in the
 * southwest corner with sandy shallows, a forest blob covering roughly a
 * quarter of the area in the northeast, and foliage masks for the rest.
 */
public final class GladeTerrain {

    // Glade spans blocks 640..895 on both axes; centre (768, 768).
    public static final int MIN = 640;
    public static final int MAX = 895;
    public static final int CENTER = 768;
    public static final int MAX_RAISE = 8;

    // Lake — southwest corner. Deeper and irregular (noise-warped shoreline).
    public static final double LAKE_CX = 698;
    public static final double LAKE_CZ = 840;
    public static final double LAKE_RX = 44;
    public static final double LAKE_RZ = 33;
    public static final int LAKE_MAX_DEPTH = 11;

    /** Bed materials. */
    public static final int BED_DIRT = 0, BED_SAND = 1, BED_CLAY = 2, BED_GRAVEL = 3;

    // Forest — a blob centred in the northeast quadrant (~1/4 of the Glade).
    public static final double FOREST_CX = 838;
    public static final double FOREST_CZ = 700;
    public static final double FOREST_R = 90;

    private GladeTerrain() {}

    public static boolean inGlade(int x, int z) {
        return x >= MIN && x <= MAX && z >= MIN && z <= MAX;
    }

    /**
     * Normalised radial field warped by low-frequency noise so the shoreline
     * is lobed and natural rather than a clean ellipse. ≤1 is water.
     */
    public static double lakeField(int x, int z) {
        double dx = (x - LAKE_CX) / LAKE_RX;
        double dz = (z - LAKE_CZ) / LAKE_RZ;
        double base = Math.sqrt(dx * dx + dz * dz);
        double warp = (Noise.fbm2(x, z, 22, 0x1A4E) - 0.5) * 0.55; // in-out bays and points
        return base + warp;
    }

    public static boolean inLake(int x, int z) {
        return lakeField(x, z) <= 1.0;
    }

    /** Water depth (0..6) — a warped basin, deepest off-centre, shelving to shore. */
    public static int lakeDepth(int x, int z) {
        double f = lakeField(x, z);
        if (f > 1.0) return 0;
        double basin = Math.pow(1.0 - f, 0.75);                       // steep drop near shore
        double lumps = (Noise.fbm2(x, z, 14, 0x0DEE) - 0.5) * 0.9;    // uneven bed
        double d = basin * LAKE_MAX_DEPTH + lumps * LAKE_MAX_DEPTH * 0.35;
        return Math.max(1, Math.min(LAKE_MAX_DEPTH, (int) Math.round(d)));
    }

    /** Bed material at a lake/shore column: mixed clay, dirt, sand and gravel. */
    public static int bedMaterial(int x, int z) {
        double n = Noise.fbm2(x, z, 11, 0x5A9D);
        double g = Noise.value2(x, z, 6, 0x6AC0);
        boolean deep = lakeField(x, z) < 0.55;
        if (g > 0.80) return BED_GRAVEL;            // scattered gravel patches
        if (deep && n > 0.62) return BED_CLAY;      // clay in the deep middle
        if (n < 0.42) return BED_SAND;              // sandy shallows/shore
        return BED_DIRT;                            // muddy dirt elsewhere
    }

    /** Sand on the shore band and sandy bed cells. */
    public static boolean isSandy(int x, int z) {
        double f = lakeField(x, z);
        if (f > 1.28) return false;
        if (f > 1.0) return Noise.value2(x, z, 7, 0x5A9D) > 0.35; // beach ring
        return bedMaterial(x, z) == BED_SAND;
    }

    /** Forest membership — a noise-eroded blob, denser toward the middle. */
    public static boolean inForest(int x, int z) {
        double dx = x - FOREST_CX;
        double dz = z - FOREST_CZ;
        double r = Math.sqrt(dx * dx + dz * dz) / FOREST_R;
        if (r > 1.15) return false;
        double n = Noise.fbm2(x, z, 26, 0xF03E57);
        return n > 0.16 + r * 0.38; // erode edges, keep the core solid
    }

    /**
     * Ground raise above the flat floor (0..5), smooth and flowing.
     * Zero at the Glade edges, around the elevator, and across the lake.
     */
    public static int heightAt(int x, int z) {
        if (!inGlade(x, z)) return 0;

        // Two broad, low-frequency octaves so hills reach up to +8 in places
        // while elevation still never steps by more than 1 block per cell.
        double n = Noise.value2(x, z, 52, 0x6E1A11) * 0.62 + Noise.value2(x, z, 28, 0x2C71) * 0.38;
        // Slight overshoot so the tallest hills clip to the +8 cap (see clamp
        // below); still low-frequency, so the surface never steps by >1 block.
        double h = Math.max(0, (n - 0.18) / 0.64) * MAX_RAISE;

        // feather to zero at the walls so corridors and doors meet flat ground
        int edge = Math.min(Math.min(x - MIN, MAX - x), Math.min(z - MIN, MAX - z));
        if (edge < 10) h *= edge / 10.0;

        // flat pad around the elevator spawn
        double cd = Math.max(Math.abs(x - CENTER), Math.abs(z - CENTER));
        if (cd < 16) h = 0;
        else if (cd < 26) h *= (cd - 16) / 10.0;

        // the lake basin and its banks sit low
        double lf = lakeField(x, z);
        if (lf <= 1.0) h = 0;
        else if (lf < 1.6) h *= (lf - 1.0) / 0.6;

        return Math.max(0, Math.min(MAX_RAISE, (int) Math.round(h)));
    }

    // ------------------------------------------------------------- foliage

    /** Flower patches (clustered, not uniform sprinkle). 0 = none, 1..5 = species. */
    public static int flowerAt(int x, int z) {
        if (Noise.value2(x, z, 13, 0x40F7) < 0.62) return 0;   // outside a patch
        double d = Noise.hash2(x, z, 0x77AA);
        if (d > 0.30) return 0;                                 // density inside patch
        return 1 + (int) (Noise.hash2(x, z, 0x1234) * 5) % 5;   // species per position
    }

    /** Grass/fern ground cover chance (denser in the forest). */
    public static boolean grassAt(int x, int z, boolean forest) {
        return Noise.hash2(x, z, 0x9C3B) < (forest ? 0.24 : 0.12);
    }

    /** Occasional double-height grass in the open meadow. */
    public static boolean tallGrassAt(int x, int z) {
        return Noise.hash2(x, z, 0xD00D) < 0.02;
    }

    // ------------------------------------------------------------- trees

    /** Tree trunks sit on a jittered 6-block lattice inside the forest mask. */
    public static boolean isTrunkSite(int x, int z) {
        if (Math.floorMod(x, 6) != 3 || Math.floorMod(z, 6) != 3) return false;
        if (!inForest(x, z)) return false;
        if (inLake(x, z) || lakeField(x, z) < 1.3) return false;
        return Noise.hash2(x * 7 + 1, z * 7 + 3, 0x7EE5) < 0.55;
    }

    public static final int OAK = 0, BIRCH = 1, DARK_OAK = 2, APPLE_OAK = 3;

    /**
     * Tree species — mostly per-tree so the woods are a natural mix of all
     * four kinds standing side by side, with only a mild regional lean so the
     * mix drifts across the forest instead of being uniform noise.
     */
    public static int speciesAt(int x, int z) {
        double perTree = Noise.hash2(x, z, 0xA997);
        double lean = (Noise.value2(x, z, 24, 0x5EED5) - 0.5) * 0.22; // gentle drift
        double v = perTree + lean;
        if (v < 0.40) return OAK;
        if (v < 0.62) return BIRCH;
        if (v < 0.85) return DARK_OAK;
        return APPLE_OAK;
    }

    public static int trunkHeight(int x, int z) {
        int base = switch (speciesAt(x, z)) {
            case BIRCH -> 6;
            case DARK_OAK -> 6;
            default -> 5;
        };
        return base + (int) (Noise.hash2(x, z * 3 + 1, 0x71EE) * 3); // +0..2
    }
}
