package com.provenance.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The complete lifetime record of one physical item, keyed by its Item Record
 * ID. Everything except a small identity reference lives here, server-side —
 * item NBT/components never carry the contributor list.
 *
 * <p>Two invariants are enforced rather than assumed:
 *
 * <ol>
 *   <li>Every statistic write goes through {@link #record}, which updates the
 *       overall counter and the acting player's counter inside one synchronized
 *       block. The two views cannot diverge, and
 *       {@link #verifyOverallMatchesContributors()} proves it.</li>
 *   <li>Creation facts — crafter, company, creation date, origin — have no
 *       setters. Selling, renaming, repairing and upgrading physically cannot
 *       rewrite them.</li>
 * </ol>
 */
public final class ItemRecord {

    private final UUID recordId;

    // --- Immutable creation record (specification section 3) ---
    private final String originalItemId;
    private final ItemCategory category;
    private final UUID crafterId;
    private final String crafterName;
    private final String companyId;
    private final String companyName;
    private final long createdEpochMilli;
    private final Origin origin;
    /** True when the creation date is a migration date, not a real forging date. */
    private final boolean creationDateApproximate;

    // --- Mutable present state ---
    private String currentItemId;
    private String customName;
    private UUID currentOwnerId;
    private String currentOwnerName;

    /**
     * Rolling anti-duplication token. The stack carries a copy; the server
     * re-stamps it on validation. A cloned stack presents a stale token and is
     * refused the identity. See {@link RecordRegistry}.
     */
    private long bindingToken;

    private boolean destroyed;

    private final StatBucket overall = new StatBucket();
    private final Map<UUID, Contribution> contributors = new LinkedHashMap<>();
    private final EnumMap<MilestoneTier, MilestoneAward> milestones = new EnumMap<>(MilestoneTier.class);

    private transient boolean dirty;

    private ItemRecord(Builder b) {
        this.recordId = Objects.requireNonNull(b.recordId, "recordId");
        this.originalItemId = Objects.requireNonNull(b.originalItemId, "originalItemId");
        this.currentItemId = b.currentItemId == null ? b.originalItemId : b.currentItemId;
        this.category = Objects.requireNonNull(b.category, "category");
        this.crafterId = b.crafterId;
        this.crafterName = b.crafterName;
        this.companyId = b.companyId;
        this.companyName = b.companyName;
        this.createdEpochMilli = b.createdEpochMilli;
        this.origin = Objects.requireNonNull(b.origin, "origin");
        this.creationDateApproximate = b.creationDateApproximate;
        this.currentOwnerId = b.currentOwnerId;
        this.currentOwnerName = b.currentOwnerName;
        this.customName = b.customName;
        this.bindingToken = b.bindingToken;
    }

    public static Builder builder(UUID recordId, String originalItemId, ItemCategory category) {
        return new Builder(recordId, originalItemId, category);
    }

    // ------------------------------------------------------------------
    // Identity and creation facts
    // ------------------------------------------------------------------

    public UUID recordId() {
        return recordId;
    }

    public String originalItemId() {
        return originalItemId;
    }

    public String currentItemId() {
        return currentItemId;
    }

    public ItemCategory category() {
        return category;
    }

    public UUID crafterId() {
        return crafterId;
    }

    /** Display name of the maker, or "Unknown" for found and legacy items. */
    public String crafterDisplayName() {
        return crafterName == null || crafterName.isEmpty() ? "Unknown" : crafterName;
    }

    public String companyId() {
        return companyId;
    }

    /**
     * The manufacturer line. Renders "Independent" for a genuine personal
     * craft; a company appears only when one was actually recorded at creation,
     * never inferred from the crafter's current membership.
     */
    public String manufacturerDisplayName() {
        if (origin == Origin.LEGACY || origin == Origin.FOUND) {
            return "Unknown";
        }
        return companyName == null || companyName.isEmpty() ? "Independent" : companyName;
    }

    public long createdEpochMilli() {
        return createdEpochMilli;
    }

    public boolean creationDateApproximate() {
        return creationDateApproximate;
    }

    public Origin origin() {
        return origin;
    }

    public long ageMillis(long nowEpochMilli) {
        return Math.max(0L, nowEpochMilli - createdEpochMilli);
    }

    // ------------------------------------------------------------------
    // Present state
    // ------------------------------------------------------------------

    public synchronized UUID currentOwnerId() {
        return currentOwnerId;
    }

    /** The registered owner, or "Unregistered" for a genuinely orphaned item. */
    public synchronized String currentOwnerDisplayName() {
        return currentOwnerId == null || currentOwnerName == null || currentOwnerName.isEmpty()
                ? "Unregistered"
                : currentOwnerName;
    }

    /**
     * Reassigns the registered owner. Deliberately touches nothing else: no
     * statistic, contributor, milestone or creation fact is reachable from
     * here, so a transfer cannot reset history.
     */
    public synchronized void setOwner(UUID ownerId, String ownerName) {
        this.currentOwnerId = ownerId;
        this.currentOwnerName = ownerName;
        markDirty();
    }

    public synchronized String customName() {
        return customName;
    }

    /** Purely cosmetic. Never touches the authenticated milestone title. */
    public synchronized void setCustomName(String customName) {
        this.customName = customName;
        markDirty();
    }

    /** Records a legitimate configured upgrade, e.g. diamond to netherite. */
    public synchronized void setCurrentItemId(String itemId) {
        this.currentItemId = itemId;
        markDirty();
    }

    public synchronized long bindingToken() {
        return bindingToken;
    }

    synchronized void setBindingToken(long token) {
        this.bindingToken = token;
        markDirty();
    }

    public synchronized boolean isDestroyed() {
        return destroyed;
    }

    synchronized void markDestroyed() {
        this.destroyed = true;
        markDirty();
    }

    // ------------------------------------------------------------------
    // Statistics
    // ------------------------------------------------------------------

    /**
     * The single write path for every tracked action.
     *
     * <p>Updates the item's overall counter and the acting player's counter
     * together, under this record's monitor. Callers on the server thread and
     * on the batched distance/time scheduler therefore cannot interleave in a
     * way that leaves the two views inconsistent.
     *
     * @param playerId the acting player; never null, because an unattributed
     *                 statistic would break the overall/contributor invariant
     */
    public synchronized void record(UUID playerId, String playerName, StatKey key, long amount, long nowEpochMilli) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(key, "key");
        if (key.aggregation() == StatKey.Aggregation.SUM && amount == 0L) {
            return;
        }
        Contribution contribution = contributorFor(playerId, playerName, nowEpochMilli);
        contribution.stats().record(key, amount);
        contribution.touch(nowEpochMilli);
        overall.record(key, amount);
        markDirty();
    }

    public synchronized void recordBreakdown(UUID playerId, String playerName, BreakdownKind kind,
                                             String id, long amount, long nowEpochMilli) {
        Objects.requireNonNull(playerId, "playerId");
        Contribution contribution = contributorFor(playerId, playerName, nowEpochMilli);
        contribution.stats().recordBreakdown(kind, id, amount);
        contribution.touch(nowEpochMilli);
        overall.recordBreakdown(kind, id, amount);
        markDirty();
    }

    private Contribution contributorFor(UUID playerId, String playerName, long nowEpochMilli) {
        Contribution existing = contributors.get(playerId);
        if (existing == null) {
            existing = new Contribution(playerId, playerName, nowEpochMilli);
            contributors.put(playerId, existing);
        } else {
            existing.updateName(playerName);
        }
        return existing;
    }

    /** Combined lifetime totals across every player who has ever used the item. */
    public synchronized StatBucket overall() {
        return overall;
    }

    /**
     * A player's cumulative lifetime contribution, or null if they have never
     * used the item. Returned for former owners too, which is what lets a
     * repurchased item continue a previous tally instead of restarting it.
     */
    public synchronized Contribution contribution(UUID playerId) {
        return contributors.get(playerId);
    }

    /** The registered owner's personal contribution, for the Current Owner view. */
    public synchronized Contribution currentOwnerContribution() {
        return currentOwnerId == null ? null : contributors.get(currentOwnerId);
    }

    public synchronized List<Contribution> contributors() {
        return List.copyOf(contributors.values());
    }

    public synchronized int contributorCount() {
        return contributors.size();
    }

    /**
     * Recomputes overall totals from contributors and reports whether they
     * already agreed. Used by the consistency tests and by an administrative
     * repair command; not needed in normal operation because {@link #record}
     * maintains both sides together.
     */
    public synchronized boolean verifyOverallMatchesContributors() {
        StatBucket rebuilt = new StatBucket();
        for (Contribution c : contributors.values()) {
            rebuilt.mergeFrom(c.stats());
        }
        for (StatKey key : StatKey.values()) {
            long expected = rebuilt.get(key);
            long actual = overall.get(key);
            if (expected != actual) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Milestones
    // ------------------------------------------------------------------

    /**
     * Awards any newly reached tiers and returns them in ascending order.
     *
     * <p>Awards are idempotent: a tier already present keeps its original
     * achievement date, so lowering a threshold later cannot rewrite history,
     * and re-evaluating on every action cannot double-award.
     */
    public synchronized List<MilestoneAward> evaluateMilestones(MilestoneTrack track, long nowEpochMilli) {
        long value = overall.getOrZero(track.primaryStat());
        MilestoneTier earned = track.tierFor(value);
        if (earned == null) {
            return List.of();
        }
        List<MilestoneAward> newly = new ArrayList<>();
        for (MilestoneTier tier : MilestoneTier.values()) {
            if (tier.index() > earned.index()) {
                break;
            }
            if (!milestones.containsKey(tier)) {
                MilestoneAward award = new MilestoneAward(tier, nowEpochMilli, track.primaryStat(), value);
                milestones.put(tier, award);
                newly.add(award);
                markDirty();
            }
        }
        return newly;
    }

    /** The highest tier actually awarded, or null for an item still unproven. */
    public synchronized MilestoneTier currentTier() {
        MilestoneTier best = null;
        for (MilestoneTier tier : milestones.keySet()) {
            if (best == null || tier.index() > best.index()) {
                best = tier;
            }
        }
        return best;
    }

    public synchronized MilestoneAward award(MilestoneTier tier) {
        return milestones.get(tier);
    }

    public synchronized Map<MilestoneTier, MilestoneAward> milestones() {
        return Collections.unmodifiableMap(new EnumMap<>(milestones));
    }

    void putAwardForLoad(MilestoneAward award) {
        milestones.put(award.tier(), award);
    }

    void putContributorForLoad(Contribution contribution) {
        contributors.put(contribution.playerId(), contribution);
    }

    // ------------------------------------------------------------------
    // Persistence bookkeeping
    // ------------------------------------------------------------------

    public synchronized boolean isDirty() {
        return dirty;
    }

    synchronized void markDirty() {
        this.dirty = true;
    }

    synchronized void clearDirty() {
        this.dirty = false;
    }

    /** Builder used by {@link RecordRegistry} and by deserialisation. */
    public static final class Builder {
        private final UUID recordId;
        private final String originalItemId;
        private final ItemCategory category;
        private String currentItemId;
        private UUID crafterId;
        private String crafterName;
        private String companyId;
        private String companyName;
        private long createdEpochMilli;
        private Origin origin = Origin.FOUND;
        private boolean creationDateApproximate;
        private UUID currentOwnerId;
        private String currentOwnerName;
        private String customName;
        private long bindingToken;

        private Builder(UUID recordId, String originalItemId, ItemCategory category) {
            this.recordId = recordId;
            this.originalItemId = originalItemId;
            this.category = category;
        }

        public Builder currentItemId(String v) {
            this.currentItemId = v;
            return this;
        }

        public Builder crafter(UUID id, String name) {
            this.crafterId = id;
            this.crafterName = name;
            return this;
        }

        public Builder company(String id, String name) {
            this.companyId = id;
            this.companyName = name;
            return this;
        }

        public Builder created(long epochMilli, boolean approximate) {
            this.createdEpochMilli = epochMilli;
            this.creationDateApproximate = approximate;
            return this;
        }

        public Builder origin(Origin v) {
            this.origin = v;
            return this;
        }

        public Builder owner(UUID id, String name) {
            this.currentOwnerId = id;
            this.currentOwnerName = name;
            return this;
        }

        public Builder customName(String v) {
            this.customName = v;
            return this;
        }

        public Builder bindingToken(long v) {
            this.bindingToken = v;
            return this;
        }

        public ItemRecord build() {
            return new ItemRecord(this);
        }
    }
}
