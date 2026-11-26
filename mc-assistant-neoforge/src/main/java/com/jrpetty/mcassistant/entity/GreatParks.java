package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featbuild] The town's bigger greens, as it grows: the statue gardens of a young stone town (Monuments) first; at forty
 * folk a <b>park</b> a whole block of the plan across (winding paths, a flower meadow, a grove, a pond with lily pads and a
 * bridge over it, benches, a gazebo or a bandstand, lamps), another at seventy; and at ninety, a city's <b>central park</b>
 * four blocks across, planned into the city's layout: a lake with a boathouse and a bridge, tree-lined avenues, lawns and
 * flower gardens, a fountain plaza, statues, a walking loop, a sports green with its pitch marked (for the matches on the
 * green), lamps all round, picnic rugs; folk stroll, picnic and sit there as in any park (Park's visits).
 *
 * <p><b>Where.</b> Only on land the plan sets aside for green: whole blocks of the plan (between its streets) none of whose
 * lots are built on, claimed, or kept for the farmland, the mine or a railway, on level dry ground, the nearest the heart
 * first, and never so many that the town would be short of lots for homes (a dozen kept free in the plan as it is laid out,
 * the growth belt beyond it untouched). Once chosen, the block is the park's for good: its lots are kept off for anything
 * else (Villages.lotKeptOff), so the homes go up round it, not over it.
 *
 * <p><b>Laid out.</b> By the town's gardeners a little at a time (a few dozen blocks a round): the ground levelled (its edge
 * stepped to the land round it, never a drop over a block), then the water, paths, buildings, trees, beds and lamps. Every
 * pond and lake is dug into the ground with a sound floor and rim and water only ever standing level with the ground; each
 * round the rims are looked at and any gap stopped with stone, and a block of a rim broken empties its water first.
 */
@EventBusSubscriber(modid = com.jrpetty.mcassistant.McAssistantMod.MODID)
public final class GreatParks {

    private GreatParks() {}

    public static final String MID = "bigpark", LATE = "citypark";
    static final String NOTE = "greens.great";
    public static final int MID_FOLK = 40, MID_MORE = 70, LATE_FOLK = 90;
    /** Lots of the plan kept free for homes when a park takes a block. */
    static final int KEEP_FREE = 12;
    /** Blocks laid a round (each round a second or so). */
    static final int A_ROUND = 48;

    private static volatile boolean standDown;
    private static final Map<UUID, Integer> FOLK_FOR_TESTS = new ConcurrentHashMap<>();

    public static void standDownForTests(boolean down) {
        standDown = down;
    }

    public static void folkForTests(UUID town, @Nullable Integer folk) {
        if (folk == null) FOLK_FOR_TESTS.remove(town); else FOLK_FOR_TESTS.put(town, folk);
    }

    public static void resetForTests() {
        DESIGNS.clear();
        LAST.clear();
        LOOKED.clear();
        FOLK_FOR_TESTS.clear();
    }

    public static boolean mine(@Nullable String structure) {
        return MID.equals(structure) || LATE.equals(structure);
    }

    static int folk(UUID town) {
        Integer f = FOLK_FOR_TESTS.get(town);
        return f != null ? f : Villages.headcount(town);
    }

    /** The tier the town is at: 0 statue gardens only, 1 a park, 2 two parks, 3 the central park too. */
    public static int tier(UUID town) {
        int f = folk(town);
        return f >= LATE_FOLK ? 3 : f >= MID_MORE ? 2 : f >= MID_FOLK ? 1 : 0;
    }

    // ------------------------------------------------------------------ the greens kept

    /** A green: its kind, its bounds (world), its ground's level, the way it faces (toward the heart). */
    public record Green(String kind, int x0, int z0, int x1, int z1, int y, Direction facing) {
        BlockPos anchor() {
            return new BlockPos((x0 + x1) / 2, y + 1, (z0 + z1) / 2);
        }

        public int w() { return x1 - x0 + 1; }

        public int d() { return z1 - z0 + 1; }

        String text() {
            return kind + "," + x0 + "," + z0 + "," + x1 + "," + z1 + "," + y + "," + facing.get2DDataValue();
        }

        boolean contains(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }

