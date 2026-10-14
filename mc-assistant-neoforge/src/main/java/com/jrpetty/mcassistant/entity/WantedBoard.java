package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageBoardBlock;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.Worth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [earning] The town's Wanted board: what it is short of, posted each morning as orders a player may fill, at a premium
 * and by a day.
 * <ul>
 * <li><b>Where the orders come from.</b> The leader's forecast for the next age (Villages.needs: the food, timber,
 *     stone, coal and iron it is short of), the dining rooms' pantry, and in a war the war stores (arrows, iron). At most
 *     {@link #MOST} at once; each for so many, at a price a piece, by a day ({@link #DAYS}).</li>
 * <li><b>The price.</b> Over the town's paying price ({@link #PREMIUM}), never more than nine tenths of what it sells the
 *     thing for (Worth.FLOOR): a premium, never a loop.</li>
 * <li><b>Filling one.</b> Hold the goods and use the town board or the storehouse, or Deliver on the store's Sell tab:
 *     as many as the order still wants go into the stores and the treasury pays for them there and then.</li>
 * <li><b>Shown</b> on the town board, the store's Sell tab and the Earnings page; kept in the ledger, so they outlast a
 *     restart.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class WantedBoard {

    private WantedBoard() {}

    /** Orders posted at once; days each stands; the premium over the paying price; the least an order is for. */
    static final int MOST = 5, DAYS = 3, LEAST = 8;
    static final double PREMIUM = 1.3;
    /** A shortfall posted is at most this many lots of the good. */
    static final int MOST_LOTS = 4;

    /** An order: its number, the thing, how many, how many filled, the price a piece (hundredths), its last day, why. */
    public static final class Order {
        public final String id;
        public final Item item;
        public final int count;
        public int filled;
        public final int eachX100;
        public final long deadline;
        public final String why;

        Order(String id, Item item, int count, int filled, int eachX100, long deadline, String why) {
            this.id = id;
            this.item = item;
            this.count = count;
            this.filled = filled;
            this.eachX100 = eachX100;
            this.deadline = deadline;
            this.why = why;
        }

        public int left() {
            return Math.max(0, count - filled);
        }

        /** What so many pay, in whole coin. */
        public int pay(int n) {
            return (int) Math.floor(n * eachX100 / 100.0 + 1e-9);
        }
    }

    // ------------------------------------------------------------------ the board's orders

    /** The town's open orders (posted afresh each morning). */
    public static List<Order> orders(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        String posted = Ledger.note(v.id(), "wanted.day");
        if (posted == null || !posted.equals(Long.toString(day))) {
            Ledger.note(v.id(), "wanted.day", Long.toString(day));
            post(level, v, day);
        }
        return read(v.id());
    }

    /** Each morning: the filled and the out-of-date taken down, new shortfalls put up. */
    static void post(ServerLevel level, Villages.Village v, long day) {
        List<Order> kept = new ArrayList<>();
        for (Order o : read(v.id())) if (o.left() > 0 && o.deadline >= day) kept.add(o);
        List<Object[]> want = new ArrayList<>();                          // {item, count, why}
        try {
            for (Villages.Need n : Villages.needs(level, v.id())) {
                if (n.task() == Villages.Task.NONE || n.task() == Villages.Task.BUILD || n.task() == Villages.Task.HANDS || n.amount() <= 0) continue;
                for (Market.Good g : Market.GOODS) {
                    if (g.need() != n.task()) continue;
                    ItemStack s = PriceIndex.sampleOfGood(g);
                    if (s.isEmpty()) continue;
                    want.add(new Object[]{ s.getItem(), Math.min(n.amount(), MOST_LOTS * Math.max(1, g.bundle())), "for the " + n.what() });
                    break;
                }
            }
        } catch (RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().debug("[earning] the forecast could not be read: {}", e.toString());
        }
        if (!DiningPlaces.of(level, v.id()).isEmpty()) {
            for (WorkStock.Making m : Dining.PANTRY) {
                if (Market.stock(level, v.id(), m.is()) < m.keep()) want.add(new Object[]{ m.sample(), Math.max(LEAST, m.keep() * 2), "for the kitchen" });
            }
        }
        if (!Wars.enemies(v.id()).isEmpty()) {
            if (Market.stock(level, v.id(), s -> s.is(Items.ARROW)) < 64) want.add(new Object[]{ Items.ARROW, 64, "for the war stores" });
            if (Market.stock(level, v.id(), s -> s.is(Items.IRON_INGOT)) < 16) want.add(new Object[]{ Items.IRON_INGOT, 16, "for the war stores" });
        }
        int n = 0;
        for (Object[] w : want) {
            if (kept.size() >= MOST) break;
            Item it = (Item) w[0];
            boolean dup = false;
            for (Order o : kept) dup |= o.item == it;
            if (dup) continue;
            int each = priceX100(level, v.id(), new ItemStack(it));
            if (each <= 0) continue;
            kept.add(new Order(day + "-" + (n++), it, Math.max(LEAST, (int) w[1]), 0, each, day + DAYS, (String) w[2]));
        }
        write(v.id(), kept);
    }

    /** A piece's price on the board (hundredths): over the paying price, under what the town sells it for. */
    static int priceX100(ServerLevel level, UUID village, ItemStack one) {
        double bid = Market.bidEach(level, village, one) * PREMIUM;
        double least = PriceIndex.each(level, village, one) * Worth.FLOOR * 0.98;
        return (int) Math.floor(Math.min(bid, least) * 100);
    }

    // ------------------------------------------------------------------ filling one

    /** Deliver what the player carries of this order: up to what it still wants, into the stores, paid at once. */
    static String deliver(ServerLevel level, ServerPlayer p, Villages.Village v, Order o) {
        long day = level.getDayTime() / 24000L;
        if (o.deadline < day) return "That order has run out.";
        int have = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(o.item) && !s.isDamaged() && !Shifts.isShiftItem(s)) have += s.getCount();
        int n = Math.min(have, o.left());
        if (n <= 0) return have <= 0 ? "You've no " + Goods.name(new ItemStack(o.item)).toLowerCase(Locale.ROOT) + " on you." : "That order is filled.";
        int pay = o.pay(n);
        if (pay <= 0) return "Bring a few more: that few isn't worth a coin.";
        if (Ledger.coins(v.id()) < pay) return "The treasury hasn't the coin to pay for that just now.";
        ItemStack lot = new ItemStack(o.item, n);
        ItemStack left = Market.intoStores(level, v.id(), lot);
        int in = n - left.getCount();
        if (in <= 0) return "The stores are full just now.";
        int toTake = in;
        for (ItemStack s : p.getInventory().items) {
            if (toTake <= 0) break;
            if (!s.is(o.item) || s.isDamaged() || Shifts.isShiftItem(s)) continue;
            int k = Math.min(toTake, s.getCount());
            s.shrink(k);
            toTake -= k;
        }
        p.getInventory().setChanged();
        pay = o.pay(in);
        o.filled += in;
        List<Order> all = read(v.id());
        for (Order x : all) if (x.id.equals(o.id)) x.filled = o.filled;
        write(v.id(), all);
        Earnings.fromTreasury(p, v.id(), pay, Earnings.Source.WANTED, Villages.name(v.id()), "for " + Goods.words(new ItemStack(o.item), in) + " (wanted " + o.why + ")");
        if (o.left() <= 0) Villages.tell(v.id(), day, p.getName().getString() + " filled the board's order for " + Goods.words(new ItemStack(o.item), o.count) + " " + o.why);
        return "";
    }

    /** At the store's counter (the Sell tab's Deliver). */
    static ServicePoints.Result deliverAt(ServicePoints.Visit visit, String id) {
        ServerPlayer p = visit.player();
        if (p == null) return ServicePoints.Result.no("");
        for (Order o : orders(visit.level(), visit.village())) {
            if (!o.id.equals(id)) continue;
            String said = deliver(visit.level(), p, visit.village(), o);
            return said.isEmpty() ? ServicePoints.Result.done("Delivered to the Wanted board.") : ServicePoints.Result.no(said);
        }
        return ServicePoints.Result.no("That order has come down.");
    }

    /** Goods in hand at the town board or the storehouse: an order for them is filled. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p) || e.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty() || Market.isCoin(held)) return;
        BlockPos at = e.getPos();
        boolean board = level.getBlockState(at).getBlock() instanceof VillageBoardBlock;
        if (!board && !(level.getBlockEntity(at) instanceof StorehouseBlockEntity)) return;
        if (Shifts.onShift(p) && !board) return;                              // a hauler loading up at the storehouse
        Villages.Village v = PlayerServices.townAt(level, at);
        if (v == null) return;
        for (Order o : orders(level, v)) {
            if (!held.is(o.item) || o.left() <= 0) continue;
            String said = deliver(level, p, v, o);
            Earnings.tell(p, said);
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
    }

    // ------------------------------------------------------------------ shown

    /** The store's Sell tab's rows: each open order, what it pays, how many you carry. */
    static ListTag ordersTag(ServicePoints.Visit visit) {
        ListTag out = new ListTag();
        ServerPlayer p = visit.player();
        long day = visit.level().getDayTime() / 24000L;
        for (Order o : orders(visit.level(), visit.village())) {
            if (o.left() <= 0) continue;
            CompoundTag t = new CompoundTag();
            t.putString("id", o.id);
            t.put("item", new ItemStack(o.item).save(visit.level().registryAccess()));
            t.putString("name", Goods.name(new ItemStack(o.item)));
            t.putInt("count", o.left());
            t.putInt("price", o.pay(o.left()));
            t.putString("why", o.why);
            t.putInt("days", (int) (o.deadline - day));
            int have = 0;
            if (p != null) for (ItemStack s : p.getInventory().items) if (s.is(o.item) && !Shifts.isShiftItem(s)) have += s.getCount();
            t.putInt("have", have);
            out.add(t);
        }
        return out;
    }

    /** The town board's line: "Wanted: 32 wheat for the Iron Age (14c, 3 days)...". Null with none up. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        List<String> parts = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        for (Order o : orders(level, v)) {
            if (o.left() <= 0 || parts.size() >= 3) continue;
            long d = o.deadline - day;
            parts.add(Goods.words(new ItemStack(o.item), o.left()) + " " + o.why + " (" + o.pay(o.left()) + "c, " + (d <= 0 ? "today" : d + (d == 1 ? " day" : " days")) + ")");
        }
        return parts.isEmpty() ? null : "Wanted: " + String.join("; ", parts) + ". Bring it to the board or the storehouse.";
    }

    // ------------------------------------------------------------------ kept in the ledger

    static List<Order> read(UUID village) {
        List<Order> out = new ArrayList<>();
        String s = Ledger.note(village, "wanted.orders");
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] f = part.split("\\|");
            if (f.length < 7) continue;
            try {
                Item it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(f[1]));
                if (it == Items.AIR) continue;
                out.add(new Order(f[0], it, Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]), Long.parseLong(f[5]), f[6]));
            } catch (RuntimeException e) {
                // a line from an older or damaged note: skipped
            }
        }
        return out;
    }

    static void write(UUID village, List<Order> orders) {
        StringBuilder sb = new StringBuilder();
        for (Order o : orders) {
            if (sb.length() > 0) sb.append(';');
            sb.append(o.id).append('|').append(BuiltInRegistries.ITEM.getKey(o.item)).append('|').append(o.count).append('|').append(o.filled)
                .append('|').append(o.eachX100).append('|').append(o.deadline).append('|').append(o.why.replace("|", " ").replace(";", ","));
        }
        Ledger.note(village, "wanted.orders", sb.toString());
    }

    /** Tests: post an order now (the board's own price unless given). */
    public static Order postForTests(ServerLevel level, Villages.Village v, Item item, int count) {
        long day = level.getDayTime() / 24000L;
        Ledger.note(v.id(), "wanted.day", Long.toString(day));
        List<Order> all = read(v.id());
        Order o = new Order(day + "-t" + all.size(), item, count, 0, priceX100(level, v.id(), new ItemStack(item)), day + DAYS, "for the test");
        all.add(o);
        write(v.id(), all);
        return o;
    }

    /** Tests: deliver to an order as the board would. */
    public static String deliverForTests(ServerLevel level, ServerPlayer p, Villages.Village v, String id) {
        for (Order o : orders(level, v)) if (o.id.equals(id)) return deliver(level, p, v, o);
        return "no such order";
    }
}
