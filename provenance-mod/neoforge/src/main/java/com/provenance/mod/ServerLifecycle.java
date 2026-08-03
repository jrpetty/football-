package com.provenance.mod;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Opens and closes the record store with the server, and cleans up per-player state. */
public final class ServerLifecycle {

    private ServerLifecycle() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        ProvenanceState.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ProvenanceState.stop();
    }

    /**
     * Login is the custody checkpoint.
     *
     * <p>Duplication is caught here rather than on every action. A one-off pass
     * over the player's inventory validates and re-stamps their equipment,
     * which is enough to catch a duplicate entering circulation while costing
     * nothing during play. Gameplay itself never rotates tokens, so an ordinary
     * hiccup can no longer look like duplication.
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }

        var inventory = player.getInventory();
        int checked = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (Stamps.claimCustody(stack, player, true) != null) {
                checked++;
            }
        }
        if (checked > 0) {
            Provenance.LOGGER.debug("Provenance validated {} item(s) for {}",
                    checked, player.getGameProfile().getName());
        }
    }

    /**
     * Writes out whatever the player accrued, then drops their movement
     * baseline so the gap between logging out in one place and logging in
     * somewhere else is never billed as travel.
     */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ProvenanceState state = ProvenanceState.get();
        if (state != null) {
            state.flushUsage();
            state.distances().forget(event.getEntity().getUUID());
        }
    }
}
