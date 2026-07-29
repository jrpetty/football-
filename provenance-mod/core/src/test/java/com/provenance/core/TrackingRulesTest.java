package com.provenance.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance tests 8, 18 and 23, plus eligibility configuration. */
class TrackingRulesTest {

    private RecordRegistry registry(Path dir) {
        return new RecordRegistry(new FileRecordStore(dir), ProvenanceConfig.withDefaults());
    }

    // ------------------------------------------------------------------
    // Acceptance test 8: armour must not multiply one event across four pieces
    // ------------------------------------------------------------------

    @Test
    void oneDamageEventIsSplitAcrossArmourRatherThanCopiedToEachPiece(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID wearer = UUID.randomUUID();

        ItemRecord helmet = armour(registry, "minecraft:netherite_helmet", wearer);
        ItemRecord chest = armour(registry, "minecraft:netherite_chestplate", wearer);
        ItemRecord legs = armour(registry, "minecraft:netherite_leggings", wearer);
        ItemRecord boots = armour(registry, "minecraft:netherite_boots", wearer);

        // Vanilla netherite armour points: 3 / 8 / 6 / 3.
        List<ArmourDamageAllocator.Piece> pieces = List.of(
                new ArmourDamageAllocator.Piece(helmet, 3),
                new ArmourDamageAllocator.Piece(chest, 8),
                new ArmourDamageAllocator.Piece(legs, 6),
                new ArmourDamageAllocator.Piece(boots, 3));

        long prevented = 1_000L;
        List<ArmourDamageAllocator.Allocation> allocations =
                ArmourDamageAllocator.allocate(pieces, prevented);

        long total = allocations.stream().mapToLong(ArmourDamageAllocator.Allocation::amount).sum();
        assertEquals(prevented, total, "allocations must sum to exactly the damage actually prevented");

        for (ArmourDamageAllocator.Allocation allocation : allocations) {
            assertTrue(allocation.amount() < prevented, "no single piece may be credited the whole event");
            registry.record(allocation.record(), wearer, "Wearer", StatKey.DAMAGE_ABSORBED, allocation.amount());
        }

        long recorded = helmet.overall().getOrZero(StatKey.DAMAGE_ABSORBED)
                + chest.overall().getOrZero(StatKey.DAMAGE_ABSORBED)
                + legs.overall().getOrZero(StatKey.DAMAGE_ABSORBED)
                + boots.overall().getOrZero(StatKey.DAMAGE_ABSORBED);
        assertEquals(prevented, recorded, "the set as a whole must record the event exactly once");

        // The chestplate did the most work, so it carries the largest share.
        assertTrue(chest.overall().getOrZero(StatKey.DAMAGE_ABSORBED)
                > helmet.overall().getOrZero(StatKey.DAMAGE_ABSORBED));
    }

    @Test
    void allocationIsExactEvenWhenItDoesNotDivideEvenly() {
        ItemRecord a = bare("a");
        ItemRecord b = bare("b");
        ItemRecord c = bare("c");

        for (long prevented = 1; prevented <= 200; prevented++) {
            List<ArmourDamageAllocator.Allocation> allocations = ArmourDamageAllocator.allocate(
                    List.of(new ArmourDamageAllocator.Piece(a, 3),
                            new ArmourDamageAllocator.Piece(b, 8),
                            new ArmourDamageAllocator.Piece(c, 6)),
                    prevented);
            long total = allocations.stream().mapToLong(ArmourDamageAllocator.Allocation::amount).sum();
            assertEquals(prevented, total, "rounding lost or invented damage at " + prevented);
        }
    }

    @Test
    void allocationHandlesNoArmourAndNoDamage() {
        assertTrue(ArmourDamageAllocator.allocate(List.of(), 100).isEmpty());
        assertTrue(ArmourDamageAllocator.allocate(
                List.of(new ArmourDamageAllocator.Piece(bare("a"), 5)), 0).isEmpty());
        assertTrue(ArmourDamageAllocator.allocate(null, 100).isEmpty());
    }

    @Test
    void weightlessPiecesStillSplitRatherThanEachTakingTheFullAmount() {
        List<ArmourDamageAllocator.Allocation> allocations = ArmourDamageAllocator.allocate(
                List.of(new ArmourDamageAllocator.Piece(bare("a"), 0),
                        new ArmourDamageAllocator.Piece(bare("b"), 0)),
                101);
        long total = allocations.stream().mapToLong(ArmourDamageAllocator.Allocation::amount).sum();
        assertEquals(101, total);
    }

    // ------------------------------------------------------------------
    // Acceptance test 18: repairs
    // ------------------------------------------------------------------

