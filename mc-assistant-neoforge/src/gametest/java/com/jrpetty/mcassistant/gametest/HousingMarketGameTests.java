package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bank;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.HousingMarket;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PriceIndex;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
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
 * The housing market (entity/HousingMarket): a folk's own house billed block by block at the town's prices,
 * plus its builders' hours, its plot, its furnishing and its permit, item by item; a folk that cannot pay for it
 * does not commission it, and says what it is saving for; one that can pays, the treasury and the builders are
 * paid and the blocks come out of the stores as the house goes up, and it moves in as its owner; the index,
 * house prices and rents rise while homes are scarce and fall while they stand empty; and an owner moving up
 * sells its old house at the going price to a household that can buy it (or, with nobody to buy, back to the
 * council at four-fifths of it). Every coin is counted before and after: the purses, the treasury, the vault,
 * what is put by toward houses and what is held for a house going up.
 *
 * <p>At x 680000-689999, z 64000, each test on its own ground.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class HousingMarketGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 64000;

    private record Town(UUID id, Villages.Village v, BlockPos heart, List<VillageFolkEntity> folk, List<BlockPos> houses) {}

    /** A village of so many folk in this age, each with a farmer's trade and an empty purse, and so many houses stamped and on the books. */
    private static Town town(GameTestHelper helper, ServerLevel level, int x, int folk, int houses, Villages.Age age) {
        Kit.reset(level);
        Bank.resetForTests();
        HousingMarket.resetForTests();
        HousingMarket.openForTests(true);
        level.setDayTime(24000L * 8 + 7000);                        // past the morning's business: only ours runs
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        helper.assertTrue(v != null, "the village is on the books");
        List<VillageFolkEntity> all = new ArrayList<>();
        all.add(first);
        for (int i = 1; i < folk; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 2 * i, Z + 3), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i + " of the village");
            all.add(f);
        }
        Villages.ageForTests(id, age);
        for (VillageFolkEntity f : all) {
            f.spend(f.purse());
            f.rentFree(false);
            f.setJob(StationTask.FARM);
            nature(f, Values.Value.FOOD);
        }
        List<BlockPos> hs = new ArrayList<>();
        for (int n = 0; n < houses; n++) {
            BlockPos at = Kit.surface(level, x - 30 + 14 * n, Z + 52);
            BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", at, Direction.NORTH);
            hs.add(at);
        }
        return new Town(id, v, heart, all, hs);
    }

    private static void nature(VillageFolkEntity f, Values.Value top) {
        for (Values.Value val : Values.Value.values()) Values.setForTests(f, val, val == top ? 100 : 0);
        f.life().setTraitsForTests(Social.Trait.HARDWORKING, Social.Trait.SHY);
    }

    /** Two marked store chests by the heart, filled with what a house is built and furnished of. */
    private static void stores(ServerLevel level, Town t) {
        ItemStack[][] goods = {
            { new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.STONE_BRICKS, 64),
                new ItemStack(Items.GLASS, 32), new ItemStack(Items.GLASS_PANE, 32), new ItemStack(Items.TORCH, 32),
                new ItemStack(Items.LANTERN, 8), new ItemStack(Items.WHITE_WOOL, 16) },
            { new ItemStack(Items.STONE, 64), new ItemStack(Items.BRICKS, 64), new ItemStack(Items.BRICKS, 64),
                new ItemStack(Items.COBBLED_DEEPSLATE, 64), new ItemStack(Items.DEEPSLATE_TILES, 64), new ItemStack(Items.DEEPSLATE_TILES, 64),
                new ItemStack(Items.RED_CARPET, 16), new ItemStack(Items.POPPY, 8), new ItemStack(Items.BARREL, 2),
                new ItemStack(Items.WHITE_BED), new ItemStack(Items.WHITE_BED), new ItemStack(Items.WHITE_BED),
                new ItemStack(Items.WHITE_BED), new ItemStack(Items.WHITE_BED), new ItemStack(Items.WHITE_BED) } };
        int[][] at = { { 4, -4 }, { -4, -4 } };
        for (int i = 0; i < goods.length; i++) {
            BlockPos chest = Kit.surface(level, t.heart().getX() + at[i][0], t.heart().getZ() + at[i][1]);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, chest);
            Container box = (Container) level.getBlockEntity(chest);
            for (int k = 0; k < goods[i].length; k++) box.setItem(k, goods[i][k]);
            box.setChanged();
        }
        Villages.forgetStores(t.id());
    }

    /** Every coin of the village: purses, the treasury, the vault, what is put by toward houses, what is held for a house going up. */
    private static long coin(UUID id) {
        long n = (long) Ledger.coins(id) + Bank.cash(id) + Homes.savedTotal(id) + HousingMarket.escrowForTests(id);
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f) n += f.purse();
        return n;
    }

    private static boolean told(UUID id, String words) {
        for (com.jrpetty.mcassistant.village.Chronicle.Entry e : com.jrpetty.mcassistant.village.Chronicle.of(id)) if (e.text().contains(words)) return true;
        for (Villages.News n : Villages.news(id)) if (n.text().contains(words)) return true;
        return false;
    }

    /** What the stores hold of the makings of a house: planks (a log four), stone and brick, glass. */
    private static int makings(ServerLevel level, UUID id) {
        return Market.stock(level, id, s -> s.is(ItemTags.PLANKS)) + 4 * Market.stock(level, id, s -> s.is(ItemTags.LOGS))
            + Market.stock(level, id, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE) || s.is(Items.STONE_BRICKS) || s.is(Items.BRICKS)
                || s.is(Items.DEEPSLATE_TILES) || s.is(Items.COBBLED_DEEPSLATE))
            + Market.stock(level, id, s -> s.is(Items.GLASS) || s.is(Items.GLASS_PANE));
    }

    // ============================================================ the bill

    /**
     * A family house's bill on a plot by the village: every line is so many of a block at what one costs in the town
     * today (PriceIndex), the blocks are the sum of the house's lines, the furnishing the sum of its beds' and rugs';
     * the labour is the builders' hours (twelve blocks an hour, the ground made up included) at a craftsman's rate;
     * the plot is the plot's price there; the permit a twentieth of the building, at least two; and the total is the
     * five of them. Every block of the drawing is on the bill.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "hm01_bill")
    public static void hm01_bill(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 680000;
        Town t = town(helper, level, x, 3, 1, Villages.Age.IRON);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos anchor = Kit.surface(level, x + 20, Z - 30);
            HousingMarket.Bill b = HousingMarket.quoteForTests(level, t.v(), HousingMarket.Design.FAMILY, anchor, Direction.NORTH, 3);
            double house = 0, things = 0;
            int n = 0;
            StringBuilder lines = new StringBuilder();
            for (HousingMarket.Line l : b.lines) {
                double each = PriceIndex.each(level, id, l.item());
                ok.that(Math.abs(l.each() - each) < 1e-9, "a line at the town's price today: " + l.what() + " " + l.each() + " (PriceIndex " + each + ")");
                ok.that(Math.abs(l.cost() - each * l.n()) < 1e-9, "its cost is its count times its price: " + l.what());
                if (l.furnishing()) things += l.cost();
                else house += l.cost();
                n += l.n();
                lines.append(l.n()).append(' ').append(l.what()).append(" @ ").append(String.format(java.util.Locale.ROOT, "%.3f", l.each())).append("; ");
            }
            int cellsDrawn = HousingMarket.cellsForTests(HousingMarket.Design.FAMILY, 3);
            double hourly = HousingMarket.hourlyForTests(id);
            int labour = (int) Math.ceil((b.cells + b.ground) / 12.0 * hourly - 1e-9);
            int plot = HousingMarket.plotForTests(id, anchor);
            int permit = Math.max(2, (int) Math.round((b.blocks + b.labour) * 5 / 100.0));
            Kit.log("hm01 a family house's bill at " + anchor + ": " + b.total() + "c — " + b.parts() + " | " + b.cells + " blocks laid of "
                + cellsDrawn + " in the drawing, " + b.ground + " of ground, " + b.beds + " beds | " + lines);
            ok.that(b.blocks == (int) Math.round(house), "the blocks are the house's lines summed: " + b.blocks + " = " + house);
            ok.that(b.furnish == (int) Math.round(things) && things > 0, "the furnishing is its beds' and rugs' lines summed: " + b.furnish + " = " + things);
            ok.that(n == b.cells + b.ground, "every block laid (and the ground made up) is on a line: " + n + " of " + b.cells + " + " + b.ground);
            ok.that(b.cells >= cellsDrawn * 9 / 10 && b.cells <= cellsDrawn, "every block of the drawing is on the bill: " + b.cells + " of " + cellsDrawn);
            ok.that(b.labour == labour && labour > 0, "the labour is the builders' hours at their rate: " + b.labour + " = " + (b.cells + b.ground)
                + " blocks / 12 an hour x " + hourly);
            ok.that(Math.abs(hourly - Wealth.tradeWage(StationTask.SMITH, id) / 10.0) < 1e-9, "at a craftsman's rate: " + hourly);
            ok.that(b.plot == plot && plot > 0, "the plot at the plot's price there: " + b.plot + " = " + plot);
            ok.that(b.permit == permit, "the permit a twentieth of the building, two at least: " + b.permit);
            ok.that(b.total() == b.blocks + b.labour + b.plot + b.furnish + b.permit, "the total is the five of them: " + b.total());
            // A villa is dearer than a cottage, and a cottage for two wants fewer beds than a family house for five.
            HousingMarket.Bill cottage = HousingMarket.quoteForTests(level, t.v(), HousingMarket.Design.COTTAGE, anchor, Direction.NORTH, 1);
            HousingMarket.Bill villa = HousingMarket.quoteForTests(level, t.v(), HousingMarket.Design.VILLA, anchor, Direction.NORTH, 4);
            Kit.log("hm01 a cottage " + cottage.total() + "c (" + cottage.parts() + "), a villa " + villa.total() + "c (" + villa.parts() + ")");
            ok.that(villa.total() > b.total() && b.total() > cottage.total(), "a villa dearer than a family house, a family house than a cottage: "
                + villa.total() + " > " + b.total() + " > " + cottage.total());
            ok.that(cottage.beds == 2 && b.beds == 4 && villa.beds == 5, "beds as the household wants: " + cottage.beds + ", " + b.beds + ", " + villa.beds);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ can it pay?

    /**
     * A Visionary that wants a house of its own drawing, thirty coins to its name and no bank in the town: it cannot
     * pay for any house, so it commissions none — nothing is taken from it, the treasury or the stores, no lot is
     * held — and it says what it is saving for. Given what it lacks it can, and does.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "hm02_cannot_afford")
    public static void hm02_cannot_afford(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 682000;
        Town t = town(helper, level, x, 3, 2, Villages.Age.IRON);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            Homes.tickForTests(level, t.v());
            VillageFolkEntity dreamer = t.folk().get(0);
            nature(dreamer, Values.Value.PROGRESS);
            dreamer.setJob(StationTask.SMITH);
            dreamer.earn(30);
            int treasury = Ledger.coins(id), purse = dreamer.purse(), makings = makings(level, id);
            long coins = coin(id);
            List<VillageFolkEntity> household = HousingMarket.householdForTests(dreamer);
            int cottage = HousingMarket.quoteForTests(level, t.v(), HousingMarket.Design.COTTAGE, Kit.surface(level, x + 20, Z - 30),
                Direction.NORTH, household.size()).total();
            HousingMarket.Means m = HousingMarket.meansForTests(level, t.v(), household, cottage);
            boolean made = HousingMarket.considerForTests(level, t.v(), true);
            Kit.log("hm02 " + dreamer.displayNameCap() + " has " + purse + "c; a cottage is " + cottage + "c; means: " + m + "; commissioned " + made
                + " | card: " + HousingMarket.cardForTests(dreamer));
            ok.that(!m.can() && m.why().contains("no bank"), "it cannot pay for even a cottage: " + m.why());
            ok.that(!made && HousingMarket.buildForTests(id) == null, "so it commissions nothing");
            ok.that(dreamer.purse() == purse && Ledger.coins(id) == treasury && coin(id) == coins, "nothing is taken from it or paid to the treasury: purse "
                + dreamer.purse() + ", treasury " + Ledger.coins(id));
            ok.that(makings(level, id) == makings, "nor out of the stores: " + makings + " -> " + makings(level, id));
            ok.that(!Villages.sitesOf(id).containsKey("villa") && !Villages.sitesOf(id).containsKey("house2"), "no lot is held for it: " + Villages.sitesOf(id).keySet());
            ok.that(HousingMarket.cardForTests(dreamer).startsWith("Saving to build"), "it says what it is saving for: " + HousingMarket.cardForTests(dreamer));
            // With what it lacks, it can.
            dreamer.earn(cottage * 3);
            boolean now = HousingMarket.considerForTests(level, t.v(), true);
            int[] b = HousingMarket.buildForTests(id);
            Kit.log("hm02 with " + dreamer.purse() + "c more: commissioned " + now + " " + (b == null ? "" : java.util.Arrays.toString(b)));
            ok.that(now && b != null, "able to pay, it commissions its house");
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ paying, building, moving in

    /**
     * A household commissions a cottage: the plot and the permit go to the treasury that day, the rest is held for
     * the build; the house goes up a course at a time out of the stores' real blocks, the treasury paid for each
     * block and the builder for its hours as it lays them; at the end the treasury has had the plot, the permit, the
     * blocks and the furnishing, the builders the labour, to the coin; the stores are short what went into it; it
     * stands; and the household owns it and lives in it. No coin is made or lost.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "hm03_build")
    public static void hm03_build(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 684000;
        Town t = town(helper, level, x, 4, 2, Villages.Age.IRON);
        stores(level, t);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            Homes.tickForTests(level, t.v());
            VillageFolkEntity owner = t.folk().get(0);
            nature(owner, Values.Value.HOMES);
            owner.setJob(StationTask.SMITH);
            owner.earn(400);
            List<VillageFolkEntity> household = HousingMarket.householdForTests(owner);
            int treasury0 = Ledger.coins(id), purse0 = owner.purse(), makings0 = makings(level, id);
            int beds0 = Market.stock(level, id, s -> s.is(ItemTags.BEDS)), wool0 = Market.stock(level, id, s -> s.is(ItemTags.WOOL));
            long coins0 = coin(id);
            java.util.Map<UUID, Integer> others0 = new java.util.HashMap<>();
            for (VillageFolkEntity f : t.folk()) if (f != owner) others0.put(f.getUUID(), f.purse());
            boolean made = HousingMarket.commissionForTests(level, t.v(), household, HousingMarket.Design.COTTAGE);
            int[] b = HousingMarket.buildForTests(id);
            BlockPos at = HousingMarket.buildAnchorForTests(id);
            Direction facing = HousingMarket.buildFacingForTests(id);
            Kit.log("hm03 commissioned " + made + ": " + (b == null ? "nothing (" + Villages.lotReport(id) + ")" : java.util.Arrays.toString(b))
                + " at " + at + "; treasury " + treasury0 + " -> " + Ledger.coins(id) + ", purse " + purse0 + " -> " + owner.purse());
            if (b == null || at == null) {
                helper.fail("no cottage commissioned: " + Villages.lotReport(id));
                return;
            }
            int total = b[0], blocks = b[1], labour = b[2], plot = b[3], furnish = b[4], permit = b[5];
            ok.that(Ledger.coins(id) == treasury0 + plot + permit, "the plot and the permit to the treasury that day: " + treasury0 + " -> " + Ledger.coins(id));
            ok.that(owner.purse() == purse0 - total, "the whole bill out of its purse: " + purse0 + " -> " + owner.purse() + " (bill " + total + ")");
            ok.that(HousingMarket.escrowForTests(id) == total - plot - permit, "the rest held for the build: " + HousingMarket.escrowForTests(id));
            ok.that(coin(id) == coins0, "coin conserved by the commission: " + coins0 + " -> " + coin(id));
            ok.that(Villages.sitesOf(id).containsKey("house2"), "its lot held among the town's sites: " + Villages.sitesOf(id).keySet());
            // Up it goes.
            Map<UUID, Integer> paidHands = Map.of();
            int visits = 0, midTreasury = -1;
            String last = "";
            while (HousingMarket.buildForTests(id) != null && visits < 200) {
                paidHands = HousingMarket.buildersForTests(id);
                last = HousingMarket.workForTests(level, t.v(), 24);
                visits++;
                if (visits == 6) midTreasury = Ledger.coins(id);
                if (last.startsWith("waiting")) break;
            }
            Kit.log("hm03 after " + visits + " visits: " + last + " | treasury " + Ledger.coins(id) + " (mid-build " + midTreasury + ") | stores' makings "
                + makings0 + " -> " + makings(level, id) + ", beds " + beds0 + " -> " + Market.stock(level, id, s -> s.is(ItemTags.BEDS))
                + " | hands paid " + paidHands + " | " + Homes.talk(owner) + " | " + HousingMarket.cardForTests(owner));
            ok.that(HousingMarket.buildForTests(id) == null, "the cottage is finished: " + last);
            ok.that(midTreasury > treasury0 + plot + permit, "the treasury paid for the blocks as they went in: " + midTreasury);
            ok.that(Ledger.coins(id) == treasury0 + plot + permit + blocks + furnish, "the treasury has had the plot, the permit, the blocks and the furnishing: "
                + Ledger.coins(id) + " = " + treasury0 + " + " + plot + " + " + permit + " + " + blocks + " + " + furnish);
            int hands = 0;
            for (VillageFolkEntity f : t.folk()) if (f != owner) hands += f.purse() - others0.get(f.getUUID());
            ok.that(hands == labour && labour > 0, "the builders had the labour, into their purses: " + hands + " of " + labour);
            ok.that(coin(id) == coins0, "coin conserved through the build: " + coins0 + " -> " + coin(id));
            ok.that(makings(level, id) < makings0 - 150, "real blocks came out of the stores: " + makings0 + " -> " + makings(level, id));
            int beds1 = Market.stock(level, id, s -> s.is(ItemTags.BEDS)), wool1 = Market.stock(level, id, s -> s.is(ItemTags.WOOL));
            int bedsIn = Homes.bedsForTests(level, id, at).size();
            ok.that(bedsIn >= 2, "its two beds made up in it: " + bedsIn);
            ok.that(beds1 + wool1 / 3 <= beds0 + wool0 / 3 - 2, "out of the stores' beds (or their wool): beds " + beds0 + " -> " + beds1
                + ", wool " + wool0 + " -> " + wool1);
            int standing = 0, drawn = 0;
            for (BuildGoal.Placement p : BuildGoal.plan("house", at, facing == null ? Direction.NORTH : facing, 13)) {
                if (p.part() != BuildGoal.Part.BLOCK) continue;
                drawn++;
                if (!level.getBlockState(p.pos()).isAir()) standing++;
            }
            ok.that(standing >= drawn * 95 / 100, "it stands: " + standing + " of " + drawn + " blocks of its drawing");
            ok.that(at.equals(Homes.homeOf(owner)) && "OWNED".equals(Homes.tenureForTests(id, at)), "the household owns it and lives in it: "
                + Homes.homeOf(owner) + " " + Homes.tenureForTests(id, at));
            ok.that(HousingMarket.isPrivate(id, at), "it is the household's own, not the council's");
            ok.that(!Villages.sitesOf(id).containsKey("house2"), "its site is off the list of what is going up");
            ok.that(told(id, "moved into the cottage they had built"), "the chronicle tells of it");
            ok.that(HousingMarket.cardForTests(owner).startsWith("Built its own cottage"), "its card says so: " + HousingMarket.cardForTests(owner));
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ supply and demand

    /**
     * Seven households and one house: forty mornings of homes scarce, and the index, the price of a house and the rent
     * of a manor all rise; then fourteen houses stand empty, and eighty mornings on they have all fallen, the index
     * below what a house costs to build.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "hm04_index")
    public static void hm04_index(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 686000;
        Town t = town(helper, level, x, 7, 1, Villages.Age.IRON);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            Homes.tickForTests(level, t.v());
            HousingMarket.costForTests(level, id);
            double i0 = HousingMarket.index(id);
            int price0 = Homes.priceForTests(id, "house"), manor0 = Homes.rentForTests(id, "manor");
            HousingMarket.daysForTests(level, t.v(), 40);
            double i1 = HousingMarket.index(id);
            int price1 = Homes.priceForTests(id, "house"), manor1 = Homes.rentForTests(id, "manor");
            Kit.log("hm04 scarce (" + Homes.line(level, id) + "): index " + i0 + " -> " + i1 + ", a house " + price0 + " -> " + price1
                + ", a manor's rent " + manor0 + " -> " + manor1 + " | " + HousingMarket.lines(level, id).get(0));
            ok.that(i1 > i0 * 1.25, "homes scarce, the index rises: " + i0 + " -> " + i1);
            ok.that(price1 > price0, "and the price of a house: " + price0 + " -> " + price1);
            ok.that(manor1 > manor0, "and the rents: a manor " + manor0 + " -> " + manor1);
            ok.that(HousingMarket.weekChange(id) > 0, "the week's trend is up: " + HousingMarket.weekChange(id));
            // Fourteen houses empty.
            for (int n = 0; n < 14; n++) Ledger.built(id, "house", Kit.surface(level, x - 45 + 13 * (n % 7), Z - 40 - 13 * (n / 7)), Direction.NORTH);
            HousingMarket.daysForTests(level, t.v(), 80);
            double i2 = HousingMarket.index(id);
            int price2 = Homes.priceForTests(id, "house"), manor2 = Homes.rentForTests(id, "manor");
            Kit.log("hm04 empty (" + Homes.line(level, id) + "): index " + i1 + " -> " + i2 + ", a house " + price1 + " -> " + price2
                + ", a manor's rent " + manor1 + " -> " + manor2);
            ok.that(i2 < i1 && i2 < 1.0, "homes standing empty, the index falls, below a house's cost: " + i1 + " -> " + i2);
            ok.that(price2 < price1 && price2 < price0, "and the price of a house: " + price1 + " -> " + price2);
            ok.that(manor2 < manor1, "and the rents: a manor " + manor1 + " -> " + manor2);
            ok.that(i2 >= 0.6 - 1e-9 && i1 <= 1.8 + 1e-9, "within its bounds");
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ selling at the market

    /**
     * An owner moving up sells its old house: at the going price (what it cost to build, as the market stands: not
     * half what it paid) to a household that wants a house of its own and can pay for it, which moves in owning it;
     * the seller has the price. With nobody able to buy, the council buys the next one at four-fifths of the going
     * price. Both sales are kept, and no coin is made or lost.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "hm05_sale")
    public static void hm05_sale(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 688000;
        Town t = town(helper, level, x, 4, 4, Villages.Age.IRON);
        UUID id = t.id();
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            Homes.tickForTests(level, t.v());
            HousingMarket.costForTests(level, id);
            VillageFolkEntity seller = t.folk().get(0), buyer = t.folk().get(1), other = t.folk().get(2);
            BlockPos old = Homes.homeOf(seller), buyersHouse = Homes.homeOf(buyer), othersHouse = Homes.homeOf(other);
            if (old == null || buyersHouse == null || othersHouse == null) {
                helper.fail("every one of them housed: " + Homes.line(level, id));
                return;
            }
            HousingMarket.ownForTests(id, old, 40);
            HousingMarket.ownForTests(id, othersHouse, 40);
            HousingMarket.indexForTests(id, 1.2);                       // homes are wanted: the market stands a fifth over cost
            nature(buyer, Values.Value.HOMES);
            buyer.earn(300);
            Ledger.addCoins(id, 500);
            long coins0 = coin(id);
            int going = HousingMarket.marketPriceForTests(id, old), sellerPurse = seller.purse(), buyerPurse = buyer.purse();
            int got = HousingMarket.sellForTests(level, t.v(), old, seller);
            List<String[]> sales = HousingMarket.sales(id);
            Kit.log("hm05 " + seller.displayNameCap() + " sells " + old + " (bought for 40) at the going " + going + ": had " + got + "; "
                + buyer.displayNameCap() + " " + buyerPurse + " -> " + buyer.purse() + ", now at " + Homes.homeOf(buyer) + " ("
                + Homes.tenureForTests(id, old) + ") | sales " + (sales.isEmpty() ? "none" : String.join("/", sales.get(0))));
            ok.that(going > 40 / 2 && going != 20, "the going price, not half what it paid: " + going);
            ok.that(got == going && seller.purse() == sellerPurse + going, "the seller has the going price: " + got + ", purse " + sellerPurse + " -> " + seller.purse());
            ok.that(old.equals(Homes.homeOf(buyer)) && "OWNED".equals(Homes.tenureForTests(id, old)), "the buyer owns it and lives in it");
            ok.that(buyer.purse() == buyerPurse - going, "the buyer paid it: " + buyerPurse + " -> " + buyer.purse());
            ok.that(!sales.isEmpty() && "market".equals(sales.get(0)[3]) && Integer.parseInt(sales.get(0)[2]) == going, "the sale is kept, at the market");
            ok.that(coin(id) == coins0, "coin conserved: " + coins0 + " -> " + coin(id));
            // Nobody able to buy: the council buys it, at four-fifths.
            for (VillageFolkEntity f : t.folk()) f.spend(f.purse());
            int going2 = HousingMarket.marketPriceForTests(id, othersHouse), treasury = Ledger.coins(id);
            long coins1 = coin(id);
            int got2 = HousingMarket.sellForTests(level, t.v(), othersHouse, other);
            Kit.log("hm05 " + other.displayNameCap() + " sells " + othersHouse + " at the going " + going2 + " with nobody to buy: had " + got2
                + "; treasury " + treasury + " -> " + Ledger.coins(id) + "; now " + Homes.tenureForTests(id, othersHouse));
            ok.that(got2 == Math.round(going2 * 0.8) && other.purse() == got2, "the council buys it at four-fifths: " + got2 + " of " + going2);
            ok.that(Ledger.coins(id) == treasury - got2 && "RENTED".equals(Homes.tenureForTests(id, othersHouse)), "out of the treasury, the council's to let");
            ok.that("council".equals(HousingMarket.sales(id).get(0)[3]) && HousingMarket.sales(id).size() == 2, "both sales kept");
            ok.that(coin(id) == coins1, "coin conserved: " + coins1 + " -> " + coin(id));
            ok.that(told(id, "the going price"), "the chronicle tells of the sale");
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }
}
