package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [house34] Terraces: rows of houses sharing their side walls along a street, built as one, each house a home of its own.
 *
 * <p>Three of them, so a town's streets show who lives where (drawn by tools/terrace_design.py):
 * <ul>
 * <li><b>the workers' terrace</b> ({@code terrace}): four two-up two-downs in brick, front doors straight onto the
 *     street, a yard behind each with its coal bunker, privy and gate to the back lane, a stack up every party wall.
 *     From the Stone Age, for the working households: the poor and those getting by;</li>
 * <li><b>the bay-fronted terrace</b> ({@code terrace2}): three taller houses with bay windows, porches, front gardens
 *     behind low walls and dormers in the roof, for the comfortable, from the Iron Age;</li>
 * <li><b>the grand terrace</b> ({@code terrace3}): three Georgian town houses four storeys high, steps up to the door,
 *     iron railings round the area, tall windows, a cornice and parapet: for the well-off and the wealthy of a town.</li>
 * </ul>
 * A row goes on the register as one building, on a long street front (village/TerraceLots: a long lot by an avenue
 * turned to face it, or two lots side by side), and the builders raise it like anything else, every block out of the
 * stores. Where the street climbs, each house steps with it: each stands at the height of the street before its own
 * door, the party walls carried up and down between them, so a row on a hill is a flight of houses, not one floor cut
 * into the slope (the steps are kept by the row's anchor and every laying-out of it reads them: BuildGoal.layout).
 *
 * <p>Each house is a home on the books (Homes), let and sold like any house, its rent by its size (a cottage's, a
 * two-storey house's, a villa's). Who gets one: a household waiting for a home is offered a house in the row of its
 * standing first (its best-off grown folk's: Wealth); a grand house only to the well-off; and a household renting
 * below its standing moves up when a house of its kind stands empty. When the town wants homes, it builds the row its
 * households are short of, in place of a house: a terrace puts four homes (or three big ones) on the ground of two.
 *
 * <p>Once it stands the town's hands finish it house by house out of the stores (TownJobs): each front door painted
 * its household's colour (a dye), the grand row's railings and balconettes in iron (the smith's bars), a chimney pot on
 * each house's flue and its hearth lit when somebody lives there, and its number and name by its door.
 */
public final class TerraceRows {

    private TerraceRows() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** A house of a row, on the homes' books. */
    public static final String HOUSE = "rowhouse";

    private static final String STEPS = "terrace/steps/", COST = "terrace/cost/", DRESSED = "terrace/dressed/", OPENED = "terrace/opened/";

    /**
     * A row, as its drawing has it: its name on the register, what it is called, what its rows are called street by
     * street, its party walls (dx), its houses ({left, right, mirrored} interior columns), the column of each door in a
     * house, the row its front wall is on (dz), the floor its door opens on, its size for the rent, and the Wealth it is for.
     */
    public enum Tier {
        WORKERS("terrace", "workers' terrace", "a workers' terrace", new String[]{ "Cottages", "Row", "Yard" },
            new int[]{ -10, -5, 0, 5, 10 }, new int[][]{ { -9, -6, 0 }, { -4, -1, 1 }, { 1, 4, 0 }, { 6, 9, 1 } }, 3, -5, 0, 1, 2, 7,
            "two-up two-down cottages in brick, front doors on the street and a yard behind each"),
        BAY("terrace2", "bay-fronted terrace", "a bay-fronted terrace", new String[]{ "Terrace", "Villas", "Gardens" },
            new int[]{ -10, -3, 4, 11 }, new int[][]{ { -9, -4, 0 }, { -2, 3, 0 }, { 5, 10, 0 } }, 5, -2, 0, 2, 4, 8,
            "taller houses with bay windows, porches, front gardens and dormers"),
        GRAND("terrace3", "grand terrace", "a grand terrace", new String[]{ "Place", "Parade", "Crescent" },
            new int[]{ -10, -3, 4, 11 }, new int[][]{ { -9, -4, 0 }, { -2, 3, 0 }, { 5, 10, 0 } }, 5, -2, 2, 3, 4, 16,
            "Georgian town houses four storeys high, steps up to the door and iron railings round the area");

        public final String structure, word, a, looks;
        final String[] names;
        final int[] walls;
        final int[][] houses;
        final int door, front, stand, size, back, eaves;

        Tier(String structure, String word, String a, String[] names, int[] walls, int[][] houses, int door, int front, int stand,
             int size, int back, int eaves, String looks) {
            this.back = back;
            this.eaves = eaves;
            this.structure = structure;
            this.word = word;
            this.a = a;
            this.names = names;
            this.walls = walls;
            this.houses = houses;
            this.door = door;
            this.front = front;
            this.stand = stand;
            this.size = size;
            this.looks = looks;
        }

        public int count() { return houses.length; }

        int left(int i) { return houses[i][0]; }
        int right(int i) { return houses[i][1]; }

        /** The column of house i's front door. */
        int doorDx(int i) { return houses[i][2] == 1 ? houses[i][1] - door : houses[i][0] + door; }

        /** The row of standing a household of this Wealth wants. */
        public static Tier forWealth(Wealth.Tier w) {
            return switch (w) {
                case POOR, GETTING_BY -> WORKERS;
                case COMFORTABLE -> BAY;
                default -> GRAND;
            };
        }
    }

    /** The row this register name (or drawing, a theme's "x/terrace2" too) is, or null. */
    @Nullable
    public static Tier tier(@Nullable String structure) {
        if (structure == null) return null;
        int slash = structure.indexOf('/');
        String s = slash >= 0 ? structure.substring(slash + 1) : structure;
        for (Tier t : Tier.values()) if (t.structure.equals(s)) return t;
        return null;
    }

    public static boolean isRow(@Nullable String structure) {
        return tier(structure) != null;
    }

    static boolean isHouse(Homes.Home h) {
        return HOUSE.equals(h.structure);
    }

    // ------------------------------------------------------------------ the steps of a row on a slope

    /** Each row's houses' floors over its anchor, by its anchor (an empty array: level). */
    private static final Map<Long, int[]> STEP_CACHE = new ConcurrentHashMap<>();
    /** Steps worked out for a lot being looked at (Villages.siteFor), by village and anchor, until one is chosen. */
    private static final Map<String, int[]> PROPOSED = new ConcurrentHashMap<>();
    /** Each drawing laid out with its steps, worked out once. */
    private static final Map<String, List<Blueprints.Cell>> STEPPED = new ConcurrentHashMap<>();
    private static final int[] LEVEL = new int[0];

    public static void resetForTests() {
        STEP_CACHE.clear();
        PROPOSED.clear();
        STEPPED.clear();
        PLANS.clear();
        DRESS_LOOK.clear();
        FINISHED.clear();
        PIECES.clear();
        WORTH.clear();
        WANT.clear();
    }

    /** The steps of the row at this anchor (the floor of each house over the anchor's), or an empty array if level. */
    public static int[] steps(BlockPos anchor) {
        int[] have = STEP_CACHE.get(anchor.asLong());
        if (have != null) return have;
        int[] got = LEVEL;
        for (Villages.Village v : Villages.every()) {
            String s = Ledger.note(v.id(), STEPS + anchor.asLong());
            if (s != null && !s.isEmpty()) { got = parse(s); break; }
        }
        if (!Villages.every().isEmpty()) STEP_CACHE.put(anchor.asLong(), got);
        return got;
    }

    private static int[] parse(String s) {
        String[] p = s.split(",");
        int[] out = new int[p.length];
        try {
            for (int i = 0; i < p.length; i++) out[i] = Integer.parseInt(p[i].trim());
        } catch (NumberFormatException e) {
            return LEVEL;
        }
        return out;
    }

    /** Keep a row's steps (by its anchor) on the town's books. */
    public static void recordSteps(UUID village, BlockPos anchor, int[] steps) {
        boolean level = true;
        for (int s : steps) if (s != 0) level = false;
        StringBuilder sb = new StringBuilder();
        for (int s : steps) sb.append(sb.length() == 0 ? "" : ",").append(s);
        Ledger.note(village, STEPS + anchor.asLong(), level ? "" : sb.toString());
        STEP_CACHE.put(anchor.asLong(), level ? LEVEL : steps.clone());
        PLANS.clear();
    }

    /** Villages.siteFor: the lot chosen for a row, its steps kept. */
    public static void chosen(UUID village, String project, Villages.Site site) {
        if (!isRow(project)) return;
        int[] s = PROPOSED.remove(village + "|" + site.anchor().asLong());
        if (s != null) recordSteps(village, site.anchor(), s);
        LOG.info("[MCA-TERRACE] {}: {} on the lot at {} (its back {}), its houses' floors {}", Villages.name(village), project,
            site.anchor().toShortString(), site.facing(), s == null ? "level" : Arrays.toString(s));
        Ledger.note(village, SITE_NOTE + project, site.anchor().getX() + "," + site.anchor().getY() + "," + site.anchor().getZ() + ","
            + site.facing().get3DDataValue() + "," + site.radius());
    }

    /** [house34b] The lot a row was going up on, kept over a restart (Ledger note). */
    static final String SITE_NOTE = "terrace/site/";

    /**
     * [house34b] Villages.siteFor: a row begun before a restart, its ground again. The town's chosen lots live only in
     * memory: a row half up when the server stopped was left standing half built, and the town chose another lot for its
     * next and began again there. Only a row actually begun (a good part of its footing laid) is taken up again; one not
     * yet started may go wherever the town now likes, and the note is let go.
     */
    @Nullable
    public static Villages.Site kept(ServerLevel level, UUID village, String project) {
        if (!isRow(project)) return null;
        String raw = Ledger.note(village, SITE_NOTE + project);
        if (raw == null || raw.isEmpty()) return null;
        Villages.Site site;
        try {
            String[] p = raw.split(",");
            site = new Villages.Site(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])),
                Direction.from3DDataValue(Integer.parseInt(p[3])), Integer.parseInt(p[4]));
        } catch (RuntimeException e) {
            Ledger.forget(village, SITE_NOTE + project);
            return null;
        }
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (b.anchor().equals(site.anchor())) { Ledger.forget(village, SITE_NOTE + project); return null; }   // built
        }
        if (!level.isLoaded(site.anchor())) return null;
        int footing = 0, laid = 0;
        for (BuildGoal.Placement pl : BuildGoal.plan(project, site.anchor(), site.facing(), 13)) {
            if (pl.part() != BuildGoal.Part.BLOCK || pl.pos().getY() > site.anchor().getY()) continue;
            footing++;
            if (!level.getBlockState(pl.pos()).isAir() && !BuildGoal.soft(level.getBlockState(pl.pos()))) laid++;
        }
        if (footing == 0 || laid * 2 < footing) { Ledger.forget(village, SITE_NOTE + project); return null; }
        return site;
    }

    private static int step(int[] steps, int i) {
        return steps.length > i ? steps[i] : 0;
    }

    /** The houses whose ground a column of the drawing is part of: its own house, or both, for a party wall. */
    private static int[] owners(Tier t, int dx) {
        int a = -1, b = -1;
        for (int i = 0; i < t.count(); i++) {
            if (dx >= t.left(i) - 1 && dx <= t.right(i) + 1) {
                if (a < 0) a = i; else b = i;
            }
        }
        return b >= 0 ? new int[]{ a, b } : a >= 0 ? new int[]{ a } : new int[]{ 0 };
    }

    /** How much a block of the drawing wins a place over another laid there by the neighbour's steps: a wall over a roof. */
    private static int weight(Blueprints.Key k) {
        if (k.part() == BuildGoal.Part.WINDOW) return 4;
        if (k.part() != BuildGoal.Part.BLOCK) return 3;
        return switch (k.style()) {
            case ROOF_STAIR, ROOF_STAIR_TOP, ROOF_SLAB, ROOF_SLAB_TOP, ROOF_BLOCK -> 1;
            case FOUNDATION, SOIL -> 2;
            default -> 4;
        };
    }

    /**
     * The drawing with each house at its own height: every cell of a house's own columns lifted by its step, and a party
     * wall's by both its houses' (it is the wall of each), where two meet in one place the wall over the roof and the
     * higher house over the lower: a lower house's roof runs into its higher neighbour's wall, the wall's gable showing
     * above it, and a higher house's footing sits on its lower neighbour's wall.
     */
    static List<Blueprints.Cell> stepped(String drawing, int[] steps) {
        Tier t = tier(drawing);
        boolean level = true;
        for (int s : steps) if (s != 0) level = false;
        if (t == null || level) return Blueprints.cells(drawing);
        String key = drawing + "|" + Arrays.toString(steps);
        if (STEPPED.size() > 256) STEPPED.clear();
        return STEPPED.computeIfAbsent(key, k -> {
            Map<Long, Blueprints.Cell> at = new HashMap<>();
            Map<Long, Integer> rank = new HashMap<>();
            for (Blueprints.Cell c : Blueprints.cells(drawing)) {
                for (int i : owners(t, c.dx())) {
                    int s = step(steps, i);
                    int h = c.h() + s;
                    long p = ((long) (c.dx() & 0xfffff) << 40) | ((long) (h & 0xfffff) << 20) | (c.dz() & 0xfffff);
                    int r = weight(c.key()) * 1000 + s;
                    Integer was = rank.get(p);
                    if (was != null && was >= r) continue;
                    rank.put(p, r);
                    at.put(p, new Blueprints.Cell(c.dx(), h, c.dz(), c.key()));
                }
            }
            List<Blueprints.Cell> out = new ArrayList<>(at.values());
            out.sort(Comparator.comparingInt(Blueprints.Cell::h).thenComparingInt(c -> -c.dz()).thenComparingInt(Blueprints.Cell::dx));
            return List.copyOf(out);
        });
    }

    /** BuildGoal.layout: a row laid out at its anchor with its houses' steps. */
    public static void layout(String drawing, BlockPos anchor, Direction facing, List<BuildGoal.Placement> out) {
        Direction right = facing.getClockWise();
        for (Blueprints.Cell c : stepped(drawing, steps(anchor))) {
            out.add(new BuildGoal.Placement(anchor.relative(right, c.dx()).relative(facing, c.dz()).above(c.h()),
                c.key().part(), c.key().style(), c.key().way()));
        }
    }

    // ------------------------------------------------------------------ its ground

    /** Neighbours' floors may differ by this much; the row's lowest and highest by this. */
    static final int STEP_MOST = 2, SPREAD_MOST = 6;
    /** A house's ground may be cut or made up by this much at the most. */
    static final int CUT_MOST = 6;

    /**
     * Villages.siteFor: the floor for a row on this lot, its middle at x, z (in the world) and its back toward
     * {@code back}, or null if the ground will not take it. Each house's floor is the street's height before its own
     * door; neighbours step at most two, and nothing stands on water. The anchor is at the lowest floor; the steps over it
     * are kept until a lot is chosen (chosen).
     */
    @Nullable
    public static BlockPos ground(ServerLevel level, UUID village, String project, int x, int z, Direction back) {
        Tier t = tier(project);
        if (t == null) return null;
        Direction right = back.getClockWise();
        BlockPos base = new BlockPos(x, 0, z);
        int n = t.count();
        int[] floor = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos street = base.relative(right, t.doorDx(i)).relative(back, -6);
            if (!level.hasChunk(street.getX() >> 4, street.getZ() >> 4)) return null;
            int top = BuildGoal.groundTop(level, street.getX(), street.getZ());
            if (!level.getFluidState(new BlockPos(street.getX(), top - 1, street.getZ())).isEmpty()
                    || !level.getFluidState(new BlockPos(street.getX(), top, street.getZ())).isEmpty()) return null;
            floor[i] = top;
        }
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            lo = Math.min(lo, floor[i]);
            hi = Math.max(hi, floor[i]);
            if (i > 0 && Math.abs(floor[i] - floor[i - 1]) > STEP_MOST) return null;
        }
        if (hi - lo > SPREAD_MOST) return null;
        // The ground under each house: not water, not a cliff to cut or a hollow to make up.
        for (int i = 0; i < n; i++) {
            for (int dx = t.left(i) - 1; dx <= t.right(i) + 1; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos c = base.relative(right, dx).relative(back, dz);
                    if (!level.hasChunk(c.getX() >> 4, c.getZ() >> 4)) return null;
                    int top = BuildGoal.groundTop(level, c.getX(), c.getZ());
                    if (!level.getFluidState(new BlockPos(c.getX(), top - 1, c.getZ())).isEmpty()) return null;
                    if (Math.abs(top - floor[i]) > CUT_MOST) return null;
                }
            }
        }
        int[] steps = new int[n];
        for (int i = 0; i < n; i++) steps[i] = floor[i] - lo;
        BlockPos anchor = new BlockPos(x, lo, z);
        PROPOSED.put(village + "|" + anchor.asLong(), steps);
        if (PROPOSED.size() > 512) PROPOSED.clear();
        return anchor;
    }

    /**
     * BuildGoal.addTerrainWork, for a row: each house's ground made to its own floor (made up under it, its outer faces in
     * stone, earth under its rooms; the ground standing in its rooms, its yard and its garden dug out), the trees on it
     * felled and what grows wild in it taken up. Only within the row's own columns: never the street at its ends.
     */
    public static void terrainWork(net.minecraft.world.level.Level level, BlockPos base, Direction facing, String structure,
                                   List<BuildGoal.Placement> plan) {
        Tier t = tier(structure);
        if (t == null) return;
        int[] steps = steps(base);
        Direction right = facing.getClockWise();
        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        for (BuildGoal.Placement p : plan) taken.add(p.pos());
        int x0 = t.walls[0], x1 = t.walls[t.walls.length - 1];
        for (int dx = x0; dx <= x1; dx++) {
            int[] own = owners(t, dx);
            int s = Integer.MAX_VALUE;
            for (int i : own) s = Math.min(s, step(steps, i));
            int floorY = base.getY() + s;
            int top = 0;
            for (int i : own) top = Math.max(top, step(steps, i));
            for (int dz = -5; dz <= 5; dz++) {
                BlockPos col = base.relative(right, dx).relative(facing, dz);
                if (!level.hasChunk(col.getX() >> 4, col.getZ() >> 4)) continue;
                int g = BuildGoal.groundTop(level, col.getX(), col.getZ());
                boolean face = dx == x0 || dx == x1 || dz == -5 || dz == 5;
                for (int y = Math.max(g, floorY - 8); y < floorY; y++) {
                    BlockPos c = new BlockPos(col.getX(), y, col.getZ());
                    if (taken.add(c)) plan.add(new BuildGoal.Placement(c, BuildGoal.Part.BLOCK,
                        face ? Blueprints.Style.FOUNDATION : Blueprints.Style.SOIL, Blueprints.Way.UP));
                }
                int digTop = base.getY() + top + 12;
                for (int y = floorY; y <= digTop; y++) {
                    BlockPos c = new BlockPos(col.getX(), y, col.getZ());
                    if (taken.contains(c)) continue;
                    BlockState there = level.getBlockState(c);
                    if (SiteWork.litter(level, c, there)) plan.add(new BuildGoal.Placement(c, BuildGoal.Part.CLEAR, Blueprints.Style.GENERIC, Blueprints.Way.UP));
                    else if (Terraform.earth(there) && !there.is(Blocks.BEDROCK)) plan.add(new BuildGoal.Placement(c, BuildGoal.Part.CLEAR, Blueprints.Style.SOIL, Blueprints.Way.UP));
                }
            }
        }
        // a front garden's beds: gravel or sand where the flowers go is dug out first, and earth laid in its place
        java.util.Set<BlockPos> flowers = new java.util.HashSet<>();
        for (BuildGoal.Placement p : plan) if (p.part() == BuildGoal.Part.FLOWER) flowers.add(p.pos());
        List<BuildGoal.Placement> beds = new ArrayList<>();
        for (BuildGoal.Placement p : plan) {
            if (p.part() != BuildGoal.Part.BLOCK || p.style() != Blueprints.Style.SOIL || !flowers.contains(p.pos().above())) continue;
            BlockState there = level.getBlockState(p.pos());
            if (!there.is(net.minecraft.tags.BlockTags.DIRT) && Terraform.earth(there) && !there.is(Blocks.BEDROCK)) {
                beds.add(new BuildGoal.Placement(p.pos(), BuildGoal.Part.CLEAR, Blueprints.Style.SOIL, Blueprints.Way.UP));
            }
        }
        plan.addAll(beds);
        for (BlockPos c : BuildGoal.treeOnLot(level, structure, base, facing)) {
            plan.add(new BuildGoal.Placement(c, BuildGoal.Part.CLEAR, Blueprints.Style.GENERIC, Blueprints.Way.UP));
        }
    }

    // ------------------------------------------------------------------ the rows and their houses

    /** One house of a row: the row, which house, and its anchor on the homes' books. */
    record Spot(Ledger.Building row, Tier tier, int index, BlockPos anchor) {}

    /** The town's rows, in the order they went up. */
    static List<Ledger.Building> rows(UUID village) {
        List<Ledger.Building> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(village)) if (isRow(b.structure())) out.add(b);
        return out;
    }

    static BlockPos cell(Ledger.Building b, int dx, int h, int dz) {
        return b.anchor().relative(b.facing().getClockWise(), dx).relative(b.facing(), dz).above(h);
    }

    /** A spot in the world in the row's drawing: {across, up, toward the back}. */
    static int[] local(Ledger.Building b, BlockPos p) {
        int x = p.getX() - b.anchor().getX(), z = p.getZ() - b.anchor().getZ();
        Direction f = b.facing(), r = f.getClockWise();
        return new int[]{ x * r.getStepX() + z * r.getStepZ(), p.getY() - b.anchor().getY(), x * f.getStepX() + z * f.getStepZ() };
    }

    /** Every house of a row, left to right along its front. */
    static List<Spot> spots(Ledger.Building b) {
        Tier t = tier(b.structure());
        List<Spot> out = new ArrayList<>();
        if (t == null) return out;
        int[] steps = steps(b.anchor());
        for (int i = 0; i < t.count(); i++) {
            out.add(new Spot(b, t, i, cell(b, t.doorDx(i), t.stand + step(steps, i), t.front + 1)));
        }
        return out;
    }

    @Nullable
    static Spot spotAt(UUID village, BlockPos anchor) {
        for (Ledger.Building b : rows(village)) {
            if (Math.abs(anchor.getX() - b.anchor().getX()) > 12 || Math.abs(anchor.getZ() - b.anchor().getZ()) > 12) continue;
            for (Spot s : spots(b)) if (s.anchor().equals(anchor)) return s;
        }
        return null;
    }

    /** Homes: the register's entry for a house of a row (its row's way round, at the house's own anchor), or null. */
    @Nullable
    static Ledger.Building building(UUID village, long anchor) {
        Spot s = spotAt(village, BlockPos.of(anchor));
        return s == null ? null : new Ledger.Building(HOUSE, s.anchor(), s.row().facing());
    }

    @Nullable
    static Ledger.Building building(UUID village, String key) {
        try {
            return building(village, Long.parseLong(key));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Is this spot in this house: between its party walls, from the street to the back of its yard, footing to chimney? */
    static boolean inside(Spot s, BlockPos p) {
        int[] d = local(s.row(), p);
        int st = step(steps(s.row().anchor()), s.index());
        return d[0] >= s.tier().left(s.index()) && d[0] <= s.tier().right(s.index()) && d[2] >= -5 && d[2] <= 5
            && d[1] - st >= -1 && d[1] - st <= 20;
    }

    /** Homes.homeAt: is this spot in this house of a row? */
    static boolean inside(UUID village, Homes.Home h, BlockPos p) {
        if (Math.abs(p.getX() - h.anchor.getX()) > 14 || Math.abs(p.getZ() - h.anchor.getZ()) > 14) return false;
        Spot s = spotAt(village, h.anchor);
        return s != null && inside(s, p);
    }

    /** Homes.enrol: every house of the town's rows on the books, empty until somebody takes it. */
    static void enrol(UUID village, Map<Long, Homes.Home> homes) {
        for (Ledger.Building b : rows(village)) {
            for (Spot s : spots(b)) {
                if (homes.containsKey(s.anchor().asLong())) continue;
                Homes.Home h = new Homes.Home(s.anchor(), HOUSE);
                homes.put(s.anchor().asLong(), h);
                Homes.save(village, h);
            }
        }
    }

    private static final Map<String, List<BuildGoal.Placement>> PLANS = new ConcurrentHashMap<>();

    /** A row's drawing laid out where it stands (its theme's drawing, its steps). */
    static List<BuildGoal.Placement> plan(UUID village, Ledger.Building b) {
        String drawing = Themes.design(village, b.structure());
        if (PLANS.size() > 64) PLANS.clear();
        return PLANS.computeIfAbsent(drawing + "|" + b.anchor().asLong() + "|" + b.facing(),
            k -> List.copyOf(BuildGoal.plan(drawing, b.anchor(), b.facing(), 13)));
    }

    private static Direction lie(BuildGoal.Placement p, Direction facing) {
        return p.way() == Blueprints.Way.UP ? facing : Blueprints.world(p.way(), facing);
    }

    /** The heads of the beds made up in a house of a row: its own (from the drawing) and any bought for a child. */
    static List<BlockPos> bedsIn(ServerLevel level, UUID village, Homes.Home h) {
        List<BlockPos> out = new ArrayList<>();
        Spot s = spotAt(village, h.anchor);
        if (s == null || !level.isLoaded(h.anchor)) return out;
        for (BuildGoal.Placement p : plan(village, s.row())) {
            if (p.part() != BuildGoal.Part.BED || !inside(s, p.pos())) continue;
            BlockPos head = p.pos().relative(lie(p, s.row().facing()));
            if (Homes.isBedHead(level, head) && !out.contains(head)) out.add(head);
        }
        String extra = Ledger.note(village, "homebeds/" + h.anchor.asLong());
        if (extra != null && !extra.isEmpty()) {
            for (String e : extra.split(",")) {
                try {
                    BlockPos head = BlockPos.of(Long.parseLong(e));
                    if (Homes.isBedHead(level, head) && !out.contains(head)) out.add(head);
                } catch (NumberFormatException ignored) { }
            }
        }
        out.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return out;
    }

    /** The household's chest: the one by its bed upstairs, else any of its own. */
    @Nullable
    static BlockPos chestOf(ServerLevel level, UUID village, Homes.Home h) {
        Spot s = spotAt(village, h.anchor);
        if (s == null || !level.isLoaded(h.anchor)) return null;
        BlockPos best = null;
        for (BuildGoal.Placement p : plan(village, s.row())) {
            if (p.part() != BuildGoal.Part.CHEST || !inside(s, p.pos())) continue;
            if (!(level.getBlockState(p.pos()).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) continue;
            if (best == null || p.pos().getY() < best.getY()) best = p.pos();
        }
        return best;
    }

    /** Where a child's bed goes: two clear cells on a floor of the house, as far from the grown-ups' beds as it allows. */
    @Nullable
    static BlockPos bedSpot(ServerLevel level, UUID village, Homes.Home h, List<BlockPos> beds) {
        Spot s = spotAt(village, h.anchor);
        if (s == null) return null;
        double cx = h.anchor.getX(), cz = h.anchor.getZ();
        if (!beds.isEmpty()) {
            cx = 0;
            cz = 0;
            for (BlockPos b : beds) { cx += b.getX(); cz += b.getZ(); }
            cx /= beds.size();
            cz /= beds.size();
        }
        BlockPos best = null;
        double far = -1;
        java.util.Set<Integer> floors = new java.util.TreeSet<>();
        for (BlockPos b : beds) floors.add(b.getY());
        if (floors.isEmpty()) floors.add(h.anchor.getY());
        for (BuildGoal.Placement p : plan(village, s.row())) {
            if (p.part() != BuildGoal.Part.BLOCK || p.style() != Blueprints.Style.FLOOR) continue;
            BlockPos c = p.pos().above();
            if (!floors.contains(c.getY()) || !inside(s, c)) continue;
            Direction lie = Homes.spotLie(level, c);
            BlockPos head = c.relative(lie);
            if (!inside(s, head) || !Homes.clear(level, c) || !Homes.clear(level, head)) continue;
            double d = (c.getX() - cx) * (c.getX() - cx) + (c.getZ() - cz) * (c.getZ() - cz);
            if (d > far) { far = d; best = c; }
        }
        return best;
    }

    /** Decor: the house's own part of its row's rooms. */
    static boolean decorInside(UUID village, BlockPos houseAnchor, BlockPos p) {
        Spot s = spotAt(village, houseAnchor);
        return s != null && inside(s, p);
    }

    /** Decor: a house's front door, or null. */
    @Nullable
    static BlockPos frontDoor(UUID village, BlockPos houseAnchor) {
        Spot s = spotAt(village, houseAnchor);
        if (s == null) return null;
        return cell(s.row(), s.tier().doorDx(s.index()), s.tier().stand + step(steps(s.row().anchor()), s.index()), s.tier().front);
    }

    /** Decor: the row a house is in (its register entry), or null. */
    @Nullable
    static Ledger.Building rowOf(UUID village, BlockPos houseAnchor) {
        Spot s = spotAt(village, houseAnchor);
        return s == null ? null : s.row();
    }

    // ------------------------------------------------------------------ names

    /** What the town calls a row: its street and its kind, "Mill Lane Cottages", the next of its kind there "Mill Lane Row". */
    public static String name(UUID village, Ledger.Building b) {
        Tier t = tier(b.structure());
        if (t == null) return Villages.spoken(b.structure());
        Villages.Village v = Villages.get(village);
        String street = street(village, v, b);
        int i = 0;
        for (Ledger.Building o : rows(village)) {
            if (o.anchor().equals(b.anchor())) break;
            if (tier(o.structure()) == t && street.equals(street(village, v, o))) i++;
        }
        return street + " " + t.names[i % t.names.length];
    }

    private static String street(UUID village, @Nullable Villages.Village v, Ledger.Building b) {
        String[] a = v == null ? null : TownLife.address(village, v.centre(), b);
        return a != null ? a[1] : Villages.name(village);
    }

    /** "No. 2, Mill Lane Cottages". */
    static String address(UUID village, Homes.Home h) {
        Spot s = spotAt(village, h.anchor);
        return s == null ? "a house at " + h.anchor.getX() + ", " + h.anchor.getZ() : "No. " + (s.index() + 1) + ", " + name(village, s.row());
    }

    /** What kind of house it is, for the books: "a house in a workers' terrace". */
    static String kind(UUID village, Homes.Home h) {
        Spot s = spotAt(village, h.anchor);
        return s == null ? "terraced house" : s.tier() == Tier.WORKERS ? "workers' cottage" : s.tier() == Tier.BAY ? "bay-fronted house" : "town house";
    }

    /** Its tier, for a house of a row (null for any other home). */
    @Nullable
    static Tier tierOf(UUID village, Homes.Home h) {
        if (!isHouse(h)) return null;
        Spot s = spotAt(village, h.anchor);
        return s == null ? null : s.tier();
    }

    /** How big it is for its rent (Homes.size): a cottage's one, a bay-fronted house's two, a town house's three. */
    static int size(UUID village, Homes.Home h) {
        Tier t = tierOf(village, h);
        return t == null ? 1 : t.size;
    }

    /** The register's word for a row (Villages.spoken). */
    @Nullable
    public static String spoken(String structure) {
        Tier t = tier(structure);
        return t == null ? null : t.a;
    }

    /** For a sign or a title: "The Workers' Terrace". */
    @Nullable
    public static String title(String structure) {
        Tier t = tier(structure);
        if (t == null) return null;
        String w = t.word;
        StringBuilder sb = new StringBuilder("The");
        for (String part : w.split(" ")) sb.append(' ').append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return sb.toString();
    }

    // ------------------------------------------------------------------ who lives where

    /**
     * [house34b] A household's standing for its house: its means as the housing market reckons them (means), on the
     * folk's scale (Wealth.tier). It was its best-off grown folk's whole worth, tools and clothes and comforts at home
     * and all: a miner's iron pick and a coat made a household with nothing put by "comfortable", and the towns built
     * bay-fronted rows for folk who could not keep them.
     */
    static Wealth.Tier standing(List<VillageFolkEntity> household) {
        return Wealth.tier(means(household));
    }

    /** [house34b] What a household has for a house: what it can put to one (HousingMarket.cashOf: its purses over its living,
     *  its savings at the bank, what it has put by toward its house) and the share of a house it owns. */
    static int means(List<VillageFolkEntity> household) {
        UUID village = null;
        for (VillageFolkEntity f : household) if (f.ownerId() != null) { village = f.ownerId(); break; }
        if (village == null) return 0;
        int n = HousingMarket.cashOf(village, household);
        for (VillageFolkEntity f : Homes.grown(household)) n += Homes.ownedShare(f);
        return n;
    }

    /** May this household have this house of a row? A town house is for the well-off; a bay-fronted house for those who can
     *  pay its rent; a cottage for anybody. */
    static boolean mayHave(UUID village, Homes.Home h, List<VillageFolkEntity> household) {
        Tier t = tierOf(village, h);
        if (t == null) return true;
        Wealth.Tier w = standing(household);
        return switch (t) {
            case WORKERS -> true;
            case BAY -> w.ordinal() >= Wealth.Tier.COMFORTABLE.ordinal() || Homes.affords(household, h) || Homes.rentFree(household);
            case GRAND -> w.ordinal() >= Wealth.Tier.WELL_OFF.ordinal();
        };
    }

    /** Homes.vacancyFor: the empty house of a row of this household's standing, with beds enough, or null. */
    @Nullable
    static Homes.Home vacancyFor(ServerLevel level, UUID village, List<VillageFolkEntity> household) {
        Tier want = Tier.forWealth(standing(household));
        int adults = Homes.grown(household).size();
        Homes.Home best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Homes.Home h : Homes.homes(village).values()) {
            if (!isHouse(h) || !h.vacant() || h.tenure == Homes.Tenure.PLAYER || !level.isLoaded(h.anchor)) continue;
            Tier t = tierOf(village, h);
            if (t == null || !mayHave(village, h, household)) continue;
            int beds = Homes.bedsIn(level, village, h).size();
            if (beds < Math.max(1, adults)) continue;
            int score = Math.abs(t.ordinal() - want.ordinal()) * 10 + (t.ordinal() > want.ordinal() ? 5 : 0);
            if (score < bestScore) { bestScore = score; best = h; }
        }
        // A household of its kind's row only: an empty cottage is not offered to the wealthy while a house would do.
        return best != null && tierOf(village, best) == want ? best : null;
    }

    /**
     * Homes.tick: a household renting below its standing (a cottage while it is comfortable, a bay-fronted house while it
     * is well off, or a plain house) moves up into an empty house of its kind's row, what it had put by toward buying
     * its old one given back to it. One move a round.
     */
    static void moveUp(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (Homes.Home h : new ArrayList<>(Homes.homes(id).values())) {
            if (h.members.isEmpty() || h.tenure != Homes.Tenure.RENTED || Homes.seat(h) || Flats.isFlat(h)) continue;
            List<VillageFolkEntity> household = Homes.loadedMembers(id, h);
            if (household.isEmpty() || household.size() < h.members.size()) continue;
            Tier want = Tier.forWealth(standing(household));
            Tier now = tierOf(id, h);
            int nowRank = now == null ? (h.structure.equals("house") ? 0 : 99) : now.ordinal();
            if (want.ordinal() <= nowRank) continue;
            Homes.Home better = vacancyFor(level, id, household);
            if (better == null || better == h || tierOf(id, better) != want) continue;
            if (Homes.bedsIn(level, id, better).size() < Homes.grown(household).size()) continue;
            Homes.giveBack(household, h);
            String from = Homes.address(id, v, h);
            Homes.settle(level, v, better, household, day);
            Villages.tell(id, day, Homes.names(household) + " moved up from " + from + " to " + address(id, better) + ", " + want.a);
            for (VillageFolkEntity f : Homes.grown(household)) f.persona().remember(day, "we moved up to " + address(id, better), 7);
            return;
        }
    }

    // ------------------------------------------------------------------ what the town builds

    /** Why the town would build a row now, by village: the row and the words (Villages.whyBuild). */
    private static final Map<UUID, String[]> WANT = new ConcurrentHashMap<>();

    /**
     * Villages.projectsWanted: when the town wants a house, the row its households are short of goes ahead of it: a
     * grand terrace in a town (the Iron Age and thirty folk) with well-off households and no town house for them, a
     * bay-fronted terrace from the Iron Age with comfortable households wanting, and otherwise, from the Stone Age, a
     * workers' terrace: four homes on the ground of two houses. One row at a time.
     */
    public static List<String> wanted(UUID village, List<String> projects) {
        int house = projects.indexOf("house");
        for (Tier t : Tier.values()) if (projects.contains(t.structure)) return projects;
        // one going up already: that one stays on the list ahead of the house till it is built ([house34b]: it was dropped
        // from the list the moment its lot was chosen, and the builders went on raising houses while the row's lot waited)
        for (String k : Villages.sitesOf(village).keySet()) {
            if (isRow(k)) { projects.add(house < 0 ? projects.size() : house, k); return projects; }
        }
        if (house < 0) { WANT.remove(village); return projects; }
        Villages.Age age = Villages.ageOf(village);
        if (age.ordinal() < Villages.Age.STONE.ordinal() || Villages.headcount(village) < 12) { WANT.remove(village); return projects; }
        Tier t = choose(village);
        if (t == null) { WANT.remove(village); return projects; }
        projects.add(house, t.structure);
        return projects;
    }

    /** Which row the town is short of, and why (kept for Villages.whyBuild), or null. */
    @Nullable
    static Tier choose(UUID village) {
        int[] want = new int[3], free = new int[3];
        for (Homes.Home h : Homes.homes(village).values()) {
            Tier t = tierOf(village, h);
            if (t != null && h.vacant()) free[t.ordinal()]++;
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isShowcase() && !f.isHired()) folk.add(f);
        java.util.Set<UUID> counted = new java.util.HashSet<>();
        for (VillageFolkEntity f : folk) {
            if (f.isBaby() || counted.contains(f.getUUID())) continue;
            Homes.Home h = Homes.homeOf(village, f.getUUID());
            List<VillageFolkEntity> household = new ArrayList<>();
            if (h == null) household.add(f);
            else for (UUID m : h.members) { VillageFolkEntity o = Homes.loaded(village, m); if (o != null) household.add(o); }
            for (VillageFolkEntity o : household) counted.add(o.getUUID());
            Tier t = Tier.forWealth(standing(household));
            Tier now = h == null ? null : tierOf(village, h);
            boolean wants;
            if (h == null) wants = true;                                        // nowhere of its own
            else if (h.tenure != Homes.Tenure.RENTED || Homes.seat(h)) wants = false;
            else if (now != null) wants = now.ordinal() < t.ordinal();          // a row below its standing
            else wants = t != Tier.WORKERS && (h.structure.equals("house") || Flats.isFlat(h));   // a plain house or a flat, and it could do better
            if (wants) want[t.ordinal()]++;
        }
        Villages.Rank rank = Villages.rank(village);
        String why;
        Tier pick;
        if (rank.ordinal() >= Villages.Rank.TOWN.ordinal() && want[2] > free[2]) {
            pick = Tier.GRAND;
            why = want[2] + (want[2] == 1 ? " well-off household wants" : " well-off households want") + " a town house of its standing";
        } else if (rank.ordinal() >= Villages.Rank.VILLAGE.ordinal() && Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal()
                && want[1] > free[1]) {
            pick = Tier.BAY;
            why = want[1] + (want[1] == 1 ? " comfortable household wants" : " comfortable households want") + " a house with a garden";
        } else if (want[0] + want[1] + want[2] - free[0] - free[1] - free[2] >= 2) {
            pick = Tier.WORKERS;
            why = "homes for the working households, four to a lot";
        } else {
            WANT.remove(village);
            return null;
        }
        WANT.put(village, new String[]{ pick.structure, why });
        return pick;
    }

    /** Villages.whyBuild: why this row. */
    public static String why(UUID village, String structure) {
        Tier t = tier(structure);
        if (t == null) return structure;
        String[] w = WANT.get(village);
        return t.a + " of " + (t.count() == 4 ? "four" : "three") + " homes on one lot, " + t.looks
            + (w != null && w[0].equals(structure) ? ": " + w[1] : "");
    }

    /** The beds the town's rows hold (Villages.bedsPlanned, Villages.housing). */
    public static int bedsPlanned(UUID village) {
        int n = 0;
        for (Ledger.Building b : rows(village)) {
            for (Blueprints.Cell c : Blueprints.cells(b.structure())) if (c.key().part() == BuildGoal.Part.BED) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ what a house is worth

    private static final Map<String, Double> WORTH = new ConcurrentHashMap<>();

    /** HousingMarket.worth: a house of a row is worth its share of what its row costs to build today. */
    static double worth(UUID village, Homes.Home h) {
        Spot s = spotAt(village, h.anchor);
        if (s == null) return 35;
        Double w = WORTH.get(village + "|" + s.row().structure());
        if (w != null) return w;
        String kept = Ledger.note(village, COST + s.row().structure());
        if (kept != null && !kept.isEmpty()) {
            try { return Double.parseDouble(kept); } catch (NumberFormatException ignored) { }
        }
        double base = s.tier() == Tier.WORKERS ? 30 : s.tier() == Tier.BAY ? 60 : 110;
        return base * (1.0 + 0.25 * Villages.ageOf(village).ordinal());
    }

    /** Each row's cost reckoned once a day (its blocks at the town's prices, its builders' hours), shared among its houses. */
    static void cost(ServerLevel level, UUID village) {
        for (Ledger.Building b : rows(village)) {
            Tier t = tier(b.structure());
            String key = village + "|" + b.structure();
            int[] r = HousingMarket.replacement(level, village, b.structure());
            double each = Math.max(1, (r[0] + r[1]) / (double) t.count());
            WORTH.put(key, each);
            Ledger.note(village, COST + b.structure(), String.format(java.util.Locale.ROOT, "%.1f", each));
        }
    }

    // ------------------------------------------------------------------ finishing a row, house by house

    /** Every round of the homes (Homes.tick): the rows finished a piece at a time, those who can move up moved. */
    static void tick(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<Ledger.Building> rows = rows(id);
        if (rows.isEmpty()) return;
        if (!(Long.toString(day)).equals(Ledger.note(id, COST + "day"))) {
            cost(level, id);
            Ledger.note(id, COST + "day", Long.toString(day));
        }
        opened(id, day);
        moveUp(level, v, day);
    }

    private static final Map<UUID, Long> FINISHED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> PIECES = new ConcurrentHashMap<>();

    /** Grow's clock (every five seconds): the next piece of the finishing of the town's rows, by a hand sent to it. */
    static void finish(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = FINISHED.get(id);
        if (last != null && now - last < 100L && now >= last) return;
        FINISHED.put(id, now);
        for (Ledger.Building b : rows(id)) {
            if (!Land.areaLoaded(level, b.anchor(), 12) || Ledger.raising(id, b.anchor())) continue;
            if (!standing(level, id, b)) continue;
            int n = dress(level, v, b, false);
            if (n > 0) {
                int all = PIECES.merge(b.anchor().asLong() + "", n, Integer::sum);
                if (all == n || all % 200 < n) LOG.info("[MCA-TERRACE] {}: the hands finishing {} ({} pieces so far)", Villages.name(id), name(id, b), all);
                break;                                          // one piece of work a round
            }
        }
    }

    /** Is the row up (its doors hung)? */
    static boolean standing(ServerLevel level, UUID village, Ledger.Building b) {
        Tier t = tier(b.structure());
        for (Spot s : spots(b)) {
            BlockPos door = cell(b, t.doorDx(s.index()), t.stand + step(steps(b.anchor()), s.index()), t.front);
            if (!(level.getBlockState(door).getBlock() instanceof DoorBlock)) return false;
        }
        return true;
    }

    /** A row newly up goes into the chronicle. */
    private static void opened(UUID id, long day) {
        for (Ledger.Building b : rows(id)) {
            String key = OPENED + b.anchor().asLong();
            if (Ledger.note(id, key) != null) continue;
            Ledger.note(id, key, Long.toString(day));
            Ledger.forget(id, SITE_NOTE + b.structure());                    // [house34b] up: its lot no longer kept
            Tier t = tier(b.structure());
            Villages.tell(id, day, name(id, b) + " was built: " + t.a + ", " + t.count() + " homes in a row, " + t.looks);
        }
    }

    /** The painted doors: a colour each, as a dye; the door that colour of wood. */
    private record Paint(Block door, Item dye, String colour) {}

    private static final Paint[] WORKERS_DOORS = {
        new Paint(Blocks.DARK_OAK_DOOR, Items.BLACK_DYE, "black"), new Paint(Blocks.MANGROVE_DOOR, Items.RED_DYE, "red"),
        new Paint(Blocks.WARPED_DOOR, Items.GREEN_DYE, "green"), new Paint(Blocks.SPRUCE_DOOR, Items.BROWN_DYE, "brown"),
        new Paint(Blocks.BIRCH_DOOR, Items.WHITE_DYE, "cream") };
    private static final Paint[] BAY_DOORS = {
        new Paint(Blocks.MANGROVE_DOOR, Items.RED_DYE, "red"), new Paint(Blocks.WARPED_DOOR, Items.CYAN_DYE, "teal"),
        new Paint(Blocks.BAMBOO_DOOR, Items.YELLOW_DYE, "yellow"), new Paint(Blocks.CHERRY_DOOR, Items.PINK_DYE, "pink"),
        new Paint(Blocks.DARK_OAK_DOOR, Items.BLACK_DYE, "black"), new Paint(Blocks.CRIMSON_DOOR, Items.PURPLE_DYE, "plum") };
    private static final Paint[] GRAND_DOORS = {
        new Paint(Blocks.DARK_OAK_DOOR, Items.BLACK_DYE, "black"), new Paint(Blocks.MANGROVE_DOOR, Items.RED_DYE, "red"),
        new Paint(Blocks.WARPED_DOOR, Items.GREEN_DYE, "green"), new Paint(Blocks.CRIMSON_DOOR, Items.PURPLE_DYE, "plum") };

    private static final Map<Long, Integer> DRESS_LOOK = new ConcurrentHashMap<>();
    /** How many bricks a hand lays facing a row in a round. */
    static final int REFACE = 48;
    /** The rows' own crew (TownJobs): not the walls crew, which the age's make-over of every building keeps at one spot
     *  after another for days, while the rows stood half in cobble and timber waiting on it. */
    static final String ROWS_WORK = "terraces";
    /** [house34b] The wash on the yards' lines: sheets and shirts, white, blue, pink, yellow, grey. */
    private static final Block[] WASH = { Blocks.WHITE_WALL_BANNER, Blocks.LIGHT_BLUE_WALL_BANNER, Blocks.WHITE_WALL_BANNER,
        Blocks.PINK_WALL_BANNER, Blocks.YELLOW_WALL_BANNER, Blocks.LIGHT_GRAY_WALL_BANNER, Blocks.WHITE_WALL_BANNER };
    private static final Block[] WALL_BANNERS = { Blocks.WHITE_WALL_BANNER, Blocks.ORANGE_WALL_BANNER, Blocks.MAGENTA_WALL_BANNER,
        Blocks.LIGHT_BLUE_WALL_BANNER, Blocks.YELLOW_WALL_BANNER, Blocks.LIME_WALL_BANNER, Blocks.PINK_WALL_BANNER, Blocks.GRAY_WALL_BANNER,
        Blocks.LIGHT_GRAY_WALL_BANNER, Blocks.CYAN_WALL_BANNER, Blocks.PURPLE_WALL_BANNER, Blocks.BLUE_WALL_BANNER, Blocks.BROWN_WALL_BANNER,
        Blocks.GREEN_WALL_BANNER, Blocks.RED_WALL_BANNER, Blocks.BLACK_WALL_BANNER };

    /** The wall banner of a banner's colour, to hang it on a line. */
    @Nullable
    private static Block wallBanner(ItemStack banner) {
        if (!(banner.getItem() instanceof net.minecraft.world.item.BannerItem bi)) return null;
        net.minecraft.world.item.DyeColor c = bi.getColor();
        return WALL_BANNERS[c.getId() % WALL_BANNERS.length];
    }

    /** A whole block where a slab was drawn, and the slab it is cut down to. */
    private static final Map<Block, Block> SLAB_OF = Map.of(
        Blocks.COBBLESTONE, Blocks.COBBLESTONE_SLAB, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB, Blocks.STONE, Blocks.STONE_SLAB,
        Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_SLAB, Blocks.BRICKS, Blocks.BRICK_SLAB, Blocks.SMOOTH_STONE, Blocks.SMOOTH_STONE_SLAB,
        Blocks.ANDESITE, Blocks.ANDESITE_SLAB, Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_TILE_SLAB, Blocks.COBBLED_DEEPSLATE, Blocks.COBBLED_DEEPSLATE_SLAB,
        Blocks.SANDSTONE, Blocks.SANDSTONE_SLAB);

    /** Each house's own colour, by where it stands: never the same as the house beside it. */
    static Paint paint(Ledger.Building b, int i) {
        Tier t = tier(b.structure());
        Paint[] list = t == Tier.WORKERS ? WORKERS_DOORS : t == Tier.BAY ? BAY_DOORS : GRAND_DOORS;
        int seed = (int) (CityDress.mix(b.anchor(), 0x7e44ace5L) & 0x7fffffff);
        int stride = list.length % 2 == 1 || list.length > 4 ? 2 : 1;          // a different colour for every house of the row
        return list[(seed + i * stride) % list.length];
    }

    /**
     * A piece of the finishing of a row ({@code free}: all of it at once, for nothing): its brickwork faced in brick where
     * the builders had to make do with stone (a stretch at a time, the stone back into the stores), each front door painted, the grand
     * row's railings in iron, each house's chimney pots and its fire lit while it is lived in, the numbers and names by
     * the doors. Out of the stores, by a hand at work there; how many pieces were done.
     */
    public static int dress(ServerLevel level, @Nullable Villages.Village v, Ledger.Building b, boolean free) {
        Tier t = tier(b.structure());
        if (t == null || (!free && v == null)) return 0;
        UUID id = v == null ? null : v.id();
        int[] steps = steps(b.anchor());
        int done = 0;
        // the brickwork faced in brick where it went up in whatever the builders had (a stretch at a time)
        int laid = 0;
        boolean[] have = { free || Crafts.stock(level, v, st -> st.is(Items.BRICKS)) > 0, free || Crafts.stock(level, v, st -> st.is(Items.STONE_BRICKS)) > 0 };
        for (BuildGoal.Placement pl : plan(id, b)) {
            if (pl.part() != BuildGoal.Part.BLOCK) continue;
            // the brick in brick; and ([house34b]) the dressed stone (the grand row's ground floors, the bands, the quoins) in
            // stone bricks where the masons had only rough cobble
            boolean brick = pl.style() == Blueprints.Style.BRICK;
            if (!brick && pl.style() != Blueprints.Style.MASONRY) continue;
            BlockState there = level.getBlockState(pl.pos());
            if (!brick && !(there.is(Blocks.COBBLESTONE) || there.is(Blocks.MOSSY_COBBLESTONE))) continue;
            Block face = brick ? Blocks.BRICKS : Blocks.STONE_BRICKS;
            Item faceItem = brick ? Items.BRICKS : Items.STONE_BRICKS;
            if (there.is(face) || !there.isCollisionShapeFullBlock(level, pl.pos()) || there.hasBlockEntity()) continue;
            if (!have[brick ? 0 : 1]) continue;
            if (!free) {
                if (laid == 0 && !TownJobs.atWork(level, v, ROWS_WORK, pl.pos(), "facing " + name(id, b) + (brick ? " in brick" : " in dressed stone"))) break;
                if (Crafts.takeOne(level, v, st -> st.is(faceItem)).isEmpty()) { have[brick ? 0 : 1] = false; continue; }
                Crafts.giveBack(level, v, there.getBlock().asItem(), 1);                  // what came off goes back into the stores
            }
            level.setBlock(pl.pos(), face.defaultBlockState(), 2);
            laid++;
            if (!free && laid >= REFACE) break;
        }
        if (laid > 0) {
            done += laid;
            if (!free) return done;
        }
        // the roofs slated (the Iron Age's make-over of the town's roofs): the wood of every roof, porch and privy in slate
        if (id == null || Villages.ageOf(id).ordinal() >= Villages.Age.IRON.ordinal()) {
            int slated = 0;
            for (BuildGoal.Placement pl : plan(id, b)) {
                if (pl.part() != BuildGoal.Part.BLOCK || !BuildGoal.isRoof(pl.style())) continue;
                int[] d = local(b, pl.pos());
                int[] own = owners(t, d[0]);
                int st = step(steps, own[0]);
                if (d[1] - st < t.eaves && d[2] >= t.front && d[2] <= t.back) continue;      // the stairs and chairs indoors stay as they are
                BlockState there = level.getBlockState(pl.pos());
                Block slate = there.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS) ? Blocks.DEEPSLATE_TILE_STAIRS
                    : there.is(net.minecraft.tags.BlockTags.WOODEN_SLABS) ? Blocks.DEEPSLATE_TILE_SLAB
                    : there.is(net.minecraft.tags.BlockTags.PLANKS) ? Blocks.DEEPSLATE_TILES : null;
                if (slate == null) continue;
                if (!free) {
                    java.util.function.Predicate<ItemStack> tile = it -> it.is(Items.DEEPSLATE_TILES) || it.is(Items.COBBLED_DEEPSLATE);
                    if (slated == 0 && (Crafts.stock(level, v, tile) < 1
                            || !TownJobs.atWork(level, v, ROWS_WORK, pl.pos(), "slating the roof of " + name(id, b)))) break;
                    if (Crafts.takeOne(level, v, tile).isEmpty()) break;
                    Crafts.giveBack(level, v, there.getBlock().asItem(), 1);
                }
                level.setBlock(pl.pos(), copy(there, slate.defaultBlockState()), 2);
                slated++;
                if (!free && slated >= REFACE) break;
            }
            if (slated > 0) {
                done += slated;
                if (!free) return done;
            }
        }
        // the copings and steps that went up as whole blocks (stone to hand when there were no slabs) cut down to slabs
        int cut = 0;
        for (BuildGoal.Placement pl : plan(id, b)) {
            if (pl.part() != BuildGoal.Part.BLOCK || pl.style() != Blueprints.Style.STONE_SLAB) continue;
            BlockState there = level.getBlockState(pl.pos());
            Block slab = SLAB_OF.get(there.getBlock());
            if (slab == null) continue;
            if (!free && cut == 0 && !TownJobs.atWork(level, v, ROWS_WORK, pl.pos(), "cutting the copings of " + name(id, b))) break;
            level.setBlock(pl.pos(), slab.defaultBlockState(), 3);
            cut++;
            if (!free && cut >= REFACE) break;
        }
        if (cut > 0) {
            done += cut;
            if (!free) return done;
        }
        for (Spot s : spots(b)) {
            int i = s.index(), st = step(steps, i);
            // the front door, its household's colour
            BlockPos door = cell(b, t.doorDx(i), t.stand + st, t.front);
            BlockState was = level.getBlockState(door);
            Paint p = paint(b, i);
            if (was.getBlock() instanceof DoorBlock && !was.is(p.door())) {
                if (free || take(level, v, p.dye(), "painting the front door of " + address(id, s), door)) {
                    swapDoor(level, door, p.door());
                    done++;
                    if (!free) return done;
                }
            }
        }
        // the grand row's railings and balconettes, the smith's iron
        if (t == Tier.GRAND) {
            for (BuildGoal.Placement pl : plan(id, b)) {
                if (pl.part() != BuildGoal.Part.FENCE || local(b, pl.pos())[2] >= t.front) continue;   // out front: not the tables, the banisters
                BlockState there = level.getBlockState(pl.pos());
                if (!(there.getBlock() instanceof FenceBlock)) continue;
                if (!free && !take(level, v, Items.IRON_BARS, "putting up the iron railings of " + name(id, b), pl.pos())) break;
                BlockState bars = Blocks.IRON_BARS.defaultBlockState();
                level.setBlock(pl.pos(), Block.updateFromNeighbourShapes(bars, level, pl.pos()), 3);
                done++;
                if (!free) return done;
            }
            if (done > 0) for (BuildGoal.Placement pl : plan(id, b)) {
                if (pl.part() != BuildGoal.Part.FENCE || local(b, pl.pos())[2] >= t.front) continue;
                BlockState st = level.getBlockState(pl.pos());
                BlockState joined = Block.updateFromNeighbourShapes(st, level, pl.pos());
                if (joined != st) level.setBlock(pl.pos(), joined, 2);
            }
        }
        // the chimney pots, and each house's fire lit while it is lived in
        for (Spot s : spots(b)) {
            boolean lived = id != null && Homes.livedIn(id, s.anchor());
            List<BlockPos> tops = flues(level, b, s);
            for (int k = 0; k < tops.size(); k++) {
                BlockPos pot = tops.get(k).above();
                BlockState there = level.getBlockState(pot);
                boolean fire = k == 0 && lived;
                if (fire) {
                    if (there.getBlock() instanceof CampfireBlock) {
                        if (!there.getValue(CampfireBlock.LIT)) level.setBlock(pot, there.setValue(CampfireBlock.LIT, true), 3);
                        continue;
                    }
                    if (!there.isAir() && !there.is(net.minecraft.tags.BlockTags.FLOWER_POTS) && !there.is(Blocks.DECORATED_POT)) continue;
                    if (!free && !take(level, v, Items.CAMPFIRE, "laying the hearth fire in " + address(id, s), pot)) continue;
                    level.setBlock(pot, Blocks.CAMPFIRE.defaultBlockState(), 3);
                    done++;
                    if (!free) return done;
                } else {
                    if (there.getBlock() instanceof CampfireBlock && k == 0) {
                        if (there.getValue(CampfireBlock.LIT)) level.setBlock(pot, there.setValue(CampfireBlock.LIT, false), 3);
                        continue;
                    }
                    if (!there.isAir()) continue;
                    boolean decorated = ((CityDress.mix(s.anchor(), 0x5a17L + k) >>> 5) & 3) == 0;
                    if (!free && !take(level, v, decorated ? Items.DECORATED_POT : Items.FLOWER_POT, "setting a chimney pot on " + address(id, s), pot)) continue;
                    level.setBlock(pot, decorated ? Blocks.DECORATED_POT.defaultBlockState() : Blocks.FLOWER_POT.defaultBlockState(), 3);
                    done++;
                    if (!free) return done;
                }
            }
        }
        // [house34b] the washing lines across the workers' yards, party wall to party wall, the week's wash pegged out
        if (t == Tier.WORKERS) {
            for (Spot s : spots(b)) {
                int i = s.index(), st = step(steps, i), z = t.back + 1;
                List<BlockPos> line = new ArrayList<>();
                for (int dx = t.left(i) - 1; dx <= t.right(i) + 1; dx++) line.add(cell(b, dx, st + 2, z));
                boolean up = level.getBlockState(line.get(1)).getBlock() instanceof FenceBlock;
                if (!up) {
                    boolean clear = true;
                    for (BlockPos p : line) {
                        BlockState there = level.getBlockState(p);
                        if (!there.isAir() && !(there.getBlock() instanceof FenceBlock)) clear = false;
                    }
                    if (!clear) continue;
                    if (!free) {
                        if (Crafts.stock(level, v, it -> it.is(net.minecraft.tags.ItemTags.WOODEN_FENCES)) < line.size()
                                || !TownJobs.atWork(level, v, "yards", line.get(1), "putting up the washing line at " + address(id, s))) continue;
                        for (int k = 0; k < line.size(); k++) Crafts.takeOne(level, v, it -> it.is(net.minecraft.tags.ItemTags.WOODEN_FENCES));
                    }
                    BlockState post = Blocks.SPRUCE_FENCE.defaultBlockState();
                    for (BlockPos p : line) if (level.getBlockState(p).isAir()) level.setBlock(p, post, 2 | 16);
                    for (BlockPos p : line) level.setBlock(p, Block.updateFromNeighbourShapes(level.getBlockState(p), level, p), 2 | 16);
                    done++;
                    if (!free) return done;
                }
                // the wash: two pieces hung from the line over the middle of the yard
                for (int k = 1; k <= 2; k++) {
                    BlockPos p = cell(b, t.left(i) + k, st + 2, z + 1);
                    if (!level.getBlockState(p).isAir() || !level.getBlockState(p.below()).isAir()) continue;
                    if (!(level.getBlockState(cell(b, t.left(i) + k, st + 2, z)).getBlock() instanceof FenceBlock)) continue;
                    Block wash = WASH[(int) ((CityDress.mix(s.anchor(), 0x3a5L + k) >>> 3) % WASH.length)];
                    if (!free) {
                        ItemStack got = Crafts.stock(level, v, it -> it.is(net.minecraft.tags.ItemTags.BANNERS)) < 1 ? ItemStack.EMPTY
                            : TownJobs.atWork(level, v, "yards", p, "hanging out the washing at " + address(id, s))
                            ? Crafts.takeOne(level, v, it -> it.is(net.minecraft.tags.ItemTags.BANNERS)) : ItemStack.EMPTY;
                        if (got.isEmpty()) break;
                        Block wall = wallBanner(got);
                        if (wall != null) wash = wall;
                    }
                    level.setBlock(p, wash.defaultBlockState().setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, b.facing()), 3);
                    done++;
                    if (!free) return done;
                }
            }
        }
        // the numbers and names by the doors
        for (Spot s : spots(b)) {
            BlockPos at = signSpot(b, s);
            BlockState there = level.getBlockState(at);
            BlockPos wall = at.relative(b.facing());
            String[] lines = signLines(id, s);
            if (there.getBlock() instanceof WallSignBlock) {
                if (level.getBlockEntity(at) instanceof SignBlockEntity sign && !Arrays.equals(lines, read(sign))) TownLife.write(sign, lines);
                continue;
            }
            if (!there.isAir() || !level.getBlockState(wall).isSolid()) continue;
            if (!free && !take(level, v, Items.OAK_SIGN, "putting up the number by the door of " + address(id, s), at)) continue;
            Block signBlock = t == Tier.GRAND ? Blocks.DARK_OAK_WALL_SIGN : t == Tier.BAY ? Blocks.SPRUCE_WALL_SIGN : Blocks.OAK_WALL_SIGN;
            level.setBlock(at, signBlock.defaultBlockState().setValue(WallSignBlock.FACING, b.facing().getOpposite()), 3);
            if (level.getBlockEntity(at) instanceof SignBlockEntity sign) TownLife.write(sign, lines);
            done++;
            if (!free) return done;
        }
        return done;
    }

    private static String address(@Nullable UUID village, Spot s) {
        return "No. " + (s.index() + 1) + ", " + (village == null ? s.tier().word : name(village, s.row()));
    }

    private static String[] read(SignBlockEntity sign) {
        String[] out = new String[4];
        for (int i = 0; i < 4; i++) out[i] = sign.getFrontText().getMessage(i, false).getString();
        return out;
    }

    /** The sign by a house's door: its number, its row's name, and who lives there (or "To let"). */
    static String[] signLines(@Nullable UUID village, Spot s) {
        String row = village == null ? s.tier().names[0] : name(village, s.row());
        String street = row, kind = "";
        int sp = row.lastIndexOf(' ');
        if (row.length() > 15 && sp > 0) { street = row.substring(0, sp); kind = row.substring(sp + 1); }
        String who = "";
        if (village != null) {
            Homes.Home h = Homes.homes(village).get(s.anchor().asLong());
            if (h != null && !h.members.isEmpty()) {
                List<VillageFolkEntity> m = Homes.loadedMembers(village, h);
                for (VillageFolkEntity f : m) if (!f.isBaby()) { who = f.displayNameCap(); break; }
            } else if (h != null) {
                who = "To let";
            }
        }
        if (who.length() > 15) who = who.substring(0, 15);
        if (street.length() > 15) street = street.substring(0, 15);
        return kind.isEmpty() ? new String[]{ "No. " + (s.index() + 1), street, who, "" } : new String[]{ "No. " + (s.index() + 1), street, kind, who };
    }

    /** Where a house's sign hangs, on its front by the door: the pier beside a cottage's door, the bay's pier by the path, by a
     *  town house's door over the area. */
    static BlockPos signSpot(Ledger.Building b, Spot s) {
        Tier t = s.tier();
        int i = s.index(), st = step(steps(b.anchor()), i);
        boolean m = t.houses[i][2] == 1;
        return switch (t) {
            case WORKERS -> cell(b, m ? t.right(i) - 2 : t.left(i) + 2, 1 + st, t.front - 1);
            case BAY -> cell(b, t.left(i) + 4, 2 + st, t.front - 2);          // on the bay's pier by the path
            case GRAND -> cell(b, t.left(i) + 4, 3 + st, t.front - 1);
        };
    }

    /** The tops of a house's flues: the stacks on its party walls, a place on each for it (its left stack's first, the
     *  first it lights). Each stack's top cells, the house to the left of a wall taking the front one, to the right the back. */
    static List<BlockPos> flues(ServerLevel level, Ledger.Building b, Spot s) {
        Tier t = s.tier();
        int i = s.index();
        List<BlockPos> out = new ArrayList<>();
        int[] walls = { t.left(i) - 1, t.right(i) + 1 };
        Map<String, BlockPos> top = new HashMap<>();
        for (BuildGoal.Placement p : plan(null, b)) {
            if (p.part() != BuildGoal.Part.BLOCK || p.style() != Blueprints.Style.BRICK) continue;
            int[] d = local(b, p.pos());
            if (d[0] != walls[0] && d[0] != walls[1]) continue;
            if (d[1] < (t == Tier.GRAND ? 17 : t == Tier.BAY ? 10 : 10)) continue;
            String k = d[0] + "|" + d[2];
            BlockPos was = top.get(k);
            if (was == null || p.pos().getY() > was.getY()) top.put(k, p.pos());
        }
        for (int w = 0; w < 2; w++) {
            int wall = walls[w];
            List<int[]> cols = new ArrayList<>();
            for (String k : top.keySet()) {
                String[] q = k.split("\\|");
                if (Integer.parseInt(q[0]) == wall) cols.add(new int[]{ Integer.parseInt(q[1]) });
            }
            if (cols.isEmpty()) continue;
            cols.sort(Comparator.comparingInt(c -> c[0]));
            // the house on the wall's left (w == 1: this house is left of its right wall) takes the front cell
            int[] pick = w == 1 ? cols.get(0) : cols.get(cols.size() - 1);
            BlockPos p = top.get(wall + "|" + pick[0]);
            if (p != null && level.getBlockState(p).isSolid()) out.add(p);
        }
        // the stack whose top is highest first: the house's own hearth
        out.sort(Comparator.comparingInt((BlockPos p) -> -p.getY()));
        return out;
    }

    private static boolean take(ServerLevel level, Villages.Village v, Item item, String what, BlockPos at) {
        if (Crafts.stock(level, v, s -> s.is(item)) < 1) return false;
        if (!TownJobs.atWork(level, v, "furniture", at, what)) return false;
        ItemStack got = Crafts.takeOne(level, v, s -> s.is(item));
        return !got.isEmpty();
    }

    /** A door changed for one of another wood, as it hangs: its way, its hinge, open or shut. */
    static void swapDoor(ServerLevel level, BlockPos lower, Block to) {
        BlockState was = level.getBlockState(lower);
        if (!(was.getBlock() instanceof DoorBlock)) return;
        if (was.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) lower = lower.below();
        was = level.getBlockState(lower);
        BlockState now = copy(was, to.defaultBlockState());
        BlockState up = copy(level.getBlockState(lower.above()), to.defaultBlockState());
        level.setBlock(lower, now, 2 | 16);
        level.setBlock(lower.above(), up, 2 | 16);
        level.sendBlockUpdated(lower, was, now, 3);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static BlockState copy(BlockState from, BlockState to) {
        for (Property p : from.getProperties()) if (to.hasProperty(p)) to = to.setValue(p, from.getValue(p));
        return to;
    }

    // ------------------------------------------------------------------ the books

    /** The Buildings page (Annals): a row's name, its houses let and empty. */
    static void annals(UUID village, Ledger.Building b, CompoundTag c) {
        Tier t = tier(b.structure());
        if (t == null) return;
        Map<Long, Homes.Home> homes = Homes.homes(village);
        int let = 0, living = 0;
        for (Spot s : spots(b)) {
            Homes.Home h = homes.get(s.anchor().asLong());
            if (h != null && !h.members.isEmpty()) {
                let++;
                living += h.members.size();
            }
        }
        c.putString("title", name(village, b) + " (" + t.word + ")");
        c.putInt("storeys", t == Tier.GRAND ? 4 : 2);
        c.putInt("living", living);
        c.putString("tenure", let + " of " + t.count() + " houses lived in");
    }

    /** For the status line (Homes.line): "; terraces: 7 of 10 houses lived in". */
    static String line(UUID village) {
        int n = 0, let = 0;
        for (Homes.Home h : Homes.homes(village).values()) {
            if (!isHouse(h)) continue;
            n++;
            if (!h.members.isEmpty()) let++;
        }
        return n == 0 ? "" : "; terraces: " + let + " of " + n + " houses lived in";
    }

    // ------------------------------------------------------------------ staging and tests

    /**
     * The pictures (/village terrace stage, ops): on a flat stage at the spot, a paved street running east, and along its
     * south side the three rows end to end, a cross street between each, every one dressed as a town finishes it; or, on
     * "slope", the street climbing a block every five and a workers' and a bay-fronted row stepping up it. In the Iron
     * Age's look (brick, dressed stone, slate), not out of any stores. Returns where each row's anchor is.
     */
    public static String stage(ServerLevel level, BlockPos at, String kind) {
        boolean slope = "slope".equals(kind);
        String[] rows = slope ? new String[]{ "terrace", "terrace2" } : new String[]{ "terrace", "terrace2", "terrace3" };
        int x0 = at.getX() - 8, x1 = at.getX() + 25 * rows.length + 8, z0 = at.getZ() - 14, z1 = at.getZ() + 12;
        int y = at.getY();
        for (int x = x0; x <= x1; x++) {
            int rise = slope ? Math.max(0, (x - at.getX()) / 6) : 0;
            for (int z = z0; z <= z1; z++) {
                for (int h = -6; h <= 40; h++) {
                    BlockPos p = new BlockPos(x, y + h, z);
                    BlockState want = h < rise - 1 ? Blocks.DIRT.defaultBlockState() : h == rise - 1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.AIR.defaultBlockState();
                    if (level.getBlockState(p) != want) level.setBlock(p, want, 2 | 16);
                }
                // the street before the rows, paved, with its kerbs
                if (z >= at.getZ() - 11 && z <= at.getZ() - 7) {
                    BlockPos road = new BlockPos(x, y + rise - 1, z);
                    boolean kerb = z == at.getZ() - 7 || z == at.getZ() - 11;
                    level.setBlock(road, (kerb ? Blocks.SMOOTH_STONE : Blocks.STONE_BRICKS).defaultBlockState(), 2 | 16);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.length; i++) {
            Tier t = tier(rows[i]);
            int cx = at.getX() + 10 + 25 * i;
            Direction back = Direction.SOUTH;
            BlockPos base = new BlockPos(cx, y, at.getZ());
            int[] steps = new int[t.count()];
            int lo = Integer.MAX_VALUE;
            Direction right = back.getClockWise();
            for (int k = 0; k < t.count(); k++) {
                BlockPos street = base.relative(right, t.doorDx(k)).relative(back, -6);
                steps[k] = BuildGoal.groundTop(level, street.getX(), street.getZ());
                lo = Math.min(lo, steps[k]);
            }
            for (int k = 0; k < t.count(); k++) steps[k] -= lo;
            BlockPos anchor = base.atY(lo);
            // the ground under each house made to its floor, as the builders make it
            for (int dx = t.walls[0]; dx <= t.walls[t.walls.length - 1]; dx++) {
                int[] own = owners(t, dx);
                int s = Integer.MAX_VALUE;
                for (int o : own) s = Math.min(s, steps[o]);
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos col = anchor.relative(right, dx).relative(back, dz);
                    for (int h = -4; h <= 30; h++) {
                        BlockPos p = col.above(h);
                        level.setBlock(p, h < s - 1 ? Blocks.DIRT.defaultBlockState() : h == s - 1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                }
            }
            Ledger.Building b = stampForTests(level, rows[i], anchor, back, steps, null);
            dress(level, null, b, true);
            out.append(out.length() == 0 ? "" : "; ").append(rows[i]).append(' ').append(anchor.getX()).append(' ').append(anchor.getY())
                .append(' ').append(anchor.getZ()).append(" steps ").append(Arrays.toString(steps));
        }
        return out.toString();
    }

    /** Tests and the camera: a row stamped at once, its steps as given, dressed for nothing. Returns its register entry
     *  (not put on any town's register). */
    public static Ledger.Building stampForTests(ServerLevel level, String structure, BlockPos anchor, Direction facing, int[] steps,
                                                @Nullable UUID village) {
        if (village != null) recordSteps(village, anchor, steps);
        else STEP_CACHE.put(anchor.asLong(), steps.clone());
        PLANS.clear();
        BuildGoal.stamp(level, structure, anchor, facing, 13, com.jrpetty.mcassistant.Showcase.painter(Ages.CIVIC));
        return new Ledger.Building(structure, anchor, facing);
    }

    /** Tests: the steps proposed for a lot looked at (by anchor), or null. */
    @Nullable
    public static int[] proposedForTests(UUID village, BlockPos anchor) {
        return PROPOSED.get(village + "|" + anchor.asLong());
    }

    /** Tests: each house's anchor on the homes' books. */
    public static List<BlockPos> housesForTests(Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        for (Spot s : spots(b)) out.add(s.anchor());
        return out;
    }

    /** Tests: the front door of house i. */
    public static BlockPos doorForTests(Ledger.Building b, int i) {
        Tier t = tier(b.structure());
        return cell(b, t.doorDx(i), t.stand + step(steps(b.anchor()), i), t.front);
    }

    /** Tests: the address of the house at this anchor. */
    public static String addressForTests(UUID village, BlockPos anchor) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        return h == null ? "" : address(village, h);
    }

    /** Tests: the tier of the house at this anchor ("WORKERS"...), or "". */
    public static String tierForTests(UUID village, BlockPos anchor) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        Tier t = h == null ? null : tierOf(village, h);
        return t == null ? "" : t.name();
    }

    /** Tests: which row the town would build now, and why, or null. */
    @Nullable
    public static String[] chooseForTests(UUID village) {
        Tier t = choose(village);
        return t == null ? null : WANT.get(village);
    }

    /** Tests: the houses' sign lines. */
    public static String[] signForTests(UUID village, Ledger.Building b, int i) {
        return signLines(village, spots(b).get(i));
    }

    /** Tests: the composed drawing with steps (cells). */
    public static List<Blueprints.Cell> steppedForTests(String drawing, int[] steps) {
        return stepped(drawing, steps);
    }

    /** Tests: a folk with nothing on its back (its clothes count to its standing: Wealth). */
    public static void undressForTests(VillageFolkEntity f) {
        Arrays.fill(f.style().worn, ItemStack.EMPTY);
    }

    /** Tests: one round of the rows (finishing, moving up), now. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v, level.getDayTime() / 24000L);
    }
}
