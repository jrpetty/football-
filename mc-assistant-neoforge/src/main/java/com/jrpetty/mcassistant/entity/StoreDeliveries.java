package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The shop's deliveries: goods carried, by hand, between the village's storehouse and the shop's stockroom.
 * <ul>
 * <li><b>In.</b> The stock keeper's orders on the storehouse (StockKeeper): so many of a ware packed at the
 *     storehouse out of the stores (booked out of its books to the store), walked over to the shop, and put
 *     away in its stockroom. Real things in a real pack: what the stores have not got is not carried.</li>
 * <li><b>Back.</b> What has sat too long in the stockroom (food past its days, a ware nobody buys) packed there
 *     and carried back into the stores.</li>
 * <li><b>Who carries.</b> The storehouse's couriers (Couriers), between their own runs: a delivery waiting is
 *     taken by a courier with nothing on its list, or by the first one free once it has waited a minute. A
 *     delivery no courier takes (a town with none, or all of them busy) the stock keeper fetches itself; at a
 *     shop with no stock keeper, its keeper. The carrier's day is booked in the storehouse's books.</li>
 * </ul>
 */
public final class StoreDeliveries {

    private StoreDeliveries() {}

    /** Where a delivery has got to. */
    enum Stage {
        WAITING("waiting for a carrier"), PACKING("packing it"), OUT("carrying it over"), UNLOADING("putting it away"), DONE("done");

        final String words;

        Stage(String words) {
            this.words = words;
        }
    }

    /** A courier with runs of its own leaves a delivery this long before it takes it (ticks); the stock keeper
     *  fetches one nobody has taken after this long; one under way this long is given up, and one nobody has
     *  taken in a day is taken off the list. */
    static final long COURIER_WAIT = 1200L, KEEPER_WAIT = 2400L, TOO_LONG = 9600L, STALE = 24000L;
    /** The most carried at once. */
    static final int MOST = 64;

    /** One delivery: a ware, how many, in to the stockroom or back to the stores, and who has it. */
    static final class Delivery {
        final int id;
        final String key;
        final Predicate<ItemStack> what;
        final ItemStack sample;
        int count;
        final boolean back;
        final long queued;
        @Nullable UUID carrier;
        String carrierName = "";
        boolean courier;
        Stage stage = Stage.WAITING;
        int carried, delivered;
        long taken, progress;
        double best = Double.MAX_VALUE;
        int orderId = -1;

        Delivery(int id, String key, Predicate<ItemStack> what, ItemStack sample, int count, boolean back, long queued) {
            this.id = id;
            this.key = key;
            this.what = what;
            this.sample = sample;
            this.count = count;
            this.back = back;
            this.queued = queued;
        }

        /** "16 bread from the storehouse to the store". */
        String words(String place) {
            String n = Bench.words(sample.getItem(), count);
            return back ? n + " back from " + place + " to the stores" : n + " from the storehouse to " + place;
        }
    }

    private static final Map<UUID, List<Delivery>> LISTS = new ConcurrentHashMap<>();
    private static int nextId = 1;

    public static void resetForTests() {
        LISTS.clear();
    }

