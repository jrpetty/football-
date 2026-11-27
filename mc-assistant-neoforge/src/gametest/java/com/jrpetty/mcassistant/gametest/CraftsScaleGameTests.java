package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Apiary;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CraftScale;
import com.jrpetty.mcassistant.entity.CraftServices;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Trades;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [roles-b] The crafts at city scale (CraftScale, Apiary, CraftServices).
 *
 * <ul>
 * <li><b>rb01</b>: a hundred-and-fifty-folk Iron Age town wants three smiths, but its smithy has one anvil, so it keeps
 *     one; the smith at its work makes a second anvil of the stores' iron by the game's recipe and sets it in the
 *     smithy, and the town keeps two. The Iron Age smithy has room for no third. The curve: one, a second at sixty, a
 *     third at a hundred and twenty, four at most.</li>
 * <li><b>rb02</b>: the enchanter works at the library till its tower stands, then carries its table over. A player's
 *     iron sword: with no lapis anywhere, the player is told so and nothing changes hands; with lapis but no book, told
 *     that; with both in the stores, the sword is enchanted, three lapis and a book leave the stores, and the player
 *     pays.</li>
 * <li><b>rb03</b>: the tailor takes an order for a white banner of the player's wool, paid now; asked the next day it
 *     hands the banner over. An iron sword is not the tailor's work.</li>
 * <li><b>rb04</b>: the apiary: the beekeeper's hives and bees are brought over from its old meadow, none lost; the
 *     spare comb is dipped into candles with the stores' string, three comb kept back. Worst case, no hive and no
 *     swarm: it finds a wild nest by the town, takes it under a campfire with shears, and carries the swarm home.</li>
 * <li><b>rb05</b>: a potion of swiftness brewed to order (paid now; the brewer's next brew; collected once in the
 *     stores); regeneration with no ghast tear is refused with the reason. Honey sold from the stores; with none there,
 *     the player's bottle filled at a full hive.</li>
 * </ul>
 * Band x 2,000,000 to 2,019,999, z 66,000; a batch each.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CraftsScaleGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    private static final long DAY = 24000L * 4;

    // ------------------------------------------------------------------ helpers

    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    private static List<VillageFolkEntity> town(GameTestHelper helper, int x, long time, int folk) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        level.setDayTime(time);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 44);
        Kit.prepare(level, x, Z, 44);
        BlockPos heart = flat(level, x, Z, 36);
        List<VillageFolkEntity> out = new ArrayList<>();
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null && first.ownerId() != null, "a village");
        out.add(first);
        for (int i = 1; i < folk; i++) {
            BlockPos at = Kit.surface(level, x + 2 + (i % 5), Z + 2 + i / 5);
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
            helper.assertTrue(f != null && first.ownerId().equals(f.ownerId()), "folk " + i + " of the village");
            f.setAgeForTests(30 + i);
            f.insertItem(new ItemStack(Items.COOKED_BEEF, 16));
            out.add(f);
        }
        return out;
    }

    private static BlockPos stamp(ServerLevel level, UUID village, String structure, BlockPos at) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        BuildGoal.stamp(level, structure, p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, structure, p, Direction.NORTH);
        Villages.builtAtForTests(village, structure, p);
        return p;
    }

    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    private static void moveTo(VillageFolkEntity f, BlockPos p) {
        f.getNavigation().stop();
        f.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.0F, 0.0F);
    }

    /** A grown hand of the town (not the elder) set to a trade, at a level. */
    private static VillageFolkEntity hand(List<VillageFolkEntity> folk, int i, StationTask t, int level) {
        VillageFolkEntity f = folk.get(i);
        f.setJob(t);
        f.tradeXpForTests(t, AssistantEntity.xpForLevel(level));
        return f;
    }

    private static ServerPlayer player(GameTestHelper helper, VillageFolkEntity near, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.teleportTo(near.getX() + 1, near.getY(), near.getZ());
        p.getInventory().clearContent();
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return p;
    }

    private static boolean hasBlock(ServerLevel level, BlockPos c, net.minecraft.world.level.block.Block b) {
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-5, -1, -6), c.offset(5, 3, 6))) if (level.getBlockState(p).is(b)) return true;
        return false;
    }

    private static int carried(ServerPlayer p, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    // ============================================================ rb01: the smithy grows

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "rb01_scale")
    public static void rb01_scale(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2000000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, 6);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            helper.assertTrue(CraftScale.curve(StationTask.SMITH, 59) == 1 && CraftScale.curve(StationTask.SMITH, 60) == 2
                && CraftScale.curve(StationTask.SMITH, 150) == 3 && CraftScale.curve(StationTask.SMITH, 900) == 4,
                "one smith, a second at sixty, a third at a hundred and twenty, four at most");
            helper.assertTrue(CraftScale.curve(StationTask.BANK, 119) == 1 && CraftScale.curve(StationTask.BANK, 400) == 2, "a bank clerk at 120");
            helper.assertTrue(CraftScale.curve(StationTask.FARM, 150) == 1 && !CraftScale.scales(StationTask.FARM), "the fields are not a craft");
            Villages.ageForTests(id, Villages.Age.IRON);
            Villages.restore(level, id, heart, Villages.Age.IRON, List.of(), 150);
            helper.assertTrue(Villages.headcount(id) >= 150, "a town of 150 on the roll: " + Villages.headcount(id));
            BlockPos smithy = stamp(level, id, "smithy", heart.offset(0, 0, -16));
            Ledger.Building sb = new Ledger.Building("smithy", smithy, Direction.NORTH);
            int one = CraftScale.recountForTests(level, v, StationTask.SMITH);
            Kit.log("rb01 smithy at " + smithy.toShortString() + ": " + one + " anvil(s); room " + CraftScale.roomFor(Villages.Age.IRON));
            helper.assertTrue(one == 1, "the smithy's own anvil: " + one);
            helper.assertTrue(CraftScale.most(id, StationTask.SMITH, 150, 1) == 1, "three wanted, but one anvil: one smith kept");
            VillageFolkEntity smith = hand(folk, 1, StationTask.SMITH, 30);
            moveTo(smith, smithy.south());
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.IRON_BLOCK, 4), new ItemStack(Items.IRON_INGOT, 64),
                new ItemStack(Items.CRAFTING_TABLE));
            int blocks = stock(level, id, s -> s.is(Items.IRON_BLOCK));
            String did = CraftScale.tendForTests(level, v, smith);
            int two = CraftScale.recountForTests(level, v, StationTask.SMITH);
            Kit.log("rb01 the smith: '" + did + "'; anvils " + two + "; iron blocks " + blocks + " -> " + stock(level, id, s -> s.is(Items.IRON_BLOCK))
                + "; " + smith.debugLine());
            helper.assertTrue(did != null && two == 2, "a second anvil in the smithy: " + did + " (" + two + ")");
            int inside = 0;
            for (BlockPos p : BlockPos.betweenClosed(smithy.offset(-3, -1, -3), smithy.offset(3, 2, 3))) {
                if (level.getBlockState(p).is(BlockTags.ANVIL)) inside++;
            }
            helper.assertTrue(inside == 2, "both anvils inside the smithy's walls: " + inside);
            helper.assertTrue(stock(level, id, s -> s.is(Items.IRON_BLOCK)) <= blocks - 3, "made of the stores' iron, three blocks of it");
            helper.assertTrue(CraftScale.most(id, StationTask.SMITH, 150, 1) == 2, "two anvils: two smiths kept");
            // The Iron Age smithy holds two: no third, however much iron.
            String third = CraftScale.tendForTests(level, v, smith);
            helper.assertTrue(third == null && CraftScale.recountForTests(level, v, StationTask.SMITH) == 2, "no room for a third in the Iron Age");
            helper.assertTrue(CraftScale.roomFor(Villages.Age.DIAMOND) == 3 && CraftScale.roomFor(Villages.Age.STONE) == 1, "more room as the town ages");
            helper.succeed();
        });
    }

    // ============================================================ rb02: the enchanter

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "rb02_enchant")
    public static void rb02_enchant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2004000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, 6);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.DIAMOND);
            helper.assertTrue(!CraftScale.enchanterReady(id), "nowhere to enchant yet");
            BlockPos library = stamp(level, id, "library", heart.offset(-16, 0, -16));
            helper.assertTrue(CraftScale.enchanterReady(id) && "library".equals(CraftScale.houseOf(id, StationTask.ENCHANT)),
                "the library till the tower stands");
            VillageFolkEntity e = hand(folk, 1, StationTask.ENCHANT, 30);
            BlockPos tower = stamp(level, id, CraftScale.TOWER, heart.offset(16, 0, -16));
            helper.assertTrue(CraftScale.TOWER.equals(CraftScale.houseOf(id, StationTask.ENCHANT)), "its own tower once it stands");
            // The tower's table not set yet; the library's carried over.
            BlockPos towerTable = null;
            for (BlockPos p : BlockPos.betweenClosed(tower.offset(-4, -1, -4), tower.offset(4, 2, 4))) {
                if (level.getBlockState(p).is(Blocks.ENCHANTING_TABLE)) { towerTable = p.immutable(); break; }
            }
            helper.assertTrue(towerTable != null, "the tower's drawing has its table");
            level.removeBlock(towerTable, false);
            String carried = CraftScale.tendForTests(level, v, e);
            Kit.log("rb02 the table: '" + carried + "'");
            helper.assertTrue(carried != null && level.getBlockState(towerTable).is(Blocks.ENCHANTING_TABLE)
                && !hasBlock(level, library, Blocks.ENCHANTING_TABLE), "the library's table carried to the tower");
            moveTo(e, towerTable.south());
            ServerPlayer p = player(helper, e, 200);
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            int coins = Market.coinsHeld(p);
            // No lapis anywhere: told so, nothing changes hands.
            String none = CraftServices.enchant(e, p);
            helper.assertTrue(none.contains("lapis") && !p.getMainHandItem().isEnchanted() && Market.coinsHeld(p) == coins, "no lapis: told why: " + none);
            // Lapis, but no book.
            Container box = chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.LAPIS_LAZULI, 6));
            String noBook = CraftServices.enchant(e, p);
            helper.assertTrue(noBook.contains("book") && !p.getMainHandItem().isEnchanted() && Market.coinsHeld(p) == coins, "no book: told why: " + noBook);
            box.setItem(1, new ItemStack(Items.BOOK, 2));
            box.setChanged();
            int lapis = stock(level, id, s -> s.is(Items.LAPIS_LAZULI)), books = stock(level, id, s -> s.is(Items.BOOK));
            String done = CraftServices.enchant(e, p);
            Kit.log("rb02 enchanted: '" + done + "'; coins " + coins + " -> " + Market.coinsHeld(p));
            helper.assertTrue(p.getMainHandItem().isEnchanted(), "the sword enchanted: " + done);
            helper.assertTrue(stock(level, id, s -> s.is(Items.LAPIS_LAZULI)) == lapis - 3 && stock(level, id, s -> s.is(Items.BOOK)) == books - 1,
                "three lapis and a book out of the stores");
            helper.assertTrue(Market.coinsHeld(p) < coins, "and paid for");
            String again = CraftServices.enchant(e, p);
            helper.assertTrue(again.contains("already") && stock(level, id, s -> s.is(Items.LAPIS_LAZULI)) == lapis - 3, "not twice: " + again);
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    // ============================================================ rb03: the tailor's order

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "rb03_tailor")
    public static void rb03_tailor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2008000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, 6);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity t = hand(folk, 1, StationTask.TAILOR, 20);
            ServerPlayer p = player(helper, t, 40);
            p.getInventory().add(new ItemStack(Items.WHITE_WOOL, 6));
            p.getInventory().add(new ItemStack(Items.STICK, 1));
            String smith = CraftServices.tailor(t, p, "an iron sword");
            helper.assertTrue(smith.contains("smith") && Market.coinsHeld(p) == 40, "a sword is the smith's: " + smith);
            String ordered = CraftServices.tailor(t, p, "a white banner");
            Kit.log("rb03 ordered: '" + ordered + "'; coins 40 -> " + Market.coinsHeld(p));
            helper.assertTrue(Market.coinsHeld(p) < 40 && carried(p, s -> s.is(Items.WHITE_WOOL)) == 0, "paid, and its wool taken: " + ordered);
            String early = CraftServices.tailor(t, p, "");
            helper.assertTrue(carried(p, s -> s.is(Items.WHITE_BANNER)) == 0, "not ready the same day: " + early);
            level.setDayTime(level.getDayTime() + 24000L);
            String got = CraftServices.tailor(t, p, "");
            Kit.log("rb03 next day: '" + got + "'");
            helper.assertTrue(carried(p, s -> s.is(Items.WHITE_BANNER)) == 1, "the banner handed over the next day: " + got);
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    // ============================================================ rb04: the apiary

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "rb04_apiary")
    public static void rb04_apiary(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2012000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, 6);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.STONE);
            VillageFolkEntity k = hand(folk, 1, StationTask.BEEKEEP, 10);
            helper.assertTrue(CraftScale.houseOf(id, StationTask.BEEKEEP) == null, "its own meadow till the apiary stands");
            // Its old meadow: two hives, honey in one, bees about.
            BlockPos meadow = Kit.surface(level, x + 20, Z + 20);
            k.assignPlot(WorkZone.around(meadow, 6, WorkZone.DEFAULT_DEPTH), "Meadow");
            BlockPos h1 = meadow.offset(2, 0, 2), h2 = meadow.offset(-2, 0, 2);
            level.setBlock(h1, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, 3), 3);
            level.setBlock(h2, Blocks.BEEHIVE.defaultBlockState(), 3);
            for (int i = 0; i < 3; i++) {
                Bee b = EntityType.BEE.create(level);
                b.moveTo(meadow.getX() + 0.5, meadow.getY() + 2, meadow.getZ() + 0.5, 0, 0);
                b.setPersistenceRequired();
                level.addFreshEntity(b);
            }
            com.jrpetty.mcassistant.entity.Crafts.beekeepForTests(level, v, k);   // a turn at its hives there: the meadow kept in mind
            BlockPos apiary = stamp(level, id, Apiary.STRUCTURE, heart.offset(0, 0, -20));
            helper.assertTrue(Apiary.STRUCTURE.equals(CraftScale.houseOf(id, StationTask.BEEKEEP)), "the apiary once it stands");
            k.assignPlot(WorkZone.around(apiary, 5, WorkZone.DEFAULT_DEPTH), "Apiary");   // as workInTheBuilding moves it
            int beesBefore = level.getEntitiesOfClass(Bee.class, new AABB(meadow).inflate(12), Bee::isAlive).size();
            String brought = Apiary.noHivesForTests(level, v, k);
            int beesAfter = level.getEntitiesOfClass(Bee.class, new AABB(apiary).inflate(10), Bee::isAlive).size();
            Kit.log("rb04 brought: '" + brought + "'; bees " + beesBefore + " -> " + beesAfter + "; hives here " + Apiary.hivesForTests(level, apiary)
                + ", left " + Apiary.hivesForTests(level, meadow));
            helper.assertTrue(brought != null && Apiary.hivesForTests(level, apiary) == 2 && Apiary.hivesForTests(level, meadow) == 0,
                "both hives brought over: " + brought);
            helper.assertTrue(beesAfter >= beesBefore && beesAfter > 0, "the bees with them, none lost: " + beesBefore + " -> " + beesAfter);
            // The candle bench: comb and string into candles, three comb kept for a new hive.
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.HONEYCOMB, 7), new ItemStack(Items.STRING, 5));
            int string0 = stock(level, id, s -> s.is(Items.STRING));
            String dipped = Apiary.candlesForTests(level, v, k);
            Kit.log("rb04 candles: '" + dipped + "'; candles " + stock(level, id, s -> s.is(Items.CANDLE)) + ", comb "
                + stock(level, id, s -> s.is(Items.HONEYCOMB)) + ", string " + string0 + " -> " + stock(level, id, s -> s.is(Items.STRING)));
            helper.assertTrue(stock(level, id, s -> s.is(Items.CANDLE)) == 4 && stock(level, id, s -> s.is(Items.HONEYCOMB)) == 3
                && stock(level, id, s -> s.is(Items.STRING)) == string0 - 4, "four candles of four comb and four string: " + dipped);
            helper.assertTrue(Apiary.candlesForTests(level, v, k) == null, "no more: the last three comb are kept");
            // Worst case: no hive and no swarm. A wild nest by the town, taken under a campfire's smoke with shears.
            level.removeBlock(apiary.offset(2, 0, 2), false);
            for (BlockPos p : BlockPos.betweenClosed(apiary.offset(-6, -2, -6), apiary.offset(6, 4, 6))) {
                if (level.getBlockState(p).getBlock() instanceof BeehiveBlock) level.removeBlock(p, false);
            }
            chestAt(level, heart.offset(-3, 0, -3), new ItemStack(Items.SHEARS), new ItemStack(Items.CAMPFIRE));
            BlockPos trunk = Kit.surface(level, x - 18, Z + 18);
            for (int dy = 0; dy < 5; dy++) level.setBlock(trunk.above(dy), Blocks.OAK_LOG.defaultBlockState(), 3);
            BlockPos nest = trunk.above(3).east();
            level.setBlock(nest, Blocks.BEE_NEST.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, BeehiveBlock.MAX_HONEY_LEVELS), 3);
            Apiary.INSTANT = true;
            try {
                String set = null;
                for (int i = 0; i < 30 && Apiary.errandForTests(k) == null; i++) set = Apiary.noHivesForTests(level, v, k);
                Kit.log("rb04 wild: '" + set + "', errand " + Apiary.errandForTests(k));
                helper.assertTrue(nest.equals(Apiary.errandForTests(k)), "the wild nest found: " + set);
                int comb = stock(level, id, s -> s.is(Items.HONEYCOMB));
                for (int i = 0; i < 4 && Apiary.errandForTests(k) != null; i++) Apiary.errandStepForTests(k, level);
                Kit.log("rb04 taken: nest " + level.getBlockState(nest) + "; swarm carried " + k.countCarried(Trades::isSwarm) + "; " + k.debugLine());
                helper.assertTrue(!level.getBlockState(nest).is(Blocks.BEE_NEST) && k.countCarried(Trades::isSwarm) == 1, "the nest taken, the swarm carried home");
                helper.assertTrue(stock(level, id, s -> s.is(Items.HONEYCOMB)) == comb + 3, "its full comb cut with the shears");
                helper.assertTrue(k.countCarried(s -> s.is(Items.CAMPFIRE)) + stock(level, id, s -> s.is(Items.CAMPFIRE)) == 1
                    && !level.getBlockState(nest.below()).is(Blocks.CAMPFIRE), "the campfire put out and kept");
            } finally {
                Apiary.INSTANT = false;
            }
            helper.succeed();
        });
    }

    // ============================================================ rb05: the brewer and the beekeeper

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "rb05_potions")
    public static void rb05_potions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2016000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, 6);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(id, Villages.Age.IRON);
            VillageFolkEntity b = hand(folk, 1, StationTask.BREW, 10);
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.SUGAR, 4), new ItemStack(Items.NETHER_WART, 4), new ItemStack(Items.GLASS_BOTTLE, 6));
            ServerPlayer p = player(helper, b, 60);
            String refused = CraftServices.potion(b, p, "a potion of regeneration");
            helper.assertTrue(refused.contains("ghast tear") && Market.coinsHeld(p) == 60, "no ghast tear: told why: " + refused);
            String ordered = CraftServices.potion(b, p, "brew me a potion of swiftness");
            Kit.log("rb05 ordered: '" + ordered + "'; coins 60 -> " + Market.coinsHeld(p));
            helper.assertTrue(Market.coinsHeld(p) < 60 && CraftServices.hasPotionOrder(id, p.getUUID()), "paid now: " + ordered);
            helper.assertTrue(Potions.SWIFTNESS.equals(CraftServices.orderedPotionForTests(level, v)), "the brewer's next brew");
            String early = CraftServices.potion(b, p, "");
            helper.assertTrue(early.contains("isn't off the stand") && carried(p, s -> s.is(Items.POTION)) == 0, "not yet: " + early);
            // Off the stand into the stores, as the brewer's brew does.
            chestAt(level, heart.offset(-3, 0, -3), PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
            String got = CraftServices.potion(b, p, "");
            Kit.log("rb05 collected: '" + got + "'");
            helper.assertTrue(carried(p, s -> s.is(Items.POTION)) == 1 && !CraftServices.hasPotionOrder(id, p.getUUID()), "handed over: " + got);
            helper.assertTrue(CraftServices.orderedPotionForTests(level, v) == null, "nothing more on order");
            // Honey: from the stores; with none there, a bottle of the player's filled at a full hive.
            VillageFolkEntity k = hand(folk, 2, StationTask.BEEKEEP, 5);
            BlockPos meadow = Kit.surface(level, x + 16, Z + 16);
            k.assignPlot(WorkZone.around(meadow, 6, WorkZone.DEFAULT_DEPTH), "Meadow");
            BlockPos hive = meadow.offset(2, 0, 2);
            level.setBlock(hive, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, BeehiveBlock.MAX_HONEY_LEVELS), 3);
            moveTo(k, meadow);
            ServerPlayer q = player(helper, k, 30);
            q.getInventory().add(new ItemStack(Items.GLASS_BOTTLE));
            String fresh = CraftServices.honey(k, q, "could I buy some honey?");
            Kit.log("rb05 honey: '" + fresh + "'; coins 30 -> " + Market.coinsHeld(q));
            helper.assertTrue(carried(q, s -> s.is(Items.HONEY_BOTTLE)) == 1 && carried(q, s -> s.is(Items.GLASS_BOTTLE)) == 0
                && Market.coinsHeld(q) < 30, "a bottle filled at the hive: " + fresh);
            helper.assertTrue(level.getBlockState(hive).getValue(BeehiveBlock.HONEY_LEVEL) == 0, "the hive emptied");
            String empty = CraftServices.honey(k, q, "honey");
            helper.assertTrue(empty.contains("no honey") && carried(q, s -> s.is(Items.HONEY_BOTTLE)) == 1, "none left: told why: " + empty);
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }
}
