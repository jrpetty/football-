package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The builders' stock goes back to the stores: what a hand carries about that is the village's and not
 * its trade's.
 *
 * <p>The hundred days' guard, Bryn, walked its beat with two hundred and twenty-one things in its pack:
 * eighty-eight oak logs, seventy-two oak stairs, twelve slabs, ten glass, eight sand, six copper ingots,
 * three doors. The stairs, the slabs, the glass and the doors were what it had drawn and cut for a
 * building another hand went on to lead (a lead that lapses hands back its timber and stone, never its
 * finishing); the logs came sixteen at a time out of the stores on its tries at torches with no coal to
 * make them of. Nothing ever sent them back: a hand banks its own trade's work, and a guard's trade is
 * bones and string. The village meanwhile sat in the Wood Age short of timber.
 *
 * <p>So once a minute a hand with nothing queued looks at its pack. Anything the builders use (timber,
 * planks, stone and brick, stairs, slabs, doors, glass and panes, sand, ingots, fences, ladders, beds)
 * that is not its own trade's work (a woodcutter's logs, a miner's stone, which its own rules bank) and
 * not held back (its kit, the building it is leading), goes back to the stores: at once for a load of
 * sixteen or more, and anything less once it has been carried about for three minutes. The smelter, the
 * crafts, the couriers and the storekeeper work with the builders' stock, and are let be.
 */
public final class Strays {

    private Strays() {}

    /** How often a hand looks at what it carries, in ticks. */
    static final long LOOK = 1200;
    /** A load worth the walk at once... */
    static final int LOAD = 16;
    /** ...and how long a smaller one may be carried about before it goes in anyway. */
    static final long LINGER = 3600;

    /** A run that moved nothing (the stores full, or out of reach): this long before it is tried again. */
    static final long BACK_OFF = 6000;

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SINCE = new ConcurrentHashMap<>();
    /** What it carried when it last set off with it: the same again or more, and the run moved nothing. */
    private static final Map<UUID, Integer> SENT = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        SINCE.clear();
        SENT.clear();
    }

    /** Is this the builders' stock, the village's? */
    public static boolean buildersStock(ItemStack s) {
        if (s.isEmpty()) return false;
        return BuildGoal.isBuildingBlock(s) || s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)
            || finishing(s)
            || s.is(Items.SAND) || s.is(Items.RED_SAND) || s.is(Items.GRAVEL) || s.is(Items.CLAY_BALL)
            || s.is(Items.IRON_INGOT) || s.is(Items.COPPER_INGOT) || s.is(Items.GOLD_INGOT) || s.is(Items.BRICK)
            || s.is(Items.LADDER) || s.is(ItemTags.FENCES) || s.is(ItemTags.FENCE_GATES) || s.is(ItemTags.BEDS)
            || s.is(com.jrpetty.mcassistant.McAssistantMod.STOREHOUSE_ITEM.get());
    }

    /**
     * The finishing a builder cuts for a building: roof stairs and slabs, doors and trapdoors, glass and
     * panes, lanterns, barrels, bookshelves. What a lapsed lead hands back with its timber and stone
     * (VillageFolkEntity.handBackTheBuild).
     */
    public static boolean finishing(ItemStack s) {
        return s.is(ItemTags.STAIRS) || s.is(ItemTags.SLABS) || s.is(ItemTags.WALLS) || s.is(ItemTags.DOORS)
            || s.is(ItemTags.TRAPDOORS) || s.is(Items.GLASS) || s.is(Items.GLASS_PANE)
            || s.is(Items.LANTERN) || s.is(Items.BARREL) || s.is(Items.BOOKSHELF) || s.is(Items.HAY_BLOCK);
    }

    /** What this trade works with itself, and its own rules bank or keep. */
    static boolean ownWork(StationTask t, ItemStack s) {
        return switch (t) {
            case WOOD -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS);
            case MINE -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.STONE) || s.is(Items.DEEPSLATE)
                || s.is(Items.ANDESITE) || s.is(Items.DIORITE) || s.is(Items.GRANITE) || s.is(Items.TUFF) || s.is(Items.CALCITE)
                || s.is(Items.DIRT) || s.is(Items.GRAVEL) || s.is(Items.SAND) || s.is(Items.RED_SAND) || s.is(Items.LADDER);
            default -> false;
        };
    }

    /** The trades looked at: those whose own work is not the builders' stock. */
    static boolean looksAt(StationTask t) {
        return switch (t) {
            case GUARD, FARM, FISH, HUNT, RANCH, SCOUT, WOOD, MINE, NONE -> true;
            default -> false;
        };
    }

    /** How much of the builders' stock this hand carries that is free to go back (not its kit, not a lead's building). */
    public static int carried(VillageFolkEntity f) {
        StationTask t = f.stationTask();
        return f.countStashable(s -> buildersStock(s) && !ownWork(t, s));
    }

    /**
     * A look at the pack, from the folk's own round when it has nothing queued. True if it set off for the
     * stores with the builders' stock.
     */
    public static boolean tend(VillageFolkEntity f) {
        if (f.isBaby() || !f.isAlive()) return false;
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return false;
        UUID me = f.getUUID();
        long now = level.getGameTime();
        Long last = LOOKED.get(me);
        if (last != null && now - last < LOOK) return false;          // (a run that moved nothing puts this ahead)
        LOOKED.put(me, now);
        // The hand raising the village's building keeps it in hand (and its buildReserve keeps it besides).
        if (Villages.holdsTheLead(village, me, now)) {
            SINCE.remove(me);
            return false;
        }
        // A lead that has lapsed lets go of what it drew, whatever its trade: it is the stores' again, and
        // its own deposits take it in from now (a smelter's, a courier's) or the look below does.
        f.releaseLapsedBuild();
        if (!looksAt(f.stationTask())) return false;
        int n = carried(f);
        if (n <= 0) {
            SINCE.remove(me);
            SENT.remove(me);
            return false;
        }
        Integer sent = SENT.remove(me);
        if (sent != null && n >= sent) {
            // The last run moved none of it: not every minute to stores that will not take it.
            LOOKED.put(me, now + BACK_OFF - LOOK);
            return false;
        }
        long since = SINCE.computeIfAbsent(me, k -> now);
        if (n < LOAD && now - since < LINGER) return false;
        SINCE.remove(me);
        SENT.put(me, n);
        f.enqueue(f.storesDeposit());
        f.brain("taking " + n + " of the builders' stock back to the stores");
        return true;
    }
}
