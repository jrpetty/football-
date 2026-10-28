package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [borders-map] A town's lands: the ground it holds round its heart, chunk by chunk, and where it meets its neighbours'.
 *
 * <p><b>How far a town's word runs.</b> Out from its heart over the ground, step by step, as far as its standing will
 * carry it: a camp of a few folk holds a long stone's throw round its fire; a town of a hundred, old, built up and in the
 * Iron Age, holds three hundred blocks and more. Every grown folk, every age, every building and every year adds to it
 * (budget). Open grass is easy to hold, a wood a little less so, bare rock and snow harder, and water hardest of all, so
 * a town's lands run along a valley and stop at a lake's shore, and a mountain range or the sea makes a natural border.
 * Its fields, its mine, its woodcutters' plots and every building it has put up are its own too, and pull its lands out
 * to them (sources).
 *
 * <p><b>Where two towns meet.</b> Every chunk goes to the town that holds it most easily for its size (the cost of the
 * walk out to it against how far its word runs). Where two towns hold it near enough equally the ground is contested:
 * the line between them runs there, but neither is happy with it until their elders agree it (Bonds.agreeBorder: the
 * line is fixed where it stands, and stays there as the towns grow) or a dispute settles it (BorderDisputes). A treaty,
 * a payment, an arbiter's word or a war won fixes the chunks it gave to whoever it gave them to (overrides).
 *
 * <p><b>What is kept.</b> The lands as last drawn (so the map and the borders are there the moment the world loads,
 * before every town's folk have woken and put their town back on the register; a town not yet back keeps its lands
 * asleep), the chunks fixed by treaty, the towns fallen to ruin (their claims let go: release), what each town has seen
 * of its lands change, the border markers (BorderMarkers) and the disputes (BorderDisputes). All of it with the world.
 *
 * <p><b>Seen by players.</b> Crossing a border puts its name on the screen ("Entering the lands of Ashford", with its
 * motto under it); a toggle (/village borders notices) turns it to the action bar or off. The war room's map table
 * (WarRoom) draws the lands in each town's colour. The Lands page of the town's books, the chronicle and the board.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Borders extends SavedData {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final String ID = "mc_assistant_borders";

    /** The least a town's word runs, in chunks of easy ground, and the most. */
    static final double LEAST = 4.0, MOST = 24.0;
    /** How far a field, a mine or a building pulls the lands out round itself, in chunks. */
    static final double SOURCE = 2.5;
    /** Two towns within this much of each other's hold on a chunk both claim it (on the 0..1 scale). */
    static final float CONTESTED = 0.12F;
    /** How often the lands are drawn again (ticks), and how soon after something moves them. */
    static final long REDRAW = 2400L, SOON = 100L;
    /** How many recent changes each town keeps of its lands. */
    static final int CHANGES = 12;

    // ------------------------------------------------------------------ kept with the world

    /** One dimension's lands as last drawn. */
    static final class Land {
        final Long2ObjectOpenHashMap<UUID> owner = new Long2ObjectOpenHashMap<>();
        /** Contested chunks, and the other town that claims each. */
        final Long2ObjectOpenHashMap<UUID> contested = new Long2ObjectOpenHashMap<>();
        /** Each town's hold on each chunk it reaches (0 at its heart, 1 at its edge): not kept, drawn each time. */
        final Map<UUID, Long2FloatOpenHashMap> hold = new HashMap<>();
        long drawnAt = -1;
    }

    /** What a town remembers of its lands: how big they were when last told, and what has changed. */
    static final class Lands {
        int area, told;
        long firstMet = -1;
        final List<String> changes = new ArrayList<>();
        final Set<UUID> metLines = new HashSet<>();
        String name = "";
        int x, z;
    }

    final Map<String, Land> lands = new HashMap<>();
    /** Chunks fixed to a town by a treaty, an arbiter, a payment or a war, by dimension. */
    final Map<String, Long2ObjectOpenHashMap<UUID>> fixed = new HashMap<>();
    /** Towns fallen to ruin or gone: their lands let go. Name and heart kept for the map. */
    final Map<UUID, String> fallen = new LinkedHashMap<>();
    final Map<UUID, Lands> towns = new HashMap<>();
    /** Every change to anybody's lands bumps this: the map table's clients redraw their borders when it moves. */
    long version;
    /** The disputes, open and settled (BorderDisputes). */
    final List<BorderDisputes.Dispute> disputes = new ArrayList<>();
    int nextDispute = 1;
    /** The border markers, by dimension and block (BorderMarkers). */
    final Map<String, Long2ObjectOpenHashMap<BorderMarkers.Marker>> markers = new HashMap<>();

    private Borders() {}

    @Nullable private static Borders cached;
    @Nullable private static MinecraftServer cachedFor;

    static Borders data(MinecraftServer server) {
        if (cachedFor == server && cached != null) return cached;
        Borders b = server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Borders::new, Borders::load, null), ID);
        cached = b;
        cachedFor = server;
        return b;
    }

    @Nullable
    static Borders data() {
        MinecraftServer s = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return s == null ? null : data(s);
    }

    static String dim(Level level) {
        return level.dimension().location().toString();
    }

    static String dim(Villages.Village v) {
        return v.dim().location().toString();
    }

    Land land(String dim) {
        return lands.computeIfAbsent(dim, k -> new Land());
    }

    Lands lands(UUID town) {
        return towns.computeIfAbsent(town, k -> new Lands());
    }

    /** When each dimension is next drawn. */
    private static final Map<String, Long> DUE = new ConcurrentHashMap<>();
    /** Where each player last stood, as whose land (for the notice on crossing). */
    private static final Map<UUID, String> STOOD = new ConcurrentHashMap<>();
    @Nullable private static Boolean onForTests;

    public static void resetForTests(ServerLevel level) {
        Borders b = data(level.getServer());
        b.lands.clear();
        b.fixed.clear();
        b.fallen.clear();
        b.towns.clear();
        b.disputes.clear();
        b.markers.clear();
        b.version++;
        b.setDirty();
        DUE.clear();
        STOOD.clear();
        REACH.clear();
        BorderMarkers.resetForTests();
        BorderDisputes.resetForTests();
        WarRoom.resetForTests();
    }

    /** Are the borders on (the config's townBorders)? */
    public static boolean on() {
        Boolean t = onForTests;
        return t != null ? t : com.jrpetty.mcassistant.AssistantConfig.townBorders();
    }

    public static void switchForTests(@Nullable Boolean on) {
        onForTests = on;
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        cached = null;
        cachedFor = null;
        REACH.clear();
        DUE.clear();
        STOOD.clear();
    }

    // ------------------------------------------------------------------ the public face

    /** Whose land this is (null: nobody's, or the borders are off). For an estate, a law, a toll: whoever asks. */
    @Nullable
    public static UUID owner(Level level, BlockPos pos) {
        Borders b = level.getServer() == null ? null : data(level.getServer());
        if (b == null || !on()) return null;
        Land l = b.lands.get(dim(level));
        return l == null ? null : l.owner.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** The other town that claims this chunk as well, where it is contested; null if nobody disputes it. */
    @Nullable
    public static UUID rival(Level level, BlockPos pos) {
        Borders b = level.getServer() == null ? null : data(level.getServer());
        if (b == null || !on()) return null;
        Land l = b.lands.get(dim(level));
        return l == null ? null : l.contested.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /**
     * A town has fallen to ruin, or is gone: its lands are let go there and then (anybody's to take as the others
     * grow), the chunks fixed to it by treaty freed, its disputes closed and its markers left standing as they are,
     * nobody's any more. The ruin keeps its name on the map.
     */
    public static void release(UUID town) {
        Borders b = data();
        if (b == null) return;
        Lands mine = b.towns.get(town);
        Villages.Village v = Villages.get(town);
        String name = Villages.name(town);
        if (mine == null && v == null && !b.ownsAny(town)) return;
        b.fallen.put(town, name + "|" + (v != null ? v.centre().getX() : mine != null ? mine.x : 0) + "|"
            + (v != null ? v.centre().getZ() : mine != null ? mine.z : 0));
        while (b.fallen.size() > 64) b.fallen.remove(b.fallen.keySet().iterator().next());
        for (Long2ObjectOpenHashMap<UUID> f : b.fixed.values()) f.values().removeIf(town::equals);
        for (Land l : b.lands.values()) {
            l.owner.values().removeIf(town::equals);
            l.contested.values().removeIf(town::equals);
            l.hold.remove(town);
        }
        BorderDisputes.closeAll(b, town, "the matter died with " + name);
        BorderMarkers.abandon(b, town);
        b.towns.remove(town);
        b.version++;
        b.setDirty();
        DUE.clear();
        LOG.info("[MCA-BORDERS] the lands of {} let go", name);
    }

    private boolean ownsAny(UUID town) {
        for (Land l : lands.values()) if (l.owner.containsValue(town)) return true;
        return false;
    }

    /** Is the town's land let go (a ruin)? */
    public static boolean fallen(UUID town) {
        Borders b = data();
        return b != null && b.fallen.containsKey(town);
    }

    /** How many chunks the town holds (contested ones included). */
    public static int area(UUID town) {
        Borders b = data();
        if (b == null) return 0;
        Villages.Village v = Villages.get(town);
        Land l = v == null ? null : b.lands.get(dim(v));
        if (l == null) return 0;
        int n = 0;
        for (UUID o : l.owner.values()) if (o.equals(town)) n++;
        return n;
    }

    /** The chunks the town holds (ChunkPos longs). */
    public static LongOpenHashSet territory(UUID town) {
        LongOpenHashSet out = new LongOpenHashSet();
        Borders b = data();
        Villages.Village v = Villages.get(town);
        Land l = b == null || v == null ? null : b.lands.get(dim(v));
        if (l == null) return out;
        for (Long2ObjectOpenHashMap.Entry<UUID> e : l.owner.long2ObjectEntrySet()) if (e.getValue().equals(town)) out.add(e.getLongKey());
        return out;
    }

    /** Do the two towns' claims overlap anywhere (ground both claim, no line agreed)? */
    public static boolean contested(UUID a, UUID b) {
        return contestedCount(a, b) > 0;
    }

    static int contestedCount(UUID a, UUID b) {
        Borders d = data();
        Villages.Village va = Villages.get(a);
        Land l = d == null || va == null ? null : d.lands.get(dim(va));
        if (l == null) return 0;
        int n = 0;
        for (Long2ObjectOpenHashMap.Entry<UUID> e : l.contested.long2ObjectEntrySet()) {
            UUID o = l.owner.get(e.getLongKey());
            if (o == null) continue;
            if (o.equals(a) && e.getValue().equals(b) || o.equals(b) && e.getValue().equals(a)) n++;
        }
        return n;
    }

    /** Is there a land dispute open between the two (BorderDisputes)? */
    public static boolean disputed(UUID a, UUID b) {
        return BorderDisputes.open(a, b) != null;
    }

    /**
     * [borders-map] Is this spot plainly another town's land, so that this town's folk should not stake a field, a
     * plot or a mine there (Bonds.overBorder)? Only where another town holds it outright: contested ground and the
     * wilds are anybody's, and a town's own land is its own.
     */
    public static boolean theirs(@Nullable UUID village, Level level, BlockPos spot) {
        if (village == null) return false;
        UUID o = owner(level, spot);
        return o != null && !o.equals(village) && rival(level, spot) == null && Villages.get(o) != null;
    }

    /** The same, for a town's own spot (in its own dimension), with no level to hand (Bonds.overBorder). */
    public static boolean theirs(@Nullable UUID village, BlockPos spot) {
        Villages.Village v = village == null ? null : Villages.get(village);
        Borders b = data();
        if (v == null || b == null || !on()) return false;
        Land l = b.lands.get(dim(v));
        if (l == null) return false;
        long c = ChunkPos.asLong(spot.getX() >> 4, spot.getZ() >> 4);
        UUID o = l.owner.get(c);
        return o != null && !o.equals(village) && !l.contested.containsKey(c) && Villages.get(o) != null;
    }

    /** Every town's lands drawn again soon (a treaty, a war, a new town). */
    public static void redrawSoon() {
        DUE.clear();
    }

    // ------------------------------------------------------------------ the day's work

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 13) return;
        Guard.run("borders", () -> {
            if (!on()) return;
            for (ServerLevel level : event.getServer().getAllLevels()) {
                String d = dim(level);
                long now = level.getGameTime();
                Long due = DUE.get(d);
                if (due == null || now >= due) {
                    boolean any = false;
                    for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension())) { any = true; break; }
                    Borders b = data(event.getServer());
                    if (any || b.lands.containsKey(d)) draw(level);
                    DUE.put(d, now + REDRAW);
                }
                BorderDisputes.tick(level);
                BorderMarkers.tick(level);
                WarRoom.tick(level);
            }
            notices(event.getServer());
        });
    }

    /** Tests and commands: the lands of this dimension drawn now. */
    public static void drawNow(ServerLevel level) {
        draw(level);
        DUE.put(dim(level), level.getGameTime() + REDRAW);
    }

    /**
     * How far a town's word runs, in chunks of easy ground: its folk (the square root, so a town of a hundred holds
     * about twice what a town of twenty-five does), its age, its buildings, its years; never less than its own streets
     * and a margin round them.
     */
    public static double budget(UUID town, long day) {
        int folk = Math.max(Villages.headcount(town), Villages.recordedPopulation(town));
        int age = Villages.ageOf(town).ordinal();
        int built = 0;
        for (String s : Villages.builtList(town)) if (!"colony".equals(s)) built++;
        long founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(town);
        long days = founded < 0 ? 0 : Math.max(0, day - founded);
        double b = LEAST + Math.sqrt(folk) * 1.1 + age + Math.min(4.0, built / 6.0) + Math.min(3.0, Math.log1p(days) / Math.log(2) * 0.5);
        b *= com.jrpetty.mcassistant.AssistantConfig.townBorderReach() / 100.0;
        double streets = Villages.townReach(town) / 16.0 + 2.0;
        return Math.max(streets, Math.min(MOST, b));
    }

    /** What pulls a town's lands out besides its heart: its buildings, its mine, its folk's fields, woods and pits. */
    static List<BlockPos> sources(UUID town) {
        List<BlockPos> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(town)) out.add(b.anchor());
        BlockPos mine = TownMine.siteOf(town);
        if (mine != null) out.add(mine);
        for (AssistantEntity a : Villages.folkOf(town)) {
            WorkZone z = a.workZone();
            if (z == null) continue;
            AssistantEntity.StationTask t = a.stationTask();
            if (t == AssistantEntity.StationTask.FARM || t == AssistantEntity.StationTask.WOOD || t == AssistantEntity.StationTask.MINE
                || t == AssistantEntity.StationTask.RANCH) out.add(z.center());
        }
        return out;
    }

    /** The cost of stepping into a chunk of this ground. */
    static float stepCost(MapTerrain.Ground g) {
        return switch (g) {
            case WATER -> 2.5F;
            case ROCK -> 1.6F;
            case FOREST -> 1.15F;
            default -> 1.0F;
        };
    }

    /**
     * One town's hold on the ground round it: a walk out from its heart (and its fields, mine, buildings) over the
     * chunks, each step costing what its ground costs, as far as its word runs. Each chunk's hold is its cost against
     * the budget: 0 at the heart, 1 at the very edge.
     */
    static Long2FloatOpenHashMap reach(MapTerrain terrain, Villages.Village v, double budget) {
        int hx = v.centre().getX() >> 4, hz = v.centre().getZ() >> 4;
        int r = (int) Math.ceil(budget + SOURCE) + 2, side = 2 * r + 1;
        float[] cost = new float[side * side];
        java.util.Arrays.fill(cost, Float.MAX_VALUE);
        PriorityQueue<long[]> q = new PriorityQueue<>(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[0])));
        int home = r * side + r;
        cost[home] = 0;
        q.add(new long[]{ Double.doubleToLongBits(0), home });
        for (BlockPos s : sources(v.id())) {
            int sx = (s.getX() >> 4) - hx + r, sz = (s.getZ() >> 4) - hz + r;
            if (sx < 0 || sz < 0 || sx >= side || sz >= side) continue;
            double straight = Math.hypot(sx - r, sz - r);
            if (straight > budget + SOURCE) continue;                 // a plot out beyond its word: not its land for that
            float seed = (float) Math.max(0, budget - SOURCE);
            int i = sz * side + sx;
            if (seed < cost[i]) {
                cost[i] = seed;
                q.add(new long[]{ Double.doubleToLongBits(seed), i });
            }
        }
        int[][] steps = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };
        while (!q.isEmpty()) {
            long[] top = q.poll();
            double c = Double.longBitsToDouble(top[0]);
            int i = (int) top[1];
            if (c > cost[i]) continue;
            int x = i % side, z = i / side;
            for (int[] st : steps) {
                int nx = x + st[0], nz = z + st[1];
                if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                float step = stepCost(terrain.ground(hx + nx - r, hz + nz - r)) * (st[0] != 0 && st[1] != 0 ? 1.4142F : 1F);
                float nc = (float) c + step;
                if (nc > budget) continue;
                int j = nz * side + nx;
                if (nc < cost[j]) {
                    cost[j] = nc;
                    q.add(new long[]{ Double.doubleToLongBits(nc), j });
                }
            }
        }
        Long2FloatOpenHashMap out = new Long2FloatOpenHashMap();
        for (int i = 0; i < cost.length; i++) {
            if (cost[i] == Float.MAX_VALUE) continue;
            out.put(ChunkPos.asLong(hx + i % side - r, hz + i / side - r), (float) (cost[i] / budget));
        }
        return out;
    }

    /** Each town's last reach, and what it was worked out from: drawn again only when its budget, sources or ground change. */
    private static final Map<UUID, Object[]> REACH = new ConcurrentHashMap<>();

    static Long2FloatOpenHashMap cachedReach(MapTerrain terrain, Villages.Village v, double budget) {
        int hx = v.centre().getX() >> 4, hz = v.centre().getZ() >> 4;
        int r = (int) Math.ceil(budget + SOURCE) + 2;
        long key = Double.doubleToLongBits(Math.round(budget * 100) / 100.0) * 31 + v.centre().asLong();
        for (BlockPos s : sources(v.id())) key = key * 31 + (s.getX() >> 4) * 7919L + (s.getZ() >> 4);
        for (int tx = Math.floorDiv(hx - r, MapTerrain.TILE); tx <= Math.floorDiv(hx + r, MapTerrain.TILE); tx++) {
            for (int tz = Math.floorDiv(hz - r, MapTerrain.TILE); tz <= Math.floorDiv(hz + r, MapTerrain.TILE); tz++) key = key * 31 + terrain.tileStamp(tx, tz);
        }
        Object[] was = REACH.get(v.id());
        if (was != null && (Long) was[0] == key) return (Long2FloatOpenHashMap) was[1];
        Long2FloatOpenHashMap out = reach(terrain, v, budget);
        REACH.put(v.id(), new Object[]{ key, out });
        return out;
    }

    /**
     * Every town's lands in this dimension drawn again: each town's hold on the ground round it, every chunk to the
     * town that holds it most easily, the contested ground marked, the treaties' chunks kept to whoever they were given,
     * a town not back on the register yet keeping its lands asleep. Then what changed is told (changes).
     */
    static void draw(ServerLevel level) {
        Borders b = data(level.getServer());
        String d = dim(level);
        long day = level.getDayTime() / 24000L;
        MapTerrain terrain = MapTerrain.of(level);
        Land was = b.lands.get(d);
        Land now = new Land();
        Map<UUID, Long2FloatOpenHashMap> holds = new HashMap<>();
        List<Villages.Village> live = new ArrayList<>();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension()) || b.fallen.containsKey(v.id())) continue;
            live.add(v);
            holds.put(v.id(), cachedReach(terrain, v, budget(v.id(), day)));
            Lands ls = b.lands(v.id());
            ls.name = Villages.name(v.id());
            ls.x = v.centre().getX();
            ls.z = v.centre().getZ();
        }
        // A town known before but not back on the register yet (its folk still asleep after a restart): its lands as
        // they were, so nobody else's grow into them in the meantime.
        Set<UUID> asleep = new HashSet<>();
        if (was != null) {
            for (Long2ObjectOpenHashMap.Entry<UUID> e : was.owner.long2ObjectEntrySet()) {
                UUID o = e.getValue();
                if (holds.containsKey(o) && !asleep.contains(o) || b.fallen.containsKey(o)) continue;
                if (Villages.get(o) == null) {
                    asleep.add(o);
                    holds.computeIfAbsent(o, k -> new Long2FloatOpenHashMap()).put(e.getLongKey(), 0F);
                }
            }
        }
        Long2ObjectOpenHashMap<UUID> fix = b.fixed.computeIfAbsent(d, k -> new Long2ObjectOpenHashMap<>());
        // Each chunk's best two claimants, gathered town by town (one pass over every hold, not every town for every chunk).
        Long2ObjectOpenHashMap<Object[]> top = new Long2ObjectOpenHashMap<>();
        for (Map.Entry<UUID, Long2FloatOpenHashMap> e : holds.entrySet()) {
            UUID t = e.getKey();
            for (Long2FloatOpenHashMap.Entry h : e.getValue().long2FloatEntrySet()) {
                float n = h.getFloatValue();
                Object[] c = top.get(h.getLongKey());
                if (c == null) {
                    top.put(h.getLongKey(), new Object[]{ t, n, null, Float.MAX_VALUE });
                } else if (n < (Float) c[1]) {
                    c[2] = c[0];
                    c[3] = c[1];
                    c[0] = t;
                    c[1] = n;
                } else if (n < (Float) c[3]) {
                    c[2] = t;
                    c[3] = n;
                }
            }
        }
        for (Long2ObjectOpenHashMap.Entry<Object[]> e : top.long2ObjectEntrySet()) {
            long c = e.getLongKey();
            Object[] o = e.getValue();
            UUID best = (UUID) o[0], second = (UUID) o[2];
            float bn = (Float) o[1], sn = (Float) o[3];
            UUID fixedTo = fix.get(c);
            if (fixedTo != null && holds.containsKey(fixedTo) && holds.get(fixedTo).containsKey(c)) {
                // Fixed by treaty, arbiter, payment or war: whoever it was given to, for as long as its word reaches it.
                now.owner.put(c, fixedTo);
                continue;
            }
            now.owner.put(c, best);
            if (second != null && sn - bn < CONTESTED && !Bonds.border(best, second) && !asleep.contains(best) && !asleep.contains(second)) {
                now.contested.put(c, second);
            }
        }
        // The line where two towns agreed it (Bonds.agreeBorder) is fixed where it now runs: every chunk within two of it
        // stays whose it is, however either grows.
        fixLines(b, now, fix);
        now.hold.putAll(holds);
        now.drawnAt = level.getGameTime();
        b.lands.put(d, now);
        changes(level, b, was, now, live, day);
        // The clients' copies of the borders are sent again only when they have changed.
        if (was == null || !was.owner.equals(now.owner) || !was.contested.equals(now.contested)) b.version++;
        b.setDirty();
    }

    /** Pairs whose line was agreed since it was last drawn (Bonds.agreeBorder): fixed now. */
    private static final Set<String> AGREED = ConcurrentHashMap.newKeySet();

    /** [borders-map] Two towns' elders agreed their border (Bonds.agreeBorder): fixed at the next drawing, which is soon. */
    public static void lineAgreed(UUID a, UUID b, long day) {
        AGREED.add(Ledger.pair(a, b));
        BorderDisputes.settledByLine(a, b, day);
        DUE.clear();
    }

    private static void fixLines(Borders b, Land now, Long2ObjectOpenHashMap<UUID> fix) {
        if (AGREED.isEmpty()) return;
        Set<String> pairs = new HashSet<>(AGREED);
        AGREED.clear();
        for (Long2ObjectOpenHashMap.Entry<UUID> e : now.owner.long2ObjectEntrySet()) {
            long c = e.getLongKey();
            UUID o = e.getValue();
            int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
            boolean near = false;
            for (int dx = -2; dx <= 2 && !near; dx++) {
                for (int dz = -2; dz <= 2 && !near; dz++) {
                    UUID n = now.owner.get(ChunkPos.asLong(cx + dx, cz + dz));
                    if (n != null && !n.equals(o) && pairs.contains(Ledger.pair(o, n))) near = true;
                }
            }
            if (near) fix.put(c, o);
        }
        b.setDirty();
    }

    /**
     * What changed in each town's lands, told: grown (by a sixth or more since last told), its lands first meeting a
     * neighbour's, chunks lost to or won from a neighbour. Into its chronicle and its Lands page.
     */
    private static void changes(ServerLevel level, Borders b, @Nullable Land was, Land now, List<Villages.Village> live, long day) {
        Map<UUID, Integer> area = new HashMap<>();
        for (UUID o : now.owner.values()) area.merge(o, 1, Integer::sum);
        Map<String, Integer> moved = new HashMap<>();
        if (was != null) {
            for (Long2ObjectOpenHashMap.Entry<UUID> e : now.owner.long2ObjectEntrySet()) {
                UUID before = was.owner.get(e.getLongKey());
                if (before != null && !before.equals(e.getValue())) moved.merge(before + ">" + e.getValue(), 1, Integer::sum);
            }
        }
        for (Villages.Village v : live) {
            UUID id = v.id();
            Lands ls = b.lands(id);
            int a = area.getOrDefault(id, 0);
            ls.area = a;
            if (ls.told == 0) ls.told = a;
            else if (a >= ls.told + Math.max(6, ls.told / 6)) {
                note(b, id, day, "our lands grew to " + a + " chunks", true);
                ls.told = a;
            } else if (a < ls.told - Math.max(6, ls.told / 6)) {
                note(b, id, day, "our lands shrank to " + a + " chunks", false);
                ls.told = a;
            }
            // Meeting a neighbour's lands for the first time.
            for (Villages.Village o : live) {
                if (o.id().equals(id) || ls.metLines.contains(o.id())) continue;
                if (Diplomacy.apart(v, o) > (MOST * 2 + 4) * 16) continue;
                if (touches(now, id, o.id())) {
                    ls.metLines.add(o.id());
                    b.lands(o.id()).metLines.add(id);
                    String line = "the lands of " + Villages.name(id) + " and " + Villages.name(o.id()) + " now meet, "
                        + Diplomacy.way(v, o) + " of " + Villages.name(id);
                    Villages.tell(id, day, line);
                    Villages.tell(o.id(), day, "the lands of " + Villages.name(o.id()) + " and " + Villages.name(id) + " now meet, "
                        + Diplomacy.way(o, v) + " of " + Villages.name(o.id()));
                    note(b, id, day, "our lands met " + Villages.name(o.id()) + "'s", false);
                    note(b, o.id(), day, "our lands met " + Villages.name(id) + "'s", false);
                }
            }
        }
        for (Map.Entry<String, Integer> e : moved.entrySet()) {
            if (e.getValue() < 2) continue;
            String[] p = e.getKey().split(">");
            UUID from = UUID.fromString(p[0]), to = UUID.fromString(p[1]);
            if (Villages.get(from) == null || Villages.get(to) == null) continue;
            note(b, from, day, Villages.name(to) + " took in " + e.getValue() + " chunks that were ours", false);
            note(b, to, day, "we took in " + e.getValue() + " chunks that were " + Villages.name(from) + "'s", false);
        }
    }

    /** Do two towns' lands touch (a chunk of the one beside a chunk of the other)? */
    static boolean touches(Land l, UUID a, UUID b) {
        for (Long2ObjectOpenHashMap.Entry<UUID> e : l.owner.long2ObjectEntrySet()) {
            if (!e.getValue().equals(a)) continue;
            long c = e.getLongKey();
            int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
            for (long n : new long[]{ ChunkPos.asLong(x + 1, z), ChunkPos.asLong(x - 1, z), ChunkPos.asLong(x, z + 1), ChunkPos.asLong(x, z - 1) }) {
                if (b.equals(l.owner.get(n))) return true;
            }
        }
        return false;
    }

    /** A change to a town's lands, for its Lands page (and its chronicle, if it is news). */
    static void note(Borders b, UUID town, long day, String what, boolean chronicle) {
        Lands ls = b.lands(town);
        ls.changes.add(0, "day " + (day + 1) + ": " + what);
        while (ls.changes.size() > CHANGES) ls.changes.remove(ls.changes.size() - 1);
        if (chronicle) Villages.tell(town, day, what);
        b.setDirty();
    }

    /** [borders-map] A change to the town's lands written on its Lands page from elsewhere (a dispute, a war). */
    static void note(UUID town, long day, String what) {
        Borders b = data();
        if (b != null) note(b, town, day, what, false);
    }

    // ------------------------------------------------------------------ the war and the treaty

    /**
     * [borders-map] A war's end (WarAndPeace.makePeace): the side that won takes the land in dispute between them (all of
     * it for the whole of a border goal, half of it for a part, every dispute's chunks for a war for anything else that
     * was won outright); the other side's banners there are taken down and the winner's planted (BorderMarkers). The
     * words for the treaty, or "".
     */
    public static String afterWar(ServerLevel level, UUID aggressor, UUID defender, @Nullable WarAndPeace.Goal goal, int share, long day) {
        Borders b = data(level.getServer());
        if (!on()) return "";
        UUID winner = share >= 1 ? aggressor : defender, loser = winner.equals(aggressor) ? defender : aggressor;
        boolean border = goal == WarAndPeace.Goal.BORDER;
        if (!border && share < 2 && BorderDisputes.open(aggressor, defender) == null) return "";
        LongOpenHashSet chunks = BorderDisputes.chunksBetween(b, level, aggressor, defender);
        if (chunks.isEmpty()) return "";
        List<Long> list = new ArrayList<>(chunks);
        Villages.Village w = Villages.get(winner);
        if (border && share == 1 && w != null) {
            // Half of it: the half nearer the winner's heart.
            list.sort(Comparator.comparingDouble(c -> Math.hypot(ChunkPos.getX(c) * 16 - w.centre().getX(), ChunkPos.getZ(c) * 16 - w.centre().getZ())));
            list = list.subList(0, (list.size() + 1) / 2);
        }
        Long2ObjectOpenHashMap<UUID> fix = b.fixed.computeIfAbsent(dim(level), k -> new Long2ObjectOpenHashMap<>());
        for (long c : list) fix.put(c, winner);
        BorderDisputes.closeBetween(b, aggressor, defender, day, "settled by the war: the land to " + Villages.name(winner));
        note(b, winner, day, "won " + list.size() + " chunks from " + Villages.name(loser) + " in the war", true);
        note(b, loser, day, "lost " + list.size() + " chunks to " + Villages.name(winner) + " in the war", true);
        BorderMarkers.swapSoon(winner, loser);
        b.version++;
        b.setDirty();
        DUE.clear();
        return "the disputed land (" + list.size() + (list.size() == 1 ? " chunk" : " chunks") + ") to " + Villages.name(winner);
    }

    /** These chunks are the town's now, fixed (a treaty, an arbiter, a payment). */
    static void give(Borders b, String dim, Iterable<Long> chunks, UUID to) {
        Long2ObjectOpenHashMap<UUID> fix = b.fixed.computeIfAbsent(dim, k -> new Long2ObjectOpenHashMap<>());
        for (long c : chunks) fix.put(c, to);
        b.version++;
        b.setDirty();
        DUE.clear();
    }

    // ------------------------------------------------------------------ crossing a border

    /** The notices' modes, kept on the player: the title (the default), the action bar, or none. */
    public static final String NOTICES = "mca_border_notices";

    public static String notices(ServerPlayer p) {
        String s = p.getPersistentData().getString(NOTICES);
        return s.isEmpty() ? "title" : s;
    }

    public static void notices(ServerPlayer p, String mode) {
        p.getPersistentData().putString(NOTICES, mode);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STOOD.remove(event.getEntity().getUUID());
    }

    /** Every player, once a second: across a border since last time? Its name on the screen. */
    private static void notices(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p.level() instanceof ServerLevel level)) continue;
            UUID o = owner(level, p.blockPosition());
            UUID r = o == null ? null : rival(level, p.blockPosition());
            String key = dim(level) + "|" + o + "|" + r;
            String last = STOOD.put(p.getUUID(), key);
            if (last == null || last.equals(key)) continue;
            String mode = notices(p);
            if (mode.equals("off")) continue;
            String lastOwner = last.split("\\|")[1];
            Component title, sub = null;
            if (o == null) {
                if ("null".equals(lastOwner)) continue;
                UUID left = UUID.fromString(lastOwner);
                title = Component.literal("Leaving the lands of " + Villages.name(left)).withStyle(ChatFormatting.GRAY);
                if (mode.equals("title")) {
                    p.connection.send(new ClientboundSetActionBarTextPacket(title));
                    continue;
                }
            } else if (r != null) {
                title = Component.literal("Disputed ground").withStyle(ChatFormatting.GOLD);
                sub = Component.literal("claimed by " + Villages.name(o) + " and " + Villages.name(r)).withStyle(ChatFormatting.YELLOW);
            } else {
                if (lastOwner.equals(o.toString())) continue;            // the same town's land, a dispute over or begun
                title = Component.literal("Entering the lands of " + Villages.name(o)).withStyle(ChatFormatting.WHITE);
                String motto = Heraldry.motto(o);
                sub = motto == null ? null : Component.literal("“" + motto + "”").withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY);
            }
            if (mode.equals("bar")) {
                p.connection.send(new ClientboundSetActionBarTextPacket(sub == null ? title : title.copy().append(Component.literal(" — ")).append(sub)));
            } else {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
                p.connection.send(new ClientboundSetSubtitleTextPacket(sub == null ? Component.empty() : sub));
                p.connection.send(new ClientboundSetTitleTextPacket(title));
            }
        }
    }

    // ------------------------------------------------------------------ the books

    /**
     * The Lands page (Annals: "lands"): the area and why it is as big as it is, the neighbours along its border, the
     * disputes, the markers, the recent changes, and a small map of the ground round the town, whose each chunk is.
     */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag out = new CompoundTag();
        Borders b = data(level.getServer());
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        out.putBoolean("on", on());
        Land l = b.lands.get(dim(v));
        int area = 0, contested = 0;
        Map<UUID, Integer> edge = new LinkedHashMap<>();
        if (l != null) {
            for (Long2ObjectOpenHashMap.Entry<UUID> e : l.owner.long2ObjectEntrySet()) {
                if (!e.getValue().equals(id)) continue;
                area++;
                long c = e.getLongKey();
                if (l.contested.containsKey(c)) contested++;
                int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
                for (long n : new long[]{ ChunkPos.asLong(x + 1, z), ChunkPos.asLong(x - 1, z), ChunkPos.asLong(x, z + 1), ChunkPos.asLong(x, z - 1) }) {
                    UUID o = l.owner.get(n);
                    if (o != null && !o.equals(id)) edge.merge(o, 1, Integer::sum);
                }
            }
        }
        out.putInt("area", area);
        out.putInt("contested", contested);
        double budget = budget(id, day);
        out.putInt("reach", (int) Math.round(budget * 16));
        int folk = Math.max(Villages.headcount(id), Villages.recordedPopulation(id));
        out.putString("why", "its word runs about " + (int) Math.round(budget * 16) + " blocks over open ground: " + folk + " folk, "
            + Villages.ageOf(id).label + ", " + Villages.builtList(id).size() + " buildings"
            + (com.jrpetty.mcassistant.village.Chronicle.foundedOn(id) >= 0 ? ", " + Math.max(0, day - com.jrpetty.mcassistant.village.Chronicle.foundedOn(id)) + " days old" : "")
            + "; water, rock and snow are harder to hold");
        ListTag ns = new ListTag();
        for (Map.Entry<UUID, Integer> e : edge.entrySet()) {
            CompoundTag n = new CompoundTag();
            UUID o = e.getKey();
            n.putString("name", Villages.name(o));
            n.putInt("edge", e.getValue());
            n.putInt("contested", contestedCount(id, o));
            n.putString("terms", Ledger.knowEachOther(id, o) ? Diplomacy.terms(id, o).words : "not met");
            n.putBoolean("agreed", Bonds.border(id, o));
            n.putBoolean("war", Wars.atWar(id, o));
            n.putInt("colour", Villages.colour(o));
            ns.add(n);
        }
        out.put("neighbours", ns);
        out.put("disputes", BorderDisputes.report(b, id));
        out.put("markers", BorderMarkers.report(level, b, v));
        Lands ls = b.towns.get(id);
        ListTag ch = new ListTag();
        if (ls != null) for (String s : ls.changes) ch.add(StringTag.valueOf(s));
        out.put("changes", ch);
        out.putInt("colour", Villages.colour(id));
        out.putString("table", WarRoom.tableLine(level, id));
        // The little map: 41 by 41 chunks round the heart, each its owner's index in "who" (0 nobody), contested +64.
        int r = 20, side = 2 * r + 1;
        byte[] grid = new byte[side * side];
        List<UUID> who = new ArrayList<>();
        int hx = v.centre().getX() >> 4, hz = v.centre().getZ() >> 4;
        if (l != null) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    long c = ChunkPos.asLong(hx + dx, hz + dz);
                    UUID o = l.owner.get(c);
                    if (o == null) continue;
                    int i = who.indexOf(o);
                    if (i < 0) {
                        if (who.size() >= 60) continue;
                        who.add(o);
                        i = who.size() - 1;
                    }
                    grid[(dz + r) * side + dx + r] = (byte) (i + 1 + (l.contested.containsKey(c) ? 64 : 0));
                }
            }
        }
        out.putByteArray("grid", grid);
        out.putInt("side", side);
        ListTag wl = new ListTag();
        for (UUID o : who) {
            CompoundTag t = new CompoundTag();
            t.putString("name", Villages.name(o));
            t.putInt("colour", Villages.colour(o));
            t.putBoolean("us", o.equals(id));
            wl.add(t);
        }
        out.put("who", wl);
        return out;
    }

    /** A line for the board and /village status: the lands in a few words. */
    public static String boardLine(UUID town) {
        if (!on()) return "";
        int a = area(town);
        if (a == 0) return "";
        Borders b = data();
        List<String> parts = new ArrayList<>();
        parts.add("Our lands: " + a + " chunks");
        if (b != null) {
            BorderDisputes.Dispute d = BorderDisputes.firstOpen(b, town);
            if (d != null) parts.add("in dispute with " + Villages.name(d.other(town)) + " over " + d.subject);
        }
        return String.join("; ", parts);
    }

    /** What a folk says of its town's border with a neighbour (Diplomacy.rivals), or "". */
    static String about(UUID town, UUID other) {
        BorderDisputes.Dispute d = BorderDisputes.open(town, other);
        if (d != null) return "We're at odds with them over " + d.subject + ". ";
        if (contested(town, other)) return "Nobody's agreed where our land ends and theirs begins. ";
        return "";
    }

    /** /village borders: every town's lands in a line. */
    public static List<String> lines(ServerLevel level) {
        List<String> out = new ArrayList<>();
        Borders b = data(level.getServer());
        Land l = b.lands.get(dim(level));
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int a = 0, c = 0;
            if (l != null) for (Long2ObjectOpenHashMap.Entry<UUID> e : l.owner.long2ObjectEntrySet()) {
                if (!e.getValue().equals(v.id())) continue;
                a++;
                if (l.contested.containsKey(e.getLongKey())) c++;
            }
            out.add(String.format(Locale.ROOT, "%s: %d chunks (%d contested), word runs %d blocks", Villages.name(v.id()), a, c,
                (int) Math.round(budget(v.id(), level.getDayTime() / 24000L) * 16)));
        }
        for (Map.Entry<UUID, String> e : b.fallen.entrySet()) out.add(e.getValue().split("\\|")[0] + ": fallen, its lands let go");
        return out;
    }

    // ------------------------------------------------------------------ for the map table

    /** The version the clients' borders are drawn from. */
    static long version(MinecraftServer server) {
        return data(server).version;
    }

    // ------------------------------------------------------------------ kept with the world

    public static Borders load(CompoundTag tag, HolderLookup.Provider registries) {
        Borders b = new Borders();
        b.version = tag.getLong("version") + 1;
        b.nextDispute = Math.max(1, tag.getInt("next"));
        CompoundTag ls = tag.getCompound("lands");
        for (String d : ls.getAllKeys()) {
            CompoundTag t = ls.getCompound(d);
            Land l = new Land();
            ListTag ids = t.getList("ids", Tag.TAG_STRING);
            List<UUID> idx = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) idx.add(uuid(ids.getString(i)));
            long[] keys = t.getLongArray("keys");
            int[] who = t.getIntArray("who"), riv = t.getIntArray("rival");
            for (int i = 0; i < keys.length && i < who.length; i++) {
                UUID o = who[i] >= 0 && who[i] < idx.size() ? idx.get(who[i]) : null;
                if (o == null) continue;
                l.owner.put(keys[i], o);
                if (i < riv.length && riv[i] >= 0 && riv[i] < idx.size() && idx.get(riv[i]) != null) l.contested.put(keys[i], idx.get(riv[i]));
            }
            b.lands.put(d, l);
        }
        CompoundTag fx = tag.getCompound("fixed");
        for (String d : fx.getAllKeys()) {
            CompoundTag t = fx.getCompound(d);
            Long2ObjectOpenHashMap<UUID> m = new Long2ObjectOpenHashMap<>();
            ListTag ids = t.getList("ids", Tag.TAG_STRING);
            long[] keys = t.getLongArray("keys");
            int[] who = t.getIntArray("who");
            for (int i = 0; i < keys.length && i < who.length; i++) {
                if (who[i] < 0 || who[i] >= ids.size()) continue;
                UUID o = uuid(ids.getString(who[i]));
                if (o != null) m.put(keys[i], o);
            }
            b.fixed.put(d, m);
        }
        CompoundTag fl = tag.getCompound("fallen");
        for (String k : fl.getAllKeys()) {
            UUID u = uuid(k);
            if (u != null) b.fallen.put(u, fl.getString(k));
        }
        CompoundTag tw = tag.getCompound("towns");
        for (String k : tw.getAllKeys()) {
            UUID u = uuid(k);
            if (u == null) continue;
            CompoundTag t = tw.getCompound(k);
            Lands x = new Lands();
            x.area = t.getInt("area");
            x.told = t.getInt("told");
            x.name = t.getString("name");
            x.x = t.getInt("x");
            x.z = t.getInt("z");
            ListTag ch = t.getList("changes", Tag.TAG_STRING);
            for (int i = 0; i < ch.size(); i++) x.changes.add(ch.getString(i));
            ListTag met = t.getList("met", Tag.TAG_STRING);
            for (int i = 0; i < met.size(); i++) {
                UUID m = uuid(met.getString(i));
                if (m != null) x.metLines.add(m);
            }
            b.towns.put(u, x);
        }
        ListTag ds = tag.getList("disputes", Tag.TAG_COMPOUND);
        for (int i = 0; i < ds.size(); i++) {
            BorderDisputes.Dispute d = BorderDisputes.Dispute.load(ds.getCompound(i));
            if (d != null) b.disputes.add(d);
        }
        CompoundTag mk = tag.getCompound("markers");
        for (String d : mk.getAllKeys()) {
            ListTag l = mk.getList(d, Tag.TAG_COMPOUND);
            Long2ObjectOpenHashMap<BorderMarkers.Marker> m = new Long2ObjectOpenHashMap<>();
            for (int i = 0; i < l.size(); i++) {
                BorderMarkers.Marker x = BorderMarkers.Marker.load(l.getCompound(i));
                if (x != null) m.put(x.pos, x);
            }
            b.markers.put(d, m);
        }
        return b;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("version", version);
        tag.putInt("next", nextDispute);
        CompoundTag ls = new CompoundTag();
        for (Map.Entry<String, Land> e : lands.entrySet()) {
            Land l = e.getValue();
            List<UUID> idx = new ArrayList<>();
            Map<UUID, Integer> at = new HashMap<>();
            long[] keys = new long[l.owner.size()];
            int[] who = new int[keys.length], riv = new int[keys.length];
            int i = 0;
            for (Long2ObjectOpenHashMap.Entry<UUID> o : l.owner.long2ObjectEntrySet()) {
                keys[i] = o.getLongKey();
                who[i] = at.computeIfAbsent(o.getValue(), k -> { idx.add(k); return idx.size() - 1; });
                UUID r = l.contested.get(o.getLongKey());
                riv[i] = r == null ? -1 : at.computeIfAbsent(r, k -> { idx.add(k); return idx.size() - 1; });
                i++;
            }
            CompoundTag t = new CompoundTag();
            ListTag ids = new ListTag();
            for (UUID u : idx) ids.add(StringTag.valueOf(u.toString()));
            t.put("ids", ids);
            t.putLongArray("keys", keys);
            t.putIntArray("who", who);
            t.putIntArray("rival", riv);
            ls.put(e.getKey(), t);
        }
        tag.put("lands", ls);
        CompoundTag fx = new CompoundTag();
        for (Map.Entry<String, Long2ObjectOpenHashMap<UUID>> e : fixed.entrySet()) {
            List<UUID> idx = new ArrayList<>();
            Map<UUID, Integer> at = new HashMap<>();
            long[] keys = new long[e.getValue().size()];
            int[] who = new int[keys.length];
            int i = 0;
            for (Long2ObjectOpenHashMap.Entry<UUID> o : e.getValue().long2ObjectEntrySet()) {
                keys[i] = o.getLongKey();
                who[i] = at.computeIfAbsent(o.getValue(), k -> { idx.add(k); return idx.size() - 1; });
                i++;
            }
            CompoundTag t = new CompoundTag();
            ListTag ids = new ListTag();
            for (UUID u : idx) ids.add(StringTag.valueOf(u.toString()));
            t.put("ids", ids);
            t.putLongArray("keys", keys);
            t.putIntArray("who", who);
            fx.put(e.getKey(), t);
        }
        tag.put("fixed", fx);
        CompoundTag fl = new CompoundTag();
        for (Map.Entry<UUID, String> e : fallen.entrySet()) fl.putString(e.getKey().toString(), e.getValue());
        tag.put("fallen", fl);
        CompoundTag tw = new CompoundTag();
        for (Map.Entry<UUID, Lands> e : towns.entrySet()) {
            Lands x = e.getValue();
            CompoundTag t = new CompoundTag();
            t.putInt("area", x.area);
            t.putInt("told", x.told);
            t.putString("name", x.name);
            t.putInt("x", x.x);
            t.putInt("z", x.z);
            ListTag ch = new ListTag();
            for (String s : x.changes) ch.add(StringTag.valueOf(s));
            t.put("changes", ch);
            ListTag met = new ListTag();
            for (UUID m : x.metLines) met.add(StringTag.valueOf(m.toString()));
            t.put("met", met);
            tw.put(e.getKey().toString(), t);
        }
        tag.put("towns", tw);
        ListTag ds = new ListTag();
        for (BorderDisputes.Dispute d : disputes) ds.add(d.save());
        tag.put("disputes", ds);
        CompoundTag mk = new CompoundTag();
        for (Map.Entry<String, Long2ObjectOpenHashMap<BorderMarkers.Marker>> e : markers.entrySet()) {
            ListTag l = new ListTag();
            for (BorderMarkers.Marker m : e.getValue().values()) l.add(m.save());
            mk.put(e.getKey(), l);
        }
        tag.put("markers", mk);
        return tag;
    }

    /**
     * Tests: everything kept written out and read back as a restart would, compared: the lands as drawn, the chunks
     * fixed by treaty, the fallen, the disputes and the markers. "" if all came back as they were, else what did not.
     */
    public static String roundTripForTests(ServerLevel level) {
        Borders b = data(level.getServer());
        Borders back = load(b.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Land> e : b.lands.entrySet()) {
            Land l = back.lands.get(e.getKey());
            if (l == null || !l.owner.equals(e.getValue().owner)) wrong.add("lands " + e.getKey());
            else if (!l.contested.equals(e.getValue().contested)) wrong.add("contested " + e.getKey());
        }
        for (Map.Entry<String, Long2ObjectOpenHashMap<UUID>> e : b.fixed.entrySet()) {
            if (!e.getValue().equals(back.fixed.get(e.getKey()))) wrong.add("fixed " + e.getKey());
        }
        if (!b.fallen.equals(back.fallen)) wrong.add("fallen");
        if (b.disputes.size() != back.disputes.size()) wrong.add("disputes " + b.disputes.size() + " v " + back.disputes.size());
        for (int i = 0; i < Math.min(b.disputes.size(), back.disputes.size()); i++) {
            BorderDisputes.Dispute x = b.disputes.get(i), y = back.disputes.get(i);
            if (x.state != y.state || !x.subject.equals(y.subject) || x.chunks.length != y.chunks.length) wrong.add("dispute #" + x.id);
        }
        for (Map.Entry<String, Long2ObjectOpenHashMap<BorderMarkers.Marker>> e : b.markers.entrySet()) {
            Long2ObjectOpenHashMap<BorderMarkers.Marker> m = back.markers.get(e.getKey());
            if (m == null || !m.keySet().equals(e.getValue().keySet())) wrong.add("markers " + e.getKey());
        }
        for (Map.Entry<UUID, Lands> e : b.towns.entrySet()) {
            Lands l = back.towns.get(e.getKey());
            if (l == null || l.area != e.getValue().area || !l.changes.equals(e.getValue().changes)) wrong.add("town " + e.getValue().name);
        }
        return String.join(", ", wrong);
    }

    @Nullable
    static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
