package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.PlaceSettingBlock;
import com.jrpetty.mcassistant.block.PlaceSettingBlockEntity;
import com.jrpetty.mcassistant.entity.DiningMenu.Course;
import com.jrpetty.mcassistant.entity.DiningMenu.Dish;
import com.jrpetty.mcassistant.entity.DiningPlaces.Place;
import com.jrpetty.mcassistant.entity.DiningPlaces.Table;
import com.jrpetty.mcassistant.item.DiningItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [dining] Eating out, the whole way, the same for a player and a folk: an order taken, cooked in the kitchen from the
 * town's real stores by a named cook at the right station, carried on a tray by a waiter to the diner's table and set
 * down on a plate (drinks first), eaten bite by bite, paid for, the table cleared and the plates washed before they are
 * used again.
 * <ul>
 * <li><b>Orders</b> (Order, saved with the world): who for (a player or a folk), where (a table, the counter to take
 *     away, or a runner to bring it), the dishes and their prices, paid or not, the tip. The card prices them
 *     (DiningMenu); a player pays in coin, upfront or from the bill; a folk from its purse when it has eaten.</li>
 * <li><b>The kitchen</b> works through the tickets in order, drinks first: a free hand of the kitchen (Hospitality's
 *     COOK duty: the head cook, the line cooks, in a small place whoever there is) walks to the dish's station, takes
 *     its makings out of the stores and makes it by the game's own recipe (or plates what is ready: the café's bread,
 *     the cellar's cider, a slice off a cake), and is a few seconds at it, faster and finer the better the hand; smoke
 *     and the sizzle while it works. A green hand burns one now and then: thrown out, made again. A dish whose makings
 *     have run out is struck off: the diner is told and not charged.</li>
 * <li><b>The floor</b>: a waiter (SERVE duty) takes the plate off the pass, carries it (in hand, to be seen) to the table
 *     and sets it down; a dish to take away is handed over at the counter; a runner takes one to a player in town. When
 *     the diner has finished and paid, it clears the table: the plate and the cup to the wash.</li>
 * <li><b>The wash</b>: the pot washer (WASH duty) washes each plate and cup at the sink, and puts it back on the shelf
 *     (the place's chest, else the stores). Only a clean one is set on a table. The house keeps enough of them: short,
 *     its kitchen makes more of the stores' bricks (Bench, by the plate's and the cup's recipes).</li>
 * </ul>
 * <b>Cost.</b> One pass every half second over the orders and the hands at work, nothing when there are none: a few
 * map lookups and a walk order each. The places' layouts are read once and kept (DiningPlaces); the card once in ten
 * seconds (DiningMenu); the house's crockery once a quarter-minute (round).
 */
public final class Dining {

    private Dining() {}

    /** How often the orders and the hands at work are seen to (ticks). */
    static final int EVERY = 10;
    /** How long a hand is given to walk somewhere before it is put there (a doorway that will not let it by). */
    static long STUCK = 400L;
    /** Within reach of what it walks to (squared blocks). */
    static final double AT = 2.4 * 2.4;
    /** A plate or a cup washed (ticks); a bite or a sip at a folk's meal; the order taken at a table. */
    static final int WASH_TICKS = 50, BITE_TICKS = 80, TAKE_TICKS = 40;
    /** A green hand (level under this) burns one dish in so many. */
    static final int GREEN = 3, BURNS_ONE_IN = 10;
    /** The levels for a good dish and a master's. */
    static final int GOOD_FROM = 5, MASTER_FROM = 15;
    /** The experience a dish cooked, a plate carried, a table cleared and a plate washed are worth. */
    static final int XP_COOK = 6, XP_SERVE = 3, XP_CLEAR = 1, XP_WASH = 1;
    /** How many plates and cups a house keeps to each of its tables. */
    static final int PLATES_A_TABLE = 2, CUPS_A_TABLE = 2;
    /** How far a runner goes to a player in town; how far a player may go from a table bill unpaid. */
    static final int RUN_REACH = 160, BILL_REACH = 48;

    /** Tests: the hands put at their spot at once, and the work quick. */
    static boolean QUICK = false;

    public static void quickForTests(boolean on) {
        QUICK = on;
        STUCK = on ? 40L : 400L;
    }

    // ------------------------------------------------------------------ the orders

    /** Where a line of an order is: in the kitchen's queue, at the stove, on the pass, in a waiter's hand, on the table,
     *  eaten; or struck off (its makings ran out). */
    public enum Step { QUEUED, COOKING, PASS, CARRYING, SERVED, EATEN, STRUCK }

    /** How it is served: at a table, to take away from the counter, or brought by a runner. */
    public enum Mode { TABLE, TAKEAWAY, DELIVERY }

    /** One dish of an order. */
    public static final class Line {
        public final String dish;
        public Step step = Step.QUEUED;
        /** The dish as made (once it is off the stove). */
        public ItemStack got = ItemStack.EMPTY;
        /** Its price: a player's in whole coin, a folk's to the hundredth. */
        public final int price;
        public final double each;
        public int quality;
        public String cook = "";
        /** Where it was cooked (the station the cook stood at). */
        @Nullable public BlockPos at;

        Line(String dish, int price, double each) {
            this.dish = dish;
            this.price = price;
            this.each = each;
        }

        @Nullable public Dish dishDef() { return DiningMenu.byId(dish); }

        public boolean drink() {
            Dish d = dishDef();
            return d != null && d.course() == Course.DRINKS;
        }

        boolean open() { return step != Step.EATEN && step != Step.STRUCK; }
    }

    /** An order, saved with the world. */
    public static final class Order {
        public final int id;
        public final UUID village;
        public final Ledger.Building place;
        public final int table;
        public final UUID diner;
        public final String dinerName;
        public final boolean player;
        public final Mode mode;
        public final List<Line> lines = new ArrayList<>();
        public boolean paid;
        public int paidCoins, tip;
        public final long placed;
        /** The diner has left (a folk got up, a player walked off): what is left on the table is cleared. */
        public boolean done;

        Order(int id, UUID village, Ledger.Building place, int table, UUID diner, String dinerName, boolean player, Mode mode, long placed) {
            this.id = id;
            this.village = village;
            this.place = place;
            this.table = table;
            this.diner = diner;
            this.dinerName = dinerName;
            this.player = player;
            this.mode = mode;
            this.placed = placed;
        }

        /** The coin it comes to (a player's; the struck lines not charged). */
        public int total() {
            int n = 0;
            for (Line l : lines) if (l.step != Step.STRUCK) n += l.price;
            return n;
        }

        /** A folk's bill to the hundredth. */
        double totalEach() {
            double n = 0;
            for (Line l : lines) if (l.step != Step.STRUCK) n += l.each;
            return n;
        }

        /** Everything served and eaten (or struck off). */
        public boolean finished() {
            for (Line l : lines) if (l.open()) return false;
            return true;
        }

        /** Everything at least on the table (or handed over, or struck off). */
        public boolean allServed() {
            for (Line l : lines) if (l.step.ordinal() < Step.SERVED.ordinal()) return false;
            return true;
        }
    }

    static final Map<Integer, Order> ORDERS = new ConcurrentHashMap<>();
    private static int nextId = 1;

    /** A place's dirty plates and cups waiting at the wash, by its anchor: {plates, cups}. */
    static final Map<Long, int[]> DIRTY = new ConcurrentHashMap<>();
    /** Tables to clear, by place anchor: the table numbers. */
    static final Map<Long, java.util.Set<Integer>> TO_CLEAR = new ConcurrentHashMap<>();

    /** What each hand is doing for the dining room, for its card. */
    static final Map<UUID, String> DOING = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ORDERS.clear();
        DIRTY.clear();
        TO_CLEAR.clear();
        TASKS.clear();
        DOING.clear();
        Diners.resetForTests();
        DiningMenu.resetForTests();
        DiningPlaces.resetForTests();
        Hospitality.resetForTests();
        ROUND.clear();
        STAFF_FED.clear();
        nextId = 1;
        quickForTests(false);
    }

    /** The orders open at a place (oldest first). */
    static List<Order> at(Ledger.Building place) {
        List<Order> out = new ArrayList<>();
        for (Order o : ORDERS.values()) if (o.place.equals(place)) out.add(o);
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return out;
    }

    @Nullable
    public static Order order(int id) {
        return ORDERS.get(id);
    }

    /** This diner's open order (a player's or a folk's), or null. */
    @Nullable
    public static Order orderOf(UUID diner) {
        for (Order o : ORDERS.values()) if (o.diner.equals(diner) && !o.done) return o;
        return null;
    }

    /** The order at this table now, or null. */
    @Nullable
    static Order atTable(Ledger.Building place, int table) {
        for (Order o : ORDERS.values()) if (o.place.equals(place) && o.table == table && o.mode == Mode.TABLE && !o.done) return o;
        return null;
    }

    /**
     * An order put in: the lines on the kitchen's queue. Returns it, or null if nothing on it can be had (each dish must
     * be on the card and not sold out). {@code lines}: dish ids, one each.
     */
    @Nullable
    static Order place(ServerLevel level, Place p, int table, UUID diner, String name, boolean player, Mode mode, List<String> dishes,
                       @Nullable VillageFolkEntity folk) {
        List<DiningMenu.Line> card = DiningMenu.card(level, p, DiningMenu.meal(level.getDayTime()));
        Order o = new Order(nextId++, p.village, p.building, table, diner, name, player, mode, level.getGameTime());
        for (String id : dishes) {
            DiningMenu.Line l = null;
            for (DiningMenu.Line c : card) if (c.dish().id().equals(id)) { l = c; break; }
            if (l == null || l.soldOut()) continue;
            double each = folk != null ? DiningMenu.each(level, p, l.dish(), folk) : l.each();
            o.lines.add(new Line(id, l.price(), each));
        }
        if (o.lines.isEmpty()) return null;
        // Drinks first: they go out before the food.
        o.lines.sort((a, b) -> Boolean.compare(!a.drink(), !b.drink()));
        ORDERS.put(o.id, o);
        save(level);
        return o;
    }

    /** More put in on an order already in (a pudding after the main): the new lines join it, the bill with them. */
    static void merge(Order mine, Order more) {
        ORDERS.remove(more.id);
        mine.lines.addAll(more.lines);
        mine.paidCoins += more.paidCoins;
        mine.paid = mine.paidCoins >= mine.total();
        mine.done = false;
    }

    // ------------------------------------------------------------------ the hands at work

    enum Job { COOK, CARRY, TAKE, CLEAR, WASH, RUN }

    /** A hand's piece of dining work: what, for which order and line (or table), where it is going, and since when. */
    static final class Task {
        final Job job;
        final Ledger.Building place;
        final int order, line, table;
        int phase;
        long legFrom, until;
        @Nullable BlockPos target;

        Task(Job job, Ledger.Building place, int order, int line, int table, long now) {
            this.job = job;
            this.place = place;
            this.order = order;
            this.line = line;
            this.table = table;
            this.legFrom = now;
        }
    }

    static final Map<UUID, Task> TASKS = new ConcurrentHashMap<>();

    /** Has this hand a piece of dining work in hand (its trade's other work waits; the town is its ground meanwhile)? */
    public static boolean busy(VillageFolkEntity f) {
        return TASKS.containsKey(f.getUUID());
    }

    /** A beat of its dining work, from its own brain (Hospitality.work): the walking and the work are seen to by the
     *  pass every half second; here it is only kept from anything else. True while it has some. */
    static boolean work(VillageFolkEntity f, ServerLevel level) {
        return TASKS.containsKey(f.getUUID());
    }

    /** What it is doing for the dining room, for its card ("Cooking a roast beef at the smoker"), or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        return DOING.get(f.getUUID());
    }

    private static boolean free(VillageFolkEntity f) {
        return f.isAlive() && !f.isSleeping() && !TASKS.containsKey(f.getUUID()) && Diners.of(f) == null;
    }

    // ------------------------------------------------------------------ the pass every half second

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVERY != 0) return;
        if (ORDERS.isEmpty() && TASKS.isEmpty() && DIRTY.isEmpty() && TO_CLEAR.isEmpty() && Diners.none()) return;
        ServerLevel level = server.overworld();
        try {
            step(level);
        } catch (RuntimeException e) {
            if (LOGGED.add(e.getClass().getName())) com.mojang.logging.LogUtils.getLogger().warn("[dining] a dining pass failed", e);
        }
    }

    private static final java.util.Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** One pass: the hands at work, then new work handed out, then the folk at their tables (Diners). */
    static void step(ServerLevel level) {
        long now = level.getGameTime();
        for (Iterator<Map.Entry<UUID, Task>> it = TASKS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Task> e = it.next();
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity f) || !f.isAlive()) {
                giveBack(e.getValue());
                it.remove();
                DOING.remove(e.getKey());
                continue;
            }
            Task t = e.getValue();
            if (f.ownerId() != null && Raids.underAlarm(f.ownerId())) {      // the bell: everybody to safety, the work back in the queue
                giveBack(t);
                it.remove();
                DOING.remove(e.getKey());
                dropProp(f);
                continue;
            }
            boolean over;
            try {
                over = drive(level, f, t, now);
            } catch (RuntimeException ex) {
                if (LOGGED.add("task/" + t.job)) com.mojang.logging.LogUtils.getLogger().warn("[dining] a {} went wrong", t.job, ex);
                giveBack(t);
                over = true;
            }
            if (over) {
                it.remove();
                DOING.remove(e.getKey());
                dropProp(f);
            }
        }
        handOut(level, now);
        Diners.step(level, now);
        for (Iterator<Order> it = ORDERS.values().iterator(); it.hasNext(); ) {
            Order o = it.next();
            if (o.player && o.paid && o.finished()) o.done = true;
            if (o.done && o.paid || o.done && !o.player && o.finished()) {
                if (o.mode == Mode.TABLE) TO_CLEAR.computeIfAbsent(o.place.anchor().asLong(), k -> ConcurrentHashMap.newKeySet()).add(o.table);
                it.remove();
                save(level);
            } else if (o.player) {
                billOnLeaving(level, o);
            }
        }
    }

    /** A task given up half done (its hand gone): its line back where the next hand will pick it up. */
    private static void giveBack(Task t) {
        Order o = ORDERS.get(t.order);
        if (o == null || t.line < 0 || t.line >= o.lines.size()) return;
        Line l = o.lines.get(t.line);
        if (t.job == Job.COOK && l.step == Step.COOKING) l.step = l.got.isEmpty() ? Step.QUEUED : Step.PASS;
        if ((t.job == Job.CARRY || t.job == Job.RUN) && l.step == Step.CARRYING) l.step = Step.PASS;
    }

    /** New work to free hands: each line on the queue to a cook, each plate on the pass to a waiter (drinks first), each
     *  table to clear and each dirty plate to whoever washes. */
    private static void handOut(ServerLevel level, long now) {
        for (Order o : sortedOrders()) {
            Place p = placeOf(level, o);
            if (p == null || Raids.underAlarm(o.village)) continue;
            for (int i = 0; i < o.lines.size(); i++) {
                Line l = o.lines.get(i);
                Dish d = l.dishDef();
                if (d == null) { l.step = Step.STRUCK; continue; }
                if (l.step == Step.QUEUED) {
                    VillageFolkEntity cook = Hospitality.duty(level, o.village, Hospitality.Duty.COOK, p.station(d.station()), Dining::free);
                    if (cook == null) continue;
                    l.step = Step.COOKING;
                    Task t = new Task(Job.COOK, o.place, o.id, i, o.table, now);
                    t.target = DiningPlaces.behind(level, p.station(d.station()), p.door);
                    assign(cook, t, "Going to the " + stationWord(d.station()) + " for " + d.name().toLowerCase(java.util.Locale.ROOT));
                } else if (l.step == Step.PASS && (!foodWaitsForDrink(o, i))) {
                    VillageFolkEntity waiter = Hospitality.duty(level, o.village, Hospitality.Duty.SERVE, p.pass, Dining::free);
                    if (waiter == null) continue;
                    l.step = Step.CARRYING;
                    Task t = new Task(o.mode == Mode.DELIVERY ? Job.RUN : Job.CARRY, o.place, o.id, i, o.table, now);
                    t.target = DiningPlaces.behind(level, p.pass, p.door);
                    assign(waiter, t, "Taking " + d.name().toLowerCase(java.util.Locale.ROOT) + " off the pass");
                }
            }
        }
        for (Map.Entry<Long, java.util.Set<Integer>> e : TO_CLEAR.entrySet()) {
            for (Iterator<Integer> it = e.getValue().iterator(); it.hasNext(); ) {
                int table = it.next();
                Place p = placeByAnchor(level, e.getKey());
                if (p == null) { it.remove(); continue; }
                Table tb = p.table(table);
                if (tb == null) { it.remove(); continue; }
                if (atTable(p.building, table) != null) { it.remove(); continue; }          // somebody sat down meanwhile
                VillageFolkEntity waiter = Hospitality.duty(level, p.village, Hospitality.Duty.SERVE, tb.cell(), Dining::free);
                if (waiter == null) break;
                Task t = new Task(Job.CLEAR, p.building, -1, -1, table, now);
                t.target = standBy(level, tb);
                assign(waiter, t, "Clearing table " + table);
                it.remove();
            }
        }
        TO_CLEAR.values().removeIf(java.util.Set::isEmpty);
        for (Map.Entry<Long, int[]> e : DIRTY.entrySet()) {
            int[] d = e.getValue();
            if (d[0] + d[1] <= 0 || washing(e.getKey())) continue;
            Place p = placeByAnchor(level, e.getKey());
            if (p == null) continue;
            VillageFolkEntity washer = Hospitality.duty(level, p.village, Hospitality.Duty.WASH, p.wash, Dining::free);
            if (washer == null) continue;
            Task t = new Task(Job.WASH, p.building, -1, -1, 0, now);
            t.target = DiningPlaces.behind(level, p.wash, p.door);
            assign(washer, t, "Going to the sink at " + p.words());
        }
    }

    /** Food waits on the pass while a drink of the same order is still to go out: drinks first. */
    private static boolean foodWaitsForDrink(Order o, int i) {
        if (o.lines.get(i).drink()) return false;
        for (Line l : o.lines) if (l.drink() && (l.step == Step.QUEUED || l.step == Step.COOKING)) return true;
        return false;
    }

    private static boolean washing(long anchor) {
        for (Task t : TASKS.values()) if (t.job == Job.WASH && t.place.anchor().asLong() == anchor) return true;
        return false;
    }

    private static List<Order> sortedOrders() {
        List<Order> out = new ArrayList<>(ORDERS.values());
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return out;
    }

    private static void assign(VillageFolkEntity f, Task t, String doing) {
        TASKS.put(f.getUUID(), t);
        DOING.put(f.getUUID(), doing);
        f.clearQueue();
        f.getNavigation().stop();
    }

    @Nullable
    static Place placeOf(ServerLevel level, Order o) {
        if (!level.isLoaded(o.place.anchor())) return null;
        return DiningPlaces.place(level, o.village, o.place);
    }

    @Nullable
    private static Place placeByAnchor(ServerLevel level, long anchor) {
        for (Villages.Village v : Villages.every()) {
            for (Ledger.Building b : Ledger.buildings(v.id())) {
                if (b.anchor().asLong() == anchor && level.isLoaded(b.anchor())) return DiningPlaces.place(level, v.id(), b);
            }
        }
        return null;
    }

    static String stationWord(DiningPlaces.Station s) {
        return switch (s) {
            case SMOKER -> "smoker";
            case FURNACE -> "oven";
            case HEARTH -> "hearth";
            case BENCH -> "bench";
            case BREWING -> "kettle";
        };
    }

    // ------------------------------------------------------------------ walking

    /** A step toward a spot: true once there. Stuck too long (a doorway), it is put there. */
    static boolean walk(VillageFolkEntity f, BlockPos to, Task t, long now) {
        double d = f.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (d <= AT) {
            f.getNavigation().stop();
            return true;
        }
        if (now - t.legFrom > STUCK) {
            f.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            f.getNavigation().stop();
            return true;
        }
        if (f.getNavigation().isDone() || (now - t.legFrom) % 60 < EVERY) f.walkTo(to, 0.9D);
        return false;
    }

    /** Where a waiter stands to set a table: beside the table's place, not in the chair. */
    static BlockPos standBy(ServerLevel level, Table tb) {
        BlockPos post = tb.cell().below();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos s = post.relative(d);
            if (!s.equals(tb.seat()) && StoreFloor.standable(level, s)) return s.immutable();
        }
        return tb.seat().relative(tb.looks().getClockWise()).immutable();
    }

    // ------------------------------------------------------------------ the work itself

    /** A beat of one hand's task. True when the task is over (done, or given up). */
    private static boolean drive(ServerLevel level, VillageFolkEntity f, Task t, long now) {
        if (t.job == Job.TAKE) return Diners.take(level, f, t, now);
        if (t.job == Job.CLEAR) return clear(level, f, t, now);
        if (t.job == Job.WASH) return wash(level, f, t, now);
        Order o = ORDERS.get(t.order);
        if (o == null || t.line >= o.lines.size()) return true;
        Line l = o.lines.get(t.line);
        Place p = placeOf(level, o);
        Dish d = l.dishDef();
        if (p == null || d == null) {
            giveBack(t);
            return true;
        }
        return switch (t.job) {
            case COOK -> cook(level, f, t, o, l, p, d, now);
            case CARRY -> carry(level, f, t, o, l, p, d, now);
            case RUN -> run(level, f, t, o, l, p, d, now);
            default -> true;
        };
    }

    /** At the stove: there, the makings out of the stores, so long at the work (smoke, the sizzle), then on the pass. */
    private static boolean cook(ServerLevel level, VillageFolkEntity f, Task t, Order o, Line l, Place p, Dish d, long now) {
        BlockPos station = p.station(d.station());
        if (t.phase == 0) {
            if (!walk(f, t.target, t, now)) return false;
            Villages.Village v = Villages.get(o.village);
            ItemStack made = v == null ? ItemStack.EMPTY : produce(level, v, p, d, f);
            if (made.isEmpty()) {
                strike(level, o, l, p, f, d);
                return true;
            }
            l.got = made;
            l.cook = f.displayNameCap();
            l.at = station.immutable();
            t.phase = 1;
            t.until = now + cookTicks(f, d);
            holdProp(f, made);
            DOING.put(f.getUUID(), "Cooking " + d.name().toLowerCase(java.util.Locale.ROOT) + " at the " + stationWord(d.station()));
            f.brain("cooking " + d.name().toLowerCase(java.util.Locale.ROOT) + " for " + o.dinerName);
            return false;
        }
        f.getLookControl().setLookAt(station.getX() + 0.5, station.getY() + 0.5, station.getZ() + 0.5);
        if ((now / EVERY) % 3 == 0) f.swing(InteractionHand.MAIN_HAND);
        atTheStove(level, station, d.station(), now);
        if (now < t.until) return false;
        int level0 = f.veteranLevel();
        if (level0 < GREEN && Math.floorMod(o.id * 31 + t.line * 7 + (int) (now / 20), BURNS_ONE_IN) == 0) {
            // Burnt: thrown out, and made again from fresh makings.
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Ach, burnt it! Again, from the start.", "That one's ruined. Sorry, another's coming."));
            level.sendParticles(ParticleTypes.LARGE_SMOKE, station.getX() + 0.5, station.getY() + 1.1, station.getZ() + 0.5, 8, 0.2, 0.2, 0.2, 0.01);
            BURNT.merge(o.village, 1, Integer::sum);
            l.got = ItemStack.EMPTY;
            l.step = Step.QUEUED;
            return true;
        }
        l.quality = level0 >= MASTER_FROM ? 2 : level0 >= GOOD_FROM ? 1 : 0;
        l.step = Step.PASS;
        f.awardXp(XP_COOK);
        level.playSound(null, p.pass, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.35F, 1.6F);   // the pass bell
        if (o.player && level.getPlayerByUUID(o.diner) instanceof ServerPlayer sp && l.drink()) {
            sp.displayClientMessage(Component.literal(d.name() + " is ready."), true);
        }
        save(level);
        return true;
    }

    /** Dishes burnt by green hands, by town (the books). */
    static final Map<UUID, Integer> BURNT = new ConcurrentHashMap<>();

    /** How long a hand takes over a dish: the dish's own time, less the better the hand (to half), a little less with
     *  a master's knack. */
    static long cookTicks(VillageFolkEntity f, Dish d) {
        double scale = Math.max(0.5, 1.2 - 0.03 * f.veteranLevel());
        if (FolkSkills.active(f, FolkSkills.Knack.MASTER_HOST)) scale *= 0.88;
        long ticks = (long) (d.seconds() * 20 * scale);
        return QUICK ? Math.max(10, ticks / 8) : ticks;
    }

    /** The stove at work: smoke up the flue, the fire, the sizzle. */
    private static void atTheStove(ServerLevel level, BlockPos s, DiningPlaces.Station st, long now) {
        double x = s.getX() + 0.5, y = s.getY() + 1.0, z = s.getZ() + 0.5;
        level.sendParticles(ParticleTypes.SMOKE, x, y, z, 2, 0.15, 0.05, 0.15, 0.01);
        if (st == DiningPlaces.Station.SMOKER || st == DiningPlaces.Station.FURNACE || st == DiningPlaces.Station.HEARTH) {
            level.sendParticles(ParticleTypes.FLAME, x, y - 0.4, z, 1, 0.2, 0.05, 0.2, 0.0);
        }
        if ((now / EVERY) % 4 == 0) {
            level.playSound(null, s, st == DiningPlaces.Station.BREWING ? SoundEvents.BREWING_STAND_BREW
                : st == DiningPlaces.Station.BENCH ? SoundEvents.VILLAGER_WORK_BUTCHER : SoundEvents.FURNACE_FIRE_CRACKLE,
                SoundSource.BLOCKS, 0.5F, 1.0F);
        }
    }

    /**
     * The dish got for an order, out of the stores: a portion already cut; else as the dish is got (made to order,
     * ready first, or off the shelf) — ready means what the stores hold and can spare, made means by the game's recipe
     * the whole way from the stores (a town's own dish its own way: Cuisine.make). A whole cut into portions leaves the
     * rest for the next orders. Empty if nothing can be had (and nothing taken).
     */
    static ItemStack produce(ServerLevel level, Villages.Village v, Place p, Dish d, @Nullable VillageFolkEntity cook) {
        WorkStock.beginWith(shelf(level, p));                     // the kitchen's own pantry first, then the stores
        try {
            return producing(level, v, p, d, cook);
        } finally {
            WorkStock.end();
        }
    }

    private static ItemStack producing(ServerLevel level, Villages.Village v, Place p, Dish d, @Nullable VillageFolkEntity cook) {
        String cut = DiningMenu.cutKey(p, d);
        int left = DiningMenu.CUT.getOrDefault(cut, 0);
        if (d.portions() > 1 && left > 0) {
            DiningMenu.CUT.put(cut, left - 1);
            return d.one();
        }
        ItemStack got = switch (d.got()) {
            case MADE -> { ItemStack m = make(level, v, p, d, cook); yield m.isEmpty() ? ready(level, v, p, d) : m; }
            case READY_FIRST -> { ItemStack r = ready(level, v, p, d); yield r.isEmpty() ? make(level, v, p, d, cook) : r; }
            case SHELF -> ready(level, v, p, d);
        };
        DiningMenu.stale(p);
        return got;
    }

    /** One out of the stores, if they can spare it: the dish itself, else a whole to cut (the rest kept as portions). */
    private static ItemStack ready(ServerLevel level, Villages.Village v, Place p, Dish d) {
        ItemStack one = d.one();
        Predicate<ItemStack> same = one.is(Items.POTION) ? s -> ItemStack.isSameItemSameComponents(s, one) : s -> ItemStack.isSameItem(s, one);
        if (one.is(Items.POTION) || Budget.spare(level, v.id(), one) > 0) {
            ItemStack got = Crafts.takeOne(level, v, same);
            if (!got.isEmpty()) {
                if (d.whole() != null && ItemStack.isSameItem(d.whole().get(), one)) DiningMenu.CUT.merge(DiningMenu.cutKey(p, d), d.portions() - 1, Integer::sum);
                return got;
            }
        }
        if (d.whole() != null && !ItemStack.isSameItem(d.whole().get(), one)) {
            ItemStack whole = d.whole().get();
            if (Budget.spare(level, v.id(), whole) > 0 && !Crafts.takeOne(level, v, s -> ItemStack.isSameItem(s, whole)).isEmpty()) {
                DiningMenu.CUT.merge(DiningMenu.cutKey(p, d), d.portions() - 1, Integer::sum);
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /** One made by the game's own recipe out of the stores, into them, and taken (so what it made with it stays). */
    private static ItemStack make(ServerLevel level, Villages.Village v, Place p, Dish d, @Nullable VillageFolkEntity cook) {
        if (d.got() == DiningMenu.Got.SHELF) return ItemStack.EMPTY;
        ItemStack target = d.whole() != null ? d.whole().get() : d.one();
        Cuisine.Dish town = Cuisine.dishFor(target);
        ItemStack made;
        if (town != null) {
            made = Cuisine.make(level, v, town, cook);
        } else {
            Bench.Hand hand = Bench.handOf(level, v, cook, p.kind.structure);    // the place's own smoker, oven and hearth
            Bench.Plan plan = Bench.plan(level, v, target.getItem(), 1, hand);
            made = plan.ok() ? Bench.make(level, v, plan, cook, hand) : ItemStack.EMPTY;
        }
        if (made.isEmpty()) return ItemStack.EMPTY;
        ItemStack one = Crafts.takeOne(level, v, s -> ItemStack.isSameItem(s, target));
        if (one.isEmpty()) return ItemStack.EMPTY;
        if (d.whole() == null) return one;
        DiningMenu.CUT.merge(DiningMenu.cutKey(p, d), d.portions() - 1, Integer::sum);       // the rest of the whole, cut and kept
        return ItemStack.isSameItem(target, d.one()) ? one : d.one();
    }

    /** A dish whose makings ran out mid-service: struck off the card, the diner told, nothing charged for it. */
    private static void strike(ServerLevel level, Order o, Line l, Place p, VillageFolkEntity cook, Dish d) {
        l.step = Step.STRUCK;
        DiningMenu.stale(p);
        Stockroom.missed(level, o.village, p.kind == DiningPlaces.Kind.TAVERN ? Stockroom.Seller.TAVERN : Stockroom.Seller.CAFE, d.one());
        String said = "Sorry, we're out of " + d.name().toLowerCase(java.util.Locale.ROOT) + " tonight. It's off the menu, and off your bill.";
        if (o.player && level.getPlayerByUUID(o.diner) instanceof ServerPlayer sp) {
            if (o.paid && l.price > 0) refund(sp, o, l.price);
            sp.displayClientMessage(Component.literal(said), false);
        }
        VillageFolkEntity w = Hospitality.duty(level, o.village, Hospitality.Duty.SERVE, p.pass, x -> x.isAlive() && !x.isSleeping());
        FolkTalk.speak(w != null ? w : cook, said);
        save(level);
    }

    private static void refund(ServerPlayer sp, Order o, int coins) {
        int back = Math.min(coins, o.paidCoins);
        if (back <= 0) return;
        Ledger.takeCoins(o.village, back);
        o.paidCoins -= back;
        ItemStack c = new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), back);
        if (!sp.getInventory().add(c)) sp.drop(c, false);
    }

    /** The waiter: to the pass, the plate taken up (in hand), to the table, set down; or handed over at the counter. */
    private static boolean carry(ServerLevel level, VillageFolkEntity f, Task t, Order o, Line l, Place p, Dish d, long now) {
        if (t.phase == 0) {
            if (!walk(f, t.target, t, now)) return false;
            t.phase = 1;
            t.legFrom = now;
            holdProp(f, l.got);
            if (o.mode == Mode.TAKEAWAY) {
                t.target = t.target;                                       // it stays at the counter to hand it over
            } else {
                Table tb = p.table(o.table);
                if (tb == null) {
                    l.step = Step.PASS;
                    return true;
                }
                t.target = standBy(level, tb);
            }
            DOING.put(f.getUUID(), "Carrying " + d.name().toLowerCase(java.util.Locale.ROOT) + (o.mode == Mode.TABLE ? " to table " + o.table : " to the counter"));
            return false;
        }
        if (!walk(f, t.target, t, now)) return false;
        if (o.mode == Mode.TAKEAWAY) {
            handOver(level, f, o, l, d);
            return true;
        }
        Table tb = p.table(o.table);
        if (tb == null || !setDown(level, p, tb, o, l)) {
            // The table's place taken by something it cannot set down beside: handed to a player, or eaten as it is.
            if (o.player && level.getPlayerByUUID(o.diner) instanceof ServerPlayer sp && sp.distanceToSqr(f) < 12 * 12) {
                handOver(level, f, o, l, d);
                return true;
            }
            if (!o.player) {
                l.step = Step.SERVED;
                Diners.servedInHand(level, o, l);
                return true;
            }
            return false;
        }
        l.step = Step.SERVED;
        f.awardXp(XP_SERVE);
        f.getLookControl().setLookAt(tb.cell().getX() + 0.5, tb.cell().getY(), tb.cell().getZ() + 0.5);
        f.swing(InteractionHand.MAIN_HAND);
        FolkTalk.speak(f, l.drink() ? FolkTalk.pick(f.getRandom(), "Your " + d.name().toLowerCase(java.util.Locale.ROOT) + ".", "Something to drink while you wait.")
            : FolkTalk.pick(f.getRandom(), d.name() + ". Enjoy!", "Here we are: " + d.name().toLowerCase(java.util.Locale.ROOT) + ". Mind, it's hot.",
                "From the kitchen, with " + (l.cook.isEmpty() ? "the cook" : l.cook) + "'s compliments."));
        save(level);
        return true;
    }

    /** Taken away (or brought): the dish handed to the player, as it was made. */
    private static void handOver(ServerLevel level, VillageFolkEntity f, Order o, Line l, Dish d) {
        l.step = Step.EATEN;
        ItemStack s = l.got.copy();
        l.got = ItemStack.EMPTY;
        if (level.getPlayerByUUID(o.diner) instanceof ServerPlayer sp) {
            if (!sp.getInventory().add(s)) sp.drop(s, false);
            sp.displayClientMessage(Component.literal(f.displayNameCap() + " hands you the " + d.name().toLowerCase(java.util.Locale.ROOT)
                + (o.mode == Mode.TAKEAWAY ? ", wrapped to take away." : ", brought to you.")), true);
        } else {
            Villages.Village v = Villages.get(o.village);
            if (v != null) Crafts.store(level, v, s);                    // nobody to take it: back in the stores
        }
        f.awardXp(XP_SERVE);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Here you are. Enjoy it!", "Still warm. Mind how you go!"));
        save(level);
    }

    /** The runner: the dish in hand, out to the player wherever it is in town. Too far, or gone: back to the stores. */
    private static boolean run(ServerLevel level, VillageFolkEntity f, Task t, Order o, Line l, Place p, Dish d, long now) {
        if (t.phase == 0) {
            if (!walk(f, t.target, t, now)) return false;
            t.phase = 1;
            t.legFrom = now;
            holdProp(f, l.got);
            DOING.put(f.getUUID(), "Running " + d.name().toLowerCase(java.util.Locale.ROOT) + " out to " + o.dinerName);
        }
        ServerPlayer sp = level.getPlayerByUUID(o.diner) instanceof ServerPlayer x ? x : null;
        if (sp == null || sp.distanceToSqr(p.centre().getX(), p.centre().getY(), p.centre().getZ()) > RUN_REACH * RUN_REACH) {
            Villages.Village v = Villages.get(o.village);
            if (v != null && !l.got.isEmpty()) Crafts.store(level, v, l.got.copy());
            l.got = ItemStack.EMPTY;
            l.step = Step.STRUCK;
            if (sp != null && o.paid) refund(sp, o, l.price);
            return true;
        }
        BlockPos to = sp.blockPosition();
        if (t.target == null || !t.target.closerThan(to, 3)) {
            t.target = to;
        }
        if (!walk(f, t.target, t, now)) return false;
        handOver(level, f, o, l, d);
        return true;
    }

    /**
     * A plate (or a cup) set down at a table: on the setting there, or a setting laid on the table's cloth in place of its
     * carpet, out of the house's clean crockery. False if it cannot be (no clean plate, the place taken).
     */
    static boolean setDown(ServerLevel level, Place p, Table tb, Order o, Line l) {
        BlockPos cell = tb.cell();
        BlockState here = level.getBlockState(cell);
        boolean drink = l.drink();
        BlockState want;
        if (here.getBlock() instanceof PlaceSettingBlock) {
            if (!(level.getBlockEntity(cell) instanceof PlaceSettingBlockEntity be)) return false;
            if (drink ? !be.drink().isEmpty() : !be.food().isEmpty()) return false;            // still busy with the last
            want = here.setValue(PlaceSettingBlock.HOUSE, true);
            if (drink && here.getValue(PlaceSettingBlock.CUP) == PlaceSettingBlock.Cup.NONE) {
                if (!takeClean(level, p, false)) return false;
                want = want.setValue(PlaceSettingBlock.CUP, PlaceSettingBlock.Cup.FULL);
            }
            if (!drink && !here.getValue(PlaceSettingBlock.DISH).plate()) {
                if (!takeClean(level, p, true)) return false;
                want = want.setValue(PlaceSettingBlock.DISH, PlaceSettingBlock.Dish.EMPTY);
            }
            level.setBlock(cell, want, 3);
        } else {
            boolean cloth = PlaceSettingBlock.isCloth(here);
            if (!cloth && !here.isAir() || !PlaceSettingBlock.onATable(level, cell)) return false;
            if (!takeClean(level, p, !drink)) return false;
            want = DiningItems.PLACE_SETTING.get().defaultBlockState().setValue(PlaceSettingBlock.CLOTH, cloth).setValue(PlaceSettingBlock.HOUSE, true)
                .setValue(PlaceSettingBlock.DISH, drink ? PlaceSettingBlock.Dish.NONE : PlaceSettingBlock.Dish.EMPTY)
                .setValue(PlaceSettingBlock.CUP, drink ? PlaceSettingBlock.Cup.FULL : PlaceSettingBlock.Cup.NONE);
            level.setBlock(cell, want, 3);
            if (level.getBlockEntity(cell) instanceof PlaceSettingBlockEntity be) be.layOn(here);
        }
        if (!(level.getBlockEntity(cell) instanceof PlaceSettingBlockEntity be)) return false;
        if (drink) be.serveDrink(l.got, PlaceSettingBlock.SIPS, o.village, o.diner, o.dinerName, o.id);
        else be.serveFood(l.got, PlaceSettingBlock.bitesFor(l.got), o.village, o.diner, o.dinerName, o.id, l.quality);
        l.got = ItemStack.EMPTY;
        PlaceSettingBlock.refresh(level, cell);
        level.playSound(null, cell, SoundEvents.DECORATED_POT_PLACE, SoundSource.BLOCKS, 0.6F, 1.25F);
        return true;
    }

    /** A table cleared: the setting taken up (the cloth back to its carpet), the plate and the cup to the wash, a bowl or a
     *  bottle back to the stores, anything left uneaten to the stores' scraps (thrown out). */
    private static boolean clear(ServerLevel level, VillageFolkEntity f, Task t, long now) {
        UUID village = f.ownerId();
        Place p = village == null ? null : DiningPlaces.place(level, village, t.place);
        if (p == null) return true;
        Table tb = p.table(t.table);
        if (tb == null || !walk(f, t.target, t, now)) return tb == null;
        clearTable(level, p, tb);
        f.swing(InteractionHand.MAIN_HAND);
        f.awardXp(XP_CLEAR);
        return true;
    }

    /** The setting at a table taken up into the house's wash. */
    static void clearTable(ServerLevel level, Place p, Table tb) {
        BlockPos cell = tb.cell();
        BlockState st = level.getBlockState(cell);
        if (!(st.getBlock() instanceof PlaceSettingBlock) || !(level.getBlockEntity(cell) instanceof PlaceSettingBlockEntity be)) return;
        if (!st.getValue(PlaceSettingBlock.HOUSE)) return;                       // a player's own: not the house's to take
        int[] dirty = DIRTY.computeIfAbsent(p.building.anchor().asLong(), k -> new int[2]);
        if (st.getValue(PlaceSettingBlock.DISH).plate()) dirty[0]++;
        if (st.getValue(PlaceSettingBlock.CUP) != PlaceSettingBlock.Cup.NONE) dirty[1]++;
        // The bowl a stew came in, the bottle a drink was poured from: back to the stores. Anything left uneaten is scraps.
        Villages.Village v = Villages.get(p.village);
        if (v != null) for (ItemStack back : be.takeLeftovers()) Crafts.store(level, v, back);
        BlockState carpet = be.carpet();
        be.markCleared();
        level.setBlock(cell, st.getValue(PlaceSettingBlock.CLOTH) ? carpet : Blocks.AIR.defaultBlockState(), 3);
        level.playSound(null, cell, SoundEvents.DECORATED_POT_INSERT, SoundSource.BLOCKS, 0.6F, 1.1F);
        save(level);
    }

    /** At the sink: each plate and cup washed in turn (a splash, the clink), and back on the shelf. */
    private static boolean wash(ServerLevel level, VillageFolkEntity f, Task t, long now) {
        long key = t.place.anchor().asLong();
        int[] dirty = DIRTY.get(key);
        if (dirty == null || dirty[0] + dirty[1] <= 0) {
            DIRTY.remove(key);
            return true;
        }
        if (t.phase == 0) {
            if (!walk(f, t.target, t, now)) return false;
            t.phase = 1;
            t.until = now + (QUICK ? 10 : WASH_TICKS);
            DOING.put(f.getUUID(), "Washing up");
            return false;
        }
        Place p = null;
        if (f.ownerId() != null) p = DiningPlaces.place(level, f.ownerId(), t.place);
        if (p == null) return true;
        f.getLookControl().setLookAt(p.wash.getX() + 0.5, p.wash.getY() + 0.8, p.wash.getZ() + 0.5);
        if ((now / EVERY) % 2 == 0) {
            f.swing(InteractionHand.MAIN_HAND);
            level.sendParticles(ParticleTypes.SPLASH, p.wash.getX() + 0.5, p.wash.getY() + 1.0, p.wash.getZ() + 0.5, 6, 0.2, 0.1, 0.2, 0.05);
        }
        if (now < t.until) return false;
        boolean plate = dirty[0] > 0;
        if (plate) dirty[0]--; else dirty[1]--;
        putClean(level, p, new ItemStack(plate ? DiningItems.PLATE.get() : DiningItems.CUP.get()));
        WASHED.merge(p.village, 1, Integer::sum);
        level.playSound(null, p.wash, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.4F, 1.4F);
        f.awardXp(XP_WASH);
        t.until = now + (QUICK ? 10 : WASH_TICKS);
        save(level);
        return false;
    }

    /** Plates and cups washed, by town (the books, and the tests). */
    static final Map<UUID, Integer> WASHED = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ the house's crockery

    /** What a kitchen's pantry keeps, filled from the stores by the couriers (WorkStock.sendFor): the roasts' and the
     *  fish's raw makings, the vegetables and the bowls for the stews, the baking's wheat, eggs and sugar. Data. */
    static final List<WorkStock.Making> PANTRY = List.of(
        pantry(Items.BEEF, 4), pantry(Items.PORKCHOP, 4), pantry(Items.MUTTON, 4), pantry(Items.CHICKEN, 4), pantry(Items.COD, 4),
        pantry(Items.SALMON, 4), pantry(Items.RABBIT, 2), pantry(Items.POTATO, 8), pantry(Items.CARROT, 4), pantry(Items.BEETROOT, 6),
        pantry(Items.BROWN_MUSHROOM, 2), pantry(Items.RED_MUSHROOM, 2), pantry(Items.BOWL, 4), pantry(Items.WHEAT, 8), pantry(Items.EGG, 4),
        pantry(Items.SUGAR, 4));

    private static WorkStock.Making pantry(net.minecraft.world.item.Item it, int keep) {
        return new WorkStock.Making(s -> s.is(it) && s.getComponentsPatch().isEmpty(), it, keep, Bench.words(it, keep));
    }

    /**
     * The kitchen's pantry kept (the round, once a minute): its chest named for it (so the stores never reach in: one
     * truth), and what it is running low of sent for from the stores, for the couriers to carry over (StoreDeliveries).
     */
    static void keepPantry(ServerLevel level, Villages.Village v, Place p) {
        BlockPos at = shelfPos(level, p);
        if (at == null || !(level.getBlockEntity(at) instanceof Container c)) return;
        String name = cap(p.words().replace("the ", "")) + ": kitchen";
        if (level.getBlockEntity(at) instanceof net.minecraft.world.Nameable n && (n.getCustomName() == null || !n.getCustomName().getString().equals(name))
                && (n.getCustomName() == null || ZoneChests.isVillageStore(level.getBlockEntity(at)))) {
            // Named with what it holds kept: applying a name alone would empty a chest (its contents are a component too).
            var be = level.getBlockEntity(at);
            be.applyComponents(net.minecraft.core.component.DataComponentMap.builder().addAll(be.collectComponents())
                .set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name)).build(),
                net.minecraft.core.component.DataComponentPatch.EMPTY);
            level.getBlockEntity(at).setChanged();
            Villages.forgetStores(v.id());
        }
        WorkStock.sendFor(level, v, PANTRY, name, at, c);
    }

    /** The place's shelf: the first chest in it (else the town's stores). */
    @Nullable
    static Container shelf(ServerLevel level, Place p) {
        BlockPos at = shelfPos(level, p);
        return at != null && level.getBlockEntity(at) instanceof Container c ? c : null;
    }

    @Nullable
    private static BlockPos shelfPos(ServerLevel level, Place p) {
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement pl
                : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan(p.building.structure(), p.building.anchor(), p.building.facing(), 13)) {
            if (level.getBlockState(pl.pos()).getBlock() instanceof ChestBlock) return pl.pos();
        }
        return null;
    }

    /** A clean plate (or cup) off the house's shelf, else out of the town's stores. False if it has none. */
    static boolean takeClean(ServerLevel level, Place p, boolean plate) {
        net.minecraft.world.item.Item want = plate ? DiningItems.PLATE.get() : DiningItems.CUP.get();
        Container shelf = shelf(level, p);
        if (shelf != null) {
            for (int i = 0; i < shelf.getContainerSize(); i++) {
                ItemStack s = shelf.getItem(i);
                if (s.is(want)) {
                    s.shrink(1);
                    shelf.setChanged();
                    return true;
                }
            }
        }
        Villages.Village v = Villages.get(p.village);
        return v != null && !Crafts.takeOne(level, v, s -> s.is(want)).isEmpty();
    }

    /** A washed plate or cup back on the house's shelf (else the stores). */
    static void putClean(ServerLevel level, Place p, ItemStack s) {
        Container shelf = shelf(level, p);
        if (shelf != null) {
            for (int i = 0; i < shelf.getContainerSize() && !s.isEmpty(); i++) {
                ItemStack in = shelf.getItem(i);
                if (in.isEmpty()) {
                    shelf.setItem(i, s.copy());
                    s.setCount(0);
                } else if (ItemStack.isSameItemSameComponents(in, s) && in.getCount() < in.getMaxStackSize()) {
                    in.grow(1);
                    s.shrink(1);
                }
            }
            shelf.setChanged();
        }
        Villages.Village v = Villages.get(p.village);
        if (!s.isEmpty() && v != null) Crafts.store(level, v, s);
    }

    /** How many clean plates (or cups) the house has to hand: its shelf and the stores. */
    static int clean(ServerLevel level, Place p, boolean plate) {
        net.minecraft.world.item.Item want = plate ? DiningItems.PLATE.get() : DiningItems.CUP.get();
        int n = 0;
        Container shelf = shelf(level, p);
        if (shelf != null) for (int i = 0; i < shelf.getContainerSize(); i++) if (shelf.getItem(i).is(want)) n += shelf.getItem(i).getCount();
        return n + Market.stock(level, p.village, s -> s.is(want));
    }

    /** The house's crockery kept up (the round): short of plates or cups for its tables, its kitchen makes them of the
     *  stores' bricks (or the clay, fired), by the game's recipes, onto its shelf. */
    static void keepCrockery(ServerLevel level, Villages.Village v, Place p) {
        if (p.tables.isEmpty()) return;
        int[] dirty = DIRTY.getOrDefault(p.building.anchor().asLong(), new int[2]);
        for (boolean plate : new boolean[]{ true, false }) {
            int want = p.tables.size() * (plate ? PLATES_A_TABLE : CUPS_A_TABLE);
            int have = clean(level, p, plate) + (plate ? dirty[0] : dirty[1]) + onTables(level, p, plate);
            if (have >= want) continue;
            net.minecraft.world.item.Item it = plate ? DiningItems.PLATE.get() : DiningItems.CUP.get();
            Bench.Hand hand = Bench.handOf(level, v, Hospitality.first(v.id(), Hospitality.Role.HEAD_COOK), p.kind.structure);
            Bench.Plan plan = Bench.plan(level, v, it, want - have, hand);
            if (!plan.ok()) continue;
            ItemStack made = Bench.make(level, v, plan, null, hand);
            if (made.isEmpty()) continue;
            ItemStack moved = Crafts.takeOne(level, v, s -> s.is(it));
            while (!moved.isEmpty()) {
                putClean(level, p, moved);
                if (clean(level, p, plate) >= want) break;
                moved = Crafts.takeOne(level, v, s -> s.is(it));
            }
        }
    }

    private static int onTables(ServerLevel level, Place p, boolean plate) {
        int n = 0;
        for (Table t : p.tables) {
            BlockState s = level.getBlockState(t.cell());
            if (!(s.getBlock() instanceof PlaceSettingBlock) || !s.getValue(PlaceSettingBlock.HOUSE)) continue;
            if (plate ? s.getValue(PlaceSettingBlock.DISH).plate() : s.getValue(PlaceSettingBlock.CUP) != PlaceSettingBlock.Cup.NONE) n++;
        }
        return n;
    }

    /** A house's setting gone other than by its waiter (its table broken, say): the crockery back to the wash. */
    static void settingLost(PlaceSettingBlock.Lost lost) {
        Place p = DiningPlaces.at(lost.level(), lost.pos());
        if (p == null) return;
        int[] dirty = DIRTY.computeIfAbsent(p.building.anchor().asLong(), k -> new int[2]);
        if (lost.state().getValue(PlaceSettingBlock.DISH).plate()) dirty[0]++;
        if (lost.state().getValue(PlaceSettingBlock.CUP) != PlaceSettingBlock.Cup.NONE) dirty[1]++;
    }

    // ------------------------------------------------------------------ eating, paying

    /** Somebody ate at a setting: a player's line is eaten when its plate (or cup) is empty. */
    static void ate(PlaceSettingBlock.Ate a) {
        if (!(a.level() instanceof ServerLevel level) || !(level.getBlockEntity(a.pos()) instanceof PlaceSettingBlockEntity be)) return;
        Order o = ORDERS.get(be.order());
        if (o == null) return;
        if (a.bite().last()) {
            for (Line l : o.lines) {
                if (l.step == Step.SERVED && l.drink() == a.bite().drink()) {
                    l.step = Step.EATEN;
                    break;
                }
            }
            if (a.who() instanceof ServerPlayer sp && o.finished() && !o.paid) {
                sp.displayClientMessage(Component.literal("That was good. Pay the bill at the table (right-click the chair or the table) when you're ready."), true);
            }
            save(level);
        }
    }

    /**
     * A player pays an order: the whole bill (less what is struck off and what was paid upfront) and the tip, coin out of
     * the pack into the house's till (the town's treasury), the tip into the waiter's purse; the takings booked to the
     * house (Stockroom) and the town's books. Returns what it says, or null if it went through.
     */
    @Nullable
    static String pay(ServerLevel level, ServerPlayer p, Order o, int tip) {
        int due = Math.max(0, o.total() - o.paidCoins);
        int coins = Market.coinsHeld(p);
        if (coins < due + Math.max(0, tip)) return "The bill's " + due + (tip > 0 ? " and " + tip + " for the waiter" : "") + "; you've " + coins + ".";
        if (due > 0) {
            Market.payOut(p, due);
            takings(level, o, due);
            o.paidCoins += due;
        }
        o.paid = true;
        if (tip > 0) tipTheWaiter(level, o, tip, () -> Market.payOut(p, tip));
        if (o.mode != Mode.TABLE || o.finished()) o.done = true;
        save(level);
        return null;
    }

    /** The takings of a sale into the till, booked. */
    static void takings(ServerLevel level, Order o, int coins) {
        Ledger.addCoins(o.village, coins);
        Economy.sold(o.village, coins);
        Place p = placeOf(level, o);
        Stockroom.Seller seller = p != null && p.kind == DiningPlaces.Kind.TAVERN ? Stockroom.Seller.TAVERN : Stockroom.Seller.CAFE;
        for (Line l : o.lines) {
            if (l.step == Step.STRUCK) continue;
            Dish d = l.dishDef();
            if (d != null) Stockroom.sold(level, o.village, seller, d.one(), 1, l.price);
        }
        TAKINGS.merge(o.village, coins, Integer::sum);
    }

    /** The houses' takings, by town, since the start (the books). */
    static final Map<UUID, Integer> TAKINGS = new ConcurrentHashMap<>();

    /** A tip: to the waiter who served (else whoever of the floor is in), its spirits lifted for the day. */
    static void tipTheWaiter(ServerLevel level, Order o, int tip, Runnable takeIt) {
        Place p = placeOf(level, o);
        VillageFolkEntity w = p == null ? null : Hospitality.duty(level, o.village, Hospitality.Duty.SERVE, p.pass, x -> x.isAlive());
        if (w == null) return;
        takeIt.run();
        o.tip += tip;
        w.earn(tip);
        TIPPED.put(w.getUUID(), level.getDayTime() / 24000L);
        w.refreshMood();
        FolkTalk.speak(w, FolkTalk.pick(w.getRandom(), "Thank you kindly!", "That's very good of you. Come again!", "Much obliged!"));
    }

    /** The day each waiter was last tipped (its spirits). */
    static final Map<UUID, Long> TIPPED = new ConcurrentHashMap<>();

    /** A player gone off with the bill unpaid (past a table's reach): it is settled out of the purse, what there is. */
    private static void billOnLeaving(ServerLevel level, Order o) {
        if (o.paid || o.mode != Mode.TABLE || !o.allServed()) return;
        ServerPlayer p = level.getPlayerByUUID(o.diner) instanceof ServerPlayer sp ? sp : null;
        Place place = placeOf(level, o);
        if (place == null) return;
        if (p != null && p.distanceToSqr(place.centre().getX(), place.centre().getY(), place.centre().getZ()) < BILL_REACH * BILL_REACH) return;
        if (p != null) {
            int due = Math.max(0, o.total() - o.paidCoins);
            int take = Math.min(due, Market.coinsHeld(p));
            if (take > 0) {
                Market.payOut(p, take);
                takings(level, o, take);
                o.paidCoins += take;
            }
            p.displayClientMessage(Component.literal("You left " + place.words() + " without paying: " + take + " of the " + due
                + " coins came out of your purse." + (take < due ? " They'll remember." : "")), false);
            if (take < due) {
                // The house's staff think the less of them for it (the town's view of a player is its folk's: Standing).
                for (VillageFolkEntity f : Hospitality.trade(o.village)) {
                    if (f.distanceToSqr(place.centre().getX(), place.centre().getY(), place.centre().getZ()) > 48 * 48) continue;
                    f.persona().feelFor(p.getUUID(), p.getName().getString(), -6);
                    f.persona().remember(level.getDayTime() / 24000L, p.getName().getString() + " left " + place.words() + " without paying", -2);
                }
                Standing.stir(o.village, p.getUUID());
            }
        }
        o.paid = true;
        o.done = true;
        save(level);
    }

    // ------------------------------------------------------------------ the round (every quarter-minute, Grow)

    private static final Map<UUID, Long> ROUND = new ConcurrentHashMap<>();
    private static final Map<String, Long> BOARDED = new ConcurrentHashMap<>();

    /**
     * The town's dining seen to on its quarter-minute round (Grow.tick): its staff (Hospitality, once a minute), the
     * crockery kept up, the tables with nobody's order on them cleared (after a restart, a folk gone off), the menu boards
     * written up once a day. Cheap when there is nothing to do: each part keeps its own clock.
     */
    public static void round(ServerLevel level, Villages.Village v) {
        Hospitality.tick(level, v);
        long now = level.getGameTime();
        Long last = ROUND.get(v.id());
        if (last != null && now - last < 1200L && now >= last) return;           // the rest once a minute
        ROUND.put(v.id(), now);
        long day = level.getDayTime() / 24000L;
        for (Place p : DiningPlaces.of(level, v.id())) {
            keepPantry(level, v, p);
            keepCrockery(level, v, p);
            for (Table t : p.tables) {
                BlockState s = level.getBlockState(t.cell());
                if (!(s.getBlock() instanceof PlaceSettingBlock) || !s.getValue(PlaceSettingBlock.HOUSE)) continue;
                if (atTable(p.building, t.number()) != null) continue;
                TO_CLEAR.computeIfAbsent(p.building.anchor().asLong(), k -> ConcurrentHashMap.newKeySet()).add(t.number());
            }
            staffMeal(level, v, p, day);
            String key = p.building.anchor().asLong() + "/" + day;
            if (!BOARDED.containsKey(key)) {
                DiningPlaces.writeBoard(level, v, p, DiningMenu.board(level, p));
                BOARDED.put(key, now);
            }
        }
    }

    private static final Map<String, Long> STAFF_FED = new ConcurrentHashMap<>();
    /** How long after closing the staff sit down to their supper (longer than the round's minute, so it is not missed). */
    static final long STAFF_MEAL_WINDOW = 1500L;

    /**
     * The staff's supper, once the place has shut for the night (the first round in the half hour after closing): each
     * of the trade still at the place eats a plain meal out of the stores at the town's charge, and it is their supper.
     */
    static void staffMeal(ServerLevel level, Villages.Village v, Place p, long day) {
        long t = level.getDayTime() % 24000L;
        int close = p.kind.role.hours.close();
        if (Math.floorMod(t - close, 24000L) > STAFF_MEAL_WINDOW) return;
        String key = p.building.anchor().asLong() + "/" + day;
        if (STAFF_FED.putIfAbsent(key, day) != null) return;
        for (VillageFolkEntity f : Hospitality.trade(v.id())) {
            if (f.isSleeping() || f.distanceToSqr(p.centre().getX(), p.centre().getY(), p.centre().getZ()) > 24 * 24) continue;
            ItemStack food = Crafts.takeOne(level, v, Meals.FOOD);
            if (food.isEmpty()) break;
            FoodProperties fp = food.get(net.minecraft.core.component.DataComponents.FOOD);
            if (fp != null) f.heal(fp.nutrition() * 0.5F);
            f.meals().ate(level.getGameTime(), food.getHoverName().getString().toLowerCase(java.util.Locale.ROOT));
            Meals.ateOut(f, level);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Staff supper at last.", "That's the last table. Sit down, everyone, and eat."));
        }
    }

    // ------------------------------------------------------------------ the books

    /** The town's dining in a few lines (the board's pages, the smoke): the places, the orders in, the takings. */
    public static List<String> lines(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Place p : DiningPlaces.of(level, village)) {
            int in = 0;
            for (Order o : at(p.building)) if (!o.done) in++;
            out.add(cap(p.words()) + ": " + p.tables.size() + " tables, " + in + " orders in, " + clean(level, p, true) + " clean plates");
        }
        out.add("Takings " + TAKINGS.getOrDefault(village, 0) + " coins; " + WASHED.getOrDefault(village, 0) + " plates washed; "
            + BURNT.getOrDefault(village, 0) + " dishes burnt");
        out.addAll(Hospitality.lines(village));
        return out;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Its spirits (VillageFolkEntity.refreshMood): the happier for a meal out tonight, and a waiter for a tip. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        if (Diners.dinedOn(f) == day) {
            m += 5;
            why.add(new Object[]{ "dined", 6 });
        }
        Long tipped = TIPPED.get(f.getUUID());
        if (tipped != null && tipped == day) {
            m += 2;
            why.add(new Object[]{ "tipped", 3 });
        }
        return m;
    }

    /** How it puts it, asked how it is (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String why) {
        return switch (why) {
            case "dined" -> "We ate out tonight. " + (Diners.lastPlace(f).isEmpty() ? "" : "At " + Diners.lastPlace(f) + ". ") + "Lovely.";
            case "tipped" -> "A customer left me a tip. Made my day.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ saved with the world

    /** The orders, the wash and the cut portions, saved with the overworld (loaded at the first pass after a start). */
    static final class Saved extends SavedData {
        CompoundTag data = new CompoundTag();

        @Override
        public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
            tag.merge(data);
            return tag;
        }
    }

    private static final String SAVE = "mc_assistant_dining";
    private static boolean loaded;

    private static Saved saved(ServerLevel level) {
        Saved s = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Saved::new, (tag, reg) -> {
            Saved x = new Saved();
            x.data = tag;
            return x;
        }), SAVE);
        if (!loaded) {
            loaded = true;
            load(level, s.data);
        }
        return s;
    }

    /** Written out (an order taken, served, paid; a plate washed). */
    static void save(ServerLevel level) {
        Saved s = saved(level);
        CompoundTag t = new CompoundTag();
        ListTag orders = new ListTag();
        for (Order o : ORDERS.values()) orders.add(write(level, o));
        t.put("Orders", orders);
        t.putInt("Next", nextId);
        CompoundTag dirty = new CompoundTag();
        for (Map.Entry<Long, int[]> e : DIRTY.entrySet()) dirty.putIntArray(Long.toString(e.getKey()), e.getValue());
        t.put("Dirty", dirty);
        CompoundTag cut = new CompoundTag();
        for (Map.Entry<String, Integer> e : DiningMenu.CUT.entrySet()) cut.putInt(e.getKey(), e.getValue());
        t.put("Cut", cut);
        s.data = t;
        s.setDirty();
    }

    /** At the first pass after a start: the server's world, its orders read back (work in hand back in the queue). */
    @SubscribeEvent
    public static void onStarted(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        loaded = false;
        ORDERS.clear();
        DIRTY.clear();
        saved(e.getServer().overworld());
    }

    private static CompoundTag write(ServerLevel level, Order o) {
        CompoundTag t = new CompoundTag();
        t.putInt("Id", o.id);
        t.putUUID("Village", o.village);
        t.putString("Structure", o.place.structure());
        t.putLong("Anchor", o.place.anchor().asLong());
        t.putInt("Facing", o.place.facing().get3DDataValue());
        t.putInt("Table", o.table);
        t.putUUID("Diner", o.diner);
        t.putString("Name", o.dinerName);
        t.putBoolean("Player", o.player);
        t.putString("Mode", o.mode.name());
        t.putBoolean("Paid", o.paid);
        t.putInt("PaidCoins", o.paidCoins);
        t.putInt("Tip", o.tip);
        t.putLong("Placed", o.placed);
        t.putBoolean("Done", o.done);
        ListTag lines = new ListTag();
        for (Line l : o.lines) {
            CompoundTag x = new CompoundTag();
            x.putString("Dish", l.dish);
            x.putString("Step", l.step.name());
            x.putInt("Price", l.price);
            x.putDouble("Each", l.each);
            x.putInt("Quality", l.quality);
            x.putString("Cook", l.cook);
            if (!l.got.isEmpty()) x.put("Got", l.got.save(level.registryAccess()));
            lines.add(x);
        }
        t.put("Lines", lines);
        return t;
    }

    private static void load(ServerLevel level, CompoundTag t) {
        ORDERS.clear();
        nextId = Math.max(1, t.getInt("Next"));
        ListTag orders = t.getList("Orders", Tag.TAG_COMPOUND);
        for (int i = 0; i < orders.size(); i++) {
            CompoundTag x = orders.getCompound(i);
            try {
                Ledger.Building b = new Ledger.Building(x.getString("Structure"), BlockPos.of(x.getLong("Anchor")), Direction.from3DDataValue(x.getInt("Facing")));
                Order o = new Order(x.getInt("Id"), x.getUUID("Village"), b, x.getInt("Table"), x.getUUID("Diner"), x.getString("Name"),
                    x.getBoolean("Player"), Mode.valueOf(x.getString("Mode")), x.getLong("Placed"));
                o.paid = x.getBoolean("Paid");
                o.paidCoins = x.getInt("PaidCoins");
                o.tip = x.getInt("Tip");
                o.done = x.getBoolean("Done");
                ListTag lines = x.getList("Lines", Tag.TAG_COMPOUND);
                for (int k = 0; k < lines.size(); k++) {
                    CompoundTag y = lines.getCompound(k);
                    Line l = new Line(y.getString("Dish"), y.getInt("Price"), y.getDouble("Each"));
                    l.step = Step.valueOf(y.getString("Step"));
                    l.quality = y.getInt("Quality");
                    l.cook = y.getString("Cook");
                    if (y.contains("Got")) l.got = ItemStack.parseOptional(level.registryAccess(), y.getCompound("Got"));
                    // Work in hand at the restart goes back: off the stove onto the queue, out of a hand onto the pass.
                    if (l.step == Step.COOKING) l.step = l.got.isEmpty() ? Step.QUEUED : Step.PASS;
                    if (l.step == Step.CARRYING) l.step = l.got.isEmpty() ? Step.QUEUED : Step.PASS;   // [earning] a player's plate is gone
                    o.lines.add(l);
                }
                // A folk's meal does not outlast a restart: it got up and went home (what was cooked back to the stores).
                if (!o.player) {
                    o.done = true;
                    o.paid = true;
                    Villages.Village v = Villages.get(o.village);
                    for (Line l : o.lines) if (!l.got.isEmpty() && v != null) Crafts.store(level, v, l.got.copy());
                }
                ORDERS.put(o.id, o);
                nextId = Math.max(nextId, o.id + 1);
            } catch (RuntimeException bad) {
                com.mojang.logging.LogUtils.getLogger().warn("[dining] an order could not be read back: {}", bad.toString());
            }
        }
        CompoundTag dirty = t.getCompound("Dirty");
        for (String k : dirty.getAllKeys()) {
            int[] d = dirty.getIntArray(k);
            if (d.length == 2) DIRTY.put(Long.parseLong(k), d);
        }
        CompoundTag cut = t.getCompound("Cut");
        for (String k : cut.getAllKeys()) DiningMenu.CUT.put(k, cut.getInt(k));
    }

    /** Tests: the saved state written and read back, as at a restart (work in hand back in the queue). */
    public static void restartForTests(ServerLevel level) {
        save(level);
        CompoundTag data = saved(level).data.copy();
        TASKS.clear();
        DOING.clear();
        Diners.resetForTests();
        DIRTY.clear();
        load(level, data);
    }

    /** Tests: one pass now. */
    public static void stepForTests(ServerLevel level) {
        step(level);
    }

    /** Tests: the houses' books for a town: {takings, plates washed, dishes burnt}. */
    public static int[] booksForTests(UUID village) {
        return new int[]{ TAKINGS.getOrDefault(village, 0), WASHED.getOrDefault(village, 0), BURNT.getOrDefault(village, 0) };
    }

    /** Tests: the dirty plates and cups at a place. */
    public static int[] dirtyForTests(Ledger.Building b) {
        return DIRTY.getOrDefault(b.anchor().asLong(), new int[2]).clone();
    }

    /** Tests: who has which job in hand: "COOK", "CARRY"... or null. */
    @Nullable
    public static String jobForTests(VillageFolkEntity f) {
        Task t = TASKS.get(f.getUUID());
        return t == null ? null : t.job.name();
    }

    static {
        PlaceSettingBlock.ATE = Dining::ate;
        PlaceSettingBlock.LOST = Dining::settingLost;
    }

    /** Hooked up at the mod's start (DiningItems.register): the setting's news comes here. */
    public static void hook() {
        PlaceSettingBlock.ATE = Dining::ate;
        PlaceSettingBlock.LOST = Dining::settingLost;
    }

    /** A dish in hand to be seen (the tray): a copy marked as a prop in its off hand, never dropped or eaten (Leisure). */
    static void holdProp(VillageFolkEntity f, ItemStack s) {
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!off.isEmpty() && !Leisure.isProp(off)) return;
        ItemStack c = s.copyWithCount(1);
        CompoundTag t = new CompoundTag();
        t.putBoolean("mca_prop", true);
        c.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, CustomData.of(t));
        f.setItemSlot(EquipmentSlot.OFFHAND, c);
    }

    /** The prop put down. */
    static void dropProp(VillageFolkEntity f) {
        if (Leisure.isProp(f.getItemBySlot(EquipmentSlot.OFFHAND))) f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }
}
