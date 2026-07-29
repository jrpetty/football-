package com.provenance.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance tests 5, 6, 9 and 10. */
class StatisticsTest {

    private RecordRegistry registry(Path dir) {
        return new RecordRegistry(new FileRecordStore(dir), ProvenanceConfig.withDefaults());
    }

    @Test
    void usingASwordIncrementsBothOverallAndActingPlayer(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID jon = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:netherite_sword", ItemCategory.MELEE_WEAPON, jon, "Jon", null, null);

        for (int i = 0; i < 7; i++) {
            registry.record(sword, jon, "Jon", StatKey.KILLS, 1);
        }

        assertEquals(7, sword.overall().getOrZero(StatKey.KILLS));
        assertEquals(7, sword.contribution(jon).stats().getOrZero(StatKey.KILLS));
        assertTrue(sword.verifyOverallMatchesContributors());
    }

    @Test
    void miningIncrementsBothOverallAndActingPlayer(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID jon = UUID.randomUUID();
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, jon, "Jon", null, null);

        registry.record(pick, jon, "Jon", StatKey.BLOCKS_MINED, 40);
        registry.record(pick, jon, "Jon", StatKey.ORES_MINED, 6);

        assertEquals(40, pick.overall().getOrZero(StatKey.BLOCKS_MINED));
        assertEquals(6, pick.contribution(jon).stats().getOrZero(StatKey.ORES_MINED));
        assertTrue(pick.verifyOverallMatchesContributors());
    }

    /** Acceptance tests 9 and 10. */
    @Test
    void secondPlayerGetsASeparateRecordAndOverallIncludesBoth(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID james = UUID.randomUUID();
        UUID jon = UUID.randomUUID();
        UUID steve = UUID.randomUUID();

        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, james, "James", null, null);

        registry.record(pick, james, "James", StatKey.BLOCKS_MINED, 131_395);
        registry.record(pick, jon, "Jon", StatKey.BLOCKS_MINED, 43_207);
        registry.record(pick, steve, "Steve", StatKey.BLOCKS_MINED, 8_940);

        assertEquals(3, pick.contributorCount());
        assertEquals(131_395, pick.contribution(james).stats().getOrZero(StatKey.BLOCKS_MINED));
        assertEquals(43_207, pick.contribution(jon).stats().getOrZero(StatKey.BLOCKS_MINED));
        assertEquals(8_940, pick.contribution(steve).stats().getOrZero(StatKey.BLOCKS_MINED));
        assertEquals(183_542, pick.overall().getOrZero(StatKey.BLOCKS_MINED));
        assertTrue(pick.verifyOverallMatchesContributors());
    }

    @Test
    void usernameChangeUpdatesTheSnapshotWithoutForkingTheContributor(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID player = UUID.randomUUID();
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "OldName", null, null);

        registry.record(pick, player, "OldName", StatKey.BLOCKS_MINED, 10);
        registry.record(pick, player, "NewName", StatKey.BLOCKS_MINED, 5);

        assertEquals(1, pick.contributorCount(), "a rename must not create a second contributor");
        assertEquals("NewName", pick.contribution(player).nameSnapshot());
        assertEquals(15, pick.contribution(player).stats().getOrZero(StatKey.BLOCKS_MINED));
    }

    @Test
    void twoPlayersSharingADisplayNameAreNeverMerged(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, first, "Steve", null, null);

        registry.record(sword, first, "Steve", StatKey.KILLS, 4);
        registry.record(sword, second, "Steve", StatKey.KILLS, 9);

        assertEquals(2, sword.contributorCount());
        assertEquals(4, sword.contribution(first).stats().getOrZero(StatKey.KILLS));
        assertEquals(9, sword.contribution(second).stats().getOrZero(StatKey.KILLS));
        assertEquals(13, sword.overall().getOrZero(StatKey.KILLS));
    }

    @Test
    void maxAndMinStatsAggregateCorrectlyRatherThanSumming(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, a, "A", null, null);

        registry.record(pick, a, "A", StatKey.DEEPEST_BLOCK_Y, 12);
        registry.record(pick, b, "B", StatKey.DEEPEST_BLOCK_Y, -58);
        registry.record(pick, a, "A", StatKey.DEEPEST_BLOCK_Y, -4);

        assertEquals(-58, pick.overall().getOrZero(StatKey.DEEPEST_BLOCK_Y), "deepest is the minimum Y, not a sum");
        assertEquals(-4, pick.contribution(a).stats().getOrZero(StatKey.DEEPEST_BLOCK_Y));
        assertTrue(pick.verifyOverallMatchesContributors());
    }

    @Test
    void longestKillIsAMaximumAcrossContributors(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ItemRecord bow = registry.registerCrafted(
                "minecraft:bow", ItemCategory.RANGED_WEAPON, a, "A", null, null);

        registry.record(bow, a, "A", StatKey.LONGEST_KILL_CM, 4_200);
        registry.record(bow, b, "B", StatKey.LONGEST_KILL_CM, 9_100);

        assertEquals(9_100, bow.overall().getOrZero(StatKey.LONGEST_KILL_CM));
        assertTrue(bow.verifyOverallMatchesContributors());
    }

    @Test
    void breakdownsTrackMostFrequentType(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID jon = UUID.randomUUID();
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, jon, "Jon", null, null);

        registry.recordBreakdown(pick, jon, "Jon", BreakdownKind.BLOCK_MINED, "minecraft:stone", 900);
        registry.recordBreakdown(pick, jon, "Jon", BreakdownKind.BLOCK_MINED, "minecraft:deepslate", 300);

        assertEquals("minecraft:stone", pick.overall().mostFrequent(BreakdownKind.BLOCK_MINED));
        assertEquals(900, pick.overall().breakdown(BreakdownKind.BLOCK_MINED).get("minecraft:stone"));
    }

    @Test
    void breakdownsStayBoundedOnALongLivedItem(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID jon = UUID.randomUUID();
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, jon, "Jon", null, null);

        for (int i = 0; i < 1_000; i++) {
            registry.recordBreakdown(pick, jon, "Jon", BreakdownKind.BLOCK_MINED, "mod:block_" + i, 1);
        }
        registry.recordBreakdown(pick, jon, "Jon", BreakdownKind.BLOCK_MINED, "minecraft:stone", 50_000);

        assertTrue(pick.overall().breakdown(BreakdownKind.BLOCK_MINED).size() <= StatBucket.MAX_BREAKDOWN_ENTRIES,
                "breakdown maps must not grow without bound");
        assertEquals("minecraft:stone", pick.overall().mostFrequent(BreakdownKind.BLOCK_MINED),
                "trimming must never discard the leader");
    }

    /**
     * Acceptance test 25. The forbidden categories must be unrepresentable, not
     * merely unused, so the enum itself is checked. Matching is on whole
     * underscore-separated tokens: "damage_prevented" is legitimate and happens
     * to contain the letters of "event".
     */
    @Test
    void thereIsNoStatKeyForHunterArenaOrServerEvents() {
        java.util.Set<String> forbidden = java.util.Set.of(
                "hunter", "hunters", "arena", "arenas", "event", "events", "survived", "round", "rounds");

        for (StatKey key : StatKey.values()) {
            for (String token : key.id().split("_")) {
                assertTrue(!forbidden.contains(token),
                        "found a forbidden statistic: " + key.id());
            }
        }
    }

    /**
     * Acceptance test 5/6 under concurrency: the overall counter and the
     * contributor counter must never drift, even when several event handlers
     * touch one record at once.
     */
    @Test
    void concurrentWritesKeepOverallAndContributorsConsistent(@TempDir Path dir) throws Exception {
        RecordRegistry registry = registry(dir);
        UUID owner = UUID.randomUUID();
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, owner, "Jon", null, null);

        int threads = 8;
        int perThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            UUID player = UUID.randomUUID();
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    pick.record(player, "P", StatKey.BLOCKS_MINED, 1, 0L);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish");

        assertEquals((long) threads * perThread, pick.overall().getOrZero(StatKey.BLOCKS_MINED));
        assertEquals(threads, pick.contributorCount());
        assertTrue(pick.verifyOverallMatchesContributors(), "overall drifted from contributor totals");
    }

    @Test
    void unusedPlayersHaveNoContributionRecord(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID jon = UUID.randomUUID();
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, jon, "Jon", null, null);

        assertNull(pick.contribution(UUID.randomUUID()));
        assertNotNull(pick.recordId());
    }
}
