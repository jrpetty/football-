package com.jrpetty.mcassistant.village;

/**
 * [house34b] A building's ground as a box, turned and set in the world. Only arithmetic, like the rest of the plan.
 *
 * <p>A drawing's cells run from its left to its right ({@code dx}, right +) and from its front to its back ({@code dz},
 * back +) about its anchor. One drawn an odd number of blocks across runs as far each way; one drawn an even number runs
 * a block further to its right than to its left (twenty-two across: dx -10 to +11). A half taken each way and turned
 * (the old way every building's ground was reckoned) then held a column past its left end that was nobody's: the street
 * beside a row of houses was refused there, and the lamps, fences and paths kept off it. Here the box is the drawing's
 * own, {least dx, most dx, least dz, most dz}, turned with its back toward one of the plan's directions.
 */
public final class Extents {

    private Extents() {}

    /** Step across a building (its right) and back through it, in world x and z, for a back toward {@code back}
     *  (TownPlan.NORTH...): {right x, right z, back x, back z}. Its right is its back turned clockwise. */
    public static int[] axes(int back) {
        return switch (back) {
            case TownPlan.NORTH -> new int[]{ 1, 0, 0, -1 };
            case TownPlan.EAST -> new int[]{ 0, 1, 1, 0 };
            case TownPlan.SOUTH -> new int[]{ -1, 0, 0, 1 };
            default -> new int[]{ 0, -1, -1, 0 };
        };
    }

    /** A box in a building's own terms {least dx, most dx, least dz, most dz}, turned and set at an anchor:
     *  {minX, maxX, minZ, maxZ} in the world. */
    public static int[] turn(int[] e, int ax, int az, int back) {
        int[] a = axes(back);
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (int dx : new int[]{ e[0], e[1] }) {
            for (int dz : new int[]{ e[2], e[3] }) {
                int x = ax + dx * a[0] + dz * a[2], z = az + dx * a[1] + dz * a[3];
                x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z);
            }
        }
        return new int[]{ x0, x1, z0, z1 };
    }

    /** The old reckoning, a half each way ({@code max |dx|}, {@code max |dz|}) turned: for comparison. */
    public static int[] halves(int[] e, int ax, int az, int back) {
        int hx = Math.max(-e[0], e[1]), hz = Math.max(-e[2], e[3]);
        boolean turned = back == TownPlan.EAST || back == TownPlan.WEST;
        int wx = turned ? hz : hx, wz = turned ? hx : hz;
        return new int[]{ ax - wx, ax + wx, az - wz, az + wz };
    }
}
