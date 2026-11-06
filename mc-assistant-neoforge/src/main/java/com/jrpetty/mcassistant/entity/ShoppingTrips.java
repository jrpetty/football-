package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [shops-homes] A folk's shopping, once a day in its own time: the top of its list (Needs) it can pay for out of its own
 * purse, three things at most, at the shop while it is open. It walks there, queues at the counter and is served
 * (ServiceQueue), pays the shop's price out of its purse into the treasury (ShopDesk.folkBuy, the same price and stock
 * as a player's), and carries what it bought home: the food into the larder, the rest into the chest, the wardrobe or
 * the toy box (HomeStores); a new larder or wardrobe is set down against a wall and named. What it bought is its own
 * (a keepsake of it: never banked into the stores) and the household's.
 */
public final class ShoppingTrips {

    private ShoppingTrips() {}

    /** The most things bought on one trip, the coin it always keeps by, and how long a trip may take (ticks). */
    static final int MOST_A_TRIP = 3, KEEP_BY = 2;
    static final long TOO_LONG = 4800L, RETRY = 1200L;

    private static final class Trip {
        final List<Needs.Want> wants;
        final long started;
        boolean bought;
        int walked = -1000;

        Trip(List<Needs.Want> wants, long started) {
            this.wants = wants;
            this.started = started;
        }
    }

