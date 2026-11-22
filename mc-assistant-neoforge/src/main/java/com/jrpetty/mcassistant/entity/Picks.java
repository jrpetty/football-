package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [fixes14] The miners' picks kept up: the tool chain of a town's mine.
 *
 * <p>A stone pick is worn through in a hundred and thirty blocks, and a miner cuts that in half a shift. The rack kept a
 * spare for every four miners whatever they wore out, so in a real town the picks ran out in the first morning, miners
 * walked up out of the works "for another" and stood at the heart till somebody at the stores made one, and the mine was
 * a day and more sinking its first turn of the shaft. Now:
 * <ul>
 * <li><b>Kept to the need.</b> The rack keeps a spare pick for every four miners at the least, and as many as half a
 *     day wears out where they wear them out faster (the last day's handing out), twelve at most.</li>
 * <li><b>Iron once there is iron.</b> Picks are made of iron as soon as the stores hold the bars for them past what the
 *     smith keeps back (and, while the town puts iron by for its age, past a good deal more): twice the wear of stone.</li>
 * <li><b>Mended.</b> A worn pick handed in is not thrown away: two of one metal are mended into one at the rack, as a
 *     smith mends them, before a new one is made.</li>
 * <li><b>Changed before a shift.</b> A miner setting out for a shift with its pick a third worn or less takes a fresh
 *     one off the rack and hands the worn one in to be mended; one with no spare and a pick half gone takes a spare along.
 *     (Before, a pick was changed only once it was a fifth from breaking or broken, mid-shift, at the bottom of the works.)</li>
 * <li><b>Made first.</b> While the rack is short of picks the town's smith makes them before anything else at its forge,
 *     wherever the stores' hands are (Toolrack.restock did it only for a hand at the stores with a moment to spare).</li>
 * </ul>
 */
public final class Picks {

    private Picks() {}

    /** The most picks the rack keeps. */
    static final int MOST = 12;
    /** A pick with this much of its wear left or less is changed before a shift (percent). */
    public static final int CHANGE_AT = 34;
    /** A pick with this much or less and no spare in the pack: a spare taken along. */
    static final int SPARE_AT = 55;

    /** When each pick was handed out, by town, over the last day (the wear rate). */
    private static final Map<UUID, ArrayDeque<Long>> ISSUED = new ConcurrentHashMap<>();
    /** When each town's smith last looked to the rack. */
    private static final Map<UUID, Long> SMITH = new ConcurrentHashMap<>();
    /** Mended, by town (the books and the tests). */
    private static final Map<UUID, Integer> MENDED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ISSUED.clear();
        SMITH.clear();
        MENDED.clear();
    }

    public static void issuedForTests(UUID village, long now) {
        issued(village, now);
    }

    public static void emptyRackForTests(ServerLevel level, Villages.Village v) {
        while (Crafts.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.PICKAXES), 1)) { }
    }

    public static void resetSmithForTests() {
        SMITH.clear();
    }

    public static int mended(UUID village) {
        return MENDED.getOrDefault(village, 0);
    }

    static void issued(UUID village, long now) {
        NOW = now;
        ArrayDeque<Long> q = ISSUED.computeIfAbsent(village, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && (now - q.peekFirst() > 24000L || q.peekFirst() > now)) q.pollFirst();
        }
    }

    /** Picks handed out over the last day. */
    public static int lastDay(UUID village, long now) {
        ArrayDeque<Long> q = ISSUED.get(village);
        if (q == null) return 0;
        synchronized (q) {
            while (!q.isEmpty() && (now - q.peekFirst() > 24000L || q.peekFirst() > now)) q.pollFirst();
            return q.size();
        }
    }

    /** The spares the rack keeps: one for every four miners at the least, and as many as half a day wears out (the last
     *  day's handing out), twelve at most. */
    static int wanted(UUID village, int miners) {
        long now = NOW;
        int byWear = (lastDay(village, now) + 1) / 2;
        return Math.min(MOST, Math.max(Math.max(1, (miners + 3) / 4), byWear));
    }

    /** The game's time when the picks were last looked at (the wear rate's clock, where no level is to hand). */
    private static volatile long NOW;

    /** Iron for a pick: past the smith's bars and eight besides, or (the town putting iron by) past three dozen. */
    static boolean ironFor(int iron, boolean ironFree, int head) {
        return ironFree ? iron >= head + Crafts.IRON_KEPT + 8 : iron >= head + Crafts.IRON_KEPT + 24;
    }

    /**
     * Two worn picks of one metal in the stores mended into one (the wear of both and a twentieth besides, as the
     * crafting grid mends them). Returns it in words, or null if there were not two to mend.
     */
    @Nullable
    static String mend(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity maker) {
        for (net.minecraft.world.item.Item kind : new net.minecraft.world.item.Item[]{ Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.DIAMOND_PICKAXE }) {
            Container ca = null, cb = null;
            int sa = -1, sb = -1;
            for (BlockPos p : Villages.storeChests(level, v.id())) {
                if (!(level.getBlockEntity(p) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (!s.is(kind) || !s.isDamaged() || Toolrack.left(s) >= Toolrack.SPARE) continue;
                    if (ca == null) { ca = c; sa = i; }
                    else if (cb == null) { cb = c; sb = i; }
                }
                if (cb != null) break;
            }
            if (cb == null) continue;
            ItemStack a = ca.getItem(sa), b = cb.getItem(sb);
            int max = a.getMaxDamage();
            int left = (max - a.getDamageValue()) + (max - b.getDamageValue()) + max / 20;
            ItemStack made = new ItemStack(kind);
            made.setDamageValue(Math.max(0, max - Math.min(max, left)));
            ca.setItem(sa, ItemStack.EMPTY);
            cb.setItem(sb, ItemStack.EMPTY);
            ca.setChanged();
            cb.setChanged();
            Crafts.store(level, v, made);
            MENDED.merge(v.id(), 1, Integer::sum);
            if (maker != null) maker.brain("mended two worn picks into one for the rack");
            return "a mended " + (kind == Items.IRON_PICKAXE ? "iron" : kind == Items.STONE_PICKAXE ? "stone" : "diamond") + " pick";
        }
        return null;
    }

    /**
     * A miner setting out for a shift: a pick a third worn or less changed for a fresh one off the rack (the worn one handed
     * in to be mended); one with no spare and a pick half gone takes a spare along. True if it changed anything.
     */
    public static boolean beforeShift(ServerLevel level, VillageFolkEntity f) {
        NOW = level.getGameTime();
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        if (DeepPicks.beforeShift(level, f)) return true;                 // [econ32] an iron pick for the diamonds
        ItemStack best = ItemStack.EMPTY;
        int picks = 0;
        for (ItemStack s : f.getInventoryItems()) {
            if (!Toolrack.is(Toolrack.Tool.PICKAXE, s) || !s.isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) continue;
            picks++;
            if (best.isEmpty() || Toolrack.left(s) > Toolrack.left(best)) best = s;
        }
        ItemStack held = f.getMainHandItem();
        if (Toolrack.is(Toolrack.Tool.PICKAXE, held) && held.isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) {
            picks++;
            if (best.isEmpty() || Toolrack.left(held) > Toolrack.left(best)) best = held;
        }
        int left = best.isEmpty() ? 0 : Toolrack.left(best);
        boolean change = left <= CHANGE_AT, spare = picks < 2 && left <= SPARE_AT;
        if (!change && !spare) return false;
        if (Toolrack.onRack(level, id, Toolrack.Tool.PICKAXE) <= 0) return false;
        ItemStack got = Purchases.tool(level, v, f, Toolrack.Tool.PICKAXE);
        if (got.isEmpty()) return false;
        final ItemStack worn = best;
        if (change && !worn.isEmpty()) {
            // The worn one handed in to be mended (a broken stub is not: there is nothing left of it).
            ItemStack old = worn.copy();
            if (worn == held) f.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            else f.removeMatching(s -> s == worn, 1);
            if (Toolrack.left(old) > 0) Crafts.store(level, v, old);
            f.brain("handed in a worn pick (" + Toolrack.left(old) + "% left) and took a fresh one for the shift");
        } else {
            f.brain("took a spare pick along for the shift");
        }
        return true;
    }

    /**
     * A miner with no pick it can cut stone with (worn through in the works, or none to start with): a spare off the
     * rack, sent down to it as a courier brings one out; and the rack empty, one made there and then of the stores'
     * stone and timber (or iron) and sent. True if it has one now. (Miners whose picks broke at the bottom of the works
     * stood there for half a day: the walk up for another went nowhere, and the rack was empty when they got there.)
     */
    public static boolean sendDown(ServerLevel level, VillageFolkEntity f) {
        NOW = level.getGameTime();
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        ItemStack got = Purchases.tool(level, v, f, Toolrack.Tool.PICKAXE);
        if (got.isEmpty()) {
            if (!Storehouses.stands(id) && Villages.storehouseFirst(id, level.getGameTime())) return false;
            net.minecraft.world.item.Item item = Toolrack.tierFor(level, v, Toolrack.Tool.PICKAXE);
            if (item == null || Toolrack.make(level, v, null, Toolrack.Tool.PICKAXE, item) == null) return false;
            got = Purchases.tool(level, v, f, Toolrack.Tool.PICKAXE);
            if (got.isEmpty()) return false;
            f.brain("a pick made at the stores and sent down: mine was worn through");
        } else {
            f.brain("a spare pick sent down off the rack: mine was worn through");
        }
        f.equipBestTool(Blocks.STONE.defaultBlockState());
        return f.getMainHandItem().isCorrectToolForDrops(Blocks.STONE.defaultBlockState());
    }

    /**
     * The town's smith, while the rack is short of picks: two made at its forge before anything else it does, once in ten
     * seconds a town. Returns what it made, in words, or null.
     */
    @Nullable
    public static String smith(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() != StationTask.SMITH || f.isBaby() || f.isSleeping()) return null;
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return null;
        long now = level.getGameTime();
        NOW = now;
        Long last = SMITH.get(id);
        if (last != null && now - last < Toolrack.EVERY && now >= last) return null;
        SMITH.put(id, now);
        int want = Toolrack.wanted(id, Toolrack.Tool.PICKAXE);
        if (want <= 0 || Toolrack.onRack(level, id, Toolrack.Tool.PICKAXE) >= want) return DeepPicks.smith(f, level);   // [econ32] an iron one for the diamonds
        return Toolrack.restockPicks(level, v, f);
    }
}
