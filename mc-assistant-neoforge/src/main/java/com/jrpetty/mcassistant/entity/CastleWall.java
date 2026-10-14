package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import com.jrpetty.mcassistant.village.WallPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [defence] The town wall: a castle's curtain wall round the whole town, planned with the town plan (WallPlan),
 * built out of the stores a section at a time by the town's hands, kept, upgraded and moved out as the town grows.
 *
 * <h2>The plan</h2>
 * Every building the town has put up on its plan (or taken a lot for) is inside it; its fields, pens, mines and woods
 * may stand outside. The wall's ring (seven deep from its inner face: the wall and its towers) is kept off: no lot on
 * it is offered (Villages.siteFor), and the first row of fields keeps off it too. The plan is kept with the world
 * (Ledger "castle.sides").
 *
 * <h2>The wall</h2>
 * Three thick, five high in the Wood and Stone Ages, six in the Iron Age, seven after; a walkway two wide along its
 * top, battlements on its outer face, a lantern on every fourth merlon and a torch on its inner face every eight
 * blocks over the street. A tower seven square at each corner and every thirty blocks or so, three higher than the
 * walkway, crenellated, a lantern on top: a ladder inside from the street to the walkway (the walkway runs through
 * the tower) and another on up to its top. On each avenue a gatehouse: a gate as wide as the avenue between two
 * flanking towers, its doors shut by the watch at dusk and when the bell rings (folk open them to pass, monsters
 * can't). Where a paved cross street runs out through it, a postern with doors under an arch. A flight of steps up
 * to the walkway from inside each side of each gate. The ground is followed: the walkway never steps more than a
 * block at a time, and nothing is built over water, a field, a road or anything somebody put up (the wall leaves a
 * gap there rather).
 *
 * <h2>By age</h2>
 * A log palisade with plank walks in the Wood Age; cobblestone in the Stone Age (mossy here and there); stone bricks
 * with mossy and cracked ones and smooth stone caps in the Iron Age; deepslate bricks and polished deepslate in the
 * Diamond Age; deepslate tiles and chiselled deepslate after. Every block out of the stores, none from nothing:
 * short of the age's stone, the wall goes up in the last age's and is refaced when the stone comes (old stone back
 * into the stores), as the town's houses are renovated. As the town comes of age the wall grows a block higher.
 *
 * <h2>Built</h2>
 * Once the town has its stores and its homes and is fed (a grown town of eight or more, the storehouse up, no
 * hunger, beds made), a hand walks out to the wall and lays a dozen blocks at a time (TownJobs: never more than the
 * town can spare, by day, never in an alarm), the stores keeping back enough for the builders. A section at a time:
 * the gatehouses first, then the corner towers, the towers between and the curtain. Looked over every couple of
 * minutes, so whatever a creeper or a siege knocks out is put back.
 *
 * <h2>Moved out</h2>
 * When the town takes a lot past the wall, that side's line moves out a ring: the new section goes up, and once the
 * new wall stands the old section comes down (its stone into the stores) and its line goes back to the lots.
 */
public final class CastleWall {

    private CastleWall() {}

    static final String SIDES = "castle.sides", OLD = "castle.old", FIT = "castle.fit/", BEGUN = "castle.begun";
    /** How often the works on the wall are seen to (ticks), and the whole wall looked over. */
    static final long EVERY = 100L, SURVEY = 2400L;
    /** Blocks a hand lays at a time; what of each stone the stores keep back for the builders. */
    static final int BATCH = 12, KEEP = 48;
    /** Laying at once (NOW), how often the stores are counted again, so their keep-back is held. */
    static final int RECOUNT = 32;
    /** A run of curtain built as one section. */
    static final int RUN = 16;

    // ------------------------------------------------------------------ the blocks, by age

    /** What a block of the wall is. */
    enum Role { BODY, ACCENT, CAP, MERLON, STAIR, ARCH, LADDER, LANTERN, TORCH, DOOR, AIR }

    /**
     * One age's stone for the wall: its body; its accent (the base course along the foot, and a weathered stone here
     * and there in the faces), with a second, worn one if the age has it; its coping (the walkway's caps and the
     * line along the battlements' foot); and its steps (the flights up, the arch shoulders, the towers' corbels).
     */
    record Palette(Block body, Block accent, @Nullable Block worn, Block cap, Block steps) {
        boolean has(BlockState st) {
            return st.is(body) || st.is(accent) || worn != null && st.is(worn) || st.is(cap);
        }
    }

    /** The wall's stone by age, Wood to Nether: a log palisade with plank walks; cobblestone; stone bricks with mossy
     *  and cracked ones and smooth stone coping; deepslate bricks and polished deepslate; deepslate tiles and chiselled
     *  deepslate. No earth, nothing precious, nothing of the Nether. */
    static final List<Palette> PALETTES = List.of(
        new Palette(Blocks.OAK_LOG, Blocks.OAK_LOG, null, Blocks.OAK_PLANKS, Blocks.OAK_STAIRS),
        new Palette(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, null, Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS),
        new Palette(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.STONE_BRICK_STAIRS),
        new Palette(Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS, null, Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_BRICK_STAIRS),
        new Palette(Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES, null, Blocks.CHISELED_DEEPSLATE, Blocks.DEEPSLATE_TILE_STAIRS));

    /** The palisade's age: its stone is any log, its caps any planks. */
    static final int WOOD = 0;
    /** Of the stones in the wall's faces, one in this many is the accent (weathered, mossy). */
    static final int ACCENT_EVERY = 9;
    /** The base course: this many rows of the accent stone along the foot of the outer face and the towers. */
    static final int PLINTH = 1;

    /** The age's stone: Wood 0 to Nether 4. */
    static int tier(Villages.Age age) {
        return age.ordinal();
    }

    /** Which age's stone a block of the wall is (-1: none of the wall's). Logs and planks are the palisade's. */
    static int tierOf(BlockState st) {
        if (st.is(BlockTags.LOGS) || st.is(BlockTags.PLANKS)) return WOOD;
        for (int t = WOOD + 1; t < PALETTES.size(); t++) if (PALETTES.get(t).has(st)) return t;
        return -1;
    }

    /** Which age's steps these are (-1: none of the wall's). */
    static int stepsTier(BlockState st) {
        if (st.is(BlockTags.WOODEN_STAIRS)) return WOOD;
        for (int t = WOOD + 1; t < PALETTES.size(); t++) if (st.is(PALETTES.get(t).steps())) return t;
        return -1;
    }

    /** The wall's fittings: what it hangs and sets on itself besides stone. */
    static boolean fitting(BlockState st) {
        return st.is(Blocks.LADDER) || st.is(Blocks.LANTERN) || st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)
            || st.getBlock() instanceof DoorBlock && st.is(BlockTags.WOODEN_DOORS) || stepsTier(st) >= 0;
    }

    /** What the stores pay for a block of this role in this age's stone. */
    static Predicate<ItemStack> paid(int tier, Role role) {
        if (tier == WOOD) {
            if (role == Role.CAP || role == Role.STAIR || role == Role.ARCH) return s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS);
            return s -> s.is(ItemTags.LOGS) && !s.getItem().toString().contains("stripped");
        }
        Palette p = PALETTES.get(tier);
        return switch (role) {
            case ACCENT -> s -> s.is(p.accent().asItem()) || p.worn() != null && s.is(p.worn().asItem()) || s.is(p.body().asItem());
            case CAP -> s -> s.is(p.cap().asItem()) || s.is(p.body().asItem());
            default -> s -> s.is(p.body().asItem());
        };
    }

    /** The block a paid-for item makes in this role. */
    static BlockState made(int tier, Role role, ItemStack paid, Direction facing, boolean top) {
        if (role == Role.STAIR || role == Role.ARCH) {
            return PALETTES.get(tier).steps().defaultBlockState().setValue(StairBlock.FACING, facing)
                .setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
        }
        BlockState st = paid.getItem() instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : PALETTES.get(tier).body().defaultBlockState();
        if (tier == WOOD && role == Role.CAP && st.is(BlockTags.LOGS) && st.hasProperty(RotatedPillarBlock.AXIS)) {
            return st.setValue(RotatedPillarBlock.AXIS, facing.getAxis());     // a log laid along the walk
        }
        return st;
    }

    // ------------------------------------------------------------------ the plan, kept with the world

    private static final Set<String> NOT_IN_TOWN = Set.of("fortify", "colony", "pen", "lighthouse", Divers.SHED, "court");

    /** The town's buildings as boxes on the plan ({x, z, halfX, halfZ} from the heart): what the wall must enclose. */
    static List<int[]> boxes(UUID id) {
        Villages.Village v = Villages.get(id);
        List<int[]> out = new ArrayList<>();
        if (v == null) return out;
        List<BlockPos> at = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(id)) if (!NOT_IN_TOWN.contains(b.structure())) at.add(b.anchor());
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(id).entrySet()) {
            if (!NOT_IN_TOWN.contains(e.getKey())) at.add(e.getValue().anchor());
        }
        for (BlockPos p : at) {
            int dx = p.getX() - v.centre().getX(), dz = p.getZ() - v.centre().getZ();
            if (Math.max(Math.abs(dx), Math.abs(dz)) > TownPlan.reach()) continue;     // out past the plan: not the town proper
            int[] box = null;
            for (TownPlan.Lot l : TownPlan.lots()) {
                int[] b = box(l);
                if (Math.abs(dx - b[0]) <= b[2] && Math.abs(dz - b[1]) <= b[3]) { box = b; break; }
            }
            out.add(box != null ? box : new int[]{ dx, dz, 3, 3 });
        }
        return out;
    }

    static int[] box(TownPlan.Lot l) {
        boolean turned = l.back() == TownPlan.EAST || l.back() == TownPlan.WEST;
        return new int[]{ l.x(), l.z(), turned ? l.halfDeep() : l.halfAcross(), turned ? l.halfAcross() : l.halfDeep() };
    }

    /** The wall's plan as it stands: kept with the world, or the first wall if none was drawn yet. */
    public static WallPlan.Sides sides(UUID id) {
        WallPlan.Sides s = WallPlan.Sides.decode(Ledger.note(id, SIDES));
        return s != null ? s : WallPlan.around(boxes(id));
    }

    /** The old line still standing where the wall has been moved out, or null. */
    @Nullable
    static WallPlan.Sides old(UUID id) {
        return WallPlan.Sides.decode(Ledger.note(id, OLD));
    }

    /**
     * Draw the wall again round what the town has now: any side the town has grown past moves out a ring (never in).
     * Returns whether it moved.
     */
    static boolean replan(ServerLevel level, UUID id) {
        WallPlan.Sides now = WallPlan.Sides.decode(Ledger.note(id, SIDES));
        WallPlan.Sides want = WallPlan.around(boxes(id));
        if (now == null) {
            Ledger.note(id, SIDES, want.encode());
            return false;
        }
        WallPlan.Sides grown = now.max(want);
        if (grown.equals(now)) return false;
        if (old(id) == null && Ledger.note(id, BEGUN) != null) Ledger.note(id, OLD, now.encode());   // the old line, to come down
        Ledger.note(id, SIDES, grown.encode());
        STATES.remove(id);
        List<String> ways = new ArrayList<>();
        String[] names = { "north", "east", "south", "west" };
        for (int side = 0; side < 4; side++) if (grown.of(side) > now.of(side)) ways.add(names[side]);
        Villages.tell(id, level.getDayTime() / 24000L, "the town has grown past its wall, so the wall is to go out further to the "
            + String.join(" and ", ways));
        return true;
    }

    /** Is a lot of the plan on the wall's ring (its own ground and its towers', or the old line still standing)? */
    public static boolean keptOff(UUID id, TownPlan.Lot lot) {
        if (!live() || lot.kind() == TownPlan.Kind.SQUARE) return false;
        int[] b = box(lot);
        return onRing(id, b[0], b[1], b[2], b[3]);
    }

    /** Is this box (an offset from the heart and its half-widths) on the wall's ring? */
    public static boolean onRing(UUID id, int dx, int dz, int hx, int hz) {
        if (!live()) return false;
        WallPlan.Sides s = sides(id);
        if (WallPlan.onRing(s, dx, dz, hx, hz)) return true;
        WallPlan.Sides o = old(id);
        return o != null && WallPlan.onRing(o, dx, dz, hx, hz);
    }

    /** Is this block on the wall's ring (for anything else that would build there)? */
    public static boolean reserved(UUID id, BlockPos heart, BlockPos p) {
        return onRing(id, p.getX() - heart.getX(), p.getZ() - heart.getZ(), 0, 0);
    }

    /**
     * [defence] How big a field square may grow at this offset (its middle) and still keep off the wall's ring, from
     * the most it would: the first row of fields stands just past the town's first block, where the wall goes.
     */
    public static boolean fieldKeptOff(@Nullable UUID id, int dx, int dz, int half) {
        return id != null && onRing(id, dx, dz, half + 1, half + 1);
    }

    // ------------------------------------------------------------------ fitting it to the ground

    static final int WALL = 0, LEFT_OUT = 1, OPENING = 2;

    /** A side fitted to the ground: for each column along it, the ground its walkway is measured from (smoothed),
     *  what it is (wall, left out, an opening for a road), and an opening's floor. */
    record Fit(int lo, int[] ground, int[] kind, int[] floor, Set<Long> skip) {
        int ground(int a) { int i = a - lo; return i < 0 || i >= ground.length ? Integer.MIN_VALUE : ground[i]; }
        int kind(int a) { int i = a - lo; return i < 0 || i >= kind.length ? LEFT_OUT : kind[i]; }
        int floor(int a) { int i = a - lo; return i < 0 || i >= floor.length ? ground(a) : floor[i]; }

        String encode() {
            StringBuilder sb = new StringBuilder().append(lo);
            for (int i = 0; i < ground.length; i++) sb.append(',').append(ground[i]).append(':').append(kind[i]).append(':').append(floor[i]);
            sb.append(';');
            boolean first = true;
            for (long k : skip) {
                if (!first) sb.append(',');
                sb.append(k);
                first = false;
            }
            return sb.toString();
        }

        @Nullable
        static Fit decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            try {
                String[] halves = s.split(";", -1);
                Set<Long> skip = new HashSet<>();
                if (halves.length > 1) for (String k : halves[1].split(",")) if (!k.isEmpty()) skip.add(Long.parseLong(k));
                String[] p = halves[0].split(",");
                int n = p.length - 1;
                int[] g = new int[n], k = new int[n], f = new int[n];
                for (int i = 0; i < n; i++) {
                    String[] q = p[i + 1].split(":");
                    g[i] = Integer.parseInt(q[0]);
                    k[i] = Integer.parseInt(q[1]);
                    f[i] = Integer.parseInt(q[2]);
                }
                return new Fit(Integer.parseInt(p[0]), g, k, f, skip);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    static String fitKey(WallPlan.Sides s, int side) {
        return FIT + side + "/" + s.of(side) + "/" + WallPlan.lo(s, side) + "/" + WallPlan.hi(s, side);
    }

    /** A side's fit: kept with the world once made (so what is built is never measured again), or made now. Null
     *  while its ground is not all loaded. */
    @Nullable
    static Fit fit(ServerLevel level, Villages.Village v, WallPlan.Sides s, int side) {
        String key = fitKey(s, side);
        Fit have = Fit.decode(Ledger.note(v.id(), key));
        if (have != null) return have;
        Fit made = fitNow(level, v, s, side);
        if (made != null) Ledger.note(v.id(), key, made.encode());
        return made;
    }

    @Nullable
    static Fit fitNow(ServerLevel level, Villages.Village v, WallPlan.Sides s, int side) {
        BlockPos c = v.centre();
        int line = s.of(side);
        int lo = WallPlan.lo(s, side) - WallPlan.TOWER, hi = WallPlan.hi(s, side) + WallPlan.TOWER;
        int n = hi - lo + 1;
        int[] g = new int[n], k = new int[n], f = new int[n];
        int up = WallPlan.height(Villages.Age.NETHER.ordinal()) + 4;
        WallPlan.Sides was = old(v.id());
        List<WallPlan.Tower> wasTowers = was == null ? List.of() : WallPlan.towers(was);
        for (int i = 0; i < n; i++) {
            int a = lo + i;
            int most = Integer.MIN_VALUE, mid = Integer.MIN_VALUE;
            boolean out = false, road = false;
            for (int u = 0; u < WallPlan.THICK; u++) {
                int[] o = WallPlan.offset(side, line + u, a);
                int x = c.getX() + o[0], z = c.getZ() + o[1];
                if (!level.hasChunk(x >> 4, z >> 4)) return null;
                boolean stood = stood(s, was, wasTowers, o[0], o[1]);
                int y = ground(level, x, z, c.getY(), stood);
                if (y == Integer.MIN_VALUE) { out = true; continue; }
                BlockState under = level.getBlockState(new BlockPos(x, y - 1, z));
                if (road(under)) road = true;
                if (under.is(Blocks.FARMLAND) || Math.abs(y - c.getY()) > 24 || foreignOver(level, x, y, z, y + up, stood)) out = true;
                if (!road(under) && !natural(under) && !(stood && mine(under))) out = true;
                most = Math.max(most, y);
                if (u == 1) mid = y;
            }
            boolean avenue = Math.abs(a) <= WallPlan.GATE;
            if (avenue || road && WallPlan.onCrossStreet(a)) {
                k[i] = most == Integer.MIN_VALUE ? LEFT_OUT : OPENING;
            } else {
                k[i] = out ? LEFT_OUT : WALL;
            }
            g[i] = most == Integer.MIN_VALUE ? c.getY() : most;
            f[i] = mid == Integer.MIN_VALUE ? g[i] : mid;
        }
        // The walkway never steps more than a block at a time: raised where the ground falls away.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i < n; i++) if (g[i] < g[i - 1] - 1) g[i] = g[i - 1] - 1;
            for (int i = n - 2; i >= 0; i--) if (g[i] < g[i + 1] - 1) g[i] = g[i + 1] - 1;
        }
        // The towers of this side (and the corner each side has as its own): a column of one over water, a field
        // or somebody's work is left out of it.
        Set<Long> skip = new HashSet<>();
        for (WallPlan.Tower t : WallPlan.towers(s)) {
            if (t.side() != side) continue;
            for (int x = t.x0(); x <= t.x1(); x++) {
                for (int z = t.z0(); z <= t.z1(); z++) {
                    int wx = c.getX() + x, wz = c.getZ() + z;
                    if (!level.hasChunk(wx >> 4, wz >> 4)) return null;
                    boolean stood = stood(s, was, wasTowers, x, z);
                    int y = ground(level, wx, wz, c.getY(), stood);
                    BlockState under = y == Integer.MIN_VALUE ? null : level.getBlockState(new BlockPos(wx, y - 1, wz));
                    if (under == null || under.is(Blocks.FARMLAND) || Math.abs(y - c.getY()) > 24
                        || !natural(under) && !road(under) && !(stood && mine(under))
                        || foreignOver(level, wx, y, wz, y + up + 4, stood)) skip.add(TownPlan.cellKey(x, z));
                }
            }
        }
        return new Fit(lo, g, k, f, skip);
    }

    /** Did the wall stand here already (its old line, or the wall as it is drawn now, with a column of it up)? The
     *  wall's own stone there is its own, not somebody's work. */
    private static boolean stood(WallPlan.Sides now, @Nullable WallPlan.Sides was, List<WallPlan.Tower> wasTowers, int dx, int dz) {
        return was != null && WallPlan.cell(was, wasTowers, dx, dz) != null;
    }

    /** The ground of a column: the first block of the world's own under whatever grows on it (plants, snow, a tree)
     *  and, where the wall stands or stood, the wall itself. MIN_VALUE in water or a hole too deep. */
    static int ground(ServerLevel level, int x, int z, int aroundY, boolean inPlan) {
        int y = Math.min(level.getMaxBuildHeight() - 1, Math.max(aroundY + 40,
            level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z)));
        int least = aroundY - 40;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (; y > least; y--) {
            BlockState st = level.getBlockState(p.set(x, y, z));
            if (!st.getFluidState().isEmpty() && !st.is(Blocks.LADDER)) return Integer.MIN_VALUE;
            if (wild(level, p, st) || inPlan && mine(st)) continue;
            return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    /** What grows or lies on the world's own ground, which the wall clears out of its way: plants, snow, a tree. */
    static boolean wild(ServerLevel level, BlockPos p, BlockState st) {
        if (st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty()) return true;
        if (st.is(BlockTags.LEAVES)) {
            return !(st.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                && st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT));
        }
        if (st.is(BlockTags.LOGS)) return com.jrpetty.mcassistant.entity.goal.BuildGoal.isTreeLog(level, p);
        return st.is(BlockTags.FLOWERS) || st.is(Blocks.SNOW) || st.is(Blocks.BAMBOO) || st.is(Blocks.SWEET_BERRY_BUSH)
            || st.is(Blocks.CACTUS) || st.is(Blocks.SUGAR_CANE) || st.is(Blocks.VINE) || st.is(Blocks.BROWN_MUSHROOM)
            || st.is(Blocks.RED_MUSHROOM) || st.is(Blocks.PUMPKIN) || st.is(Blocks.MELON);
    }

    /** The wall's own: its stone in any age, and its fittings. */
    static boolean mine(BlockState st) {
        return tierOf(st) >= 0 || fitting(st);
    }

    /** Where the wall stands, what it clears out of its way or takes down: what grows there, and its own. */
    static boolean clearable(ServerLevel level, BlockPos p, BlockState st) {
        return wild(level, p, st) || mine(st);
    }

    /** The world's own ground: earth, stone, sand and the like. */
    static boolean natural(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.SAND)
            || st.is(Blocks.GRAVEL) || st.is(Blocks.CLAY) || st.is(Blocks.SNOW_BLOCK) || st.is(BlockTags.TERRACOTTA)
            || st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE) || st.is(Blocks.PACKED_ICE) || st.is(Blocks.ICE)
            || st.is(Blocks.DIRT_PATH) || st.is(BlockTags.ICE) || st.is(Blocks.CALCITE) || st.is(Blocks.MUD);
    }

    /** A road's paving: a worn path or a laid one. */
    static boolean road(BlockState st) {
        return st.is(Blocks.DIRT_PATH) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE) || st.is(Blocks.STONE_BRICKS)
            || st.is(Blocks.GRAVEL) || st.is(Blocks.POLISHED_ANDESITE) || st.is(Blocks.SMOOTH_STONE) || st.is(Blocks.BRICKS)
            || st.is(Blocks.MUD_BRICKS) || st.is(BlockTags.SLABS);
    }

    /** Somebody's work standing on this column (a house, a fence, a lamp post): the wall keeps off it. The wall's
     *  own stone counts as the wall's only where the wall (or its old line) stands. */
    static boolean foreignOver(ServerLevel level, int x, int g, int z, int upTo, boolean inPlan) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = g; y <= upTo; y++) {
            BlockState st = level.getBlockState(p.set(x, y, z));
            if (wild(level, p, st) || inPlan && mine(st)) continue;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the wall, block by block

    /** One block of the wall as it should be: where, what, which way it faces, and (a door) which half and hinge. */
    record Want(BlockPos pos, Role role, Direction facing, int extra) {}

    /** The wall worked out for this sweep: its plan, its fit, its height and what it is built of. */
    static final class Wall {
        final WallPlan.Sides sides;
        final List<WallPlan.Tower> towers;
        final Fit[] fits = new Fit[4];
        final int height, tier;
        final BlockPos heart;
        final int[] cornerGround = new int[4];
        final List<int[]> stairs;

        Wall(WallPlan.Sides sides, BlockPos heart, Villages.Age age) {
            this.sides = sides;
            this.towers = WallPlan.towers(sides);
            this.heart = heart;
            this.tier = tier(age);
            this.height = WallPlan.height(age.ordinal());
            this.stairs = WallPlan.stairs(sides, towers, height);
        }

        /** The ground a tower's top is measured from. */
        int towerGround(WallPlan.Tower t) {
            if (t.kind() == WallPlan.TowerKind.CORNER) return cornerGround[towers.indexOf(t)];
            Fit f = fits[t.side()];
            int most = Integer.MIN_VALUE;
            for (int a = t.centre() - 3; a <= t.centre() + 3; a++) most = Math.max(most, f.ground(a));
            return most;
        }

        /** Is this tower column left out (over water, a field or somebody's work)? */
        boolean skipped(int dx, int dz) {
            long key = TownPlan.cellKey(dx, dz);
            for (Fit f : fits) if (f != null && f.skip().contains(key)) return true;
            return false;
        }

        /** The walkway's level (where one stands on it) at this column of a side: its ground and the wall's height,
         *  raised toward a tower that stands higher, a block a step. */
        int top(int side, int a) {
            int g = fits[side].ground(a);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 2; j++) {
                    if (ENDS[i][j * 2] != side) continue;
                    boolean hiEnd = ENDS[i][j * 2 + 1] == 1;
                    int r0 = hiEnd ? WallPlan.hi(sides, side) + 1 : WallPlan.lo(sides, side) - WallPlan.TOWER;
                    int r1 = r0 + WallPlan.TOWER - 1;
                    int d = a < r0 ? r0 - a : a > r1 ? a - r1 : 0;
                    g = Math.max(g, cornerGround[i] - d);
                }
            }
            for (WallPlan.Tower t : towers) {
                if (t.kind() == WallPlan.TowerKind.CORNER || t.side() != side) continue;
                int r0 = t.centre() - 3, r1 = t.centre() + 3;
                int d = a < r0 ? r0 - a : a > r1 ? a - r1 : 0;
                g = Math.max(g, towerGround(t) - d);
            }
            return g + height;
        }
    }

    /** The corners (NE, SE, SW, NW, as WallPlan.towers has them) and the two ends of sides they stand at: {side,
     *  1 its high end / 0 its low end} twice. */
    static final int[][] ENDS = { { TownPlan.NORTH, 1, TownPlan.EAST, 0 }, { TownPlan.EAST, 1, TownPlan.SOUTH, 1 },
        { TownPlan.SOUTH, 0, TownPlan.WEST, 1 }, { TownPlan.WEST, 0, TownPlan.NORTH, 0 } };

    @Nullable
    static Wall wall(ServerLevel level, Villages.Village v, WallPlan.Sides s) {
        Wall w = new Wall(s, v.centre(), Villages.ageOf(v.id()));
        for (int side = 0; side < 4; side++) {
            w.fits[side] = fit(level, v, s, side);
            if (w.fits[side] == null) return null;
        }
        // The corners: the higher of the two sides' ground under them.
        int[][] ends = ENDS;
        for (int i = 0; i < 4; i++) {
            int most = Integer.MIN_VALUE;
            for (int j = 0; j < 2; j++) {
                int side = ends[i][j * 2], hiEnd = ends[i][j * 2 + 1];
                int from = hiEnd == 1 ? WallPlan.hi(s, side) + 1 : WallPlan.lo(s, side) - WallPlan.TOWER;
                for (int a = from; a < from + WallPlan.TOWER; a++) most = Math.max(most, w.fits[side].ground(a));
            }
            w.cornerGround[i] = most;
        }
        return w;
    }

    /** Which way is out of the town, on a side. */
    static Direction outward(int side) {
        return switch (side) {
            case TownPlan.EAST -> Direction.EAST;
            case TownPlan.SOUTH -> Direction.SOUTH;
            case TownPlan.WEST -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    /** The way along a side that its across grows. */
    static Direction alongward(int side) {
        return side == TownPlan.EAST || side == TownPlan.WEST ? Direction.SOUTH : Direction.EAST;
    }

    /** The lowest block of a column the wall stands on: down from here through anything it clears away to the
     *  world's own ground. MIN_VALUE over water. */
    static int foot(ServerLevel level, int x, int z, int from) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = from; y > from - 40; y--) {
            BlockState st = level.getBlockState(p.set(x, y, z));
            if (!st.getFluidState().isEmpty() && !st.is(Blocks.LADDER)) return Integer.MIN_VALUE;
            if (clearable(level, p, st)) continue;
            return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    /** Everything the wall wants at one column (dx, dz from the heart). */
    static List<Want> wants(ServerLevel level, Wall w, int dx, int dz) {
        List<Want> out = new ArrayList<>();
        WallPlan.Cell c = WallPlan.cell(w.sides, w.towers, dx, dz);
        if (c == null) return out;
        int x = w.heart.getX() + dx, z = w.heart.getZ() + dz;
        if (!level.hasChunk(x >> 4, z >> 4)) return out;                     // never a chunk loaded for the wall's sake
        Direction outD = outward(c.side()), along = alongward(c.side()), in = outD.getOpposite();
        if (c.kind() == WallPlan.Kind.TOWER) {
            tower(level, w, c, x, z, out);
            return out;
        }
        Fit f = w.fits[c.side()];
        int a = c.across(), layer = c.layer();
        int kind = f.kind(a);
        if (kind == LEFT_OUT) return out;
        int top = w.top(c.side(), a);
        int foot = foot(level, x, z, top - 1);
        if (foot == Integer.MIN_VALUE || top - foot > w.height + 14) return out;
        boolean merlon = Math.floorMod(a, 2) == 0;
        if (kind == OPENING) {
            int floor = f.floor(a);
            foot = Math.min(foot, floor);
            boolean gate = Math.abs(a) <= WallPlan.GATE;
            for (int y = foot; y < floor; y++) out.add(new Want(new BlockPos(x, y, z), Role.BODY, outD, 0));
            if (layer < 2) {
                for (int y = floor; y <= floor + 2; y++) out.add(new Want(new BlockPos(x, y, z), Role.AIR, outD, 0));
                // The arch's shoulders, either side of the opening.
                int edge = gate ? WallPlan.GATE : -1;
                if (gate && Math.abs(a) == edge) {
                    out.set(out.size() - 1, new Want(new BlockPos(x, floor + 2, z), Role.ARCH, a < 0 ? along.getOpposite() : along, 1));
                }
            } else {
                Direction hingeSide = a < 0 ? along.getOpposite() : along;
                out.add(new Want(new BlockPos(x, floor, z), Role.DOOR, outD, a <= 0 ? 0 : 2));
                out.add(new Want(new BlockPos(x, floor + 1, z), Role.DOOR, outD, a <= 0 ? 1 : 3));
                out.add(new Want(new BlockPos(x, floor + 2, z), Role.BODY, hingeSide, 0));
            }
            for (int y = floor + 3; y <= top - 2; y++) out.add(new Want(new BlockPos(x, y, z), Role.BODY, outD, 0));
        } else {
            // A flight of steps up from inside, cut into the inner face.
            int step = -1;
            int[] flight = null;
            if (layer == 0) {
                for (int[] fl : w.stairs) {
                    if (fl[0] != c.side()) continue;
                    int k = WallPlan.stepAt(fl, w.height, a);
                    if (k >= 0) { step = k; flight = fl; }
                }
            }
            if (step >= 0) {
                int sy = top - w.height + step;
                for (int y = foot; y < sy; y++) out.add(new Want(new BlockPos(x, y, z), Role.BODY, outD, 0));
                Direction climb = flight[2] > 0 ? along : along.getOpposite();
                out.add(new Want(new BlockPos(x, sy, z), Role.STAIR, climb, 0));
                for (int y = sy + 1; y <= top + 1; y++) out.add(new Want(new BlockPos(x, y, z), Role.AIR, outD, 0));
                return out;
            }
            for (int y = foot; y <= top - 2; y++) {
                boolean face = layer == 2 && y < foot + PLINTH || layer != 1 && weathered(x, y, z);
                out.add(new Want(new BlockPos(x, y, z), face ? Role.ACCENT : Role.BODY, outD, 0));
            }
        }
        // The top: the walkway's cap, the battlements on the outer face, a lantern on every fourth merlon.
        // The coping: the walkway's caps, and a line of it along the battlements' foot on the outer face.
        out.add(new Want(new BlockPos(x, top - 1, z), Role.CAP, along, 0));
        if (layer < 2) {
            out.add(new Want(new BlockPos(x, top, z), Role.AIR, outD, 0));
            out.add(new Want(new BlockPos(x, top + 1, z), Role.AIR, outD, 0));
        } else if (merlon) {
            out.add(new Want(new BlockPos(x, top, z), Role.MERLON, outD, 0));
            if (Math.floorMod(a, 8) == 0) out.add(new Want(new BlockPos(x, top + 1, z), Role.LANTERN, outD, 0));
        } else {
            out.add(new Want(new BlockPos(x, top, z), Role.AIR, outD, 0));
        }
        // A torch on the inner face over the street, every eight.
        if (layer == 0 && kind == WALL && Math.floorMod(a, 8) == 4) {
            BlockPos t = new BlockPos(x, w.fits[c.side()].ground(a) + 3, z).relative(in);
            if (t.getY() < top - 1) out.add(new Want(t, Role.TORCH, in, 0));
        }
        return out;
    }

    /** A tower's column: solid to the walkway, the walkway through it, solid three more, crenels round its top;
     *  a ladder from the street inside it up to the walkway and another on up to its top; a lantern on top. */
    static void tower(ServerLevel level, Wall w, WallPlan.Cell c, int x, int z, List<Want> out) {
        WallPlan.Tower t = c.tower();
        int top = w.towerGround(t) + w.height;
        int foot = foot(level, x, z, top - 1);
        int dx = x - w.heart.getX(), dz = z - w.heart.getZ();
        if (foot == Integer.MIN_VALUE || top - foot > w.height + 16 || w.skipped(dx, dz)) return;
        int i = c.across() - (t.centre() - 3), layer = c.layer();
        Direction outD = outward(t.side()), in = outD.getOpposite();
        boolean corner = t.kind() == WallPlan.TowerKind.CORNER;
        // The walkway's way through: straight through a tower on a side; round the inner corner of a corner tower.
        boolean passage;
        if (corner) {
            int icx = Math.abs(t.x0()) < Math.abs(t.x1()) ? t.x0() : t.x1();
            int icz = Math.abs(t.z0()) < Math.abs(t.z1()) ? t.z0() : t.z1();
            passage = Math.abs(dx - icx) <= 1 && Math.abs(dz - icz) <= 1;
        } else {
            passage = layer <= 1;
        }
        boolean shaft = !corner && i == 3 && layer == 0;                       // the ladder up from the street
        boolean upper;                                                         // the ladder on up to the top
        Direction upperFacing;
        if (corner) {
            int icx = Math.abs(t.x0()) < Math.abs(t.x1()) ? t.x0() : t.x1();
            int icz = Math.abs(t.z0()) < Math.abs(t.z1()) ? t.z0() : t.z1();
            int ox = t.x0() == icx ? 1 : -1, oz = t.z0() == icz ? 1 : -1;      // the way out from the inner corner
            upper = dx == icx + ox && dz == icz + oz;
            upperFacing = Math.abs(ox) > 0 ? (ox > 0 ? Direction.WEST : Direction.EAST) : Direction.NORTH;
        } else {
            upper = i == 3 && layer == 1;
            upperFacing = in;
        }
        boolean rim = i == 0 || i == 6 || layer == 0 || layer == 6;
        for (int y = foot; y <= top - 1; y++) {
            if (shaft) out.add(new Want(new BlockPos(x, y, z), Role.LADDER, in, 0));
            else {
                boolean face = rim && (y < foot + PLINTH || weathered(x, y, z));
                out.add(new Want(new BlockPos(x, y, z), face ? Role.ACCENT : Role.BODY, outD, 0));
            }
        }
        for (int y = top; y <= top + 1; y++) {
            if (upper) out.add(new Want(new BlockPos(x, y, z), Role.LADDER, upperFacing, 0));
            else out.add(new Want(new BlockPos(x, y, z), passage ? Role.AIR : Role.BODY, outD, 0));
        }
        // The parapet's floor, its rim a course of corbels: steps hung upside down, cut away underneath on the outside.
        if (upper) out.add(new Want(new BlockPos(x, top + 2, z), Role.LADDER, upperFacing, 0));
        else if (rim) out.add(new Want(new BlockPos(x, top + 2, z), Role.ARCH, corbel(t, x - w.heart.getX(), z - w.heart.getZ()), 0));
        else out.add(new Want(new BlockPos(x, top + 2, z), Role.CAP, outD, 0));
        if (rim) {
            boolean crenel = (i + layer) % 2 == 0 || (i == 0 || i == 6) && (layer == 0 || layer == 6);
            out.add(new Want(new BlockPos(x, top + 3, z), crenel ? Role.MERLON : Role.AIR, outD, 0));
        } else if (i == 3 && layer == 3) {
            out.add(new Want(new BlockPos(x, top + 3, z), Role.LANTERN, outD, 0));
        } else {
            out.add(new Want(new BlockPos(x, top + 3, z), Role.AIR, outD, 0));
        }
    }

    // ------------------------------------------------------------------ the sections

    /** Is this stone of a face one of the weathered ones (mossy, cracked): one in ACCENT_EVERY, scattered. */
    static boolean weathered(int x, int y, int z) {
        return Math.floorMod(x * 31 + y * 17 + z * 7, ACCENT_EVERY) == 0;
    }

    /** Which way a corbel on a tower's rim faces: in from the face it is on (its cut-away side out). */
    static Direction corbel(WallPlan.Tower t, int dx, int dz) {
        if (dx == t.x0()) return Direction.EAST;
        if (dx == t.x1()) return Direction.WEST;
        if (dz == t.z0()) return Direction.SOUTH;
        return Direction.NORTH;
    }

    /** The wall's sections in the order they go up: the gatehouses, the corner towers, the towers between, the
     *  curtain a run at a time, each its columns. */
    static Map<String, List<int[]>> sections(Wall w) {
        Map<String, List<int[]>> by = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        String[] names = { "north", "east", "south", "west" };
        for (int side = 0; side < 4; side++) order.add("the " + names[side] + " gatehouse");
        String[] corners = { "the north-east tower", "the south-east tower", "the south-west tower", "the north-west tower" };
        for (String cn : corners) order.add(cn);
        for (String o : order) by.put(o, new ArrayList<>());
        for (int[] col : WallPlan.columns(w.sides, w.towers)) {
            WallPlan.Cell c = WallPlan.cell(w.sides, w.towers, col[0], col[1]);
            if (c == null) continue;
            String key;
            if (c.kind() == WallPlan.Kind.GATE) key = "the " + names[c.side()] + " gatehouse";
            else if (c.kind() == WallPlan.Kind.TOWER) {
                WallPlan.Tower t = c.tower();
                key = switch (t.kind()) {
                    case FLANK -> "the " + names[t.side()] + " gatehouse";
                    case CORNER -> corners[w.towers.indexOf(t)];
                    case MID -> "a tower on the " + names[t.side()] + " wall (" + t.centre() + ")";
                };
            } else {
                int run = Math.floorDiv(c.across(), RUN);
                key = "the " + names[c.side()] + " wall, run " + run;
            }
            by.computeIfAbsent(key, k -> new ArrayList<>()).add(col);
        }
        // The curtain's runs: the ones by the gates first.
        List<Map.Entry<String, List<int[]>>> rest = new ArrayList<>();
        Map<String, List<int[]>> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, List<int[]>> e : by.entrySet()) {
            if (order.contains(e.getKey())) sorted.put(e.getKey(), e.getValue());
            else rest.add(e);
        }
        rest.sort(Comparator.comparingInt((Map.Entry<String, List<int[]>> e) -> e.getKey().startsWith("a tower") ? 0 : 1)
            .thenComparingInt(e -> {
                int[] col = e.getValue().get(0);
                WallPlan.Cell c = WallPlan.cell(w.sides, w.towers, col[0], col[1]);
                return c == null ? 0 : Math.abs(c.across());
            }));
        for (Map.Entry<String, List<int[]>> e : rest) sorted.put(e.getKey(), e.getValue());
        return sorted;
    }

    // ------------------------------------------------------------------ keeping it

    /** What the town knows of its wall between looks. */
    static final class State {
        WallPlan.Sides sides;
        Wall wall;
        Map<String, List<int[]>> sections;
        List<String> names = List.of();
        int at, cursor;
        final Map<String, Count> counts = new HashMap<>();
        long surveyed = -100000L, lastWork = -100000L;
        int total, laid, blocked, waiting;
        final Map<String, Integer> wanted = new LinkedHashMap<>();
        final List<BlockPos> doors = new ArrayList<>();
        final Set<String> finished = new HashSet<>();
        Boolean shut;
        boolean takingDown;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /** Tests: let the works go on in a town that has not got its essentials yet. */
    private static volatile boolean readyForTests;
    /** Tests: the wall stood down (no plan, no ring kept, no works) for the other features' tests (Kit.reset); the
     *  defence tests stand it up. */
    private static volatile boolean standDown;

    public static void standDownForTests(boolean down) {
        standDown = down;
    }

    /** Does the town wall go on at all (the config, and not stood down for a test)? */
    public static boolean live() {
        return AssistantConfig.castleWalls() && !standDown;
    }

    public static void resetForTests() {
        STATES.clear();
        readyForTests = false;
    }

    public static void readyForTests(boolean on) {
        readyForTests = on;
    }

    /** Has the town what comes first (its stores, its homes and its food) and the hands to spare for a wall? */
    static boolean ready(UUID id) {
        if (!live()) return false;
        if (readyForTests) return true;
        if (Villages.headcount(id) < BigWorks.GROWN_AT_LEAST) return false;
        if (!Villages.hasBuilt(id, "storage") && !Villages.hasBuilt(id, "storehouse")) return false;
        return !Market.hungry(id) && Market.bedsShort(id) <= 2;
    }

    /** Does the wall stand (most of it laid)? */
    public static boolean stands(UUID id) {
        State st = STATES.get(id);
        return st != null && st.total > 0 && st.laid * 10 >= st.total * 9;
    }

    /**
     * Seen to from the watch's look round (Raids.tick, every second): its gates shut at dusk and when the bell rings
     * and opened in the morning; and every five seconds a dozen blocks laid, refaced or taken down.
     */
    public static void keep(ServerLevel level, Villages.Village v) {
        if (!live()) return;
        com.jrpetty.mcassistant.Guard.run("castle wall", () -> keepNow(level, v));
    }

    private static void keepNow(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        State st = STATES.computeIfAbsent(id, k -> new State());
        long now = level.getGameTime();
        // The gates: only on the change, so a folk opening a door to go through isn't fought by the watch.
        long t = level.getDayTime() % 24000L;
        boolean shut = (t >= Ethos.gatesShut(id) && t < Ethos.gatesOpen(id)) || Raids.underAlarm(id);
        if (st.shut == null || st.shut != shut) {
            if (!st.doors.isEmpty() || st.shut != null) shutGates(level, st, shut);
            st.shut = shut;
        }
        if (now - st.lastWork < EVERY && now >= st.lastWork) return;
        st.lastWork = now;
        if (!ready(id)) return;
        work(level, v, st, HAND, BATCH);
    }

    /** How the works go: by a hand, paid out of the stores (the game); at once, paid (tests); at once, out of
     *  nothing (the pictures). */
    static final int HAND = 0, NOW = 1, FREE = 2;

    /** One look at the wall's works: a batch laid, or the next section looked at. Returns blocks set. */
    static int work(ServerLevel level, Villages.Village v, State st, int mode, int batch) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (st.wall == null || now - st.surveyed >= SURVEY || now < st.surveyed) {
            replan(level, id);
            WallPlan.Sides s = sides(id);
            if (Ledger.note(id, SIDES) == null) Ledger.note(id, SIDES, s.encode());
            Wall w = wall(level, v, s);
            if (w == null) return 0;                               // its ground not all loaded yet
            if (!s.equals(st.sides) || st.wall == null || st.wall.height != w.height || st.wall.tier != w.tier) st.counts.clear();
            st.sides = s;
            st.wall = w;
            st.sections = sections(w);
            st.names = new ArrayList<>(st.sections.keySet());
            st.at = 0;
            st.surveyed = now;
            if (mode != HAND) survey(level, v, st);
        }
        // In the game a section is counted a look (never the whole wall in one tick); counted, it is all known.
        if (mode == HAND && !st.names.isEmpty()) {
            count(level, v, st, st.names.get(st.cursor++ % st.names.size()), availability(level, v, st.wall.tier));
        }
        int set = 0;
        // A section at a time; the next when this one has nothing left to do with what the stores hold.
        for (int tries = 0; tries < 6 * st.names.size() + 6 && set < batch; tries++) {
            if (st.at >= st.names.size()) break;
            String name = st.names.get(st.at);
            int r = section(level, v, st, name, mode, batch - set);
            if (r > 0) { set += r; if (mode == HAND) return set; continue; }
            if (r < 0) return set;                                  // a hand on its way
            if (st.finished.add(name) && mode != FREE && Ledger.note(id, BEGUN) != null) {
                Villages.tell(id, level.getDayTime() / 24000L, capital(name) + " of the town wall was finished");
            }
            st.at++;
        }
        // All of it up: the old line, if it was moved out, comes down.
        if (st.at >= st.names.size() && old(id) != null) set += takeDown(level, v, st, mode, batch - set);
        return set;
    }

    /** Count the whole wall at once (the tests, the command): blocks wanted, laid, left out, what the rest wants. */
    static void survey(ServerLevel level, Villages.Village v, State st) {
        int[] best = availability(level, v, st.wall.tier);
        st.counts.clear();
        for (String name : st.names) count(level, v, st, name, best);
    }

    /** What one section of the wall comes to: its blocks wanted, laid, left out, its doors, what the rest wants. */
    record Count(int total, int laid, int blocked, Map<String, Integer> wanted, List<BlockPos> doors) {}

    /** Count one section, and the wall's totals again from the sections'. */
    static void count(ServerLevel level, Villages.Village v, State st, String name, int[] best) {
        List<int[]> cols = st.sections.get(name);
        if (cols == null) return;
        int total = 0, laid = 0, blocked = 0;
        Map<String, Integer> wanted = new LinkedHashMap<>();
        List<BlockPos> doors = new ArrayList<>();
        for (int[] col : cols) {
            for (Want w : wants(level, st.wall, col[0], col[1])) {
                if (w.role() == Role.AIR) continue;
                if (w.role() == Role.DOOR && w.extra() % 2 == 0) doors.add(w.pos());
                total++;
                int d = doing(level, st.wall, w, best);
                if (d == 0) laid++;
                else if (d == BLOCKED) blocked++;
                else wanted.merge(itemName(st.wall.tier, w.role()), 1, Integer::sum);
            }
        }
        st.counts.put(name, new Count(total, laid, blocked, wanted, doors));
        st.total = 0;
        st.laid = 0;
        st.blocked = 0;
        st.wanted.clear();
        st.doors.clear();
        for (Count c : st.counts.values()) {
            st.total += c.total();
            st.laid += c.laid();
            st.blocked += c.blocked();
            c.wanted().forEach((k, n) -> st.wanted.merge(k, n, Integer::sum));
            st.doors.addAll(c.doors());
        }
    }

    static String itemName(int tier, Role role) {
        return switch (role) {
            case LADDER -> "ladders";
            case LANTERN -> "lanterns";
            case TORCH -> "torches";
            case DOOR -> "doors (planks)";
            case STAIR, ARCH -> tier == WOOD ? "planks" : name(PALETTES.get(tier).body());
            case CAP -> tier == WOOD ? "planks" : name(PALETTES.get(tier).cap());
            case ACCENT -> tier == WOOD ? "logs" : name(PALETTES.get(tier).accent());
            default -> tier == WOOD ? "logs" : name(PALETTES.get(tier).body());
        };
    }

    private static String name(Block b) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath().replace('_', ' ');
    }

    static final int BLOCKED = 9, PLACE = 1, UPGRADE = 2, CLEAR = 3, WAIT = 4;

    /** Which age's stone the stores can spare for each role now (-1: none): [masonry, cap, steps]. */
    static int[] availability(ServerLevel level, Villages.Village v, int tier) {
        int[] out = { -1, -1, -1 };
        int least = Math.min(1, tier);
        for (int t = tier; t >= least && out[0] < 0; t--) if (Crafts.stock(level, v, paid(t, Role.BODY)) > KEEP) out[0] = t;
        for (int t = tier; t >= least && out[1] < 0; t--) if (Crafts.stock(level, v, paid(t, Role.CAP)) > KEEP) out[1] = t;
        for (int t = tier; t >= least && out[2] < 0; t--) if (Crafts.stock(level, v, paid(t, Role.STAIR)) > KEEP) out[2] = t;
        return out;
    }

    private static int[] free(int tier) {
        return new int[]{ tier, tier, tier };
    }

    /** What a block wants doing: 0 nothing, PLACE, UPGRADE (refaced in the age's stone), CLEAR, WAIT (for stone),
     *  BLOCKED (somebody's work in the way). */
    static int doing(ServerLevel level, Wall w, Want want, int[] best) {
        BlockState st = level.getBlockState(want.pos());
        switch (want.role()) {
            case AIR -> {
                if (st.isAir()) return 0;
                if (clearable(level, want.pos(), st) || natural(st)) return st.getFluidState().isEmpty() || st.canBeReplaced() ? CLEAR : BLOCKED;
                return BLOCKED;
            }
            case LADDER -> {
                if (st.is(Blocks.LADDER) && st.getValue(LadderBlock.FACING) == want.facing()) return 0;
                return clearable(level, want.pos(), st) ? PLACE : BLOCKED;
            }
            case LANTERN, TORCH -> {
                if (st.is(Blocks.LANTERN) || st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) return 0;
                return st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty() ? PLACE : 0;   // a fitting: never in anybody's way
            }
            case DOOR -> {
                if (st.getBlock() instanceof DoorBlock) return 0;
                return clearable(level, want.pos(), st) ? PLACE : BLOCKED;
            }
            case STAIR, ARCH -> {
                int have = stepsTier(st);
                int can = best[2];
                if (have >= 0 && st.getValue(StairBlock.FACING) == want.facing()) {
                    return have >= w.tier || can <= have ? 0 : UPGRADE;
                }
                if (!clearable(level, want.pos(), st)) return natural(st) ? (can >= 0 ? UPGRADE : WAIT) : BLOCKED;
                return can >= 0 ? PLACE : WAIT;
            }
            default -> {
                int have = tierOf(st);
                int can = want.role() == Role.CAP ? best[1] : best[0];
                if (have >= 0 && !st.is(BlockTags.LEAVES)) {
                    boolean log = st.is(BlockTags.LOGS);
                    if (log && w.tier > 0 && can < 0) return 0;          // a log in a stone wall, kept till stone comes
                    return have >= w.tier || can <= have ? 0 : UPGRADE;
                }
                if (natural(st)) return 0;                              // the world's own ground does for a footing
                if (!clearable(level, want.pos(), st)) return BLOCKED;
                return can >= 0 ? PLACE : WAIT;
            }
        }
    }

    /** Work one section: a batch of its blocks. Returns blocks set, 0 if it has nothing left that can be done, -1
     *  while a hand is on its way. */
    static int section(ServerLevel level, Villages.Village v, State st, String name, int mode, int batch) {
        List<int[]> cols = st.sections.get(name);
        if (cols == null) return 0;
        Wall w = st.wall;
        int[] best = mode == FREE ? free(w.tier) : availability(level, v, w.tier);
        List<Want> todo = new ArrayList<>();
        List<Integer> how = new ArrayList<>();
        for (int[] col : cols) {
            for (Want want : wants(level, w, col[0], col[1])) {
                int d = doing(level, w, want, best);
                if (d == PLACE || d == UPGRADE || d == CLEAR) { todo.add(want); how.add(d); }
            }
        }
        if (todo.isEmpty()) return 0;
        // From the bottom up, so nothing hangs in the air; near the first.
        Integer[] idx = new Integer[todo.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, Comparator.comparingInt((Integer i) -> todo.get(i).pos().getY())
            .thenComparingInt(i -> todo.get(i).role() == Role.AIR ? 0 : 1));
        BlockPos first = todo.get(idx[0]).pos();
        if (mode == HAND && !TownJobs.atWork(level, v, "the town wall", first, "building " + name + " of the town wall")) return -1;
        int set = 0;
        for (Integer i : idx) {
            if (set >= batch) break;
            Want want = todo.get(i);
            if (mode == HAND && want.pos().distManhattan(first) > 12) continue;
            if (mode == NOW && set > 0 && set % RECOUNT == 0) best = availability(level, v, w.tier);   // the stores' keep-back held
            if (lay(level, v, w, want, how.get(i), best, mode == FREE)) {
                set++;
                if (Ledger.note(v.id(), BEGUN) == null) Ledger.note(v.id(), BEGUN, Long.toString(level.getDayTime() / 24000L));
            }
        }
        return set;
    }

    /** Lay (or reface, or clear) one block, paid out of the stores. */
    static boolean lay(ServerLevel level, Villages.Village v, Wall w, Want want, int how, int[] best, boolean free) {
        BlockPos p = want.pos();
        BlockState old = level.getBlockState(p);
        if (how == CLEAR) {
            if (!old.isAir() && !old.canBeReplaced()) give(level, v, old, free);
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            return true;
        }
        BlockState put;
        switch (want.role()) {
            case LADDER -> {
                if (!free && !TownWork.take(level, v, s -> s.is(Items.LADDER), 1) && !TownWork.take(level, v, s -> s.is(ItemTags.PLANKS), 1)) return false;
                put = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, want.facing());
                if (!put.canSurvive(level, p)) return false;
            }
            case LANTERN -> {
                boolean lantern = free || TownWork.take(level, v, s -> s.is(Items.LANTERN), 1);
                if (!lantern && !TownWork.take(level, v, s -> s.is(Items.TORCH), 1)) return false;
                put = lantern ? Blocks.LANTERN.defaultBlockState() : Blocks.TORCH.defaultBlockState();
            }
            case TORCH -> {
                if (!free && !TownWork.take(level, v, s -> s.is(Items.TORCH), 1)) return false;
                put = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, want.facing());
                if (!put.canSurvive(level, p)) return false;
            }
            case DOOR -> {
                if (want.extra() % 2 == 1) return false;                 // the upper half comes with the lower
                if (!free && !TownWork.take(level, v, s -> s.is(ItemTags.PLANKS), 2)
                        && !TownWork.take(level, v, s -> s.is(ItemTags.LOGS), 1)) return false;
                boolean right = want.extra() >= 2;
                BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, want.facing())
                    .setValue(DoorBlock.HINGE, right ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT)
                    .setValue(DoorBlock.OPEN, !(STATES.containsKey(v.id()) && Boolean.TRUE.equals(STATES.get(v.id()).shut)));
                give(level, v, level.getBlockState(p.above()), free);
                give(level, v, old, free);
                level.setBlock(p, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2 | 16);
                level.setBlock(p.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
                return true;
            }
            default -> {
                int t = want.role() == Role.STAIR || want.role() == Role.ARCH ? best[2] : want.role() == Role.CAP ? best[1] : best[0];
                if (t < 0) return false;
                ItemStack paid;
                if (free) {
                    Palette pal = PALETTES.get(t);
                    Block accent = pal.worn() != null && Math.floorMod(p.getX() + p.getY() + p.getZ(), 2) == 0 ? pal.worn() : pal.accent();
                    paid = new ItemStack((want.role() == Role.CAP ? pal.cap() : want.role() == Role.ACCENT ? accent : pal.body()).asItem());
                }
                else {
                    paid = Crafts.takeOne(level, v, paid(t, want.role()));
                    if (paid.isEmpty()) return false;
                }
                put = made(t, want.role(), paid, want.facing(), want.role() == Role.ARCH);
            }
        }
        if (!old.isAir() && !old.canBeReplaced()) give(level, v, old, free);
        level.setBlock(p, put, 3);
        return true;
    }

    /** What came out of the wall's way back into the stores: its old stone, a felled tree's logs. Leaves and earth
     *  are not kept. */
    static void give(ServerLevel level, Villages.Village v, BlockState old, boolean free) {
        if (free || old.isAir() || old.is(BlockTags.LEAVES) || old.canBeReplaced()) return;
        if (old.getBlock() instanceof DoorBlock) {
            if (old.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) TownWork.give(level, v, new ItemStack(old.getBlock().asItem()));
            return;
        }
        if (tierOf(old) < 0 && !fitting(old) && !natural(old)) return;
        if (natural(old) && !old.is(BlockTags.BASE_STONE_OVERWORLD)) return;      // earth is left be
        ItemStack back = new ItemStack(old.getBlock().asItem());
        if (!back.isEmpty()) TownWork.give(level, v, back);
    }

    /** Shut (or open) every door of the wall's gates and posterns. Returns how many moved. */
    static int shutGates(ServerLevel level, State st, boolean shut) {
        int moved = 0;
        for (BlockPos d : st.doors) {
            if (!level.isLoaded(d)) continue;
            BlockState s = level.getBlockState(d);
            if (!(s.getBlock() instanceof DoorBlock door) || s.getValue(DoorBlock.OPEN) == !shut) continue;
            door.setOpen(null, level, s, d, !shut);
            moved++;
        }
        return moved;
    }

    // ------------------------------------------------------------------ the old line

    /** The old line taken down where the wall has moved out, a batch at a time: its stone into the stores, its
     *  ground given back to the lots. Returns blocks taken. */
    static int takeDown(ServerLevel level, Villages.Village v, State st, int mode, int batch) {
        UUID id = v.id();
        WallPlan.Sides o = old(id);
        if (o == null) return 0;
        WallPlan.Sides now = st.sides;
        List<WallPlan.Tower> oldTowers = WallPlan.towers(o);
        List<BlockPos> todo = new ArrayList<>();
        int high = st.wall.height + 4;
        for (int[] col : WallPlan.columns(o, oldTowers)) {
            if (WallPlan.cell(now, st.wall.towers, col[0], col[1]) != null) continue;   // still the wall's
            int x = v.centre().getX() + col[0], z = v.centre().getZ() + col[1];
            if (!level.hasChunk(x >> 4, z >> 4)) return 0;
            int g = ground(level, x, z, v.centre().getY(), true);
            if (g == Integer.MIN_VALUE) continue;
            for (int y = g + high + 6; y >= g - 1; y--) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState bs = level.getBlockState(p);
                if (mine(bs) && !wild(level, p, bs)) todo.add(p);
            }
        }
        if (todo.isEmpty()) {
            Ledger.forget(id, OLD);
            STATES.remove(id);
            Villages.tell(id, level.getDayTime() / 24000L, "the old wall was taken down where the town grew past it, and its line given back to the lots");
            return 0;
        }
        todo.sort(Comparator.comparingInt((BlockPos p) -> -p.getY()));
        BlockPos first = todo.get(0);
        if (mode == HAND && !TownJobs.atWork(level, v, "the town wall", first, "taking down the old wall")) return 0;
        int n = 0;
        for (BlockPos p : todo) {
            if (n >= batch) break;
            if (mode == HAND && p.distManhattan(first) > 12) continue;
            BlockState bs = level.getBlockState(p);
            give(level, v, bs, mode == FREE);
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ the watch on it

    /**
     * Places on the wall for the watch's archers (Raids.postFor), once the wall stands there: the top of each
     * gatehouse tower, its ladder's foot in the street; and the walkway midway along each run between the towers.
     */
    public static List<Watch.Post> posts(ServerLevel level, UUID id) {
        List<Watch.Post> out = new ArrayList<>();
        State st = STATES.get(id);
        Villages.Village v = Villages.get(id);
        if (st == null || st.wall == null || v == null || !live()) return out;
        Wall w = st.wall;
        for (WallPlan.Tower t : w.towers) {
            if (t.kind() != WallPlan.TowerKind.FLANK) continue;
            int top = w.towerGround(t) + w.height;
            int[] mid = WallPlan.offset(t.side(), w.sides.of(t.side()) + 3, t.centre());
            int[] shaft = WallPlan.offset(t.side(), w.sides.of(t.side()), t.centre());
            BlockPos stand = new BlockPos(v.centre().getX() + mid[0], top + 3, v.centre().getZ() + mid[1]);
            int fx = v.centre().getX() + shaft[0], fz = v.centre().getZ() + shaft[1];
            int footY = foot(level, fx, fz, top - 1);
            if (footY == Integer.MIN_VALUE || !level.isLoaded(stand)) continue;
            if (!level.getBlockState(stand.below()).isSolid() || !level.getBlockState(stand).isAir()) continue;
            out.add(new Watch.Post(stand, new BlockPos(fx, footY, fz), outward(t.side())));
        }
        return out;
    }

    /** Where a raiding band gathers, out past the wall if it stands: never nearer the heart than this. */
    public static int outside(UUID id, int near) {
        if (!live() || !stands(id)) return near;
        WallPlan.Sides s = sides(id);
        return Math.max(near, Math.max(Math.max(s.n(), s.e()), Math.max(s.s(), s.w())) + WallPlan.BAND + 6);
    }

    // ------------------------------------------------------------------ telling

    /** The wall in a few lines, for the books and the command. */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        if (!live()) {
            out.add("The town wall is turned off (castleWalls).");
            return out;
        }
        State st = STATES.computeIfAbsent(id, k -> new State());
        if (st.wall == null) work(level, v, st, FREE, 0);          // planned and counted (nothing laid: no batch)
        WallPlan.Sides s = sides(id);
        out.add("The town wall: its inner face " + s.n() + " out to the north, " + s.e() + " east, " + s.s() + " south, "
            + s.w() + " west of the heart; " + (st.wall == null ? "its ground not all loaded yet" : st.wall.towers.size() + " towers, "
            + st.wall.height + " high, in " + Villages.ageOf(id).label + "'s stone"));
        if (st.wall != null) {
            int pct = st.total == 0 ? 0 : (int) (100L * st.laid / st.total);
            out.add("Laid " + st.laid + " of " + st.total + " blocks (" + pct + "%)" + (st.blocked > 0 ? ", " + st.blocked + " left out where something stands in the way" : "")
                + "; " + (ready(id) ? "the hands are at it" : "it waits on the town's homes, food and stores"));
            if (!st.wanted.isEmpty()) {
                List<String> w = new ArrayList<>();
                for (Map.Entry<String, Integer> e : st.wanted.entrySet()) w.add(e.getValue() + " " + e.getKey());
                out.add("Still wants: " + String.join(", ", w));
            }
            if (st.at < st.names.size()) out.add("Now at " + st.names.get(st.at) + " (" + (st.at + 1) + " of " + st.names.size() + " sections)");
            out.add("Gates: " + st.doors.size() / 5 + "+ (" + st.doors.size() + " doors), " + (Boolean.TRUE.equals(st.shut) ? "shut" : "open"));
        }
        if (old(id) != null) out.add("The old line, " + old(id).encode() + ", to come down once the new wall stands");
        return out;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests and the stage

    /** Tests and the smoke stage: plan the wall now and lay all of it at once out of nothing (a showcase), or as
     *  much as the stores pay for. Returns blocks set. */
    public static int buildNow(ServerLevel level, Villages.Village v, boolean free) {
        State st = STATES.computeIfAbsent(v.id(), k -> new State());
        st.wall = null;
        int total = 0;
        for (int round = 0; round < 400; round++) {
            int n = work(level, v, st, free ? FREE : NOW, 100000);
            total += n;
            if (n == 0) break;
            st.at = 0;
        }
        st.surveyed = -100000L;
        work(level, v, st, FREE, 0);                                  // counted again
        return total;
    }

    /** Tests: the wall as worked out now (null while its ground is not loaded). */
    @Nullable
    public static WallPlan.Sides plannedForTests(ServerLevel level, Villages.Village v) {
        State st = STATES.computeIfAbsent(v.id(), k -> new State());
        st.surveyed = -100000L;
        work(level, v, st, FREE, 0);
        return st.sides;
    }

    /** Tests: {laid, total, blocked} at the last look. */
    public static int[] countsForTests(UUID id) {
        State st = STATES.get(id);
        return st == null ? new int[3] : new int[]{ st.laid, st.total, st.blocked };
    }

    /** Tests: the gates' and posterns' doors. */
    public static List<BlockPos> doorsForTests(UUID id) {
        State st = STATES.get(id);
        return st == null ? List.of() : List.copyOf(st.doors);
    }

    /** Tests: the walkway's level at a column of a side. */
    public static int topForTests(UUID id, int side, int across) {
        State st = STATES.get(id);
        return st == null || st.wall == null ? Integer.MIN_VALUE : st.wall.top(side, across);
    }

    /** Tests: a batch of the works now, as a hand would lay it. */
    public static int workForTests(ServerLevel level, Villages.Village v) {
        State st = STATES.computeIfAbsent(v.id(), k -> new State());
        st.lastWork = -100000L;
        return work(level, v, st, HAND, BATCH);
    }
}
