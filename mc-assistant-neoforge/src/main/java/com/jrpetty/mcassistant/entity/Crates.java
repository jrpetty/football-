package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.ShippingCrateBlock;
import com.jrpetty.mcassistant.block.ShippingCrateBlockEntity;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [workitems] The shipping crates at work: nine stacks of the town's goods in a hand's one slot.
 * <ul>
 * <li><b>The couriers</b> (Couriers.fetch): each carries two empty crates (WorkTools.kitUp). At a production chest it fills
 *     its pack as ever, then packs what is left in the chest into its crates, nine stacks a crate; at the storehouse the
 *     stores unpack them with the rest of its load (WorkTools.unpackInto), and the crates go out empty again. A run that
 *     carried two hundred and fifty-six things carries well over a thousand.</li>
 * <li><b>The caravans</b> (Caravans.setOut, arrive): the carrier takes up to three empty crates out of the stores and packs
 *     them with the town's surplus over the four loose stacks it always took, as much again as its pack will take at the
 *     far end; there the crates are unpacked onto its back and sold off it as ever, and they come home with it (the home
 *     load packed into them), back into the stores.</li>
 * </ul>
 * Shown on a courier's "doing" line and card, in the caravan's talk and the chronicle, and on /village items work.
 */
public final class Crates {

    private Crates() {}

    /** A courier's crates; a caravan's. */
    public static final int KIT = 2, CARAVAN = 3;

    /** Each village's crates unpacked at its stores: {crates, stacks}. */
    private static final Map<UUID, int[]> UNPACKED = new ConcurrentHashMap<>();
    /** What each caravan carrier packed in its crates when it set out: stacks. */
    private static final Map<UUID, Integer> CARAVAN_PACKED = new ConcurrentHashMap<>();

    static void resetForTests() {
        UNPACKED.clear();
        CARAVAN_PACKED.clear();
    }

    public static boolean isCrate(ItemStack s) {
        return s.is(WorkItems.SHIPPING_CRATE_ITEM.get());
    }

    /** Does this town send caravans (a colony of its own, or a mother it trades back to)? */
    static boolean caravans(UUID village) {
        Map<UUID, UUID> links = Ledger.links();
        return links.containsValue(village) || links.containsKey(village);
    }

    /** Stacks a crate item has room for. */
    static int room(ItemStack crate) {
        return ShippingCrateBlockEntity.SLOTS - ShippingCrateBlock.contents(crate).size();
    }

    /** Goods into a crate item, merged onto its stacks first: what would not go is left in {@code s} (shrunk). */
    static int into(ItemStack crate, ItemStack s) {
        List<ItemStack> in = ShippingCrateBlock.contents(crate);
        int moved = 0;
        for (ItemStack c : in) {
            if (s.isEmpty()) break;
            if (!ItemStack.isSameItemSameComponents(c, s)) continue;
            int k = Math.min(s.getCount(), c.getMaxStackSize() - c.getCount());
            if (k <= 0) continue;
            c.grow(k);
            s.shrink(k);
            moved += k;
        }
        while (!s.isEmpty() && in.size() < ShippingCrateBlockEntity.SLOTS) {
            int k = Math.min(s.getCount(), s.getMaxStackSize());
            in.add(s.copyWithCount(k));
            s.shrink(k);
            moved += k;
        }
        ShippingCrateBlock.fill(crate, in);
        return moved;
    }

    // ------------------------------------------------------------------ the couriers

    /**
     * A courier at a chest with its pack loaded (Couriers.fetch): what is left in the chest worth carrying packed into
     * its crates, whole stacks, nine to a crate. Never the furnace's ore and fuel (only what it has made), nor the
     * field's carrots and potatoes (its seed: they are left loose, for the pack's own rule). How many things it packed.
     */
    public static int packFrom(VillageFolkEntity c, BlockPos chest) {
        if (!(c.level().getBlockEntity(chest) instanceof Container box)) return 0;
        boolean furnace = box instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
        int moved = 0, crates = 0;
        for (ItemStack crate : c.getInventoryItems()) {
            if (!isCrate(crate) || room(crate) <= 0) continue;
            int before = moved;
            for (int i = 0; i < box.getContainerSize() && room(crate) > 0; i++) {
                if (furnace && i != 2) continue;
                ItemStack s = box.getItem(i);
                if (s.isEmpty() || AssistantEntity.haulWeight(s) <= 0 || isCrate(s) || s.is(Items.CARROT) || s.is(Items.POTATO)) continue;
                ItemStack lot = s.copy();
                int k = into(crate, lot);
                if (k <= 0) continue;
                s.shrink(k);
                box.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
                moved += k;
            }
            if (moved > before) crates++;
        }
        if (moved > 0) {
            box.setChanged();
            c.brain("packed " + Storekeeping.words(moved) + " into " + (crates == 1 ? "a crate" : crates + " crates"));
        }
        return moved;
    }

