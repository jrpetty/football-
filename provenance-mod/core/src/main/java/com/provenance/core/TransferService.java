package com.provenance.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ownership changes.
 *
 * <p>Two routes exist, and the distinction is the point of this class:
 *
 * <ul>
 *   <li><b>Trusted systems</b> — marketplace, Auction Block, secure trade —
 *       call {@link #transferViaTrustedSystem} and ownership moves at once,
 *       because the sale itself was the consent.</li>
 *   <li><b>Hand to hand</b> — {@link #propose} then confirmation from
 *       <em>both</em> parties. Picking an item up, borrowing it, repairing it or
 *       storing it in someone else's chest does none of this, so none of them
 *       move the registered owner.</li>
 * </ul>
 *
 * <p>No path here touches statistics, contributors, milestones or creation
 * facts. {@link ItemRecord#setOwner} is the only thing a transfer can reach.
 */
public final class TransferService {

    /** A pending hand-to-hand transfer awaiting both confirmations. */
    public static final class PendingTransfer {
        private final UUID recordId;
        private final UUID senderId;
        private final UUID receiverId;
        private final String receiverName;
        private final long expiresAtEpochMilli;
        private boolean senderConfirmed;
        private boolean receiverConfirmed;

        PendingTransfer(UUID recordId, UUID senderId, UUID receiverId, String receiverName, long expiresAt) {
            this.recordId = recordId;
            this.senderId = senderId;
            this.receiverId = receiverId;
            this.receiverName = receiverName;
            this.expiresAtEpochMilli = expiresAt;
        }

        public UUID recordId() {
            return recordId;
        }

        public UUID senderId() {
            return senderId;
        }

        public UUID receiverId() {
            return receiverId;
        }

        public boolean isReady() {
            return senderConfirmed && receiverConfirmed;
        }

        public boolean isExpired(long now) {
            return now > expiresAtEpochMilli;
        }
    }

    public enum Result {
        /** Ownership moved. */
        TRANSFERRED,
        /** Confirmation accepted; still waiting on the other party. */
        AWAITING_OTHER_PARTY,
        /** No such pending transfer, or it expired. */
        NO_PENDING_TRANSFER,
        /** The confirming player is not part of this transfer. */
        NOT_A_PARTY,
        /** The sender is not the registered owner. */
        NOT_THE_OWNER
    }

    private final Map<UUID, PendingTransfer> pending = new HashMap<>();
    private final long timeoutMillis;

    public TransferService() {
        this(60_000L);
    }

    public TransferService(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Moves ownership on behalf of a system that has already established
     * consent, such as a completed Auction Block sale.
     *
     * <p>History is untouched: the buyer's own past contribution to this exact
     * item, if any, is still there and simply resumes.
     */
    public void transferViaTrustedSystem(ItemRecord record, UUID newOwnerId, String newOwnerName) {
        Objects.requireNonNull(record, "record");
        record.setOwner(newOwnerId, newOwnerName);
    }

    /** Opens a hand-to-hand transfer. Both parties must then confirm. */
    public Result propose(ItemRecord record, UUID senderId, UUID receiverId, String receiverName, long now) {
        if (!Objects.equals(record.currentOwnerId(), senderId)) {
            return Result.NOT_THE_OWNER;
        }
        pending.put(record.recordId(),
                new PendingTransfer(record.recordId(), senderId, receiverId, receiverName, now + timeoutMillis));
        return Result.AWAITING_OTHER_PARTY;
    }

    /**
     * Registers one party's confirmation, completing the transfer once both
     * have confirmed.
     */
    public Result confirm(ItemRecord record, UUID playerId, long now) {
        PendingTransfer transfer = pending.get(record.recordId());
        if (transfer == null || transfer.isExpired(now)) {
            pending.remove(record.recordId());
            return Result.NO_PENDING_TRANSFER;
        }
        if (playerId.equals(transfer.senderId())) {
            transfer.senderConfirmed = true;
        } else if (playerId.equals(transfer.receiverId())) {
            transfer.receiverConfirmed = true;
        } else {
            return Result.NOT_A_PARTY;
        }

        if (!transfer.isReady()) {
            return Result.AWAITING_OTHER_PARTY;
        }

        // Re-check ownership at completion: the sender may have lost the item
        // between proposing and confirming.
        if (!Objects.equals(record.currentOwnerId(), transfer.senderId())) {
            pending.remove(record.recordId());
            return Result.NOT_THE_OWNER;
        }

        record.setOwner(transfer.receiverId(), transfer.receiverName);
        pending.remove(record.recordId());
        return Result.TRANSFERRED;
    }

    public void cancel(UUID recordId) {
        pending.remove(recordId);
    }

    public void pruneExpired(long now) {
        pending.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    public int pendingCount() {
        return pending.size();
    }

    /**
     * Administrative claim for a genuinely orphaned item.
     *
     * <p>Requires an operator to authorise, which is what stops this being a
     * theft route: a normal player finding an unregistered item cannot claim it
     * out from under anyone.
     */
    public boolean adminClaim(ItemRecord record, UUID newOwnerId, String newOwnerName, boolean operatorAuthorised) {
        if (!operatorAuthorised) {
            return false;
        }
        if (record.currentOwnerId() != null) {
            return false;
        }
        record.setOwner(newOwnerId, newOwnerName);
        return true;
    }
}
