package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * [pa33] Which chests a Player AI Assistant may open. Asked through the one chokepoint every chest question in the
 * mod passes (ZoneChests.isPrivate), and only while a Player AI Assistant is thinking (a thread local, set for its
 * tick): for anybody else, folk and crew alike, this always answers "not barred" and changes nothing.
 *
 * <p>Never a town's stores, never a chest on a town's ground or in someone else's land (with the server's griefing
 * safety on), never a signed chest (that rule is ZoneChests' own). With its player's "may use your chests" off, only
 * the chests its player has pointed it at.
 */
public final class PaChests {

    private PaChests() {}

    private static final ThreadLocal<PlayerAssistantEntity> ASKING = new ThreadLocal<>();

    /** Set who is asking; returns who was before, to restore. */
    public static PlayerAssistantEntity askAs(PlayerAssistantEntity a) {
        PlayerAssistantEntity before = ASKING.get();
        if (a == null) ASKING.remove(); else ASKING.set(a);
        return before;
    }

    public static boolean barred(Level level, BlockPos chest) {
        PlayerAssistantEntity a = ASKING.get();
        if (a == null || level.isClientSide) return false;
        return !allowed(a, level, chest);
    }

    /** May this assistant open this container? (Also asked directly by its own goals and the tests.) */
    public static boolean allowed(PlayerAssistantEntity a, Level level, BlockPos chest) {
        if (a.pointedChest(chest)) return true;                       // its player said "that chest"
        BlockEntity be = level.getChunkSource().getChunkNow(chest.getX() >> 4, chest.getZ() >> 4) == null ? null
            : level.getBlockEntity(chest);
        if (be != null && com.jrpetty.mcassistant.entity.ZoneChests.isVillageStore(be)) return false;   // a town's stores
        if (PaSafety.offLimits(a, chest)) return false;                // a town's ground, someone else's land
        PaData.Rec r = a.rec();
        return r == null || r.useChests;
    }
}
