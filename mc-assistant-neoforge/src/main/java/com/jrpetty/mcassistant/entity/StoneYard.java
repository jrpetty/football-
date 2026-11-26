package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featbuild] The stone yard: where the quarry's stone goes, is dressed and is kept, and where the town's builders draw their
 * stone from.
 *
 * <p><b>The yard.</b> A fenced yard on the quarry's town side (Quarry chooses both together, out past the town's growth line):
 * a gate toward the town, a sign with the town's name, the mason's stonecutter and furnace, a barrel, and its stockpiles in
 * three rows: the rough stone (cobblestone and the rest of what the quarry cuts), the dressed stone (stone bricks), and the
 * bricks. A pile stands a block high for every sixteen in stock, three high at most, five piles a row: what is in the yard is
 * what is seen there. Its stock is kept with the world ("yard.stock"), and a pile block somebody carries off is counted
 * off the stock at the next look.
 *
 * <p><b>The mason.</b> By day, with a quarryman at the yard, eight cobblestone at a time are smelted and cut into stone bricks
 * (a coal or charcoal of the stores' for every eight, the furnace lit while it works), and four of the stores' clay balls
 * fired into a block of bricks, while the yard has fewer than it wants of them.
 *
 * <p><b>The builders.</b> City buildings draw their stone from the yard: a commission (BuildOrders) takes its cobblestone,
 * stone, stone bricks and bricks out of the yard's stock first, and only then out of the stores; short of them in both, its
 * builders wait, and its status says "waiting on stone from the yard". The yard also keeps the town's stores in stone: a
 * quarryman carts a load to the stores whenever they run under what the town's own works want (sixty-four cobblestone,
 * sixty-four stone bricks, thirty-two bricks). What it is short of, and who is waiting, is counted for the board.
 *
 * <p>The stock, the day's flow (cut, dressed, carted to the stores, drawn by the builders) and the shortages are on the board's
 * Stone page, with markers for the quarry and the yard.
 */
public final class StoneYard {

    private StoneYard() {}

    static final String STOCK = "yard.stock", FLOW = "yard.flow", SITE = "yard.site";
    /** The rows of piles: rough stone, dressed stone, bricks. */
    static final int ROUGH = 0, DRESSED = 1, BRICK = 2;
    static final int PILES = 5, HIGH = 3, PER_BLOCK = 16;
    /** The yard's footprint: so many either side of its middle, across and deep. */
    public static final int HALF_W = 4, HALF_D = 3;
    /** What the yard keeps the stores in, of each (the town's own works draw on the stores). */
    static final int STORES_ROUGH = 64, STORES_DRESSED = 64, STORES_BRICK = 32;
    /** A cart's load to the stores. */
    static final int CART = 32;
    /** What the mason dresses at a time; how much dressed stone and brick the yard wants in hand before it stops. */
    static final int DRESS = 8, WANT_DRESSED = 160, WANT_BRICK = 64;

    /** The stone a yard keeps, by its item. */
    static final List<Item> ROUGH_STONE = List.of(Items.COBBLESTONE, Items.STONE, Items.COBBLED_DEEPSLATE, Items.ANDESITE, Items.DIORITE,
        Items.GRANITE, Items.TUFF, Items.CALCITE);

    /** The stone the yard keeps (and builders may draw on it for). */
    public static boolean isYardStone(@Nullable Item it) {
        return it != null && (ROUGH_STONE.contains(it) || it == Items.STONE_BRICKS || it == Items.BRICKS);
    }

    static int row(Item it) {
        return it == Items.STONE_BRICKS ? DRESSED : it == Items.BRICKS ? BRICK : ROUGH;
    }

    // ------------------------------------------------------------------ where it is

    /** The yard's middle and the way its gate faces (toward the town), or null while the town has none. */
    public record Site(BlockPos centre, Direction gate) {}

