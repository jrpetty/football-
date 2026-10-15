package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.MapTableBlock;
import com.jrpetty.mcassistant.block.MapTableBlockEntity;
import com.jrpetty.mcassistant.item.BorderItems;
import com.jrpetty.mcassistant.net.WarRoomDataPayload;
import com.jrpetty.mcassistant.net.WarRoomTilesPayload;
import com.jrpetty.mcassistant.village.Ledger;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [borders-map] The war room: the map table's live map, as the server draws it for one viewer, and the town's own table.
 *
 * <p><b>What a table knows.</b> A town's table is drawn from what its town knows, no more (the fog of war): the ground
 * round its heart and its own lands; the bearings and rings its scouts have walked (Scouts' atlas, its explored sectors);
 * every place in its atlas; the towns it has met (their hearts and the country round them, their lands, the roads to
 * them); its own armies, caravans, envoys and scouts, and other towns' where they are on ground it knows. A traveller's
 * table (one set down in no town's land by a player of no town) knows the country round itself. An admin, or a player in
 * creative with the right, sees the whole world (and /village maptable reveal shows it to anybody for a while).
 *
 * <p><b>What it costs.</b> Nothing until somebody opens a table. Open, its viewer's client asks every second and a half
 * for the overlay (a few kilobytes: the towns, the roads and routes, what is moving, the disputes, the news) and, now and
 * then, for the terrain tiles in its view that it has not got (a tile is 64 by 64 pixels of the map, four blocks a pixel,
 * out of MapTerrain, the unknown blanked: four kilobytes, a dozen at most a request). The borders go only when they have
 * changed since the client last had them, as runs of chunks. Each town's reckoning of another's strength is kept a few
 * seconds, not worked out every time.
 *
 * <p><b>The town's own table.</b> A town of the Stone Age with a dozen folk (or any town with scouts out, a dispute or
 * a war) makes one (Bench, out of its stores: the recipe a player uses) and a hand sets it in its hall's council room,
 * under the roof, by the middle of the floor; with no hall, beside the board. If it is broken or carried off, the town
 * makes another in time.
 */
public final class WarRoom {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private WarRoom() {}

    /** The works' name for setting the table (TownJobs). */
    static final String WORKS = "warroom";
    /** Tiles sent in one answer, at most. */
    public static final int TILES_MOST = 12;
    /** How near the table a viewer must stand. */
    static final double REACH = 8.0;

    /** Players shown everything for a while (/village maptable reveal), till when (game time). */
    private static final Map<UUID, Long> REVEALED = new ConcurrentHashMap<>();
    /** When each player was last answered (overlay), for the rate limit. */
    private static final Map<UUID, Long> ANSWERED = new ConcurrentHashMap<>();
    /** Each town's reckoning of another's strength, kept a little while. */
    private static final Map<String, Object[]> RECKONED = new ConcurrentHashMap<>();
    /** When each town's table is next looked to. */
    private static final Map<UUID, Long> DUE = new ConcurrentHashMap<>();

    static void resetForTests() {
        REMOTE.clear();
        REVEALED.clear();
        ANSWERED.clear();
        RECKONED.clear();
        DUE.clear();
    }

    // ------------------------------------------------------------------ whose table

    /** The town a table set down here by this player is drawn for: its citizen's town, else the land's, else the nearest. */
    @Nullable
    public static UUID townFor(ServerLevel level, BlockPos pos, @Nullable Player p) {
        if (p != null) {
            for (Villages.Village v : Villages.every()) if (Ledger.citizen(v.id(), p.getUUID()) && v.dim().equals(level.dimension())) return v.id();
        }
        UUID land = Borders.owner(level, pos);
        if (land != null) return land;
        Villages.Village near = Villages.nearest(level, pos);
        return near == null ? null : near.id();
    }

    public static boolean revealed(ServerPlayer p) {
        Long until = REVEALED.get(p.getUUID());
        return until != null && until > p.serverLevel().getGameTime();
    }

    /** Show this player everything on any table for an hour of game time (or stop). */
    public static void reveal(ServerPlayer p, boolean on) {
        if (on) REVEALED.put(p.getUUID(), p.serverLevel().getGameTime() + 72000L);
        else REVEALED.remove(p.getUUID());
    }

    /** Does this viewer see the whole world (an admin in creative, or revealed)? */
    static boolean all(ServerPlayer p) {
        return revealed(p) || p.isCreative() && p.hasPermissions(2);
    }

    // ------------------------------------------------------------------ opening and asking

    /** Players let look at a table from afar (an op's /village maptable view, for the pictures). */
    private static final Map<UUID, BlockPos> REMOTE = new ConcurrentHashMap<>();

    /** An op opens a town's table from wherever it stands. */
    public static void viewFrom(ServerPlayer p, BlockPos table) {
        REMOTE.put(p.getUUID(), table.immutable());
        open(p, table);
    }

    /** The table right-clicked: the screen opened with the map as its town knows it. */
    public static void open(ServerPlayer p, BlockPos table) {
        CompoundTag data = overlay(p.serverLevel(), p, table, -1);
        data.putBoolean("open", true);
        PacketDistributor.sendToPlayer(p, new WarRoomDataPayload(data));
    }

    /**
     * The client's ask (WarRoomAskPayload): the overlay (if it is due: no oftener than twice a second a player), and any
     * tiles it wants that it has not got as they are now. Ignored unless the player stands at a map table.
     */
    public static void ask(ServerPlayer p, BlockPos table, long claimsHave, long[] want, boolean overlay) {
        ServerLevel level = p.serverLevel();
        if (!(level.getBlockState(table).getBlock() instanceof MapTableBlock)) return;
        boolean remote = table.equals(REMOTE.get(p.getUUID()));
        if (!remote && p.distanceToSqr(table.getX() + 0.5, table.getY() + 0.5, table.getZ() + 0.5) > REACH * REACH * 4) return;
        long now = level.getGameTime();
        if (overlay && now - ANSWERED.getOrDefault(p.getUUID(), -100L) >= 10) {
            ANSWERED.put(p.getUUID(), now);
            PacketDistributor.sendToPlayer(p, new WarRoomDataPayload(overlay(level, p, table, claimsHave)));
        }
        if (want.length >= 2) {
            Known k = known(level, p, table);
            List<long[]> heads = new ArrayList<>();
            List<byte[]> bodies = new ArrayList<>();
            for (int i = 0; i + 1 < want.length && heads.size() < TILES_MOST; i += 2) {
                long key = want[i], have = want[i + 1];
                long stamp = stamp(level, k, key);
                if (stamp == have) continue;
                heads.add(new long[]{ key, stamp });
                byte[] px = tile(level, k, ChunkPos.getX(key), ChunkPos.getZ(key));
                boolean blank = true;
                for (byte b : px) if (b != 0) { blank = false; break; }
                bodies.add(blank ? new byte[0] : px);                    // nothing known there: four kilobytes saved
            }
            if (!heads.isEmpty()) PacketDistributor.sendToPlayer(p, new WarRoomTilesPayload(heads, bodies));
        }
    }

    /** The table's town (null: a traveller's). */
    @Nullable
    static UUID tableTown(ServerLevel level, BlockPos table) {
        if (level.getBlockEntity(table) instanceof MapTableBlockEntity be) {
            UUID t = be.town();
            if (t != null && Villages.get(t) != null) return t;
        }
        return null;
    }

    // ------------------------------------------------------------------ what a town knows

    /** What a viewer may see: the whole world, or a town's knowledge, or a traveller's look round its table. */
    public static final class Known {
        final boolean all;
        @Nullable final UUID town;
        final Set<UUID> towns = new HashSet<>();
        /** Discs of ground known: x, z, radius. */
        final List<int[]> discs = new ArrayList<>();
        final LongOpenHashSet chunks = new LongOpenHashSet();
        @Nullable BlockPos home;
        long[] explored = new long[2];
        long hash;

        Known(boolean all, @Nullable UUID town) {
            this.all = all;
            this.town = town;
        }

        public boolean chunk(int cx, int cz) {
            if (all) return true;
            if (chunks.contains(ChunkPos.asLong(cx, cz))) return true;
            int x = cx * 16 + 8, z = cz * 16 + 8;
            for (int[] d : discs) {
                long dx = x - d[0], dz = z - d[1];
                if (dx * dx + dz * dz <= (long) (d[2] + 11) * (d[2] + 11)) return true;
            }
            if (home != null) {
                int dx = x - home.getX(), dz = z - home.getZ();
                int ring = (int) (Math.sqrt((double) dx * dx + (double) dz * dz) / Scouts.RING);
                if (ring < Scouts.RINGS && Scouts.been(explored, Scouts.bearingOf(dx, dz), ring)) return true;
            }
            return false;
        }

        public boolean at(int x, int z) {
            return chunk(x >> 4, z >> 4);
        }

        public boolean town(UUID t) {
            return all || towns.contains(t);
        }
    }

    static Known known(ServerLevel level, ServerPlayer p, BlockPos table) {
        if (all(p)) {
            Known k = new Known(true, tableTown(level, table));
            for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension())) k.towns.add(v.id());
            return k;
        }
        return knownBy(level, tableTown(level, table), table);
    }

    /** What this town knows of the world (or, with no town, what can be seen from the table). */
    public static Known knownBy(ServerLevel level, @Nullable UUID town, BlockPos table) {
        Villages.Village v = town == null ? null : Villages.get(town);
        if (v == null) {
            Known k = new Known(false, null);
            k.discs.add(new int[]{ table.getX(), table.getZ(), 256 });
            for (Villages.Village o : Villages.every()) {
                if (o.dim().equals(level.dimension()) && o.centre().distSqr(table) <= 300 * 300) k.towns.add(o.id());
            }
            k.hash = k.towns.size();
            return k;
        }
        Known k = new Known(false, town);
        long day = level.getDayTime() / 24000L;
        k.home = v.centre();
        k.towns.add(town);
        k.discs.add(new int[]{ v.centre().getX(), v.centre().getZ(), (int) Math.max(160, Borders.budget(town, day) * 16 + 16) });
        k.chunks.addAll(Borders.territory(town));
        k.explored = Scouts.explored(town);
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(town) || !o.dim().equals(v.dim())) continue;
            boolean met = Ledger.knowEachOther(town, o.id()) || Scouts.met(town, o.id()) || Diplomacy.kin(town, o.id());
            if (!met) continue;
            k.towns.add(o.id());
            k.discs.add(new int[]{ o.centre().getX(), o.centre().getZ(), 112 });
            k.chunks.addAll(Borders.territory(o.id()));
            // The way there, walked by its envoys and traders.
            corridor(k, v.centre(), o.centre());
        }
        for (Scouts.Find f : Scouts.atlas(town)) {
            int r = switch (f.kind()) {
                case TOWN, SETTLEMENT, RUIN -> 64;
                case PEAK, WATER, LAND -> 56;
                case PLAYER, BLOCKED -> 24;
                default -> 40;
            };
            k.discs.add(new int[]{ f.at().getX(), f.at().getZ(), r });
        }
        k.hash = k.towns.size() * 7919L + Scouts.atlas(town).size() * 104729L + Long.bitCount(k.explored[0]) + Long.bitCount(k.explored[1]) * 31L
            + k.chunks.size() * 13L;
        return k;
    }

    static void corridor(Known k, BlockPos a, BlockPos b) {
        double d = Math.sqrt(a.distSqr(b));
        int steps = (int) Math.min(40, d / 40);
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            k.discs.add(new int[]{ (int) (a.getX() + (b.getX() - a.getX()) * t), (int) (a.getZ() + (b.getZ() - a.getZ()) * t), 28 });
        }
    }

    // ------------------------------------------------------------------ the tiles

    /** A tile's stamp as this viewer would have it: the terrain's and the knowledge's together. */
    static long stamp(ServerLevel level, Known k, long tileKey) {
        long t = MapTerrain.of(level).tileStamp(ChunkPos.getX(tileKey), ChunkPos.getZ(tileKey));
        return t * 1_000_003L + (k.all ? 1 : k.hash) + 1;
    }

    /** One tile's pixels: the terrain's colour where known, nothing (0) where not. */
    public static byte[] tile(ServerLevel level, Known k, int tx, int tz) {
        MapTerrain terrain = MapTerrain.of(level);
        int side = MapTerrain.TILE * MapTerrain.SIDE;
        byte[] out = new byte[side * side];
        for (int lz = 0; lz < MapTerrain.TILE; lz++) {
            for (int lx = 0; lx < MapTerrain.TILE; lx++) {
                int cx = tx * MapTerrain.TILE + lx, cz = tz * MapTerrain.TILE + lz;
                byte[] s = terrain.chunk(cx, cz);
                if (s == null || !k.chunk(cx, cz)) continue;
                for (int sz = 0; sz < MapTerrain.SIDE; sz++) {
                    System.arraycopy(s, sz * MapTerrain.SIDE, out, (lz * MapTerrain.SIDE + sz) * side + lx * MapTerrain.SIDE, MapTerrain.SIDE);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the overlay

    /**
     * Everything drawn over the terrain for this viewer: the towns known, their lands (if they changed since
     * {@code claimsHave}), the roads and trade routes, the armies, camps, battles and sieges, the caravans, envoys,
     * wagons and scouts, the disputes, the ruins, the border markers, the viewer, and the news.
     */
    public static CompoundTag overlay(ServerLevel level, @Nullable ServerPlayer p, BlockPos table, long claimsHave) {
        Known k = p == null ? knownBy(level, tableTown(level, table), table) : known(level, p, table);
        return build(level, k, table, p == null ? null : p.blockPosition(), claimsHave);
    }

    /** Tests: the overlay as a town's table would have it, with its fog. */
    public static CompoundTag buildForTests(ServerLevel level, @Nullable UUID town, BlockPos table, boolean all) {
        Known k;
        if (all) {
            k = new Known(true, town);
            for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension())) k.towns.add(v.id());
        } else k = knownBy(level, town, table);
        return build(level, k, table, null, -1);
    }

    static CompoundTag build(ServerLevel level, Known k, BlockPos table, @Nullable BlockPos you, long claimsHave) {
        CompoundTag out = new CompoundTag();
        long day = level.getDayTime() / 24000L;
        UUID us = k.town;
        out.putBoolean("all", k.all);
        out.putString("town", us == null ? "" : us.toString());
        out.putString("title", us == null ? "A traveller's map" : "The war room of " + Villages.name(us));
        out.putIntArray("table", new int[]{ table.getX(), table.getZ() });
        out.putLong("tablePos", table.asLong());
        if (you != null) out.putIntArray("you", new int[]{ you.getX(), you.getZ() });
        Villages.Village home = us == null ? null : Villages.get(us);
        out.putIntArray("home", home != null ? new int[]{ home.centre().getX(), home.centre().getZ() } : new int[]{ table.getX(), table.getZ() });
        out.putLong("day", day);
        // The towns.
        ListTag towns = new ListTag();
        List<UUID> order = new ArrayList<>();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension()) || !k.town(v.id())) continue;
            order.add(v.id());
            towns.add(townTag(level, v, us, day));
        }
        out.put("towns", towns);
        // Their lands, when they have changed since the client last had them.
        long version = Borders.version(level.getServer());
        out.putLong("claimsVersion", version);
        if (claimsHave != version) out.put("claims", claims(level, k, order));
        out.put("roads", roads(level, k));
        out.put("routes", routes(level, k));
        movers(level, k, out);
        war(level, k, out);
        out.put("disputes", disputes(level, k));
        out.put("ruins", ruins(level, k));
        out.put("markers", markers(level, k, table));
        out.put("news", news(us, k));
        WarRoomRealm.add(level, k, out);                                   // [realm-polish] harbours, roads by grade, inns, knights, wonders
        return out;
    }

    static CompoundTag townTag(ServerLevel level, Villages.Village v, @Nullable UUID us, long day) {
        UUID id = v.id();
        CompoundTag t = new CompoundTag();
        t.putString("id", id.toString());
        t.putString("name", Villages.name(id));
        t.putInt("x", v.centre().getX());
        t.putInt("z", v.centre().getZ());
        t.putInt("colour", Villages.colour(id));
        t.putInt("folk", Villages.headcount(id));
        t.putString("age", Villages.ageOf(id).label);
        long founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(id);
        t.putLong("days", founded < 0 ? 0 : Math.max(0, day - founded));
        t.putInt("area", Borders.area(id));
        String motto = Heraldry.motto(id);
        if (motto != null) t.putString("motto", motto);
        Heraldry.Design d = Heraldry.design(id);
        if (d != null) t.putInt("field", d.field().getTextureDiffuseColor());
        boolean self = id.equals(us);
        t.putBoolean("us", self);
        if (us != null && !self) {
            t.putString("terms", Ledger.knowEachOther(us, id) ? Diplomacy.terms(us, id).words : "not met");
            t.putInt("rel", Ledger.relation(us, id));
            t.putBoolean("war", Wars.atWar(us, id));
            t.putBoolean("allied", Envoys.allied(us, id));
            t.putBoolean("pact", Envoys.pact(us, id));
            t.putBoolean("agreed", Bonds.border(us, id));
            BorderDisputes.Dispute dsp = BorderDisputes.open(us, id);
            if (dsp != null) t.putString("dispute", dsp.subject + " (" + dsp.state.words + ")");
        }
        // Who it is at war with, trades with: what the viewer's town would know of it.
        ListTag wars = new ListTag(), trade = new ListTag();
        for (UUID e : Wars.enemies(id)) wars.add(StringTag.valueOf(Villages.name(e)));
        for (Villages.Village o : Villages.every()) {
            if (!o.id().equals(id) && (Envoys.pact(id, o.id()) || TradeDeals.live(id, o.id()))) trade.add(StringTag.valueOf(Villages.name(o.id())));
            if (trade.size() >= 6) break;
        }
        t.put("wars", wars);
        t.put("trade", trade);
        if (Vassals.isVassal(id)) t.putString("vassal", Villages.name(Vassals.overlord(id)));
        WarRoomRealm.card(v, us, t);                                       // [realm-polish] prisoners held, wonders, knights
        // Its strength as the viewer's town reckons it (WarAndPeace): its own counted, another's from report or rumour.
        if (us != null) {
            Object[] r = reckoned(level, us, id, day);
            t.putInt("strength", (Integer) r[0]);
            t.putString("basis", (String) r[1]);
            t.putBoolean("guessed", (Boolean) r[2]);
        }
        return t;
    }

    static Object[] reckoned(ServerLevel level, UUID us, UUID them, long day) {
        String key = us + "/" + them;
        Object[] was = RECKONED.get(key);
        long now = level.getGameTime();
        if (was != null && now - (Long) was[3] < 200) return was;
        Object[] out;
        try {
            WarAndPeace.Reckoning r = us.equals(them) ? WarAndPeace.own(level, us) : WarAndPeace.of(level, us, them, day);
            out = new Object[]{ r.strength(), us.equals(them) ? "counted" : r.source(), r.guessed(), now };
        } catch (RuntimeException e) {
            out = new Object[]{ 0, "unknown", true, now };
        }
        RECKONED.put(key, out);
        return out;
    }

    /** Each town's lands as runs of chunks, row by row: [cz, x0, length, ...]; and the contested chunks. */
    static CompoundTag claims(ServerLevel level, Known k, List<UUID> order) {
        CompoundTag out = new CompoundTag();
        Borders b = Borders.data(level.getServer());
        Borders.Land l = b.lands.get(Borders.dim(level));
        ListTag ts = new ListTag();
        LongArrayTag contested = new LongArrayTag(new long[0]);
        if (l != null) {
            Map<UUID, Map<Integer, List<Integer>>> rows = new HashMap<>();
            for (Long2ObjectOpenHashMap.Entry<UUID> e : l.owner.long2ObjectEntrySet()) {
                if (!order.contains(e.getValue())) continue;
                long c = e.getLongKey();
                rows.computeIfAbsent(e.getValue(), x -> new HashMap<>()).computeIfAbsent(ChunkPos.getZ(c), x -> new ArrayList<>()).add(ChunkPos.getX(c));
            }
            for (UUID t : order) {
                Map<Integer, List<Integer>> r = rows.get(t);
                if (r == null) continue;
                List<Integer> runs = new ArrayList<>();
                for (Map.Entry<Integer, List<Integer>> e : r.entrySet()) {
                    List<Integer> xs = e.getValue();
                    xs.sort(null);
                    int start = xs.get(0), prev = start;
                    for (int i = 1; i <= xs.size(); i++) {
                        if (i < xs.size() && xs.get(i) == prev + 1) { prev = xs.get(i); continue; }
                        runs.add(e.getKey());
                        runs.add(start);
                        runs.add(prev - start + 1);
                        if (i < xs.size()) start = prev = xs.get(i);
                    }
                }
                CompoundTag c = new CompoundTag();
                c.putString("id", t.toString());
                c.put("runs", new IntArrayTag(runs.stream().mapToInt(Integer::intValue).toArray()));
                ts.add(c);
            }
            List<Long> cs = new ArrayList<>();
            for (Long2ObjectOpenHashMap.Entry<UUID> e : l.contested.long2ObjectEntrySet()) {
                UUID o = l.owner.get(e.getLongKey());
                if (o != null && order.contains(o) && order.contains(e.getValue())) cs.add(e.getLongKey());
            }
            contested = new LongArrayTag(cs.stream().mapToLong(Long::longValue).toArray());
        }
        out.put("towns", ts);
        out.put("contested", contested);
        return out;
    }

    /** The roads between mother towns and their colonies, as far as they are laid: both ends known. */
    static ListTag roads(ServerLevel level, Known k) {
        ListTag out = new ListTag();
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            Villages.Village c = Villages.get(link.getKey()), m = Villages.get(link.getValue());
            if (c == null || m == null || !c.dim().equals(level.dimension()) || !k.town(c.id()) || !k.town(m.id())) continue;
            List<int[]> line = Roads.line(m, c);
            if (line.isEmpty()) continue;
            double done = Math.max(0, Roads.progress(c.id()));
            CompoundTag t = new CompoundTag();
            int[] a = line.get(0), z = line.get(line.size() - 1);
            t.putIntArray("line", new int[]{ a[0], a[1], z[0], z[1] });
            t.putFloat("done", (float) done);
            out.add(t);
        }
        return out;
    }

    /** Trade: every pact or standing deal between two known towns. */
    static ListTag routes(ServerLevel level, Known k) {
        ListTag out = new ListTag();
        List<Villages.Village> vs = new ArrayList<>();
        for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension()) && k.town(v.id())) vs.add(v);
        for (int i = 0; i < vs.size(); i++) {
            for (int j = i + 1; j < vs.size(); j++) {
                Villages.Village a = vs.get(i), b = vs.get(j);
                boolean pact = Envoys.pact(a.id(), b.id()), deal = TradeDeals.live(a.id(), b.id());
                if (!pact && !deal) continue;
                CompoundTag t = new CompoundTag();
                t.putIntArray("line", new int[]{ a.centre().getX(), a.centre().getZ(), b.centre().getX(), b.centre().getZ() });
                t.putString("kind", deal ? "deal" : "pact");
                t.putString("label", Villages.name(a.id()) + " – " + Villages.name(b.id()));
                out.add(t);
            }
        }
        return out;
    }

    /** What is on the move: caravans, envoys and heralds, trade wagons, scouts. Ours always; others' on ground we know. */
    static void movers(ServerLevel level, Known k, CompoundTag out) {
        ListTag ms = new ListTag();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            boolean ours = v.id().equals(k.town);
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity f) || !f.isAlive()) continue;
                BlockPos at = f.blockPosition();
                Caravans.Trip t = f.trip();
                Scouts.Expedition e = f.expedition();
                if (t == null && e == null) continue;
                if (!k.all && !ours && !k.at(at.getX(), at.getZ())) continue;
                CompoundTag m = new CompoundTag();
                m.putInt("x", at.getX());
                m.putInt("z", at.getZ());
                m.putInt("colour", Villages.colour(v.id()));
                if (t != null) {
                    Villages.Village to = Villages.get(t.back ? t.from : t.to);
                    String kind = t.errand == Envoys.Errand.WAR ? "herald" : t.errand != null ? "envoy" : t.trade ? "trade" : "caravan";
                    m.putString("kind", kind);
                    m.putString("label", f.displayNameCap() + ", " + (kind.equals("caravan") || kind.equals("trade") ? "caravan" : kind) + " of "
                        + Villages.name(t.from) + (t.back ? " homeward from " + Villages.name(t.to) : " to " + Villages.name(t.to))
                        + (t.errand != null ? " (" + t.errand.purpose + ")" : ""));
                    if (to != null) m.putIntArray("to", new int[]{ to.centre().getX(), to.centre().getZ() });
                } else {
                    if (!ours && !k.all) continue;                         // another town's scouts are not seen
                    String kind = e.mission() != null ? "spy" : e.delve() != null ? "delver" : e.venture() != null ? "trader" : "scout";
                    m.putString("kind", kind.equals("delver") || kind.equals("trader") ? "scout" : kind);
                    m.putString("label", f.displayNameCap() + ", " + kind + ", " + (e.returning() ? "coming home" : "heading " + e.heading()));
                    m.putIntArray("to", new int[]{ e.target.getX(), e.target.getZ() });
                }
                ms.add(m);
                if (ms.size() >= 120) break;
            }
        }
        WarRoomRealm.ships(level, k, ms);                                  // [realm-polish] the merchant ships from over the sea
        // The trade wagons on the road.
        for (TradeWagon w : level.getEntities(WagonRegistry.TRADE_WAGON.get(), x -> x.isAlive())) {
            if (ms.size() >= 160) break;
            BlockPos at = w.blockPosition();
            if (!k.all && (w.town == null || !w.town.equals(k.town)) && !k.at(at.getX(), at.getZ())) continue;
            CompoundTag m = new CompoundTag();
            m.putInt("x", at.getX());
            m.putInt("z", at.getZ());
            m.putString("kind", "wagon");
            m.putInt("colour", w.town == null ? 0x8A6A3A : Villages.colour(w.town));
            m.putString("label", "a trade wagon" + (w.town == null ? "" : " of " + Villages.name(w.town)));
            ms.add(m);
        }
        out.put("movers", ms);
    }

    /** The armies: each column's head and its road, its camp; battles being fought; sieges. Ours, our enemies', or on known ground. */
    static void war(ServerLevel level, Known k, CompoundTag out) {
        ListTag armies = new ListTag(), camps = new ListTag(), battles = new ListTag(), sieges = new ListTag();
        for (Campaigns.Campaign c : Campaigns.active()) {
            Villages.Village va = Villages.get(c.a), vb = Villages.get(c.b);
            if (va == null || vb == null || !va.dim().equals(level.dimension())) continue;
            boolean ours = k.all || c.a.equals(k.town) || c.b.equals(k.town);
            for (int i = 0; i < 2; i++) {
                Armies.Army army = c.armies[i];
                if (army == null) continue;
                List<BlockPos> way = c.ways.get(i);
                BlockPos head = way.isEmpty() ? (i == 0 ? va : vb).centre() : c.head(i);
                if (!ours && !k.at(head.getX(), head.getZ())) continue;
                UUID side = i == 0 ? c.a : c.b;
                CompoundTag t = new CompoundTag();
                t.putInt("x", head.getX());
                t.putInt("z", head.getZ());
                t.putInt("colour", Villages.colour(side));
                t.putString("town", Villages.name(side));
                String inn = ArmyInns.atInn(c, i);                             // [realm-polish] resting at a roadside inn
                t.putString("stage", inn.isEmpty() ? c.stage.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') : inn.substring(2));
                t.putInt("size", army.fighters.size() + army.behind);
                if (!way.isEmpty()) {
                    int step = Math.max(1, way.size() / 16);
                    List<Integer> pts = new ArrayList<>();
                    for (int j = 0; j < way.size(); j += step) { pts.add(way.get(j).getX()); pts.add(way.get(j).getZ()); }
                    BlockPos end = way.get(way.size() - 1);
                    pts.add(end.getX());
                    pts.add(end.getZ());
                    t.putIntArray("path", pts.stream().mapToInt(Integer::intValue).toArray());
                }
                armies.add(t);
                Battlefields.Camp camp = c.camps[i];
                if (camp != null) {
                    CompoundTag ct = new CompoundTag();
                    ct.putInt("x", camp.centre().getX());
                    ct.putInt("z", camp.centre().getZ());
                    ct.putInt("colour", Villages.colour(side));
                    ct.putString("label", "the camp of " + Villages.name(side) + "'s army" + (camp.tents() > 0 ? ", " + camp.tents() + " tents" : ""));
                    camps.add(ct);
                }
            }
            if (c.ground != null && (ours || k.at(c.ground.centre().getX(), c.ground.centre().getZ()))
                    && (c.stage == Campaigns.Stage.BATTLE || c.stage == Campaigns.Stage.ASSAULT)) {
                CompoundTag bt = new CompoundTag();
                BlockPos at = c.stage == Campaigns.Stage.ASSAULT && c.gate != null ? c.gate : c.ground.centre();
                bt.putInt("x", at.getX());
                bt.putInt("z", at.getZ());
                bt.putString("label", "battle: " + Villages.name(c.a) + " against " + Villages.name(c.b) + (c.name.isEmpty() ? "" : ", " + c.name));
                battles.add(bt);
            }
            if (c.stage == Campaigns.Stage.SIEGE_MARCH || c.stage == Campaigns.Stage.SIEGE_CAMP || c.stage == Campaigns.Stage.ASSAULT) {
                if (ours || k.at(vb.centre().getX(), vb.centre().getZ())) {
                    CompoundTag st = new CompoundTag();
                    st.putInt("x", vb.centre().getX());
                    st.putInt("z", vb.centre().getZ());
                    st.putString("label", Villages.name(c.b) + " under siege by " + Villages.name(c.a));
                    sieges.add(st);
                }
            }
        }
        out.put("armies", armies);
        out.put("camps", camps);
        out.put("battles", battles);
        out.put("sieges", sieges);
    }

    static ListTag disputes(ServerLevel level, Known k) {
        ListTag out = new ListTag();
        Borders b = Borders.data(level.getServer());
        for (BorderDisputes.Dispute d : b.disputes) {
            if (!d.dim.equals(Borders.dim(level))) continue;
            if (!d.live() && Bonds.today() - d.ended > 7) continue;
            if (!k.town(d.a) && !k.town(d.b)) continue;
            CompoundTag t = new CompoundTag();
            t.putInt("x", d.at.getX());
            t.putInt("z", d.at.getZ());
            t.putString("label", Villages.name(d.a) + " v " + Villages.name(d.b) + ": " + d.subject);
            t.putString("state", d.state.words);
            t.putBoolean("live", d.live());
            out.add(t);
        }
        return out;
    }

    static ListTag ruins(ServerLevel level, Known k) {
        ListTag out = new ListTag();
        Borders b = Borders.data(level.getServer());
        for (Map.Entry<UUID, String> e : b.fallen.entrySet()) {
            String[] p = e.getValue().split("\\|");
            if (p.length < 3) continue;
            int x, z;
            try { x = Integer.parseInt(p[1]); z = Integer.parseInt(p[2]); } catch (NumberFormatException ex) { continue; }
            if (!k.all && !k.at(x, z)) continue;
            CompoundTag t = new CompoundTag();
            t.putInt("x", x);
            t.putInt("z", z);
            t.putString("label", "the ruins of " + p[0]);
            out.add(t);
        }
        if (k.town != null) {
            for (Scouts.Find f : Scouts.atlas(k.town)) {
                if (f.kind() != Scouts.Kind.RUIN || out.size() >= 40) continue;
                CompoundTag t = new CompoundTag();
                t.putInt("x", f.at().getX());
                t.putInt("z", f.at().getZ());
                t.putString("label", f.label() + " (found by " + f.by() + ")");
                out.add(t);
            }
        }
        return out;
    }

    static ListTag markers(ServerLevel level, Known k, BlockPos table) {
        ListTag out = new ListTag();
        for (BorderMarkers.Marker m : BorderMarkers.all(level)) {
            if (out.size() >= 400) break;
            BlockPos p = m.pos();
            if (p.distSqr(table) > 2500L * 2500L || !k.at(p.getX(), p.getZ()) || !k.town(m.town)) continue;
            out.add(new IntArrayTag(new int[]{ p.getX(), p.getZ(), Villages.colour(m.town), m.stone ? 1 : 0 }));
        }
        return out;
    }

    /** What's new: the town's own news of the last few days (the war, the borders, envoys, trade), newest first. */
    static ListTag news(@Nullable UUID us, Known k) {
        ListTag out = new ListTag();
        if (us != null) {
            for (Villages.News n : Villages.news(us)) {
                if (out.size() >= 10) break;
                out.add(StringTag.valueOf("day " + (n.day() + 1) + ": " + n.text()));
            }
        } else if (k.all) {
            for (Villages.Village v : Villages.every()) {
                List<Villages.News> ns = Villages.news(v.id());
                if (!ns.isEmpty()) out.add(StringTag.valueOf(Villages.name(v.id()) + ", day " + (ns.get(0).day() + 1) + ": " + ns.get(0).text()));
                if (out.size() >= 10) break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the town's own table

    /** Each town, now and then by day: does it want a table, and has it one? If not, made and set in its hall. */
    static void tick(ServerLevel level) {
        long now = level.getGameTime();
        long t = level.getDayTime() % 24000L;
        if ((t >= 12500L && t < 23500L) && !TownJobs.instantNow()) return;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            Long due = DUE.get(v.id());
            if (due != null && now < due) continue;
            boolean busy = false;
            try {
                busy = keepTable(level, v);
            } catch (RuntimeException e) {
                com.jrpetty.mcassistant.Guard.struck("war room", Villages.name(v.id()), e);
            }
            DUE.put(v.id(), now + (busy ? 100L : 2400L + Math.floorMod(v.id().hashCode(), 300)));
        }
    }

    /** Does the town want a map table? A Stone Age town of a dozen, or any with scouts out, a dispute, a feud or a war. */
    public static boolean wanted(UUID town) {
        if (Villages.ageOf(town).ordinal() >= Villages.Age.STONE.ordinal() && Villages.headcount(town) >= 12) return true;
        if (!Scouts.atlas(town).isEmpty() || !Wars.enemies(town).isEmpty()) return true;
        Borders b = Borders.data();
        return b != null && BorderDisputes.firstOpen(b, town) != null;
    }

    /** Where the town's table stands, if it has one standing (or its ground is asleep). */
    @Nullable
    public static BlockPos table(ServerLevel level, UUID town) {
        String s = Ledger.note(town, "warroom.table");
        if (s == null || s.isEmpty()) return null;
        try {
            BlockPos p = BlockPos.of(Long.parseLong(s));
            if (!level.isLoaded(p)) return p;
            return level.getBlockState(p).getBlock() instanceof MapTableBlock ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean keepTable(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!wanted(id) || table(level, id) != null) return false;
        Spot s = spot(level, id);
        if (s == null) return false;
        Bench.Plan plan = Bench.plan(level, v, BorderItems.MAP_TABLE_ITEM.get(), 1, Bench.handOf(level, v, null, "workshop"));
        if (!plan.ok()) {
            Ledger.note(id, "warroom.short", plan.shortOf == null ? "" : plan.shortOf);
            return false;
        }
        Ledger.forget(id, "warroom.short");
        if (!TownJobs.atWork(level, v, WORKS, s.at, "setting the map table in " + s.where)) return true;
        if (!Bench.take(level, v, plan, null)) return false;
        place(level, v, s);
        return false;
    }

    record Spot(BlockPos at, Direction facing, String where) {}

    /** Under the hall's roof, by the middle of its floor; with no hall, beside the board. */
    @Nullable
    static Spot spot(ServerLevel level, UUID town) {
        Ledger.Building hall = Culture.hall(town);
        if (hall != null && level.isLoaded(hall.anchor())) {
            Spot best = null;
            double bestD = Double.MAX_VALUE;
            BlockPos mid = Culture.at(hall, 0, 0, 5);
            for (int dz = 1; dz <= 12; dz++) {
                for (int dx = -6; dx <= 6; dx++) {
                    for (int h = 0; h <= 1; h++) {
                        BlockPos p = Culture.at(hall, dx, h, dz);
                        if (!freeFloor(level, p) || !roofed(level, p)) continue;
                        double d = p.distSqr(mid);
                        if (d < bestD) {
                            bestD = d;
                            best = new Spot(p.immutable(), hall.facing(), "the council room of " + Villages.spoken(hall.structure()));
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        BlockPos lectern = VillageBoards.lectern(town);
        Direction f = VillageBoards.facingOf(town);
        if (lectern != null && level.isLoaded(lectern)) {
            BlockPos p = Trades.floorSpot(level, lectern, 4);
            if (p != null) return new Spot(p, f == null ? Direction.SOUTH : f, "beside the board");
        }
        // No hall and no board yet (a camp): by its heart, under the sky.
        Villages.Village v = Villages.get(town);
        if (v != null && level.isLoaded(v.centre())) {
            BlockPos p = Trades.floorSpot(level, v.centre(), 6);
            if (p != null) return new Spot(p, Direction.SOUTH, "by the heart of the town");
        }
        return null;
    }

    static boolean freeFloor(ServerLevel level, BlockPos p) {
        if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) return false;
        if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState n = level.getBlockState(p.relative(d));
            if (n.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || n.getBlock() instanceof net.minecraft.world.level.block.StairBlock) return false;
        }
        return true;
    }

    static boolean roofed(ServerLevel level, BlockPos p) {
        for (int up = 2; up <= 8; up++) if (!level.getBlockState(p.above(up)).isAir()) return true;
        return false;
    }

    /** The table set down, bound to the town, and its place written in the town's books. */
    static void place(ServerLevel level, Villages.Village v, Spot s) {
        level.setBlock(s.at, BorderItems.MAP_TABLE.get().defaultBlockState().setValue(MapTableBlock.FACING, s.facing), 3);
        if (level.getBlockEntity(s.at) instanceof MapTableBlockEntity be) be.bind(v.id());
        Ledger.note(v.id(), "warroom.table", Long.toString(s.at.asLong()));
        long day = level.getDayTime() / 24000L;
        Villages.tell(v.id(), day, "a map table was set in " + s.where + ", the country drawn on it as the town knows it");
        level.playSound(null, s.at, net.minecraft.sounds.SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
        LOG.info("[MCA-BORDERS] {} set its map table at {}", Villages.name(v.id()), s.at.toShortString());
    }

    /** Admin and tests: the town's table made and set now (out of nothing: a decree). Where, or null. */
    @Nullable
    public static BlockPos placeNow(ServerLevel level, Villages.Village v) {
        BlockPos have = table(level, v.id());
        if (have != null) return have;
        Spot s = spot(level, v.id());
        if (s == null) return null;
        place(level, v, s);
        return s.at;
    }

    /** Tests: the town's table looked to now (made out of its stores and set, the works done at once). */
    public static boolean keepTableForTests(ServerLevel level, Villages.Village v) {
        keepTable(level, v);
        return table(level, v.id()) != null;
    }

    /** For the Lands page: the town's table, or what it waits on. */
    static String tableLine(ServerLevel level, UUID town) {
        BlockPos t = table(level, town);
        if (t != null) return "The map table stands at " + t.getX() + ", " + t.getY() + ", " + t.getZ() + ".";
        if (!wanted(town)) return "No map table yet: a Stone Age town of a dozen makes one (or one with scouts out, a dispute or a war).";
        String s = Ledger.note(town, "warroom.short");
        return "A map table is wanted" + (s == null || s.isEmpty() ? "." : ", and waits on " + s + ".");
    }
}
