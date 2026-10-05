package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Nobody goes hungry while the larder is full: a packed lunch for a hand going out to a far plot, and a
 * walk in for food when one is out there with nothing left.
 *
 * <p>A folk eats its three meals from its pack, from its household's chest if it is near home, or from
 * the village's stores if it is within sixty-four blocks of them (Meals). A miner ninety blocks out,
 * a woodcutter, a farmer at the far edge of its field: with nothing in the pack, the meal is missed
 * there, and nothing ever sent it for more. On the hundred days' tenth day one folk went a whole day
 * without, "nothing to eat in reach", with two hundred and fifty meals in the stores. (A farmer carrying
 * eighty potatoes would not eat them either: they were its seed. It eats them now before it goes
 * without: AssistantEntity.eatFromSeed.) So:
 * <ul>
 * <li><b>A packed lunch.</b> A hand whose plot lies beyond the stores' reach, in the town and about to
 *     set out on a working day with fewer than a day's meals in its pack (its seed not counted), takes
 *     them out of the stores first: three meals and one over, two more for a trade that eats its
 *     rations at work.</li>
 * <li><b>Out of food, out there.</b> A mealtime come with nothing to eat in reach: the storehouse sends
 *     some out with a courier if it has couriers, and otherwise the hand walks in to the stores for a
 *     day's meals when its work in hand is done. Once a meal, not every few seconds.</li>
 * </ul>
 */
public final class PackedLunch {

    private PackedLunch() {}

    /** A day's meals in a pack: breakfast, the midday meal and supper, and one over. */
    public static final int DAY = 4;
    /** How often a hand looks, in ticks. */
    static final long LOOK = 1200;

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** The meal (day * 4 + which) a hand last sent for food at, so it sends once a meal. */
    private static final Map<UUID, Long> SENT = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        SENT.clear();
    }

    private static final Predicate<ItemStack> RATION = com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor("ration");

    /** The meals a hand has in its pack: its food, less a farmer's seed carrots and potatoes it keeps back. */
    public static int meals(VillageFolkEntity f) {
        int have = f.countCarried(RATION);
        if (f.stationTask() == StationTask.FARM) {
            int keep = f.depositReserve(new ItemStack(Items.CARROT));
            have -= Math.min(keep, f.countCarried(s -> s.is(Items.CARROT)));
            have -= Math.min(keep, f.countCarried(s -> s.is(Items.POTATO)));
        }
        return Math.max(0, have);
    }

    /** How many meals a hand takes out for the day. */
    static int wanted(VillageFolkEntity f) {
        StationTask t = f.stationTask();
        boolean rations = t != StationTask.NONE && t != StationTask.FARM && t != StationTask.FISH && t != StationTask.HUNT;
        return DAY + (rations ? 2 : 0);
    }

    /** Is this hand's plot beyond where the stores feed it at mealtimes? */
    static boolean far(VillageFolkEntity f, Villages.Village v) {
        WorkZone z = f.workZone();
        if (z == null) return false;
        BlockPos c = z.center(), h = v.centre();
        double dx = c.getX() - h.getX(), dz = c.getZ() - h.getZ();
        double reach = Math.max(0, Meals.STORES_REACH - z.radius());
        return dx * dx + dz * dz > reach * reach;
    }

    /** The stores' reach for a withdrawal, as the evening's rations look (VillageFolkEntity.restockRations). */
    static int radius(UUID village) {
        return Math.min(112, Math.max(32, Villages.storesRadius(village)));
    }

    /**
     * The look before setting out: a hand in the town, on its working day, with a far plot and fewer than a
     * day's meals, goes to the stores for them. True if it set off. From the folk's own round, with nothing
     * queued (VillageFolkEntity).
     */
    public static boolean take(VillageFolkEntity f) {
        if (f.isBaby() || !f.isAlive() || f.peekJob() != null || !f.onShift()) return false;
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || !(f.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        Long last = LOOKED.get(f.getUUID());
        if (last != null && now - last < LOOK && now >= last) return false;
        LOOKED.put(f.getUUID(), now);
        if (!far(f, v)) return false;
        BlockPos heart = v.centre();
        int town = Math.max(48, Villages.townReach(village) + 8);
        if (Math.max(Math.abs(heart.getX() - f.getBlockX()), Math.abs(heart.getZ() - f.getBlockZ())) > town) return false;
        int have = meals(f), want = wanted(f);
        if (have >= DAY) return false;
        if (Market.stock(level, village, RATION) <= 0) return false;
        f.enqueue(Job.withdrawAt("ration", want - have, heart, radius(village)));
        f.brain("a packed lunch: " + (want - have) + " meals from the stores for the day out at the plot");
        return true;
    }

    /**
     * A mealtime come and nothing to eat in reach (Meals.tick): food is sent for, once a meal. A courier
     * brings it if the storehouse has couriers; otherwise the hand walks in for a day's meals once its
     * work in hand is done. True if anything was set going.
     */
    public static boolean sendFor(VillageFolkEntity f, long mealKey) {
        if (f.isBaby() || !f.isAlive()) return false;
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || !(f.level() instanceof ServerLevel level)) return false;
        Long sent = SENT.get(f.getUUID());
        if (sent != null && sent == mealKey) return false;
        if (meals(f) > 0) return false;
        SENT.put(f.getUUID(), mealKey);                       // (looked at once a meal, food or none)
        if (Market.stock(level, village, RATION) <= 0) return false;
        int want = wanted(f);
        if (Couriers.sendOut(f, "ration", want)) {
            f.brain("nothing to eat out here: asked the storehouse to send food out");
            return true;
        }
        Job head = f.peekJob();
        for (Job j : f.queuedJobs()) {
            if (j.type() == Job.Type.WITHDRAW && j.arg() != null && j.arg().startsWith("ration@")) return false;   // already going
        }
        f.enqueue(Job.withdrawAt("ration", want, v.centre(), radius(village)));
        f.brain("nothing to eat out here: going in to the stores for food" + (head == null ? "" : " when this is done"));
        return true;
    }
}
