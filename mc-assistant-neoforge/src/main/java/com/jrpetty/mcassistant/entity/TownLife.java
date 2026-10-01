package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The small things that make a place lived in, put in a little at a time round the
 * buildings a village has raised (village/Ledger):
 * <ul>
 * <li>the windows light up after dark, house by house, and go dark again before dawn;</li>
 * <li>smoke from the chimneys: a fire kept in the hearth, its campfire on the chimney's top;</li>
 * <li>a washing line behind the houses, the week's wash pegged out on it;</li>
 * <li>a scarecrow in every field;</li>
 * <li>stalls on the square from the Stone Age, the stores' best goods set out on their counters;</li>
 * <li>a name for every street, on a post at every corner, and a number by every door,
 *     with the names of the family who live there.</li>
 * </ul>
 */
public final class TownLife {

    private TownLife() {}

    private static final long EVERY = 200L;
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();
    /** Whether each building's lights are on now, by its anchor (unknown after a restart). */
    private static final Map<Long, Boolean> LIT = new ConcurrentHashMap<>();
    /** The fields that have their scarecrow. */
    private static final Set<Long> GUARDED = ConcurrentHashMap.newKeySet();

    public static void resetForTests() {
        LAST.clear();
        CURSOR.clear();
        LIT.clear();
        GUARDED.clear();
        FITTINGS.clear();
        NAMES.clear();
    }

