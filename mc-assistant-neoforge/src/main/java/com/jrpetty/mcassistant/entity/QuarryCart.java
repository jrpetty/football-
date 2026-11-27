package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [quarry2] The quarry's handcart: parked at the quarry's rim, loaded by the quarrymen as they bring the stone up, and
 * pushed by one of them along the way to wherever the stone is wanted, unloaded there, and pushed home again.
 *
 * <p><b>The cart.</b> One of the town's handcarts (a TradeWagon of the handcart kind, in the town's colours, so it looks like
 * the town's own carts), made once the stone yard is laid out and stood at the cart station beside the quarry's gate, square
 * to the way into town. Its load is the real thing (its bed, shown on it).
 *
 * <p><b>Loading.</b> A quarryman with a load walks it to the cart instead of the yard while the cart is home (the yard
 * takes the loads while it is out). It sets off when it holds {@link #FULL}, or {@link #SOME} when the stone is wanted
 * now: a build waiting on it, the stores low, the yard bare. The quarryman whose load made it ready pushes it.
 *
 * <p><b>Where it goes.</b> By need: a building site waiting on stone (the nearest first: BuildOrders' commissions, their
 * shortage marked with where they stand), the mason at the smeltery while it wants stone to dress, the storehouse while
 * the stores are under what the town's works want; the yard takes the rest (it is the first stop, on the way, where the
 * dressed stone and the bricks a later stop wants are picked up too). Then home to the rim.
 *
 * <p><b>The drive.</b> The driver walks to the handles and draws it: the driver walks (its own way-finding, a leg at a
 * time on a long way, as the quarrymen walk out), and the cart follows the very way it walked, its middle a handle's length
 * behind, facing it, the gates opened for it as for the town's wagon. So the cart goes wherever its driver can, round
 * corners and up the steps of the way, and nobody is ever put anywhere.
 *
 * <p>The board's Stone page says where it is and what it carries ("Cart: taking 96 stone to Ann's cottage").
 */
public final class QuarryCart {

    private QuarryCart() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    static final String CART = "quarry.cart", TRIP = "quarry.cart.trip", WORDS = "quarry.cart.words", LOG_KEY = "quarry.cart.log",
        LAST = "quarry.cart.last", AT = "quarry.cart.at";
    /** It sets off with this much aboard; or with {@link #SOME} when the stone is wanted now. */
    public static final int FULL = 96, SOME = 16;
    /** A leg of the way, blocks at most, asked of the way-finder at a time. */
    static final double LEG = 40.0;
    /** Near enough a stop to unload there (blocks, flat); home, parked on its station. */
    static final double THERE = 4.5, HOME = 0.9;
    /** Ticks unloading takes at a stop. */
    static final int UNLOAD_TICKS = 60;
    /** Ticks a stop is tried before the load is handed over where the cart stands (porters carry it the rest of the way). */
    static final int STOP_TRIES = 3600;

    private static volatile boolean on = true;

    /** Tests: the quarry's cart in use (on) or not (the loads walked to the yard, as before it). */
    public static void onForTests(boolean b) { on = b; }

    public static boolean on() { return on; }

    // ------------------------------------------------------------------ the stops

    /** A stop on a trip: what kind, where, its words, what is set down there and what is taken on. */
    record Stop(String kind, BlockPos at, String label, Map<Item, Integer> drop, Map<Item, Integer> pick) {
        String encode() {
            return kind + "|" + at.getX() + "|" + at.getY() + "|" + at.getZ() + "|" + label.replace("|", "/").replace("~", "-") + "|" + items(drop) + "|" + items(pick);
        }

