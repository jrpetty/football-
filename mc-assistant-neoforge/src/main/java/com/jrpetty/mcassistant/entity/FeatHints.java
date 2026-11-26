package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * [featbuild] The first-time hints for the walls and the night watch, the parks and monuments, and the quarry and the stone
 * yard: the first time a player is about the town when one of them comes into being, a line over the hotbar says what it
 * is and where on the board to read about it. Once a player per town per feature, kept with the world.
 */
public final class FeatHints {

    private FeatHints() {}

    /** How near the town a player must be to be told. */
    static final int NEAR = 160;

    /** Hint every player near who has not had this one in this town. Returns how many were told. */
    public static int hint(ServerLevel level, UUID town, BlockPos at, String key, String text) {
        String note = "featbuild.hint." + key;
        String was = Ledger.note(town, note);
        Set<String> told = new HashSet<>();
        if (was != null && !was.isEmpty()) for (String s : was.split(",")) told.add(s);
        int n = 0;
        for (ServerPlayer p : level.players()) {
            if (!p.blockPosition().closerThan(at, NEAR)) continue;
            if (!told.add(p.getStringUUID())) continue;
            p.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.YELLOW), true);
            n++;
        }
        if (n > 0) Ledger.note(town, note, String.join(",", told));
        return n;
    }
}
