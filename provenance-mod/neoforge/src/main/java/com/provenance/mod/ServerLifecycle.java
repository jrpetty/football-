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
     * Drops the player's movement baseline so the gap between logging out in
     * one place and logging in somewhere else is never billed as travel.
     */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ProvenanceState state = ProvenanceState.get();
        if (state != null) {
            state.distances().forget(event.getEntity().getUUID());
        }
    }
}
