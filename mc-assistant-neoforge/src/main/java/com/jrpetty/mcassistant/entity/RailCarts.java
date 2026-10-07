package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [transport] The carts on the town's lines, and who rides them.
 * <ul>
 * <li><b>The ore cart.</b> A mine line has a chest minecart the smith made (a minecart and a chest, out of the stores'
 *     iron and planks), put on the line at the town's station. At the mine's station a miner loads it from the miners'
 *     work chests round the mine (what the couriers used to walk out a hundred blocks for); full, or loaded and kept
 *     waiting long enough, or at the day's end, it is sent home: the lever on the buffer thrown, it rolls down the line
 *     on the powered rails to the town's station, where a hand from the storehouse unloads it into the stores and sends
 *     it back. Every cart and what it brought is in the books.</li>
 * <li><b>Riding.</b> Each line has a minecart for riders. A miner going out to the mine of a morning, met at the town's
 *     station, rides it out (the cart is called down the line if it stands at the other end); one coming home at the
 *     day's end rides it back. A caravan or an envoy between two towns joined by a line rides it with its packs, its
 *     beast brought along. A player may ride any of them, and throw the lever to go.</li>
 * <li><b>Kept safe and on the rails.</b> Each cart under way is watched: anybody standing on the track ahead is moved off
 *     it and the cart slowed for them; nobody is carried off who did not board (Aboard); a cart off the rails is put
 *     back on them, and one that has stalled on a slope is given a push. One stuck for good is set down at the station
 *     it was making for. A cart is never conjured: one broken up is gone, and the smith makes another.</li>
 * </ul>
 */
public final class RailCarts {

    private RailCarts() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The tag every town cart wears. */
    public static final String TAG = "mca_rail";
    /** A loaded ore cart goes home once this many of its slots are taken, or it has waited this long. */
    static final int FULL_SLOTS = 18;
    static final long LOADED_WAIT = 2400L;
    /** How long a folk waits on the platform for the cart before it walks. */
    static final long WAIT = 1200L, WALK = 600L;

    public enum Role { ORE, RIDE }

    /** One cart of a town's: which line, what for, which end it stands at, where it is making for. */
    static final class Cart {
        final UUID id;
        final Role role;
        final String line;
        /** The end it stands at (0: the town's station, 1: the far one), or -1 when it is out on the line. */
        int at;
        /** The end it is making for, or -1 when it stands. */
        int going = -1;
        long since, progressAt;
        int idx, bestLeft = Integer.MAX_VALUE;
        @Nullable UUID rider;
        @Nullable BlockPos lastPos, window;
        int leverEnd = -1;
        long leverOffAt;
        /** Ticks it has stood still at the end of a run. */
        int still;
        /** The rail it came to a stand at (a bump from another cart is not a setting off). */
        int standIdx = -1;

        Cart(UUID id, Role role, String line, int at) {
            this.id = id;
            this.role = role;
            this.line = line;
            this.at = at;
        }
    }

    /** What a line has done, for the books. */
    public static final class Stats {
        public int carts, goods, riders, mended;
    }

