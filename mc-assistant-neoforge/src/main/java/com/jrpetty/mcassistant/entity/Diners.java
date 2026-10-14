package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.PlaceSettingBlock;
import com.jrpetty.mcassistant.entity.DiningMenu.Course;
import com.jrpetty.mcassistant.entity.DiningPlaces.Place;
import com.jrpetty.mcassistant.entity.DiningPlaces.Table;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [dining] The diners: folk who eat out, and the way a player sits down to order.
 * <ul>
 * <li><b>Who eats out, and when.</b> Of an evening a folk with coin to spare may dine out: never the poor (they eat at
 *     home, or the stores feed them), now and then those getting by, more often the comfortable, most evenings the
 *     wealthy; twice as likely on payday, more on the day of rest (a family dinner), with its partner along when it is
 *     free (a couple courting), less when it is low. It goes to the place open now (the restaurant first for the well off,
 *     the tavern of a late evening, the café by day), takes a free table, sits down; a waiter comes over and it orders
 *     (a word each side) what it can afford of the card: a drink, a main, a starter or a pudding as its purse runs to.</li>
 * <li><b>At the table</b> it is served as a player is (Dining), eats bite by bite (its hunger and its health), chats with
 *     whoever is at the next table, pays the bill to the hundredth (on the slate if its purse is short), leaves a tip if
 *     it is pleased, and gets up; the waiter clears. It is the happier for it for the day (Dining.mood), and it has had
 *     its supper (Meals). Kept waiting too long, it gives up, grumbling; what was made goes back to the stores.</li>
 * <li><b>A player</b> sits down by right-clicking a chair at a table in a café, a tavern or a restaurant (or stands at
 *     a table and right-clicks its cloth): the order screen opens for that table, and the waiter comes over to say hello.
 *     The menu board by the door and the counter open the same screen, to take away or have it brought.</li>
 * </ul>
 */
public final class Diners {

    private Diners() {}

    /** Where a diner is. */
    enum Stage { GOING, SEATED, ORDERED, PAYING }

    /** A folk dining out: where, which table, its order, since when. */
    static final class Diner {
        final com.jrpetty.mcassistant.village.Ledger.Building place;
        final int table;
        Stage stage = Stage.GOING;
        int order = -1;
        long since, lastBite, waiterAsked = -1;
        @Nullable UUID partner;

        Diner(com.jrpetty.mcassistant.village.Ledger.Building place, int table, long since) {
            this.place = place;
            this.table = table;
            this.since = since;
        }
    }

    static final Map<UUID, Diner> DINERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DECIDED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DINED = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_PLACE = new ConcurrentHashMap<>();

    /** The chance in a hundred of an evening out, by how well off it is: never the poor. */
    static final int[] CHANCE_BY_TIER = { 0, 5, 12, 25, 40 };
    /** How long a diner waits for its food before it gives up (ticks); for a waiter to take its order before it orders
     *  at the counter. */
    static final long GIVES_UP = 6000L, ASKS_AT_COUNTER = 600L;
    /** The coin a folk wants in its purse to think of eating out. */
    static final int MIN_PURSE = 4;

    static void resetForTests() {
        DINERS.clear();
        DECIDED.clear();
        DINED.clear();
        LAST_PLACE.clear();
    }

    static boolean none() {
        return DINERS.isEmpty();
    }

    /** This folk at a table out, or null. */
    @Nullable
    static Diner of(VillageFolkEntity f) {
        return DINERS.get(f.getUUID());
    }

    /** Is this folk out to dinner (its supper waits for the table: Meals)? */
    public static boolean dining(VillageFolkEntity f) {
        return DINERS.containsKey(f.getUUID());
    }

    /** The day it last dined out (-1 never). */
    static long dinedOn(VillageFolkEntity f) {
        return DINED.getOrDefault(f.getUUID(), -1L);
    }

    static String lastPlace(VillageFolkEntity f) {
        return LAST_PLACE.getOrDefault(f.getUUID(), "");
    }

    // ------------------------------------------------------------------ the evening out