        static Stop decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 7) return null;
            try {
                return new Stop(p[0], new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])), p[4], items(p[5]), items(p[6]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        int count() {
            int n = 0;
            for (int c : drop.values()) n += c;
            return n;
        }
    }

    static String items(Map<Item, Integer> m) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : m.entrySet()) if (e.getValue() > 0) out.add(BuiltInRegistries.ITEM.getKey(e.getKey()) + "=" + e.getValue());
        return String.join(";", out);
    }

    static Map<Item, Integer> items(String s) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            ResourceLocation id = ResourceLocation.tryParse(kv[0]);
            if (id == null) continue;
            Item it = BuiltInRegistries.ITEM.get(id);
            try { out.merge(it, Integer.parseInt(kv[1]), Integer::sum); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static List<Stop> trip(UUID town) {
        List<Stop> out = new ArrayList<>();
        String s = Ledger.note(town, TRIP);
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split("~")) {
            Stop st = Stop.decode(part);
            if (st != null) out.add(st);
        }
        return out;
    }

    static void saveTrip(UUID town, List<Stop> stops) {
        List<String> parts = new ArrayList<>();
        for (Stop s : stops) parts.add(s.encode());
        if (parts.isEmpty()) Ledger.forget(town, TRIP);
        else Ledger.note(town, TRIP, String.join("~", parts));
    }

    // ------------------------------------------------------------------ the cart and its station

    /** The cart station: beside the quarry's way out (past the stone yard's side), the cart facing the town. */
    @Nullable
    static BlockPos station(ServerLevel level, UUID town) {
        Quarry.Site s = Quarry.site(town);
        if (s == null) return null;
        BlockPos o = Quarry.outside(level, s);
        BlockPos p = o.relative(s.away().getClockWise(), StoneYard.HALF_W + 3);
        if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return null;
        int y = Quarry.surface(level, p.getX(), p.getZ());
        return y == Integer.MIN_VALUE ? null : new BlockPos(p.getX(), y + 1, p.getZ());
    }

    static float stationYaw(UUID town) {
        Quarry.Site s = Quarry.site(town);
        return s == null ? 0.0F : s.away().getOpposite().toYRot();
    }

    /** The town's quarry cart, if it is in the world just now. */
    @Nullable
    public static TradeWagon cart(ServerLevel level, UUID town) {
        String id = Ledger.note(town, CART);
        if (id == null || id.isEmpty()) return null;
        try {
            Entity e = level.getEntity(UUID.fromString(id));
            return e instanceof TradeWagon w && w.isAlive() ? w : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** On the quarry's round: a cart made for it once its yard is laid out (and a new one if the old is gone for good). */
    static void ensure(ServerLevel level, Villages.Village v) {
        if (!on || Quarry.stoodDown()) return;
        UUID id = v.id();
        if (Ledger.note(id, "yard.laid") == null || Quarry.site(id) == null) return;
        if (cart(level, id) != null) return;
        String had = Ledger.note(id, CART);
        if (had != null && !had.isEmpty()) {
            // Out of the world (its ground not loaded): looked for again later. Gone where its ground is loaded: made again.
            BlockPos last = lastAt(id);
            if (last != null && !level.isLoaded(last)) return;
        }
        BlockPos at = station(level, id);
        if (at == null || !level.isLoaded(at)) return;
        TradeWagon w = WagonRegistry.TRADE_WAGON.get().create(level);
        if (w == null) return;
        w.town = id;
        w.quarry = true;
        w.setKind(WagonKind.HANDCART);
        Wagons.colours(w, id);
        w.park = at;
        w.parkYaw = stationYaw(id);
        w.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, w.parkYaw, 0.0F);
        w.setPhase(TradeWagon.Phase.PARKED);
        if (!level.addFreshEntity(w)) return;
        Ledger.note(id, CART, w.getUUID().toString());
        Ledger.forget(id, TRIP);
        noteAt(w);
        words(id, "parked at the quarry's rim, empty");
        if (had == null) Villages.tell(id, level.getDayTime() / 24000L, "a handcart was stood at the quarry's rim, to carry its stone to wherever it is wanted");
    }

    @Nullable
    private static BlockPos lastAt(UUID town) {
        String s = Ledger.note(town, AT);
        if (s == null) return null;
        String[] p = s.split(",");
        try {
            return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void noteAt(TradeWagon w) {
        if (w.town == null) return;
        String s = w.getBlockX() + "," + w.getBlockY() + "," + w.getBlockZ();
        if (!s.equals(Ledger.note(w.town, AT))) Ledger.note(w.town, AT, s);
    }

    /** The cart home at its station, waiting for loads (null otherwise). */
    @Nullable
    static TradeWagon home(ServerLevel level, UUID town) {
        if (!on) return null;
        TradeWagon w = cart(level, town);
        if (w == null || w.phase != TradeWagon.Phase.PARKED || w.driver != null) return null;
        return w;
    }

    /** Where a quarryman stands to tip its load into the cart: beside its bed. */
    static BlockPos loadSpot(ServerLevel level, TradeWagon w) {
        Vec3 p = w.spot(level, -0.4, 1.4);
        return BlockPos.containing(p.x, p.y, p.z);
    }

    static int aboard(TradeWagon w) {
        int n = 0;
        for (int i = 0; i < w.cargo.getContainerSize(); i++) n += w.cargo.getItem(i).getCount();
        return n;
    }

    static Map<Item, Integer> load(TradeWagon w) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < w.cargo.getContainerSize(); i++) {
            ItemStack s = w.cargo.getItem(i);
            if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return out;
    }

    /** Put so many of a thing aboard; what would not fit is returned. */
    static int put(TradeWagon w, Item it, int n) {
        int left = n;
        while (left > 0) {
            ItemStack rest = Wagons.put(w, new ItemStack(it, Math.min(left, it.getDefaultMaxStackSize())));
            int took = Math.min(left, it.getDefaultMaxStackSize()) - rest.getCount();
            if (took <= 0) break;
            left -= took;
        }
        w.cargoChanged();
        w.setShownFill(w.realFill());
        return left;
    }

    /** Take so many of a thing off; how many came off. */
    static int take(TradeWagon w, Item it, int n) {
        int got = 0;
        for (int i = 0; i < w.cargo.getContainerSize() && got < n; i++) {
            ItemStack s = w.cargo.getItem(i);
            if (s.isEmpty() || s.getItem() != it) continue;
            int k = Math.min(n - got, s.getCount());
            s.shrink(k);
            if (s.isEmpty()) w.cargo.setItem(i, ItemStack.EMPTY);
            got += k;
        }
        w.cargo.setChanged();
        w.cargoChanged();
        w.setShownFill(w.realFill());
        return got;
    }

    /** A quarryman's load tipped into the cart. What would not fit is left in the map (it goes to the yard). */
    static void tipIn(ServerLevel level, TradeWagon w, Map<Item, Integer> m) {
        for (Map.Entry<Item, Integer> e : new ArrayList<>(m.entrySet())) {
            int left = put(w, e.getKey(), e.getValue());
            if (left <= 0) m.remove(e.getKey());
            else m.put(e.getKey(), left);
        }
        level.playSound(null, w.blockPosition(), SoundEvents.STONE_PLACE, SoundSource.NEUTRAL, 0.9F, 0.8F);
        words(w.town, "parked at the quarry's rim, loading: " + aboard(w) + " stone");
    }

    /** Is the stone wanted now (a build waiting on it, the stores low, the yard bare)? */
    static boolean wantedNow(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!StoneYard.waiting(id).isEmpty()) return true;
        if (StoneYard.total(id) < Quarry.LOAD * 2) return true;
        return StoneYard.deliveryDue(level, v) != null || Market.stock(level, id, s -> s.is(Items.COBBLESTONE)) < StoneYard.STORES_ROUGH;
    }

    /** Ready to set off: full, or with some aboard when it is wanted now. */
    static boolean ready(ServerLevel level, Villages.Village v, TradeWagon w) {
        int n = aboard(w);
        return n >= FULL || n >= SOME && wantedNow(level, v);
    }

    // ------------------------------------------------------------------ the trip planned

    private record Need(String kind, BlockPos at, String label, Item item, int n) {}

    /** The stops for a trip from the rim with this load: the yard first, then by need (sites nearest first), then home. */
    static List<Stop> plan(ServerLevel level, Villages.Village v, TradeWagon w) {
        UUID id = v.id();
        List<Need> needs = new ArrayList<>();
        StoneYard.Site yard = StoneYard.site(id);
        BlockPos from = yard != null ? yard.centre() : w.blockPosition();
        // Building sites waiting on the yard's stone, the nearest first.
        List<StoneYard.Wait> waits = new ArrayList<>(StoneYard.waiting(id));
        waits.sort((a, b) -> Double.compare(a.at().distSqr(from), b.at().distSqr(from)));
        for (StoneYard.Wait wt : waits) needs.add(new Need("site", wt.at(), wt.who(), wt.item(), Math.min(192, wt.wanted())));
        // The mason, wanting stone to dress at the smeltery.
        BlockPos smeltery = Villages.builtAt(id, "smeltery");
        if (smeltery != null && Masonry.wantsStone(level, v)) needs.add(new Need("mason", smeltery, "the mason at the smeltery", Items.COBBLESTONE, 32));
        // The storehouse, under what the town's works want.
        BlockPos stores = storesSpot(level, v);
        Object[][] wants = { { Items.COBBLESTONE, StoneYard.STORES_ROUGH }, { Items.STONE_BRICKS, StoneYard.STORES_DRESSED }, { Items.BRICKS, StoneYard.STORES_BRICK } };
        for (Object[] wn : wants) {
            Item it = (Item) wn[0];
            int in = Market.stock(level, id, s -> s.is(it));
            int want = (Integer) wn[1];
            if (in < want) needs.add(new Need("stores", stores, "the storehouse", it, Math.min(128, want - in + 16)));
        }
        Map<Item, Integer> cargo = load(w), yardStock = StoneYard.stock(id), pick = new LinkedHashMap<>();
        Map<String, Stop> town = new LinkedHashMap<>();
        for (Need n : needs) {
            int a = Math.min(n.n(), cargo.getOrDefault(n.item(), 0));
            cargo.merge(n.item(), -a, Integer::sum);
            int b = 0;
            if (yard != null && a < n.n()) {
                b = Math.min(n.n() - a, yardStock.getOrDefault(n.item(), 0) - pick.getOrDefault(n.item(), 0));
                b = Math.max(0, Math.min(b, 192));
                if (b > 0) pick.merge(n.item(), b, Integer::sum);
            }
            if (a + b <= 0) continue;
            String key = n.kind() + "@" + n.at().asLong();
            Stop st = town.computeIfAbsent(key, k -> new Stop(n.kind(), n.at(), n.label(), new LinkedHashMap<>(), new LinkedHashMap<>()));
            st.drop().merge(n.item(), a + b, Integer::sum);
        }
        List<Stop> out = new ArrayList<>();
        Map<Item, Integer> rest = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : cargo.entrySet()) if (e.getValue() > 0) rest.put(e.getKey(), e.getValue());
        if (yard != null && (!rest.isEmpty() || !pick.isEmpty())) {
            BlockPos gate = StoneYard.ground(StoneYard.at(yard, 0, -StoneYard.HALF_D - 2), yard);
            out.add(new Stop("yard", gate, "the stone yard", rest, pick));
        }
        // The town's stops, each the nearest left to the one before.
        List<Stop> left = new ArrayList<>(town.values());
        BlockPos here = out.isEmpty() ? w.blockPosition() : out.get(0).at();
        while (!left.isEmpty()) {
            Stop best = null;
            for (Stop s : left) if (best == null || s.at().distSqr(here) < best.at().distSqr(here)) best = s;
            left.remove(best);
            out.add(best);
            here = best.at();
        }
        BlockPos st = station(level, id);
        out.add(new Stop("home", st != null ? st : w.park != null ? w.park : w.blockPosition(), "the quarry", new LinkedHashMap<>(), new LinkedHashMap<>()));
        return out;
    }

    static BlockPos storesSpot(ServerLevel level, Villages.Village v) {
        List<BlockPos> chests = Villages.storeChests(level, v.id());
        if (!chests.isEmpty()) return chests.get(0);
        BlockPos sh = Villages.builtAt(v.id(), "storehouse");
        if (sh != null) return sh;
        BlockPos st = Villages.builtAt(v.id(), "storage");
        return st != null ? st : v.centre();
    }

    /** A quarryman sets off with the cart: the trip planned, the quarryman its driver. */
    static boolean setOff(ServerLevel level, Villages.Village v, TradeWagon w, VillageFolkEntity f) {
        List<Stop> stops = plan(level, v, w);
        if (stops.size() <= 1) return false;
        saveTrip(v.id(), stops);
        w.driver = f.getUUID();
        w.setRoute(null);
        w.setPhase(TradeWagon.Phase.ROAD);
        HITCHED.remove(w.getUUID());
        stopSince.put(w.getUUID(), level.getGameTime());
        Stop first = stops.get(0);
        FolkTalk.speak(f, "That's a load. " + (first.kind().equals("yard") && stops.size() == 2 ? "Down to the yard with it."
            : "Off to " + stops.get(stops.size() > 2 && first.kind().equals("yard") ? 1 : 0).label() + " with it."));
        words(v.id(), wordsFor(w, first));
        LOG.info("[MCA-QUARRY] {}'s quarry cart sets off with {} stone: {}", Villages.name(v.id()), aboard(w), describe(stops));
        return true;
    }

    static String describe(List<Stop> stops) {
        List<String> out = new ArrayList<>();
        for (Stop s : stops) out.add(s.label() + (s.count() > 0 ? " (" + s.count() + ")" : "") + (s.pick().isEmpty() ? "" : " +" + items(s.pick())));
        return String.join(" -> ", out);
    }

    /** The cart wants a driver (one left it on the way, or it stands out on the road with nobody at its handles). */
    @Nullable
    static TradeWagon wantsDriver(ServerLevel level, UUID town) {
        TradeWagon w = cart(level, town);
        if (w == null || w.driver != null) return null;
        return w.phase == TradeWagon.Phase.ROAD || w.phase == TradeWagon.Phase.UNLOADING || w.phase == TradeWagon.Phase.FETCH ? w : null;
    }

    static void takeUp(TradeWagon w, VillageFolkEntity f) {
        w.driver = f.getUUID();
        if (w.phase == TradeWagon.Phase.FETCH) w.setPhase(TradeWagon.Phase.ROAD);
    }

    /** Is this folk the quarry cart's driver just now? */
    public static boolean driving(ServerLevel level, VillageFolkEntity f) {
        if (f.ownerId() == null) return false;
        TradeWagon w = cart(level, f.ownerId());
        return w != null && f.getUUID().equals(w.driver);
    }

    // ------------------------------------------------------------------ the cart's tick (TradeWagon.tick, by Wagons.tick)

    private static final Map<UUID, Long> stopSince = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Long> driverAway = new java.util.concurrent.ConcurrentHashMap<>();

    static void tick(ServerLevel level, TradeWagon w) {
        if (w.town == null || w.tickCount % 100 == 17 && Villages.get(w.town) == null) {
            w.wrecked = true;
            w.discard();
            return;
        }
        if (w.tickCount % 100 == 13 && !w.getUUID().toString().equals(Ledger.note(w.town, CART))) {
            // A stray (a copy after a mishap): its load to the yard, and gone.
            Map<Item, Integer> m = load(w);
            if (!m.isEmpty()) StoneYard.receive(level, w.town, m);
            w.wrecked = true;
            w.discard();
            return;
        }
        if (w.tickCount % 20 == 0) noteAt(w);
        switch (w.phase) {
            case ROAD -> road(level, w);
            case UNLOADING -> unloading(level, w);
            case FETCH -> fetch(level, w);
            default -> parked(level, w);
        }
    }

    private static void parked(ServerLevel level, TradeWagon w) {
        if (w.tickCount % 40 != 0) return;
        if (w.driver != null) w.driver = null;
        BlockPos st = station(level, w.town);
        if (w.park == null && st != null) { w.park = st; w.parkYaw = stationYaw(w.town); }
        if (st != null && w.distanceToSqr(Vec3.atBottomCenterOf(st)) > 6.0 * 6.0) {
            // Not at its station (the quarry moved on, or it was left on the way): a trip home.
            List<Stop> home = new ArrayList<>();
            home.add(new Stop("home", st, "the quarry", new LinkedHashMap<>(), new LinkedHashMap<>()));
            saveTrip(w.town, home);
            w.setRoute(null);
            w.setPhase(TradeWagon.Phase.FETCH);
            words(w.town, "standing out by " + w.blockPosition().toShortString() + ", waiting for a hand to push it home");
            return;
        }
        if (w.park != null && w.distanceToSqr(Vec3.atBottomCenterOf(w.park)) > 4.0) { w.park = w.blockPosition(); w.parkYaw = w.getYRot(); }
        if (w.getYRot() != w.parkYaw) w.setYRot(w.parkYaw);
        if (w.shownFill() != w.realFill()) w.setShownFill(w.realFill());
        Wagons.releaseWindow(level, w);
        int n = aboard(w);
        words(w.town, n == 0 ? "parked at the quarry's rim, empty" : "parked at the quarry's rim, loading: " + n + " stone");
    }

    private static void fetch(ServerLevel level, TradeWagon w) {
        Wagons.keepWindow(level, w);
        if (w.driver != null) w.setPhase(TradeWagon.Phase.ROAD);
    }

    // ------------------------------------------------------------------ the drive: the driver walks, the cart follows

    /** Where each cart's driver has walked lately (newest last): the cart is drawn along that very way. */
    private static final Map<UUID, java.util.ArrayDeque<Vec3>> TRAIL = new java.util.concurrent.ConcurrentHashMap<>();
    /** Each cart's driver at its handles (hitched) since this tick; best distance to the stop and when it was had. */
    private static final Map<UUID, Long> HITCHED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, double[]> BEST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Long> NOT_BUSY = new java.util.concurrent.ConcurrentHashMap<>();
    /** Blocks between the driver (at the handles) and the cart's middle, along the way walked. */
    static final double DRAW = TradeWagon.HANDLES;
    /** The driver's pace pulling the cart (the folk's own walking, a little slower than its errands). */
    static final double PACE = 0.7;

    /** The spot the driver makes for: the stop, or (home) a little past the station the way it comes, so the cart, drawn
     *  behind, stands on it. */
    static BlockPos goalFor(ServerLevel level, TradeWagon w, Stop stop) {
        if (!stop.kind().equals("home")) return stop.at();
        double dx = stop.at().getX() + 0.5 - w.getX(), dz = stop.at().getZ() + 0.5 - w.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.5) return stop.at();
        return BlockPos.containing(stop.at().getX() + 0.5 + dx / d * 2.0, stop.at().getY(), stop.at().getZ() + 0.5 + dz / d * 2.0);
    }

    @Nullable
    private static VillageFolkEntity driverOf(ServerLevel level, TradeWagon w) {
        if (w.driver == null) return null;
        Entity e = level.getEntity(w.driver);
        if (e instanceof VillageFolkEntity f && f.isAlive()) { driverAway.remove(w.getUUID()); return f; }
        long now = level.getGameTime();
        long since = driverAway.computeIfAbsent(w.getUUID(), k -> now);
        if (now - since > 600) {
            w.driver = null;                                              // gone: the next free hand takes it on
            driverAway.remove(w.getUUID());
            HITCHED.remove(w.getUUID());
            TRAIL.remove(w.getUUID());
            words(w.town, "standing out by " + w.blockPosition().toShortString() + ", waiting for a hand to push it");
        }
        return null;
    }

    private static void road(ServerLevel level, TradeWagon w) {
        Wagons.keepWindow(level, w);
        List<Stop> stops = trip(w.town);
        if (stops.isEmpty()) { arriveHome(level, w); return; }
        Stop stop = stops.get(0);
        VillageFolkEntity d = driverOf(level, w);
        if (d == null) { HITCHED.remove(w.getUUID()); return; }
        long now = level.getGameTime();
        UUID id = w.getUUID();
        // A driver called away (to bed, the bell, the town's work) and not back in a minute: the cart left for another hand.
        if (Quarry.busy(d)) NOT_BUSY.remove(id);
        else if (now - NOT_BUSY.computeIfAbsent(id, k -> now) > 1200) {
            NOT_BUSY.remove(id);
            w.driver = null;
            HITCHED.remove(id);
            words(w.town, "standing out by " + w.blockPosition().toShortString() + ", waiting for a hand to push it");
            return;
        }
        // First the driver to the handles (walking: never put there).
        if (!HITCHED.containsKey(id)) {
            Vec3 at = w.spot(level, DRAW, 0);
            double far = d.position().distanceTo(at);
            long since = stopSince.computeIfAbsent(id, k -> now);
            if (far > 1.0 && !(far < 2.5 && now - since > 100)) {
                if (w.phaseTicks % 20 == 2 || d.getNavigation().isDone()) d.walkTo(Quarry.leg(level, d, BlockPos.containing(at)), PACE);
                return;
            }
            d.getNavigation().stop();
            java.util.ArrayDeque<Vec3> t = new java.util.ArrayDeque<>();
            t.add(w.position());
            t.add(d.position());
            TRAIL.put(id, t);
            HITCHED.put(id, now);
            BEST.remove(id);
            stopSince.put(id, now);
        }
        BlockPos goal = goalFor(level, w, stop);
        double dx = goal.getX() + 0.5 - d.getX(), dz = goal.getZ() + 0.5 - d.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        boolean home = stop.kind().equals("home");
        long since = stopSince.computeIfAbsent(id, k -> now);
        double cartOff = Math.sqrt(w.distanceToSqr(Vec3.atBottomCenterOf(stop.at())));
        if ((home ? cartOff <= HOME + 0.6 || flat <= HOME : flat <= THERE) || now - since > STOP_TRIES) {
            d.getNavigation().stop();
            if (home) { arriveHome(level, w); return; }
            w.setPhase(TradeWagon.Phase.UNLOADING);
            words(w.town, "unloading at " + stop.label());
            return;
        }
        // The driver walks on to the stop, a leg at a time; a walk that gets no nearer for half a minute is planned afresh.
        double[] best = BEST.computeIfAbsent(id, k -> new double[]{ Double.MAX_VALUE, now });
        if (flat < best[0] - 1.0) { best[0] = flat; best[1] = now; }
        if (now - (long) best[1] > 600) { d.getNavigation().stop(); best[0] = flat; best[1] = now; }
        if (w.phaseTicks % 20 == 3 || d.getNavigation().isDone()) d.walkTo(Quarry.leg(level, d, goal), PACE);
        follow(level, w, d);
        if (w.tickCount % 20 == 0) words(w.town, wordsFor(w, stop));
    }

    /** The cart drawn along the way its driver walked, its middle a handle's length behind the driver, facing it. */
    static void follow(ServerLevel level, TradeWagon w, VillageFolkEntity d) {
        java.util.ArrayDeque<Vec3> t = TRAIL.computeIfAbsent(w.getUUID(), k -> {
            java.util.ArrayDeque<Vec3> n = new java.util.ArrayDeque<>();
            n.add(w.position());
            return n;
        });
        Vec3 here = d.position();
        if (t.isEmpty() || t.peekLast().distanceToSqr(here) >= 0.04) t.addLast(here);
        // Back along the trail from the driver: the point a handle's length behind it.
        List<Vec3> pts = new ArrayList<>(t);
        double need = DRAW, got = 0;
        Vec3 at = null, ahead = here;
        int keepFrom = 0;
        for (int i = pts.size() - 1; i > 0; i--) {
            Vec3 a = pts.get(i), b = pts.get(i - 1);
            double seg = a.distanceTo(b);
            if (got + seg >= need) {
                double f = seg <= 1.0E-6 ? 0 : (need - got) / seg;
                at = a.add(b.subtract(a).scale(f));
                ahead = a;
                keepFrom = Math.max(0, i - 3);
                break;
            }
            got += seg;
        }
        if (at == null) return;                                          // not drawn out a handle's length yet
        while (keepFrom-- > 0 && t.size() > 4) t.pollFirst();
        Vec3 dir = here.subtract(at);
        if (dir.horizontalDistanceSqr() > 1.0E-4) {
            float yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
            w.setYRot(net.minecraft.util.Mth.rotLerp(0.4F, w.getYRot(), yaw));
        }
        double y = Math.min(at.y, ahead.y) + Math.abs(at.y - ahead.y) * 0.5;
        w.setPos(at.x, y, at.z);
        w.setXRot(0.0F);
        if (w.tickCount % 10 == 0) CastleWall.wagonAt(level, w.town, at.x, at.z);   // [defence] a gate opened for it, as for the town's wagon
    }

    /** Held up (Wagons.unstick never drives this cart; kept for its hook): its trail started afresh. */
    static void replan(ServerLevel level, TradeWagon w) {
        TRAIL.remove(w.getUUID());
        HITCHED.remove(w.getUUID());
    }

    private static void unloading(ServerLevel level, TradeWagon w) {
        Wagons.keepWindow(level, w);
        List<Stop> stops = trip(w.town);
        VillageFolkEntity d = driverOf(level, w);
        if (stops.isEmpty()) { arriveHome(level, w); return; }
        if (d == null) return;
        Stop stop = stops.get(0);
        if (w.phaseTicks % 15 == 0) {
            d.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, w.blockPosition(), SoundEvents.STONE_HIT, SoundSource.NEUTRAL, 0.7F, 0.9F);
        }
        if (w.phaseTicks < UNLOAD_TICKS) return;
        Villages.Village v = Villages.get(w.town);
        if (v == null) return;
        int set = 0;
        for (Map.Entry<Item, Integer> e : stop.drop().entrySet()) {
            int got = take(w, e.getKey(), e.getValue());
            if (got <= 0) continue;
            set += got;
            switch (stop.kind()) {
                case "yard" -> StoneYard.receive(level, w.town, Map.of(e.getKey(), got));
                case "site" -> StoneYard.toSite(level, w.town, e.getKey(), got);
                default -> StoneYard.deliver(level, v, new ItemStack(e.getKey(), got));   // the mason's, the storehouse's: into the stores
            }
        }
        int picked = 0;
        for (Map.Entry<Item, Integer> e : stop.pick().entrySet()) {
            int n = Math.min(e.getValue(), StoneYard.count(w.town, e.getKey()));
            if (n <= 0 || !StoneYard.take(w.town, e.getKey(), n)) continue;
            int left = put(w, e.getKey(), n);
            if (left > 0) StoneYard.receive(level, w.town, Map.of(e.getKey(), left));
            picked += n - left;
        }
        if (set > 0) {
            String[] k = (Ledger.note(w.town, LOG_KEY) == null ? "0,0" : Ledger.note(w.town, LOG_KEY)).split(",");
            long carried = 0, trips = 0;
            try { trips = Long.parseLong(k[0]); carried = Long.parseLong(k[1]); } catch (RuntimeException ignored) { }
            Ledger.note(w.town, LOG_KEY, trips + "," + (carried + set));
            String last = Ledger.note(w.town, LAST);
            Ledger.note(w.town, LAST, (last == null || last.isEmpty() || last.startsWith("day") ? "" : last + ", ") + set + " to " + stop.label());
        }
        FolkTalk.speak(d, set > 0 ? set + " stone for " + stop.label() + "." + (picked > 0 ? " And " + picked + " more aboard for the town." : "") : "Nothing for here after all.");
        LOG.info("[MCA-QUARRY] {}'s quarry cart set down {} at {} (took on {})", Villages.name(w.town), set, stop.label(), picked);
        stops.remove(0);
        saveTrip(w.town, stops);
        stopSince.put(w.getUUID(), level.getGameTime());
        BEST.remove(w.getUUID());
        w.setPhase(TradeWagon.Phase.ROAD);
        if (!stops.isEmpty()) words(w.town, wordsFor(w, stops.get(0)));
    }

    private static void arriveHome(ServerLevel level, TradeWagon w) {
        Wagons.releaseWindow(level, w);
        w.setRoute(null);
        w.speedNow = 0;
        w.driver = null;
        TRAIL.remove(w.getUUID());
        HITCHED.remove(w.getUUID());
        BEST.remove(w.getUUID());
        stopSince.remove(w.getUUID());
        Ledger.forget(w.town, TRIP);
        String[] k = (Ledger.note(w.town, LOG_KEY) == null ? "0,0" : Ledger.note(w.town, LOG_KEY)).split(",");
        long carried = 0, trips = 0;
        try { trips = Long.parseLong(k[0]); carried = Long.parseLong(k[1]); } catch (RuntimeException ignored) { }
        Ledger.note(w.town, LOG_KEY, (trips + 1) + "," + carried);
        String last = Ledger.note(w.town, LAST);
        if (last != null && !last.startsWith("day")) Ledger.note(w.town, LAST, "day " + (level.getDayTime() / 24000L + 1) + ": " + last);
        w.park = w.blockPosition();                                       // parked where it was drawn up, on its station
        w.parkYaw = w.getYRot();
        w.setPhase(TradeWagon.Phase.PARKED);
        int n = aboard(w);
        words(w.town, n == 0 ? "parked at the quarry's rim, empty" : "parked at the quarry's rim, loading: " + n + " stone");
    }

    // ------------------------------------------------------------------ the board

    static String wordsFor(TradeWagon w, Stop s) {
        int n = aboard(w);
        return switch (s.kind()) {
            case "yard" -> "on the road to the yard, " + n + " stone";
            case "home" -> "on the way back to the quarry" + (n > 0 ? ", " + n + " stone aboard" : ", empty");
            default -> "taking " + s.count() + " stone to " + s.label() + (n > s.count() ? " (" + n + " aboard)" : "");
        };
    }

    static void words(@Nullable UUID town, String w) {
        if (town == null) return;
        if (!w.equals(Ledger.note(town, WORDS))) Ledger.note(town, WORDS, w);
    }

    /** "Cart: on the road to the yard, 96 stone", for the board (null: no cart). */
    @Nullable
    public static String words(UUID town) {
        if (Ledger.note(town, CART) == null) return null;
        String w = Ledger.note(town, WORDS);
        return w == null ? null : "Cart: " + w;
    }

    /** {trips made, stone carried}. */
    public static long[] tally(UUID town) {
        String s = Ledger.note(town, LOG_KEY);
        if (s == null) return new long[]{ 0, 0 };
        String[] k = s.split(",");
        try {
            return new long[]{ Long.parseLong(k[0]), Long.parseLong(k[1]) };
        } catch (RuntimeException e) {
            return new long[]{ 0, 0 };
        }
    }

    @Nullable
    public static String lastTrip(UUID town) {
        String s = Ledger.note(town, LAST);
        return s == null || !s.startsWith("day") ? null : s;
    }

    // ------------------------------------------------------------------ the tests

    public static void ensureForTests(ServerLevel level, Villages.Village v) {
        ensure(level, v);
    }

    @Nullable
    public static TradeWagon cartForTests(ServerLevel level, UUID town) {
        return cart(level, town);
    }

    public static int aboardForTests(TradeWagon w) {
        return aboard(w);
    }

    public static String phaseForTests(TradeWagon w) {
        return w.phase.name();
    }

    @Nullable
    public static UUID driverForTests(TradeWagon w) {
        return w.driver;
    }

    @Nullable
    public static BlockPos stationForTests(ServerLevel level, UUID town) {
        return station(level, town);
    }

    public static long[] tallyForTests(UUID town) {
        return tally(town);
    }

    public static String tripForTests(UUID town) {
        return describe(trip(town));
    }

    public static void resetForTests() {
        on = true;
        TRAIL.clear();
        HITCHED.clear();
        BEST.clear();
        stopSince.clear();
        driverAway.clear();
    }
}