    private static final Map<UUID, List<Cart>> CARTS = new ConcurrentHashMap<>();
    private static final Map<String, Stats> STATS = new ConcurrentHashMap<>();
    private static final Map<UUID, Ride> RIDES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_LOOK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_WORK = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CARTS.clear();
        STATS.clear();
        RIDES.clear();
        NEXT_LOOK.clear();
        LAST_WORK.clear();
    }

    // ------------------------------------------------------------------ the books

    static Stats stats(UUID village, String key) {
        return STATS.computeIfAbsent(village + "/" + key, k -> {
            Stats s = new Stats();
            String n = Ledger.note(village, "rail/stats/" + key);
            if (n != null && !n.isEmpty()) {
                try {
                    String[] f = n.split(",");
                    s.carts = Integer.parseInt(f[0]);
                    s.goods = Integer.parseInt(f[1]);
                    s.riders = Integer.parseInt(f[2]);
                    s.mended = Integer.parseInt(f[3]);
                } catch (RuntimeException ignored) {
                    // a damaged entry: counted afresh
                }
            }
            return s;
        });
    }

    /** A line's figures, kept with the town that keeps its carts (a link's figures are the same for both towns). */
    public static Stats statsOf(UUID village, Railways.Line l) {
        UUID k = keeper(village, l);
        return stats(k, k.equals(village) ? l.key() : "link/" + village);
    }

    static void saveStatsOf(UUID village, Railways.Line l) {
        UUID k = keeper(village, l);
        saveStats(k, k.equals(village) ? l.key() : "link/" + village);
    }

    static void saveStats(UUID village, String key) {
        Stats s = stats(village, key);
        Ledger.note(village, "rail/stats/" + key, s.carts + "," + s.goods + "," + s.riders + "," + s.mended);
    }

    /** Tests and the books: what a line has carried. */
    public static Stats statsFor(UUID village, String key) {
        return stats(village, key);
    }

    private static List<Cart> carts(UUID village) {
        return CARTS.computeIfAbsent(village, RailCarts::loadCarts);
    }

    private static List<Cart> loadCarts(UUID village) {
        List<Cart> out = new java.util.concurrent.CopyOnWriteArrayList<>();
        String s = Ledger.note(village, "rail/carts");
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            try {
                String[] f = part.split(",");
                Cart c = new Cart(UUID.fromString(f[0]), Role.valueOf(f[1]), f[2], Integer.parseInt(f[3]));
                c.going = Integer.parseInt(f[4]);
                out.add(c);
            } catch (RuntimeException ignored) {
                // a damaged entry: that cart is looked for no more
            }
        }
        return out;
    }

    private static void saveCarts(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Cart c : carts(village)) {
            if (sb.length() > 0) sb.append(';');
            sb.append(c.id).append(',').append(c.role.name()).append(',').append(c.line).append(',').append(c.at).append(',').append(c.going);
        }
        Ledger.note(village, "rail/carts", sb.toString());
    }

    /** Which town keeps a line's carts: its own for a mine line; of the two a link joins, the one that planned it. */
    static UUID keeper(UUID village, Railways.Line l) {
        if (l.kind() != Railways.Kind.LINK || l.other() == null) return village;
        return village.toString().compareTo(l.other().toString()) < 0 ? village : l.other();
    }

    @Nullable
    static Cart cart(UUID village, String line, Role role) {
        for (Cart c : carts(village)) if (c.line.equals(line) && c.role == role) return c;
        return null;
    }

    /** The carts the stores should hold for the lines to have their carts: {chest minecarts, minecarts}. */
    static int[] cartsWanted(ServerLevel level, Villages.Village v) {
        int ore = 0, ride = 0;
        for (Railways.Line l : Railways.lines(v.id()).values()) {
            if (l.state() != Railways.State.OPEN || !keeper(v.id(), l).equals(v.id())) continue;
            if (l.kind() == Railways.Kind.MINE && cart(v.id(), l.key(), Role.ORE) == null) ore++;
            if (cart(v.id(), l.key(), Role.RIDE) == null) ride++;
        }
        return new int[]{ ore, ride };
    }

    // ------------------------------------------------------------------ the town's carts, every few seconds

    /** Every five seconds for each village (Transport): its carts put on the line, loaded, sent, unloaded. */
    public static void work(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = LAST_WORK.get(id);
        if (last != null && now - last < 100L && now >= last) return;
        LAST_WORK.put(id, now);
        for (Railways.Line l : Railways.lines(id).values()) {
            if (l.state() != Railways.State.OPEN || !keeper(id, l).equals(id)) continue;
            if (l.kind() == Railways.Kind.MINE) {
                Cart ore = cart(id, l.key(), Role.ORE);
                if (ore == null) putOn(level, v, l, Role.ORE);
                else oreCart(level, v, l, ore);
            }
            Cart ride = cart(id, l.key(), Role.RIDE);
            if (ride == null) putOn(level, v, l, Role.RIDE);
            else if (ride.going < 0 && ride.at < 0 && ride.rider == null && now - ride.since > 600L) {
                AbstractMinecart e = entity(level, ride);
                if (e != null && e.getPassengers().isEmpty()) dispatch(level, l, ride, 0);     // left out on the line: home
            }
        }
    }

    /** A cart out of the stores put on the line at the town's station, by a hand at the works. */
    private static boolean putOn(ServerLevel level, Villages.Village v, Railways.Line l, Role role) {
        java.util.function.Predicate<ItemStack> what = role == Role.ORE ? s -> s.is(Items.CHEST_MINECART) : s -> s.is(Items.MINECART);
        if (Market.stock(level, v.id(), what) == 0) return false;
        BlockPos at = l.rail(role == Role.ORE ? 3 : 1);              // the riders' cart by the buffer, the ore cart before it
        if (!level.isLoaded(at) || !(level.getBlockState(at).getBlock() instanceof BaseRailBlock)) return false;
        if (!TownJobs.atWork(level, v, "railway", at, "putting a cart on " + l.name(), AssistantEntity.StationTask.HAUL)) return false;
        if (!Crafts.take(level, v, what, 1)) return false;
        AbstractMinecart cart = AbstractMinecart.createMinecart(level, at.getX() + 0.5, at.getY() + 0.0625, at.getZ() + 0.5,
            role == Role.ORE ? AbstractMinecart.Type.CHEST : AbstractMinecart.Type.RIDEABLE,
            new ItemStack(role == Role.ORE ? Items.CHEST_MINECART : Items.MINECART), null);
        cart.addTag(TAG);
        cart.addTag(TAG + "/" + v.id());
        cart.setCustomName(net.minecraft.network.chat.Component.literal(Villages.name(v.id()) + (role == Role.ORE ? " ore cart" : " cart")));
        if (!level.addFreshEntity(cart)) return false;
        Cart c = new Cart(cart.getUUID(), role, l.key(), 0);
        c.since = level.getGameTime();
        c.lastPos = at;
        carts(v.id()).add(c);
        saveCarts(v.id());
        Villages.tell(v.id(), level.getDayTime() / 24000L, (role == Role.ORE ? "an ore cart" : "a cart for riders") + " was put on " + l.name());
        return true;
    }

    /** The ore cart's round: loaded at the mine, sent home, unloaded at the town's station, sent back. */
    private static void oreCart(ServerLevel level, Villages.Village v, Railways.Line l, Cart c) {
        if (c.going >= 0) return;
        AbstractMinecart e = entity(level, c);
        if (!(e instanceof MinecartChest box)) return;
        long now = level.getGameTime();
        int goods = goods(box);
        if (c.at == 0) {
            if (goods > 0) {
                if (!TownJobs.atWork(level, v, "railway", l.platform(0), "unloading the ore cart into the stores",
                    AssistantEntity.StationTask.HAUL)) return;
                int moved = unload(level, v, box);
                if (moved > 0) {
                    Stats s = stats(v.id(), l.key());
                    s.carts++;
                    s.goods += moved;
                    saveStats(v.id(), l.key());
                    if (s.carts == 1) {
                        Villages.tell(v.id(), level.getDayTime() / 24000L, "the first cart of ore came down the line from the mine: "
                            + moved + " goods into the stores");
                    }
                    LOG.info("[MCA-RAIL] {}: the ore cart brought {} goods in (cart {} of the line)", Villages.name(v.id()), moved, s.carts);
                }
                if (goods(box) > 0) return;                    // the stores full: it waits
            }
            dispatch(level, l, c, 1);
            return;
        }
        if (c.at == 1) {
            boolean dayEnd = level.getDayTime() % 24000L >= 11500L;
            int loaded = 0;
            // A miner sent to load it only when the work chests round the mine have something in them worth the cart.
            if (slotsTaken(box) < box.getContainerSize() && loadable(level, v, l) >= 8
                    && TownJobs.atWork(level, v, "railway/load", l.platform(1), "loading the ore cart at the mine", AssistantEntity.StationTask.MINE)) {
                loaded = load(level, v, l, box);
            }
            goods = goods(box);
            int slots = slotsTaken(box);
            boolean waited = now - c.since > LOADED_WAIT || (loaded == 0 && now - c.since > 600L);
            if (goods > 0 && (slots >= FULL_SLOTS || waited || dayEnd)) dispatch(level, l, c, 0);
            return;
        }
        // Left standing out on the line (a player took it off, or it was stopped): on to wherever it is wanted.
        if (now - c.since > 600L) dispatch(level, l, c, goods > 0 ? 0 : 1);
    }

    /** The miners' work chests round the mine's station emptied into the cart: what will go (not their tools or torches). */
    private static int load(ServerLevel level, Villages.Village v, Railways.Line l, MinecartChest box) {
        BlockPos station = l.platform(1);
        int moved = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.MINE) continue;
            BlockPos chest = f.productionChest();
            if (chest == null || chest.distSqr(station) > 56 * 56 || !level.isLoaded(chest)) continue;
            if (!(level.getBlockEntity(chest) instanceof Container from)) continue;
            moved += move(from, box, s -> AssistantEntity.haulWeight(s) > 0);
            if (slotsTaken(box) >= box.getContainerSize()) break;
        }
        return moved;
    }

    /** What the miners' work chests round the mine's station hold that would go in the cart. */
    private static int loadable(ServerLevel level, Villages.Village v, Railways.Line l) {
        BlockPos station = l.platform(1);
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.MINE) continue;
            BlockPos chest = f.productionChest();
            if (chest == null || chest.distSqr(station) > 56 * 56 || !level.isLoaded(chest)) continue;
            if (!(level.getBlockEntity(chest) instanceof Container from)) continue;
            for (int i = 0; i < from.getContainerSize(); i++) {
                ItemStack s = from.getItem(i);
                if (AssistantEntity.haulWeight(s) > 0) n += s.getCount();
            }
        }
        return n;
    }

    /** Move what matches from one container into another, onto part stacks first. Returns how many moved. */
    static int move(Container from, Container to, java.util.function.Predicate<ItemStack> what) {
        int moved = 0;
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack s = from.getItem(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int before = s.getCount();
            for (int j = 0; j < to.getContainerSize() && !s.isEmpty(); j++) {
                ItemStack t = to.getItem(j);
                if (!t.isEmpty() && ItemStack.isSameItemSameComponents(s, t) && t.getCount() < t.getMaxStackSize()) {
                    int k = Math.min(s.getCount(), t.getMaxStackSize() - t.getCount());
                    t.grow(k);
                    s.shrink(k);
                }
            }
            for (int j = 0; j < to.getContainerSize() && !s.isEmpty(); j++) {
                if (to.getItem(j).isEmpty()) {
                    to.setItem(j, s.copy());
                    s.setCount(0);
                }
            }
            moved += before - s.getCount();
            from.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
        }
        if (moved > 0) {
            from.setChanged();
            to.setChanged();
        }
        return moved;
    }

    /** The cart's goods into the stores (the storehouse first: TownWork.give), booked in. Returns how many went in. */
    private static int unload(ServerLevel level, Villages.Village v, MinecartChest box) {
        int moved = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty()) continue;
            int before = s.getCount();
            ItemStack go = s.copy();
            TownWork.give(level, v, go);
            moved += before - go.getCount();
            box.setItem(i, go.isEmpty() ? ItemStack.EMPTY : go);
        }
        box.setChanged();
        return moved;
    }

    static int goods(Container box) {
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) n += box.getItem(i).getCount();
        return n;
    }

    private static int slotsTaken(Container box) {
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) if (!box.getItem(i).isEmpty()) n++;
        return n;
    }

    @Nullable
    static AbstractMinecart entity(ServerLevel level, Cart c) {
        Entity e = level.getEntity(c.id);
        return e instanceof AbstractMinecart m && m.isAlive() ? m : null;
    }

    // ------------------------------------------------------------------ sending a cart

    /**
     * Send a standing cart to the other end: the lever on the buffer thrown (the powered rails by it set the cart off;
     * thrown back once it has gone), and a push to be sure. A cart of ours standing in front of it on the platform goes
     * first, the same way. True if it went.
     */
    static boolean dispatch(ServerLevel level, Railways.Line l, Cart c, int to) {
        AbstractMinecart e = entity(level, c);
        if (e == null) return false;
        UUID keeper = keeperOf(c);
        int n = l.length();
        int idx = nearest(l, e.position(), c.idx);
        int from = c.at >= 0 ? c.at : (idx < n / 2 ? 0 : 1);
        if (from == to && c.at == to) return false;
        // One way at a time on a single track: nothing sent toward a cart coming the other way.
        if (keeper != null) {
            for (Cart o : carts(keeper)) if (o != c && o.line.equals(c.line) && o.going >= 0 && o.going != to) return false;
        }
        // Anything of ours standing between it and the line, going first.
        if (keeper != null && c.at >= 0) {
            for (Cart o : carts(keeper)) {
                if (o == c || !o.line.equals(c.line) || o.at != c.at || o.going >= 0) continue;
                AbstractMinecart oe = entity(level, o);
                if (oe == null) continue;
                int oi = nearest(l, oe.position(), o.idx);
                boolean inFront = from == 0 ? oi > idx : oi < idx;
                if (inFront && oe.getPassengers().isEmpty()) dispatch(level, l, o, to);
            }
        }
        long now = level.getGameTime();
        c.going = to;
        c.at = -1;
        c.since = now;
        c.progressAt = now;
        c.bestLeft = Integer.MAX_VALUE;
        c.idx = idx;
        c.still = 0;
        c.standIdx = -1;
        // The lever at the end it leaves from.
        BlockPos lever = l.buffer(from).above();
        BlockState st = level.getBlockState(lever);
        if (st.getBlock() instanceof LeverBlock lb && !st.getValue(LeverBlock.POWERED)) {
            lb.pull(st, level, lever, null);
            c.leverEnd = from;
            c.leverOffAt = now + 50L;
        }
        Direction way = to == 1 ? l.outAt(Math.max(0, Math.min(n - 2, idx))) : l.outAt(Math.max(0, Math.min(n - 2, idx - 1))).getOpposite();
        e.setDeltaMovement(way.getStepX() * 0.4, 0.0, way.getStepZ() * 0.4);
        if (keeper != null) saveCarts(keeper);
        return true;
    }

    @Nullable
    private static UUID keeperOf(Cart c) {
        for (Map.Entry<UUID, List<Cart>> e : CARTS.entrySet()) if (e.getValue().contains(c)) return e.getKey();
        return null;
    }

    /** The rail nearest this spot, looked for round where it was last (all of them if it is not near there). */
    static int nearest(Railways.Line l, Vec3 p, int hint) {
        int n = l.length();
        int best = -1;
        double bestD = Double.MAX_VALUE;
        int lo = Math.max(0, hint - 10), hi = Math.min(n - 1, hint + 10);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = lo; i <= hi; i++) {
                BlockPos r = l.rails().get(i);
                double dx = r.getX() + 0.5 - p.x, dz = r.getZ() + 0.5 - p.z, dy = r.getY() - p.y;
                double d = dx * dx + dz * dz + dy * dy * 0.25;
                if (d < bestD) { bestD = d; best = i; }
            }
            if (bestD <= 9.0) break;
            lo = 0;
            hi = n - 1;
        }
        return Math.max(0, best);
    }

    // ------------------------------------------------------------------ every tick: the carts under way

    /** Every tick (Transport): each town's carts under way kept moving, safe and on the rails; the others now and then. */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (Map.Entry<UUID, List<Cart>> e : CARTS.entrySet()) {
            Villages.Village v = Villages.get(e.getKey());
            if (v == null || !v.dim().equals(level.dimension())) continue;
            boolean changed = false;
            for (Cart c : e.getValue()) {
                Railways.Line l = Railways.line(v.id(), c.line);
                if (l == null) { e.getValue().remove(c); changed = true; continue; }
                if (c.leverEnd >= 0 && now >= c.leverOffAt) {
                    BlockPos lever = l.buffer(c.leverEnd).above();
                    BlockState st = level.getBlockState(lever);
                    if (st.getBlock() instanceof LeverBlock lb && st.getValue(LeverBlock.POWERED)) lb.pull(st, level, lever, null);
                    c.leverEnd = -1;
                }
                if (c.going < 0 && Math.floorMod(now + c.id.hashCode(), 20L) != 0) continue;
                changed |= watch(level, v, l, c, now);
            }
            if (changed) saveCarts(v.id());
        }
    }

    /** One cart looked over. True if its standing changed (to be saved). */
    private static boolean watch(ServerLevel level, Villages.Village v, Railways.Line l, Cart c, long now) {
        AbstractMinecart e = entity(level, c);
        if (e == null) {
            if (c.lastPos != null && level.isLoaded(c.lastPos) && level.getChunkSource().hasChunk(c.lastPos.getX() >> 4, c.lastPos.getZ() >> 4)
                    && now - c.since > 40L) {
                // Its ground is there and it is not: broken up, or run off somewhere for good. The smith makes another.
                carts(v.id()).remove(c);
                letGo(level, c);
                Villages.tell(v.id(), level.getDayTime() / 24000L, (c.role == Role.ORE ? "the ore cart" : "the riders' cart")
                    + " on " + l.name() + " was lost");
                LOG.info("[MCA-RAIL] {}: {} cart on {} lost at {}", Villages.name(v.id()), c.role, l.key(), c.lastPos.toShortString());
                return true;
            }
            return false;
        }
        c.lastPos = e.blockPosition();
        keepAwake(level, c, c.lastPos);
        // Nobody aboard who did not board: an animal, a folk the cart ran into.
        for (Entity p : List.copyOf(e.getPassengers())) {
            if (p instanceof Player) continue;
            if (c.rider != null && p.getUUID().equals(c.rider)) continue;
            p.stopRiding();
        }
        int n = l.length();
        int idx = nearest(l, e.position(), c.idx);
        c.idx = idx;
        double speed = e.getDeltaMovement().horizontalDistance();
        if (c.going < 0) {
            if (c.standIdx < 0) c.standIdx = idx;
            // Standing: set off by somebody (a player at the lever, or giving it a shove)? A cart run into by another
            // coming in moves a rail or two and no more; one set off is well on its way by the next look.
            if (speed > 0.1 && c.rider == null && Math.abs(idx - c.standIdx) >= 3) {
                int to = idx > c.standIdx ? 1 : 0;
                c.going = to;
                c.at = -1;
                c.since = now;
                c.progressAt = now;
                c.bestLeft = Integer.MAX_VALUE;
                return true;
            }
            return false;
        }
        int goal = c.going == 1 ? n - 1 : 0;
        int left = Math.abs(goal - idx);
        // Off the rails: back on them where it left them.
        BlockPos under = e.blockPosition();
        boolean onRails = level.getBlockState(under).getBlock() instanceof BaseRailBlock
            || level.getBlockState(under.below()).getBlock() instanceof BaseRailBlock;
        BlockPos r = l.rail(idx);
        if (!onRails && e.position().distanceToSqr(r.getX() + 0.5, r.getY() + 0.1, r.getZ() + 0.5) > 1.5) {
            e.moveTo(r.getX() + 0.5, r.getY() + 0.0625, r.getZ() + 0.5, e.getYRot(), e.getXRot());
            e.setDeltaMovement(Vec3.ZERO);
            c.progressAt = now;
        }
        // At the station it was making for, and stood still (against the buffer, or behind a cart already there).
        if (left <= Railways.PLATFORM - 1 && speed < 0.03) {
            if (++c.still >= 8 || left <= 1) {
                arrive(level, v, l, c, c.going);
                return true;
            }
        } else {
            c.still = 0;
        }
        if (left < c.bestLeft) {
            c.bestLeft = left;
            c.progressAt = now;
        }
        int step = c.going == 1 ? 1 : -1;
        // Anybody on the track ahead: moved off it, and the cart slowed for them.
        if (speed > 0.02 && clearTheWay(level, l, c, e, idx, step)) {
            c.progressAt = now;
            return false;
        }
        // Stalled (a slope, a stretch with no powered rail, something it ran into): a push on.
        if (now - c.progressAt > 30L && speed < 0.12 && left > 1) {
            int a = Math.max(0, Math.min(n - 1, idx)), b = Math.max(0, Math.min(n - 1, idx + step));
            BlockPos ra = l.rail(a), rb = l.rail(b);
            double dx = rb.getX() - ra.getX(), dz = rb.getZ() - ra.getZ();
            double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
            double up = rb.getY() > ra.getY() ? 0.1 : 0.0;
            e.setDeltaMovement(dx / len * 0.35, up, dz / len * 0.35);
        }
        // Stuck for good: set down at the station it was making for (the same cart, carried the rest of the way).
        if (now - c.progressAt > 900L) {
            BlockPos end = l.rail(goal == 0 ? 2 : n - 3);
            e.moveTo(end.getX() + 0.5, end.getY() + 0.0625, end.getZ() + 0.5, e.getYRot(), e.getXRot());
            e.setDeltaMovement(Vec3.ZERO);
            LOG.info("[MCA-RAIL] {}: {} cart on {} stuck at rail {} for good: set down at the station", Villages.name(v.id()), c.role, l.key(), idx);
            arrive(level, v, l, c, c.going);
            return true;
        }
        return false;
    }

    /** At the station it was making for: stands there, its ground let go. */
    private static void arrive(ServerLevel level, Villages.Village v, Railways.Line l, Cart c, int end) {
        c.at = end;
        c.going = -1;
        c.since = level.getGameTime();
        c.still = 0;
        c.standIdx = c.idx;
        AbstractMinecart e = entity(level, c);
        if (e != null) e.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * The track ahead of a moving cart looked along a few rails: a folk, a beast or anybody else (not a player, who can
     * look after itself) standing on it is moved off to the side, and the cart slows for it. True if anybody was there.
     */
    private static boolean clearTheWay(ServerLevel level, Railways.Line l, Cart c, AbstractMinecart e, int idx, int step) {
        boolean any = false;
        for (int k = 1; k <= 4; k++) {
            int i = idx + step * k;
            if (i < 0 || i >= l.length()) break;
            BlockPos r = l.rail(i);
            AABB box = new AABB(r.getX() + 0.15, r.getY(), r.getZ() + 0.15, r.getX() + 0.85, r.getY() + 1.5, r.getZ() + 0.85);
            for (LivingEntity who : level.getEntitiesOfClass(LivingEntity.class, box, x -> x.isAlive() && !(x instanceof Player)
                    && x.getVehicle() == null)) {
                any = true;
                Direction side = l.outAt(Math.max(0, Math.min(l.length() - 2, i))).getClockWise();
                BlockPos off = r.relative(side);
                if (!level.getBlockState(off).isAir() || !level.getBlockState(off.above()).isAir()) {
                    side = side.getOpposite();
                    off = r.relative(side);
                }
                who.push(side.getStepX() * 0.35, 0.05, side.getStepZ() * 0.35);
                if (who instanceof VillageFolkEntity f) {
                    f.getNavigation().stop();
                    f.walkTo(off.relative(side), 1.1D);
                }
            }
        }
        if (any) e.setDeltaMovement(e.getDeltaMovement().scale(0.3));
        return any;
    }

    private static UUID owner(Cart c) {
        return UUID.nameUUIDFromBytes(("mca-rail-cart-" + c.id).getBytes());
    }

    /** The ground round a cart kept awake (it may stand at the mine with nobody near), a window that goes with it. */
    private static void keepAwake(ServerLevel level, Cart c, BlockPos at) {
        if (c.window != null && c.window.distSqr(at) < 12 * 12) return;
        if (c.window != null) ChunkLoad.setLoaded(level, owner(c), c.window, 1, false);
        ChunkLoad.setLoaded(level, owner(c), at, 1, true);
        c.window = at.immutable();
    }

    private static void letGo(ServerLevel level, Cart c) {
        if (c.window != null) ChunkLoad.setLoaded(level, owner(c), c.window, 1, false);
        c.window = null;
    }

    // ------------------------------------------------------------------ riders

    enum Stage { WALK, WAIT, ABOARD }

    /** A folk's ride: on which line (as its keeper has it), from which end to which, how far it has got. */
    static final class Ride {
        final UUID keeper;
        final String line;
        final int from, to;
        Stage stage = Stage.WALK;
        long since;
        int walkTick = -1000;
        /** A caravan's ride: the leg it was on (so it rides each leg once). */
        @Nullable final Caravans.Trip trip;
        final boolean back;

        Ride(UUID keeper, String line, int from, int to, @Nullable Caravans.Trip trip) {
            this.keeper = keeper;
            this.line = line;
            this.from = from;
            this.to = to;
            this.trip = trip;
            this.back = trip != null && trip.homeward();
        }
    }

    /** Is this folk sitting in a cart on a ride of its own? (Its day waits on the ride: VillageFolkEntity.aiStep.) */
    public static boolean aboard(VillageFolkEntity f) {
        Ride r = RIDES.get(f.getUUID());
        return r != null && r.stage == Stage.ABOARD && f.getVehicle() instanceof AbstractMinecart;
    }

    /** Has this folk a ride on just now (walking to the platform, waiting there, or aboard)? */
    public static boolean riding(VillageFolkEntity f) {
        return RIDES.containsKey(f.getUUID());
    }

    /** May this folk get into this cart: the one its ride is in? (Aboard.) */
    public static boolean mayBoard(VillageFolkEntity f, Entity vehicle) {
        Ride r = RIDES.get(f.getUUID());
        if (r == null || !(vehicle instanceof AbstractMinecart)) return false;
        Cart c = cart(r.keeper, r.line, Role.RIDE);
        return c != null && c.id.equals(vehicle.getUUID());
    }

    /** What a rider is doing, for its card; null if it has no ride. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        Ride r = RIDES.get(f.getUUID());
        if (r == null) return null;
        Railways.Line l = Railways.line(r.keeper, r.line);
        String where = l == null ? "the line" : l.name();
        return switch (r.stage) {
            case WALK -> "walking to the station for " + where;
            case WAIT -> "waiting on the platform for the cart";
            case ABOARD -> "riding " + where;
        };
    }

    /**
     * From the folk's tick: its ride seen to (to the platform, waiting there, aboard), or, now and then, whether it
     * should take one. True while it is on a ride.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Ride r = RIDES.get(f.getUUID());
        if (r == null) {
            if (f.tickCount % 40 != 9) return false;
            consider(f, level);
            r = RIDES.get(f.getUUID());
            if (r == null) return false;
        }
        return ride(f, level, r);
    }

    /** A miner of a town with an open line to its mine: out to it of a morning from the town's station, home at the day's end. */
    private static void consider(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || f.stationTask() != AssistantEntity.StationTask.MINE || f.workZone() == null) return;
        if (f.trip() != null || f.expedition() != null || f.isPassenger()) return;
        long now = level.getGameTime();
        if (NEXT_LOOK.getOrDefault(f.getUUID(), 0L) > now) return;
        Railways.Line l = Railways.mineLine(id);
        if (l == null || l.state() != Railways.State.OPEN) return;
        if (cart(id, l.key(), Role.RIDE) == null) return;
        BlockPos zone = f.workZone().center();
        BlockPos town = l.platform(0), mine = l.platform(1);
        if (flatSq(zone, mine) > 56 * 56 || flatSq(zone, town) < 40 * 40) return;          // its face is not out at the mine
        long t = level.getDayTime() % 24000L;
        boolean working = f.onShift() && !f.offWorkNow();
        BlockPos here = f.blockPosition();
        if (working && t >= 1000L && t < 10000L && flatSq(here, town) < 20 * 20) {
            book(f, level, id, l, 0, 1, null);
        } else if ((!working || t >= 11000L) && t < 13000L && flatSq(here, mine) < 20 * 20) {
            book(f, level, id, l, 1, 0, null);
        }
    }

    private static void book(VillageFolkEntity f, ServerLevel level, UUID keeper, Railways.Line l, int from, int to,
                             @Nullable Caravans.Trip trip) {
        Ride r = new Ride(keeper, l.key(), from, to, trip);
        r.since = level.getGameTime();
        RIDES.put(f.getUUID(), r);
        NEXT_LOOK.put(f.getUUID(), level.getGameTime() + 2400L);
        f.clearQueue();
        f.getNavigation().stop();
        f.brain("taking the cart on " + l.name());
    }

    private static void end(VillageFolkEntity f, ServerLevel level, Ride r, @Nullable String why) {
        RIDES.remove(f.getUUID());
        Cart c = cart(r.keeper, r.line, Role.RIDE);
        if (c != null && f.getUUID().equals(c.rider)) c.rider = null;
        if (f.getVehicle() instanceof AbstractMinecart) f.stopRiding();
        if (why != null) f.brain(why);
    }

    /** One step of a ride. True while it goes on. */
    private static boolean ride(VillageFolkEntity f, ServerLevel level, Ride r) {
        Railways.Line l = Railways.line(r.keeper, r.line);
        Cart c = cart(r.keeper, r.line, Role.RIDE);
        if (l == null || c == null || l.state() != Railways.State.OPEN || !f.isAlive()) {
            end(f, level, r, null);
            return false;
        }
        long now = level.getGameTime();
        AbstractMinecart e = entity(level, c);
        BlockPos platform = l.platform(r.from);
        switch (r.stage) {
            case WALK -> {
                if (flatSq(f.blockPosition(), platform) <= 2 * 2 + 1 && Math.abs(f.getY() - platform.getY()) < 2.5) {
                    r.stage = Stage.WAIT;
                    r.since = now;
                    return true;
                }
                if (now - r.since > WALK) {
                    end(f, level, r, "gave up on the cart: walked");
                    return false;
                }
                if (f.getNavigation().isDone() || f.tickCount - r.walkTick > 60) {
                    f.walkTo(platform, 1.0D);
                    r.walkTick = f.tickCount;
                }
                return true;
            }
            case WAIT -> {
                f.getNavigation().stop();
                if (e != null) f.getLookControl().setLookAt(e);
                // The cart here, standing, and free: in, and away.
                if (e != null && c.at == r.from && c.going < 0 && c.rider == null && e.getPassengers().isEmpty()
                        && f.distanceToSqr(e) < 3.5 * 3.5) {
                    c.rider = f.getUUID();
                    if (f.startRiding(e, true)) {
                        r.stage = Stage.ABOARD;
                        r.since = now;
                        dispatch(level, l, c, r.to);
                        if (level.getRandom().nextInt(3) == 0) {
                            FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Off we go!", "Mind your feet on the platform.",
                                "Hold on to your hat.", "Beats walking."));
                        }
                        return true;
                    }
                    c.rider = null;
                }
                // The cart at the other end with nobody in it: called down the line.
                if (e != null && c.at == 1 - r.from && c.going < 0 && c.rider == null && e.getPassengers().isEmpty()) {
                    dispatch(level, l, c, r.from);
                    ringFor(level, l, r.from);
                }
                if (now - r.since > WAIT) {
                    end(f, level, r, "waited for the cart long enough: walked");
                    return false;
                }
                return true;
            }
            case ABOARD -> {
                if (e == null || f.getVehicle() != e) {
                    end(f, level, r, null);
                    return false;
                }
                if (c.going < 0 && c.at == r.to) {
                    alight(f, level, l, c, r);
                    return false;
                }
                if (c.going < 0 && c.at == r.from) {
                    // In, and the line not clear yet (a cart coming the other way): it goes when it is.
                    if (!dispatch(level, l, c, r.to) && now - r.since > WAIT) {
                        end(f, level, r, "the line never cleared: walked");
                        return false;
                    }
                    return true;
                }
                if (c.going < 0 && c.at != r.to && now - r.since > 200L) {
                    // Stopped short and not going on: off, and the rest on foot.
                    end(f, level, r, "the cart stopped short: walked the rest");
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    /** Off at the other end: onto the platform, the ride in the books. */
    private static void alight(VillageFolkEntity f, ServerLevel level, Railways.Line l, Cart c, Ride r) {
        f.stopRiding();
        BlockPos p = l.platform(r.to);
        f.moveTo(p.getX() + 0.5, p.getY() + 0.6, p.getZ() + 0.5, f.getYRot(), 0.0F);
        c.rider = null;
        RIDES.remove(f.getUUID());
        Stats s = stats(r.keeper, r.line);
        s.riders++;
        if (r.trip != null) s.goods += carried(f);
        saveStats(r.keeper, r.line);
        f.brain("got off the cart at the " + (r.to == 0 ? "town's" : l.kind() == Railways.Kind.MINE ? "mine's" : "other") + " station");
        if (s.riders == 1) {
            Villages.tell(r.keeper, level.getDayTime() / 24000L, f.displayNameCap() + " was the first to ride " + l.name());
        }
        if (r.trip != null) arrivedOnATrip(f, level, l, r);
    }

    /** The lever's bell: a rider on the platform rings for the cart (the note block's bell, from the buffer). */
    private static void ringFor(ServerLevel level, Railways.Line l, int end) {
        level.playSound(null, l.buffer(end), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
            net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.2F);
    }

    private static int carried(VillageFolkEntity f) {
        int n = 0;
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && AssistantEntity.haulWeight(s) > 0) n += s.getCount();
        return n;
    }

    private static double flatSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ travellers between towns

    /**
     * A caravan or an envoy on the road between two towns a line joins (Caravans.drive): it rides the line between the
     * two stations, its packs with it and its beast brought along, and walks on from the other station. True while the
     * ride is on (the walk waits).
     */
    public static boolean caravan(VillageFolkEntity f, ServerLevel level, Caravans.Trip t) {
        Ride r = RIDES.get(f.getUUID());
        if (r != null) {
            if (r.trip != t || r.back != t.homeward()) {
                end(f, level, r, null);
                return false;
            }
            return ride(f, level, r);
        }
        UUID leaving = t.homeward() ? t.to : t.from, going = t.destination();
        if (!Railways.linkOpen(leaving, going)) return false;
        Railways.Line mine = Railways.linkTo(leaving, going);
        if (mine == null) return false;
        UUID keeper = keeper(leaving, mine);
        Railways.Line l = Railways.linkTo(keeper, keeper.equals(leaving) ? going : leaving);
        if (l == null || cart(keeper, l.key(), Role.RIDE) == null) return false;
        int from = keeper.equals(leaving) ? 0 : 1, to = 1 - from;
        int leg = t.homeward() ? 2 : 1;
        synchronized (RODE) {
            if ((RODE.getOrDefault(t, 0) & leg) != 0) return false;
        }
        // Only while it is still about its own town: near the station it leaves from.
        if (flatSq(f.blockPosition(), l.platform(from)) > 48 * 48) return false;
        synchronized (RODE) {
            RODE.merge(t, leg, (a, b) -> a | b);
        }
        book(f, level, keeper, l, from, to, t);
        return true;
    }

    /** The legs of each trip already ridden (1 out, 2 home): each is ridden once. Let go with the trip. */
    private static final Map<Caravans.Trip, Integer> RODE = new java.util.WeakHashMap<>();

    /** A traveller off the cart at the other town's station: its trip goes on from the waypoint past the station, its beast with it. */
    private static void arrivedOnATrip(VillageFolkEntity f, ServerLevel level, Railways.Line l, Ride r) {
        Caravans.Trip t = r.trip;
        if (t == null || f.trip() != t) return;
        BlockPos station = l.platform(r.to);
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int k = 0; k < t.way.size(); k++) {
            double d = flatSq(t.way.get(k), station);
            if (d < bestD) { bestD = d; best = k; }
        }
        t.at = Math.min(t.way.size(), Math.max(t.at, best + 1));
        t.best = Double.MAX_VALUE;
        t.gainedTick = f.tickCount;
        if (t.llama != null && level.getEntity(t.llama) instanceof LivingEntity beast) {
            beast.moveTo(station.getX() + 1.5, station.getY() + 0.6, station.getZ() + 0.5, beast.getYRot(), 0.0F);
        }
        Riding.bringAlong(f, level, station.getX() + 0.5, station.getY() + 0.6, station.getZ() + 0.5);
        // Its cart home again, empty, for the next of its own town's travellers.
        Cart c = cart(r.keeper, r.line, Role.RIDE);
        if (c != null && r.to == 1) dispatch(level, l, c, 0);
    }

    // ------------------------------------------------------------------ for the books and the tests

    /** The carts in a line or two: where each is. */
    public static List<String> report(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Cart c : carts(village)) {
            Railways.Line l = Railways.line(village, c.line);
            if (l == null) continue;
            String where = c.going >= 0 ? "on its way to the " + endName(l, c.going) + " station"
                : c.at >= 0 ? "at the " + endName(l, c.at) + " station" : "out on the line";
            AbstractMinecart e = entity(level, c);
            String load = e instanceof MinecartChest box ? " with " + goods(box) + " goods" : "";
            String rider = "";
            if (c.rider != null && level.getEntity(c.rider) instanceof VillageFolkEntity f) rider = ", " + f.displayNameCap() + " aboard";
            else if (e != null && e.getFirstPassenger() instanceof Player p) rider = ", " + p.getName().getString() + " aboard";
            out.add(Railways.cap(c.role == Role.ORE ? "the ore cart" : "the riders' cart") + " on " + l.name() + ": " + where + load + rider + ".");
        }
        return out;
    }

    static String endName(Railways.Line l, int end) {
        return end == 0 ? "town's" : l.kind() == Railways.Kind.MINE ? "mine's" : "far";
    }

    /** Tests: the town's carts put on the line and worked, now. */
    public static void workForTests(ServerLevel level, Villages.Village v) {
        LAST_WORK.remove(v.id());
        work(level, v);
    }

    /** Tests: the cart of this kind on the line, or null. */
    @Nullable
    public static AbstractMinecart cartForTests(ServerLevel level, UUID village, String line, Role role) {
        Cart c = cart(village, line, role);
        return c == null ? null : entity(level, c);
    }

    /** Tests: where the cart stands: {at, going}. */
    public static int[] cartStateForTests(UUID village, String line, Role role) {
        Cart c = cart(village, line, role);
        return c == null ? new int[]{ -9, -9 } : new int[]{ c.at, c.going };
    }

    /** Tests: send the cart now. */
    public static boolean dispatchForTests(ServerLevel level, UUID village, String line, Role role, int to) {
        Cart c = cart(village, line, role);
        Railways.Line l = Railways.line(village, line);
        return c != null && l != null && dispatch(level, l, c, to);
    }

    /** Tests: a ride booked for this folk now. */
    public static void bookForTests(VillageFolkEntity f, ServerLevel level, Railways.Line l, int from, int to) {
        book(f, level, f.ownerId(), l, from, to, null);
    }

    /** Tests: put a cart of the stores' on the line now (the works done at once). */
    public static boolean putOnForTests(ServerLevel level, Villages.Village v, Railways.Line l, Role role) {
        return putOn(level, v, l, role);
    }

    /** Tests: a cart record for a cart the test made, standing at an end. */
    public static void adoptForTests(UUID village, Railways.Line l, AbstractMinecart cart, Role role, int at) {
        Cart c = new Cart(cart.getUUID(), role, l.key(), at);
        c.lastPos = cart.blockPosition();
        carts(village).add(c);
        cart.addTag(TAG);
        saveCarts(village);
    }

    /** Is this folk's cart ride the reason it is not at work? (VillageFolkEntity.calledAway, through Transport.) */
    public static boolean busy(VillageFolkEntity f) {
        return RIDES.containsKey(f.getUUID());
    }
}
