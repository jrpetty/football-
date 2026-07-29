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
 * <p>Everything here is server-only. The client is never trusted to say which
 * record a stack belongs to; it merely carries the stamp it was given.
 */
public final class Stamps {

    private Stamps() {
    }

    public static String itemId(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? null : id.toString();
    }

    /** The stack's tag ids, in the form the configuration matches against. */
    public static List<String> tagsOf(ItemStack stack) {
        List<String> tags = new ArrayList<>();
        stack.getTags().forEach(tag -> tags.add(tag.location().toString()));
        return tags;
    }

    public static ItemCategory categoryOf(ProvenanceConfig config, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        return config.resolveCategory(itemId(stack), tagsOf(stack));
    }

    public static ItemStamp readStamp(ItemStack stack) {
        return stack.get(ProvenanceComponents.STAMP.get());
    }

    public static void writeStamp(ItemStack stack, ItemStamp stamp) {
        stack.set(ProvenanceComponents.STAMP.get(), stamp);
    }

    /**
     * Returns the authoritative record for a stack, registering one if the item
     * is eligible and has never been seen.
     *
     * <p>This is the single funnel every gameplay handler goes through, which
     * is what makes the guarantees hold uniformly:
     *
     * <ul>
     *   <li>An ineligible item yields null and is never tracked.</li>
     *   <li>An unstamped eligible item is adopted as a Legacy Item — a new id,
     *       an honest migration date, no invented maker, counters at zero.</li>
     *   <li>A stamped item is validated. A stale token means this stack is a
     *       copy, so it is given a fresh empty identity rather than the
     *       original's history.</li>
     * </ul>
     *
     * @param player the player acting on the stack, used for ownership context
     *               on first registration; may be null for world interactions
     */
    public static ItemRecord resolve(ItemStack stack, ServerPlayer player) {
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
            ItemRecord record = registry.registerLegacy(itemId, category,
                    player == null ? null : player.getUUID(),
                    player == null ? null : player.getGameProfile().getName());
            writeStamp(stack, ItemStamp.of(record));
            return record;
        }

        RecordRegistry.Claim claim = registry.claim(stamp, itemId, category,
                player == null ? null : player.getUUID(),
                player == null ? null : player.getGameProfile().getName());

        switch (claim.status()) {
            case VALID, DUPLICATE -> {
                writeStamp(stack, claim.stamp());
                return claim.record();
            }
            case UNKNOWN -> {
                // The stamp references an id the server has never issued: a
                // hand-edited stack, or data from a world this record store
                // does not belong to. Re-register rather than trust it.
                ItemRecord record = registry.registerLegacy(itemId, category,
                        player == null ? null : player.getUUID(),
                        player == null ? null : player.getGameProfile().getName());
                writeStamp(stack, ItemStamp.of(record));
                return record;
            }
            default -> {
                return null;
            }
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
