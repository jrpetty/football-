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
 * <li><b>The one decision</b> what a folk would want of a thing is {@link #wants}: ItemUses (what each thing is for, and
 *     what each folk has a use for now) answers it there, and a thing bought is put to its use.</li>
 * </ul>
 */
public final class ShopCustomers {

    private ShopCustomers() {}

    /** How long a whole visit may take, and each leg of it (ticks). */
    static final int VISIT = 2400, LEG = 600, BROWSE = 50, PAY = 30;

    /** What a folk would want of a thing, and why, or null: the one place the buying decision is made. [join21] uses-16's
     *  ItemUses answers it: what the folk (its household, its firm, the town it buys for) has a real use for now, how many,
     *  the most it would pay for one, and who pays. */
    public record Want(ItemUses.Want use, boolean favourite, String why) {}

    /** Would this folk want this thing, and what for (ItemUses: its needs, its means and its room). Null if not. */
    @Nullable
    public static Want wants(ServerLevel level, VillageFolkEntity f, ItemStack s) {
        if (s.isEmpty() || Market.isCoin(s) || s.isDamaged()) return null;
        ItemUses.Want w = ItemUses.want(f, s);                               // [join21]
        if (!w.yes()) return null;
        return new Want(w, false, w.reason());
    }

    /** [join21] Its books' key for what folk turned down here (apart from its owner's stall's). */
    static UUID refusals(UUID owner) {
        return UUID.nameUUIDFromBytes(("shop:" + owner).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** For the shop's screen: who would buy this here and why, or why nobody would (ItemUses' verdict on the whole town). */
    public static String whoBuys(ServerLevel level, UUID village, ItemStack s) {
        if (s.isDamaged()) return "Worn: nobody buys a worn thing.";
        ItemUses.Verdict v = ItemUses.judge(level, village, s);              // [join21]
        if (!v.yes()) return v.line() + ".";
        return "Would buy it: " + v.line() + "; " + v.qty() + " wanted in all, up to " + PlayerLife.coins(v.mostEach()) + " each.";
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
        UUID village = CoinCensus.purseTown(f);                              // [join21] a visitor shops in the town it is visiting
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
        return (int) Math.round((4 + s.rep * 20) * Fittings.footfall(s.village, "pshop:" + s.key()));   // [till] its fit-out brings custom
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
                q.removeIf(u -> { VillageFolkEntity o = level.getEntity(u) instanceof VillageFolkEntity vf ? vf : null; Visit ov = VISITS.get(u);   // [join21] a visitor too
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

    private record Offer(Container box, ItemStack sample, String key, int n, int price, double going, ItemUses.Want want) {
        double ratio() { return going <= 0 ? 99 : price / going; }
    }

    /**
     * At the counter: [join21] of what the shelves hold that it has a real use for now (ItemUses), at a price it thinks fair
     * and whoever pays for it (its purse, its firm's till, the treasury) can pay, the best value for the use, no more of it
     * than the use takes; bought into the till, the goods put to their use (ItemUses.use). What it has no use for goes on
     * the shop's books with the reason. Nothing fair: the dearest it looked at goes on the books as too dear (and said aloud
     * if {@code there}). Returns what it bought, in words, or null.
     */
    @Nullable
    public static String buy(ServerLevel level, VillageFolkEntity f, PlayerShops.Shop s, boolean market, boolean there) {
        if (PlayerShops.state(level, s) != PlayerShops.State.OPEN) return null;
        UUID id = s.village;
        Offer best = null, dearest = null;
        double bestScore = -1;
        String noUse = null;
        List<String> looked = new ArrayList<>();
        double nameBonus = (s.rep - 0.5) * 0.2;
        for (Container box : PlayerShops.shelves(level, s)) {
            for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack x = box.getItem(i);
                if (x.isEmpty() || Market.isCoin(x) || x.isDamaged()) continue;
                String key = PlayerStalls.key(x);
                if (looked.contains(key)) continue;
                looked.add(key);
                ItemUses.Want w = ItemUses.want(f, x);
                if (!w.yes()) {
                    if (there) ItemUses.turnedDown(level, id, refusals(s.owner), key, f, w.reason());
                    if (noUse == null) noUse = w.reason();
                    continue;
                }
                int going = PlayerStalls.going(level, id, x);
                if (going <= 0) continue;
                int price = PlayerShops.asking(level, s, key, x);
                if (price <= 0) continue;
                int lot = PlayerStalls.lot(x);
                int n = Math.min(count(box, x), w.qty());                   // as many as the use takes, a part lot or several
                if (n <= 0) continue;
                int priceN = Math.max(1, (int) Math.ceil(price * n / (double) lot - 1e-9));   // [join21] its share of the lot's price, or several lots'
                double goingN = going * n / (double) lot;
                boolean villageHas = Market.stock(level, id, y -> ItemStack.isSameItemSameComponents(y, x)) > 0;
                Offer o = new Offer(box, x.copyWithCount(1), key, n, priceN, goingN, w);
                int least = villageHas ? Market.leastLot(level, id, x, n) : Integer.MAX_VALUE;
                double k = Math.min(PlayerStalls.FAR_OVER, ItemUses.keen(f, w.need()) + nameBonus);
                if (priceN > Math.min(least, PlayerStalls.most(goingN, k, villageHas))) {
                    if (dearest == null || o.ratio() > dearest.ratio()) dearest = o;
                    continue;
                }
                boolean own = w.need().payer() == ItemUses.Payer.OWN;
                if (own && f.purse() < priceN + (w.value() >= 0.6 ? 0 : 2)) continue;
                double score = (0.5 + w.value()) * ItemUses.fit(x, w) * n / priceN;
                if (best == null || score > bestScore) {
                    best = o;
                    bestScore = score;
                }
            }
        }
        String who = f.displayNameCap();
        if (best == null) missedHere(level, f, s, looked);                  // [biz30c] a ware it sells, gone from its shelves, this buyer wanted
        if (best == null) {
            if (dearest != null) {
                int going = (int) Math.max(1, Math.round(dearest.going()));
                PlayerShops.tooDear(level, s, who, dearest.sample(), dearest.price(), going);
                if (there) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), capital(PlayerStalls.words(dearest.price())) + " for "
                    + PlayerStalls.nameOf(dearest.sample()).toLowerCase(Locale.ROOT) + "? I'll get it cheaper at the market.",
                    "Too dear for me, I'm afraid.", "Maybe if it were " + PlayerStalls.words(going) + "."));
            } else {
                PlayerShops.bare(level, s);
                if (there) FolkTalk.speak(f, noUse != null ? capital(noUse.endsWith("?") ? noUse : noUse + ".")
                    : FolkTalk.pick(f.getRandom(), "Nothing I need today.", "I'll look in again another day."));
            }
            return null;
        }
        ItemStack got = take(best.box(), best.sample(), best.n());
        String forWhat = s.name;
        if (got.getCount() < best.n() || !ItemUses.pay(level, f, best.want(), best.price(), got.getCount() + " " + ItemUses.name(got) + " at " + forWhat)) {
            putBack(best.box(), got);
            return null;
        }
        ItemUses.paid(f, best.price());
        ItemUses.Payer payer = best.want().need().payer();
        String whoFor = who + (payer == ItemUses.Payer.FIRM ? " (for " + best.want().need().reason().replaceFirst(" is short of.*", "") + ")"
            : payer == ItemUses.Payer.TOWN ? " (for the town)" : "");
        PlayerShops.booked(level, s, whoFor, got, best.price(), best.going());
        Tills.rang(level, s.village, "pshop:" + s.key(), f.blockPosition(), f.displayNameCap(),
            s.keeper != null && level.getEntity(s.keeper) instanceof VillageFolkEntity k ? k : null);   // [till] its till rings
        String lotWords = PlayerStalls.thing(got, got.getCount());
        long day = PlayerShops.today(level);
        f.brain("bought " + lotWords + " at " + s.name);
        f.persona().remember(day, "I bought " + lotWords + " at " + s.name, 1);
        if (f.getRandom().nextInt(3) == 0) f.persona().feelFor(s.owner, s.ownerName, 1);
        ItemUses.use(f, got, best.want(), forWhat);                          // [join21] put to its use: worn, sown, set out at home
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
            FolkTalk.speak(f, bargain ? FolkTalk.pick(f.getRandom(), "Cheaper than the market. I'll be back.", capital(lotWords) + " for " + PlayerStalls.words(best.price()) + "? A bargain.")
                : FolkTalk.pick(f.getRandom(), capital(lotWords) + " — just what I needed: " + best.want().reason() + ".", "Just what I came for.", "Thank you, " + s.ownerName + "'s!"));
        }
        return lotWords + " at " + s.name;
    }

    // ------------------------------------------------------------------ [join21] at the town's counter

    /**
     * A player's shop as a seller to a folk buying at the town's counter (Sellers.best): the best open shop with {@code n}
     * of this on its shelves, for a buyer that has a use for it (ItemUses) at no more than it would pay for that use, weighed
     * as any seller is (the price, the walk, the shop's name). Null if none would do.
     */
    @Nullable
    static Sellers.Offer counterOffer(ServerLevel level, UUID village, @Nullable VillageFolkEntity buyer, ItemStack one, int n, double going, BlockPos from) {
        if (buyer == null || one.isEmpty() || n <= 0) return null;
        List<PlayerShops.Shop> shops = PlayerShops.all(village);
        if (shops.isEmpty()) return null;
        ItemUses.Want w = null;
        String key = PlayerStalls.key(one);
        Sellers.Offer best = null;
        for (PlayerShops.Shop s : shops) {
            if (s.owner.equals(buyer.getUUID()) || !level.isLoaded(s.anchor) || PlayerShops.state(level, s) != PlayerShops.State.OPEN) continue;
            int have = 0;
            for (Container c : PlayerShops.shelves(level, s)) have += count(c, one);
            if (have < n) continue;
            int price = PlayerShops.asking(level, s, key, one);
            if (price <= 0) continue;
            if (w == null) {
                w = ItemUses.want(buyer, one);
                if (!w.yes()) return null;                                   // no use for it: the town's counter, as before
            }
            double each = price / (double) Math.max(1, PlayerStalls.lot(one));
            if (each > w.mostEach() + 1e-9) continue;                        // dearer than it would pay for its use
            double u = Sellers.utility(each, going, Math.sqrt(from.distSqr(s.anchor)), s.rep);
            if (best == null || u > best.utility()) best = new Sellers.Offer(null, each, u, s);
        }
        return best;
    }

    /**
     * The sale, when a player's shop won a counter purchase: {@code n} of it off the shop's shelves, paid out of the payer's
     * purse into the shop's till (its books and its owner's), into the pack of the folk it is for. Returns how many (nought:
     * nothing sold, nothing moved).
     */
    public static int counterSale(ServerLevel level, VillageFolkEntity payer, VillageFolkEntity to, PlayerShops.Shop s, ItemStack one, int n, double each) {
        if (n <= 0 || PlayerShops.state(level, s) != PlayerShops.State.OPEN) return 0;
        int coins = Math.max(1, (int) Math.ceil(each * n - 1e-9));
        if (payer.purse() < coins) return 0;
        List<Container> shelves = PlayerShops.shelves(level, s);
        List<Container> from = new ArrayList<>();
        List<ItemStack> taken = new ArrayList<>();
        int got = 0;
        for (Container c : shelves) {
            if (got >= n) break;
            ItemStack g = take(c, one, n - got);
            if (g.isEmpty()) continue;
            from.add(c);
            taken.add(g);
            got += g.getCount();
        }
        if (got < n || !payer.spend(coins)) {
            for (int i = 0; i < taken.size(); i++) putBack(from.get(i), taken.get(i));
            return 0;
        }
        ItemStack lot = one.copyWithCount(n);
        int going = PlayerStalls.going(level, s.village, one);
        PlayerShops.booked(level, s, payer.displayNameCap() + " (through the town's counter)", lot, coins, going * n / (double) Math.max(1, PlayerStalls.lot(one)));
        ItemStack left = to.insertGiven(lot.copy());
        Villages.Village v = Villages.get(to.ownerId());
        if (!left.isEmpty() && v != null) Crafts.store(level, v, left);
        payer.brain("bought " + PlayerStalls.thing(lot, n) + " of " + s.name);
        return n;
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
    /** [biz30c] A sale missed: a ware the shop has sold (on its books) not on its shelves now, that this buyer would have had. */
    static void missedHere(ServerLevel level, VillageFolkEntity f, PlayerShops.Shop s, List<String> looked) {
        Business book = Businesses.shopBook(level, s);
        for (String key : book.allTime.lines.keySet()) {
            if (looked.contains(key)) continue;
            ItemStack sample = Stockroom.sampleOf(key);
            if (sample.isEmpty() || !ItemUses.want(f, sample).yes()) continue;
            Businesses.missed(level, book, key, 1);
            return;
        }
    }

    public static String buyForTests(ServerLevel level, VillageFolkEntity f, PlayerShops.Shop s) {
        return buy(level, f, s, false, true);
    }

    static final Item NONE = Items.AIR;
}
