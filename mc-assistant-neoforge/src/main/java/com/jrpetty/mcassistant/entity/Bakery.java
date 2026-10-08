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

    static void resetForTests() {
        TODAY.clear();
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

    /** Does this want wheat (so the mill's is worth fetching)? */
    static boolean wheaten(Item what) {
        return what == Items.BREAD || what == Items.COOKIE || what == Items.CAKE;
    }

    /** The hand at the ovens now (the town's works call it "baking"), or null. */
    @Nullable
    static VillageFolkEntity baker(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f) {
                String doing = TownJobs.doing(f);
                if (doing != null && doing.contains("bakery")) return f;
            }
        }
        return null;
    }

    /**
     * The bakes the stores are shortest of, in turn, each as the bench would make it now: the first the stores
     * run to. Null if nothing is short or nothing can be made.
     */
    @Nullable
    static Object[] next(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, boolean milled) {
        UUID id = v.id();
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
        Bench.Hand hand = Bench.handOf(level, v, f, TownLook.BAKERY);
        for (Bake b : order) {
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
        f = baker(v.id());
        Bench.Hand hand = Bench.handOf(level, v, f, TownLook.BAKERY);
        Bench.Plan plan = Bench.plan(level, v, bake.what(), bake.batch() * (milled && bake.what() != Items.CAKE ? 2 : 1), hand);
        if (!plan.ok()) return null;
        ItemStack made = Bench.make(level, v, plan, f, hand);
        if (made.isEmpty()) return null;
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
        if (f != null && f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Fresh out of the oven!", "Mind, that's hot.",
                "Smell that? " + capital(Bench.words(made.getItem(), made.getCount())) + ".", "The café'll want these."));
        }
        return made;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
        return "the bakery " + (today.isEmpty() ? "(nothing out of the oven yet today)" : "baked " + today + " today");
    }

    @Nullable
    static String cardPart(VillageFolkEntity f) {
        String doing = TownJobs.doing(f);
        if (doing == null || !doing.contains("bakery")) return null;
        UUID village = f.ownerId();
        String today = village == null ? "" : todayWords(village, f.level().getDayTime() / 24000L);
        return "baking for the town" + (today.isEmpty() ? "" : " (" + today + " so far today)");
    }

    static String line(ServerLevel level, Villages.Village v) {
        if (bakery(v.id()) == null) return "none yet";
        String today = todayWords(v.id(), level.getDayTime() / 24000L);
        List<String> stock = new ArrayList<>();
        for (Bake b : BAKES) stock.add(Market.stock(level, v.id(), s -> s.is(b.what())) + " " + Bench.plural(b.what().getDescription().getString().toLowerCase(java.util.Locale.ROOT)));
        return (today.isEmpty() ? "nothing baked yet today" : "baked " + today + " today") + "; the stores have " + String.join(", ", stock);
    }

    // ------------------------------------------------------------------ tests

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

    /** Tests: how many of this the bakery means to keep by. */
    public static int wantedForTests(UUID village, Item what, long day) {
        return wanted(village, what, day);
    }
}
