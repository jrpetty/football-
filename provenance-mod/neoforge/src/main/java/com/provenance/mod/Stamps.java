package com.provenance.mod;

import com.provenance.core.ItemCategory;
import com.provenance.core.ItemRecord;
import com.provenance.core.ItemStamp;
import com.provenance.core.ProvenanceConfig;
import com.provenance.core.RecordRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges a physical {@link ItemStack} to its server-side record.
 *
 * <p>There are two deliberately separate paths.
 *
 * <p>{@link #track} is what gameplay uses — every mined block, every kill,
 * every sampling tick. It never rewrites the stack and never rotates the
 * anti-duplication token. That matters twice over: rewriting an item's
 * component dirties its inventory slot and forces a client sync, and rotating
 * the token on every action makes ordinary hiccups look like duplication.
 *
 * <p>{@link #claimCustody} is the rotating path, reserved for the handful of
 * moments when an item genuinely changes hands: logging in, a completed
 * transfer, a repair. Duplicates are caught at those checkpoints instead of
 * continuously, which is where they actually enter circulation anyway.
 *
 * <p>Everything here is server-only. The client is never trusted to say which
 * record a stack belongs to; it merely carries the stamp it was given.
 */
public final class Stamps {

    private Stamps() {
    }

    /**
     * Registry ids, resolved once per item type.
     *
     * <p>{@code ResourceLocation.toString()} builds a new string every call, and
     * this runs on every block broken and every hit landed. The id of an item
     * type never changes, so it is cached.
     */
    private static final java.util.Map<net.minecraft.world.item.Item, String> ID_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static String itemId(ItemStack stack) {
        return ID_CACHE.computeIfAbsent(stack.getItem(), item -> {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            return id == null ? "" : id.toString();
        });
    }

    /** The stack's tag ids, in the form the configuration matches against. */
    public static List<String> tagsOf(ItemStack stack) {
        List<String> tags = new ArrayList<>();
        stack.getTags().forEach(tag -> tags.add(tag.location().toString()));
        return tags;
    }

    /**
     * Eligibility per item type, resolved once.
     *
     * <p>Resolving a category means building the stack's tag list and walking
     * configuration, which allocates. Eligibility depends only on the item
     * type, never on the individual stack, so it is cached — otherwise the
     * once-a-second sampler would redo that work six times per player per
     * second forever.
     */
    private static final java.util.Map<net.minecraft.world.item.Item, java.util.Optional<ItemCategory>>
            CATEGORY_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Called when the server starts, so a config change is picked up. */
    public static void clearCategoryCache() {
        CATEGORY_CACHE.clear();
        ID_CACHE.clear();
    }

    /**
     * Writes the tooltip summary onto the stack, but only when it changed.
     *
     * <p>Called on the slow flush tick, at custody moments, and immediately
     * when a milestone is earned so the new tier shows up at once. Never per
     * action: a stack write dirties the inventory slot and forces a sync.
     */
    public static void refreshSummary(ItemStack stack, ItemRecord record) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null || record == null || stack.isEmpty()) {
            return;
        }
        com.provenance.core.ItemSummary next =
                com.provenance.core.ItemSummary.of(record, state.registry().trackFor(record));
        if (next.differsFrom(stack.get(ProvenanceComponents.SUMMARY.get()))) {
            stack.set(ProvenanceComponents.SUMMARY.get(), next);
        }
    }

    /** Refreshes the record's display name. Called only off the hot path. */
    public static void refreshCustomName(ItemStack stack, ItemRecord record) {
        syncCustomName(stack, record);
    }

    public static ItemCategory categoryOf(ProvenanceConfig config, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        return CATEGORY_CACHE
                .computeIfAbsent(stack.getItem(),
                        item -> java.util.Optional.ofNullable(config.resolveCategory(itemId(stack), tagsOf(stack))))
                .orElse(null);
    }

    /**
     * The cheapest possible lookup, for the per-second sampler.
     *
     * <p>A component read and a cached map lookup. Deliberately does not adopt
     * unregistered items, sync names, detect upgrades or rotate tokens: a
     * sampling tick is not a gameplay event and must not mutate anything.
     *
     * @return the record, or null if the stack is untracked or unregistered
     */
    public static ItemRecord sampled(ItemStack stack) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return null;
        }
        ItemStamp stamp = readStamp(stack);
        if (stamp == null) {
            return null;
        }
        RecordRegistry.Claim claim = state.registry().verify(stamp);
        return claim.status() == RecordRegistry.Status.VALID ? claim.record() : null;
    }

    public static ItemStamp readStamp(ItemStack stack) {
        return stack.get(ProvenanceComponents.STAMP.get());
    }

    public static void writeStamp(ItemStack stack, ItemStamp stamp) {
        stack.set(ProvenanceComponents.STAMP.get(), stamp);
    }

    /**
     * The gameplay path: returns the record for a stack without touching the
     * stack or the token.
     *
     * <p>An eligible item with no stamp is adopted once, here, as a Legacy item
     * — a new id, an honest migration date, no invented maker, counters at
     * zero. That is the only case in which this method writes to the stack.
     *
     * @return the record, or null when the item is not tracked
     */
    public static ItemRecord track(ItemStack stack, ServerPlayer player) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null || stack.isEmpty()) {
            return null;
        }

        ItemCategory category = categoryOf(state.config(), stack);
        if (category == null) {
            return null;
        }

        RecordRegistry registry = state.registry();
        String itemId = itemId(stack);
        ItemStamp stamp = readStamp(stack);

        if (stamp == null) {
            return adopt(stack, itemId, category, player, registry);
        }

        RecordRegistry.Claim claim = registry.verify(stamp);
        switch (claim.status()) {
            case VALID -> {
                // No name sync here: reading a custom name allocates a string,
                // and this path runs on every recorded action. Renames are
                // picked up at custody moments and whenever the item is
                // inspected, which is soon enough for a cosmetic field.
                noteUpgrade(claim.record(), itemId, registry);
                return claim.record();
            }
            case DUPLICATE -> {
                // Reported, not acted on. Forking an identity is a custody-time
                // decision, so that a rollback or an odd mod interaction cannot
                // silently erase a relic's history mid-swing.
                return claim.record();
            }
            default -> {
                // A stamp referencing an id this server never issued: a
                // hand-edited stack, or data from another world's store.
                return adopt(stack, itemId, category, player, registry);
            }
        }
    }

    private static ItemRecord adopt(ItemStack stack, String itemId, ItemCategory category,
                                    ServerPlayer player, RecordRegistry registry) {
        ItemRecord record = registry.registerLegacy(itemId, category,
                player == null ? null : player.getUUID(),
                player == null ? null : player.getGameProfile().getName());
        writeStamp(stack, ItemStamp.of(record));
        syncCustomName(stack, record);
        refreshSummary(stack, record);
        return record;
    }

    /**
     * The custody path: validates a stack and rotates its token.
     *
     * <p>Called only when an item genuinely changes hands. A stale token here
     * means the stack is a copy, and it is given a fresh empty identity that
     * records what it was forked from, so an operator can undo a false positive
     * with {@code /provenance admin restore}.
     */
    public static ItemRecord claimCustody(ItemStack stack, ServerPlayer player) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null || stack.isEmpty()) {
            return null;
        }

        ItemCategory category = categoryOf(state.config(), stack);
        if (category == null) {
            return null;
        }

        RecordRegistry registry = state.registry();
        String itemId = itemId(stack);
        ItemStamp stamp = readStamp(stack);
        if (stamp == null) {
            return adopt(stack, itemId, category, player, registry);
        }

        RecordRegistry.Claim claim = registry.claim(stamp, itemId, category,
                player == null ? null : player.getUUID(),
                player == null ? null : player.getGameProfile().getName());

        switch (claim.status()) {
            case VALID -> {
                writeStamp(stack, claim.stamp());
                noteUpgrade(claim.record(), itemId, registry);
                syncCustomName(stack, claim.record());
                refreshSummary(stack, claim.record());
                return claim.record();
            }
            case DUPLICATE -> {
                writeStamp(stack, claim.stamp());
                Provenance.LOGGER.warn(
                        "Provenance: {} presented a stale token for {}; issued fresh record {} forked from {}. "
                                + "If this was a rollback rather than duplication, use /provenance admin restore.",
                        player == null ? "an unknown holder" : player.getGameProfile().getName(),
                        itemId, claim.record().recordId(), claim.record().forkedFromRecordId());
                return claim.record();
            }
            default -> {
                return adopt(stack, itemId, category, player, registry);
            }
        }
    }

    /**
     * Detects a legitimate upgrade — diamond equipment becoming netherite, or
     * any modded equivalent — and moves the record's current type to match.
     *
     * <p>Deliberately not driven by a smithing event. An upgrade is directly
     * observable: the stack still carries its original stamp, and its registry
     * id no longer matches the one on record. That is only possible when the
     * same physical item legitimately changed type, because vanilla builds a
     * smithing result by copying the base stack's data components — the same
     * mechanism that keeps enchantments and custom names across a netherite
     * upgrade.
     *
     * <p>The original type stays on the record permanently, so a netherite
     * pickaxe still shows it was born diamond.
     */
    private static void noteUpgrade(ItemRecord record, String currentItemId, RecordRegistry registry) {
        if (record == null || currentItemId == null || currentItemId.equals(record.currentItemId())) {
            return;
        }
        Provenance.LOGGER.info("Provenance {} upgraded: {} -> {} (history preserved)",
                record.recordId(), record.currentItemId(), currentItemId);
        registry.applyUpgrade(record, currentItemId);
    }

    /**
     * Keeps the record's display name in step with the anvil.
     *
     * <p>Purely cosmetic, and deliberately separate from the earned milestone
     * title: a pickaxe renamed "Steelbreaker" still shows "Veteran" as its
     * authenticated tier.
     */
    private static void syncCustomName(ItemStack stack, ItemRecord record) {
        if (record == null) {
            return;
        }
        net.minecraft.network.chat.Component custom =
                stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        String name = custom == null ? null : custom.getString();
        if (!java.util.Objects.equals(name, record.customName())) {
            record.setCustomName(name);
        }
    }

    /**
     * Read-only lookup for tooltips and inspection. Does not register, migrate
     * or rotate anything, so hovering over an item can never change it.
     */
    public static ItemRecord peek(ItemStack stack) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null || stack.isEmpty()) {
            return null;
        }
        return state.registry().peek(readStamp(stack));
    }

    /**
     * True when the action should be recorded at all. Creative-mode activity is
     * excluded unless a server opts in, so testing in creative does not inflate
     * a real item's history.
     */
    public static boolean shouldCount(ServerPlayer player) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null || player == null) {
            return false;
        }
        if (player.isCreative() && !state.config().creativeActionsCount()) {
            return false;
        }
        return !player.isSpectator();
    }
}
