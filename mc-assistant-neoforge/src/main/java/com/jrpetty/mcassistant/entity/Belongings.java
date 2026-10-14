package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [shops-homes] A folk's possessions, as they really are: what its household's home holds in its chest, larder,
 * wardrobe and toy box (HomeStores), and what it carries of its own; each thing with its kind (Goods), where it is and
 * whose. Read from the boxes themselves (looked at again every few seconds at most): the one truth, never a second
 * count. For the folk's profile (its Belongings tab), its talk and the town's pages.
 *
 * <p>API: {@link #of(ServerLevel, VillageFolkEntity)} for a folk, {@link #ofHome(ServerLevel, UUID, BlockPos)} for a
 * house (also Homes.possessions), and {@link #toTag} to send either to a screen.
 */
public final class Belongings {

    private Belongings() {}

    /** One thing: the stack, its kind, where it is ("Larder", "Wardrobe", "carried") and whose ("the household", a name). */
    public record Thing(ItemStack stack, Goods.Category category, String where, String whose) {}

    /** A home's (or a folk's) possessions: where, the things, how many of each kind, what they are worth, in a line. */
    public record View(@Nullable BlockPos home, List<Thing> things, Map<Goods.Category, Integer> counts, double worth, String summary) {

        /** How many things in all. */
        public int total() {
            int n = 0;
            for (int c : counts.values()) n += c;
            return n;
        }

        /** How many different kinds of thing (by item). */
        public int kinds() {
            java.util.Set<net.minecraft.world.item.Item> seen = new java.util.HashSet<>();
            for (Thing t : things) seen.add(t.stack().getItem());
            return seen.size();
        }
    }

    public static final View NONE = new View(null, List.of(), Map.of(), 0, "No home of its own yet.");

    private record Seen(long at, View view) {}

    private static final Map<Long, Seen> SEEN = new ConcurrentHashMap<>();
    /** How long a home's possessions are kept before they are read from the boxes again (ticks). */
    static final long KEEP_FOR = 100L;

    public static void resetForTests() {
        SEEN.clear();
    }

    /** This folk's possessions: its household's things at home, and what it carries of its own. */
    public static View of(ServerLevel level, VillageFolkEntity f) {
        UUID village = f.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, f.getUUID());
        View home = h == null ? null : ofHome(level, village, h);
        List<Thing> things = new ArrayList<>(home == null ? List.of() : home.things());
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || !Homes.isKeepsake(s) || !Homes.ownedBy(s, f)) continue;
            things.add(new Thing(s.copy(), Goods.of(s), "carried", f.displayNameCap()));
        }
        if (home == null && things.isEmpty()) return NONE;
        return view(home == null ? null : home.home(), things, f);
    }

    /** The possessions of the house standing at this anchor (its household's), or NONE. */
    public static View ofHome(ServerLevel level, UUID village, BlockPos anchor) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        return h == null ? NONE : ofHome(level, village, h);
    }

    static View ofHome(ServerLevel level, UUID village, Homes.Home h) {
        long now = level.getGameTime();
        Seen s = SEEN.get(h.anchor.asLong());
        if (s != null && now - s.at() < KEEP_FOR && now >= s.at()) return s.view();
        Map<UUID, String> names = new java.util.HashMap<>();
        for (UUID m : h.members) if (level.getEntity(m) instanceof VillageFolkEntity f) names.put(m, f.displayNameCap());
        List<Thing> things = new ArrayList<>();
        for (HomeStores.Box b : HomeStores.of(level, village, h)) {
            if (!(level.getBlockEntity(b.pos()) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.isEmpty()) continue;
                things.add(new Thing(st.copy(), Goods.of(st), b.kind().label, whose(st, names)));
            }
        }
        View v = view(h.anchor, things, null);
        SEEN.put(h.anchor.asLong(), new Seen(now, v));
        return v;
    }

    private static String whose(ItemStack s, Map<UUID, String> names) {
        var d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null) return "the household";
        String owner = d.copyTag().getString("mca_keepsake");
        if (owner.isEmpty()) return "the household";
        try {
            String n = names.get(UUID.fromString(owner));
            return n == null ? "the household" : n;
        } catch (IllegalArgumentException e) {
            return "the household";
        }
    }

    private static View view(@Nullable BlockPos home, List<Thing> things, @Nullable VillageFolkEntity f) {
        Map<Goods.Category, Integer> counts = new EnumMap<>(Goods.Category.class);
        double worth = 0;
        for (Thing t : things) {
            counts.merge(t.category(), t.stack().getCount(), Integer::sum);
            worth += Prices.of(t.stack().copyWithCount(1)) * t.stack().getCount();
        }
        return new View(home, List.copyOf(things), Map.copyOf(counts), worth, summary(counts, f));
    }

    /** "12 food, 3 clothes, 2 tools, 5 household things and 4 comforts". */
    private static String summary(Map<Goods.Category, Integer> counts, @Nullable VillageFolkEntity f) {
        if (counts.isEmpty()) return "Nothing put by at home yet.";
        List<String> parts = new ArrayList<>();
        for (Goods.Category c : Goods.Category.values()) {
            int n = counts.getOrDefault(c, 0);
            if (n > 0) parts.add(n + " " + c.label.toLowerCase(java.util.Locale.ROOT));
        }
        String s = parts.size() == 1 ? parts.get(0) : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        String setOut = f == null ? null : Decor.cardLine(f);
        return "At home: " + s + "." + (setOut == null || setOut.isEmpty() ? "" : " Set out: " + setOut);
    }

    /** A view for a screen: {home:{x,y,z}, summary, worth, counts:{FOOD:n...}, things:[{item, cat, where, whose}]}. */
    public static CompoundTag toTag(View v, HolderLookup.Provider reg) {
        CompoundTag t = new CompoundTag();
        if (v.home() != null) {
            CompoundTag h = new CompoundTag();
            h.putInt("x", v.home().getX());
            h.putInt("y", v.home().getY());
            h.putInt("z", v.home().getZ());
            t.put("home", h);
        }
        t.putString("summary", v.summary());
        t.putDouble("worth", v.worth());
        CompoundTag counts = new CompoundTag();
        for (Map.Entry<Goods.Category, Integer> e : v.counts().entrySet()) counts.putInt(e.getKey().name(), e.getValue());
        t.put("counts", counts);
        ListTag things = new ListTag();
        for (Thing th : v.things()) {
            CompoundTag e = new CompoundTag();
            e.put("item", th.stack().save(reg));
            e.putString("cat", th.category().name());
            e.putString("where", th.where());
            e.putString("whose", th.whose());
            things.add(e);
        }
        t.put("things", things);
        return t;
    }
}
