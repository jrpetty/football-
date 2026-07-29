package com.provenance.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance tests 15, 16 and 17. */
class MilestoneTest {

    private long clock = 1_000_000L;

    private RecordRegistry registry(Path dir, ProvenanceConfig config) {
        return new RecordRegistry(new FileRecordStore(dir), config, () -> clock);
    }

    @Test
    void milestonesReadOverallItemStatisticsNotTheOwnersOwn(@TempDir Path dir) {
        RecordRegistry registry = registry(dir, ProvenanceConfig.withDefaults());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, a, "A", null, null);

        // Neither player alone reaches Initiated (10); together they pass it.
        registry.record(sword, a, "A", StatKey.KILLS, 6);
        List<MilestoneAward> awards = registry.record(sword, b, "B", StatKey.KILLS, 6);

        assertEquals(1, awards.size());
        assertEquals(MilestoneTier.INITIATED, awards.get(0).tier());
        assertEquals(MilestoneTier.INITIATED, sword.currentTier());
    }

    @Test
    void eachMilestoneIsAwardedOnceAndKeepsItsDate(@TempDir Path dir) {
        RecordRegistry registry = registry(dir, ProvenanceConfig.withDefaults());
        UUID player = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "A", null, null);

        clock = 5_000L;
        registry.record(sword, player, "A", StatKey.KILLS, 10);
        long firstAwardTime = sword.award(MilestoneTier.INITIATED).achievedEpochMilli();
        assertEquals(5_000L, firstAwardTime);

        clock = 9_999L;
        List<MilestoneAward> again = registry.record(sword, player, "A", StatKey.KILLS, 5);

        assertTrue(again.isEmpty(), "a tier must not be awarded twice");
        assertEquals(firstAwardTime, sword.award(MilestoneTier.INITIATED).achievedEpochMilli(),
                "the original achievement date must not be rewritten");
    }

    @Test
    void crossingSeveralThresholdsAtOnceAwardsEveryTierInOrder(@TempDir Path dir) {
        RecordRegistry registry = registry(dir, ProvenanceConfig.withDefaults());
        UUID player = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "A", null, null);

        List<MilestoneAward> awards = registry.record(sword, player, "A", StatKey.KILLS, 1_000);

        assertEquals(List.of(MilestoneTier.INITIATED, MilestoneTier.PROVEN,
                        MilestoneTier.SEASONED, MilestoneTier.VETERAN),
                awards.stream().map(MilestoneAward::tier).toList());
        assertEquals(MilestoneTier.VETERAN, sword.currentTier());
    }

    @Test
    void loweringAThresholdLaterDoesNotRewriteAnEarnedDate(@TempDir Path dir) {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        RecordRegistry registry = registry(dir, config);
        UUID player = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "A", null, null);

        clock = 1_111L;
        registry.record(sword, player, "A", StatKey.KILLS, 10);
        long original = sword.award(MilestoneTier.INITIATED).achievedEpochMilli();

        // Admin lowers Proven so the item now qualifies retroactively.
        config.setTrack(ItemCategory.MELEE_WEAPON,
                new MilestoneTrack(StatKey.KILLS, new java.util.EnumMap<>(java.util.Map.of(
                        MilestoneTier.INITIATED, 1L,
                        MilestoneTier.PROVEN, 5L,
                        MilestoneTier.SEASONED, 250L,
                        MilestoneTier.VETERAN, 1_000L,
                        MilestoneTier.MASTER, 5_000L,
                        MilestoneTier.LEGENDARY, 25_000L,
                        MilestoneTier.SERVER_RELIC, 100_000L))));

        clock = 7_777L;
        List<MilestoneAward> newAwards = registry.evaluate(sword, registry.now());

        assertEquals(1, newAwards.size());
        assertEquals(MilestoneTier.PROVEN, newAwards.get(0).tier());
        assertEquals(original, sword.award(MilestoneTier.INITIATED).achievedEpochMilli(),
                "an already-earned tier keeps its original date");
    }

    @Test
    void progressReportsNextTierAndPercentage(@TempDir Path dir) {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        RecordRegistry registry = registry(dir, config);
        UUID player = UUID.randomUUID();
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon", null, null);

        registry.record(pick, player, "Jon", StatKey.BLOCKS_MINED, 183_542);
        MilestoneTrack track = registry.trackFor(pick);

        assertEquals(MilestoneTier.VETERAN, pick.currentTier());
        assertEquals(MilestoneTier.MASTER, track.nextTier(pick.currentTier()));
        assertEquals(500_000L, track.threshold(MilestoneTier.MASTER));

        // Measured from the Veteran floor (100,000) to the Master target.
        double progress = track.progressToNext(183_542, MilestoneTier.VETERAN);
        assertEquals(0.2088, progress, 0.001);
    }

    @Test
    void serverRelicIsTheEndOfTheLadder(@TempDir Path dir) {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        RecordRegistry registry = registry(dir, config);
        UUID player = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "A", null, null);

        registry.record(sword, player, "A", StatKey.KILLS, 100_000);

        assertEquals(MilestoneTier.SERVER_RELIC, sword.currentTier());
        assertNull(registry.trackFor(sword).nextTier(MilestoneTier.SERVER_RELIC));
        assertEquals(1.0d, registry.trackFor(sword).progressToNext(100_000, MilestoneTier.SERVER_RELIC));
    }

    @Test
    void anUnprovenItemHasNoTier(@TempDir Path dir) {
        RecordRegistry registry = registry(dir, ProvenanceConfig.withDefaults());
        UUID player = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "A", null, null);

        registry.record(sword, player, "A", StatKey.KILLS, 9);

        assertNull(sword.currentTier());
        assertEquals(MilestoneTier.INITIATED, registry.trackFor(sword).nextTier(null));
    }

    /**
     * Acceptance test 17. A tier must be incapable of granting power, so the
     * type is checked to expose nothing a combat or mining calculation could
     * read.
     */
    @Test
    void milestoneTiersExposeNoGameplayPower() {
        List<String> forbidden = List.of("damage", "protection", "efficiency", "speed",
                "luck", "attack", "armor", "armour", "modifier", "bonus", "multiplier");

        for (Method method : MilestoneTier.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            for (String word : forbidden) {
                assertFalse(name.contains(word),
                        "MilestoneTier exposes a power-shaped member: " + method.getName());
            }
        }

        // The only numeric a tier carries is its ladder position.
        assertEquals(3, MilestoneTier.VETERAN.index());
        assertNotNull(MilestoneTier.VETERAN.displayName());
        assertNotNull(MilestoneTier.VETERAN.emblem());
    }

    @Test
    void everyCategoryHasAMilestoneTrack() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        for (ItemCategory category : ItemCategory.values()) {
            MilestoneTrack track = config.track(category);
            assertNotNull(track, "no track for " + category);
            for (MilestoneTier tier : MilestoneTier.values()) {
                assertNotNull(track.threshold(tier), category + " has no threshold for " + tier);
            }
        }
    }

    @Test
    void defaultThresholdsMatchTheSpecification() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();

        MilestoneTrack melee = config.track(ItemCategory.MELEE_WEAPON);
        assertEquals(10L, melee.threshold(MilestoneTier.INITIATED));
        assertEquals(100_000L, melee.threshold(MilestoneTier.SERVER_RELIC));
        assertEquals(StatKey.KILLS, melee.primaryStat());

        MilestoneTrack pickaxe = config.track(ItemCategory.PICKAXE);
        assertEquals(100L, pickaxe.threshold(MilestoneTier.INITIATED));
        assertEquals(5_000_000L, pickaxe.threshold(MilestoneTier.SERVER_RELIC));
        assertEquals(StatKey.BLOCKS_MINED, pickaxe.primaryStat());

        MilestoneTrack armour = config.track(ItemCategory.ARMOUR);
        assertEquals(100L, armour.threshold(MilestoneTier.INITIATED));
        assertEquals(2_000_000L, armour.threshold(MilestoneTier.SERVER_RELIC));
        assertEquals(StatKey.DAMAGE_ABSORBED, armour.primaryStat());

        MilestoneTrack rod = config.track(ItemCategory.FISHING_ROD);
        assertEquals(25L, rod.threshold(MilestoneTier.INITIATED));
        assertEquals(500_000L, rod.threshold(MilestoneTier.SERVER_RELIC));
    }

    /**
     * Every displayed statistic must have a handler behind it. A key with no
     * writer renders as a permanent zero, which reads as a broken feature.
     */
    @Test
    void noStatisticIsDeclaredWithoutAWriter() {
        for (StatKey key : StatKey.values()) {
            assertFalse(key.id().startsWith("attacks_blocked")
                            || key.id().equals("projectiles_fired")
                            || key.id().equals("farmland_created")
                            || key.id().equals("entities_sheared")
                            || key.id().equals("honeycomb_harvested")
                            || key.id().equals("paths_created"),
                    "unwritten statistic reintroduced without a handler: " + key.id());
        }
    }
}
