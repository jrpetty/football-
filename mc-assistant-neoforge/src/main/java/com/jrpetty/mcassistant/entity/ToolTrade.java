package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ32] The working tools traded between towns: picks, axes and hoes.
 *
 * <p>Tools were "crafts" in the towns' trade books, and the crafts were never carried: a town short of iron made its picks
 * of stone for ever (half the wear), and a town with iron to spare never sold a tool, for no tool ever left a town that
 * wanted anything at all (Budget.spare). Now tools are a ware of their own (TradeBook.Ware.TOOLS):
 * <ul>
 * <li><b>Short.</b> A town is short of tools by what its rack lacks of the spares its miners, woodcutters and farmers wear
 *     through (Toolrack.wanted), where it cannot make them in iron itself just now (no iron to spare, or still putting it
 *     by for its age), and by every hand without the tool of its trade.</li>
 * <li><b>To spare.</b> A town keeps its rack's spares; past them, its good iron (or diamond) tools are for sale. Its smith,
 *     with iron well past what the town keeps and its age asking none, makes a few more iron picks and axes than the rack
 *     wants ({@link #TRADE_STOCK} of each) for the caravans ({@link #forge}).</li>
 * <li>The envoys deal in them like any other ware (TradeTalks): a lot is one tool, and the caravan carries only good ones
 *     (half their wear left or more), never a hand's worn old one.</li>
 * </ul>
 */
public final class ToolTrade {

    private ToolTrade() {}

    /** The kinds traded. */
    static final Toolrack.Tool[] KINDS = { Toolrack.Tool.PICKAXE, Toolrack.Tool.AXE, Toolrack.Tool.HOE };
    /** Iron tools of each kind the smith keeps past the rack's spares, for sale, when the town has iron to spare. */
    public static final int TRADE_STOCK = 3;
    /** Iron past what the smith keeps (Crafts.IRON_KEPT) before it makes tools for sale. */
    static final int IRON_FOR_TRADE = 40;
    /** How often a town's smith looks at its trade stock (ticks). */
    static final long EVERY = 1200L;

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> MADE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        MADE.clear();
    }

    /** Tools made for sale, by town (the books, the tests). */
    public static int madeForTrade(UUID village) {
        return MADE.getOrDefault(village, 0);
    }

    /** The kind of traded tool this is (a pick, an axe, a hoe of stone or better), or null. */
    @Nullable
    static Toolrack.Tool kind(ItemStack s) {
        if (s.isEmpty() || Toolrack.metal(s) < 2) return null;
        for (Toolrack.Tool t : KINDS) if (Toolrack.is(t, s)) return t;
        return null;
    }

    /** Is this a traded tool (stone or better)? */
    public static boolean isTool(ItemStack s) {
        return kind(s) != null;
    }

    /** Is this a tool good enough to load on a caravan: iron or better, with half its wear left? */
    public static boolean carried(ItemStack s) {
        return isTool(s) && Toolrack.metal(s) >= 3 && !s.isEnchanted() && Toolrack.left(s) >= Toolrack.SPARE;
    }

    /** What the town keeps of its tools before it parts with any: its rack's spares of each kind. */
    static int keep(UUID village) {
        int n = 0;
        for (Toolrack.Tool t : KINDS) n += Toolrack.wanted(village, t);
        return n;
    }

    /** Good tools of this item in the stores (half their wear left or more, not enchanted). */
    static int good(ServerLevel level, UUID village, Item item) {
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.is(item) && carried(s)) n += s.getCount();
            }
        }
        return n;
    }

    /** Copies of so many of the good tools of this item in the stores, as they are (each with its maker's mark): what a
     *  shipment takes out (Shipments.dispatch takes what matches exactly). */
    static java.util.List<ItemStack> samples(ServerLevel level, UUID village, Item item, int n) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && out.size() < n; i++) {
                ItemStack s = c.getItem(i);
                if (s.is(item) && carried(s)) out.add(s.copyWithCount(1));
            }
        }
        return out;
    }

    /** How many of this tool the town can sell: what it holds of its kind past the rack's spares, of this item's good ones. */
    public static int spare(ServerLevel level, UUID village, ItemStack sample) {
        Toolrack.Tool t = kind(sample);
        if (t == null || Toolrack.metal(sample) < 3) return 0;
        int onRack = Toolrack.onRack(level, village, t);
        int over = onRack - Toolrack.wanted(village, t);
        if (over <= 0) return 0;
        return Math.min(over, good(level, village, sample.getItem()));
    }

    /**
     * How short of tools the town is: its rack's want of each kind it cannot make in iron itself just now, and every hand
     * without the tool of its trade (counted by the caller).
     */
    static int want(ServerLevel level, UUID village, int handsWithout) {
        Villages.Village v = Villages.get(village);
        if (v == null) return handsWithout;
        int n = handsWithout;
        for (Toolrack.Tool t : KINDS) {
            int gap = Toolrack.wanted(village, t) - Toolrack.onRack(level, village, t);
            if (gap <= 0) continue;
            Item can = Toolrack.tierFor(level, v, t);
            boolean iron = can != null && Toolrack.metal(new ItemStack(can)) >= 3;
            if (!iron) n += gap;
        }
        return n;
    }

    /**
     * The smith's tools for sale: with the town's iron well past what it keeps and its age asking none, an iron pick or axe
     * made for the caravans while the rack holds fewer than its spares and {@link #TRADE_STOCK} more. Once a minute a town.
     * Returns what it made, in words, or null.
     */
    @Nullable
    public static String forge(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() != AssistantEntity.StationTask.SMITH || f.isBaby() || f.isSleeping()) return null;
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || Villages.ageOf(id).ordinal() < Villages.Age.IRON.ordinal()) return null;
        long now = level.getGameTime();
        Long last = LOOKED.get(id);
        if (last != null && now - last < EVERY && now >= last) return null;
        LOOKED.put(id, now);
        if (Crafts.savingIron(level, v)) return null;
        int iron = Market.stock(level, id, Toolrack.IRON);
        for (Toolrack.Tool t : new Toolrack.Tool[]{ Toolrack.Tool.PICKAXE, Toolrack.Tool.AXE }) {
            if (iron < t.head + Crafts.IRON_KEPT + IRON_FOR_TRADE) return null;
            if (Toolrack.onRack(level, id, t) >= Toolrack.wanted(id, t) + TRADE_STOCK) continue;
            Item item = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace("iron_" + t.suffix.substring(1)));
            String made = Toolrack.make(level, v, f, t, item);
            if (made == null) return null;
            MADE.merge(id, 1, Integer::sum);
            f.brain("made " + made + " to sell to the other towns");
            return made;
        }
        return null;
    }
}
