package com.jrpetty.mcassistant.pa;

/**
 * [featpa] A blueprint's turn and mirror as plain sums on its box (no game classes, so the unit test reads them): the
 * footprint once turned, and where a cell lands. Rotations are quarter turns clockwise seen from above; the mirror swaps
 * east and west (x flipped) before the turn. A quarter turn takes a step north (-z) to a step east (+x), as the game's
 * Rotation.CLOCKWISE_90 turns a block's facing, so the states and the cells turn together.
 */
public final class PaTurn {

    private PaTurn() {}

    /** The footprint once turned: {width along x, depth along z}. */
    public static int[] size(int sx, int sz, int rot) {
        return (rot & 1) == 1 ? new int[]{sz, sx} : new int[]{sx, sz};
    }

    /** Where a cell (x, z) of an sx by sz box lands in the turned box. */
    public static int[] map(int x, int z, int sx, int sz, boolean mirror, int rot) {
        int mx = mirror ? sx - 1 - x : x;
        return switch (rot & 3) {
            case 1 -> new int[]{sz - 1 - z, mx};
            case 2 -> new int[]{sx - 1 - mx, sz - 1 - z};
            case 3 -> new int[]{z, sx - 1 - mx};
            default -> new int[]{mx, z};
        };
    }
}
