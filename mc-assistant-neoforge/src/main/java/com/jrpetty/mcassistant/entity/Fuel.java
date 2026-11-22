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
 *
 * <p>[wf] And while the age is short of coal, two miners in three take their mines up to the coal seam
 * ({@link #coalSeamFor}, VillageFolkEntity.seekTheSeam) instead of down at the iron, and stay there till
 * the stores hold half as much again as the age asks ({@link #coalSeamWanted}).
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

    // ------------------------------------------------------------------ [growth24] the smelters' coal

    /** Over the floor, this much more coal is kept back for the smelters (two of their draws) from every other use. */
    public static final int SMELT_SPARE = 16;

    /**
     * [growth24] Is the stores' coal kept back for the smelters now: under the floor and two smelters' draws more? A
     * smelter draws coal only while the stores are over the floor (VillageFolkEntity.coalLow); the town's other uses (a
     * street light's torch, a campfire, the fish smoked, the lighthouse lit, a festival's bonfire) drew it from nothing
     * upward, one at a time, and an Iron Age town of sixty-five held 0 to 4 coal from one week to the next with three
     * smelters and its iron raw. Those uses now wait (or burn wood) while the stores are this low.
     */
    public static boolean keptForSmelting(ServerLevel level, @Nullable UUID village) {
        if (village == null || Villages.get(village) == null) return false;
        return inStores(level, village) < floor(Villages.headcount(village)) + SMELT_SPARE;
    }

    /** [growth24] Tests: so much coal (or charcoal) taken out of the stores for the town's works (TownWork.take). */
    public static boolean takeCoalForTests(ServerLevel level, UUID village, int n) {
        Villages.Village v = Villages.get(village);
        return v != null && TownWork.take(level, v, s -> s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL), n);
    }

    /** Does this draw on the stores take coal or charcoal? */
    public static boolean drawsCoal(java.util.function.Predicate<net.minecraft.world.item.ItemStack> what) {
        return what.test(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL))
            || what.test(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHARCOAL));
    }

    // ------------------------------------------------------------------ [wf] the coal seam

    /**
     * Where coal lies thickest in the ground this game makes, below the mountains' own band: ninety-six.
     * It thins to nothing at the bottom of the band, at nought, and the iron seam the mines are taken
     * down to is at sixteen, where there is a sixth as much. A mountain town of seventy-seven with twenty
     * miners sat in the Stone Age for want of coal, its stores swinging between thirty-two and ninety-four
     * from one morning to the next against the forty-eight the age asked for, every one of its mines
     * down at the iron.
     */
    public static final int COAL_SEAM_Y = 96;
    /** Above this the mountains' own coal begins, as thick all the way up: a mine high in a mountain digs there. */
    static final int UPPER_COAL_Y = 136;
    /** Rock left over a coal gallery: the seam is dug under the ground, not along the top of it. */
    static final int COAL_COVER = 12;

    /**
     * The depth a mine on ground this high goes to for its coal: ninety-six, or twelve under the ground
     * if that is higher up in the mountains' band, or shallower; or -1 where that would be little better
     * than the iron seam itself ({@code ironSeam}), on low ground.
     */
    public static int coalSeamFor(int groundY, int ironSeam) {
        int under = groundY - COAL_COVER;
        int y = under >= UPPER_COAL_Y ? under : Math.min(COAL_SEAM_Y, under);
        return y >= ironSeam + 8 ? y : -1;
    }

    /**
     * Is a mine wanted at the coal seam? While the age asks for coal (the town is short of it for its
     * next age), yes. And a mine already there stays till the stores hold half as much again as the age
     * asks, or the age has moved on: the stores swung across the age's mark from day to day, and a mine
     * sent up and down its staircase with every swing would dig nothing but stairs.
     */
    public static boolean coalSeamWanted(ServerLevel level, UUID village, boolean there) {
        if (ageWants(level, village, Villages.Task.COAL)) return true;
        if (!there || Villages.ageOf(village) != Villages.Age.STONE) return false;
        int folk = Math.min(Villages.headcount(village), Villages.AGE_FOLK);
        return inStores(level, village) < com.jrpetty.mcassistant.village.VillageMath.coalWanted(folk) * 3 / 2;
    }
}
