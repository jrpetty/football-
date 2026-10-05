package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Coal, or charcoal, in the stores most days.
 *
 * <p>The hundred days' village went thirty-five days in the Wood Age with no coal in its stores on all
 * but a handful of mornings, and on into the Stone Age with none: the miners' coal went on their own
 * torches and the watch's, sixteen at a time whenever any coal was about; the smelter kept thirty-two in
 * its pack for its furnaces; and charcoal was only ever made once the age itself asked for coal, though
 * the stores held two hundred and more logs. A player with no coal does not go without: they burn logs
 * into charcoal. So now:
 * <ul>
 * <li>A village keeps a floor of coal in its stores whatever its age ({@link #floor}: sixteen, one a head
 *     beyond that, at most forty-eight). Under it (VillageFolkEntity.coalLow) the smelter burns logs the
 *     builders can spare into charcoal for the stores first thing, ore or no ore, as it does when the
 *     age asks for coal; it fires its furnaces on wood first (SmeltGoal), banks the charcoal it makes
 *     rather than keeping it as fuel; the couriers take it wood and not the last coal; and the stores'
 *     torches are made of charcoal only (Links.torches).</li>
 * <li>Not in the Wood Age while the age still wants timber: the houses come first.</li>
 * <li>Torches are made out of coal in hand, as many as it makes and no more, and not out of the
 *     village's coal while it is short or under the floor: a miner may light its shaft with what it dug
 *     itself, but the watch waits (AssistantEntity.torchLumps).</li>
 * </ul>
 * A smelter's own coal (given it, or drawn while the stores had plenty) is still its fuel: the floor is
 * about what the stores hold, not about taking the fire out of a smelter's hands.
 */
public final class Fuel {

    private Fuel() {}

    /** The coal (or charcoal) a village keeps in its stores whatever its age: torches for the mines and the watch,
     *  and fuel for the smelter's work. */
    public static int floor(int folk) {
        return Math.max(16, Math.min(48, folk));
    }

    /** Coal and charcoal in the village's stores. */
    public static int inStores(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return 0;
        return Villages.stock(level, v.centre(), Villages.Task.COAL, Villages.storesRadius(village));
    }

    /** Are the stores under the floor? */
    public static boolean low(ServerLevel level, @Nullable UUID village) {
        if (village == null || Villages.get(village) == null) return false;
        return inStores(level, village) < floor(Villages.headcount(village));
    }

    /** Does the village's age ask for more coal than it has? */
    static boolean ageWants(ServerLevel level, UUID village, Villages.Task task) {
        for (Villages.Need n : Villages.needs(level, village)) if (n.task() == task) return true;
        return false;
    }

    /**
     * Is charcoal wanted? When the age asks for coal (as it always was), or the stores are under the floor;
     * but in the Wood Age not while the age still wants timber, for the houses come first. (What logs it
     * may burn is the smelter's own rule: only what the builders can spare.)
     */
    public static boolean charcoalWanted(ServerLevel level, @Nullable UUID village) {
        if (village == null) return false;
        if (ageWants(level, village, Villages.Task.COAL)) return true;
        if (!low(level, village)) return false;
        return Villages.ageOf(village) != Villages.Age.WOOD || !ageWants(level, village, Villages.Task.LOGS);
    }
}
