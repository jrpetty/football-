package com.jrpetty.mazerunner.config;

/**
 * Vanilla-tree crown geometry — pure maths, no Minecraft classes, so the shape
 * of the Glade forest can be unit-tested directly. {@code gen.TreePlacer} does
 * nothing but map these numbers onto real oak/birch/dark-oak blocks.
 *
 * <p>Silhouettes differ by species so a mixed forest reads naturally: birches
 * are tall and slim, dark oaks broad and low, oaks in between. Each tree also
 * carries a small/medium/large size tier (see {@link GladeTerrain#treeSize}).
 */
public final class TreeShape {

    /** Largest horizontal crown radius any species/size produces. */
    public static final int MAX_RADIUS = 3;

    private TreeShape() {}

    /** Horizontal crown radius: birch narrow, dark oak broad, oak between. */
    public static int horizontalRadius(int species, int size) {
        return switch (species) {
            case GladeTerrain.BIRCH -> 1 + Math.min(1, size);       // 1, 2, 2
            case GladeTerrain.DARK_OAK -> 2 + (size >= 1 ? 1 : 0);  // 2, 3, 3
            default -> 2 + (size >= 2 ? 1 : 0);                     // oak/apple 2, 2, 3
        };
    }

    /** Vertical crown radius: birch tall, dark oak low, oak between. */
    public static int verticalRadius(int species, int size) {
        return switch (species) {
            case GladeTerrain.BIRCH -> 2 + size;                    // 2, 3, 4 (tall, slim)
            case GladeTerrain.DARK_OAK -> 2 + (size >= 2 ? 1 : 0);  // 2, 2, 3 (broad, low)
            default -> 2 + (size >= 1 ? 1 : 0);                     // oak 2, 3, 3
        };
    }

    /** Crown centre sits one block above the top of the trunk. */
    public static int crownCenterY(int groundY, int trunkHeight) {
        return groundY + trunkHeight + 1;
    }

    /** Highest block the crown can occupy — used to keep leaves under the walls. */
    public static int crownTopY(int groundY, int trunkHeight, int species, int size) {
        return crownCenterY(groundY, trunkHeight) + verticalRadius(species, size);
    }

    /**
     * Ellipsoid test for one block of a crown centred on {@code (0, centerY, 0)}
     * relative to the trunk. A small deterministic wobble keyed off the world
     * column stops the silhouette from being a perfect egg.
     */
    public static boolean inCrown(int dx, int dz, int y, int centerY, int rH, int rV,
            int worldX, int worldZ) {
        int horiz = dx * dx + dz * dz;
        if (horiz > rH * rH) return false;
        int dy = y - centerY;
        double h = horiz / (double) (rH * rH);
        double v = (dy * dy) / (double) (rV * rV);
        double wobble = (Noise.hash2(worldX * 3 + y, worldZ * 3 - y, 0x1EAF) - 0.5) * 0.22;
        return h + v <= 1.0 + wobble;
    }
}
