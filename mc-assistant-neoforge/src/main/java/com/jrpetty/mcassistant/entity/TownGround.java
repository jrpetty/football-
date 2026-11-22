package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * [life28] The town's open ground: its square and its streets, where nothing is set down loose. A hand that wants a
 * crafting table to craft at, in the square or a street, crafts at the stores' bench instead (CraftGoal); a trade that
 * sets its workstation down by its post does it off the street (Trades.floorSpot); and anything set down there all the
 * same is taken up into the stores by the square's sweep (SquareDress). A grown town's square, filmed, had two crafting
 * tables stacked by a chest on it.
 */
public final class TownGround {

    private TownGround() {}

    /** Is this spot on a town's square or one of its streets (and in none of its buildings)? */
    public static boolean open(Level world, BlockPos pos) {
        if (!(world instanceof ServerLevel level)) return false;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int dx = pos.getX() - v.centre().getX(), dz = pos.getZ() - v.centre().getZ();
            int d = Math.max(Math.abs(dx), Math.abs(dz));
            if (d > Villages.townReach(v.id()) + 4 || Math.abs(pos.getY() - v.centre().getY()) > 24) continue;
            if (d > TownPlan.PLAZA && !TownPlan.isStreet(dx, dz)) continue;
            return TownBlocks.buildingAt(level, v, pos) == null;
        }
        return false;
    }
}