    public static List<Green> greens(@Nullable UUID town) {
        List<Green> out = new ArrayList<>();
        if (town == null) return out;
        String s = Ledger.note(town, NOTE);
        if (s == null || s.isEmpty()) return out;
        for (String e : s.split(";")) {
            String[] p = e.split(",");
            if (p.length < 7) continue;
            try {
                out.add(new Green(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                    Integer.parseInt(p[5]), Direction.from2DDataValue(Integer.parseInt(p[6]))));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static void save(UUID town, List<Green> gs) {
        List<String> parts = new ArrayList<>();
        for (Green g : gs) parts.add(g.text());
        Ledger.note(town, NOTE, String.join(";", parts));
    }

    /** The greens as the park's buildings (Park.parks: visited, strolled, sat in). Not in the ledger's buildings. */
    public static List<Ledger.Building> buildings(@Nullable UUID town) {
        List<Ledger.Building> out = new ArrayList<>();
        for (Green g : greens(town)) out.add(new Ledger.Building(g.kind(), g.anchor(), g.facing()));
        return out;
    }

    @Nullable
    static Green green(@Nullable UUID town, BlockPos anchor) {
        for (Green g : greens(town)) if (g.anchor().equals(anchor)) return g;
        return null;
    }

    /** Is this lot of the plan on a green's ground (kept off for anything else)? */
    public static boolean reserved(UUID town, BlockPos heart, TownPlan.Lot lot) {
        List<Green> gs = greens(town);
        if (gs.isEmpty()) return false;
        int[] b = box(lot);
        int x0 = heart.getX() + b[0] - b[2], x1 = heart.getX() + b[0] + b[2], z0 = heart.getZ() + b[1] - b[3], z1 = heart.getZ() + b[1] + b[3];
        for (Green g : gs) if (x1 >= g.x0() && x0 <= g.x1() && z1 >= g.z0() && z0 <= g.z1()) return true;
        return false;
    }

    static int[] box(TownPlan.Lot l) {
        boolean turned = l.back() == TownPlan.EAST || l.back() == TownPlan.WEST;
        return new int[]{ l.x(), l.z(), turned ? l.halfDeep() : l.halfAcross(), turned ? l.halfAcross() : l.halfDeep() };
    }

    // ------------------------------------------------------------------ where: a block of the plan set aside

    /** A block of the plan from the heart along one axis: k-th out, {from, to} (both inclusive), on the + side. */
    static int[] span(int k, int blocks) {
        int from = TownPlan.RING + TownPlan.STREET + k * TownPlan.PERIOD;
        return new int[]{ from, from + blocks * TownPlan.PERIOD - TownPlan.STREET - 1 };
    }

    /**
     * Set a block (or four, for the central park) of the plan aside for a green: the nearest the heart all of whose lots
     * are free and whose ground is level and dry, keeping a dozen lots free for homes. Null if there is none (the board
     * says so).
     */
    @Nullable
    static Green choose(ServerLevel level, Villages.Village v, String kind) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        int blocks = LATE.equals(kind) ? 2 : 1;
        int rings = Math.min(TownPlan.MAX_RINGS - blocks, CityGrowth.rings(id));
        int free = freeLots(id, heart, rings);
        Green best = null;
        int bestNear = Integer.MAX_VALUE;
        for (int kx = 0; kx <= rings; kx++) {
            for (int kz = 0; kz <= rings; kz++) {
                for (int sx : new int[]{ 1, -1 }) {
                    for (int sz : new int[]{ 1, -1 }) {
                        int[] ax = span(kx, blocks), az = span(kz, blocks);
                        int x0 = sx > 0 ? ax[0] : -ax[1], x1 = sx > 0 ? ax[1] : -ax[0];
                        int z0 = sz > 0 ? az[0] : -az[1], z1 = sz > 0 ? az[1] : -az[0];
                        int near = Math.max(Math.min(Math.abs(x0), Math.abs(x1)), Math.min(Math.abs(z0), Math.abs(z1)));
                        if (near >= bestNear) continue;
                        int lots = lotsFree(level, id, heart, x0, z0, x1, z1);
                        if (lots <= 0 || free - lots < KEEP_FREE) continue;
                        int y = levelGround(level, heart.getX() + x0, heart.getZ() + z0, heart.getX() + x1, heart.getZ() + z1);
                        if (y == Integer.MIN_VALUE) continue;
                        Direction facing = Math.abs(x0 + x1) >= Math.abs(z0 + z1) ? (x0 + x1 > 0 ? Direction.WEST : Direction.EAST)
                            : (z0 + z1 > 0 ? Direction.NORTH : Direction.SOUTH);
                        best = new Green(kind, heart.getX() + x0, heart.getZ() + z0, heart.getX() + x1, heart.getZ() + z1, y, facing);
                        bestNear = near;
                    }
                }
            }
        }
        return best;
    }

    /** Lots of the plan out to so many rings not built on, claimed or kept off. */
    static int freeLots(UUID id, BlockPos heart, int rings) {
        int n = 0;
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (l.kind() != TownPlan.Kind.LOT || TownPlan.ringOf(l) > rings) continue;
            int[] b = box(l);
            if (Villages.builtOver(id, b[0], b[1], b[2], b[3]) || Villages.lotKeptOff(id, heart, l)) continue;
            n++;
        }
        return n;
    }

    /** How many lots this box takes, all of them free; 0 if any is built on, claimed or kept off (or none is in it). */
    static int lotsFree(ServerLevel level, UUID id, BlockPos heart, int x0, int z0, int x1, int z1) {
        int n = 0;
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (l.x() < x0 || l.x() > x1 || l.z() < z0 || l.z() > z1) continue;
            if (l.kind() != TownPlan.Kind.LOT) return 0;
            int[] b = box(l);
            if (Villages.builtOver(id, b[0], b[1], b[2], b[3]) || Villages.lotKeptOff(id, heart, l)) return 0;
            n++;
        }
        return n;
    }

    /** The ground's level for a green on this ground (its middle height), or MIN if it is not level (three blocks at most),
     *  wet, or built on (anything but the world's own ground, plants, trees and the plan's streets). */
    static int levelGround(ServerLevel level, int x0, int z0, int x1, int z1) {
        List<Integer> hs = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x += 2) {
            for (int z = z0; z <= z1; z += 2) {
                if (!level.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                int y = top;
                for (; y > top - 16; y--) {
                    BlockState st = level.getBlockState(p.set(x, y, z));
                    if (!st.getFluidState().isEmpty()) return Integer.MIN_VALUE;
                    if (CastleWall.wild(level, p, st)) continue;
                    if (CastleWall.natural(st) || CastleWall.road(st)) break;
                    return Integer.MIN_VALUE;                                    // somebody's work
                }
                hs.add(y);
            }
        }
        if (hs.isEmpty()) return Integer.MIN_VALUE;
        hs.sort(Integer::compare);
        if (hs.get(hs.size() - 1) - hs.get(0) > 3) return Integer.MIN_VALUE;
        return hs.get(hs.size() / 2);
    }

    // ------------------------------------------------------------------ the design

    /** One thing laid: a block; or, with {@code level} set, a column of ground levelled to this height (its top turf). */
    record Put(BlockPos pos, @Nullable BlockState st, boolean ground) {}

