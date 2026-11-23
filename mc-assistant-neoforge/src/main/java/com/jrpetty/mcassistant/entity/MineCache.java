package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [econ32c] The landings' cache: food and torches in the chest of each level's landing, sent down the cage once a day by a
 * hauler of the town's, so a miner takes its breather at the landing (MineWorks.Task's WorksGoal) and goes back to the face
 * instead of climbing out to eat at home and fetch torches.
 *
 * <p>The landing's chest was furnished with the landing and stood empty. Now, while the works have a cage and the town a
 * hauler, every morning the hauler sends down what each landing's chest lacks of {@link #FOOD} meals and {@link #TORCHES}
 * torches (the stores' own; nothing if the stores have none to spare). A miner at the end of a stint, by daylight, with
 * room in its pack and its pick good, walks to its level's landing and rests there a few seconds, taking a few meals and a
 * handful of torches from the chest, and starts another stint: twice a shift at most.
 */
public final class MineCache {

    private MineCache() {}

    /** Meals and torches each landing's chest is kept up to. */
    static final int FOOD = 12, TORCHES = 16;
    /** A breather's take. */
    static final int TAKE_FOOD = 4, TAKE_TORCHES = 8;

    static final Predicate<ItemStack> MEAL = s -> s.is(Items.BREAD) || s.is(Items.BAKED_POTATO) || s.is(Items.COOKED_BEEF)
        || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_MUTTON) || s.is(Items.COOKED_CHICKEN) || s.is(Items.COOKED_COD)
        || s.is(Items.COOKED_SALMON) || s.is(Items.CARROT) || s.is(Items.APPLE) || s.is(Items.COOKED_RABBIT);
    static final Predicate<ItemStack> TORCH = s -> s.is(Items.TORCH);

    private static final Map<UUID, Long> SENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SENT_COUNT = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SENT.clear();
        SENT_COUNT.clear();
    }

    /** The chest of a stop's landing (MineWorks.furnish), or null for the pithead. */
    @Nullable
    static BlockPos chest(MineWorks.Works w, MineLift.Stop s) {
        if (s.index() == 0) return null;
        int[] o = MineWorks.diag((s.index() / 4) % 4);
        return new BlockPos(w.sx + 8 * o[0], s.y(), w.sz + 8 * o[1]);
    }

    /** Once a day, by the works' round (MineLift.upkeep): the landings' chests topped up by a hauler, down the cage. */
    static void restock(ServerLevel level, MineWorks.Works w, java.util.List<MineLift.Stop> stops, boolean now) {
        long day = level.getDayTime() / 24000L;
        Long last = SENT.get(w.village);
        if (!now && last != null && last == day) return;
        Villages.Village v = Villages.get(w.village);
        if (v == null) return;
        VillageFolkEntity hauler = null;
        for (AssistantEntity a : Villages.folkOf(w.village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isBaby() && f.stationTask() == AssistantEntity.StationTask.HAUL) { hauler = f; break; }
        }
        if (hauler == null) return;
        SENT.put(w.village, day);
        int meals = 0, torches = 0;
        for (MineLift.Stop s : stops) {
            BlockPos at = chest(w, s);
            if (at == null || !(level.getBlockEntity(at) instanceof Container c)) continue;
            meals += topUp(level, v, c, MEAL, FOOD);
            torches += topUp(level, v, c, TORCH, TORCHES);
        }
        if (meals + torches == 0) return;
        SENT_COUNT.merge(w.village, meals + torches, Integer::sum);
        hauler.brain("sent " + meals + " meals and " + torches + " torches down the cage to the landings");
        com.mojang.logging.LogUtils.getLogger().info("[MCA-LIFT] {}: {} sent {} meals and {} torches down the cage to the landings",
            Villages.name(w.village), hauler.displayNameCap(), meals, torches);
    }

    private static int topUp(ServerLevel level, Villages.Village v, Container c, Predicate<ItemStack> what, int upTo) {
        int have = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) have += c.getItem(i).getCount();
        int sent = 0;
        while (have + sent < upTo) {
            ItemStack one = Crafts.takeOne(level, v, what);
            if (one.isEmpty()) break;
            if (!put(c, one)) { Crafts.store(level, v, one); break; }
            sent++;
        }
        return sent;
    }

    private static boolean put(Container c, ItemStack one) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, one) && s.getCount() < s.getMaxStackSize()) { s.grow(1); c.setChanged(); return true; }
        }
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (c.getItem(i).isEmpty()) { c.setItem(i, one); c.setChanged(); return true; }
        }
        return false;
    }

    /** The stop of the landing this folk is on (its level's, within three blocks up or down), with a cache, or null. */
    @Nullable
    public static MineLift.Stop near(ServerLevel level, AssistantEntity a) {
        if (!(a instanceof VillageFolkEntity f) || f.ownerId() == null) return null;
        MineWorks.Works w = MineWorks.works(level, f.ownerId());
        if (w == null) return null;
        for (MineLift.Stop s : MineLift.stops(f.ownerId())) {
            BlockPos at = chest(w, s);
            if (at == null || Math.abs(a.getBlockY() - s.y()) > 3) continue;
            if (level.getBlockEntity(at) instanceof Container c && count(c, MEAL) + count(c, TORCH) > 0) return s;
        }
        return null;
    }

    /** A breather at the landing: a few meals and torches out of its chest into the pack. Returns what was taken, in words. */
    public static String take(ServerLevel level, AssistantEntity a, MineLift.Stop s) {
        if (!(a instanceof VillageFolkEntity f) || f.ownerId() == null) return "";
        MineWorks.Works w = MineWorks.works(level, f.ownerId());
        BlockPos at = w == null ? null : chest(w, s);
        if (at == null || !(level.getBlockEntity(at) instanceof Container c)) return "";
        int meals = Math.max(0, TAKE_FOOD - a.countCarried(MEAL)), torches = Math.max(0, TAKE_TORCHES - a.countCarried(TORCH));
        int m = move(c, a, MEAL, meals), t = move(c, a, TORCH, torches);
        TAKEN.merge(f.ownerId(), m + t, Integer::sum);
        return m + " meals and " + t + " torches";
    }

    private static final Map<UUID, Integer> TAKEN = new ConcurrentHashMap<>();

    private static int move(Container c, AssistantEntity a, Predicate<ItemStack> what, int n) {
        int moved = 0;
        for (int i = 0; i < c.getContainerSize() && moved < n; i++) {
            ItemStack s = c.getItem(i);
            if (!what.test(s)) continue;
            int k = Math.min(n - moved, s.getCount());
            ItemStack one = s.split(k);
            ItemStack left = a.insertItem(one);
            if (!left.isEmpty()) { s.grow(left.getCount()); k -= left.getCount(); }
            moved += k;
            c.setChanged();
            if (!left.isEmpty()) break;
        }
        return moved;
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** For the books: sent down and taken. */
    @Nullable
    public static String report(UUID village) {
        int sent = SENT_COUNT.getOrDefault(village, 0), taken = TAKEN.getOrDefault(village, 0);
        if (sent == 0 && taken == 0) return null;
        return "The landings' cache: " + sent + " meals and torches sent down the cage, " + taken + " taken at the landings.";
    }

    /** Tests: the restock now, whatever the day. */
    public static void restockForTests(ServerLevel level, UUID village) {
        MineWorks.Works w = MineWorks.works(level, village);
        if (w != null) restock(level, w, MineLift.stops(village), true);
    }

    public static int countForTests(ServerLevel level, UUID village, MineLift.Stop s, boolean food) {
        MineWorks.Works w = MineWorks.works(level, village);
        BlockPos at = w == null ? null : chest(w, s);
        return at != null && level.getBlockEntity(at) instanceof Container c ? count(c, food ? MEAL : TORCH) : -1;
    }
}
