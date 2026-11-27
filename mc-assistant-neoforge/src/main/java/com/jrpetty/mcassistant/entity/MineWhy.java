package com.jrpetty.mcassistant.entity;

/**
 * [econ32b] Why a miner was sent up out of the works, or gave up its shift on the way to the pithead: with
 * {@code -Dmcassistant.minewhy=true} each is logged ("[MCA-MINEWHY]") with where it stood and what it was at, so the
 * climb-outs a town's books count ("Out of the works") can be told apart.
 */
public final class MineWhy {

    private MineWhy() {}

    static final boolean LOG = Boolean.getBoolean("mcassistant.minewhy");

    public static void note(AssistantEntity a, String why) {
        if (!LOG || !(a instanceof VillageFolkEntity f) || f.ownerId() == null) return;
        net.minecraft.core.BlockPos site = TownMine.siteOf(f.ownerId());
        String where = site == null ? "-" : (int) Math.sqrt(f.blockPosition().atY(0).distSqr(site.atY(0))) + " from the shaft";
        Job j = f.peekJob();
        com.mojang.logging.LogUtils.getLogger().info("[MCA-MINEWHY] {} {}: {} at {} ({}); job {}; {}", Villages.name(f.ownerId()), f.displayNameCap(),
            why, f.blockPosition().toShortString(), where, j == null ? "-" : j.type() + ":" + j.arg(), f.debugLine().replaceAll(" traits=.*", ""));
    }
}
