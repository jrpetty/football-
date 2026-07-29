package com.provenance.mod.client;

import com.provenance.mod.HistorySnapshot;
import net.minecraft.client.Minecraft;

/**
 * Holds the most recent snapshot the server sent and opens or refreshes the
 * screen with it.
 *
 * <p>Purely a display buffer. Nothing here is authoritative and nothing here is
 * ever sent back.
 */
public final class ClientHistoryCache {

    private static HistorySnapshot latest;

    private ClientHistoryCache() {
    }

    public static void accept(HistorySnapshot snapshot) {
        latest = snapshot;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ItemHistoryScreen screen) {
            screen.refresh(snapshot);
        } else {
            minecraft.setScreen(new ItemHistoryScreen(snapshot));
        }
    }

    public static HistorySnapshot latest() {
        return latest;
    }
}
