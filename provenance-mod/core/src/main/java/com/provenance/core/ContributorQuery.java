package com.provenance.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Search, sort and pagination for the Contributors tab.
 *
 * <p>An item that has passed through a whole server can have a large
 * contributor list, and the screen must stay responsive. Sorting and slicing
 * happen here, server-side, so a client is only ever sent one page.
 */
public final class ContributorQuery {

    /** One row of the Contributors table, already resolved for display. */
    public record Row(UUID playerId, String name, long primaryValue, long secondaryValue,
                      long repairs, long distanceCm, long timeTicks, boolean isCurrentOwner,
                      boolean isOriginalCrafter) {
    }

    /** A page of rows plus the totals needed to render the pager. */
    public record Page(List<Row> rows, int pageIndex, int pageCount, int totalRows) {
    }

    private ContributorQuery() {
    }

    /**
     * Builds a page of contributor rows.
     *
     * @param primary   the statistic the category leads with, e.g. blocks mined
     * @param secondary the supporting statistic, e.g. ores mined
     * @param search    case-insensitive name filter, or null for everyone
     * @param descending sort direction on {@code primary}
     */
    public static Page page(ItemRecord record, StatKey primary, StatKey secondary,
                            String search, boolean descending, int pageIndex, int pageSize) {
        UUID owner = record.currentOwnerId();
        UUID crafter = record.crafterId();
        String needle = search == null ? null : search.trim().toLowerCase(Locale.ROOT);

        List<Row> rows = new ArrayList<>();
        for (Contribution c : record.contributors()) {
            if (needle != null && !needle.isEmpty()
                    && !c.nameSnapshot().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            rows.add(new Row(
                    c.playerId(),
                    c.nameSnapshot(),
                    c.stats().getOrZero(primary),
                    secondary == null ? 0L : c.stats().getOrZero(secondary),
                    c.stats().getOrZero(StatKey.REPAIRS),
                    c.stats().getOrZero(StatKey.DISTANCE_CM),
                    timeKeyFor(record.category()) == null
                            ? 0L
                            : c.stats().getOrZero(timeKeyFor(record.category())),
                    c.playerId().equals(owner),
                    c.playerId().equals(crafter)));
        }

        Comparator<Row> comparator = Comparator.comparingLong(Row::primaryValue);
        if (descending) {
            comparator = comparator.reversed();
        }
        // Stable tiebreak, so paging cannot show the same player twice or skip
        // one when several share a value.
        rows.sort(comparator.thenComparing(Row::name).thenComparing(r -> r.playerId().toString()));

        int size = Math.max(1, pageSize);
        int totalRows = rows.size();
        int pageCount = Math.max(1, (totalRows + size - 1) / size);
        int clampedIndex = Math.max(0, Math.min(pageIndex, pageCount - 1));
        int from = clampedIndex * size;
        int to = Math.min(totalRows, from + size);
        List<Row> slice = from >= totalRows ? List.of() : List.copyOf(rows.subList(from, to));

        return new Page(slice, clampedIndex, pageCount, totalRows);
    }

    /**
     * Armour records time worn. Nothing else records time at all — a held tool
     * accrued "time used" merely for being in a hand, which measured holding
     * rather than using and cost a per-second server pass to collect.
     *
     * @return the time counter for this category, or null if it has none
     */
    public static StatKey timeKeyFor(ItemCategory category) {
        return category == ItemCategory.ARMOUR ? StatKey.TIME_WORN_TICKS : null;
    }

    /** The secondary column each category shows beside its primary statistic. */
    public static StatKey secondaryFor(ItemCategory category) {
        return switch (category) {
            case PICKAXE -> StatKey.ORES_MINED;
            case MELEE_WEAPON, RANGED_WEAPON, TRIDENT -> StatKey.DAMAGE_DEALT;
            case ARMOUR -> StatKey.HITS_RECEIVED;
            case AXE -> StatKey.BLOCKS_CHOPPED;
            // A shovel has no natural second measure, so fall back to a
            // universally recorded one rather than a column of zeroes.
            case SHOVEL -> StatKey.DURABILITY_RESTORED;
            case HOE -> StatKey.CROPS_HARVESTED;
            case FISHING_ROD -> StatKey.TREASURE_CAUGHT;
            case SHEARS, OTHER -> StatKey.DURABILITY_RESTORED;
        };
    }
}
