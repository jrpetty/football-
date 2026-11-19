package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [player15] Customers at a player's shop (PlayerShops), as anybody can watch them: in its hours a folk who wants
 * something the shop has walks there, in at its door, over to a shelf to look it over, into the queue at the counter,
 * pays (the keeper takes the coin, or it goes in the till), says a word and goes off with it; or finds it too dear,
 * says so and leaves.
 * <ul>
 * <li><b>Who comes.</b> Off work, once a day (and once more a couple of hours on if it bought nothing), a folk with a want
 *     a shop open now can meet: the tool of its trade, food when the town is hungry or a treat on market day, a comfort for
 *     its home, something nice when it is well off. More of them the better the shop's name: a few a day at first, a
 *     couple of dozen for a shop everybody speaks well of.</li>
 * <li><b>What it pays.</b> As at a stall: weighed against the going price by its means and nature, never more than the
 *     town lets its own stock go for, a little more at a shop of good name. Out of its own purse, into the till; the goods
 *     into its pack, a comfort carried home.</li>
 * <li><b>The one decision</b> what a folk would want of a thing is {@link #wants}: a later round's knowledge of what each
 *     item is for answers it there.</li>
 * </ul>
 */
public final class ShopCustomers {

    private ShopCustomers() {}

    /** How long a whole visit may take, and each leg of it (ticks). */
    static final int VISIT = 2400, LEG = 600, BROWSE = 50, PAY = 30;

    /** What a folk would want of a thing, and why, or null: the one place the buying decision is made. */
    public record Want(PlayerStalls.Use use, boolean favourite, String why) {}

    private static final Map<UUID, List<PlayerStalls.Want>> WANTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> WANTS_AT = new ConcurrentHashMap<>();

    /** Would this folk want this thing, and what for (the stall's reckoning of its needs and means for now). */
    @Nullable
    public static Want wants(ServerLevel level, VillageFolkEntity f, ItemStack s) {
        if (s.isEmpty() || Market.isCoin(s) || s.isDamaged()) return null;
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        if (v == null) return null;
        Long at = WANTS_AT.get(f.getUUID());
        List<PlayerStalls.Want> list = WANTS.get(f.getUUID());
        if (list == null || at == null || level.getGameTime() - at > 200) {
            list = PlayerStalls.wants(level, v, f, Market.marketDay(id, PlayerShops.today(level)));
            WANTS.put(f.getUUID(), list);
            WANTS_AT.put(f.getUUID(), level.getGameTime());
        }
        for (PlayerStalls.Want w : list) {
            if (!w.what().test(s)) continue;
            boolean fav = w.favourite() != null && s.is(w.favourite());
            return new Want(w.use(), fav, why(w.use(), fav));
        }
        return null;
    }

    private static String why(PlayerStalls.Use use, boolean fav) {
        if (fav) return "its favourite";
        return switch (use) {
            case FOOD -> "food for a hungry household";
            case TREAT -> "a treat on market day";
            case TOOL -> "the tool of its trade";
            case HOME -> "a comfort for its home";
            case LUXURY -> "something nice";
            case USE -> "a use for it";                                   // [join21] uses-16's kind
        };
    }

    /** For the shop's screen: who would buy this here and why, or why nobody would. */
    public static String whoBuys(ServerLevel level, UUID village, ItemStack s) {
        if (PlayerStalls.going(level, village, s) <= 0) return "Nobody here deals in it, so nobody knows its worth: it won't sell.";
        if (s.isDamaged()) return "Worn: nobody buys a worn thing.";
        if (PlayerStalls.food(s)) return "Folk buy food when the town is hungry, and a treat on market day.";
        for (AssistantEntity.StationTask t : AssistantEntity.StationTask.values()) {
            var tool = Cafe.toolFor(t);
            if (tool != null && tool.test(s)) return "A " + t.title.toLowerCase(Locale.ROOT) + " without one buys it for its work.";
        }
        if (PlayerStalls.comfort(s, Wealth.Tier.WEALTHY)) return "The comfortable buy it for their homes.";
        if (PlayerStalls.luxury(s)) return "The well-off buy it for pleasure (the comfortable too, on market day).";
        return "Nobody here has a use for it: not food, a tool, a comfort for a home, nor a luxury.";
    }

    // ------------------------------------------------------------------ a visit

    enum Leg { DOOR, IN, LOOK, QUEUE, PAY, OUT, DONE }

    static final class Visit {
        UUID owner, village;
        String shop = "";
        long day;
        int start, legAt, walked = -1000;
        Leg leg = Leg.DOOR;
        BlockPos door = BlockPos.ZERO, shelf = BlockPos.ZERO, look = BlockPos.ZERO;
        boolean market, bought;
        @Nullable String turned;
    }

    private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PLANNED = new ConcurrentHashMap<>();
    private static final Map<String, Long> TURNED_AWAY = new ConcurrentHashMap<>();
    /** Who is queueing at each shop's counter, first first. */
    private static final Map<UUID, List<UUID>> QUEUES = new ConcurrentHashMap<>();

    static void resetForTests() {
        VISITS.clear();
        PLANNED.clear();
        TURNED_AWAY.clear();
        QUEUES.clear();
        WANTS.clear();
        WANTS_AT.clear();
    }

    /** On a visit now (its walk is driven every tick: {@link #hold}). */
    public static boolean underWay(VillageFolkEntity f) {
        Visit t = VISITS.get(f.getUUID());
        return t != null && t.leg != Leg.DONE;
    }

    /**
     * Off work: a folk that wants something an open shop has sets off for it (once a day, again two hours on if it bought
     * nothing). True if it set off.
     */
    public static boolean errand(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.purse() < 1 || underWay(f)) return underWay(f);
        if (PlayerShops.town(village).isEmpty()) return false;
        long now = level.getGameTime();
        Long last = PLANNED.get(f.getUUID());
        if (last != null && now - last < 2400 && now >= last) return false;
        PLANNED.put(f.getUUID(), now);
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        long day = PlayerShops.today(level);
        boolean md = Market.marketDay(village, day);
        List<PlayerShops.Shop> shops = PlayerShops.all(village);
        int start = shops.isEmpty() ? 0 : Math.floorMod(f.getUUID().hashCode() + (int) day, shops.size());
        for (int i = 0; i < shops.size(); i++) {
            PlayerShops.Shop s = shops.get((start + i) % shops.size());
            if (s.owner.equals(f.getUUID()) || PlayerShops.state(level, s) != PlayerShops.State.OPEN) continue;
            if (s.anchor.distSqr(f.blockPosition()) > 112.0 * 112.0) continue;
            if (s.visitDay != day) { s.visitDay = day; s.visitsToday = 0; }
            if (s.visitsToday >= visitsADay(s)) continue;
            Long away = TURNED_AWAY.get(f.getUUID() + "/" + s.owner);
            if (!md && away != null && day - away <= 1) continue;
            if (!hasWanted(level, f, s)) continue;
            return go(level, f, s, md);
        }
        return false;
    }

    /** A shop's name brings custom: four a day at the worst, two dozen at the best. */
    static int visitsADay(PlayerShops.Shop s) {
        return 4 + (int) Math.round(s.rep * 20);
    }

    static boolean hasWanted(ServerLevel level, VillageFolkEntity f, PlayerShops.Shop s) {
        for (Container c : PlayerShops.shelves(level, s)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack x = c.getItem(i);
                if (!x.isEmpty() && wants(level, f, x) != null) return true;
            }
        }
        return false;
    }

    /** Set off for this shop now (tests, and the errand). */
    public static boolean go(ServerLevel level, VillageFolkEntity f, PlayerShops.Shop s, boolean market) {
        ShopNeeds.Survey sv = PlayerShops.survey(level, s);
        if (sv.outside == null) return false;
        Visit t = new Visit();
        t.owner = s.owner;
        t.village = s.village;
        t.shop = s.name;
        t.day = PlayerShops.today(level);
        t.start = f.tickCount;
        t.legAt = f.tickCount;
        t.door = sv.outside;
        t.market = market;
        s.visitsToday++;
        VISITS.put(f.getUUID(), t);
        f.hobbyNow = "on the way to " + s.name;
        return true;
    }

    /** Every tick for a folk on a visit: its walk, its look, its turn in the queue, its purchase. True while it is about it. */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Visit t = VISITS.get(f.getUUID());
        if (t == null || t.leg == Leg.DONE) return false;
        PlayerShops.Shop s = PlayerShops.of(t.village, t.owner);
        if (s == null || f.tickCount - t.start > VISIT || f.isSleeping() || f.getHealth() < f.getMaxHealth() * 0.4F) {
            end(f, t, s);
            return false;
        }
        ShopNeeds.Survey sv = PlayerShops.survey(level, s);
        switch (t.leg) {
            case DOOR -> {
                if (closeTo(f, t.door, 1.6)) {
                    door(level, sv, f, true);                                 // in at the door: it opens it
                    next(f, t, Leg.IN);
                } else walk(f, t, t.door, 0.8);
            }
            case IN -> {
                if (t.shelf.equals(BlockPos.ZERO)) pickShelf(level, f, t, s, sv);
                if (t.shelf.equals(BlockPos.ZERO)) { next(f, t, Leg.QUEUE); break; }
                if (closeTo(f, t.look, 1.3)) next(f, t, Leg.LOOK);
                else walk(f, t, t.look, 0.7);
            }
            case LOOK -> {
                f.getNavigation().stop();
                f.getLookControl().setLookAt(t.shelf.getX() + 0.5, t.shelf.getY() + 0.6, t.shelf.getZ() + 0.5);
                if (f.tickCount - t.legAt >= BROWSE) next(f, t, Leg.QUEUE);
            }
            case QUEUE -> {
                List<UUID> q = QUEUES.computeIfAbsent(s.owner, k -> new ArrayList<>());
                q.removeIf(u -> { VillageFolkEntity o = Homes.loaded(t.village, u); Visit ov = VISITS.get(u);
                    return o == null || ov == null || ov.leg == Leg.DONE || ov.leg == Leg.OUT || !ov.owner.equals(t.owner); });
                if (!q.contains(f.getUUID())) q.add(f.getUUID());
                int place = q.indexOf(f.getUUID());
                BlockPos spot = queueSpot(level, s, sv, place);
                BlockPos counter = sv.counter();
                if (closeTo(f, spot, 1.2)) {
                    f.getNavigation().stop();
                    if (counter != null) f.getLookControl().setLookAt(counter.getX() + 0.5, counter.getY() + 1.0, counter.getZ() + 0.5);
                    if (place == 0) next(f, t, Leg.PAY);
                    else f.hobbyNow = "queueing at " + s.name;
                } else {
                    walk(f, t, spot, 0.6);
                }
            }
            case PAY -> {
                f.getNavigation().stop();
                BlockPos counter = sv.counter();
                if (counter != null) f.getLookControl().setLookAt(counter.getX() + 0.5, counter.getY() + 1.0, counter.getZ() + 0.5);
                if (f.tickCount - t.legAt >= PAY) {
                    String bought = buy(level, f, s, t.market, true);
                    t.bought = bought != null;
                    if (bought != null) f.swing(InteractionHand.MAIN_HAND);
                    List<UUID> q = QUEUES.get(s.owner);
                    if (q != null) q.remove(f.getUUID());
                    next(f, t, Leg.OUT);
                }
            }
            case OUT -> {
                if (f.tickCount - t.legAt == 1) door(level, sv, f, true);
                if (closeTo(f, t.door, 1.6) || f.tickCount - t.legAt > LEG) {
                    door(level, sv, f, false);                                // and shuts it behind it
                    end(f, t, s);
                } else walk(f, t, t.door, 0.8);
            }
            default -> { }
        }
        if (t.leg != Leg.DONE && t.leg != Leg.OUT && t.leg != Leg.PAY && t.leg != Leg.LOOK && f.tickCount - t.legAt > LEG) {
            end(f, t, s);                                                    // could not get there
            return false;
        }
        return t.leg != Leg.DONE;
    }

    /** The shop's door nearest this folk, opened or shut as a customer would. */
    private static void door(ServerLevel level, ShopNeeds.Survey sv, VillageFolkEntity f, boolean open) {
        BlockPos best = null;
        for (BlockPos d : sv.doors) if (best == null || d.distSqr(f.blockPosition()) < best.distSqr(f.blockPosition())) best = d;
        if (best == null || best.distSqr(f.blockPosition()) > 9) return;
        var st = level.getBlockState(best);
        if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door && st.getValue(net.minecraft.world.level.block.DoorBlock.OPEN) != open) {
            door.setOpen(f, level, st, best, open);
        }
    }

    private static void next(VillageFolkEntity f, Visit t, Leg leg) {
        t.leg = leg;
        t.legAt = f.tickCount;
        t.walked = -1000;
    }

    private static void end(VillageFolkEntity f, Visit t, @Nullable PlayerShops.Shop s) {
        t.leg = Leg.DONE;
        if (s != null) {
            List<UUID> q = QUEUES.get(s.owner);
            if (q != null) q.remove(f.getUUID());
            if (!t.bought) TURNED_AWAY.put(f.getUUID() + "/" + s.owner, t.day);
        }
        f.hobbyNow = null;
    }

    /** On that spot: near enough across, and on its floor (not up on the counter beside it). */
    static boolean closeTo(VillageFolkEntity f, BlockPos p, double r) {
        double dx = f.getX() - (p.getX() + 0.5), dz = f.getZ() - (p.getZ() + 0.5);
        return dx * dx + dz * dz <= r * r && Math.abs(f.getY() - p.getY()) < 0.6;
    }

    private static void walk(VillageFolkEntity f, Visit t, BlockPos to, double speed) {
        if (f.getNavigation().isDone() || f.tickCount - t.walked >= 60) {
            f.walkTo(to, speed);
            t.walked = f.tickCount;
        }
    }

    /** The shelf it goes to look at: one with something it wants on it, else any. */
    private static void pickShelf(ServerLevel level, VillageFolkEntity f, Visit t, PlayerShops.Shop s, ShopNeeds.Survey sv) {
        BlockPos any = null, anyLook = null;
        for (BlockPos p : sv.stock) {
            if (s.back.contains(p)) continue;
            BlockPos look = standBeside(level, sv, p);
            if (look == null) continue;
            if (any == null) { any = p; anyLook = look; }
            if (level.getBlockEntity(p) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (wants(level, f, c.getItem(i)) != null) {
                        t.shelf = p;
                        t.look = look;
                        return;
                    }
                }
            }
        }
        if (any != null) { t.shelf = any; t.look = anyLook; }
    }

    /** A spot inside the shop next to this block, to stand on. */
    @Nullable
    static BlockPos standBeside(ServerLevel level, ShopNeeds.Survey sv, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            for (int dy : new int[]{ 0, -1, 1 }) {
                BlockPos f = n.above(dy);
                if (sv.room.contains(f) && sv.room.contains(f.above()) && !sv.room.contains(f.below())) return f;
            }
        }
        return null;
    }

    /** Where the {@code i}th in the queue stands: before the counter, then back from it. */
    static BlockPos queueSpot(ServerLevel level, PlayerShops.Shop s, ShopNeeds.Survey sv, int i) {
        BlockPos counter = sv.counter();
        if (counter == null) return sv.outside == null ? s.anchor : sv.outside;
        Direction front = level.getBlockState(counter).hasProperty(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING)
            ? level.getBlockState(counter).getValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING) : Direction.NORTH;
        BlockPos first = counter.relative(front);
        if (!sv.room.contains(first)) {
            BlockPos b = standBeside(level, sv, counter);
            first = b == null ? counter.relative(front) : b;
        }
        BlockPos spot = first;
        for (int k = 0; k < i; k++) {
            BlockPos n = spot.relative(front);
            if (!sv.room.contains(n)) break;
            spot = n;
        }
        return spot;
    }

    // ------------------------------------------------------------------ the purchase

    private record Offer(Container box, ItemStack sample, String key, int n, int price, double going, boolean favourite, PlayerStalls.Use use) {
        double ratio() { return going <= 0 ? 99 : price / going; }
    }

    /**
     * At the counter: of what the shelves hold that it wants and thinks fair and can pay for, its favourite, else the best
     * bargain; bought out of its purse into the till, the goods to it. Nothing fair: the dearest it looked at goes on the
     * books as too dear (and said aloud if {@code there}). Returns what it bought, in words, or null.
     */
    @Nullable
    public static String buy(ServerLevel level, VillageFolkEntity f, PlayerShops.Shop s, boolean market, boolean there) {
        if (PlayerShops.state(level, s) != PlayerShops.State.OPEN) return null;
        UUID id = s.village;
        Offer best = null, dearest = null;
        List<String> looked = new ArrayList<>();
        double nameBonus = (s.rep - 0.5) * 0.2;
        for (Container box : PlayerShops.shelves(level, s)) {
            for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack x = box.getItem(i);
                if (x.isEmpty()) continue;
                Want w = wants(level, f, x);
                if (w == null) continue;
                String key = PlayerStalls.key(x);
                if (looked.contains(key)) continue;
                looked.add(key);
                int going = PlayerStalls.going(level, id, x);
                if (going <= 0) continue;
                int price = PlayerShops.asking(level, s, key, x);
                if (price <= 0) continue;
                int lot = PlayerStalls.lot(x);
                int have = count(box, x);
                int n = Math.min(lot, have);
                int priceN = n >= lot ? price : Math.max(1, (int) Math.ceil(price * n / (double) lot - 1e-9));
                double goingN = going * n / (double) lot;
                boolean villageHas = Market.stock(level, id, y -> ItemStack.isSameItemSameComponents(y, x)) > 0;
                Offer o = new Offer(box, x.copyWithCount(1), key, n, priceN, goingN, w.favourite(), w.use());
                int least = villageHas ? Market.leastLot(level, id, x, n) : Integer.MAX_VALUE;
                double k = Math.min(PlayerStalls.FAR_OVER, PlayerStalls.willing(f, w.favourite()) + nameBonus);
                if (priceN > Math.min(least, PlayerStalls.most(goingN, k, villageHas))) {
                    if (dearest == null || o.ratio() > dearest.ratio()) dearest = o;
                    continue;
                }
                if (f.purse() < priceN + (w.use().need ? 0 : 2)) continue;
                if (best == null || (o.favourite() && !best.favourite())
                        || (o.favourite() == best.favourite() && o.ratio() < best.ratio())) best = o;
            }
        }
        String who = f.displayNameCap();
        if (best == null) {
            if (dearest != null) {
                int going = (int) Math.max(1, Math.round(dearest.going()));
                PlayerShops.tooDear(level, s, who, dearest.sample(), dearest.price(), going);
                if (there) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), capital(PlayerStalls.words(dearest.price())) + " for "
                    + PlayerStalls.nameOf(dearest.sample()).toLowerCase(Locale.ROOT) + "? I'll get it cheaper at the market.",
                    "Too dear for me, I'm afraid.", "Maybe if it were " + PlayerStalls.words(going) + "."));
            } else {
                PlayerShops.bare(level, s);
                if (there) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Nothing I need today.", "I'll look in again another day."));
            }
            return null;
        }
        ItemStack got = take(best.box(), best.sample(), best.n());
        if (got.getCount() < best.n() || !f.spend(best.price())) {
            putBack(best.box(), got);
            return null;
        }
        PlayerShops.booked(level, s, who, got, best.price(), best.going());
        String lotWords = PlayerStalls.thing(got, got.getCount());
        deliver(level, f, got, best.use());
        long day = PlayerShops.today(level);
        f.brain("bought " + lotWords + " at " + s.name);
        f.persona().remember(day, "I bought " + lotWords + " at " + s.name, 1);
        if (f.getRandom().nextInt(3) == 0) f.persona().feelFor(s.owner, s.ownerName, 1);
        if (there) {
            BlockPos at = s.anchor;
            level.playSound(null, at, SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.1F);
            level.playSound(null, at, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.4F, 1.6F);
            VillageFolkEntity keeper = ShopKeepers.behindCounter(level, s);
            if (keeper != null) {
                FolkTalk.speak(keeper, FolkTalk.pick(keeper.getRandom(), "That's " + PlayerStalls.words(best.price()) + ", thank you kindly.",
                    "There you are. Come again!", capital(lotWords) + ": a good choice."));
            }
            boolean bargain = best.ratio() < 0.95;
            FolkTalk.speak(f, best.favourite() ? capital(lotWords) + "! My favourite."
                : bargain ? FolkTalk.pick(f.getRandom(), "Cheaper than the market. I'll be back.", capital(lotWords) + " for " + PlayerStalls.words(best.price()) + "? A bargain.")
                : FolkTalk.pick(f.getRandom(), capital(lotWords) + " — a fair price.", "Just what I came for.", "Thank you, " + s.ownerName + "'s!"));
        }
        return lotWords + " at " + s.name;
    }

    private static void deliver(ServerLevel level, VillageFolkEntity f, ItemStack got, PlayerStalls.Use use) {
        if (use == PlayerStalls.Use.HOME && f.carryHome(got.copyWithCount(1))) got.shrink(1);
        if (got.isEmpty()) return;
        if (use == PlayerStalls.Use.HOME || use == PlayerStalls.Use.LUXURY) Homes.keepsake(got, f, Homes.Source.BOUGHT);
        ItemStack left = f.insertItem(got);
        Villages.Village v = Villages.get(f.ownerId());
        if (!left.isEmpty() && v != null) Crafts.store(level, v, left);
    }

    static int count(Container box, ItemStack like) {
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack x = box.getItem(i);
            if (!x.isEmpty() && ItemStack.isSameItemSameComponents(x, like)) n += x.getCount();
        }
        return n;
    }

    static ItemStack take(Container box, ItemStack like, int n) {
        int got = 0;
        for (int i = box.getContainerSize() - 1; i >= 0 && got < n; i--) {
            ItemStack x = box.getItem(i);
            if (x.isEmpty() || !ItemStack.isSameItemSameComponents(x, like)) continue;
            int k = Math.min(n - got, x.getCount());
            x.shrink(k);
            if (x.isEmpty()) box.setItem(i, ItemStack.EMPTY);
            got += k;
        }
        box.setChanged();
        return like.copyWithCount(got);
    }

    static void putBack(Container box, ItemStack x) {
        if (x.isEmpty()) return;
        ItemStack left = Stacking.insert(box, x);
        box.setChanged();
        if (!left.isEmpty() && box instanceof net.minecraft.world.level.block.entity.BlockEntity be && be.getLevel() != null) {
            net.minecraft.world.level.block.Block.popResource(be.getLevel(), be.getBlockPos().above(), left);
        }
    }

    static String capital(String x) {
        return x.isEmpty() ? x : Character.toUpperCase(x.charAt(0)) + x.substring(1);
    }

    /** Tests: where a folk is on its visit ("DOOR", "LOOK", "QUEUE", "PAY", "OUT", "DONE"), or "". */
    public static String legForTests(VillageFolkEntity f) {
        Visit t = VISITS.get(f.getUUID());
        return t == null ? "" : t.leg.name();
    }

    /** Tests: a purchase at the counter now, as if this folk had walked in. */
    @Nullable
    public static String buyForTests(ServerLevel level, VillageFolkEntity f, PlayerShops.Shop s) {
        return buy(level, f, s, false, true);
    }

    static final Item NONE = Items.AIR;
}
