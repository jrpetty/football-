package com.provenance.core;

import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The service the game layer talks to. Owns identity creation, duplication
 * defence, statistic recording and milestone awarding.
 *
 * <p>Every method here is server-authoritative by construction: nothing accepts
 * a statistic value from a client, and the only thing a client ever holds is an
 * opaque id and token pair.
 *
 * <h2>How duplication is caught</h2>
 *
 * <p>The record holds a rolling {@code bindingToken}, and the item stack holds a
 * copy. When a stack is claimed for use, the server checks that the presented
 * token matches and immediately re-stamps both sides with a fresh random value.
 *
 * <p>If a stack is copied by any means — a crafting exploit, a rollback, a
 * broken mod interaction — both copies carry the same token. The first to be
 * claimed matches and rotates the token; the second now presents a stale value
 * and is refused. It receives a brand new record with {@link Origin#DUPLICATE},
 * so the copy starts empty and cannot inherit the original's history. Two
 * physical items can therefore never hold the same identity.
 */
public final class RecordRegistry {

    /** Outcome of presenting an item's stamp to the server. */
    public enum Status {
        /** Token matched. The record is authentic and has been re-stamped. */
        VALID,
        /** Token did not match: this stack is a copy and was given a fresh identity. */
        DUPLICATE,
        /** No such record. The stack must be registered before it can be used. */
        UNKNOWN
    }

    /**
     * @param status  what happened
     * @param record  the authoritative record for this stack, or null when UNKNOWN
     * @param stamp   the stamp to write back to the stack, or null when nothing changed
     */
    public record Claim(Status status, ItemRecord record, ItemStamp stamp) {
    }

    private final RecordStore store;
    private final ProvenanceConfig config;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();

    public RecordRegistry(RecordStore store, ProvenanceConfig config) {
        this(store, config, System::currentTimeMillis);
    }

    /** Clock is injectable so milestone dates and repair windows are testable. */
    public RecordRegistry(RecordStore store, ProvenanceConfig config, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProvenanceConfig config() {
        return config;
    }

    public long now() {
        return clock.getAsLong();
    }

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    /**
     * Registers a freshly crafted item.
     *
     * @param companyId   the company the craft genuinely happened on behalf of,
     *                    or null for a personal craft. Callers must pass null
     *                    unless the craft used a company workstation or an
     *                    explicitly active company context — membership alone
     *                    is not attribution.
     */
    public ItemRecord registerCrafted(String itemId, ItemCategory category,
                                      UUID crafterId, String crafterName,
                                      String companyId, String companyName) {
        long now = now();
        ItemRecord record = ItemRecord.builder(newRecordId(), itemId, category)
                .origin(Origin.CRAFTED)
                .crafter(crafterId, crafterName)
                .company(companyId, companyName)
                .created(now, false)
                .owner(crafterId, crafterName)
                .bindingToken(newToken())
                .build();
        store.insert(record);
        return record;
    }

    /**
     * Registers an eligible item that was found rather than made — loot, a
     * trade, a spawner reward. Creator stays Unknown and no manufacturer is
     * invented.
     */
    public ItemRecord registerFound(String itemId, ItemCategory category, UUID holderId, String holderName) {
        ItemRecord record = withDeclaredMaker(ItemRecord.builder(newRecordId(), itemId, category)
                .origin(Origin.FOUND)
                .created(now(), true)
                .owner(holderId, holderName)
                .bindingToken(newToken()), itemId)
                .build();
        store.insert(record);
        return record;
    }

    /**
     * Adopts equipment that predates this system.
     *
     * <p>The creation date is the migration date, flagged approximate. Nothing
     * is backdated and no crafter is guessed, because an invented provenance is
     * worse than an honest gap. Counters start at zero.
     */
    public ItemRecord registerLegacy(String itemId, ItemCategory category, UUID holderId, String holderName) {
        ItemRecord record = withDeclaredMaker(ItemRecord.builder(newRecordId(), itemId, category)
                .origin(Origin.LEGACY)
                .created(now(), true)
                .owner(holderId, holderName)
                .bindingToken(newToken()), itemId)
                .build();
        store.insert(record);
        return record;
    }

    /**
     * Fills in the manufacturer for equipment that arrives already made.
     *
     * <p>Only ever sets the company line, never the crafter: the configuration
     * knows what model an item is, not who assembled this one. An item with no
     * declared maker is left exactly as it was.
     */
    private ItemRecord.Builder withDeclaredMaker(ItemRecord.Builder builder, String itemId) {
        String name = config.manufacturerFor(itemId);
        if (name == null || name.isBlank()) {
            return builder;
        }
        return builder.company(slug(name), name);
    }

    /** A stable key for a display name, so the same company groups together. */
    public static String slug(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private ItemRecord registerDuplicate(String itemId, ItemCategory category, UUID holderId, String holderName,
                                         UUID forkedFrom) {
        ItemRecord record = ItemRecord.builder(newRecordId(), itemId, category)
                .origin(Origin.DUPLICATE)
                .created(now(), true)
                .owner(holderId, holderName)
                .bindingToken(newToken())
                .forkedFrom(forkedFrom)
                .build();
        store.insert(record);
        return record;
    }

    private UUID newRecordId() {
        UUID id = UUID.randomUUID();
        // Astronomically unlikely, but an identity collision is unrecoverable,
        // so it is checked rather than trusted.
        while (store.exists(id)) {
            id = UUID.randomUUID();
        }
        return id;
    }

    private long newToken() {
        long token = random.nextLong();
        return token == 0L ? 1L : token;
    }

    // ------------------------------------------------------------------
    // Claiming and validation
    // ------------------------------------------------------------------

    /**
     * Reads a record without rotating its token. Use for inspection, tooltips
     * and any path that cannot write the stack back.
     */
    public ItemRecord peek(ItemStamp stamp) {
        return stamp == null ? null : store.load(stamp.recordId());
    }

    /**
     * Looks a stack up without rotating its token, reporting whether the token
     * matched.
     *
     * <p>This is the path gameplay uses. Recording a kill or a mined block must
     * not rewrite the item's component: doing so dirties the inventory slot and
     * forces a client sync every time, and it burns through tokens so fast that
     * any hiccup looks like duplication.
     *
     * <p>A mismatch here is reported, never acted on. Forking an identity is a
     * custody-time decision — see {@link #claim}.
     */
    public Claim verify(ItemStamp stamp) {
        if (stamp == null) {
            return new Claim(Status.UNKNOWN, null, null);
        }
        ItemRecord record = store.load(stamp.recordId());
        if (record == null) {
            return new Claim(Status.UNKNOWN, null, null);
        }
        if (record.bindingToken() != stamp.bindingToken()) {
            return new Claim(Status.DUPLICATE, record, null);
        }
        return new Claim(Status.VALID, record, stamp);
    }

    /**
     * Validates a stack and rotates its token. Reserved for genuine custody
     * changes — login, a completed transfer, a repair — rather than for every
     * recorded action.
     *
     * <p>On a stale token the stack is given a fresh, empty identity that
     * records what it was forked from. Duplication detection cannot be perfect,
     * and quietly erasing a relic's history because of a rollback or an odd mod
     * interaction is far worse than a duplicate existing for a while, so the
     * fork is recoverable rather than final.
     *
     * @param itemId   current registry id, used if a replacement record is needed
     * @param category resolved category, used if a replacement record is needed
     */
    public Claim claim(ItemStamp stamp, String itemId, ItemCategory category, UUID holderId, String holderName) {
        if (stamp == null) {
            return new Claim(Status.UNKNOWN, null, null);
        }
        ItemRecord record = store.load(stamp.recordId());
        if (record == null) {
            return new Claim(Status.UNKNOWN, null, null);
        }
        if (record.bindingToken() != stamp.bindingToken()) {
            // Stale token: the original was already claimed elsewhere, so this
            // stack is the copy. Give it an empty identity of its own, marked
            // with where it came from so a false positive can be undone.
            ItemRecord replacement = registerDuplicate(itemId, category, holderId, holderName, record.recordId());
            return new Claim(Status.DUPLICATE, replacement, ItemStamp.of(replacement));
        }
        record.setBindingToken(newToken());
        store.save(record);
        return new Claim(Status.VALID, record, ItemStamp.of(record));
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    /**
     * Records one action against both the item's overall total and the acting
     * player's contribution, then awards any milestone this crossed.
     *
     * @return newly earned tiers, usually empty
     */
    public List<MilestoneAward> record(ItemRecord record, UUID playerId, String playerName,
                                       StatKey key, long amount) {
        long now = now();
        record.record(playerId, playerName, key, amount, now);
        store.save(record);

        // Only the statistic that drives this category's ladder can advance it.
        // Recording damage dealt or ores mined cannot earn a tier on a track
        // measured in kills or blocks, so there is nothing to evaluate — and
        // this skips roughly two thirds of evaluations on a block break.
        MilestoneTrack track = config.track(record.category());
        if (key != track.primaryStat()) {
            return List.of();
        }
        return record.evaluateMilestones(track, now);
    }

    public void recordBreakdown(ItemRecord record, UUID playerId, String playerName,
                                BreakdownKind kind, String id, long amount) {
        record.recordBreakdown(playerId, playerName, kind, id, amount, now());
        store.save(record);
    }

    /**
     * Re-evaluates the milestone ladder from scratch against current overall
     * statistics. Used after a configuration change or by an operator command,
     * never on the recording hot path.
     */
    public List<MilestoneAward> evaluate(ItemRecord record, long now) {
        MilestoneTrack track = config.track(record.category());
        return record.reevaluateMilestones(track, now);
    }

    public MilestoneTrack trackFor(ItemRecord record) {
        return config.track(record.category());
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Applies a configured upgrade path, e.g. diamond to netherite. Identity,
     * creation facts, statistics, contributors and milestones all carry over;
     * only the current item type changes, and the original type stays on record.
     */
    public void applyUpgrade(ItemRecord record, String newItemId) {
        record.setCurrentItemId(newItemId);
        store.save(record);
    }

    /**
     * Flags a record as belonging to an item that no longer exists. The id is
     * retained, never recycled, so a future item cannot inherit this history.
     *
     * <p>Used when an item breaks. The record survives for the configured
     * retention period so an operator can still answer "what happened to it?",
     * then the retention sweep removes it.
     */
    public void markDestroyed(ItemRecord record) {
        record.markDestroyed();
        store.save(record);
    }

    /**
     * Deletes a record outright, for an item that is unambiguously gone — a
     * dropped stack that despawned.
     *
     * <p>There is nothing left to attribute and nobody left to ask, so keeping
     * the history would just be the server tracking an item no player can ever
     * hold again. Record ids are random, so the deleted id can never be handed
     * to a different item.
     *
     * @return true if a record was removed
     */
    public boolean purge(ItemRecord record) {
        return record != null && store.delete(record.recordId());
    }

    /**
     * Re-issues the parent's identity to a stack that was wrongly forked,
     * undoing a false-positive duplication call.
     *
     * <p>Operator-driven on purpose: the server cannot tell a rollback from an
     * exploit, but a human looking at the two items usually can.
     *
     * @return the stamp to write back to the stack, or null when the record was
     *         not a fork or the parent is gone
     */
    public ItemStamp restoreFork(ItemRecord forked) {
        if (forked == null || forked.forkedFromRecordId() == null) {
            return null;
        }
        ItemRecord parent = store.load(forked.forkedFromRecordId());
        if (parent == null) {
            return null;
        }
        parent.setBindingToken(newToken());
        store.save(parent);
        // The fork itself is left in place, destroyed, so its id is never
        // reissued and the incident stays auditable.
        forked.markDestroyed();
        store.save(forked);
        return ItemStamp.of(parent);
    }

    public RecordStore store() {
        return store;
    }
}
