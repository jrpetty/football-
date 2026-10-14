package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * [market] The seam between the town economy and whatever carries its goods. The economy decides WHAT goes, WHERE,
 * WHEN and AT WHAT PRICE (TradeDeals, the leader's routes); a carrier (the trade wagons, or, with none, the road itself)
 * decides HOW: it loads, travels and unloads, and reports the shipment delivered or lost. This class settles both ends.
 *
 * <ul>
 * <li><b>Dispatch.</b> {@link #dispatch} takes the manifest out of the origin's stores there and then (nothing ships
 *     that is not there; nothing is in two places), records the shipment in the origin's ledger, and offers it to the
 *     registered {@link Carrier}. With no carrier, or one that declines, it goes by the road: it arrives after the time
 *     a laden cart takes for the distance ({@link #ROAD_BLOCKS_A_DAY}), with a small chance of loss on a long road.</li>
 * <li><b>Delivered.</b> {@link #delivered} puts what arrived into the destination's stores, has the destination pay the
 *     coin due on delivery to the origin (as far as its treasury runs; the rest is owed and the listener told), books
 *     both towns' accounts, and tells the shipment's {@link Listener} (the contract's bookkeeping).</li>
 * <li><b>Lost.</b> {@link #lost} returns what was saved to the origin's stores, pays nothing, and tells the listener.</li>
 * </ul>
 * A shipment outlasts a restart (the origin's ledger); one the carrier forgot is brought home by the road on its due day
 * and a week after it (never silently lost).
 */
public final class Shipments {

    private Shipments() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** How far a laden cart or pack train goes in a day by the road (the fallback carrier). */
    public static final int ROAD_BLOCKS_A_DAY = 2400;
    /** The fallback carrier's chance in the hundred of losing a load, per thousand blocks of road (bandits, a river). */
    public static final double ROAD_LOSS_PER_THOUSAND = 1.0;
    /** A shipment past its due day by this many days is settled by the road (its carrier lost track of it). */
    public static final int OVERDUE_DAYS = 7;
    /** The ledger's key for a shipment. */
    static final String KEY = "ship/";

    // ------------------------------------------------------------------ the seam

    /**
     * One shipment: its id, the towns it goes from and to, the contract it is for ("deal/&lt;id&gt;" or any key the
     * listener knows), what it carries, the coin the destination pays on delivery, and the day it set out and is due.
     */
    public record Shipment(UUID id, UUID from, UUID to, String contract, List<ItemStack> manifest, int coinDue, long dispatched,
                           long due) {
        public Shipment {
            manifest = List.copyOf(manifest);
        }

        /** How many things it carries, all told. */
        public int pieces() {
            int n = 0;
            for (ItemStack s : manifest) n += s.getCount();
            return n;
        }
    }

    /** What carries a shipment (the trade wagons). */
    public interface Carrier {
        /**
         * Take this shipment from its origin to its destination: true if a carrier has it (it must later call
         * {@link Shipments#delivered} or {@link Shipments#lost} with its id), false to leave it to the road.
         */
        boolean carry(ServerLevel level, Shipment s);
    }

    /** Who is told how a shipment went (a contract's bookkeeping), by the contract key's prefix. */
    public interface Listener {
        void delivered(ServerLevel level, Shipment s, List<ItemStack> arrived, int paid);

        void lost(ServerLevel level, Shipment s, String why);
    }

    private static volatile Carrier carrier;
    private static final Map<String, Listener> LISTENERS = new ConcurrentHashMap<>();
    /** The shipments the road carries, by id: when each arrives (game time) and whether it comes to grief. */
    private static final Map<UUID, long[]> ROAD = new ConcurrentHashMap<>();
    /** The shipments under way (a cache of the ledgers), by id. */
    private static final Map<UUID, Shipment> OPEN = new ConcurrentHashMap<>();
    private static final List<Shipment> SETTLED = new CopyOnWriteArrayList<>();
    private static final java.util.Set<UUID> READ = ConcurrentHashMap.newKeySet();

    /** The trade wagons register here; null gives the road back the carrying. */
    public static void register(@Nullable Carrier c) {
        carrier = c;
    }

    /** Is a carrier registered (the trade wagons)? With none, the town's own caravans carry its deals, and the road the rest. */
    public static boolean hasCarrier() {
        return carrier != null;
    }

    /** A contract keeper listens for its own shipments: those whose contract key starts with {@code prefix}. */
    public static void listen(String prefix, Listener l) {
        LISTENERS.put(prefix, l);
    }

    public static void resetForTests() {
        ROAD.clear();
        OPEN.clear();
        SETTLED.clear();
        READ.clear();
    }

    // ------------------------------------------------------------------ dispatch

    /**
     * Send these goods from one town's stores to another's, for this contract, the destination paying {@code coinDue}
     * on delivery. Takes them out of the origin's stores now: null (and nothing taken) if the stores have not all of it.
     */
    @Nullable
    public static Shipment dispatch(ServerLevel level, UUID from, UUID to, String contract, List<ItemStack> manifest, int coinDue) {
        Villages.Village origin = Villages.get(from), dest = Villages.get(to);
        if (origin == null || dest == null || from.equals(to) || manifest.isEmpty()) return null;
        for (ItemStack s : manifest) {
            if (s.isEmpty()) return null;
            if (Market.stock(level, from, x -> ItemStack.isSameItemSameComponents(x, s)) < s.getCount()) return null;
        }
        List<ItemStack> taken = new ArrayList<>();
        for (ItemStack s : manifest) {
            if (!TownWork.take(level, origin, x -> ItemStack.isSameItemSameComponents(x, s), s.getCount())) {
                for (ItemStack back : taken) Market.intoStores(level, from, back);        // all or nothing
                return null;
            }
            taken.add(s.copy());
        }
        long day = level.getDayTime() / 24000L;
        double blocks = Math.sqrt(origin.centre().distSqr(dest.centre()));
        long days = Math.max(1, (long) Math.ceil(blocks / ROAD_BLOCKS_A_DAY));
        Shipment s = new Shipment(UUID.randomUUID(), from, to, contract, taken, Math.max(0, coinDue), day, day + days);
        OPEN.put(s.id(), s);
        save(s);
        Carrier c = carrier;
        boolean carried = false;
        if (c != null) {
            try {
                carried = c.carry(level, s);
            } catch (RuntimeException e) {
                LOG.warn("[MCA-SHIP] the carrier would not take {}: {}", s.id(), e.toString());
            }
        }
        if (!carried) {
            long ticks = Math.max(1200L, (long) (blocks / ROAD_BLOCKS_A_DAY * 24000L));
            boolean grief = level.getRandom().nextDouble() * 100 < ROAD_LOSS_PER_THOUSAND * blocks / 1000.0;
            ROAD.put(s.id(), new long[]{ level.getGameTime() + ticks, grief ? 1 : 0 });
        }
        Villages.tell(from, day, "a load of " + words(s) + " set out for " + Villages.name(to));
        return s;
    }

    // ------------------------------------------------------------------ settling

    /** The carrier's report: the shipment arrived with these goods (all of it, or what was left). Settles it. */
    public static void delivered(ServerLevel level, UUID shipment, List<ItemStack> arrived) {
        Shipment s = OPEN.remove(shipment);
        ROAD.remove(shipment);
        if (s == null) return;                                                   // settled already (a double report)
        forget(s);
        int pieces = 0;
        for (ItemStack a : arrived) {
            if (a.isEmpty()) continue;
            pieces += a.getCount();
            ItemStack left = Market.intoStores(level, s.to(), a.copy());
            if (!left.isEmpty()) Market.intoStores(level, s.from(), left);       // no room at all: back to the sender's books
        }
        // Paid on delivery, for what arrived: the destination's treasury to the origin's.
        int due = s.pieces() <= 0 ? 0 : (int) Math.round(s.coinDue() * Math.min(1.0, pieces / (double) s.pieces()));
        int paid = due <= 0 ? 0 : Ledger.takeCoins(s.to(), due);
        if (paid > 0) {
            Ledger.addCoins(s.from(), paid);
            Economy.sold(s.from(), paid);
            Economy.spent(s.to(), paid);
        }
        long day = level.getDayTime() / 24000L;
        Villages.tell(s.to(), day, "a load of " + words(s) + " came in from " + Villages.name(s.from()) + (paid > 0 ? ", paid " + paid + " coin" : ""));
        SETTLED.add(s);
        if (SETTLED.size() > 64) SETTLED.remove(0);
        Listener l = listenerFor(s);
        if (l != null) l.delivered(level, s, arrived, paid);
    }

    /** The carrier's report: the shipment was lost (a raid, a river); what was saved goes home. Settles it. */
    public static void lost(ServerLevel level, UUID shipment, List<ItemStack> saved, String why) {
        Shipment s = OPEN.remove(shipment);
        ROAD.remove(shipment);
        if (s == null) return;
        forget(s);
        for (ItemStack k : saved) if (!k.isEmpty()) Market.intoStores(level, s.from(), k.copy());
        Villages.tell(s.from(), level.getDayTime() / 24000L, "a load of " + words(s) + " for " + Villages.name(s.to()) + " was lost: " + why);
        Listener l = listenerFor(s);
        if (l != null) l.lost(level, s, why);
    }

    /** The road's own carrying, once a few seconds (TownLife's server tick): what has arrived arrives. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % 100 != 0) return;
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        for (Map.Entry<UUID, long[]> e : new ArrayList<>(ROAD.entrySet())) {
            if (now < e.getValue()[0]) continue;
            Shipment s = OPEN.get(e.getKey());
            if (s == null) { ROAD.remove(e.getKey()); continue; }
            if (e.getValue()[1] == 1) lost(level, s.id(), List.of(), "it never came in by the road");
            else delivered(level, s.id(), s.manifest());
        }
        // A carrier that lost track of a shipment: the road settles it a week after its due day.
        for (Shipment s : new ArrayList<>(OPEN.values())) {
            if (!ROAD.containsKey(s.id()) && day > s.due() + OVERDUE_DAYS) delivered(level, s.id(), s.manifest());
        }
    }

    // ------------------------------------------------------------------ what is under way

    /** The shipments under way from or to this town. */
    public static List<Shipment> inTransit(UUID village) {
        read(village);
        List<Shipment> out = new ArrayList<>();
        for (Shipment s : OPEN.values()) if (s.from().equals(village) || s.to().equals(village)) out.add(s);
        out.sort((a, b) -> Long.compare(a.due(), b.due()));
        return out;
    }

    /** The shipments settled lately (the Trade page's last deliveries), newest last. */
    public static List<Shipment> settled() {
        return Collections.unmodifiableList(SETTLED);
    }

    /** One shipment under way, by id, or null. */
    @Nullable
    public static Shipment get(UUID id) {
        return OPEN.get(id);
    }

    /** "16 bread and 8 logs". */
    public static String words(Shipment s) {
        List<String> parts = new ArrayList<>();
        for (ItemStack x : s.manifest()) parts.add(x.getCount() + " " + x.getHoverName().getString().toLowerCase(java.util.Locale.ROOT));
        return parts.isEmpty() ? "nothing" : String.join(" and ", parts);
    }

    @Nullable
    private static Listener listenerFor(Shipment s) {
        for (Map.Entry<String, Listener> e : LISTENERS.entrySet()) if (s.contract().startsWith(e.getKey())) return e.getValue();
        return null;
    }

    // ------------------------------------------------------------------ kept with the world

    private static void save(Shipment s) {
        StringBuilder m = new StringBuilder();
        for (ItemStack x : s.manifest()) {
            if (m.length() > 0) m.append(',');
            m.append(BuiltInRegistries.ITEM.getKey(x.getItem())).append('*').append(x.getCount());
        }
        Ledger.note(s.from(), KEY + s.id(), String.join("|", "v1", s.to().toString(), s.contract().replace("|", "/"), m.toString(),
            Integer.toString(s.coinDue()), Long.toString(s.dispatched()), Long.toString(s.due())));
    }

    private static void forget(Shipment s) {
        Ledger.note(s.from(), KEY + s.id(), "");
    }

    /** A town's shipments under way, read from its ledger once (after a restart the road carries them in). */
    private static void read(UUID village) {
        if (!READ.add(village)) return;
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith(KEY) || e.getValue() == null || e.getValue().isEmpty()) continue;
            try {
                UUID id = UUID.fromString(e.getKey().substring(KEY.length()));
                if (OPEN.containsKey(id)) continue;
                String[] p = e.getValue().split("\\|", -1);
                if (p.length < 7 || !p[0].equals("v1")) continue;
                List<ItemStack> manifest = new ArrayList<>();
                for (String it : p[3].split(",")) {
                    String[] q = it.split("\\*");
                    if (q.length != 2) continue;
                    Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(q[0]));
                    if (item != Items.AIR) manifest.add(new ItemStack(item, Integer.parseInt(q[1])));
                }
                Shipment s = new Shipment(id, village, UUID.fromString(p[1]), p[2], manifest, Integer.parseInt(p[4]), Long.parseLong(p[5]),
                    Long.parseLong(p[6]));
                OPEN.put(id, s);
                // After a restart: with no carrier the road brings it in now; a carrier's own has its week (OVERDUE_DAYS).
                if (carrier == null) ROAD.putIfAbsent(id, new long[]{ 0L, 0L });
            } catch (RuntimeException ex) {
                LOG.warn("[MCA-SHIP] a shipment in {}'s ledger would not read: {}", village, e.getKey());
            }
        }
    }

    /** Every known town's shipments read in (the morning, so a restart's loads come in without anybody asking). */
    public static void readAll() {
        for (Villages.Village v : Villages.every()) read(v.id());
    }
}
