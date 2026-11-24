package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [pa36] The storage map: the containers a player's assistant knows are theirs (learned as they are placed, opened or
 * pinged by its player, and as it opens them itself), each with a name, what it held when last looked in, and whether
 * it is the drop-off or the storage; the standing stock jobs ("keep this chest stocked with 64 bread"); and who placed
 * each container, so it never takes from another player's chest. Its own saved data, keyed by owner, apart from the
 * assistant's record and from the village folk.
 */
public final class PaStore extends SavedData {

    public static final String ID = "mc_assistant_pa_storage";
    /** The most containers remembered per player (the oldest unused go first). */
    public static final int MOST = 160;

    /** One known container. */
    public static final class Known {
        public long pos;
        public String dim = "";
        public String name = "";
        /** The block's registry id ("minecraft:chest"), for its icon and type. */
        public String block = "";
        /** What it held when last looked in: item id -> count (the dozen biggest). */
        public final Map<String, Integer> snap = new LinkedHashMap<>();
        public int used, size;
        public long seen;
        public boolean dropoff, storage;

        public BlockPos at() { return BlockPos.of(pos); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("P", pos);
            t.putString("D", dim);
            t.putString("N", name);
            t.putString("B", block);
            CompoundTag s = new CompoundTag();
            snap.forEach(s::putInt);
            t.put("S", s);
            t.putInt("U", used);
            t.putInt("Z", size);
            t.putLong("T", seen);
            t.putBoolean("Drop", dropoff);
            t.putBoolean("Store", storage);
            return t;
        }

        static Known load(CompoundTag t) {
            Known k = new Known();
            k.pos = t.getLong("P");
            k.dim = t.getString("D");
            k.name = t.getString("N");
            k.block = t.getString("B");
            CompoundTag s = t.getCompound("S");
            for (String key : s.getAllKeys()) k.snap.put(key, s.getInt(key));
            k.used = t.getInt("U");
            k.size = t.getInt("Z");
            k.seen = t.getLong("T");
            k.dropoff = t.getBoolean("Drop");
            k.storage = t.getBoolean("Store");
            return k;
        }

        /** The name it goes by: its own, else its kind ("chest", "barrel"). */
        public String label() {
            if (!name.isEmpty()) return name;
            String b = block.contains(":") ? block.substring(block.indexOf(':') + 1) : block;
            return b.isEmpty() ? "chest" : b.replace('_', ' ');
        }
    }

    /** A standing job: keep this container holding at least n of item, checked every {@code every} ticks. */
    public static final class Stock {
        public long pos;
        public String dim = "";
        public String item = "";
        public int n;
        public int every = 20 * 60 * 3;
        public long next;
        /** "fuel" rules cover every known furnace (pos 0). */
        public boolean furnaces;

        public BlockPos at() { return BlockPos.of(pos); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("P", pos);
            t.putString("D", dim);
            t.putString("I", item);
            t.putInt("N", n);
            t.putInt("E", every);
            t.putLong("X", next);
            t.putBoolean("F", furnaces);
            return t;
        }

        static Stock load(CompoundTag t) {
            Stock s = new Stock();
            s.pos = t.getLong("P");
            s.dim = t.getString("D");
            s.item = t.getString("I");
            s.n = t.getInt("N");
            s.every = Math.max(200, t.getInt("E"));
            s.next = t.getLong("X");
            s.furnaces = t.getBoolean("F");
            return s;
        }
    }

    /** One player's storage. */
    public static final class Owner {
        public final List<Known> known = new ArrayList<>();
        public final List<Stock> stock = new ArrayList<>();
        public boolean autoDrop;

        @Nullable
        public Known at(BlockPos p, String dim) {
            long l = p.asLong();
            for (Known k : known) if (k.pos == l && k.dim.equals(dim)) return k;
            return null;
        }

        @Nullable
        public Known dropoff(String dim) {
            for (Known k : known) if (k.dropoff && k.dim.equals(dim)) return k;
            return null;
        }
    }

    private final Map<UUID, Owner> owners = new HashMap<>();
    /** Who placed each container: "dim|pos" -> player. Only containers, so it stays small. */
    private final Map<String, UUID> placed = new HashMap<>();

