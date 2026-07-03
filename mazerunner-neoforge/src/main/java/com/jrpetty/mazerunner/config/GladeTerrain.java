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
    public static final int MAX_RAISE = 5;

    // Lake — southwest corner.
    public static final double LAKE_CX = 700;
    public static final double LAKE_CZ = 838;
    public static final double LAKE_RX = 40;
    public static final double LAKE_RZ = 30;

    // Forest — a blob centred in the northeast quadrant (~1/4 of the Glade).
    public static final double FOREST_CX = 838;
    public static final double FOREST_CZ = 700;
    public static final double FOREST_R = 90;

    private GladeTerrain() {}

    public static boolean inGlade(int x, int z) {
        return x >= MIN && x <= MAX && z >= MIN && z <= MAX;
    }

    /** Normalised (0 at centre, 1 at edge) squared radial distance to the lake. */
    public static double lakeField(int x, int z) {
        double dx = (x - LAKE_CX) / LAKE_RX;
        double dz = (z - LAKE_CZ) / LAKE_RZ;
        return dx * dx + dz * dz;
    }

    public static boolean inLake(int x, int z) {
        return lakeField(x, z) <= 1.0;
    }

    /** Water depth (0..3) — deepest at the middle, shelving to the shore. */
    public static int lakeDepth(int x, int z) {
        double f = lakeField(x, z);
        if (f > 1.0) return 0;
        double edgeWobble = (Noise.value2(x, z, 9, 0x1AEE) - 0.5) * 0.25;
        double d = (1.0 - f + edgeWobble) * 3.4;
        return Math.max(0, Math.min(3, (int) Math.round(d)));
    }

    /** Sandy patches: lake bed shallows and the shore band. */
    public static boolean isSandy(int x, int z) {
        double f = lakeField(x, z);
        if (f > 1.35) return false;
        if (f >= 0.75) return Noise.value2(x, z, 7, 0x5A9D) > 0.35; // shore band mostly sand
        return Noise.value2(x, z, 7, 0x5A9D) > 0.55;                // deeper bed part sand
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

        double n = Noise.fbm2(x, z, 34, 0x6E1A11); // broad rolling hills
        double h = Math.max(0, (n - 0.30) / 0.70) * MAX_RAISE;

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

    /** True → birch, false → oak. */
    public static boolean isBirch(int x, int z) {
        return Noise.hash2(x, z, 0xB12C) < 0.3;
    }

    public static int trunkHeight(int x, int z) {
        int base = isBirch(x, z) ? 5 : 4;
        return base + (int) (Noise.hash2(x, z * 3 + 1, 0x71EE) * 3); // 4..7
    }
}
