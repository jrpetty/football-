package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [wpb] Signed contracts between players, with escrow held by the town (Town Hall or bank: the treasury's books keep it
 * apart, and the town's coin census counts it). Two kinds:
 * <ul>
 * <li><b>Goods for coin.</b> The seller proposes: so many of a thing (the kind in their hand) by a day, for a price, with a
 *     penalty. Proposing puts the seller's penalty bond in escrow; the buyer signs, putting the price and its own bond in
 *     escrow. The seller hands the goods in on the contract's screen before the deadline (the town checks the kind and
 *     the count): the goods go to the buyer, the price to the seller, both bonds back.</li>
 * <li><b>A service for coin</b> (building a house, a season's work): described in words. Both press Done and the escrow
 *     releases; either may dispute instead, and it goes to the town's small-claims hearing (WP-E's seam:
 *     {@link #claimHook}), the escrow held till then.</li>
 * </ul>
 * At the deadline, undelivered: the buyer has the price back and the seller's bond as the penalty; the seller's bond is lost.
 * A service not confirmed by either side by then goes to the hearing. Both players see their contracts on the Contracts page
 * and in their planners.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Contracts extends SavedData {

    private static final String ID = "mc_assistant_wpb_contracts";
    @Nullable private static Contracts loose;

    /** A contract. */
    public static final class Deal {
        public int id;
        public UUID town = new UUID(0, 0), seller = new UUID(0, 0), buyer = new UUID(0, 0);
        public String sellerName = "", buyerName = "", kind = "goods", item = "", service = "";
        public int qty, price, penalty;
        public long deadline;
        /** DRAFT, SIGNED, DONE, DISPUTED, LAPSED, REFUSED. */
        public String state = "DRAFT";
        /** Coins held: the price, the seller's bond, the buyer's bond. */
        public int heldPrice, heldSellerBond, heldBuyerBond;
        public boolean sellerDone, buyerDone;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("id", id);
            t.putUUID("town", town);
            t.putUUID("seller", seller);
            t.putUUID("buyer", buyer);
            t.putString("sn", sellerName);
            t.putString("bn", buyerName);
            t.putString("kind", kind);
            t.putString("item", item);
            t.putString("service", service);
            t.putInt("qty", qty);
            t.putInt("price", price);
            t.putInt("penalty", penalty);
            t.putLong("deadline", deadline);
            t.putString("state", state);
            t.putIntArray("held", new int[]{ heldPrice, heldSellerBond, heldBuyerBond });
            t.putBoolean("sd", sellerDone);
            t.putBoolean("bd", buyerDone);
            return t;
        }

        static Deal load(CompoundTag t) {
            Deal d = new Deal();
            d.id = t.getInt("id");
            d.town = t.getUUID("town");
            d.seller = t.getUUID("seller");
            d.buyer = t.getUUID("buyer");
            d.sellerName = t.getString("sn");
            d.buyerName = t.getString("bn");
            d.kind = t.getString("kind");
            d.item = t.getString("item");
            d.service = t.getString("service");
            d.qty = t.getInt("qty");
            d.price = t.getInt("price");
            d.penalty = t.getInt("penalty");
            d.deadline = t.getLong("deadline");
            d.state = t.getString("state");
            int[] h = t.getIntArray("held");
            if (h.length == 3) { d.heldPrice = h[0]; d.heldSellerBond = h[1]; d.heldBuyerBond = h[2]; }
            d.sellerDone = t.getBoolean("sd");
            d.buyerDone = t.getBoolean("bd");
            return d;
        }

        public String what() {
            if (kind.equals("service")) return "\"" + service + "\"";
            Item it = item();
            return qty + " " + (it == null ? item : Goods.name(new ItemStack(it)).toLowerCase(java.util.Locale.ROOT));
        }

        @Nullable
        Item item() {
            ResourceLocation rl = ResourceLocation.tryParse(item);
            return rl == null ? null : BuiltInRegistries.ITEM.get(rl);
        }
    }

    final Map<Integer, Deal> deals = new LinkedHashMap<>();
    int seq;

    public Contracts() {}

    static Contracts of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Contracts();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Contracts::new, Contracts::load, null), ID);
    }

    static Contracts load(CompoundTag t, HolderLookup.Provider p) {
        Contracts c = new Contracts();
        c.seq = t.getInt("seq");
        ListTag l = t.getList("deals", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            Deal d = Deal.load(l.getCompound(i));
            c.deals.put(d.id, d);
        }
        CompoundTag held = t.getCompound("held");
        for (String k : held.getAllKeys()) HELD.put(UUID.fromString(k), held.getInt(k));
        CompoundTag goods = t.getCompound("heldGoods");
        for (String k : goods.getAllKeys()) {
            ListTag gl = goods.getList(k, Tag.TAG_COMPOUND);
            List<ItemStack> list = new ArrayList<>();
            for (int i = 0; i < gl.size(); i++) ItemStack.parse(p, gl.getCompound(i)).ifPresent(list::add);
            HELD_GOODS.put(UUID.fromString(k), list);
        }
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag t, HolderLookup.Provider p) {
        t.putInt("seq", seq);
        ListTag l = new ListTag();
        for (Deal d : deals.values()) l.add(d.save());
        t.put("deals", l);
        CompoundTag held = new CompoundTag();
        HELD.forEach((k, v) -> held.putInt(k.toString(), v));
        t.put("held", held);
        CompoundTag goods = new CompoundTag();
        HELD_GOODS.forEach((k, v) -> {
            ListTag gl = new ListTag();
            for (ItemStack st : v) if (!st.isEmpty()) gl.add(st.save(p));
            goods.put(k.toString(), gl);
        });
        t.put("heldGoods", goods);
        return t;
    }

    static void dirty() {
        of().setDirty();
    }

    public static void resetForTests() {
        of().deals.clear();
        dirty();
    }

    /** The town's escrow for players' contracts, coins (the coin census counts it). */
    public static long escrowed(UUID town) {
        long n = 0;
        for (Deal d : of().deals.values()) if (d.town.equals(town)) n += d.heldPrice + d.heldSellerBond + d.heldBuyerBond;
        return n;
    }

    /** What one contract holds in escrow, coins. */
    static int escrowedOf(Deal d) {
        return d.heldPrice + d.heldSellerBond + d.heldBuyerBond;
    }

    static List<Deal> of(UUID town, UUID player) {
        List<Deal> out = new ArrayList<>();
        for (Deal d : of().deals.values()) if (d.town.equals(town) && (d.seller.equals(player) || d.buyer.equals(player))) out.add(d);
        return out;
    }

    @Nullable
    static Deal get(int id) {
        return of().deals.get(id);
    }

    static boolean take(ServerPlayer p, UUID town, int coins, String what) {
        if (coins <= 0) return true;
        if (Market.coinsHeld(p) < coins) return false;
        Market.payOut(p, coins);
        CoinCensus.playerIn(town, coins, p, PlayerFinance.Cat.HELD, what);
        return true;
    }

    static void give(ServerLevel level, UUID to, UUID town, int coins, String what) {
        if (coins <= 0) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(to);
        if (p != null) {
            Earnings.giveCoins(p, coins);
            CoinCensus.playerOut(town, coins, p, PlayerFinance.Cat.RELEASED, what);
        } else {
            CoinCensus.playerOut(town, coins, to, PlayerFinance.Cat.RELEASED, what);
            Ledger.addHeld(to, coins);
        }
    }

    /** Coin held for a player who was away when it was released (paid in at their next login). */
    static final class Ledger {
        private Ledger() {}

        static void addHeld(UUID player, int coins) {
            HELD.merge(player, coins, Integer::sum);
            of().setDirty();
        }
    }

    static final Map<UUID, Integer> HELD = new java.util.concurrent.ConcurrentHashMap<>();

    // ------------------------------------------------------------------ the steps

    /** The seller proposes goods (the kind in their main hand) for a price. */
    static String proposeGoods(ServerLevel level, ServerPlayer seller, UUID town, ServerPlayer buyer, int qty, int price, int days, int penalty) {
        ItemStack hand = seller.getMainHandItem();
        if (hand.isEmpty()) return "!Hold the kind of goods you'll deliver in your hand.";
        return propose(level, seller, town, buyer, "goods", BuiltInRegistries.ITEM.getKey(hand.getItem()).toString(), "", qty, price, days, penalty);
    }

    static String propose(ServerLevel level, ServerPlayer seller, UUID town, ServerPlayer buyer, String kind, String item, String service, int qty, int price,
                          int days, int penalty) {
        if (seller.getUUID().equals(buyer.getUUID())) return "!A contract needs two parties.";
        if (price <= 0) return "!Set a price.";
        if (!take(seller, town, penalty, "a contract's bond")) return "!Your bond of " + penalty + "c goes into escrow when you propose; you carry " + Market.coinsHeld(seller) + "c.";
        Contracts c = of();
        Deal d = new Deal();
        d.id = ++c.seq;
        d.town = town;
        d.seller = seller.getUUID();
        d.sellerName = seller.getGameProfile().getName();
        d.buyer = buyer.getUUID();
        d.buyerName = buyer.getGameProfile().getName();
        d.kind = kind;
        d.item = item;
        d.service = service;
        d.qty = Math.max(1, qty);
        d.price = price;
        d.penalty = Math.max(0, penalty);
        d.heldSellerBond = d.penalty;
        d.deadline = level.getDayTime() / 24000L + Math.max(1, days);
        c.deals.put(d.id, d);
        dirty();
        WorkLife.toast(buyer, d.sellerName + " proposes a contract: " + d.what() + " for " + price + "c by day " + d.deadline + ". See Contracts.");
        Planner.add(seller, d.deadline, 18, "contract", "Deliver " + d.what() + " to " + d.buyerName, null);
        return "Proposed to " + d.buyerName + ": " + d.what() + " for " + price + "c by day " + d.deadline + ", penalty " + d.penalty
            + "c. Your bond is in escrow at the hall. Next: they sign, putting the price in escrow.";
    }

    /** The buyer signs: the price and its bond into escrow. */
    static String sign(ServerLevel level, ServerPlayer buyer, Deal d) {
        if (!d.buyer.equals(buyer.getUUID()) || !d.state.equals("DRAFT")) return "!There's nothing for you to sign.";
        int need = d.price + d.penalty;
        if (!take(buyer, d.town, need, "a contract's price and bond")) return "!Signing puts " + need + "c in escrow (the price and your bond); you carry " + Market.coinsHeld(buyer) + "c.";
        d.heldPrice = d.price;
        d.heldBuyerBond = d.penalty;
        d.state = "SIGNED";
        dirty();
        ServerPlayer s = level.getServer().getPlayerList().getPlayer(d.seller);
        if (s != null) WorkLife.toast(s, d.buyerName + " signed: deliver " + d.what() + " by day " + d.deadline + ".");
        Planner.add(buyer, d.deadline, 18, "contract", d.sellerName + " delivers " + d.what(), null);
        return "Signed. " + need + "c is held in escrow at the hall. " + (d.kind.equals("service") ? "When the work is done, both press Done." : d.sellerName + " hands the goods in here by day " + d.deadline + ".");
    }

    static String refuse(ServerLevel level, ServerPlayer buyer, Deal d) {
        if (!d.buyer.equals(buyer.getUUID()) || !d.state.equals("DRAFT")) return "!There's nothing to refuse.";
        d.state = "REFUSED";
        give(level, d.seller, d.town, d.heldSellerBond, "a contract's bond back");
        d.heldSellerBond = 0;
        dirty();
        return "Refused. " + d.sellerName + "'s bond goes back to them.";
    }

    /** The seller hands the goods in: checked, the escrow released. */
    static String deliver(ServerLevel level, ServerPlayer seller, Deal d) {
        if (!d.seller.equals(seller.getUUID()) || !d.state.equals("SIGNED") || !d.kind.equals("goods")) return "!There's nothing for you to deliver.";
        Item it = d.item();
        if (it == null) return "!The goods aren't known.";
        int have = 0;
        for (ItemStack st : seller.getInventory().items) if (st.is(it)) have += st.getCount();
        if (have < d.qty) return "!You carry " + have + " of the " + d.qty + " " + Goods.name(new ItemStack(it)).toLowerCase(java.util.Locale.ROOT) + " the contract asks for.";
        int left = d.qty;
        for (int i = 0; i < seller.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack st = seller.getInventory().getItem(i);
            if (!st.is(it)) continue;
            int k = Math.min(left, st.getCount());
            st.shrink(k);
            left -= k;
        }
        ServerPlayer buyer = level.getServer().getPlayerList().getPlayer(d.buyer);
        ItemStack goods = new ItemStack(it, d.qty);
        if (buyer != null) { if (!buyer.getInventory().add(goods)) buyer.drop(goods, false); }
        else { HELD_GOODS.computeIfAbsent(d.buyer, k -> new ArrayList<>()).add(goods); dirty(); }
        settle(level, d, "DONE");
        return "Delivered and checked. " + d.price + "c is yours, and both bonds are back. " + (buyer == null ? d.buyerName + " collects the goods at their next visit." : "");
    }

    static final Map<UUID, List<ItemStack>> HELD_GOODS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A service: this side says it's done; both, and it's settled. */
    static String done(ServerLevel level, ServerPlayer p, Deal d) {
        if (!d.state.equals("SIGNED") || !d.kind.equals("service")) return "!Nothing to confirm.";
        if (d.seller.equals(p.getUUID())) d.sellerDone = true;
        else if (d.buyer.equals(p.getUUID())) d.buyerDone = true;
        else return "!It isn't your contract.";
        dirty();
        if (d.sellerDone && d.buyerDone) {
            settle(level, d, "DONE");
            return "Both agree it's done: " + d.price + "c to " + d.sellerName + ", both bonds back.";
        }
        return "Noted. Waiting for " + (d.sellerDone ? d.buyerName : d.sellerName) + " to agree.";
    }

    /** Either side disputes a service: held for the town's small-claims hearing. */
    static String dispute(ServerLevel level, ServerPlayer p, Deal d) {
        if (!d.state.equals("SIGNED")) return "!Only a signed contract can be disputed.";
        if (!d.seller.equals(p.getUUID()) && !d.buyer.equals(p.getUUID())) return "!It isn't your contract.";
        d.state = "DISPUTED";
        dirty();
        claimHook(level, d, p.getUUID());
        return "Disputed. The escrow stays held at the hall till the town hears the claim.";
    }

    /** WP-E's small-claims seam: a disputed contract to be heard. A stub till then (the escrow stays held). */
    public static void claimHook(ServerLevel level, Deal d, UUID by) {
        // [wpe] Heard by the town's small-claims court: the escrow released to whoever the court finds for.
        boolean buyer = by.equals(d.buyer);
        UUID other = buyer ? d.seller : d.buyer;
        int evidence = buyer ? (d.sellerDone ? 3 : 5) : (d.sellerDone ? 5 : 3);
        Court.smallClaimEscrow(level, d.town, by, buyer ? d.buyerName : d.sellerName, other, buyer ? d.sellerName : d.buyerName, d.price,
            "contract #" + d.id + " (" + (d.kind.equals("service") ? d.service : d.qty + " " + d.item) + ")", evidence, t -> {
                if (!d.state.equals("DISPUTED")) return;
                boolean forClaimant = t.getString("state").equals("for claimant");
                settle(level, d, forClaimant == buyer ? "LAPSED" : "DONE");
            });
    }

    /** Released: done (price to the seller, bonds back), or lapsed (price back to the buyer, with the seller's bond). */
    static void settle(ServerLevel level, Deal d, String state) {
        if (state.equals("DONE")) {
            give(level, d.seller, d.town, d.heldPrice + d.heldSellerBond, "a contract paid");
            give(level, d.buyer, d.town, d.heldBuyerBond, "a contract's bond back");
        } else {
            give(level, d.buyer, d.town, d.heldPrice + d.heldBuyerBond + d.heldSellerBond, "a contract lapsed: the price back and the penalty");
        }
        d.heldPrice = d.heldSellerBond = d.heldBuyerBond = 0;
        d.state = state;
        dirty();
    }

    private static long lastDay = -1;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 100 != 61) return;
        ServerLevel level = e.getServer().overworld();
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {   // coin and goods held for the away
            Integer c = HELD.remove(p.getUUID());
            if (c != null && c > 0) Earnings.giveCoins(p, c);
            List<ItemStack> g = HELD_GOODS.remove(p.getUUID());
            if (g != null) for (ItemStack st : g) if (!p.getInventory().add(st)) p.drop(st, false);
        }
        long day = level.getDayTime() / 24000L;
        if (day == lastDay) return;
        lastDay = day;
        for (Deal d : List.copyOf(of().deals.values())) {
            if (day <= d.deadline) continue;
            if (d.state.equals("SIGNED")) {
                if (d.kind.equals("service")) { d.state = "DISPUTED"; claimHook(level, d, d.buyer); dirty(); }
                else settle(level, d, "LAPSED");
            } else if (d.state.equals("DRAFT")) {
                give(level, d.seller, d.town, d.heldSellerBond, "an unsigned contract's bond back");
                d.heldSellerBond = 0;
                d.state = "LAPSED";
                dirty();
            }
        }
    }

    // ------------------------------------------------------------------ tests

    public static int proposeForTests(ServerLevel level, ServerPlayer seller, UUID town, ServerPlayer buyer, int qty, int price, int days, int penalty) {
        String s = proposeGoods(level, seller, town, buyer, qty, price, days, penalty);
        return s.startsWith("!") ? -1 : of().seq;
    }

    public static String signForTests(ServerLevel level, ServerPlayer buyer, int id) {
        return sign(level, buyer, get(id));
    }

    public static String deliverForTests(ServerLevel level, ServerPlayer seller, int id) {
        return deliver(level, seller, get(id));
    }

    public static String stateForTests(int id) {
        Deal d = get(id);
        return d == null ? "" : d.state;
    }

    public static void lapseForTests(ServerLevel level, int id) {
        Deal d = get(id);
        if (d != null) settle(level, d, "LAPSED");
    }
}
