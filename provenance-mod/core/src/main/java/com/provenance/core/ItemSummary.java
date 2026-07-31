package com.provenance.core;

/**
 * The handful of values a tooltip needs, small enough to ride along on the item.
 *
 * <p>The client holds only an opaque record id, so without this it can display
 * nothing but "press H". Most players never open the history screen but see
 * tooltips constantly, so this is the majority of the feature's visible surface.
 *
 * <p>Kept deliberately tiny — a tier index, a name and one number. The full
 * record, contributors included, stays server-side. Nothing here is trusted on
 * the way back: the server never reads a summary off a stack, it only writes
 * one, so a client editing it changes what that player sees and nothing more.
 *
 * @param tierIndex        earned milestone tier, or -1 for none
 * @param crafterName      maker's display name, or empty when unknown
 * @param primaryStatId    the statistic this category leads with
 * @param primaryStatValue its overall value at the last refresh
 */
public record ItemSummary(int tierIndex, String crafterName, String primaryStatId, long primaryStatValue) {

    public static final ItemSummary EMPTY = new ItemSummary(-1, "", "", 0L);

    /** Builds the summary a record would currently display. */
    public static ItemSummary of(ItemRecord record, MilestoneTrack track) {
        MilestoneTier tier = record.currentTier();
        return new ItemSummary(
                tier == null ? -1 : tier.index(),
                record.crafterDisplayName(),
                track.primaryStat().id(),
                record.overall().getOrZero(track.primaryStat()));
    }

    /**
     * True when this differs from {@code other} in a way worth re-syncing.
     *
     * <p>Used to keep the summary off the hot path: it is refreshed on a slow
     * interval and only written to the stack when it actually changed, so a
     * stationary item costs nothing.
     */
    public boolean differsFrom(ItemSummary other) {
        if (other == null) {
            return true;
        }
        return tierIndex != other.tierIndex
                || primaryStatValue != other.primaryStatValue
                || !primaryStatId.equals(other.primaryStatId)
                || !crafterName.equals(other.crafterName);
    }
}