    /** A courier's crates unpacked at the stores (WorkTools.unpackInto): counted for the books. */
    static void unpacked(UUID village, int stacks) {
        int[] u = UNPACKED.computeIfAbsent(village, k -> new int[2]);
        u[0]++;
        u[1] += Math.max(0, stacks);
    }

    /** "with two crates packed (18 stacks)" for its doing line, or empty. */
    public static String doingWords(VillageFolkEntity f) {
        int crates = 0, stacks = 0;
        for (ItemStack s : f.getInventoryItems()) {
            if (!isCrate(s)) continue;
            int n = ShippingCrateBlock.contents(s).size();
            if (n > 0) { crates++; stacks += n; }
        }
        if (crates == 0) return "";
        return ", " + (crates == 1 ? "a crate" : crates + " crates") + " packed (" + stacks + (stacks == 1 ? " stack" : " stacks") + ")";
    }

    /** Its crates, for its card: "2 shipping crates (one packed: 9 stacks)". */
    static String cardWords(VillageFolkEntity f) {
        int all = 0, packed = 0, stacks = 0;
        for (ItemStack s : f.getInventoryItems()) {
            if (!isCrate(s)) continue;
            all++;
            int n = ShippingCrateBlock.contents(s).size();
            if (n > 0) { packed++; stacks += n; }
        }
        if (all == 0) return "";
        String w = all == 1 ? "a shipping crate" : all + " shipping crates";
        return packed == 0 ? w + ", empty" : w + " (" + packed + " packed: " + stacks + (stacks == 1 ? " stack" : " stacks") + ")";
    }

    // ------------------------------------------------------------------ the caravans

    /**
     * A caravan setting out (Caravans.setOut, its loose load on the carrier's back): up to three empty crates out of
     * the stores, packed at the stores with the town's surplus over that load (the far town's wants first, as the loose
     * load is chosen; never past what the stores keep in plenty), no more than its pack will hold loose at the far end.
     * The stacks packed.
     */
    public static int packCaravan(ServerLevel level, Villages.Village from, UUID to, VillageFolkEntity carrier) {
        int free = 0;
        for (ItemStack s : carrier.getInventoryItems()) if (s.isEmpty()) free++;
        int crates = Math.min(CARAVAN, Math.max(0, free - 1));
        List<ItemStack> boxes = new ArrayList<>();
        for (int i = 0; i < crates; i++) {
            ItemStack c = Crafts.takeOne(level, from, s -> isCrate(s) && ShippingCrateBlock.contents(s).isEmpty());
            if (c.isEmpty()) break;
            boxes.add(c);
        }
        if (boxes.isEmpty()) return 0;
        // Room for it all loose at the far end: the pack's free slots, less the crates' own.
        int most = Math.min(boxes.size() * ShippingCrateBlockEntity.SLOTS, free - boxes.size());
        java.util.Set<Villages.Task> wanted = java.util.EnumSet.noneOf(Villages.Task.class);
        for (Villages.Need n : Villages.needs(level, to)) wanted.add(n.task());
        List<Market.Good> order = new ArrayList<>();
        for (Market.Good g : Market.GOODS) if (wanted.contains(g.need())) order.add(g);
        for (Market.Good g : Market.GOODS) if (!order.contains(g) && g.need() != Villages.Task.NONE) order.add(g);
        int stacks = 0, box = 0;
        for (Market.Good g : order) {
            if (stacks >= most || box >= boxes.size()) break;
            int have = Market.stock(level, from.id(), g.what());
            int plenty = g.bundle() * 4;
            int spare = Math.min(64 * 3, have - plenty);
            if (spare < g.bundle()) continue;
            for (ItemStack lot : Caravans.takeOut(level, from, g.what(), spare)) {
                ItemStack rest = lot.copy();
                while (!rest.isEmpty() && box < boxes.size() && stacks < most) {
                    int before = ShippingCrateBlock.contents(boxes.get(box)).size();
                    into(boxes.get(box), rest);
                    stacks += ShippingCrateBlock.contents(boxes.get(box)).size() - before;
                    if (!rest.isEmpty()) box++;
                }
                if (!rest.isEmpty()) Crafts.store(level, from, rest);
            }
        }
        int packed = 0;
        for (ItemStack b : boxes) {
            if (ShippingCrateBlock.contents(b).isEmpty()) {
                Crafts.store(level, from, b);
                continue;
            }
            packed++;
            ItemStack left = carrier.insertGiven(b);
            if (!left.isEmpty()) {
                for (ItemStack s : ShippingCrateBlock.contents(left)) Crafts.store(level, from, s);
                ShippingCrateBlock.fill(left, List.of());
                Crafts.store(level, from, left);
            }
        }
        if (packed == 0) return 0;
        CARAVAN_PACKED.put(carrier.getUUID(), stacks);
        bump(from.id(), "work.caravan.crates", packed);
        bump(from.id(), "work.caravan.stacks", stacks);
        Villages.tell(from.id(), level.getDayTime() / 24000L, "the caravan for " + Villages.name(to) + " took " + packed
            + (packed == 1 ? " shipping crate" : " shipping crates") + " besides its load: " + stacks + " stacks more of the town's surplus");
        return stacks;
    }

