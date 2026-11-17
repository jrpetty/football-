package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.Interviews;
import com.jrpetty.mcassistant.entity.LabourMarket;
import com.jrpetty.mcassistant.entity.MarketMaker;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [labour] The labour market (economy batch 2): openings and offers, workers choosing, wages set by the market, lay-offs
 * and relief, apprentices, a founder outbidding for a skilled hand, essentials always staffed, kept with the world, and
 * ten days of a town under it.
 *
 * <p>Each on its own ground, x 3000000 to 3099999 (2000 apart), z 90000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class LabourGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    // ============================================================ the ground

    /** A town of so many folk at these trades (NONE: out of work), every purse at {@code purse}; the market open, the Stone Age. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int purse, StationTask... trades) {
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        Enterprise.resetForTests();
        Enterprise.autoForTests(false);
        LabourMarket.onPaperForTests(true);
        level.setDayTime(8 * 24000L + 7000L);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 6 + (i % 6) * 2, Z - 4 + (i / 6) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setJob(trades[i]);
            if (trades[i] == StationTask.NONE) f.setWorkZone(null);
            f.keepTradeForTests();
            f.spend(f.purse());
            f.earn(purse);
            Values.setForTests(f, Values.Value.TRADITION, 100);
            Values.setForTests(f, Values.Value.WEALTH, 0);
            Values.setForTests(f, Values.Value.PROGRESS, 0);
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        Ledger.addCoins(id, 300);
        MarketMaker.openForTests(level, Villages.get(id));
        return folk;
    }

    private static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    private static void nextDay(ServerLevel level) {
        level.setDayTime((day(level) + 1) * 24000L + 7000L);
    }

    private static void logOpenings(String tag, UUID id) {
        for (Object[] o : LabourMarket.openingsForTests(id)) {
            Kit.log("[VT] " + tag + " opening #" + o[0] + " " + o[1] + " " + o[2] + " x" + o[3] + " at " + o[4] + "c, " + o[5] + " waiting, " + o[6]
                + (Boolean.TRUE.equals(o[7]) ? " essential" : "") + " by " + o[8]);
        }
    }

    private static void logNews(String tag, UUID id) {
        for (String n : LabourMarket.newsForTests(id)) Kit.log("[VT] " + tag + " news " + n);
    }

    @SuppressWarnings("unchecked")
    private static Object[] openingOf(UUID id, String employer) {
        Object[] got = null;
        for (Object[] o : LabourMarket.openingsForTests(id)) if (employer.equals(o[1])) got = o;
        return got;
    }

    private static String terms(VillageFolkEntity f) {
        Object[] t = LabourMarket.termsForTests(f);
        return t == null ? "none" : t[0] + " " + t[1] + String.format(Locale.ROOT, " x%.2f", (Double) t[2]) + (Boolean.TRUE.equals(t[3]) ? " apprentice" : "")
            + " since " + t[4] + (((Long) t[5]) >= 0 ? " leaving " + t[5] : "");
    }

    private static int coinCount(UUID id) {
        int n = Ledger.coins(id);
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f) n += f.purse();
        return n;
    }

    // ============================================================ lab01 offers and applications

    /**
     * A firm short of a hand posts an opening with its wage offer (never under the living wage); one out of work applies,
     * and the next morning's decision takes it on: on the firm's books, on its terms, the chronicle told.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lab01_offers")
    public static void lab01_offers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3000000;
        List<VillageFolkEntity> folk = town(helper, level, x, 30, StationTask.SMITH, StationTask.GUARD, StationTask.GUARD, StationTask.NONE,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity bram = folk.get(0), dell = folk.get(3);
        bram.tradeXpForTests(StationTask.SMITH, 20000);
        dell.tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(4));
        UUID id = bram.ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        LabourMarket.morningNow(level, v);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", bram);
        ok.that(b != null, "the toolshop opened");
        if (b == null) { helper.fail(ok.summary()); return; }
        Kit.log("[VT] lab01 hands after founding: " + Enterprise.hands(id, b).size() + "; dell " + LabourMarket.employerForTests(dell));
        String posted = LabourMarket.post(level, id, b, 0);
        Kit.log("[VT] lab01 " + posted);
        logOpenings("lab01", id);
        Object[] o = openingOf(id, "firm:" + b.id);
        ok.that(o != null, "the firm's opening is on the Jobs page");
        if (o != null) {
            ok.that((Integer) o[4] >= LabourMarket.minimumWage(id), "its wage offer " + o[4] + " at least the living wage " + LabourMarket.minimumWage(id));
            ok.that((Integer) o[5] >= 1, "somebody applied: " + o[5]);
        }
        nextDay(level);
        LabourMarket.morningNow(level, v);
        logOpenings("lab01", id);
        logNews("lab01", id);
        Kit.log("[VT] lab01 dell " + terms(dell) + " at " + dell.stationTask());
        ok.that(("firm:" + b.id).equals(LabourMarket.employerForTests(dell)), "the one out of work was taken on at the firm: " + LabourMarket.employerForTests(dell));
        ok.that(Enterprise.hands(id, b).contains(dell), "on the firm's books");
        Object[] t = LabourMarket.termsForTests(dell);
        ok.that(t != null && ("firm:" + b.id).equals(t[0]), "on the firm's terms: " + terms(dell));
        boolean told = false;
        for (Villages.News n : Villages.news(id)) told |= n.text().contains("taken on at " + b.name);
        ok.that(told, "the chronicle has it");
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ lab02 a better offer

    /**
     * A guard on the watch's terms sees a firm offering much more for a hand of its skill: it applies, is taken, gives a
     * day's notice (the chronicle has it), and the next morning moves to the firm at the better wage.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lab02_better")
    public static void lab02_better(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3002000;
        List<VillageFolkEntity> folk = town(helper, level, x, 30, StationTask.SMITH, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD,
            StationTask.GUARD, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity bram = folk.get(0), ash = folk.get(1);
        bram.tradeXpForTests(StationTask.SMITH, 20000);
        ash.tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(8));
        ash.tradeXpForTests(StationTask.GUARD, AssistantEntity.xpForLevel(3));
        UUID id = bram.ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        LabourMarket.morningNow(level, v);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", bram);
        ok.that(b != null, "the toolshop opened");
        if (b == null) { helper.fail(ok.summary()); return; }
        int before = LabourMarket.wageForTests(ash);
        String was = LabourMarket.employerForTests(ash);
        Kit.log("[VT] lab02 ash " + terms(ash) + " paid " + before);
        Kit.log("[VT] lab02 " + LabourMarket.post(level, id, b, 4 * before));
        logOpenings("lab02", id);
        LabourMarket.morningNow(level, v);
        logOpenings("lab02", id);
        logNews("lab02", id);
        Object[] t = LabourMarket.termsForTests(ash);
        Kit.log("[VT] lab02 ash after the morning " + terms(ash));
        ok.that(t != null && (Long) t[5] >= 0, "the guard gave notice: " + terms(ash));
        ok.that(was.equals(LabourMarket.employerForTests(ash)), "and works out its day where it was: " + LabourMarket.employerForTests(ash));
        boolean told = false;
        for (Villages.News n : Villages.news(id)) told |= n.text().contains("gave notice");
        ok.that(told, "the chronicle has the notice");
        nextDay(level);
        LabourMarket.morningNow(level, v);
        logNews("lab02", id);
        int after = LabourMarket.wageForTests(ash);
        Kit.log("[VT] lab02 ash moved: " + terms(ash) + " paid " + after + " (was " + before + ")");
        ok.that(("firm:" + b.id).equals(LabourMarket.employerForTests(ash)), "it moved to the firm: " + LabourMarket.employerForTests(ash));
        ok.that(ash.stationTask() == StationTask.SMITH, "at the firm's trade: " + ash.stationTask());
        ok.that(after > before, "for more pay: " + before + " -> " + after);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ lab03 wages by supply and demand

    /**
     * An opening nobody can take: the offer goes up each morning it stands empty (to what the firm can afford). Then a
     * queue of applicants: the offer comes down. Set as low as it will go, the wage paid is never under the living wage, and
     * with the council's minimum wage set over it, never under that.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lab03_wages")
    public static void lab03_wages(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3004000;
        List<VillageFolkEntity> folk = town(helper, level, x, 30, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.GUARD);
        VillageFolkEntity bram = folk.get(0);
        bram.tradeXpForTests(StationTask.SMITH, 20000);
        UUID id = bram.ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        LabourMarket.morningNow(level, v);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", bram);
        ok.that(b != null, "the toolshop opened");
        if (b == null) { helper.fail(ok.summary()); return; }
        String emp = "firm:" + b.id;
        LabourMarket.firmShortForTests(id, b);
        LabourMarket.morningNow(level, v);
        double m0 = LabourMarket.offerForTests(id, emp, StationTask.SMITH);
        Object[] o0 = openingOf(id, emp);
        Kit.log("[VT] lab03 day 0 offer " + m0 + " " + (o0 == null ? "-" : o0[4] + "c, " + o0[5] + " waiting"));
        double m = m0;
        for (int d = 1; d <= 3; d++) {
            nextDay(level);
            LabourMarket.morningNow(level, v);
            m = LabourMarket.offerForTests(id, emp, StationTask.SMITH);
            Object[] o = openingOf(id, emp);
            Kit.log("[VT] lab03 day " + d + " offer " + String.format(Locale.ROOT, "%.3f", m) + " " + (o == null ? "-" : o[4] + "c, " + o[5] + " waiting, " + o[6]));
        }
        ok.that(m > m0 + 0.1, String.format(Locale.ROOT, "the offer rose while nobody came: %.3f -> %.3f", m0, m));
        // A queue: three out of work, all smiths, apply for the one place.
        List<VillageFolkEntity> queue = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 4 + i * 2, Z + 6), 0.0F);
            f.ensurePersona();
            f.setJob(StationTask.NONE);
            f.setWorkZone(null);
            f.tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(6 + i));
            queue.add(f);
        }
        nextDay(level);
        double beforeQueue = LabourMarket.offerForTests(id, emp, StationTask.SMITH);
        LabourMarket.morningNow(level, v);
        double afterQueue = LabourMarket.offerForTests(id, emp, StationTask.SMITH);
        logOpenings("lab03", id);
        logNews("lab03", id);
        ok.that(afterQueue < beforeQueue, String.format(Locale.ROOT, "with a queue at the door the offer came down: %.3f -> %.3f", beforeQueue, afterQueue));
        VillageFolkEntity taken = null;
        for (VillageFolkEntity f : queue) if (emp.equals(LabourMarket.employerForTests(f))) taken = f;
        ok.that(taken != null && taken == queue.get(2), "the most skilled of the queue was taken: " + (taken == null ? "nobody" : taken.displayNameCap()));
        // The floor: the offer as low as it goes, and then the council's minimum wage.
        if (taken != null) {
            LabourMarket.termsForTests(taken, 0.05, true, day(level));
            int paid = LabourMarket.wageForTests(taken);
            ok.that(paid >= LabourMarket.minimumWage(id), "never under the living wage: paid " + paid + ", living " + LabourMarket.minimumWage(id));
            int min = LabourMarket.minimumWage(id) + 3;
            Kit.log("[VT] lab03 " + LabourMarket.setMinimum(id, min, "the test"));
            int paid2 = LabourMarket.wageForTests(taken);
            ok.that(paid2 >= min, "never under the council's minimum wage: paid " + paid2 + ", minimum " + min);
        }
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ lab04 lay-off and relief

    /**
     * A firm losing money lays off its newest hand: off its books and its trade, out of work, the chronicle told, its spirits
     * down; it draws poor relief from the treasury the same morning (coin moved, none made: the town's coin the same).
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lab04_layoff")
    public static void lab04_layoff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3006000;
        List<VillageFolkEntity> folk = town(helper, level, x, 0, StationTask.SMITH, StationTask.SMITH, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.GUARD, StationTask.GUARD);
        VillageFolkEntity bram = folk.get(0), cole = folk.get(1);
        bram.tradeXpForTests(StationTask.SMITH, 20000);
        UUID id = bram.ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        Business b = Enterprise.foundForTests(level, v, "toolsmith", bram);
        ok.that(b != null, "the toolshop opened");
        if (b == null) { helper.fail(ok.summary()); return; }
        if (!Enterprise.hands(id, b).contains(cole)) Enterprise.staffForTests(id, b, cole);
        LabourMarket.termsForTests(cole, 1.0, false, day(level) - 2);
        CompoundTag r = Enterprise.recordForTests(id, b);
        r.putString("status", "struggling");
        nextDay(level);
        int coinsBefore = coinCount(id);
        int purse0 = cole.purse();
        LabourMarket.morningNow(level, v);
        logNews("lab04", id);
        logOpenings("lab04", id);
        Kit.log("[VT] lab04 cole " + cole.stationTask() + " " + terms(cole) + " purse " + cole.purse());
        boolean told = false;
        for (Villages.News n : Villages.news(id)) told |= n.text().contains("laid off by " + b.name);
        ok.that(told, "the chronicle has the lay-off");
        ok.that(!Enterprise.hands(id, b).contains(cole), "off the firm's books");
        boolean out = LabourMarket.outForTests(cole);
        ok.that(out || !LabourMarket.employerForTests(cole).equals("firm:" + b.id), "out of work (or taken on elsewhere at once): " + cole.stationTask());
        if (out) ok.that(cole.purse() > purse0, "it drew poor relief: purse " + purse0 + " -> " + cole.purse());
        ok.that(coinCount(id) == coinsBefore, "coin moved, none made: " + coinsBefore + " -> " + coinCount(id));
        ok.that(cole.persona().mood() < 100, "its spirits");
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ lab05 an apprentice

    /**
     * A hand new to the trade taken on as an apprentice is paid less than a journeyman on the same terms; working beside the
     * firm's master it is taught each morning, and at level five it is a journeyman on the full offer, paid more.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lab05_apprentice")
    public static void lab05_apprentice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3008000;
        List<VillageFolkEntity> folk = town(helper, level, x, 20, StationTask.SMITH, StationTask.SMITH, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.GUARD);
        VillageFolkEntity bram = folk.get(0), pip = folk.get(1);
        bram.tradeXpForTests(StationTask.SMITH, 20000);
        pip.tradeXpForTests(StationTask.SMITH, 0);
        UUID id = bram.ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        Business b = Enterprise.foundForTests(level, v, "toolsmith", bram);
        ok.that(b != null, "the toolshop opened");
        if (b == null) { helper.fail(ok.summary()); return; }
        if (!Enterprise.hands(id, b).contains(pip)) Enterprise.staffForTests(id, b, pip);
        LabourMarket.termsForTests(pip, 2.0, true, day(level));
        int asApprentice = LabourMarket.wageForTests(pip);
        LabourMarket.termsForTests(pip, 2.0, false, day(level));
        int asHand = LabourMarket.wageForTests(pip);
        LabourMarket.termsForTests(pip, 2.0, true, day(level));
        Kit.log("[VT] lab05 apprentice " + asApprentice + " against " + asHand + " on the same terms; living " + LabourMarket.minimumWage(id));
        ok.that(asApprentice < asHand, "an apprentice is paid less: " + asApprentice + " against " + asHand);
        int xp0 = pip.tradeXpOfForTests(StationTask.SMITH);
        nextDay(level);
        LabourMarket.morningNow(level, v);
        int xp1 = pip.tradeXpOfForTests(StationTask.SMITH);
        ok.that(xp1 > xp0, "taught beside the master: " + xp0 + " -> " + xp1);
        pip.tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(5) - 1);
        nextDay(level);
        LabourMarket.morningNow(level, v);
        logNews("lab05", id);
        Object[] t = LabourMarket.termsForTests(pip);
        int grown = LabourMarket.wageForTests(pip);
        Kit.log("[VT] lab05 pip " + terms(pip) + " level " + pip.tradeLevel(StationTask.SMITH) + " paid " + grown);
        ok.that(t != null && !Boolean.TRUE.equals(t[3]), "its apprenticeship done: " + terms(pip));
        ok.that(grown > asApprentice, "paid more as it grew: " + asApprentice + " -> " + grown);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ lab06 a founder outbids

    /**
     * A would-be clothier without the skill, and the only skilled tailor in town at work for another firm: the founder
     * takes it on by outbidding its employer (more than it was paid), off the other firm's books, which posts an opening
     * for its place.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lab06_outbid")
    public static void lab06_outbid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3010000;
        List<VillageFolkEntity> folk = town(helper, level, x, 30, StationTask.SMITH, StationTask.SMITH, StationTask.SMITH, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.GUARD, StationTask.GUARD);
        VillageFolkEntity ox = folk.get(0), bram = folk.get(1), wren = folk.get(2);
        ox.tradeXpForTests(StationTask.SMITH, 40000);
        bram.tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(15));
        wren.tradeXpForTests(StationTask.SMITH, 0);
        UUID id = ox.ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        LabourMarket.morningNow(level, v);
        Business first = Enterprise.foundForTests(level, v, "toolsmith", ox);
        ok.that(first != null, "the first toolshop opened");
        if (first == null) { helper.fail(ok.summary()); return; }
        if (!Enterprise.hands(id, first).contains(bram)) Enterprise.staffForTests(id, first, bram);
        LabourMarket.termsForTests(bram, 1.0, false, day(level));
        int before = LabourMarket.wageForTests(bram);
        Kit.log("[VT] lab06 bram " + terms(bram) + " paid " + before + "; wren level " + wren.tradeLevel(StationTask.SMITH));
        Business second = Enterprise.foundForTests(level, v, "armourer", wren);
        if (second == null) second = Enterprise.foundForTests(level, v, "toolsmith", wren);
        ok.that(second != null, "the unskilled founder's firm opened");
        if (second == null) { helper.fail(ok.summary()); return; }
        logNews("lab06", id);
        int after = LabourMarket.wageForTests(bram);
        Kit.log("[VT] lab06 bram now " + terms(bram) + " paid " + after);
        ok.that(Enterprise.hands(id, second).contains(bram), "the skilled hand works for the new firm");
        ok.that(!Enterprise.hands(id, first).contains(bram), "off its old firm's books");
        ok.that(after > before, "outbid: " + before + " -> " + after);
        nextDay(level);
        LabourMarket.morningNow(level, v);
        logOpenings("lab06", id);
        Object[] o = openingOf(id, "firm:" + first.id);
        ok.that(o != null, "the old firm posted an opening for its place");
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ lab07 essentials

    /**
     * A town with nobody in its fields: the essential opening is posted, pays more out of the budget, and by the next
     * morning somebody is at the fields (taken on, or called by the elder): no town deadlocks for want of farmers.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lab07_essentials")
    public static void lab07_essentials(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3012000;
        List<VillageFolkEntity> folk = town(helper, level, x, 30, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD,
            StationTask.GUARD, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        LabourMarket.morningNow(level, v);
        logOpenings("lab07", id);
        Object[] o = openingOf(id, "town:FARM");
        ok.that(o != null && Boolean.TRUE.equals(o[7]), "the fields' opening, essential: " + (o == null ? "none" : o[7]));
        int farmers = 0;
        for (int d = 1; d <= 2 && farmers == 0; d++) {
            nextDay(level);
            LabourMarket.morningNow(level, v);
            logOpenings("lab07", id);
            logNews("lab07", id);
            farmers = 0;
            for (VillageFolkEntity f : folk) if (f.stationTask() == StationTask.FARM) farmers++;
        }
        ok.that(farmers >= 1, "somebody is at the fields: " + farmers);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ lab08 kept with the world

    /** The hands' terms, the openings and the minimum wage written out with the world and read back the same. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lab08_saved")
    public static void lab08_saved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3014000;
        List<VillageFolkEntity> folk = town(helper, level, x, 30, StationTask.SMITH, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD,
            StationTask.FARM, StationTask.FARM, StationTask.FARM);
        VillageFolkEntity bram = folk.get(0), ash = folk.get(1);
        bram.tradeXpForTests(StationTask.SMITH, 20000);
        UUID id = bram.ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        LabourMarket.morningNow(level, v);
        Business b = Enterprise.foundForTests(level, v, "toolsmith", bram);
        if (b != null) LabourMarket.firmShortForTests(id, b);
        LabourMarket.termsForTests(ash, 1.23, true, day(level) - 3);
        LabourMarket.setMinimum(id, LabourMarket.minimumWage(id) + 2, "the test");
        nextDay(level);
        LabourMarket.morningNow(level, v);
        String termsBefore = terms(ash);
        int openBefore = LabourMarket.openingsForTests(id).size();
        int minBefore = LabourMarket.councilMinimum(id);
        Map<String, Double> idxBefore = LabourMarket.indexForTests(id);
        String idx0 = idxBefore == null ? "-" : idxBefore.toString();
        LabourMarket.reloadForTests(level.registryAccess());
        Map<String, Double> idxAfter = LabourMarket.indexForTests(id);
        Kit.log("[VT] lab08 " + termsBefore + " / " + terms(ash) + "; openings " + openBefore + "; minimum " + minBefore);
        ok.that(termsBefore.equals(terms(ash)), "its terms the same: " + terms(ash));
        ok.that(openBefore > 0 && openBefore == LabourMarket.openingsForTests(id).size(), "the openings the same: " + openBefore);
        ok.that(minBefore > 0 && minBefore == LabourMarket.councilMinimum(id), "the minimum wage the same: " + minBefore);
        ok.that(idx0.equals(idxAfter == null ? "-" : idxAfter.toString()), "the wage index the same");
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ lab09 the interview

    /**
     * Two skilled applicants for a firm's place, and the town holding interviews: an interview is set at the hall for the
     * post (job:N), the applicants shortlisted; the panel's choice takes up the place.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lab09_interview")
    public static void lab09_interview(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 3016000;
        List<VillageFolkEntity> folk = town(helper, level, x, 30, StationTask.SMITH, StationTask.NONE, StationTask.NONE, StationTask.NONE,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.GUARD, StationTask.GUARD);
        VillageFolkEntity bram = folk.get(0);
        bram.tradeXpForTests(StationTask.SMITH, 20000);
        for (int i = 1; i <= 3; i++) folk.get(i).tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(5 + i));
        UUID id = bram.ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        Business b = Enterprise.foundForTests(level, v, "toolsmith", bram);
        ok.that(b != null, "the toolshop opened");
        if (b == null) { helper.fail(ok.summary()); return; }
        // Whoever the founding took on, the place stays open for the two.
        LabourMarket.onPaperForTests(false);
        Interviews.liveForTests(true);
        Interviews.fairDayForTests(true);
        try {
            LabourMarket.firmShortForTests(id, b);
            nextDay(level);
            LabourMarket.morningNow(level, v);
            logOpenings("lab09", id);
            List<String> ivs = LabourMarket.interviewsForTests(id);
            Kit.log("[VT] lab09 interviews " + ivs);
            Object[] o = openingOf(id, "firm:" + b.id);
            boolean set = false;
            for (String s : ivs) set |= s.startsWith("job:");
            ok.that(set && o != null && "INTERVIEW".equals(o[6]), "an interview set for the firm's place: " + ivs + " " + (o == null ? "-" : o[6]));
            if (o != null) {
                VillageFolkEntity winner = null;
                for (int i = 1; i <= 3; i++) if (winner == null && !Enterprise.hands(id, b).contains(folk.get(i))) winner = folk.get(i);
                String took = LabourMarket.interviewedForTests(level, v, (Integer) o[0], winner);
                Kit.log("[VT] lab09 the panel chose " + winner.displayNameCap() + ": " + took);
                ok.that(("firm:" + b.id).equals(LabourMarket.employerForTests(winner)), "the panel's choice took up the place: "
                    + LabourMarket.employerForTests(winner));
            }
        } finally {
            Interviews.liveForTests(false);
            Interviews.fairDayForTests(false);
        }
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ lab10 ten days

    /**
     * Ten days of a town founded and left to itself under the market (EntrepreneurGameTests' long harness): each morning the
     * labour market's line (the wage index by trade, the employed, the out of work, the openings, hirings, notices and
     * lay-offs). At the end: wages moved with supply and demand, few out of work, the firms staffed, the census at nought
     * drift (the harness's own checks: the IOUs bounded, it grew, built and fed itself).
     */
    @GameTest(template = EMPTY, timeoutTicks = 900000, required = false, batch = "lab10_ten_days")
    public static void lab10_ten_days(GameTestHelper helper) {
        final double[] outShare = { 0, 0 };
        final java.util.Map<String, Double> firstIndex = new java.util.TreeMap<>();
        final int[] totals = new int[4];
        EntrepreneurGameTests.tenDays(helper, 3020000, "lab10", false, 10, (id, dayNo) -> {
            int[] n = LabourMarket.countsForTests(id);
            int all = n[0] + n[1] + n[2];
            if (all > 0 && MarketMaker.live(id)) {
                outShare[0] += n[2] / (double) all;
                outShare[1]++;
            }
            Map<String, Double> idx = LabourMarket.indexForTests(id);
            if (idx != null && firstIndex.isEmpty()) firstIndex.putAll(idx);
            for (String l : LabourMarket.status(helper.getLevel(), id)) Kit.log("lab10 day " + dayNo + " LABOURPAGE " + l);
        }, (id, ok) -> {
            Map<String, Double> idx = LabourMarket.indexForTests(id);
            double moved = 0;
            if (idx != null) for (Map.Entry<String, Double> e : idx.entrySet()) {
                Double was = firstIndex.get(e.getKey());
                moved = Math.max(moved, Math.abs(e.getValue() - (was == null ? 1.0 : was)));
            }
            Kit.log("lab10 INDEX first " + firstIndex + " last " + idx);
            ok.that(moved >= 0.03, String.format(Locale.ROOT, "wages moved with supply and demand: the most a trade's index moved %.3f", moved));
            int[] n = LabourMarket.countsForTests(id);
            double avg = outShare[1] == 0 ? 0 : outShare[0] / outShare[1];
            Kit.log(String.format(Locale.ROOT, "lab10 UNEMPLOYMENT average %.3f, at the end %d of %d", avg, n[2], n[0] + n[1] + n[2]));
            ok.that(avg <= 0.10, String.format(Locale.ROOT, "few out of work: %.1f%% on average", avg * 100));
            int unstaffed = 0;
            for (Business b : Enterprise.open(id)) if (Enterprise.hands(id, b).isEmpty()) unstaffed++;
            ok.that(unstaffed == 0, "every firm staffed: " + unstaffed + " with nobody");
            for (String l : LabourMarket.newsForTests(id)) Kit.log("lab10 LABOUR NEWS " + l);
        });
    }
}
