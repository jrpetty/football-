package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Battlefields;
import com.jrpetty.mcassistant.entity.Campaigns;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * [field] Open-field battles: the Captains choose flat open ground (no ravine, no forest) to fight on, and two towns of
 * seventy put big armies into the field.
 *
 * <p>Each on its own ground in x 2040000-2059999, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BattlefieldGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ bf01

    /**
     * A plain of grass laid high over the world (nothing of the world's own ground in it), a road's two ends 140 blocks
     * apart across it; a ravine cut clean across the plain just past the road's middle, a forest beyond it. The field
     * the Captains choose is on the open plain, not over the ravine or among the trees, and the road's own middle (the
     * ravine) and the forest are both found unfit.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bf01_open_plain")
    public static void bf01_open_plain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x0 = 2040000, top = 250, ravine = x0 + 80;
        Kit.hold(level, x0 + 70, Z, 96);
        Kit.prepare(level, x0 + 70, Z, 96);
        for (int x = x0 - 10; x <= x0 + 150; x++) {
            for (int z = Z - 50; z <= Z + 50; z++) {
                boolean cut = x >= ravine && x <= ravine + 5;                     // the ravine: six blocks wide, down to the world's own ground
                level.setBlock(new BlockPos(x, top, z), cut ? Blocks.AIR.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        }
        for (int x = x0 + 92; x <= x0 + 146; x += 8) for (int z = Z - 46; z <= Z + 46; z += 8) Kit.tree(level, x, z);
        BlockPos from = new BlockPos(x0, top + 1, Z), to = new BlockPos(x0 + 140, top + 1, Z);
        Battlefields.Ground g = Battlefields.chooseForTests(level, from, to);
        Kit.log("bf01 the field: " + (g == null ? "none" : g.centre().toShortString() + " known " + g.known()));
        helper.assertTrue(g != null && g.known(), "a field chosen on the plain");
        helper.assertTrue(g.centre().getY() == top + 1, "the field on the plain's grass, not down the ravine: " + g.centre());
        helper.assertTrue(g.centre().getX() < ravine, "the field on the open side of the ravine, not among the trees: " + g.centre());
        helper.assertTrue(Battlefields.fitForTests(level, g.centre(), from, to), "the chosen field is fit to fight on: " + g.centre());
        helper.assertTrue(!Battlefields.fitForTests(level, new BlockPos(x0 + 70, top + 1, Z), from, to), "the road's middle, over the ravine, is unfit");
        helper.assertTrue(!Battlefields.fitForTests(level, new BlockPos(x0 + 120, top + 1, Z), from, to), "the forest is unfit");
        helper.succeed();
    }

    // ------------------------------------------------------------------ bf02

    /**
     * Two towns of seventy, 260 blocks apart, forty-five of each put on the watch and armed in iron (the battle
     * scenario's own steps), at war: the admin's battle staged at the muster fields thirty and more a side, every one
     * of them drawn in full, on a field the Captains chose with its ground looked at.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bf02_big_armies")
    public static void bf02_big_armies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        WarAndPeace.resetForTests();
        Campaigns.resetForTests();
        Campaigns.hurryForTests(true);
        level.setDayTime(40L * 24000L + 1200L);
        int x = 2050000, apart = 260;
        Villages.Village a = town(level, x), b = town(level, x + apart);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(40, () -> {
            for (Villages.Village v : new Villages.Village[]{ a, b }) {
                List<VillageFolkEntity> watch = new ArrayList<>();
                Random rng = new Random(7);
                for (int k = 0; k < 45; k++) {
                    VillageFolkEntity f = AdminTools.enlist(v);
                    if (f == null) f = AdminTools.raise(level, v, AdminTools.Kind.ADULT, AssistantEntity.StationTask.GUARD, rng);
                    if (f != null) watch.add(f);
                }
                AdminTools.kit(level, v, watch, "iron", true);
            }
            helper.assertTrue(AdminTools.war(level, a, b).ok(), "at war");
            AdminTools.Done d = AdminTools.phase(level, a, "muster");
            Kit.log("bf02 staged: " + d.text().replace("\n", " | "));
            helper.assertTrue(d.ok(), "the battle staged: " + d.text());
            Campaigns.Campaign c = Campaigns.ofTown(a.id());
            helper.assertTrue(c != null && c.army(0) != null && c.army(1) != null, "both armies mustered");
            helper.assertTrue(c.ground().known(), "the field chosen from its ground, not blind: " + c.ground().centre());
            for (int i = 0; i < 2; i++) {
                int size = c.army(i).size(), drawn = c.army(i).fighters().size();
                helper.assertTrue(size >= 30, "a town of seventy fields thirty or more: side " + i + " has " + size);
                helper.assertTrue(drawn >= 30, "every one of them drawn in full on the stage: side " + i + " drawn " + drawn + " of " + size);
            }
            Campaigns.unstage(level, a.id());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ bf03

    /**
     * Two towns with a lake between them, nothing fit to fight on: the last resort is no longer taken blind at the
     * edge of the larger town's fields towards the other (in the lake) but is the driest, levellest ground round
     * either town's fields: on dry land, never in the water.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bf03_not_in_the_lake")
    public static void bf03_not_in_the_lake(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x = 2056000, apart = 300;
        Villages.Village a = town(level, x), b = town(level, x + apart);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        // The lake: everything between the two towns' own ground, the surface turned to water.
        int reach = Villages.townReach(a.id()) + 4;
        Kit.hold(level, x + apart / 2, Z, 160);
        Kit.prepare(level, x + apart / 2, Z, 160);
        for (int xx = x + reach; xx <= x + apart - reach; xx++) {
            for (int z = Z - 120; z <= Z + 120; z++) {
                BlockPos g = Kit.surface(level, xx, z).below();
                level.setBlock(g, Blocks.WATER.defaultBlockState(), 2);
            }
        }
        BlockPos at = Battlefields.driestForTests(level, a, b);
        boolean wet = !level.getBlockState(at.below()).getFluidState().isEmpty();
        Kit.log("bf03 the last resort: " + at.toShortString() + " on " + level.getBlockState(at.below()) + "; town " + a.centre().toShortString());
        helper.assertTrue(!wet, "the armies meet on dry ground, not in the lake: " + at.toShortString());
        helper.assertTrue(at.getX() < x + reach || at.getX() > x + apart - reach, "beside a town's fields, out of the lake: " + at.toShortString());
        helper.succeed();
    }

    private static Villages.Village town(ServerLevel level, int x) {
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 70);
        return Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
    }
}