    static List<Delivery> of(UUID village) {
        return LISTS.computeIfAbsent(village, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
    }

    /** A delivery put on the list. */
    static Delivery queue(ServerLevel level, UUID village, String key, Predicate<ItemStack> what, ItemStack sample, int count, boolean back) {
        Delivery d = new Delivery(nextId++, key, what, sample.copyWithCount(1), Math.max(1, Math.min(MOST, count)), back, level.getGameTime());
        of(village).add(d);
        return d;
    }

    /** The goods of this ware on their way in (or out), not yet put away. */
    static int inFlight(UUID village, String key, boolean back) {
        int n = 0;
        for (Delivery d : of(village)) if (d.key.equals(key) && d.back == back && d.stage != Stage.DONE) n += d.count;
        return n;
    }

    @Nullable
    static Delivery carriedBy(VillageFolkEntity c) {
        UUID v = c.ownerId();
        if (v == null) return null;
        for (Delivery d : of(v)) if (c.getUUID().equals(d.carrier) && d.stage != Stage.DONE) return d;
        return null;
    }

    /** What this carrier is doing for the shop, in words, or null. */
    @Nullable
    static String doing(VillageFolkEntity c) {
        Delivery d = carriedBy(c);
        return d == null ? null : "For " + place(c.ownerId()) + ": " + d.stage.words + " (" + d.words(place(c.ownerId())) + ")";
    }

    private static String place(@Nullable UUID village) {
        return village != null && Store.stands(village) ? "the store" : "the shop";
    }

    /** The oldest delivery waiting at least this long, or null. */
    @Nullable
    private static Delivery waiting(ServerLevel level, UUID village, long atLeast) {
        long now = level.getGameTime();
        Delivery best = null;
        for (Delivery d : of(village)) {
            if (d.stage != Stage.WAITING || d.carrier != null) continue;
            if (now - d.queued < atLeast && now >= d.queued) continue;
            if (best == null || d.queued < best.queued) best = d;
        }
        return best;
    }

    private static void take(VillageFolkEntity c, ServerLevel level, Delivery d, boolean courier) {
        d.carrier = c.getUUID();
        d.carrierName = c.displayNameCap();
        d.courier = courier;
        d.stage = Stage.PACKING;
        d.taken = level.getGameTime();
        d.progress = d.taken;
        d.best = Double.MAX_VALUE;
        c.brain((courier ? "courier run for " : "fetching for ") + place(c.ownerId()) + ": " + d.words(place(c.ownerId())));
        if (level.getRandom().nextInt(2) == 0) {
            FolkTalk.speak(c, FolkTalk.pick(level.getRandom(), "Right — " + d.words(place(c.ownerId())) + ".",
                d.back ? "These have sat long enough. Back to the stores with them." : "The stock keeper wants " + Bench.words(d.sample.getItem(), d.count) + ". On my way."));
        }
    }

    // ------------------------------------------------------------------ who carries

    /**
     * A courier's turn at the shop's deliveries (Couriers.work, when it has no run of its own in hand): carry on
     * with the delivery it has, or take one waiting (at once with nothing on its own list, else once it has
     * waited a minute). False if there is nothing for it here.
     */
    public static boolean courier(VillageFolkEntity c, ServerLevel level, boolean runListEmpty) {
        UUID v = c.ownerId();
        if (v == null) return false;
        Delivery d = carriedBy(c);
        if (d != null) return step(c, level, d);
        // A new one only with an empty pack, and never the street sweeper's (Sweepers: it sweeps first).
        if (!ShopStock.open(v) || c.stashable() > 0 || Sweepers.appointed(c)) return false;
        d = waiting(level, v, runListEmpty ? 0L : COURIER_WAIT);
        if (d == null) return false;
        take(c, level, d, true);
        return step(c, level, d);
    }

    /** The stock keeper (or, at a shop with none, its keeper) fetches a delivery nobody has taken: its own if it
     *  has one under way, else one that has waited this long. */
    static boolean fetch(VillageFolkEntity f, ServerLevel level, long waited) {
        UUID v = f.ownerId();
        if (v == null) return false;
        Delivery d = carriedBy(f);
        if (d != null) return step(f, level, d);
        d = waiting(level, v, waited);
        if (d == null) return false;
        take(f, level, d, false);
        return step(f, level, d);
    }

    /** Is there a courier at work in the town (one of the storehouse's), to carry the shop's deliveries? */
    static boolean couriers(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity c && c.stationTask() == StationTask.HAUL && c.isAlive() && Couriers.employed(c)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the errand

    /** A beat of a delivery: to where it is packed, packed, carried over, put away. */
    static boolean step(VillageFolkEntity c, ServerLevel level, Delivery d) {
        UUID v = c.ownerId();
        Villages.Village vill = v == null ? null : Villages.get(v);
        if (vill == null) return false;
        String place = place(v);
        switch (d.stage) {
            case WAITING, DONE -> {
                return false;
            }
            case PACKING -> {
                BlockPos from = d.back ? Store.stockroomSpot(level, v) : Couriers.base(level, v);
                if (from == null) { drop(level, vill, c, d, "nowhere to fetch it from"); return true; }
                if (!near(c, from)) return walk(c, level, vill, d, from);
                List<ItemStack> lots = d.back ? Store.take(level, v, d.what, d.count, false) : Store.fromStores(level, v, d.what, d.count);
                int packed = 0;
                for (ItemStack lot : lots) {
                    ItemStack left = c.insertGiven(lot.copy());
                    packed += lot.getCount() - left.getCount();
                    if (!left.isEmpty()) {
                        if (d.back) Store.put(level, v, left);               // no room in the pack: it stays in the stockroom
                        else TownWork.give(level, vill, left);              // nor here: it stays in the stores
                    }
                }
                c.swing(InteractionHand.MAIN_HAND);
                if (packed <= 0) {
                    drop(level, vill, c, d, d.back ? "nothing of it left in the stockroom" : "the stores had none to spare after all");
                    return true;
                }
                d.carried = packed;
                d.stage = Stage.OUT;
                d.best = Double.MAX_VALUE;
                d.progress = level.getGameTime();
                return true;
            }
            case OUT -> {
                BlockPos to = d.back ? Couriers.base(level, v) : Store.stockroomSpot(level, v);
                if (to == null) { d.stage = Stage.UNLOADING; return true; }
                if (!near(c, to)) return walk(c, level, vill, d, to);
                d.stage = Stage.UNLOADING;
                return true;
            }
            case UNLOADING -> {
                int moved = 0;
                var inv = c.getInventoryItems();
                for (int i = 0; i < inv.size() && moved < d.carried; i++) {
                    ItemStack s = inv.get(i);
                    if (s.isEmpty() || !d.what.test(s)) continue;
                    int k = Math.min(s.getCount(), d.carried - moved);
                    ItemStack lot = s.copyWithCount(k);
                    int put;
                    if (d.back) {
                        TownWork.give(level, vill, lot);                     // into the stores (give leaves what would not fit)
                        put = k - lot.getCount();
                    } else {
                        ItemStack left = Store.put(level, v, lot);
                        put = k - left.getCount();
                    }
                    s.shrink(put);
                    if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                    moved += put;
                    if (put < k) break;                                      // no room left
                }
                // Whatever would not go in (a full stockroom) goes back into the stores, not left in the pack.
                int over = d.carried - moved;
                if (over > 0 && !d.back) {
                    for (int i = 0; i < inv.size() && over > 0; i++) {
                        ItemStack s = inv.get(i);
                        if (s.isEmpty() || !d.what.test(s)) continue;
                        int k = Math.min(s.getCount(), over);
                        ItemStack lot = s.copyWithCount(k);
                        TownWork.give(level, vill, lot);
                        int gone = k - lot.getCount();
                        s.shrink(gone);
                        if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                        over -= gone;
                    }
                }
                c.swing(InteractionHand.MAIN_HAND);
                finish(level, vill, c, d, moved);
                return true;
            }
        }
        return false;
    }

    /** Within reach of where it is going. */
    private static boolean near(VillageFolkEntity c, BlockPos to) {
        return c.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5) <= 3.5 * 3.5;
    }

    /** Walk on towards there, watching that it gets nearer; forty-five seconds getting no nearer and it is put
     *  beside it (as any stuck hand of the village is), or failing that the delivery is given up. */
    private static boolean walk(VillageFolkEntity c, ServerLevel level, Villages.Village v, Delivery d, BlockPos to) {
        if (Store.INSTANT) {
            c.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            return true;
        }
        long now = level.getGameTime();
        double dist = c.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (dist < d.best - 1.0) {
            d.best = dist;
            d.progress = now;
        } else if (now - d.progress > 900L || now < d.progress) {
            if (c.putBeside(to)) {
                d.best = Double.MAX_VALUE;
                d.progress = now;
                return true;
            }
            drop(level, v, c, d, "could not get there");
            return true;
        }
        if (c.getNavigation().isDone()) c.walkTo(to, 1.0D);
        return true;
    }

    /** Put away: booked in the stock keeper's book and the storehouse's, the carrier's day tallied. */
    private static void finish(ServerLevel level, Villages.Village v, VillageFolkEntity c, Delivery d, int moved) {
        d.delivered = moved;
        d.stage = Stage.DONE;
        String place = place(v.id());
        StockKeeper.delivered(level, v.id(), d, moved);
        if (d.courier) {
            int[] day = Couriers.office(level, v.id()).staff.computeIfAbsent(c.getUUID(), k -> new int[2]);
            day[0]++;
            day[1] += moved;
        }
        Storekeeping.bookRun(level, v.id(), c.displayNameCap(), d.words(place), moved, d.courier ? "the stock keeper" : "");
        c.note(AssistantEntity.Deed.LOADS_HAULED, 1);
        c.brain((d.back ? "carried back to the stores: " : "put away in the stockroom: ") + Bench.words(d.sample.getItem(), moved));
        if (moved > 0 && level.getRandom().nextInt(2) == 0) {
            FolkTalk.speak(c, d.back ? FolkTalk.pick(level.getRandom(), "Back in the stores, where they'll be some use.",
                    Storekeeping.words(moved) + " back on the storehouse's shelves.")
                : FolkTalk.pick(level.getRandom(), Bench.words(d.sample.getItem(), moved) + " for " + place + "'s stockroom. That's that.",
                    "In the stockroom: " + Bench.words(d.sample.getItem(), moved) + ". Sign for it, somebody!"));
        }
    }

    /** Given up: what it carries goes back where it came from, and the stock keeper's book says why. */
    private static void drop(ServerLevel level, Villages.Village v, VillageFolkEntity c, Delivery d, String why) {
        if (d.carried > 0) {
            int left = d.carried;
            var inv = c.getInventoryItems();
            for (int i = 0; i < inv.size() && left > 0; i++) {
                ItemStack s = inv.get(i);
                if (s.isEmpty() || !d.what.test(s)) continue;
                int k = Math.min(s.getCount(), left);
                ItemStack lot = s.copyWithCount(k);
                if (d.back) {
                    ItemStack rest = Store.put(level, v.id(), lot);
                    if (!rest.isEmpty()) TownWork.give(level, v, rest);
                } else {
                    TownWork.give(level, v, lot);
                }
                s.shrink(k);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                left -= k;
            }
        }
        d.stage = Stage.DONE;
        StockKeeper.deliveryFailed(level, v.id(), d, why);
        c.brain("gave up a delivery for " + place(v.id()) + ": " + why);
    }

    // ------------------------------------------------------------------ the round

    /** The list kept tidy: what is done goes, a delivery under way far too long is given up (its carrier gone,
     *  or the world unloaded under it). */
    static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        List<Delivery> list = of(v.id());
        for (Delivery d : list) {
            if (d.stage == Stage.DONE) { list.remove(d); continue; }
            if (d.carrier == null) {
                // A day waiting and nobody to carry it: off the list, and the morning's count orders afresh.
                if (now - d.queued > STALE || now < d.queued) {
                    d.stage = Stage.DONE;
                    StockKeeper.deliveryFailed(level, v.id(), d, "nobody came to carry it");
                    list.remove(d);
                }
                continue;
            }
            // The carrier by the village's own roll (the level's lookup misses a folk on ground only just loaded).
            VillageFolkEntity e = null;
            for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && d.carrier.equals(f.getUUID())) { e = f; break; }
            boolean gone = e == null || !e.isAlive() || e.ownerId() == null || !e.ownerId().equals(v.id());
            if (gone || now - d.taken > TOO_LONG || now < d.taken) {
                if (!gone) {
                    drop(level, v, e, d, "it took far too long");
                } else {
                    // The carrier is gone with whatever it had (to its grave, or another town): nothing to put back.
                    d.stage = Stage.DONE;
                    StockKeeper.deliveryFailed(level, v.id(), d, "its carrier never came back");
                }
                list.remove(d);
            }
        }
    }

    /** What is waiting or under way, in a line each, for the books. */
    static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        String place = place(village);
        for (Delivery d : of(village)) {
            if (d.stage == Stage.DONE) continue;
            out.add(d.words(place) + ": " + (d.carrier == null ? "waiting for a carrier" : d.carrierName + ", " + d.stage.words));
        }
        return out;
    }

    // ------------------------------------------------------------------ the smoke and the tests

    /** Every delivery waiting, carried over at once as a carrier would (the stage for the smoke). */
    static void deliverAllNow(ServerLevel level, Villages.Village v) {
        for (Delivery d : of(v.id())) {
            if (d.stage != Stage.WAITING) continue;
            List<ItemStack> lots = d.back ? Store.take(level, v.id(), d.what, d.count, false) : Store.fromStores(level, v.id(), d.what, d.count);
            int moved = 0;
            for (ItemStack lot : lots) {
                if (d.back) {
                    ItemStack rest = lot.copy();
                    TownWork.give(level, v, rest);
                    moved += lot.getCount() - rest.getCount();
                    if (!rest.isEmpty()) Store.put(level, v.id(), rest);
                } else {
                    ItemStack left = Store.put(level, v.id(), lot);
                    moved += lot.getCount() - left.getCount();
                    if (!left.isEmpty()) TownWork.give(level, v, left);
                }
            }
            d.carrierName = "the stock keeper";
            d.carried = moved;
            d.delivered = moved;
            d.stage = Stage.DONE;
            StockKeeper.delivered(level, v.id(), d, moved);
        }
    }

    /** Tests: the deliveries on the list, as "key:count:stage:carrier". */
    public static List<String> listForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (Delivery d : of(village)) out.add(d.key + ":" + d.count + ":" + d.stage + ":" + d.carrierName + (d.back ? ":back" : ""));
        return out;
    }

    /** Tests: this folk takes the oldest delivery waiting (whatever its wait) and carries it all the way, a beat
     *  at a time, as it would at work (with Store.instantForTests on, it is put beside each end). Returns how many
     *  it put away, or -1 if there was none to take. */
    public static int carryForTests(VillageFolkEntity f, ServerLevel level) {
        UUID v = f.ownerId();
        if (v == null) return -1;
        Delivery d = carriedBy(f);
        if (d == null) {
            d = waiting(level, v, 0L);
            if (d == null) return -1;
            take(f, level, d, f.stationTask() == StationTask.HAUL);
        }
        for (int i = 0; i < 40 && d.stage != Stage.DONE; i++) step(f, level, d);
        return d.delivered;
    }
}
