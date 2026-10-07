package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.FolkSkills;
import com.jrpetty.mcassistant.entity.JobWorth;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Names;
import com.jrpetty.mcassistant.entity.ShopRoles;
import com.jrpetty.mcassistant.entity.StoreStaff;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
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
 * What every job is worth (entity/JobWorth): a job's day's wage is the town's pay level times its worth (its value
 * to the town, how hard it is to fill, how hard it is and the skill it takes) times the folk's own hand at it.
 *
 * <ul>
 * <li><b>wg01</b>: a Stone Age village of twelve with one miner where its shape wants two or three: over eight
 *     mornings the mines' scarcity eases up and the miner's pay with it, the gazette and the chronicle tell of the
 *     rise, and its card says the mines are short of hands. Then six farmers go down the mines (seven miners where
 *     two or three are wanted): eight mornings on, the same miner is paid less.</li>
 * <li><b>wg02</b>: a master miner is paid more than a novice at the same mines; the hand's curve is smooth (no
 *     step at ten or at twenty-five); a knack of the trade adds a little.</li>
 * <li><b>wg03</b>: the shop's stock keeper's job is worth more than the counter's, and the keeper's too, for the
 *     same hand; a real assistant (ShopRoles) is paid as one.</li>
 * <li><b>wg04</b>: six days of books in which the farmers, the miners and the fisher bring in goods and the watch
 *     brings in nothing: the guard is paid for keeping folk alive, more than a farmer of its years.</li>
 * <li><b>wg05</b>: a town with no bread in its stores and next to nothing coming in: the pay level falls as far as
 *     it goes, and still every wage, at the leader's rate too, covers two meals and the rent; top pay is five
 *     times the lowest.</li>
 * <li><b>wg06</b>: a steady town with a steady income (what it makes, and the tax back): over twenty mornings the
 *     day's bill settles within what comes in; with three times as much coming in, the pay level rises and the
 *     bill stays within it.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WagesGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 64000;
    /** The hour the clock is held at: past the morning's business (Market.tick's hours), before dusk. */
    private static final long HOUR = 11600L;

    /** A town founded here, its builders kept from starting anything for the length of the test. */
    private static Villages.Village town(ServerLevel level, int x) {
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        Villages.Village v = Villages.found(level, Kit.surface(level, x, Z));
        Villages.noteAttempt(v.id(), level.getGameTime() + 400000L);
        return v;
    }

    /** A grown folk of this village standing here, at this trade and level. */
    private static VillageFolkEntity folk(ServerLevel level, Villages.Village v, int x, int z, StationTask trade, int lv) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        BlockPos at = Kit.surface(level, x, z);
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        f.rename(Names.freeFor(v.id()));
        VillageSpawner.starterKit(f);
        f.joinVillage(v.id(), v.centre());
        level.addFreshEntity(f);
        Villages.recordBirth(v.id());
        f.setAgeForTests(30);
        setTrade(f, trade, lv);
        return f;
    }

    private static void setTrade(VillageFolkEntity f, StationTask trade, int lv) {
        f.setJob(trade);
        f.tradeXpForTests(trade, lv * lv * Math.max(1, AssistantConfig.levelCurveFactor()) + 1);
    }

    /** So many folk at this trade and level, in a row south of the heart. */
    private static List<VillageFolkEntity> crew(ServerLevel level, Villages.Village v, int x, int row, int n, StationTask trade, int lv) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(folk(level, v, x - 12 + (i % 8) * 3, Z + 6 + row * 3, trade, lv));
        return out;
    }

    /** A well-kept larder: a store chest by the heart with two stacks of bread, so bread is at its usual price. */
    private static void larder(ServerLevel level, Villages.Village v) {
        BlockPos at = Kit.surface(level, v.centre().getX() + 2, v.centre().getZ() - 3);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        box.setItem(0, new ItemStack(Items.BREAD, 64));
        box.setItem(1, new ItemStack(Items.BREAD, 64));
        box.setChanged();
    }

    private static void logScale(String tag, ServerLevel level, UUID id) {
        for (String line : JobWorth.pageLines(level, id)) Kit.log(tag + " " + line);
    }

    // ============================================================ short of hands

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wg01_short_handed")
    public static void wg01_short_handed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        long base = (level.getDayTime() / 24000L) * 24000L;
        level.setDayTime(base + HOUR);
        int x = 660000;
        Villages.Village v = town(level, x);
        UUID id = v.id();
        larder(level, v);
        List<VillageFolkEntity> farmers = crew(level, v, x, 0, 11, StationTask.FARM, 5);
        VillageFolkEntity miner = folk(level, v, x + 8, Z - 8, StationTask.MINE, 15);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            long day0 = level.getDayTime() / 24000L;
            String news = "", gazette = null;
            for (int d = 1; d <= 8; d++) {
                JobWorth.morningForTests(level, v, day0 + d, null);
                String n = JobWorth.newsForTests(id, day0 + d);
                if (news.isEmpty() && !n.isEmpty()) {
                    news = n;
                    gazette = JobWorth.gazette(id, day0 + d);     // the morning's issue, as the gazette prints it
                }
            }
            JobWorth.Worth shortW = JobWorth.scale(level, id).worth("MINE");
            int wageShort = Wealth.wage(miner);
            String cardShort = JobWorth.cardLine(miner);
            Kit.log("wg01 " + Villages.name(id) + ", " + Villages.rank(id) + " of " + Villages.headcount(id) + ": one miner; after eight mornings "
                + "the mines' scarcity " + shortW.scarcity() + " (" + shortW.have() + " at it, " + shortW.target() + " wanted), the miner paid "
                + wageShort + " (" + Wealth.breakdown(miner) + "); news: " + news + "; card: " + cardShort);
            logScale("wg01 short:", level, id);
            // Six farmers down the mines: seven miners where two or three are wanted.
            for (int i = 0; i < 6; i++) setTrade(farmers.get(i), StationTask.MINE, 5);
            for (int d = 9; d <= 16; d++) JobWorth.morningForTests(level, v, day0 + d, null);
            JobWorth.Worth overW = JobWorth.scale(level, id).worth("MINE");
            int wageOver = Wealth.wage(miner);
            Kit.log("wg01 seven miners; after eight mornings the mines' scarcity " + overW.scarcity() + " (" + overW.have() + " at it, "
                + overW.target() + " wanted), the same miner paid " + wageOver + " (" + Wealth.breakdown(miner) + "); card: "
                + JobWorth.cardLine(miner));
            logScale("wg01 over:", level, id);
            helper.assertTrue(shortW.scarcity() > 1.1, "short of hands, the mines' scarcity rises: " + shortW.scarcity());
            helper.assertTrue(overW.scarcity() < 0.9, "over-staffed, it falls: " + overW.scarcity());
            helper.assertTrue(wageShort > wageOver, "the same miner is paid more while the mines are short of hands ("
                + wageShort + ") than when they have hands to spare (" + wageOver + ")");
            helper.assertTrue(cardShort.contains("short of hands") || cardShort.contains("hands short"),
                "its card says the mines are short: " + cardShort);
            helper.assertTrue(news.toLowerCase(java.util.Locale.ROOT).contains("miners' pay is up"),
                "the gazette tells of the miners' rise: " + news);
            helper.assertTrue(gazette != null && gazette.contains("Wages") && gazette.contains("pay is up"),
                "the gazette's wages column: " + gazette);
            helper.succeed();
        });
    }

    // ============================================================ skill

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wg02_skilled")
    public static void wg02_skilled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        long base = (level.getDayTime() / 24000L) * 24000L;
        level.setDayTime(base + HOUR);
        int x = 662000;
        Villages.Village v = town(level, x);
        UUID id = v.id();
        larder(level, v);
        crew(level, v, x, 0, 10, StationTask.FARM, 5);
        VillageFolkEntity novice = folk(level, v, x + 8, Z - 8, StationTask.MINE, 0);
        VillageFolkEntity master = folk(level, v, x + 11, Z - 8, StationTask.MINE, 25);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            long day0 = level.getDayTime() / 24000L;
            for (int d = 1; d <= 2; d++) JobWorth.morningForTests(level, v, day0 + d, null);
            int n = Wealth.wage(novice), m = Wealth.wage(master);
            JobWorth.Pay pn = JobWorth.payOf(novice), pm = JobWorth.payOf(master);
            Kit.log("wg02 the novice miner (level " + novice.veteranLevel() + ") " + n + ": " + Wealth.breakdown(novice));
            Kit.log("wg02 the master miner (level " + master.veteranLevel() + ") " + m + ": " + Wealth.breakdown(master) + "; card: "
                + JobWorth.cardLine(master));
            // The curve: no step at ten or twenty-five, every level a little more than the last.
            StringBuilder curve = new StringBuilder();
            double worst = 0;
            boolean rising = true;
            for (int lv = 1; lv <= 40; lv++) {
                double step = JobWorth.skillCurve(lv) - JobWorth.skillCurve(lv - 1);
                worst = Math.max(worst, step);
                rising &= step > 0;
                if (lv % 5 == 0) curve.append(lv).append(':').append(String.format(java.util.Locale.ROOT, "%.3f", JobWorth.skillCurve(lv))).append(' ');
            }
            Kit.log("wg02 the hand's curve " + curve + "; the biggest step " + worst);
            helper.assertTrue(m > n, "a master miner is paid more than a novice: " + m + " against " + n);
            helper.assertTrue(pm.skill() > pn.skill() + 0.4, "its hand counts: " + pm.skill() + " against " + pn.skill());
            helper.assertTrue(rising && worst < 0.06, "a smooth curve, rising every level, no steps: the biggest " + worst);
            helper.assertTrue(JobWorth.cardLine(master).contains("master miner"), "its card says why: " + JobWorth.cardLine(master));
            // A knack of the trade counts too.
            double before = JobWorth.payOf(master).skill();
            boolean granted = FolkSkills.grant(level, master, FolkSkills.Knack.STEADY_HANDS);
            double after = JobWorth.payOf(master).skill();
            Kit.log("wg02 Steady Hands granted " + granted + ": its hand " + before + " -> " + after);
            helper.assertTrue(!granted || after > before, "a knack of its trade adds to its hand: " + before + " -> " + after);
            helper.succeed();
        });
    }

    // ============================================================ the shop's jobs

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wg03_stock_keeper")
    public static void wg03_stock_keeper(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        long base = (level.getDayTime() / 24000L) * 24000L;
        level.setDayTime(base + HOUR);
        int x = 664000;
        Villages.Village v = town(level, x);
        UUID id = v.id();
        larder(level, v);
        crew(level, v, x, 0, 10, StationTask.FARM, 5);
        VillageFolkEntity keeper = folk(level, v, x + 8, Z - 8, StationTask.SHOP, 14);
        VillageFolkEntity assistant = folk(level, v, x + 11, Z - 8, StationTask.SHOP, 10);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            // The town has no shop yet, so on its first look round a shopkeeper takes up whatever the town is short
            // of (VillageFolkEntity.changedTrade): the two are put back at the shop, in their jobs, as the store's
            // staffing puts them (StoreStaff: the keeper untagged, the assistant with the counter's tag).
            StoreStaff.appointForTests(level, v, keeper, ShopRoles.Role.KEEPER);
            StoreStaff.appointForTests(level, v, assistant, ShopRoles.Role.ASSISTANT);
            JobWorth.Post stock = JobWorth.post("SHOP/STOCK_KEEPER"), counter = JobWorth.post("SHOP/ASSISTANT"),
                keeps = JobWorth.post("SHOP/KEEPER");
            int asStock = JobWorth.payAs(assistant, stock), asCounter = JobWorth.payAs(assistant, counter), asKeeper = JobWorth.payAs(assistant, keeps);
            int paid = Wealth.wage(assistant);
            Kit.log("wg03 " + keeper.displayNameCap() + " is " + ShopRoles.role(keeper) + ", " + assistant.displayNameCap() + " "
                + ShopRoles.role(assistant) + " (level " + assistant.veteranLevel() + "): as the stock keeper " + asStock + ", at the counter "
                + asCounter + ", keeping the shop " + asKeeper + "; paid " + paid + " (" + Wealth.breakdown(assistant) + ")");
            JobWorth.Scale s = JobWorth.scale(level, id);
            Kit.log("wg03 " + JobWorth.jobLine(s, s.worth(stock.key)) + " | " + JobWorth.jobLine(s, s.worth(counter.key)));
            helper.assertTrue(ShopRoles.role(assistant) == ShopRoles.Role.ASSISTANT && ShopRoles.role(keeper) == ShopRoles.Role.KEEPER,
                "the keeper keeps the shop, the other serves at the counter");
            helper.assertTrue(stock.difficulty() > counter.difficulty(), "the stock keeper's is the harder, more skilled job");
            helper.assertTrue(asStock > asCounter, "the stock keeper is paid more than a shop assistant: " + asStock + " against " + asCounter);
            helper.assertTrue(asKeeper > asCounter, "and the keeper too: " + asKeeper + " against " + asCounter);
            helper.assertTrue(JobWorth.payOf(assistant).worthPart() == asCounter && paid < asStock,
                "the assistant is paid as one: " + paid);
            helper.succeed();
        });
    }

    // ============================================================ the watch

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wg04_watch")
    public static void wg04_watch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        long base = (level.getDayTime() / 24000L) * 24000L;
        level.setDayTime(base + HOUR);
        int x = 666000;
        Villages.Village v = town(level, x);
        UUID id = v.id();
        larder(level, v);
        List<VillageFolkEntity> farmers = crew(level, v, x, 0, 8, StationTask.FARM, 20);
        List<VillageFolkEntity> miners = crew(level, v, x, 1, 2, StationTask.MINE, 5);
        VillageFolkEntity fisher = folk(level, v, x - 8, Z - 8, StationTask.FISH, 5);
        VillageFolkEntity guard = folk(level, v, x + 8, Z - 8, StationTask.GUARD, 20);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            long day0 = level.getDayTime() / 24000L;
            // Six days of books: the fields, the mines and the water bring goods in; the watch brings in nothing.
            for (int d = 1; d <= 6; d++) {
                for (VillageFolkEntity f : farmers) Economy.produced(f, new ItemStack(Items.WHEAT, 40));
                for (VillageFolkEntity f : miners) Economy.produced(f, new ItemStack(Items.COBBLESTONE, 64));
                Economy.produced(fisher, new ItemStack(Items.COD, 6));
                Economy.closeTheDay(level, v, day0 + d);
                JobWorth.morningForTests(level, v, day0 + d, null);
            }
            JobWorth.Scale s = JobWorth.scale(level, id);
            JobWorth.Worth g = s.worth("GUARD"), fa = s.worth("FARM");
            int guardPay = Wealth.wage(guard), farmerPay = Wealth.wage(farmers.get(0));
            Kit.log("wg04 a hand brings in " + s.ref + " a day; " + JobWorth.jobLine(s, g) + " | " + JobWorth.jobLine(s, fa));
            Kit.log("wg04 the guard (level " + guard.veteranLevel() + ") " + guardPay + ": " + Wealth.breakdown(guard) + "; card: "
                + JobWorth.cardLine(guard));
            Kit.log("wg04 a farmer (level " + farmers.get(0).veteranLevel() + ") " + farmerPay + ": " + Wealth.breakdown(farmers.get(0)));
            helper.assertTrue(s.ref > 1.0, "the books have a hand's output: " + s.ref);
            helper.assertTrue(g.perHand() < 0.25 * s.ref, "the watch brought in next to nothing: " + g.perHand());
            helper.assertTrue(g.service() > 0 && g.value() >= 0.95, "it is valued for keeping folk alive: value " + g.value()
                + " from a service of " + g.service());
            helper.assertTrue(guardPay > farmerPay, "the guard is paid more than a farmer of its years: " + guardPay + " against " + farmerPay);
            helper.assertTrue(JobWorth.cardLine(guard).contains("keeps folk alive"), "its card says why: " + JobWorth.cardLine(guard));
            helper.succeed();
        });
    }

    // ============================================================ the living wage

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wg05_living_wage")
    public static void wg05_living_wage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        long base = (level.getDayTime() / 24000L) * 24000L;
        level.setDayTime(base + HOUR);
        int x = 668000;
        Villages.Village v = town(level, x);                  // no larder: bread is dear
        UUID id = v.id();
        List<VillageFolkEntity> all = new ArrayList<>();
        all.addAll(crew(level, v, x, 0, 6, StationTask.FARM, 0));
        all.addAll(crew(level, v, x, 1, 2, StationTask.FISH, 0));
        all.addAll(crew(level, v, x, 2, 2, StationTask.WOOD, 0));
        all.add(folk(level, v, x + 8, Z - 8, StationTask.MINE, 2));
        all.add(folk(level, v, x + 11, Z - 8, StationTask.SMELT, 30));
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            long day0 = level.getDayTime() / 24000L;
            // Next to nothing coming in, twelve mornings running.
            for (int d = 1; d <= 12; d++) JobWorth.morningForTests(level, v, day0 + d, 6);
            JobWorth.Scale s = JobWorth.scale(level, id);
            JobWorth.Cost cost = JobWorth.costOfLiving(level, id);
            logScale("wg05", level, id);
            int least = Integer.MAX_VALUE, leastPaid = Integer.MAX_VALUE, most = 0;
            for (VillageFolkEntity f : all) {
                int w = Wealth.wage(f);
                least = Math.min(least, w);
                leastPaid = Math.min(leastPaid, JobWorth.atLeadersRate(id, w));
                most = Math.max(most, JobWorth.payOf(f).worthPart());
            }
            Kit.log("wg05 the pay level " + s.payLevel + " (afford " + s.afford + "); cost of living " + cost.coins() + " (" + cost.words()
                + "); lowest wage " + least + ", paid at the leader's rate " + leastPaid + "; the scale's lowest " + s.lowest() + ", top "
                + s.cap() + "; the best paid's worth " + most);
            helper.assertTrue(s.afford <= 0.7, "with next to nothing coming in the pay level falls: " + s.afford);
            helper.assertTrue(least >= cost.coins() && leastPaid >= cost.coins(),
                "the lowest wage still covers two meals and the rent: " + least + " (paid " + leastPaid + ") against " + cost.coins());
            helper.assertTrue(s.lowest() >= cost.coins() && s.cap() == JobWorth.CAP_TIMES * s.lowest(),
                "the scale's bounds: lowest " + s.lowest() + ", top " + s.cap());
            helper.assertTrue(most <= s.cap(), "nobody's worth is paid over the top of the scale: " + most);
            helper.succeed();
        });
    }

    // ============================================================ the bill within the income

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wg06_bill_within_income")
    public static void wg06_bill_within_income(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        long base = (level.getDayTime() / 24000L) * 24000L;
        level.setDayTime(base + HOUR);
        int x = 670000;
        Villages.Village v = town(level, x);
        UUID id = v.id();
        larder(level, v);
        crew(level, v, x, 0, 6, StationTask.FARM, 5);
        crew(level, v, x, 1, 2, StationTask.MINE, 10);
        folk(level, v, x + 8, Z - 8, StationTask.GUARD, 10);
        folk(level, v, x + 11, Z - 8, StationTask.WOOD, 5);
        folk(level, v, x - 8, Z - 8, StationTask.SMELT, 8);
        folk(level, v, x - 11, Z - 8, StationTask.FISH, 3);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            long day0 = level.getDayTime() / 24000L;
            JobWorth.Scale first = JobWorth.scale(level, id);
            int full = first.bill(), floors = 12 * first.floor;
            // What the town makes and sells, steady: a little under its full bill (and never under what the living
            // wages come to); the tax on the day's wages comes back on top.
            int made = Math.max((int) Math.round(0.9 * full), (int) Math.round(1.3 * floors));
            int bill = full, income = 0;
            StringBuilder days = new StringBuilder();
            for (int d = 1; d <= 20; d++) {
                income = made + Math.round(bill / 10f);
                JobWorth.Scale s = JobWorth.morningForTests(level, v, day0 + d, income);
                bill = s.bill();
                days.append(d).append(": in ").append(income).append(" bill ").append(bill).append(" at ")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", s.afford)).append("; ");
            }
            JobWorth.Scale s = JobWorth.scale(level, id);
            Kit.log("wg06 full bill " + full + ", the living wages " + floors + ", steady takings " + made + "; " + days);
            logScale("wg06 steady:", level, id);
            helper.assertTrue(s.bill() <= s.income() && s.bill() <= income,
                "in a steady town the day's bill settles within what comes in: " + s.bill() + " against " + s.income() + " (today " + income + ")");
            helper.assertTrue(String.join(" ", JobWorth.summary(s)).contains("within it"), "the wages page says so");
            // Payday out of that day's income: never more paid out than came in.
            Ledger.takeCoins(id, Ledger.coins(id));
            Ledger.addCoins(id, income);
            int paid = Market.payWages(level, v);
            Kit.log("wg06 payday out of " + income + ": paid " + paid + ", the treasury keeps " + Ledger.coins(id));
            helper.assertTrue(paid <= income, "paid " + paid + " out of " + income);
            // Three times as much coming in: the pay level rises, and the bill stays within it.
            double was = s.afford;
            for (int d = 21; d <= 30; d++) {
                income = 3 * full;
                JobWorth.morningForTests(level, v, day0 + d, income);
            }
            JobWorth.Scale rich = JobWorth.scale(level, id);
            Kit.log("wg06 well off: the pay level " + was + " -> " + rich.afford + ", bill " + rich.bill() + " against " + rich.income());
            helper.assertTrue(rich.afford > was && rich.afford <= JobWorth.AFFORD_HI + 1e-9, "the pay level rises, so far: " + rich.afford);
            helper.assertTrue(rich.bill() <= rich.income(), "the bill within what comes in: " + rich.bill() + " against " + rich.income());
            helper.succeed();
        });
    }
}
