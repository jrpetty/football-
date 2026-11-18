package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * [city9] The town's look as a whole: whether it reads as a place laid out and grown, not a scatter and not a stamp.
 * <ul>
 * <li><b>ct01</b> (not required, minutes long): towns grown for some days on a meadow cut by a channel of water east of
 *     the square, and on rolling hills, then measured: the buildings whose street is walked from the heart, the streets
 *     walked, the homes' ways round, the works in the heart, farmland among the houses, bare earth left lying, the
 *     square's greens, the avenues' lamps. Self-contained (it walks the streets itself), so it runs on an older build
 *     for the before and after.</li>
 * <li><b>ct02</b>: a lot over the water is not on the town's streets until a bridge carries its street over.</li>
 * <li><b>ct04</b>: each plot closed in its own way (a fence and a gate, a low wall, or open), and two fenced neighbours
 *     sharing the side between them, not standing two fences side by side.</li>
 * <li><b>ct03</b>: the square's four corners laid to turf with a tree and flowers out of the stores, never in the
 *     courtyard; a grown tree's low leaves over the square trimmed, its crown kept.</li>
 * </ul>
 * x 3,900,000 to 4,000,000, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CityLookGameTests {

    private static final int Z = 90000;
    private static final double DAYS = Double.parseDouble(System.getProperty("ct.days", "3"));
    private static final int FOLK = Integer.getInteger("ct.folk", 40);
    private static final String GROUNDS = System.getProperty("ct.grounds", "lake,hills");

    // ============================================================ ct01: the audit

    @GameTest(template = "empty", timeoutTicks = 900000, required = false, batch = "ct01_audit")
    public static void ct01_audit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Kit.reset(level);
        Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        List<Growth> towns = new ArrayList<>();
        int x = 3900000;
        for (String g : GROUNDS.split(",")) {
            towns.add(new Growth(helper, level, g.trim(), x, Z));
            x += 30000;
        }
        server.tickRateManager().setTickRate(10000.0F);
        final boolean[] over = { false };
        java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> each = e -> {
            if (over[0]) return;
            try {
                boolean all = true;
                for (Growth t : towns) if (!t.done) { t.step(); all &= t.done; }
                if (!all) return;
                over[0] = true;
                server.tickRateManager().setTickRate(20.0F);
                Kit.Expect expect = new Kit.Expect();
                for (Growth t : towns) if (t.failure != null) expect.that(false, t.ground + ": " + t.failure);
                helper.runAfterDelay(1, () -> { if (expect.clean()) helper.succeed(); else helper.fail(expect.summary()); });
            } catch (RuntimeException ex) {
                over[0] = true;
                server.tickRateManager().setTickRate(20.0F);
                helper.runAfterDelay(1, () -> helper.fail("the run threw " + ex));
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(each);
    }

    /** A town grown on its ground, measured at the end. */
    static final class Growth {
        final GameTestHelper helper;
        final ServerLevel level;
        final String ground;
        final BlockPos heart;
        UUID id;
        long began = -1;
        int lastDay = -1;
        boolean done;
        String failure;

        Growth(GameTestHelper helper, ServerLevel level, String ground, int cx, int cz) {
            this.helper = helper;
            this.level = level;
            this.ground = ground;
            int outer = FoundingPlan.coreRadius(FOLK) + FoundingPlan.BAND_MAX;
            int r = Math.max(outer + 40, TownPlan.reach() + 12);
            if (ground.equals("hills")) {
                BuildAuditGameTests.shape(level, BuildAuditGameTests.Ground.HILLS, cx, cz, r, 9101);
            } else {
                BuildAuditGameTests.shape(level, BuildAuditGameTests.Ground.MEADOW, cx, cz, r, 9100);
                if (ground.equals("lake")) channel(level, cx, cz, r);
            }
            this.heart = Kit.surface(level, cx, cz);
            Kit.log("[VT] ct01 " + ground + " found " + FOLK + ": " + Kit.command(level, "village found " + FOLK + " " + cx + " " + cz));
        }

        void step() {
            long t = helper.getTick();
            if (began < 0) {
                if (Founding.near(level, heart, 8)) return;
                Villages.Village v = Villages.nearest(level, heart, 96);
                if (v == null) { failure = "no village at " + heart; done = true; return; }
                id = v.id();
                began = t;
                Villages.ageForTests(id, Villages.Age.IRON);
                stock(level, v);
                return;
            }
            Villages.Village v = Villages.get(id);
            if (v == null) { failure = "the village is gone"; done = true; return; }
            long ran = t - began;
            int day = (int) (ran / 24000L);
            if (day != lastDay) {
                lastDay = day;
                stock(level, v);
                Kit.log("[VT] ct01 " + ground + " day " + day + ": " + Villages.headcount(id) + " folk, " + Ledger.buildings(id).size() + " buildings");
            }
            if (ran >= (long) (DAYS * 24000L)) {
                Map<String, Object> a = audit(level, v);
                Kit.log("[VT] ct01 " + ground + " AUDIT " + a);
                done = true;
            }
        }
    }

    /** The growth's stores, and what the greens and the avenues' trees are made of. */
    static void stock(ServerLevel level, Villages.Village v) {
        Kit.stockForGrowth(level, v);
        Object[][] want = { { Items.OAK_SAPLING, 24 }, { Items.BIRCH_SAPLING, 8 }, { Items.DIRT, 64 }, { Items.DANDELION, 16 } };
        for (Object[] w : want) {
            net.minecraft.world.item.Item it = (net.minecraft.world.item.Item) w[0];
            int need = (Integer) w[1] - com.jrpetty.mcassistant.entity.Market.stock(level, v.id(), s -> s.is(it));
            if (need > 0) com.jrpetty.mcassistant.entity.Homes.storeForTests(level, v, new ItemStack(it, need));
        }
    }

    /** A channel of water three wide, two deep, down the line of the first street east of the square, all the way. */
    static void channel(ServerLevel level, int cx, int cz, int r) {
        int base = Kit.surface(level, cx, cz).getY();
        for (int dx = TownPlan.RING; dx < TownPlan.RING + TownPlan.STREET; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int y = base - 3; y < base + 6; y++) {
                    level.setBlock(new BlockPos(cx + dx, y, cz + dz), y < base - 1 ? Blocks.WATER.defaultBlockState()
                        : y == base - 1 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the measures

    static final Set<String> WORKS = Set.of("smeltery", "smithy", "workshop", "brewery", "foundry", "redstoneworks", "wheelwright", "fletcher");
    static final Set<String> HOMES = Set.of("house", "house2", "manor", "villa", "flats", "flats4");

    /** The town measured: see the class comment. */
    static Map<String, Object> audit(ServerLevel level, Villages.Village v) {
        Map<String, Object> out = new TreeMap<>();
        UUID id = v.id();
        BlockPos h = v.centre();
        int reach = Villages.townReach(id);
        boolean[][] walked = walk(level, h, TownPlan.reach() + TownPlan.STREET + 1);
        int off = TownPlan.reach() + TownPlan.STREET + 1;
        int planned = 0, onStreets = 0, homes = 0, homesX = 0, works = 0, worksInCore = 0;
        int maxHome = 0;
        List<String> offList = new ArrayList<>();
        java.util.Set<Long> footprint = new java.util.HashSet<>();
        for (Ledger.Building b : Ledger.buildings(id)) {
            int dx = b.anchor().getX() - h.getX(), dz = b.anchor().getZ() - h.getZ();
            int[] g = BuildGoal.footprint(b.structure());
            int gh = Math.max(g[0], g[1]) + 1;
            for (int ox = -gh; ox <= gh; ox++) for (int oz = -gh; oz <= gh; oz++)
                footprint.add(BlockPos.asLong(dx + ox, 0, dz + oz));
            if (WORKS.contains(b.structure())) {
                works++;
                if (Math.max(Math.abs(dx), Math.abs(dz)) <= TownPlan.RING + TownPlan.STREET + TownPlan.LOT) worksInCore++;
            }
            if (HOMES.contains(b.structure())) {
                homes++;
                if (b.facing().getAxis() == Direction.Axis.X) homesX++;
                maxHome = Math.max(maxHome, Math.max(Math.abs(dx), Math.abs(dz)));
            }
            TownPlan.Lot lot = null;
            for (TownPlan.Lot l : TownPlan.lots()) if (l.x() == dx && l.z() == dz) { lot = l; break; }
            if (lot == null) continue;
            planned++;
            int[] f = front(lot);
            boolean on = false;
            boolean alongX = lot.back() == TownPlan.NORTH || lot.back() == TownPlan.SOUTH;
            for (int k = -2; k <= 2 && !on; k++) {
                int x = alongX ? f[0] + k : f[0], z = alongX ? f[1] : f[1] + k;
                if (Math.abs(x) <= off && Math.abs(z) <= off && walked[x + off][z + off]) on = true;
            }
            if (on) onStreets++;
            else offList.add(b.structure() + "@" + dx + "," + dz);
        }
        int cells = 0, walkedCells = 0, scars = 0, farmInHomes = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = h.getX() + dx, z = h.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                if (TownPlan.isStreet(dx, dz)) {
                    cells++;
                    if (walked[dx + off][dz + off]) walkedCells++;
                    continue;
                }
                if (TownPlan.isSquare(dx, dz) || footprint.contains(BlockPos.asLong(dx, 0, dz))) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockState top = level.getBlockState(new BlockPos(x, y - 1, z));
                if (top.is(Blocks.FARMLAND)) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) < maxHome - TownPlan.LOT) farmInHomes++;
                    continue;
                }
                if ((top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT) || top.is(Blocks.ROOTED_DIRT))
                        && level.getBlockState(new BlockPos(x, y, z)).isAir()) scars++;
            }
        }
        int greens = 0;
        for (int[] m : new int[][]{ { 9, 9 }, { -9, 9 }, { -9, -9 }, { 9, -9 } }) {
            BlockPos p = Kit.surface(level, h.getX() + m[0], h.getZ() + m[1]);
            BlockPos q = p;
            for (int i = 0; i < 8 && level.getBlockState(q.below()).is(BlockTags.LOGS); i++) q = q.below();
            BlockState st = level.getBlockState(q);
            if (st.getBlock() instanceof SaplingBlock || level.getBlockState(q).is(BlockTags.LOGS)
                    || level.getBlockState(q.below()).is(BlockTags.LOGS)) greens++;
        }
        out.put("buildings", Ledger.buildings(id).size());
        out.put("folk", Villages.headcount(id));
        out.put("planned", planned);
        out.put("offStreets", planned - onStreets);
        out.put("offList", offList.size() > 8 ? offList.subList(0, 8) : offList);
        out.put("streetsWalkedPct", cells == 0 ? 0 : 100 * walkedCells / cells);
        out.put("homes", homes);
        out.put("homesFacingXPct", homes == 0 ? 0 : 100 * homesX / homes);
        out.put("works", works);
        out.put("worksInCore", worksInCore);
        out.put("farmlandAmongHomes", farmInHomes);
        out.put("bareEarth", scars);
        out.put("squareGreens", greens);
        return out;
    }

    /** The cell straight out from a lot's door, from the heart. */
    static int[] front(TownPlan.Lot lot) {
        int reach = (lot.kind() == TownPlan.Kind.LONG ? lot.halfDeep() : TownPlan.LOT / 2) + 1;
        return switch (lot.back()) {
            case TownPlan.NORTH -> new int[]{ lot.x(), lot.z() + reach };
            case TownPlan.SOUTH -> new int[]{ lot.x(), lot.z() - reach };
            case TownPlan.EAST -> new int[]{ lot.x() - reach, lot.z() };
            default -> new int[]{ lot.x() + reach, lot.z() };
        };
    }

    /** The square and the plan's streets walked from the heart: a block up, three down, never into water. */
    static boolean[][] walk(ServerLevel level, BlockPos heart, int r) {
        int side = 2 * r + 1;
        boolean[][] seen = new boolean[side][side];
        int[][] floor = new int[side][side];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        seen[r][r] = true;
        floor[r][r] = heart.getY() - 1;
        q.add(new int[]{ 0, 0 });
        int[][] steps = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int hgt = floor[c[0] + r][c[1] + r];
            for (int[] s : steps) {
                int nx = c[0] + s[0], nz = c[1] + s[1];
                if (Math.abs(nx) > r || Math.abs(nz) > r || seen[nx + r][nz + r]) continue;
                if (!TownPlan.isStreet(nx, nz) && !TownPlan.isSquare(nx, nz)) continue;
                int f = floorAt(level, heart.getX() + nx, heart.getZ() + nz, hgt);
                if (f == Integer.MIN_VALUE) continue;
                seen[nx + r][nz + r] = true;
                floor[nx + r][nz + r] = f;
                q.add(new int[]{ nx, nz });
            }
        }
        return seen;
    }

    private static int floorAt(ServerLevel level, int x, int z, int h) {
        if (!level.hasChunk(x >> 4, z >> 4)) return h;
        for (int y = h + 1; y >= h - 3; y--) {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            boolean room = clear(level, x, y + 1, z) && clear(level, x, y + 2, z);
            if (!st.getFluidState().isEmpty() && !st.blocksMotion()) {
                if (room) return Integer.MIN_VALUE;
                continue;
            }
            if (st.blocksMotion() && room) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean clear(ServerLevel level, int x, int y, int z) {
        BlockState st = level.getBlockState(new BlockPos(x, y, z));
        return !st.blocksMotion() && st.getFluidState().isEmpty();
    }

    // ============================================================ ct02: no lot over the water

    @GameTest(template = "empty", timeoutTicks = 400, batch = "ct02_lot_over_water")
    public static void ct02_lot_over_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cx = 3960000, cz = Z;
        VillageFolkEntity first = founder(helper, cx, cz);
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        int r = TownPlan.reach() + 6;
        channel(level, cx, cz, r);
        com.jrpetty.mcassistant.entity.CityPlan.forget(id);
        TownPlan.Lot west = lot(-TownPlan.RING - TownPlan.STREET - TownPlan.LOT - TownPlan.LOT / 2, 8);    // (-33, 8): its door onto the street at -39
        TownPlan.Lot east = lot(TownPlan.RING + TownPlan.STREET + TownPlan.LOT + TownPlan.LOT / 2, 8);     // (33, 8): over the channel, its door at 39
        helper.assertTrue(west != null && east != null, "the plan's lots at 33 either side");
        helper.assertTrue(com.jrpetty.mcassistant.entity.CityPlan.reaches(level, v, west), "the lot on the town's side is on its streets");
        helper.assertFalse(com.jrpetty.mcassistant.entity.CityPlan.reaches(level, v, east), "the lot over the water is not");
        // A bridge of planks carries the east avenue over the channel.
        int base = Kit.surface(level, cx, cz).getY();
        for (int dx = TownPlan.RING; dx < TownPlan.RING + TownPlan.STREET; dx++) {
            for (int dz = -TownPlan.AVENUE; dz <= TownPlan.AVENUE; dz++) {
                level.setBlock(new BlockPos(cx + dx, base - 1, cz + dz), Blocks.OAK_PLANKS.defaultBlockState(), 2 | 16);
            }
        }
        com.jrpetty.mcassistant.entity.CityPlan.forget(id);
        helper.assertTrue(com.jrpetty.mcassistant.entity.CityPlan.reaches(level, v, east), "over the bridge, the lot is on the streets");
        Kit.log("[VT] ct02 west and east lots, the bridge: as expected");
        helper.succeed();
    }

    static TownPlan.Lot lot(int x, int z) {
        for (TownPlan.Lot l : TownPlan.lots()) if (l.kind() == TownPlan.Kind.LOT && l.x() == x && l.z() == z) return l;
        return null;
    }

    // ============================================================ ct03: the square's greens

    @GameTest(template = "empty", timeoutTicks = 400, batch = "ct03_square_greens")
    public static void ct03_square_greens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cx = 3980000, cz = Z;
        VillageFolkEntity first = founder(helper, cx, cz);
        UUID id = first.ownerId();
        BlockPos heart = Kit.surface(level, cx, cz);
        for (int i = 0; i < 5; i++) VillageFolkSpawnerBlock.raise(level, Kit.surface(level, cx + 5 + 2 * i, cz + 5), 0.0F);
        helper.runAtTickTime(5, () -> {
            Villages.Village v = Villages.get(id);
            Villages.ageForTests(id, Villages.Age.STONE);
            com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
            try {
                for (BlockPos p : Villages.storeChests(level, id)) if (level.getBlockEntity(p) instanceof Container c) { c.clearContent(); c.setChanged(); }
                BlockPos chest = Kit.surface(level, cx + 3, cz - 3);
                level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
                ZoneChests.mark(level, chest);
                Container box = (Container) level.getBlockEntity(chest);
                box.setItem(0, new ItemStack(Items.DIRT, 64));
                box.setItem(1, new ItemStack(Items.OAK_SAPLING, 12));
                box.setItem(2, new ItemStack(Items.SPRUCE_SAPLING, 12));
                box.setItem(3, new ItemStack(Items.POPPY, 32));
                Villages.forgetStores(id);
                // The square paved, as the streets' work leaves it in the Stone Age.
                int y = heart.getY() - 1;
                for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) <= 4) continue;
                    BlockPos g = new BlockPos(cx + dx, y, cz + dz);
                    if (level.getBlockState(g).is(Blocks.GRASS_BLOCK) && level.getBlockState(g.above()).isAir()) level.setBlock(g, Blocks.COBBLESTONE.defaultBlockState(), 2 | 16);
                }
                int did = com.jrpetty.mcassistant.entity.CityGreen.roundsForTests(level, v, 80);
                int[] court = Villages.courtRectForTests(id);
                int greens = 0, wrong = 0, inCourt = 0;
                for (int[] m : new int[][]{ { 9, 9 }, { -9, 9 }, { -9, -9 }, { 9, -9 } }) {
                    boolean byCourt = court != null && cx + m[0] + 3 >= court[0] && cx + m[0] - 3 <= court[1] && cz + m[1] + 3 >= court[2] && cz + m[1] - 3 <= court[3];
                    BlockState mid = level.getBlockState(new BlockPos(cx + m[0], y + 1, cz + m[1]));
                    int turf = 0, flowers = 0;
                    for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                        if (level.getBlockState(new BlockPos(cx + m[0] + ox, y, cz + m[1] + oz)).is(Blocks.GRASS_BLOCK)) turf++;
                        if (level.getBlockState(new BlockPos(cx + m[0] + ox, y + 1, cz + m[1] + oz)).is(BlockTags.SMALL_FLOWERS)) flowers++;
                    }
                    Kit.log("[VT] ct03 green " + m[0] + "," + m[1] + ": " + mid + ", turf " + turf + ", flowers " + flowers + (byCourt ? " (by the courtyard)" : ""));
                    if (byCourt) { inCourt++; if (turf > 0 && mid.getBlock() instanceof SaplingBlock) wrong++; continue; }
                    if (mid.is(Blocks.OAK_SAPLING) && turf == 9 && flowers == 4) greens++;
                    if (mid.is(Blocks.SPRUCE_SAPLING)) wrong++;
                }
                helper.assertTrue(wrong == 0, "no green in the courtyard and no spruce on the square");
                helper.assertTrue(greens >= 3 && greens + inCourt == 4, greens + " greens laid (" + inCourt + " corners by the courtyard), " + did + " rounds did something");
                helper.assertTrue(com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(Items.COBBLESTONE)) >= 9 * greens,
                    "the paving lifted went back into the stores");
                // One of them grown, with leaves hanging low over the square: trimmed, the crown kept.
                int[] m = null;
                for (int[] c : new int[][]{ { 9, 9 }, { -9, 9 }, { -9, -9 }, { 9, -9 } }) {
                    if (level.getBlockState(new BlockPos(cx + c[0], y + 1, cz + c[1])).is(Blocks.OAK_SAPLING)) { m = c; break; }
                }
                BlockPos foot = new BlockPos(cx + m[0], y + 1, cz + m[1]);
                for (int k = 0; k < 5; k++) level.setBlock(foot.above(k), Blocks.OAK_LOG.defaultBlockState(), 2 | 16);
                BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
                for (int ox = -2; ox <= 2; ox++) for (int oz = -2; oz <= 2; oz++) {
                    if (ox == 0 && oz == 0) continue;
                    level.setBlock(foot.offset(ox, 1, oz), leaf, 2 | 16);
                    level.setBlock(foot.offset(ox, 4, oz), leaf, 2 | 16);
                }
                com.jrpetty.mcassistant.entity.CityGreen.roundsForTests(level, v, 8);
                int low = 0, high = 0;
                for (int ox = -2; ox <= 2; ox++) for (int oz = -2; oz <= 2; oz++) {
                    if (level.getBlockState(foot.offset(ox, 1, oz)).is(Blocks.OAK_LEAVES)) low++;
                    if (level.getBlockState(foot.offset(ox, 4, oz)).is(Blocks.OAK_LEAVES)) high++;
                }
                helper.assertTrue(low == 0 && high == 24, "low leaves trimmed (" + low + " left), the crown kept (" + high + ")");
                helper.assertTrue(level.getBlockState(foot).is(Blocks.OAK_LOG), "the tree never felled");
            } finally {
                com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
            }
            helper.succeed();
        });
    }

    // ============================================================ ct04: the plots' edges

    @GameTest(template = "empty", timeoutTicks = 400, batch = "ct04_plot_edges")
    public static void ct04_plot_edges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cx = 3940000, cz = Z;
        VillageFolkEntity first = founder(helper, cx, cz);
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        int y = Kit.surface(level, cx, cz).getY();
        // Houses on the plan's lots, side by side as they stand: two fenced neighbours, one walled and one open.
        int[] pairs = { 22, 47, 72, -33, -58, -83 };                     // the nearer of two lots side by side (22 and 33, ...)
        int[] bands = { -22, -33, -47, -58, -72, -83, 22, 33, 47, 58, 72, 83 };
        List<BlockPos> row = new ArrayList<>();
        BlockPos fenceA = null, fenceB = null, walled = null, open = null;
        for (int bz : bands) {
            for (int px : pairs) {
                BlockPos a = new BlockPos(cx + px, y, cz + bz), b = a.offset(TownPlan.LOT, 0, 0);
                row.add(a);
                row.add(b);
                if (fenceA == null && edgeAt(a) == com.jrpetty.mcassistant.entity.CityPlots.Edge.FENCE
                        && edgeAt(b) == com.jrpetty.mcassistant.entity.CityPlots.Edge.FENCE) { fenceA = a; fenceB = b; }
            }
        }
        for (BlockPos p : row) {
            if (p.equals(fenceA) || p.equals(fenceB)) continue;
            if (walled == null && edgeAt(p) == com.jrpetty.mcassistant.entity.CityPlots.Edge.WALL) walled = p;
            if (open == null && edgeAt(p) == com.jrpetty.mcassistant.entity.CityPlots.Edge.OPEN) open = p;
        }
        helper.assertTrue(fenceA != null && walled != null && open != null, "a row with each kind of plot");
        BlockPos chest = Kit.surface(level, cx + 3, cz - 3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.OAK_FENCE, 64));
        box.setItem(1, new ItemStack(Items.OAK_FENCE_GATE, 8));
        box.setItem(2, new ItemStack(Items.COBBLESTONE, 64));
        box.setItem(3, new ItemStack(Items.POPPY, 64));
        box.setItem(4, new ItemStack(Items.OAK_PLANKS, 64));
        Villages.forgetStores(id);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
        Map<BlockPos, Ledger.Building> houses = new java.util.LinkedHashMap<>();
        try {
            for (BlockPos p : new BlockPos[]{ fenceA, fenceB, walled, open }) {
                BuildGoal.stamp(level, "house", p, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
                Ledger.built(id, "house", p, Direction.NORTH);
                houses.put(p, new Ledger.Building("house", p, Direction.NORTH));
            }
            for (Ledger.Building b : houses.values()) com.jrpetty.mcassistant.entity.Grow.garden(level, v, b, false);
        } finally {
            com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        }
        int[] fa = edges(level, fenceA), fb = edges(level, fenceB), wa = edges(level, walled), op = edges(level, open);
        Kit.log("[VT] ct04 fenced " + java.util.Arrays.toString(fa) + " " + java.util.Arrays.toString(fb) + ", walled " + java.util.Arrays.toString(wa)
            + ", open " + java.util.Arrays.toString(op) + " (fences, gates, walls, flowers)");
        helper.assertTrue(fa[0] >= 30 && fa[1] == 1, "a fence all round the first plot, a gate in front");
        helper.assertTrue(fb[0] >= 20 && fb[0] <= fa[0] - 8 && fb[1] == 1, "the next fenced plot shares the side between them (" + fb[0] + " posts)");
        // Between the two: one line of fence, not two.
        int east = fenceA.getX() + 5;                                   // the first plot's side; the next plot's is a block on
        int doubled = 0;
        for (int dz = -4; dz <= 4; dz++) {
            if (level.getBlockState(new BlockPos(east, y, fenceA.getZ() + dz)).is(BlockTags.FENCES)
                    && level.getBlockState(new BlockPos(east + 1, y, fenceA.getZ() + dz)).is(BlockTags.FENCES)) doubled++;
        }
        helper.assertTrue(doubled == 0, "no fence doubled along the shared side (" + doubled + ")");
        helper.assertTrue(wa[2] >= 30 && wa[0] == 0 && wa[1] == 0, "a low wall of the stores' stone round the walled plot, no gate");
        helper.assertTrue(op[0] == 0 && op[2] == 0 && op[3] >= 2, "the open plot has no edge, only its flowers");
        helper.succeed();
    }

    static com.jrpetty.mcassistant.entity.CityPlots.Edge edgeAt(BlockPos p) {
        return com.jrpetty.mcassistant.entity.CityPlots.edge(new Ledger.Building("house", p, Direction.NORTH));
    }

    /** Round a house's plot (its ring five out, and the ring inside it for flowers): fences, gates, walls, flowers. */
    static int[] edges(ServerLevel level, BlockPos a) {
        int[] n = new int[4];
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) < 4) continue;
            BlockState st = level.getBlockState(a.offset(dx, 0, dz));
            if (st.is(BlockTags.FENCE_GATES)) n[1]++;
            else if (st.is(BlockTags.FENCES)) n[0]++;
            else if (st.is(BlockTags.WALLS)) n[2]++;
            else if (st.is(BlockTags.SMALL_FLOWERS)) n[3]++;
        }
        return n;
    }

    // ------------------------------------------------------------------ the ground

    /** A village's first folk on clean flat ground at x, z, by day. */
    static VillageFolkEntity founder(GameTestHelper helper, int x, int z) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 4 + 3000);
        level.setWeatherParameters(24000, 0, false, false);
        int r = TownPlan.reach() + 10;
        Kit.hold(level, x, z, r);
        Kit.prepare(level, x, z, r);
        BlockPos mid = Kit.surface(level, x, z);
        int y = mid.getY();
        for (int cx = x - r; cx <= x + r; cx++) {
            for (int cz = z - r; cz <= z + r; cz++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(cx, y + dy, cz), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 12; dy++) {
                    BlockPos p = new BlockPos(cx, y + dy, cz);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, z), 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        return f;
    }
}
