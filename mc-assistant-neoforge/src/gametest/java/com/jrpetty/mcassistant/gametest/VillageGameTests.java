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
            int party = VillageFolkSpawnerBlock.foundingParty();
            helper.assertTrue(folk.size() == party,
                "placing the first spawner should found a village of " + party + ", found " + folk.size());
            helper.assertTrue(level.getBlockState(ground).is(Blocks.CHEST),
                "the founding stores should stand where the block stood, found " + level.getBlockState(ground));
            // It may have eaten a loaf by now (it heals by eating): what matters is that it carries a store.
            helper.assertTrue(folk.get(0).countFood() >= 12, "a folk should carry its bread, has food=" + folk.get(0).countFood());
            java.util.Set<java.util.UUID> villages = new java.util.HashSet<>();
            for (VillageFolkEntity f : folk) villages.add(f.ownerId());
            helper.assertTrue(villages.size() == 1, "the founding party should be one village, found " + villages.size());
            // A second spawner, placed within reach, adds one settler to that village.
            BlockPos next = Kit.surface(level, 2610, 2600);   // clear of the party
            ItemStack more = new ItemStack(McAssistantMod.FOLK_SPAWNER_ITEM.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, more);
            more.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(next.below()), Direction.UP, next.below(), false)));
            int after = level.getEntitiesOfClass(VillageFolkEntity.class, around(ground, 16)).size();
            Kit.log("t02 after a second spawner: " + after);
            helper.assertTrue(after == party + 1, "a second spawner should add exactly one, found " + after);
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
            helper.assertTrue(folk.size() == VillageFolkSpawnerBlock.foundingParty(),
                "the charter should found a village of " + VillageFolkSpawnerBlock.foundingParty()
                + ", found " + folk.size());
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
        var well = BuildGoal.partCounts("well", 13);
        helper.assertTrue(well.getOrDefault(BuildGoal.Part.FENCE, 0) == 8 && well.getOrDefault(BuildGoal.Part.LANTERN, 0) == 1
                && well.getOrDefault(BuildGoal.Part.WATER, 0) == 1,
            "the well has four posts two high, a lantern and water, wants " + well);
        helper.assertTrue(house.getOrDefault(BuildGoal.Part.BED, 0) == com.jrpetty.mcassistant.village.VillageMath.BEDS_PER_HOUSE
                && house.getOrDefault(BuildGoal.Part.DOOR, 0) == 1 && house.getOrDefault(BuildGoal.Part.WINDOW, 0) >= 8,
            "a house sleeps a family of four, has a door and glass in its windows, wants " + house);
        var houseStyles = BuildGoal.styleCounts("house");
        helper.assertTrue(houseStyles.getOrDefault(com.jrpetty.mcassistant.entity.goal.Blueprints.Style.ROOF_STAIR, 0) >= 60
                && houseStyles.getOrDefault(com.jrpetty.mcassistant.entity.goal.Blueprints.Style.POST, 0) >= 12,
            "a house has a pitched roof of stairs and a timber frame, wants " + houseStyles);
        var hall = BuildGoal.partCounts("hall", 13);
        helper.assertTrue(hall.getOrDefault(BuildGoal.Part.CHEST, 0) == 2 && hall.getOrDefault(BuildGoal.Part.CRAFTING_TABLE, 0) == 1
                && hall.getOrDefault(BuildGoal.Part.BLOCK, 0) > house.getOrDefault(BuildGoal.Part.BLOCK, 0),
            "the meeting hall is the biggest thing a village builds, with two chests and a bench, wants " + hall);
        var pen = BuildGoal.partCounts("pen", 13);
        helper.assertTrue(pen.getOrDefault(BuildGoal.Part.FENCE, 0) >= 20 && pen.getOrDefault(BuildGoal.Part.GATE, 0) == 1,
            "the pen is a ring of fence with a gate, wants " + pen);
        // The later ages' buildings and the great works.
        var market = BuildGoal.partCounts("market", 13);
        helper.assertTrue(market.getOrDefault(BuildGoal.Part.CHEST, 0) == 2
                && market.getOrDefault(BuildGoal.Part.CRAFTING_TABLE, 0) == 2 && market.getOrDefault(BuildGoal.Part.FURNACE, 0) == 1,
            "the market is a roof on posts over two chests, two benches and a furnace, wants " + market);
        var chapel = BuildGoal.partCounts("chapel", 13);
        helper.assertTrue(chapel.getOrDefault(BuildGoal.Part.WINDOW, 0) >= 20 && chapel.getOrDefault(BuildGoal.Part.CRAFTING_TABLE, 0) == 1,
            "the chapel has tall windows and an altar, wants " + chapel);
        var gateway = BuildGoal.partCounts("gateway", 13);
        helper.assertTrue(gateway.getOrDefault(BuildGoal.Part.OBSIDIAN, 0) == 10 && gateway.getOrDefault(BuildGoal.Part.BLOCK, 0) >= 4,
            "the gateway is a ten-obsidian frame on a stone dais, wants " + gateway);
        var granary = BuildGoal.partCounts("granary", 13);
        helper.assertTrue(granary.getOrDefault(BuildGoal.Part.CHEST, 0) == 3, "the granary holds three chests, wants " + granary);
        var barracks = BuildGoal.partCounts("barracks", 13);
        helper.assertTrue(barracks.getOrDefault(BuildGoal.Part.BED, 0) == 6 && barracks.getOrDefault(BuildGoal.Part.CHEST, 0) == 2,
            "the barracks have six bunks and two chests, wants " + barracks);
        var monument = BuildGoal.partCounts("monument", 13);
        helper.assertTrue(monument.getOrDefault(BuildGoal.Part.BLOCK, 0) >= 17 && monument.getOrDefault(BuildGoal.Part.LANTERN, 0) >= 1,
            "the monument is a plinth, a step, a pillar and a light, wants " + monument);
        // Every drawing reads, and every building has a way in or is open.
        for (String st : BuildGoal.STRUCTURES) {
            if (!com.jrpetty.mcassistant.entity.goal.Blueprints.has(st)) continue;
            int[] g = com.jrpetty.mcassistant.entity.goal.Blueprints.groundHalf(st);
            Kit.log("t08 " + st + " stands " + (2 * g[0] + 1) + " by " + (2 * g[1] + 1) + ", "
                + com.jrpetty.mcassistant.entity.goal.Blueprints.cells(st).size() + " blocks");
        }
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
        // A builder that gives a lot up hands what it drew for it back to the stores:
        // the founding chests and the stone, for whoever builds it next.
        int chestsBefore = storedAt(level, heart, net.minecraft.world.item.Items.CHEST);
        int stoneBefore = storedAt(level, heart, net.minecraft.world.item.Items.COBBLESTONE);
        int ownChests = f.countCarried(st -> st.is(net.minecraft.world.item.Items.CHEST));
        f.insertItem(new ItemStack(net.minecraft.world.item.Items.CHEST, 4));
        f.insertItem(new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 20));
        f.noteBuildAbandoned("storage");
        int chestsAfter = storedAt(level, heart, net.minecraft.world.item.Items.CHEST);
        int stoneAfter = storedAt(level, heart, net.minecraft.world.item.Items.COBBLESTONE);
        int keptChests = f.countCarried(st -> st.is(net.minecraft.world.item.Items.CHEST));
        Kit.log("t09 handed back: chests " + chestsBefore + " -> " + chestsAfter + " (kept " + keptChests
            + " of " + (ownChests + 4) + "), stone " + stoneBefore + " -> " + stoneAfter);
        helper.assertTrue(stoneAfter - stoneBefore == 20, "the stone drawn for a given-up building goes back to the stores");
        helper.assertTrue(keptChests == 1 && chestsAfter - chestsBefore == ownChests + 3,
            "the chests drawn for a given-up building go back to the stores (one is kept for its own ground)");
        helper.succeed();
    }

    /** How many of this item the container at {@code pos} holds. */
    private static int storedAt(ServerLevel level, BlockPos pos, net.minecraft.world.item.Item item) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container c)) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
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
        for (int i = 0; i < 6; i++) builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
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
        helper.assertTrue(site.anchor().distSqr(heart) < 30 * 30, "with trees all round, the lot is still beside the heart: " + site);
        for (int i = 0; i < 5; i++) builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
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

    /** The two new designs, raised for real: a meeting hall (seven across, the stepped roof
     *  five blocks up — within a builder's reach from its own floor) and a well. */
    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "t13_hall_and_well")
    public static void t13_hall_and_well(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        Kit.hold(level, 3600, 3600, 48);
        Kit.prepare(level, 3600, 3600, 48);
        BlockPos heart = Kit.surface(level, 3600, 3600);
        VillageFolkEntity builder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(builder != null, "could not found a village for the hall");
        Villages.Village v = Villages.nearest(level, heart, 100);
        BlockPos hallAt = Kit.surface(level, 3614, 3600);
        BlockPos wellAt = Kit.surface(level, 3590, 3600);
        for (int i = 0; i < 12; i++) builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        builder.insertItem(new ItemStack(Items.CHEST, 2));
        builder.insertItem(new ItemStack(Items.CRAFTING_TABLE, 2));
        builder.insertItem(new ItemStack(Items.TORCH, 8));
        builder.insertItem(new ItemStack(Items.GLASS_PANE, 48));
        builder.insertItem(new ItemStack(Items.OAK_FENCE, 16));
        builder.insertItem(new ItemStack(Items.OAK_STAIRS, 64));
        builder.insertItem(new ItemStack(Items.OAK_STAIRS, 64));
        builder.insertItem(new ItemStack(Items.OAK_STAIRS, 64));
        builder.enqueue(Job.buildAt("hall", hallAt, Direction.WEST, 0));
        builder.enqueue(Job.buildAt("well", wellAt, Direction.EAST, 0));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);      // building is day work
            if (t % 1200 == 0) Kit.log("t13 @" + t + " built=" + Villages.builtList(v.id()) + " — " + builder.debugLine());
            var built = Villages.builtList(v.id());
            if (built.contains("hall") && built.contains("well")) {
                boolean ridge = !level.getBlockState(hallAt.above(9)).isAir();
                boolean roof = !level.getBlockState(wellAt.above(3)).isAir();
                Kit.log("t13 hall and well stand at tick " + t + "; hall ridge " + ridge + ", well roof " + roof);
                boolean stairs = level.getBlockState(hallAt.above(5).relative(Direction.NORTH, 4)).getBlock()
                    instanceof net.minecraft.world.level.block.StairBlock;
                Kit.log("t13 the hall's roof is of stairs: " + stairs);
                helper.assertTrue(ridge, "the hall's ridge should be up (nine above the floor)");
                helper.assertTrue(stairs, "and its roof laid in stairs");
                helper.assertTrue(roof, "the well should have its roof");
                helper.succeed();
            } else if (t >= 11800) {
                helper.fail("hall and well not both built in 11800 ticks: " + built + " — " + builder.debugLine());
            }
        });
    }

    /** The later ages' buildings and a great work, raised for real: the market's fence
     *  posts and roof, the gateway's obsidian frame, the monument's pillar. */
    @GameTest(template = EMPTY, timeoutTicks = 12000, batch = "t14_great_works")
    public static void t14_great_works(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        Kit.hold(level, 3800, 3800, 48);
        Kit.prepare(level, 3800, 3800, 48);
        BlockPos heart = Kit.surface(level, 3800, 3800);
        VillageFolkEntity builder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(builder != null, "could not found a village for the great works");
        Villages.Village v = Villages.nearest(level, heart, 100);
        BlockPos marketAt = Kit.surface(level, 3814, 3800);
        BlockPos gateAt = Kit.surface(level, 3788, 3800);
        BlockPos monumentAt = Kit.surface(level, 3800, 3814);
        for (int i = 0; i < 12; i++) builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        builder.insertItem(new ItemStack(Items.OAK_LOG, 64));
        builder.insertItem(new ItemStack(Items.CHEST, 2));
        builder.insertItem(new ItemStack(Items.CRAFTING_TABLE, 2));
        builder.insertItem(new ItemStack(Items.FURNACE, 1));
        builder.insertItem(new ItemStack(Items.TORCH, 8));
        builder.insertItem(new ItemStack(Items.OBSIDIAN, 10));
        builder.enqueue(Job.buildAt("market", marketAt, Direction.WEST, 0));
        builder.enqueue(Job.buildAt("gateway", gateAt, Direction.EAST, 0));
        builder.enqueue(Job.buildAt("monument", monumentAt, Direction.NORTH, 0));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);      // building is day work
            if (t % 1200 == 0) Kit.log("t14 @" + t + " built=" + Villages.builtList(v.id()) + " — " + builder.debugLine());
            var built = Villages.builtList(v.id());
            if (built.contains("market") && built.contains("gateway") && built.contains("monument")) {
                int posts = count(level, marketAt, 4, 0, 3, st -> st.is(net.minecraft.tags.BlockTags.LOGS));
                int obsidian = count(level, gateAt, 4, 0, 5, st -> st.is(Blocks.OBSIDIAN));
                boolean marketRoof = !level.getBlockState(marketAt.above(4)).isAir();
                boolean pillar = !level.getBlockState(monumentAt.above(4)).isAir();
                Kit.log("t14 stand at tick " + t + ": market posts " + posts + ", roof " + marketRoof
                    + "; gateway obsidian " + obsidian + "; monument pillar " + pillar);
                helper.assertTrue(posts >= 24, "the market stands on log posts four high, found " + posts + " log");
                helper.assertTrue(marketRoof, "the market's raised roof should be up");
                helper.assertTrue(obsidian == 10, "the gateway is a frame of ten obsidian, found " + obsidian);
                helper.assertTrue(pillar, "the monument's pillar should be up");
                helper.succeed();
            } else if (t >= 11800) {
                helper.fail("market, gateway and monument not all built in 11800 ticks: " + built + " — " + builder.debugLine());
            }
        });
    }

    /** How many blocks round {@code at} (this far each way, these heights) match. */
    private static int count(ServerLevel level, BlockPos at, int half, int lo, int hi,
                             java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> what) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-half, lo, -half), at.offset(half, hi, half))) {
            if (what.test(level.getBlockState(p))) n++;
        }
        return n;
    }

    /** What a village builds next, and in what order, all the way past the last age: a
     *  project that cannot go ahead is set aside and the next one goes up; a village that
     *  has come through every age raises its gateway and then the great works, for ever. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "t15_onward")
    public static void t15_onward(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        long now = level.getGameTime();
        java.util.UUID stone = java.util.UUID.randomUUID();
        Villages.restore(level, stone, new BlockPos(4200, 64, 4200), Villages.Age.STONE,
            List.of("storage", "shelter", "house", "house", "well"), 15);
        Villages.projectDue(stone, now);
        String first = Villages.nextProject(stone);
        helper.assertTrue("fortify".equals(first), "a Stone Age village wants its wall first, got " + first);
        Villages.defer(stone, "fortify", now + 1000000L);
        String meanwhile = Villages.nextProject(stone);
        Kit.log("t15 Stone Age: first " + first + ", with the wall set aside " + meanwhile);
        helper.assertTrue("smeltery".equals(meanwhile),
            "with the wall set aside the smeltery should go up meanwhile, got " + meanwhile);

        java.util.UUID late = java.util.UUID.randomUUID();
        // Homes enough for its twenty (a village short of beds builds houses first).
        List<String> raised = new java.util.ArrayList<>(List.of("storage", "shelter", "well", "fortify", "smeltery",
            "hall", "workshop", "watchtower", "market", "pen", "lighthouse", "chapel"));
        int homes = (20 + com.jrpetty.mcassistant.village.VillageMath.BEDS_PER_HOUSE - 1)
            / com.jrpetty.mcassistant.village.VillageMath.BEDS_PER_HOUSE;
        for (int i = 0; i < homes; i++) raised.add("house");
        Villages.restore(level, late, new BlockPos(4600, 64, 4600), Villages.Age.NETHER, raised, 20);
        List<String> order = new java.util.ArrayList<>();
        int roomBefore = Villages.housing(late);
        for (int i = 0; i < 5; i++) {
            String next = Villages.nextProject(late);
            order.add(next);
            if (next == null) break;
            Villages.noteProject(late, next, now);
        }
        Kit.log("t15 past the last age: " + order + ", renown " + Villages.renown(late)
            + ", room " + roomBefore + " -> " + Villages.housing(late));
        helper.assertTrue(order.equals(List.of("gateway", "granary", "barracks", "monument", "granary")),
            "the Nether Age raises its gateway, then the great works go round: " + order);
        helper.assertTrue(Villages.renown(late) == 4, "four great works raised, renown " + Villages.renown(late));
        helper.assertTrue(Villages.housing(late) == roomBefore + 6, "the barracks are room for six more");
        helper.succeed();
    }

    /** A grown village sends out a founding party: a new village of its own a long way
     *  off, paid for out of the mother village's larder, and written down in its record. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t16_colony")
    public static void t16_colony(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 5000, 5000, 32);
        Kit.hold(level, 5200, 5000, 32);
        BlockPos heart = Kit.surface(level, 5000, 5000);
        VillageFolkEntity founder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(founder != null, "could not found the mother village");
        Villages.Village mother = Villages.nearest(level, heart, 100);
        int foodBefore = storedAt(level, heart, Items.BREAD);
        BlockPos far = Kit.surface(level, 5200, 5000);
        boolean sent = com.jrpetty.mcassistant.Colonies.found(level, mother, far, level.getGameTime());
        helper.runAtTickTime(20, () -> {
            Villages.Village colony = Villages.nearest(level, far, 40);
            int folk = colony == null ? 0 : Villages.headcount(colony.id());
            int foodAfter = storedAt(level, heart, Items.BREAD);
            Kit.log("t16 colony sent " + sent + ": " + (colony == null ? "none" : colony.centre() + ", " + folk + " folk")
                + "; mother's record " + Villages.builtList(mother.id()) + "; bread " + foodBefore + " -> " + foodAfter);
            helper.assertTrue(sent && colony != null && !colony.id().equals(mother.id()),
                "a new village should stand two hundred blocks out");
            helper.assertTrue(folk == com.jrpetty.mcassistant.Colonies.party(),
                "the colony is founded by a full party, found " + folk);
            helper.assertTrue(Villages.builtList(mother.id()).contains("colony"), "the mother village remembers its colony");
            helper.assertTrue(foodAfter < foodBefore, "the party is fed out of the mother village's larder");
            helper.succeed();
        });
    }

    /** People, not workers: time spent together makes friends, a generous friend feeds a
     *  hungry one, raising a child makes partners and a family, and all of it is kept. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t17_social")
    public static void t17_social(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 5600, 5600, 32);
        BlockPos heart = Kit.surface(level, 5600, 5600);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.east(), 0.0F);
        helper.assertTrue(a != null && b != null && a.ownerId().equals(b.ownerId()), "two folk of one village");
        // A moment for the ground they stand on to finish loading (its entities are read
        // from it), then side by side.
        helper.runAtTickTime(20, () -> {
            b.moveTo(a.getX() + 1.0, a.getY(), a.getZ(), 0.0F, 0.0F);
            com.jrpetty.mcassistant.entity.Social.Life la = a.life(), lb = b.life();
            la.traits().clear();
            la.traits().add(com.jrpetty.mcassistant.entity.Social.Trait.SOCIABLE);
            la.traits().add(com.jrpetty.mcassistant.entity.Social.Trait.GENEROUS);
            lb.traits().clear();
            lb.traits().add(com.jrpetty.mcassistant.entity.Social.Trait.CHEERFUL);
            lb.traits().add(com.jrpetty.mcassistant.entity.Social.Trait.SOCIABLE);
            for (int i = 0; i < 20; i++) {
                a.socialBeat();
                b.socialBeat();
            }
            int ab = la.affinity(b.getUUID()), ba = lb.affinity(a.getUUID());
            Kit.log("t17 after twenty beats together: a->b " + ab + ", b->a " + ba);
            helper.assertTrue(ab >= com.jrpetty.mcassistant.entity.Social.FRIEND && ba >= com.jrpetty.mcassistant.entity.Social.FRIEND,
                "two folk who spend their time together become friends: " + ab + " / " + ba);
            // A generous friend does not let a friend go hungry.
            b.removeMatching(st -> st.get(net.minecraft.core.component.DataComponents.FOOD) != null, 999);
            a.insertItem(new ItemStack(Items.BREAD, 10));
            a.socialBeat();
            Kit.log("t17 b's food after a generous friend's beat: " + b.countFood());
            helper.assertTrue(b.countFood() > 0, "a generous folk shares a ration with a hungry friend");
            // A child: partners, a family, a trait from a parent.
            a.insertItem(new ItemStack(Items.BREAD, 4));
            b.insertItem(new ItemStack(Items.BREAD, 4));
            VillageFolkEntity child = a.raiseChildWith(b);
            helper.assertTrue(child != null, "the two could raise a child");
            com.jrpetty.mcassistant.entity.Social.Life lc = child.life();
            Kit.log("t17 family: " + lc.describe(child.displayNameCap(), "child") + " / " + la.describe(a.displayNameCap(), "first"));
            helper.assertTrue(b.getUUID().equals(la.partner()) && a.getUUID().equals(lb.partner()), "parents become partners");
            helper.assertTrue(lc.parents().contains(a.displayNameCap()) && lc.parents().contains(b.displayNameCap()),
                "the child knows whose it is: " + lc.parents());
            helper.assertTrue(la.children() == 1 && lb.children() == 1, "both parents count the child");
            boolean takesAfter = false;
            for (var t : lc.traits()) takesAfter |= la.has(t) || lb.has(t);
            helper.assertTrue(lc.traits().size() == 2 && takesAfter, "the child takes after a parent: " + lc.traits());
            // Kept across a save.
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            la.save(tag);
            com.jrpetty.mcassistant.entity.Social.Life back = new com.jrpetty.mcassistant.entity.Social.Life();
            back.load(tag);
            helper.assertTrue(back.describe("x", "y").equals(la.describe("x", "y")),
                "a folk's personality and friends survive a save: " + back.describe("x", "y"));
            helper.succeed();
        });
    }

    /** Night: a folk finds a bed in the village (not just one beside the heart) and
     *  sleeps in it, and is up and about by morning. */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "t18_sleep")
    public static void t18_sleep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 6000, 6000, 40);
        Kit.prepare(level, 6000, 6000, 40);
        BlockPos heart = Kit.surface(level, 6000, 6000);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a folk to put to bed");
        // A house sixteen blocks out with a bed in it: further than the old twelve-block search.
        BlockPos foot = Kit.surface(level, 6016, 6000);
        BlockPos head = foot.east();
        level.setBlock(foot, Blocks.RED_BED.defaultBlockState()
            .setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.EAST)
            .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 3);
        level.setBlock(head, Blocks.RED_BED.defaultBlockState()
            .setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.EAST)
            .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        level.setDayTime(18000);                       // the middle of the night
        long[] slept = {-1};
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (slept[0] < 0) {
                if (level.getDayTime() % 24000 < 15000) level.setDayTime(18000);   // keep it night
                if (folk.isSleeping()) {
                    slept[0] = t;
                    Kit.log("t18 asleep at tick " + t + " in the bed at " + folk.getSleepingPos().orElse(null));
                    level.setDayTime(1000);            // and morning comes
                } else if (t % 400 == 0) {
                    Kit.log("t18 @" + t + " not asleep yet — " + folk.debugLine());
                }
                if (t >= 3000) helper.fail("no folk asleep in 3000 ticks of night: " + folk.debugLine());
            } else if (!folk.isSleeping()) {
                Kit.log("t18 up at tick " + t + ", " + (t - slept[0]) + " ticks after morning came");
                helper.succeed();
            } else if (t - slept[0] > 800) {
                helper.fail("still asleep 800 ticks into the morning");
            }
        });
    }

    /** The gateway's obsidian is made where the lava is: a Nether Age miner with a diamond
     *  pickaxe and a bucket of water turns a lava pool's surface into obsidian and takes it. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t19_obsidian")
    public static void t19_obsidian(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 6400, 6400, 32);
        Kit.prepare(level, 6400, 6400, 32);
        BlockPos heart = Kit.surface(level, 6400, 6400);
        VillageFolkEntity miner = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(miner != null, "a miner for the lava");
        helper.runAtTickTime(20, () -> {
            Villages.ageForTests(miner.ownerId(), Villages.Age.NETHER);
            miner.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.MINE);
            miner.insertItem(new ItemStack(Items.DIAMOND_PICKAXE));
            miner.insertItem(new ItemStack(Items.WATER_BUCKET));
            // A lava source let into the ground three blocks off, open to the air above.
            BlockPos top = Kit.surface(level, miner.getBlockX() + 3, miner.getBlockZ());
            BlockPos lava = top.below();
            level.setBlock(top, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(lava, Blocks.LAVA.defaultBlockState(), 3);
            int before = miner.countCarried(st -> st.is(Items.OBSIDIAN));
            miner.obsidianFromLava();
            int after = miner.countCarried(st -> st.is(Items.OBSIDIAN));
            boolean gone = level.getFluidState(lava).isEmpty();
            Kit.log("t19 obsidian " + before + " -> " + after + ", lava there now " + !gone
                + ", block " + level.getBlockState(lava).getBlock());
            helper.assertTrue(after == before + 1, "water on the lava gives an obsidian: " + before + " -> " + after);
            helper.assertTrue(gone, "the lava is gone from where it was");
            helper.assertTrue(miner.countCarried(st -> st.is(Items.WATER_BUCKET)) == 1, "the water bucket is kept");
            helper.succeed();
        });
    }

    /** A folk is somebody you can talk to: it answers every question in its own way,
     *  likes a present it loves and refuses one it hates, won't wander off with a
     *  stranger but will with a friend, remembers being hit, understands plain words,
     *  and keeps all of it across a save. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t20_talk")
    public static void t20_talk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 6800, 6800, 32);
        BlockPos heart = Kit.surface(level, 6800, 6800);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a folk to talk to");
        level.setDayTime(1000);                       // broad day: nobody is off to bed
        helper.runAtTickTime(20, () -> {
            folk.life().traits().clear();
            folk.life().traits().add(com.jrpetty.mcassistant.entity.Social.Trait.SOCIABLE);
            folk.life().traits().add(com.jrpetty.mcassistant.entity.Social.Trait.CHEERFUL);
            folk.ensurePersona();
            com.jrpetty.mcassistant.entity.Persona me = folk.persona();
            helper.assertTrue(me.rolled() && me.mood() >= 0 && me.mood() <= 100, "a folk has an inner life");
            net.minecraft.world.entity.player.Player you = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            java.util.UUID id = you.getUUID();
            StringBuilder heard = new StringBuilder();
            for (com.jrpetty.mcassistant.entity.TalkTopic t : new com.jrpetty.mcassistant.entity.TalkTopic[]{
                    com.jrpetty.mcassistant.entity.TalkTopic.OPEN, com.jrpetty.mcassistant.entity.TalkTopic.HOW,
                    com.jrpetty.mcassistant.entity.TalkTopic.DOING, com.jrpetty.mcassistant.entity.TalkTopic.ABOUT,
                    com.jrpetty.mcassistant.entity.TalkTopic.PEOPLE, com.jrpetty.mcassistant.entity.TalkTopic.VILLAGE,
                    com.jrpetty.mcassistant.entity.TalkTopic.DREAMS, com.jrpetty.mcassistant.entity.TalkTopic.HOBBY,
                    com.jrpetty.mcassistant.entity.TalkTopic.JOKE}) {
                String said = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk, you, t, "");
                helper.assertTrue(said != null && !said.isBlank(), "an answer to " + t);
                heard.append(t).append(": ").append(said).append(" | ");
            }
            Kit.log("t20 a conversation with " + folk.displayNameCap() + " (" + me.hobby().word + ", hopes "
                + me.ambition().hope + "): " + heard);
            // A stranger asking it to come along is turned down.
            String no = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk, you, com.jrpetty.mcassistant.entity.TalkTopic.FOLLOW, "");
            helper.assertTrue(!folk.isFollowing(you), "a folk does not go off with a stranger: " + no);
            // Something it loves.
            ItemStack loved = new ItemStack(switch (me.loves()) {
                case FLOWERS -> Items.POPPY;
                case SWEETS -> Items.COOKIE;
                case GEMS -> Items.EMERALD;
                case BOOKS -> Items.BOOK;
                case MUSIC -> Items.NOTE_BLOCK;
                case FISH -> Items.COD;
                case TOOLS -> Items.IRON_PICKAXE;
                case WOOL -> Items.WHITE_WOOL;
                case GOLD -> Items.GOLD_INGOT;
                default -> Items.POPPY;
            }, 2);
            you.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, loved);
            int before = me.affinity(id);
            String thanks = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk, you, com.jrpetty.mcassistant.entity.TalkTopic.GIFT, "");
            int after = me.affinity(id);
            Kit.log("t20 gift of " + me.loves() + ": " + thanks + " (" + before + " -> " + after + ")");
            helper.assertTrue(after >= before + 15, "a present it loves warms it: " + before + " -> " + after);
            helper.assertTrue(you.getMainHandItem().getCount() == 1, "one of the two was given");
            // Something it hates is refused.
            you.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.ROTTEN_FLESH, 3));
            int pre = me.affinity(id);
            String ugh = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk, you, com.jrpetty.mcassistant.entity.TalkTopic.GIFT, "");
            helper.assertTrue(me.affinity(id) < pre && you.getMainHandItem().getCount() == 3,
                "rotten flesh is refused and resented: " + ugh);
            // A friend it goes with — and comes back from when asked.
            me.feelFor(id, "friend", 60);
            String yes = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk, you, com.jrpetty.mcassistant.entity.TalkTopic.FOLLOW, "");
            Kit.log("t20 asked along as a friend (mood " + me.mood() + "): " + yes);
            helper.assertTrue(folk.isFollowing(you), "a folk goes along with a friend: " + yes);
            com.jrpetty.mcassistant.entity.FolkTalk.answer(folk, you, com.jrpetty.mcassistant.entity.TalkTopic.SAY, "you can go back to work now");
            helper.assertTrue(!folk.isFollowing(you), "and goes back to its day when told it may");
            // Plain words.
            helper.assertTrue(com.jrpetty.mcassistant.entity.FolkTalk.understand("How are you today?")
                == com.jrpetty.mcassistant.entity.TalkTopic.HOW, "how are you");
            helper.assertTrue(com.jrpetty.mcassistant.entity.FolkTalk.understand("tell me a joke")
                == com.jrpetty.mcassistant.entity.TalkTopic.JOKE, "a joke");
            helper.assertTrue(com.jrpetty.mcassistant.entity.FolkTalk.understand("will you come with me?")
                == com.jrpetty.mcassistant.entity.TalkTopic.FOLLOW, "come with me");
            // Being hit is remembered.
            int fond = me.affinity(id);
            folk.hurt(level.damageSources().playerAttack(you), 1.0F);
            Kit.log("t20 hit: " + fond + " -> " + me.affinity(id));
            helper.assertTrue(me.affinity(id) <= fond - 25, "being hit sours it: " + fond + " -> " + me.affinity(id));
            // All of it kept across a save.
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            me.save(tag);
            com.jrpetty.mcassistant.entity.Persona back = new com.jrpetty.mcassistant.entity.Persona();
            back.load(tag);
            helper.assertTrue(back.hobby() == me.hobby() && back.ambition() == me.ambition()
                && back.quirk().equals(me.quirk()) && back.affinity(id) == me.affinity(id)
                && back.memories().size() == me.memories().size(), "a folk's inner life survives a save");
            helper.succeed();
        });
    }

    /** A village has a name and a history, and a player has a standing in it: ask a
     *  folk how you can help and it sets you an errand from what the village needs;
     *  hand it over and you are rewarded, and the village thinks better of you; turn
     *  it against you and nobody will talk to you; and folk will tell you what they
     *  think of each other. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t21_errands")
    public static void t21_errands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 7200, 7200, 32);
        BlockPos heart = Kit.surface(level, 7200, 7200);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity other = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(folk != null && other != null, "two folk of one village");
        level.setDayTime(1000);
        helper.runAtTickTime(20, () -> {
            java.util.UUID village = folk.ownerId();
            String name = Villages.name(village);
            helper.assertTrue(!name.isEmpty() && name.equals(Villages.name(village)), "a village has a name of its own");
            long day = level.getDayTime() / 24000L;
            Villages.tell(village, day, "the test bell was rung");
            boolean written = com.jrpetty.mcassistant.village.Chronicle.of(village).stream()
                .anyMatch(e -> e.text().equals("the test bell was rung"));
            helper.assertTrue(written, "what happens goes into the village's history");
            ItemStack book = com.jrpetty.mcassistant.entity.Chronicles.book(village, day);
            var content = book.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
            helper.assertTrue(content != null && content.pages().size() >= 2, "the chronicle is a book with pages");
            Kit.log("t21 " + name + "'s chronicle: " + content.pages().size() + " pages, titled "
                + content.title().raw());

            folk.life().traits().clear();
            folk.life().traits().add(com.jrpetty.mcassistant.entity.Social.Trait.CHEERFUL);
            folk.life().traits().add(com.jrpetty.mcassistant.entity.Social.Trait.GENEROUS);
            folk.ensurePersona();
            other.ensurePersona();
            net.minecraft.world.entity.player.Player you = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            com.jrpetty.mcassistant.entity.Persona me = folk.persona();
            String asked = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk, you, com.jrpetty.mcassistant.entity.TalkTopic.HELP, "");
            Kit.log("t21 asked how to help: " + asked + " [" + com.jrpetty.mcassistant.entity.Errands.describe(me) + "]");
            helper.assertTrue(me.hasErrand() && you.getUUID().equals(me.errandFor()), "asking to help gets an errand: " + asked);
            if (!me.errandKind().equals("hunt")) {
                ItemStack bring = new ItemStack(switch (me.errandItem()) {
                    case "food" -> Items.BREAD;
                    case "logs" -> Items.OAK_LOG;
                    case "stone" -> Items.COBBLESTONE;
                    case "iron" -> Items.IRON_INGOT;
                    case "coal" -> Items.COAL;
                    case "diamond" -> Items.DIAMOND;
                    case "obsidian" -> Items.OBSIDIAN;
                    case "flowers" -> Items.POPPY;
                    case "fish" -> Items.COD;
                    case "book" -> Items.BOOK;
                    case "note_block" -> Items.NOTE_BLOCK;
                    default -> net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace(me.errandItem()));
                }, me.errandCount());
                you.getInventory().add(bring);
                helper.assertTrue(com.jrpetty.mcassistant.entity.Errands.canDeliver(folk, you), "the errand can be handed over");
                int xpBefore = you.totalExperience;
                String done = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk, you, com.jrpetty.mcassistant.entity.TalkTopic.DELIVER, "");
                Kit.log("t21 handed over: " + done + " (xp " + xpBefore + " -> " + you.totalExperience + ")");
                helper.assertTrue(!me.hasErrand(), "a delivered errand is done: " + done);
                helper.assertTrue(you.totalExperience > xpBefore, "and rewarded with experience");
                com.jrpetty.mcassistant.entity.Standing.View view = com.jrpetty.mcassistant.entity.Standing.of(
                    village, you.getUUID(), level.getGameTime() + 1000);
                Kit.log("t21 standing after the errand: " + com.jrpetty.mcassistant.entity.Standing.titleIn(village, view.title())
                    + " (warmth " + view.score() + ", known to " + view.knownBy() + ")");
                helper.assertTrue(view.title().atLeast(com.jrpetty.mcassistant.entity.Standing.Title.FRIEND),
                    "helping the village makes you its friend: " + view.title());
            }
            // Folk on each other.
            String ofOther = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk, you, com.jrpetty.mcassistant.entity.TalkTopic.SAY,
                "what do you think of " + other.displayNameCap() + "?");
            Kit.log("t21 asked about " + other.displayNameCap() + ": " + ofOther);
            helper.assertTrue(ofOther.contains(other.displayNameCap()), "a folk speaks of another by name: " + ofOther);
            // A village turned against you.
            me.feelFor(you.getUUID(), "x", -200);
            other.persona().feelFor(you.getUUID(), "x", -200);
            com.jrpetty.mcassistant.entity.Standing.stir(village, you.getUUID());
            String shut = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk, you, com.jrpetty.mcassistant.entity.TalkTopic.HOW, "");
            Kit.log("t21 as an outcast: " + shut);
            helper.assertTrue(com.jrpetty.mcassistant.entity.Standing.of(village, you.getUUID(), level.getGameTime() + 2000).title()
                == com.jrpetty.mcassistant.entity.Standing.Title.OUTCAST, "a village can turn against you");
            helper.succeed();
        });
    }

    /** A village's life together: two who grow close enough pledge themselves and
     *  the village holds their wedding; a child is born small, plays, and grows up
     *  in three days; every seventh evening is a feast; a death is a vigil. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "t22_village_life")
    public static void t22_village_life(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 7600, 7600, 32);
        BlockPos heart = Kit.surface(level, 7600, 7600);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.east(), 0.0F);
        helper.assertTrue(a != null && b != null, "two folk of one village");
        level.setDayTime(1000);
        VillageFolkEntity[] child = new VillageFolkEntity[1];
        helper.runAtTickTime(20, () -> {
            java.util.UUID village = a.ownerId();
            b.moveTo(a.getX() + 1.0, a.getY(), a.getZ(), 0.0F, 0.0F);
            a.ensurePersona();
            b.ensurePersona();
            a.life().feel(b.getUUID(), b.displayNameCap(), 90);
            b.life().feel(a.getUUID(), a.displayNameCap(), 90);
            a.socialBeat();
            long day = level.getDayTime() / 24000L;
            Kit.log("t22 courtship: " + a.displayNameCap() + " & " + b.displayNameCap() + " partners "
                + b.getUUID().equals(a.life().partner()) + ", tonight " + com.jrpetty.mcassistant.entity.Gatherings.tonight(village, day));
            helper.assertTrue(b.getUUID().equals(a.life().partner()) && a.getUUID().equals(b.life().partner()),
                "two who grow that close pledge themselves");
            helper.assertTrue(com.jrpetty.mcassistant.entity.Gatherings.tonight(village, day)
                == com.jrpetty.mcassistant.entity.Gatherings.Kind.WEDDING, "and the village holds a wedding");
            a.insertItem(new ItemStack(Items.BREAD, 8));
            b.insertItem(new ItemStack(Items.BREAD, 8));
            child[0] = a.raiseChildWith(b);
            helper.assertTrue(child[0] != null && child[0].isBaby(), "a child is born a child");
            helper.assertTrue(child[0].getBbHeight() < a.getBbHeight() * 0.7F,
                "and small: " + child[0].getBbHeight() + " against " + a.getBbHeight());
            String chat = com.jrpetty.mcassistant.entity.FolkTalk.answer(child[0],
                helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL), com.jrpetty.mcassistant.entity.TalkTopic.ABOUT, "");
            Kit.log("t22 the child " + child[0].displayNameCap() + " says: " + chat);
            child[0].bornDaysAgo(com.jrpetty.mcassistant.entity.VillageFolkEntity.GROW_DAYS);
            // The calendar: a feast every seventh evening, a vigil after a death.
            java.util.UUID elsewhere = java.util.UUID.randomUUID();
            helper.assertTrue(com.jrpetty.mcassistant.entity.Gatherings.tonight(elsewhere, 13)
                == com.jrpetty.mcassistant.entity.Gatherings.Kind.FEAST, "every seventh evening is a feast");
            com.jrpetty.mcassistant.entity.Gatherings.mourn(elsewhere, "Old Tom", 20);
            helper.assertTrue(com.jrpetty.mcassistant.entity.Gatherings.tonight(elsewhere, 20)
                == com.jrpetty.mcassistant.entity.Gatherings.Kind.VIGIL, "a death is a vigil");
        });
        helper.runAtTickTime(260, () -> {
            Kit.log("t22 three days on: " + child[0].displayNameCap() + " a child still? " + child[0].isBaby()
                + ", height " + child[0].getBbHeight());
            helper.assertTrue(!child[0].isBaby() && child[0].getBbHeight() > 1.5F, "a child grows up in three days");
            helper.succeed();
        });
    }

    /** A village that takes a player to its heart: as its honoured guest it resolves
     *  to build them a house, keeps the bed for them and hands them the key; as its
     *  hero it holds a night in their honour and gives them its medal. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t23_welcome")
    public static void t23_welcome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 8000, 8000, 32);
        BlockPos heart = Kit.surface(level, 8000, 8000);
        java.util.List<VillageFolkEntity> folk = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.east(i), 0.0F);
            helper.assertTrue(f != null, "folk " + i);
            folk.add(f);
        }
        level.setDayTime(1000);
        helper.runAtTickTime(20, () -> {
            java.util.UUID village = folk.get(0).ownerId();
            net.minecraft.world.entity.player.Player you = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            long t = level.getGameTime();
            for (VillageFolkEntity f : folk) {
                f.ensurePersona();
                f.persona().feelFor(you.getUUID(), you.getName().getString(), 40);
            }
            com.jrpetty.mcassistant.entity.Standing.stir(village, you.getUUID());
            Villages.noteProject(village, "storage", t);
            com.jrpetty.mcassistant.entity.Welcome.check(level, you, village);
            var guest = com.jrpetty.mcassistant.village.Chronicle.guest(village, you.getUUID());
            helper.assertTrue(guest != null, "an honoured guest is to have a house");
            helper.assertTrue(Villages.projectsWanted(village).contains("guesthouse"),
                "and the village sets about building it: " + Villages.projectsWanted(village));
            Villages.noteProject(village, "guesthouse", t);
            helper.assertTrue(guest.built, "the house goes up");
            helper.assertTrue(Villages.inAGuestHouse(village, new BlockPos((int) guest.x, (int) guest.y, (int) guest.z)),
                "its bed is kept for the guest");
            String key = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk.get(1), you, com.jrpetty.mcassistant.entity.TalkTopic.OPEN, "");
            Kit.log("t23 the key: " + key);
            boolean hasKey = false;
            for (ItemStack st : you.getInventory().items) hasKey |= st.is(Items.TRIPWIRE_HOOK);
            helper.assertTrue(hasKey, "the guest is handed the key: " + key);
            // A hero.
            for (VillageFolkEntity f : folk) f.persona().feelFor(you.getUUID(), you.getName().getString(), 40);
            com.jrpetty.mcassistant.entity.Standing.stir(village, you.getUUID());
            com.jrpetty.mcassistant.entity.Welcome.check(level, you, village);
            long day = level.getDayTime() / 24000L;
            helper.assertTrue(guest.hero, "a village names its hero");
            helper.assertTrue(com.jrpetty.mcassistant.entity.Gatherings.tonight(village, day)
                == com.jrpetty.mcassistant.entity.Gatherings.Kind.HONOUR, "and celebrates them tonight");
            String medal = com.jrpetty.mcassistant.entity.FolkTalk.answer(folk.get(2), you, com.jrpetty.mcassistant.entity.TalkTopic.OPEN, "");
            Kit.log("t23 the medal: " + medal);
            boolean hasMedal = false;
            for (ItemStack st : you.getInventory().items) hasMedal |= st.is(Items.GOLD_NUGGET);
            helper.assertTrue(hasMedal, "and gives them its medal: " + medal);
            helper.succeed();
        });
    }

    /** The one the village looks up to becomes its elder, and the village says so. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "t24_elder")
    public static void t24_elder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 8400, 8400, 32);
        BlockPos heart = Kit.surface(level, 8400, 8400);
        java.util.List<VillageFolkEntity> folk = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) folk.add(VillageFolkSpawnerBlock.raise(level, heart.east(i), 0.0F));
        helper.runAtTickTime(20, () -> {
            java.util.UUID village = folk.get(0).ownerId();
            for (VillageFolkEntity f : folk) f.ensurePersona();
            VillageFolkEntity wise = folk.get(2);
            for (VillageFolkEntity f : folk) if (f != wise) f.life().feel(wise.getUUID(), wise.displayNameCap(), 80);
            long day = level.getDayTime() / 24000L + 1;
            Villages.chooseElder(village, day);
            Kit.log("t24 elder of " + Villages.name(village) + ": " + Villages.elderName(village));
            helper.assertTrue(wise.getUUID().equals(Villages.elder(village)) && wise.isElder(),
                "the folk everybody looks up to is the elder: " + Villages.elderName(village));
            helper.assertTrue(Villages.news(village).stream().anyMatch(n -> n.text().contains("village elder")),
                "and the village says so");
            // The register: every resident, the elder marked.
            net.minecraft.world.item.ItemStack book = com.jrpetty.mcassistant.entity.Chronicles.register(village, day);
            net.minecraft.world.item.component.WrittenBookContent content =
                book.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
            String all = content == null ? "" : content.pages().stream()
                .map(pg -> pg.raw().getString()).collect(java.util.stream.Collectors.joining(" | "));
            Kit.log("t24 the register: " + all.substring(0, Math.min(400, all.length())));
            helper.assertTrue(all.contains(wise.displayNameCap() + "§r (elder)") && all.contains("Elder: " + wise.displayNameCap()),
                "the register lists everybody and marks the elder");
            helper.succeed();
        });
    }

    /** Word gets round: a folk who thinks well of a player tells a friend, who greets
     *  them as somebody they have heard of; and a grudge softens with the days. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "t25_gossip")
    public static void t25_gossip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 8800, 8800, 32);
        BlockPos heart = Kit.surface(level, 8800, 8800);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.east(), 0.0F);
        helper.assertTrue(a != null && b != null, "two folk of one village");
        helper.runAtTickTime(20, () -> {
            java.util.UUID village = a.ownerId();
            a.ensurePersona();
            b.ensurePersona();
            net.minecraft.world.entity.player.Player you = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            a.persona().feelFor(you.getUUID(), you.getName().getString(), 60);
            a.life().feel(b.getUUID(), b.displayNameCap(), 50);
            b.life().feel(a.getUUID(), a.displayNameCap(), 50);
            helper.assertTrue(!b.persona().knows(you.getUUID()), "the second has never met you");
            a.gossip(b, true, false, village);
            Kit.log("t25 " + b.displayNameCap() + " heard from " + b.persona().heardFrom(you.getUUID())
                + ", thinks " + b.persona().affinity(you.getUUID()));
            helper.assertTrue(a.displayNameCap().equals(b.persona().heardFrom(you.getUUID()))
                && b.persona().affinity(you.getUUID()) > 0, "and hears about you from the first");
            helper.assertTrue(!b.persona().knows(you.getUUID()), "which is not the same as meeting you");
            String hello = com.jrpetty.mcassistant.entity.FolkTalk.answer(b, you,
                com.jrpetty.mcassistant.entity.TalkTopic.OPEN, "");
            Kit.log("t25 on meeting: " + hello);
            helper.assertTrue(hello.contains(a.displayNameCap()), "so it greets you as somebody it has heard of: " + hello);
            helper.assertTrue(b.persona().knows(you.getUUID()), "and now it knows you");
            // A grudge softens.
            long day = level.getDayTime() / 24000L;
            b.persona().feelFor(you.getUUID(), you.getName().getString(), -60);
            int before = b.persona().affinity(you.getUUID());
            b.persona().mend(day + 1, 2);
            b.persona().mend(day + 1, 2);
            b.persona().mend(day + 2, 2);
            Kit.log("t25 a grudge: " + before + " -> " + b.persona().affinity(you.getUUID()));
            helper.assertTrue(b.persona().affinity(you.getUUID()) == before + 4, "a grudge softens a little each day");
            // A friend brings you something, unasked — but not every time you pass.
            boolean given = false;
            for (int i = 0; i < 60 && !given; i++) given = a.present(you);
            int carried = 0;
            for (int i = 0; i < you.getInventory().getContainerSize(); i++) carried += you.getInventory().getItem(i).getCount();
            Kit.log("t25 a present: " + given + ", you now carry " + carried + " item(s)");
            helper.assertTrue(given && carried > 0, "a folk fond of you gives you a present");
            helper.assertTrue(!a.present(you), "and not another the same day");
            // Set on by a monster: kill it and you saved its life.
            net.minecraft.world.entity.monster.Zombie zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
            zombie.moveTo(b.getX() + 1.0, b.getY(), b.getZ(), 0.0F, 0.0F);
            level.addFreshEntity(zombie);
            b.hurt(level.damageSources().mobAttack(zombie), 1.0F);
            helper.assertTrue(b.besetBy(zombie.getUUID()), "a folk set on by a monster is in danger from it");
            int fond = b.persona().affinity(you.getUUID());
            b.rescuedBy(you, "zombie");
            zombie.discard();
            Kit.log("t25 rescued: " + fond + " -> " + b.persona().affinity(you.getUUID()) + "; news: "
                + Villages.news(village).get(0).text());
            helper.assertTrue(b.persona().affinity(you.getUUID()) > fond
                && Villages.news(village).stream().anyMatch(n -> n.text().contains("saved " + b.displayNameCap())),
                "and whoever saves it is remembered for it");
            helper.succeed();
        });
    }

    /** A miner with ore and stone to spare sells a bundle for an emerald, and keeps the emerald. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "t26_trade")
    public static void t26_trade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 9200, 9200, 32);
        BlockPos heart = Kit.surface(level, 9200, 9200);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(a != null, "a folk");
        helper.runAtTickTime(20, () -> {
            a.ensurePersona();
            a.setJob(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.MINE);
            a.insertItem(new ItemStack(Items.RAW_COPPER, 20));
            net.minecraft.world.entity.player.Player you = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            you.getInventory().add(new ItemStack(Items.EMERALD, 1));
            String offer = com.jrpetty.mcassistant.entity.FolkTalk.answer(a, you,
                com.jrpetty.mcassistant.entity.TalkTopic.TRADE, "");
            Kit.log("t26 the offer: " + offer);
            helper.assertTrue(offer.contains("for an emerald") && com.jrpetty.mcassistant.entity.Trade.canPay(a, you),
                "it offers what it has to spare, for what you can pay: " + offer);
            String done = com.jrpetty.mcassistant.entity.FolkTalk.answer(a, you,
                com.jrpetty.mcassistant.entity.TalkTopic.DELIVER, "");
            int goods = 0, emeralds = 0;
            for (int i = 0; i < you.getInventory().getContainerSize(); i++) {
                ItemStack st = you.getInventory().getItem(i);
                if (st.is(Items.EMERALD)) emeralds += st.getCount();
                else goods += st.getCount();
            }
            Kit.log("t26 the deal: " + done + " — you have " + goods + " goods, " + emeralds + " emeralds; "
                + a.displayNameCap() + " has " + a.countCarried(st -> st.is(Items.EMERALD)) + " emerald(s)");
            helper.assertTrue(goods >= 8 && emeralds == 0 && a.countCarried(st -> st.is(Items.EMERALD)) == 1,
                "the goods change hands, and so does the price");
            helper.assertTrue(!com.jrpetty.mcassistant.entity.Trade.live(a, you), "and the deal is done");
            helper.succeed();
        });
    }

    /** Everybody sleeps: two guards keep the night in two watches, the founders' camp
     *  has a bed each, the village plans a house for every two people without one,
     *  and a bed somebody brings a folk is laid at the camp and slept in. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "t27_night")
    public static void t27_night(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 9600, 9600, 32);
        Kit.prepare(level, 9600, 9600, 24);
        BlockPos heart = Kit.surface(level, 9600, 9600);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.east(), 0.0F);
        VillageFolkEntity c = VillageFolkSpawnerBlock.raise(level, heart.west(), 0.0F);
        helper.assertTrue(a != null && b != null && c != null, "three folk of one village");
        helper.runAtTickTime(20, () -> {
            java.util.UUID village = a.ownerId();
            a.setJob(StationTask.GUARD);
            b.setJob(StationTask.GUARD);
            a.setShift(AssistantEntity.Shift.ALWAYS);
            b.setShift(AssistantEntity.Shift.ALWAYS);
            level.setDayTime(15000);                       // (the sky darkens on the next tick)
        });
        boolean[] w = new boolean[4];
        helper.runAtTickTime(24, () -> {
            w[0] = a.onShift();
            w[1] = b.onShift();
            level.setDayTime(20000);
        });
        helper.runAtTickTime(28, () -> {
            w[2] = a.onShift();
            w[3] = b.onShift();
            level.setDayTime(6000);
        });
        helper.runAtTickTime(32, () -> {
            java.util.UUID village = a.ownerId();
            Kit.log("t27 watches: " + a.displayNameCap() + " " + w[0] + "/" + w[2] + ", "
                + b.displayNameCap() + " " + w[1] + "/" + w[3] + "; by day " + a.onShift() + "/" + b.onShift()
                + "; night? " + level.isNight());
            helper.assertTrue(w[0] != w[1] && w[2] != w[3] && w[0] != w[2],
                "two guards keep the night in two watches, and each sleeps half of it");
            helper.assertTrue(a.onShift() && b.onShift(), "and both are on duty by day");
            // The founders' camp.
            BlockPos chest = heart;
            int laid = com.jrpetty.mcassistant.VillageSpawner.pitchCamp(level, chest, 3);
            int camp = com.jrpetty.mcassistant.VillageSpawner.campBeds(level, chest).size();
            Kit.log("t27 the camp: " + laid + " laid, " + camp + " standing");
            helper.assertTrue(laid == 3 && camp == 3, "the founders lay a bed each round the heart");
            // Houses until there is a bed for everyone.
            Villages.noteProject(village, "storage", level.getGameTime());
            Villages.noteProject(village, "shelter", level.getGameTime());
            Villages.noteProject(village, "well", level.getGameTime());
            Villages.noteProject(village, "house", level.getGameTime());
            java.util.List<String> want = Villages.projectsWanted(village);
            Kit.log("t27 3 folk, homes for " + Villages.bedsPlanned(village) + ": wanted " + want);
            helper.assertTrue(Villages.bedsPlanned(village) >= 3 || want.contains("house"),
                "a village with more people than its homes have beds builds another house");
            // A bed somebody brings.
            c.insertItem(new ItemStack(Items.RED_BED));
            helper.assertTrue(c.layGivenBed() && c.bedPos() != null
                    && com.jrpetty.mcassistant.VillageSpawner.campBeds(level, chest).size() == 4,
                "a bed brought to a folk is laid at the camp, and it is that folk's");
            helper.succeed();
        });
    }

    // ============================================================ the stores

    private static int holding(ServerLevel level, BlockPos at, net.minecraft.world.item.Item item) {
        if (!(level.getBlockEntity(at) instanceof net.minecraft.world.Container c)) return -1;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    /**
     * The carriers stock the storehouse. A field's chest and a furnace's finished ingots are
     * carried in to the storehouse's chest, not to the founding chest nearer the heart; the
     * field keeps its seed and its seed carrots, the furnace its ore. Then a farmer who has
     * run out of seed draws it from the storehouse.
     */
    @GameTest(template = EMPTY, timeoutTicks = 8000, batch = "t28_stores")
    public static void t28_stores(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        Kit.hold(level, 10800, 10800, 64);
        Kit.prepare(level, 10800, 10800, 64);
        BlockPos heart = Kit.surface(level, 10800, 10800);
        VillageFolkEntity carrier = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(carrier != null, "a village to carry for");
        carrier.setJob(StationTask.HAUL);
        java.util.UUID village = carrier.ownerId();
        // The storehouse's chest north of the square; a field's chest and a furnace out in the plots.
        BlockPos store = Kit.surface(level, 10808, 10778);
        BlockPos field = Kit.surface(level, 10840, 10804);
        BlockPos oven = Kit.surface(level, 10760, 10804);
        level.setBlockAndUpdate(store, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(field, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(oven, Blocks.FURNACE.defaultBlockState());
        for (BlockPos p : List.of(store, field, oven)) com.jrpetty.mcassistant.entity.ZoneChests.mark(level, p);
        Villages.builtAtForTests(village, "storage", store);
        net.minecraft.world.Container fieldBox = (net.minecraft.world.Container) level.getBlockEntity(field);
        fieldBox.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        fieldBox.setItem(1, new ItemStack(Items.WHEAT, 40));
        fieldBox.setItem(2, new ItemStack(Items.CARROT, 20));
        fieldBox.setItem(3, new ItemStack(Items.WHEAT_SEEDS, 10));
        net.minecraft.world.Container ovenBox = (net.minecraft.world.Container) level.getBlockEntity(oven);
        ovenBox.setItem(0, new ItemStack(Items.RAW_IRON, 8));
        ovenBox.setItem(2, new ItemStack(Items.IRON_INGOT, 16));
        BlockPos depot = Villages.depot(level, village);
        Kit.log("t28 stores " + Villages.storeChests(level, village) + "; the depot " + depot + ", the storehouse " + store);
        helper.assertTrue(store.equals(depot), "loads go to the storehouse, not the founding chest nearer the heart: " + depot);

        VillageFolkEntity[] farmer = new VillageFolkEntity[1];
        long[] stocked = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);   // carrying is day work
            Villages.noteAttempt(village, level.getGameTime());                 // and nobody builds meanwhile
            int ingots = holding(level, store, Items.IRON_INGOT), cobble = holding(level, store, Items.COBBLESTONE);
            if (t % 600 == 0) {
                Kit.log("t28 @" + t + " store: iron " + ingots + ", cobble " + cobble + ", wheat "
                    + holding(level, store, Items.WHEAT) + ", seeds " + holding(level, store, Items.WHEAT_SEEDS)
                    + "; field: cobble " + holding(level, field, Items.COBBLESTONE) + ", carrots " + holding(level, field, Items.CARROT)
                    + "; furnace out " + ovenBox.getItem(2).getCount() + " — " + carrier.debugLine()
                    + (farmer[0] == null ? "" : " | " + farmer[0].debugLine()));
            }
            if (stocked[0] < 0) {
                if (ingots >= 16 && cobble >= 64) {
                    stocked[0] = t;
                    Kit.log("t28 the storehouse is stocked at tick " + t + ": field carrots " + holding(level, field, Items.CARROT)
                        + ", field seeds " + holding(level, field, Items.WHEAT_SEEDS) + ", furnace ore " + ovenBox.getItem(0).getCount());
                    helper.assertTrue(holding(level, field, Items.CARROT) == 16, "the field keeps sixteen carrots to plant");
                    helper.assertTrue(holding(level, field, Items.WHEAT_SEEDS) == 10, "and all its seed");
                    helper.assertTrue(ovenBox.getItem(0).is(Items.RAW_IRON) && ovenBox.getItem(0).getCount() == 8,
                        "the furnace keeps its ore; only what it made is carried");
                    // Now a farmer with no seed, and seed in the storehouse.
                    ((net.minecraft.world.Container) level.getBlockEntity(store)).setItem(26, new ItemStack(Items.WHEAT_SEEDS, 32));
                    VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, 10800, 10830), 0.0F);
                    helper.assertTrue(f != null, "a farmer for the village");
                    f.removeMatching(st -> st.is(Items.WHEAT_SEEDS) || st.is(Items.CARROT) || st.is(Items.POTATO)
                        || st.is(Items.BEETROOT_SEEDS), 999);
                    f.setJob(StationTask.FARM);
                    farmer[0] = f;
                } else if (t >= 5000) {
                    helper.fail("the storehouse was not stocked in 5000 ticks: iron " + ingots + ", cobble " + cobble
                        + " — " + carrier.debugLine());
                }
                return;
            }
            int seeds = farmer[0].countCarried(st -> st.is(Items.WHEAT_SEEDS));
            if (seeds >= 8) {
                Kit.log("t28 the farmer drew " + seeds + " seed from the storehouse by tick " + t
                    + " (the storehouse has " + holding(level, store, Items.WHEAT_SEEDS) + " left)");
                helper.succeed();
            } else if (t - stocked[0] >= 2600) {
                helper.fail("a farmer with no seed did not draw any from the storehouse in 2600 ticks — " + farmer[0].debugLine());
            }
        });
    }

    // ============================================================ the town's life

    /**
     * The small things: a house and a storehouse raised on the plan's lots, and round them a
     * fire in the hearth, a number by the door with the family's names, a washing line, the
     * windows lit after dark, a street sign at the corner, stalls on the square with the
     * stores' goods on them, and a scarecrow in a field.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t29_town_life")
    public static void t29_town_life(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        Kit.hold(level, 11400, 11400, 48);
        Kit.prepare(level, 11400, 11400, 48);
        BlockPos heart = Kit.surface(level, 11400, 11400);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a village");
        java.util.UUID village = folk.ownerId();
        Villages.Village v = Villages.get(village);
        // A house whose back is to the north (so it has the washing line), and the storehouse.
        com.jrpetty.mcassistant.village.TownPlan.Lot home = null;
        for (com.jrpetty.mcassistant.village.TownPlan.Lot l : com.jrpetty.mcassistant.village.TownPlan.candidates("house")) {
            if (l.kind() == com.jrpetty.mcassistant.village.TownPlan.Kind.LOT && l.back() == com.jrpetty.mcassistant.village.TownPlan.NORTH
                    && l.distance() < 40) { home = l; break; }
        }
        helper.assertTrue(home != null, "a lot for a house");
        BlockPos houseAt = Kit.surface(level, heart.getX() + home.x(), heart.getZ() + home.z());
        Direction houseBack = Villages.direction(home.back());
        com.jrpetty.mcassistant.village.TownPlan.Lot civic = com.jrpetty.mcassistant.village.TownPlan.candidates("storage").get(0);
        BlockPos storeAt = Kit.surface(level, heart.getX() + civic.x(), heart.getZ() + civic.z());
        Direction storeBack = Villages.direction(civic.back());
        BuildGoal.stamp(level, "house", houseAt, houseBack, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        BuildGoal.stamp(level, "storage", storeAt, storeBack, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.BIRCH));
        com.jrpetty.mcassistant.village.Ledger.built(village, "house", houseAt, houseBack);
        com.jrpetty.mcassistant.village.Ledger.built(village, "storage", storeAt, storeBack);
        var all = com.jrpetty.mcassistant.village.Ledger.buildings(village);
        helper.assertTrue(all.size() == 2, "the ledger has both buildings: " + all);
        var house = all.get(0);
        // A field of farmland to stand a scarecrow by.
        BlockPos field = heart.offset(-30, 0, -30);
        field = Kit.surface(level, field.getX(), field.getZ());
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            level.setBlock(field.offset(dx, -1, dz), Blocks.FARMLAND.defaultBlockState(), 3);
        }
        com.jrpetty.mcassistant.entity.TownLife.dressNow(level, village, heart, all,
            List.of(Items.BREAD, Items.CARROT, Items.APPLE, Items.WHITE_WOOL, Items.IRON_INGOT, Items.EGG));
        boolean crow = com.jrpetty.mcassistant.entity.TownLife.scarecrow(level, field, 4);

        var fit = com.jrpetty.mcassistant.entity.TownLife.fittings(house);
        Kit.log("t29 the house: " + fit.windows().size() + " windows, door " + fit.door() + ", chimneys " + fit.chimneys()
            + ", address " + java.util.Arrays.toString(com.jrpetty.mcassistant.entity.TownLife.address(village, heart, house)));
        helper.assertTrue(!fit.chimneys().isEmpty() && level.getBlockState(fit.chimneys().get(0).above()).is(Blocks.CAMPFIRE),
            "a fire in the house's hearth, smoking from its chimney");
        // The sign by the door.
        String signText = null;
        for (BlockPos p : BlockPos.betweenClosed(fit.door().offset(-2, 0, -2), fit.door().offset(2, 2, 2))) {
            if (level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                StringBuilder t = new StringBuilder();
                for (int i = 0; i < 4; i++) t.append(sign.getFrontText().getMessage(i, false).getString()).append(" / ");
                signText = t.toString();
            }
        }
        Kit.log("t29 the sign by the door: " + signText);
        helper.assertTrue(signText != null && signText.startsWith("No. "), "a number by the house's door");
        // The washing line behind it.
        BlockPos line = houseAt.relative(houseBack, fit.half()[1] + 1).above(2);
        int banners = 0;
        for (BlockPos p : BlockPos.betweenClosed(line.relative(houseBack).offset(-4, 0, -4), line.relative(houseBack).offset(4, 0, 4))) {
            if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.WallBannerBlock) banners++;
        }
        Kit.log("t29 the washing line: " + level.getBlockState(line) + ", " + banners + " things on it");
        helper.assertTrue(level.getBlockState(line).getBlock() instanceof net.minecraft.world.level.block.FenceBlock && banners == 4,
            "a washing line behind the house with the wash on it");
        // The windows after dark.
        com.jrpetty.mcassistant.entity.TownLife.lightsNow(level, all, true);
        int lit = 0, lamps = 0;
        for (BlockPos w : fit.windows()) if (level.getBlockState(w).is(Blocks.YELLOW_STAINED_GLASS_PANE)) lit++;
        for (BlockPos in : fit.insides()) if (in != null && level.getBlockState(in).is(Blocks.LIGHT)) lamps++;
        com.jrpetty.mcassistant.entity.TownLife.lightsNow(level, all, false);
        int still = 0;
        for (BlockPos w : fit.windows()) if (level.getBlockState(w).is(Blocks.YELLOW_STAINED_GLASS_PANE)) still++;
        Kit.log("t29 lights: " + lit + " of " + fit.windows().size() + " windows lit, " + lamps + " lamps; after dawn " + still);
        helper.assertTrue(lit >= fit.windows().size() / 2 && lamps > 0 && still == 0, "the windows light up after dark and go out at dawn");
        // A street sign at the corner of the South Road and the street round the square.
        BlockPos post = Kit.surface(level, heart.getX() + 3, heart.getZ() + 17).below(2);   // under the lantern
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockEntity(post.relative(d)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                names.add(sign.getFrontText().getMessage(1, false).getString());
            }
        }
        Kit.log("t29 the corner of " + names + " (post " + level.getBlockState(post.below()) + ")");
        helper.assertTrue(names.contains("South Road") && names.size() == 2, "a street sign with both streets' names");
        // The stalls, and the goods on them.
        int stalls = 0, goods = 0;
        for (int[] s : new int[][]{ { 8, 8 }, { -8, 8 }, { 8, -8 }, { -8, -8 } }) {
            if (level.getBlockState(Kit.surface(level, heart.getX() + s[0], heart.getZ() + s[1]).below(4)).is(Blocks.BARREL)
                    || !level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class,
                        around(heart.offset(s[0], 0, s[1]), 3)).isEmpty()) stalls++;
        }
        for (var f : level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, around(heart, 14))) {
            if (f.getTags().contains("mca_stall") && !f.getItem().isEmpty()) goods++;
        }
        Kit.log("t29 the square: " + stalls + " stalls, " + goods + " goods out on them");
        helper.assertTrue(stalls == 4 && goods == 6, "four stalls on the square with the stores' goods on them");
        Kit.log("t29 the field's scarecrow: " + crow);
        helper.assertTrue(crow, "a scarecrow by the field");
        // And the village's own visits light the windows when night falls.
        level.setDayTime(18000);
        com.jrpetty.mcassistant.entity.TownLife.resetForTests();
        com.jrpetty.mcassistant.entity.TownLife.tick(level, v);
        int night = 0;
        for (BlockPos w : fit.windows()) if (level.getBlockState(w).is(Blocks.YELLOW_STAINED_GLASS_PANE)) night++;
        Kit.log("t29 at midnight " + night + " windows are lit");
        helper.assertTrue(night > 0, "the village lights its own windows at night");
        level.setDayTime(6000);
        helper.succeed();
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