    /**
     * A caravan at the end of a leg (Caravans.arrive): its crates unpacked onto its back, to be sold off it there as its
     * loose load is; home again, the empty crates back into the stores.
     */
    public static void unpackCaravan(ServerLevel level, VillageFolkEntity f, Caravans.Trip t) {
        var pack = f.getInventoryItems();
        for (ItemStack crate : pack) {
            if (!isCrate(crate)) continue;
            List<ItemStack> in = ShippingCrateBlock.contents(crate);
            if (in.isEmpty()) continue;
            List<ItemStack> kept = new ArrayList<>();
            for (ItemStack s : in) {
                ItemStack left = f.insertGiven(s);
                if (!left.isEmpty()) kept.add(left);
            }
            ShippingCrateBlock.fill(crate, kept);
        }
        if (t.homeward()) {
            Villages.Village home = Villages.get(t.destination());
            if (home == null) return;
            for (int i = 0; i < pack.size(); i++) {
                ItemStack s = pack.get(i);
                if (!isCrate(s) || !ShippingCrateBlock.contents(s).isEmpty()) continue;
                ItemStack left = Market.intoStores(level, home.id(), s.copy());
                pack.set(i, left);
            }
            CARAVAN_PACKED.remove(f.getUUID());
        }
    }

    /** The goods for home packed back into the caravan's crates, off its back (Caravans.arrive, after it has bought them). */
    public static void packForHome(VillageFolkEntity f) {
        int stacks = 0;
        var pack = f.getInventoryItems();
        for (ItemStack crate : pack) {
            if (!isCrate(crate)) continue;
            for (int i = 0; i < pack.size() && room(crate) > 0; i++) {
                ItemStack s = pack.get(i);
                if (s.isEmpty() || isCrate(s) || Market.goodFor(s) == null) continue;
                int before = ShippingCrateBlock.contents(crate).size();
                into(crate, s);
                stacks += ShippingCrateBlock.contents(crate).size() - before;
                if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
            }
        }
        if (stacks > 0) CARAVAN_PACKED.put(f.getUUID(), stacks);
    }

    /** " — three crates packed, 22 stacks", for a carrier's talk on the road, or empty. */
    public static String caravanWords(VillageFolkEntity f) {
        int crates = 0;
        for (ItemStack s : f.getInventoryItems()) if (isCrate(s) && !ShippingCrateBlock.contents(s).isEmpty()) crates++;
        Integer stacks = CARAVAN_PACKED.get(f.getUUID());
        if (crates == 0 || stacks == null) return "";
        return " I've " + (crates == 1 ? "a crate" : crates + " crates") + " packed besides — " + stacks + " stacks of goods.";
    }

    private static void bump(UUID village, String key, int by) {
        Ledger.note(village, key, String.valueOf(WorkTools.count(village, key) + by));
    }

    /** The crates' lines for /village items work. */
    static List<String> report(UUID village) {
        List<String> out = new ArrayList<>();
        int[] u = UNPACKED.get(village);
        if (u != null && u[0] > 0) out.add("Shipping crates: " + u[0] + " unpacked at the stores, " + u[1] + " stacks carried in them.");
        int cc = WorkTools.count(village, "work.caravan.crates");
        if (cc > 0) out.add("Caravans: " + cc + " crates sent out packed, " + WorkTools.count(village, "work.caravan.stacks") + " stacks more than they could carry loose.");
        return out;
    }

    /** Tests: {crates unpacked at the stores, stacks in them} for this village. */
    public static int[] unpackedForTests(UUID village) {
        int[] u = UNPACKED.get(village);
        return u == null ? new int[2] : u.clone();
    }
}