    /**
     * Its evening (VillageFolkEntity.eveningSocial, before the family supper): out to dinner if it is one of tonight's
     * diners, or decides to be. True while it is.
     */
    public static boolean evening(VillageFolkEntity f, long t) {
        if (!(f.level() instanceof ServerLevel level) || f.isBaby() || f.ownerId() == null || f.isShowcase()) return false;
        Diner d = DINERS.get(f.getUUID());
        if (d == null) {
            if (!decides(level, f)) return false;
            d = start(level, f, null);
            if (d == null) return false;
        }
        Place p = DiningPlaces.place(level, f.ownerId(), d.place);
        Table tb = p == null ? null : p.table(d.table);
        if (tb == null) {
            leave(level, f, d, "");
            return false;
        }
        if (d.stage == Stage.GOING && !Seats.sitting(f)) Seats.dine(f, level, tb.seat(), f.tickCount + 12000);
        if (d.stage == Stage.GOING && Seats.seated(f)) {
            d.stage = Stage.SEATED;
            d.since = level.getGameTime();
        }
        f.hobbyNow = d.stage == Stage.GOING ? "going out to dinner at " + p.words() : "dining at " + p.words() + ", table " + d.table;
        return true;
    }

    /** Does it dine out tonight? Once an evening it weighs it up (the class comment), by a roll of its own and the day's. */
    static boolean decides(ServerLevel level, VillageFolkEntity f) {
        long day = level.getDayTime() / 24000L;
        Long was = DECIDED.put(f.getUUID(), day);
        if (was != null && was == day) return false;
        if (f.purse() < MIN_PURSE || Raids.underAlarm(f.ownerId()) || f.stationTask() == AssistantEntity.StationTask.COOK && Dining.busy(f)) return false;
        int tier = Wealth.tier(f).ordinal();
        int chance = CHANCE_BY_TIER[Math.min(tier, CHANCE_BY_TIER.length - 1)];
        if (chance <= 0) return false;
        if (Market.paidOn(f.getUUID(), day) > 0) chance *= 2;                       // payday: a meal out
        if (RestDay.now(f.ownerId(), level.getDayTime()) != null) chance += 15;     // the day of rest: the family's dinner
        if (f.life().partner() != null && f.persona().mood() >= 60) chance += 10;   // a couple out together
        if (f.persona().mood() < 30) chance /= 2;
        int roll = Math.floorMod((int) (day * 73L) ^ f.getUUID().hashCode() * 31, 100);
        return roll < chance;
    }

