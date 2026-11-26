package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Blink;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.Calendar;
import com.jrpetty.mcassistant.entity.Church;
import com.jrpetty.mcassistant.entity.Clubs;
import com.jrpetty.mcassistant.entity.Fairground;
import com.jrpetty.mcassistant.entity.FootballEntity;
import com.jrpetty.mcassistant.entity.Friends;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.LifeLog;
import com.jrpetty.mcassistant.entity.LifePage;
import com.jrpetty.mcassistant.entity.LifeWeights;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.MarketDays;
import com.jrpetty.mcassistant.entity.Pitch;
import com.jrpetty.mcassistant.entity.Planner;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.RestDay;
import com.jrpetty.mcassistant.entity.SchoolDays;
import com.jrpetty.mcassistant.entity.Stalls;
import com.jrpetty.mcassistant.entity.TownCalendar;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Visitors;
import com.jrpetty.mcassistant.entity.WeekendLife;
import com.jrpetty.mcassistant.entity.WorkHours;
import com.jrpetty.mcassistant.item.LeisureItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [featlife] The week and the life in it: the calendar's weekend, employers' hours (the folk off on a Saturday, the
 * shopkeeper at work), a weekend market (a folk's stall selling, folk buying for themselves), the fair (a trader walking
 * in from out of sight), character telling over a week, a friendship growing and a dinner invitation, the Sunday service
 * and a christening, a funeral and its wake, a club's meeting and a match on the green. Each on its own ground in the
 * band x 13,800,000 to 13,900,000, z 93,000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class LifeWeekGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 93000;

    /** Flat ground, a town at its heart, so many folk with coin, in the Stone Age and past its first week, keeping its week. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int n, int radius) {
        Kit.reset(level);
        Kit.hold(level, x, Z, radius);
        Kit.prepare(level, x, Z, 48);
        BlockPos ground = Kit.surface(level, x, Z);
        int y = ground.getY();
        for (int dx = -32; dx <= 32; dx++) {
            for (int dz = -32; dz <= 32; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 2, Z + dz), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int h = 0; h <= 14; h++) level.setBlock(new BlockPos(x + dx, y + h, Z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, new BlockPos(x - 3 + (i % 4) * 2, y, Z - 4 + (i / 4) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.earn(40);
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        WorkHours.forceForTests = true;
        WorkHours.cacheOff = true;
        return folk;
    }

    /** The first day on or after {@code from} that is this day of the week. */
    private static long next(long from, Calendar.Day d) {
        return from + Calendar.daysTo(from, d);
    }

    private static long founded(UUID id) {
        return Math.max(0L, com.jrpetty.mcassistant.village.Chronicle.foundedOn(id));
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos at) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        Planner.resetForTests(p.getUUID());
        return p;
    }

    private static CompoundTag button(CompoundTag page, String label) {
        for (Tag t : page.getList("blocks", Tag.TAG_COMPOUND)) {
            ListTag b = ((CompoundTag) t).getList("b", Tag.TAG_COMPOUND);
            for (int i = 0; i < b.size(); i++) if (b.getCompound(i).getString("l").equals(label)) return b.getCompound(i);
        }
        return null;
    }

    private static String pageText(CompoundTag page) {
        StringBuilder sb = new StringBuilder(page.getString("title")).append(" | ").append(page.getString("sub"));
        for (Tag t : page.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            sb.append(" | ").append(c.getString("l")).append(" ").append(c.getString("s")).append(" ").append(c.getString("r"));
        }
        return sb.toString();
    }

    // ============================================================ lf01: the calendar

    /** Seven named days, Saturday and Sunday the weekend; market on Saturday, rest on Sunday, the fair the fourth Saturday; no school at weekends. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lf01_calendar")
    public static void lf01_calendar(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 13800000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3, 48);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            helper.assertTrue(Calendar.name(0).equals("Monday") && Calendar.name(5).equals("Saturday") && Calendar.name(6).equals("Sunday"), "named days");
            helper.assertTrue(!Calendar.isWeekend(4) && Calendar.isWeekend(5) && Calendar.isWeekend(6) && !Calendar.isWeekend(7), "the weekend is Saturday and Sunday");
            helper.assertTrue(Calendar.isFairDay(26) && Calendar.day(26) == Calendar.Day.SATURDAY && Calendar.daysToFair(20) == 6, "the fair, the fourth Saturday");
            long base = founded(id) + 7;
            long sat = next(base, Calendar.Day.SATURDAY), sun = sat + 1, mon = sat + 2;
            helper.assertTrue(Market.marketDay(id, sat) && !Market.marketDay(id, mon), "market day is Saturday");
            helper.assertTrue(RestDay.today(id, sun) && !RestDay.today(id, sat), "the day of rest is Sunday: " + RestDay.today(id, sun));
            helper.assertTrue(SchoolDays.noSchool(id, sat) && SchoolDays.noSchool(id, sun) && !SchoolDays.noSchool(id, mon), "no school at weekends");
            helper.assertTrue(MarketDays.open(id, sat, MarketDays.Kind.WEEKLY) && MarketDays.open(id, sun, MarketDays.Kind.WEEKEND)
                && !MarketDays.open(id, mon, MarketDays.Kind.WEEKEND), "MarketDays reads the calendar");
            level.setDayTime(sat * 24000L + 3000L);
            helper.assertTrue(Calendar.isWeekend(level) && Calendar.isMarketDay(level), "the level's day");
            List<String> board = TownCalendar.board(level, id);
            Kit.log("[VT] lf01 board " + board + " / hud " + Calendar.hudLine(sat));
            helper.assertTrue(String.join(" ", board).contains("Saturday: market day"), "the board names the day");
            helper.assertTrue(Calendar.hudLine(sat).startsWith("Saturday") && Calendar.hudLine(sat).contains("Market day"), "the HUD's line");
            helper.succeed();
        });
    }

    // ============================================================ lf02: employers' hours

    /** On a Saturday the farmer (the town's works) is off and the shopkeeper works; owners choose hours by character; the page shows them. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lf02_hours")
    public static void lf02_hours(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 13810000;
        List<VillageFolkEntity> folk = town(helper, level, x, 5, 48);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            VillageFolkEntity farmer = folk.get(0), keeper = folk.get(1), owner = folk.get(2), easy = folk.get(3);
            farmer.setStation(heart.offset(20, 0, 20), StationTask.FARM);
            keeper.setStation(heart.offset(8, 0, 0), StationTask.SHOP);
            Business shop = Businesses.openForTests(level, id, Business.Type.SHOP, heart.offset(8, 0, 0));
            helper.assertTrue(shop != null && Businesses.employer(id, keeper) == shop, "the shop employs its keeper");
            long sat = next(founded(id) + 7, Calendar.Day.SATURDAY);
            level.setDayTime(sat * 24000L + 3000L);
            Boolean f = WorkHours.shift(farmer), k = WorkHours.shift(keeper);
            Kit.log("[VT] lf02 saturday: farmer " + f + " dayOff " + WorkHours.dayOff(farmer) + ", keeper " + k + " dayOff " + WorkHours.dayOff(keeper)
                + "; farmer onShift " + farmer.onShift());
            helper.assertTrue(Boolean.FALSE.equals(f) && WorkHours.dayOff(farmer) && !farmer.onShift(), "the farmer is off on a Saturday");
            helper.assertTrue(k == null && !WorkHours.dayOff(keeper), "the shopkeeper works its shop's Saturday");
            level.setDayTime((sat + 2) * 24000L + 3000L);
            helper.assertTrue(WorkHours.shift(farmer) == null && !WorkHours.dayOff(farmer), "Monday, the farmer at work");
            // Owners by character.
            LifeWeights.setForTests(owner, LifeWeights.Mark.AMBITIOUS);
            LifeWeights.setForTests(easy, LifeWeights.Mark.EASYGOING);
            Business.Hours amb = LifeWeights.ownerHours(owner, Business.Type.SHOP), lazy = LifeWeights.ownerHours(easy, Business.Type.FARM);
            Kit.log("[VT] lf02 ambitious " + amb.words() + " (" + amb.why() + "); easygoing " + lazy.words() + " (" + lazy.why() + ")");
            helper.assertTrue(amb.dayCount() == 7 && amb.close() >= 20 && amb.why().contains("ambitious"), "the ambitious keep long hours, every day");
            helper.assertTrue(lazy.dayCount() == 4 && lazy.why().contains("easygoing"), "the easygoing four days");
            Business bakery = Businesses.openForTests(level, id, Business.Type.BAKERY, heart.offset(-8, 0, 0));
            bakery.owners.clear();
            bakery.owners.add(new Business.Stake(Business.Holder.FOLK, owner.getUUID(), owner.displayNameCap(), Business.WHOLE));
            Business.Hours chosen = WorkHours.hoursOf(id, bakery);
            helper.assertTrue(bakery.hoursChosen() && chosen.why().contains(owner.displayNameCap()), "its owner chose its hours: " + chosen.why());
            CompoundTag saved = bakery.saveForTests();
            Business back = Business.loadForTests(saved);
            helper.assertTrue(back != null && back.hours().equals(bakery.hours()), "the hours are saved with the business");
            // The page: every business's hours; the button that isn't yours says why.
            ServerPlayer p = player(helper, heart);
            PlayerLife.open(p, "week", LifePage.args("tab", "hours"));
            CompoundTag page = PlayerLife.pageForTests(p);
            String text = pageText(page);
            Kit.log("[VT] lf02 page " + text);
            helper.assertTrue(text.contains(bakery.name) && text.contains(chosen.words()), "the page shows the bakery's hours");
            CompoundTag change = button(page, "Change hours");
            helper.assertTrue(change != null && !change.getBoolean("on") && change.getString("why").contains("owner"), "a business not yours: the button says why");
            helper.assertTrue(String.join(" ", Calendar.book(level, id)).contains(bakery.name), "the books list its hours");
            helper.succeed();
        });
    }

    // ============================================================ lf03: the weekend market

    /** A folk's stall goes up in the square and sells to a neighbour out of its purse; a folk off for the day buys itself a treat at the shop. */
    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "lf03_market")
    public static void lf03_market(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 13820000;
        List<VillageFolkEntity> folk = town(helper, level, x, 4, 48);
        long[] day = new long[1];
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Villages.Village v = Villages.get(id);
            day[0] = next(founded(id) + 7, Calendar.Day.SATURDAY);
            level.setDayTime(day[0] * 24000L + 2000L);
            VillageFolkEntity seller = folk.get(0), buyer = folk.get(1), shopper = folk.get(2);
            BlockPos stall = Stalls.raiseForTests(level, v, seller, List.of(new ItemStack(Items.POPPY, 3)));
            Kit.log("[VT] lf03 stall at " + stall + ": " + Stalls.lines(id));
            helper.assertTrue(stall != null && level.getBlockState(stall).is(Blocks.BARREL), "a stall in the square");
            int sellerBefore = seller.purse(), buyerBefore = buyer.purse();
            String got = com.jrpetty.mcassistant.entity.WeekendLifeTestHooks.buyAtStalls(level, v, buyer, day[0]);
            Kit.log("[VT] lf03 bought " + got + "; seller " + sellerBefore + "->" + seller.purse() + ", buyer " + buyerBefore + "->" + buyer.purse());
            helper.assertTrue(got != null && seller.purse() > sellerBefore && buyer.purse() < buyerBefore, "bought at the stall, the seller paid");
            helper.assertTrue(buyer.countCarried(s -> s.is(Items.POPPY)) == 3, "the poppies are the buyer's");
            helper.assertTrue(LifeLog.week(seller, LifeLog.Stat.SOLD) > 0, "the seller's week counts the sale");
            for (int i = 0; i < 4; i++) Homes.storeForTests(level, v, new ItemStack(Items.CAKE, 1));
            Homes.storeForTests(level, v, new ItemStack(Items.BREAD, 32));
            shopper.earn(60);
            WeekendLife.forceForTests(shopper, WeekendLife.Outing.SHOP);
            helper.assertTrue(WeekendLife.free(shopper), "a Saturday off");
        });
        helper.succeedWhen(() -> {
            VillageFolkEntity shopper = folk.get(2);
            WeekendLife.spend(shopper);
            List<LifeLog.Line> lines = LifeLog.lines(shopper);
            boolean shopped = !lines.isEmpty() && lines.get(0).text().startsWith("Went shopping");
            helper.assertTrue(shopped, "still on its way to the shop: " + shopper.hobbyNow() + " " + lines);
            Kit.log("[VT] lf03 shopper: " + lines.get(0).text() + " | spent this week " + LifeLog.week(shopper, LifeLog.Stat.SPENT));
            helper.assertTrue(LifeLog.week(shopper, LifeLog.Stat.SPENT) > 0, "it paid out of its own purse: " + lines.get(0).text());
        });
    }

    // ============================================================ lf04: the fair

    /** Fair day: a trader from a neighbouring town comes in from out of sight, walks in and sets up a stall; lanterns by the treasury. */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "lf04_fair")
    public static void lf04_fair(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 13830000;
        List<VillageFolkEntity> folk = town(helper, level, x, 5, 160);
        VillageFolkEntity[] trader = new VillageFolkEntity[1];
        double[] startDist = new double[1];
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Villages.Village v = Villages.get(id);
            BlockPos heart = Kit.surface(level, x, Z);
            long fair = founded(id) + 7 + Calendar.daysToFair(founded(id) + 7);
            level.setDayTime(fair * 24000L + 1000L);
            helper.assertTrue(Fairground.on(id, fair), "the fair is on");
            Ledger.addCoins(id, 300);
            Fairground.roundForTests(level, v);
            helper.assertTrue(Fairground.lampsUp(id) > 0, "lanterns round the square, the town can afford them: " + Fairground.lampsUp(id));
            Kit.prepare(level, x, Z, 150);
            Blink.watch(level, heart.getX(), heart.getZ());
            trader[0] = Fairground.traderForTests(level, v);
            helper.assertTrue(trader[0] != null, "a trader on the road in");
            startDist[0] = Math.sqrt(trader[0].distanceToSqr(heart.getX(), heart.getY(), heart.getZ()));
            Kit.log("[VT] lf04 trader " + trader[0].displayNameCap() + " starts " + (int) startDist[0] + " blocks out");
            helper.assertTrue(startDist[0] > Blink.SIGHT, "it starts out of sight of the square: " + (int) startDist[0]);
        });
        helper.succeedWhen(() -> {
            VillageFolkEntity t = trader[0];
            helper.assertTrue(t != null && t.isAlive(), "the trader");
            UUID id = folk.get(0).ownerId();
            String stage = Visitors.stageForTests(t);
            helper.assertTrue("stay".equals(stage), "still walking in: " + stage + ", " + (int) Math.sqrt(t.distanceToSqr(x, t.getY(), Z)) + " out");
            BlockPos stall = Stalls.at(id, t.getUUID());
            helper.assertTrue(stall != null, "its stall is up");
            Kit.log("[VT] lf04 arrived; stalls " + Stalls.lines(id));
            Blink.unwatchAll();
        });
    }

    // ============================================================ lf05: character over a week

    /** Two folk of opposite character, a week: the easygoing spendthrift late, spends more and drinks; the hardworking thrifty one is on time, saves, stays in. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lf05_traits")
    public static void lf05_traits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 13840000;
        List<VillageFolkEntity> folk = town(helper, level, x, 4, 48);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Villages.Village v = Villages.get(id);
            BlockPos heart = Kit.surface(level, x, Z);
            Ledger.built(id, "tavern", heart.offset(-10, 0, 10), Direction.NORTH);
            VillageFolkEntity steady = folk.get(0), easy = folk.get(1);
            steady.setStation(heart.offset(20, 0, 20), StationTask.FARM);
            easy.setStation(heart.offset(-20, 0, 20), StationTask.FARM);
            LifeWeights.setForTests(steady, LifeWeights.Mark.HARDWORKING, LifeWeights.Mark.THRIFTY, LifeWeights.Mark.SHY, LifeWeights.Mark.HOMEBODY, LifeWeights.Mark.DEVOUT);
            LifeWeights.setForTests(easy, LifeWeights.Mark.EASYGOING, LifeWeights.Mark.SPENDTHRIFT, LifeWeights.Mark.CHATTY, LifeWeights.Mark.GRUMPY);
            long mon = next(founded(id) + 7, Calendar.Day.MONDAY);
            // The week's mornings, an hour at a time from six: the easygoing one is late each working day, the other never.
            for (long d = mon; d < mon + 7; d++) {
                for (int hr = 0; hr < 3; hr++) {
                    level.setDayTime(d * 24000L + hr * 1000L + 100L);
                    WorkHours.shift(steady);
                    WorkHours.shift(easy);
                }
            }
            int lateEasy = LifeLog.fortnight(easy, LifeLog.Stat.LATE), lateSteady = LifeLog.fortnight(steady, LifeLog.Stat.LATE);
            steady.earn(60);
            easy.earn(60);
            int budgetSteady = LifeWeights.budget(steady), budgetEasy = LifeWeights.budget(easy);
            long sat = mon + 5;
            level.setDayTime(sat * 24000L + 2000L);
            Map<WeekendLife.Outing, String> whyS = new java.util.EnumMap<>(WeekendLife.Outing.class), whyE = new java.util.EnumMap<>(WeekendLife.Outing.class);
            Map<WeekendLife.Outing, Double> aS = WeekendLife.appeal(level, v, steady, sat, 0, whyS), aE = WeekendLife.appeal(level, v, easy, sat, 0, whyE);
            double churchS = LifeWeights.churchChance(steady), churchE = LifeWeights.churchChance(easy);
            Kit.log("[VT] lf05 late " + lateSteady + " v " + lateEasy + "; budget " + budgetSteady + " v " + budgetEasy + "; tavern "
                + aS.get(WeekendLife.Outing.TAVERN) + " v " + aE.get(WeekendLife.Outing.TAVERN) + "; home " + aS.get(WeekendLife.Outing.HOME) + " v "
                + aE.get(WeekendLife.Outing.HOME) + "; church " + churchS + " v " + churchE + "; why " + whyE);
            helper.assertTrue(lateEasy >= 5 && lateSteady == 0, "the easygoing one late every working day, the other never");
            helper.assertTrue(budgetEasy > budgetSteady * 3, "the spendthrift spends more of the same purse");
            helper.assertTrue(aE.get(WeekendLife.Outing.TAVERN) > aS.get(WeekendLife.Outing.TAVERN) * 2, "the tavern pulls the easygoing");
            helper.assertTrue(aS.get(WeekendLife.Outing.HOME) > aE.get(WeekendLife.Outing.HOME), "the homebody stays in");
            helper.assertTrue(churchS > churchE + 0.4, "the devout goes to church, the grumpy hardly");
            helper.assertTrue("easygoing".equals(whyE.get(WeekendLife.Outing.TAVERN)), "the reason given: " + whyE.get(WeekendLife.Outing.TAVERN));
            // Its card gives the reason.
            WeekendLife.forceForTests(easy, WeekendLife.Outing.TAVERN);
            for (int i = 0; i < 3; i++) WeekendLife.spend(easy);
            easy.moveTo(heart.getX() - 10 + 0.5, heart.getY(), heart.getZ() + 10 + 0.5);
            for (int i = 0; i < 3; i++) WeekendLife.spend(easy);
            String card = LifeLog.cardLine(easy);
            Kit.log("[VT] lf05 card: " + card);
            helper.assertTrue(card.contains("at the tavern (easygoing)"), "the card: " + card);
            helper.succeed();
        });
    }

    // ============================================================ lf06: friends and a dinner

    /** Two folk warm from acquaintances to best friends; a folk who likes a player asks them to dinner, a yes, and the dinner; best friends at a wedding. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lf06_friends")
    public static void lf06_friends(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 13850000;
        List<VillageFolkEntity> folk = town(helper, level, x, 4, 48);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Villages.Village v = Villages.get(id);
            BlockPos heart = Kit.surface(level, x, Z);
            VillageFolkEntity a = folk.get(0), b = folk.get(1), c = folk.get(2), d = folk.get(3);
            List<String> levels = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                Friends.chatted(a, b, 2);
                String lv = Friends.level(a, b.getUUID()).name();
                if (levels.isEmpty() || !levels.get(levels.size() - 1).equals(lv)) levels.add(lv);
            }
            Kit.log("[VT] lf06 levels " + levels);
            helper.assertTrue(levels.contains("FRIEND") && levels.contains("CLOSE") && levels.get(levels.size() - 1).equals("BEST"),
                "acquaintance, friend, close, best: " + levels);
            // A player asked to dinner.
            ServerPlayer p = player(helper, heart.offset(1, 0, 1));
            c.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), 60);
            long thu = next(founded(id) + 7, Calendar.Day.THURSDAY);
            Friends.Invite inv = Friends.invitePlayer(level, v, c, p, thu + 2);
            boolean planned = Planner.of(p.getUUID(), thu).stream().anyMatch(e -> e.kind().equals("dinner"));
            helper.assertTrue(inv != null && planned && Friends.asked(c.getUUID(), p.getUUID()), "the invitation, on the player's planner");
            String said = Friends.talk(c, p, "I'd love to come to dinner");
            Kit.log("[VT] lf06 yes: " + said);
            helper.assertTrue(said != null && "yes".equals(Friends.inviteFor(p.getUUID()).status), "the player said yes");
            int before = c.persona().affinity(p.getUUID());
            level.setDayTime((thu + 2) * 24000L + 6000L);
            c.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5);
            p.moveTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 0.5);
            for (int i = 0; i < 4; i++) com.jrpetty.mcassistant.entity.WeekendLifeTestHooks.dinner(level, c);
            Kit.log("[VT] lf06 dinner: " + Friends.inviteFor(p.getUUID()).status + ", affinity " + before + " -> " + c.persona().affinity(p.getUUID())
                + ", bread " + p.getInventory().countItem(Items.BREAD));
            helper.assertTrue("done".equals(Friends.inviteFor(p.getUUID()).status) && c.persona().affinity(p.getUUID()) > before, "dinner eaten, a better friend");
            // Best friends at a wedding.
            for (int i = 0; i < 45; i++) Friends.chatted(c, d, 2);
            List<String> guests = Friends.weddingGuests(level, v, a.getUUID(), c.getUUID(), thu);
            Kit.log("[VT] lf06 wedding best friends " + guests);
            helper.assertTrue(guests.contains(b.displayNameCap()) && guests.contains(d.displayNameCap()), "the couple's best friends stand with them");
            helper.assertTrue(Friends.cardLine(a).contains("best friend " + b.displayNameCap()), "the card: " + Friends.cardLine(a));
            PlayerLife.open(p, "week", LifePage.args("tab", "friends"));
            helper.assertTrue(pageText(PlayerLife.pageForTests(p)).contains(c.displayNameCap()), "the player's page lists the friend");
            helper.succeed();
        });
    }

    // ============================================================ lf07: the service and a christening

    /** Sunday: the congregation at the service, the chaplain's sermon; a baby christened after, with its parents' best friend at the font. */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "lf07_service")
    public static void lf07_service(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 13860000;
        List<VillageFolkEntity> folk = town(helper, level, x, 6, 48);
        long[] sun = new long[1];
        VillageFolkEntity[] baby = new VillageFolkEntity[1];
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            sun[0] = next(founded(id) + 7, Calendar.Day.SUNDAY);
            level.setDayTime(sun[0] * 24000L + 1200L);
            Church.forceAll = true;
            helper.assertTrue(RestDay.today(id, sun[0]), "Sunday is the day of rest");
            for (VillageFolkEntity f : folk) RestDay.spend(f);
            Kit.log("[VT] lf07 congregation " + Church.congregation(id, sun[0]));
            helper.assertTrue(Church.congregation(id, sun[0]) >= 5, "the congregation at the service");
        });
        helper.runAtTickTime(500, () -> {
            UUID id = folk.get(0).ownerId();
            Kit.log("[VT] lf07 sermon lines said " + Church.sermonLinesSaid(id));
            helper.assertTrue(Church.sermonLinesSaid(id) >= 1, "the chaplain preached");
            // A baby, its parents folk 0 and 1; folk 2 folk 0's best friend.
            VillageFolkEntity mum = folk.get(0), dad = folk.get(1), friend = folk.get(2);
            baby[0] = folk.get(5);
            baby[0].setChild(true);
            baby[0].parentIds().clear();
            baby[0].parentIds().add(mum.getUUID());
            baby[0].parentIds().add(dad.getUUID());
            for (int i = 0; i < 45; i++) Friends.chatted(mum, friend, 2);
            Church.born(baby[0]);
            Church.Christening c = Church.christeningOf(baby[0].getUUID());
            helper.assertTrue(c != null && c.guests.contains(friend.getUUID()) && Calendar.isRestDay(c.day), "a christening on Sunday, the best friend a guest");
            Church.christenTodayForTests(baby[0].getUUID(), sun[0]);
            level.setDayTime(sun[0] * 24000L + 3800L);
        });
        helper.succeedWhen(() -> {
            Church.Christening c = Church.christeningOf(baby[0].getUUID());
            helper.assertTrue(c != null && c.done, "the christening still going");
            Kit.log("[VT] lf07 christened " + c.name);
        });
    }

    // ============================================================ lf08: a funeral and a wake

    /** A folk dies: the next morning its mourners gather, walk to the grave behind the chaplain, the words; then the wake at the tavern. */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "lf08_funeral")
    public static void lf08_funeral(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 13870000;
        List<VillageFolkEntity> folk = town(helper, level, x, 6, 48);
        String[] name = new String[1];
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            Ledger.built(id, "tavern", heart.offset(-8, 0, 8), Direction.NORTH);
            VillageFolkEntity dead = folk.get(5);
            name[0] = dead.displayNameCap();
            dead.kill();
            Church.Funeral fu = Church.funeralOf(id, name[0]);
            helper.assertTrue(fu != null && fu.mourners.size() >= 4, "a funeral, its mourners");
            long day = level.getDayTime() / 24000L;
            Church.funeralTodayForTests(id, name[0], day);
            level.setDayTime(day * 24000L + 2000L);
        });
        helper.succeedWhen(() -> {
            UUID id = folk.get(0).ownerId();
            Church.Funeral fu = Church.funeralOf(id, name[0]);
            helper.assertTrue(fu != null && ("wake".equals(fu.stage) || "done".equals(fu.stage)), "the funeral: " + (fu == null ? "none" : fu.stage));
            int waked = 0;
            for (VillageFolkEntity f : folk) {
                if (!f.isAlive()) continue;
                for (LifeLog.Line l : LifeLog.lines(f)) if (l.text().contains("funeral and the wake")) waked++;
            }
            helper.assertTrue(waked >= 2, "mourners at the wake: " + waked);
            Kit.log("[VT] lf08 " + name[0] + " laid to rest; " + waked + " at the wake");
        });
    }

    // ============================================================ lf09: a club's meeting and a match

    /** The choir meets (its members by character), and the sports team plays a match on the pitch with the town watching. */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "lf09_clubs")
    public static void lf09_clubs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 13880000;
        List<VillageFolkEntity> folk = town(helper, level, x, 10, 48);
        long[] sat = new long[1];
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Villages.Village v = Villages.get(id);
            BlockPos heart = Kit.surface(level, x, Z);
            for (int i = 0; i < 3; i++) LifeWeights.setForTests(folk.get(i), LifeWeights.Mark.MUSICAL, LifeWeights.Mark.DEVOUT);
            for (int i = 3; i < 9; i++) LifeWeights.setForTests(folk.get(i), LifeWeights.Mark.SPORTY, LifeWeights.Mark.CHEERFUL);
            LifeWeights.setForTests(folk.get(9), LifeWeights.Mark.CHATTY);
            helper.assertTrue(Clubs.member(folk.get(0), Clubs.Club.CHOIR) && !Clubs.member(folk.get(9), Clubs.Club.CHOIR), "the choir by character");
            sat[0] = next(founded(id) + 7, Calendar.Day.SATURDAY);
            level.setDayTime(sat[0] * 24000L + 7100L);
            Clubs.forceMeetingForTests(id, Clubs.Club.CHOIR, true);
            Pitch.putUp(level, id, heart.offset(0, 0, 18), Direction.EAST);
            Homes.storeForTests(level, v, new ItemStack(LeisureItems.LEATHER_FOOTBALL.get()));
            String m = Clubs.matchForTests(level, v);
            Kit.log("[VT] lf09 match " + m);
            helper.assertTrue(m != null && m.contains("on the pitch"), "a match on the pitch");
        });
        helper.runAtTickTime(900, () -> {
            UUID id = folk.get(0).ownerId();
            int choir = Clubs.attended(id, Clubs.Club.CHOIR, sat[0]);
            Kit.log("[VT] lf09 choir " + choir + "; score " + Clubs.score(id) + ", crowd " + Clubs.crowd(id));
            helper.assertTrue(choir >= 3, "the choir met: " + choir);
            helper.assertTrue(Clubs.matchOn(id), "the match still on");
            boolean kicked = false;
            for (FootballEntity b : level.getEntitiesOfClass(FootballEntity.class, new net.minecraft.world.phys.AABB(Kit.surface(level, x, Z)).inflate(40))) {
                if (b.lastKicker() != null) kicked = true;
            }
            helper.assertTrue(kicked, "the ball has been kicked");
            helper.assertTrue(Clubs.crowd(id) >= 1, "folk watching from the touchline: " + Clubs.crowd(id));
            Villages.Village v = Villages.get(id);
            Clubs.endForTests(level, v);
            helper.assertTrue(Clubs.lastResult(id) != null && !Clubs.matchOn(id), "full time: " + Clubs.lastResult(id));
            helper.succeed();
        });
    }
}
