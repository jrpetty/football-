package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [pa35] The mining ladder at work. Every block a work goal would take is asked here (through the assistant's
 * inZone, which every gathering, mining, tunnelling and clearing goal asks): an ore above its level stays put. Met
 * close by and in plain sight, it says so once per vein, in its own voice, and pings it for its player (Settings,
 * Behaviour: on by default). Asked outright for a locked ore, it says how far off the level is and what it can do
 * instead. The gate itself is PaLevels.canMine, which the logistics jobs call as well.
 */
public final class PaMineLock {

    private PaMineLock() {}

    /** Veins it has told its player about lately, per assistant: so once per vein, not once per block. */
    private static final Map<UUID, Deque<long[]>> TOLD = new ConcurrentHashMap<>();

    /** May it take the block here? A locked ore close by and showing is mentioned (once a vein). */
    public static boolean allowed(PlayerAssistantEntity a, BlockPos pos) {
        if (a.level().isClientSide) return true;
        BlockState st = a.level().getBlockState(pos);
        if (PaLevels.canMine(a, st)) return true;
        if (pos.distSqr(a.blockPosition()) <= 5 * 5 && com.jrpetty.mcassistant.entity.OreSight.exposed(a.level(), pos)) met(a, pos, st);
        return false;
    }

    /** A locked ore met in the course of work: noted and told, once per vein (within 8 blocks, ten minutes). */
    static void met(PlayerAssistantEntity a, BlockPos pos, BlockState st) {
        long now = a.level().getGameTime();
        Deque<long[]> told = TOLD.computeIfAbsent(a.getUUID(), k -> new ArrayDeque<>());
        while (!told.isEmpty() && now - told.peekFirst()[3] > 12000) told.pollFirst();
        for (long[] t : told) if (Math.abs(t[0] - pos.getX()) <= 8 && Math.abs(t[1] - pos.getY()) <= 8 && Math.abs(t[2] - pos.getZ()) <= 8) return;
        told.addLast(new long[]{pos.getX(), pos.getY(), pos.getZ(), now});
        while (told.size() > 32) told.pollFirst();
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        PaMineLadder.Rung rung = PaMineLadder.rung(path);
        String what = rung == null ? st.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT) : rung.says();
        int need = PaLevels.mineLevel(st);
        PaData.Rec r = a.rec();
        if (a.getOwnerPlayer() instanceof ServerPlayer owner && (r == null || r.pingLocked)) PaNet.ping(owner, pos, PaNet.cap(what), "GATHER", "found");
        if (r != null && r.mute >= 2) return;
        PaTalk.comment(a, 0, PaTalk.line(a, "lock.met", "what", what, "level", Integer.toString(need)), true);
    }

    /** Asked for an ore it may not mine yet: how far off it is, and what it can do instead. Null when it may. */
    public static String refusal(PlayerAssistantEntity a, String oreWord) {
        PaMineLadder.Rung rung = PaMineLadder.forWord(oreWord);
        if (rung == null || !PaConfig.ladderOn()) return null;
        int need = PaMineLadder.level(rung, PaConfig.ladderLevels()), lv = a.veteranLevel();
        if (lv >= need) return null;
        String instead = "";
        for (PaMineLadder.Rung r : PaMineLadder.RUNGS) if (PaMineLadder.level(r, PaConfig.ladderLevels()) <= lv) instead = r.says();
        return PaTalk.line(a, "lock.refuse", "what", rung.says(), "level", Integer.toString(need), "mine", Integer.toString(lv),
            "time", PaLevels.timeTo(a.paXp(), need), "instead", instead.isEmpty() ? "stone" : instead);
    }

    /** Game tests: forget what it has told. */
    public static void resetForTests() {
        TOLD.clear();
    }
}