    private static final Map<UUID, Trip> TRIPS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DONE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> WAIT = new ConcurrentHashMap<>();
    /** What each folk paid at the counter on its last trip (the card's books, the tests). */
    private static final Map<UUID, Integer> PAID = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TRIPS.clear();
        DONE.clear();
        WAIT.clear();
        PAID.clear();
    }

    /** Its shopping, a beat of it (VillageFolkEntity.socialise, in its own time). True while it is about it. */
    public static boolean errand(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.isSleeping()) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        long day = level.getDayTime() / 24000L, now = level.getGameTime();
        Trip t = TRIPS.get(f.getUUID());
        if (t == null) {
            if (DONE.getOrDefault(f.getUUID(), -1L) == day || now < WAIT.getOrDefault(f.getUUID(), 0L)) return false;
            t = plan(level, v, f, day);
            if (t == null) return false;
            TRIPS.put(f.getUUID(), t);
            f.brain("off to the shop for " + words(t.wants));
        }
        if (now - t.started > TOO_LONG) return giveUp(f, day, "could not get it done");
        return t.bought ? home(level, v, f, t, day) : shop(level, v, f, t, day);
    }

    /** What it will buy today, if anything: the most needed it can pay for, while the shop is open. */
    @Nullable
    private static Trip plan(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        if (!ShopStock.open(v.id())) return null;
        ServicePoints.Point point = ServicePoints.nearest(level, v.id(), ServiceRole.SHOP, f.blockPosition());
        if (point != null && !ServicePoints.status(level, v.id(), ServiceRole.SHOP, point).open()) return null;
        List<Needs.Want> list = Needs.of(level, f);
        if (list.isEmpty()) {
            DONE.put(f.getUUID(), day);
            return null;
        }
        int purse = f.purse() - KEEP_BY;
        List<Needs.Want> take = new ArrayList<>();
        for (Needs.Want w : list) {
            if (take.size() >= MOST_A_TRIP) break;
            int price = ShopStock.lotPrice(level, v.id(), w.sample(), w.count());
            if (price > purse) continue;
            purse -= price;
            take.add(w);
        }
        if (take.isEmpty()) {
            DONE.put(f.getUUID(), day);
            return null;
        }
        return new Trip(take, level.getGameTime());
    }

    /** To the shop, in line, served, and paid. */
    private static boolean shop(ServerLevel level, Villages.Village v, VillageFolkEntity f, Trip t, long day) {
        ServicePoints.Point point = ServiceQueue.pointFor(f, level, ServiceRole.SHOP);       // the line it is in, else the nearest
        BlockPos to = point != null ? point.queueSpot(level, 0) : Villages.builtAt(v.id(), Store.buildingForShop(v.id()));
        if (to == null) return giveUp(f, day, "there's no shop");
        if (point == null && f.blockPosition().distSqr(to) > 4 * 4 || point != null && f.blockPosition().distSqr(to) > 10 * 10) {
            if (f.getNavigation().isDone() || f.tickCount - t.walked >= 100) {
                f.walkTo(to, 0.9D);
                t.walked = f.tickCount;
            }
            f.hobbyNow = "off to the shop for " + words(t.wants);
            f.lastLeisureTick = f.tickCount;
            return true;
        }
        ServiceQueue.Turn turn = ServiceQueue.turn(f, level, ServiceRole.SHOP, t.wants.get(0).words());
        if (turn == ServiceQueue.Turn.BUSY) return true;
        if (turn == ServiceQueue.Turn.CLOSED) {
            TRIPS.remove(f.getUUID());
            WAIT.put(f.getUUID(), level.getGameTime() + RETRY);
            return false;
        }
        List<String> got = buyAll(level, v, f, t.wants, day);
        if (got.isEmpty()) return giveUp(f, day, "none of it in");
        t.bought = true;
        t.walked = -1000;
        return true;
    }

    /** At the counter, served: everything on the trip's list bought out of its own purse, made its own, and said. */
    private static List<String> buyAll(ServerLevel level, Villages.Village v, VillageFolkEntity f, List<Needs.Want> wants, long day) {
        List<String> got = new ArrayList<>();
        int purse = f.purse();
        for (Needs.Want w : wants) {
            ItemStack bought = ShopDesk.folkBuy(level, v, f, w.sample(), w.count());
            if (bought == null) continue;
            ownIt(f, w.sample(), bought.getCount());
            got.add(Goods.words(w.sample(), bought.getCount()));
        }
        Needs.bought(f, got, day);
        PAID.put(f.getUUID(), purse - f.purse());
        if (got.isEmpty()) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "None of what I wanted. I'll try again tomorrow.", "Sold out! Tomorrow, then."));
            return got;
        }
        f.swing(InteractionHand.MAIN_HAND);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "That's " + String.join(" and ", got) + ". Home with it.",
            "Got what I came for: " + String.join(" and ", got) + ".", "Thank you! " + cap(got.get(0)) + " — just the thing."));
        f.persona().remember(day, "I bought " + String.join(" and ", got) + " at the shop", 1);
        f.brain("bought " + String.join(" and ", got) + " at the shop, for its home");
        return got;
    }

    /**
     * What it bought, made its own (a keepsake of it: carried home, never banked into the stores). The very things it
     * was handed, nothing more: taken out of its pack and put back marked.
     */
    private static void ownIt(VillageFolkEntity f, ItemStack sample, int n) {
        int taken = f.removeMatching(s -> ItemStack.isSameItemSameComponents(s, sample), n);
        int left = taken;
        while (left > 0) {
            int k = Math.min(left, sample.getMaxStackSize());
            ItemStack mine = sample.copyWithCount(k);
            Homes.keepsake(mine, f, Homes.Source.BOUGHT);
            ItemStack over = f.insertItem(mine);
            if (!over.isEmpty()) {
                // A full pack: what will not go back in as its own goes back in as it was (nothing lost).
                ItemStack plain = sample.copyWithCount(over.getCount());
                ItemStack still = f.insertItem(plain);
                if (!still.isEmpty() && f.level() instanceof ServerLevel level) {
                    Villages.Village v = Villages.get(f.ownerId());
                    if (v != null) TownWork.give(level, v, still);
                }
            }
            left -= k;
        }
    }

    /** Home with it, and put away (and a new larder or wardrobe set down). */
    private static boolean home(ServerLevel level, Villages.Village v, VillageFolkEntity f, Trip t, long day) {
        Homes.Home h = Homes.homeOf(v.id(), f.getUUID());
        BlockPos chest = h == null ? null : Homes.chestOf(level, v.id(), h);
        if (h == null || chest == null) return giveUp(f, day, "nowhere to put it");          // it carries it till it has
        if (f.blockPosition().distSqr(chest) > 2.5 * 2.5) {
            if (f.getNavigation().isDone() || f.tickCount - t.walked >= 100) {
                f.walkTo(chest, 0.9D);
                t.walked = f.tickCount;
            }
            f.hobbyNow = "carrying its shopping home";
            f.lastLeisureTick = f.tickCount;
            return true;
        }
        f.getNavigation().stop();
        arriveHome(level, v, h, f);
        DONE.put(f.getUUID(), day);
        TRIPS.remove(f.getUUID());
        return true;
    }

    /** Home with its shopping: a new larder or wardrobe set down, and everything of its own put away. */
    private static void arriveHome(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f) {
        f.swing(InteractionHand.MAIN_HAND);
        setDownFurniture(level, v, h, f);
        putAwayOwn(level, v.id(), h, f);
    }

    /** Everything of its own it carries that it does not keep about it, put away in its home. */
    static int putAwayOwn(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f) {
        int put = 0;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || !Homes.isKeepsake(s) || !Homes.ownedBy(s, f) || Keepsakes.isCarried(s)) continue;
            ItemStack left = HomeStores.putAway(level, village, h, s.copy(), f.isBaby());
            put += s.getCount() - left.getCount();
            pack.set(i, left);
        }
        return put;
    }

    /** A barrel it bought is the house's larder, a chest its wardrobe: set down against a wall, out of the way, and named. */
    private static void setDownFurniture(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f) {
        Ledger.Building b = Homes.building(v.id(), h.anchor);
        if (b == null || Flats.isFlat(h)) return;
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || !Homes.ownedBy(s, f) || !(s.is(Items.BARREL) || s.is(Items.CHEST))) continue;
            HomeStores.Kind kind = s.is(Items.BARREL) ? HomeStores.Kind.LARDER : HomeStores.Kind.WARDROBE;
            if (HomeStores.box(level, v.id(), h, kind) != null) continue;
            Decor.Room room = Decor.room(v.id(), b);
            BlockPos near = f.bedPos() != null ? f.bedPos() : h.anchor;
            // The best free floor against a wall the town's gate allows (a cell by the door or on the way in it refuses).
            java.util.Set<BlockPos> taken = new java.util.HashSet<>(Decor.reserved(level, v.id(), b));
            Decor.Spot spot = null;
            for (int tries = 0; tries < 8; tries++) {
                Decor.Spot cand = Decor.floorSpot(level, room, near, taken);
                if (cand == null) break;
                if (mayPlace(level, v, cand.at())) { spot = cand; break; }
                taken.add(cand.at());
            }
            if (spot == null) continue;
            var state = s.is(Items.BARREL)
                ? Blocks.BARREL.defaultBlockState().setValue(net.minecraft.world.level.block.BarrelBlock.FACING, net.minecraft.core.Direction.UP)
                : Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, spot.facing());
            level.setBlock(spot.at(), state, 3);
            BlockEntity be = level.getBlockEntity(spot.at());
            if (be != null) {
                HomeStores.rename(be, kind.label);
            }
            s.shrink(1);
            HomeStores.resetFor(h);
            FolkTalk.speak(f, kind == HomeStores.Kind.LARDER ? "A proper larder at last." : "A wardrobe! Room to breathe in that chest.");
            return;                                                              // one piece of furniture a trip
        }
    }

    /** The town's one gate for a block it places ([integrity] TownBlocks): a larder or a wardrobe is furniture, on a
     *  free floor cell against a wall inside the home (Decor.floorSpot), never a doorway, a way in or a stair. */
    private static boolean mayPlace(ServerLevel level, Villages.Village v, BlockPos at) {
        return level.isLoaded(at) && level.getBlockState(at).isAir() && TownBlocks.mayPlace(level, v, at, TownBlocks.Why.FURNISH);
    }

    private static boolean giveUp(VillageFolkEntity f, long day, String why) {
        TRIPS.remove(f.getUUID());
        DONE.put(f.getUUID(), day);
        ServiceQueue.leave(f.getUUID());
        f.brain("gave up its shopping: " + why);
        return false;
    }

    private static String words(List<Needs.Want> wants) {
        List<String> w = new ArrayList<>();
        for (Needs.Want x : wants) w.add(x.words());
        return String.join(", ", w);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Tests: a whole trip now, the walking left out (put at the counter, then home): what it bought, or empty if it
     * would not go today (nothing wanted, nothing it can pay for, the shop shut).
     */
    public static List<String> tripNowForTests(ServerLevel level, VillageFolkEntity f) {
        againForTests(f);
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return List.of();
        long day = level.getDayTime() / 24000L;
        Trip t = plan(level, v, f, day);
        if (t == null) return List.of();
        ServicePoints.Point point = ServicePoints.nearest(level, v.id(), ServiceRole.SHOP, f.blockPosition());
        if (point != null) {
            BlockPos at = point.queueSpot(level, 0);
            f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        }
        List<String> got = buyAll(level, v, f, t.wants, day);
        Homes.Home h = Homes.homeOf(v.id(), f.getUUID());
        BlockPos chest = h == null ? null : Homes.chestOf(level, v.id(), h);
        if (chest != null) {
            f.teleportTo(chest.getX() + 0.5, chest.getY() + 1, chest.getZ() + 0.5);
            arriveHome(level, v, h, f);
        }
        DONE.put(f.getUUID(), day);
        return got;
    }

    /** [home-making] Is it on a shopping trip now (to the shop or home with it)? Its other errands wait for it. */
    static boolean underWay(VillageFolkEntity f) {
        return TRIPS.containsKey(f.getUUID());
    }

    /** Tests: is it on a trip now, and has it bought yet ("none", "shop", "home"). */
    public static String stageForTests(VillageFolkEntity f) {
        Trip t = TRIPS.get(f.getUUID());
        return t == null ? "none" : t.bought ? "home" : "shop";
    }

    /** Tests: what it paid at the counter on its last trip. */
    public static int paidForTests(VillageFolkEntity f) {
        return PAID.getOrDefault(f.getUUID(), 0);
    }

    /** Tests: shop again today. */
    public static void againForTests(VillageFolkEntity f) {
        DONE.remove(f.getUUID());
        WAIT.remove(f.getUUID());
        TRIPS.remove(f.getUUID());
    }
}
