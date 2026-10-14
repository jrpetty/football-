package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [shops-homes] A home's own storage, and what goes where in it: the household chest (the house's own), the larder
 * (a barrel: the household's food), the wardrobe (a second chest: its clothes), the toy box (a third, where there are
 * children); and a spare chest after that. Each is named for what it keeps, which makes it the household's: the town's
 * stores, its builders and its couriers only ever reach into a chest that bears the village's mark, so a named one is
 * never stripped (and a player's own chest, which never bore the mark, is never named or touched).
 * <ul>
 * <li><b>Put away.</b> What a folk brings home of its own (what it bought, was given or made for itself: Homes.moving)
 *     goes in where it belongs: food to the larder, clothes to the wardrobe, a child's toys to the toy box, the rest
 *     to the chest.</li>
 * <li><b>Taken out to use.</b> A meal out of the larder first (Meals), the tool of its trade out of the chest in the
 *     morning when it has none on it (toolsOut), and put back what it no longer needs at night.</li>
 * </ul>
 */
public final class HomeStores {

    private HomeStores() {}

    /** What a box in a home keeps, and the name it bears. */
    public enum Kind {
        CHEST("Household Chest"), LARDER("Larder"), WARDROBE("Wardrobe"), TOY_BOX("Toy Box"), SPARE("Spare Chest");

        public final String label;

        Kind(String label) {
            this.label = label;
        }

        /** The kind a box's name says it is, or null. */
        @Nullable
        static Kind named(String name) {
            for (Kind k : values()) if (k.label.equalsIgnoreCase(name)) return k;
            return null;
        }
    }

    /** One box of a home's storage: where, and what it keeps. */
    public record Box(BlockPos pos, Kind kind) {}

    /** How long a home's storage is kept before it is looked at again (ticks). */
    static final long KEEP_FOR = 200L;

    private record Seen(long at, List<Box> boxes) {}

    private static final Map<Long, Seen> SEEN = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SEEN.clear();
    }

    /** This home's storage has changed (a box set down or taken up): look again next time. */
    static void resetFor(Homes.Home h) {
        SEEN.remove(h.anchor.asLong());
    }

    // ------------------------------------------------------------------ what a home has

    /**
     * The home's storage now: its own chest first (the house's plan), then every other chest and barrel inside it.
     * A box is what its name says it is; one not named yet is the larder if it is the first barrel, else the wardrobe,
     * the toy box (with children in the household) or a spare, in that order. Empty for a home not loaded.
     */
    static List<Box> of(ServerLevel level, UUID village, Homes.Home h) {
        long now = level.getGameTime();
        Seen s = SEEN.get(h.anchor.asLong());
        if (s != null && now - s.at() < KEEP_FOR && now >= s.at()) return s.boxes();
        List<Box> out = look(level, village, h);
        SEEN.put(h.anchor.asLong(), new Seen(now, out));
        return out;
    }

    private static List<Box> look(ServerLevel level, UUID village, Homes.Home h) {
        List<Box> out = new ArrayList<>();
        if (!level.isLoaded(h.anchor)) return out;
        BlockPos own = Homes.chestOf(level, village, h);
        List<BlockPos> chests = new ArrayList<>(), barrels = new ArrayList<>();
        if (own != null) chests.add(own);
        int r = Flats.isFlat(h) ? 8 : radius(village, h);
        boolean before = ZoneChests.askAs(false);
        try {
            for (ZoneChests.Found f : ZoneChests.around(level, h.anchor, r, 8)) {
                BlockPos p = f.pos();
                if (p.equals(own) || Homes.homeAt(village, p) != h) continue;
                BlockEntity be = f.blockEntity();
                if (!(be instanceof Container)) continue;
                if (level.getBlockState(p).getBlock() instanceof BarrelBlock) barrels.add(p.immutable());
                else if (level.getBlockState(p).getBlock() instanceof ChestBlock) chests.add(p.immutable());
            }
        } finally {
            ZoneChests.askAs(before);
        }
        boolean children = false;
        for (UUID m : h.members) if (level.getEntity(m) instanceof VillageFolkEntity f && f.isBaby()) children = true;
        // Named first, then the rest in the order a household fills them.
        java.util.EnumSet<Kind> taken = java.util.EnumSet.noneOf(Kind.class);
        List<BlockPos> unnamed = new ArrayList<>();
        for (BlockPos p : join(chests, barrels)) {
            Kind k = nameOf(level, p);
            if (k != null && (k == Kind.SPARE || !taken.contains(k))) { out.add(new Box(p, k)); taken.add(k); }
            else unnamed.add(p);
        }
        for (BlockPos p : unnamed) {
            boolean barrel = level.getBlockState(p).getBlock() instanceof BarrelBlock;
            Kind k = barrel ? (taken.contains(Kind.LARDER) ? Kind.SPARE : Kind.LARDER)
                : !taken.contains(Kind.CHEST) ? Kind.CHEST
                : !taken.contains(Kind.WARDROBE) ? Kind.WARDROBE
                : children && !taken.contains(Kind.TOY_BOX) ? Kind.TOY_BOX : Kind.SPARE;
            out.add(new Box(p, k));
            taken.add(k);
        }
        return List.copyOf(out);
    }

    private static List<BlockPos> join(List<BlockPos> a, List<BlockPos> b) {
        List<BlockPos> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static int radius(UUID village, Homes.Home h) {
        int[] half = BuildGoal.footprint(Homes.drawing(village, h));
        return Math.max(half[0], half[1]) + 1;
    }

    @Nullable
    private static Kind nameOf(ServerLevel level, BlockPos p) {
        if (!(level.getBlockEntity(p) instanceof Nameable n) || n.getCustomName() == null) return null;
        return Kind.named(n.getCustomName().getString());
    }

    /** The box of this kind in a home, or null. */
    @Nullable
    static Container box(ServerLevel level, UUID village, Homes.Home h, Kind kind) {
        for (Box b : of(level, village, h)) {
            if (b.kind() == kind && level.getBlockEntity(b.pos()) instanceof Container c) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------ the household's own

    /**
     * Name every box of every household's home for what it keeps: the household's, never the stores' again. Only a
     * box that bears the village's mark (the village put it there) or none; a home a player owns is let be. Once a day
     * (and on the first look at an old town's homes: their chests were the stores' to reach into till now).
     */
    static int claim(ServerLevel level, Villages.Village v) {
        int named = 0;
        for (Homes.Home h : Homes.homes(v.id()).values()) {
            if (h.members.isEmpty() || h.tenure == Homes.Tenure.PLAYER || Homes.seat(h) || !level.isLoaded(h.anchor)) continue;
            SEEN.remove(h.anchor.asLong());
            for (Box b : of(level, v.id(), h)) {
                BlockEntity be = level.getBlockEntity(b.pos());
                if (!(be instanceof Nameable n)) continue;
                Component name = n.getCustomName();
                if (name != null && !ZoneChests.isVillageStore(be) && Kind.named(name.getString()) == null) continue;   // somebody else's name: theirs
                if (name != null && name.getString().equals(b.kind().label)) continue;
                be.applyComponents(net.minecraft.core.component.DataComponentMap.builder()
                    .set(DataComponents.CUSTOM_NAME, Component.literal(b.kind().label)).build(),
                    net.minecraft.core.component.DataComponentPatch.EMPTY);
                be.setChanged();
                named++;
            }
        }
        return named;
    }

    /** Which box a thing of a household's goes in. */
    static Kind kindFor(ItemStack s, boolean child) {
        if (Goods.drink(s) || s.get(DataComponents.FOOD) != null) return Kind.LARDER;
        if (Goods.clothes(s)) return Kind.WARDROBE;
        if (child && Goods.comfort(s)) return Kind.TOY_BOX;
        return Kind.CHEST;
    }

    /**
     * Put a thing of the household's away in its home: in the box it belongs in, else the chest, else any box with
     * room. Returns what would not go in (for the caller to keep).
     */
    public static ItemStack putAway(ServerLevel level, UUID village, Homes.Home h, ItemStack s, boolean child) {
        if (s.isEmpty()) return ItemStack.EMPTY;
        Kind want = kindFor(s, child);
        ItemStack left = s.copy();
        Container first = box(level, village, h, want);
        if (first != null) left = Homes.insertInto(first, left);
        if (!left.isEmpty() && want != Kind.CHEST) {
            Container chest = box(level, village, h, Kind.CHEST);
            if (chest != null) left = Homes.insertInto(chest, left);
        }
        for (Box b : of(level, village, h)) {
            if (left.isEmpty()) break;
            if (level.getBlockEntity(b.pos()) instanceof Container c) left = Homes.insertInto(c, left);
        }
        return left;
    }

    /** As putAway, into the chest given (Homes.moving's), routed to where it belongs in that chest's home. */
    static ItemStack putAway(ServerLevel level, UUID village, VillageFolkEntity f, Container fallback, ItemStack s) {
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null) return Homes.insertInto(fallback, s);
        ItemStack left = putAway(level, village, h, s, f.isBaby());
        return left.isEmpty() ? left : Homes.insertInto(fallback, left);
    }

    /** A meal's worth of food out of the home: the larder first, then the household chest. True if it took one. */
    static boolean takeFood(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f, Predicate<ItemStack> food) {
        for (Kind k : new Kind[]{ Kind.LARDER, Kind.CHEST }) {
            Container c = box(level, village, h, k);
            if (c != null && takeOne(c, f, food)) return true;
        }
        return false;
    }

    private static boolean takeOne(Container c, VillageFolkEntity f, Predicate<ItemStack> what) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty() || !what.test(st)) continue;
            ItemStack left = f.insertGiven(st.copyWithCount(1));
            if (!left.isEmpty()) return false;
            st.shrink(1);
            if (st.isEmpty()) c.setItem(i, ItemStack.EMPTY);
            c.setChanged();
            return true;
        }
        return false;
    }

    /** How many of what matches the home holds, in all its boxes. */
    static int count(ServerLevel level, UUID village, Homes.Home h, Predicate<ItemStack> what) {
        int n = 0;
        for (Box b : of(level, village, h)) {
            if (!(level.getBlockEntity(b.pos()) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && what.test(s)) n += s.getCount();
            }
        }
        return n;
    }

    /** How full a box is: the share of its slots in use. */
    static double fullness(ServerLevel level, UUID village, Homes.Home h, Kind kind) {
        Container c = box(level, village, h, kind);
        if (c == null) return 1.0;
        int used = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) used++;
        return used / (double) Math.max(1, c.getContainerSize());
    }

    // ------------------------------------------------------------------ the morning's tools

    /**
     * In the morning, before its shift: the tool of its trade out of the household chest, when it has none on it and
     * one of its own is at home (one it bought or was given: never somebody else's). True if it took one.
     */
    static boolean toolsOut(ServerLevel level, VillageFolkEntity f) {
        UUID village = f.ownerId();
        Predicate<ItemStack> tool = Cafe.toolFor(f.stationTask());
        if (village == null || tool == null || f.countCarried(tool) > 0) return false;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null || f.blockPosition().distSqr(h.anchor) > 12 * 12) return false;
        for (Box b : of(level, village, h)) {
            if (!(level.getBlockEntity(b.pos()) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !tool.test(s) || !Homes.ownedBy(s, f)) continue;
                ItemStack left = f.insertItem(s.copy());
                if (!left.isEmpty()) return false;
                c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                f.brain("took its " + Goods.name(s).toLowerCase(java.util.Locale.ROOT) + " out of the chest for the day");
                return true;
            }
        }
        return false;
    }

    /** Tests: a home's storage, as "kind@x,y,z". */
    public static List<String> boxesForTests(ServerLevel level, UUID village, BlockPos anchor) {
        List<String> out = new ArrayList<>();
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        if (h == null) return out;
        SEEN.remove(anchor.asLong());
        for (Box b : of(level, village, h)) out.add(b.kind().name() + "@" + b.pos().toShortString());
        return out;
    }

    /** Tests: name the households' boxes now. */
    public static int claimForTests(ServerLevel level, Villages.Village v) {
        return claim(level, v);
    }
}
