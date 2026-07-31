package com.provenance.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance tests 14 and 28: the Contributors tab. */
class ContributorQueryTest {

    private RecordRegistry registry(Path dir) {
        return new RecordRegistry(new FileRecordStore(dir), ProvenanceConfig.withDefaults());
    }

    @Test
    void rowsSortByPrimaryStatisticInBothDirections(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID james = UUID.randomUUID();
        UUID jon = UUID.randomUUID();
        UUID steve = UUID.randomUUID();

        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, james, "James", null, null);
        registry.record(pick, james, "James", StatKey.BLOCKS_MINED, 131_395);
        registry.record(pick, jon, "Jon", StatKey.BLOCKS_MINED, 43_207);
        registry.record(pick, steve, "Steve", StatKey.BLOCKS_MINED, 8_940);
        pick.setOwner(jon, "Jon");

        ContributorQuery.Page descending = ContributorQuery.page(
                pick, StatKey.BLOCKS_MINED, StatKey.ORES_MINED, null, true, 0, 25);
        assertEquals(List.of("James", "Jon", "Steve"),
                descending.rows().stream().map(ContributorQuery.Row::name).toList());

        ContributorQuery.Page ascending = ContributorQuery.page(
                pick, StatKey.BLOCKS_MINED, StatKey.ORES_MINED, null, false, 0, 25);
        assertEquals(List.of("Steve", "Jon", "James"),
                ascending.rows().stream().map(ContributorQuery.Row::name).toList());
    }

    @Test
    void theOwnerAndTheMakerAreFlaggedForHighlighting(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID james = UUID.randomUUID();
        UUID jon = UUID.randomUUID();

        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, james, "James", null, null);
        registry.record(pick, james, "James", StatKey.BLOCKS_MINED, 100);
        registry.record(pick, jon, "Jon", StatKey.BLOCKS_MINED, 50);
        pick.setOwner(jon, "Jon");

        ContributorQuery.Page page = ContributorQuery.page(
                pick, StatKey.BLOCKS_MINED, StatKey.ORES_MINED, null, true, 0, 25);

        ContributorQuery.Row maker = page.rows().stream().filter(r -> r.name().equals("James")).findFirst().orElseThrow();
        ContributorQuery.Row owner = page.rows().stream().filter(r -> r.name().equals("Jon")).findFirst().orElseThrow();

        assertTrue(maker.isOriginalCrafter());
        assertFalse(maker.isCurrentOwner());
        assertTrue(owner.isCurrentOwner());
        assertFalse(owner.isOriginalCrafter());
    }

    @Test
    void searchFiltersByName(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID a = UUID.randomUUID();
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, a, "A", null, null);

        registry.record(pick, a, "Alexander", StatKey.BLOCKS_MINED, 10);
        registry.record(pick, UUID.randomUUID(), "Bartholomew", StatKey.BLOCKS_MINED, 20);
        registry.record(pick, UUID.randomUUID(), "alexandra", StatKey.BLOCKS_MINED, 30);

        ContributorQuery.Page page = ContributorQuery.page(
                pick, StatKey.BLOCKS_MINED, null, "alex", true, 0, 25);

        assertEquals(2, page.totalRows(), "search must be case-insensitive");
        assertEquals(List.of("alexandra", "Alexander"),
                page.rows().stream().map(ContributorQuery.Row::name).toList());
    }

    /**
     * Acceptance test 28. A relic that has passed through a whole server can
     * have thousands of contributors; paging must stay exact and must never
     * repeat or drop a row.
     */
    @Test
    void largeContributorListsPageWithoutRepeatingOrDroppingRows(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, UUID.randomUUID(), "Maker", null, null);

        int contributors = 5_000;
        for (int i = 0; i < contributors; i++) {
            // Deliberately many ties, which is where naive paging breaks.
            registry.record(pick, UUID.randomUUID(), "Player" + i, StatKey.BLOCKS_MINED, (i % 10) + 1);
        }

        int pageSize = 25;
        Set<UUID> seen = new HashSet<>();
        List<Long> values = new ArrayList<>();

        ContributorQuery.Page first = ContributorQuery.page(
                pick, StatKey.BLOCKS_MINED, StatKey.ORES_MINED, null, true, 0, pageSize);
        assertEquals(contributors, first.totalRows());
        assertEquals((contributors + pageSize - 1) / pageSize, first.pageCount());

        for (int p = 0; p < first.pageCount(); p++) {
            ContributorQuery.Page page = ContributorQuery.page(
                    pick, StatKey.BLOCKS_MINED, StatKey.ORES_MINED, null, true, p, pageSize);
            for (ContributorQuery.Row row : page.rows()) {
                assertTrue(seen.add(row.playerId()), "a contributor appeared on two pages");
                values.add(row.primaryValue());
            }
        }

        assertEquals(contributors, seen.size(), "paging dropped contributors");
        for (int i = 1; i < values.size(); i++) {
            assertTrue(values.get(i - 1) >= values.get(i), "descending order broke across a page boundary");
        }
    }

    @Test
    void outOfRangePagesClampInsteadOfThrowing(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, UUID.randomUUID(), "Maker", null, null);
        registry.record(pick, UUID.randomUUID(), "Solo", StatKey.BLOCKS_MINED, 5);

        ContributorQuery.Page page = ContributorQuery.page(
                pick, StatKey.BLOCKS_MINED, null, null, true, 999, 25);

        assertEquals(0, page.pageIndex());
        assertEquals(1, page.rows().size());
    }

    @Test
    void anItemNobodyHasUsedYieldsAnEmptyPage(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, UUID.randomUUID(), "Maker", null, null);

        ContributorQuery.Page page = ContributorQuery.page(
                pick, StatKey.BLOCKS_MINED, null, null, true, 0, 25);

        assertEquals(0, page.totalRows());
        assertTrue(page.rows().isEmpty());
        assertEquals(1, page.pageCount());
    }

    @Test
    void everyCategoryHasASensibleSecondaryColumn() {
        for (ItemCategory category : ItemCategory.values()) {
            assertTrue(ContributorQuery.secondaryFor(category) != null, "no secondary column for " + category);
        }
        assertEquals(StatKey.ORES_MINED, ContributorQuery.secondaryFor(ItemCategory.PICKAXE));
        assertEquals(StatKey.DAMAGE_DEALT, ContributorQuery.secondaryFor(ItemCategory.MELEE_WEAPON));
        assertEquals(StatKey.HITS_RECEIVED, ContributorQuery.secondaryFor(ItemCategory.ARMOUR));
        assertEquals(StatKey.TIME_WORN_TICKS, ContributorQuery.timeKeyFor(ItemCategory.ARMOUR));
        assertNull(ContributorQuery.timeKeyFor(ItemCategory.PICKAXE),
                "only armour records time; a held tool no longer accrues it");
    }
}
