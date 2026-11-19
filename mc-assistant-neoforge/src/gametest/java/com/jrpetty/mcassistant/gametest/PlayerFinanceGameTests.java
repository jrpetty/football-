package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bourse;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.FinanceSheet;
import com.jrpetty.mcassistant.entity.FolkCredit;
import com.jrpetty.mcassistant.entity.LabourMarket;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.MarketMaker;
import com.jrpetty.mcassistant.entity.PlayerCoinPaths;
import com.jrpetty.mcassistant.entity.PlayerFinance;
import com.jrpetty.mcassistant.entity.PlayerFinance.Cat;
import com.jrpetty.mcassistant.entity.TownBonds;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [purse20] A player's own finances (PlayerFinance, FinanceSheet): every coin the census books for a player, through
 * every path the player-coin tests drive (the talk, the law, the counters: a shift's pay, a stall's till, rent, the
 * bank, fines), is in its ledger to the coin, and in a category; the capital markets' dealings in theirs (savings,
 * a loan and its repayment, bonds and a coupon, shares bought and sold, the money waiting at the hall); net worth is
 * assets less liabilities and its lines match the holdings; the history and the ledger are capped; it all survives a
 * save and a load; and another player cannot ask for yours.
 *
 * <p>Each on its own ground, x 5000000 to 5099999 (2000 apart and more), z 90000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PlayerFinanceGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    private record Town(UUID id, Villages.Village v, List<VillageFolkEntity> folk) {}

    // ============================================================ the ground (as PlayerCoinGameTests)

    private static Town town(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        level.setDayTime(2000L);
        Kit.hold(level, x, Z, 72);
        Kit.prepare(level, x, Z, 72);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        List<VillageFolkEntity> folk = new ArrayList<>();
        folk.add(first);
        for (int i = 0; i < 6; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.south(2 + i).east(1), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i);
            f.setJob(StationTask.NONE);
            folk.add(f);
        }
        StorehouseBlockEntity store = storehouse(helper, level, heart);
        Villages.ageForTests(id, Villages.Age.STONE);
        for (ItemStack g : List.of(new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.BREAD, 32))) {
            for (int i = 0; i < store.getContainerSize(); i++) if (store.getItem(i).isEmpty()) { store.setItem(i, g.copy()); break; }
        }
        store.setChanged();
        Villages.forgetStock();
        Villages.forgetStores(id);
        com.jrpetty.mcassistant.entity.Budget.forget(id);
        Ledger.addCoins(id, 1500);
        for (VillageFolkEntity f : folk) f.earn(30);
        return new Town(id, Villages.get(id), folk);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart) {
        for (BlockPos p : Villages.storeChests(level, Villages.nearest(level, heart, Villages.VILLAGE_RANGE).id())) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        BlockPos origin = Kit.surface(level, heart.getX() + 8, heart.getZ() + 8);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        net.minecraft.world.level.block.state.BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) level.setBlock(origin.offset(x, y, z), unit, 3);
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands");
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    private static ServerPlayer player(GameTestHelper helper, VillageFolkEntity near, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.teleportTo(near.getX() + 1.5, near.getY(), near.getZ() + 0.5);
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        PlayerFinance.resetForTests(p.getUUID());
        return p;
    }

    /**
     * Each path in turn: what each player's pack gained or lost against what its ledger booked through the pack (both in
     * pennies), and that every entry booked has a category. Paths that hold a player's coin without booking it (a bid or a
     * deposit held for it, still its own) are named in {@code held}: there the ledger books it when it is spent.
     */
    private static void drive(GameTestHelper helper, String test, List<PlayerCoinPaths.Path> paths, List<String> held, ServerPlayer... players) {
        List<String> wrong = new ArrayList<>();
        int booked = 0;
        try {
            for (PlayerCoinPaths.Path path : paths) {
                path.setup().run();
                long[] pack0 = new long[players.length], led0 = new long[players.length], seq0 = new long[players.length];
                for (int i = 0; i < players.length; i++) {
                    pack0[i] = Market.coinsHeld(players[i]) * 100L;
                    led0[i] = PlayerFinance.cashSumForTests(players[i].getUUID());
                    seq0[i] = PlayerFinance.seqForTests(players[i].getUUID());
                }
                String said;
                try {
                    said = path.run().get();
                } catch (RuntimeException e) {
                    said = "threw " + e;
                }
                for (int i = 0; i < players.length; i++) {
                    long pack = Market.coinsHeld(players[i]) * 100L - pack0[i];
                    long led = PlayerFinance.cashSumForTests(players[i].getUUID()) - led0[i];
                    long n = PlayerFinance.seqForTests(players[i].getUUID()) - seq0[i];
                    booked += n;
                    Kit.log("[VT] " + test + " " + path.name() + " (player " + i + "): pack " + pack + "p, ledger " + led + "p, " + n + " entries; said: "
                        + (said == null ? "-" : said.length() > 120 ? said.substring(0, 120) : said));
                    if (pack != led && !held.contains(path.name())) wrong.add(path.name() + " (player " + i + ": pack " + pack + "p, ledger " + led + "p)");
                }
            }
        } finally {
            Kit.noLeftoverPlayers(helper.getLevel());
        }
        helper.assertTrue(wrong.isEmpty(), "every booked coin in the player's ledger: " + wrong);
        helper.assertTrue(booked > 0, "something was booked");
        for (ServerPlayer p : players) {
            for (Tag t : PlayerFinance.ledger(p.getUUID())) {
                CompoundTag e = (CompoundTag) t;
                helper.assertTrue(!e.getString("c").isEmpty() && !e.getString("w").isEmpty(), "every entry has a kind and a what: " + e);
            }
        }
    }

    // ============================================================ pf01-pf03 every path the player-coin tests drive

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pf01_talk")
    public static void pf01_talk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 5000100);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity f = t.folk().get(1);
            ServerPlayer p = player(helper, f, 400), q = player(helper, f, 0);
            drive(helper, "pf01", PlayerCoinPaths.talk(level, t.v(), f, p, q), List.of(), p, q);
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pf02_law")
    public static void pf02_law(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 5002300);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity guard = t.folk().get(1);
            guard.setJob(StationTask.GUARD);
            UUID elderId = Villages.elder(t.id());
            VillageFolkEntity elder = t.folk().get(0);
            for (VillageFolkEntity f : t.folk()) if (f.getUUID().equals(elderId)) elder = f;
            ServerPlayer p = player(helper, guard, 400), q = player(helper, guard, 0);
            drive(helper, "pf02", PlayerCoinPaths.law(level, t.v(), guard, elder, p, q), List.of(), p, q);
            // A fine is booked as a fine.
            long fines = PlayerFinance.span(p.getUUID(), level.getDayTime() / 24000L, 1).getOrDefault(Cat.FINES, 0L)
                + PlayerFinance.span(q.getUUID(), level.getDayTime() / 24000L, 1).getOrDefault(Cat.FINES, 0L);
            helper.assertTrue(fines < 0, "the fines paid are in the fines' category: " + fines);
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pf03_counters")
    public static void pf03_counters(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 5004500);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity f = t.folk().get(2);
            ServerPlayer p = player(helper, f, 400);
            drive(helper, "pf03", PlayerCoinPaths.counters(level, t.v(), f, p), List.of(), p);
            Map<Cat, Long> today = PlayerFinance.span(p.getUUID(), level.getDayTime() / 24000L, 1);
            Kit.log("[VT] pf03 categories: " + today);
            helper.assertTrue(today.getOrDefault(Cat.STALL, 0L) > 0, "the stall's till is stall takings: " + today);
            helper.assertTrue(today.getOrDefault(Cat.RENT_IN, 0L) > 0, "rent collected is rent received: " + today);
            helper.assertTrue(today.containsKey(Cat.SAVED) || today.containsKey(Cat.DRAWN), "the bank's deposit and withdrawal are savings moved: " + today);
            long[] io = PlayerFinance.inOut(today);
            helper.assertTrue(io[0] > 0, "income booked");
            helper.succeed();
        });
    }

    // ============================================================ pf04 the capital markets, and net worth

    /**
     * Savings in and out, a loan against them and its repayment, a bond bought and a week later its coupon collected,
     * shares bought off a folk's ask and sold to a folk's bid (the price waiting at the hall, then collected): each in its
     * own category, the pack and the ledger agreeing; then the balance sheet: net worth is assets less liabilities, and
     * its lines are the holdings (the savings club, the bonds at face, the shares at the last price, the loan).
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pf04_capital")
    public static void pf04_capital(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        Enterprise.resetForTests();
        Enterprise.autoForTests(false);
        LabourMarket.onPaperForTests(true);
        level.setDayTime(8 * 24000L + 7000L);
        int x = 5006700;
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        List<VillageFolkEntity> folk = new ArrayList<>();
        StationTask[] trades = { StationTask.SMITH, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.GUARD };
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 6 + i * 2, Z - 4), 0.0F);
            helper.assertTrue(f != null, "folk " + i);
            f.ensurePersona();
            f.setJob(trades[i]);
            f.keepTradeForTests();
            f.spend(f.purse());
            f.earn(60);
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        Ledger.addCoins(id, 600);
        MarketMaker.openForTests(level, Villages.get(id));
        Business b = Enterprise.foundForTests(level, Villages.get(id), "toolsmith", folk.get(0));
        if (b == null) { helper.fail("no firm"); return; }
        long day = level.getDayTime() / 24000L;
        b.opened = day - 13;
        for (int k = 10; k >= 1; k--) { Business.Day d = b.today(day - k); d.revenue = 3000; d.cogs = 2200; }
        ServerPlayer p = player(helper, folk.get(0), 400);
        UUID me = p.getUUID();
        Kit.Expect ok = new Kit.Expect();
        long led0 = PlayerFinance.cashSumForTests(me);
        int pack0 = Market.coinsHeld(p);
        Kit.log("[VT] pf04 " + FolkCredit.playerDeposits(level, p, id, 50));
        Kit.log("[VT] pf04 " + FolkCredit.playerWithdraws(level, p, id, 10));
        Kit.log("[VT] pf04 " + FolkCredit.playerBorrows(level, p, id, 10));
        Kit.log("[VT] pf04 " + FolkCredit.playerRepays(level, p, id, 4));
        Kit.log("[VT] pf04 " + TownBonds.playerBuys(level, p, id, 20));
        Bourse.orderForTests(id, b.id, false, folk.get(0), 5, Bourse.last(level, id, b), day);
        Kit.log("[VT] pf04 " + Bourse.playerBuys(level, p, id, b.name, 5));
        Bourse.orderForTests(id, b.id, true, folk.get(1), 3, Bourse.last(level, id, b) + 5, day);
        Kit.log("[VT] pf04 " + Bourse.playerSells(level, p, id, b.name, 3, 0));
        Kit.log("[VT] pf04 collect: " + TownBonds.playerCollects(p, id));
        // A week on, the bond's coupon, waiting at the hall, then collected.
        TownBonds.morningForTests(level, Villages.get(id), day + 7);
        Kit.log("[VT] pf04 coupon collect: " + TownBonds.playerCollects(p, id));
        long pack = (Market.coinsHeld(p) - pack0) * 100L, led = PlayerFinance.cashSumForTests(me) - led0;
        ok.that(pack == led, "the pack moved " + pack + "p and the ledger booked " + led + "p");
        Map<Cat, Long> cats = PlayerFinance.span(me, day, 1);
        Kit.log("[VT] pf04 categories: " + cats);
        ok.that(cats.getOrDefault(Cat.SAVED, 0L) == -5000, "into savings, 50 coins: " + cats.get(Cat.SAVED));
        ok.that(cats.getOrDefault(Cat.DRAWN, 0L) == 1000, "out of savings, 10: " + cats.get(Cat.DRAWN));
        ok.that(cats.getOrDefault(Cat.BORROWED, 0L) > 0, "a loan: " + cats.get(Cat.BORROWED));
        ok.that(cats.getOrDefault(Cat.LOAN_PAY, 0L) < 0, "a repayment: " + cats.get(Cat.LOAN_PAY));
        ok.that(cats.getOrDefault(Cat.INVESTED, 0L) < 0, "bonds and shares bought: " + cats.get(Cat.INVESTED));
        ok.that(cats.getOrDefault(Cat.CASHED, 0L) > 0, "the shares' price, collected at the hall: " + cats.get(Cat.CASHED));
        ok.that(cats.getOrDefault(Cat.COUPONS, 0L) > 0, "the bond's coupon: " + cats.get(Cat.COUPONS));
        // The balance sheet.
        FinanceSheet.Sheet s = FinanceSheet.work(level, me, p);
        ok.that(s.net() == s.assetsTotal() - s.liabilitiesTotal(), "net worth is assets less liabilities");
        ok.that(s.group(FinanceSheet.CASH, false) == Market.coinsHeld(p) * 100L, "the cash line is the coin carried: " + s.group(FinanceSheet.CASH, false));
        long club = 0, bonds = 0, shares = 0, loan = 0;
        for (FinanceSheet.Row r : s.assets) {
            if (r.label().equals("Savings club")) club += r.value();
            if (r.label().equals("Town bonds")) bonds += r.value();
            if (r.label().startsWith("Shares: ")) shares += r.value();
        }
        for (FinanceSheet.Row r : s.liabilities) if (r.label().startsWith("Loan from")) loan += r.value();
        ok.that(club == FolkCredit.balance(id, me) * 100L, "the savings line is the balance: " + club + " against " + FolkCredit.balance(id, me));
        ok.that(bonds == TownBonds.heldBy(id, me), "the bonds at face: " + bonds + " against " + TownBonds.heldBy(id, me));
        ok.that(shares == Bourse.last(level, id, b) * Bourse.sharesForTests(b, me) / 100, "two shares at the last price: " + shares);
        ok.that(loan > 0, "the rest of the loan owed: " + loan);
        CompoundTag page = FinanceSheet.page(level, me, p, true);
        ok.that(page.getLong("net") == s.net() && page.getList("ledger", Tag.TAG_COMPOUND).size() > 0 && page.getLongArray("sNet").length == PlayerFinance.DAYS,
            "the page carries the net worth, the ledger and sixty days of charts");
        Enterprise.autoForTests(true);
        Kit.noLeftoverPlayers(level);
        helper.assertTrue(ok.clean(), ok.summary());
        helper.succeed();
    }

    // ============================================================ pf05 kept, capped, saved and loaded, private

    /**
     * The ledger keeps its last two hundred entries; the days and the history their last sixty; all of it the same
     * after a save and a load; and a player who is not an operator cannot ask for another's finances.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pf05_kept")
    public static void pf05_kept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 5008900);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity f = t.folk().get(1);
            ServerPlayer p = player(helper, f, 50), q = player(helper, f, 10);
            UUID me = p.getUUID();
            long day = level.getDayTime() / 24000L;
            Kit.Expect ok = new Kit.Expect();
            PlayerFinance.floodForTests(me, 250);
            ListTag l = PlayerFinance.ledger(me);
            ok.that(l.size() == PlayerFinance.LEDGER, "the ledger capped at 200: " + l.size());
            ok.that(l.getCompound(l.size() - 1).getString("w").equals("test entry 249") && l.getCompound(0).getString("w").equals("test entry 50"),
                "the newest kept, the oldest gone");
            level.setDayTime((day + 80) * 24000L + 2000L);
            long now = day + 80;
            for (int k = 0; k < 75; k++) {
                PlayerFinance.dayForTests(me, now - k, Cat.WAGES, 100);
                PlayerFinance.worthForTests(me, now - k, 1000 + k);
            }
            ok.that(PlayerFinance.daysKeptForTests(me) <= PlayerFinance.DAYS, "sixty days of categories kept: " + PlayerFinance.daysKeptForTests(me));
            ok.that(PlayerFinance.worthKeptForTests(me) <= PlayerFinance.DAYS, "sixty days of history kept: " + PlayerFinance.worthKeptForTests(me));
            long wages = PlayerFinance.span(me, now, 7).getOrDefault(Cat.WAGES, 0L);
            ok.that(wages == 700, "a week of wages: " + wages);
            // A save and a load.
            long sum = PlayerFinance.cashSumForTests(me);
            PlayerFinance.roundTripForTests(level);
            ok.that(PlayerFinance.ledger(me).size() == PlayerFinance.LEDGER && PlayerFinance.cashSumForTests(me) == sum
                && PlayerFinance.span(me, now, 7).getOrDefault(Cat.WAGES, 0L) == 700 && PlayerFinance.history(me).size() == PlayerFinance.worthKeptForTests(me),
                "the same after a save and a load");
            // Privacy: another player asks for p's finances by name.
            String refused = FinanceSheet.refusal(q, p.getGameProfile().getName());
            ok.that(refused != null, "another player is refused: " + refused);
            ok.that(FinanceSheet.whose(q, "").equals(q.getUUID()) && FinanceSheet.refusal(q, "") == null, "a player may see its own");
            level.setDayTime(2000L);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(ok.clean(), ok.summary());
            helper.succeed();
        });
    }
}
