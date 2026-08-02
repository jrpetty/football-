package dev.structint.core;

/**
 * Hydrostatic load arithmetic, in "blocks of head" units.
 *
 * <p>Pure maths, no world access, so it can be reasoned about and tested on its own. One unit of
 * load is the push exerted by one block of water depth; one unit of resistance is one block of
 * head a single block of that material can hold back unbraced.
 */
public final class HydroLoad {

    /** Returned by {@link #runCapacity} when the load path reaches natural ground. */
    public static final int ANCHORED = Integer.MAX_VALUE;

    private HydroLoad() {
    }

    /**
     * Load on a face with {@code head} blocks of water above it.
     *
     * <p>Real hydrostatic pressure is p = rho*g*h and depends on depth alone. {@code bodyFactor}
     * is a deliberate gameplay term on top of that, standing in for the stored energy of a large
     * reservoir - see {@link #bodyFactor}.
     */
    public static double load(int head, double pressurePerBlock, double bodyFactor) {
        if (head <= 0) return 0.0;
        return head * pressurePerBlock * bodyFactor;
    }

    /**
     * Scales load by how much water is actually behind the wall.
     *
     * <p>Not physics: a pond and an ocean of the same depth push equally hard per block. This is
     * here because a big reservoir keeps pushing after the first block goes, and because a long
     * dam carries proportionally more total force. Doubling the sampled volume adds
     * {@code influence} to the factor; the result is clamped to [1, max].
     */
    public static double bodyFactor(int sampledVolume, int reference, double influence, double max) {
        if (sampledVolume <= 0 || reference <= 0 || influence <= 0.0) return 1.0;
        double doublings = Math.log((double) sampledVolume / reference) / Math.log(2.0);
        if (doublings <= 0.0) return 1.0;
        return Math.min(max, 1.0 + influence * doublings);
    }

    /**
     * Capacity gained from a buttress or thicker section to the side.
     *
     * <p>A slab spanning between buttresses borrows from them, less so the further away they are.
     * Only the surplus over the block's own run counts, so a uniform wall shares nothing.
     */
    public static double bracingBonus(int ownCapacity, int neighbourCapacity, int distance, double share) {
        if (neighbourCapacity <= ownCapacity || distance < 1 || share <= 0.0) return 0.0;
        long surplus = (long) neighbourCapacity - ownCapacity;
        return share * surplus / distance;
    }

    /**
     * Arch action. A wall that bows into the water throws its thrust sideways into the abutments,
     * which is why real arch dams get away with being thin. {@code setback} is how far the
     * flanking wall sits behind this block, in blocks, taken as the smaller of the two sides.
     */
    public static double archMultiplier(int setback, double gain, int maxSetback) {
        if (setback <= 0 || gain <= 0.0) return 1.0;
        return 1.0 + gain * Math.min(setback, maxSetback);
    }

    /** Fraction of capacity used. {@code >= 1} fails; {@code capacity <= 0} is always failure. */
    public static double stress(double load, double capacity) {
        if (load <= 0.0) return 0.0;
        if (capacity <= 0.0) return Double.POSITIVE_INFINITY;
        return load / capacity;
    }
}
