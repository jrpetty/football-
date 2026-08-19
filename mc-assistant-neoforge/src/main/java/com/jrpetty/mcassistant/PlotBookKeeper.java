package com.jrpetty.mcassistant;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The plot book survives its owner's death. Player persistent data is copied
 * to the respawned player only under the "PlayerPersisted" subtree — the book
 * lives at the root (it predates this class), so without this hook one death
 * quietly erased every plot and saved patch the player had.
 */
public final class PlotBookKeeper {

    private static final String KEY = "mc_assistant_presets";

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        var old = event.getOriginal().getPersistentData();
        if (old.contains(KEY)) {
            event.getEntity().getPersistentData().put(KEY, old.get(KEY).copy());
        }
    }
}
