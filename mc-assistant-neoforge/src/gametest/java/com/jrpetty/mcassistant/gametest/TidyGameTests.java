package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Eyesores;
import com.jrpetty.mcassistant.entity.Floors;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.PlayerBlocks;
import com.jrpetty.mcassistant.entity.TidyAudit;
import com.jrpetty.mcassistant.entity.TownLooks;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Tidy;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * [tidy] The town cares for its looks: no grass inside its buildings (Floors), the tidy crew (Tidy) and the town's
 * eyesores and looks.
 *
 * <p>Each on its own ground (x 1660000 to 1679999, z 66000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TidyGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    private static String fixes(List<Floors.Fix> all) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size() && i < 8; i++) {
            Floors.Fix f = all.get(i);
            sb.append(f.lay() ? "earth " : "plant ").append(f.pos().toShortString()).append(' ').append(f.style()).append("; ");
        }
        return all.size() + " [" + sb + "]";
    }

    /** Grass and flowers growing over this house's whole lot, as on a meadow. */
    private static void meadow(ServerLevel level, BlockPos at, int half) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                BlockPos g = Kit.surface(level, at.getX() + dx, at.getZ() + dz).below();
                if (!level.getBlockState(g).is(Blocks.GRASS_BLOCK)) level.setBlockAndUpdate(g, Blocks.GRASS_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(g.above(), ((dx + dz) & 3) == 0 ? Blocks.POPPY.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState());
            }
        }
    }

    // ============================================================ no grass inside

    /**
     * A house raised for real by a builder on a meadow (grass under its whole lot, grass and poppies standing on it):
     * its floor and footing are laid over the grass, so once it stands there is no grass block, earth or plant
     * anywhere its drawing lays a block or on its floor; and none comes back after a day of random ticks (fast).
     */
    @GameTest(template = EMPTY, timeoutTicks = 40000, batch = "tdy01_house_on_grass")
    public static void tdy01_house_on_grass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        int x = 1660000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity builder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(builder != null, "a village");
        BlockPos at = Kit.surface(level, x + 20, Z + 20);                    // on a lot, off the streets
        meadow(level, at, 6);
        helper.assertTrue(level.getBlockState(at.below()).is(Blocks.GRASS_BLOCK), "the house's lot is grass: " + level.getBlockState(at.below()));
        for (int i = 0; i < 4; i++) builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 3; i++) builder.insertItem(new ItemStack(Items.OAK_PLANKS, 64));
        builder.insertItem(new ItemStack(Items.OAK_LOG, 32));
        builder.insertItem(new ItemStack(Items.OAK_STAIRS, 64));
        builder.insertItem(new ItemStack(Items.OAK_STAIRS, 64));
        builder.insertItem(new ItemStack(Items.OAK_SLAB, 32));
        builder.insertItem(new ItemStack(Items.STONE_BRICKS, 32));
        builder.insertItem(new ItemStack(Items.CHEST, 2));
        builder.insertItem(new ItemStack(Items.FURNACE, 2));
        builder.insertItem(new ItemStack(Items.CRAFTING_TABLE, 2));
        builder.insertItem(new ItemStack(Items.OAK_DOOR, 2));
        builder.insertItem(new ItemStack(Items.GLASS_PANE, 32));
        builder.insertItem(new ItemStack(Items.TORCH, 8));
        builder.insertItem(new ItemStack(Items.RED_BED, 4));
        builder.enqueue(Job.buildAt("house", at, Direction.NORTH, 0));
        Ledger.Building house = new Ledger.Building("house", at, Direction.NORTH);
        long[] built = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (built[0] < 0) {
                if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);      // building is day work
                if (t % 1200 == 0) Kit.log("tdy01 @" + t + " built=" + Villages.builtList(builder.ownerId()) + " — " + builder.debugLine());
                if (Villages.builtList(builder.ownerId()).contains("house")) {
                    built[0] = t;
                    List<Floors.Fix> left = Floors.inside(level, "house", house);
                    Kit.log("tdy01 the house stands at tick " + t + "; inside it: " + fixes(left) + "; under the door "
                        + level.getBlockState(at.below()) + ", the floor at " + at.below().north(2).toShortString() + " "
                        + level.getBlockState(at.below().north(2)));
                    helper.assertTrue(left.isEmpty(), "no grass, earth or plant inside the house once built: " + fixes(left));
                    // A day of random ticks, fast: whatever grass could creep in or grow, would.
                    level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(60, level.getServer());
                    level.getServer().tickRateManager().setTickRate(10000.0F);
                } else if (t >= 15000) {
                    helper.fail("the house was not built in 15000 ticks: " + Villages.builtList(builder.ownerId()) + " — " + builder.debugLine());
                }
                return;
            }
            if (t - built[0] >= 24000) {
                level.getServer().tickRateManager().setTickRate(20.0F);
                level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3, level.getServer());
                List<Floors.Fix> left = Floors.inside(level, "house", house);
                int grass = 0;
                int[] h = Blueprints.groundHalf("house");
                for (BlockPos p : BlockPos.betweenClosed(at.offset(-h[0] + 1, -1, -h[1] + 1), at.offset(h[0] - 1, 1, h[1] - 1))) {
                    BlockState st = level.getBlockState(p);
                    if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.SHORT_GRASS) || st.is(Blocks.DIRT) || BuildGoal.isWildPlant(st)) grass++;
                }
                Kit.log("tdy01 a day of random ticks on: inside " + fixes(left) + "; grass, earth or plants within the walls " + grass);
                helper.assertTrue(left.isEmpty() && grass == 0, "still no grass inside the house after a day: " + fixes(left) + ", " + grass + " within the walls");
                helper.succeed();
            }
        });
    }

    /**
     * An old house, built before the builder laid its floor over the ground: half its floor still the meadow's grass,
     * grass and a poppy standing on it. The tidy crew's round finds it and puts it right: the earth floored with what
     * the rest of the floor is laid in (spruce boards, out of the stores), the plants taken up; nothing left inside.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "tdy02_old_house_floored")
    public static void tdy02_old_house_floored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        int x = 1661000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        BlockPos at = Kit.surface(level, x + 20, Z + 20);                    // on a lot, off the streets
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, p -> {
            if (p.part() == BuildGoal.Part.BLOCK) return p.style() == Blueprints.Style.FLOOR ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                : Blocks.COBBLESTONE.defaultBlockState();
            if (p.part() == BuildGoal.Part.WINDOW) return Blocks.GLASS_PANE.defaultBlockState();
            if (p.part() == BuildGoal.Part.DOOR) return Blocks.OAK_DOOR.defaultBlockState();
            return null;
        });
        Ledger.built(village, "house", at, Direction.NORTH);
        // Back as it was before: half the floor's boards the meadow's grass, grass and a poppy on it.
        int turned = 0;
        for (BuildGoal.Placement p : BuildGoal.plan("house", at, Direction.NORTH, 13)) {
            if (p.part() != BuildGoal.Part.BLOCK || p.style() != Blueprints.Style.FLOOR) continue;
            if (((p.pos().getX() + p.pos().getZ()) & 1) != 0) continue;
            level.setBlockAndUpdate(p.pos(), Blocks.GRASS_BLOCK.defaultBlockState());
            if (level.getBlockState(p.pos().above()).isAir()) {
                level.setBlockAndUpdate(p.pos().above(), (turned % 3 == 0 ? Blocks.POPPY : Blocks.SHORT_GRASS).defaultBlockState());
            }
            turned++;
        }
        final int turnedUp = turned;
        List<Floors.Fix> before = Floors.inside(level, village, Ledger.buildings(village).get(0));
        Kit.log("tdy02 the old house: " + turned + " floor cells grass; inside it " + fixes(before));
        helper.assertTrue(turned >= 6 && before.size() >= turned, "the old house has grass inside: " + fixes(before));
        // Boards in the stores to floor it with.
        boolean stocked = false;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (c.getItem(i).isEmpty()) { c.setItem(i, new ItemStack(Items.SPRUCE_PLANKS, 64)); stocked = true; break; }
                }
            }
            if (stocked) break;
        }
        helper.assertTrue(stocked, "spruce boards in the stores");
        int[] n = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 20 != 0) return;
            Tidy.tickForTests(level, v);
            List<Floors.Fix> left = Floors.inside(level, village, Ledger.buildings(village).get(0));
            if (left.isEmpty()) {
                int spruce = 0, grass = 0;
                for (BuildGoal.Placement p : BuildGoal.plan("house", at, Direction.NORTH, 13)) {
                    if (p.part() != BuildGoal.Part.BLOCK || p.style() != Blueprints.Style.FLOOR) continue;
                    BlockState st = level.getBlockState(p.pos());
                    if (st.is(Blocks.SPRUCE_PLANKS)) spruce++;
                    if (BuildGoal.bareEarth(st)) grass++;
                }
                int[] done = Tidy.floorsForTests(level, village);
                Kit.log("tdy02 floored by tick " + t + ": " + done[0] + " cells floored, " + done[1] + " plants up; the floor " + spruce
                    + " spruce boards, " + grass + " earth");
                helper.assertTrue(grass == 0 && done[0] >= turnedUp, "every grass cell floored: " + done[0] + " of " + turnedUp);
                helper.assertTrue(spruce >= turnedUp * 2 - 2, "in spruce boards, as the rest of the floor: " + spruce);
                helper.succeed();
            } else if (t >= 2200) {
                helper.fail("the old house still has grass inside: " + fixes(left) + "; done " + java.util.Arrays.toString(Tidy.floorsForTests(level, village)));
            }
        });
    }

    // ============================================================ the long growth: every eyesore, and who left it

    /** The stores topped up with what building takes (and earth for the crew to fill with), each up to so much. */
    private static void stock(ServerLevel level, Villages.Village v) {
        Object[][] want = {
            { Items.OAK_LOG, 256 }, { Items.OAK_PLANKS, 384 }, { Items.COBBLESTONE, 512 }, { Items.STONE_BRICKS, 192 },
            { Items.OAK_STAIRS, 192 }, { Items.OAK_SLAB, 128 }, { Items.STONE_BRICK_STAIRS, 64 }, { Items.STONE_BRICK_SLAB, 64 },
            { Items.GLASS_PANE, 128 }, { Items.GLASS, 32 }, { Items.OAK_DOOR, 16 }, { Items.TORCH, 64 }, { Items.LANTERN, 32 },
            { Items.CHEST, 24 }, { Items.BARREL, 24 }, { Items.FURNACE, 8 }, { Items.CRAFTING_TABLE, 8 }, { Items.LADDER, 64 },
            { Items.OAK_FENCE, 64 }, { Items.OAK_FENCE_GATE, 8 }, { Items.WHITE_BED, 16 }, { Items.BRICKS, 128 },
            { Items.BREAD, 128 }, { Items.HAY_BLOCK, 16 }, { Items.BOOKSHELF, 16 }, { Items.LECTERN, 4 }, { Items.WHITE_CARPET, 32 },
            { Items.POPPY, 32 }, { Items.SMOOTH_STONE_SLAB, 64 }, { Items.SMOKER, 4 }, { Items.DIRT, 256 }, { Items.OAK_SAPLING, 16 },
        };
        for (Object[] w : want) {
            net.minecraft.world.item.Item it = (net.minecraft.world.item.Item) w[0];
            int need = (Integer) w[1] - com.jrpetty.mcassistant.entity.Market.stock(level, v.id(), s -> s.is(it));
            while (need > 0) {
                int k = Math.min(need, it.getDefaultMaxStackSize());
                com.jrpetty.mcassistant.entity.Homes.storeForTests(level, v, new ItemStack(it, k));
                need -= k;
            }
        }
    }

    private static final double DAYS = Double.parseDouble(System.getProperty("tidy.days", "4"));
    private static final int FOLK = Integer.getInteger("tidy.folk", 40);

    /** The audit in the log, every eyesore with what most likely left it (the first so many of each kind). */
    private static void report(ServerLevel level, String label, TidyAudit.Report r) {
        for (String line : r.lines()) Kit.log("tdy10 " + label + " " + line);
        java.util.Map<Eyesores.Kind, Integer> shown = new java.util.EnumMap<>(Eyesores.Kind.class);
        for (Eyesores.Spot s : r.spots()) {
            int n = shown.merge(s.kind(), 1, Integer::sum);
            if (n > 6) continue;
            Kit.log("tdy10 " + label + "   " + s.kind() + " at " + s.pos().toShortString() + " (" + s.note() + "): " + Eyesores.cause(level, s));
        }
    }

    /**
     * A town founded and grown flat out for some days ({@code -Dtidy.days}, four by default) with everything live:
     * every day the town is audited (every eyesore, what most likely left it, the camp, the grass inside the
     * buildings, the looks), and at the end it must be cared for: no camp bed left out once everybody is housed, no
     * grass inside a building, nothing bad (a spill, a hole, a pillar) standing two audits running, few eyesores left
     * at all, and looks of tidy enough or better. As [integrity]'s growth test: generous ground, the Iron Age, the
     * stores topped up daily with what building takes (a test of how the town keeps itself, not of how it quarries).
     * Not required (it runs for minutes); it fails when the bar is not met.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400000, required = false, batch = "tdy10_long_growth")
    public static void tdy10_long_growth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Kit.reset(level);
        Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.Police.standDownForTests(false);
        com.jrpetty.mcassistant.entity.Identity.liveForTests();
        com.jrpetty.mcassistant.entity.Perks.liveForTests(true);
        com.jrpetty.mcassistant.entity.Interviews.liveForTests(true);
        com.jrpetty.mcassistant.entity.Weave.liveForTests(true);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        int cx = 1670000;
        int outer = com.jrpetty.mcassistant.village.FoundingPlan.coreRadius(FOLK) + com.jrpetty.mcassistant.village.FoundingPlan.BAND_MAX;
        Kit.hold(level, cx, Z, outer + 32);
        Kit.prepare(level, cx, Z, outer + 32);
        BlockPos heart = Kit.surface(level, cx, Z);
        Kit.generousTerrain(level, cx, Z);
        Kit.log("tdy10 found " + FOLK + ": " + Kit.command(level, "village found " + FOLK + " " + cx + " " + Z));
        server.tickRateManager().setTickRate(10000.0F);
        final long[] phase = { 0, 0 };
        final UUID[] id = { null };
        final TidyAudit.Report[] before = { null };
        final boolean[] over = { false };
        final String[] verdict = { null };
        // The verdict is given from the test's own sequence (an assertion thrown from a server tick listener is no test's).
        helper.startSequence()
            .thenWaitUntil(() -> {
                if (verdict[0] == null) throw new net.minecraft.gametest.framework.GameTestAssertException("the town is still growing");
            })
            .thenExecute(() -> {
                if (!verdict[0].isEmpty()) helper.fail(verdict[0]);
            })
            .thenSucceed();
        java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> each = e -> {
            if (over[0]) return;
            try {
                long t = helper.getTick();
                if (phase[0] == 0) {
                    if (Founding.near(level, heart, 8)) {
                        if (t % 1000 == 0) Kit.log("tdy10 founding @" + t + " " + Founding.status(server));
                        return;
                    }
                    Villages.Village v = Villages.nearest(level, heart, 64);
                    if (v == null) { over[0] = true; verdict[0] = "no village at " + heart; return; }
                    id[0] = v.id();
                    Villages.ageForTests(v.id(), Villages.Age.IRON);
                    stock(level, v);
                    phase[0] = 1;
                    phase[1] = t;
                    Kit.log("tdy10 town " + Villages.name(v.id()) + " of " + Villages.headcount(v.id()) + " founded at tick " + t);
                    return;
                }
                long ran = t - phase[1];
                if (ran > 0 && ran % 24000 == 0) {
                    Villages.Village v = Villages.get(id[0]);
                    if (v == null) { over[0] = true; verdict[0] = "the village is gone"; return; }
                    stock(level, v);
                    TidyAudit.Report r = TidyAudit.check(level, id[0]);
                    Kit.log("tdy10 ---- day " + ran / 24000 + ": " + Villages.headcount(id[0]) + " folk, built " + Villages.builtList(id[0]).size()
                        + ", beds made up " + Villages.bedsMadeUp(level, id[0]) + ", camp " + com.jrpetty.mcassistant.VillageSpawner.campBeds(level, v.centre()).size()
                        + ", tidied today " + Tidy.doneForTests(level, id[0]) + " floors " + java.util.Arrays.toString(Tidy.floorsForTests(level, id[0]))
                        + " proud " + java.util.Arrays.toString(Tidy.proudForTests(level, id[0])));
                    if (r != null) report(level, "day " + ran / 24000, r);
                    if (ran >= (long) (DAYS * 24000L) && r != null) {
                        over[0] = true;
                        server.tickRateManager().setTickRate(20.0F);
                        java.util.Set<Long> bad = new java.util.HashSet<>();
                        if (before[0] != null) {
                            for (Eyesores.Spot s : before[0].spots()) {
                                if (s.kind().worst >= Eyesores.Kind.PILLAR.worst) bad.add(s.pos().asLong());
                            }
                        }
                        int standing = 0;
                        for (Eyesores.Spot s : r.spots()) if (bad.contains(s.pos().asLong())) standing++;
                        boolean housed = Villages.bedsMadeUp(level, id[0]) >= Villages.headcount(id[0]);
                        Kit.log("tdy10 END: standing bad eyesores " + standing + ", housed " + housed + ", looks " + r.looks().score());
                        List<String> wrong = new java.util.ArrayList<>();
                        if (!r.noGrassInside()) wrong.add("grass inside the buildings: " + r.earthInside() + " earth, " + r.plantsInside() + " plants");
                        if (housed && r.campBedsHoused() > 0) wrong.add("camp beds left out with everybody housed: " + r.campBedsHoused());
                        if (standing > 0) wrong.add("spills, holes or pillars standing a day: " + standing);
                        if (r.looks().score() < TownLooks.UNTIDY) wrong.add("the town not tidy enough: " + r.lines());
                        verdict[0] = String.join("; ", wrong);
                        return;
                    }
                    before[0] = r;
                }
            } catch (RuntimeException ex) {
                over[0] = true;
                server.tickRateManager().setTickRate(20.0F);
                verdict[0] = "thrown: " + ex;
                Kit.LOG.error("tdy10", ex);
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(each);
    }

    // ============================================================ the crew

    /** A store chest of the village's by the heart, with these in it. */
    private static Container stores(ServerLevel level, BlockPos heart, int dx, ItemStack... goods) {
        BlockPos at = Kit.surface(level, heart.getX() + dx, heart.getZ() + 3);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, at);
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) c.setItem(i, goods[i].copy());
        return c;
    }



    /** The ground's top block at this offset from the heart. */
    private static BlockPos ground(ServerLevel level, BlockPos heart, int dx, int dz) {
        return Kit.surface(level, heart.getX() + dx, heart.getZ() + dz).below();
    }

    private static String census(Eyesores.Walk w) {
        return w.total() + " " + w.counts;
    }

    /**
     * The tidy crew puts every kind of eyesore right, out of the stores and into them: a cobble pillar a folk left,
     * a stray block, a three-deep hole, a scar of bare earth in the turf, a crownless trunk (finished, and a sapling
     * planted), a torch dropped on the ground, a puddle of lava, leaves hanging in the air with no trunk, a pothole in
     * the avenue. And it leaves alone what is not its to touch: a pillar a player built, and one on a house's lot.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "tdy03_crew_clears")
    public static void tdy03_crew_clears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 1662000;
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        f.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.HAUL);     // no ground of its own staked over the eyesores
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        // (Boards for the fence post the dropped torch is set on: with none, the post is made of one of the trunk's logs.)
        stores(level, heart, 2, new ItemStack(Items.DIRT, 32), new ItemStack(Items.OAK_SAPLING, 2), new ItemStack(Items.OAK_PLANKS, 8));
        java.util.function.ToIntFunction<java.util.function.Predicate<ItemStack>> stock =
            what -> com.jrpetty.mcassistant.entity.Market.stock(level, village, what);
        int cobbleBefore = stock.applyAsInt(it -> it.is(Items.COBBLESTONE));
        // The eyesores.
        BlockPos pillar = ground(level, heart, 6, -20);
        for (int h = 1; h <= 3; h++) level.setBlockAndUpdate(pillar.above(h), Blocks.COBBLESTONE.defaultBlockState());
        BlockPos stray = ground(level, heart, -6, -20).above();
        level.setBlockAndUpdate(stray, Blocks.COBBLESTONE.defaultBlockState());
        BlockPos hole = ground(level, heart, 8, 20);
        for (int h = 0; h < 3; h++) level.setBlockAndUpdate(hole.below(h), Blocks.AIR.defaultBlockState());
        BlockPos scar = ground(level, heart, -8, 20);
        level.setBlockAndUpdate(scar, Blocks.DIRT.defaultBlockState());
        BlockPos tree = ground(level, heart, 20, 6);
        for (int h = 1; h <= 3; h++) level.setBlockAndUpdate(tree.above(h), Blocks.OAK_LOG.defaultBlockState());
        BlockPos torch = ground(level, heart, 20, -6).above();
        level.setBlockAndUpdate(torch, Blocks.TORCH.defaultBlockState());
        BlockPos lava = ground(level, heart, -20, 8);
        level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
        BlockPos leaves = ground(level, heart, -20, -8).above(6);
        level.setBlock(leaves, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 7), 2 | 16);
        BlockPos pothole = ground(level, heart, 0, 24);
        level.setBlockAndUpdate(pothole, Blocks.AIR.defaultBlockState());
        // Not the crew's: a player's pillar, and a pillar on a house's lot.
        BlockPos players = ground(level, heart, 12, -24);
        for (int h = 1; h <= 3; h++) {
            level.setBlockAndUpdate(players.above(h), Blocks.COBBLESTONE.defaultBlockState());
            PlayerBlocks.markForTests(level, players.above(h), true);
        }
        BlockPos lot = Kit.surface(level, x - 24, Z - 24);
        Ledger.built(village, "house", lot, Direction.NORTH);
        BlockPos lotPillar = ground(level, heart, -24, -24 + 6);
        for (int h = 1; h <= 3; h++) level.setBlockAndUpdate(lotPillar.above(h), Blocks.COBBLESTONE.defaultBlockState());

        TownLooks.measureNow(level, v);
        Eyesores.Walk w = Eyesores.last(village);
        Kit.log("tdy03 found: " + census(w));
        for (Eyesores.Spot sp : w.spots) Kit.log("tdy03   " + sp.kind() + " at " + sp.pos().toShortString() + " (" + sp.note() + ")");
        Eyesores.Kind[] want = { Eyesores.Kind.PILLAR, Eyesores.Kind.STRAY, Eyesores.Kind.HOLE, Eyesores.Kind.SCAR, Eyesores.Kind.HALF_TREE,
            Eyesores.Kind.TORCH, Eyesores.Kind.SPILL, Eyesores.Kind.LEAVES, Eyesores.Kind.ROAD };
        for (Eyesores.Kind k : want) helper.assertTrue(w.count(k) >= 1, "found " + k + ": " + census(w));
        for (Eyesores.Spot sp : w.spots) {
            helper.assertTrue(!sp.pos().equals(players.above(3)) && !sp.pos().equals(lotPillar.above(3)), "not a player's or a lot's: " + sp);
        }
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 10 != 0) return;
            Tidy.tickForTests(level, v);
            Eyesores.Walk now = Eyesores.walkNow(level, v);
            int left = 0;
            for (Eyesores.Kind k : want) left += now.count(k);
            if (left == 0) {
                int cobbleIn = stock.applyAsInt(it -> it.is(Items.COBBLESTONE)) - cobbleBefore;
                int logs = stock.applyAsInt(it -> it.is(net.minecraft.tags.ItemTags.LOGS));
                int torches = stock.applyAsInt(it -> it.is(Items.TORCH));
                Kit.log("tdy03 tidy by tick " + t + ": done " + Tidy.doneForTests(level, village) + "; the stores: cobble "
                    + cobbleIn + " in, dirt " + stock.applyAsInt(it -> it.is(Items.DIRT)) + ", logs " + logs + ", torches " + torches);
                helper.assertTrue(level.getBlockState(pillar.above()).isAir() && level.getBlockState(stray).isAir(), "the pillar and the stray block taken up");
                helper.assertTrue(Eyesores.Kind.values().length > 0 && level.getBlockState(hole).isSolidRender(level, hole), "the hole filled: " + level.getBlockState(hole));
                helper.assertTrue(level.getBlockState(scar).is(Blocks.GRASS_BLOCK), "the scar grassed: " + level.getBlockState(scar));
                helper.assertTrue(!level.getBlockState(tree.above()).is(net.minecraft.tags.BlockTags.LOGS)
                    && level.getBlockState(tree.above()).is(net.minecraft.tags.BlockTags.SAPLINGS), "the trunk felled and a sapling planted: " + level.getBlockState(tree.above()));
                helper.assertTrue(level.getBlockState(lava).getFluidState().isEmpty() && !level.getBlockState(torch).is(Blocks.TORCH)
                    && level.getBlockState(leaves).isAir(), "the lava, the torch and the leaves gone");
                helper.assertTrue(level.getBlockState(pothole).isSolidRender(level, pothole), "the pothole mended: " + level.getBlockState(pothole));
                helper.assertTrue(level.getBlockState(players.above(3)).is(Blocks.COBBLESTONE) && level.getBlockState(lotPillar.above(3)).is(Blocks.COBBLESTONE),
                    "the player's pillar and the lot's left standing");
                // (The town's other works draw on the same stores, cobble and torches for its streets: the trunk's logs are
                // what only the crew puts in.)
                helper.assertTrue(logs >= 3, "the felled trunk's logs went into the stores: " + logs);
                helper.succeed();
            } else if (t >= 2200) {
                StringBuilder sb = new StringBuilder();
                for (Eyesores.Spot sp : now.spots) sb.append(sp.kind()).append('@').append(sp.pos().toShortString()).append(' ');
                helper.fail("still untidy: " + census(now) + " " + sb + "; done " + Tidy.doneForTests(level, village));
            }
        });
    }

    /**
     * The founders' camp taken up once everybody has a bed in a house: the camp's beds go into the stores, one a
     * folk calls its own only once there is a house bed for it; none is taken from under a sleeper.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "tdy04_camp_taken_up")
    public static void tdy04_camp_taken_up(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 1663000;
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.east(), 0.0F);
        helper.assertTrue(a != null && b != null, "a village of two");
        UUID village = a.ownerId();
        Villages.Village v = Villages.get(village);
        int laid = com.jrpetty.mcassistant.VillageSpawner.pitchCamp(level, heart, 4);
        stores(level, heart, 2);
        int bedsBefore = com.jrpetty.mcassistant.entity.Market.stock(level, village, it -> it.is(net.minecraft.tags.ItemTags.BEDS));
        int campBefore = com.jrpetty.mcassistant.VillageSpawner.campBeds(level, heart).size();
        helper.assertTrue(laid >= 2 && campBefore >= 2, "a camp: " + laid);
        // A house with four beds, beyond the camp.
        BlockPos house = Kit.surface(level, x + 22, Z);
        BuildGoal.stamp(level, "house", house, Direction.NORTH, 13, p -> {
            if (p.part() == BuildGoal.Part.BLOCK) return p.style() == Blueprints.Style.FLOOR ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            if (p.part() == BuildGoal.Part.BED) return Blocks.BLUE_BED.defaultBlockState();
            if (p.part() == BuildGoal.Part.DOOR) return Blocks.OAK_DOOR.defaultBlockState();
            return null;
        });
        Ledger.built(village, "house", house, Direction.NORTH);
        // One of them calls a camp bed its own.
        BlockPos campHead = com.jrpetty.mcassistant.VillageSpawner.campBeds(level, heart).get(0);
        a.forgetBed();
        a.takeBedForTests(campHead);
        helper.assertTrue(campHead.equals(a.bedPos()), "a sleeps at the camp: " + a.bedPos());
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 10 != 0) return;
            Tidy.tickForTests(level, v);
            int camp = com.jrpetty.mcassistant.VillageSpawner.campBeds(level, heart).size();
            if (camp == 0) {
                int beds = com.jrpetty.mcassistant.entity.Market.stock(level, village, it -> it.is(net.minecraft.tags.ItemTags.BEDS)) - bedsBefore;
                Kit.log("tdy04 the camp taken up by tick " + t + ": " + campBefore + " beds, " + beds + " into the stores; a's bed now " + a.bedPos()
                    + "; done " + Tidy.doneForTests(level, village));
                helper.assertTrue(beds >= campBefore, "the camp's beds into the stores: " + beds + " of " + campBefore);
                helper.assertTrue(!campHead.equals(a.bedPos()), "a let go of its camp bed for a house's");
                helper.succeed();
            } else if (t >= 2200) {
                helper.fail("the camp still out: " + camp + " beds; made up " + Villages.bedsMadeUp(level, village) + " for " + Villages.headcount(village)
                    + "; walk " + census(Eyesores.walkNow(level, v)));
            }
        });
    }

    /**
     * The town's looks: a town strewn with stray blocks measures a mess; the folk are put out (contentment), the
     * elder calls a clean-up day of a morning and the board says so; once the crew has been round, the looks are up.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "tdy05_looks_and_cleanup")
    public static void tdy05_looks_and_cleanup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 3 + 1000);
        int x = 1664000;
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity f = null;
        for (int i = 0; i < 6; i++) {
            f = VillageFolkSpawnerBlock.raise(level, heart.offset(i % 3, 0, i / 3), 0.0F);
            if (f != null) f.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.HAUL);
        }
        helper.assertTrue(f != null, "a village of six");
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        stores(level, heart, 2, new ItemStack(Items.DIRT, 64));
        for (int i = 0; i < 40; i++) {
            int dx = -30 + (i % 10) * 6 + 1, dz = -30 + (i / 10) * 15 + 2;
            if (TownPlanSquare.near(dx, dz)) continue;
            level.setBlockAndUpdate(ground(level, heart, dx, dz).above(), Blocks.COBBLESTONE.defaultBlockState());
        }
        TownLooks.Looks mess = TownLooks.measureNow(level, v);
        Kit.log("tdy05 the messy town: " + mess);
        helper.assertTrue(mess.score() < 50, "a town strewn with stray blocks looks untidy or worse: " + mess);
        TownLooks.cleanupForTests(level, village, false);
        final TownLooks.Looks[] first = { null };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 10 != 0) return;
            if (level.getDayTime() % 24000L > 5000L) level.setDayTime(24000L * 3 + 1000);     // of a morning, all the test
            Tidy.tickForTests(level, v);
            if (first[0] == null && TownLooks.cleanupDay(level, village)) {
                first[0] = TownLooks.looks(village);
                List<String> board = com.jrpetty.mcassistant.entity.VillageBoards.compose(level, village);
                String said = String.join(" | ", board);
                boolean news = false;
                for (Villages.News n : Villages.news(village)) news |= n.text().contains("clean-up day");
                Kit.log("tdy05 clean-up day called at tick " + t + "; the board: " + TownLooks.boardLine(level, village) + "; news " + news);
                helper.assertTrue(said.contains("Looks:") && said.contains("clean-up day"), "the board shows the looks and the clean-up day");
                helper.assertTrue(news, "the clean-up day is in the town's news");
                java.util.List<String> good = new java.util.ArrayList<>(), bad = new java.util.ArrayList<>();
                helper.assertTrue(TownLooks.contentment(village, good, bad) < 0 && !bad.isEmpty(), "the folk are put out by it: " + bad);
            }
            if (first[0] != null) {
                TownLooks.Looks now = TownLooks.measureNow(level, v);
                if (now.score() >= 50 && now.eyesores() == 0) {
                    Kit.log("tdy05 tidied by tick " + t + ": looks " + mess.score() + " -> " + now.score() + " " + now);
                    helper.succeed();
                }
            }
            if (t >= 2200) helper.fail("not tidied: clean-up " + TownLooks.cleanupDay(level, village) + ", looks " + TownLooks.looks(village));
        });
    }

    /** Spots of the square kept clear in the tests (the well and monuments, the heart). */
    private static final class TownPlanSquare {
        static boolean near(int dx, int dz) {
            return Math.abs(dx) <= 3 && Math.abs(dz) <= 3 || Math.abs(dx) <= 5 && Math.abs(Math.abs(dz) - 8) <= 5
                || Math.abs(dz) <= 5 && Math.abs(Math.abs(dx) - 8) <= 5;
        }
    }
}
