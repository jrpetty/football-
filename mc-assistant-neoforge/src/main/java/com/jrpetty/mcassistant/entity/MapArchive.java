package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [cartographer] The old maps of the town, kept. When the hall's wall is drawn afresh the old one is not thrown
 * away: it goes to the museum's archive, named for the town as it was ("Thornhurst in its Stone Age"), each sheet
 * still under its glass so it never changes again.
 * <ul>
 * <li>The first wall of each age the town comes through is hung in the museum, a group of frames on a free stretch of
 *     its wall (clear of the museum's own places and their labels): the town in its Stone Age beside the town in its
 *     Iron Age, for anybody to walk along and see it grow.</li>
 * <li>The rest of an age's walls (and every wall, in a town with no museum yet) go into the map room's chest, the
 *     cartographer's archive, from where a player may take one to look at.</li>
 * </ul>
 * The town's books keep the list (the Maps page): when each was drawn and taken down, of what age, and where it is.
 */
public final class MapArchive {

    private MapArchive() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The archive's frames in the museum. */
    static final String TAG = "mca_map_archive";

    /** One wall kept: when it was drawn and taken down, the age it showed, how many sheets, and where it is. */
    public record Kept(long drawn, long down, String age, int sheets, String where) {
        String encode() {
            return drawn + "|" + down + "|" + age.replace('|', ' ') + "|" + sheets + "|" + where.replace('|', ' ');
        }

        @Nullable
        static Kept decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 5) return null;
            try {
                return new Kept(Long.parseLong(p[0]), Long.parseLong(p[1]), p[2], Integer.parseInt(p[3]), p[4]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** The town's kept walls, oldest first. */
    public static List<Kept> kept(UUID village) {
        List<Kept> out = new ArrayList<>();
        String s = Ledger.note(village, "carto.archive");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            Kept k = Kept.decode(line);
            if (k != null) out.add(k);
        }
        return out;
    }

    private static void note(UUID village, Kept k) {
        List<Kept> all = kept(village);
        all.add(k);
        while (all.size() > 40) all.remove(0);
        StringBuilder sb = new StringBuilder();
        for (Kept x : all) sb.append(sb.length() == 0 ? "" : "\n").append(x.encode());
        Ledger.note(village, "carto.archive", sb.toString());
    }

    /** "Stone Age", out of "the Stone Age". */
    static String ageWords(String label) {
        return label.replaceFirst("^the ", "");
    }

    /**
     * The old wall kept: each sheet named for the town in its age and locked (a pane from the stores, for a sheet
     * the clerk drew open), then hung in the museum if it is the first of its age there and there is room, else into
     * the map room's chest, else the stores. Where it went, in words ("went to the museum's archive").
     */
    static String keep(ServerLevel level, Villages.Village v, List<ItemStack> sheets, @Nullable MapSurveys.Wall was, long day) {
        UUID id = v.id();
        String age = was != null ? was.age() : Villages.ageOf(id).label;
        long drawn = was != null ? was.day() : day;
        String town = Villages.name(id);
        String title = town + " in its " + ageWords(age);
        int across = sheets.size() >= 9 ? 3 : sheets.size() >= 4 ? 2 : 1;
        List<ItemStack> named = new ArrayList<>();
        for (int i = 0; i < sheets.size(); i++) {
            ItemStack s = sheets.get(i).copy();
            MapItemSavedData d = MapItem.getSavedData(s, level);
            if (d != null && !d.locked && Crafts.take(level, v, x -> x.is(Items.GLASS_PANE), 1)) MapItem.lockMap(level, s);
            s.set(DataComponents.ITEM_NAME, Component.literal(title + (across > 1 ? " (" + MapSurveys.part(across, i) + ")" : "")));
            s.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Drawn on day " + (drawn + 1) + (was != null && !was.by().isEmpty() ? " by " + was.by() : "")).withStyle(ChatFormatting.GRAY),
                Component.literal("Hung in the hall till day " + (day + 1) + "; kept in the town's archive").withStyle(ChatFormatting.GRAY))));
            named.add(s);
        }
        String where;
        Ledger.Building museum = Museum.building(id);
        boolean first = museum != null && !hungAge(id, age);
        if (first && hangInMuseum(level, v, museum, named)) {
            Ledger.note(id, "carto.archive.hung/" + age, Long.toString(day));
            where = "went to the museum's archive";
        } else if (intoChest(level, id, named)) {
            where = "went into the map room's archive chest";
        } else {
            for (ItemStack s : named) Crafts.store(level, v, s);
            where = "went to the stores";
        }
        note(id, new Kept(drawn, day, age, named.size(), where.replaceFirst("^went (to|into) ", "")));
        LOG.info("[MCA-CARTO] {}: the old wall ({} sheets, {}) {}", town, named.size(), title, where);
        return "(\"" + title + "\") " + where;
    }

    static boolean hungAge(UUID village, String age) {
        String s = Ledger.note(village, "carto.archive.hung/" + age);
        return s != null && !s.isEmpty();
    }

    /**
     * The sheets hung together on a free stretch of the museum's wall, clear of its own places and their labels and of
     * the archive's other groups: frames out of the stores. False (nothing hung) if there is no room or no frames.
     */
    static boolean hangInMuseum(ServerLevel level, Villages.Village v, Ledger.Building b, List<ItemStack> sheets) {
        if (!level.isLoaded(b.anchor())) return false;
        Set<BlockPos> taken = new HashSet<>();
        for (Museum.Place p : Museum.PLACES) {
            BlockPos at = Museum.at(b, p.dx(), p.h(), p.dz());
            BlockPos label = Museum.at(b, p.lx(), p.lh(), p.lz());
            for (BlockPos c : new BlockPos[]{ at, label }) {
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) taken.add(c.offset(dx, dy, dz));
            }
        }
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(b.anchor()).inflate(12), x -> x.getTags().contains(TAG))) {
            BlockPos c = f.getPos();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) taken.add(c.offset(dx, dy, dz));
        }
        List<Decor.Spot> spots = MapRoom.wall(level, v.id(), b, sheets.size(), taken);
        if (spots == null || spots.size() != sheets.size()) return false;
        if (!MapRoom.framesToHand(level, v, spots.size()) || !MapRoom.payForFrames(level, v, spots.size())) return false;
        for (int i = 0; i < spots.size(); i++) {
            Decor.Spot sp = spots.get(i);
            ItemFrame f = new ItemFrame(level, sp.at(), sp.facing());
            f.addTag(TAG);
            level.addFreshEntity(f);
            f.setItem(sheets.get(i), false);
        }
        return true;
    }

    /** Into the map room's chest: the cartographer's archive. False if there is none, or no room. */
    static boolean intoChest(ServerLevel level, UUID village, List<ItemStack> sheets) {
        BlockPos at = Cartographers.archiveChest(level, village);
        if (at == null || !(level.getBlockEntity(at) instanceof Container c)) return false;
        int free = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) free++;
        if (free < sheets.size()) return false;
        for (ItemStack s : sheets) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).isEmpty()) { c.setItem(i, s); break; }
            }
        }
        c.setChanged();
        return true;
    }

    /** Tests: the archive's frames hanging in the museum, and their maps. */
    public static List<ItemStack> inMuseumForTests(ServerLevel level, UUID village) {
        List<ItemStack> out = new ArrayList<>();
        Ledger.Building b = Museum.building(village);
        if (b == null) return out;
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(b.anchor()).inflate(14), x -> x.getTags().contains(TAG))) {
            out.add(f.getItem().copy());
        }
        return out;
    }

    /** Tests: the maps in the map room's archive chest. */
    public static List<ItemStack> inChestForTests(ServerLevel level, UUID village) {
        List<ItemStack> out = new ArrayList<>();
        BlockPos at = Cartographers.archiveChest(level, village);
        if (at == null || !(level.getBlockEntity(at) instanceof Container c)) return out;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.FILLED_MAP)) out.add(c.getItem(i).copy());
        return out;
    }

    /** A map's id, or -1. */
    static int idOf(ItemStack s) {
        MapId m = s.get(DataComponents.MAP_ID);
        return m == null ? -1 : m.id();
    }
}
