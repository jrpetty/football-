package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.StocksBlock;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Crime;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [crime] Petty crime and the watch (Crime, Mischief, Inquiry, Trial). A poor, unhappy, dishonest folk picks a purse
 * at the market, and the coins really move; a folk looking that way from across the square is on the books as a
 * witness, and names who it saw; the guard walks to the scene, asks about, searches, names the culprit by the clues,
 * and the council convicts; the victim is paid back out of the culprit's purse; a town of content folk has no crime
 * in four weeks of mornings; an innocent with nothing against it is acquitted and cleared; a vandal mends the window
 * it broke out of the stores at its own cost; and a second offender sits in the stocks the town puts up on the square.
 *
 * <p>Each test has its own batch and its own ground: x 1060000 to 1079999, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CrimeGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ helpers

    /** Level, open ground: a few blocks of earth under it and nothing over it. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        int y = Kit.surface(level, cx, cz).getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 12; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** The next day, at this hour. */
    private static void morning(ServerLevel level, long hour) {
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + hour);
        level.updateSkyBrightness();                  // a witness's eyes go by the light, and the light by the sky's
    }

    private static VillageFolkEntity folk(GameTestHelper helper, ServerLevel level, BlockPos at, String who) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
        helper.assertTrue(f != null, "raised " + who);
        return f;
    }

    /** Poor, low, greedy and not honest: nothing in its purse, a grump, and nothing to be cheerful about. */
    private static void poorAndLow(VillageFolkEntity f) {
        f.spend(f.purse());
        f.life().setTraitsForTests(Social.Trait.GRUMPY, Social.Trait.EASYGOING);
        Values.setForTests(f, Values.Value.WEALTH, 85);
        Crime.honestyForTests(f, 20);
        f.persona().setMood(18, List.of("hungry", "lonely"));
    }

    /** A folk with this many coins, kept where it is, looking this way. */
    private static void standing(VillageFolkEntity f, int coins, Direction looks) {
        f.setNoAi(true);
        f.spend(f.purse());
        if (coins > 0) f.earn(coins);
        f.setYRot(looks.toYRot());
        f.setYHeadRot(looks.toYRot());
        f.setYBodyRot(looks.toYRot());
    }

    /** One who notices things, kept where it is, looking this way. */
    private static void witness(VillageFolkEntity w, Direction looks) {
        standing(w, 5, looks);
        w.life().setTraitsForTests(Social.Trait.CURIOUS, Social.Trait.HARDWORKING);
        Crime.honestyForTests(w, 80);
    }

    private static void at(VillageFolkEntity f, BlockPos p, Direction looks) {
        f.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, looks.toYRot(), 0.0F);
        f.setYHeadRot(looks.toYRot());
        f.getNavigation().stop();
    }

    /** A chest of the stores by the heart, with these in it. */
    private static BlockPos stores(ServerLevel level, BlockPos heart, ItemStack... things) {
        BlockPos at = heart.offset(3, 0, 3);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        if (level.getBlockEntity(at) instanceof Container c) {
            for (int i = 0; i < things.length; i++) c.setItem(i, things[i]);
            c.setChanged();
        }
        return at;
    }

    private static int count(ServerLevel level, BlockPos chest, net.minecraft.world.item.Item item) {
        int n = 0;
        if (level.getBlockEntity(chest) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        }
        return n;
    }

    private static void logCase(String prefix, Crime.Case c) {
        Kit.log(prefix + " case #" + c.id + " " + c.title() + ": " + c.what() + "; " + c.stage() + "; investigator " + c.investigatorName()
            + "; accused " + c.accusedName() + "; verdict " + c.verdict() + "; sentence " + c.sentence());
        for (Crime.Witness w : c.witnesses()) Kit.log(prefix + "   witness " + w.name() + " certainty " + w.certainty());
        for (Crime.Statement s : c.statements()) Kit.log(prefix + "   said: " + s.text());
        for (Crime.Clue k : c.clues()) Kit.log(prefix + "   clue: " + k.text());
        for (String s : Crime.suspectsForTests(c)) Kit.log(prefix + "   suspect " + s);
        for (String n : c.notes()) Kit.log(prefix + "   note " + n);
    }

    // ============================================================ cr01: a purse picked at the market

    /**
     * A poor, unhappy, greedy and dishonest folk is tempted (its lot outweighs its honesty), and its plan for the day is
     * to pick a purse at the market. It walks up to a folk with ten coins standing at the market and, nobody near
     * enough to see, lifts a few: they leave the victim's purse and are in the culprit's, and the case is on the books.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "cr01_pickpocket")
    public static void cr01_pickpocket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1060000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 30);
        morning(level, 3000L);
        VillageFolkEntity victim = folk(helper, level, heart, "the victim");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(-7, 0, 0), "the culprit");
        VillageFolkEntity other = folk(helper, level, heart.offset(0, 0, 22), "a third folk");
        UUID village = victim.ownerId();
        Villages.builtAtForTests(village, "market", heart);
        standing(victim, 10, Direction.NORTH);
        standing(other, 4, Direction.NORTH);
        culprit.setJob(StationTask.FARM);
        poorAndLow(culprit);
        int tempted = Crime.temptationForTests(level, culprit);
        Kit.log("cr01 the culprit: purse " + culprit.purse() + ", honesty " + Crime.honestyOfForTests(culprit) + ", mood " + culprit.persona().mood()
            + ", temptation " + tempted + "; the victim's purse " + victim.purse());
        helper.assertTrue(tempted >= 15, "a poor, low, greedy, dishonest folk is tempted: " + tempted);
        helper.assertTrue(Crime.planForTests(level, culprit, Crime.Kind.PICKPOCKET, victim), "it means to pick the victim's purse");
        helper.onEachTick(() -> {
            long t = helper.getTick();
            List<Crime.Case> cases = Crime.casesForTests(village);
            if (!cases.isEmpty()) {
                Crime.Case c = cases.get(0);
                logCase("cr01", c);
                // Its purse just before the deed, as the case has it: a poor folk may have had a coin or two from the poor box
                // (or anybody else) since the test emptied it, and those are its own.
                int before = c.culpritPurseBefore();
                Kit.log("cr01 at tick " + t + ": the victim's purse " + victim.purse() + ", the culprit's " + before + " -> " + culprit.purse() + ", at " + c.place);
                helper.assertTrue(culprit.getUUID().equals(c.culprit()) && victim.getUUID().equals(c.victim()), "the culprit's deed, on the victim");
                helper.assertTrue(c.coins() >= 1 && c.coins() <= 5, "a few coins, never a ruinous sum: " + c.coins());
                helper.assertTrue(victim.purse() == 10 - c.coins(), "the coins left the victim's purse: " + victim.purse());
                helper.assertTrue(culprit.purse() == before + c.coins(), "and are in the culprit's: " + before + " -> " + culprit.purse());
                helper.assertTrue("the market".equals(c.place), "at the market: " + c.place);
                helper.succeed();
                return;
            }
            if (t % 100 == 0) {
                Kit.log("cr01 tick " + t + ": the culprit " + culprit.distanceTo(victim) + " from the victim, planning " + Crime.planningForTests(culprit)
                    + ", " + culprit.debugLine());
            }
            if (t > 1500) helper.fail("cr01 no purse picked in " + (t / 20) + " s: " + culprit.debugLine());
        });
    }

    // ============================================================ cr02: a witness

    /**
     * The same deed, with a curious folk across the square looking that way, further off than the culprit minds: the
     * case has it as a witness, how well it saw (the distance, the light, its attention), and who it thinks it was.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "cr02_witness")
    public static void cr02_witness(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1062000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 30);
        morning(level, 3000L);
        VillageFolkEntity victim = folk(helper, level, heart, "the victim");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(-7, 0, 0), "the culprit");
        VillageFolkEntity seer = folk(helper, level, heart.offset(8, 0, 0), "the witness");
        UUID village = victim.ownerId();
        Villages.builtAtForTests(village, "market", heart);
        standing(victim, 10, Direction.NORTH);
        witness(seer, Direction.WEST);
        culprit.setJob(StationTask.FARM);
        poorAndLow(culprit);
        helper.assertTrue(Crime.planForTests(level, culprit, Crime.Kind.PICKPOCKET, victim), "it means to pick the victim's purse");
        helper.onEachTick(() -> {
            long t = helper.getTick();
            List<Crime.Case> cases = Crime.casesForTests(village);
            if (!cases.isEmpty()) {
                Crime.Case c = cases.get(0);
                logCase("cr02", c);
                Crime.Witness w = null;
                for (Crime.Witness o : c.witnesses()) if (o.name().equals(seer.displayNameCap())) w = o;
                helper.assertTrue(w != null, "the folk across the square is on the books as a witness: " + c.witnesses().size());
                helper.assertTrue(w.certainty() >= 50, "it saw it fairly well, from " + String.format("%.1f", seer.distanceTo(culprit)) + ": " + w.certainty());
                helper.assertTrue(culprit.getUUID().equals(w.thinks()), "and thinks it was the culprit");
                helper.succeed();
                return;
            }
            if (t > 1500) helper.fail("cr02 no purse picked in " + (t / 20) + " s: " + culprit.debugLine());
        });
    }

    // ============================================================ cr03: the watch finds the culprit, and the council convicts

    /**
     * A purse picked in front of a witness and reported. The town's guard takes the case, walks to the scene, asks the
     * victim, the witness and whoever else was about (the culprit says it was elsewhere), searches the likeliest (a
     * purse fuller than its wages), and names the culprit; the council sits on the square, hears it, and convicts.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4800, batch = "cr03_investigation")
    public static void cr03_investigation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1064000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 36);
        morning(level, 2500L);
        VillageFolkEntity victim = folk(helper, level, heart, "the victim");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(-2, 0, 0), "the culprit");
        VillageFolkEntity seer = folk(helper, level, heart.offset(7, 0, 0), "the witness");
        VillageFolkEntity guard = folk(helper, level, heart.offset(0, 0, -18), "the guard");
        VillageFolkEntity fifth = folk(helper, level, heart.offset(0, 0, 18), "a fifth folk");
        UUID village = victim.ownerId();
        Villages.builtAtForTests(village, "market", heart);
        Crime.hurryForTests(true);
        guard.setJob(StationTask.GUARD);
        standing(victim, 10, Direction.NORTH);
        witness(seer, Direction.WEST);
        standing(fifth, 3, Direction.NORTH);
        culprit.setJob(StationTask.FARM);
        poorAndLow(culprit);
        int[] id = { -1 };
        helper.runAtTickTime(20, () -> {
            at(culprit, heart.offset(-1, 0, 0), Direction.EAST);
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            helper.assertTrue(c != null, "the purse is picked");
            Crime.reportForTests(level, c);
            id[0] = c.id;
            Kit.log("cr03 the deed: " + c.what() + "; " + c.witnesses().size() + " witness(es)");
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (id[0] < 0) return;
            Crime.Case c = Crime.caseForTests(id[0]);
            if (c == null) { helper.fail("cr03 the case is gone"); return; }
            if (t % 200 == 0) {
                Kit.log("cr03 tick " + t + ": " + c.stage() + ", investigator " + c.investigatorName() + "; the guard at "
                    + String.format("%.1f", guard.distanceTo(victim)) + " from the scene, " + guard.debugLine());
            }
            if (c.stage() == Crime.Stage.CONVICTED) {
                logCase("cr03", c);
                helper.assertTrue(guard.getUUID().equals(c.investigator()), "the guard took the case");
                helper.assertTrue(culprit.getUUID().equals(c.accused()), "the watch named the culprit: " + c.accusedName());
                helper.assertTrue(c.statements().size() >= 2, "it asked about: " + c.statements().size() + " statements");
                helper.assertTrue(!c.clues().isEmpty(), "and found something: " + c.clues().size() + " clues");
                helper.assertTrue(c.verdict().startsWith("guilty"), "the council convicted: " + c.verdict());
                helper.assertTrue(Crime.convictionsForTests(culprit) == 1, "on the culprit's record");
                helper.succeed();
                return;
            }
            if (c.stage() == Crime.Stage.UNSOLVED || c.stage() == Crime.Stage.ACQUITTED) {
                logCase("cr03", c);
                helper.fail("cr03 the case ended " + c.stage() + ": " + c.verdict());
            }
            if (t > 4600) {
                logCase("cr03", c);
                helper.fail("cr03 still " + c.stage() + " after " + (t / 20) + " s");
            }
        });
    }

    // ============================================================ cr04: the victim made whole

    /**
     * Convicted of picking a purse of ten, the culprit pays the coins back to the victim out of its own purse (the very
     * coins it took); the fine comes out of whatever is left in it, and what it cannot pay it owes the town.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "cr04_repaid")
    public static void cr04_repaid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1066000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 30);
        morning(level, 2500L);
        VillageFolkEntity victim = folk(helper, level, heart, "the victim");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(-2, 0, 0), "the culprit");
        VillageFolkEntity seer = folk(helper, level, heart.offset(6, 0, 0), "the witness");
        VillageFolkEntity guard = folk(helper, level, heart.offset(0, 0, -16), "the guard");
        UUID village = victim.ownerId();
        Crime.hurryForTests(true);
        guard.setJob(StationTask.GUARD);
        standing(victim, 10, Direction.NORTH);
        witness(seer, Direction.WEST);
        culprit.setJob(StationTask.FARM);
        poorAndLow(culprit);
        int[] id = { -1 }, took = { 0 };
        helper.runAtTickTime(20, () -> {
            at(culprit, heart.offset(-1, 0, 0), Direction.EAST);
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            helper.assertTrue(c != null, "the purse is picked");
            took[0] = c.coins();
            Kit.log("cr04 " + c.coins() + " taken: the victim has " + victim.purse() + ", the culprit " + culprit.purse());
            Crime.reportForTests(level, c);
            Crime.investigateNowForTests(level, c);
            logCase("cr04", c);
            helper.assertTrue(c.stage() == Crime.Stage.ACCUSED && culprit.getUUID().equals(c.accused()), "the watch names the culprit: " + c.stage());
            helper.assertTrue(Crime.trialNowForTests(level, Villages.get(village)), "the council sits");
            id[0] = c.id;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (id[0] < 0) return;
            Crime.Case c = Crime.caseForTests(id[0]);
            if (c == null) { helper.fail("cr04 the case is gone"); return; }
            if (c.stage() == Crime.Stage.CONVICTED) {
                logCase("cr04", c);
                Kit.log("cr04 after: the victim's purse " + victim.purse() + ", the culprit's " + culprit.purse() + "; fined " + c.fine() + ", "
                    + c.finePaid() + " of it paid, " + Crime.owesTownForTests(culprit) + " owed to the town, " + Crime.owesForTests(culprit) + " owed in all");
                helper.assertTrue(victim.purse() == 10, "the victim has its ten coins again: " + victim.purse());
                helper.assertTrue(Crime.owesForTests(culprit) == Crime.owesTownForTests(culprit), "nothing still owed to the victim");
                // Whatever else was in its purse (the poor box's coin, say) went to the fine first; what it could not pay is owed.
                helper.assertTrue(c.fine() >= 2 && c.finePaid() + Crime.owesTownForTests(culprit) == c.fine(),
                    "the fine paid out of its purse, the rest owed: fined " + c.fine() + ", paid " + c.finePaid() + ", owed " + Crime.owesTownForTests(culprit));
                helper.assertTrue(Crime.repaidForTests(victim), "the victim knows it was paid back");
                helper.succeed();
                return;
            }
            if (!c.stage().open()) {
                logCase("cr04", c);
                helper.fail("cr04 the case ended " + c.stage());
            }
            if (t > 2300) helper.fail("cr04 still " + c.stage() + " after " + (t / 20) + " s");
        });
    }

    // ============================================================ cr05: a contented town

    /**
     * Eight folk, each with a purse of twenty and in good spirits, their natures as they come. Four weeks of mornings'
     * temptations, and nobody so much as thinks of it: no plan, no case, and nobody's lot outweighs its honesty.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "cr05_contented_town")
    public static void cr05_contented_town(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1068000;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        BlockPos heart = flat(level, x, Z, 24);
        morning(level, 3000L);
        List<VillageFolkEntity> town = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            VillageFolkEntity f = folk(helper, level, heart.offset((i % 4) * 4 - 6, 0, (i / 4) * 4 - 2), "folk " + i);
            standing(f, 20, Direction.NORTH);
            town.add(f);
        }
        UUID village = town.get(0).ownerId();
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            int plans = 0, most = Integer.MIN_VALUE;
            for (int d = 0; d < 28; d++) {
                for (VillageFolkEntity f : town) {
                    f.persona().setMood(75, List.of("friends", "fed"));
                    most = Math.max(most, Crime.temptationForTests(level, f));
                }
                plans += Crime.dailyForTests(level, Villages.get(village), day + d);
            }
            // The same measure is live: one of them made poor, low and dishonest is tempted at once.
            VillageFolkEntity sour = town.get(5);
            sour.spend(sour.purse());
            sour.persona().setMood(15, List.of("hungry"));
            Crime.honestyForTests(sour, 15);
            int soured = Crime.temptationForTests(level, sour);
            Kit.log("cr05 four weeks: " + plans + " plans, " + Crime.casesForTests(village).size() + " cases; the most anybody was tempted "
                + most + "; one made poor and low: " + soured);
            helper.assertTrue(plans == 0 && Crime.plansForTests(village) == 0, "no plans in four weeks: " + plans);
            helper.assertTrue(Crime.casesForTests(village).isEmpty(), "no crime");
            helper.assertTrue(most < 15, "nobody's lot outweighs its honesty: " + most);
            helper.assertTrue(soured >= 15, "(and the measure is live: a poor, low, dishonest one is tempted: " + soured + ")");
            helper.succeed();
        });
    }

    // ============================================================ cr06: an innocent acquitted

    /**
     * A purse is picked with nobody watching, and a folk forty blocks off at the time, with nothing whatever against it,
     * is put before the council for it. It is found not guilty and cleared: no conviction on its record, and (with no
     * watch to look again) the case closes as an acquittal.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2000, batch = "cr06_innocent_acquitted")
    public static void cr06_innocent_acquitted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1070000;
        Kit.hold(level, x, Z, 56);
        Kit.prepare(level, x, Z, 56);
        BlockPos heart = flat(level, x, Z, 46);
        morning(level, 2500L);
        VillageFolkEntity victim = folk(helper, level, heart, "the victim");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(-2, 0, 0), "the culprit");
        VillageFolkEntity innocent = folk(helper, level, heart.offset(40, 0, 0), "the innocent");
        UUID village = victim.ownerId();
        Crime.hurryForTests(true);
        standing(victim, 10, Direction.NORTH);
        poorAndLow(culprit);
        Crime.honestyForTests(innocent, 70);
        int[] id = { -1 };
        helper.runAtTickTime(20, () -> {
            at(culprit, heart.offset(-1, 0, 0), Direction.EAST);
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            helper.assertTrue(c != null, "the purse is picked");
            Crime.reportForTests(level, c);
            Crime.accuseForTests(c, innocent);
            helper.assertTrue(Crime.trialNowForTests(level, Villages.get(village)), "the council sits on the innocent");
            id[0] = c.id;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (id[0] < 0) return;
            Crime.Case c = Crime.caseForTests(id[0]);
            if (c == null) { helper.fail("cr06 the case is gone"); return; }
            if (c.cleared().contains(innocent.getUUID())) {
                logCase("cr06", c);
                helper.assertTrue(c.stage() == Crime.Stage.ACQUITTED, "acquitted: " + c.stage());
                helper.assertTrue(Crime.convictionsForTests(innocent) == 0, "nothing on its record");
                helper.assertTrue(Crime.clearedForTests(innocent), "and it is cleared");
                helper.succeed();
                return;
            }
            if (c.stage() == Crime.Stage.CONVICTED) {
                logCase("cr06", c);
                helper.fail("cr06 the innocent was convicted on nothing");
            }
            if (t > 1900) helper.fail("cr06 still " + c.stage() + " after " + (t / 20) + " s");
        });
    }

    // ============================================================ cr07: the vandal mends the window

    /**
     * A vandal breaks a window in a wall by the square (really breaks it), seen by a folk close by. Named and convicted,
     * a first offender's sentence for damage is community work: it walks to the window, puts a pane from the stores back
     * in it, and pays for the pane out of its purse.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "cr07_vandal_mends")
    public static void cr07_vandal_mends(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1072000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 30);
        morning(level, 2500L);
        // A short wall with a window in it, east of the heart.
        BlockPos pane = heart.offset(6, 1, 0);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = 0; dy <= 1; dy++) level.setBlock(heart.offset(6, dy, dz), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        }
        level.setBlock(pane, Blocks.GLASS_PANE.defaultBlockState(), 3);
        BlockPos chest = stores(level, heart, new ItemStack(Items.GLASS_PANE, 4));
        VillageFolkEntity victim = folk(helper, level, heart.offset(-6, 0, -6), "a townsfolk");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(4, 0, 0), "the vandal");
        VillageFolkEntity seer = folk(helper, level, heart.offset(-2, 0, 0), "the witness");
        VillageFolkEntity guard = folk(helper, level, heart.offset(0, 0, -14), "the guard");
        UUID village = victim.ownerId();
        Crime.hurryForTests(true);
        guard.setJob(StationTask.GUARD);
        standing(victim, 3, Direction.NORTH);
        witness(seer, Direction.EAST);
        poorAndLow(culprit);
        culprit.earn(5);
        int[] id = { -1 }, purse = { 0 };
        helper.runAtTickTime(20, () -> {
            at(culprit, heart.offset(4, 0, 0), Direction.EAST);
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.VANDALISM, null, pane);
            helper.assertTrue(c != null, "the window is broken");
            helper.assertTrue(level.getBlockState(pane).isAir(), "really broken: " + level.getBlockState(pane));
            Crime.reportForTests(level, c);
            Crime.investigateNowForTests(level, c);
            logCase("cr07", c);
            helper.assertTrue(c.stage() == Crime.Stage.ACCUSED && culprit.getUUID().equals(c.accused()), "the vandal is named: " + c.stage());
            helper.assertTrue(Crime.trialNowForTests(level, Villages.get(village)), "the council sits");
            purse[0] = culprit.purse();
            id[0] = c.id;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (id[0] < 0) return;
            Crime.Case c = Crime.caseForTests(id[0]);
            if (c == null) { helper.fail("cr07 the case is gone"); return; }
            if (t % 200 == 0) Kit.log("cr07 tick " + t + ": " + c.stage() + ", sentence '" + Crime.sentenceForTests(culprit) + "', the vandal "
                + String.format("%.1f", Math.sqrt(culprit.distanceToSqr(pane.getX() + 0.5, pane.getY(), pane.getZ() + 0.5))) + " from the window, " + culprit.debugLine());
            if (c.stage() != Crime.Stage.CONVICTED) {
                if (!c.stage().open()) { logCase("cr07", c); helper.fail("cr07 the case ended " + c.stage()); }
                if (t > 2300) helper.fail("cr07 still " + c.stage());
                return;
            }
            if (Crime.mendedForTests(c)) {
                logCase("cr07", c);
                Kit.log("cr07 mended at tick " + t + ": " + level.getBlockState(pane) + "; panes left in the stores " + count(level, chest, Items.GLASS_PANE)
                    + "; the vandal's purse " + purse[0] + " -> " + culprit.purse());
                helper.assertTrue(level.getBlockState(pane).is(Blocks.GLASS_PANE), "the window is back: " + level.getBlockState(pane));
                helper.assertTrue(count(level, chest, Items.GLASS_PANE) == 3, "out of the stores: " + count(level, chest, Items.GLASS_PANE));
                helper.assertTrue(culprit.purse() < purse[0] || Crime.owesForTests(culprit) > 0, "at the vandal's cost");
                helper.succeed();
                return;
            }
            if (t > 2300) helper.fail("cr07 convicted (" + c.sentence() + ") but the window not mended: " + culprit.debugLine());
        });
    }

    // ============================================================ cr08: the stocks

    /**
     * A folk already convicted once picks a purse, in front of a witness. Convicted again, it is sentenced to the stocks:
     * the town puts a pair up on the square out of its stores (three planks, from whichever chest has them, and the two
     * logs from the only chest that has any), and it sits in them.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "cr08_stocks")
    public static void cr08_stocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1074000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 30);
        morning(level, 2500L);
        BlockPos chest = stores(level, heart, new ItemStack(Items.OAK_LOG, 4), new ItemStack(Items.OAK_PLANKS, 4));
        VillageFolkEntity victim = folk(helper, level, heart, "the victim");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(-2, 0, 0), "the culprit");
        VillageFolkEntity seer = folk(helper, level, heart.offset(6, 0, 0), "the witness");
        VillageFolkEntity guard = folk(helper, level, heart.offset(0, 0, -16), "the guard");
        UUID village = victim.ownerId();
        Crime.hurryForTests(true);
        guard.setJob(StationTask.GUARD);
        standing(victim, 10, Direction.NORTH);
        witness(seer, Direction.WEST);
        culprit.setJob(StationTask.FARM);
        poorAndLow(culprit);
        Crime.recordForTests(culprit, 1);
        int[] id = { -1 };
        helper.runAtTickTime(20, () -> {
            at(culprit, heart.offset(-1, 0, 0), Direction.EAST);
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            helper.assertTrue(c != null, "the purse is picked");
            Crime.reportForTests(level, c);
            Crime.investigateNowForTests(level, c);
            helper.assertTrue(c.stage() == Crime.Stage.ACCUSED, "the culprit is named: " + c.stage());
            helper.assertTrue(Crime.trialNowForTests(level, Villages.get(village)), "the council sits");
            id[0] = c.id;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (id[0] < 0) return;
            Crime.Case c = Crime.caseForTests(id[0]);
            if (c == null) { helper.fail("cr08 the case is gone"); return; }
            if (c.stage() != Crime.Stage.CONVICTED) {
                if (!c.stage().open()) { logCase("cr08", c); helper.fail("cr08 the case ended " + c.stage()); }
                if (t > 2300) helper.fail("cr08 still " + c.stage());
                return;
            }
            BlockPos stocks = Crime.stocksForTests(level, Villages.get(village));
            if (t % 100 == 0) Kit.log("cr08 tick " + t + ": sentence '" + Crime.sentenceForTests(culprit) + "', the stocks at " + stocks + ", " + culprit.debugLine());
            if (stocks != null && Crime.inStocksForTests(culprit)) {
                logCase("cr08", c);
                Villages.Village v = Villages.get(village);
                Kit.log("cr08 sat in the stocks at tick " + t + ": " + level.getBlockState(stocks) + ", paid with " + Crime.stocksPaidForTests(v)
                    + "; our chest now holds " + count(level, chest, Items.OAK_LOG) + " logs and " + count(level, chest, Items.OAK_PLANKS) + " planks, the stores "
                    + Crime.storesForTests(level, v, Items.OAK_LOG) + " logs and " + Crime.storesForTests(level, v, Items.OAK_PLANKS) + " planks");
                helper.assertTrue(level.getBlockState(stocks).getBlock() instanceof StocksBlock, "the stocks stand on the square");
                helper.assertTrue("stocks".equals(Crime.sentenceForTests(culprit)), "a second offence: the stocks");
                // The planks come out of whichever of the stores' chests the town reaches first (a founding chest has plenty);
                // the town's only logs are the two in ours, so it is ours that is two logs the lighter.
                helper.assertTrue("three planks and two logs".equals(Crime.stocksPaidForTests(v)), "made out of the stores: " + Crime.stocksPaidForTests(v));
                helper.assertTrue(count(level, chest, Items.OAK_LOG) == 2, "two of the stores' logs went into them: " + count(level, chest, Items.OAK_LOG) + " left");
                helper.succeed();
                return;
            }
            if (t > 2300) helper.fail("cr08 convicted (" + c.sentence() + ") but not in the stocks: " + culprit.debugLine());
        });
    }
}