    /**
     * Off to dinner: the place open now that suits (the restaurant first for the well off, else whichever is open), a
     * free table there; its partner along to the next free table if it is free too. Null if there is nowhere.
     */
    @Nullable
    static Diner start(ServerLevel level, VillageFolkEntity f, @Nullable Place only) {
        UUID id = f.ownerId();
        if (id == null) return null;
        long t = level.getDayTime() % 24000L;
        Place best = null;
        Table table = null;
        for (Place p : DiningPlaces.of(level, id)) {
            if (only != null && p != only) continue;
            if (!ServicePoints.HOURS_OFF_FOR_TESTS && !p.kind.role.hours.isOpen(t)) continue;
            Table free = freeTable(level, p, null);
            if (free == null) continue;
            best = p;
            table = free;
            break;                                                    // the restaurant first (DiningPlaces.of's order)
        }
        if (best == null) return null;
        Diner d = new Diner(best.building, table.number(), level.getGameTime());
        DINERS.put(f.getUUID(), d);
        f.brain("out to dinner at " + best.words());
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'm eating out tonight.", "Dinner at " + best.words() + ", I think.",
            "No cooking for me tonight!"));
        // Its partner, if free, comes along to the next table.
        UUID partner = f.life().partner();
        if (partner != null && level.getEntity(partner) instanceof VillageFolkEntity pf && pf.isAlive() && !pf.isSleeping() && pf.offWorkNow()
                && !DINERS.containsKey(partner) && !Dining.busy(pf) && pf.distanceToSqr(f) < 64 * 64) {
            Table next = freeTable(level, best, table.seat());
            if (next != null) {
                DINERS.put(partner, new Diner(best.building, next.number(), level.getGameTime()));
                DECIDED.put(partner, level.getDayTime() / 24000L);
                d.partner = partner;
                FolkTalk.speak(pf, "Dinner out? Lovely, I'll come.");
            }
        }
        return d;
    }

    /** A table nobody has: no order on it, no diner going to it, its chair free, nothing left on it. The nearest to
     *  {@code near} if given (a couple side by side). */
    @Nullable
    static Table freeTable(ServerLevel level, Place p, @Nullable BlockPos near) {
        Table best = null;
        double bestD = Double.MAX_VALUE;
        for (Table t : p.tables) {
            if (Dining.atTable(p.building, t.number()) != null || claimed(p, t.number())) continue;
            if (Seats.taken(level, t.seat(), null) || Seats.claimed(t.seat())) continue;
            BlockState s = level.getBlockState(t.cell());
            if (s.getBlock() instanceof PlaceSettingBlock && s.getValue(PlaceSettingBlock.HOUSE)) continue;   // not yet cleared
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, new net.minecraft.world.phys.AABB(t.seat()).inflate(0.6)).isEmpty()) continue;
            double d = near == null ? t.number() : near.distSqr(t.seat());
            if (d < bestD) { bestD = d; best = t; }
        }
        return best;
    }

    private static boolean claimed(Place p, int table) {
        for (Diner d : DINERS.values()) if (d.place.equals(p.building) && d.table == table) return true;
        return false;
    }

    // ------------------------------------------------------------------ at the table (the dining pass, every half second)

    /** The diners seen to: the order taken, the bites eaten, the bill paid, up and away. */
    static void step(ServerLevel level, long now) {
        for (Iterator<Map.Entry<UUID, Diner>> it = DINERS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Diner> e = it.next();
            Diner d = e.getValue();
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity f) || !f.isAlive()) {
                abandon(level, d);
                it.remove();
                continue;
            }
            if (f.ownerId() == null) { it.remove(); continue; }
            Place p = DiningPlaces.place(level, f.ownerId(), d.place);
            Table tb = p == null ? null : p.table(d.table);
            if (tb == null || f.isSleeping() || Raids.underAlarm(f.ownerId())) {
                abandon(level, d);
                it.remove();
                Seats.stand(f, null);
                continue;
            }
            if (d.stage == Stage.GOING) {
                if (now - d.since > Dining.STUCK * 3) { abandon(level, d); it.remove(); Seats.stand(f, null); }
                continue;
            }
            if (d.stage == Stage.SEATED) seated(level, f, d, p, tb, now);
            else if (d.stage == Stage.ORDERED) atTable(level, f, d, p, tb, now);
            if (d.stage == Stage.PAYING) {
                pay(level, f, d, p);
                it.remove();
                leave(level, f, d, p.words());
            }
        }
    }

    /** Sat down: a waiter is sent over to take the order (Dining's pass walks it there: take); with nobody to come, it
     *  orders at the counter. */
    private static void seated(ServerLevel level, VillageFolkEntity f, Diner d, Place p, Table tb, long now) {
        boolean coming = false;
        for (Dining.Task t : Dining.TASKS.values()) {
            if (t.job == Dining.Job.TAKE && t.place.equals(p.building) && t.table == d.table) { coming = true; break; }
        }
        if (!coming && d.waiterAsked < 0) {
            VillageFolkEntity w = Hospitality.duty(level, p.village, Hospitality.Duty.SERVE, tb.cell(), x -> x.isAlive() && !x.isSleeping()
                && !Dining.busy(x) && !DINERS.containsKey(x.getUUID()));
            d.waiterAsked = now;
            if (w != null) {
                Dining.Task t = new Dining.Task(Dining.Job.TAKE, p.building, -1, -1, d.table, now);
                t.target = Dining.standBy(level, tb);
                Dining.TASKS.put(w.getUUID(), t);
                Dining.DOING.put(w.getUUID(), "Taking an order at table " + d.table);
                return;
            }
        }
        if (!coming && now - d.waiterAsked > ASKS_AT_COUNTER) order(level, f, d, p, null);
    }

    /**
     * The waiter at a table (Dining's pass, its TAKE task): there, it takes a seated folk's order (a word each side), or
     * says hello to a player sat down with the menu. True when that is done.
     */
    static boolean take(ServerLevel level, VillageFolkEntity w, Dining.Task t, long now) {
        if (!Dining.walk(w, t.target, t, now)) return false;
        for (Map.Entry<UUID, Diner> e : DINERS.entrySet()) {
            Diner d = e.getValue();
            if (!d.place.equals(t.place) || d.table != t.table || d.stage != Stage.SEATED) continue;
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity f) || f.ownerId() == null) return true;
            Place p = DiningPlaces.place(level, f.ownerId(), d.place);
            if (p == null) return true;
            w.getLookControl().setLookAt(f, 30.0F, 30.0F);
            FolkTalk.speak(w, FolkTalk.pick(w.getRandom(), "Good evening! What can I get you?", "Evening. Have you decided?",
                "Welcome to " + p.words() + "! Tonight's special is on the board."));
            order(level, f, d, p, w);
            return true;
        }
        return greetPlayer(level, w, t, now);
    }

    /**
     * What it orders: of the card, what it can afford as its purse runs to, its own town's dish or the special first, a
     * drink always if there is one, a starter or a pudding for the well off; said aloud. Nothing it can afford: it goes.
     */
    static void order(ServerLevel level, VillageFolkEntity f, Diner d, Place p, @Nullable VillageFolkEntity waiter) {
        List<DiningMenu.Line> card = DiningMenu.card(level, p, DiningMenu.meal(level.getDayTime()));
        double purse = f.purse();
        int tier = Wealth.tier(f).ordinal();
        List<String> want = new ArrayList<>();
        List<String> words = new ArrayList<>();
        double spent = 0;
        for (Course c : new Course[]{ Course.MAINS, Course.DRINKS, Course.STARTERS, Course.DESSERTS }) {
            if (c == Course.STARTERS && tier < 3 || c == Course.DESSERTS && tier < 2) continue;
            DiningMenu.Line pick = null;
            for (DiningMenu.Line l : card) {
                if (l.dish().course() != c || l.soldOut()) continue;
                double each = DiningMenu.each(level, p, l.dish(), f);
                if (spent + each > purse) continue;
                if (pick == null || l.special() || l.local() || roll(f, l.dish().id()) && !pick.special() && !pick.local()) pick = l;
                if (l.special() || l.local()) break;
            }
            if (pick == null) continue;
            spent += DiningMenu.each(level, p, pick.dish(), f);
            want.add(pick.dish().id());
            words.add(pick.dish().name().toLowerCase(java.util.Locale.ROOT));
        }
        Dining.Order o = want.isEmpty() ? null : Dining.place(level, p, d.table, f.getUUID(), f.displayNameCap(), false, Dining.Mode.TABLE, want, f);
        if (o == null) {
            FolkTalk.speak(f, "Hmm. Nothing I fancy tonight, at those prices.");
            d.stage = Stage.PAYING;
            return;
        }
        d.order = o.id;
        d.stage = Stage.ORDERED;
        d.since = level.getGameTime();
        FolkTalk.speak(f, (words.size() == 1 ? "The " + words.get(0) : "The " + String.join(", the ", words.subList(0, words.size() - 1))
            + " and the " + words.get(words.size() - 1)) + ", please" + (waiter == null ? "" : ", " + waiter.displayNameCap()) + ".");
        f.brain("ordered " + String.join(", ", words) + " at " + p.words());
    }

    private static boolean roll(VillageFolkEntity f, String dish) {
        return Math.floorMod(f.getUUID().hashCode() ^ dish.hashCode(), 3) == 0;
    }

    /** At the table with its order in: a sip or a bite now and then (the drink first), a word with the next table. */
    private static void atTable(ServerLevel level, VillageFolkEntity f, Diner d, Place p, Table tb, long now) {
        Dining.Order o = Dining.order(d.order);
        if (o == null) {
            d.stage = Stage.PAYING;
            return;
        }
        if (o.finished()) {
            d.stage = Stage.PAYING;
            return;
        }
        if (now - d.since > GIVES_UP && !anyServed(o)) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I've waited long enough. I'm off home.", "Still nothing? Never mind, I'll go."));
            for (Dining.Line l : o.lines) if (l.open() && l.step.ordinal() < Dining.Step.SERVED.ordinal()) l.step = Dining.Step.STRUCK;
            d.stage = Stage.PAYING;
            return;
        }
        f.getLookControl().setLookAt(tb.cell().getX() + 0.5, tb.cell().getY(), tb.cell().getZ() + 0.5);
        if (now - d.lastBite < (Dining.QUICK ? 20 : Dining.BITE_TICKS)) return;
        PlaceSettingBlock.Bite b = PlaceSettingBlock.sip(level, tb.cell());
        if (b == null) b = PlaceSettingBlock.bite(level, tb.cell());
        if (b == null) {
            if (f.getRandom().nextInt(6) == 0) chat(level, f);
            return;
        }
        d.lastBite = now;
        f.swing(InteractionHand.MAIN_HAND);
        eat(level, f, b);
        Dining.ate(new PlaceSettingBlock.Ate(level, tb.cell(), f, b));
    }

    private static boolean anyServed(Dining.Order o) {
        for (Dining.Line l : o.lines) if (l.step.ordinal() >= Dining.Step.SERVED.ordinal() && l.step != Dining.Step.STRUCK) return true;
        return false;
    }

    /** A bite (or a sip) eaten by a folk: its health a little the better, its meal booked, the dish's good on the last. */
    static void eat(ServerLevel level, VillageFolkEntity f, PlaceSettingBlock.Bite b) {
        if (b.nutrition() > 0) f.heal(b.nutrition() * 0.5F);
        f.meals().ate(level.getGameTime(), b.of().getHoverName().getString().toLowerCase(java.util.Locale.ROOT));
        if (b.last()) {
            for (MobEffectInstance e : PlaceSettingBlock.effectsOf(b.of())) f.addEffect(new MobEffectInstance(e));
            if (!b.drink()) Cuisine.ate(f, b.of());
            if (f.getRandom().nextInt(2) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Delicious.", "That was good.",
                "My compliments to the cook!", "Just what I wanted."));
        }
    }

    /** A dish with no place to set it (no clean plates at all): eaten as it comes, in the hand. */
    static void servedInHand(ServerLevel level, Dining.Order o, Dining.Line l) {
        if (!(level.getEntity(o.diner) instanceof VillageFolkEntity f) || l.got.isEmpty()) return;
        net.minecraft.world.food.FoodProperties fp = PlaceSettingBlock.foodOf(l.got);
        eat(level, f, new PlaceSettingBlock.Bite(fp == null ? 0 : fp.nutrition(), fp == null ? 0 : fp.saturation(), true, l.drink(), l.got.copy()));
        l.got = ItemStack.EMPTY;
        l.step = Dining.Step.EATEN;
    }

    /** A word with whoever is at the next table (Smalltalk). */
    private static void chat(ServerLevel level, VillageFolkEntity f) {
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(3.5), x -> x != f && DINERS.containsKey(x.getUUID()))) {
            if (f.tickCount - f.lastSmalltalk > 600 && Smalltalk.chat(f, o, level)) {
                f.lastSmalltalk = f.tickCount;
                o.lastSmalltalk = o.tickCount;
            }
            return;
        }
    }

    /**
     * The bill paid: what it ate, to the hundredth, out of its purse into the till (on the slate if short: Purchases), the
     * takings booked to the house; a tip for the waiter if it is pleased and can spare it.
     */
    static void pay(ServerLevel level, VillageFolkEntity f, Diner d, Place p) {
        Dining.Order o = Dining.order(d.order);
        if (o == null) return;
        double bill = 0;
        for (Dining.Line l : o.lines) if (l.step == Dining.Step.EATEN || l.step == Dining.Step.SERVED) bill += l.each;
        if (bill > 0) {
            int paid = Purchases.charge(level, f, p.village, bill, true, ItemStack.EMPTY, 0);
            o.paidCoins += Math.max(0, paid);
            Dining.TAKINGS.merge(p.village, Math.max(0, paid), Integer::sum);
            Stockroom.Seller seller = p.kind == DiningPlaces.Kind.TAVERN ? Stockroom.Seller.TAVERN : Stockroom.Seller.CAFE;
            for (Dining.Line l : o.lines) {
                DiningMenu.Dish dish = l.dishDef();
                if (dish != null && (l.step == Dining.Step.EATEN || l.step == Dining.Step.SERVED)) {
                    Stockroom.sold(level, p.village, seller, dish.one(), 1, (int) Math.round(l.each));
                    PriceIndex.bought(p.village, dish.one(), 1);
                }
            }
            int tip = f.persona().mood() >= 55 && f.purse() >= 2 ? 1 + (Wealth.tier(f).ordinal() >= 3 ? 1 : 0) : 0;
            if (tip > 0) Dining.tipTheWaiter(level, o, tip, () -> f.spend(tip));
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Thank you, that was lovely.", "Here's for the bill. Keep the change!",
                "Lovely evening. Goodnight!"));
        }
        // Whatever was not eaten stays on the table for the waiter to clear.
        for (Dining.Line l : o.lines) if (l.open() && l.step.ordinal() < Dining.Step.SERVED.ordinal()) l.step = Dining.Step.STRUCK;
        o.paid = true;
        o.done = true;
        Dining.save(level);
    }

    /** Up from the table and off: the day's dinner out remembered (its spirits, its supper had). */
    private static void leave(ServerLevel level, VillageFolkEntity f, Diner d, String where) {
        DINERS.remove(f.getUUID());
        Seats.stand(f, null);
        if (!where.isEmpty()) {
            long day = level.getDayTime() / 24000L;
            DINED.put(f.getUUID(), day);
            LAST_PLACE.put(f.getUUID(), where);
            Meals.ateOut(f, level);
            f.refreshMood();
            f.persona().remember(day, "dinner out at " + where, 2);
        }
    }

    /** A diner gone (its folk gone, asleep, called to the bell): its order given up, what was made back to the stores. */
    private static void abandon(ServerLevel level, Diner d) {
        Dining.Order o = Dining.order(d.order);
        if (o == null) return;
        Villages.Village v = Villages.get(o.village);
        for (Dining.Line l : o.lines) {
            if (!l.open()) continue;
            if (!l.got.isEmpty() && v != null) Crafts.store(level, v, l.got.copy());
            l.got = ItemStack.EMPTY;
            if (l.step.ordinal() < Dining.Step.SERVED.ordinal()) l.step = Dining.Step.STRUCK;
        }
        o.paid = true;
        o.done = true;
    }

    // ------------------------------------------------------------------ a player sits down

    /**
     * A player's right-click in a place to eat out: a chair at a table sits them down (the order screen for the table, and
     * the waiter comes over), the table's cloth or post opens the screen standing, the menu board by the door the screen to
     * take away or have it brought. Crouch to use the block as it is.
     */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (e.getHand() != InteractionHand.MAIN_HAND || p.isShiftKeyDown()) return;
        BlockPos pos = e.getPos();
        BlockState s = level.getBlockState(pos);
        boolean chair = s.getBlock() instanceof StairBlock, cloth = PlaceSettingBlock.isCloth(s) || s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock;
        boolean board = s.getBlock() instanceof WallSignBlock;
        if (!chair && !cloth && !board) return;
        if ((cloth || chair) && !p.getMainHandItem().isEmpty() && !Market.isCoin(p.getMainHandItem())) return;   // a plate to lay, a block to place
        Place place = DiningPlaces.at(level, pos);
        if (place == null) return;
        if (board) {
            if (!pos.equals(place.board)) return;
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            ServicePoints.openDining(p, place, null);
            return;
        }
        Table t = place.table(pos);
        if (t == null) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        sitDown(level, p, place, t, chair);
    }

    /** A player at a table: sat in its chair (if it asked to be, and it is free), the screen for it opened, the waiter
     *  sent over to greet them. */
    static void sitDown(ServerLevel level, ServerPlayer p, Place place, Table t, boolean sit) {
        Dining.Order mine = Dining.atTable(place.building, t.number());
        if (mine != null && !mine.diner.equals(p.getUUID())) {
            p.displayClientMessage(Component.literal("Table " + t.number() + " is " + mine.dinerName + "'s."), true);
            return;
        }
        if (mine == null && claimed(place, t.number())) {
            p.displayClientMessage(Component.literal("Somebody's on their way to table " + t.number() + "."), true);
            return;
        }
        if (sit && !p.isPassenger() && !Seats.taken(level, t.seat(), null)) DiningSeat.sit(level, p, t.seat());
        ServicePoints.openDining(p, place, t);
        if (mine == null) greet(level, place, t);
    }

    /** The waiter comes over to a player who has sat down, and says hello. */
    private static void greet(ServerLevel level, Place place, Table t) {
        VillageFolkEntity w = Hospitality.duty(level, place.village, Hospitality.Duty.SERVE, t.cell(), x -> x.isAlive() && !x.isSleeping()
            && !Dining.busy(x) && !DINERS.containsKey(x.getUUID()));
        if (w == null) return;
        Dining.Task task = new Dining.Task(Dining.Job.TAKE, place.building, -1, -1, t.number(), level.getGameTime());
        task.target = Dining.standBy(level, t);
        Dining.TASKS.put(w.getUUID(), task);
        Dining.DOING.put(w.getUUID(), "Bringing the menu to table " + t.number());
    }

    /** The waiter's greeting to a player (Dining's pass, a TAKE task at a table with no folk diner): once there, a word. */
    static boolean greetPlayer(ServerLevel level, VillageFolkEntity w, Dining.Task t, long now) {
        if (!Dining.walk(w, t.target, t, now)) return false;
        net.minecraft.world.entity.player.Player near = level.getNearestPlayer(w, 6.0);
        if (near != null) w.getLookControl().setLookAt(near, 30.0F, 30.0F);
        FolkTalk.speak(w, FolkTalk.pick(w.getRandom(), "Good evening! The menu's on the table. Call me when you're ready.",
            "Welcome! Take your time with the menu.", "Hello there. Something to drink while you choose?"));
        return true;
    }

    /**
     * A player right-clicked one of the floor at work in an open place (VillageFolkEntity.mobInteract): the menu, for the
     * player's table if they are at one, else to order to take away. False (the folk's card as usual) otherwise.
     */
    public static boolean staffClicked(VillageFolkEntity f, ServerPlayer p) {
        Hospitality.Role r = Hospitality.role(f);
        if (r != Hospitality.Role.WAITER && r != Hospitality.Role.HOST && r != Hospitality.Role.BARKEEP || f.ownerId() == null) return false;
        if (!(f.level() instanceof ServerLevel level)) return false;
        Place place = DiningPlaces.at(level, f.blockPosition());
        if (place == null || place != DiningPlaces.at(level, p.blockPosition())) return false;
        Table mine = null;
        double best = 4.0 * 4.0;
        for (Table t : place.tables) {
            double d = t.seat().distToCenterSqr(p.position());
            if (d < best) { best = d; mine = t; }
        }
        f.getLookControl().setLookAt(p, 30.0F, 30.0F);
        FolkTalk.speak(f, "Here's the menu" + (mine != null ? " for table " + mine.number() : "") + ". What can I get you?");
        ServicePoints.openDining(p, place, mine);
        return true;
    }

    /** Tests: this folk out to dinner at this place now (whatever the roll). True if it found a table. */
    public static boolean dineForTests(ServerLevel level, VillageFolkEntity f, Place p) {
        return start(level, f, p) != null;
    }

    /** Tests: the stage this folk's dinner is at, or null. */
    @Nullable
    public static String stageForTests(VillageFolkEntity f) {
        Diner d = DINERS.get(f.getUUID());
        return d == null ? null : d.stage.name();
    }

    /** Tests: its order's id (-1 none). */
    public static int orderForTests(VillageFolkEntity f) {
        Diner d = DINERS.get(f.getUUID());
        return d == null ? -1 : d.order;
    }

    /** Tests: the table it has (0 none). */
    public static int tableForTests(VillageFolkEntity f) {
        Diner d = DINERS.get(f.getUUID());
        return d == null ? 0 : d.table;
    }
}
