package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * The personal planner's one way in (the plan's USER-PICKED ADDITIONS: WP-D builds the screen; every package feeds it).
 * [wpb] A no-op stub so the packages can call it now: shifts, interviews, leave, applications, contracts. WP-A may add the
 * same stub; keep this signature exactly so the two merge cleanly, and WP-D replaces the body.
 */
public final class Planner {

    private Planner() {}

    /** An entry on the player's planner: the day, the hour (0-23), its kind ("shift", "interview"...), its words, its place. */
    public static void add(ServerPlayer p, long day, int hour, String kind, String text, @Nullable BlockPos where) {
        // stub: the planner screen (WP-D) keeps and shows these
    }
}
