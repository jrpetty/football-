package com.jrpetty.mcassistant.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [mine-safety] Nobody is carried off in a boat or a cart it never meant to board.
 *
 * <p>The game's boat takes aboard any creature that bumps into it, a player's apart, and a creature
 * never gets out of its own accord. Every fisher's jetty has a boat tied up alongside it (Waterfront),
 * and on the sixty-day plains game four folk of seven ended up sitting in two of them, a pair to a boat,
 * at the water's level thirty blocks under the town: a farmer and the fisher from the fifth day, two
 * miners from the twentieth (one of them made a farmer since), to the last day. The farmers farmed
 * nothing, the larder ran out, and no child was born. Every rescue the town had (put back on its plot, set down by its bed, by its chest)
 * moved them and said so, and the boat set them back in their seats on the next tick: a creature
 * aboard goes where its boat goes. Their cards said "stepped aside out of a wedge" for forty days.
 *
 * <p>So a folk boards a boat or a cart only when it means to (a boat trip it was sent on: BoatGoal);
 * and one that finds itself aboard all the same (a world saved with folk in the boats) steps out, the
 * way a player would, and wades ashore. A horse is another matter: folk ride them on purpose (Riding,
 * Stables).
 */
public final class Aboard {

    private Aboard() {}

    /** Folk that stepped out of a boat or a cart they never meant to board, by village: for the books. */
    private static final Map<UUID, Integer> STEPPED_OUT = new ConcurrentHashMap<>();

    /** May this folk get into, or be taken into, this vehicle? A boat or a cart only on a boat trip of its own. */
    public static boolean mayBoard(VillageFolkEntity f, Entity vehicle) {
        if (!(vehicle instanceof Boat) && !(vehicle instanceof AbstractMinecart)) return true;
        if (Fleet.crewing(f, vehicle)) return true;                   // [fleet] its own boat, on its day with the fishing fleet
        Job j = f.peekJob();
        return j != null && j.type() == Job.Type.BOAT;
    }

    /**
     * Once a second (VillageFolkEntity.aiStep): aboard a boat or a cart it never meant to board, it gets
     * out. The game sets it down beside the boat on dry ground where there is some; in the water, it
     * swims for the bank like anybody else (AssistantEntity.climbOutOfTheWater).
     */
    public static void step(VillageFolkEntity f) {
        Entity v = f.getVehicle();
        if (v == null || mayBoard(f, v)) return;
        String what = v instanceof Boat ? "boat" : "cart";
        f.stopRiding();
        f.getNavigation().stop();
        if (f.ownerId() != null) STEPPED_OUT.merge(f.ownerId(), 1, Integer::sum);
        f.brain("got out of a " + what + " it never meant to board");
        com.mojang.logging.LogUtils.getLogger().info("[MCA-BOAT] {} of {} was sitting in a {} at {}: got out",
            f.getName().getString(), f.ownerId() == null ? "nowhere" : Villages.name(f.ownerId()), what, f.blockPosition().toShortString());
    }

    /** What the folk card says it is sitting in, or null when it is on its own feet. */
    public static String riding(AssistantEntity a) {
        Entity v = a.getVehicle();
        if (v == null) return null;
        return v instanceof Boat ? "boat" : v instanceof AbstractMinecart ? "cart"
            : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).getPath();
    }

    /** How many of this village's folk have stepped out of a boat or a cart they never meant to board. */
    public static int steppedOut(UUID village) {
        return STEPPED_OUT.getOrDefault(village, 0);
    }

    public static void resetForTests() {
        STEPPED_OUT.clear();
    }
}
