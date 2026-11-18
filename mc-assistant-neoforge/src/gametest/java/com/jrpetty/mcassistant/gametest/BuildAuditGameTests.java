package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.CastleWall;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.Restore;
import com.jrpetty.mcassistant.entity.Terraform;
import com.jrpetty.mcassistant.entity.TownAudit;
import com.jrpetty.mcassistant.entity.TownBlocks;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * [build5] The building audit: towns grown on a meadow strewn with plants and torches, on rolling hills, and on steep
 * broken ground by a river, left to build for some days, and then every building they finished looked over for the
 * mistakes a player sees: floors in the air, footings that stop short of the ground, ground banked up against the
 * walls or left in the rooms, plants and torches standing in a cell the drawing wanted, doors onto a bank or a drop,
 * bare earth showing as the face of a footing, roofs left open; the town wall's gaps and the fields' dry or trampled
 * farmland. Counted by kind, with the worst examples and where they are.
 *
 * <p>x 3,500,000 to 3,600,000, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BuildAuditGameTests {

    private static final int Z = 90000;
    /** Days grown (-Dba.days), folk each town is founded with (-Dba.folk), and whether the bar is enforced (-Dba.strict). */
    private static final double DAYS = Double.parseDouble(System.getProperty("ba.days", "4"));
    private static final int FOLK = Integer.getInteger("ba.folk", 24);
    private static final boolean STRICT = Boolean.parseBoolean(System.getProperty("ba.strict", "true"));
    /** Which grounds to grow (-Dba.grounds, a list of meadow, hills, steep). */
    private static final String GROUNDS = System.getProperty("ba.grounds", "meadow,hills,steep");

    /** The grounds: the meadow, gentle hills, steep broken ground by a river. */
    enum Ground { MEADOW, HILLS, STEEP }

    @GameTest(template = "empty", timeoutTicks = 900000, required = false, batch = "ba01_audit")
    public static void ba01_audit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Kit.reset(level);
        Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.Police.standDownForTests(false);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        List<Growth> towns = new ArrayList<>();
        int x = 3500000;
        for (Ground g : Ground.values()) {
            if (!GROUNDS.contains(g.name().toLowerCase(java.util.Locale.ROOT))) { x += 30000; continue; }
            towns.add(new Growth(helper, level, g, x, Z));
            x += 30000;
        }
        server.tickRateManager().setTickRate(10000.0F);
        final boolean[] over = { false };
        java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> each = e -> {
            if (over[0]) return;
            try {
                boolean all = true;
                for (Growth t : towns) if (!t.done) { t.step(); all &= t.done; }
                if (all) {
                    over[0] = true;
                    server.tickRateManager().setTickRate(20.0F);
                    Kit.Expect expect = new Kit.Expect();
                    Map<String, Integer> total = new TreeMap<>();
                    for (Growth t : towns) {
                        if (t.failure != null) expect.that(false, t.ground + ": " + t.failure);
                        for (Map.Entry<String, Integer> c : t.counts.entrySet()) total.merge(c.getKey(), c.getValue(), Integer::sum);
                    }
                    Kit.log("[VT] ba01 TOTAL " + total);
                    if (STRICT) {
                        for (String k : BAR) expect.that(total.getOrDefault(k, 0) == 0, k + " = " + total.getOrDefault(k, 0));
                        int buildings = total.getOrDefault("buildings", 0);
                        expect.that(buildings >= 6 * towns.size(), "the towns built (" + buildings + " buildings)");
                    }
                    helper.runAfterDelay(1, () -> { if (expect.clean()) helper.succeed(); else helper.fail(expect.summary()); });
                }
            } catch (RuntimeException ex) {
                over[0] = true;
                server.tickRateManager().setTickRate(20.0F);
                helper.runAfterDelay(1, () -> helper.fail("the run threw " + ex));
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(each);
    }

    /** The counts that must come to nothing. */
    static final String[] BAR = { "floating", "hollow_footing", "earth_inside", "plants_inside", "plant_in_cell",
        "door_blocked", "door_over_drop", "banked_wall", "dirt_face", "wall_gap" };

    // ------------------------------------------------------------------ the ground

    /** A height field over the ground round the heart, earth over stone, turfed; and what grows and stands on it. */
    static void shape(ServerLevel level, Ground g, int cx, int cz, int r, long seed) {
        Kit.hold(level, cx, cz, r + 16);
        Kit.prepare(level, cx, cz, r + 16);
        int base = Kit.surface(level, cx, cz).getY();
        RandomSource rnd = RandomSource.create(seed);
        double p1 = rnd.nextDouble() * 6, p2 = rnd.nextDouble() * 6;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int h = switch (g) {
                    case MEADOW -> 0;
                    case HILLS -> (int) Math.round(3 + 3 * Math.sin(dx / 13.0 + p1) + 2.5 * Math.cos(dz / 11.0 + p2));
                    case STEEP -> (int) Math.round(8 + 6 * Math.sin(dx / 8.0 + p1) * Math.cos(dz / 10.0 + p2)
                        + 3 * Math.sin((dx + dz) / 6.0 + p2));
                };
                int x = cx + dx, z = cz + dz;
                boolean river = g == Ground.STEEP && Math.abs(dz - 34 - (int) (4 * Math.sin(dx / 15.0))) <= 2;
                if (river) {
                    for (int y = base - 2; y < base + 20; y++) {
                        level.setBlock(new BlockPos(x, y, z), y < base ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                    continue;
                }
                if (h > 0) {
                    level.setBlock(new BlockPos(x, base - 1, z), Blocks.DIRT.defaultBlockState(), 2 | 16);
                    for (int y = base; y < base + h; y++) {
                        BlockState st = y == base + h - 1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : y >= base + h - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
                        level.setBlock(new BlockPos(x, y, z), st, 2 | 16);
                    }
                }
            }
        }
        // What grows and stands on it: grass, ferns, flowers, snow, saplings, dead bushes, a torch here and there,
        // mushrooms; tall grass and big ferns two high.
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx, z = cz + dz;
                BlockPos top = Kit.surface(level, x, z);
                if (!level.getBlockState(top.below()).is(Blocks.GRASS_BLOCK) || !level.getBlockState(top).isAir()) continue;
                double roll = rnd.nextDouble();
                BlockState st = null;
                if (roll < 0.10) st = Blocks.SHORT_GRASS.defaultBlockState();
                else if (roll < 0.12) st = Blocks.FERN.defaultBlockState();
                else if (roll < 0.14) st = rnd.nextBoolean() ? Blocks.POPPY.defaultBlockState() : Blocks.DANDELION.defaultBlockState();
                else if (roll < 0.16) st = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1 + rnd.nextInt(2));
                else if (roll < 0.165) st = Blocks.OAK_SAPLING.defaultBlockState();
                else if (roll < 0.17) st = Blocks.DEAD_BUSH.defaultBlockState();
                else if (roll < 0.174) st = Blocks.TORCH.defaultBlockState();
                else if (roll < 0.176) st = Blocks.BROWN_MUSHROOM.defaultBlockState();
                else if (roll < 0.20) {
                    BlockState tall = (rnd.nextBoolean() ? Blocks.TALL_GRASS : Blocks.LARGE_FERN).defaultBlockState();
                    DoublePlantBlock.placeAt(level, tall, top, 2 | 16);
                    continue;
                }
                if (st != null) level.setBlock(top, st, 2 | 16);
            }
        }
        // Trees, a pond, and sugar cane on its bank.
        for (int i = 0; i < 26; i++) {
            int tx = cx - r + 8 + rnd.nextInt(2 * r - 16), tz = cz - r + 8 + rnd.nextInt(2 * r - 16);
            if (Math.abs(tx - cx) < 10 && Math.abs(tz - cz) < 10) continue;
            Kit.wildTree(level, tx, tz);
        }
        if (g != Ground.STEEP) {
            int px = cx + 30, pz = cz - 26;
            Kit.pond(level, px, pz, 3);
            for (int k = -3; k <= 3; k++) {
                BlockPos bank = Kit.surface(level, px + k, pz + 4);
                if (level.getBlockState(bank.below()).is(Blocks.GRASS_BLOCK)) {
                    level.setBlock(bank, Blocks.SUGAR_CANE.defaultBlockState(), 2 | 16);
                    level.setBlock(bank.above(), Blocks.SUGAR_CANE.defaultBlockState(), 2 | 16);
                }
            }
        }
    }

    // ------------------------------------------------------------------ a town's growth

    static final class Growth {
        final GameTestHelper helper;
        final ServerLevel level;
        final Ground ground;
        final BlockPos heart;
        UUID id;
        long began = -1;
        int lastDay = -1;
        boolean done;
        String failure;
        final Map<String, Integer> counts = new TreeMap<>();

        Growth(GameTestHelper helper, ServerLevel level, Ground ground, int cx, int cz) {
            this.helper = helper;
            this.level = level;
            this.ground = ground;
            int outer = FoundingPlan.coreRadius(FOLK) + FoundingPlan.BAND_MAX;
            shape(level, ground, cx, cz, outer + 40, 9000 + ground.ordinal());
            this.heart = Kit.surface(level, cx, cz);
            Kit.log("[VT] ba01 " + ground + " found " + FOLK + ": " + Kit.command(level, "village found " + FOLK + " " + cx + " " + cz));
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
                Kit.stockForGrowth(level, v);
                Kit.log("[VT] ba01 " + ground + " town " + Villages.name(id) + " founded at tick " + t + " heart " + v.centre().toShortString());
                return;
            }
            Villages.Village v = Villages.get(id);
            if (v == null) { failure = "the village is gone"; done = true; return; }
            long ran = t - began;
            int day = (int) (ran / 24000L);
            if (day != lastDay) {
                lastDay = day;
                Kit.stockForGrowth(level, v);
                Kit.log("[VT] ba01 " + ground + " day " + day + ": " + Villages.headcount(id) + " folk, " + Ledger.buildings(id).size()
                    + " buildings, going up " + Villages.sitesOf(id).keySet());
            }
            if (ran >= (long) (DAYS * 24000L)) {
                Report r = audit(level, v);
                counts.putAll(r.counts);
                Kit.log("[VT] ba01 " + ground + " AUDIT " + r.counts);
                for (Map.Entry<String, List<String>> e : r.examples.entrySet()) {
                    for (String s : e.getValue()) Kit.log("[VT] ba01 " + ground + "   " + e.getKey() + ": " + s);
                }
                done = true;
            }
        }
    }

    // ------------------------------------------------------------------ the audit

    /** What the audit found: counts by kind, and the first few of each with where. */
    static final class Report {
        final Map<String, Integer> counts = new TreeMap<>();
        final Map<String, List<String>> examples = new TreeMap<>();

        void add(String kind, int n, String where) {
            if (n <= 0) return;
            counts.merge(kind, n, Integer::sum);
            List<String> l = examples.computeIfAbsent(kind, k -> new ArrayList<>());
            if (l.size() < 6) l.add(where + (n > 1 ? " (x" + n + ")" : ""));
        }

        void zero(String... kinds) { for (String k : kinds) counts.putIfAbsent(k, 0); }
    }

    /** Something growing or lying on the ground that has no business in a building's cell or room. */
    static boolean plantish(BlockState st) {
        return BuildGoal.isWildPlant(st) || st.is(Blocks.SNOW) || st.is(BlockTags.SAPLINGS) || st.is(Blocks.TORCH)
            || st.is(Blocks.WALL_TORCH) || st.is(Blocks.BROWN_MUSHROOM) || st.is(Blocks.RED_MUSHROOM) || st.is(Blocks.SUGAR_CANE)
            || st.is(Blocks.VINE) || st.is(Blocks.SWEET_BERRY_BUSH)
            || (st.is(BlockTags.LEAVES) && !(st.hasProperty(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.PERSISTENT)));
    }

    /** Does this hold a floor up: something solid, not air, water, a plant or snow. */
    static boolean holds(BlockState st) {
        return st.isSolid() && st.getFluidState().isEmpty() && !st.canBeReplaced();
    }

    /** The town's finished buildings looked over, and its wall and fields. */
    static Report audit(ServerLevel level, Villages.Village v) {
        Report r = new Report();
        r.zero(BAR);
        UUID id = v.id();
        int buildings = 0;
        for (Ledger.Building b : Ledger.buildings(id)) {
            String design = TownBlocks.designOf(id, b);
            List<BuildGoal.Placement> plan = BuildGoal.plan(design, b.anchor(), b.facing(), 13);
            if (plan.isEmpty() || b.structure().equals("fortify")) continue;
            buildings++;
            String name = design + "@" + b.anchor().toShortString();
            Set<Long> mine = new HashSet<>();
            int minY = Integer.MAX_VALUE;
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.CLEAR) continue;
                mine.add(p.pos().asLong());
                minY = Math.min(minY, p.pos().getY());
            }
            // The footing layer's extent: the building's box on the ground.
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            List<BlockPos> footing = new ArrayList<>();
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.CLEAR || p.pos().getY() != minY) continue;
                footing.add(p.pos());
                x0 = Math.min(x0, p.pos().getX()); x1 = Math.max(x1, p.pos().getX());
                z0 = Math.min(z0, p.pos().getZ()); z1 = Math.max(z1, p.pos().getZ());
            }
            // Floating and hollow footings: under every footing cell, solid down to the ground.
            int floating = 0, hollow = 0, dirtFace = 0;
            BlockPos fWhere = null, hWhere = null, dWhere = null;
            for (BlockPos f : footing) {
                if (!level.isLoaded(f)) continue;
                if (!holds(level.getBlockState(f))) continue;          // not built (the diff's business, not the ground's)
                BlockPos under = f.below();
                if (!holds(level.getBlockState(under))) { floating++; if (fWhere == null) fWhere = f; continue; }
                boolean gap = false, solidBelowGap = false;
                for (int d = 2; d <= 12; d++) {
                    BlockState st = level.getBlockState(f.below(d));
                    if (!holds(st)) gap = true;
                    else if (gap) { solidBelowGap = true; break; }
                }
                if (gap && solidBelowGap) { hollow++; if (hWhere == null) hWhere = f; }
                // Bare earth for the face of a footing: earth under the floor with open air beside it, on the edge.
                boolean edge = f.getX() == x0 || f.getX() == x1 || f.getZ() == z0 || f.getZ() == z1;
                if (edge) {
                    for (int d = 1; d <= 10; d++) {
                        BlockPos c = f.below(d);
                        BlockState st = level.getBlockState(c);
                        if (!holds(st)) break;
                        boolean open = false;
                        for (Direction dir : Direction.Plane.HORIZONTAL) {
                            BlockPos n = c.relative(dir);
                            if (n.getX() >= x0 && n.getX() <= x1 && n.getZ() >= z0 && n.getZ() <= z1) continue;
                            if (level.getBlockState(n).isAir()) open = true;
                        }
                        if (open && (st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.COARSE_DIRT))) {
                            dirtFace++; if (dWhere == null) dWhere = c;
                        }
                    }
                }
            }
            r.add("floating", floating, name + " first " + (fWhere == null ? "" : fWhere.toShortString()));
            r.add("hollow_footing", hollow, name + " first " + (hWhere == null ? "" : hWhere.toShortString()));
            r.add("dirt_face", dirtFace, name + " first " + (dWhere == null ? "" : dWhere.toShortString()));
            // The rooms: earth or plants left in a cell the drawing leaves open, inside the box, the floor's two layers up.
            int earth = 0, plants = 0;
            BlockPos eWhere = null, pWhere = null;
            for (int x = x0 + 1; x < x1; x++) {
                for (int z = z0 + 1; z < z1; z++) {
                    for (int y = minY + 1; y <= minY + 2; y++) {
                        BlockPos c = new BlockPos(x, y, z);
                        if (mine.contains(c.asLong()) || !level.isLoaded(c)) continue;
                        BlockState st = level.getBlockState(c);
                        if (Terraform.earth(st) && !st.is(Blocks.BEDROCK)) { earth++; if (eWhere == null) eWhere = c; }
                        else if (plantish(st)) { plants++; if (pWhere == null) pWhere = c; }
                    }
                }
            }
            r.add("earth_inside", earth, name + " first " + (eWhere == null ? "" : eWhere.toShortString()));
            r.add("plants_inside", plants, name + " first " + (pWhere == null ? "" : pWhere.toShortString() + " " + level.getBlockState(pWhere).getBlock()));
            // A plant, snow or a torch standing where the drawing wants a block: a step that stalled or was skipped.
            int inCell = 0;
            BlockPos cWhere = null;
            int roof = 0;
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.CLEAR || !level.isLoaded(p.pos())) continue;
                BlockState st = level.getBlockState(p.pos());
                if (plantish(st) && Restore.judge(st, p) != null) { inCell++; if (cWhere == null) cWhere = p.pos(); }
                if (p.part() == BuildGoal.Part.BLOCK && BuildGoal.isRoof(p.style()) && Restore.judge(st, p) == Restore.Kind.MISSING) roof++;
            }
            r.add("plant_in_cell", inCell, name + " first " + (cWhere == null ? "" : cWhere.toShortString() + " " + level.getBlockState(cWhere).getBlock()));
            r.add("roof_missing", roof, name);
            // The ground round the walls: banked up over the floor, two or more.
            int banked = 0;
            BlockPos bWhere = null;
            int floorTop = minY + 1;                                   // the first block of air in the rooms
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                for (int z = z0 - 1; z <= z1 + 1; z++) {
                    if (x >= x0 && x <= x1 && z >= z0 && z <= z1) continue;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    int g = natural(level, x, z, floorTop + 8);
                    if (g >= floorTop + 2) { banked++; if (bWhere == null) bWhere = new BlockPos(x, g, z); }
                }
            }
            r.add("banked_wall", banked, name + " first " + (bWhere == null ? "" : bWhere.toShortString()));
            // The doors: out onto open ground at the door's own level, with ground (or a step) under the first cell.
            for (BuildGoal.Placement p : plan) {
                if (p.part() != BuildGoal.Part.DOOR || p.pos().getY() != minY + 1) continue;
                BlockPos door = p.pos();
                Direction out = null;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos n = door.relative(d);
                    if (n.getX() < x0 || n.getX() > x1 || n.getZ() < z0 || n.getZ() > z1) { out = d; break; }
                }
                if (out == null) continue;
                BlockPos step = door.relative(out);
                if (mine.contains(step.asLong())) continue;
                BlockState at = level.getBlockState(step), over = level.getBlockState(step.above());
                if (holds(at) || holds(over)) r.add("door_blocked", 1, name + " door " + door.toShortString() + " " + at.getBlock());
                else if (!holds(level.getBlockState(step.below())) && !holds(level.getBlockState(step.below(2))))
                    r.add("door_over_drop", 1, name + " door " + door.toShortString());
            }
        }
        r.counts.put("buildings", buildings);
        // The audit everyone uses: what stands on the roads, half built, foreign things in doorways.
        TownAudit.Report ta = TownAudit.check(level, v);
        r.counts.put("on_roads", (int) ta.measure("blocks_on_roads"));
        r.counts.put("half_built", ta.count(TownAudit.HALF_BUILT));
        r.counts.put("doorway_foreign", ta.count(TownAudit.FOREIGN));
        // The wall: a cross-section with none of its three cells built from the walkway down to the ground is a gap.
        if (CastleWall.stands(id)) {
            for (int[] e : CastleWall.ringForTests(id)) {
                boolean any = false;
                for (int u = 0; u < 3; u++) {
                    BlockPos c = new BlockPos(e[2 * u], e[6] - 1, e[2 * u + 1]);
                    if (level.isLoaded(c) && CastleWall.wallBlockForTests(level.getBlockState(c))) any = true;
                }
                if (!any) r.add("wall_gap", 1, e[0] + "," + (e[6] - 1) + "," + e[1]);
            }
            int[] c = CastleWall.countsForTests(id);
            r.counts.put("wall_laid", c[0]);
            r.counts.put("wall_total", c[1]);
        }
        // The fields: farmland, dry farmland, and earth trodden into a field's rows.
        int farmland = 0, dry = 0;
        int reach = 100;
        for (int x = v.centre().getX() - reach; x <= v.centre().getX() + reach; x++) {
            for (int z = v.centre().getZ() - reach; z <= v.centre().getZ() + reach; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int y = top - 2; y <= top; y++) {
                    BlockState st = level.getBlockState(new BlockPos(x, y, z));
                    if (st.is(Blocks.FARMLAND)) {
                        farmland++;
                        if (st.getValue(FarmBlock.MOISTURE) == 0) dry++;
                    }
                }
            }
        }
        r.counts.put("farmland", farmland);
        r.counts.put("farmland_dry", dry);
        return r;
    }

    /** The natural ground at a column: the first open cell over earth, through plants and snow, looking down from {@code from}. */
    static int natural(ServerLevel level, int x, int z, int from) {
        for (int y = from; y > from - 24; y--) {
            BlockState st = level.getBlockState(new BlockPos(x, y - 1, z));
            if (Terraform.earth(st)) return y;
            if (holds(st)) return Integer.MIN_VALUE;                  // somebody's build: not the ground's business
        }
        return Integer.MIN_VALUE;
    }
}