    @Nullable
    public static PaStore get() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PaStore::new, PaStore::load, null), ID);
    }

    public static Owner of(UUID owner) {
        PaStore s = get();
        if (s == null) return new Owner();
        return s.owners.computeIfAbsent(owner, k -> new Owner());
    }

    public static void touch() {
        PaStore s = get();
        if (s != null) s.setDirty();
    }

    static String key(String dim, BlockPos p) {
        return dim + "|" + p.asLong();
    }

    /** Who placed the container here (null when nobody we saw: a world's own chest, or one placed before). */
    @Nullable
    public static UUID placedBy(String dim, BlockPos p) {
        PaStore s = get();
        return s == null ? null : s.placed.get(key(dim, p));
    }

    public static void setPlacedBy(String dim, BlockPos p, @Nullable UUID who) {
        PaStore s = get();
        if (s == null) return;
        if (who == null) s.placed.remove(key(dim, p));
        else s.placed.put(key(dim, p), who);
        if (s.placed.size() > 20000) s.placed.keySet().iterator().remove();
        s.setDirty();
    }

    /** Learn (or refresh) a container for this player; returns its entry. */
    public static Known learn(UUID owner, String dim, BlockPos p, String block, long now) {
        Owner o = of(owner);
        Known k = o.at(p, dim);
        if (k == null) {
            k = new Known();
            k.pos = p.asLong();
            k.dim = dim;
            o.known.add(k);
            if (o.known.size() > MOST) {
                o.known.sort((x, y) -> Long.compare(y.seen, x.seen));
                while (o.known.size() > MOST) o.known.remove(o.known.size() - 1);
            }
        }
        if (block != null && !block.isEmpty()) k.block = block;
        k.seen = now;
        touch();
        return k;
    }

    public static void forget(UUID owner, String dim, BlockPos p) {
        Owner o = of(owner);
        o.known.removeIf(k -> k.pos == p.asLong() && k.dim.equals(dim));
        o.stock.removeIf(s -> !s.furnaces && s.pos == p.asLong() && s.dim.equals(dim));
        touch();
    }

    /** A container broken: no one knows it any more. */
    public static void gone(String dim, BlockPos p) {
        PaStore s = get();
        if (s == null) return;
        boolean any = s.placed.remove(key(dim, p)) != null;
        for (Owner o : s.owners.values()) {
            any |= o.known.removeIf(k -> k.pos == p.asLong() && k.dim.equals(dim));
            any |= o.stock.removeIf(st -> !st.furnaces && st.pos == p.asLong() && st.dim.equals(dim));
        }
        if (any) s.setDirty();
    }

    /** Tests: a clean slate. */
    public static void resetForTests() {
        PaStore s = get();
        if (s == null) return;
        s.owners.clear();
        s.placed.clear();
        s.setDirty();
    }

    // ------------------------------------------------------------------ saving

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag os = new ListTag();
        for (Map.Entry<UUID, Owner> e : owners.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("U", e.getKey());
            ListTag ks = new ListTag();
            for (Known k : e.getValue().known) ks.add(k.save());
            t.put("K", ks);
            ListTag ss = new ListTag();
            for (Stock s : e.getValue().stock) ss.add(s.save());
            t.put("S", ss);
            t.putBoolean("Auto", e.getValue().autoDrop);
            os.add(t);
        }
        tag.put("Owners", os);
        ListTag pl = new ListTag();
        for (Map.Entry<String, UUID> e : placed.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("K", e.getKey());
            t.putUUID("U", e.getValue());
            pl.add(t);
        }
        tag.put("Placed", pl);
        return tag;
    }

    public static PaStore load(CompoundTag tag, HolderLookup.Provider registries) {
        PaStore s = new PaStore();
        for (Tag e : tag.getList("Owners", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) e;
            Owner o = new Owner();
            for (Tag k : t.getList("K", Tag.TAG_COMPOUND)) o.known.add(Known.load((CompoundTag) k));
            for (Tag k : t.getList("S", Tag.TAG_COMPOUND)) o.stock.add(Stock.load((CompoundTag) k));
            o.autoDrop = t.getBoolean("Auto");
            s.owners.put(t.getUUID("U"), o);
        }
        for (Tag e : tag.getList("Placed", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) e;
            s.placed.put(t.getString("K"), t.getUUID("U"));
        }
        return s;
    }
}