    record Design(List<Put> puts, Park.Layout layout, List<BlockPos> water, @Nullable int[] pitch) {}

    private static final Map<String, Design> DESIGNS = new ConcurrentHashMap<>();

    static Design design(Green g) {
        return DESIGNS.computeIfAbsent(g.text(), k -> new Drawer(g).draw());
    }

    /** Draws a green: what goes where, in the order it is laid. */
    private static final class Drawer {
        final Green g;
        final int y;
        final Map<Long, Put> ground = new LinkedHashMap<>();
        final List<Put> water = new ArrayList<>(), paths = new ArrayList<>(), build = new ArrayList<>(), green = new ArrayList<>();
        final Set<Long> taken = new HashSet<>();
        final List<Park.Seat> seats = new ArrayList<>();
        final List<BlockPos> pathCells = new ArrayList<>(), trees = new ArrayList<>(), flowers = new ArrayList<>(), lights = new ArrayList<>(),
            waterCells = new ArrayList<>(), stone = new ArrayList<>();
        int[] pitch;

        Drawer(Green g) {
            this.g = g;
            this.y = g.y();
        }

        int n(int x, int z) {
            return Quarry.noise(x, z, new BlockPos(g.x0(), y, g.z0()));
        }

        boolean free(int x, int z) {
            return g.contains(x, z) && !taken.contains(BlockPos.asLong(x, 0, z));
        }

        void take(int x, int z) {
            taken.add(BlockPos.asLong(x, 0, z));
        }

        void put(List<Put> to, int x, int yy, int z, BlockState st) {
            to.add(new Put(new BlockPos(x, yy, z), st, false));
        }

        Design draw() {
            // The ground: level, its outer ring a step to the land round it at most (Put.ground, worked out where it is laid).
            for (int x = g.x0(); x <= g.x1(); x++) for (int z = g.z0(); z <= g.z1(); z++) {
                boolean edge = x == g.x0() || x == g.x1() || z == g.z0() || z == g.z1();
                ground.put(BlockPos.asLong(x, 0, z), new Put(new BlockPos(x, y, z), edge ? null : Blocks.GRASS_BLOCK.defaultBlockState(), true));
            }
            if (LATE.equals(g.kind())) central(); else park();
            List<Put> all = new ArrayList<>(ground.values());
            all.addAll(water);
            all.addAll(paths);
            all.addAll(build);
            all.addAll(green);
            Park.Layout l = new Park.Layout(g.anchor(), g.facing(), List.copyOf(seats), List.copyOf(pathCells), List.copyOf(trees),
                List.copyOf(flowers), List.copyOf(lights), List.copyOf(waterCells), List.copyOf(stone));
            return new Design(all, l, List.copyOf(waterCells), pitch);
        }

        // -------------------------------------------------- pieces

        void path(int x, int z, BlockState st) {
            if (!g.contains(x, z) || taken.contains(BlockPos.asLong(x, 0, z))) return;
            take(x, z);
            put(paths, x, y, z, st);
            pathCells.add(new BlockPos(x, y, z));
        }

        /** A pond (or lake): an oval dug into the ground, water level with the turf, two deep in its middle; lily pads. */
        void pond(int cx, int cz, int rx, int rz, boolean lilies) {
            for (int x = cx - rx; x <= cx + rx; x++) for (int z = cz - rz; z <= cz + rz; z++) {
                double e = sq((x - cx) / (rx + 0.5)) + sq((z - cz) / (rz + 0.5));
                if (e > 1.0 || !free(x, z)) continue;
                // Never at the green's edge: a rim of turf all round it.
                if (x <= g.x0() + 1 || x >= g.x1() - 1 || z <= g.z0() + 1 || z >= g.z1() - 1) continue;
                take(x, z);
                boolean deep = e < 0.45;
                put(water, x, y - (deep ? 2 : 1), z, Blocks.CLAY.defaultBlockState());
                if (deep) put(water, x, y - 1, z, Blocks.WATER.defaultBlockState());
                put(water, x, y, z, Blocks.WATER.defaultBlockState());
                waterCells.add(new BlockPos(x, y, z));
                if (deep) waterCells.add(new BlockPos(x, y - 1, z));
                if (lilies && (n(x, z) % 9) == 0) put(green, x, y + 1, z, Blocks.LILY_PAD.defaultBlockState());
            }
        }

        /** A footbridge of slabs across water along a line, rails along its sides. */
        void bridge(int x0, int z0, int x1, int z1) {
            int dx = Integer.signum(x1 - x0), dz = Integer.signum(z1 - z0);
            int len = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
            for (int i = 0; i <= len; i++) {
                int x = x0 + dx * i, z = z0 + dz * i;
                BlockState slab = Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
                put(build, x, y + 1, z, slab);
                pathCells.add(new BlockPos(x, y + 1, z).below());
                if (i > 0 && i < len) {
                    put(build, x + dz, y + 2, z + dx, Blocks.SPRUCE_FENCE.defaultBlockState());
                    put(build, x - dz, y + 2, z - dx, Blocks.SPRUCE_FENCE.defaultBlockState());
                    put(build, x + dz, y + 1, z + dx, slab);
                    put(build, x - dz, y + 1, z - dx, slab);
                }
                take(x, z);
            }
        }

        void tree(int x, int z, boolean birch) {
            if (!free(x, z)) return;
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) take(x + a, z + b);
            Block log = birch ? Blocks.BIRCH_LOG : Blocks.OAK_LOG;
            Block leaf = birch ? Blocks.BIRCH_LEAVES : Blocks.OAK_LEAVES;
            int h = 4 + n(x, z) % 2;
            for (int k = 1; k <= h; k++) put(green, x, y + k, z, log.defaultBlockState());
            BlockState lv = leaf.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
            for (int k = h - 1; k <= h + 1; k++) {
                int r = k == h + 1 ? 1 : 2;
                for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) {
                    if (a == 0 && b == 0 && k <= h) continue;
                    if (Math.abs(a) == r && Math.abs(b) == r && (n(x + a, z + b) & 1) == 0) continue;
                    put(green, x + a, y + k, z + b, lv);
                }
            }
            trees.add(new BlockPos(x, y + 1, z));
        }

