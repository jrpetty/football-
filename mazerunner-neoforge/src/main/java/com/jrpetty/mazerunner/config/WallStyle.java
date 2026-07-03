package com.jrpetty.mazerunner.config;

/**
 * The look of the maze walls — pure logic, no Minecraft classes.
 *
 * <p>Vertical weathering gradient, dark at the base to light at the top,
 * through six materials: polished tuff → tuff → polished andesite → mossy
 * stone bricks → stone bricks → andesite. Band boundaries wander per column
 * and blocks jitter across neighbouring bands, so the decay reads as natural
 * while the overall tone stays constant everywhere in the maze.
 *
 * <p>Also decides the greenery: vine runs and mangrove-leaf moss clumps on
 * wall faces — noticeable, never a full coat.
 */
public final class WallStyle {

    /** Number of palette bands (index 0 = darkest/bottom, 5 = lightest/top). */
    public static final int BANDS = 6;

    private WallStyle() {}

    /**
     * Palette band for a wall block, 0..5.
     *
     * @param y      block Y
     * @param baseY  bottom of the wall (first wall block)
     * @param topY   top of the wall (last wall block, inclusive)
     */
    public static int bandAt(int x, int y, int z, int baseY, int topY) {
        double u = (y - baseY) / (double) Math.max(1, topY - baseY); // 0..1 up the wall
        double columnWander = (Noise.value2(x, z, 9, 0xBA57) - 0.5) * 1.8;   // band thickness varies
        double blockJitter = (Noise.hash3(x, y, z, 0x3E77) - 0.5) * 1.1;     // speckled boundaries
        int band = (int) Math.floor(u * BANDS + columnWander + blockJitter);
        return Math.max(0, Math.min(BANDS - 1, band));
    }

    // ------------------------------------------------------------- greenery

    /**
     * Vine run on one wall face-column: returns null for bare, else
     * {startOffset, length} measured up from the wall base. Roughly one face
     * column in five carries a run, at varying heights and lengths.
     */
    public static int[] vineRun(int wallX, int wallZ, int face) {
        double roll = Noise.hash2(wallX * 4 + face, wallZ * 4 - face, 0x71E5);
        if (roll > 0.22) return null;
        int start = (int) (Noise.hash2(wallX + face, wallZ, 0xA1B2) * 22);       // 0..21 up
        int length = 3 + (int) (Noise.hash2(wallX - face, wallZ + 7, 0xC3D4) * 12); // 3..14
        return new int[] { start, length };
    }

    /**
     * Mangrove-leaf moss clump on one wall face-column: returns null or
     * {startOffset, height(1..3)}. Sparse — a few percent of faces.
     */
    public static int[] mossClump(int wallX, int wallZ, int face) {
        double roll = Noise.hash2(wallX * 9 + face * 3, wallZ * 9 + face, 0x9055);
        if (roll > 0.045) return null;
        int start = 1 + (int) (Noise.hash2(wallX, wallZ + face, 0xE1F2) * 12); // low-ish on the wall
        int height = 1 + (int) (Noise.hash2(wallX + 3, wallZ - 5, 0x0BB0) * 3); // 1..3
        return new int[] { start, height };
    }
}
