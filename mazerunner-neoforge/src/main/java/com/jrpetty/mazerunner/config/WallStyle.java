package com.jrpetty.mazerunner.config;

/**
 * The look of the maze walls — pure logic, no Minecraft classes.
 *
 * <p>Two things: the vertical weathering palette (dark base → light top through
 * six materials), and the ivy — cascading strands that hang from the top of
 * clustered patches, tapering at the edges, so faces read like real overgrown
 * stone rather than a uniform coat or a barcode of single stripes.
 */
public final class WallStyle {

    public static final int BANDS = 6;

    /**
     * Bare stone left below the wall crest, in blocks. Vines are climbable and
     * leaf clumps have full collision, so greenery that reached the top would
     * let a runner climb out and walk across the maze to the exit. Nothing
     * climbable is ever placed within this margin of the crest, which — with
     * the build limit sitting below it too — makes the wall tops unreachable.
     */
    public static final int CLIMB_MARGIN = 4;

    private WallStyle() {}

    /** Highest Y any greenery may occupy on a wall face. */
    public static int greeneryTopY(int wallTopY) {
        return wallTopY - CLIMB_MARGIN;
    }

    /** Palette band for a wall block, 0 (dark, base) .. 5 (light, top). */
    public static int bandAt(int x, int y, int z, int baseY, int topY) {
        double u = (y - baseY) / (double) Math.max(1, topY - baseY);
        double columnWander = (Noise.value2(x, z, 9, 0xBA57) - 0.5) * 1.8;
        double blockJitter = (Noise.hash3(x, y, z, 0x3E77) - 0.5) * 1.1;
        int band = (int) Math.floor(u * BANDS + columnWander + blockJitter);
        return Math.max(0, Math.min(BANDS - 1, band));
    }

    // ------------------------------------------------------------- ivy

    /**
     * Vertical ivy span hanging on one wall-face column, or null for bare.
     *
     * @param run  coordinate along the wall face (the horizontal axis)
     * @param line the wall face's fixed coordinate (which wall it is)
     * @return {startY, endY} inclusive block-Y range, or null
     */
    public static int[] ivySpan(int run, int line, int baseY, int topY) {
        // Patch membership: clustered blobs along the wall so ivy comes in
        // clumps with bare stone between them (not evenly spaced stripes).
        double patch = Noise.fbm2(run, line, 12, 0x1B9E);
        if (patch < 0.62) return null;

        // Ivy hangs below the bare crest band — never within reach of the top.
        int crest = greeneryTopY(topY);
        if (crest <= baseY) return null;
        int height = crest - baseY;
        double intensity = (patch - 0.62) / 0.38; // 0 at patch edge → 1 at core

        // Hangs from near the crest band, with the top edge slightly ragged.
        int top = crest - (int) (Noise.hash2(run, line + 7, 0x77E1) * 3);
        // Length grows toward the patch core and jitters per column for taper.
        int len = 2 + (int) (intensity * height * 0.85)
            + (int) ((Noise.hash2(run * 3 + 1, line, 0xC3D4) - 0.5) * 6);
        len = Math.max(2, Math.min(len, height));

        int start = Math.max(baseY, top - len);
        if (top < start) return null;
        return new int[] { start, Math.min(top, crest) };
    }
}
