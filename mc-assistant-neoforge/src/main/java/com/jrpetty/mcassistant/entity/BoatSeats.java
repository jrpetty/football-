package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.DiningItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;

import javax.annotation.Nullable;

/**
 * [roles-d] A player's seat in a boat behind a folk at the oars (the ferryman's, a fisher's). The game seats a player who
 * climbs into a boat in front, at the oars, whoever else is in it; the player would then be rowing, and the folk could
 * not take it anywhere. So the player sits on an unseen seat (DiningSeat, not saved) that is itself the boat's second
 * passenger: the folk keeps the oars, and the player rides along. Off the seat (crouching, or set down on the far side),
 * the seat goes; a seat whose boat is gone goes too, and stands its rider up.
 */
public final class BoatSeats {

    private BoatSeats() {}

    /** Sit the player in the boat's second place, behind whoever is at the oars. False if there is no room. */
    public static boolean seat(ServerLevel level, Player p, Boat b) {
        if (b.getPassengers().size() >= 2 || p.isPassenger()) return false;
        DiningSeat seat = new DiningSeat(DiningItems.SEAT.get(), level);
        seat.moveTo(b.getX(), b.getY(), b.getZ(), b.getYRot(), 0.0F);
        if (!level.addFreshEntity(seat)) return false;
        if (!seat.startRiding(b, true)) {
            seat.discard();
            return false;
        }
        if (!p.startRiding(seat, true)) {
            seat.discard();
            return false;
        }
        return true;
    }

    /** Is this a seat (one of the boat's passengers that a player sits on)? */
    public static boolean isSeat(Entity e) {
        return e instanceof DiningSeat;
    }

    /** The player sitting on this seat, or null. */
    @Nullable
    public static Player rider(Entity e) {
        return e instanceof DiningSeat && e.getFirstPassenger() instanceof Player p ? p : null;
    }
}
