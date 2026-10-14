package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Level of detail for towns: how much of each town the server simulates, by how near the players are.
 *
 * <ul>
 * <li><b>FULL</b>: a player within {@code townLodFullRange} blocks of the town's ground. Everything runs.</li>
 * <li><b>NEAR</b>: a player within view distance (and a margin) of it. Its folk tick as ever; the town-level
 *     planners on the {@link TownScheduler} run at a slower beat.</li>
 * <li><b>AWAY</b>: nobody near for {@code townLodAwaySeconds}. The town <em>sleeps</em>: the chunks it and its folk
 *     force-load for their work are let go (and remembered), so its folk are saved with their chunks and cost
 *     nothing, and the town is simulated by a step of its own every few seconds at the rates it was measured to
 *     work at while somebody watched (see {@link Rates}): its stores fill and empty, its treasury earns and pays,
 *     and its folk raise children, at the same rates. Trade with it goes on: its stores answer from
 *     {@link #stock}, {@link #store} and {@link #take} (what they held when it slept, and what has come and gone
 *     since), so shipments and the market find the same goods there.</li>
 * </ul>
 * When a player comes near, the town <em>wakes</em>: its chunks are forced again, and once its stores are loaded the
 * goods made and used while it slept go into and out of the real chests, a few stacks a tick, and the children due
 * are born. Its folk grow up by the calendar, so a child that came of age while it slept is grown when it wakes.
 *
 * <p>Cost: one look per town a second, spread over the second (a town's turn comes on its own tick); an AWAY town's
 * step is a few map updates; a FULL town's stores are counted once a game day (its learning). Nothing here scans the
 * world.
 */
public final class TownLod {

    private static final Logger LOG = LogUtils.getLogger();

    public enum Lod { FULL, NEAR, AWAY }

    /** Each town is looked at once in this many ticks, on a tick of its own (its id picks it). */
    private static final int LOOK_EVERY = 20;
    /** Past the view distance, how much further a town still counts as near: a rider is ahead of the chunks. */
    private static final int NEAR_MARGIN = 48;
    /** How far past its loaded ring a town's own chunk requests are reckoned its own (its far plots, its windows). */
    private static final int AREA_MARGIN_CHUNKS = 2;
    /** A town is measured over at least this many ticks of being watched before the rates are taken. */
    private static final long LEARN_EVERY = 24000L;
    /** The rates learned are a running mean over about this many days, a week (an older day counts for less): long
     *  enough that a day's noise (a chest emptied for a build the day before the count) does not set the rate. */
    private static final double LEARN_SPAN_DAYS = 7.0;
    /** Learned over less than this, a town is simulated at the server's mean rates instead of its own. */
    private static final double MIN_OWN_DAYS = 0.5;
    /** How many lots of a woken town's goods go into (or out of) its stores in one tick. */
    private static final int APPLY_PER_TICK = 8;
    /** How long a woken town's chunks are given to come in before its stores are filled. */
    private static final long SETTLE_TICKS = 100L;
    /** A founding under way this near a town's heart keeps it awake till it is done. */
    private static final int FOUNDING_NEAR = 16;
    /** The ledger's notes (survive a restart): the learned rates, and an asleep town's state. */
    private static final String RATES_NOTE = "lod.rates", AWAY_NOTE = "lod.away",
        PARKED_NOTE = "lod.parked", OTHERS_NOTE = "lod.others";

    private TownLod() {}

    /** One town's level of detail, its learning and (asleep) its abstract state. */
    static final class Town {
        @Nullable Lod lod;
        long lastNear;
        long lastStep;
        // Learning, while watched: the stores, treasury and roll at the start of the measured stretch.
        @Nullable Map<String, Integer> base;
        int baseCoins, basePop;
        long baseTime;
        // Asleep: the stores as they stood, what came and went since, the coin and children due, the chunks let go.
        final Map<String, Integer> held = new HashMap<>();
        final Map<String, Double> pending = new HashMap<>();
        double coinDue, birthsDue;
        long sleptAt;
        final List<Parked> parked = new ArrayList<>();
        // Woken: the goods and children still to be put into the world, from when.
        boolean applying;
        long wokeAt;
        @Nullable Rates rates;
        boolean ratesRead;
        // Asleep: what others do to it (shipments, taxes, the war), measured from when, to be taken out of its rates.
        @Nullable Rates others;
        final Map<String, Double> byOthers = new HashMap<>();
        long othersFrom;
        int coinsFrom, coinsByStep;
    }

    /** A chunk request of a town's let go while it sleeps, to be made again when it wakes. */
    public record Parked(UUID owner, long chunk) {}

    /**
     * What a town does in a day, for each of its people: the goods its stores gain (or lose, eaten or used) item by
     * item, the coin its treasury gains (or pays out), and how much its roll grows. Measured from the town itself
     * while it was watched, over {@code days} days.
     */
    public record Rates(Map<String, Double> items, double coin, double growth, double days) {
        static final Rates NONE = new Rates(Map.of(), 0, 0, 0);
    }

    private static final Map<UUID, Town> TOWNS = new ConcurrentHashMap<>();
    /** How many towns are asleep (the chunk queue asks nothing of TownLod while none are). */
    private static int asleep;
    /** Sleeping towns whose list of chunks let go grew this tick (written once, at the next). */
    private static final java.util.Set<UUID> dirtyParked = new java.util.HashSet<>();
    @Nullable private static Rates serverMean;
    private static long serverMeanAt = Long.MIN_VALUE;
    /** Forced: every town held at one level, whatever the players (tests); null for the real reckoning. */
    @Nullable private static Lod forced;

    /** Forget every town's level (a world closing, or a test). */
    public static void reset() {
        TOWNS.clear();
        TownScheduler.reset();
        asleep = 0;
        liveInTests = false;
        dirtyParked.clear();
        serverMean = null;
        serverMeanAt = Long.MIN_VALUE;
        forced = null;
    }

    // ------------------------------------------------------------------ asking

    /** The town's level of detail just now (FULL for a town not yet looked at, or with the feature off). */
    public static Lod lod(@Nullable UUID village) {
        Town t = village == null ? null : TOWNS.get(village);
        return t == null || t.lod == null ? Lod.FULL : t.lod;
    }

    /** Is the town asleep (AWAY), simulated by its step rather than its folk? */
    public static boolean away(@Nullable UUID village) {
        return lod(village) == Lod.AWAY;
    }

    /** The rates a town is simulated at while asleep: its own if it was watched long enough, else the server's. */
    public static Rates ratesFor(UUID village) {
        Town t = town(village);
        Rates own = rates(village, t);
        if (own.days() >= MIN_OWN_DAYS) return own;
        Rates mean = serverMean();
        return mean.days() > 0 ? mean : own;
    }

    /** The game tests' server runs with nobody in it: every town is held awake there unless a test asks. */
    private static boolean liveInTests;

    /** Is the players' reckoning in force (the config's townLod, and on a test server only when a test asks)? */
    private static boolean live(MinecraftServer server) {
        if (!AssistantConfig.townLod()) return false;
        return liveInTests || !(server instanceof net.minecraft.gametest.framework.GameTestServer);
    }

    /** Tests: the players' reckoning in force on the test server too (the perf probes), or not. */
    public static void liveForTests(boolean on) {
        liveInTests = on;
    }

    /** Tests: hold every town at this level (null: back to the players' reckoning), at once. */
    public static void forceForTests(MinecraftServer server, @Nullable Lod lod) {
        forced = lod;
        for (Villages.Village v : Villages.every()) look(server, v, true);
    }

    /** Tests: the town simulated at these rates while it sleeps (as if measured over their days). */
    public static void ratesForTests(UUID village, Rates rates) {
        Town t = town(village);
        t.rates = rates;
        t.ratesRead = true;
    }

    /** Tests: a sleeping town's step run as if this many days had passed since its last. */
    public static void stepForTests(UUID village, double days) {
        Town t = TOWNS.get(village);
        if (t == null || t.lod != Lod.AWAY) return;
        step(Villages.get(village), t, t.lastStep + Math.round(days * 24000));
    }

    /** Tests: the goods a sleeping town has made (+) or used (-) since it slept, item by item. */
    public static Map<String, Double> pendingForTests(UUID village) {
        Town t = TOWNS.get(village);
        return t == null ? Map.of() : Map.copyOf(t.pending);
    }

    /** Tests: are a woken town's goods and children all in the world? */
    public static boolean settledForTests(UUID village) {
        Town t = TOWNS.get(village);
        return t == null || !t.applying;
    }

    // ------------------------------------------------------------------ the tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        com.jrpetty.mcassistant.Guard.run("the towns' level of detail", () -> tick(event.getServer()));
    }

    private static void tick(MinecraftServer server) {
        if (!dirtyParked.isEmpty()) {
            for (UUID id : dirtyParked) {
                Town t = TOWNS.get(id);
                if (t != null && t.lod == Lod.AWAY) saveParked(id, t);
            }
            dirtyParked.clear();
        }
        long tick = server.getTickCount();
        int slot = (int) (tick % LOOK_EVERY);
        for (Villages.Village v : Villages.every()) {
            if (Math.floorMod(v.id().hashCode(), LOOK_EVERY) == slot) look(server, v, false);
            Town t = TOWNS.get(v.id());
            if (t != null && t.applying) applySome(server, v, t);
        }
        if (tick % 1200 == 0 && TOWNS.keySet().removeIf(id -> Villages.get(id) == null)) {
            asleep = (int) TOWNS.values().stream().filter(t -> t.lod == Lod.AWAY).count();
        }
    }

    /** One town's look: its level from the players, then its step (asleep) or its learning (watched). */
    private static void look(MinecraftServer server, Villages.Village v, boolean now) {
        ServerLevel level = server.getLevel(v.dim());
        if (level == null) return;
        Town t = town(v.id());
        long time = level.getGameTime();
        Lod want = forced != null ? forced : !live(server) ? Lod.FULL : reckon(level, v);
        if (want != Lod.AWAY) {
            t.lastNear = time;
            Lod was = t.lod;
            t.lod = want;
            if (was == Lod.AWAY) wake(level, v, t);
            learn(level, v, t, time);
            return;
        }
        if (t.lod == null) {
            // First seen (a world just opened, the player perhaps not in it yet): it waits like any other.
            t.lod = Lod.FULL;
            t.lastNear = time;
        }
        if (t.lod != Lod.AWAY) {
            // A town somebody has just left waits a while, so a player stepping out and back does not wake it again
            // and again.
            if (!now && time - t.lastNear < AssistantConfig.townLodAwaySeconds() * 20L) return;
            if (Founding.near(level, v.centre(), FOUNDING_NEAR)) return;      // its founding holds its own ground
            sleep(level, v, t);
            return;
        }
        if (time - t.lastStep >= AssistantConfig.townLodStepSeconds() * 20L || now) step(v, t, time);
    }

    /** Where the players are, against this town's ground. */
    private static Lod reckon(ServerLevel level, Villages.Village v) {
        int area = areaBlocks(v.id());
        double full = area + AssistantConfig.townLodFullRange();
        double near = area + level.getServer().getPlayerList().getViewDistance() * 16 + NEAR_MARGIN;
        Lod best = Lod.AWAY;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = p.getX() - v.centre().getX(), dz = p.getZ() - v.centre().getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 <= full * full) return Lod.FULL;
            if (d2 <= near * near) best = Lod.NEAR;
        }
        return best;
    }

    /** How far the town's ground reaches from its heart, in blocks: its loaded ring, or its streets if further. */
    static int areaBlocks(UUID village) {
        return Math.max(Villages.townReach(village), VillageSpawner.loadedRadiusFor(Villages.headcount(village)) * 16 + 8);
    }

    /** The town's own chunks, as a radius in chunks round its heart's: its ring, its stores' reach and a margin. */
    static int areaChunks(UUID village) {
        int heads = Villages.headcount(village);
        return Math.max(VillageSpawner.loadedRadiusFor(heads), (Villages.storesRadius(village) >> 4) + 1) + AREA_MARGIN_CHUNKS;
    }

    // ------------------------------------------------------------------ sleeping and waking

    /** Nobody near: the town's stores as they stand are kept, its chunks let go, and its step takes over. */
    private static void sleep(ServerLevel level, Villages.Village v, Town t) {
        t.lod = Lod.AWAY;
        asleep++;
        t.base = null;
        t.held.clear();
        t.pending.clear();
        t.coinDue = 0;
        t.birthsDue = 0;
        t.applying = false;
        t.sleptAt = level.getGameTime();
        t.lastStep = t.sleptAt;
        t.othersFrom = 0;
        Map<String, Integer> stores = level.isLoaded(v.centre()) ? storesNow(level, v.id()) : null;
        if (stores != null) t.held.putAll(stores);
        int r = areaChunks(v.id());
        ChunkPos c = new ChunkPos(v.centre());
        List<Parked> let = ChunkLoad.releaseWithin(level, c.x - r, c.z - r, c.x + r, c.z + r,
            chunk -> !awakeTownOwns(level, chunk));
        t.parked.addAll(let);
        save(v.id(), t);
        saveParked(v.id(), t);
        LOG.info("[MCA-LOD] {} sleeps (nobody near): {} forced chunks let go, its stores counted at {} kinds",
            Villages.name(v.id()), let.size(), t.held.size());
    }

    /** Somebody near: the town's ring and every chunk let go are asked for again, and its goods put into the world. */
    private static void wake(ServerLevel level, Villages.Village v, Town t) {
        asleep = Math.max(0, asleep - 1);
        ChunkLoad.setLoaded(level, v.id(), v.centre(), VillageSpawner.loadedRadiusFor(Villages.headcount(v.id())), true);
        for (Parked p : t.parked) {
            BlockPos at = new BlockPos((ChunkPos.getX(p.chunk()) << 4) + 8, 64, (ChunkPos.getZ(p.chunk()) << 4) + 8);
            ChunkLoad.setLoaded(level, p.owner(), at, 0, true);
        }
        int parked = t.parked.size();
        t.parked.clear();
        t.held.clear();
        t.applying = true;
        t.wokeAt = level.getGameTime();
        t.base = null;
        Ledger.forget(v.id(), AWAY_NOTE);
        Ledger.forget(v.id(), PARKED_NOTE);
        LOG.info("[MCA-LOD] {} wakes after {} days: {} chunks asked for again, {} kinds of goods and {} children to settle",
            Villages.name(v.id()), String.format(Locale.ROOT, "%.2f", (t.wokeAt - t.sleptAt) / 24000.0), parked,
            t.pending.size(), (int) t.birthsDue);
    }

    /** Is this chunk inside the ground of a town that is awake (so not to be let go for a sleeping neighbour)? */
    private static boolean awakeTownOwns(ServerLevel level, long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension()) || away(v.id())) continue;
            int r = areaChunks(v.id());
            if (Math.abs(x - (v.centre().getX() >> 4)) <= r && Math.abs(z - (v.centre().getZ() >> 4)) <= r) return true;
        }
        return false;
    }

    /**
     * ChunkLoad's question before it forces chunks: is this request on the ground of a sleeping town (and of no
     * awake one)? If so it is kept for the town's waking instead, and true is returned.
     */
    public static boolean park(ServerLevel level, UUID owner, int chunkX, int chunkZ) {
        if (asleep == 0) return false;
        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        Town sleeper = null;
        UUID asleepId = null;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int r = areaChunks(v.id());
            if (Math.abs(chunkX - (v.centre().getX() >> 4)) > r || Math.abs(chunkZ - (v.centre().getZ() >> 4)) > r) continue;
            if (!away(v.id())) return false;
            sleeper = TOWNS.get(v.id());
            asleepId = v.id();
        }
        if (sleeper == null) return false;
        Parked p = new Parked(owner, chunk);
        if (!sleeper.parked.contains(p)) {
            sleeper.parked.add(p);
            dirtyParked.add(asleepId);
        }
        return true;
    }

    // ------------------------------------------------------------------ the step (asleep)

    /** A sleeping town's few seconds: its goods made and used, its coin, its children, at its rates. */
    private static void step(Villages.Village v, Town t, long time) {
        double days = (time - t.lastStep) / 24000.0;
        t.lastStep = time;
        if (days <= 0) return;
        Rates r = ratesFor(v.id());
        Rates others = othersAsleep(v.id(), t);
        int heads = Villages.headcount(v.id());
        // The town's own rates, less what the rest of the world still does to it asleep (a shipment, a tax, the war's
        // cost): that was in what it was measured to do watched, and it goes on happening, so it is not done twice.
        for (Map.Entry<String, Double> e : r.items().entrySet()) {
            double rate = e.getValue() - others.items().getOrDefault(e.getKey(), 0.0);
            t.pending.merge(e.getKey(), rate * heads * days, Double::sum);
        }
        for (Map.Entry<String, Double> e : others.items().entrySet()) {
            if (!r.items().containsKey(e.getKey())) t.pending.merge(e.getKey(), -e.getValue() * heads * days, Double::sum);
        }
        t.coinDue += (r.coin() - others.coin()) * heads * days;
        int coins = (int) t.coinDue;                       // whole coins only; the rest waits for the next step
        int moved = coins > 0 ? coins : coins < 0 ? -Ledger.takeCoins(v.id(), -coins) : 0;
        if (coins > 0) Ledger.addCoins(v.id(), coins);
        t.coinDue -= coins;
        t.coinsByStep += moved;
        if (r.growth() > 0) t.birthsDue += r.growth() * heads * days;
        learnOthers(v.id(), t, time, heads);
        save(v.id(), t);
    }

    /** What the rest of the world does to this sleeping town (learned while it sleeps), or nothing yet. */
    private static Rates othersAsleep(UUID village, Town t) {
        if (t.others == null) t.others = read(Ledger.note(village, OTHERS_NOTE));
        return t.others;
    }

    /**
     * Asleep a day at a time: the goods that came and went by others' hands (store and take) and the coin the
     * treasury gained or lost beyond what the step moved, per head per day, into the running mean.
     */
    private static void learnOthers(UUID village, Town t, long time, int heads) {
        if (t.othersFrom == 0) {
            markOthers(village, t, time);
            return;
        }
        if (time - t.othersFrom < LEARN_EVERY) return;
        double days = (time - t.othersFrom) / 24000.0, h = Math.max(1, heads);
        Map<String, Double> items = new HashMap<>();
        for (Map.Entry<String, Double> e : t.byOthers.entrySet()) items.put(e.getKey(), e.getValue() / days / h);
        double coin = (Ledger.coins(village) - t.coinsFrom - t.coinsByStep) / days / h;
        t.others = merge(othersAsleep(village, t), new Rates(items, coin, 0, days));
        Ledger.note(village, OTHERS_NOTE, write(t.others));
        markOthers(village, t, time);
    }

    private static void markOthers(UUID village, Town t, long time) {
        t.othersFrom = time;
        t.coinsFrom = Ledger.coins(village);
        t.coinsByStep = 0;
        t.byOthers.clear();
    }

    // ------------------------------------------------------------------ the stores, asleep

    /** How much of what matches a sleeping town's stores hold: as they stood when it slept, and what came and went. */
    public static int stock(UUID village, Predicate<ItemStack> what) {
        Town t = TOWNS.get(village);
        if (t == null) return 0;
        int n = 0;
        for (String key : keys(t)) {
            ItemStack kind = kind(key);
            if (!kind.isEmpty() && what.test(kind)) n += have(t, key);
        }
        return n;
    }

    /** Goods put into a sleeping town's stores (a shipment come in, a gift): kept for its waking. Nothing is left over. */
    public static ItemStack store(UUID village, ItemStack stack) {
        Town t = TOWNS.get(village);
        if (t == null || stack.isEmpty()) return stack;
        t.pending.merge(Economy.id(stack), (double) stack.getCount(), Double::sum);
        t.byOthers.merge(Economy.id(stack), (double) stack.getCount(), Double::sum);
        return ItemStack.EMPTY;
    }

    /** Goods out of a sleeping town's stores, all or nothing (as TownWork.take). */
    public static boolean take(UUID village, Predicate<ItemStack> what, int n) {
        Town t = TOWNS.get(village);
        if (t == null || n <= 0) return n <= 0;
        if (stock(village, what) < n) return false;
        int left = n;
        for (String key : keys(t)) {
            if (left <= 0) break;
            ItemStack kind = kind(key);
            if (kind.isEmpty() || !what.test(kind)) continue;
            int k = Math.min(left, have(t, key));
            if (k <= 0) continue;
            t.pending.merge(key, (double) -k, Double::sum);
            t.byOthers.merge(key, (double) -k, Double::sum);
            left -= k;
        }
        return true;
    }

    private static List<String> keys(Town t) {
        List<String> out = new ArrayList<>(t.held.keySet());
        for (String k : t.pending.keySet()) if (!t.held.containsKey(k)) out.add(k);
        return out;
    }

    private static int have(Town t, String key) {
        return Math.max(0, (int) Math.floor(t.held.getOrDefault(key, 0) + t.pending.getOrDefault(key, 0.0)));
    }

    private static final Map<String, ItemStack> KINDS = new ConcurrentHashMap<>();

    /** One of the item this key names (EMPTY for a key no item answers to). */
    static ItemStack kind(String key) {
        return KINDS.computeIfAbsent(key, k -> {
            ResourceLocation id = ResourceLocation.tryParse(k);
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        });
    }

    /** What the town's stores hold just now, item by item (plain goods only: nothing named, enchanted or worn). */
    static Map<String, Integer> storesNow(ServerLevel level, UUID village) {
        Map<String, Integer> out = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && s.isComponentsPatchEmpty()) out.merge(Economy.id(s), s.getCount(), Integer::sum);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ waking: into the world

    /** A woken town's goods into (and out of) its real stores, a few lots a tick, then its children. */
    private static void applySome(MinecraftServer server, Villages.Village v, Town t) {
        ServerLevel level = server.getLevel(v.dim());
        if (level == null || away(v.id())) return;
        if (level.getGameTime() - t.wokeAt < SETTLE_TICKS || !level.isLoaded(v.centre())) return;
        int done = 0;
        Iterator<Map.Entry<String, Double>> it = t.pending.entrySet().iterator();
        while (it.hasNext() && done < APPLY_PER_TICK) {
            Map.Entry<String, Double> e = it.next();
            ItemStack kind = kind(e.getKey());
            int n = (int) Math.floor(Math.abs(e.getValue()));
            if (kind.isEmpty() || n <= 0) { it.remove(); continue; }
            int lot = Math.min(n, kind.getMaxStackSize());
            if (e.getValue() > 0) {
                ItemStack left = Market.intoStores(level, v.id(), kind.copyWithCount(lot));
                if (!left.isEmpty()) LOG.debug("[MCA-LOD] {}: no room in the stores for {} {}", Villages.name(v.id()), left.getCount(), e.getKey());
            } else {
                Item item = kind.getItem();
                int have = Market.stock(level, v.id(), s -> s.is(item) && s.isComponentsPatchEmpty());
                int k = Math.min(lot, have);
                if (k > 0) TownWork.take(level, v, s -> s.is(item) && s.isComponentsPatchEmpty(), k);
                if (k < lot) { it.remove(); done++; continue; }      // used what there was: the rest was never there
            }
            double rest = Math.signum(e.getValue()) * (Math.abs(e.getValue()) - lot);
            if (Math.abs(rest) < 1) it.remove(); else e.setValue(rest);
            done++;
        }
        if (done > 0 || !t.pending.isEmpty()) return;
        bornWhileAway(level, v, t);
        t.applying = false;
        t.birthsDue = 0;
    }

    /** The children due while the town slept, born to its couples now (as any birth: room, food and the caps). */
    private static void bornWhileAway(ServerLevel level, Villages.Village v, Town t) {
        int due = (int) t.birthsDue;
        if (due <= 0) return;
        List<VillageFolkEntity> adults = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() != AssistantEntity.StationTask.NONE) adults.add(f);
        }
        int born = 0;
        for (int i = 0; i + 1 < adults.size() && born < due; i += 2) {
            if (!roomForOneMore(v.id())) break;
            VillageFolkEntity a = adults.get(i), b = partnerOf(a, adults, i);
            if (b == null) continue;
            if (a.raiseChildWith(b) != null) born++;
        }
        if (born > 0) LOG.info("[MCA-LOD] {}: {} of {} children due while it slept were born", Villages.name(v.id()), born, due);
    }

    /** This folk's partner among the adults if there, else the next adult on the list. */
    @Nullable
    private static VillageFolkEntity partnerOf(VillageFolkEntity a, List<VillageFolkEntity> adults, int i) {
        UUID p = a.life().partner();
        if (p != null) for (VillageFolkEntity f : adults) if (f.getUUID().equals(p)) return f;
        return adults.get(i + 1);
    }

    private static boolean roomForOneMore(UUID village) {
        int heads = Villages.headcount(village);
        return heads < AssistantConfig.villageGrowthCap() && heads < Villages.housing(village)
            && Villages.worldHeadcount() < AssistantConfig.villageWorldCap();
    }

    // ------------------------------------------------------------------ learning (watched)

    /** A watched town's stores, treasury and roll counted at the start of a stretch, and its rates taken at the end. */
    private static void learn(ServerLevel level, Villages.Village v, Town t, long time) {
        if (t.applying || !level.isLoaded(v.centre())) {
            t.base = null;
            return;
        }
        if (t.base != null && time - t.baseTime < LEARN_EVERY) return;
        Map<String, Integer> now = storesNow(level, v.id());
        int coins = Ledger.coins(v.id()), pop = Villages.recordedPopulation(v.id());
        if (t.base != null) {
            double days = (time - t.baseTime) / 24000.0;
            double heads = Math.max(1, (t.basePop + pop) / 2.0);
            Map<String, Double> items = new HashMap<>();
            for (String k : now.keySet()) items.put(k, (now.get(k) - t.base.getOrDefault(k, 0)) / days / heads);
            for (String k : t.base.keySet()) if (!now.containsKey(k)) items.put(k, -t.base.get(k) / days / heads);
            Rates measured = new Rates(items, (coins - t.baseCoins) / days / heads, (pop - t.basePop) / days / heads, days);
            Rates merged = merge(rates(v.id(), t), measured);
            t.rates = merged;
            Ledger.note(v.id(), RATES_NOTE, write(merged));
        }
        t.base = now;
        t.baseCoins = coins;
        t.basePop = pop;
        t.baseTime = time;
    }

    /** The running mean: what was known, and a new stretch weighed by its length (an older day counts for less). */
    static Rates merge(Rates was, Rates add) {
        double w = add.days() / Math.min(was.days() + add.days(), LEARN_SPAN_DAYS);
        w = Math.min(1.0, w);
        Map<String, Double> items = new HashMap<>();
        for (Map.Entry<String, Double> e : was.items().entrySet()) items.put(e.getKey(), e.getValue() * (1 - w));
        for (Map.Entry<String, Double> e : add.items().entrySet()) items.merge(e.getKey(), e.getValue() * w, Double::sum);
        items.values().removeIf(x -> Math.abs(x) < 1e-6);
        return new Rates(items, was.coin() * (1 - w) + add.coin() * w, was.growth() * (1 - w) + add.growth() * w,
            was.days() + add.days());
    }

    /** The mean of every town's own rates, weighed by how long each was measured (a minute's cache). */
    private static Rates serverMean() {
        long now = System.currentTimeMillis();
        if (serverMean != null && now - serverMeanAt < 60_000L) return serverMean;
        Map<String, Double> items = new HashMap<>();
        double coin = 0, growth = 0, days = 0;
        for (Villages.Village v : Villages.every()) {
            Rates r = rates(v.id(), town(v.id()));
            if (r.days() < MIN_OWN_DAYS) continue;
            for (Map.Entry<String, Double> e : r.items().entrySet()) items.merge(e.getKey(), e.getValue() * r.days(), Double::sum);
            coin += r.coin() * r.days();
            growth += r.growth() * r.days();
            days += r.days();
        }
        if (days > 0) {
            final double d = days;
            items.replaceAll((k, x) -> x / d);
            serverMean = new Rates(items, coin / d, growth / d, d);
        } else {
            serverMean = Rates.NONE;
        }
        serverMeanAt = now;
        return serverMean;
    }

    // ------------------------------------------------------------------ keeping (the ledger's notes)

    private static Town town(UUID village) {
        return TOWNS.computeIfAbsent(village, id -> restore(id));
    }

    /** A town's state after a restart: asleep as it was (its chunks still let go), its rates as learned. */
    private static Town restore(UUID village) {
        Town t = new Town();
        String away = Ledger.note(village, AWAY_NOTE);
        if (away == null || away.isEmpty()) return t;
        try {
            String[] p = away.split(";", -1);
            if (!"v1".equals(p[0])) return t;
            t.sleptAt = Long.parseLong(p[1]);
            t.lastStep = Long.parseLong(p[2]);
            t.coinDue = Double.parseDouble(p[3]);
            t.birthsDue = Double.parseDouble(p[4]);
            readCounts(p[5], t.held);
            Map<String, Double> pending = readMap(p[6]);
            t.pending.putAll(pending);
            String parked = Ledger.note(village, PARKED_NOTE);
            for (String s : parked == null || parked.isEmpty() ? new String[0] : parked.split(",")) {
                int at = s.indexOf('@');
                t.parked.add(new Parked(UUID.fromString(s.substring(0, at)), Long.parseLong(s.substring(at + 1))));
            }
        } catch (RuntimeException e) {
            LOG.warn("[MCA-LOD] {}: its sleeping state could not be read ({}); it starts awake", village, e.toString());
            return new Town();
        }
        t.lod = Lod.AWAY;
        asleep++;
        return t;
    }

    private static Rates rates(UUID village, Town t) {
        if (t.rates == null && !t.ratesRead) {
            t.ratesRead = true;
            t.rates = read(Ledger.note(village, RATES_NOTE));
        }
        return t.rates == null ? Rates.NONE : t.rates;
    }

    /** A sleeping town's few changing numbers (each step): its times, coin and children due, its stores. */
    private static void save(UUID village, Town t) {
        StringBuilder held = new StringBuilder();
        for (Map.Entry<String, Integer> e : t.held.entrySet()) {
            if (!held.isEmpty()) held.append(',');
            held.append(e.getKey()).append('=').append(e.getValue());
        }
        Ledger.note(village, AWAY_NOTE, String.join(";", "v1", Long.toString(t.sleptAt), Long.toString(t.lastStep),
            Double.toString(t.coinDue), Double.toString(t.birthsDue), held, writeMap(t.pending)));
    }

    /** The chunks a sleeping town let go (when it sleeps, and when a request is kept for it). */
    private static void saveParked(UUID village, Town t) {
        StringBuilder parked = new StringBuilder();
        for (Parked p : t.parked) {
            if (!parked.isEmpty()) parked.append(',');
            parked.append(p.owner()).append('@').append(p.chunk());
        }
        Ledger.note(village, PARKED_NOTE, parked.toString());
    }

    static String write(Rates r) {
        return String.join(";", "v1", Double.toString(r.days()), Double.toString(r.coin()), Double.toString(r.growth()), writeMap(r.items()));
    }

    static Rates read(@Nullable String s) {
        if (s == null || s.isEmpty()) return Rates.NONE;
        try {
            String[] p = s.split(";", -1);
            if (!"v1".equals(p[0])) return Rates.NONE;
            return new Rates(readMap(p[4]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[1]));
        } catch (RuntimeException e) {
            return Rates.NONE;
        }
    }

    private static String writeMap(Map<String, Double> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Double> e : m.entrySet()) {
            if (!b.isEmpty()) b.append(',');
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }

    private static Map<String, Double> readMap(String s) {
        Map<String, Double> out = new HashMap<>();
        if (s.isEmpty()) return out;
        for (String kv : s.split(",")) {
            int at = kv.lastIndexOf('=');
            out.put(kv.substring(0, at), Double.parseDouble(kv.substring(at + 1)));
        }
        return out;
    }

    private static void readCounts(String s, Map<String, Integer> into) {
        for (Map.Entry<String, Double> e : readMap(s).entrySet()) into.put(e.getKey(), (int) Math.round(e.getValue()));
    }
}
