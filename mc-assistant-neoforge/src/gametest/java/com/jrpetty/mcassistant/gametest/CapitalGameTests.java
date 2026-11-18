package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Accounts;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bourse;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.CapitalBook;
import com.jrpetty.mcassistant.entity.CapitalReport;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.FolkCredit;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.LabourMarket;
import com.jrpetty.mcassistant.entity.LandTitles;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.MarketMaker;
import com.jrpetty.mcassistant.entity.Privatisation;
import com.jrpetty.mcassistant.entity.TownBonds;
import com.jrpetty.mcassistant.entity.TownBudget;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.BoardPages;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [capital] The town's capital markets (economy batch 3): the savings club and its interest, the lender's rate and its
 * judgement of a firm's books, a default taken to court and written off with the shareholders wiped after the creditors,
 * the bourse (a trade, a dividend, an issue, profit-sharing), the land register (titles, values, rents following land,
 * an idle homestead leased, a title sold to a player and its rent), the town's bonds (IOUs funded, wages found, coupons,
 * roll-over and repayment), every player's dealing booked in the coin census, kept with the world, and the Capital page.
 *
 * <p>Each on its own ground, x 3300000 to 3399999 (2000 apart), z 90000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CapitalGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    // ============================================================ the ground

    /** A town of so many folk at these trades, every purse at {@code purse}; the market open, the Stone Age. */
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
            f.keepTradeForTests();
            f.spend(f.purse());
            f.earn(purse);
            Values.setForTests(f, Values.Value.TRADITION, 100);
            Values.setForTests(f, Values.Value.WEALTH, 100);            // careful with its coin: saves, buys bonds and shares
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

    /** A firm of this founder's with a week and a half of books behind it: so much takings a day, so much profit a day. */
    private static Business firm(ServerLevel level, UUID id, VillageFolkEntity founder, String type, long revenueDay, long netDay, String status) {
        Business b = Enterprise.foundForTests(level, Villages.get(id), type, founder);
        if (b == null) return null;
        long d = day(level);
        b.opened = d - 13;                                                   // its week's drawing falls today (Businesses.draw)
        b.days.clear();
        for (int k = 10; k >= 1; k--) {
            Business.Day day = b.today(d - k);
            day.revenue = revenueDay;
            day.cogs = revenueDay - netDay;
        }
        CompoundTag r = Enterprise.recordForTests(id, b);
        if (r != null) r.putString("status", status);
        return b;
    }

    private static long coinNow(UUID id) {
        return CoinCensus.count(id);
    }

    private static ServerPlayer player(GameTestHelper helper, VillageFolkEntity near, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.teleportTo(near.getX() + 1.5, near.getY(), near.getZ() + 0.5);
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return p;
    }

    private static void logCapital(ServerLevel level, String tag, UUID id) {
        for (String l : CapitalReport.lines(level, id)) Kit.log("[VT] " + tag + " " + l);
    }

    // ============================================================ cap01 the savings club

    /**
     * The savings club: folk with coin over their week's keep put their nature's share of it in (out of their purses into
     * its chest, the census unchanged); it lends a firm out of the chest; the loan repaid with interest, its savers are paid
     * their week's interest out of what the loan earned and never more, onto their balances; a folk's card says what it has.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cap01_club")
    public static void cap01_club(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = town(helper, level, 3300000, 60, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.GUARD, StationTask.GUARD);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        long c0 = coinNow(id);
        FolkCredit.morningForTests(level, v, day(level));
        long dep = FolkCredit.deposits(id);
        logCapital(level, "cap01", id);
        ok.that(dep > 0, "folk saved at the club: " + Businesses.pence(dep));
        ok.that(FolkCredit.clubCash(id) == dep, "the chest holds what was deposited: " + Businesses.pence(FolkCredit.clubCash(id)));
        ok.that(coinNow(id) == c0, "the town's coin the same (" + c0 + " -> " + coinNow(id) + ")");
        int purse1 = folk.get(1).purse();
        ok.that(purse1 < 60 && purse1 >= 12, "a saver kept its week's keep in its purse: " + purse1);
        Business b = firm(level, id, folk.get(0), "toolsmith", 2000, 400, "trading");
        ok.that(b != null, "the toolshop opened");
        if (b == null) { helper.fail(ok.summary()); return; }
        long lent = FolkCredit.lendForTests(level, id, b, 3000);
        ok.that(lent == 3000 && b.loan == 3000 && "club".equals(b.lender), "the club lent the firm 30c: " + lent + " (" + b.lender + ")");
        ok.that(FolkCredit.clubCash(id) == dep - 3000, "out of its chest: " + Businesses.pence(FolkCredit.clubCash(id)));
        ok.that(coinNow(id) == c0, "the town's coin the same after the loan");
        b.loan += 600;                                                       // a few weeks' interest run up on it
        b.till += Businesses.fromTreasuryForTests(id, 4000);                 // (its takings: coin moved, not made)
        FolkCredit.repaidForTests(id, b, 36);
        long earned = FolkCredit.earnedForTests(id);
        ok.that(b.loan == 0 && earned > 0, "the loan repaid, the club earned " + Businesses.pence(earned));
        int before = 0;
        for (VillageFolkEntity f : folk) before += FolkCredit.balance(id, f.getUUID());
        FolkCredit.weekForTests(level, v, day(level));
        int after = 0;
        for (VillageFolkEntity f : folk) after += FolkCredit.balance(id, f.getUUID());
        Kit.log("[VT] cap01 balances " + before + " -> " + after + ", earned " + earned + ", deposit rate " + FolkCredit.depositBp(id));
        ok.that(after > before, "its savers were paid interest: " + before + " -> " + after);
        ok.that((after - before) * 100L <= earned, "never more than the loan earned: " + (after - before) + "c of " + Businesses.pence(earned));
        ok.that(coinNow(id) == c0, "the town's coin the same at the end (" + c0 + " -> " + coinNow(id) + "): no coin made");
        String card = FolkTalk.card(folk.get(1));
        ok.that(card.contains("savings club"), "the folk's card says what it has at the club");
        Enterprise.autoForTests(true);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ cap02 the rate and the judgement

    /**
     * The lender's rate follows its reserves: with its vault full it lends cheaper than with most of it lent. It reads a
     * firm's books: a thriving firm may owe a week and a half's takings, a struggling one a third of a week's, one losing a
     * fifth of its takings nothing (and the reason is in its book); its undrawn credit counts toward the wages it can pay.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cap02_credit")
    public static void cap02_credit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = town(helper, level, 3302000, 30, StationTask.SMITH, StationTask.TAILOR, StationTask.COOK, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.GUARD);
        UUID id = folk.get(0).ownerId();
        Kit.Expect ok = new Kit.Expect();
        for (int i = 3; i < folk.size(); i++) FolkCredit.depositForTests(id, folk.get(i), 25);
        int full = FolkCredit.rateBp(id);
        Business good = firm(level, id, folk.get(0), "toolsmith", 2000, 500, "thriving");
        Business poor = firm(level, id, folk.get(1), "clothier", 1000, -50, "struggling");
        Business bad = firm(level, id, folk.get(2), "baker", 1000, -300, "trading");
        ok.that(good != null && poor != null && bad != null, "three firms opened");
        if (good == null || poor == null || bad == null) { helper.fail(ok.summary()); return; }
        long rg = FolkCredit.judgeForTests(level, id, good), rp = FolkCredit.judgeForTests(level, id, poor), rb = FolkCredit.judgeForTests(level, id, bad);
        Kit.log("[VT] cap02 rooms: thriving " + rg + ", struggling " + rp + ", losing " + rb + "; rate full " + full);
        ok.that(rg == 2000 * 7 * 3 / 2, "a thriving firm may owe a week and a half's takings: " + Businesses.pence(rg));
        ok.that(rp == 1000 * 7 / 3, "a struggling one a third of a week's: " + Businesses.pence(rp));
        ok.that(rb == 0, "one losing three tenths of its takings nothing: " + Businesses.pence(rb));
        ok.that(FolkCredit.lendForTests(level, id, bad, 1000) == 0, "and is refused");
        boolean why = false;
        for (Tag t : CapitalReport.report(level, id).getCompound("credit").getList("decisions", Tag.TAG_COMPOUND)) why |= ((CompoundTag) t).getString("why").contains("lost");
        ok.that(why, "the refusal and its reason in the lender's book");
        double line = FolkCredit.wageLine(level, id, good);
        ok.that(line > 0, "the thriving firm's undrawn credit counts toward its wages: " + line + "c a day");
        long lent = FolkCredit.lendForTests(level, id, good, 9000);
        int drained = FolkCredit.rateBp(id);
        Kit.log("[VT] cap02 lent " + lent + "; rate " + full + " -> " + drained + "; line now " + FolkCredit.wageLine(level, id, good));
        ok.that(lent > 0 && drained > full, "with its vault lent out the rate goes up: " + full + " -> " + drained);
        ok.that(good.rateBp == full, "the firm borrowed at the day's rate: " + good.rateBp);
        ok.that(FolkCredit.depositBp(id) * 100 / drained >= 39, "its savers paid two fifths of the lending rate: " + FolkCredit.depositBp(id));
        Enterprise.autoForTests(true);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ cap03 a default

    /**
     * A firm whose loan has not come down in a week is behind; two weeks behind, the lender takes it to the court. Declared
     * bankrupt, the receiver pays its creditors in order (the lender what the till runs to), the rest of the loan written
     * off (a default: the rate goes up), and its shareholders are wiped out after its creditors, the bourse saying so.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cap03_default")
    public static void cap03_default(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = town(helper, level, 3304000, 30, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.GUARD);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        for (int i = 1; i < folk.size(); i++) FolkCredit.depositForTests(id, folk.get(i), 15);
        Business b = firm(level, id, folk.get(0), "toolsmith", 1500, 200, "trading");
        if (b == null) { helper.fail("no firm"); return; }
        long lent = FolkCredit.lendForTests(level, id, b, 4000);
        ok.that(lent == 4000, "the club lent it 40c: " + lent);
        int rate0 = FolkCredit.rateBp(id);
        long d0 = day(level);
        FolkCredit.loansForTests(level, v, d0);
        level.setDayTime((d0 + 7) * 24000L + 7000L);
        FolkCredit.loansForTests(level, v, d0 + 7);
        ok.that(FolkCredit.judgeForTests(level, id, b) == 0, "a week with its loan standing still: behind, and lent no more");
        level.setDayTime((d0 + 14) * 24000L + 7000L);
        FolkCredit.loansForTests(level, v, d0 + 14);
        CompoundTag r = Enterprise.recordForTests(id, b);
        ok.that(r != null && r.contains("petition"), "two weeks behind: its lender took it to the court (" + (r == null ? "-" : r.getString("petition")) + ")");
        b.till = 1000;
        b.account = 0;
        long chest = FolkCredit.clubCash(id);
        long c0 = coinNow(id);
        Enterprise.bankrupt(level, id, b, day(level), "it could not keep up its payments to the savings club", "by the council on the square");
        long got = FolkCredit.clubCash(id) - chest;
        CompoundTag credit = CapitalReport.report(level, id).getCompound("credit");
        Kit.log("[VT] cap03 creditors: " + (r == null ? "-" : r.getString("creditors")) + "; club got " + got + "; defaulted " + credit.getLong("defaulted")
            + "; rate " + rate0 + " -> " + FolkCredit.rateBp(id));
        ok.that(got > 0 && got < 4000, "the receiver paid the club what the till ran to: " + Businesses.pence(got));
        ok.that(credit.getLong("defaulted") > 0, "the rest written off: " + Businesses.pence(credit.getLong("defaulted")));
        ok.that(FolkCredit.rateBp(id) > rate0, "a default puts the rate up: " + rate0 + " -> " + FolkCredit.rateBp(id));
        boolean wiped = false;
        for (Tag t : CapitalReport.report(level, id).getCompound("bourse").getList("wiped", Tag.TAG_COMPOUND)) wiped |= ((CompoundTag) t).getString("firm").equals(b.name);
        ok.that(wiped, "its shareholders wiped out after its creditors, on the bourse");
        ok.that(coinNow(id) == c0, "the town's coin the same through the bankruptcy (" + c0 + " -> " + coinNow(id) + ")");
        Enterprise.autoForTests(true);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ cap04 the bourse

    /**
     * The bourse: a holder's ask and a saver's bid matched at the price between them, the coin purse to purse and the parts
     * holder to holder, the last price set; the week's dividend shared by the shares; a thriving firm's issue of new shares
     * bought by savers, the coin into its till and its holders' parts shared down; a skilled hand kept with two shares.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cap04_bourse")
    public static void cap04_bourse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = town(helper, level, 3306000, 60, StationTask.SMITH, StationTask.SMITH, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.FARM, StationTask.GUARD, StationTask.GUARD);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        VillageFolkEntity founder = folk.get(0), saver = folk.get(2), hand = folk.get(1);
        Business b = firm(level, id, founder, "toolsmith", 3000, 800, "trading");
        if (b == null) { helper.fail("no firm"); return; }
        b.till = 20000;
        long c0 = coinNow(id);
        long last0 = Bourse.last(level, id, b);
        int fp0 = founder.purse(), sp0 = saver.purse();
        Bourse.orderForTests(id, b.id, false, founder, 10, last0 - 2, day(level));
        Bourse.orderForTests(id, b.id, true, saver, 10, last0 + 2, day(level));
        Bourse.matchForTests(level, id, day(level));
        int sold = Bourse.sharesForTests(b, saver.getUUID()) / 100;
        long each = Bourse.firmForTests(id, b.id).getLong("last");
        Kit.log("[VT] cap04 traded " + sold + " at " + each + " (asked " + (last0 - 2) + ", bid " + (last0 + 2) + "); purses " + fp0 + "->" + founder.purse()
            + ", " + sp0 + "->" + saver.purse());
        ok.that(sold == 10, "ten shares changed hands: " + sold);
        ok.that(founder.purse() - fp0 == sp0 - saver.purse() && founder.purse() > fp0, "the saver's coin to the founder, purse to purse");
        ok.that(Math.abs(each - last0) <= 2, "the last price set between the bid and the ask: " + each);
        ok.that(coinNow(id) == c0, "the town's coin the same");
        // The week's dividend, by the shares.
        int fd = founder.purse(), sd = saver.purse();
        Businesses.morningForTests(level, v);
        long div = Bourse.firmForTests(id, b.id).getLong("divWeek");
        Kit.log("[VT] cap04 dividend " + div + "; founder +" + (founder.purse() - fd) + ", saver +" + (saver.purse() - sd) + "; " + String.join(" | ", b.notes.subList(Math.max(0, b.notes.size() - 4), b.notes.size())));
        ok.that(div > 0, "the week's dividend drawn: " + Businesses.pence(div));
        ok.that(saver.purse() > sd && founder.purse() - fd >= 8 * (saver.purse() - sd), "shared by the shares (nine to one): founder +" + (founder.purse() - fd) + ", saver +" + (saver.purse() - sd));
        // A thriving firm issues new shares.
        CompoundTag r = Enterprise.recordForTests(id, b);
        if (r != null) r.putString("status", "thriving");
        long till0 = b.till;
        for (VillageFolkEntity f : folk) if (f != founder && f != hand) { f.spend(f.purse()); f.earn(80); }
        Bourse.morningForTests(level, v, day(level));
        CompoundTag fb = Bourse.firmForTests(id, b.id);
        int parts = 0;
        for (Business.Stake s : b.owners) parts += s.parts();
        Kit.log("[VT] cap04 issue: issued " + fb.getInt("issuedAll") + ", raised " + fb.getLong("raisedAll") + "; till " + till0 + " -> " + b.till + "; owners " + b.ownersWords());
        ok.that(fb.getInt("issuedAll") > 0 && b.till > till0, "new shares sold, the coin into its till: " + fb.getInt("issuedAll") + " for " + Businesses.pence(fb.getLong("raisedAll")));
        ok.that(parts == Business.WHOLE, "its holders' parts shared down to make room: " + parts);
        // Profit-sharing: a skilled hand kept with two shares.
        ok.that(Bourse.profitShare(level, id, "firm:" + b.id, hand, day(level)), "the firm kept its hand with a share in its profits");
        ok.that(Bourse.sharesForTests(b, hand.getUUID()) == 200, "two shares out of the founder's stake: " + Bourse.sharesForTests(b, hand.getUUID()));
        ok.that(FolkTalk.card(saver).contains("shares"), "the saver's card has its shares");
        Enterprise.autoForTests(true);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ cap05 land

    /**
     * The land register: a title to every homestead, worth twelve weeks of what it earns; rents follow the land index a
     * twentieth a week at most; a homestead whose hand has gone stands idle two mornings and is taken on a lease by a folk
     * at its trade with none of its own; a player buys the title to a firm's premises from the town, and the rent the firm
     * pays waits for the player at the hall, every coin booked.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cap05_land")
    public static void cap05_land(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = town(helper, level, 3308000, 30, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.SMITH,
            StationTask.GUARD, StationTask.GUARD, StationTask.HAUL);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        List<Business> homes = Privatisation.ensureForTests(level, id);
        Business farm = null;
        for (Business h : Businesses.all(id)) if (h.id.endsWith(folk.get(1).getUUID().toString())) farm = h;
        ok.that(farm != null, "the farmer's homestead is a business: " + homes.size());
        if (farm == null) { helper.fail(ok.summary()); return; }
        long d = day(level);
        LandTitles.morningForTests(level, v, d);
        CompoundTag t = LandTitles.titleForTests(id, "firm:" + farm.id);
        ok.that(t != null && t.getLong("value") > 0 && t.getLong("value") == Math.max(800, t.getLong("earns") * 12), "a title to the homestead, worth twelve weeks of what it earns (its title's worth at least): "
            + (t == null ? "-" : Businesses.pence(t.getLong("value")) + " on " + Businesses.pence(t.getLong("earns"))));
        // Its hand gone: idle two mornings, then leased to a farmer with no homestead of its own.
        folk.get(1).discard();
        VillageFolkEntity newcomer = folk.get(6);
        newcomer.setJob(StationTask.FARM);
        newcomer.keepTradeForTests();
        LandTitles.morningForTests(level, v, d + 1);
        LandTitles.morningForTests(level, v, d + 2);
        Kit.log("[VT] cap05 " + LandTitles.line(id) + "; farm owners " + farm.ownersWords());
        ok.that(farm.ownedBy(newcomer.getUUID()), "the idle homestead taken on a lease by the farmer with none: " + farm.ownersWords());
        // Rents follow the land: the index up, the factor a twentieth a week at most.
        double f0 = LandTitles.rentFactor(id);
        LandTitles.baseForTests(id, 1);
        level.setDayTime((MarketMaker.openedDay(id) + 14) * 24000L + 7000L);
        LandTitles.rentsForTests(id, day(level));
        double f1 = LandTitles.rentFactor(id);
        Kit.log("[VT] cap05 rent factor " + f0 + " -> " + f1 + " (index " + LandTitles.index(id) + ")");
        ok.that(f1 > f0 && f1 - f0 <= 0.0501, "rents follow the land up, a twentieth at most: " + f0 + " -> " + f1);
        // A player buys the title to a firm's premises; the firm's rent goes to the player.
        Business shop = Enterprise.foundForTests(level, v, "toolsmith", folk.get(3));
        ok.that(shop != null, "a toolshop opened");
        if (shop == null) { helper.fail(ok.summary()); return; }
        shop.till = 5000;
        LandTitles.morningForTests(level, v, day(level));
        int n = 0, i = 1;
        for (String line : LandTitles.playerList(id)) { if (line.contains(shop.name) && line.contains("for sale")) n = i; i++; }
        ok.that(n > 0, "the shop's premises are the town's, for sale");
        ServerPlayer p = player(helper, folk.get(0), 500);
        long c0 = coinNow(id), f0n = CoinCensus.netFlowPence(id);
        String said = LandTitles.playerBuys(level, p, id, Math.max(1, n));
        Kit.log("[VT] cap05 " + said);
        ok.that(said.startsWith("You hold the title"), "the player bought the title");
        ok.that(coinNow(id) - c0 == CoinCensus.netFlowPence(id) - f0n && coinNow(id) > c0, "its coin into the town, booked: " + (coinNow(id) - c0));
        long due0 = TownBonds.playersDue(id);
        Businesses.morningForTests(level, v);
        ok.that(TownBonds.playersDue(id) > due0, "the firm's rent waits for the player at the hall: " + Businesses.pence(TownBonds.playersDue(id) - due0));
        long c1 = coinNow(id), f1n = CoinCensus.netFlowPence(id);
        String col = TownBonds.playerCollects(p, id);
        Kit.log("[VT] cap05 " + col);
        ok.that(coinNow(id) - c1 == CoinCensus.netFlowPence(id) - f1n, "collected, booked");
        Enterprise.autoForTests(true);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ cap06 the town's bonds

    /**
     * The town's bonds: owing its market more than a quarter of what it can carry, the treasurer sells bonds to the folk with
     * coin to spare (at the day's rate, within its room) and pays the IOUs off with it, the market's cash restored; short of
     * yesterday's wages, it sells bonds for them; a week on the coupons are paid out of the treasury; due with the treasury
     * empty they are rolled over at the day's rate, and repaid when it can spare them. The rate rises with the debt.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cap06_bonds")
    public static void cap06_bonds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = town(helper, level, 3310000, 40, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.SMITH, StationTask.GUARD, StationTask.GUARD, StationTask.HAUL, StationTask.HAUL);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        Ledger.takeCoins(id, Ledger.coins(id));
        int rate0 = TownBonds.rateBp(id);
        Accounts.debitForTests(level, id, Accounts.Account.dept(Accounts.Dept.WORKS), 6000, Accounts.Purpose.BUILD);
        long ious0 = Accounts.debts(id), cash0 = MarketMaker.cash(id), c0 = coinNow(id);
        int rate1 = TownBonds.rateBp(id);
        Kit.log("[VT] cap06 IOUs " + ious0 + ", limit " + TownBudget.limit(id) + ", room " + TownBonds.room(id) + ", rate " + rate0 + " -> " + rate1);
        ok.that(ious0 == 6000, "the town owes its market 60c on IOUs");
        ok.that(rate1 > rate0, "its rate up with its debt: " + rate0 + " -> " + rate1);
        TownBonds.beforeWagesForTests(level, v);
        long out = TownBonds.outstanding(id);
        Kit.log("[VT] cap06 sold " + out + "; IOUs " + Accounts.debts(id) + "; market cash " + cash0 + " -> " + MarketMaker.cash(id) + "; " + TownBonds.line(id));
        ok.that(out > 0 && out <= TownBonds.ceiling(id), "bonds sold within its room: " + Businesses.pence(out) + " of " + Businesses.pence(TownBonds.ceiling(id)));
        ok.that(Accounts.debts(id) == ious0 - out, "the IOUs paid off with it: " + Businesses.pence(Accounts.debts(id)));
        ok.that(MarketMaker.cash(id) == cash0 + out, "the market has the coin");
        ok.that(coinNow(id) == c0, "the town's coin the same: purses to the market");
        ok.that(FolkTalk.card(folk.get(0)).contains("bonds") || FolkTalk.card(folk.get(1)).contains("bonds"), "a holder's card has its bonds");
        // The wages: short of yesterday's bill, bonds sold for them (room permitting).
        TownBonds.paydayForTests(id, 10, 0);
        Accounts.debitForTests(level, id, Accounts.Account.dept(Accounts.Dept.WORKS), 0, Accounts.Purpose.BUILD);
        long t0 = Businesses.treasuryPence(id), out0 = TownBonds.outstanding(id);
        TownBonds.beforeWagesForTests(level, v);
        Kit.log("[VT] cap06 wages: treasury " + t0 + " -> " + Businesses.treasuryPence(id) + ", bonds " + out0 + " -> " + TownBonds.outstanding(id));
        ok.that(TownBonds.outstanding(id) == out0 || Businesses.treasuryPence(id) > t0, "short of the wages: bonds sold toward them as its room allows");
        // A week on, the coupons; the treasury empty at the due day, rolled over; then repaid.
        long d0 = day(level);
        Ledger.addCoins(id, 50);
        int p0 = 0;
        for (VillageFolkEntity f : folk) p0 += f.purse();
        level.setDayTime((d0 + 7) * 24000L + 7000L);
        TownBonds.morningForTests(level, v, d0 + 7);
        int p1 = 0;
        for (VillageFolkEntity f : folk) p1 += f.purse();
        long coupons = TownBonds.reportForTests(id).getCompound("thisWeek").getLong("coupons") + TownBonds.reportForTests(id).getCompound("lastWeek").getLong("coupons");
        Kit.log("[VT] cap06 coupons " + coupons + "; purses " + p0 + " -> " + p1);
        ok.that(coupons > 0 && p1 >= p0, "the week's coupons paid to the holders out of the treasury: " + Businesses.pence(coupons));
        Ledger.takeCoins(id, Ledger.coins(id));
        long before = TownBonds.outstanding(id);
        level.setDayTime((d0 + 14) * 24000L + 7000L);
        TownBonds.morningForTests(level, v, d0 + 14);
        CompoundTag first = TownBonds.firstForTests(id);
        ok.that(TownBonds.outstanding(id) == before && first != null && first.getLong("due") > d0 + 14, "due with the treasury empty: rolled over at the day's rate");
        Ledger.addCoins(id, 500);
        level.setDayTime((d0 + 28) * 24000L + 7000L);
        TownBonds.morningForTests(level, v, d0 + 28);
        Kit.log("[VT] cap06 after repayment: " + TownBonds.line(id));
        ok.that(TownBonds.outstanding(id) < before, "repaid when the treasury can spare it: " + Businesses.pence(before) + " -> " + Businesses.pence(TownBonds.outstanding(id)));
        Enterprise.autoForTests(true);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ cap07 a player's dealings

    /**
     * Every way a player deals at the town's capital markets moves the town's coin by just what the census booked: savings
     * in and out, a loan against them and its repayment, a bond bought and its coupon collected, shares bought off a folk's
     * ask and sold to a folk's bid (the coin waiting at the hall, collected).
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cap07_players")
    public static void cap07_players(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = town(helper, level, 3312000, 40, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.GUARD, StationTask.GUARD);
        UUID id = folk.get(0).ownerId();
        Kit.Expect ok = new Kit.Expect();
        Business b = firm(level, id, folk.get(0), "toolsmith", 3000, 800, "trading");
        if (b == null) { helper.fail("no firm"); return; }
        ServerPlayer p = player(helper, folk.get(0), 400);
        Accounts.debitForTests(level, id, Accounts.Account.dept(Accounts.Dept.WORKS), Businesses.treasuryPence(id) + 2000, Accounts.Purpose.BUILD);
        List<String> wrong = new ArrayList<>();
        Runnable[] steps = {
            () -> FolkCredit.playerDeposits(level, p, id, 50),
            () -> FolkCredit.playerWithdraws(level, p, id, 10),
            () -> FolkCredit.playerBorrows(level, p, id, 10),
            () -> FolkCredit.playerRepays(level, p, id, 0),
            () -> TownBonds.playerBuys(level, p, id, 10),
            () -> Bourse.orderForTests(id, b.id, false, folk.get(0), 5, Bourse.last(level, id, b), day(level)),
            () -> Bourse.playerBuys(level, p, id, b.name, 5),
            () -> Bourse.orderForTests(id, b.id, true, folk.get(1), 3, Bourse.last(level, id, b) + 5, day(level)),
            () -> Bourse.playerSells(level, p, id, b.name, 3, 0),
            () -> TownBonds.playerCollects(p, id),
        };
        String[] names = { "deposit", "withdraw", "borrow", "repay", "bond", "(a folk's ask)", "buy shares", "(a folk's bid)", "sell shares", "collect" };
        for (int i = 0; i < steps.length; i++) {
            long c0 = coinNow(id), n0 = CoinCensus.netFlowPence(id);
            int held0 = Market.coinsHeld(p);
            steps[i].run();
            long dc = coinNow(id) - c0, dn = CoinCensus.netFlowPence(id) - n0;
            int dh = Market.coinsHeld(p) - held0;
            Kit.log("[VT] cap07 " + names[i] + ": town " + dc + ", booked " + dn + ", player " + dh);
            if (dc != dn) wrong.add(names[i] + " moved " + dc + " but booked " + dn);
            if (dh * 100L != -dc) wrong.add(names[i] + ": the player's coin " + dh + " against the town's " + dc);
        }
        ok.that(wrong.isEmpty(), "every dealing booked in the census: " + wrong);
        ok.that(Bourse.sharesForTests(b, p.getUUID()) == 200, "the player holds two shares after buying five and selling three: " + Bourse.sharesForTests(b, p.getUUID()));
        ok.that(TownBonds.heldBy(id, p.getUUID()) == 1000, "and ten coins of the town's bonds");
        Enterprise.autoForTests(true);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ cap08 kept with the world

    /** The club's savers, a firm's loan and its lender, the bonds, a share trade and a title's landlord, saved and loaded. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cap08_saved")
    public static void cap08_saved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = town(helper, level, 3314000, 50, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.FARM, StationTask.GUARD, StationTask.GUARD);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        for (int i = 1; i < folk.size(); i++) FolkCredit.depositForTests(id, folk.get(i), 20);
        Business b = firm(level, id, folk.get(0), "toolsmith", 2000, 500, "trading");
        if (b == null) { helper.fail("no firm"); return; }
        FolkCredit.lendForTests(level, id, b, 2500);
        Ledger.takeCoins(id, Ledger.coins(id));
        Accounts.debitForTests(level, id, Accounts.Account.dept(Accounts.Dept.WORKS), 3000, Accounts.Purpose.BUILD);
        TownBonds.beforeWagesForTests(level, v);
        Bourse.orderForTests(id, b.id, false, folk.get(0), 4, Bourse.last(level, id, b), day(level));
        Bourse.orderForTests(id, b.id, true, folk.get(2), 4, Bourse.last(level, id, b) + 3, day(level));
        Bourse.matchForTests(level, id, day(level));
        LandTitles.morningForTests(level, v, day(level));
        CapitalBook.morningForTests(level, v, day(level));
        String before = String.join(" | ", CapitalReport.lines(level, id)) + " | " + b.loan + " " + b.lender + " " + b.rateBp + " " + Bourse.sharesForTests(b, folk.get(2).getUUID())
            + " | " + FolkCredit.balance(id, folk.get(3).getUUID()) + " | " + TownBonds.outstanding(id) + " " + TownBonds.countForTests(id);
        CapitalBook.roundTripForTests(id, level.registryAccess());
        Business b2 = Businesses.of(id, b.id);
        String after = String.join(" | ", CapitalReport.lines(level, id)) + " | " + (b2 == null ? "-" : b2.loan + " " + b2.lender + " " + b2.rateBp + " "
            + Bourse.sharesForTests(b2, folk.get(2).getUUID())) + " | " + FolkCredit.balance(id, folk.get(3).getUUID()) + " | " + TownBonds.outstanding(id) + " " + TownBonds.countForTests(id);
        Kit.log("[VT] cap08 before: " + before);
        Kit.log("[VT] cap08 after:  " + after);
        ok.that(before.equals(after), "the capital the same after a save and a load");
        ok.that(b.loan > 0 && TownBonds.outstanding(id) > 0 && Bourse.sharesForTests(b, folk.get(2).getUUID()) == 400, "with something in each part to keep");
        Enterprise.autoForTests(true);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ cap09 the Capital page

    /** The Capital page in the town's books (the Economy section): the lender, the bourse, the land and the bonds. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cap09_page")
    public static void cap09_page(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = town(helper, level, 3316000, 50, StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM,
            StationTask.GUARD, StationTask.GUARD);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Kit.Expect ok = new Kit.Expect();
        Business b = firm(level, id, folk.get(0), "toolsmith", 2000, 500, "thriving");
        if (b == null) { helper.fail("no firm"); return; }
        CapitalBook.morningForTests(level, v, day(level));
        CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(level, v);
        CompoundTag t = books.getCompound("capital");
        Kit.log("[VT] cap09 " + t.getAllKeys() + " " + t.getList("lines", Tag.TAG_STRING));
        ok.that(t.getBoolean("live"), "the Capital page in the town's books");
        ok.that(t.contains("credit") && t.contains("bourse") && t.contains("land") && t.contains("bonds"), "its four parts");
        ok.that(t.getCompound("bourse").getList("rows", Tag.TAG_COMPOUND).size() == 1, "the toolshop on the bourse");
        ok.that(BoardPages.pageNumber("Capital") >= 0, "a page of the books");
        boolean inEconomy = false;
        for (BoardPages.Section s : BoardPages.SECTIONS) if (s.name().equals("Economy")) for (String pg : s.pages()) inEconomy |= pg.equals("Capital");
        ok.that(inEconomy, "in the Economy section");
        ok.that(!CapitalBook.daysForTests(id).isEmpty(), "the day's figures written in the day book");
        Enterprise.autoForTests(true);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }
}
