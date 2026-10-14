package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bakery [batchE]: a bakehouse with two ovens and a counter (bakery.txt), built in the Stone Age once a
 * farming town is twenty strong. A hand at the town's works bakes in it, the café's cook for choice: bread of the
 * farmers' wheat, cookies of wheat and cocoa beans, pumpkin pie of a pumpkin, sugar pressed from cane and an
 * egg, and a cake of three buckets of the rancher's milk (the buckets go back), sugar, an egg and wheat. Every
 * one by the game's own recipe, the whole way from what the stores hold (Bench: cane to sugar, wheat to bread),
 * and never the farmers' last twelve of the wheat.
 *
 * <p>It bakes whatever the stores are shortest of against what a town of its size likes to have by (a loaf
 * a head, a few dozen cookies, a pie or two, a cake, and another for a feast night), into the stores, where
 * the café sets it out on its counter (Cafe) and the feasts and the larder draw on it. Its wheat comes from
 * the windmill first, when there is one: the baker walks over for a sack, and with the mill grinding for it
 * bakes twice the batch at a time. The village's idle hands, who have always baked the odd batch of bread at
 * the stores, leave the bread to the bakery while it is at it (Villages.noteBake).
 */
public final class Bakery {

    private Bakery() {}

    /** A town of this many keeps a bakery. */
    static final int FROM = 20;
    /** A sack of the mill's wheat. */
    static final int SACK = 27;

    /** What the bakery bakes, and how many to a batch (without the mill). */
    record Bake(Item what, int batch) {}

    static final List<Bake> BAKES = List.of(
        new Bake(Items.BREAD, 3), new Bake(Items.COOKIE, 8), new Bake(Items.PUMPKIN_PIE, 1), new Bake(Items.CAKE, 1));

    /** What it baked today, by village: the day and so many of each. */
    private static final Map<UUID, Object[]> TODAY = new ConcurrentHashMap<>();

    /** [roles-c] A batch's time in the oven, which the baker waits out at the ovens before the next (ticks). */
    static final long BATCH_EVERY = 400L;
    /** [roles-c] How long a baker tries to reach a spot before it gives the round up (and the works' hand bakes). */
    static final long STUCK = 1200L;
    /** [roles-c] Close enough to the ovens or the mill's door to work at it. */
    static final double REACH = 2.5;

    /** [roles-c] Where the baker is in its round. */
    enum Leg { NONE, TO_MILL, HOME_WITH_SACK, AT_OVEN }

    /** [roles-c] The baker's round: its leg, the wheat it carries from the mill, when its batch went in, how long it
     *  has been walking to where it is going, how long nothing is wanted at the ovens, and the day it was last short. */
    private static final class Round {
        Leg leg = Leg.NONE;
        int sack;
        long batchAt = -100000L, legSince, idleUntil, stuckUntil, shortDay = -1;
        int walkTick = -1000;
    }

    private static final Map<UUID, Round> ROUNDS = new ConcurrentHashMap<>();
    /** [roles-c] Tests: the baker arrives wherever it walks at once, and a batch is out at once. */
    private static volatile boolean quick;

    static void resetForTests() {
        TODAY.clear();
        ROUNDS.clear();
        quick = false;
    }

    @Nullable
    static Ledger.Building bakery(UUID village) {
        return TownLook.building(village, TownLook.BAKERY);
    }

    /** Where the baker works: before the ovens. */
    static BlockPos oven(Ledger.Building b) {
        return TownLook.cell(b, -1, 0, 1);
    }

    /** How many of each the town likes to have by, for its size (and a cake more on a feast night). */
    static int wanted(UUID village, Item what, long day) {
        int folk = Villages.headcount(village);
        if (what == Items.BREAD) return Math.max(24, folk);
        if (what == Items.COOKIE) return 16 + folk / 4;
        if (what == Items.PUMPKIN_PIE) return 4 + folk / 10;
        if (what == Items.CAKE) return 1 + (Gatherings.tonight(village, day) == Gatherings.Kind.FEAST ? 1 : 0);
        return 0;
    }

