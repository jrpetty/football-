package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * [wpb] The seams WP-C (property) fills in later: premises a firm trades from, and land titles. No-op stubs so WP-B's
 * business code (Enterprise.bankrupt, found and close; FirmSale; PlayerFarms) calls them now and WP-C's real Premises
 * replaces this file. Keep these signatures.
 */
public final class Premises {

    private Premises() {}

    /** A firm gone bankrupt, before its creditors are paid: its firm-held premises sold to them (WP-C). */
    public static void onBankrupt(ServerLevel level, UUID village, Business b) {
        // stub (WP-C)
    }

    /** A firm opened or closed: its premises linked to it (open) or released (close) in the register (WP-C). */
    public static void linkFirm(ServerLevel level, UUID village, Business b, boolean open) {
        // stub (WP-C)
    }

    /** A firm sold whole: its premises (and their titles) go with it to the buyer (WP-C). */
    public static void transferWithFirm(ServerLevel level, UUID village, Business b, UUID from, UUID to) {
        // stub (WP-C)
    }

    /** Land a player holds title to, for registering a farm on it (WP-C's land titles); false until WP-C lands. */
    public static boolean holdsTitle(ServerLevel level, UUID village, UUID player, int x0, int z0, int x1, int z1) {
        return false;
    }
}