    @Test
    void mendingOrbsAggregateIntoRepairSessionsInsteadOfCountingEachOrb() {
        RepairSessionTracker tracker = new RepairSessionTracker(30);
        UUID item = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        int repairs = 0;
        long now = 0L;
        // Two hundred orbs arriving a second apart is one repair session.
        for (int i = 0; i < 200; i++) {
            now += 1_000L;
            if (tracker.continuousRestoration(item, player, 2, now).countsAsNewRepair()) {
                repairs++;
            }
        }
        assertEquals(1, repairs, "a stream of Mending orbs is one repair, not two hundred");

        // A long gap starts a new session.
        now += 60_000L;
        assertTrue(tracker.continuousRestoration(item, player, 2, now).countsAsNewRepair());
    }

    @Test
    void anvilRepairsAlwaysCountIndividually() {
        RepairSessionTracker tracker = new RepairSessionTracker(30);
        UUID item = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        assertTrue(tracker.discreteRepair(item, player, 500, 1_000L).countsAsNewRepair());
        assertTrue(tracker.discreteRepair(item, player, 500, 2_000L).countsAsNewRepair());
        assertTrue(tracker.discreteRepair(item, player, 500, 3_000L).countsAsNewRepair());
    }

    @Test
    void separatePlayersRepairingTheSameItemGetSeparateSessions() {
        RepairSessionTracker tracker = new RepairSessionTracker(30);
        UUID item = UUID.randomUUID();

        assertTrue(tracker.continuousRestoration(item, UUID.randomUUID(), 2, 1_000L).countsAsNewRepair());
        assertTrue(tracker.continuousRestoration(item, UUID.randomUUID(), 2, 1_500L).countsAsNewRepair());
    }

    @Test
    void expiredRepairSessionsArePruned() {
        RepairSessionTracker tracker = new RepairSessionTracker(30);
        tracker.continuousRestoration(UUID.randomUUID(), UUID.randomUUID(), 2, 1_000L);
        assertEquals(1, tracker.openSessionCount());

        tracker.pruneExpired(1_000L + 31_000L);
        assertEquals(0, tracker.openSessionCount(), "a long-lived server must not retain a session per item");
    }

    @Test
    void repairingPreservesIdentityAndEveryRecord(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID owner = UUID.randomUUID();
        UUID smith = UUID.randomUUID();

        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, owner, "Jon", "krown", "Krown Mining Company");
        registry.record(pick, owner, "Jon", StatKey.BLOCKS_MINED, 150_000);
        UUID id = pick.recordId();
        long created = pick.createdEpochMilli();
        MilestoneTier tier = pick.currentTier();

        // A different player repairs it; the repair is credited to them.
        registry.record(pick, smith, "Smith", StatKey.REPAIRS, 1);
        registry.record(pick, smith, "Smith", StatKey.DURABILITY_RESTORED, 800);

