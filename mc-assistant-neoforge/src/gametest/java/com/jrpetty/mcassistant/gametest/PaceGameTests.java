package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Getting quicker at the work (AssistantEntity's pace of work): every level at a trade makes its
 * work a percent quicker, to thirty at level thirty; a better tool, wood to stone to iron to
 * diamond, makes it quicker again; a builder lays its blocks quicker with experience; the old are
 * a little slower than their young selves but an old master still beats a young beginner; and
 * the folk's card says how quick it is and why.
 *
 * <p>Each measures the pace straight from the folk, in the tick it is raised: no work is waited
 * on, so nothing is left to chance. The folk's crew, quirk, mood, village, nature and skills are
 * drawn at random, and any of them could fill the cap, so the measures are taken with them left
 * out (plainPaceForTests): its level, its years and its tool alone. Like the village tests, each
 * runs on its own ground, far from the others, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PaceGameTests {

    private static final String EMPTY = "empty";

    /** A folk of a new village on its own ground, at this trade, measured on its level, years and tool alone. */
    private static VillageFolkEntity folk(GameTestHelper helper, int x, int z, StationTask trade) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        Kit.hold(level, x, z, 24);
        Kit.prepare(level, x, z, 24);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        f.setJob(trade);
        f.plainPaceForTests(true);
        f.bornDaysAgo(VillageFolkEntity.GROW_DAYS);         // just grown: eighteen, nowhere near old
        return f;
    }

    /** So many levels at its trade, as though it had earned them there. */
    private static void level(GameTestHelper helper, VillageFolkEntity f, int lv) {
        f.tradeXpForTests(f.stationTask(), AssistantEntity.xpForLevel(lv));
        helper.assertTrue(f.veteranLevel() == lv, "level " + lv + " at its trade, not " + f.veteranLevel());
    }

    private static void hold(VillageFolkEntity f, Item tool) {
        f.setItemSlot(EquipmentSlot.MAINHAND, tool == Items.AIR ? ItemStack.EMPTY : new ItemStack(tool));
    }

    // ============================================================ experience

    /**
     * Every level counts: three percent quicker a level (ninety at level thirty), and never less than the old
     * rungs gave (10% at 10, 20% at 20, 30% at 35). A miner with the same stone pick breaks stone
     * quicker at level ten than at nought, and quicker again at thirty; its other work (the pace
     * actionPaceTicks sets, a shear, a crop, a feed) the same; and the crafts' bench and the
     * fisher's wait shorten with it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pc01_experience")
    public static void pc01_experience(GameTestHelper helper) {
        // The curve itself.
        for (int lv = 0; lv <= 50; lv++) {
            int now = AssistantEntity.experiencePercentAt(lv);
            int rung = lv >= 35 ? 30 : lv >= 20 ? 20 : lv >= 10 ? 10 : 0;
            helper.assertTrue(now >= rung, "level " + lv + " is never slower than it was on the rungs: " + now + "% against " + rung + "%");
            helper.assertTrue(now <= AssistantEntity.MOST_EXPERIENCE_PERCENT, "and never more than the most: " + now);
            helper.assertTrue(AssistantEntity.experienceSpeedAt(lv) == 3 * lv, "three percent quicker a level: level " + lv);
            if (lv > 0) {
                helper.assertTrue(now >= AssistantEntity.experiencePercentAt(lv - 1), "every level counts: level " + lv);
            }
        }
        VillageFolkEntity f = folk(helper, 120000, 40000, StationTask.MINE);
        BlockState stone = Blocks.STONE.defaultBlockState();
        hold(f, Items.STONE_PICKAXE);
        int[] levels = { 0, 10, 30 };
        int[] stroke = new int[3], action = new int[3], bench = new int[3], bite = new int[3];
        for (int i = 0; i < levels.length; i++) {
            level(helper, f, levels[i]);
            helper.assertTrue(f.experiencePercent() == AssistantEntity.experiencePercentAt(levels[i]),
                "level " + levels[i] + " is worth " + AssistantEntity.experienceSpeedAt(levels[i]) + "% quicker: " + f.experiencePercent() + "% off the time");
            helper.assertTrue(f.workBonusPercent() == f.experiencePercent(), "and nothing else counts with the pace kept plain: " + f.workBonusPercent());
            stroke[i] = f.workTicksFor(stone);
            action[i] = f.actionPaceTicks();
            bench[i] = f.pacedTicks(400, 100);
            bite[i] = f.pacedTicks(400, 50);
        }
        Kit.log("pc01 a block of stone with a stone pick at levels 0/10/30: " + java.util.Arrays.toString(stroke)
            + " ticks; an action: " + java.util.Arrays.toString(action) + "; a piece at the bench: " + java.util.Arrays.toString(bench)
            + "; the longest wait for a bite: " + java.util.Arrays.toString(bite));
        helper.assertTrue(stroke[0] > stroke[1] && stroke[1] > stroke[2], "stone breaks quicker at level 10, and quicker again at 30: " + java.util.Arrays.toString(stroke));
        helper.assertTrue(action[0] > action[1] && action[1] > action[2], "and every other piece of work too: " + java.util.Arrays.toString(action));
        // Three percent quicker a level: level thirty is 90% quicker, a job in 53% of the time.
        helper.assertTrue(bench[0] == 400 && bench[1] == 308 && bench[2] == 212,
            "a piece at the bench every twenty seconds for a new hand, 15.4 at level ten, 10.6 at thirty: " + java.util.Arrays.toString(bench));
        helper.assertTrue(bite[0] == 400 && bite[2] == 308, "and the fish bite sooner for an old hand, by half as much: " + java.util.Arrays.toString(bite));
        helper.assertTrue(stroke[2] >= 12 && action[2] >= 12, "never quicker than about half a second");
        helper.succeed();
    }

    // ============================================================ tools

    /**
     * A better tool is quicker work, at the same level: a miner's pick (wood, stone, iron, diamond)
     * on iron ore, a woodcutter's axe (wood, stone, iron) on a log, and a farmer's hoe (none, wood,
     * stone, iron) at the field — a farmer with no hoe slowest of all, where it used to work at the
     * three-second base, a netherite hoe's pace.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pc02_tools")
    public static void pc02_tools(GameTestHelper helper) {
        VillageFolkEntity f = folk(helper, 121500, 40000, StationTask.MINE);
        level(helper, f, 10);
        BlockState ore = Blocks.IRON_ORE.defaultBlockState();
        Item[] picks = { Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE };
        int[] mine = new int[picks.length];
        for (int i = 0; i < picks.length; i++) {
            hold(f, picks[i]);
            mine[i] = f.workTicksFor(ore);
        }
        f.setJob(StationTask.WOOD);
        level(helper, f, 10);
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        Item[] axes = { Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE };
        int[] chop = new int[axes.length];
        for (int i = 0; i < axes.length; i++) {
            hold(f, axes[i]);
            chop[i] = f.workTicksFor(log);
        }
        f.setJob(StationTask.FARM);
        level(helper, f, 10);
        Item[] hoes = { Items.AIR, Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE };
        int[] farm = new int[hoes.length];
        for (int i = 0; i < hoes.length; i++) {
            hold(f, hoes[i]);
            farm[i] = f.actionPaceTicks("_hoe");
        }
        // A pick in the farmer's hand is no hoe: bare hands' pace at the field.
        hold(f, Items.IRON_PICKAXE);
        int wrong = f.actionPaceTicks("_hoe");
        Kit.log("pc02 at level 10: iron ore with wood/stone/iron/diamond picks " + java.util.Arrays.toString(mine)
            + " ticks; a log with wood/stone/iron axes " + java.util.Arrays.toString(chop)
            + "; a crop with no hoe/wood/stone/iron " + java.util.Arrays.toString(farm) + ", with a pick in hand " + wrong);
        for (int i = 1; i < mine.length; i++) helper.assertTrue(mine[i - 1] > mine[i], "each better pick digs quicker: " + java.util.Arrays.toString(mine));
        for (int i = 1; i < chop.length; i++) helper.assertTrue(chop[i - 1] > chop[i], "each better axe fells quicker: " + java.util.Arrays.toString(chop));
        for (int i = 1; i < farm.length; i++) helper.assertTrue(farm[i - 1] > farm[i], "no hoe slowest, then wood, stone, iron: " + java.util.Arrays.toString(farm));
        helper.assertTrue(wrong == farm[0], "and a pick is no hoe: " + wrong + " against " + farm[0]);
        helper.succeed();
    }

    // ============================================================ builders

    /**
     * A builder lays its blocks quicker with experience: at level nought, ten and thirty at its
     * trade (every block laid adds to it), and with the blocks it has laid in its life (its level
     * at building), whichever is the more. Kept to the hundredth of a tick, so every level counts.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pc03_builders")
    public static void pc03_builders(GameTestHelper helper) {
        VillageFolkEntity f = folk(helper, 123000, 40000, StationTask.MINE);
        int[] levels = { 0, 10, 30 };
        int[] fine = new int[3], ticks = new int[3];
        for (int i = 0; i < levels.length; i++) {
            level(helper, f, levels[i]);
            fine[i] = f.buildPaceHundredths();
            ticks[i] = f.buildPaceTicks();
        }
        // Back to nought at its trade, and five thousand blocks laid: the knack of building.
        level(helper, f, 0);
        int before = f.buildPaceHundredths();
        f.note(AssistantEntity.Deed.BLOCKS_BUILT, 5000);
        int after = f.buildPaceHundredths();
        Kit.log("pc03 a builder at levels 0/10/30: " + java.util.Arrays.toString(fine) + " hundredths of a tick a block ("
            + java.util.Arrays.toString(ticks) + " ticks); new to it " + before + ", after 5000 blocks laid (building level "
            + f.buildingLevel() + ", trade level " + f.veteranLevel() + ") " + after + "; build speed " + AssistantConfig.villageBuildSpeed() + "%");
        helper.assertTrue(fine[0] >= fine[1] && fine[1] >= fine[2] && ticks[0] >= ticks[1] && ticks[1] >= ticks[2],
            "a builder never lays slower for more experience: " + java.util.Arrays.toString(fine));
        if (AssistantConfig.villageBuildSpeed() == 100) {
            helper.assertTrue(fine[0] == 600 && fine[1] == 462 && fine[2] == 318,
                "six ticks a block for a new hand, 4.6 at level ten, 3.2 at thirty (three percent quicker a level): " + java.util.Arrays.toString(fine));
            helper.assertTrue(ticks[0] > ticks[1] && ticks[1] > ticks[2], "to the nearest tick, too: " + java.util.Arrays.toString(ticks));
            helper.assertTrue(f.buildingLevel() >= 1 && after < before, "laying blocks makes a quicker builder: " + before + " -> " + after);
        }
        helper.succeed();
    }

    // ============================================================ old age

    /**
     * The old are a little slower at their work than their young selves, and less so the more of
     * it they have done; but a lifetime at it shows: an old master is quicker than a young beginner
     * with the same tool.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pc04_old_hands")
    public static void pc04_old_hands(GameTestHelper helper) {
        for (int lv = 0; lv <= 50; lv++) {
            int age = VillageFolkEntity.oldAgePercentAt(lv);
            helper.assertTrue(age >= -10 && age <= -5, "old age takes five to ten percent, never more nor less: " + age + " at level " + lv);
            if (lv >= 10) {
                helper.assertTrue(AssistantEntity.experiencePercentAt(lv) + age > 0,
                    "an old hand of level " + lv + " is still quicker than a young beginner");
            }
        }
        VillageFolkEntity master = folk(helper, 124500, 40000, StationTask.MINE);
        level(helper, master, 30);
        hold(master, Items.STONE_PICKAXE);
        int masterYoung = master.actionPaceTicks();
        master.bornDaysAgo(25);                                   // sixty-two: old, and well short of its years
        int masterOld = master.actionPaceTicks();
        boolean old = master.isOld();
        VillageFolkEntity novice = VillageFolkSpawnerBlock.raise(helper.getLevel(), master.blockPosition().east(2), 0.0F);
        helper.assertTrue(novice != null, "a second folk");
        novice.setJob(StationTask.MINE);
        novice.plainPaceForTests(true);
        novice.bornDaysAgo(VillageFolkEntity.GROW_DAYS);
        novice.tradeXpForTests(StationTask.MINE, 0);
        hold(novice, Items.STONE_PICKAXE);
        int noviceYoung = novice.actionPaceTicks();
        novice.bornDaysAgo(25);
        int noviceOld = novice.actionPaceTicks();
        String card = master.paceLine();
        Kit.log("pc04 an action with a stone pick: the master (level 30) young " + masterYoung + ", old " + masterOld
            + " (" + master.ageYears() + ", old " + old + "); the beginner young " + noviceYoung + ", old " + noviceOld + "; " + card);
        helper.assertTrue(old, "sixty-two is old");
        helper.assertTrue(masterOld > masterYoung, "the old master is a little slower than it was young: " + masterOld + " against " + masterYoung);
        helper.assertTrue(masterOld < noviceYoung, "and still quicker than a young beginner: " + masterOld + " against " + noviceYoung);
        helper.assertTrue(noviceOld > noviceYoung, "an old beginner is slower than a young one: " + noviceOld + " against " + noviceYoung);
        helper.assertTrue(card.contains("its years (−5%)"), "its card says what its years cost it: " + card);
        helper.succeed();
    }

    // ============================================================ the card, and the carriers

    /**
     * The folk's card (its About page) says how quick it is and why: its level, its tool and what
     * the tool is worth against wood. And a carrier's work is the walking: an old hand at it walks
     * a little quicker, 5% at level twenty.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pc05_card_and_stride")
    public static void pc05_card_and_stride(GameTestHelper helper) {
        VillageFolkEntity f = folk(helper, 126000, 40000, StationTask.MINE);
        level(helper, f, 10);
        hold(f, Items.STONE_PICKAXE);
        String plain = f.paceLine();
        String card = FolkTalk.card(f);
        f.plainPaceForTests(false);
        String full = FolkTalk.card(f);
        Kit.log("pc05 the pace line: " + plain + " / on the card: " + card.replace('\n', ' ') + " / in full: " + full.replace('\n', ' '));
        helper.assertTrue(plain.startsWith("30% quicker than a new hand: level 10 (+30%, 3% a level), stone pickaxe (5.3 s a stroke against 6 for wood)"),
            "its pace, part by part: " + plain);
        helper.assertTrue(card.contains("Pace|10% quicker than a new hand"), "and it is on its card: " + card);
        helper.assertTrue(full.contains("Pace|") && full.contains("level 10 (+10%)") && full.contains("for wood"),
            "with everything else counted too: " + full);
        // The carrier's stride.
        f.plainPaceForTests(true);
        f.setJob(StationTask.HAUL);
        level(helper, f, 0);
        double slow = f.getAttributeValue(Attributes.MOVEMENT_SPEED);
        level(helper, f, 20);
        double quick = f.getAttributeValue(Attributes.MOVEMENT_SPEED);
        Kit.log("pc05 a carrier's stride: " + slow + " new to it, " + quick + " at level 20 (" + f.strideTenths() / 10.0 + "%)");
        helper.assertTrue(f.strideTenths() == 50 && quick > slow * 1.04 && quick < slow * 1.06,
            "a carrier of level twenty walks 5% quicker: " + slow + " -> " + quick);
        helper.succeed();
    }
}