        void flowerBed(int x0, int z0, int x1, int z1, boolean meadow) {
            Block[] kinds = { Blocks.POPPY, Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.ALLIUM,
                Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.PINK_TULIP, Blocks.WHITE_TULIP, Blocks.LILY_OF_THE_VALLEY, Blocks.BLUE_ORCHID };
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                if (!free(x, z)) continue;
                int r = n(x, z);
                if (meadow && r % 3 != 0) { if (r % 5 == 0) put(green, x, y + 1, z, Blocks.SHORT_GRASS.defaultBlockState()); continue; }
                take(x, z);
                Block f = meadow ? kinds[r % kinds.length] : kinds[(Math.floorDiv(x - x0, 2) + Math.floorDiv(z - z0, 2) * 3) % kinds.length];
                put(green, x, y + 1, z, f.defaultBlockState());
                flowers.add(new BlockPos(x, y + 1, z));
            }
        }

        void bench(int x, int z, Direction looks) {
            if (!free(x, z)) return;
            take(x, z);
            put(build, x, y + 1, z, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, looks.getOpposite()));
            seats.add(new Park.Seat(new BlockPos(x, y + 1, z), looks));
        }

        void lamp(int x, int z) {
            if (!free(x, z)) return;
            take(x, z);
            put(build, x, y + 1, z, Blocks.SPRUCE_FENCE.defaultBlockState());
            put(build, x, y + 2, z, Blocks.SPRUCE_FENCE.defaultBlockState());
            put(build, x, y + 3, z, Blocks.LANTERN.defaultBlockState());
            lights.add(new BlockPos(x, y + 3, z));
        }

        /** A gazebo: five across, a plank floor, posts at its corners, a slab roof with a lantern under it, benches inside. */
        void gazebo(int cx, int cz) {
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) {
                take(cx + a, cz + b);
                put(build, cx + a, y, cz + b, Blocks.OAK_PLANKS.defaultBlockState());
                put(build, cx + a, y + 4, cz + b, Blocks.DARK_OAK_SLAB.defaultBlockState());
                if (Math.abs(a) == 2 && Math.abs(b) == 2) for (int k = 1; k <= 3; k++) put(build, cx + a, y + k, cz + b, Blocks.OAK_FENCE.defaultBlockState());
            }
            put(build, cx, y + 5, cz, Blocks.DARK_OAK_PLANKS.defaultBlockState());
            put(build, cx, y + 3, cz, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
            lights.add(new BlockPos(cx, y + 3, cz));
            for (int a = -1; a <= 1; a++) {
                put(build, cx + a, y + 1, cz - 2, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
                seats.add(new Park.Seat(new BlockPos(cx + a, y + 1, cz - 2), Direction.SOUTH));
            }
            stone.add(new BlockPos(cx, y, cz));
        }

        /** A bandstand: a raised floor of stone seven across with steps up, posts, a spruce roof. */
        void bandstand(int cx, int cz, Direction front) {
            for (int a = -3; a <= 3; a++) for (int b = -3; b <= 3; b++) {
                if (Math.abs(a) == 3 && Math.abs(b) == 3) continue;
                take(cx + a, cz + b);
                put(build, cx + a, y + 1, cz + b, Blocks.STONE_BRICKS.defaultBlockState());
                stone.add(new BlockPos(cx + a, y + 1, cz + b));
                put(build, cx + a, y + 5, cz + b, Blocks.SPRUCE_SLAB.defaultBlockState());
                boolean post = (Math.abs(a) == 3 && Math.abs(b) == 2) || (Math.abs(a) == 2 && Math.abs(b) == 3);
                if (post) for (int k = 2; k <= 4; k++) put(build, cx + a, y + k, cz + b, Blocks.SPRUCE_FENCE.defaultBlockState());
            }
            put(build, cx, y + 6, cz, Blocks.SPRUCE_PLANKS.defaultBlockState());
            put(build, cx, y + 4, cz, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
            lights.add(new BlockPos(cx, y + 4, cz));
            // The steps up at its front, and a note block or two for the band.
            BlockPos st = new BlockPos(cx, y + 1, cz).relative(front, 4);
            take(st.getX(), st.getZ());
            put(build, st.getX(), y + 1, st.getZ(), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, front.getOpposite()));
            put(build, cx - 1, y + 2, cz, Blocks.NOTE_BLOCK.defaultBlockState());
            put(build, cx + 1, y + 2, cz, Blocks.NOTE_BLOCK.defaultBlockState());
        }

        /** A statue on its plinth: a figure of stone (a column with arms and a head) over a carved base. */
        void statue(int cx, int cz, Direction facing) {
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) take(cx + a, cz + b);
            put(build, cx, y + 1, cz, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            put(build, cx, y + 2, cz, Blocks.POLISHED_ANDESITE.defaultBlockState());
            put(build, cx, y + 3, cz, Blocks.ANDESITE_WALL.defaultBlockState());
            Direction side = facing.getClockWise();
            put(build, cx + side.getStepX(), y + 3, cz + side.getStepZ(), Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ButtonBlock.FACING, side).setValue(net.minecraft.world.level.block.ButtonBlock.FACE,
                    net.minecraft.world.level.block.state.properties.AttachFace.WALL));
            put(build, cx - side.getStepX(), y + 3, cz - side.getStepZ(), Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ButtonBlock.FACING, side.getOpposite()).setValue(net.minecraft.world.level.block.ButtonBlock.FACE,
                    net.minecraft.world.level.block.state.properties.AttachFace.WALL));
            put(build, cx, y + 4, cz, Blocks.SKELETON_SKULL.defaultBlockState().setValue(net.minecraft.world.level.block.SkullBlock.ROTATION,
                net.minecraft.world.level.block.state.properties.RotationSegment.convertToSegment(facing)));
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) if (a != 0 || b != 0) put(paths, cx + a, y, cz + b, Blocks.POLISHED_ANDESITE.defaultBlockState());
            stone.add(new BlockPos(cx, y + 1, cz));
        }

        /** A fountain plaza: a square of stone, a basin with water level with its rim's foot, a pillar and a lantern. */
        void plaza(int cx, int cz, int r) {
            for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) {
                if (!g.contains(cx + a, cz + b)) continue;
                take(cx + a, cz + b);
                put(Math.max(Math.abs(a), Math.abs(b)) <= 2 ? water : paths, cx + a, y, cz + b,
                    (Math.abs(a) + Math.abs(b)) % 2 == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState());
                pathCells.add(new BlockPos(cx + a, y, cz + b));
            }
            put(water, cx, y + 1, cz, Blocks.STONE_BRICK_WALL.defaultBlockState());   // the pillar first: the basin's water holds round it
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) {
                boolean rim = Math.max(Math.abs(a), Math.abs(b)) == 2;
                if (rim) { put(water, cx + a, y + 1, cz + b, Blocks.STONE_BRICKS.defaultBlockState()); stone.add(new BlockPos(cx + a, y + 1, cz + b)); }
                else if (a == 0 && b == 0) {
                    put(build, cx, y + 2, cz, Blocks.STONE_BRICK_WALL.defaultBlockState());
                    put(build, cx, y + 3, cz, Blocks.LANTERN.defaultBlockState());
                    lights.add(new BlockPos(cx, y + 3, cz));
                } else {
                    put(water, cx + a, y + 1, cz + b, Blocks.WATER.defaultBlockState());
                    waterCells.add(new BlockPos(cx + a, y + 1, cz + b));
                }
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos s = new BlockPos(cx, y, cz).relative(d, 3);
                put(build, s.getX(), y + 1, s.getZ(), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, d));
                seats.add(new Park.Seat(s.above(), d.getOpposite()));
            }
        }

        /** The sports green: level turf with its lines marked (white carpet), a goal at each end. Left for the matches. */
        void pitch(int x0, int z0, int x1, int z1) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                if (!g.contains(x, z)) continue;
                take(x, z);
                boolean line = x == x0 || x == x1 || z == z0 || z == z1 || x == (x0 + x1) / 2;
                if (line) put(green, x, y + 1, z, Blocks.WHITE_CARPET.defaultBlockState());
            }
            int mz = (z0 + z1) / 2;
            for (int[] end : new int[][]{ { x0 - 1, 1 }, { x1 + 1, -1 } }) {
                int x = end[0];
                if (!g.contains(x, mz)) continue;
                for (int dz = -1; dz <= 1; dz++) take(x, mz + dz);
                put(build, x, y + 1, mz - 1, Blocks.BIRCH_FENCE.defaultBlockState());
                put(build, x, y + 1, mz + 1, Blocks.BIRCH_FENCE.defaultBlockState());
                put(build, x, y + 2, mz - 1, Blocks.BIRCH_SLAB.defaultBlockState());
                put(build, x, y + 2, mz, Blocks.BIRCH_SLAB.defaultBlockState());
                put(build, x, y + 2, mz + 1, Blocks.BIRCH_SLAB.defaultBlockState());
            }
            pitch = new int[]{ x0, z0, x1, z1 };
        }

        void picnic(int x, int z, boolean red) {
            if (!free(x, z) || !free(x + 1, z + 1)) return;
            for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) {
                take(x + a, z + b);
                put(green, x + a, y + 1, z + b, ((a + b) % 2 == 0) == red ? Blocks.RED_CARPET.defaultBlockState() : Blocks.WHITE_CARPET.defaultBlockState());
            }
            pathCells.add(new BlockPos(x, y, z));
        }

        void boathouse(int cx, int cz, Direction toWater) {
            Direction side = toWater.getClockWise();
            for (int a = -2; a <= 2; a++) for (int b = 0; b <= 3; b++) {
                BlockPos p = new BlockPos(cx, y, cz).relative(side, a).relative(toWater, b);
                take(p.getX(), p.getZ());
                boolean wall = Math.abs(a) == 2 || b == 0;
                put(build, p.getX(), y, p.getZ(), Blocks.SPRUCE_PLANKS.defaultBlockState());
                if (wall && !(b == 0 && a == 0)) for (int k = 1; k <= 3; k++) put(build, p.getX(), y + k, p.getZ(), Blocks.SPRUCE_PLANKS.defaultBlockState());
                put(build, p.getX(), y + 4, p.getZ(), Blocks.SPRUCE_SLAB.defaultBlockState());
            }
            BlockPos lamp = new BlockPos(cx, y + 3, cz).relative(toWater, 2);
            put(build, lamp.getX(), lamp.getY(), lamp.getZ(), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
            lights.add(lamp);
            BlockPos seat = new BlockPos(cx, y + 1, cz).relative(toWater, 1).relative(side, 1);
            put(build, seat.getX(), seat.getY(), seat.getZ(), Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, toWater.getOpposite()));
            seats.add(new Park.Seat(seat, toWater));
        }

        // -------------------------------------------------- the two plans

        /** A park a block across: a winding path, a pond with a bridge, a meadow, a grove, benches, a gazebo or bandstand, lamps. */
        void park() {
            int w = g.w(), d = g.d();
            boolean band = (n(g.x0(), g.z0()) & 1) == 0;
            int mz = g.z0() + d / 2;
            // The winding path, west to east, two wide.
            int pondX = g.x0() + w / 2, pondZ = mz;
            for (int x = g.x0(); x <= g.x1(); x++) {
                int z = mz + (int) Math.round(Math.sin((x - g.x0()) * Math.PI * 2 / w) * (d / 4.0));
                boolean overPond = Math.abs(x - pondX) <= 3;
                if (overPond) continue;
                path(x, z, Blocks.DIRT_PATH.defaultBlockState());
                path(x, z + 1, Blocks.DIRT_PATH.defaultBlockState());
                if ((x - g.x0()) % 6 == 3) { bench(x, z + 2, Direction.NORTH); lamp(x, z - 1); }
            }
            pond(pondX, pondZ, 3, 3, true);
            bridge(pondX - 4, pondZ, pondX + 4, pondZ);
            if (band) bandstand(g.x0() + 5, g.z1() - 5, Direction.NORTH); else gazebo(g.x0() + 4, g.z1() - 4);
            tree(g.x1() - 3, g.z0() + 3, false);
            tree(g.x1() - 7, g.z0() + 4, true);
            tree(g.x1() - 4, g.z0() + 8, false);
            flowerBed(g.x0() + 1, g.z0() + 1, g.x0() + w / 2 - 4, g.z0() + d / 2 - 4, true);
            flowerBed(g.x1() - 9, g.z1() - 7, g.x1() - 1, g.z1() - 1, true);
            lamp(g.x0() + 1, g.z0() + 1);
            lamp(g.x1() - 1, g.z1() - 1);
            picnic(g.x1() - 8, g.z1() - 9, true);
        }

        /** The central park, four blocks across: a loop, two tree-lined avenues crossing at a fountain plaza, a lake with a
         *  boathouse and a bridge, flower gardens with statues and a gazebo, lawns with a grove, picnics and the bandstand, a
         *  sports green with its pitch marked, lamps all round. */
        void central() {
            int cx = (g.x0() + g.x1()) / 2, cz = (g.z0() + g.z1()) / 2;
            plaza(cx, cz, 4);
            // The avenues: gravel, three wide, trees along both sides every five.
            for (int x = g.x0(); x <= g.x1(); x++) for (int k = -1; k <= 1; k++) path(x, cz + k, Blocks.GRAVEL.defaultBlockState());
            for (int z = g.z0(); z <= g.z1(); z++) for (int k = -1; k <= 1; k++) path(cx + k, z, Blocks.GRAVEL.defaultBlockState());
            for (int i = 6; i < Math.max(g.w(), g.d()); i += 5) {
                for (int s : new int[]{ -3, 3 }) {
                    if (g.x0() + i < cx - 6 || g.x0() + i > cx + 6) tree(g.x0() + i, cz + s, false);
                    if (g.z0() + i < cz - 6 || g.z0() + i > cz + 6) tree(cx + s, g.z0() + i, false);
                }
            }
            // The walking loop: two wide, three in from the edge, its corners cut.
            for (int x = g.x0() + 3; x <= g.x1() - 3; x++) for (int z = g.z0() + 3; z <= g.z1() - 3; z++) {
                int ex = Math.min(x - g.x0() - 3, g.x1() - 3 - x), ez = Math.min(z - g.z0() - 3, g.z1() - 3 - z);
                if (Math.min(ex, ez) <= 1 && ex + ez >= 2) path(x, z, Blocks.DIRT_PATH.defaultBlockState());
            }
            for (int x = g.x0() + 3; x <= g.x1() - 3; x += 8) { lamp(x, g.z0() + 2); lamp(x, g.z1() - 2); }
            for (int z = g.z0() + 7; z <= g.z1() - 7; z += 8) { lamp(g.x0() + 2, z); lamp(g.x1() - 2, z); }
            int qx = (cx - g.x0()) / 2, qz = (cz - g.z0()) / 2;
            // North-west: the lake, its boathouse and a bridge across its middle.
            int lx = g.x0() + qx + 1, lz = g.z0() + qz + 1;
            pond(lx, lz, qx - 4, qz - 4, true);
            bridge(lx, g.z0() + 5, lx, cz - 3);
            boathouse(g.x0() + 6, lz + 2, Direction.EAST);
            // North-east: the flower gardens, two statues, the gazebo.
            int fx = cx + qx + 1, fz = g.z0() + qz + 1;
            flowerBed(cx + 4, g.z0() + 6, g.x1() - 6, cz - 4, false);
            statue(fx - 5, fz, Direction.WEST);
            statue(fx + 5, fz, Direction.EAST);
            gazebo(fx, fz);
            // South-west: lawns, a grove, picnics, the bandstand.
            int sx = g.x0() + qx + 1, sz = cz + qz + 1;
            bandstand(sx + 3, sz + 2, Direction.NORTH);
            tree(g.x0() + 6, cz + 5, true);
            tree(g.x0() + 10, cz + 7, false);
            tree(g.x0() + 6, g.z1() - 6, false);
            tree(sx - 4, g.z1() - 5, true);
            picnic(sx - 6, sz - 6, true);
            picnic(sx + 6, sz - 7, false);
            picnic(sx - 3, g.z1() - 8, true);
            // South-east: the sports green.
            int px = cx + qx + 1, pz = cz + qz + 1;
            pitch(px - 8, pz - 5, px + 8, pz + 5);
            // Benches along the avenues, facing them.
            for (int i = 8; i < Math.max(g.w(), g.d()); i += 9) {
                bench(g.x0() + i, cz - 2, Direction.SOUTH);
                bench(g.x0() + i, cz + 2, Direction.NORTH);
                bench(cx - 2, g.z0() + i, Direction.EAST);
                bench(cx + 2, g.z0() + i, Direction.WEST);
            }
        }
    }

    static double sq(double a) {
        return a * a;
    }

    // ------------------------------------------------------------------ laid out

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>(), LOOKED = new ConcurrentHashMap<>();

    static String doneKey(Green g) {
        return "greens.done." + g.x0() + "." + g.z0();
    }

    static int done(UUID town, Green g) {
        try {
            String s = Ledger.note(town, doneKey(g));
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean finished(UUID town, Green g) {
        return done(town, g) >= design(g).puts().size();
    }

    /** From the town's round: a green chosen when its tier wants one; the next few blocks of one being laid; the water kept. */
    public static void tick(ServerLevel level, Villages.Village v) {
        if (standDown) return;
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = LAST.get(id);
        if (last != null && now - last < 20L && now >= last) return;
        LAST.put(id, now);
        List<Green> gs = greens(id);
        int t = tier(id);
        long mids = gs.stream().filter(x -> MID.equals(x.kind())).count();
        boolean late = gs.stream().anyMatch(x -> LATE.equals(x.kind()));
        String want = t >= 3 && !late ? LATE : t >= 1 && mids < Math.min(2, t) ? MID : null;
        Long looked = LOOKED.get(id);
        if (want != null && (looked == null || now - looked >= 600L || now < looked)) {
            LOOKED.put(id, now);
            Green g = choose(level, v, want);
            if (g != null) {
                gs.add(g);
                save(id, gs);
                long day = level.getDayTime() / 24000L;
                Villages.tell(id, day, LATE.equals(want) ? "the council set four blocks of the plan aside for a central park, " + g.w() + " by " + g.d()
                    : "the council set a block of the plan aside for a park, " + g.w() + " by " + g.d());
                FeatHints.hint(level, id, v.centre(), "greatpark" + want, "New: " + Villages.name(id) + " is laying out "
                    + (LATE.equals(want) ? "a central park" : "a new park") + ". The board's Parks page shows it.");
            }
        }
        for (Green g : gs) {
            if (!level.isLoaded(new BlockPos(g.x0(), g.y(), g.z0())) || !level.isLoaded(new BlockPos(g.x1(), g.y(), g.z1()))) continue;
            if (!finished(id, g)) {
                lay(level, id, g, A_ROUND);
                break;
            }
        }
        if (now % 100L < 20L) for (Green g : gs) contain(level, id, g);
    }

    /** So many more of a green's blocks laid; returns how many were. */
    static int lay(ServerLevel level, UUID town, Green g, int budget) {
        Design d = design(g);
        int at = done(town, g), n = 0;
        while (at < d.puts().size() && n < budget) {
            Put p = d.puts().get(at++);
            n++;
            if (p.ground()) levelColumn(level, g, p.pos());
            else place(level, p.pos(), p.st());
        }
        Ledger.note(town, doneKey(g), Integer.toString(at));
        if (at >= d.puts().size()) {
            Villages.tell(town, level.getDayTime() / 24000L, (LATE.equals(g.kind()) ? "the central park" : "a new park") + " was opened, " + g.w() + " by " + g.d());
        }
        return n;
    }

    private static void place(ServerLevel level, BlockPos p, BlockState st) {
        BlockState was = level.getBlockState(p);
        if (st.is(Blocks.WATER)) {
            // Water only where it will stand: a floor under it, and every side water, ground or another of its cells.
            if (!holds(level, p)) return;
        }
        if (was.equals(st)) return;
        level.setBlock(p, st, 2 | 16);
    }

    /** Would water here stay put: solid or water under it, and solid or water on every side? */
    static boolean holds(ServerLevel level, BlockPos p) {
        BlockState under = level.getBlockState(p.below());
        if (!(under.isSolid() || !under.getFluidState().isEmpty())) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState s = level.getBlockState(p.relative(d));
            if (!s.isSolid() && s.getFluidState().isEmpty() && !s.is(Blocks.LILY_PAD)) return false;
        }
        return true;
    }

    /** A column of the green levelled: everything wild over it cleared, its ground made up or cut down to the level (the
     *  outer ring only a step from the land round it), turf on top. */
    static void levelColumn(ServerLevel level, Green g, BlockPos at) {
        int x = at.getX(), z = at.getZ(), y = g.y();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int ground = top;
        for (; ground > y - 12; ground--) {
            BlockState st = level.getBlockState(p.set(x, ground, z));
            if (!CastleWall.wild(level, p, st) && st.getFluidState().isEmpty()) break;
        }
        boolean edge = x == g.x0() || x == g.x1() || z == g.z0() || z == g.z1();
        int want = edge ? Math.max(y - 1, Math.min(y + 1, ground)) : y;
        for (int yy = Math.max(ground, want) + 1; yy <= Math.max(top, want) + 8; yy++) {
            BlockState st = level.getBlockState(p.set(x, yy, z));
            if (!st.isAir() && (CastleWall.wild(level, p, st) || CastleWall.natural(st) || CastleWall.road(st))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        for (int yy = want + 1; yy <= ground; yy++) level.setBlock(p.set(x, yy, z), Blocks.AIR.defaultBlockState(), 2 | 16);
        for (int yy = ground + 1; yy < want; yy++) level.setBlock(p.set(x, yy, z), Blocks.DIRT.defaultBlockState(), 2 | 16);
        level.setBlock(p.set(x, want, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
    }

    /** Every water cell of a green whose rim or floor has a gap: the gap stopped with stone (mossy cobble), so it never runs. */
    static int contain(ServerLevel level, UUID town, Green g) {
        int fixed = 0;
        Design d = design(g);
        Set<BlockPos> cells = new HashSet<>(d.water());
        for (BlockPos w : d.water()) {
            if (level.getFluidState(w).isEmpty()) continue;
            for (Direction dir : new Direction[]{ Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST }) {
                BlockPos n = w.relative(dir);
                if (cells.contains(n)) continue;
                BlockState s = level.getBlockState(n);
                if (s.isSolid() || s.is(Blocks.LILY_PAD) && dir != Direction.DOWN) continue;
                if (!s.getFluidState().isEmpty() && !cells.contains(n)) {
                    // Water that got out: taken up, the gap stopped.
                    level.setBlock(n, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
                } else {
                    level.setBlock(n, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
                }
                fixed++;
            }
        }
        return fixed;
    }

    /** A block of a green's rim or floor broken: that green's water taken up first, so none runs out of the gap; the round
     *  stops the gap and the water is laid again only where it holds. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE);
        if (v == null) return;
        for (Green g : greens(v.id())) {
            if (!g.contains(pos.getX(), pos.getZ())) continue;
            Design d = design(g);
            boolean rim = false;
            for (BlockPos w : d.water()) if (w.distManhattan(pos) == 1) { rim = true; break; }
            if (!rim) continue;
            for (BlockPos w : d.water()) {
                if (!level.getFluidState(w).isEmpty() && level.getBlockState(w).is(Blocks.WATER)) level.setBlock(w, Blocks.AIR.defaultBlockState(), 3);
            }
            // Laid again from its water on (the next rounds), once the gap is stopped.
            int first = d.puts().size();
            for (int i = 0; i < d.puts().size(); i++) if (d.puts().get(i).st() != null && d.puts().get(i).st().is(Blocks.WATER)) { first = i; break; }
            Ledger.note(v.id(), doneKey(g), Integer.toString(Math.min(done(v.id(), g), first)));
        }
    }

    // ------------------------------------------------------------------ for the park's visits and the board

    @Nullable
    public static Park.Layout layout(Ledger.Building b) {
        for (Villages.Village v : Villages.every()) {
            Green g = green(v.id(), b.anchor());
            if (g != null) return design(g).layout();
        }
        return null;
    }

    /** The bounds of a green whose layout this is {x0, z0, x1, z1}, or null for a blueprint park. */
    @Nullable
    public static int[] bounds(Park.Layout l) {
        for (Map.Entry<String, Design> e : DESIGNS.entrySet()) {
            if (e.getValue().layout() != l) continue;
            String[] p = e.getKey().split(",");
            return new int[]{ Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]) };
        }
        return null;
    }

    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag c = new CompoundTag();
        UUID id = v.id();
        c.putInt("tier", tier(id));
        c.putInt("folk", folk(id));
        ListTag list = new ListTag();
        for (Green g : greens(id)) {
            CompoundTag t = new CompoundTag();
            t.putString("kind", LATE.equals(g.kind()) ? "Central park" : "Park");
            t.putInt("w", g.w());
            t.putInt("d", g.d());
            BlockPos a = g.anchor();
            t.putIntArray("at", new int[]{ a.getX(), a.getY(), a.getZ() });
            t.putInt("dist", (int) Math.round(Math.sqrt(a.distSqr(v.centre()))));
            int total = design(g).puts().size();
            t.putInt("pct", total == 0 ? 100 : Math.min(100, done(id, g) * 100 / total));
            t.putString("has", LATE.equals(g.kind()) ? "a lake with a boathouse and a bridge, tree-lined avenues, a fountain plaza, flower gardens with statues,"
                + " a gazebo, a bandstand, picnic lawns, a walking loop, the sports green (its pitch marked) and lamps all round"
                : "a winding path, a pond with lily pads and a bridge, a flower meadow, a grove, benches, " + (design(g).layout().stone().size() > 10 ? "a bandstand" : "a gazebo") + " and lamps");
            int there = 0;
            for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(g.x0(), g.y() - 2, g.z0(), g.x1() + 1, g.y() + 6, g.z1() + 1))) there++;
            t.putInt("there", there);
            list.add(t);
        }
        c.put("great", list);
        int t = tier(id);
        boolean late = greens(id).stream().anyMatch(x -> LATE.equals(x.kind()));
        String next = t < 1 ? "A park a block across comes at " + MID_FOLK + " folk (it has " + folk(id) + ")."
            : t < 2 ? "Another park at " + MID_MORE + " folk; the central park at " + LATE_FOLK + "."
            : !late && t < 3 ? "The central park, four blocks of the plan, at " + LATE_FOLK + " folk (it has " + folk(id) + ")."
            : !late ? "The central park is wanted: the council is looking for four free blocks of the plan (never the lots the homes need)."
            : "All the town's greens are planned.";
        c.putString("next", next);
        return c;
    }

    // ------------------------------------------------------------------ the tests

    @Nullable
    public static Green chooseForTests(ServerLevel level, Villages.Village v, String kind) {
        return choose(level, v, kind);
    }

    public static void addForTests(UUID town, Green g) {
        List<Green> gs = greens(town);
        gs.add(g);
        save(town, gs);
    }

    public static void layAllForTests(ServerLevel level, UUID town) {
        for (Green g : greens(town)) while (!finished(town, g)) lay(level, town, g, 4000);
    }

    public static void tickForTests(ServerLevel level, Villages.Village v) {
        LAST.remove(v.id());
        LOOKED.remove(v.id());
        tick(level, v);
    }

    public static List<BlockPos> waterForTests(Green g) {
        return design(g).water();
    }

    @Nullable
    public static int[] pitchForTests(Green g) {
        return design(g).pitch();
    }

    public static Park.Layout layoutForTests(Green g) {
        return design(g).layout();
    }
}
