package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * [featfolk] The board's pages for the town's lines (client FamiliesPage, ApprenticesPage, RespectPage), added to the
 * books sent to one player (AssistantNetwork.sendCityStats): the family trees, the masters and apprentices, and what the
 * town thinks of that player and why, from its witnesses.
 */
public final class FolkLinesReport {

    private FolkLinesReport() {}

    public static void viewer(CompoundTag books, ServerLevel level, Villages.Village v, ServerPlayer player) {
        try {
            books.put("families", Lineage.report(level, v.id()));
            books.put("apprentices", Apprenticeship.report(v.id()));
            books.put("respect", TownOpinion.report(v.id(), player.getUUID(), level.getGameTime()));
        } catch (RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().warn("[featfolk] the town's lines for the board: {}", e.toString());
        }
    }
}
