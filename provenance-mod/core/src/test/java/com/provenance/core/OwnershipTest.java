package com.provenance.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance tests 11, 12, 13, 14, 21 and 22. */
class OwnershipTest {

    private RecordRegistry registry(Path dir) {
        return new RecordRegistry(new FileRecordStore(dir), ProvenanceConfig.withDefaults());
    }

    /** Acceptance test 11 and 14: a sale resets nothing and erases nobody. */
    @Test
    void transferringOwnershipResetsNothing(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        TransferService transfers = new TransferService();
        UUID james = UUID.randomUUID();
        UUID jon = UUID.randomUUID();

        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, james, "James", "deepcore", "Deepcore Mining Ltd");
        registry.record(pick, james, "James", StatKey.BLOCKS_MINED, 131_395);
        registry.record(pick, james, "James", StatKey.REPAIRS, 6);
        long created = pick.createdEpochMilli();
        MilestoneTier tierBefore = pick.currentTier();
        long awardDate = pick.award(MilestoneTier.VETERAN).achievedEpochMilli();

        transfers.transferViaTrustedSystem(pick, jon, "Jon");

        assertEquals(jon, pick.currentOwnerId());
        assertEquals(131_395, pick.overall().getOrZero(StatKey.BLOCKS_MINED), "overall statistics survive");
        assertEquals(131_395, pick.contribution(james).stats().getOrZero(StatKey.BLOCKS_MINED),
                "the previous owner's contribution survives");
        assertEquals(6, pick.overall().getOrZero(StatKey.REPAIRS));
        assertEquals(tierBefore, pick.currentTier(), "milestones survive");
        assertEquals(awardDate, pick.award(MilestoneTier.VETERAN).achievedEpochMilli(), "milestone dates survive");
        assertEquals(created, pick.createdEpochMilli(), "creation date survives");
        assertEquals("James", pick.crafterDisplayName(), "original crafter survives");
        assertEquals("Deepcore Mining Ltd", pick.manufacturerDisplayName(), "original company survives");
    }

    /** Acceptance test 12. */
    @Test
    void currentOwnerViewShowsThatOwnersLifetimeContribution(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        TransferService transfers = new TransferService();
        UUID james = UUID.randomUUID();
        UUID jon = UUID.randomUUID();

        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, james, "James", null, null);
        registry.record(pick, james, "James", StatKey.BLOCKS_MINED, 131_395);
        registry.record(pick, jon, "Jon", StatKey.BLOCKS_MINED, 43_207);

        transfers.transferViaTrustedSystem(pick, jon, "Jon");

        Contribution ownerView = pick.currentOwnerContribution();
        assertNotNull(ownerView);
        assertEquals(jon, ownerView.playerId());
        assertEquals(43_207, ownerView.stats().getOrZero(StatKey.BLOCKS_MINED),
                "the owner tab shows the owner's own contribution");
        assertEquals(174_602, pick.overall().getOrZero(StatKey.BLOCKS_MINED),
                "which is distinct from the overall total");
    }

    /** Acceptance test 13: sell, then buy back, and the old tally continues. */
    @Test
    void aFormerOwnerRegainingTheItemContinuesTheirPreviousContribution(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        TransferService transfers = new TransferService();
        UUID jon = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();

        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, jon, "Jon", null, null);
        registry.record(pick, jon, "Jon", StatKey.BLOCKS_MINED, 43_207);

        transfers.transferViaTrustedSystem(pick, buyer, "Buyer");
        registry.record(pick, buyer, "Buyer", StatKey.BLOCKS_MINED, 1_000);

        // Jon buys it back.
        transfers.transferViaTrustedSystem(pick, jon, "Jon");
        registry.record(pick, jon, "Jon", StatKey.BLOCKS_MINED, 793);

        assertEquals(44_000, pick.contribution(jon).stats().getOrZero(StatKey.BLOCKS_MINED),
                "Jon's earlier work must continue, not restart");
        assertEquals(2, pick.contributorCount());
        assertEquals(45_000, pick.overall().getOrZero(StatKey.BLOCKS_MINED));
        assertTrue(pick.verifyOverallMatchesContributors());
    }

    /** Acceptance test 22: borrowing is not owning. */
    @Test
    void temporaryUseDoesNotSilentlyTransferOwnership(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID owner = UUID.randomUUID();
        UUID borrower = UUID.randomUUID();

        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, owner, "Owner", null, null);

        // The borrower mines with it and even repairs it.
        registry.record(pick, borrower, "Borrower", StatKey.BLOCKS_MINED, 500);
        registry.record(pick, borrower, "Borrower", StatKey.REPAIRS, 1);

        assertEquals(owner, pick.currentOwnerId(), "using an item must not claim it");
        assertEquals(500, pick.contribution(borrower).stats().getOrZero(StatKey.BLOCKS_MINED),
                "but their contribution is still recorded under their own UUID");
    }

    @Test
    void handToHandTransferNeedsBothConfirmations(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        TransferService transfers = new TransferService();
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();

        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, sender, "Sender", null, null);

        assertEquals(TransferService.Result.AWAITING_OTHER_PARTY,
                transfers.propose(sword, sender, receiver, "Receiver", 0L));

        assertEquals(TransferService.Result.AWAITING_OTHER_PARTY, transfers.confirm(sword, sender, 10L));
        assertEquals(sender, sword.currentOwnerId(), "one confirmation is not enough");

        assertEquals(TransferService.Result.TRANSFERRED, transfers.confirm(sword, receiver, 20L));
        assertEquals(receiver, sword.currentOwnerId());
        assertEquals(0, transfers.pendingCount());
    }

    @Test
    void aNonOwnerCannotProposeATransfer(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        TransferService transfers = new TransferService();
        UUID owner = UUID.randomUUID();
        UUID thief = UUID.randomUUID();

        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, owner, "Owner", null, null);

        assertEquals(TransferService.Result.NOT_THE_OWNER,
                transfers.propose(sword, thief, thief, "Thief", 0L));
        assertEquals(owner, sword.currentOwnerId());
    }

    @Test
    void anOutsiderCannotConfirmSomeoneElsesTransfer(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        TransferService transfers = new TransferService();
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();

        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, sender, "Sender", null, null);
        transfers.propose(sword, sender, receiver, "Receiver", 0L);

        assertEquals(TransferService.Result.NOT_A_PARTY,
                transfers.confirm(sword, UUID.randomUUID(), 5L));
    }

    @Test
    void aPendingTransferExpires(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        TransferService transfers = new TransferService(1_000L);
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();

        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, sender, "Sender", null, null);
        transfers.propose(sword, sender, receiver, "Receiver", 0L);

        assertEquals(TransferService.Result.NO_PENDING_TRANSFER, transfers.confirm(sword, sender, 5_000L));
        assertEquals(sender, sword.currentOwnerId());
    }

    @Test
    void anOrphanedItemShowsUnregisteredAndOnlyAnOperatorCanClaimIt(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        TransferService transfers = new TransferService();
        UUID finder = UUID.randomUUID();

        ItemRecord sword = ItemRecord.builder(UUID.randomUUID(), "minecraft:iron_sword", ItemCategory.MELEE_WEAPON)
                .origin(Origin.FOUND)
                .created(0L, true)
                .bindingToken(1L)
                .build();

        assertNull(sword.currentOwnerId());
        assertEquals("Unregistered", sword.currentOwnerDisplayName());

        assertFalse(transfers.adminClaim(sword, finder, "Finder", false),
                "a normal player must not be able to claim an item");
        assertNull(sword.currentOwnerId());

        assertTrue(transfers.adminClaim(sword, finder, "Finder", true));
        assertEquals(finder, sword.currentOwnerId());
    }

    @Test
    void anOperatorCannotClaimAnItemThatAlreadyHasAnOwner(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        TransferService transfers = new TransferService();
        UUID owner = UUID.randomUUID();
        UUID thief = UUID.randomUUID();

        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, owner, "Owner", null, null);

        assertFalse(transfers.adminClaim(sword, thief, "Thief", true),
                "the claim path is for orphans only, not a theft route");
        assertEquals(owner, sword.currentOwnerId());
    }
}