    /** The hand at the ovens now: [roles-c] the baker at its post, else the town's works' hand ("baking"); or null. */
    @Nullable
    static VillageFolkEntity baker(UUID village) {
        VillageFolkEntity post = Hospitality.first(village, Hospitality.Role.BAKER);
        if (post != null && post.isAlive()) {
            Round r = ROUNDS.get(post.getUUID());
            if (r != null && r.leg == Leg.AT_OVEN) return post;
        }
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f) {
                String doing = TownJobs.doing(f);
                if (doing != null && doing.contains("bakery")) return f;
            }
        }
        return null;
    }

    /** The bakes the stores are short of, the shortest first (none: the shelves are full). */
    static List<Bake> shortest(ServerLevel level, UUID id) {
        long day = level.getDayTime() / 24000L;
        List<Bake> order = new ArrayList<>(BAKES);
        Map<Item, Double> shortBy = new LinkedHashMap<>();
        for (Bake b : order) {
            int want = wanted(id, b.what(), day);
            int have = Market.stock(level, id, s -> s.is(b.what()));
            shortBy.put(b.what(), want <= 0 ? 0.0 : (want - have) / (double) want);
        }
        order.removeIf(b -> shortBy.get(b.what()) <= 0.0);
        order.sort((a, b) -> Double.compare(shortBy.get(b.what()), shortBy.get(a.what())));
        return order;
    }

    /**
     * The bakes the stores are shortest of, in turn, each as the bench would make it now: the first the stores
     * run to. Null if nothing is short or nothing can be made.
     */
    @Nullable
    static Object[] next(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, boolean milled) {
        Bench.Hand hand = Bench.handOf(level, v, f, TownLook.BAKERY);
        for (Bake b : shortest(level, v.id())) {
            int n = b.batch() * (milled && b.what() != Items.CAKE ? 2 : 1);
            Bench.Plan plan = Bench.plan(level, v, b.what(), n, hand);
            if (plan.ok() && plan.made > 0) return new Object[]{ b, plan, hand };
        }
        return null;
    }

    /**
     * One visit: a sack of wheat fetched from the mill when the stores are low on it, else a batch baked.
     * True if anything was done.
     */
    static boolean tick(ServerLevel level, Villages.Village v) {
        Ledger.Building b = bakery(v.id());
        if (b == null || !Land.areaLoaded(level, b.anchor(), 6)) return false;
        if (postHeld(level, v.id())) return false;                       // [roles-c] the bakery is its baker's
        return fetch(level, v) || bake(level, v, b) != null;
    }

    /** A sack of the mill's wheat brought to the stores, when the stores are low on it and the mill is not. */
    static boolean fetch(ServerLevel level, Villages.Village v) {
        Ledger.Building mill = Windmill.mill(v.id());
        if (mill == null || Windmill.grain(level, v.id()) < 9) return false;
        if (Windmill.inStores(level, v.id(), mill) >= 12 + SACK) return false;           // the stores have the baking's wheat
        if (!TownJobs.atWork(level, v, "baking", Windmill.door(mill), "fetching a sack of flour from the windmill for the bakery",
                AssistantEntity.StationTask.COOK)) return false;
        int got = Windmill.draw(level, v.id(), SACK);
        if (got <= 0) return false;
        Windmill.toStores(level, v, mill, new ItemStack(Items.WHEAT, got));
        return true;
    }

    /** A batch baked at the bakery, into the stores. Returns what came out of the oven, or null. */
    @Nullable
    static ItemStack bake(ServerLevel level, Villages.Village v, Ledger.Building b) {
        boolean milled = Windmill.mill(v.id()) != null;
        VillageFolkEntity f = baker(v.id());
        Object[] next = next(level, v, f, milled);
        if (next == null) return null;
        Bake bake = (Bake) next[0];
        if (!TownJobs.atWork(level, v, "baking", oven(b), "baking at the bakery", AssistantEntity.StationTask.COOK)) return null;
        return bakeWith(level, v, b, bake, baker(v.id()), milled);
    }

    /** A batch of this bake by this hand (the baker, the works' hand, or nobody's for the tests), into the stores. */
    @Nullable
    static ItemStack bakeWith(ServerLevel level, Villages.Village v, Ledger.Building b, Bake bake, @Nullable VillageFolkEntity f,
                              boolean milled) {
        Bench.Hand hand = Bench.handOf(level, v, f, TownLook.BAKERY);
        Bench.Plan plan = Bench.plan(level, v, bake.what(), bake.batch() * (milled && bake.what() != Items.CAKE ? 2 : 1), hand);
        if (!plan.ok()) return null;
        ItemStack made = Bench.make(level, v, plan, f, hand);
        if (made.isEmpty()) return null;
        booked(level, v, b, made);
        if (f != null && f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Fresh out of the oven!", "Mind, that's hot.",
                "Smell that? " + capital(Bench.words(made.getItem(), made.getCount())) + ".", "The café'll want these."));
        }
        return made;
    }

    /** A batch out of the oven, counted: the day's bakes, the idle hands' bread, the first lighting's news, the oven's
     *  crackle. ([roles-c] a player's batch on a baker's-hand shift too: HospitalityShifts.) */
    static void booked(ServerLevel level, Villages.Village v, Ledger.Building b, ItemStack made) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        if (made.is(Items.BREAD)) Villages.noteBake(id, level.getGameTime());       // the idle hands' bread waits on the bakery's
        Object[] today = TODAY.get(id);
        if (Ledger.note(id, "bakery.first") == null || Ledger.note(id, "bakery.first").isEmpty()) {
            Ledger.note(id, "bakery.first", Long.toString(day));
            Villages.tell(id, day, "the bakery's ovens were lit for the first time: " + Bench.words(made.getItem(), made.getCount()));
        }
        if (today == null || (long) today[0] != day) {
            today = new Object[]{ day, new LinkedHashMap<Item, Integer>() };
            TODAY.put(id, today);
        }
        @SuppressWarnings("unchecked") Map<Item, Integer> counts = (Map<Item, Integer>) today[1];
        counts.merge(made.getItem(), made.getCount(), Integer::sum);
        level.playSound(null, oven(b), SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.8F, 1.0F);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ [roles-c] the baker at its post

    /** Does the town's baker hold the bakery now (alive, up and about, not stuck on its way)? Then the works leave it be. */
    static boolean postHeld(ServerLevel level, UUID village) {
        VillageFolkEntity f = Hospitality.first(village, Hospitality.Role.BAKER);
        if (f == null || !f.isAlive() || Health.laidUp(f)) return false;
        Round r = ROUNDS.get(f.getUUID());
        return r == null || level.getGameTime() >= r.stuckUntil;
    }

    /** Is a sack of the mill's wheat wanted at the bakery (the stores low on it, the mill not)? */
    static boolean sackWanted(ServerLevel level, UUID village) {
        Ledger.Building mill = Windmill.mill(village);
        return mill != null && Windmill.grain(level, village) >= 9 && Windmill.inStores(level, village, mill) < 12 + SACK;
    }

    /**
     * A beat of the baker's own work through its hours (Hospitality.work): the mill's wheat fetched on its own back when
     * the stores are low on it, then a batch put in at the ovens and waited out, and the next. False when nothing is
     * wanted at the ovens (it helps at the café meanwhile) or there is no bakery.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        Ledger.Building b = id == null ? null : bakery(id);
        if (v == null || b == null || !Land.areaLoaded(level, b.anchor(), 6) || Raids.underAlarm(id)) return false;
        Round r = ROUNDS.computeIfAbsent(f.getUUID(), k -> new Round());
        long now = level.getGameTime();
        if (r.leg == Leg.NONE) {
            if (now < r.idleUntil && r.sack == 0) return false;
            r.leg = r.sack > 0 ? Leg.HOME_WITH_SACK : sackWanted(level, id) ? Leg.TO_MILL : Leg.AT_OVEN;
            r.legSince = now;
        }
        return switch (r.leg) {
            case TO_MILL -> toMill(f, level, v, r, now);
            case HOME_WITH_SACK -> homeWithSack(f, level, v, b, r, now);
            default -> atOven(f, level, v, b, r, now);
        };
    }

    /** To the mill's door, and a sack of its wheat into the baker's pack. */
    private static boolean toMill(VillageFolkEntity f, ServerLevel level, Villages.Village v, Round r, long now) {
        Ledger.Building mill = Windmill.mill(v.id());
        if (mill == null) { r.leg = Leg.NONE; return false; }
        BlockPos door = Windmill.door(mill);
        if (!arrive(f, door, r, now, "on its way to the windmill for a sack of wheat")) return true;
        int got = Windmill.draw(level, v.id(), SACK);
        ItemStack left = got > 0 ? f.insertItem(new ItemStack(Items.WHEAT, got)) : ItemStack.EMPTY;
        if (!left.isEmpty()) Windmill.store(level, mill, left);                          // no room in its pack: back in the mill
        r.sack = got - left.getCount();
        r.leg = r.sack > 0 ? Leg.HOME_WITH_SACK : Leg.AT_OVEN;
        r.legSince = now;
        if (r.sack > 0) f.brain("took a sack of " + r.sack + " wheat from the windmill for the bakery");
        return true;
    }

    /** Back to the bakery with the sack, and the wheat into the stores for the bake. */
    private static boolean homeWithSack(VillageFolkEntity f, ServerLevel level, Villages.Village v, Ledger.Building b, Round r, long now) {
        if (!arrive(f, oven(b), r, now, "carrying a sack of the mill's wheat to the bakery")) return true;
        int carried = f.removeMatching(s -> s.is(Items.WHEAT) && s.getComponentsPatch().isEmpty(), r.sack);
        Ledger.Building mill = Windmill.mill(v.id());
        if (carried > 0) {
            ItemStack wheat = new ItemStack(Items.WHEAT, carried);
            if (mill != null) Windmill.toStores(level, v, mill, wheat);
            else Crafts.store(level, v, wheat);
        }
        r.sack = 0;
        r.leg = Leg.AT_OVEN;
        r.legSince = now;
        return true;
    }

    /** At the ovens: a batch in, waited out; nothing short, back to the café a while; the makings gone, said so. */
    private static boolean atOven(VillageFolkEntity f, ServerLevel level, Villages.Village v, Ledger.Building b, Round r, long now) {
        BlockPos oven = oven(b);
        if (!arrive(f, oven, r, now, "on its way to the bakery's ovens")) return true;
        f.getLookControl().setLookAt(oven.getX() + 0.5, oven.getY() + 0.5, oven.getZ() + 0.5);
        f.hobbyNow = "baking at the bakery";
        if (!quick && now - r.batchAt < BATCH_EVERY && now >= r.batchAt) {
            if (f.tickCount % 40 == 0) f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);   // the dough worked, the oven watched
            return true;
        }
        boolean milled = Windmill.mill(v.id()) != null;
        Object[] next = next(level, v, f, milled);
        ItemStack made = next == null ? null : bakeWith(level, v, b, (Bake) next[0], f, milled);
        if (made != null) {
            r.batchAt = now;
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return true;
        }
        outOfMakings(level, v, f, r);
        r.leg = Leg.NONE;
        r.idleUntil = now + BATCH_EVERY;
        return false;
    }

    /**
     * Nothing could be baked: if a bake is short, the makings are gone. The café's books count the bake it could not
     * make (Stockroom: what is kept follows what was wanted), and the baker says so, once a day.
     */
    private static void outOfMakings(ServerLevel level, Villages.Village v, VillageFolkEntity f, Round r) {
        List<Bake> wanted = shortest(level, v.id());
        long day = level.getDayTime() / 24000L;
        if (wanted.isEmpty() || r.shortDay == day) return;
        r.shortDay = day;
        Bake first = wanted.get(0);
        Stockroom.missed(level, v.id(), Stockroom.Seller.CAFE, new ItemStack(first.what()));
        f.brain("could not bake: the stores and the mill are out of the makings for " + Bench.plural(
            first.what().getDescription().getString().toLowerCase(java.util.Locale.ROOT)));
        FolkTalk.speak(f, first.what() == Items.BREAD ? "No wheat, no bread. The mill's empty, and so are the stores."
            : "I'm out of the makings. The ovens will have to wait.");
    }

    /**
     * Walking to a spot: true once there. A baker that gets no nearer for a long while gives the round up for a while
     * (the works' hand bakes meanwhile: postHeld).
     */
    private static boolean arrive(VillageFolkEntity f, BlockPos at, Round r, long now, String what) {
        if (quick && f.blockPosition().distSqr(at) > REACH * REACH) f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (f.blockPosition().distSqr(at) <= REACH * REACH) {
            f.getNavigation().stop();
            return true;
        }
        if (now - r.legSince > STUCK) {
            r.leg = Leg.NONE;
            r.stuckUntil = now + STUCK;
            r.idleUntil = now + STUCK;
            f.brain("could not get to " + (what.contains("windmill") ? "the windmill" : "the bakery") + ": the works will bake for now");
            return false;
        }
        if (f.getNavigation().isDone() || f.tickCount - r.walkTick > 60) {
            f.walkTo(at, 0.9D);
            r.walkTick = f.tickCount;
        }
        f.hobbyNow = what;
        return false;
    }

    /** "2 loaves of bread, 8 cookies": what it baked today. */
    static String todayWords(UUID village, long day) {
        Object[] today = TODAY.get(village);
        if (today == null || (long) today[0] != day) return "";
        @SuppressWarnings("unchecked") Map<Item, Integer> counts = (Map<Item, Integer>) today[1];
        List<String> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : counts.entrySet()) out.add(Bench.words(e.getKey(), e.getValue()));
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ where a player sees it

    @Nullable
    static String boardPart(ServerLevel level, UUID village) {
        if (bakery(village) == null) return null;
        String today = todayWords(village, level.getDayTime() / 24000L);
        VillageFolkEntity post = Hospitality.first(village, Hospitality.Role.BAKER);   // [roles-c] its baker by name
        return "the bakery" + (post == null ? "" : " (" + post.displayNameCap() + " the baker)")
            + (today.isEmpty() ? " (nothing out of the oven yet today)" : " baked " + today + " today");
    }

    @Nullable
    static String cardPart(VillageFolkEntity f) {
        UUID village = f.ownerId();
        boolean post = Hospitality.role(f) == Hospitality.Role.BAKER && village != null && bakery(village) != null;   // [roles-c]
        String doing = TownJobs.doing(f);
        if (!post && (doing == null || !doing.contains("bakery"))) return null;
        String today = village == null ? "" : todayWords(village, f.level().getDayTime() / 24000L);
        return (post ? "the town's baker" : "baking for the town") + (today.isEmpty() ? "" : " (" + today + " so far today)");
    }

    static String line(ServerLevel level, Villages.Village v) {
        if (bakery(v.id()) == null) return "none yet";
        String today = todayWords(v.id(), level.getDayTime() / 24000L);
        List<String> stock = new ArrayList<>();
        for (Bake b : BAKES) stock.add(Market.stock(level, v.id(), s -> s.is(b.what())) + " " + Bench.plural(b.what().getDescription().getString().toLowerCase(java.util.Locale.ROOT)));
        return (today.isEmpty() ? "nothing baked yet today" : "baked " + today + " today") + "; the stores have " + String.join(", ", stock);
    }

    // ------------------------------------------------------------------ tests

    /** [roles-c] Tests: the baker's round so many beats, arriving at once and each batch out at once. Returns what the
     *  stores gained of each bake, in order of the beats that baked. */
    public static List<String> postRoundsForTests(ServerLevel level, VillageFolkEntity baker, int beats) {
        List<String> out = new ArrayList<>();
        quick = true;
        try {
            for (int i = 0; i < beats; i++) {
                Round r = ROUNDS.get(baker.getUUID());
                Leg before = r == null ? Leg.NONE : r.leg;
                boolean busy = work(baker, level);
                Round after = ROUNDS.get(baker.getUUID());
                out.add(before + ">" + (after == null ? Leg.NONE : after.leg) + (busy ? "" : " idle"));
                if (after != null && !busy) after.idleUntil = 0;
            }
        } finally {
            quick = false;
        }
        return out;
    }

    /** [roles-c] Tests: does the baker hold the bakery (the works leave it be)? */
    public static boolean postHeldForTests(ServerLevel level, UUID village) {
        return postHeld(level, village);
    }

    /** Tests: so many batches baked now (a sack fetched from the mill counts as one round). Returns what came out, in order. */
    public static List<ItemStack> bakeForTests(ServerLevel level, Villages.Village v, int rounds) {
        List<ItemStack> out = new ArrayList<>();
        Ledger.Building b = bakery(v.id());
        if (b == null) return out;
        for (int i = 0; i < rounds; i++) {
            if (fetch(level, v)) continue;
            ItemStack made = bake(level, v, b);
            if (made == null) break;
            out.add(made);
        }
        return out;
    }

}
