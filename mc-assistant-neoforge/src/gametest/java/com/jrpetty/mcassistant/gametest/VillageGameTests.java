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
    @GameTest(template = EMPTY, timeoutTicks = 5000, batch = "t11_hillside")
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
            } else if (t >= 4800) {
                helper.fail("the storehouse was not built on the hillside in 4800 ticks: " + builder.debugLine());
            }
        });
    }

    /** Trees where the first lots are: the lot is taken anyway, and the builder fells
     *  what is in the way and keeps the wood. */
    @GameTest(template = EMPTY, timeoutTicks = 5000, batch = "t12_woodland")
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
            } else if (t >= 4800) {
                helper.fail("the storehouse was not built among the trees in 4800 ticks: " + builder.debugLine());
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
        for (int i = 0; i < 9; i++) {
            String next = Villages.nextProject(late);
            order.add(next);
            if (next == null) break;
            Villages.noteProject(late, next, now);
        }
        Kit.log("t15 past the last age: " + order + ", renown " + Villages.renown(late)
            + ", room " + roomBefore + " -> " + Villages.housing(late));
        // Twenty folk: the café, the tavern, the smithy and the shop after the gateway, before the great works.
        helper.assertTrue(order.equals(List.of("gateway", "cafe", "tavern", "smithy", "shop", "granary", "barracks", "monument", "granary")),
            "the Nether Age raises its gateway, its amenities, then the great works go round: " + order);
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
            // A stall's awning is the top of its column.
            if (level.getBlockState(Kit.surface(level, heart.getX() + s[0], heart.getZ() + s[1]).below())
                    .is(net.minecraft.tags.BlockTags.WOOL)) stalls++;
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

    /**
     * Money: a new village's purse, coin minted from the stores' gold in the Iron Age, a
     * day's wages, prices that move with the stores, a folk's market-day treat, and a player
     * buying a lot of bread at a stall and selling the village some iron.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "t30_market")
    public static void t30_market(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(7000);
        Kit.hold(level, 12000, 12000, 32);
        Kit.prepare(level, 12000, 12000, 32);
        BlockPos heart = Kit.surface(level, 12000, 12000);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a village");
        folk.setJob(StationTask.FARM);
        java.util.UUID village = folk.ownerId();
        Villages.Village v = Villages.get(village);
        BlockPos chest = Kit.surface(level, heart.getX() + 4, heart.getZ());
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.BREAD, 64));
        box.setItem(1, new ItemStack(Items.APPLE, 16));
        box.setItem(2, new ItemStack(Items.COOKIE, 8));
        box.setItem(3, new ItemStack(Items.GOLD_INGOT, 4));
        Villages.ageForTests(village, Villages.Age.IRON);

        // The founders' purse.
        com.jrpetty.mcassistant.entity.Market.tick(level, v);
        int opened = com.jrpetty.mcassistant.village.Ledger.coins(village);
        // Gold into coin, when the treasury is low.
        com.jrpetty.mcassistant.village.Ledger.takeCoins(village, opened);
        int minted = com.jrpetty.mcassistant.entity.Market.mint(level, v);
        // A day's wages.
        int paid = com.jrpetty.mcassistant.entity.Market.payWages(level, v);
        Kit.log("t30 the treasury opened with " + opened + "; minted " + minted + " from gold; paid " + paid
            + " in wages; " + folk.displayNameCap() + " has " + folk.purse() + "; treasury " + com.jrpetty.mcassistant.village.Ledger.coins(village));
        helper.assertTrue(opened == com.jrpetty.mcassistant.entity.Market.FOUNDING_PURSE, "a new village has the founders' purse");
        helper.assertTrue(minted == 18 && ((ItemStack) box.getItem(3)).getCount() == 2, "two bars of gold minted into eighteen coins");
        helper.assertTrue(paid == 1 && folk.purse() == 1, "a working folk is paid its wage");
        // Prices move with the stores.
        var bread = com.jrpetty.mcassistant.entity.Market.goodFor(new ItemStack(Items.BREAD));
        int plenty = com.jrpetty.mcassistant.entity.Market.sellPrice(bread, 64, false);
        int scarce = com.jrpetty.mcassistant.entity.Market.sellPrice(bread, 4, false);
        int buys = com.jrpetty.mcassistant.entity.Market.buyPrice(bread, 64, false);
        Kit.log("t30 eight bread: " + plenty + "c with plenty, " + scarce + "c when scarce; the village pays " + buys + "c");
        helper.assertTrue(plenty < scarce && buys < plenty, "dear when scarce, cheap when plenty, and it buys for less than it sells");
        int days = 0;
        for (long d = 0; d < 7; d++) if (com.jrpetty.mcassistant.entity.Market.marketDay(village, d)) days++;
        helper.assertTrue(days == 1, "one market day a week");
        // A folk's treat.
        folk.earn(10);
        int before = folk.purse(), treasury = com.jrpetty.mcassistant.village.Ledger.coins(village);
        String treat = com.jrpetty.mcassistant.entity.Market.folkBuys(level, v, folk);
        Kit.log("t30 " + folk.displayNameCap() + " (likes " + folk.persona().food() + ") bought " + treat + "; purse "
            + before + " -> " + folk.purse() + ", treasury " + treasury + " -> " + com.jrpetty.mcassistant.village.Ledger.coins(village));
        helper.assertTrue(treat != null && folk.purse() < before
            && com.jrpetty.mcassistant.village.Ledger.coins(village) == treasury + before - folk.purse(),
            "a folk spends its savings on a treat, and the coin goes back to the treasury");
        // A player at a stall.
        net.minecraft.world.entity.player.Player p = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        p.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 20));   // in the pack, not the hand
        treasury = com.jrpetty.mcassistant.village.Ledger.coins(village);
        String bought = com.jrpetty.mcassistant.entity.Market.deal(level, v, p, new ItemStack(Items.BREAD));
        int coins = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
        int breadHeld = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(Items.BREAD)) breadHeld += p.getInventory().getItem(i).getCount();
        }
        Kit.log("t30 the player: " + bought + " (" + coins + " coins left, " + breadHeld + " bread)");
        helper.assertTrue(breadHeld == 8 && coins < 20 && com.jrpetty.mcassistant.village.Ledger.coins(village) == treasury + 20 - coins,
            "a player buys a lot of bread with coin, and the coin goes into the treasury");
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT, 8));
        String sold = com.jrpetty.mcassistant.entity.Market.deal(level, v, p, ItemStack.EMPTY);
        int after = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
        Kit.log("t30 the player: " + sold + " (" + after + " coins now, " + p.getMainHandItem().getCount() + " iron in hand)");
        helper.assertTrue(after > coins && p.getMainHandItem().getCount() == 4, "the village buys iron from a player for coin");
        // The stalls' price signs.
        com.jrpetty.mcassistant.entity.TownLife.dressNow(level, village, heart, java.util.List.of(),
            java.util.List.of(Items.BREAD, Items.APPLE, Items.COOKIE));
        java.util.List<String> signs = new java.util.ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(heart.offset(3, 0, 3), heart.offset(12, 4, 12))) {
            if (level.getBlockEntity(q) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                signs.add(sign.getFrontText().getMessage(0, false).getString() + " / " + sign.getFrontText().getMessage(1, false).getString());
            }
        }
        Kit.log("t30 the stall's signs: " + signs);
        helper.assertTrue(signs.stream().anyMatch(t -> t.startsWith("8 Bread ")) && signs.stream().anyMatch(t -> t.startsWith("We buy:")),
            "a stall shows its prices, and what the village is buying");
        helper.succeed();
    }

    /**
     * The crafts, from one village's stores: a blacksmith beats iron into a pick or a blade, a
     * tailor makes a bed, a beekeeper sets up a hive and takes its honey, a brewer brews
     * healing, an enchanter binds books and enchants the smith's work, a cook makes apple
     * cider. Then the café's counter shows the cider, a folk has one on its break, and a
     * player buys one and the enchanted thing from the shop's counter.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "t32_crafts")
    public static void t32_crafts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(7000);
        Kit.hold(level, 13600, 12000, 32);
        Kit.prepare(level, 13600, 12000, 32);
        BlockPos heart = Kit.surface(level, 13600, 12000);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a village");
        java.util.UUID village = folk.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.NETHER);
        BlockPos chest = Kit.surface(level, heart.getX() + 4, heart.getZ());
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.IRON_INGOT, 40));
        box.setItem(1, new ItemStack(Items.OAK_PLANKS, 32));
        box.setItem(2, new ItemStack(Items.WHITE_WOOL, 16));
        box.setItem(3, new ItemStack(Items.GLASS_BOTTLE, 6));
        box.setItem(4, new ItemStack(Items.GLASS, 6));
        box.setItem(5, new ItemStack(Items.MELON_SLICE, 4));
        box.setItem(6, new ItemStack(Items.GOLD_NUGGET, 4));
        box.setItem(7, new ItemStack(Items.SUGAR_CANE, 6));
        box.setItem(8, new ItemStack(Items.LEATHER, 2));
        box.setItem(9, new ItemStack(Items.LAPIS_LAZULI, 9));
        box.setItem(10, new ItemStack(Items.APPLE, 8));
        box.setItem(11, new ItemStack(Items.POTATO, 20));
        java.util.function.ToIntFunction<java.util.function.Predicate<ItemStack>> stock =
            what -> com.jrpetty.mcassistant.entity.Market.stock(level, village, what);

        // The blacksmith.
        folk.setJob(StationTask.SMITH);
        int iron = stock.applyAsInt(s -> s.is(Items.IRON_INGOT));
        boolean smithed = com.jrpetty.mcassistant.entity.Crafts.now(folk, level, v);
        int ironTools = stock.applyAsInt(s -> s.isDamageableItem()
            && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().startsWith("iron_"));
        Kit.log("t32 the blacksmith: " + smithed + ", iron " + iron + " -> " + stock.applyAsInt(s -> s.is(Items.IRON_INGOT))
            + ", iron things in the stores " + ironTools);
        helper.assertTrue(smithed && ironTools >= 1 && stock.applyAsInt(s -> s.is(Items.IRON_INGOT)) < iron,
            "the blacksmith makes iron tools or armour out of the stores' iron");
        // The tailor.
        folk.setJob(StationTask.TAILOR);
        java.util.function.Predicate<ItemStack> cloth = s -> s.is(ItemTags.BEDS) || s.is(ItemTags.WOOL_CARPETS) || s.is(ItemTags.BANNERS);
        int clothBefore = stock.applyAsInt(cloth);
        boolean tailored = com.jrpetty.mcassistant.entity.Crafts.now(folk, level, v);
        Kit.log("t32 the tailor: " + tailored + ", beds/rugs/banners " + clothBefore + " -> " + stock.applyAsInt(cloth));
        helper.assertTrue(tailored && stock.applyAsInt(cloth) > clothBefore, "the tailor makes a bed (or rugs, or a banner) from wool");
        // The beekeeper: the hive it brought set down, then the honey once it is full.
        folk.setJob(StationTask.BEEKEEP);
        boolean hiveKit = com.jrpetty.mcassistant.entity.Trades.kit(folk);
        boolean hived = hiveKit && com.jrpetty.mcassistant.entity.Crafts.now(folk, level, v);
        BlockPos hive = null;
        BlockPos c = folk.workZone().center();
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-6, -3, -6), c.offset(6, 4, 6))) {
            if (level.getBlockState(q).is(Blocks.BEEHIVE)) { hive = q.immutable(); break; }
        }
        helper.assertTrue(hived && hive != null, "the beekeeper sets up a hive");
        level.setBlock(hive, level.getBlockState(hive).setValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL, 5), 3);
        boolean harvested = com.jrpetty.mcassistant.entity.Crafts.now(folk, level, v);
        int honey = stock.applyAsInt(s -> s.is(Items.HONEY_BOTTLE) || s.is(Items.HONEYCOMB));
        Kit.log("t32 the beekeeper: hive at " + hive.toShortString() + ", harvested " + harvested + ", honey " + honey
            + ", bees " + level.getEntitiesOfClass(net.minecraft.world.entity.animal.Bee.class, new AABB(hive).inflate(8)).size());
        helper.assertTrue(harvested && honey >= 1, "the beekeeper takes the honey from a full hive");
        // The brewer: the stand it brought set down and loaded with water and nether wart (the
        // brew itself, twenty seconds a step, is t40's).
        folk.setJob(StationTask.BREW);
        boolean brewKit = com.jrpetty.mcassistant.entity.Trades.kit(folk);
        // Without its brewery the brewer keeps the stand in its pack; with one, it goes down inside.
        boolean waited = !com.jrpetty.mcassistant.entity.Crafts.now(folk, level, v)
            || folk.countCarried(s -> s.is(Items.BREWING_STAND)) == 1;
        BlockPos bc = Kit.surface(level, heart.getX() - 14, heart.getZ() + 14);
        com.jrpetty.mcassistant.village.Ledger.built(village, "brewery", bc, Direction.NORTH);
        boolean brewed = com.jrpetty.mcassistant.entity.Crafts.now(folk, level, v);
        BlockPos standAt = null;
        for (BlockPos q : BlockPos.betweenClosed(bc.offset(-8, -3, -8), bc.offset(8, 4, 8))) {
            if (level.getBlockState(q).is(Blocks.BREWING_STAND)) { standAt = q.immutable(); break; }
        }
        String loadedWith = standAt != null && level.getBlockEntity(standAt) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity st
            ? st.getItem(0).getHoverName().getString() + " + " + st.getItem(3).getHoverName().getString() : "no stand";
        Kit.log("t32 the brewer: kit " + brewKit + ", kept it till the brewery stood " + waited + ", " + brewed
            + ", the stand at " + standAt + " with " + loadedWith);
        helper.assertTrue(brewKit && waited && brewed && loadedWith.contains("Water") && loadedWith.contains("Nether Wart"),
            "the brewer keeps the stand it brought till the brewery stands, then sets it down there and loads it");
        // The enchanter: the table it brought set down, a book bound, then the smith's work enchanted.
        folk.setJob(StationTask.ENCHANT);
        com.jrpetty.mcassistant.entity.Trades.kit(folk);
        com.jrpetty.mcassistant.village.Ledger.built(village, "library", Kit.surface(level, heart.getX() + 14, heart.getZ() + 14), Direction.NORTH);
        int spells = 0;
        for (int i = 0; i < 3; i++) if (com.jrpetty.mcassistant.entity.Crafts.now(folk, level, v)) spells++;
        int enchanted = stock.applyAsInt(ItemStack::isEnchanted);
        Kit.log("t32 the enchanter: " + spells + " pieces of work, enchanted things " + enchanted
            + ", lapis left " + stock.applyAsInt(s -> s.is(Items.LAPIS_LAZULI)));
        helper.assertTrue(spells >= 2 && enchanted >= 1, "the enchanter binds books and enchants the smith's work with lapis");
        // The cook.
        folk.setJob(StationTask.COOK);
        boolean cooked = com.jrpetty.mcassistant.entity.Crafts.now(folk, level, v);
        int drinks = stock.applyAsInt(com.jrpetty.mcassistant.entity.Cafe::isDrink);
        Kit.log("t32 the cook: " + cooked + ", drinks " + drinks);
        helper.assertTrue(cooked && drinks == 3, "the cook makes three apple ciders");

        // The café, its counter set out.
        BlockPos at = Kit.surface(level, heart.getX(), heart.getZ() - 16);
        BuildGoal.stamp(level, "cafe", at, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        com.jrpetty.mcassistant.village.Ledger.built(village, "cafe", at, Direction.NORTH);
        BlockPos shopAt = Kit.surface(level, heart.getX() + 16, heart.getZ() - 16);
        BuildGoal.stamp(level, "shop", shopAt, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        com.jrpetty.mcassistant.village.Ledger.built(village, "shop", shopAt, Direction.NORTH);
        com.jrpetty.mcassistant.entity.TownLife.dressNow(level, village, heart,
            com.jrpetty.mcassistant.village.Ledger.buildings(village), java.util.List.of());
        java.util.function.Function<BlockPos, java.util.List<ItemStack>> counters = where -> {
            java.util.List<ItemStack> out = new java.util.ArrayList<>();
            for (net.minecraft.world.entity.decoration.ItemFrame f : level.getEntitiesOfClass(
                    net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(where).inflate(6),
                    f -> f.getTags().contains("mca_stall"))) {
                if (!f.getItem().isEmpty()) out.add(f.getItem().copy());
            }
            return out;
        };
        // Frames hung on ground loaded this tick are found from a later tick on (and the village
        // may set the counter out afresh meanwhile): look until the drink is there, or long enough.
        final long from = helper.getTick();
        final boolean[] looked = { false };
        helper.onEachTick(() -> {
            if (looked[0]) return;
            java.util.List<ItemStack> menu = counters.apply(at);
            if (!menu.stream().anyMatch(com.jrpetty.mcassistant.entity.Cafe::isDrink) && helper.getTick() - from < 200) return;
            looked[0] = true;
            java.util.List<String> tags = new java.util.ArrayList<>();
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-4, 0, -4), at.offset(4, 2, 4))) {
                if (level.getBlockEntity(q) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                    tags.add(sign.getFrontText().getMessage(0, false).getString() + " / " + sign.getFrontText().getMessage(2, false).getString());
                }
            }
            Kit.log("t32 the café's counter: " + menu.stream().map(s -> s.getHoverName().getString()).toList() + "; tags " + tags);
            helper.assertTrue(menu.stream().anyMatch(com.jrpetty.mcassistant.entity.Cafe::isDrink),
                "the café's counter has the cider on it");
            helper.assertTrue(tags.stream().anyMatch(t -> t.startsWith("Apple Cider")), "with a price tag in front");
            // A folk on its break.
            folk.earn(10);
            int purse = folk.purse();
            String had = com.jrpetty.mcassistant.entity.Cafe.folkBuys(level, v, folk);
            Kit.log("t32 at the café " + folk.displayNameCap() + " had " + had + "; purse " + purse + " -> " + folk.purse());
            helper.assertTrue(had != null && folk.purse() < purse, "a folk buys a drink or a bite at the café out of its wages");
            // A player at the café's counter, and at the shop's.
            net.minecraft.world.entity.player.Player p = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            p.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
            ItemStack cider = menu.stream().filter(com.jrpetty.mcassistant.entity.Cafe::isDrink).findFirst().orElse(ItemStack.EMPTY);
            String bought = com.jrpetty.mcassistant.entity.Market.buy(level, v, p, cider);
            folk.setJob(StationTask.SHOP);
            String shopped = com.jrpetty.mcassistant.entity.Cafe.keepShop(level, v);
            java.util.List<ItemStack> wares = counters.apply(shopAt);
            ItemStack best = wares.stream().filter(ItemStack::isEnchanted).findFirst().orElse(ItemStack.EMPTY);
            String boughtToo = com.jrpetty.mcassistant.entity.Market.buy(level, v, p, best);
            int drinksHeld = 0, enchantedHeld = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (com.jrpetty.mcassistant.entity.Cafe.isDrink(s)) drinksHeld += s.getCount();
                if (s.isEnchanted()) enchantedHeld++;
            }
            Kit.log("t32 the player: " + bought + " / the shopkeeper: " + shopped + ", wares "
                + wares.stream().map(s -> s.getHoverName().getString()).toList() + " / " + boughtToo
                + " (" + com.jrpetty.mcassistant.entity.Market.coinsHeld(p) + " coins left)");
            helper.assertTrue(drinksHeld == 1, "a player buys a cider at the café");
            helper.assertTrue(enchantedHeld == 1, "and an enchanted thing from the shop");
            helper.succeed();
        });
    }

    /**
     * A raid on a walled village. The wall is up; the village hangs its four gates and puts up
     * the alarm bell and the ladders to the watch's posts. After dark a raiding party comes at
     * a gate: the bell rings, the gates are shut, the guard climbs to its post on the wall and
     * holds it there with its bow, and everybody else stops work for shelter. When the band is
     * gone the bell stops, the night goes into the village's history, and in the morning the
     * gates are opened again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "t33_raid")
    public static void t33_raid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(14000);
        Kit.hold(level, 14400, 12000, 56);
        Kit.prepare(level, 14400, 12000, 56);
        BlockPos heart = Kit.surface(level, 14400, 12000);
        VillageFolkEntity guard = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity farmer = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(guard != null && farmer != null, "a village of two");
        java.util.UUID village = guard.ownerId();
        Villages.Village v = Villages.get(village);
        helper.assertTrue(village.equals(farmer.ownerId()), "both of the one village");
        Villages.ageForTests(village, Villages.Age.STONE);
        guard.setJob(StationTask.GUARD);
        farmer.setJob(StationTask.FARM);
        guard.insertItem(new ItemStack(Items.BOW));
        guard.insertItem(new ItemStack(Items.ARROW, 48));
        // The wall, as a village's builder leaves it.
        BuildGoal.stamp(level, "fortify", heart, Direction.NORTH, com.jrpetty.mcassistant.village.TownPlan.PLAZA,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Villages.noteProject(village, "fortify", level.getGameTime());
        Villages.builtAtForTests(village, "fortify", heart);
        int gates = com.jrpetty.mcassistant.entity.Watch.keep(level, v, true);
        BlockPos bell = com.jrpetty.mcassistant.entity.Watch.bell(level, v, true);
        java.util.List<com.jrpetty.mcassistant.entity.Watch.Post> posts = com.jrpetty.mcassistant.entity.Watch.posts(level, village);
        int ladders = 0;
        for (var p : posts) if (level.getBlockState(p.foot()).is(Blocks.LADDER)) ladders++;
        Kit.log("t33 the wall: " + gates + " gates hung, bell at " + bell + ", " + posts.size() + " posts, " + ladders + " ladders");
        helper.assertTrue(gates == 4, "a gate hung in each of the wall's four gaps, got " + gates);
        helper.assertTrue(bell != null && level.getBlockState(bell).is(Blocks.BELL), "the alarm bell on the square");
        helper.assertTrue(posts.size() >= 4 && ladders == posts.size(), "the watch's posts on the wall, a ladder to each");
        // A raiding party.
        var alarm = com.jrpetty.mcassistant.entity.Raids.raidNow(level, v);
        int band = com.jrpetty.mcassistant.entity.Raids.bandSize(village);
        int shutDoors = 0, allDoors = 0;
        for (var g : com.jrpetty.mcassistant.entity.Watch.gates(level, village)) {
            for (BlockPos d : g.doors()) {
                allDoors++;
                if (!level.getBlockState(d).getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) shutDoors++;
            }
        }
        Kit.log("t33 the raid: " + band + " raiders (" + com.jrpetty.mcassistant.entity.Raids.why(village) + "), "
            + shutDoors + " of " + allDoors + " doors shut; the farmer on shift " + farmer.onShift() + ", the guard " + guard.onShift());
        helper.assertTrue(alarm != null && band >= 3, "a raiding party of three or more");
        helper.assertTrue(com.jrpetty.mcassistant.entity.Raids.underAlarm(village), "the bell is ringing");
        helper.assertTrue(allDoors == 12 && shutDoors == allDoors, "every gate shut");
        helper.assertTrue(!farmer.onShift() && guard.onShift(), "the guard turns out, the farmer stops work");
        final int doorsTotal = allDoors;
        final long[] upAt = { -1 };
        final boolean[] cleared = { false };
        final long[] endedAt = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 5 == 0) com.jrpetty.mcassistant.entity.Raids.guardDuty(guard);
            if (t % 100 == 0 && upAt[0] < 0) {
                int alive = 0;
                for (java.util.UUID u : com.jrpetty.mcassistant.entity.Raids.band(village)) {
                    if (level.getEntity(u) instanceof net.minecraft.world.entity.LivingEntity m && m.isAlive()) alive++;
                }
                Kit.log("t33 at tick " + t + " alarm " + com.jrpetty.mcassistant.entity.Raids.underAlarm(village)
                    + " (" + com.jrpetty.mcassistant.entity.Raids.why(village) + "), band alive " + alive
                    + ", posts " + com.jrpetty.mcassistant.entity.Watch.posts(level, village).size()
                    + ", heading for a post " + com.jrpetty.mcassistant.entity.Raids.headingForPost(guard)
                    + ", difficulty " + level.getDifficulty() + "; the guard: " + guard.debugLine() + " post " + guard.post()
                    + ", target " + (guard.getTarget() == null ? "none" : guard.getTarget().getType().toShortString()));
            }
            if (upAt[0] < 0 && guard.holdingAPost()) {
                upAt[0] = t;
                Kit.log("t33 the guard is on the wall at tick " + t + " (" + guard.blockPosition().toShortString()
                    + ", post " + guard.post().toShortString() + ")");
            }
            if (upAt[0] >= 0 && !cleared[0] && t >= upAt[0] + 40) {
                // The band is beaten off.
                for (java.util.UUID u : com.jrpetty.mcassistant.entity.Raids.band(village)) {
                    if (level.getEntity(u) instanceof net.minecraft.world.entity.LivingEntity m && m.isAlive()) m.kill();
                }
                cleared[0] = true;
            }
            if (cleared[0] && endedAt[0] < 0) {
                if (t % 20 == 0) com.jrpetty.mcassistant.entity.Raids.tick(level, v);
                if (!com.jrpetty.mcassistant.entity.Raids.underAlarm(village)) {
                    endedAt[0] = t;
                    java.util.List<String> lines = new java.util.ArrayList<>();
                    for (var e : com.jrpetty.mcassistant.village.Chronicle.of(village)) lines.add(e.text());
                    String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
                    Kit.log("t33 the bell stopped at tick " + t + "; the chronicle: " + last + "; the guard came down to "
                        + guard.blockPosition().toShortString() + "; the farmer on shift " + farmer.onShift());
                    helper.assertTrue(lines.stream().anyMatch(x -> x.contains("raiders came at the")), "the raid goes into the village's history");
                    helper.assertTrue(guard.post() == null, "the guard comes down off the wall");
                    // Morning: the gates are opened again.
                    level.setDayTime(24000L + 1000L);
                    com.jrpetty.mcassistant.entity.Raids.tick(level, v);
                    int open = 0;
                    for (var g : com.jrpetty.mcassistant.entity.Watch.gates(level, village)) {
                        for (BlockPos d : g.doors()) {
                            if (level.getBlockState(d).getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) open++;
                        }
                    }
                    Kit.log("t33 in the morning " + open + " of " + doorsTotal + " doors open");
                    helper.assertTrue(open == doorsTotal, "the gates opened in the morning");
                    helper.succeed();
                }
            }
            if (t == 1500 && upAt[0] < 0) {
                helper.fail("the guard never got onto the wall: " + guard.debugLine());
            }
        });
    }

    /**
     * How a village is doing: its contentment rises when the larder is full; work goes slower
     * with bare hands than with a wooden pick, and slower with wood than iron; on the day of
     * rest nobody but the watch works and the morning service is held; and a widow is free
     * to love again, and grieves.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "t34_contentment")
    public static void t34_contentment(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, 15200, 12000, 32);
        Kit.prepare(level, 15200, 12000, 32);
        BlockPos heart = Kit.surface(level, 15200, 12000);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(a != null && b != null, "a village of two");
        java.util.UUID village = a.ownerId();
        a.setJob(StationTask.FARM);
        b.setJob(StationTask.MINE);
        // Contentment: an empty larder, then a full one.
        for (BlockPos store : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(store) instanceof net.minecraft.world.Container c) c.clearContent();
        }
        Villages.forgetStock();
        com.jrpetty.mcassistant.entity.Contentment.resetForTests();
        var hungry = com.jrpetty.mcassistant.entity.Contentment.of(level, village);
        BlockPos chest = Kit.surface(level, heart.getX() + 4, heart.getZ());
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chest);
        for (int i = 0; i < 5; i++) box.setItem(i, new ItemStack(Items.BREAD, 64));
        Villages.forgetStock();
        com.jrpetty.mcassistant.entity.Contentment.resetForTests();
        var fed = com.jrpetty.mcassistant.entity.Contentment.of(level, village);
        Kit.log("t34 contentment: hungry " + hungry.score() + " " + hungry.bad() + ", fed " + fed.score() + " " + fed.good()
            + " — " + com.jrpetty.mcassistant.entity.Contentment.line(level, village));
        helper.assertTrue(fed.score() > hungry.score() && fed.food() > hungry.food(), "a full larder makes a happier village");
        // Tools: the right tool by its tier, bare hands slowest.
        net.minecraft.world.level.block.state.BlockState stone = Blocks.STONE.defaultBlockState();
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        int bare = a.workTicksFor(stone);
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
        int wood = a.workTicksFor(stone);
        a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        int iron = a.workTicksFor(stone);
        int ore = a.workTicksFor(Blocks.IRON_ORE.defaultBlockState());
        Kit.log("t34 a block of stone: " + bare + " ticks bare-handed, " + wood + " with a wooden pick, " + iron
            + " with an iron one; iron ore " + ore + " with the iron pick");
        helper.assertTrue(bare > wood && wood > iron, "bare hands slowest, then wood, then iron");
        helper.assertTrue(ore > iron, "ore is harder work than stone");
        // The day of rest.
        Villages.ageForTests(village, Villages.Age.STONE);
        long founded = Math.max(0L, com.jrpetty.mcassistant.village.Chronicle.foundedOn(village));
        long day = founded + 7;
        while (!com.jrpetty.mcassistant.entity.RestDay.today(village, day)) day++;
        level.setDayTime(day * 24000L + 2000L);
        boolean off = !a.onShift();
        boolean spent = com.jrpetty.mcassistant.entity.RestDay.spend(a);
        String now = com.jrpetty.mcassistant.entity.RestDay.now(village, level.getDayTime());
        Kit.log("t34 day " + day + " is the day of rest: off work " + off + ", " + now + ", " + a.displayNameCap() + " is " + a.hobbyNow());
        helper.assertTrue(off && spent && "the morning service".equals(now), "on the day of rest folk go to the morning service, not to work");
        // A widow.
        a.life().partnerWith(b.getUUID(), b.displayNameCap());
        b.life().partnerWith(a.getUUID(), a.displayNameCap());
        a.ensurePersona();
        b.kill();
        a.refreshMood();
        Kit.log("t34 after " + b.displayNameCap() + " died: partner " + a.life().partner() + ", mood " + a.persona().mood()
            + " " + a.persona().moodWhy());
        helper.assertTrue(a.life().partner() == null, "a widow is free to love again");
        helper.assertTrue(a.persona().moodWhy().contains("grief"), "and grieves");
        helper.succeed();
    }

    /**
     * A life in the village: a child learns a trade at a grown-up's side and grows up into it
     * with a few levels' knack; folk grow old and at the end of their years die in their sleep;
     * the dead get a headstone in the graveyard, a place in the register and in its family
     * trees; and a player buys a round at the tavern.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "t35_life")
    public static void t35_life(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        Kit.hold(level, 16000, 12000, 40);
        Kit.prepare(level, 16000, 12000, 40);
        BlockPos heart = Kit.surface(level, 16000, 12000);
        VillageFolkEntity mum = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity dad = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(mum != null && dad != null, "a village of two");
        java.util.UUID village = mum.ownerId();
        Villages.Village v = Villages.get(village);
        mum.setJob(StationTask.FARM);
        dad.setJob(StationTask.MINE);
        VillageFolkEntity child = mum.raiseChildWith(dad);
        helper.assertTrue(child != null && child.isBaby(), "a child");
        child.bornDaysAgo(1);
        final long[] apprenticed = { -1 }, grown = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (apprenticed[0] < 0 && child.apprenticedTo() != StationTask.NONE) {
                apprenticed[0] = t;
                Kit.log("t35 at tick " + t + " " + child.displayNameCap() + " is " + child.hobbyNow());
                child.bornDaysAgo(3);                 // and grows up at its next look round
            }
            if (apprenticed[0] >= 0 && grown[0] < 0 && !child.isBaby()) {
                grown[0] = t;
                Kit.log("t35 grown up at tick " + t + ": " + child.stationTask() + " at level " + child.veteranLevel()
                    + " (learned " + child.apprenticedTo() + ")");
                helper.assertTrue(child.stationTask() == child.apprenticedTo(), "grown up into the trade it learned");
                helper.assertTrue(child.veteranLevel() >= 3, "with a few levels' knack already");
                rest(helper, level, village, v, heart, mum, dad, child);
            }
            if (t == 850 && grown[0] < 0) helper.fail("no apprenticeship and growing up: " + child.debugLine());
        });
    }

    /**
     * Civic life: the council votes on what to build and a well-liked player sways it; a friend
     * of the village becomes a citizen; a thief seen at the stores is fined, then tried, then
     * banished, and pays off what it owes; two villages too close together fall out over the
     * land, and a player makes peace between them; and the village raises a statue to its hero.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t36_civics")
    public static void t36_civics(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, 17000, 12000, 40);
        Kit.prepare(level, 17000, 12000, 40);
        BlockPos heart = Kit.surface(level, 17000, 12000);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        VillageFolkEntity c = VillageFolkSpawnerBlock.raise(level, heart.west(2), 0.0F);
        helper.assertTrue(a != null && b != null && c != null, "a village of three");
        java.util.UUID village = a.ownerId();
        Villages.Village v = Villages.get(village);
        a.setJob(StationTask.FARM);
        b.setJob(StationTask.MINE);
        c.setJob(StationTask.WOOD);
        net.minecraft.world.entity.player.Player p = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        p.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 3.5);
        p.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 40));
        for (VillageFolkEntity f : List.of(a, b, c)) {
            f.ensurePersona();
            f.persona().feelFor(p.getUUID(), p.getName().getString(), 65);
            com.jrpetty.mcassistant.entity.Standing.stir(village, p.getUUID());
        }
        // The council, and a player's proposal.
        var council = com.jrpetty.mcassistant.entity.Council.members(village);
        String put = com.jrpetty.mcassistant.entity.Council.propose(a, p, "you should build a library");
        List<String> order = com.jrpetty.mcassistant.entity.Council.order(village, List.of("cafe", "tavern", "library"));
        String news = com.jrpetty.mcassistant.entity.Council.news(a);
        Kit.log("t36 the council " + council.size() + ": " + put + " -> " + order + " / " + news);
        helper.assertTrue(council.size() == 3, "the three of them sit on the council");
        helper.assertTrue(order.get(0).equals("library"), "the council, swayed by a friend, votes for the library first: " + order);
        helper.assertTrue(news.contains("voted"), "and the vote is news");
        // Citizenship.
        String citizen = com.jrpetty.mcassistant.entity.Citizens.ask(a, p);
        int left = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
        Kit.log("t36 citizenship: " + citizen + " (" + left + " coins left)");
        helper.assertTrue(com.jrpetty.mcassistant.entity.Citizens.is(village, p.getUUID()) && left == 40 - com.jrpetty.mcassistant.entity.Citizens.FEE,
            "a friend of the village becomes a citizen, for the fee");
        // A thief at the stores: seen, fined; again, tried; again, banished.
        BlockPos chest = Kit.surface(level, heart.getX() + 3, heart.getZ() + 1);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.BREAD, 20));
        helper.assertTrue(Villages.storeChests(level, village).contains(chest), "the chest is one of the village's stores");
        net.minecraft.world.entity.player.Player thief = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        thief.moveTo(heart.getX() + 2.5, heart.getY(), heart.getZ() + 2.5);
        com.jrpetty.mcassistant.entity.Laws.onOpen(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
            thief, InteractionHand.MAIN_HAND, chest, new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false)));
        box.setItem(0, new ItemStack(Items.BREAD, 14));
        thief.getInventory().add(new ItemStack(Items.BREAD, 6));            // into the thief's pack
        com.jrpetty.mcassistant.entity.Laws.onClose(new net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close(thief, thief.inventoryMenu));
        int owed1 = com.jrpetty.mcassistant.entity.Laws.owes(village, thief.getUUID());
        int seen = com.jrpetty.mcassistant.entity.Laws.offences(village, thief.getUUID());
        com.jrpetty.mcassistant.entity.Laws.offence(level, v, thief, "breaking the well", 5);
        int owed2 = com.jrpetty.mcassistant.entity.Laws.owes(village, thief.getUUID());
        long day = level.getDayTime() / 24000L;
        boolean banishedBefore = com.jrpetty.mcassistant.entity.Laws.banished(village, thief.getUUID(), day);
        com.jrpetty.mcassistant.entity.Laws.offence(level, v, thief, "breaking the well again", 5);
        boolean banished = com.jrpetty.mcassistant.entity.Laws.banished(village, thief.getUUID(), day);
        Kit.log("t36 the thief: offences " + seen + ", owed " + owed1 + " then " + owed2 + ", banished " + banishedBefore + " -> " + banished
            + "; outlaw " + com.jrpetty.mcassistant.entity.Laws.outlaw(village, thief) + "; a's opinion " + a.persona().affinity(thief.getUUID()));
        helper.assertTrue(seen == 1 && owed1 > 0, "seen taking from the stores, and fined");
        helper.assertTrue(owed2 > owed1, "tried for the second, and fined more");
        helper.assertTrue(!banishedBefore && banished && com.jrpetty.mcassistant.entity.Laws.outlaw(village, thief), "banished for the third");
        thief.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
        String paid = com.jrpetty.mcassistant.entity.Laws.pay(a, thief);
        Kit.log("t36 the thief pays: " + paid);
        helper.assertTrue(com.jrpetty.mcassistant.entity.Laws.owes(village, thief.getUUID()) == 0, "and pays off every coin it owes");
        // A neighbour too close for comfort.
        java.util.UUID otherId = java.util.UUID.randomUUID();
        Villages.restore(level, otherId, new BlockPos(17200, heart.getY(), 12000), Villages.Age.STONE, List.of("storage"), 10);
        Villages.Village other = Villages.get(otherId);
        helper.assertTrue(com.jrpetty.mcassistant.entity.Diplomacy.neighbours(v, other), "two villages, neighbours");
        for (int d = 0; d < 8; d++) com.jrpetty.mcassistant.entity.Diplomacy.daily(level, v, other, day + d);
        int sour = com.jrpetty.mcassistant.village.Ledger.relation(village, otherId);
        String rivals = com.jrpetty.mcassistant.entity.Diplomacy.rivals(a);
        Kit.log("t36 after eight days as neighbours: " + sour + " (" + com.jrpetty.mcassistant.entity.Diplomacy.terms(sour).words + "): " + rivals);
        helper.assertTrue(sour < 0, "too close: they fall out over the land");
        helper.assertTrue(rivals.contains(Villages.name(otherId)), "and say so");
        int coins = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
        String peace = com.jrpetty.mcassistant.entity.Diplomacy.peace(a, p, "make peace with " + Villages.name(otherId));
        int mended = com.jrpetty.mcassistant.village.Ledger.relation(village, otherId);
        Kit.log("t36 peace: " + peace + " (" + sour + " -> " + mended + ", coins " + coins + " -> " + com.jrpetty.mcassistant.entity.Market.coinsHeld(p) + ")");
        helper.assertTrue(mended == Math.min(100, sour + 25) && com.jrpetty.mcassistant.entity.Market.coinsHeld(p) == coins - com.jrpetty.mcassistant.entity.Diplomacy.PEACE_COST,
            "a player carries gifts between them and mends it");
        com.jrpetty.mcassistant.village.Ledger.relate(village, otherId, -200);
        com.jrpetty.mcassistant.entity.Diplomacy.daily(level, v, other, day + 9);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (var e : com.jrpetty.mcassistant.village.Chronicle.of(village)) lines.add(e.text());
        com.jrpetty.mcassistant.entity.Contentment.resetForTests();
        var mood = com.jrpetty.mcassistant.entity.Contentment.of(level, village);
        Kit.log("t36 a feud: " + lines.get(lines.size() - 1) + "; contentment " + mood.bad());
        helper.assertTrue(lines.stream().anyMatch(x -> x.contains("fell into a feud")), "the feud goes into the history");
        helper.assertTrue(mood.bad().contains("the feud"), "and weighs on the village");
        // A statue for the hero.
        boolean raised = com.jrpetty.mcassistant.entity.Citizens.statue(level, v, p);
        BlockPos spot = v.centre().offset(5, 0, -5);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ()) - 1;
        Kit.log("t36 the statue: " + raised + ", plinth " + level.getBlockState(new BlockPos(spot.getX(), y, spot.getZ())));
        helper.assertTrue(raised && com.jrpetty.mcassistant.village.Ledger.statue(village, p.getUUID()), "the village raises its hero a statue");
        helper.succeed();
    }

    /**
     * What a village does for a player: the quest board on the hall (take a posting, bring the
     * iron, get paid; clear the spiders, get paid), a folk hired for an adventure who comes home
     * with a story and what it carried, a house built to order from the player's own makings,
     * the town ledger, and the storekeeper who gives to friends, lends tools and sells to strangers.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t37_services")
    public static void t37_services(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, 18000, 12000, 40);
        Kit.prepare(level, 18000, 12000, 40);
        BlockPos heart = Kit.surface(level, 18000, 12000);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        VillageFolkEntity c = VillageFolkSpawnerBlock.raise(level, heart.west(2), 0.0F);
        helper.assertTrue(a != null && b != null && c != null, "a village of three");
        java.util.UUID village = a.ownerId();
        Villages.Village v = Villages.get(village);
        a.setJob(StationTask.STORE);
        b.setJob(StationTask.FARM);
        c.setJob(StationTask.MINE);
        Villages.noteProject(village, "storage", level.getGameTime());
        com.jrpetty.mcassistant.village.Ledger.addCoins(village, 100);
        BlockPos hallAt = Kit.surface(level, heart.getX(), heart.getZ() - 22);
        BuildGoal.stamp(level, "hall", hallAt, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        com.jrpetty.mcassistant.village.Ledger.built(village, "hall", hallAt, Direction.NORTH);
        BlockPos chest = Kit.surface(level, heart.getX() + 3, heart.getZ() + 1);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.BREAD, 32));
        box.setItem(1, new ItemStack(Items.IRON_PICKAXE));
        net.minecraft.world.entity.player.Player p = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        p.moveTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 1.5);
        p.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 30));
        for (VillageFolkEntity f : List.of(a, b, c)) {
            f.ensurePersona();
            f.persona().feelFor(p.getUUID(), p.getName().getString(), 40);
        }
        // What a player says, understood.
        helper.assertTrue(com.jrpetty.mcassistant.entity.FolkTalk.understand("could I have 16 bread please") == com.jrpetty.mcassistant.entity.TalkTopic.STORES
            && com.jrpetty.mcassistant.entity.FolkTalk.understand("what's on the quest board?") == com.jrpetty.mcassistant.entity.TalkTopic.QUESTS
            && com.jrpetty.mcassistant.entity.FolkTalk.understand("come adventuring with me") == com.jrpetty.mcassistant.entity.TalkTopic.HIRE
            && com.jrpetty.mcassistant.entity.FolkTalk.understand("could you build me a house") == com.jrpetty.mcassistant.entity.TalkTopic.COMMISSION
            && com.jrpetty.mcassistant.entity.FolkTalk.understand("could I see the ledger") == com.jrpetty.mcassistant.entity.TalkTopic.LEDGER,
            "the folk understand what a player asks for");
        // The quest board.
        long day = level.getDayTime() / 24000L;
        var board = com.jrpetty.mcassistant.entity.Quests.postings(village);
        board.clear();
        for (var q : com.jrpetty.mcassistant.entity.Quests.samples(day)) if (q.takenBy == null) board.add(q);
        var written = com.jrpetty.mcassistant.entity.Quests.paintOn(level, com.jrpetty.mcassistant.village.Ledger.buildings(village).stream()
            .filter(x -> x.structure().equals("hall")).findFirst().orElseThrow(), board);
        String firstSign = written.isEmpty() ? "" : level.getBlockEntity(written.get(0)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign
            ? sign.getFrontText().getMessage(0, false).getString() + " " + sign.getFrontText().getMessage(1, false).getString() : "";
        Kit.log("t37 the quest board: " + written.size() + " postings hung on the hall; the first reads " + firstSign);
        helper.assertTrue(written.size() >= 3 && firstSign.startsWith("WANTED"), "the postings hang on the hall's front");
        var iron = board.get(0);
        int coins0 = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
        String took = com.jrpetty.mcassistant.entity.Quests.use(level, v, p, iron);
        p.getInventory().setItem(21, new ItemStack(Items.IRON_INGOT, 40));
        String paid = com.jrpetty.mcassistant.entity.Quests.use(level, v, p, iron);
        int coins1 = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
        int ironStored = com.jrpetty.mcassistant.entity.Market.stock(level, village, x -> x.is(Items.IRON_INGOT));
        Kit.log("t37 " + took + " / " + paid + " (coins " + coins0 + " -> " + coins1 + ", iron in the stores " + ironStored + ")");
        helper.assertTrue(coins1 == coins0 + iron.reward && ironStored == 40 && !board.contains(iron), "the iron goes into the stores and the posting pays");
        var spiders = board.stream().filter(q -> q.kind.equals("clear")).findFirst().orElseThrow();
        com.jrpetty.mcassistant.entity.Quests.use(level, v, p, spiders);
        for (int i = 0; i < spiders.count; i++) {
            net.minecraft.world.entity.monster.Spider sp = EntityType.SPIDER.create(level);
            sp.moveTo(1.0, 0.0, 1.0);
            com.jrpetty.mcassistant.entity.Quests.killed(level, p, sp);
        }
        String cleared = com.jrpetty.mcassistant.entity.Quests.use(level, v, p, spiders);
        int coins2 = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
        Kit.log("t37 " + cleared + " (coins " + coins1 + " -> " + coins2 + ")");
        helper.assertTrue(coins2 == coins1 + spiders.reward, "the spiders cleared, and paid for");
        // Hiring a folk.
        p.moveTo(b.getX() + 1.0, b.getY(), b.getZ());
        int coins3 = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
        b.persona().setMood(60, List.of());
        String hired = com.jrpetty.mcassistant.entity.Hire.ask(b, p);
        boolean going = b.isHired() && p.getUUID().equals(b.hiredBy());
        b.insertItem(new ItemStack(Items.BONE, 3));
        for (int i = 0; i < 2; i++) {
            net.minecraft.world.entity.monster.Zombie z = EntityType.ZOMBIE.create(level);
            z.moveTo(b.getX() + 3, b.getY(), b.getZ());
            com.jrpetty.mcassistant.entity.Hire.onDeath(new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(z,
                level.damageSources().mobAttack(b)));
        }
        String home = com.jrpetty.mcassistant.entity.FolkTalk.answer(b, p, com.jrpetty.mcassistant.entity.TalkTopic.STAY, "");
        int bones = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(Items.BONE)) bones += p.getInventory().getItem(i).getCount();
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (var e : com.jrpetty.mcassistant.village.Chronicle.of(village)) lines.add(e.text());
        String story = lines.stream().filter(x -> x.contains("adventuring")).findFirst().orElse("");
        Kit.log("t37 hired: " + hired + " / " + home + " / " + story + " (bones handed over " + bones + ", coins "
            + coins3 + " -> " + com.jrpetty.mcassistant.entity.Market.coinsHeld(p) + ")");
        helper.assertTrue(going && com.jrpetty.mcassistant.entity.Market.coinsHeld(p) == coins3 - com.jrpetty.mcassistant.entity.Hire.PRICE,
            "hired for a day's coin, it goes along");
        helper.assertTrue(!b.isHired() && bones == 3 && story.contains("2 monsters"), "home again: what it carried handed over, and a story told");
        // A house to order.
        p.getInventory().setItem(22, new ItemStack(Items.OAK_PLANKS, 64));
        p.getInventory().setItem(23, new ItemStack(Items.COBBLESTONE, 32));
        p.getInventory().setItem(24, new ItemStack(Items.GLASS, 8));
        String house = com.jrpetty.mcassistant.entity.Services.commission(a, p);
        int planks = com.jrpetty.mcassistant.entity.Market.stock(level, village, x -> x.is(Items.OAK_PLANKS));
        Kit.log("t37 commission: " + house + " (planks in the stores " + planks + ", next projects " + Villages.projectsWanted(village) + ")");
        helper.assertTrue(com.jrpetty.mcassistant.village.Chronicle.awaitingAHouse(village) != null && planks >= 64
            && Villages.projectsWanted(village).contains("guesthouse"), "the makings go into the stores, and a house for the player goes on the list");
        // The town ledger.
        ItemStack ledger = com.jrpetty.mcassistant.entity.Services.ledger(level, village);
        StringBuilder text = new StringBuilder();
        for (var page : ledger.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT).pages()) text.append(page.raw().getString()).append(" | ");
        Kit.log("t37 the ledger: " + text);
        helper.assertTrue(text.indexOf("Town Ledger") >= 0 && text.indexOf("In the stores") >= 0 && text.indexOf("Who lives where") >= 0
            && text.indexOf(a.displayNameCap()) >= 0 && text.indexOf("Building") >= 0, "the ledger: stores, residents, building, needs");
        // The storekeeper.
        String bread = com.jrpetty.mcassistant.entity.Services.stores(a, p, "could I have 8 bread?");
        String wrong = com.jrpetty.mcassistant.entity.Services.stores(b, p, "could I have 8 bread?");
        String lent = com.jrpetty.mcassistant.entity.Services.stores(a, p, "could I borrow the iron pickaxe?");
        boolean hasPick = p.getInventory().contains(new ItemStack(Items.IRON_PICKAXE));
        String back = com.jrpetty.mcassistant.entity.Services.returnLoan(a, p);
        net.minecraft.world.entity.player.Player stranger = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        stranger.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 20));
        String sold = com.jrpetty.mcassistant.entity.Services.stores(a, stranger, "could I have 4 bread");
        int breadHeld = 0, strangerBread = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(Items.BREAD)) breadHeld += p.getInventory().getItem(i).getCount();
        for (int i = 0; i < stranger.getInventory().getContainerSize(); i++) if (stranger.getInventory().getItem(i).is(Items.BREAD)) strangerBread += stranger.getInventory().getItem(i).getCount();
        Kit.log("t37 the storekeeper: " + bread + " / " + wrong + " / " + lent + " / " + back + " / " + sold);
        helper.assertTrue(breadHeld == 8, "a friend is given bread from the stores");
        helper.assertTrue(hasPick && !p.getInventory().contains(new ItemStack(Items.IRON_PICKAXE)), "a tool lent, and brought back");
        helper.assertTrue(strangerBread == 4 && com.jrpetty.mcassistant.entity.Market.coinsHeld(stranger) < 20, "a stranger buys");
        helper.succeed();
    }

    /**
     * The land: settlers pick the flattest ground about them; the village levels the ground of
     * the town (a knoll cut down, a hollow filled); its houses grow up (a garden fence, walls of
     * brick, a second storey); a fisher's water gets a jetty and a boat; and a dry field gets an
     * irrigation channel.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t38_land")
    public static void t38_land(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, 19000, 12000, 56);
        Kit.prepare(level, 19000, 12000, 56);
        BlockPos heart = Kit.surface(level, 19000, 12000);
        int ground = heart.getY() - 1;
        // Rough ground to the south-west: pillars of earth every few blocks.
        BlockPos rough = heart.offset(-30, 0, 30);
        for (int dx = -12; dx <= 12; dx += 3) {
            for (int dz = -12; dz <= 12; dz += 3) {
                int h = Math.floorMod(dx * 7 + dz * 13, 5);
                for (int k = 0; k < h; k++) level.setBlock(rough.offset(dx, k, dz), Blocks.DIRT.defaultBlockState(), 3);
            }
        }
        int roughHere = com.jrpetty.mcassistant.entity.Land.roughness(level, rough);
        BlockPos flat = com.jrpetty.mcassistant.entity.Land.flattest(level, rough, 32);
        int roughThere = flat == null ? -1 : com.jrpetty.mcassistant.entity.Land.roughness(level, flat);
        Kit.log("t38 founding: roughness " + roughHere + " where it stood, the flattest ground about " + flat + " at " + roughThere);
        helper.assertTrue(flat != null && roughThere < roughHere && !flat.equals(rough), "settlers pick the flattest ground about them");
        // A village, a knoll and a hollow.
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(a != null && b != null, "a village of two");
        java.util.UUID village = a.ownerId();
        Villages.Village v = Villages.get(village);
        BlockPos knoll = heart.offset(16, 0, 16), hollow = heart.offset(-16, 0, -16);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int k = 0; k < 3; k++)
            level.setBlock(knoll.offset(dx, k, dz), Blocks.DIRT.defaultBlockState(), 3);
        for (int dx = -2; dx <= 1; dx++) for (int dz = -2; dz <= 1; dz++) for (int k = 1; k <= 2; k++)
            level.setBlock(hollow.offset(dx, -k, dz), Blocks.AIR.defaultBlockState(), 3);
        int moved = 0;
        for (int i = 0; i < 4; i++) moved += com.jrpetty.mcassistant.entity.Land.level(level, v, 8000, 2000);
        int knollTop = Kit.surface(level, knoll.getX(), knoll.getZ()).getY() - 1;
        int hollowTop = Kit.surface(level, hollow.getX(), hollow.getZ()).getY() - 1;
        Kit.log("t38 levelling: " + moved + " blocks moved; the knoll's top now " + knollTop + ", the hollow's " + hollowTop + ", the square " + ground);
        helper.assertTrue(knollTop == ground && hollowTop == ground, "the knoll cut down and the hollow filled to the square's level");
        // A house grows up.
        Villages.ageForTests(village, Villages.Age.IRON);
        BlockPos chest = Kit.surface(level, heart.getX() + 3, heart.getZ() + 1);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        ((net.minecraft.world.Container) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        BlockPos houseAt = Kit.surface(level, heart.getX(), heart.getZ() - 26);
        BuildGoal.stamp(level, "house", houseAt, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        com.jrpetty.mcassistant.village.Ledger.built(village, "house", houseAt, Direction.NORTH);
        int roomBefore = Villages.housing(village);
        for (int i = 0; i < 80 && !com.jrpetty.mcassistant.village.Ledger.grown(village, houseAt); i++) {
            com.jrpetty.mcassistant.entity.Grow.work(level, v, 3000);
        }
        int bricks = 0, upstairs = 0, beds = 0, fences = 0;
        for (BlockPos q : BlockPos.betweenClosed(houseAt.offset(-6, -1, -6), houseAt.offset(6, 12, 6))) {
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(q);
            if (st.is(Blocks.BRICKS)) bricks++;
            if (q.getY() == houseAt.getY() + 4 && !st.isAir()) upstairs++;
            if (st.getBlock() instanceof net.minecraft.world.level.block.BedBlock) beds++;
            if (st.is(Blocks.OAK_FENCE) || st.is(Blocks.OAK_FENCE_GATE)) fences++;
        }
        Kit.log("t38 the house: grown " + com.jrpetty.mcassistant.village.Ledger.grown(village, houseAt) + ", " + bricks + " bricks, "
            + upstairs + " blocks on the new floor, " + beds + " bed blocks, " + fences + " fence posts; room " + roomBefore
            + " -> " + Villages.housing(village));
        helper.assertTrue(com.jrpetty.mcassistant.village.Ledger.grown(village, houseAt) && upstairs >= 10 && beds >= 10,
            "the house gets its second storey, with more beds");
        helper.assertTrue(bricks >= 15 && fences >= 10, "brick walls and a garden fence");
        helper.assertTrue(Villages.housing(village) == roomBefore + 2, "more room in the village");
        // The waterfront.
        Kit.pond(level, heart.getX() - 26, heart.getZ(), 5);
        var dock = com.jrpetty.mcassistant.entity.Waterfront.site(level, new BlockPos(heart.getX() - 26, ground, heart.getZ()), 8);
        helper.assertTrue(dock != null, "somewhere to run a jetty out");
        int laid = com.jrpetty.mcassistant.entity.Waterfront.build(level, dock);
        boolean moored = com.jrpetty.mcassistant.entity.Waterfront.moor(level, dock);
        boolean again = com.jrpetty.mcassistant.entity.Waterfront.moor(level, dock);
        boolean lamp = level.getBlockState(dock.end().above(2)).is(Blocks.LANTERN);
        Kit.log("t38 the jetty: " + dock + ", " + laid + " blocks laid, a boat moored " + moored + " (and a second " + again + "), lantern " + lamp);
        helper.assertTrue(laid >= 3 && lamp && moored && !again, "a jetty with a lantern, and one boat tied up alongside");
        // A dry field, irrigated.
        BlockPos field = new BlockPos(heart.getX() + 26, ground, heart.getZ() - 26);
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            level.setBlock(field.offset(dx, 0, dz), Blocks.FARMLAND.defaultBlockState(), 3);
            level.setBlock(field.offset(dx, 1, dz), Blocks.WHEAT.defaultBlockState(), 3);
        }
        int dug = com.jrpetty.mcassistant.entity.Waterfront.irrigate(level, village, field, 4, 100);
        int more = com.jrpetty.mcassistant.entity.Waterfront.irrigate(level, village, field, 4, 100);
        Kit.log("t38 irrigation: " + dug + " blocks of channel, then " + more + "; water at the middle " + level.getBlockState(field).is(Blocks.WATER));
        helper.assertTrue(dug >= 5 && more == 0 && level.getBlockState(field).is(Blocks.WATER), "a channel of water through the field");
        helper.succeed();
    }

    /**
     * The elder's orders: the elder looks the village over and gives an order; a player it
     * thinks well of can put another to it; the order shifts the village's make-up (one miner
     * a day goes to the fields under "fill the larder"); it heads the quest board on the hall,
     * and every folk can say what it is.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t39_orders")
    public static void t39_orders(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.hold(level, 20000, 12000, 40);
        Kit.prepare(level, 20000, 12000, 40);
        BlockPos heart = Kit.surface(level, 20000, 12000);
        // Four miners: a hand to spare even once the order counts only the trades the village can have.
        StationTask[] trades = { StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.MINE,
            StationTask.MINE, StationTask.MINE, StationTask.MINE, StationTask.WOOD, StationTask.SMELT };
        List<VillageFolkEntity> folk = new java.util.ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.offset(i % 5 * 2, 0, i / 5 * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i);
            f.setJob(trades[i]);
            f.ensurePersona();
            folk.add(f);
        }
        java.util.UUID village = folk.get(0).ownerId();
        Villages.Village v = Villages.get(village);
        long founded = Math.max(0L, com.jrpetty.mcassistant.village.Chronicle.foundedOn(village));
        long day = founded + 3;
        level.setDayTime(day * 24000L + 1000L);
        Villages.chooseElder(village, day);
        com.jrpetty.mcassistant.entity.Orders.consider(level, village, day);
        var first = com.jrpetty.mcassistant.entity.Orders.current(village);
        String elderName = Villages.elderName(village);
        Kit.log("t39 elder " + elderName + " ordered " + first);
        helper.assertTrue(first != null && !elderName.isEmpty(), "the elder gives an order");
        VillageFolkEntity elder = null;
        for (VillageFolkEntity f : folk) if (f.getUUID().equals(Villages.elder(village))) elder = f;
        helper.assertTrue(elder != null, "the elder is one of them");
        net.minecraft.world.entity.player.Player p = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        elder.persona().feelFor(p.getUUID(), p.getName().getString(), 60);
        String asked = com.jrpetty.mcassistant.entity.Orders.talk(elder, p, "you should order the village to fill the larder");
        var now = com.jrpetty.mcassistant.entity.Orders.current(village);
        double farms = Villages.share(village, StationTask.FARM), mines = Villages.share(village, StationTask.MINE);
        Kit.log("t39 petition: " + asked + " -> " + now + "; farms " + farms + ", mines " + mines);
        helper.assertTrue(now == com.jrpetty.mcassistant.entity.Orders.Order.LARDER, "a friend of the elder can put an order to it");
        helper.assertTrue(farms < 0 && mines > 0, "the order wants more farmers and can spare a miner");
        VillageFolkEntity miner = folk.get(4);
        StationTask moved = com.jrpetty.mcassistant.entity.Orders.move(village, miner, day);
        // The move counts once the folk has really changed trade (VillageFolkEntity.changedTrade).
        if (moved != null) com.jrpetty.mcassistant.entity.Orders.moved(village, day);
        StationTask again = com.jrpetty.mcassistant.entity.Orders.move(village, folk.get(5), day);
        StationTask farmer = com.jrpetty.mcassistant.entity.Orders.move(village, folk.get(0), day + 1);
        Kit.log("t39 a miner moves to " + moved + "; a second the same day " + again + "; a farmer " + farmer);
        helper.assertTrue(moved == StationTask.FARM && again == null && farmer == null,
            "one spare hand a day goes where the order wants it, and nobody leaves the ordered trade");
        // The board, and what folk say.
        BlockPos hallAt = Kit.surface(level, heart.getX(), heart.getZ() - 22);
        BuildGoal.stamp(level, "hall", hallAt, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        com.jrpetty.mcassistant.village.Ledger.built(village, "hall", hallAt, Direction.NORTH);
        com.jrpetty.mcassistant.entity.Quests.paint(level, v);
        var hall = com.jrpetty.mcassistant.village.Ledger.buildings(village).stream().filter(x -> x.structure().equals("hall")).findFirst().orElseThrow();
        var spots = com.jrpetty.mcassistant.entity.Quests.spots(level, hall);
        String head = !spots.isEmpty() && level.getBlockEntity(spots.get(0)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign
            ? sign.getFrontText().getMessage(0, false).getString() + " / " + sign.getFrontText().getMessage(1, false).getString() : "";
        String said = com.jrpetty.mcassistant.entity.Orders.talk(folk.get(9) == elder ? folk.get(8) : folk.get(9), p, "");
        Kit.log("t39 the board's head: " + head + "; a folk says: " + said);
        helper.assertTrue(head.startsWith("ELDER'S ORDERS / Fill the"), "the order heads the board on the hall");
        helper.assertTrue(said.contains("fill the larder"), "and folk know it");
        helper.succeed();
    }

    /**
     * The trades' kits, and real brewing and beekeeping. The brewer arrives with a brewing stand,
     * blaze powder, nether wart and soul sand (once: the village does not get a second); the
     * brewery standing without its stands (nothing in the village could make one), the brewer sets
     * its own down where the drawing has one; it brews for real: three water bottles and a wart,
     * twenty seconds, then a glistering melon (made of a melon slice and gold), twenty seconds, and
     * three potions of healing come out to the stores. The beekeeper sets down the hive it brought,
     * with its swarm, and makes a second from honeycomb and planks when the bees fill the first.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "t40_kits")
    public static void t40_kits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        Kit.hold(level, 22000, 12000, 40);
        Kit.prepare(level, 22000, 12000, 40);
        BlockPos heart = Kit.surface(level, 22000, 12000);
        VillageFolkEntity brewer = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(brewer != null, "a village");
        java.util.UUID village = brewer.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.NETHER);
        BlockPos chest = Kit.surface(level, heart.getX() + 3, heart.getZ() + 3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.GLASS, 12));
        box.setItem(1, new ItemStack(Items.MELON_SLICE, 4));
        box.setItem(2, new ItemStack(Items.GOLD_INGOT, 2));
        box.setItem(3, new ItemStack(Items.HONEYCOMB, 3));
        box.setItem(4, new ItemStack(Items.OAK_LOG, 4));
        java.util.function.ToIntFunction<java.util.function.Predicate<ItemStack>> stock =
            what -> com.jrpetty.mcassistant.entity.Market.stock(level, village, what);

        // The kit, once.
        brewer.setJob(StationTask.BREW);
        boolean first = com.jrpetty.mcassistant.entity.Trades.kit(brewer);
        boolean again = com.jrpetty.mcassistant.entity.Trades.kit(brewer);
        int stands = brewer.countCarried(s -> s.is(Items.BREWING_STAND)), powder = brewer.countCarried(s -> s.is(Items.BLAZE_POWDER));
        int wart = brewer.countCarried(s -> s.is(Items.NETHER_WART)), soul = brewer.countCarried(s -> s.is(Items.SOUL_SAND));
        Kit.log("t40 the brewer's kit: " + first + " (again " + again + "): stand " + stands + ", blaze powder " + powder
            + ", nether wart " + wart + ", soul sand " + soul);
        helper.assertTrue(first && !again && stands == 1 && powder == 8 && wart == 4 && soul == 4,
            "the first brewer brings a brewing stand, blaze powder, nether wart and soul sand; the village gets one kit");

        // The brewery, put up without its stands.
        BlockPos at = Kit.surface(level, heart.getX() + 14, heart.getZ());
        BuildGoal.stampOnly(level, "brewery", at, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK), p -> p.part() != BuildGoal.Part.BREWING);
        com.jrpetty.mcassistant.village.Ledger.built(village, "brewery", at, Direction.NORTH);
        java.util.Set<BlockPos> planned = new java.util.HashSet<>();
        for (BuildGoal.Placement p : BuildGoal.plan("brewery", at, Direction.NORTH, 13)) {
            if (p.part() == BuildGoal.Part.BREWING) planned.add(p.pos());
        }
        boolean loaded = com.jrpetty.mcassistant.entity.Crafts.now(brewer, level, v);
        BlockPos stand = null;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-8, -2, -8), at.offset(8, 4, 8))) {
            if (level.getBlockState(q).is(Blocks.BREWING_STAND)) { stand = q.immutable(); break; }
        }
        helper.assertTrue(loaded && stand != null && planned.contains(stand),
            "the brewer sets its stand down in the brewery, where the drawing has one (" + stand + ")");
        var bs = (net.minecraft.world.level.block.entity.BrewingStandBlockEntity) level.getBlockEntity(stand);
        Kit.log("t40 the stand at " + stand.toShortString() + ": " + bs.getItem(0).getHoverName().getString() + " x3, "
            + bs.getItem(3).getHoverName().getString() + ", fuel " + bs.getItem(4).getHoverName().getString());
        helper.assertTrue(bs.getItem(3).is(Items.NETHER_WART) && bs.getItem(0).is(Items.POTION) && bs.getItem(4).is(Items.BLAZE_POWDER),
            "three bottles of water and a wart in the stand, fired with blaze powder");
        final BlockPos standAt = stand;
        java.util.function.Function<net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion>, Integer> potions = pot ->
            stock.applyAsInt(s -> s.is(Items.POTION) && s.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                net.minecraft.world.item.alchemy.PotionContents.EMPTY).is(pot));
        // Twenty seconds a brew, then the bees: one sequence, a step at a time.
        final long[] mark = { helper.getTick() };
        final int[] phase = { 0 };
        final VillageFolkEntity[] keeper = { null };
        final int[] combAtStart = { 0 };
        final BlockPos meadow = Kit.surface(level, heart.getX() - 16, heart.getZ() + 12);
        java.util.function.IntSupplier hives = () -> {
            int n = 0;
            for (BlockPos q : BlockPos.betweenClosed(meadow.offset(-7, -3, -7), meadow.offset(7, 4, 7))) {
                if (level.getBlockState(q).is(Blocks.BEEHIVE)) n++;
            }
            return n;
        };
        helper.onEachTick(() -> {
            long now = helper.getTick();
            if (phase[0] == 0) {
                if (now - mark[0] < 430) return;
                var st = (net.minecraft.world.level.block.entity.BrewingStandBlockEntity) level.getBlockEntity(standAt);
                String brewed = st.getItem(0).getHoverName().getString();
                boolean reagent = com.jrpetty.mcassistant.entity.Crafts.now(brewer, level, v);
                Kit.log("t40 twenty seconds on: " + brewed + "; then " + reagent + ", in the stand " + st.getItem(3).getHoverName().getString()
                    + "; gold left " + stock.applyAsInt(s -> s.is(Items.GOLD_INGOT)));
                helper.assertTrue(st.getItem(3).is(Items.GLISTERING_MELON_SLICE) || potions.apply(net.minecraft.world.item.alchemy.Potions.HEALING) > 0,
                    "the awkward potions get a glistering melon, made of a melon slice and gold");
                phase[0] = 1;
                mark[0] = now;
                return;
            }
            if (phase[0] == 1) {
                if (now - mark[0] < 430) return;
                com.jrpetty.mcassistant.entity.Crafts.now(brewer, level, v);
                int healing = potions.apply(net.minecraft.world.item.alchemy.Potions.HEALING);
                Kit.log("t40 the brew out: potions of healing in the stores " + healing);
                helper.assertTrue(healing >= 3, "three potions of healing come out of the stand to the stores");
                // The beekeeper, on its meadow, with the hive it brought.
                VillageFolkEntity k = VillageFolkSpawnerBlock.raise(level, meadow, 0.0F);
                helper.assertTrue(k != null, "a beekeeper");
                k.joinVillage(village, heart);
                k.assignPlot(com.jrpetty.mcassistant.entity.WorkZone.around(meadow, 6, com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH), "the meadow");
                k.setJob(StationTask.BEEKEEP);
                combAtStart[0] = stock.applyAsInt(s -> s.is(Items.HONEYCOMB));
                boolean kit = com.jrpetty.mcassistant.entity.Trades.kit(k);
                boolean swarm = k.countCarried(com.jrpetty.mcassistant.entity.Trades::isSwarm) == 1;
                boolean placed = com.jrpetty.mcassistant.entity.Crafts.now(k, level, v);
                Kit.log("t40 the beekeeper's kit " + kit + " (a swarm " + swarm + "), set down " + placed + ": " + hives.getAsInt() + " hive");
                helper.assertTrue(kit && swarm && placed && hives.getAsInt() == 1, "the beekeeper brings a hive with a swarm, and sets it on its meadow");
                keeper[0] = k;
                phase[0] = 2;
                mark[0] = now;
                return;
            }
            if (phase[0] == 2) {
                int bees = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Bee.class, new AABB(meadow).inflate(16), b -> b.isAlive()).size();
                if (bees < 2 && now - mark[0] < 100) return;
                phase[0] = 3;
                // The beekeeper's own work may have made the second hive already: either way, it is
                // made of the stores' comb.
                boolean more = hives.getAsInt() >= 2 || com.jrpetty.mcassistant.entity.Crafts.now(keeper[0], level, v);
                int n = hives.getAsInt();
                Kit.log("t40 the beekeeper: swarm of " + bees + "; a second hive " + more + " (" + n + " hives), honeycomb "
                    + combAtStart[0] + " -> " + stock.applyAsInt(s -> s.is(Items.HONEYCOMB)));
                helper.assertTrue(bees >= 2, "the swarm comes out of the hive it brought");
                helper.assertTrue(more && n == 2 && stock.applyAsInt(s -> s.is(Items.HONEYCOMB)) == combAtStart[0] - 3,
                    "a second hive is made of three honeycomb and six planks, not out of thin air");
                helper.succeed();
            }
        });
    }

    /**
     * The links between the trades: a farmer plants the cane it brought by the field's water and
     * cuts it; a rancher milks a cow, walks a wild sheep home on a lead, and — nothing else wild
     * near — buys a drover's pair; a smelter digs sand off a pond's bed for glass; a guard drinks a
     * healing potion when hurt; the cook bakes a cake with the milk and sends the buckets back.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "t41_supply")
    public static void t41_supply(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(3000);
        // Wide: the rancher looks for wild animals 48 blocks round its pen, and only on loaded ground.
        Kit.hold(level, 24000, 12000, 80);
        Kit.prepare(level, 24000, 12000, 80);
        BlockPos heart = Kit.surface(level, 24000, 12000);
        VillageFolkEntity farmer = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(farmer != null, "a village");
        java.util.UUID village = farmer.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.IRON);
        java.util.function.ToIntFunction<java.util.function.Predicate<ItemStack>> stock =
            what -> com.jrpetty.mcassistant.entity.Market.stock(level, village, what);

        // Cane by the water.
        BlockPos field = Kit.surface(level, heart.getX() - 14, heart.getZ() - 14);
        level.setBlock(field.below(), Blocks.WATER.defaultBlockState(), 3);
        farmer.assignPlot(com.jrpetty.mcassistant.entity.WorkZone.around(field, 5, com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH), "the field");
        farmer.setJob(StationTask.FARM);
        boolean cuttings = com.jrpetty.mcassistant.entity.Trades.kit(farmer);
        int cane = farmer.countCarried(s -> s.is(Items.SUGAR_CANE));
        String planted = com.jrpetty.mcassistant.entity.Links.cane(farmer, level);
        BlockPos caneAt = null;
        for (BlockPos q : BlockPos.betweenClosed(field.offset(-2, -1, -2), field.offset(2, 1, 2))) {
            if (level.getBlockState(q).is(Blocks.SUGAR_CANE)) { caneAt = q.immutable(); break; }
        }
        helper.assertTrue(cuttings && cane == 3 && planted != null && caneAt != null,
            "the first farmer brings cane cuttings and plants one on the water's edge (" + planted + ")");
        level.setBlock(caneAt.above(), Blocks.SUGAR_CANE.defaultBlockState(), 2 | 16);
        level.setBlock(caneAt.above(2), Blocks.SUGAR_CANE.defaultBlockState(), 2 | 16);
        int before = farmer.countCarried(s -> s.is(Items.SUGAR_CANE));
        String cut = com.jrpetty.mcassistant.entity.Links.cane(farmer, level);
        int after = farmer.countCarried(s -> s.is(Items.SUGAR_CANE));
        Kit.log("t41 the farmer: " + planted + "; then " + cut + " (" + before + " -> " + after + "), the bottom left "
            + level.getBlockState(caneAt).is(Blocks.SUGAR_CANE));
        helper.assertTrue(after == before + 2 && level.getBlockState(caneAt).is(Blocks.SUGAR_CANE),
            "the cane is cut down to its bottom, which grows again");

        // The rancher: a cow milked.
        BlockPos pen = Kit.surface(level, heart.getX() + 14, heart.getZ() - 14);
        VillageFolkEntity rancher = VillageFolkSpawnerBlock.raise(level, pen, 0.0F);
        rancher.joinVillage(village, heart);
        rancher.assignPlot(com.jrpetty.mcassistant.entity.WorkZone.around(pen, 6, com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH), "the pen");
        rancher.setJob(StationTask.RANCH);
        boolean leads = com.jrpetty.mcassistant.entity.Trades.kit(rancher);
        rancher.insertItem(new ItemStack(Items.BUCKET));
        net.minecraft.world.entity.animal.Cow cow = EntityType.COW.create(level);
        cow.moveTo(pen.getX() + 2.5, pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(cow);
        // A wild sheep out in the country, and nothing else wild about.
        for (net.minecraft.world.entity.animal.Animal a : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                new AABB(pen).inflate(64, 32, 64))) {
            if (a != cow) a.discard();
        }
        net.minecraft.world.entity.animal.Sheep sheep = EntityType.SHEEP.create(level);
        sheep.moveTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 26.5, 0.0F, 0.0F);
        level.addFreshEntity(sheep);
        final long from = helper.getTick();
        final int[] step = { 0 };
        final String[] milked = { null };
        helper.onEachTick(() -> {
            if (step[0] == 0) {
                // The animals added this tick are seen from a later one.
                if (level.getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, new AABB(pen).inflate(8)).isEmpty()
                        && helper.getTick() - from < 100) return;
                milked[0] = com.jrpetty.mcassistant.entity.Links.milk(rancher, level);
                helper.assertTrue(milked[0] != null && rancher.countCarried(s -> s.is(Items.MILK_BUCKET)) == 1,
                    "the rancher milks the cow into its bucket");
                boolean off = com.jrpetty.mcassistant.entity.Drover.consider(rancher, level);
                Kit.log("t41 the rancher: leads " + leads + " (" + rancher.countCarried(s -> s.is(Items.LEAD)) + "), " + milked[0]
                    + "; the pen has a cow and no pair, off for the sheep: " + off);
                helper.assertTrue(leads && off && com.jrpetty.mcassistant.entity.Drover.busy(rancher),
                    "with no pair in the pen the rancher takes a lead out for a wild sheep");
                step[0] = 1;
                return;
            }
            if (step[0] == 1) {
                double dx = sheep.getX() - (pen.getX() + 0.5), dz = sheep.getZ() - (pen.getZ() + 0.5);
                boolean home = !com.jrpetty.mcassistant.entity.Drover.busy(rancher) && dx * dx + dz * dz < 6.0 * 6.0;
                if (!home && helper.getTick() - from < 1200) return;
                Kit.log("t41 the sheep after " + (helper.getTick() - from) + " ticks: " + Math.round(Math.sqrt(dx * dx + dz * dz))
                    + " blocks from the pen, leashed " + sheep.isLeashed() + ", the rancher's leads " + rancher.countCarried(s -> s.is(Items.LEAD))
                    + ", " + rancher.debugLine());
                helper.assertTrue(home && !sheep.isLeashed(), "the rancher walks the wild sheep home on the lead and lets it off in the pen");
                helper.assertTrue(rancher.countCarried(s -> s.is(Items.LEAD)) == 2, "and keeps its lead");
                // Nothing else wild near: the drover's pair.
                for (net.minecraft.world.entity.animal.Animal a : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                        new AABB(pen).inflate(64, 32, 64))) {
                    if (a != cow && a != sheep) a.discard();
                }
                boolean bought = com.jrpetty.mcassistant.entity.Drover.consider(rancher, level);
                step[0] = 2;
                int sheepNow = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Sheep.class, new AABB(pen).inflate(10)).size();
                Kit.log("t41 nothing wild left: the drover's pair " + bought + "; sheep by the pen " + sheepNow);
                helper.assertTrue(bought, "with nothing wild for fifty blocks the village buys a drover's pair, once");
                helper.assertTrue(!com.jrpetty.mcassistant.entity.Drover.consider(rancher, level), "and only once");
                rest41(helper, level, village, v, heart, stock, rancher);
            }
        });
    }

    /** t41 after the pen: sand for glass, a guard's potion, and a cake. */
    private static void rest41(GameTestHelper helper, ServerLevel level, java.util.UUID village, Villages.Village v, BlockPos heart,
                               java.util.function.ToIntFunction<java.util.function.Predicate<ItemStack>> stock, VillageFolkEntity rancher) {
        // A pond with a sandy bed, six blocks out.
        for (int dz = -1; dz <= 1; dz++) {
            BlockPos top = Kit.surface(level, heart.getX() + 6, heart.getZ() + dz);
            level.setBlock(top.below(2), Blocks.SAND.defaultBlockState(), 3);
            level.setBlock(top.below(), Blocks.WATER.defaultBlockState(), 3);
        }
        VillageFolkEntity smelter = VillageFolkSpawnerBlock.raise(level, heart.offset(2, 0, -2), 0.0F);
        smelter.joinVillage(village, heart);
        smelter.setJob(StationTask.SMELT);
        String dug = com.jrpetty.mcassistant.entity.Links.sand(smelter, level);
        int sand = smelter.countCarried(s -> s.is(Items.SAND));
        Kit.log("t41 the smelter: " + dug + ", carrying " + sand);
        helper.assertTrue(dug != null && sand >= 1, "the smelter digs sand off the pond's bed for glass");

        // A guard, hurt, with the brewer's healing.
        VillageFolkEntity guard = VillageFolkSpawnerBlock.raise(level, heart.offset(-2, 0, 2), 0.0F);
        guard.joinVillage(village, heart);
        guard.setJob(StationTask.GUARD);
        guard.insertItem(net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION,
            net.minecraft.world.item.alchemy.Potions.HEALING));
        guard.setHealth(5.0F);
        boolean drank = com.jrpetty.mcassistant.entity.Links.drinkIfHurt(guard);
        Kit.log("t41 the guard: drank " + drank + ", health " + guard.getHealth() + ", bottles " + guard.countCarried(s -> s.is(Items.GLASS_BOTTLE)));
        helper.assertTrue(drank && guard.getHealth() >= 9.0F && guard.countCarried(s -> s.is(Items.GLASS_BOTTLE)) == 1,
            "a guard badly hurt drinks a healing potion");

        // A cake from the stores, the buckets back.
        BlockPos chest = Kit.surface(level, heart.getX() - 3, heart.getZ() - 3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.MILK_BUCKET));
        box.setItem(1, new ItemStack(Items.MILK_BUCKET));
        box.setItem(2, new ItemStack(Items.MILK_BUCKET));
        box.setItem(3, new ItemStack(Items.SUGAR_CANE, 2));
        box.setItem(4, new ItemStack(Items.EGG, 1));
        box.setItem(5, new ItemStack(Items.WHEAT, 15));
        box.setItem(6, new ItemStack(Items.BREAD, 12));
        // Whatever the café is shortest of first (baked potatoes from the founding stores, maybe), then the cake.
        String baked = null;
        for (int i = 0; i < 4 && stock.applyAsInt(s -> s.is(Items.CAKE)) == 0; i++) baked = com.jrpetty.mcassistant.entity.Cafe.cook(level, v);
        int cakes = stock.applyAsInt(s -> s.is(Items.CAKE)), buckets = stock.applyAsInt(s -> s.is(Items.BUCKET));
        Kit.log("t41 the cook: " + baked + "; cakes " + cakes + ", empty buckets back " + buckets);
        helper.assertTrue(cakes == 1 && buckets == 3, "the cook bakes a cake with the rancher's milk and sends the buckets back");
        helper.succeed();
    }

    /**
     * Beds into a house that went up without them. A house got beds only on the day it was built,
     * from whatever wool the stores held that day, and never after: the long game's village of
     * forty-eight had four. Now one is made of the stores' wool and planks and carried in, one a
     * turn, and a house that has all its beds is left alone.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "t43_beds")
    public static void t43_beds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        Kit.hold(level, 26000, 12000, 40);
        Kit.prepare(level, 26000, 12000, 40);
        BlockPos heart = Kit.surface(level, 26000, 12000);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        java.util.UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        BlockPos at = Kit.surface(level, heart.getX() + 20, heart.getZ());
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        com.jrpetty.mcassistant.village.Ledger.built(village, "house", at, Direction.NORTH);
        java.util.function.IntSupplier beds = () -> {
            int n = 0;
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-9, -3, -9), at.offset(9, 8, 9))) {
                if (level.getBlockState(q).is(net.minecraft.tags.BlockTags.BEDS)) n++;
            }
            return n;
        };
        int drawn = beds.getAsInt();
        // Built with no wool to hand: no beds.
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-9, -3, -9), at.offset(9, 8, 9))) {
            if (level.getBlockState(q).is(net.minecraft.tags.BlockTags.BEDS)) level.setBlock(q, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        boolean none = com.jrpetty.mcassistant.entity.Grow.furnish(level, v);
        // Wool for one bed in the stores.
        BlockPos chest = Kit.surface(level, heart.getX() + 3, heart.getZ() - 3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.BLUE_WOOL, 3));
        box.setItem(1, new ItemStack(Items.OAK_PLANKS, 3));
        boolean put = com.jrpetty.mcassistant.entity.Grow.furnish(level, v);
        int after = beds.getAsInt();
        boolean blue = false;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-9, -3, -9), at.offset(9, 8, 9))) {
            if (level.getBlockState(q).is(Blocks.BLUE_BED)) blue = true;
        }
        boolean more = com.jrpetty.mcassistant.entity.Grow.furnish(level, v);
        Kit.log("t43 the house's drawing has " + drawn / 2 + " beds; with no wool a bed put in: " + none
            + "; with three wool: " + put + " (" + after / 2 + " in, blue " + blue + ", wool left "
            + box.getItem(0).getCount() + "); and with none left: " + more);
        helper.assertTrue(drawn >= 2 && !none, "a house's beds wait for the wool");
        helper.assertTrue(put && after == 2 && blue && box.getItem(0).isEmpty(),
            "three wool and three planks from the stores make a bed, carried into the house that had none");
        helper.assertTrue(!more, "and no more than the stores can make");
        helper.succeed();
    }

    /**
     * A farmer puts its field by the water nearest the village: a pond just past the town's
     * edge one way, another twice as far the other, and the field goes on the near one's bank.
     * And monsters about a village with no wall don't ring the bell (it rang the first night
     * in every new village, and in one never stopped: nobody worked for three days).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t42_fields")
    public static void t42_fields(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        Kit.hold(level, 26000, 12000, 120);
        Kit.prepare(level, 26000, 12000, 120);
        BlockPos heart = Kit.surface(level, 26000, 12000);
        VillageFolkEntity farmer = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(farmer != null, "a village");
        java.util.UUID village = farmer.ownerId();
        int reach = Villages.townReach(village);
        int nearX = heart.getX() + reach + 12, farZ = heart.getZ() - (reach + 45);
        Kit.pond(level, nearX, heart.getZ(), 3);
        Kit.pond(level, heart.getX(), farZ, 3);
        BlockPos site = farmer.fieldSiteForTests();
        int fromPond = site == null ? -1 : Math.max(Math.abs(site.getX() - nearX), Math.abs(site.getZ() - heart.getZ()));
        int fromHeart = site == null ? -1 : Math.max(Math.abs(site.getX() - heart.getX()), Math.abs(site.getZ() - heart.getZ()));
        Kit.log("t42 the town reaches " + reach + "; ponds at " + (reach + 12) + " east and " + (reach + 45)
            + " north; the field goes at " + site + ", " + fromPond + " from the near pond, " + fromHeart + " from the heart");
        helper.assertTrue(site != null && fromPond <= 9, "the field goes on the bank of the nearest water");
        // Monsters about a village with no wall, at night: no bell.
        level.setDayTime(14000);
        for (int i = 0; i < 5; i++) {
            net.minecraft.world.entity.monster.Zombie z = EntityType.ZOMBIE.create(level);
            z.moveTo(heart.getX() + 3.5 + i, heart.getY(), heart.getZ() + 3.5, 0.0F, 0.0F);
            z.setPersistenceRequired();
            level.addFreshEntity(z);
        }
        final long from = helper.getTick();
        helper.onEachTick(() -> {
            if (helper.getTick() - from < 40) return;
            int near = level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class, new AABB(heart).inflate(12)).size();
            Villages.Village v = Villages.get(village);
            com.jrpetty.mcassistant.entity.Raids.tick(level, v);
            boolean bell = com.jrpetty.mcassistant.entity.Raids.underAlarm(village);
            Kit.log("t42 " + near + " zombies about the open village; the bell " + (bell ? "rings" : "is quiet"));
            for (net.minecraft.world.entity.monster.Zombie z : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class,
                    new AABB(heart).inflate(16))) z.discard();
            helper.assertTrue(near >= 4 && !bell, "monsters about a village with no wall don't ring a bell it hasn't got");
            helper.succeed();
        });
    }

    /** The rest of t35, once the child is grown: old age, the grave, the register, the tavern. */
    private static void rest(GameTestHelper helper, ServerLevel level, java.util.UUID village, Villages.Village v, BlockPos heart,
                             VillageFolkEntity mum, VillageFolkEntity dad, VillageFolkEntity child) {
        // Old age.
        level.setDayTime((level.getDayTime() / 24000L) * 24000L + 600L);
        int lifespan = mum.lifespan();
        mum.bornDaysAgo(3 + lifespan);                 // well past its years
        String name = mum.displayNameCap();
        Kit.log("t35 " + name + " is " + mum.ageYears() + " (lifespan " + lifespan + "), old " + mum.isOld());
        mum.growOldForTests();
        java.util.List<com.jrpetty.mcassistant.village.Ledger.Grave> graves = com.jrpetty.mcassistant.village.Ledger.graves(village);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (var e : com.jrpetty.mcassistant.village.Chronicle.of(village)) lines.add(e.text());
        Kit.log("t35 after: alive " + mum.isAlive() + ", graves " + graves + ", last news " + lines.get(lines.size() - 1));
        helper.assertTrue(!mum.isAlive(), "dies at the end of its years");
        helper.assertTrue(graves.stream().anyMatch(g -> g.name().equals(name) && g.cause().equals("of old age")), "and is recorded among the dead");
        helper.assertTrue(lines.stream().anyMatch(x -> x.contains("died peacefully in their sleep")), "peacefully, says the history");
        // The graveyard.
        BlockPos yard = Kit.surface(level, heart.getX(), heart.getZ() - 18);
        BuildGoal.stamp(level, "graveyard", yard, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        com.jrpetty.mcassistant.village.Ledger.built(village, "graveyard", yard, Direction.NORTH);
        int stones = com.jrpetty.mcassistant.entity.Graves.tend(level, village);
        int[] plot = com.jrpetty.mcassistant.entity.Graves.PLOTS[0];
        BlockPos mound = yard.relative(Direction.EAST, plot[0]).relative(Direction.NORTH, plot[1]);
        String carved = level.getBlockEntity(mound) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign
            ? sign.getFrontText().getMessage(0, false).getString() : "";
        Kit.log("t35 the graveyard: " + stones + " stones put up; the first says " + carved);
        helper.assertTrue(stones == 1 && level.getBlockState(mound.relative(Direction.NORTH)).is(Blocks.CHISELED_STONE_BRICKS)
            && carved.equals(name), "a headstone with the name on it");
        // The register.
        ItemStack book = com.jrpetty.mcassistant.entity.Chronicles.register(village, level.getDayTime() / 24000L);
        StringBuilder text = new StringBuilder();
        for (var page : book.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT).pages()) {
            text.append(page.raw().getString()).append(" | ");
        }
        Kit.log("t35 the register: " + text);
        helper.assertTrue(text.indexOf("In memory") >= 0 && text.indexOf("Families") >= 0 && text.indexOf(name + " †") >= 0,
            "the register remembers the dead and the families");
        // The tavern, and a round.
        BlockPos inn = Kit.surface(level, heart.getX() + 18, heart.getZ() - 18);
        BuildGoal.stamp(level, "tavern", inn, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        com.jrpetty.mcassistant.village.Ledger.built(village, "tavern", inn, Direction.NORTH);
        var tav = com.jrpetty.mcassistant.entity.Tavern.of(village);
        com.jrpetty.mcassistant.entity.Tavern.board(level, tav);
        dad.teleportTo(inn.getX() + 1.5, inn.getY(), inn.getZ() + 0.5);
        child.teleportTo(inn.getX() + 2.5, inn.getY(), inn.getZ() - 1.5);
        net.minecraft.world.entity.player.Player p = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        p.getInventory().setItem(20, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 10));
        String said = com.jrpetty.mcassistant.entity.Tavern.round(level, v, p);
        int left = com.jrpetty.mcassistant.entity.Market.coinsHeld(p);
        Kit.log("t35 the tavern: " + said + " (" + left + " coins left)");
        helper.assertTrue(said.startsWith("You bought a round for 2") && left == 8, "a round for the two in the tavern, a coin a head");
        helper.succeed();
    }

    /**
     * A village and the colony it founded: the road between them laid from one avenue to the
     * other, a bridge where it crosses water, a signpost at each end; then a caravan that sets
     * out with the mother's surplus bread, unloads it in the colony's stores, and comes home.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "t31_roads")
    public static void t31_roads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int ax = 12800, az = 12800, bx = 13020;
        for (int x = ax - 48; x <= bx + 48; x += 48) {
            Kit.hold(level, x, az, 40);
            Kit.prepare(level, x, az, 40);
        }
        BlockPos a = Kit.surface(level, ax, az), b = Kit.surface(level, bx, az);
        VillageFolkEntity m1 = VillageFolkSpawnerBlock.raise(level, a, 0.0F);
        VillageFolkEntity m2 = VillageFolkSpawnerBlock.raise(level, a.east(), 0.0F);
        VillageFolkEntity c1 = VillageFolkSpawnerBlock.raise(level, b, 0.0F);
        helper.assertTrue(m1 != null && m2 != null && c1 != null, "a village of two and a colony of one");
        Villages.Village mother = Villages.get(m1.ownerId()), colony = Villages.get(c1.ownerId());
        helper.assertTrue(mother != null && colony != null && !mother.id().equals(colony.id()), "two villages");
        com.jrpetty.mcassistant.village.Ledger.link(mother.id(), colony.id());
        // A pond across the road's way.
        int px = ax + 100;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -4; dz <= 4; dz++) {
            BlockPos g = Kit.surface(level, px + dx, az + dz).below();
            level.setBlock(g, Blocks.WATER.defaultBlockState(), 3);
        }
        int laid = com.jrpetty.mcassistant.entity.Roads.lay(level, mother, colony, 1000);
        int[] state = com.jrpetty.mcassistant.village.Ledger.road(colony.id());
        BlockPos mid = Kit.surface(level, ax + 60, az).below();
        BlockPos deck = Kit.surface(level, px, az).below();
        Kit.log("t31 the road: " + laid + " steps laid, done " + (state != null && state[2] == 1) + "; at the middle "
            + level.getBlockState(mid) + "; over the pond " + level.getBlockState(deck)
            + ", its rail " + level.getBlockState(deck.above().relative(Direction.SOUTH, 2)));
        helper.assertTrue(state != null && state[2] == 1, "the road is finished");
        helper.assertTrue(level.getBlockState(mid).is(Blocks.DIRT_PATH), "a worn road out in the country");
        helper.assertTrue(level.getBlockState(deck).is(Blocks.SPRUCE_PLANKS)
            && level.getBlockState(deck.above().relative(Direction.SOUTH, 2)).getBlock() instanceof net.minecraft.world.level.block.FenceBlock,
            "a bridge with rails over the water");
        java.util.List<String> posts = new java.util.ArrayList<>();
        for (BlockPos at : new BlockPos[]{ a.offset(41, 0, 3), b.offset(-41, 0, -3) }) {
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-1, -3, -1), at.offset(1, 4, 1))) {
                if (level.getBlockEntity(q) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                    posts.add(sign.getFrontText().getMessage(1, false).getString());
                }
            }
        }
        Kit.log("t31 the signposts say: " + posts);
        helper.assertTrue(posts.contains(Villages.name(colony.id())) && posts.contains(Villages.name(mother.id())),
            "a signpost at each end, to the other town");
        // A caravan.
        BlockPos chest = Kit.surface(level, ax + 4, az + 4);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
        net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.BREAD, 64));
        box.setItem(1, new ItemStack(Items.BREAD, 64));
        int before = com.jrpetty.mcassistant.entity.Market.stock(level, colony.id(), st -> st.is(Items.BREAD));
        boolean out = com.jrpetty.mcassistant.entity.Caravans.setOut(level, mother, colony);
        VillageFolkEntity carrier = m1.trip() != null ? m1 : m2;
        int llamas = level.getEntitiesOfClass(net.minecraft.world.entity.animal.horse.Llama.class, around(a, 12),
            l -> l.getTags().contains("mca_caravan")).size();
        Kit.log("t31 the caravan set out: " + out + ", " + carrier.displayNameCap() + " carrying "
            + carrier.countCarried(st -> st.is(Items.BREAD)) + " bread, " + llamas + " llama");
        helper.assertTrue(out && carrier.trip() != null && carrier.countCarried(st -> st.is(Items.BREAD)) >= 32 && llamas == 1,
            "a caravan sets out with the mother's spare bread and a pack llama");
        com.jrpetty.mcassistant.entity.Caravans.arriveForTests(level, carrier);
        int after = com.jrpetty.mcassistant.entity.Market.stock(level, colony.id(), st -> st.is(Items.BREAD));
        Kit.log("t31 at the colony: its bread " + before + " -> " + after + "; homeward " + (carrier.trip() != null && carrier.trip().homeward()));
        helper.assertTrue(after >= before + 32 && carrier.trip() != null && carrier.trip().homeward(),
            "the caravan unloads in the colony's stores and turns for home");
        com.jrpetty.mcassistant.entity.Caravans.arriveForTests(level, carrier);
        helper.assertTrue(carrier.trip() == null, "and is home again");
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
