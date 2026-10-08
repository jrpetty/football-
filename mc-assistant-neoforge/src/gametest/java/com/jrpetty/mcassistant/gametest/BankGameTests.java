package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bank;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The bank (entity/Bank): nothing changes until it stands; the day it stands it opens, and the most
 * careful hand it can spare keeps it; savings go from the purses into the vault and back out again
 * when the rent wants them, a thrifty folk putting by more than a spendthrift; a household with a
 * fifth of its house's price put by borrows the rest and buys, and pays the bank back a week at a
 * time, the loan shrinking; the week's interest on the savings is paid out of what the loans earned,
 * the bank stays sound and the treasury has its share; a player keeps an account and takes a
 * mortgage too; and a household that stops paying loses the house to the bank after three weeks
 * behind, and stays on in it as the village's tenant.
 *
 * <p>Every step counts the village's coin — the purses, the treasury, what is put by toward houses,
 * the vault, and a player's pack — before and after: none of it is made or lost by the bank.
 * All of it at x 270000–277000, z 50000, each test on its own ground.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BankGameTests {

    private static final String EMPTY = "empty";

    /** A test village: its id, the village, its heart, its folk, its houses, and where its bank stands (or will). */
    private record Town(UUID id, Villages.Village v, BlockPos heart, List<VillageFolkEntity> folk, List<BlockPos> houses, BlockPos bank) {}

    /** A village of so many folk in this age, with so many houses stamped and on the books; the bank stamped too if asked. */
    private static Town town(GameTestHelper helper, ServerLevel level, int x, int z, int folk, int houses, Villages.Age age, boolean bank) {
        Kit.reset(level);
        Bank.resetForTests();
        level.setDayTime(24000L * 8 + 7000);                        // past the morning's business: only ours runs
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        helper.assertTrue(v != null, "the village is on the books");
        List<VillageFolkEntity> all = new ArrayList<>();
        all.add(first);
        for (int i = 1; i < folk; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 2 * i, z), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i + " of the village");
            all.add(f);
        }
        Villages.ageForTests(id, age);
        for (VillageFolkEntity f : all) {
            f.spend(f.purse());
            f.rentFree(false);                                       // come since the founding: they pay rent
        }
        List<BlockPos> hs = new ArrayList<>();
        for (int n = 0; n < houses; n++) {
            BlockPos at = Kit.surface(level, x - 30 + 14 * n, z + 22);
            BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", at, Direction.NORTH);
            hs.add(at);
        }
        BlockPos bankAt = Kit.surface(level, x + 22, z - 22);
        if (bank) standBank(level, id, bankAt);
        return new Town(id, v, heart, all, hs, bankAt);
    }

    /** The bank put up and on the village's books (as the builders leave it when it is done). */
    private static void standBank(ServerLevel level, UUID id, BlockPos at) {
        BuildGoal.stamp(level, "bank", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "bank", at, Direction.NORTH);
    }

    /** What it cares about most, and its two traits. */
    private static void nature(VillageFolkEntity f, Values.Value top, Social.Trait... traits) {
        for (Values.Value val : Values.Value.values()) Values.setForTests(f, val, val == top ? 100 : 0);
        f.life().setTraitsForTests(traits);
    }

    /** Every coin of the village: its folk's purses, the treasury, what is put by toward houses, the vault; and these players' packs. */
    private static long coin(UUID id, Player... players) {
        long n = (long) Ledger.coins(id) + Bank.cash(id) + Homes.savedTotal(id);
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f) n += f.purse();
        for (Player p : players) n += Market.coinsHeld(p);
        return n;
    }

    /** Has the chronicle told anything with these words in it? */
    private static boolean told(UUID id, String words) {
        for (com.jrpetty.mcassistant.village.Chronicle.Entry e : com.jrpetty.mcassistant.village.Chronicle.of(id)) if (e.text().contains(words)) return true;
        for (Villages.News n : Villages.news(id)) if (n.text().contains(words)) return true;
        return false;
    }

    /** A marked store chest beside the heart, filled with these. */
    private static void stores(ServerLevel level, UUID id, BlockPos heart, ItemStack... goods) {
        BlockPos chest = Kit.surface(level, heart.getX() + 4, heart.getZ() - 4);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStores(id);
    }

    // ------------------------------------------------------------------ savings

    /**
     * The bank's building is drawn and planned; nothing changes until it stands; the day it stands it
     * opens (told in the chronicle) and the most careful hand it can spare keeps it — a shrewd Merchant
     * before a steady soul or a spendthrift, never the only smith. Savings go from purse to vault, a
     * thrifty folk's three parts in four, a steady one's less, a spendthrift's none; a saver short of
     * its week's needs draws them out again; a player deposits and withdraws; and no coin is made or
     * lost. The banker sets the vault's bars and lays the ledger on the lectern out of the stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bank_t1_savings")
    public static void bank_t1_savings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.Expect ok = new Kit.Expect();
        // The drawing, and where it goes.
        var parts = BuildGoal.partCounts("bank", 13);
        Kit.log("bank t1 the drawing: " + Blueprints.cells("bank").size() + " blocks, " + parts + "; a " + TownPlan.placeFor("bank") + " lot");
        ok.that(BuildGoal.STRUCTURES.contains("bank") && Blueprints.has("bank"), "the builders can build a bank from its drawing");
        ok.that(parts.getOrDefault(BuildGoal.Part.LECTERN, 0) == 1 && parts.getOrDefault(BuildGoal.Part.DOOR, 0) == 1
            && parts.getOrDefault(BuildGoal.Part.BARREL, 0) >= 4, "a door, a lectern for the ledger and the vault's strongboxes: " + parts);
        ok.that("civic".equals(TownPlan.placeFor("bank")), "on one of the trades' lots facing the square");

        Town t = town(helper, level, 270500, 50000, 4, 0, Villages.Age.IRON, false);
        UUID id = t.id();
        VillageFolkEntity merchant = t.folk().get(0), spender = t.folk().get(1), steady = t.folk().get(2), smith = t.folk().get(3);
        nature(merchant, Values.Value.WEALTH, Social.Trait.HARDWORKING, Social.Trait.GRUMPY);
        nature(spender, Values.Value.LEISURE, Social.Trait.EASYGOING, Social.Trait.GENEROUS);
        nature(steady, Values.Value.FOOD, Social.Trait.CURIOUS, Social.Trait.SHY);
        nature(smith, Values.Value.TRADITION, Social.Trait.EASYGOING, Social.Trait.GENEROUS);
        for (VillageFolkEntity f : List.of(merchant, spender, steady)) f.setJob(StationTask.FARM);
        smith.setJob(StationTask.SMITH);
        Kit.log("bank t1 natures: merchant " + Bank.thrift(merchant) + " (" + Bank.thriftWord(Bank.thrift(merchant)) + "), spender " + Bank.thrift(spender)
            + ", steady " + Bank.thrift(steady) + ", smith " + Bank.thrift(smith));

        // No bank yet: the morning changes nothing.
        merchant.earn(100);
        Bank.morningForTests(level, t.v());
        ok.that(!Bank.open(id) && Bank.cash(id) == 0 && merchant.purse() == 100, "no bank, no savings taken: purse " + merchant.purse());

        // It stands: it opens, and gets its banker. A tree's leaves and a tuft of grass got into its rooms
        // as it went up: they are swept out the day it opens.
        standBank(level, id, t.bank());
        Direction right = Direction.NORTH.getClockWise();
        BlockPos leaf = t.bank().above(2).relative(right, -1), tuft = t.bank().relative(right, 1).relative(Direction.NORTH, -2);
        level.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState(), 2);
        level.setBlock(tuft, Blocks.SHORT_GRASS.defaultBlockState(), 2);
        boolean open = Bank.openForTests(level, t.v());
        ok.that(level.getBlockState(leaf).isAir() && level.getBlockState(tuft).isAir(), "nothing of a tree or the ground left in its rooms: "
            + level.getBlockState(leaf) + ", " + level.getBlockState(tuft));
        VillageFolkEntity banker = Bank.banker(id);
        Kit.log("bank t1 opened " + open + ", banker " + (banker == null ? "none" : banker.displayNameCap() + " " + banker.stationTask())
            + " | " + Bank.line(id));
        ok.that(open && told(id, "the bank opened its doors"), "the bank opens the day it stands, and the chronicle tells it");
        ok.that(banker == merchant && merchant.stationTask() == StationTask.BANK, "the shrewd Merchant keeps it: " + (banker == null ? "nobody" : banker.displayNameCap()));
        ok.that(smith.stationTask() == StationTask.SMITH && steady.stationTask() == StationTask.FARM, "the others stay at their trades");

        // The morning's savings.
        spender.earn(100);
        steady.earn(100);
        long before = coin(id);
        int keepM = Bank.keepForTests(merchant), keepS = Bank.keepForTests(steady);
        Bank.morningForTests(level, t.v());
        int bm = Bank.balance(merchant), bs = Bank.balance(spender), bt = Bank.balance(steady);
        Kit.log("bank t1 savings: merchant " + bm + " in, " + merchant.purse() + " loose (keeps " + keepM + "); spender " + bs + " in, "
            + spender.purse() + " loose; steady " + bt + " in, " + steady.purse() + " loose (keeps " + keepS + "); vault " + Bank.cash(id)
            + "; coin " + before + " -> " + coin(id));
        ok.that(bm > bt && bt > 0, "a thrifty folk puts more by than a steady one: " + bm + " against " + bt);
        ok.that(bs == 0 && spender.purse() == 100, "a spendthrift puts nothing by: " + bs);
        ok.that(merchant.purse() >= keepM && steady.purse() >= keepS, "each keeps its week's needs in its purse");
        ok.that(bm + bt == Bank.cash(id) && Bank.deposits(id) == bm + bt, "the savings are in the vault: " + Bank.cash(id));
        ok.that(coin(id) == before, "no coin made or lost: " + before + " -> " + coin(id));
        ok.that(Wealth.worth(merchant) >= merchant.purse() + bm, "its savings at the bank count in its worth: " + Wealth.worth(merchant));
        String card = FolkTalk.card(merchant);
        String cardLow = card.toLowerCase(java.util.Locale.ROOT);
        ok.that(card.contains("Bank|") && cardLow.contains("saved at the bank") && cardLow.contains("keeps the bank"), "its card shows its savings and its bank: " + card);

        // Short of its week's needs: it draws on its savings before the rent.
        merchant.spend(merchant.purse());
        long b2 = coin(id);
        int was = Bank.balance(merchant);
        Bank.morningForTests(level, t.v());
        Kit.log("bank t1 drawn: merchant purse " + merchant.purse() + ", account " + was + " -> " + Bank.balance(merchant));
        ok.that(merchant.purse() == Bank.keepForTests(merchant) && Bank.balance(merchant) == was - merchant.purse(),
            "a saver short of what the week wants draws it out: purse " + merchant.purse() + ", account " + Bank.balance(merchant));
        ok.that(coin(id) == b2, "coin conserved: " + b2 + " -> " + coin(id));

        // A player's account.
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        you.setPos(t.heart().getX() + 0.5, t.heart().getY(), t.heart().getZ() + 0.5);
        you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 30));
        long b3 = coin(id, you);
        String in = Bank.playerForTests(level, id, you, "deposit 20");
        int afterIn = Market.coinsHeld(you), balIn = Bank.balanceForTests(id, you.getUUID());
        String out = Bank.playerForTests(level, id, you, "withdraw 5");
        Kit.log("bank t1 a player: " + in + " | " + out + " | held " + Market.coinsHeld(you));
        ok.that(afterIn == 10 && balIn == 20, "a player deposits coin from its pack: held " + afterIn + ", account " + balIn);
        ok.that(Market.coinsHeld(you) == 15 && Bank.balanceForTests(id, you.getUUID()) == 15, "and draws it out again");
        ok.that(coin(id, you) == b3, "coin conserved with the player's pack: " + b3 + " -> " + coin(id, you));

        // The banker's work: the vault's bars and the ledger on the lectern, out of the stores.
        stores(level, id, t.heart(), new ItemStack(Items.IRON_BARS, 8), new ItemStack(Items.BOOK), new ItemStack(Items.INK_SAC), new ItemStack(Items.FEATHER));
        boolean w1 = false, w2 = false;
        for (int i = 0; i < 4; i++) {                                    // a piece of work at a time: the bars, then the ledger
            boolean did = Crafts.now(merchant, level, t.v());
            if (i == 0) w1 = did;
            else w2 |= did;
        }
        int bars = 0;
        for (BlockPos p : Bank.barSpotsForTests(id)) if (level.getBlockState(p).is(Blocks.IRON_BARS)) bars++;
        BlockPos lec = Bank.lecternForTests(id);
        BlockState ls = lec == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(lec);
        boolean book = ls.getBlock() instanceof LecternBlock && ls.getValue(LecternBlock.HAS_BOOK);
        Kit.log("bank t1 the banker's work: " + w1 + "/" + w2 + ", " + bars + " bars of " + Bank.barSpotsForTests(id).size() + ", ledger " + book
            + " at " + lec + " (" + ls + ")");
        ok.that(bars == Bank.barSpotsForTests(id).size() && bars == 4, "the vault's gate and grille barred: " + bars);
        ok.that(book, "the ledger written up and laid on the lectern");
        net.minecraft.nbt.CompoundTag report = Bank.report(level, id);
        Kit.log("bank t1 the books: " + report);
        ok.that(report.getBoolean("open") && report.getInt("deposits") == Bank.deposits(id), "the books show the bank");
        if (!ok.clean()) helper.fail(ok.summary());
        helper.succeed();
    }

    /**
     * The camera's bank (/village bank showcase), stood up where trees grow: every tree reaching into
     * it or round it is felled whole first, so no wood or leaves are left in its rooms or hanging over
     * it; its banker is held at the counter facing the door, the vault barred and the ledger on its
     * lectern; and "showcase done" lets the banker go.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bank_t4_showcase")
    public static void bank_t4_showcase(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.Expect ok = new Kit.Expect();
        Town t = town(helper, level, 276000, 50000, 3, 0, Villages.Age.IRON, false);
        UUID id = t.id();
        t.folk().get(0).setJob(StationTask.NONE);                      // a hand to spare for the counter
        for (int i = 1; i < t.folk().size(); i++) t.folk().get(i).setJob(StationTask.FARM);
        // Trees where the bank will go: ten blocks north of where the command is given, and round it.
        BlockPos spot = t.heart().north(10);
        int[][] trees = { { 0, 0 }, { 3, -3 }, { -3, 2 }, { 7, 0 }, { -2, -6 } };
        for (int[] tr : trees) Kit.wildTree(level, spot.getX() + tr[0], spot.getZ() + tr[1]);
        List<String> said = Kit.command(level, "execute positioned " + t.heart().getX() + " " + t.heart().getY() + " " + t.heart().getZ()
            + " run village bank showcase");
        Kit.log("bank t4 showcase: " + said);
        com.jrpetty.mcassistant.village.Ledger.Building b = Bank.buildingForTests(id);
        ok.that(b != null, "the bank stands");
        if (b == null) {
            helper.fail(ok.summary());
            return;
        }
        BlockPos a = b.anchor();
        int[] half = Blueprints.fullHalf("bank");
        // The bank's own timber is not a tree: the posts and beams of its roof frame are logs (the
        // drawing's L, - and |, 28 of them). Only what is not part of the drawing counts.
        java.util.Set<BlockPos> drawn = new java.util.HashSet<>();
        int frame = 0;
        for (BuildGoal.Placement pl : BuildGoal.plan("bank", a, b.facing(), 13)) {
            drawn.add(pl.pos());
            if (level.getBlockState(pl.pos()).is(net.minecraft.tags.BlockTags.LOGS)) frame++;
        }
        // In the bank and a block round it: no wood, no leaves, whatever tree they came from.
        int wood = 0, inside = 0;
        for (int dx = -half[0] - 1; dx <= half[0] + 1; dx++) {
            for (int dz = -half[1] - 1; dz <= half[1] + 1; dz++) {
                for (int dy = 0; dy <= 12; dy++) {
                    BlockPos q = a.offset(dx, dy, dz);
                    if (drawn.contains(q)) continue;
                    BlockState st = level.getBlockState(q);
                    if (st.is(net.minecraft.tags.BlockTags.LOGS)) wood++;
                    if (st.is(net.minecraft.tags.BlockTags.LEAVES)) inside++;
                }
            }
        }
        // And the trees that stood there felled whole: nothing of them left hanging round it.
        int left = 0;
        for (int[] tr : trees) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 8; dy++) {
                        BlockPos q = new BlockPos(spot.getX() + tr[0] + dx, a.getY() + dy, spot.getZ() + tr[1] + dz);
                        if (drawn.contains(q)) continue;
                        BlockState st = level.getBlockState(q);
                        if (st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.LEAVES)) left++;
                    }
                }
            }
        }
        Kit.log("bank t4 round the bank at " + a + ": " + wood + " logs and " + inside + " leaves in and round it (besides its own frame, "
            + frame + " logs); " + left + " blocks of the five trees left");
        ok.that(wood == 0 && inside == 0, "no wood or leaves in the bank or round it: " + wood + " logs, " + inside + " leaves");
        ok.that(left == 0, "the trees felled whole, nothing of them left hanging near it: " + left);
        VillageFolkEntity banker = Bank.banker(id);
        ok.that(banker != null && banker.isNoAi() && banker.blockPosition().closerThan(a, 1.5), "the banker held at its counter: "
            + (banker == null ? "none" : banker.blockPosition() + " noAi " + banker.isNoAi()));
        int bars = 0;
        for (BlockPos p : Bank.barSpotsForTests(id)) if (level.getBlockState(p).is(Blocks.IRON_BARS)) bars++;
        BlockPos lec = Bank.lecternForTests(id);
        BlockState ls = lec == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(lec);
        ok.that(bars == 4 && ls.getBlock() instanceof LecternBlock && ls.getValue(LecternBlock.HAS_BOOK), "the vault barred and the ledger on its lectern: "
            + bars + " bars, " + ls);
        List<String> done = Kit.command(level, "execute positioned " + t.heart().getX() + " " + t.heart().getY() + " " + t.heart().getZ()
            + " run village bank showcase done");
        Kit.log("bank t4 done: " + done);
        ok.that(banker == null || !banker.isNoAi(), "and let go again");
        if (!ok.clean()) helper.fail(ok.summary());
        helper.succeed();
    }

    // ------------------------------------------------------------------ mortgages and interest

    /** A town with a bank, savers with coin in it, and a Homemaker smith who has a fifth of its house's price and no more. */
    private record Borrowing(Town t, VillageFolkEntity buyer, VillageFolkEntity saverA, VillageFolkEntity saverB, BlockPos house) {}

    private static Borrowing borrowing(GameTestHelper helper, ServerLevel level, int x, int z, Kit.Expect ok, String tag) {
        Town t = town(helper, level, x, z, 4, 5, Villages.Age.NETHER, true);
        UUID id = t.id();
        VillageFolkEntity buyer = t.folk().get(0), saverA = t.folk().get(1), saverB = t.folk().get(2), fourth = t.folk().get(3);
        nature(buyer, Values.Value.HOMES, Social.Trait.HARDWORKING, Social.Trait.SHY);
        nature(saverA, Values.Value.WEALTH, Social.Trait.HARDWORKING, Social.Trait.GRUMPY);
        nature(saverB, Values.Value.WEALTH, Social.Trait.HARDWORKING, Social.Trait.GRUMPY);
        nature(fourth, Values.Value.FOOD, Social.Trait.CURIOUS, Social.Trait.SHY);
        buyer.setJob(StationTask.SMITH);
        buyer.note(AssistantEntity.Deed.THINGS_MADE, 60);              // a hard week's work: its wage carries a mortgage
        for (VillageFolkEntity f : List.of(saverA, saverB, fourth)) f.setJob(StationTask.FARM);
        Homes.tickForTests(level, t.v());
        BlockPos house = Homes.homeOf(buyer);
        for (VillageFolkEntity f : t.folk()) ok.that(Homes.homeOf(f) != null, f.displayNameCap() + " has a house");
        if (house == null) helper.fail("the buyer was given no house: " + ok.summary());
        ok.that(Bank.openForTests(level, t.v()), "the bank is open");
        saverA.earn(200);
        saverB.earn(200);
        Bank.morningForTests(level, t.v());
        Kit.log(tag + " savers: " + Bank.balance(saverA) + " and " + Bank.balance(saverB) + " in the bank; " + Bank.line(id) + "; banker "
            + (Bank.banker(id) == null ? "none" : Bank.banker(id).displayNameCap()));
        ok.that(Bank.deposits(id) > 150, "the savers' coin is in the vault: " + Bank.deposits(id));
        // A fifth of the price, and a little over: payday puts what it can spare by, and the bank lends the rest.
        int[] terms = Homes.termsForTests(id, house);
        int price = terms[3], rent = terms[0];
        buyer.earn(12 + rent + (price + 4) / 5 + 1);                    // a dozen to live on, the day's rent, a fifth of the price and a coin
        long before = coin(id);
        int cash0 = Bank.cash(id), treasury0 = Ledger.coins(id);
        Homes.paydayForTests(level, t.v());
        int[] loan = house == null ? null : Bank.loanForTests(id, house);
        Kit.log(tag + " payday: " + buyer.displayNameCap() + " (wage " + Wealth.wage(buyer) + ") " + Homes.tenureForTests(id, house) + " at price " + price
            + "; loan " + (loan == null ? "none (" + Bank.whyForTests(house) + ")" : java.util.Arrays.toString(loan)) + "; vault " + cash0 + " -> "
            + Bank.cash(id) + ", treasury " + treasury0 + " -> " + Ledger.coins(id) + "; coin " + before + " -> " + coin(id));
        ok.that("OWNED".equals(Homes.tenureForTests(id, house)), "the household buys its house with the bank's loan: " + Homes.tenureForTests(id, house)
            + " (" + Bank.whyForTests(house) + ")");
        ok.that(loan != null && loan[7] > 0 && loan[7] < price && loan[0] == loan[7], "the bank lent the rest of the price: " + (loan == null ? "none" : loan[7]));
        if (loan != null) {
            ok.that(loan[4] >= 8 && loan[4] <= 12 && 3 * loan[3] <= 7 * Wealth.wage(buyer), "over eight to twelve weeks, the payment a third of its week's wages at most: "
                + loan[3] + " a week for " + loan[4] + " weeks on " + Wealth.wage(buyer) + " a day");
            ok.that(Bank.cash(id) == cash0 - loan[7], "the loan came out of the vault");
        }
        ok.that(Ledger.coins(id) >= treasury0 + price, "the village has the whole price: " + treasury0 + " -> " + Ledger.coins(id));
        ok.that(coin(id) == before, "coin conserved through the purchase: " + before + " -> " + coin(id));
        ok.that(told(id, "the bank lent") && told(id, "on a mortgage from the bank"), "the chronicle tells of the loan and the purchase");
        return new Borrowing(t, buyer, saverA, saverB, house);
    }

    /**
     * A Homemaker with a fifth of its house's price put by borrows the rest and buys; a player buys an
     * empty house with a mortgage too; on the bank's rounds both are paid a week at a time, the loans
     * shrinking; the savers' interest comes out of what the loans earned (never more than six parts in
     * ten of it), the treasury has its share, the bank stays sound, and no coin is made.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bank_t2_mortgage")
    public static void bank_t2_mortgage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.Expect ok = new Kit.Expect();
        Borrowing b = borrowing(helper, level, 272500, 50000, ok, "bank t2");
        UUID id = b.t().id();
        // A player buys the empty house it stands in with a mortgage: a fifth down.
        BlockPos empty = null;
        for (BlockPos at : b.t().houses()) if (Homes.membersForTests(id, at).isEmpty()) { empty = at; break; }
        ok.that(empty != null, "an empty house for the player");
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        if (empty != null) you.setPos(empty.getX() + 0.5, empty.getY(), empty.getZ() + 0.5);
        Ledger.addCitizen(id, you.getUUID(), you.getName().getString());
        you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
        long b2 = coin(id, you);
        String bought = Bank.playerForTests(level, id, you, "I'd like a mortgage on this house");
        int[] pl = empty == null ? null : Bank.loanForTests(id, empty);
        Kit.log("bank t2 a player's mortgage: " + bought + " | " + (pl == null ? "none" : java.util.Arrays.toString(pl)) + " | held " + Market.coinsHeld(you));
        ok.that(empty != null && "PLAYER".equals(Homes.tenureForTests(id, empty)) && pl != null, "the player owns the house, on a mortgage");
        ok.that(pl != null && Market.coinsHeld(you) == 64 - (pl[7] > 0 ? Homes.termsForTests(id, empty)[3] - pl[7] : 0), "a fifth down out of its pack");
        ok.that(coin(id, you) == b2, "coin conserved: " + b2 + " -> " + coin(id, you));
        Bank.playerForTests(level, id, you, "deposit 20");             // its payments come out of its account

        // Two of the bank's rounds.
        b.buyer().earn(30);
        int savedA = Bank.balance(b.saverA()), savedB = Bank.balance(b.saverB()), youIn = Bank.balanceForTests(id, you.getUUID());
        int[] l0 = Bank.loanForTests(id, b.house());
        int[] p0 = empty == null ? null : Bank.loanForTests(id, empty);
        int earnedAll = 0, paidAll = 0, treasuryAll = 0;
        for (int week = 1; week <= 2; week++) {
            long bw = coin(id, you);
            int treasury = Ledger.coins(id);
            Bank.weekForTests(level, b.t().v());
            int[] w = Bank.lastWeekForTests(id), fig = Bank.figuresForTests(id);
            int[] l1 = Bank.loanForTests(id, b.house());
            int[] p1 = empty == null ? null : Bank.loanForTests(id, empty);
            Kit.log("bank t2 week " + week + ": earned " + w[0] + ", paid savers " + w[1] + ", treasury " + w[2] + ", repaid " + w[4]
                + "; vault " + fig[0] + ", deposits " + fig[1] + ", lent " + fig[2] + ", reserve " + fig[3] + ", worth " + fig[5]
                + "; household owes " + (l1 == null ? "nothing" : l1[0]) + ", player owes " + (p1 == null ? "nothing" : p1[0]) + "; coin " + bw + " -> " + coin(id, you));
            ok.that(l1 != null && l0 != null && l1[1] < l0[1], "week " + week + ": the household's loan shrinks: " + (l0 == null ? "?" : l0[1]) + " -> " + (l1 == null ? "?" : l1[1]));
            ok.that(p1 != null && p0 != null && p1[1] < p0[1], "week " + week + ": and the player's, out of its account");
            ok.that(w[0] > 0, "the loans earned interest: " + w[0]);
            ok.that(w[1] * 100 <= w[0] * 60 + 99, "the savers are paid out of what the loans earned, six parts in ten at most: " + w[1] + " of " + w[0]);
            ok.that(Ledger.coins(id) == treasury + w[2], "the treasury has its share: " + treasury + " -> " + Ledger.coins(id));
            ok.that(fig[0] >= 0 && fig[5] >= 0, "the bank is sound: vault " + fig[0] + ", worth " + fig[5]);
            ok.that(coin(id, you) == bw, "no coin made: " + bw + " -> " + coin(id, you));
            earnedAll += w[0];
            paidAll += w[1];
            treasuryAll += w[2];
            l0 = l1;
            p0 = p1;
        }
        int interestA = Bank.balance(b.saverA()) - savedA, interestB = Bank.balance(b.saverB()) - savedB;
        int interestYou = Bank.balanceForTests(id, you.getUUID()) - youIn;
        Kit.log("bank t2 two weeks: earned " + earnedAll + ", savers paid " + paidAll + " (" + interestA + " and " + interestB + "), treasury " + treasuryAll
            + "; the player's account " + youIn + " -> " + Bank.balanceForTests(id, you.getUUID()) + " | " + Bank.line(id)
            + " | " + b.buyer().displayNameCap() + ": " + Bank.cardLine(b.buyer()));
        ok.that(paidAll > 0 && treasuryAll > 0, "the savers had interest, and the treasury its share: " + paidAll + ", " + treasuryAll);
        ok.that(interestA >= 0 && interestB >= 0 && interestA + interestB <= paidAll, "the interest went onto the savers' accounts");
        ok.that(interestYou < 0, "the player's payments came out of its account: " + interestYou);
        ok.that(Bank.cardLine(b.buyer()).toLowerCase(java.util.Locale.ROOT).contains("owes the bank"), "the buyer's card shows its mortgage: " + Bank.cardLine(b.buyer()));
        if (!ok.clean()) helper.fail(ok.summary());
        helper.succeed();
    }

    /**
     * A household that stops paying: a payment missed, the banker's warning, a last warning, and three
     * weeks behind the bank takes the house back — the village buys it off the bank for what is owed —
     * and the household stays on as the village's tenant. The chronicle tells it all, and no coin is made.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bank_t3_foreclosure")
    public static void bank_t3_foreclosure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.Expect ok = new Kit.Expect();
        Borrowing b = borrowing(helper, level, 274500, 50000, ok, "bank t3");
        UUID id = b.t().id();
        VillageFolkEntity buyer = b.buyer();
        buyer.spend(buyer.purse());                                      // hard times: nothing to pay with
        for (int week = 1; week <= Bank.ARREARS_LIMIT; week++) {
            long bw = coin(id);
            int cash = Bank.cash(id), treasury = Ledger.coins(id);
            int[] before = Bank.loanForTests(id, b.house());
            Bank.weekForTests(level, b.t().v());
            int[] after = Bank.loanForTests(id, b.house());
            Kit.log("bank t3 week " + week + ": " + (after == null ? "no loan" : "missed " + after[5] + ", behind " + after[6] + ", owes " + after[0])
                + "; tenure " + Homes.tenureForTests(id, b.house()) + "; vault " + cash + " -> " + Bank.cash(id) + ", treasury " + treasury + " -> "
                + Ledger.coins(id) + "; coin " + bw + " -> " + coin(id));
            ok.that(coin(id) == bw, "week " + week + ": no coin made or lost: " + bw + " -> " + coin(id));
            if (week < Bank.ARREARS_LIMIT) {
                ok.that(after != null && after[5] == week && after[0] > (before == null ? 0 : before[0]) - 1, "week " + week + ": " + week + " payments behind, still owing");
                ok.that("OWNED".equals(Homes.tenureForTests(id, b.house())), "and still its house");
            } else {
                ok.that(after == null, "three weeks behind: the mortgage is closed");
                ok.that("RENTED".equals(Homes.tenureForTests(id, b.house())), "the house is the village's again: " + Homes.tenureForTests(id, b.house()));
                ok.that(Homes.membersForTests(id, b.house()).contains(buyer.getUUID()), "and the household stays on in it, as its tenant");
                ok.that(Bank.cash(id) > cash && Ledger.coins(id) < treasury, "the village bought it back off the bank: vault " + cash + " -> " + Bank.cash(id));
            }
        }
        Kit.log("bank t3 the chronicle: " + com.jrpetty.mcassistant.village.Chronicle.of(id));
        ok.that(told(id, "missed a payment") && told(id, "last warning"), "the banker warned them, and warned them again");
        ok.that(told(id, "the bank took back"), "the chronicle tells of the house taken back");
        ok.that(Homes.talk(buyer).contains("rent"), "they rent it now: " + Homes.talk(buyer));
        ok.that(Bank.figuresForTests(id)[0] >= 0, "the vault is never below nothing");
        if (!ok.clean()) helper.fail(ok.summary());
        helper.succeed();
    }
}
