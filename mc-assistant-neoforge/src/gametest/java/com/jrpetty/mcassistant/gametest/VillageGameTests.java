package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.VillagerTakeover;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.VillageMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The village, RUN. Every test here boots the real mod inside a real headless
 * server and watches what actually happens — not what the code says should.
 *
 * <p>Each test has its own batch so they run one at a time: they share a JVM,
 * and therefore every static in the mod. Each also works far from the test
 * arena, on ground of its own making, because a village needs room and the
 * arena is eight blocks wide.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class VillageGameTests {

    private static final String EMPTY = "empty";

    private static AABB around(BlockPos p, double r) {
        return new AABB(p.getX() - r, p.getY() - 20, p.getZ() - r, p.getX() + r, p.getY() + 20, p.getZ() + r);
    }

    // ============================================================ the basics

    /** Every recipe the mod ships must actually PARSE. In 1.21.1 an ingredient
     *  written as a bare string is silently dropped at load, and the item then
     *  cannot be crafted in survival — which nothing else would notice. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "t00_recipes")
    public static void t00_recipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StringBuilder missing = new StringBuilder();
        for (String id : new String[]{"assistant_spawner", "job_board", "place_marker",
                                      "zone_marker", "village_charter", "village_folk_spawner"}) {
            var key = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", id);
            boolean there = level.getServer().getRecipeManager().byKey(key).isPresent();
            Kit.log("t00 recipe " + id + ": " + (there ? "loaded" : "MISSING"));
            if (!there) missing.append(id).append(' ');
        }
        helper.assertTrue(missing.length() == 0, "recipes that did not load: " + missing);
        helper.succeed();
    }

    /** Does a folk exist, tick for ten seconds, and stay alive? A crash on
     *  the entity tick kills the whole server, so this is also a smoke test. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "t01_boot")
    public static void t01_boot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 2500, 2500, 48);
        BlockPos at = Kit.surface(level, 2500, 2500);
        VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(level);
        helper.assertTrue(folk != null, "the village_folk entity type cannot create an entity");
        folk.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(folk);
        helper.runAtTickTime(200, () -> {
            Kit.log("t01 after 200 ticks: " + folk.debugLine());
            helper.assertTrue(folk.isAlive(), "the folk died or vanished within ten seconds");
            helper.succeed();
        });
    }

    /** Placing the spawner block, exactly as a player does. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "t02_spawner_block")
    public static void t02_spawner_block(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Kit.hold(level, 2600, 2600, 48);
        BlockPos ground = Kit.surface(level, 2600, 2600);
        ItemStack stack = new ItemStack(McAssistantMod.FOLK_SPAWNER_ITEM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground.below()), Direction.UP, ground.below(), false);
        InteractionResult r = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        Kit.log("t02 useOn result: " + r);
        helper.runAtTickTime(40, () -> {
            List<VillageFolkEntity> folk = level.getEntitiesOfClass(VillageFolkEntity.class, around(ground, 12));
            Kit.log("t02 folk near the block: " + folk.size()
                + (folk.isEmpty() ? "" : " — " + folk.get(0).debugLine()));
            helper.assertTrue(folk.size() == 1, "placing the spawner should stand exactly one folk up, found " + folk.size());
            helper.assertTrue(level.getBlockState(ground).is(Blocks.CHEST),
                "the founding stores should stand where the block stood, found " + level.getBlockState(ground));
            // It may have eaten a loaf by now (it heals by eating): what matters is that it carries a store.
            helper.assertTrue(folk.get(0).countFood() >= 12, "a folk should carry its bread, has food=" + folk.get(0).countFood());
            helper.succeed();
        });
    }

    /** Using the charter on the ground. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "t03_charter")
    public static void t03_charter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Kit.hold(level, 2700, 2700, 48);
        BlockPos ground = Kit.surface(level, 2700, 2700);
        ItemStack stack = new ItemStack(McAssistantMod.VILLAGE_CHARTER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground.below()), Direction.UP, ground.below(), false);
        InteractionResult r = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        Kit.log("t03 useOn result: " + r);
        helper.runAtTickTime(40, () -> {
            List<VillageFolkEntity> folk = level.getEntitiesOfClass(VillageFolkEntity.class, around(ground, 12));
            Kit.log("t03 folk near the click: " + folk.size()
                + (folk.isEmpty() ? "" : " — " + folk.get(0).debugLine()));
            helper.assertTrue(folk.size() == 1, "the charter should stand exactly one folk up, found " + folk.size());
            helper.succeed();
        });
    }

    /** The /village command family, run as a console would. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "t04_commands")
    public static void t04_commands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 2800, 2800, 48);
        List<String> said = Kit.command(level, "village spawnat 2800 2800 3");
        Kit.log("t04 spawnat said: " + said);
        List<String> folkLines = null;
        helper.runAtTickTime(60, () -> {
            BlockPos here = Kit.surface(level, 2800, 2800);
            List<VillageFolkEntity> folk = level.getEntitiesOfClass(VillageFolkEntity.class, around(here, 20));
            helper.assertTrue(folk.size() == 3, "/village spawnat 2800 2800 3 should make three folk, found " + folk.size());
            List<String> status = Kit.command(level, "village status");
            Kit.log("t04 status said: " + status);
            List<String> lines = Kit.command(level, "village folk");
            Kit.log("t04 folk said: " + lines);
            helper.assertTrue(!status.isEmpty() && !lines.isEmpty(), "/village status and /village folk should both answer");
            for (String s : status) helper.assertFalse(s.toLowerCase().contains("exception"), "status threw: " + s);
            for (String s : lines) helper.assertFalse(s.toLowerCase().contains("exception"), "folk threw: " + s);
            helper.succeed();
        });
    }

    // ============================================================ building

    /** The blueprints are the single source of truth for what a builder must
     *  carry. If a blueprint changes, what stocks it must follow — this is the
     *  check that they are read from the same drawing. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "t08_blueprints")
    public static void t08_blueprints(GameTestHelper helper) {
        for (String st : BuildGoal.STRUCTURES) {
            Kit.log("t08 " + st + " needs " + BuildGoal.partCounts(st, 13));
        }
        var storage = BuildGoal.partCounts("storage", 13);
        helper.assertTrue(storage.getOrDefault(BuildGoal.Part.CHEST, 0) == 4,
            "the storehouse should take four chests, wants " + storage);
        var house = BuildGoal.partCounts("house", 13);
        helper.assertTrue(house.getOrDefault(BuildGoal.Part.FURNACE, 0) >= 1
                && house.getOrDefault(BuildGoal.Part.CRAFTING_TABLE, 0) >= 1
                && house.getOrDefault(BuildGoal.Part.CHEST, 0) >= 1,
            "a house should want a furnace, a bench and a chest, wants " + house);
        helper.assertTrue(BuildGoal.partCounts("watchtower", 13).getOrDefault(BuildGoal.Part.LADDER, 0) > 0,
            "the watchtower has a ladder shaft — the stocking must know");
        helper.assertTrue(BuildGoal.partCounts("lighthouse", 13).getOrDefault(BuildGoal.Part.LADDER, 0) > 0,
            "the lighthouse has a ladder shaft — the stocking must know");
        var pen = BuildGoal.partCounts("pen", 13);
        helper.assertTrue(pen.getOrDefault(BuildGoal.Part.FENCE, 0) >= 20 && pen.getOrDefault(BuildGoal.Part.GATE, 0) == 1,
            "the pen is a ring of fence with a gate, wants " + pen);
        helper.succeed();
    }

    /** A village hands out building lots: flat, clear, claimed once. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t09_lots")
    public static void t09_lots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 3100, 3100, 64);
        BlockPos heart = Kit.surface(level, 3100, 3100);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "could not found a village");
        Villages.Village v = Villages.nearest(level, heart, 100);
        helper.assertTrue(v != null, "no village to plan in");
        var first = Villages.siteFor(level, v.id(), "storage");
        var again = Villages.siteFor(level, v.id(), "storage");
        var other = Villages.siteFor(level, v.id(), "shelter");
        Kit.log("t09 storage lot " + first + ", again " + again + ", shelter lot " + other);
        helper.assertTrue(first != null && other != null, "flat ground should give a lot for each project");
        helper.assertTrue(first.equals(again), "a project's lot must be chosen once and kept");
        helper.assertTrue(!first.anchor().equals(other.anchor()), "two projects must not share a lot");
        helper.assertTrue(Math.abs(first.anchor().getY() - heart.getY()) <= 1, "a lot on flat ground stands at ground level");
        // A lot the builders could not walk to is given up, and not handed out again.
        Villages.rejectSite(v.id(), "storage", level.getGameTime());
        var next = Villages.siteFor(level, v.id(), "storage");
        Kit.log("t09 storage lot after the first was refused: " + next);
        helper.assertTrue(next != null && !next.anchor().equals(first.anchor()) && !next.anchor().equals(other.anchor()),
            "a refused lot must give way to another, not come round again");
        helper.succeed();
    }

    /** Ground that is not flat: a hillside, climbing a block for every two. A village
     *  gets a lot on it, and its builder fills the low side up to the floor instead of
     *  leaving the walls hanging over a drop. */
    @GameTest(template = EMPTY, timeoutTicks = 3200, batch = "t11_hillside")
    public static void t11_hillside(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        Kit.hold(level, 3300, 3300, 70);
        Kit.prepare(level, 3300, 3300, 70);
        for (int dx = -40; dx <= 40; dx++) {
            int rise = Math.max(0, Math.min(12, (dx + 12) / 2));
            for (int dz = -40; dz <= 40; dz++) {
                BlockPos g = Kit.surface(level, 3300 + dx, 3300 + dz);
                for (int i = 0; i < rise; i++) level.setBlock(g.above(i), Blocks.DIRT.defaultBlockState(), 3);
                if (rise > 0) level.setBlock(g.above(rise - 1), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            }
        }
        BlockPos heart = Kit.surface(level, 3300, 3300);
        VillageFolkEntity builder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(builder != null, "could not found a village on the hillside");
        Villages.Village v = Villages.nearest(level, heart, 100);
        var site = Villages.siteFor(level, v.id(), "storage");
        Kit.log("t11 hillside storage lot " + site + "; " + Villages.lotReport(v.id()));
        helper.assertTrue(site != null, "a hillside should still give a lot: " + Villages.lotReport(v.id()));
        int fill = BuildGoal.fillCells(level, site.anchor()).size();
        Kit.log("t11 the lot needs " + fill + " blocks to build up to its floor");
        builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        builder.insertItem(new ItemStack(Items.CHEST, 4));
        builder.insertItem(new ItemStack(Items.TORCH, 4));
        builder.enqueue(Job.buildAt("storage", site.anchor(), site.facing(), site.radius()));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 600 == 0) Kit.log("t11 @" + t + " built=" + Villages.builtList(v.id()) + " — " + builder.debugLine());
            if (Villages.builtList(v.id()).contains("storage")) {
                int hanging = 0;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos floor = site.anchor().offset(dx, -1, dz);
                        if (level.getBlockState(floor).isAir()) hanging++;
                    }
                }
                Kit.log("t11 the storehouse stands at tick " + t + "; " + hanging + " of 25 floor cells hang over air");
                helper.assertTrue(hanging == 0, "the low side should have been built up, " + hanging + " cells hang");
                helper.succeed();
            } else if (t >= 3000) {
                helper.fail("the storehouse was not built on the hillside in 3000 ticks: " + builder.debugLine());
            }
        });
    }

    /** Trees where the first lots are: the lot is taken anyway, and the builder fells
     *  what is in the way and keeps the wood. */
    @GameTest(template = EMPTY, timeoutTicks = 3200, batch = "t12_woodland")
    public static void t12_woodland(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        Kit.hold(level, 3400, 3400, 70);
        Kit.prepare(level, 3400, 3400, 70);
        BlockPos heart = Kit.surface(level, 3400, 3400);
        for (int dx = -9; dx <= 9; dx += 9) {
            for (int dz = -9; dz <= 9; dz += 9) {
                if (dx != 0 || dz != 0) Kit.wildTree(level, heart.getX() + dx, heart.getZ() + dz);
            }
        }
        VillageFolkEntity builder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(builder != null, "could not found a village in the wood");
        Villages.Village v = Villages.nearest(level, heart, 100);
        var site = Villages.siteFor(level, v.id(), "storage");
        Kit.log("t12 woodland storage lot " + site + "; " + Villages.lotReport(v.id()));
        helper.assertTrue(site != null, "a lot with a tree on it should still be a lot: " + Villages.lotReport(v.id()));
        helper.assertTrue(site.anchor().distSqr(heart) < 20 * 20, "with trees all round, the lot is still beside the heart: " + site);
        builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        builder.insertItem(new ItemStack(Items.CHEST, 4));
        builder.insertItem(new ItemStack(Items.TORCH, 4));
        builder.enqueue(Job.buildAt("storage", site.anchor(), site.facing(), site.radius()));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 600 == 0) Kit.log("t12 @" + t + " built=" + Villages.builtList(v.id()) + " — " + builder.debugLine());
            if (Villages.builtList(v.id()).contains("storage")) {
                int logs = builder.countMatching(st -> st.is(ItemTags.LOGS));
                Kit.log("t12 the storehouse stands at tick " + t + "; the builder carries " + logs + " logs from the tree");
                helper.succeed();
            } else if (t >= 3000) {
                helper.fail("the storehouse was not built among the trees in 3000 ticks: " + builder.debugLine());
            }
        });
    }

    // ===================================================== vanilla villagers

    /** A villager appears the ordinary way: the join event should catch it. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "t05_takeover_join")
    public static void t05_takeover_join(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 2900, 2900, 48);
        BlockPos at = Kit.surface(level, 2900, 2900);
        Villager v = EntityType.VILLAGER.create(level);
        v.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(v);
        helper.runAtTickTime(40, () -> {
            int villagers = level.getEntitiesOfClass(Villager.class, around(at, 8)).size();
            List<VillageFolkEntity> folk = level.getEntitiesOfClass(VillageFolkEntity.class, around(at, 8));
            Kit.log("t05 villagers left: " + villagers + ", folk: " + folk.size());
            helper.assertTrue(villagers == 0, "the villager should have been swapped out, " + villagers + " remain");
            helper.assertTrue(folk.size() == 1, "one folk should stand where the villager was, found " + folk.size());
            helper.succeed();
        });
    }

    /** A villager that got into the world WITHOUT the join event catching it
     *  — the way a chunk load or world generation delivers them. The periodic
     *  sweep is what must find it. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "t06_takeover_sweep")
    public static void t06_takeover_sweep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 3000, 2900, 48);
        BlockPos at = Kit.surface(level, 3000, 2900);
        VillagerTakeover.suspended = true;
        Villager v = EntityType.VILLAGER.create(level);
        v.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        v.setVillagerXp(20);            // one somebody has traded with — the old guard's blind spot
        v.setCustomName(net.minecraft.network.chat.Component.literal("Bob the Trader"));
        level.addFreshEntity(v);
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(level.getEntitiesOfClass(Villager.class, around(at, 8)).size() == 1,
                "test setup: the villager should still be there while the takeover is suspended");
            VillagerTakeover.suspended = false;
        });
        helper.runAtTickTime(240, () -> {
            // The converted folk is off to claim ground by now: look for it anywhere near.
            int villagers = level.getEntitiesOfClass(Villager.class, around(at, 8)).size();
            List<VillageFolkEntity> found = level.getEntitiesOfClass(VillageFolkEntity.class, around(at, 200));
            int folk = found.size();
            Kit.log("t06 after the sweep: villagers " + villagers + ", folk " + folk
                + (found.isEmpty() ? "" : " — " + found.get(0).debugLine()));
            helper.assertTrue(villagers == 0, "the sweep should have converted the villager, " + villagers + " remain");
            helper.assertTrue(folk == 1, "one folk should have taken its place, found " + folk);
            helper.succeed();
        });
    }

    // ========================================================== the village

    /** THE test: twelve folk from nothing, on generous ground, for three whole
     *  game days. Nobody touches them. Crops grow five times faster than in a
     *  real game, so that a farm has a harvest inside the test — nothing else
     *  is sped up. */
    @GameTest(template = EMPTY, timeoutTicks = 74000, batch = "t10_village_of_twelve")
    public static void t10_village_of_twelve(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(15, level.getServer());
        level.setWeatherParameters(1000000, 0, false, false);
        level.setDayTime(1000);

        final int cx = 3500, cz = 3500;
        Kit.hold(level, cx, cz, 140);
        Kit.generousTerrain(level, cx, cz);
        final BlockPos heart = Kit.surface(level, cx, cz);
        for (int i = 0; i < 12; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
            helper.assertTrue(f != null, "raise() returned nobody for folk " + i);
        }
        Kit.log("t10 shape wanted for 12: " + java.util.Arrays.toString(VillageMath.shapeOf(12)));

        final Kit.Expect ex = new Kit.Expect();
        final long[] nextDash = {0};
        final boolean[] done = new boolean[6];
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t >= nextDash[0]) {
                Kit.dashboard(level, heart, "soak");
                nextDash[0] = t < 1200 ? t + 300 : (t < 12000 ? t + 1200 : t + 2400);
            }
            Villages.Village v = Villages.nearest(level, heart, 600);
            List<AssistantEntity> crew = v == null ? List.of() : Villages.folkOf(v.id());

            if (!done[0] && t >= 1500) {
                done[0] = true;
                Kit.log("---- checkpoint 1500: trades and ground");
                ex.that(v != null, "a village exists");
                ex.that(crew.size() >= 12, "all twelve are still here (" + crew.size() + ")");
                Map<StationTask, Integer> by = new EnumMap<>(StationTask.class);
                int zoned = 0, indoor = 0;
                for (AssistantEntity a : crew) {
                    by.merge(a.stationTask(), 1, Integer::sum);
                    if (a.workZone() != null) zoned++;
                }
                Kit.log("  trades: " + by);
                ex.that(by.getOrDefault(StationTask.NONE, 0) == 0, "every folk has chosen a trade");
                ex.that(by.getOrDefault(StationTask.FARM, 0) >= 3, "at least three farmers");
                ex.that(by.getOrDefault(StationTask.WOOD, 0) >= 1, "at least one woodcutter");
                ex.that(by.getOrDefault(StationTask.MINE, 0) >= 1, "at least one miner");
                ex.that(zoned >= 9, "at least nine have claimed ground (" + zoned + ")");
            }
            if (!done[1] && t >= 4500) {
                done[1] = true;
                Kit.log("---- checkpoint 4500: past the checklist, actually working");
                int ready = 0;
                for (AssistantEntity a : crew) if (a.missingEssentials().isEmpty()) ready++;
                ex.that(ready >= 9, "at least nine have nothing missing from their checklist (" + ready + ")");
                var world = Kit.census(level, cx, cz, 90);
                ex.that(world.get("farmland") > 0, "some ground has been tilled (" + world.get("farmland") + ")");
            }
            if (!done[2] && t >= 12000) {
                done[2] = true;
                Kit.log("---- checkpoint 12000: producing");
                var chests = Kit.chestContents(level, cx, cz, 110);
                ex.that(chests.getOrDefault("logs", 0) > 0, "logs have reached a chest " + chests.getOrDefault("logs", 0));
                var world = Kit.census(level, cx, cz, 90);
                // Mined and banked — whether it is still lying in a chest or has already
                // gone into a wall (the builders draw on it as soon as it arrives).
                int stoneBanked = chests.getOrDefault("stone", 0) + world.getOrDefault("cobble", 0);
                // What the miners have dug, counted off their own deeds — the founding
                // stores hold cobblestone, so what is in the chests says nothing about it.
                int dug = 0;
                for (AssistantEntity a : crew) dug += a.deedCount(AssistantEntity.Deed.BLOCKS_MINED);
                Kit.log("  stone in chests + built: " + stoneBanked + "; blocks dug by the crew: " + dug);
                ex.that(dug >= 20, "the miners have dug (" + dug + " blocks)");
                ex.that(world.get("wheat") > 0 || chests.getOrDefault("wheat", 0) > 0,
                    "wheat has been grown (" + world.get("wheat") + " standing, " + chests.getOrDefault("wheat", 0) + " stored)");
                ex.that(crew.size() >= 12, "nobody has died yet (" + crew.size() + " of 12+)");
            }
            if (!done[3] && t >= 24000) {
                done[3] = true;
                Kit.log("---- checkpoint 24000: a full day");
                ex.that(crew.size() >= 11, "at most one lost in a day (" + crew.size() + " of 12+)");
                var chests = Kit.chestContents(level, cx, cz, 110);
                var world = Kit.census(level, cx, cz, 90);
                ex.that(world.get("chests") >= 8, "folk have put chests down (" + world.get("chests") + ")");
                ex.that(world.get("farmland") >= 20, "the fields are tilled (" + world.get("farmland") + ")");
                Kit.log("  " + ex.summary());
            }
            if (!done[4] && t >= 48000) {
                done[4] = true;
                Kit.log("---- checkpoint 48000: two days");
                var chests = Kit.chestContents(level, cx, cz, 110);
                var worldNow = Kit.census(level, cx, cz, 90);
                int stoneBanked = chests.getOrDefault("stone", 0) + worldNow.getOrDefault("cobble", 0);
                int dug = 0;
                for (AssistantEntity a : crew) dug += a.deedCount(AssistantEntity.Deed.BLOCKS_MINED);
                Kit.log("  stone in chests + built: " + stoneBanked + "; blocks dug by the crew: " + dug);
                ex.that(dug >= 100, "the miners keep digging (" + dug + " blocks)");
                ex.that(crew.size() >= 11, "at most one lost in two days (" + crew.size() + ")");
                Kit.log("  " + ex.summary());
            }
            if (!done[5] && t >= 72000) {
                done[5] = true;
                Kit.log("---- checkpoint 72000: three days");
                ex.that(crew.size() >= 11, "at most one lost in three days (" + crew.size() + " of 12+)");
                ex.that(v != null && !Villages.builtList(v.id()).isEmpty(),
                    "the village has built something: " + (v == null ? "-" : Villages.builtList(v.id())));
                Kit.log("  " + ex.summary());
                if (ex.clean()) helper.succeed(); else helper.fail(ex.summary());
            }
        });
    }
}
