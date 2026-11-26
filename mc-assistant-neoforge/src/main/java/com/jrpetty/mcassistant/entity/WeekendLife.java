package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] A folk's day off: the weekend, or the hours its employer keeps it off (WorkHours). From the morning to the
 * late afternoon (the evening belongs to the evening's own routines) it plans an outing for the morning and another for
 * the afternoon, by what there is in the town and what it is like (LifeWeights), and goes:
 * <ul>
 * <li><b>the market</b>: round the stalls (its neighbours' blanket stalls: Stalls; the town's market and the merchant on
 *     market day), buying something for itself out of its own purse if it sees something it likes at a price it thinks fair;</li>
 * <li><b>the shop</b>: something for itself by what it needs and what it is like: a cake or a pie, a new cap, a toy for
 *     its child, paid out of its own purse at the town's price;</li>
 * <li><b>the tavern</b>: a drink at the bar and the company there;</li>
 * <li><b>a walk</b>: round the park's paths, or the square and the town's edge with no park;</li>
 * <li><b>a friend</b>: round to see one (Friends), a chat on the step;</li>
 * <li><b>home</b>: its feet up by its own door, a book for the bookish;</li>
 * <li><b>its stall</b>: on a Saturday morning, whoever has something spare (a gardener's cut flowers, an angler's catch,
 *     a whittler's toy, food it has more of than it eats) sets up in the square and sells it (Stalls).</li>
 * </ul>
 * Each outing goes on its card with the reason ("Spent the afternoon at the tavern (easygoing)": LifeLog) and is counted
 * in its week. Nobody is sent: a folk laid up, a child or one with its own errand under way keeps to that.
 */
public final class WeekendLife {

    private WeekendLife() {}

    /** The window: from the morning to the late afternoon. */
    static final long FROM = 1000L, TO = 11500L, NOON = 6000L;

    public enum Outing {
        MARKET("at the market", "Spent the %s at the market"),
        SHOP("off to the shop", "Went shopping in the %s"),
        TAVERN("at the tavern", "Spent the %s at the tavern"),
        PARK("out for a walk", "Walked round the town in the %s"),
        FRIEND("round at a friend's", "Called on %2$s in the %1$s"),
        HOME("at home with its feet up", "Stayed home in the %s"),
        SELL("at its stall in the square", "Sold what it had spare from a stall in the %s"),
        FAIR("at the fair", "Spent the %s at the fair");

        public final String doing, line;

        Outing(String doing, String line) {
            this.doing = doing;
            this.line = line;
        }
    }

    /** A folk's outing for a part of the day. */
    static final class Plan {
        final Outing kind;
        final long day;
        final int slot;
        final String why;
        @Nullable BlockPos at;
        @Nullable UUID friend;
        String friendName = "";
        boolean arrived, logged, bought;
        int walked = -1000, beat;

        Plan(Outing kind, long day, int slot, String why) {
            this.kind = kind;
            this.day = day;
            this.slot = slot;
            this.why = why;
        }
    }

    private static final Map<UUID, Plan> PLANS = new ConcurrentHashMap<>();
    /** Tests: the outing to take instead of a planned one. */
    private static final Map<UUID, Outing> FORCED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        PLANS.clear();
        FORCED.clear();
    }

    public static void forceForTests(VillageFolkEntity f, @Nullable Outing o) {
        if (o == null) FORCED.remove(f.getUUID());
        else FORCED.put(f.getUUID(), o);
        PLANS.remove(f.getUUID());
    }

    /** Its outing now, if it is on one (its card, the week page). */
    @Nullable
    public static Outing now(VillageFolkEntity f) {
        Plan p = PLANS.get(f.getUUID());
        return p == null || p.day != f.level().getDayTime() / 24000L ? null : p.kind;
    }

    // ------------------------------------------------------------------ when

    /** Is this folk's time its own just now by its employer's hours, in the hours of an outing? */
    public static boolean free(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.isSleeping() || Health.laidUp(f) || Visitors.is(f)) return false;
        long dt = f.level().getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (t < FROM || t >= TO || !WorkHours.kept(village, day)) return false;
        if (RestDay.now(village, dt) != null) return t >= RestDay.WALKING_FROM;       // Sunday afternoon, after the games
        if (WorkHours.dayOff(f)) return true;
        if (f.stationTask() == AssistantEntity.StationTask.GUARD || f.isHired()) return false;
        return WorkHours.hour(dt) >= WorkHours.of(village, f).close();               // its employer shut early
    }

    /**
     * Its day off, a beat of it (VillageFolkEntity.bedtime, while it is off work). True while it is on an outing.
     */
    public static boolean spend(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || !free(f)) return false;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null || f.villageCentre() == null) return false;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        int slot = t < NOON ? 0 : 1;
        Plan p = PLANS.get(f.getUUID());
        if (p == null || p.day != day || p.slot != slot) {
            p = plan(level, v, f, day, slot);
            if (p == null) return false;
            PLANS.put(f.getUUID(), p);
        }
        return run(level, v, f, p, day);
    }

    // ------------------------------------------------------------------ choosing

    /** What appeals to it of what there is, the mark that tipped it, by outing (the tests read it too). */
    public static Map<Outing, Double> appeal(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day, int slot, Map<Outing, String> why) {
        Map<Outing, Double> w = new EnumMap<>(Outing.class);
        EnumSet<LifeWeights.Mark> m = LifeWeights.marks(f);
        boolean sunday = Calendar.isRestDay(day);
        boolean fair = Fairground.on(v.id(), day);
        boolean market = Calendar.isMarketDay(day) || Stalls.any(v.id());
        if (fair) {
            w.put(Outing.FAIR, 8.0 + (m.contains(LifeWeights.Mark.CHATTY) ? 2 : 0) + (m.contains(LifeWeights.Mark.SHY) ? -3 : 0));
            why.put(Outing.FAIR, LifeWeights.why(f, LifeWeights.Mark.CHATTY, LifeWeights.Mark.CHEERFUL, LifeWeights.Mark.CURIOUS));
        } else if (market && !sunday) {
            double x = LifeWeights.W_MARKET;
            x += add(m, why, Outing.MARKET, LifeWeights.Mark.CURIOUS, 2) + add(m, why, Outing.MARKET, LifeWeights.Mark.SPENDTHRIFT, 1.5)
                + add(m, why, Outing.MARKET, LifeWeights.Mark.GENEROUS, 0.5);
            w.put(Outing.MARKET, x);
        }
        if (slot == 0 && Calendar.isMarketDay(day) && !Stalls.surplus(level, f).isEmpty()) {
            w.put(Outing.SELL, 5.0 + add(m, why, Outing.SELL, LifeWeights.Mark.AMBITIOUS, 2) + add(m, why, Outing.SELL, LifeWeights.Mark.THRIFTY, 1));
            why.putIfAbsent(Outing.SELL, "something spare to sell");
        }
        if (ShopStock.open(v.id()) && WorkHours.openNow(level, v.id(), Business.Type.SHOP) && LifeWeights.budget(f) > 0) {
            double x = LifeWeights.W_SHOP + add(m, why, Outing.SHOP, LifeWeights.Mark.SPENDTHRIFT, 2.5) + add(m, why, Outing.SHOP, LifeWeights.Mark.THRIFTY, -1.5);
            w.put(Outing.SHOP, Math.max(0.2, x));
        }
        if (Tavern.of(v.id()) != null && !LawBook.dry(v.id())) {
            double x = LifeWeights.W_TAVERN + add(m, why, Outing.TAVERN, LifeWeights.Mark.EASYGOING, 2.5) + add(m, why, Outing.TAVERN, LifeWeights.Mark.CHATTY, 1.5)
                + add(m, why, Outing.TAVERN, LifeWeights.Mark.CHEERFUL, 1) + add(m, why, Outing.TAVERN, LifeWeights.Mark.SPENDTHRIFT, 1)
                + add(m, why, Outing.TAVERN, LifeWeights.Mark.DEVOUT, -1.5) + add(m, why, Outing.TAVERN, LifeWeights.Mark.THRIFTY, -1);
            w.put(Outing.TAVERN, Math.max(0.2, x));
        }
        w.put(Outing.PARK, LifeWeights.W_PARK + add(m, why, Outing.PARK, LifeWeights.Mark.WANDERER, 3) + add(m, why, Outing.PARK, LifeWeights.Mark.SHY, 1)
            + add(m, why, Outing.PARK, LifeWeights.Mark.CURIOUS, 0.5) + (Park.strollSpot(f) != null ? 1 : 0));
        if (Friends.toCallOn(f) != null) {
            double x = LifeWeights.W_FRIEND * LifeWeights.callChance(f) * 4;
            why.put(Outing.FRIEND, LifeWeights.why(f, LifeWeights.Mark.CHATTY, LifeWeights.Mark.CHEERFUL, LifeWeights.Mark.GENEROUS));
            w.put(Outing.FRIEND, x);
        }
        w.put(Outing.HOME, LifeWeights.W_HOME + add(m, why, Outing.HOME, LifeWeights.Mark.HOMEBODY, 3) + add(m, why, Outing.HOME, LifeWeights.Mark.BOOKISH, 1.5)
            + add(m, why, Outing.HOME, LifeWeights.Mark.SHY, 1.5) + add(m, why, Outing.HOME, LifeWeights.Mark.EASYGOING, 1)
            + (level.isRaining() ? 4 : 0));
        if (level.isRaining()) why.put(Outing.HOME, "the rain");
        return w;
    }

    /** A mark's weight if it has it, noting it as the reason if it is the biggest pull so far. */
    private static double add(EnumSet<LifeWeights.Mark> m, Map<Outing, String> why, Outing o, LifeWeights.Mark k, double x) {
        if (!m.contains(k)) return 0;
        if (x > 0 && !why.containsKey(o)) why.put(o, k.word);
        return x;
    }

    @Nullable
    static Plan plan(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day, int slot) {
        Map<Outing, String> why = new EnumMap<>(Outing.class);
        Map<Outing, Double> w = appeal(level, v, f, day, slot, why);
        Outing forced = FORCED.get(f.getUUID());
        Outing pick = forced;
        if (pick == null) {
            double total = 0;
            for (double x : w.values()) total += Math.max(0, x);
            if (total <= 0) return null;
            double r = LifeWeights.roll(f, day, 101 + slot) * total;
            for (Map.Entry<Outing, Double> e : w.entrySet()) {
                r -= Math.max(0, e.getValue());
                if (r <= 0) { pick = e.getKey(); break; }
            }
            if (pick == null) pick = Outing.PARK;
        }
        Plan p = new Plan(pick, day, slot, why.getOrDefault(pick, ""));
        if (pick == Outing.FRIEND) {
            VillageFolkEntity friend = Friends.toCallOn(f);
            if (friend == null) return new Plan(Outing.PARK, day, slot, "");
            p.friend = friend.getUUID();
            p.friendName = friend.displayNameCap();
        }
        p.at = where(level, v, f, p);
        f.brain("planned the " + part(slot) + ": " + pick.doing + (p.why.isEmpty() ? "" : " (" + p.why + ")"));
        return p;
    }

    static String part(int slot) {
        return slot == 0 ? "morning" : "afternoon";
    }

    @Nullable
    static BlockPos where(ServerLevel level, Villages.Village v, VillageFolkEntity f, Plan p) {
        BlockPos c = v.centre();
        RandomSource r = f.getRandom();
        return switch (p.kind) {
            case MARKET, FAIR -> {
                BlockPos stall = Stalls.frontOfOne(v.id(), r);
                if (stall != null) yield stall;
                Ledger.Building m = Visitors.building(v.id(), "market");
                yield m != null ? m.anchor() : c.offset(r.nextInt(11) - 5, 0, r.nextInt(11) - 5);
            }
            case SHOP -> {
                ServicePoints.Point pt = ServicePoints.nearest(level, v.id(), ServiceRole.SHOP, f.blockPosition());
                BlockPos at = pt != null ? pt.queueSpot(level, 0) : Villages.builtAt(v.id(), Store.buildingForShop(v.id()));
                yield at != null ? at : c;
            }
            case TAVERN -> {
                Ledger.Building b = Tavern.of(v.id());
                yield b != null ? b.anchor() : c;
            }
            case PARK -> {
                BlockPos s = Park.strollSpot(f);
                yield s != null ? s : c.offset(r.nextInt(31) - 15, 0, r.nextInt(31) - 15);
            }
            case HOME -> {
                BlockPos h = Homes.homeOf(f);
                yield h != null ? h : f.bedPos() != null ? f.bedPos() : c;
            }
            case SELL -> null;                                                // the stall's own spot (Stalls)
            case FRIEND -> null;                                              // wherever the friend is
        };
    }

    // ------------------------------------------------------------------ going

    static boolean run(ServerLevel level, Villages.Village v, VillageFolkEntity f, Plan p, long day) {
        f.lastLeisureTick = f.tickCount;
        p.beat++;
        switch (p.kind) {
            case SELL -> {
                if (!Stalls.keep(level, v, f, day)) {                         // sold out, or no spot: a walk round instead
                    PLANS.put(f.getUUID(), new Plan(Outing.MARKET, day, p.slot, p.why));
                    return true;
                }
                logOnce(f, p, day, String.format(Outing.SELL.line, part(p.slot)), p.why);
                return true;
            }
            case FRIEND -> {
                return friend(level, v, f, p, day);
            }
            default -> { }
        }
        if (p.at == null) return false;
        if (!p.arrived) {
            BlockPos to = p.at;
            if (f.blockPosition().distSqr(to) > (p.kind == Outing.HOME ? 9 : 16)) {
                if (f.getNavigation().isDone() || f.tickCount - p.walked > 100) {
                    f.walkTo(to, 0.8D);
                    p.walked = f.tickCount;
                }
                f.hobbyNow = p.kind.doing;
                return true;
            }
            p.arrived = true;
        }
        f.hobbyNow = p.kind.doing;
        switch (p.kind) {
            case MARKET, FAIR -> market(level, v, f, p, day);
            case SHOP -> shop(level, v, f, p, day);
            case TAVERN -> tavern(level, v, f, p, day);
            case PARK -> park(level, v, f, p, day);
            case HOME -> home(level, f, p, day);
            default -> { }
        }
        return true;
    }

    private static void logOnce(VillageFolkEntity f, Plan p, long day, String line, String why) {
        if (p.logged) return;
        p.logged = true;
        LifeLog.note(f, day, line, why);
    }

    /** Round the stalls: a look at each, something bought if it likes it and can pay a fair price. */
    static void market(ServerLevel level, Villages.Village v, VillageFolkEntity f, Plan p, long day) {
        LifeLog.Stat counted = LifeLog.Stat.MARKET;
        if (!p.bought) {
            p.bought = true;
            LifeLog.count(f, counted, 1);
            String got = Stalls.buy(level, v, f, day);
            if (got == null && p.kind == Outing.FAIR) got = Fairground.buy(level, v, f, day);
            if (got == null && Calendar.isMarketDay(day) && LifeWeights.budget(f) > 0) got = Market.folkBuys(level, v, f);
            String line = String.format(p.kind.line, part(p.slot));
            if (got != null) {
                LifeLog.note(f, day, line + " and came home with " + got, LifeWeights.moneyWhy(f).isEmpty() ? p.why : LifeWeights.moneyWhy(f));
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "That'll do nicely.", "I wasn't going to, and then I did.",
                    "Fair price, that.", "Don't tell anyone what I paid.", "One for me, for once.", "Well, it's the weekend."));
            } else {
                String why = LifeWeights.has(f, LifeWeights.Mark.THRIFTY) ? "thrifty" : LifeWeights.budget(f) <= 0 ? "short of coin" : p.why;
                LifeLog.note(f, day, line + ", looking more than buying", why);
            }
            p.logged = true;
        }
        // Drift between the stalls, a look at each.
        if (f.getNavigation().isDone() && f.getRandom().nextInt(3) == 0) {
            BlockPos next = Stalls.frontOfOne(v.id(), f.getRandom());
            if (next == null) next = v.centre().offset(f.getRandom().nextInt(13) - 6, 0, f.getRandom().nextInt(13) - 6);
            f.walkTo(next, 0.6D);
        }
        if (f.getRandom().nextInt(12) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "How much for that?", "They had these cheaper last week.", "I'm only looking.",
                "Is that today's?", "Busy, isn't it.", "I'll come back for that."));
        }
    }

    /** At the shop: one thing for itself, by what it needs and what it is like. */
    static void shop(ServerLevel level, Villages.Village v, VillageFolkEntity f, Plan p, long day) {
        if (p.bought) {
            BlockPos h = Homes.homeOf(f);
            if (h != null && f.getNavigation().isDone()) f.walkTo(h, 0.8D);
            return;
        }
        p.bought = true;
        int budget = LifeWeights.budget(f);
        String why = LifeWeights.moneyWhy(f);
        String got = null;
        for (Want w : wants(level, f)) {
            if (buy(level, v, f, w.item(), w.need(), budget, w.forChild())) {
                got = w.words();
                break;
            }
        }
        String line = String.format(Outing.SHOP.line, part(p.slot));
        if (got != null) {
            LifeLog.count(f, LifeLog.Stat.BOUGHT, 1);
            LifeLog.note(f, day, line + ": " + got, why.isEmpty() ? p.why : why);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Just the thing.", "Treat yourself, they say.", "I've earned it.",
                "That's the week's wages gone, near enough.", "It was on the list.", "Can't take it with you."));
        } else {
            LifeLog.note(f, day, line + " and bought nothing", why.isEmpty() ? "nothing at its price" : why);
        }
    }

    /** What it would buy for itself, in order: a toy for its child, new clothes, a treat. */
    record Want(Item item, Purchases.Need need, String words, @Nullable VillageFolkEntity forChild) {}

    static List<Want> wants(ServerLevel level, VillageFolkEntity f) {
        List<Want> out = new ArrayList<>();
        EnumSet<LifeWeights.Mark> m = LifeWeights.marks(f);
        VillageFolkEntity child = childOf(level, f);
        if (child != null) out.add(new Want(McAssistantMod.WOODEN_TOY.get(), Purchases.Need.TREAT, "a wooden toy for " + child.displayNameCap(), child));
        if (m.contains(LifeWeights.Mark.SPENDTHRIFT) || m.contains(LifeWeights.Mark.CHEERFUL) || f.getRandom().nextInt(3) == 0) {
            out.add(new Want(Items.LEATHER_HELMET, Purchases.Need.CLOTHES, "a new cap", null));
        }
        out.add(new Want(Items.CAKE, Purchases.Need.TREAT, "a cake", null));
        out.add(new Want(Items.PUMPKIN_PIE, Purchases.Need.TREAT, "a pie", null));
        out.add(new Want(Items.COOKIE, Purchases.Need.TREAT, "a bag of cookies", null));
        if (child != null) out.add(new Want(Items.COOKIE, Purchases.Need.TREAT, "cookies for " + child.displayNameCap(), child));
        out.add(new Want(Items.BREAD, Purchases.Need.FOOD, "a fresh loaf", null));
        return out;
    }

    @Nullable
    static VillageFolkEntity childOf(ServerLevel level, VillageFolkEntity f) {
        for (AssistantEntity a : Villages.folkOf(f.ownerId())) {
            if (a instanceof VillageFolkEntity c && c.isAlive() && c.isBaby() && c.parentIds().contains(f.getUUID())) return c;
        }
        return null;
    }

    /** Bought for itself at the town's counter, at the town's price, out of its own purse. */
    static boolean buy(ServerLevel level, Villages.Village v, VillageFolkEntity f, Item it, Purchases.Need need, int budget,
                       @Nullable VillageFolkEntity forChild) {
        ItemStack one = new ItemStack(it);
        if (Market.stock(level, v.id(), s -> s.is(it)) <= 0) return false;
        double each = Purchases.priceEach(level, v.id(), one, f);
        if (each > budget) return false;
        if (Purchases.decide(level, f, one, each, need, 1) <= 0 || !Purchases.canPay(f, each)) return false;
        if (!TownWork.take(level, v, s -> s.is(it), 1)) return false;
        int paid = Purchases.charge(level, f, v.id(), each, false, one, 1);
        Businesses.buyer(f.displayNameCap(), false);
        Stockroom.sold(level, v.id(), Stockroom.Seller.SHOP, one, 1, Math.max(0, paid));
        Businesses.buyer(null, false);
        PriceIndex.bought(v.id(), one, 1);
        LifeLog.count(f, LifeLog.Stat.SPENT, Math.max(1, paid));
        ItemStack bought = one.copy();
        ItemStack left = forChild != null && forChild.isAlive() ? forChild.insertGiven(bought) : f.insertItem(bought);
        if (!left.isEmpty()) left = f.insertItem(left);
        if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, f.blockPosition(), left);
        if (forChild != null) {
            forChild.life().feel(f.getUUID(), f.displayNameCap(), 4);
            forChild.persona().remember(level.getDayTime() / 24000L, f.displayNameCap() + " brought me " + it.getDescription().getString().toLowerCase(), 4);
        }
        return true;
    }

    /** At the tavern: a drink (once a day: Tavern.drink), and the company. */
    static void tavern(ServerLevel level, Villages.Village v, VillageFolkEntity f, Plan p, long day) {
        if (!p.bought) {
            p.bought = true;
            int before = f.purse();
            Tavern.drink(f, level, v, day);
            if (f.purse() < before) LifeLog.count(f, LifeLog.Stat.SPENT, before - f.purse());
            LifeLog.count(f, LifeLog.Stat.TAVERN, 1);
            logOnce(f, p, day, String.format(Outing.TAVERN.line, part(p.slot)), p.why);
        }
        if (p.beat % 6 == 0) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity o && o != f && !o.isBaby() && o.distanceToSqr(f) < 25 && now(o) == Outing.TAVERN) {
                    Friends.chatted(f, o, 1);
                    f.getLookControl().setLookAt(o, 30.0F, 30.0F);
                    if (f.getRandom().nextInt(4) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Another? Go on, then.", "Same again.", "Who's paying?",
                            "Not much of a head on that.", "To the weekend.", "Don't start on the harvest again."));
                    }
                    break;
                }
            }
        }
    }

    /** A walk: from one spot of the park's paths to the next (or round the square). */
    static void park(ServerLevel level, Villages.Village v, VillageFolkEntity f, Plan p, long day) {
        logOnce(f, p, day, String.format(Outing.PARK.line, part(p.slot)), p.why);
        if (p.beat == 2) LifeLog.count(f, LifeLog.Stat.PARK, 1);
        if (f.getNavigation().isDone()) {
            BlockPos next = Park.strollSpot(f);
            if (next == null) next = v.centre().offset(f.getRandom().nextInt(41) - 20, 0, f.getRandom().nextInt(41) - 20);
            BlockPos ground = f.surfaceAt(next.getX(), next.getZ());
            f.walkTo(ground != null ? ground : next, 0.6D);
        }
    }

    /** At home: its feet up by its own door. */
    static void home(ServerLevel level, VillageFolkEntity f, Plan p, long day) {
        String why = p.why;
        String line = LifeWeights.has(f, LifeWeights.Mark.BOOKISH) ? "Stayed home with a book in the " + part(p.slot)
            : String.format(Outing.HOME.line, part(p.slot));
        logOnce(f, p, day, line, why);
        if (p.beat == 2) LifeLog.count(f, LifeLog.Stat.HOME, 1);
        f.getNavigation().stop();
    }

    /** Round to see a friend: found wherever it is, and a chat. */
    static boolean friend(ServerLevel level, Villages.Village v, VillageFolkEntity f, Plan p, long day) {
        VillageFolkEntity o = p.friend == null ? null : level.getEntity(p.friend) instanceof VillageFolkEntity x && x.isAlive() ? x : null;
        if (o == null || o.distanceToSqr(f) > 160 * 160) {
            PLANS.put(f.getUUID(), new Plan(Outing.PARK, day, p.slot, ""));
            return true;
        }
        f.hobbyNow = "round to see " + p.friendName;
        if (f.distanceToSqr(o) > 6.0) {
            if (f.getNavigation().isDone() || f.tickCount - p.walked > 60) {
                f.walkTo(o.blockPosition(), 0.8D);
                p.walked = f.tickCount;
            }
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(o, 30.0F, 30.0F);
        if (!p.logged) {
            p.logged = true;
            LifeLog.count(f, LifeLog.Stat.CALLS, 1);
            LifeLog.note(f, day, String.format(Outing.FRIEND.line, part(p.slot), p.friendName), p.why);
            LifeLog.note(o, day, f.displayNameCap() + " dropped round in the " + part(p.slot), null);
            Friends.dropRound(level, f, o, day);
        } else if (p.beat % 8 == 0) {
            Friends.chatted(f, o, 1);
            if (f.getRandom().nextInt(3) == 0) level.sendParticles(ParticleTypes.NOTE, f.getX(), f.getEyeY() + 0.5, f.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
        }
        return true;
    }
}