    @Nullable
    public static Site site(UUID town) {
        String s = Ledger.note(town, SITE);
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length < 4) return null;
        try {
            return new Site(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), Direction.from2DDataValue(Integer.parseInt(p[3])));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean has(@Nullable UUID town) {
        return town != null && site(town) != null;
    }

    static void place(UUID town, BlockPos centre, Direction gate) {
        Ledger.note(town, SITE, centre.getX() + "," + centre.getY() + "," + centre.getZ() + "," + gate.get2DDataValue());
    }

    /** The yard's ground, as {x0, z0, x1, z1}, its fence included. */
    static int[] box(Site s) {
        boolean ns = s.gate().getAxis() == Direction.Axis.Z;
        int hx = ns ? HALF_W : HALF_D, hz = ns ? HALF_D : HALF_W;
        return new int[]{ s.centre().getX() - hx, s.centre().getZ() - hz, s.centre().getX() + hx, s.centre().getZ() + hz };
    }

    // ------------------------------------------------------------------ the stock

    public static Map<Item, Integer> stock(UUID town) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        String s = Ledger.note(town, STOCK);
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(",")) {
            int c = part.lastIndexOf('=');
            if (c <= 0) continue;
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(part.substring(0, c)));
            if (it == Items.AIR) continue;
            try {
                int n = Integer.parseInt(part.substring(c + 1));
                if (n > 0) out.merge(it, n, Integer::sum);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static void save(UUID town, Map<Item, Integer> stock) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : stock.entrySet()) {
            if (e.getValue() > 0) parts.add(BuiltInRegistries.ITEM.getKey(e.getKey()) + "=" + e.getValue());
        }
        Ledger.note(town, STOCK, String.join(",", parts));
        DIRTY.put(town, Boolean.TRUE);
    }

    public static int count(UUID town, Item it) {
        return stock(town).getOrDefault(it, 0);
    }

    /** Stock of a row: the rough stone all together, the stone bricks, the bricks. */
    public static int rowStock(UUID town, int row) {
        int n = 0;
        for (Map.Entry<Item, Integer> e : stock(town).entrySet()) if (row(e.getKey()) == row) n += e.getValue();
        return n;
    }

    /** Stone into the yard (a quarryman's load). */
    static void receive(ServerLevel level, UUID town, Map<Item, Integer> load) {
        if (load.isEmpty()) return;
        Map<Item, Integer> st = stock(town);
        int n = 0;
        for (Map.Entry<Item, Integer> e : load.entrySet()) {
            st.merge(e.getKey(), e.getValue(), Integer::sum);
            n += e.getValue();
        }
        save(town, st);
        flow(level, town, 0, n);
    }

    /** Take so many of a thing out of the yard; all or nothing. */
    static boolean take(UUID town, Item it, int n) {
        Map<Item, Integer> st = stock(town);
        int have = st.getOrDefault(it, 0);
        if (have < n) return false;
        st.put(it, have - n);
        save(town, st);
        return true;
    }

    // ------------------------------------------------------------------ the builders

    /** What each town's builders are waiting on from the yard: item → {wanted, the day first wanted}, and by whom. */
    private record Short(int wanted, long since, String who) {}

    private static final Map<UUID, Map<Item, Short>> SHORT = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> DIRTY = new ConcurrentHashMap<>();

    /**
     * A builder's block of yard stone (BuildOrders.takeOne): out of the yard's stock if it has one. Short, the shortage is
     * counted for the board (and the builder goes on to the stores, then waits).
     */
    public static boolean forBuild(ServerLevel level, Villages.Village v, Item it, int n, String who) {
        if (!isYardStone(it) || !has(v.id())) return false;
        if (take(v.id(), it, n)) {
            flow(level, v.id(), 3, n);
            Map<Item, Short> s = SHORT.get(v.id());
            if (s != null) s.remove(it);
            return true;
        }
        long day = level.getDayTime() / 24000L;
        SHORT.computeIfAbsent(v.id(), k -> new ConcurrentHashMap<>()).merge(it, new Short(n, day, who),
            (a, b) -> new Short(Math.min(9999, a.wanted() + b.wanted()), a.since(), b.who()));
        return false;
    }

    /** The words a builder's status uses for its wait: "stone from the yard (24 cobblestone)". */
    public static String waitWords(int n, Item it) {
        return "stone from the yard (" + n + " " + new ItemStack(it).getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + ")";
    }

    /** Builds waiting on the yard now, in words. */
    public static List<String> shortages(UUID town) {
        List<String> out = new ArrayList<>();
        Map<Item, Short> s = SHORT.get(town);
        if (s == null) return out;
        for (Map.Entry<Item, Short> e : s.entrySet()) {
            out.add(e.getValue().who() + ": waiting on " + e.getValue().wanted() + " " + new ItemStack(e.getKey()).getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
                + " from the yard since day " + (e.getValue().since() + 1));
        }
        return out;
    }

    // ------------------------------------------------------------------ the stores kept in stone

    /** What the stores want carted to them now (the item and how many), or null. */
    @Nullable
    static ItemStack deliveryDue(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Object[][] wants = { { Items.COBBLESTONE, STORES_ROUGH }, { Items.STONE_BRICKS, STORES_DRESSED }, { Items.BRICKS, STORES_BRICK } };
        for (Object[] w : wants) {
            Item it = (Item) w[0];
            int want = (Integer) w[1];
            int inStores = Market.stock(level, id, s -> s.is(it));
            if (inStores >= want) continue;
            int have = count(id, it);
            if (have < 8) continue;
            return new ItemStack(it, Math.min(CART, Math.min(have, want - inStores + 16)));
        }
        return null;
    }

    /** A cart's load taken out of the yard for the stores (the quarryman carries it). */
    static boolean load(UUID town, ItemStack s) {
        return take(town, s.getItem(), s.getCount());
    }

    /** The load delivered into the stores. */
    static void deliver(ServerLevel level, Villages.Village v, ItemStack s) {
        Crafts.store(level, v, s.copy());
        flow(level, v.id(), 2, s.getCount());
    }

    // ------------------------------------------------------------------ the mason (StoneYard.tick)

    private static final Map<UUID, Long> DRESSED_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SHORT.clear();
        DIRTY.clear();
        DRESSED_AT.clear();
        LOOKED.clear();
    }

    /** Every second (Raids' round): the piles kept to the stock, the mason at its work. */
    static void tick(ServerLevel level, Villages.Village v) {
        Site s = site(v.id());
        if (s == null || !level.isLoaded(s.centre())) return;
        long now = level.getGameTime();
        Long last = LOOKED.get(v.id());
        if (last != null && now - last < 100L && now >= last && !DIRTY.containsKey(v.id())) return;
        LOOKED.put(v.id(), now);
        reconcile(level, v.id(), s);
        dress(level, v, s, false);
        piles(level, v.id(), s);
        DIRTY.remove(v.id());
    }

    /** The mason's turn: cobblestone smelted and cut into stone bricks, clay fired into bricks. Returns what was made. */
    static int dress(ServerLevel level, Villages.Village v, Site s, boolean now) {
        UUID id = v.id();
        long t = Math.floorMod(level.getDayTime(), 24000L);
        if (!now && (t < 1000L || t > 11500L || !Quarry.atYard(level, v))) { lit(level, s, false); return 0; }
        long gt = level.getGameTime();
        Long was = DRESSED_AT.get(id);
        if (!now && was != null && gt - was < 600L && gt >= was) return 0;
        DRESSED_AT.put(id, gt);
        int made = 0;
        if (count(id, Items.STONE_BRICKS) < WANT_DRESSED && count(id, Items.COBBLESTONE) >= DRESS && fuel(level, v)) {
            Map<Item, Integer> st = stock(id);
            st.merge(Items.COBBLESTONE, -DRESS, Integer::sum);
            st.merge(Items.STONE_BRICKS, DRESS, Integer::sum);
            save(id, st);
            made += DRESS;
        }
        if (count(id, Items.BRICKS) < WANT_BRICK && Market.stock(level, id, x -> x.is(Items.CLAY_BALL)) >= 4 && fuel(level, v)
                && Crafts.take(level, v, x -> x.is(Items.CLAY_BALL), 4)) {
            Map<Item, Integer> st = stock(id);
            st.merge(Items.BRICKS, 1, Integer::sum);
            save(id, st);
            made++;
        }
        lit(level, s, made > 0);
        if (made > 0) flow(level, id, 1, made);
        return made;
    }

    /** A coal or charcoal of the stores' for the furnace (one does eight). */
    private static boolean fuel(ServerLevel level, Villages.Village v) {
        return Crafts.take(level, v, x -> x.is(Items.COAL) || x.is(Items.CHARCOAL), 1);
    }

    private static void lit(ServerLevel level, Site s, boolean on) {
        BlockPos f = furnace(s);
        BlockState st = level.getBlockState(f);
        if (st.getBlock() instanceof FurnaceBlock && st.getValue(FurnaceBlock.LIT) != on) level.setBlock(f, st.setValue(FurnaceBlock.LIT, on), 3);
    }

    // ------------------------------------------------------------------ the yard laid out, and its piles

    /** The yard's ground: {x, z} offsets in its own frame (across, deep from the gate side) turned to the world. */
    static BlockPos at(Site s, int across, int deep) {
        Direction g = s.gate(), right = g.getClockWise();
        BlockPos c = s.centre();
        // deep: + toward the back (away from the gate)
        return c.relative(right, across).relative(g.getOpposite(), deep);
    }

    static BlockPos furnace(Site s) {
        return ground(at(s, HALF_W - 1, -HALF_D + 1), s);
    }

    static BlockPos ground(BlockPos p, Site s) {
        return new BlockPos(p.getX(), s.centre().getY(), p.getZ());
    }

    /** The pile slots of a row: five spots side by side, each its base. */
    static List<BlockPos> slots(Site s, int row) {
        List<BlockPos> out = new ArrayList<>();
        int deep = -1 + row;                                            // rows one behind another, the gate's side first
        for (int k = 0; k < PILES; k++) out.add(ground(at(s, -HALF_W + 1 + k, deep), s));
        return out;
    }

    static Block pileBlock(int row) {
        return row == DRESSED ? Blocks.STONE_BRICKS : row == BRICK ? Blocks.BRICKS : Blocks.COBBLESTONE;
    }

    /** How many pile blocks a row should show for its stock. */
    static int shown(int stock) {
        return Math.min(PILES * HIGH, (stock + PER_BLOCK - 1) / PER_BLOCK);
    }

    /** A pile block somebody carried off is counted off the stock (sixteen of its row's stone for each). */
    static void reconcile(ServerLevel level, UUID town, Site s) {
        if (Ledger.note(town, "yard.laid") == null) return;
        for (int row = 0; row < 3; row++) {
            int want = shown(rowStock(town, row));
            int there = 0;
            Block b = pileBlock(row);
            for (BlockPos base : slots(s, row)) for (int y = 0; y < HIGH; y++) if (level.getBlockState(base.above(y)).is(b)) there++;
            if (there >= want) continue;
            int lost = (want - there) * PER_BLOCK;
            Map<Item, Integer> st = stock(town);
            for (Map.Entry<Item, Integer> e : st.entrySet()) {
                if (row(e.getKey()) != row || lost <= 0) continue;
                int off = Math.min(lost, e.getValue());
                e.setValue(e.getValue() - off);
                lost -= off;
            }
            save(town, st);
        }
    }

    /** The piles stacked to the stock: a block for every sixteen, the first pile filled to three before the next. */
    static void piles(ServerLevel level, UUID town, Site s) {
        if (Ledger.note(town, "yard.laid") == null) return;
        for (int row = 0; row < 3; row++) {
            int want = shown(rowStock(town, row));
            Block b = pileBlock(row);
            int k = 0;
            for (BlockPos base : slots(s, row)) {
                for (int y = 0; y < HIGH; y++, k++) {
                    BlockPos p = base.above(y);
                    BlockState st = level.getBlockState(p);
                    if (k < want) {
                        if (st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty()) level.setBlock(p, b.defaultBlockState(), 3);
                    } else if (st.is(b)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    /** The yard laid out (once, by the quarry's first hands): its ground made level, its fence and gate, the sign, the mason's
     *  stonecutter, furnace and barrel. Out of the stores, unless {@code free}. Returns whether it is laid. */
    static boolean layOut(ServerLevel level, Villages.Village v, Site s, boolean free) {
        UUID id = v.id();
        if (Ledger.note(id, "yard.laid") != null) return true;
        int[] b = box(s);
        int y = s.centre().getY();
        for (int x = b[0]; x <= b[2]; x++) {
            for (int z = b[1]; z <= b[3]; z++) {
                // The ground made level: the floor at y - 1, nothing natural over it.
                BlockPos floor = new BlockPos(x, y - 1, z);
                BlockState fs = level.getBlockState(floor);
                if (fs.isAir() || fs.canBeReplaced()) level.setBlock(floor, Blocks.COARSE_DIRT.defaultBlockState(), 3);
                for (int up = 0; up < 4; up++) {
                    BlockPos p = new BlockPos(x, y + up, z);
                    BlockState st = level.getBlockState(p);
                    if (!st.isAir() && (CastleWall.natural(st) || CastleWall.wild(level, p, st)) && st.getFluidState().isEmpty()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                boolean edge = x == b[0] || x == b[2] || z == b[1] || z == b[3];
                if (!edge) {
                    if (fs.is(Blocks.GRASS_BLOCK) || fs.is(Blocks.DIRT)) level.setBlock(floor, Blocks.COARSE_DIRT.defaultBlockState(), 3);
                    continue;
                }
                BlockPos p = new BlockPos(x, y, z);
                level.setBlock(p, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
            }
        }
        // The gate in the middle of the town side; the sign beside it; a lantern on each gate post.
        BlockPos gate = ground(at(s, 0, -HALF_D), s);
        level.setBlock(gate, Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, s.gate()), 3);
        for (int side : new int[]{ -1, 1 }) {
            BlockPos post = ground(at(s, side, -HALF_D), s);
            level.setBlock(post.above(), Blocks.LANTERN.defaultBlockState(), 3);
        }
        BlockPos signAt = ground(at(s, 2, -HALF_D), s).relative(s.gate());
        if (level.getBlockState(signAt).isAir() || level.getBlockState(signAt).canBeReplaced()) {
            level.setBlock(signAt, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION,
                net.minecraft.world.level.block.state.properties.RotationSegment.convertToSegment(s.gate().toYRot())), 3);
            if (level.getBlockEntity(signAt) instanceof SignBlockEntity sign) {
                TownLife.write(sign, new String[]{ "Stone yard", "of", Villages.name(id), "" });
            }
        }
        level.setBlock(furnace(s), Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, s.gate()), 3);
        level.setBlock(ground(at(s, HALF_W - 1, 0), s), Blocks.STONECUTTER.defaultBlockState(), 3);
        level.setBlock(ground(at(s, HALF_W - 1, HALF_D - 1), s), Blocks.BARREL.defaultBlockState(), 3);
        level.setBlock(ground(at(s, HALF_W - 2, HALF_D - 1), s), Blocks.SMITHING_TABLE.defaultBlockState(), 3);
        Ledger.note(id, "yard.laid", Long.toString(level.getDayTime() / 24000L));
        piles(level, id, s);
        return true;
    }

    // ------------------------------------------------------------------ the day's flow

    /** The day's flow: {cut into the yard, dressed, carted to the stores, drawn by the builders}. */
    static void flow(ServerLevel level, UUID town, int what, int n) {
        long day = level.getDayTime() / 24000L;
        List<long[]> days = flows(town);
        long[] today = null;
        for (long[] d : days) if (d[0] == day) today = d;
        if (today == null) {
            today = new long[]{ day, 0, 0, 0, 0 };
            days.add(today);
        }
        today[1 + what] += n;
        while (days.size() > 7) days.remove(0);
        List<String> parts = new ArrayList<>();
        for (long[] d : days) parts.add(d[0] + ":" + d[1] + ":" + d[2] + ":" + d[3] + ":" + d[4]);
        Ledger.note(town, FLOW, String.join(",", parts));
    }

    /** The last week's flow, oldest first: {day, cut, dressed, carted, drawn}. */
    public static List<long[]> flows(UUID town) {
        List<long[]> out = new ArrayList<>();
        String s = Ledger.note(town, FLOW);
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(",")) {
            String[] p = part.split(":");
            if (p.length < 5) continue;
            try {
                out.add(new long[]{ Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]), Long.parseLong(p[4]) });
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ the board

    public static CompoundTag report(ServerLevel level, UUID town) {
        CompoundTag c = new CompoundTag();
        Site s = site(town);
        c.putBoolean("has", s != null);
        if (s != null) c.putIntArray("at", new int[]{ s.centre().getX(), s.centre().getY(), s.centre().getZ() });
        CompoundTag st = new CompoundTag();
        for (Map.Entry<Item, Integer> e : stock(town).entrySet()) st.putInt(new ItemStack(e.getKey()).getHoverName().getString(), e.getValue());
        c.put("stock", st);
        c.putInt("rough", rowStock(town, ROUGH));
        c.putInt("dressed", rowStock(town, DRESSED));
        c.putInt("brick", rowStock(town, BRICK));
        c.putInt("storesRough", Market.stock(level, town, x -> x.is(Items.COBBLESTONE)));
        c.putInt("storesDressed", Market.stock(level, town, x -> x.is(Items.STONE_BRICKS)));
        c.putInt("storesBrick", Market.stock(level, town, x -> x.is(Items.BRICKS)));
        ListTag flow = new ListTag();
        for (long[] d : flows(town)) {
            CompoundTag f = new CompoundTag();
            f.putLong("day", d[0] + 1);
            f.putLong("cut", d[1]);
            f.putLong("dressed", d[2]);
            f.putLong("carted", d[3]);
            f.putLong("drawn", d[4]);
            flow.add(f);
        }
        c.put("flow", flow);
        ListTag sh = new ListTag();
        for (String line : shortages(town)) sh.add(StringTag.valueOf(line));
        c.put("short", sh);
        return c;
    }

    // ------------------------------------------------------------------ the tests

    public static void stockForTests(UUID town, Item it, int n) {
        Map<Item, Integer> st = stock(town);
        st.put(it, n);
        save(town, st);
    }

    public static void siteForTests(UUID town, BlockPos centre, Direction gate) {
        place(town, centre, gate);
    }

    public static boolean layOutForTests(ServerLevel level, Villages.Village v) {
        Site s = site(v.id());
        return s != null && layOut(level, v, s, true);
    }

    public static int dressForTests(ServerLevel level, Villages.Village v) {
        Site s = site(v.id());
        return s == null ? 0 : dress(level, v, s, true);
    }

    public static void pilesForTests(ServerLevel level, UUID town) {
        Site s = site(town);
        if (s != null) { reconcile(level, town, s); piles(level, town, s); }
    }

    @Nullable
    public static ItemStack deliveryForTests(ServerLevel level, Villages.Village v) {
        return deliveryDue(level, v);
    }

    static int heightAt(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