        assertEquals(id, pick.recordId());
        assertEquals(created, pick.createdEpochMilli());
        assertEquals("Jon", pick.crafterDisplayName());
        assertEquals("Krown Mining Company", pick.manufacturerDisplayName());
        assertEquals(owner, pick.currentOwnerId(), "repairing does not transfer ownership");
        assertEquals(tier, pick.currentTier());
        assertEquals(150_000, pick.overall().getOrZero(StatKey.BLOCKS_MINED));
        assertEquals(1, pick.overall().getOrZero(StatKey.REPAIRS));
        assertEquals(1, pick.contribution(smith).stats().getOrZero(StatKey.REPAIRS),
                "the repair is attributed to whoever performed it");
        assertEquals(0, pick.contribution(owner).stats().getOrZero(StatKey.REPAIRS));
    }

    // ------------------------------------------------------------------
    // Acceptance test 23: distance
    // ------------------------------------------------------------------

    @Test
    void distanceAccumulatesFromMovementSamples() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        DistanceAccumulator accumulator = new DistanceAccumulator(config);
        UUID player = UUID.randomUUID();

        assertEquals(0L, accumulator.sample(player, 0, 0, 0, "overworld"), "the first sample has no baseline");
        assertEquals(300L, accumulator.sample(player, 300, 0, 0, "overworld"));
        assertEquals(400L, accumulator.sample(player, 300, 0, 400, "overworld"));
    }

    @Test
    void standingStillAccruesNoDistance() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        DistanceAccumulator accumulator = new DistanceAccumulator(config);
        UUID player = UUID.randomUUID();

        accumulator.sample(player, 1_000, 64, 1_000, "overworld");
        for (int i = 0; i < 50; i++) {
            // Sub-block drift from idle animation and physics.
            assertEquals(0L, accumulator.sample(player, 1_000 + (i % 2), 64, 1_000, "overworld"),
                    "an AFK player must not accrue distance");
        }
    }

    @Test
    void teleportsDoNotCreateEnormousTravelRecordsByDefault() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        assertFalse(config.teleportDistanceCounts(), "teleport distance must default to off");

        DistanceAccumulator accumulator = new DistanceAccumulator(config);
        UUID player = UUID.randomUUID();

        accumulator.sample(player, 0, 64, 0, "overworld");
        long jump = accumulator.sample(player, 500_000_00L, 64, 0, "overworld");

        assertEquals(0L, jump, "a five-kilometre jump in one sample is a teleport, not travel");
    }

    @Test
    void changingDimensionOnlyRebaselines() {
        DistanceAccumulator accumulator = new DistanceAccumulator(ProvenanceConfig.withDefaults());
        UUID player = UUID.randomUUID();

        accumulator.sample(player, 10_000, 64, 10_000, "overworld");
        assertEquals(0L, accumulator.sample(player, 1_250, 64, 1_250, "the_nether"));
        assertEquals(500L, accumulator.sample(player, 1_750, 64, 1_250, "the_nether"));
    }

    @Test
    void aServerThatOptsInStillRefusesNothingLegitimate() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        config.setTeleportDistanceCounts(true);
        DistanceAccumulator accumulator = new DistanceAccumulator(config);
        UUID player = UUID.randomUUID();

        accumulator.sample(player, 0, 64, 0, "overworld");
        assertTrue(accumulator.sample(player, 500_000_00L, 64, 0, "overworld") > 0,
                "an opted-in server should see the distance");
    }

    @Test
    void loggingOutForgetsTheBaseline() {
        DistanceAccumulator accumulator = new DistanceAccumulator(ProvenanceConfig.withDefaults());
        UUID player = UUID.randomUUID();

        accumulator.sample(player, 0, 64, 0, "overworld");
        assertEquals(1, accumulator.trackedPlayerCount());

        accumulator.forget(player);
        assertEquals(0, accumulator.trackedPlayerCount());
        assertEquals(0L, accumulator.sample(player, 9_000, 64, 0, "overworld"),
                "the first sample after logging back in must not bill the gap");
    }

    // ------------------------------------------------------------------
    // Eligibility and configuration
    // ------------------------------------------------------------------

    @Test
    void categoriesResolveFromTagsAndExplicitIds() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();

        assertEquals(ItemCategory.PICKAXE,
                config.resolveCategory("minecraft:diamond_pickaxe", List.of("minecraft:pickaxes")));
        assertEquals(ItemCategory.TRIDENT, config.resolveCategory("minecraft:trident", List.of()));
        assertEquals(ItemCategory.ARMOUR,
                config.resolveCategory("minecraft:netherite_chestplate", List.of("minecraft:chest_armor")));
        assertNull(config.resolveCategory("minecraft:dirt", List.of()), "ordinary blocks are not tracked");
    }

    @Test
    void moddedEquipmentJoinsWithoutCodeChanges() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();

        assertNull(config.resolveCategory("tinkers:mattock", List.of()));

        config.mapItem("tinkers:mattock", ItemCategory.PICKAXE);
        assertEquals(ItemCategory.PICKAXE, config.resolveCategory("tinkers:mattock", List.of()));

        config.mapTag("mymod:hammers", ItemCategory.PICKAXE);
        assertEquals(ItemCategory.PICKAXE, config.resolveCategory("mymod:big_hammer", List.of("mymod:hammers")));
    }

    @Test
    void exclusionsBeatTagsEntirely() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        config.exclude("minecraft:wooden_sword");

        assertNull(config.resolveCategory("minecraft:wooden_sword", List.of("minecraft:swords")));
        assertFalse(config.isEligible("minecraft:wooden_sword", List.of("minecraft:swords")));
    }

    @Test
    void creativeActionsAreOffByDefault() {
        assertFalse(ProvenanceConfig.withDefaults().creativeActionsCount());
    }

    private static ItemRecord armour(RecordRegistry registry, String itemId, UUID wearer) {
        return registry.registerCrafted(itemId, ItemCategory.ARMOUR, wearer, "Wearer", null, null);
    }

    private static ItemRecord bare(String suffix) {
        return ItemRecord.builder(UUID.randomUUID(), "minecraft:" + suffix, ItemCategory.ARMOUR)
                .created(0L, false).bindingToken(1L).build();
    }
}