    /** One visit to a village's life: at most once every ten seconds. */
    public static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        UUID id = v.id();
        if (now - LAST.getOrDefault(id, -100000L) < EVERY) return;
        LAST.put(id, now);
        List<Ledger.Building> all = Ledger.buildings(id);
        long time = level.getDayTime() % 24000L;
        for (Ledger.Building b : all) {
            if (!level.isLoaded(b.anchor())) continue;
            lights(level, b, wantLit(b, time));
        }
        int turn = CURSOR.merge(id, 1, Integer::sum);
        // One building a visit, in turn, has its chimney, its number and its washing seen to.
        if (!all.isEmpty()) {
            Ledger.Building b = all.get(Math.floorMod(turn, all.size()));
            if (level.isLoaded(b.anchor())) {
                chimney(level, b);
                addressSign(level, id, v.centre(), b);
                if (b.structure().equals("house")) washingLine(level, b);
            }
        }
        // A street corner.
        if (all.size() >= 2) {
            List<int[]> corners = corners(Villages.townReach(id));
            if (!corners.isEmpty()) streetSign(level, id, v.centre(), corners.get(Math.floorMod(turn, corners.size())));
        }
        // The stalls on the square, and what is on them.
        if (Villages.ageOf(id).ordinal() >= Villages.Age.STONE.ordinal() && turn % 6 == 0) {
            stalls(level, v.centre(), storeGoods(level, id));
        }
        // A field's scarecrow.
        List<AssistantEntity> farmers = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a.stationTask() == AssistantEntity.StationTask.FARM && a.workZone() != null) farmers.add(a);
        }
        if (!farmers.isEmpty()) {
            WorkZone z = farmers.get(Math.floorMod(turn, farmers.size())).workZone();
            if (z != null) scarecrow(level, z.center(), Math.min(8, z.radius()));
        }
    }

    /** Everything at once, for every building: the tests and the showcase. */
    public static void dressNow(ServerLevel level, UUID village, BlockPos heart, List<Ledger.Building> all,
                                List<Item> goods) {
        for (Ledger.Building b : all) {
            chimney(level, b);
            addressSign(level, village, heart, b);
            if (b.structure().equals("house")) washingLine(level, b);
        }
        int reach = 0;
        for (Ledger.Building b : all) {
            reach = Math.max(reach, Math.max(Math.abs(b.anchor().getX() - heart.getX()), Math.abs(b.anchor().getZ() - heart.getZ())) + 6);
        }
        for (int[] c : corners(reach)) streetSign(level, village, heart, c);
        for (int i = 0; i < STALLS.length; i++) stalls(level, heart, goods);
    }

    /** Every window of these buildings lit (or put out), now. */
    public static void lightsNow(ServerLevel level, List<Ledger.Building> all, boolean on) {
        for (Ledger.Building b : all) {
            LIT.remove(b.anchor().asLong());
            lights(level, b, on);
        }
    }

    // ------------------------------------------------------------------ a building's fittings

    /** What a building's drawing says about it, worked out once: its windows (and the room
     *  behind each), its door, its chimney tops, its footprint. */
    public record Fittings(List<BlockPos> windows, List<BlockPos> insides, @Nullable BlockPos door, List<BlockPos> chimneys,
                    int[] half) {}

    private static final Map<Ledger.Building, Fittings> FITTINGS = new ConcurrentHashMap<>();

    /** Buildings with a hearth in them: their chimney tops smoke. */
    private static final Set<String> HEARTHS = Set.of("house", "guesthouse", "smeltery", "workshop", "hall",
        "barracks", "granary", "shelter", "storage", "market", "chapel");

    public static Fittings fittings(Ledger.Building b) {
        return FITTINGS.computeIfAbsent(b, k -> {
            List<BuildGoal.Placement> plan = BuildGoal.plan(k.structure(), k.anchor(), k.facing(), 13);
            Set<BlockPos> planned = new HashSet<>();
            Set<BlockPos> masonry = new HashSet<>();
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.CLEAR) continue;
                planned.add(p.pos());
                if (p.part() == BuildGoal.Part.BLOCK
                        && (p.style() == Blueprints.Style.MASONRY || p.style() == Blueprints.Style.BRICK)) {
                    masonry.add(p.pos());
                }
            }
            List<BlockPos> windows = new ArrayList<>();
            List<BlockPos> insides = new ArrayList<>();
            BlockPos door = null;
            List<BlockPos> chimneys = new ArrayList<>();
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.WINDOW) {
                    windows.add(p.pos());
                    BlockPos best = null;
                    double bd = flat(p.pos(), k.anchor());
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos n = p.pos().relative(d);
                        if (planned.contains(n)) continue;
                        double nd = flat(n, k.anchor());
                        if (nd < bd) { bd = nd; best = n; }
                    }
                    insides.add(best);
                } else if (p.part() == BuildGoal.Part.DOOR) {
                    if (door == null || p.pos().getY() < door.getY()) door = p.pos();
                }
            }
            if (HEARTHS.contains(k.structure())) {
                for (BlockPos m : masonry) {
                    if (m.getY() < k.anchor().getY() + 4) continue;
                    if (planned.contains(m.above())) continue;              // not the top
                    if (!masonry.contains(m.below()) || !masonry.contains(m.below(2))) continue;   // a stack, not a sill
                    chimneys.add(m);
                }
            }
            int[] half = BuildGoal.footprint(k.structure());
            return new Fittings(List.copyOf(windows), Collections.unmodifiableList(insides), door, List.copyOf(chimneys), half);
        });
    }

    private static double flat(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ lights in the windows

    /** Each building's lights come on a little after dusk and go out before dawn, not all at once. */
    static boolean wantLit(Ledger.Building b, long time) {
        int h = Math.floorMod(b.anchor().hashCode(), 1000);
        long on = 12300L + h, off = 22300L + h % 900;
        return time >= on && time < off;
    }

    /**
     * A lit window: its glass turns the warm yellow of a lamp behind it, and the room
     * behind it is lit. Put out, the glass is clear again. Only the glass the builder put
     * in is touched; a window somebody has bricked up stays bricked.
     */
    static void lights(ServerLevel level, Ledger.Building b, boolean on) {
        long key = b.anchor().asLong();
        Boolean known = LIT.get(key);
        if (known != null && known == on) return;
        Fittings f = fittings(b);
        for (int i = 0; i < f.windows().size(); i++) {
            BlockPos w = f.windows().get(i);
            BlockState st = level.getBlockState(w);
            BlockState to = null;
            if (on) {
                if (st.is(Blocks.GLASS_PANE)) to = Blocks.YELLOW_STAINED_GLASS_PANE.withPropertiesOf(st);
                else if (st.is(Blocks.GLASS)) to = Blocks.YELLOW_STAINED_GLASS.defaultBlockState();
            } else {
                if (st.is(Blocks.YELLOW_STAINED_GLASS_PANE)) to = Blocks.GLASS_PANE.withPropertiesOf(st);
                else if (st.is(Blocks.YELLOW_STAINED_GLASS)) to = Blocks.GLASS.defaultBlockState();
            }
            if (to != null) level.setBlock(w, to, 2);
            BlockPos in = f.insides().get(i);
            if (in == null) continue;
            BlockState there = level.getBlockState(in);
            if (on && there.isAir()) {
                level.setBlock(in, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 11), 3);
            } else if (!on && there.is(Blocks.LIGHT)) {
                level.setBlock(in, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        LIT.put(key, on);
    }

    // ------------------------------------------------------------------ the hearth

    /** A fire in the hearth: a campfire on the chimney's top, and its smoke. */
    static void chimney(ServerLevel level, Ledger.Building b) {
        for (BlockPos top : fittings(b).chimneys()) {
            if (!level.getBlockState(top).isSolid()) continue;       // the chimney is not up (or is down)
            BlockPos fire = top.above();
            if (level.getBlockState(fire).isAir()) level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), 3);
        }
    }

    // ------------------------------------------------------------------ washing

    private static final Block[] WASH = { Blocks.WHITE_WALL_BANNER, Blocks.LIGHT_BLUE_WALL_BANNER, Blocks.PINK_WALL_BANNER,
        Blocks.YELLOW_WALL_BANNER, Blocks.LIGHT_GRAY_WALL_BANNER, Blocks.LIME_WALL_BANNER, Blocks.WHITE_WALL_BANNER };

    /**
     * A washing line behind a house: two posts and a line between them, the washing pegged
     * out on it. Houses stand back to back, so only the house whose back is to the north or
     * the west puts one up, in the yard the two share.
     */
    static boolean washingLine(ServerLevel level, Ledger.Building b) {
        Direction back = b.facing();
        if (back != Direction.NORTH && back != Direction.WEST) return false;
        Fittings f = fittings(b);
        int deep = f.half()[1] + 1;
        Direction right = back.getClockWise();
        BlockPos base = b.anchor().relative(back, deep);
        BlockPos middle = base.above(2);
        if (level.getBlockState(middle).getBlock() instanceof net.minecraft.world.level.block.FenceBlock) return false;  // up already
        List<BlockPos> fences = new ArrayList<>();
        for (int a = -3; a <= 3; a++) {
            BlockPos col = base.relative(right, a);
            if (Math.abs(a) == 3) {
                if (!level.getBlockState(col.below()).isSolid()) return false;
                fences.add(col);
                fences.add(col.above());
            }
            fences.add(col.above(2));
        }
        List<BlockPos> wash = new ArrayList<>();
        for (int a : new int[]{ -2, -1, 1, 2 }) wash.add(base.relative(right, a).relative(back).above(2));
        for (BlockPos p : fences) if (!level.getBlockState(p).isAir()) return false;
        for (BlockPos p : wash) if (!level.getBlockState(p).isAir() || !level.getBlockState(p.below()).isAir()) return false;
        BlockState post = Blocks.SPRUCE_FENCE.defaultBlockState();
        for (BlockPos p : fences) level.setBlock(p, post, 2 | 16);
        for (BlockPos p : fences) {
            BlockState joined = Block.updateFromNeighbourShapes(post, level, p);
            level.setBlock(p, joined, 2 | 16);
        }
        int k = Math.floorMod(b.anchor().hashCode(), WASH.length);
        for (BlockPos p : wash) {
            level.setBlock(p, WASH[k++ % WASH.length].defaultBlockState().setValue(WallBannerBlock.FACING, back), 3);
        }
        return true;
    }

    // ------------------------------------------------------------------ scarecrows

    /** A scarecrow at a corner of a field: a post, a body of straw with its arms out, a pumpkin for a head. */
    public static boolean scarecrow(ServerLevel level, BlockPos centre, int r) {
        long key = centre.asLong();
        if (GUARDED.contains(key)) return false;
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-r - 1, -3, -r - 1), centre.offset(r + 1, 5, r + 1))) {
            if (level.getBlockState(p).is(Blocks.CARVED_PUMPKIN) || level.getBlockState(p).is(Blocks.JACK_O_LANTERN)) {
                GUARDED.add(key);
                return false;
            }
        }
        int[][] spots = { { r, r }, { -r, -r }, { r, -r }, { -r, r }, { r, 0 }, { -r, 0 }, { 0, r }, { 0, -r } };
        for (int[] s : spots) {
            int x = centre.getX() + s[0], z = centre.getZ() + s[1];
            if (!level.isLoaded(new BlockPos(x, centre.getY(), z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos foot = new BlockPos(x, y, z);
            BlockState ground = level.getBlockState(foot.below());
            if (!ground.isSolid() || ground.is(Blocks.FARMLAND) || !ground.getFluidState().isEmpty()) continue;
            if (Math.abs(y - centre.getY()) > 4) continue;
            boolean clear = true;
            for (int h = 0; h <= 2; h++) if (!level.getBlockState(foot.above(h)).isAir()) clear = false;
            if (!clear) continue;
            Direction look = Direction.getNearest((double) (centre.getX() - x), 0.0, (double) (centre.getZ() - z));
            if (look.getAxis() == Direction.Axis.Y) look = Direction.NORTH;
            level.setBlock(foot, Blocks.OAK_FENCE.defaultBlockState(), 3);
            level.setBlock(foot.above(), Blocks.HAY_BLOCK.defaultBlockState(), 3);
            level.setBlock(foot.above(2), Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, look), 3);
            for (Direction arm : new Direction[]{ look.getClockWise(), look.getCounterClockWise() }) {
                BlockPos a = foot.above().relative(arm);
                if (level.getBlockState(a).isAir()) {
                    level.setBlock(a, Block.updateFromNeighbourShapes(Blocks.OAK_FENCE.defaultBlockState(), level, a), 3);
                }
            }
            level.setBlock(foot, Block.updateFromNeighbourShapes(Blocks.OAK_FENCE.defaultBlockState(), level, foot), 3);
            GUARDED.add(key);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the stalls on the square

    /** Where the stalls stand on the square, and which way their counters face. */
    private static final int[][] STALLS = { { 8, 8 }, { -8, 8 }, { 8, -8 }, { -8, -8 } };
    private static final Block[][] AWNINGS = {
        { Blocks.RED_WOOL, Blocks.WHITE_WOOL }, { Blocks.BLUE_WOOL, Blocks.WHITE_WOOL },
        { Blocks.GREEN_WOOL, Blocks.WHITE_WOOL }, { Blocks.YELLOW_WOOL, Blocks.WHITE_WOOL } };

    /** Put up any stall that is missing (one a call), and set out the goods on all of them. */
    static void stalls(ServerLevel level, BlockPos heart, List<Item> goods) {
        boolean built = false;
        for (int i = 0; i < STALLS.length; i++) {
            BlockPos at = heart.offset(STALLS[i][0], 0, STALLS[i][1]);
            if (!level.isLoaded(at)) continue;
            Direction front = STALLS[i][0] > 0 ? Direction.WEST : Direction.EAST;
            BlockPos ground = groundAt(level, at);
            if (ground == null) continue;
            if (!stallStands(level, ground, front)) {
                if (built) continue;
                if (!putUpStall(level, ground, front, AWNINGS[i])) continue;
                built = true;
            }
            List<Item> mine = new ArrayList<>();
            for (int k = 0; k < 3; k++) {
                int idx = i * 3 + k;
                mine.add(idx < goods.size() ? goods.get(idx) : null);
            }
            setOut(level, ground, front, mine);
        }
    }

    /** The ground a stall stands (or would stand) on: the first floor down from above the square
     *  with room over it, not the stall's own awning. */
    @Nullable
    private static BlockPos groundAt(ServerLevel level, BlockPos at) {
        for (int y = at.getY() + 6; y >= at.getY() - 6; y--) {
            BlockPos p = new BlockPos(at.getX(), y, at.getZ());
            BlockState under = level.getBlockState(p.below());
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) continue;
            if (!under.isSolid() || under.is(net.minecraft.tags.BlockTags.WOOL)) continue;
            return p;
        }
        return null;
    }

    /** The counter: three barrels across the stall's front. */
    private static List<BlockPos> counter(BlockPos ground, Direction front) {
        Direction across = front.getClockWise();
        BlockPos row = ground.relative(front);
        return List.of(row.relative(across, -1), row, row.relative(across, 1));
    }

    private static boolean stallStands(ServerLevel level, BlockPos ground, Direction front) {
        for (BlockPos p : counter(ground, front)) if (!level.getBlockState(p).is(Blocks.BARREL)) return false;
        return true;
    }

    private static boolean putUpStall(ServerLevel level, BlockPos ground, Direction front, Block[] awning) {
        Direction across = front.getClockWise();
        List<BlockPos> foot = new ArrayList<>();
        for (int d = -1; d <= 1; d++) {
            for (int a = -2; a <= 2; a++) foot.add(ground.relative(front, d).relative(across, a));
        }
        for (BlockPos p : foot) {
            if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) != ground.getY()) return false;
            for (int h = 0; h <= 3; h++) if (!level.getBlockState(p.above(h)).isAir()) return false;
        }
        BlockState post = Blocks.SPRUCE_FENCE.defaultBlockState();
        for (int d : new int[]{ -1, 1 }) {
            for (int a : new int[]{ -2, 2 }) {
                BlockPos p = ground.relative(front, d).relative(across, a);
                for (int h = 0; h <= 2; h++) level.setBlock(p.above(h), post, 3);
            }
        }
        BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
        for (BlockPos p : counter(ground, front)) level.setBlock(p, barrel, 3);
        BlockPos back = ground.relative(front.getOpposite());
        level.setBlock(back.relative(across, -1), barrel, 3);
        level.setBlock(back.relative(across, 1), Blocks.HAY_BLOCK.defaultBlockState(), 3);
        for (int d = -1; d <= 1; d++) {
            for (int a = -2; a <= 2; a++) {
                BlockPos p = ground.relative(front, d).relative(across, a).above(3);
                level.setBlock(p, awning[Math.floorMod(a, 2)].defaultBlockState(), 3);
            }
        }
        return true;
    }

    /** The goods on a stall's counter: one of each, in a frame laid flat on a barrel. Fixed, so a
     *  passer-by can look and not help themselves. */
    private static void setOut(ServerLevel level, BlockPos ground, Direction front, List<Item> goods) {
        List<BlockPos> top = counter(ground, front);
        for (int k = 0; k < top.size(); k++) {
            BlockPos at = top.get(k).above();
            Item want = goods.get(k);
            ItemFrame frame = null;
            for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(at))) {
                if (f.getTags().contains("mca_stall")) { frame = f; break; }
            }
            if (frame == null) {
                if (want == null || !level.getBlockState(at).isAir()) continue;
                frame = new ItemFrame(level, at, Direction.UP);
                net.minecraft.nbt.CompoundTag t = frame.saveWithoutId(new net.minecraft.nbt.CompoundTag());
                t.putBoolean("Fixed", true);
                t.putBoolean("Invisible", true);
                frame.load(t);
                frame.addTag("mca_stall");
                level.addFreshEntity(frame);
            }
            ItemStack now = frame.getItem();
            if (want == null) {
                if (!now.isEmpty()) frame.setItem(ItemStack.EMPTY, false);
            } else if (!now.is(want)) {
                frame.setItem(new ItemStack(want), false);
            }
        }
    }

    /** What the stores hold most of, best first: what a market would have out. */
    static List<Item> storeGoods(ServerLevel level, UUID village) {
        Map<Item, Integer> count = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !forSale(s)) continue;
                count.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        List<Item> out = new ArrayList<>(count.keySet());
        out.sort(Comparator.<Item>comparingInt(count::get).reversed()
            .thenComparing(it -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString()));
        return out.size() > 12 ? out.subList(0, 12) : out;
    }

    private static boolean forSale(ItemStack s) {
        return !s.isDamageableItem() && !s.is(Items.CHEST) && !s.is(Items.FURNACE) && !s.is(Items.CRAFTING_TABLE)
            && !s.is(Items.TORCH) && !s.is(Items.LADDER) && !s.is(Items.STICK) && !s.is(Items.DIRT)
            && !s.is(Items.COBBLESTONE) && !s.is(Items.WHEAT_SEEDS);
    }

    // ------------------------------------------------------------------ street names

    private static final String[] PLACES = { "Mill", "Baker", "Orchard", "Brook", "Chapel", "Well", "Hill",
        "Market", "Bridge", "Rose", "Willow", "Ash", "Elm", "Beech", "Holly", "Ivy", "Cherry", "Apple", "Barley",
        "Smith", "Weaver", "Cooper", "Tanner", "Mason", "Potter", "Fisher", "Garden", "Meadow", "Dove", "Lark",
        "Wren", "Swan", "Fox", "Badger", "Hare", "Stone", "Copper", "Lantern", "Bell", "Candle", "Honey", "Clover",
        "Thatch", "Hazel", "Heather", "Primrose", "Sheaf", "Plough" };
    private static final String[] KINDS = { "Street", "Lane", "Row", "Way", "Walk", "Close", "Road", "End" };

    /** Each village's names for its streets: worked out once from the village, so they never change. */
    private static final Map<UUID, Map<Long, String>> NAMES = new ConcurrentHashMap<>();

    /**
     * The name of one half of a street. A street that runs east and west is {@code alongX};
     * {@code line} is where it crosses the other axis (0 for the avenues); {@code half} is
     * which side of the avenue this stretch of it is on. The avenues are the North, South,
     * East and West Roads; every other stretch has a name of its own.
     */
    public static String streetName(UUID village, boolean alongX, int line, int half) {
        if (line == 0) return alongX ? (half > 0 ? "East Road" : "West Road") : (half > 0 ? "South Road" : "North Road");
        Map<Long, String> names = NAMES.computeIfAbsent(village, TownLife::nameStreets);
        String n = names.get(segment(alongX, line, half));
        return n != null ? n : "Long Lane";
    }

    private static long segment(boolean alongX, int line, int half) {
        return ((alongX ? 1L : 0L) << 40) ^ ((long) line << 8) ^ (half > 0 ? 1L : 0L);
    }

    private static Map<Long, String> nameStreets(UUID village) {
        List<String> places = new ArrayList<>(List.of(PLACES));
        Random r = new Random(village.getMostSignificantBits() ^ village.getLeastSignificantBits());
        Collections.shuffle(places, r);
        Map<Long, String> out = new HashMap<>();
        int i = 0;
        for (int k = 0; k <= TownPlan.RINGS; k++) {
            int line = TownPlan.RING + 1 + k * TownPlan.PERIOD;
            for (int sign : new int[]{ 1, -1 }) {
                for (boolean alongX : new boolean[]{ true, false }) {
                    for (int half : new int[]{ 1, -1 }) {
                        String place = places.get(i % places.size());
                        String kind = KINDS[Math.floorMod(place.hashCode() + i * 7 + r.nextInt(3), KINDS.length)];
                        out.put(segment(alongX, sign * line, half), place + " " + kind);
                        i++;
                    }
                }
            }
        }
        return out;
    }

    /** The street line an offset across the town lies on (0 for an avenue), or MIN_VALUE if none. */
    static int lineAt(int across) {
        int a = Math.abs(across);
        if (a <= TownPlan.AVENUE) return 0;
        if (a < TownPlan.RING || (a - TownPlan.RING) % TownPlan.PERIOD >= TownPlan.STREET) return Integer.MIN_VALUE;
        return Integer.signum(across) * (TownPlan.RING + 1 + TownPlan.PERIOD * ((a - TownPlan.RING) / TownPlan.PERIOD));
    }

    /** How far out the lots along a street stand, nearest first: each is a number along it. */
    private static final int[] ALONG = alongs();

    private static int[] alongs() {
        List<Integer> out = new ArrayList<>();
        out.add(TownPlan.AVENUE + 1 + TownPlan.LOT / 2);
        for (int k = 0; k < TownPlan.RINGS + 1; k++) {
            int street = TownPlan.RING + k * TownPlan.PERIOD;
            out.add(street + TownPlan.STREET + TownPlan.LOT / 2);
            out.add(street + TownPlan.STREET + TownPlan.LOT + TownPlan.LOT / 2);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * A building's address: its number and its street, from where it stands and which way its
     * door opens. The numbers go up from the avenue outward, odd on one side and even on the
     * other, the way anybody numbers a street. Null if its door does not open onto a street.
     */
    @Nullable
    public static String[] address(UUID village, BlockPos heart, Ledger.Building b) {
        Direction front = b.facing().getOpposite();
        int ax = b.anchor().getX() - heart.getX(), az = b.anchor().getZ() - heart.getZ();
        int reach = TownPlan.LOT / 2 + 1;
        int sx = ax + front.getStepX() * reach, sz = az + front.getStepZ() * reach;
        boolean alongX = front.getAxis() == Direction.Axis.Z;      // a door to the north or south: an east-west street
        int across = alongX ? sz : sx;
        int line = lineAt(across);
        if (line == Integer.MIN_VALUE) return null;
        int along = alongX ? ax : az;
        int centreAcross = alongX ? az : ax;
        int a = Math.abs(along);
        int idx = 0, best = Integer.MAX_VALUE;
        for (int i = 0; i < ALONG.length; i++) {
            int d = Math.abs(ALONG[i] - a);
            if (d < best) { best = d; idx = i; }
        }
        boolean odd = centreAcross < line;
        int number = idx * 2 + (odd ? 1 : 2);
        return new String[]{ "No. " + number, streetName(village, alongX, line, along >= 0 ? 1 : -1) };
    }

    // ------------------------------------------------------------------ signs

    /** The sign by a building's door: its number and street, and who lives there (or what it is). */
    static boolean addressSign(ServerLevel level, UUID village, BlockPos heart, Ledger.Building b) {
        Fittings f = fittings(b);
        if (f.door() == null) return false;
        Direction front = b.facing().getOpposite();
        Direction right = b.facing().getClockWise();
        String[] where = address(village, heart, b);
        String[] lines = new String[4];
        if (b.structure().equals("house")) {
            List<String> who = residents(village, b, f);
            lines[0] = where != null ? where[0] : "";
            lines[1] = where != null ? where[1] : "";
            lines[2] = who.isEmpty() ? "" : who.get(0);
            lines[3] = who.size() > 2 ? "& family" : who.size() == 2 ? "& " + who.get(1) : "";
        } else {
            lines[0] = title(b.structure());
            lines[1] = where != null ? where[0] : "";
            lines[2] = where != null ? where[1] : "";
            lines[3] = "";
        }
        for (Direction side : new Direction[]{ right, right.getOpposite() }) {
            BlockPos wall = f.door().above().relative(side);
            BlockPos spot = wall.relative(front);
            BlockState there = level.getBlockState(spot);
            boolean ours = there.getBlock() instanceof WallSignBlock;
            if (!ours && (!there.isAir() || !level.getBlockState(wall).isSolid())) continue;
            if (!ours) {
                level.setBlock(spot, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front), 3);
            }
            if (level.getBlockEntity(spot) instanceof SignBlockEntity sign) write(sign, lines);
            return true;
        }
        return false;
    }

    /** A building's name over its door. */
    static String title(String structure) {
        return switch (structure) {
            case "storage" -> "The Storehouse";
            case "hall" -> "The Meeting Hall";
            case "guesthouse" -> "The Guest House";
            case "watchtower" -> "The Watchtower";
            case "lighthouse" -> "The Lighthouse";
            default -> "The " + Character.toUpperCase(structure.charAt(0)) + structure.substring(1);
        };
    }

    /** Who sleeps in this house, by name. */
    private static List<String> residents(UUID village, Ledger.Building b, Fittings f) {
        List<String> out = new ArrayList<>();
        int r = Math.max(f.half()[0], f.half()[1]);
        for (AssistantEntity a : Villages.folkOf(village)) {
            BlockPos bed = a.bedPos();
            if (bed == null) continue;
            if (Math.abs(bed.getX() - b.anchor().getX()) <= r && Math.abs(bed.getZ() - b.anchor().getZ()) <= r
                    && Math.abs(bed.getY() - b.anchor().getY()) <= 4) {
                out.add(a.displayNameCap());
            }
        }
        Collections.sort(out);
        return out;
    }

    private static void write(SignBlockEntity sign, String[] lines) {
        SignText now = sign.getFrontText();
        boolean same = true;
        for (int i = 0; i < 4; i++) {
            if (!now.getMessage(i, false).getString().equals(lines[i])) { same = false; break; }
        }
        if (same) return;
        SignText text = new SignText();
        for (int i = 0; i < 4; i++) text = text.setMessage(i, Component.literal(lines[i]));
        sign.setText(text, true);
        sign.setWaxed(true);
    }

    /** Every street corner of the town within this reach: the crossings of its streets, nearest first. */
    static List<int[]> corners(int reach) {
        List<Integer> lines = new ArrayList<>();
        lines.add(0);
        for (int k = 0; k < TownPlan.RINGS; k++) {
            int l = TownPlan.RING + 1 + k * TownPlan.PERIOD;
            lines.add(l);
            lines.add(-l);
        }
        List<int[]> out = new ArrayList<>();
        for (int x : lines) {
            for (int z : lines) {
                if (x == 0 && z == 0) continue;
                if (Math.max(Math.abs(x), Math.abs(z)) + 3 > reach) continue;
                out.add(new int[]{ x, z });
            }
        }
        out.sort(Comparator.comparingInt(c -> Math.max(Math.abs(c[0]), Math.abs(c[1]))));
        return out;
    }

    /**
     * A street sign at a crossing: a post on the corner away from the heart, a lantern on top,
     * and the names of the two streets on it, each facing its own street.
     */
    static boolean streetSign(ServerLevel level, UUID village, BlockPos heart, int[] crossing) {
        int x = crossing[0], z = crossing[1];
        int sx = x == 0 ? 1 : Integer.signum(x), sz = z == 0 ? 1 : Integer.signum(z);
        int cx = x + sx * ((x == 0 ? TownPlan.AVENUE : 1) + 1);
        int cz = z + sz * ((z == 0 ? TownPlan.AVENUE : 1) + 1);
        if (TownPlan.isStreet(cx, cz) || TownPlan.isSquare(cx, cz)) return false;
        int wx = heart.getX() + cx, wz = heart.getZ() + cz;
        BlockPos probe = new BlockPos(wx, heart.getY(), wz);
        if (!level.isLoaded(probe)) return false;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
        if (Math.abs(y - heart.getY()) > 10) return false;
        BlockPos post = new BlockPos(wx, y, wz);
        BlockState ground = level.getBlockState(post.below());
        // Up already: its lantern is the top of the column.
        if (ground.is(Blocks.LANTERN) || ground.getBlock() instanceof net.minecraft.world.level.block.FenceBlock) return false;
        if (!ground.isSolid() || !ground.getFluidState().isEmpty()) return false;
        Direction toNS = sx > 0 ? Direction.WEST : Direction.EAST;      // toward the street that runs north and south
        Direction toEW = sz > 0 ? Direction.NORTH : Direction.SOUTH;    // toward the one that runs east and west
        BlockPos signNS = post.above().relative(toNS), signEW = post.above().relative(toEW);
        for (BlockPos p : List.of(post, post.above(), post.above(2), signNS, signEW)) {
            if (!level.getBlockState(p).isAir()) return false;
        }
        BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        level.setBlock(post, fence, 3);
        level.setBlock(post.above(), fence, 3);
        level.setBlock(post.above(2), Blocks.LANTERN.defaultBlockState(), 3);
        String ns = streetName(village, false, x, cz >= 0 ? 1 : -1);
        String ew = streetName(village, true, z, cx >= 0 ? 1 : -1);
        level.setBlock(signNS, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, toNS), 3);
        level.setBlock(signEW, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, toEW), 3);
        if (level.getBlockEntity(signNS) instanceof SignBlockEntity a) write(a, new String[]{ "", ns, "", "" });
        if (level.getBlockEntity(signEW) instanceof SignBlockEntity b) write(b, new String[]{ "", ew, "", "" });
        return true;
    }
}
