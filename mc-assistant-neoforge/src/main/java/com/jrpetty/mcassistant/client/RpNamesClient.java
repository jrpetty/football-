package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.BelongingSync;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * [wpa] The role-play names sent by the server (BelongingSync): kept, and every player in view given its name again, so
 * the plate over its head reads as the town knows it.
 */
public final class RpNamesClient {

    private RpNamesClient() {}

    public static void apply(CompoundTag names) {
        BelongingSync.CLIENT_NAMES.clear();
        for (String k : names.getAllKeys()) {
            try {
                BelongingSync.CLIENT_NAMES.put(UUID.fromString(k), names.getString(k));
            } catch (IllegalArgumentException ignored) { }
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Player p : mc.level.players()) p.refreshDisplayName();
    }
}
