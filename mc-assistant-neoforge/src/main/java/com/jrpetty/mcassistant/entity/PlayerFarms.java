package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.WorkMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [wpb] A player's own farm (or orchard, ranch, woodlot, mine or fishery) as a registered business.
 * <ul>
 * <li><b>Registering.</b> Stand in a field you made (farmland you hoed, off the town's fields and anybody else's) and
 *     register it: its farmland is found and outlined, a charter fee paid, and the farm is a business in the town's books
 *     (a Business of the producing kind, owned by you), so it keeps books, pays the profits tax as it earns (TownBudget,
 *     through the town's own morning: Businesses.morning) and is listed with the town's businesses. More fields can be
 *     added. Buying land is WP-C's (Premises.holdsTitle is the seam): for now, a field you made counts as yours.</li>
 * <li><b>Rates.</b> Agricultural land rates each week, by the area (a lower rate than town lots), out of the farm's till
 *     to the treasury; shown as bills on its page.</li>
 * <li><b>Selling, price before you confirm.</b> Only the farm's own produce (marked as grown on its fields: Provenance) or
 *     its stock (its hands' harvest) is sold through it. The outlets: the town stores at their posted bid; a firm that
 *     sells it on, a little over the stores (the FirmBuys seam); your own shop's stock chest (the farm shop); and a
 *     player-to-player contract (Contracts). Each shows the buyer, the price a unit and what this load brings, and asks
 *     before anything moves. Every sale is written in the farm's books by outlet.</li>
 * <li><b>Hands.</b> Folk farmhands, salaried or on commission (each folk chooses by its character: PayTerms.folkChoice),
 *     set to work the farm's fields; each morning their round brings the ripe crops in to the farm's stock and they are
 *     paid out of its till. Players can be hired through the jobs board (PlayerHiring). One hand can be made manager: it
 *     sells the stock to the stores each morning while you are away. Without a manager, with you away, the farm pauses
 *     safely: no round, no wages, nothing sold.</li>
 * <li><b>Books.</b> Revenue by outlet, costs, wages, rates, tax and profit, this week and all told.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerFarms extends SavedData {

    private static final String ID = "mc_assistant_wpb_farms";
    static final int FEE = 10, MOST_FIELD = 48, ROUND_MOST = 24;
    static final double RATE_PER_BLOCK_WEEK = 0.02;
    @Nullable private static PlayerFarms loose;

    /** A farm. Its fields are {x0, z0, x1, z1, y}. */
    public static final class Farm {
        public String id = "", name = "", kind = "farm", ownerName = "";
        public UUID town = new UUID(0, 0), owner = new UUID(0, 0);
        public final List<int[]> fields = new ArrayList<>();
        /** Hands: folk uuid → {name, "c" commission or "s" salary, wage}. */
        public final Map<UUID, String[]> hands = new LinkedHashMap<>();
        @Nullable public UUID manager;
        public boolean paused;
        public long since, ratesDay = -1, roundDay = -1;
        /** Revenue by outlet (pence), this week and all told. */
        public final Map<String, Long> outletWeek = new LinkedHashMap<>(), outletAll = new LinkedHashMap<>();
        public long weekStart;

        public BlockPos centre() {
            if (fields.isEmpty()) return BlockPos.ZERO;
            int[] a = fields.get(0);
            return new BlockPos((a[0] + a[2]) / 2, a[4], (a[1] + a[3]) / 2);
        }

        public int area() {
            int n = 0;
            for (int[] f : fields) n += (f[2] - f[0] + 1) * (f[3] - f[1] + 1);
            return n;
        }

        boolean contains(BlockPos p) {
            for (int[] f : fields) {
                if (p.getX() >= f[0] && p.getX() <= f[2] && p.getZ() >= f[1] && p.getZ() <= f[3] && Math.abs(p.getY() - f[4]) <= 3) return true;
            }
            return false;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("id", id);
            t.putString("name", name);
            t.putString("kind", kind);
            t.putString("ownerName", ownerName);
            t.putUUID("town", town);
            t.putUUID("owner", owner);
            ListTag fl = new ListTag();
            for (int[] f : fields) { CompoundTag x = new CompoundTag(); x.putIntArray("r", f); fl.add(x); }
            t.put("fields", fl);
            ListTag hl = new ListTag();
            for (Map.Entry<UUID, String[]> e : hands.entrySet()) {
                CompoundTag x = new CompoundTag();
                x.putUUID("u", e.getKey());
                x.putString("n", e.getValue()[0]);
                x.putString("p", e.getValue()[1]);
                x.putString("w", e.getValue()[2]);
                hl.add(x);
            }
            t.put("hands", hl);
            if (manager != null) t.putUUID("manager", manager);
            t.putBoolean("paused", paused);
            t.putLong("since", since);
            t.putLong("ratesDay", ratesDay);
            t.putLong("roundDay", roundDay);
            t.putLong("weekStart", weekStart);
            CompoundTag ow = new CompoundTag(), oa = new CompoundTag();
            outletWeek.forEach(ow::putLong);
            outletAll.forEach(oa::putLong);
            t.put("ow", ow);
            t.put("oa", oa);
            return t;
        }

        static Farm load(CompoundTag t) {
            Farm f = new Farm();
            f.id = t.getString("id");
            f.name = t.getString("name");
            f.kind = t.getString("kind");
            f.ownerName = t.getString("ownerName");
            f.town = t.getUUID("town");
            f.owner = t.getUUID("owner");
            ListTag fl = t.getList("fields", Tag.TAG_COMPOUND);
            for (int i = 0; i < fl.size(); i++) f.fields.add(fl.getCompound(i).getIntArray("r"));
            ListTag hl = t.getList("hands", Tag.TAG_COMPOUND);
            for (int i = 0; i < hl.size(); i++) {
                CompoundTag x = hl.getCompound(i);
                f.hands.put(x.getUUID("u"), new String[]{ x.getString("n"), x.getString("p"), x.getString("w") });
            }
            if (t.hasUUID("manager")) f.manager = t.getUUID("manager");
            f.paused = t.getBoolean("paused");
            f.since = t.getLong("since");
            f.ratesDay = t.getLong("ratesDay");
            f.roundDay = t.getLong("roundDay");
            f.weekStart = t.getLong("weekStart");
            CompoundTag ow = t.getCompound("ow"), oa = t.getCompound("oa");
            for (String k : ow.getAllKeys()) f.outletWeek.put(k, ow.getLong(k));
            for (String k : oa.getAllKeys()) f.outletAll.put(k, oa.getLong(k));
            return f;
        }
    }

    final Map<String, Farm> farms = new LinkedHashMap<>();
    int seq;

    public PlayerFarms() {}

    static PlayerFarms of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new PlayerFarms();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PlayerFarms::new, PlayerFarms::load, null), ID);
    }

    static PlayerFarms load(CompoundTag t, HolderLookup.Provider p) {
        PlayerFarms s = new PlayerFarms();
        s.seq = t.getInt("seq");
        ListTag l = t.getList("farms", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            Farm f = Farm.load(l.getCompound(i));
            s.farms.put(f.id, f);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag t, HolderLookup.Provider p) {
        t.putInt("seq", seq);
        ListTag l = new ListTag();
        for (Farm f : farms.values()) l.add(f.save());
        t.put("farms", l);
        return t;
    }

    static void dirty() {
        of().setDirty();
    }

    public static void resetForTests() {
        of().farms.clear();
        of().seq = 0;
        dirty();
    }

    // ------------------------------------------------------------------ looking up

    @Nullable
    public static Farm byId(String id) {
        return of().farms.get(id);
    }

    @Nullable
    public static Farm at(Level level, BlockPos pos) {
        for (Farm f : of().farms.values()) if (f.contains(pos)) return f;
        return null;
    }

    public static List<Farm> ofOwner(UUID town, UUID owner) {
        List<Farm> out = new ArrayList<>();
        for (Farm f : of().farms.values()) {
            if (!f.town.equals(town)) continue;
            Business b = business(f.id);
            if (f.owner.equals(owner) || b != null && b.ownedBy(owner)) out.add(f);
        }
        return out;
    }

    static String businessId(String farmId) {
        return "pfarm:" + farmId;
    }

    @Nullable
    static Business business(String farmId) {
        Farm f = byId(farmId);
        return f == null ? null : Businesses.of(f.town, businessId(farmId));
    }

    /** The owner, a partner, a hand, or a player hired to work it. */
    static boolean mayHarvest(Farm f, Player p) {
        Business b = business(f.id);
        if (f.owner.equals(p.getUUID()) || b != null && b.ownedBy(p.getUUID())) return true;
        if (f.hands.containsKey(p.getUUID())) return true;
        return ("farm:" + f.id).equals(WorkLife.employerKey(p));
    }

    /** Produce into the farm's stock (its own: no cost). */
    static void intoStock(ServerLevel level, Business b, ItemStack st) {
        if (st.isEmpty()) return;
        String key = Stockroom.key(st);
        b.stock.computeIfAbsent(key, k -> new Business.Lot()).add(st.getCount(), 0);
        long day = Businesses.today(level);
        b.today(day).line(key).made += st.getCount();
        b.allTime.line(key).made += st.getCount();
        Businesses.dirty();
    }

    // ------------------------------------------------------------------ registering

    static final String[] KINDS = { "farm", "orchard", "ranch", "woodlot", "mine", "fishery" };

    static Business.Type typeOf(String kind) {
        return switch (kind) {
            case "woodlot" -> Business.Type.WOODLOT;
            case "mine" -> Business.Type.MINE;
            case "fishery" -> Business.Type.FISHERY;
            default -> Business.Type.FARM;
        };
    }

    /** The farmland around here (joined, flood-filled), as {x0,z0,x1,z1,y}, or null if none. */
    @Nullable
    static int[] fieldAround(ServerLevel level, BlockPos at) {
        BlockPos start = null;
        for (int dy = -2; dy <= 1 && start == null; dy++) {
            BlockPos q = at.offset(0, dy, 0);
            if (level.getBlockState(q).is(Blocks.FARMLAND)) start = q;
        }
        if (start == null) return null;
        int y = start.getY();
        java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        todo.add(start);
        int x0 = start.getX(), x1 = x0, z0 = start.getZ(), z1 = z0, n = 0;
        while (!todo.isEmpty() && n < 2500) {
            BlockPos q = todo.poll();
            if (!seen.add(q.asLong())) continue;
            BlockState s = level.getBlockState(q);
            boolean water = s.is(Blocks.WATER);
            if (!s.is(Blocks.FARMLAND) && !water) continue;
            if (Math.abs(q.getX() - start.getX()) > MOST_FIELD / 2 || Math.abs(q.getZ() - start.getZ()) > MOST_FIELD / 2) continue;
            n++;
            if (!water) {
                x0 = Math.min(x0, q.getX()); x1 = Math.max(x1, q.getX());
                z0 = Math.min(z0, q.getZ()); z1 = Math.max(z1, q.getZ());
            }
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) todo.add(q.relative(d));
        }
        return new int[]{ x0, z0, x1, z1, y };
    }

    /** Why this field can't be the player's farm's ("" if it can). */
    static String whyNotField(ServerLevel level, Villages.Village v, int[] r) {
        for (BlockPos c : ShiftTasks.fieldsOf(v)) {
            if (c.getX() >= r[0] - 10 && c.getX() <= r[2] + 10 && c.getZ() >= r[1] - 10 && c.getZ() <= r[3] + 10) return "it's part of the town's own fields";
        }
        for (Farm f : of().farms.values()) {
            for (int[] o : f.fields) if (r[0] <= o[2] && r[2] >= o[0] && r[1] <= o[3] && r[3] >= o[1]) return "it's part of " + f.name;
        }
        BlockPos mid = new BlockPos((r[0] + r[2]) / 2, r[4], (r[1] + r[3]) / 2);
        String built = Laws.builtBy(level, v, mid);
        if (built != null) return "it's part of " + built;
        return "";
    }

    /** Register a farm: the field the player stands in (or a nine-square plot for the other kinds), the fee paid. */
    static String register(ServerLevel level, ServerPlayer p, Villages.Village v, String kind, String name, boolean sure) {
        if (!Government.allowsPrivateFirms(v.id())) return "!The state owns the farms here: no private firm may be registered.";
        if (!Citizens.is(v.id(), p.getUUID()) && !Standing.of(v.id(), p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) {
            return "!Only a citizen or a friend of " + Villages.name(v.id()) + " may register a business here.";
        }
        int[] r = kind.equals("farm") ? fieldAround(level, p.blockPosition()) : new int[]{ p.getBlockX() - 4, p.getBlockZ() - 4, p.getBlockX() + 4, p.getBlockZ() + 4, p.getBlockY() - 1 };
        if (r == null) return "!Stand on the farmland of a field you made (hoe the ground first), then register it.";
        String no = whyNotField(level, v, r);
        if (!no.isEmpty()) return "!That ground can't be yours: " + no + ".";
        if (Market.coinsHeld(p) < FEE) return "!Registering costs " + FEE + " coins; you have " + Market.coinsHeld(p) + ".";
        String nm = name == null || name.isBlank() ? p.getGameProfile().getName() + "'s " + cap(kind) : name.trim();
        if (nm.length() > 32) nm = nm.substring(0, 32);
        Market.payOut(p, FEE);
        Ledger.addCoins(v.id(), FEE);
        CoinCensus.playerIn(v.id(), FEE, p, PlayerFinance.Cat.FEES, "a farm's registration");
        TownBudget.in(v.id(), "fees", FEE * 100L);
        PlayerFarms s = of();
        Farm f = new Farm();
        f.id = "pf" + (++s.seq);
        f.name = nm;
        f.kind = kind;
        f.town = v.id();
        f.owner = p.getUUID();
        f.ownerName = p.getGameProfile().getName();
        f.fields.add(r);
        long day = level.getDayTime() / 24000L;
        f.since = day;
        f.ratesDay = day;
        f.weekStart = day;
        s.farms.put(f.id, f);
        Business b = new Business(businessId(f.id), typeOf(kind), nm);
        b.opened = Businesses.today(level);
        b.owners.add(new Business.Stake(Business.Holder.PLAYER, p.getUUID(), p.getGameProfile().getName(), Business.WHOLE));
        b.markup = 0;
        Businesses.town(v.id()).biz.put(b.id, b);
        b.note(b.opened, "registered by " + p.getGameProfile().getName() + " as a " + kind + " of " + ((r[2] - r[0] + 1) * (r[3] - r[1] + 1)) + " blocks");
        Businesses.dirty();
        dirty();
        Villages.tell(v.id(), day, p.getGameProfile().getName() + " registered " + nm + ", a " + kind + " of their own");
        Planner.add(p, day + 7, 6, "bill", nm + ": land rates due", f.centre());
        return nm + " is registered: " + ((r[2] - r[0] + 1) * (r[3] - r[1] + 1)) + " blocks, outlined in gold. What it means: it keeps books, pays land rates weekly ("
            + ratesFor(f) + "c) and tax on its profit, and only produce grown on it sells through it. Next: harvest, then Sell.";
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static int ratesFor(Farm f) {
        return (int) Math.max(1, Math.round(f.area() * RATE_PER_BLOCK_WEEK));
    }

    /** Another field added to the farm. */
    static String addField(ServerLevel level, ServerPlayer p, Farm f) {
        Villages.Village v = Villages.get(f.town);
        if (v == null) return "!The town is gone.";
        int[] r = fieldAround(level, p.blockPosition());
        if (r == null) return "!Stand on the farmland of the new field first.";
        String no = whyNotField(level, v, r);
        if (!no.isEmpty()) return "!That field can't be added: " + no + ".";
        f.fields.add(r);
        dirty();
        return "Field added: " + ((r[2] - r[0] + 1) * (r[3] - r[1] + 1)) + " blocks. " + f.name + " now covers " + f.area() + " blocks; rates " + ratesFor(f) + "c a week.";
    }

    // ------------------------------------------------------------------ selling

    /** An outlet's offer for a load: who, the price a unit (coin), the total (coin), why not ("" if it will). */
    public record Quote(String outlet, String buyer, double each, int total, String whyNot) {}

    /** What the farm has to sell: its own marked produce in the pack, and its stock, by stock key. */
    static Map<String, Integer> sellable(ServerPlayer p, Farm f) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ItemStack st : p.getInventory().items) {
            if (st.isEmpty()) continue;
            CompoundTag m = Provenance.of(st);
            if (m != null && m.getString("e").equals("farm:" + f.id)) out.merge(Stockroom.key(st), st.getCount(), Integer::sum);
        }
        Business b = business(f.id);
        if (b != null) for (Map.Entry<String, Business.Lot> e : b.stock.entrySet()) if (e.getValue().qty > 0) out.merge(e.getKey(), (int) e.getValue().qty, Integer::sum);
        return out;
    }

    /** Every outlet's quote for so many of a thing. */
    static List<Quote> quotes(ServerLevel level, ServerPlayer p, Farm f, String key, int n) {
        List<Quote> out = new ArrayList<>();
        ItemStack one = Stockroom.sampleOf(key);
        if (one.isEmpty() || n <= 0) return out;
        int stores = Market.bidLot(level, f.town, one, n);
        out.add(new Quote("stores", "The town stores", n == 0 ? 0 : stores / (double) n, stores,
            stores <= 0 ? "the stores won't pay for these just now (they have plenty)" : Ledger.coins(f.town) < stores ? "the treasury can't pay that much just now" : ""));
        Business firm = firmFor(f.town, key);
        if (firm != null) {
            double each = Market.bidEach(level, f.town, one) * 1.08;
            int total = (int) Math.floor(each * n + 1e-9);
            out.add(new Quote("firm", firm.name, each, total, firm.till < total * 100L ? firm.name + "'s till can't pay that much" : total <= 0 ? "not worth a coin to it" : ""));
        } else out.add(new Quote("firm", "A firm", 0, 0, "no firm here sells " + Stockroom.plural(key).toLowerCase(Locale.ROOT) + " on just now"));
        PlayerShops.Shop shop = PlayerShops.of(f.town, p.getUUID());
        out.add(new Quote("shop", shop == null ? "Your farm shop" : shop.name, 0, 0, shop == null ? "you have no shop here (open one: Start a business)"
            : shop.back.isEmpty() ? shop.name + " has no stock chest" : ""));
        out.add(new Quote("contract", "A buyer on contract", 0, 0, "agree a contract with another player first (Contracts tab)"));
        return out;
    }

    @Nullable
    static Business firmFor(UUID town, String key) {
        for (Business b : Enterprise.open(town)) {
            BusinessTypes.BusinessType t = Enterprise.kindOf(town, b);
            if (t != null && t.wares().contains(key) && !b.ownedBy(null)) return b;
        }
        return null;
    }

    /** Take so many of a thing: the pack's marked ones first, then the stock. Returns what was taken. */
    static int take(ServerPlayer p, Farm f, String key, int n) {
        int got = 0;
        for (int i = 0; i < p.getInventory().getContainerSize() && got < n; i++) {
            ItemStack st = p.getInventory().getItem(i);
            CompoundTag m = Provenance.of(st);
            if (st.isEmpty() || m == null || !m.getString("e").equals("farm:" + f.id) || !Stockroom.key(st).equals(key)) continue;
            int k = Math.min(n - got, st.getCount());
            st.shrink(k);
            got += k;
        }
        Business b = business(f.id);
        if (got < n && b != null) {
            Business.Lot lot = b.stock.get(key);
            if (lot != null) {
                long k = Math.min(n - got, lot.qty);
                lot.take(k);
                got += (int) k;
            }
        }
        return got;
    }

    /** Sell through an outlet: the load taken, the buyer pays the farm's till, the books written. What it says. */
    static String sell(ServerLevel level, ServerPlayer p, Farm f, String outlet, String key, int n) {
        Business b = business(f.id);
        if (b == null) return "!The farm's books are missing.";
        int have = sellable(p, f).getOrDefault(key, 0);
        n = Math.min(n, have);
        if (n <= 0) return "!The farm has none of that to sell.";
        Quote q = null;
        for (Quote x : quotes(level, p, f, key, n)) if (x.outlet().equals(outlet)) q = x;
        if (q == null || !q.whyNot().isEmpty()) return "!" + (q == null ? "No such outlet." : "Not there: " + q.whyNot() + ".");
        ItemStack one = Stockroom.sampleOf(key);
        long day = Businesses.today(level);
        if (outlet.equals("shop")) {
            PlayerShops.Shop shop = PlayerShops.of(f.town, p.getUUID());
            int moved = 0;
            for (BlockPos c : shop.back) {
                if (!(level.getBlockEntity(c) instanceof Container ct)) continue;
                int got = take(p, f, key, n - moved);
                ItemStack left = Stacking.insert(ct, one.copyWithCount(got));
                moved += got - left.getCount();
                if (!left.isEmpty()) { intoStock(level, b, left); break; }
                if (moved >= n) break;
            }
            b.note(day, moved + " " + Stockroom.plural(key).toLowerCase(Locale.ROOT) + " to " + shop.name + " (the farm shop)");
            return moved + " " + Stockroom.plural(key).toLowerCase(Locale.ROOT) + " put in " + shop.name + "'s stock chest. Set its price on the shop's screen; folk buy it there.";
        }
        int got = take(p, f, key, n);
        int coins;
        if (outlet.equals("stores")) {
            coins = Ledger.takeCoins(f.town, Math.min(q.total(), (int) Math.round(q.each() * got)));
            Market.intoStores(level, f.town, one.copyWithCount(got));
        } else {
            Business firm = firmFor(f.town, key);
            if (firm == null) return "!That firm has gone.";
            coins = (int) Math.min(firm.till / 100, Math.round(q.each() * got));
            firm.till -= coins * 100L;
            firm.stock.computeIfAbsent(key, k -> new Business.Lot()).add(got, coins * 100L);
            firm.today(day).bought += coins * 100L;
        }
        b.till += coins * 100L;
        Business.Day d = b.today(day);
        d.revenue += coins * 100L;
        d.line(key).units += got;
        d.line(key).revenue += coins * 100L;
        b.allTime.revenue += coins * 100L;
        d.trans++;
        rollWeek(f, level.getDayTime() / 24000L);
        f.outletWeek.merge(outlet, coins * 100L, Long::sum);
        f.outletAll.merge(outlet, coins * 100L, Long::sum);
        b.note(day, "sold " + got + " " + Stockroom.plural(key).toLowerCase(Locale.ROOT) + " to " + q.buyer() + " for " + coins + "c");
        Businesses.dirty();
        dirty();
        return "Sold " + got + " " + Stockroom.plural(key).toLowerCase(Locale.ROOT) + " to " + q.buyer() + " for " + coins + "c, into " + f.name
            + "'s till. Take your share as drawings (Books), after rates and tax.";
    }

    static void rollWeek(Farm f, long day) {
        if (day - f.weekStart >= 7) {
            f.outletWeek.clear();
            f.weekStart = day;
        }
    }

    // ------------------------------------------------------------------ hands

    /** The best folk out of work (or not at a trade), not already on a farm. */
    @Nullable
    static VillageFolkEntity spareHand(UUID town) {
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(town)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            if (f.stationTask() != StationTask.NONE || onAnyFarm(f.getUUID())) continue;
            if (best == null || f.tradeLevel(StationTask.FARM) > best.tradeLevel(StationTask.FARM)) best = f;
        }
        return best;
    }

    static boolean onAnyFarm(UUID folk) {
        for (Farm f : of().farms.values()) if (f.hands.containsKey(folk)) return true;
        return false;
    }

    static String hire(ServerLevel level, Farm f) {
        VillageFolkEntity h = spareHand(f.town);
        if (h == null) return "!Nobody in town is free to take farm work just now. Post an advert for a player instead.";
        boolean comm = PayTerms.folkChoice(h, h.tradeLevel(StationTask.FARM));
        JobCards.Card c = JobCards.of(f.kind.equals("mine") ? "MINE" : f.kind.equals("fishery") ? "FISH" : f.kind.equals("woodlot") ? "WOOD" : "FARM");
        int wage = PayTerms.goingWage(level, f.town, c, 0);
        f.hands.put(h.getUUID(), new String[]{ h.displayNameCap(), comm ? "c" : "s", Integer.toString(wage) });
        h.setJob(f.kind.equals("woodlot") ? StationTask.WOOD : f.kind.equals("mine") ? StationTask.MINE : f.kind.equals("fishery") ? StationTask.FISH : StationTask.FARM);
        if (!f.fields.isEmpty()) {
            int[] r = f.fields.get(0);
            h.setWorkZone(WorkZone.of(new BlockPos(r[0], r[4], r[1]), new BlockPos(r[2], r[4] + 2, r[3]), WorkZone.DEFAULT_DEPTH));
        }
        dirty();
        return h.displayNameCap() + " takes the work at " + f.name + (comm ? ", on commission (a share of what the farm sells of the round's harvest)"
            : ", on " + wage + "c a day") + ": they chose by their character (" + Temperament.words(h) + "). Paid each morning from the farm's till.";
    }

    /** Let a hand go: a week's wage as severance (a commissioned hand, three days' worth). */
    static String letGo(ServerLevel level, Farm f, UUID hand) {
        String[] h = f.hands.remove(hand);
        if (h == null) return "!No such hand.";
        Business b = business(f.id);
        int wage = Integer.parseInt(h[2]);
        int sev = h[1].equals("c") ? wage * 3 / 2 : wage * 3;
        VillageFolkEntity folk = Homes.loaded(f.town, hand);
        long paid = b == null ? 0 : Businesses.payOut(level, f.town, b, sev * 100L, Businesses.today(level));
        if (folk != null) {
            folk.earn((int) (paid / 100));
            folk.setJob(StationTask.NONE);
            folk.setWorkZone(null);
        }
        if (b != null) { b.today(Businesses.today(level)).wages += paid; b.allTime.wages += paid; }
        if (hand.equals(f.manager)) f.manager = null;
        dirty();
        return h[0] + " is let go with " + (paid / 100) + "c severance" + (paid / 100 < sev ? " (all the till had)" : "") + ".";
    }

    // ------------------------------------------------------------------ the morning

    private static long lastDay = -1;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 200 != 77) return;
        ServerLevel level = e.getServer().overworld();
        long day = level.getDayTime() / 24000L;
        if (day == lastDay) return;
        lastDay = day;
        for (Farm f : List.copyOf(of().farms.values())) {
            try {
                morning(level, f, day);
            } catch (RuntimeException ex) {
                com.mojang.logging.LogUtils.getLogger().warn("[wpb] farm {} morning failed", f.id, ex);
            }
        }
    }

    /** A farm's morning: paused if its owner is away with no manager; else the round, wages, rates, the manager's sale. */
    static void morning(ServerLevel level, Farm f, long day) {
        Business b = business(f.id);
        if (b == null || !b.open) return;
        boolean ownerHere = level.getServer().getPlayerList().getPlayer(f.owner) != null;
        f.paused = !ownerHere && f.manager == null && !f.hands.isEmpty();
        if (day - f.ratesDay >= 7) {
            int rates = ratesFor(f);
            long paid = Businesses.payOut(level, f.town, b, rates * 100L, Businesses.today(level));
            Businesses.toTreasury(f.town, paid);
            TownBudget.in(f.town, "rents", paid);
            b.today(Businesses.today(level)).rent += rates * 100L;
            b.allTime.rent += rates * 100L;
            f.ratesDay = day;
            b.note(Businesses.today(level), "land rates " + rates + "c (" + f.area() + " blocks, agricultural)");
        }
        if (f.paused) {
            b.note(Businesses.today(level), "paused: its owner away and no manager; the hands stood down for the day");
            dirty();
            return;
        }
        int harvested = f.roundDay == day ? 0 : round(level, f, b);
        f.roundDay = day;
        for (Map.Entry<UUID, String[]> e : f.hands.entrySet()) {
            int wage = Integer.parseInt(e.getValue()[2]);
            int due = e.getValue()[1].equals("c") ? (int) Math.round(harvested / (double) Math.max(1, f.hands.size()) * commissionEach(level, f) ) : wage;
            long paid = Businesses.payOut(level, f.town, b, due * 100L, Businesses.today(level));
            VillageFolkEntity folk = Homes.loaded(f.town, e.getKey());
            if (folk != null && paid > 0) folk.earn((int) (paid / 100));
            Business.Day d = b.today(Businesses.today(level));
            d.wages += paid;
            d.staffNames.put(e.getKey(), e.getValue()[0]);
            d.staffPay.merge(e.getKey(), paid, Long::sum);
            b.allTime.wages += paid;
        }
        if (!ownerHere && f.manager != null) {                              // the manager's sale of the stock to the stores
            for (Map.Entry<String, Business.Lot> e : new ArrayList<>(b.stock.entrySet())) {
                if (e.getValue().qty <= 0) continue;
                ItemStack one = Stockroom.sampleOf(e.getKey());
                int n = (int) Math.min(64, e.getValue().qty);
                int coins = Ledger.takeCoins(f.town, Market.bidLot(level, f.town, one, n));
                if (coins <= 0) continue;
                e.getValue().take(n);
                Market.intoStores(level, f.town, one.copyWithCount(n));
                b.till += coins * 100L;
                b.today(Businesses.today(level)).revenue += coins * 100L;
                b.allTime.revenue += coins * 100L;
                f.outletWeek.merge("stores", coins * 100L, Long::sum);
                f.outletAll.merge("stores", coins * 100L, Long::sum);
            }
        }
        Businesses.dirty();
        dirty();
    }

    static double commissionEach(ServerLevel level, Farm f) {
        return WorkMath.commission(Market.bidEach(level, f.town, new ItemStack(Items.WHEAT)), 0.25, WorkMath.SHARE);
    }

    /** The hands' round: ripe crops on the farm's fields brought in to its stock and replanted. Returns how many. */
    static int round(ServerLevel level, Farm f, Business b) {
        if (f.hands.isEmpty()) return 0;
        int most = ROUND_MOST * f.hands.size(), n = 0;
        for (int[] r : f.fields) {
            for (int x = r[0]; x <= r[2] && n < most; x++) {
                for (int z = r[1]; z <= r[3] && n < most; z++) {
                    BlockPos c = new BlockPos(x, r[4] + 1, z);
                    if (!level.isLoaded(c)) continue;
                    BlockState st = level.getBlockState(c);
                    if (!(st.getBlock() instanceof CropBlock cb) || !cb.isMaxAge(st)) continue;
                    for (ItemStack drop : net.minecraft.world.level.block.Block.getDrops(st, level, c, null)) {
                        if (drop.is(cb.asItem().asItem()) && drop.getCount() > 0) drop.shrink(1);   // a seed kept back to plant
                        intoStock(level, b, drop);
                    }
                    level.setBlock(c, cb.getStateForAge(0), 3);
                    n++;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ tests

    public static String registerForTests(ServerLevel level, ServerPlayer p, String kind, String name) {
        Villages.Village v = PlayerLife.here(p);
        return v == null ? "!no town" : register(level, p, v, kind, name, true);
    }

    public static List<String> quotesForTests(ServerLevel level, ServerPlayer p, String farmId, String key, int n) {
        List<String> out = new ArrayList<>();
        Farm f = byId(farmId);
        if (f != null) for (Quote q : quotes(level, p, f, key, n)) out.add(q.outlet() + "|" + q.total() + "|" + q.whyNot());
        return out;
    }

    public static String sellForTests(ServerLevel level, ServerPlayer p, String farmId, String outlet, String key, int n) {
        Farm f = byId(farmId);
        return f == null ? "!none" : sell(level, p, f, outlet, key, n);
    }

    public static void morningForTests(ServerLevel level, String farmId, long day) {
        Farm f = byId(farmId);
        if (f != null) { f.ratesDay = day - 7; morning(level, f, day); }
    }

    /** Tests: the farm's week settled for the profits tax, as Businesses' draw would. Returns pennies taxed. */
    public static long settleWeekForTests(ServerLevel level, String farmId) {
        Farm f = byId(farmId);
        Business b = business(farmId);
        if (f == null || b == null) return -1;
        long day = Businesses.today(level);
        return TownBudget.profitsTax(level, f.town, b, b.last(day, 7).net(), day);
    }

    public static List<Farm> allForTests() {
        return new ArrayList<>(of().farms.values());
    }

    public static String hireForTests(ServerLevel level, String farmId) {
        Farm f = byId(farmId);
        return f == null ? "!none" : hire(level, f);
    }
}
