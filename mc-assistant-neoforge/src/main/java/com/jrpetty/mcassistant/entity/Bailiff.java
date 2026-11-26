package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.TieredItem;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpc] The bailiff (Debts' remedy when wages will not cover a debt): a folk of the town who walks, in plain sight, to the
 * debtor's home, goes in, and takes from its chests goods to the value of the debt, into the town's stores. Never the bed,
 * never the tools of a trade, never the clothes on the debtor's back (armour), and a few days' food is left. Coin in the
 * chests is taken first. If the walk takes too long (no way in), the bailiff gives up and the matter goes to court. Paying
 * the debt, or agreeing a plan, calls the bailiff off.
 */
public final class Bailiff {

    private Bailiff() {}

    /** Ticks a bailiff walks before giving up; how close it must get to the door. */
    static final int WALK_TICKS = 2400;
    static final double NEAR = 2.6;
    /** Food left in the house. */
    static final int FOOD_KEPT = 16;

    record Errand(UUID village, UUID debtor, String debt, UUID folk, BlockPos home, int radius, long started) {}

    private static final Map<UUID, Errand> BY_FOLK = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> BY_DEBTOR = new ConcurrentHashMap<>();

    public static boolean busy(UUID debtor) {
        return BY_DEBTOR.containsKey(debtor);
    }

    /** Is this folk a bailiff on its way (tests and the camera)? */
    public static boolean onErrand(UUID folk) {
        return BY_FOLK.containsKey(folk);
    }

    @Nullable
    public static UUID bailiffFor(UUID debtor) {
        return BY_DEBTOR.get(debtor);
    }

    /** The debtor's home: a house it owns or rents here, a folk's own house. Its anchor and a reach round it. */
    @Nullable
    static BlockPos home(UUID village, UUID debtor) {
        for (Homes.Home h : Homes.homes(village).values()) if (h.tenure == Homes.Tenure.PLAYER && debtor.equals(h.landlord)) return h.anchor;
        BlockPos rented = Tenancy.home(village, debtor);
        if (rented != null) return rented;
        Homes.Home h = Homes.homeOf(village, debtor);
        return h == null ? null : h.anchor;
    }

    /** Send a bailiff: the nearest grown, waking folk not about other business. False if nobody, or no home to go to. */
    static boolean send(ServerLevel level, Villages.Village v, UUID debtor, String debt) {
        UUID id = v.id();
        if (busy(debtor)) return true;
        BlockPos home = home(id, debtor);
        CompoundTag t = Debts.raw(id, debtor, debt);
        if (t != null) t.putLong("bailiffDay", PropertyBook.day(level));
        if (home == null) {
            PropertyBook.tell(level, debtor, "The bailiff found no home of yours to call at. The creditor will go to court instead.");
            return false;
        }
        VillageFolkEntity best = null;
        double bd = Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isSleeping() || !f.isAlive() || BY_FOLK.containsKey(f.getUUID())) continue;
            if (f.getUUID().equals(debtor) || ShopCustomers.underWay(f) || f.level() != level) continue;
            double d = f.distanceToSqr(home.getX(), home.getY(), home.getZ());
            if (d < bd) { bd = d; best = f; }
        }
        if (best == null) return false;
        Errand e = new Errand(id, debtor, debt, best.getUUID(), home, 6, level.getGameTime());
        BY_FOLK.put(best.getUUID(), e);
        BY_DEBTOR.put(debtor, best.getUUID());
        best.hobbyNow = "on the bailiff's business";
        FolkTalk.speak(best, FolkTalk.pick(best.getRandom(),
            "Bailiff's business. Not my favourite errand.",
            "A debt to collect. I'll be civil about it.",
            "Off to see about a debt. Wish me a short walk.",
            "The court's paper says I go, so I go.",
            "Somebody's behind on their payments. I'm the one who knocks.",
            "Collecting what's owed. Nobody waves at the bailiff."));
        PropertyBook.tell(level, debtor, best.displayNameCap() + " is walking to your home as the bailiff, to take goods to the value of what you owe. "
            + "Pay it, or ask for a plan on the Debts page, to call them off.");
        return true;
    }

    /** Called off (paid, or a plan agreed): the bailiff goes back to its day. */
    static void recall(ServerLevel level, UUID debtor) {
        UUID f = BY_DEBTOR.remove(debtor);
        if (f == null) return;
        BY_FOLK.remove(f);
        if (level.getEntity(f) instanceof VillageFolkEntity folk) {
            folk.getNavigation().stop();
            folk.hobbyNow = "";
            FolkTalk.speak(folk, FolkTalk.pick(folk.getRandom(), "Settled, they tell me. Good. I'll be off.", "Called off. Suits me.",
                "No need now: it's been agreed.", "Back to my own day, then.", "Paid up. Best news I've had.", "They came to terms. I'll turn round."));
        }
    }

    /** Every tick for a folk (VillageFolkEntity's day): a bailiff walks, and when it gets there takes what it may. True while it is about it. */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Errand e = BY_FOLK.get(f.getUUID());
        if (e == null) return false;
        if (level.getGameTime() - e.started() > WALK_TICKS || f.isSleeping() || !f.isAlive()) {
            end(f, e);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Couldn't get there. The court can have it.", "No way through. I'll tell the court.",
                "Gave up on that one. It's the court's now.", "Too far round. Let the court decide.", "I tried. The court will hear it.", "No getting in. Court, then."));
            return false;
        }
        BlockPos door = e.home();
        double d = f.distanceToSqr(door.getX() + 0.5, door.getY(), door.getZ() + 0.5);
        if (d > NEAR * NEAR * 4) {
            if (f.tickCount % 20 == 0 || f.getNavigation().isDone()) f.walkTo(door, 0.75);
            return true;
        }
        f.getNavigation().stop();
        int took = seize(level, e);
        end(f, e);
        FolkTalk.speak(f, took > 0 ? FolkTalk.pick(f.getRandom(), "Taken to the value. The bed and the tools stay.",
                "That's the debt seen to, as far as it goes.", "Goods for the debt. All written down, all fair.",
                "I left the bed, the tools and the bread. The rest goes to the stores.", "There. Square, or nearer it.", "Signed, sealed, carried off.")
            : FolkTalk.pick(f.getRandom(), "Nothing here I'm allowed to take.", "Bare as a bone. The court, then.", "Only the bed and the tools. Those stay.",
                "Nothing of value. I'll tell them.", "Empty chests. Not my doing.", "Nothing to take. It goes to court."));
        return false;
    }

    static void end(VillageFolkEntity f, Errand e) {
        BY_FOLK.remove(f.getUUID());
        BY_DEBTOR.remove(e.debtor());
        f.hobbyNow = "";
    }

    /** Is this something the bailiff must leave: a bed, a tool, the clothes on one's back, coin is never left. */
    static boolean essential(ItemStack s) {
        return s.is(ItemTags.BEDS) || s.getItem() instanceof TieredItem || s.getItem() instanceof ShearsItem || s.getItem() instanceof FishingRodItem
            || s.getItem() instanceof ArmorItem;
    }

    /** At the house: goods to the value of the debt out of its chests, into the town's stores; the debt paid down by their worth. */
    static int seize(ServerLevel level, Errand e) {
        UUID id = e.village();
        CompoundTag t = Debts.raw(id, e.debtor(), e.debt());
        if (t == null) return 0;
        int owed = t.getInt("owed");
        int value = 0, food = 0;
        List<String> what = new ArrayList<>();
        BlockPos a = e.home();
        int r = e.radius();
        for (BlockPos p : BlockPos.betweenClosed(a.offset(-r, -1, -r), a.offset(r, 10, r))) {
            if (value >= owed) break;
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && value < owed; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || essential(s)) continue;
                if (s.get(DataComponents.FOOD) != null && food < FOOD_KEPT) { food += s.getCount(); continue; }
                if (Market.isCoin(s)) {
                    int k = Math.min(s.getCount(), owed - value);
                    s.shrink(k);
                    Ledger.addCoins(id, k);
                    value += k;
                    what.add(k + " coins");
                    continue;
                }
                double each = Math.max(0.1, PropertyBook.worth(level, id, s.copyWithCount(1)));
                int n = (int) Math.min(s.getCount(), Math.ceil((owed - value) / each));
                if (n <= 0) continue;
                ItemStack taken = s.split(n);
                int worth = (int) Math.round(each * n);
                value += worth;
                what.add(n + " " + taken.getHoverName().getString().toLowerCase(java.util.Locale.ROOT));
                ItemStack left = Market.intoStores(level, id, taken);
                if (!left.isEmpty()) level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, a.getX() + 0.5, a.getY() + 1, a.getZ() + 0.5, left));
            }
            c.setChanged();
        }
        if (value > 0) {
            // the goods' worth is the town's (in its stores now): the debt is paid down by it, the creditor paid from the treasury
            Debts.credit(level, id, t, Math.min(value, owed));
            int left = Math.max(0, owed - value);
            t.putInt("owed", left);
            if (left <= 0) {
                net.minecraft.nbt.ListTag l = Debts.list(id, e.debtor());
                for (int i = 0; i < l.size(); i++) if (l.getCompound(i).getString("id").equals(e.debt())) { l.remove(i); break; }
            }
            PropertyBook.dirty();
            Villages.tell(id, PropertyBook.day(level), "the bailiff took goods worth " + value + " coins from a debtor's home for " + t.getString("kind") + " owed to " + t.getString("cred"));
        }
        PropertyBook.tell(level, e.debtor(), value > 0
            ? "The bailiff took " + String.join(", ", what.subList(0, Math.min(4, what.size()))) + (what.size() > 4 ? " and more" : "") + " from your home, worth "
                + PropertyBook.coins(value) + ". Your bed, tools, clothes and some food were left." + (owed - value > 0 ? " Still owed: " + PropertyBook.coins(owed - value) + "." : " The debt is cleared.")
            : "The bailiff found nothing they may take at your home (beds, tools, clothes and food are left). The creditor will go to court.");
        return value;
    }

    public static void resetForTests() {
        BY_FOLK.clear();
        BY_DEBTOR.clear();
    }

    /** Tests: send one now. */
    public static boolean sendForTests(ServerLevel level, Villages.Village v, UUID debtor, String debt) {
        return send(level, v, debtor, debt);
    }
}
