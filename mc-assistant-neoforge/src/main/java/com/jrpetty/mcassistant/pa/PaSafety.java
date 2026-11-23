package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;

/**
 * [pa33] Griefing safety for the Player AI Assistant: it never breaks or places a block, or opens a chest, on a
 * town's ground or in someone else's land, unless the server allows it (PaConfig). Asked by its own overrides of the
 * work goals' "may I work here" (inZone, inZoneColumn), by PaChests, and before any order that would build or clear.
 */
public final class PaSafety {

    private PaSafety() {}

    /** Is this block somewhere this assistant must not touch? */
    public static boolean offLimits(PlayerAssistantEntity a, BlockPos pos) {
        if (!PaConfig.safety()) return false;
        com.jrpetty.mcassistant.entity.Villages.Village town = com.jrpetty.mcassistant.entity.TownBlocks.townAt(a.level(), pos);
        if (town == null) return false;
        java.util.UUID owner = a.getOwnerId();
        if (PaConfig.workInOwnTown() && owner != null && com.jrpetty.mcassistant.entity.Citizens.is(town.id(), owner)) return false;
        return true;
    }

    /** The name of the town whose ground this is, for telling the player why not ("" when none). */
    public static String whose(PlayerAssistantEntity a, BlockPos pos) {
        com.jrpetty.mcassistant.entity.Villages.Village town = com.jrpetty.mcassistant.entity.TownBlocks.townAt(a.level(), pos);
        return town == null ? "" : com.jrpetty.mcassistant.entity.Villages.name(town.id());
    }
}
