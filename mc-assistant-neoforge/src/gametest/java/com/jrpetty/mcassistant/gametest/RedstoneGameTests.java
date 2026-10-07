package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Engineers;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Machines;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.Trades;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Watch;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [redstone] The redstone engineer (Engineers, Machines). Each test on its own ground (x 1380000 to 1398000, z 66000), in
 * a batch of its own. Every machine is laid by the engineer's own building code, each block out of the town's stores (the
 * parts it lacks made at the bench by the game's recipes), and then left to run by the game's own redstone: the tests
 * put nothing in a machine's chest. The one thing a test does for the game is the crops' growth: the game gives random
 * ticks only to ground near a player, and a test has none, so it ticks the cane and the stems itself, as the world would.
 *
 * <ul>
 * <li>rs01: the trade opens in the Diamond Age once the stores hold redstone and quartz (not in the Iron Age, not
 *     without quartz), and the town takes the right hand: its idle hand who knows metal, not its farmer; a second at a
 *     hundred folk. The talk and the card.</li>
 * <li>rs02: the redstone workshop on the wish list once the trade opens, built from its drawing, a lectern of plans, a
 *     workbench and chests in it; off the list, and no longer lacking to the engineer.</li>
 * <li>rs03: the sugar cane farm laid from the stores (hoppers, pistons, observers and chests made at the bench, by
 *     their recipes, every ingot and dust accounted for), as drawn; the cane grows, an observer sees it, a piston breaks
 *     it, and the hoppers carry it to the chest; the bottom canes stay; the cane counted to the trade; a piston a player
 *     took out put back on the rounds.</li>
 * <li>rs04: the melon and pumpkin farm, both seeds turn about: melons and pumpkins broken by the pistons and carried to
 *     the chest by the hoppers.</li>
 * <li>rs05: the street lamps: an old lantern post given a lamp (its lantern and fence back in the stores) and a new one
 *     beside it; off at noon, lit at night by the sensor alone, out in the morning.</li>
 * <li>rs06: the hopper sorter laid against the storehouse: ten filters stocked with twenty-two each of the town's ten
 *     commonest goods; the couriers' delivery chest is its own; a mixed load sorted into the goods' chests by the game,
 *     and what no filter wants carried on into the storehouse.</li>
 * <li>rs07: the piston gate laid in the wall's main gate (the wooden doors back to the stores): the lever shuts it tight
 *     and opens it again; at dusk a guard walks to the lever and throws it, and in the morning throws it back.</li>
 * <li>rs08: the auto-smelter: ore and fuel out of the stores into its chests, the hoppers feed four furnaces, and the
 *     ingots come down into its chest; counted to the trade.</li>
 * <li>rs09: every part by its real recipe out of the stores, and nothing out of nothing.</li>
 * <li>rs10: the engineer's own day: it plans a street lamp the stores can run to, walks out to it and lays it, a few
 *     blocks a second, by itself.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class RedstoneGameTests {

    static final String EMPTY = "empty";
    static final int Z = 66000;

    /** A town: its village, its heart, its storehouse (its only store) and its folk. */
    record Town(UUID id, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, List<VillageFolkEntity> folk) {}

    /** A town of these trades in this age, its ground held and live round the heart, the storehouse at (sx, sz) from it. */
    static Town town(GameTestHelper helper, int x, Villages.Age age, int hold, int sx, int sz, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        level.updateSkyBrightness();
        Kit.hold(level, x, Z, hold);
        Kit.prepare(level, x, Z, hold);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i);
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, sx, sz);
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(id);
        Villages.ageForTests(id, age);
        for (int i = 0; i < trades.length; i++) {
            folk.get(i).setAgeForTests(30);
            folk.get(i).setJob(trades[i]);
        }
        Kit.live(level, x, Z, Math.min(hold, 48));
        return new Town(id, Villages.get(id), heart, store, folk);
    }

    /** A formed storehouse, its door to the south, its corner at (dx, dz) from the heart. */
    static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
                level.setBlock(origin.offset(x, y, z), unit, 3);
            }
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands: " + level.getBlockState(door));
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    /** So many of a thing, in stacks. */
    static List<ItemStack> lot(Item it, int n) {
        List<ItemStack> out = new ArrayList<>();
        int most = new ItemStack(it).getMaxStackSize();
        while (n > 0) {
            int k = Math.min(most, n);
            out.add(new ItemStack(it, k));
            n -= k;
        }
        return out;
    }

    /**
     * What a Diamond Age town keeps back from its makers whatever they are making (Bench's keeps): the smith's iron (and
     * all of it while the age is short of iron), the builders' timber and stone, the masons' stone bricks, the glass.
     * Every test's stores hold this besides what the test is about, and none of it is touched: the engineer, like every
     * maker in the town, works from what the stores can spare.
     */
    static final Item[] RESERVE = { Items.IRON_INGOT, Items.OAK_PLANKS, Items.COBBLESTONE, Items.STONE_BRICKS, Items.GLASS };
    static final int[] RESERVE_N = { 512, 48, 64, 192, 48 };

    /** The storehouse emptied and filled with these lots (and the town's reserve), and the town's counts made afresh. */
    @SafeVarargs
    static void fill(Town t, List<ItemStack>... lots) {
        for (int i = 0; i < t.store().getContainerSize(); i++) t.store().setItem(i, ItemStack.EMPTY);
        int slot = 0;
        for (List<ItemStack> l : lots) for (ItemStack s : l) t.store().setItem(slot++, s.copy());
        for (int i = 0; i < RESERVE.length; i++) for (ItemStack s : lot(RESERVE[i], RESERVE_N[i])) t.store().setItem(slot++, s);
        restock(t);
    }

    static void restock(Town t) {
        Villages.forgetStock();
        Villages.forgetStores(t.id());
        com.jrpetty.mcassistant.entity.Budget.forget(t.id());
    }

    static int stock(ServerLevel level, Town t, Item it) {
        return Market.stock(level, t.id(), s -> s.is(it));
    }

    /** What the stores hold of each of these, now. */
    static Map<Item, Integer> count(ServerLevel level, Town t, Item... items) {
        Map<Item, Integer> out = new HashMap<>();
        for (Item it : items) out.put(it, stock(level, t, it));
        return out;
    }

    /** How many of a thing a container holds. */
    static int held(ServerLevel level, BlockPos p, Item it) {
        if (!(level.getBlockEntity(p) instanceof Container c)) return -1;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n += c.getItem(i).getCount();
        return n;
    }

    static int heldAll(ServerLevel level, BlockPos p) {
        if (!(level.getBlockEntity(p) instanceof Container c)) return -1;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
        return n;
    }

    /** The trade open and the town grown to sixteen: its engineer appointed (the fittest of its idle hands). */
    static VillageFolkEntity engineer(GameTestHelper helper, Town t) {
        Engineers.openForTests(t.id());
        while (Villages.headcount(t.id()) < Engineers.FROM) Villages.recordBirth(t.id());
        VillageFolkEntity e = Engineers.appoint(helper.getLevel(), t.v());
        helper.assertTrue(e != null && e.stationTask() == StationTask.REDSTONE, "an engineer appointed");
        return e;
    }

    /** The machine laid in full from the stores, as drawn: its faults (none) and the step it ended on. */
    static void build(GameTestHelper helper, ServerLevel level, Town t, Engineers.Machine m, VillageFolkEntity e) {
        Engineers.Step s = Engineers.buildForTests(level, t.v(), m, e);
        List<Machines.Placement> faults = Engineers.faultsForTests(level, t.v(), m);
        Kit.log("rs " + m.kind.name() + " laid at " + m.origin.toShortString() + " back " + m.back + ": " + s + ", "
            + Engineers.planOfForTests(level, t.v(), m).size() + " blocks, faults " + faults.size()
            + (faults.isEmpty() ? "" : " first " + faults.get(0).cell() + " has " + level.getBlockState(faults.get(0).pos()))
            + (m.shortOf() != null ? ", short of " + m.shortOf() + " with " + count(level, t, Items.STONE, Items.COBBLESTONE, Items.STONE_BRICKS,
                Items.REDSTONE, Items.STICK, Items.QUARTZ) + " in the stores" : "") + "; parts made " + Engineers.partsMadeForTests(t.id()));
        helper.assertTrue(s == Engineers.Step.DONE && m.state() == Engineers.State.WORKING, m.kind + " laid whole: " + s + " short of " + m.shortOf());
        helper.assertTrue(faults.isEmpty(), m.kind + " every block as drawn: " + faults.size() + " not");
    }

    /** Is anything in the town's chronicle about this? */
    static boolean told(UUID id, String words) {
        for (Chronicle.Entry e : Chronicle.of(id)) if (e.text().contains(words)) return true;
        return false;
    }

    // ============================================================ rs01: the trade opens, and who takes it

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "rs01_opens")
    public static void rs01_opens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1380000, Villages.Age.IRON, 40, 8, 8, StationTask.FARM, StationTask.FARM, StationTask.NONE, StationTask.NONE,
            StationTask.MINE);
        UUID id = t.id();
        while (Villages.headcount(id) < 20) Villages.recordBirth(id);
        VillageFolkEntity farmer = t.folk().get(1), miner = t.folk().get(4);
        VillageFolkEntity smith = !t.folk().get(2).isElder() ? t.folk().get(2) : t.folk().get(3);
        VillageFolkEntity plain = smith == t.folk().get(2) ? t.folk().get(3) : t.folk().get(2);
        farmer.tradeXpForTests(StationTask.FARM, AssistantEntity.xpForLevel(20));
        miner.tradeXpForTests(StationTask.MINE, AssistantEntity.xpForLevel(4));
        smith.tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(9));     // an idle hand that was the smith's
        fill(t, lot(Items.REDSTONE, 64), lot(Items.QUARTZ, 16));
        boolean iron = Engineers.lookAtStoresForTests(level, t.v());
        Villages.ageForTests(id, Villages.Age.DIAMOND);
        fill(t, lot(Items.REDSTONE, 64));
        boolean noQuartz = Engineers.lookAtStoresForTests(level, t.v());
        int wantedBefore = Engineers.wanted(id);
        fill(t, lot(Items.REDSTONE, 64), lot(Items.QUARTZ, 16));
        boolean open = Engineers.lookAtStoresForTests(level, t.v());
        int wanted = Engineers.wanted(id);
        List<String> wish = Villages.projectsWanted(id);
        Kit.log("rs01 iron age " + iron + ", diamond without quartz " + noQuartz + " (wanted " + wantedBefore + "), with quartz " + open
            + " (wanted " + wanted + " of " + Villages.headcount(id) + "); wish list " + wish);
        helper.assertTrue(!iron && !noQuartz && wantedBefore == 0, "no engineer before the Diamond Age, nor without quartz");
        helper.assertTrue(open && Engineers.ready(id) && wanted == 1, "the trade opens with redstone and quartz in a Diamond Age town: one engineer");
        helper.assertTrue(told(id, "redstone engineer"), "the chronicle has it");
        helper.assertTrue(wish.contains(Engineers.WORKSHOP), "the redstone workshop on the wish list: " + wish);
        VillageFolkEntity first = Engineers.appoint(level, t.v());
        VillageFolkEntity again = Engineers.appoint(level, t.v());
        Kit.log("rs01 appointed " + (first == null ? "nobody" : first.displayNameCap() + " (" + first.stationTask() + ", level "
            + first.tradeLevel(StationTask.REDSTONE) + ")") + "; again " + (again == null ? "nobody" : again.displayNameCap())
            + "; the farmer " + farmer.stationTask() + ", the miner " + miner.stationTask() + ", the plain hand " + plain.stationTask());
        helper.assertTrue(first == smith && smith.stationTask() == StationTask.REDSTONE, "the idle hand who knows metal: " + (first == null ? "nobody" : first.displayNameCap()));
        helper.assertTrue(smith.tradeLevel(StationTask.REDSTONE) >= 6, "a head start from the smith's trade: " + smith.tradeLevel(StationTask.REDSTONE));
        helper.assertTrue(again == null && farmer.stationTask() == StationTask.FARM && miner.stationTask() == StationTask.MINE,
            "one engineer, and never the farmer or the miner the town is short of");
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        String says = FolkTalk.answer(smith, you, TalkTopic.DOING, "");
        String card = FolkTalk.card(smith);
        Kit.log("rs01 says: " + says + " | card has machines: " + card.contains("Machines"));
        helper.assertTrue(!says.isEmpty() && card.contains("Machines"), "the engineer says what it does, and its card has its machines");
        while (Villages.headcount(id) < Engineers.SECOND_AT) Villages.recordBirth(id);
        int wantedAtHundred = Engineers.wanted(id);
        VillageFolkEntity second = Engineers.appoint(level, t.v());
        Kit.log("rs01 at " + Villages.headcount(id) + ": wanted " + wantedAtHundred + ", second " + (second == null ? "nobody" : second.displayNameCap()));
        helper.assertTrue(wantedAtHundred == 2 && second == plain && Engineers.engineers(id).size() == 2, "a second engineer at a hundred");
        helper.succeed();
    }

    // ============================================================ rs02: the workshop

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "rs02_workshop")
    public static void rs02_workshop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1382000, Villages.Age.DIAMOND, 40, 8, 8, StationTask.NONE, StationTask.NONE, StationTask.NONE);
        VillageFolkEntity e = engineer(helper, t);
        UUID id = t.id();
        List<String> before = Villages.projectsWanted(id);
        String why = Villages.whyBuild(id, Engineers.WORKSHOP);
        boolean builtBefore = Villages.hasBuilt(id, Engineers.WORKSHOP);
        BlockPos anchor = Kit.surface(level, t.heart().getX() + 22, t.heart().getZ() - 22);
        int laid = BuildGoal.stamp(level, Engineers.WORKSHOP, anchor, Direction.SOUTH, 13, Showcase.painter(Showcase.OAK));
        Villages.noteProject(id, Engineers.WORKSHOP, level.getGameTime());
        Villages.builtAtForTests(id, Engineers.WORKSHOP, anchor);
        Map<String, Integer> in = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(anchor.offset(-5, -1, -5), anchor.offset(5, 8, 5))) {
            BlockState s = level.getBlockState(p);
            for (net.minecraft.world.level.block.Block b : List.of(Blocks.LECTERN, Blocks.CRAFTING_TABLE, Blocks.CHEST, Blocks.BOOKSHELF,
                    Blocks.BARREL, Blocks.LANTERN)) {
                if (s.is(b)) in.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath(), 1, Integer::sum);
            }
            if (s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) in.merge("door", 1, Integer::sum);
        }
        List<String> after = Villages.projectsWanted(id);
        boolean builtAfter = Villages.hasBuilt(id, Engineers.WORKSHOP);
        Kit.log("rs02 wanted " + before.contains(Engineers.WORKSHOP) + " (" + why + "); laid " + laid + " blocks; in it " + in
            + "; wanted after " + after.contains(Engineers.WORKSHOP) + "; on the books " + builtBefore + " then " + builtAfter
            + "; the engineer's trade: " + Trades.explain(e));
        helper.assertTrue(before.contains(Engineers.WORKSHOP) && why.contains("redstone workshop"), "the workshop wanted, and why: " + why);
        helper.assertTrue(laid > 150, "the workshop built: " + laid);
        helper.assertTrue(in.getOrDefault("lectern", 0) == 1 && in.getOrDefault("crafting_table", 0) == 1 && in.getOrDefault("chest", 0) >= 3
            && in.getOrDefault("bookshelf", 0) >= 4 && in.getOrDefault("door", 0) >= 1 && in.getOrDefault("lantern", 0) >= 2,
            "a lectern of plans, a workbench, chests of parts, books, a door and lanterns: " + in);
        helper.assertTrue(!after.contains(Engineers.WORKSHOP), "off the wish list once built");
        helper.assertTrue(!builtBefore && builtAfter && anchor.equals(Villages.builtAt(id, Engineers.WORKSHOP)), "the workshop on the town's books, where it stands");
        helper.succeed();
    }

    // ============================================================ rs03: the cane farm

    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "rs03_cane")
    public static void rs03_cane(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1384000, Villages.Age.DIAMOND, 72, 8, 8, StationTask.NONE, StationTask.NONE, StationTask.NONE);
        VillageFolkEntity e = engineer(helper, t);
        Engineers.quietForTests(t.id());
        fill(t, lot(Items.STONE_BRICKS, 192), lot(Items.OAK_PLANKS, 192), lot(Items.IRON_INGOT, 96), lot(Items.COBBLESTONE, 96),
            lot(Items.REDSTONE, 40), lot(Items.QUARTZ, 12), lot(Items.GLASS, 24), lot(Items.MUD, 8), lot(Items.WATER_BUCKET, 8),
            lot(Items.SUGAR_CANE, 8));
        Item[] watch = { Items.IRON_INGOT, Items.OAK_PLANKS, Items.COBBLESTONE, Items.REDSTONE, Items.QUARTZ, Items.GLASS, Items.STONE_BRICKS,
            Items.STONE_BRICK_SLAB, Items.SUGAR_CANE, Items.MUD, Items.WATER_BUCKET, Items.BUCKET, Items.HOPPER, Items.PISTON, Items.OBSERVER };
        helper.assertTrue(Engineers.affordableForTests(level, t.v(), Engineers.Kind.CANE), "the stores run to a cane farm");
        Engineers.Machine site = Engineers.siteForTests(level, t.v(), Engineers.Kind.CANE);
        helper.assertTrue(site != null, "a site for the cane farm");
        Engineers.Machine m = Engineers.bookForTests(t.v(), site);
        Map<Item, Integer> before = count(level, t, watch);
        build(helper, level, t, m, e);
        Map<Item, Integer> after = count(level, t, watch);
        Map<String, Integer> used = new java.util.LinkedHashMap<>();
        for (Item it : watch) used.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).getPath(), before.get(it) - after.get(it));
        Map<String, Integer> made = Engineers.partsMadeForTests(t.id());
        Kit.log("rs03 the stores before less after: " + used + "; parts made " + made);
        // Sixteen hoppers (five iron round a chest), eight pistons (three planks, four cobblestone, an iron, a redstone),
        // eight observers (six cobblestone, two redstone, a quartz), the farm's chest, and eight dusts of redstone.
        helper.assertTrue(made.getOrDefault("hopper", 0) == 16 && made.getOrDefault("piston", 0) == 8 && made.getOrDefault("observer", 0) == 8,
            "its parts made at the bench: " + made);
        helper.assertTrue(used.get("iron_ingot") == 16 * 5 + 8, "iron: five a hopper and one a piston: " + used.get("iron_ingot"));
        helper.assertTrue(used.get("oak_planks") == 17 * 8 + 8 * 3, "planks: eight a chest (sixteen hoppers' and the farm's) and three a piston: " + used.get("oak_planks"));
        helper.assertTrue(used.get("cobblestone") == 8 * 4 + 8 * 6, "cobblestone: four a piston, six an observer: " + used.get("cobblestone"));
        helper.assertTrue(used.get("redstone") == 8 + 8 * 2 + 8, "redstone: one a piston, two an observer, eight dusts: " + used.get("redstone"));
        helper.assertTrue(used.get("quartz") == 8 && used.get("glass") == 24 && used.get("sugar_cane") == 8 && used.get("mud") == 8,
            "a quartz an observer, the glass front, the cane and the mud: " + used);
        helper.assertTrue(used.get("water_bucket") == 8 && used.get("bucket") == -8, "eight buckets of water poured, the buckets back: " + used);
        helper.assertTrue(used.get("hopper") == 0 && used.get("piston") == 0 && used.get("observer") == 0, "every part made was used");
        List<BlockPos> canes = Engineers.cellsOf(m, 'S');
        List<BlockPos> pistons = Engineers.cellsOf(m, 'P');
        BlockPos chest = Engineers.cellsOf(m, 'C').get(0);
        int[] tick = { 0 }, fired = { 0 }, phase = { 0 };
        String[] result = { null };
        helper.onEachTick(() -> {
            tick[0]++;
            // The cane grows: the game's random tick on its top block, as it would get near a player.
            for (BlockPos base : canes) {
                for (int h = 0; h < 3; h++) {
                    BlockPos p = base.above(h);
                    BlockState s = level.getBlockState(p);
                    if (s.is(Blocks.SUGAR_CANE) && !level.getBlockState(p.above()).is(Blocks.SUGAR_CANE)) s.randomTick(level, p, level.random);
                }
            }
            for (BlockPos p : pistons) {
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.PISTON) && s.getValue(PistonBaseBlock.EXTENDED)) fired[0]++;
            }
            if (tick[0] % 200 == 0) Kit.log("rs03 t" + tick[0] + " cane in the chest " + held(level, chest, Items.SUGAR_CANE) + ", piston-ticks out " + fired[0]);
            if (phase[0] == 0 && held(level, chest, Items.SUGAR_CANE) >= 16) {
                phase[0] = 1;
                int roots = 0;
                for (BlockPos b : canes) if (level.getBlockState(b).is(Blocks.SUGAR_CANE)) roots++;
                Engineers.roundForTests(level, t.v(), m, e);
                int counted = m.output();
                int[] books = Economy.todayForTests(t.id(), "sugar_cane");
                // A player takes a piston out: the engineer puts it back on its rounds, from the stores (made at the bench).
                BlockPos gone = pistons.get(3);
                level.setBlock(gone, Blocks.AIR.defaultBlockState(), 3);
                int pistonsMade = Engineers.partsMadeForTests(t.id()).getOrDefault("piston", 0);
                int put = Engineers.roundForTests(level, t.v(), m, e);
                boolean back = level.getBlockState(gone).is(Blocks.PISTON);
                int faults = Engineers.faultsForTests(level, t.v(), m).size();
                result[0] = "roots " + roots + ", counted " + counted + ", books made " + books[0] + ", mended " + put + " (piston back " + back
                    + ", pistons made " + pistonsMade + " then " + Engineers.partsMadeForTests(t.id()).getOrDefault("piston", 0) + "), faults " + faults;
                Kit.log("rs03 after " + tick[0] + " ticks: " + result[0]);
                helper.assertTrue(roots == 8, "the bottom cane never broken, so it grows again: " + roots);
                helper.assertTrue(fired[0] > 0, "the pistons fired");
                helper.assertTrue(counted >= 16 && books[0] >= 16, "the cane counted to the trade and in the town's books: " + counted + ", " + books[0]);
                helper.assertTrue(put == 1 && back && faults == 0 && m.mended() == 1, "the piston put back on the rounds");
                helper.assertTrue(Engineers.partsMadeForTests(t.id()).getOrDefault("piston", 0) == pistonsMade + 1, "a new piston made for it");
                phase[0] = 2;
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(phase[0] == 2, "the farm at work: " + held(level, chest, Items.SUGAR_CANE) + " cane so far"));
    }

    // ============================================================ rs04: the melon and pumpkin farm

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "rs04_melon")
    public static void rs04_melon(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1386000, Villages.Age.DIAMOND, 72, 8, 8, StationTask.NONE, StationTask.NONE, StationTask.NONE);
        VillageFolkEntity e = engineer(helper, t);
        Engineers.quietForTests(t.id());
        fill(t, lot(Items.STONE_BRICKS, 256), lot(Items.OAK_PLANKS, 128), lot(Items.IRON_INGOT, 64), lot(Items.COBBLESTONE, 96),
            lot(Items.REDSTONE, 40), lot(Items.QUARTZ, 12), lot(Items.GLASS, 24), lot(Items.MUD, 8), lot(Items.DIRT, 8),
            lot(Items.WATER_BUCKET, 8), lot(Items.MELON_SEEDS, 6), lot(Items.PUMPKIN_SEEDS, 6));
        Engineers.Machine site = Engineers.siteForTests(level, t.v(), Engineers.Kind.MELON);
        helper.assertTrue(site != null && site.crop().equals("both"), "a site for the melon farm, both seeds: " + (site == null ? "none" : site.crop()));
        Engineers.Machine m = Engineers.bookForTests(t.v(), site);
        int melonSeeds = stock(level, t, Items.MELON_SEEDS), pumpkinSeeds = stock(level, t, Items.PUMPKIN_SEEDS);
        build(helper, level, t, m, e);
        int usedMelon = melonSeeds - stock(level, t, Items.MELON_SEEDS), usedPumpkin = pumpkinSeeds - stock(level, t, Items.PUMPKIN_SEEDS);
        int farmland = 0;
        for (BlockPos p : Engineers.cellsOf(m, 'f')) if (level.getBlockState(p).is(Blocks.FARMLAND)) farmland++;
        Kit.log("rs04 seeds planted: " + usedMelon + " melon, " + usedPumpkin + " pumpkin; farmland " + farmland);
        helper.assertTrue(usedMelon == 4 && usedPumpkin == 4 && farmland == 8, "four of each seed, on eight of farmland tilled from the stores' earth");
        List<BlockPos> stems = Engineers.cellsOf(m, 'T');
        BlockPos chest = Engineers.cellsOf(m, 'C').get(0);
        int[] tick = { 0 };
        helper.onEachTick(() -> {
            tick[0]++;
            for (BlockPos p : stems) {
                BlockState s = level.getBlockState(p);
                if ((s.is(Blocks.MELON_STEM) || s.is(Blocks.PUMPKIN_STEM)) && level.random.nextInt(3) == 0) s.randomTick(level, p, level.random);
            }
            if (tick[0] % 200 == 0) Kit.log("rs04 t" + tick[0] + " slices " + held(level, chest, Items.MELON_SLICE) + " pumpkins " + held(level, chest, Items.PUMPKIN));
        });
        helper.succeedWhen(() -> {
            int slices = held(level, chest, Items.MELON_SLICE), pumpkins = held(level, chest, Items.PUMPKIN);
            helper.assertTrue(slices >= 9 && pumpkins >= 2, "melons and pumpkins in the chest: " + slices + " slices, " + pumpkins + " pumpkins");
            int stemsLeft = 0;
            for (BlockPos p : stems) {
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.MELON_STEM) || s.is(Blocks.PUMPKIN_STEM) || s.is(Blocks.ATTACHED_MELON_STEM) || s.is(Blocks.ATTACHED_PUMPKIN_STEM)) stemsLeft++;
            }
            helper.assertTrue(stemsLeft == 8, "every stem still growing: " + stemsLeft);
            Kit.log("rs04 PASS after " + tick[0] + " ticks: " + slices + " slices, " + pumpkins + " pumpkins");
        });
    }

    // ============================================================ rs05: the street lamps

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "rs05_lamps")
    public static void rs05_lamps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1388000, Villages.Age.DIAMOND, 48, 8, 8, StationTask.NONE, StationTask.NONE, StationTask.NONE);
        VillageFolkEntity e = engineer(helper, t);
        fill(t, lot(Items.GLOWSTONE, 2), lot(Items.REDSTONE, 8), lot(Items.GLASS, 6), lot(Items.QUARTZ, 6), lot(Items.OAK_PLANKS, 3),
            lot(Items.STONE_BRICKS, 16));
        List<BlockPos> spots = Engineers.lampSpotsForTests(level, t.v());
        helper.assertTrue(spots.size() >= 2, "lamp spots on the streets");
        BlockPos old = spots.get(0);
        level.setBlock(old.above(), Blocks.OAK_FENCE.defaultBlockState(), 3);              // the Iron Age's post: a lantern on a fence post
        level.setBlock(old.above(2), Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(old.above(3), Blocks.LANTERN.defaultBlockState(), 3);
        Engineers.Machine first = Engineers.nextForTests(level, t.v(), e);
        helper.assertTrue(first != null && first.kind == Engineers.Kind.LAMP && first.origin.equals(old.above()),
            "the first lamp on the old post: " + (first == null ? "none" : first.kind + " at " + first.origin.toShortString()));
        build(helper, level, t, first, e);
        Engineers.Machine second = Engineers.nextForTests(level, t.v(), e);
        helper.assertTrue(second != null && second.kind == Engineers.Kind.LAMP && second.origin.equals(spots.get(1).above()),
            "the second on the next spot along: " + (second == null ? "none" : second.kind + " at " + second.origin.toShortString()));
        build(helper, level, t, second, e);
        Kit.log("rs05 lamps at " + first.origin.toShortString() + " and " + second.origin.toShortString() + "; lanterns back " + stock(level, t, Items.LANTERN)
            + ", fences back " + stock(level, t, Items.OAK_FENCE) + ", lamps made " + Engineers.partsMadeForTests(t.id()));
        helper.assertTrue(stock(level, t, Items.LANTERN) == 1 && stock(level, t, Items.OAK_FENCE) == 2, "the old post's lantern and fences back in the stores");
        helper.assertTrue(level.getBlockState(first.origin.above(3)).is(Blocks.DAYLIGHT_DETECTOR)
            && level.getBlockState(first.origin.above(3)).getValue(net.minecraft.world.level.block.DaylightDetectorBlock.INVERTED), "an inverted sensor on top");
        level.setDayTime(6000);
        level.updateSkyBrightness();
        helper.runAfterDelay(60, () -> {
            boolean noon = Engineers.litForTests(level, first) || Engineers.litForTests(level, second);
            level.setDayTime(18000);
            level.updateSkyBrightness();
            helper.runAfterDelay(60, () -> {
                boolean night = Engineers.litForTests(level, first) && Engineers.litForTests(level, second);
                level.setDayTime(25000);
                level.updateSkyBrightness();
                helper.runAfterDelay(60, () -> {
                    boolean morning = Engineers.litForTests(level, first) || Engineers.litForTests(level, second);
                    Kit.log("rs05 lit at noon " + noon + ", at night " + night + ", in the morning " + morning);
                    helper.assertTrue(!noon, "out at noon");
                    helper.assertTrue(night, "both lit at night, by the sensor alone");
                    helper.assertTrue(!morning, "out again in the morning");
                    helper.succeed();
                });
            });
        });
    }

    // ============================================================ rs06: the hopper sorter

    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "rs06_sorter")
    public static void rs06_sorter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // The storehouse out at the edge of the stores, a lot's length of open ground east of it for the sorter to lie in.
        Town t = town(helper, 1390000, Villages.Age.DIAMOND, 56, 15, 20, StationTask.NONE, StationTask.NONE, StationTask.NONE);
        VillageFolkEntity e = engineer(helper, t);
        Engineers.quietForTests(t.id());
        fill(t, lot(Items.STONE_BRICKS, 448), lot(Items.IRON_INGOT, 256), lot(Items.OAK_PLANKS, 448), lot(Items.REDSTONE, 128),
            lot(Items.QUARTZ, 16), lot(Items.STONE, 128), lot(Items.STICK, 64), lot(Items.GLASS, 16));
        Engineers.Machine site = Engineers.siteForTests(level, t.v(), Engineers.Kind.SORTER);
        helper.assertTrue(site != null && site.docked(), "the sorter laid against the storehouse: " + (site == null ? "no site" : "docked " + site.docked()));
        Engineers.Machine m = Engineers.bookForTests(t.v(), site);
        int iron = stock(level, t, Items.IRON_INGOT), quartz = stock(level, t, Items.QUARTZ);
        build(helper, level, t, m, e);
        int ironUsed = iron - stock(level, t, Items.IRON_INGOT), quartzUsed = quartz - stock(level, t, Items.QUARTZ);
        Map<String, Integer> made = Engineers.partsMadeForTests(t.id());
        Kit.log("rs06 iron used " + ironUsed + ", quartz " + quartzUsed + "; made " + made);
        helper.assertTrue(made.getOrDefault("hopper", 0) == 41 && made.getOrDefault("comparator", 0) == 10 && made.getOrDefault("repeater", 0) == 10,
            "forty-one hoppers, ten comparators and ten repeaters made: " + made);
        helper.assertTrue(ironUsed == 41 * 5 && quartzUsed == 10, "five iron a hopper, a quartz a comparator: " + ironUsed + ", " + quartzUsed);
        // The town's ten commonest goods in the stores, and the filters stocked from them on the rounds.
        Item[] goods = { Items.COBBLESTONE, Items.OAK_LOG, Items.WHEAT, Items.CARROT, Items.POTATO, Items.COAL, Items.RAW_IRON, Items.SAND,
            Items.WHEAT_SEEDS, Items.BREAD };
        List<List<ItemStack>> lots = new ArrayList<>();
        for (Item g : goods) lots.add(lot(g, 128));
        @SuppressWarnings("unchecked") List<ItemStack>[] arr = lots.toArray(new List[0]);
        fill(t, arr);
        Engineers.roundForTests(level, t.v(), m, e);
        List<BlockPos> filters = Engineers.cellsOf(m, 'f'), chests = Engineers.cellsOf(m, 'K');
        Item[] sorts = new Item[filters.size()];
        StringBuilder fs = new StringBuilder();
        boolean stocked = true;
        for (int i = 0; i < filters.size(); i++) {
            Container f = (Container) level.getBlockEntity(filters.get(i));
            sorts[i] = f.getItem(0).getItem();
            int n = held(level, filters.get(i), sorts[i]);
            fs.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(sorts[i]).getPath()).append(' ').append(n).append(", ");
            if (n != 22 || f.getItem(0).getCount() != 18) stocked = false;
        }
        BlockPos delivery = Engineers.cellsOf(m, 'D').get(0);
        BlockPos door = t.store().getBlockPos();
        BlockPos depot = Engineers.delivery(level, t.id(), door);
        Kit.log("rs06 filters: " + fs + "the couriers' depot " + depot.toShortString() + " (delivery chest " + delivery.toShortString() + ")");
        helper.assertTrue(stocked, "every filter holds twenty-two of its good, eighteen in its first slot: " + fs);
        helper.assertTrue(depot.equals(delivery), "the couriers empty their packs into the sorter's delivery chest");
        // A courier's mixed load into the delivery chest (as its deposit puts it there): three of the goods, and dirt and
        // flint that no filter wants.
        Container in = (Container) level.getBlockEntity(delivery);
        Item a = sorts[0], b = sorts[4], c = sorts[9];
        in.setItem(0, new ItemStack(a, 40));
        in.setItem(1, new ItemStack(Items.DIRT, 7));
        in.setItem(2, new ItemStack(b, 12));
        in.setItem(3, new ItemStack(Items.FLINT, 3));
        in.setItem(4, new ItemStack(c, 5));
        int dirtBefore = held(level, door, Items.DIRT), flintBefore = held(level, door, Items.FLINT);
        int[] tick = { 0 };
        helper.onEachTick(() -> {
            if (++tick[0] % 200 == 0) {
                Kit.log("rs06 t" + tick[0] + " sorted " + held(level, chests.get(0), a) + "/" + held(level, chests.get(4), b) + "/" + held(level, chests.get(9), c)
                    + ", into the storehouse dirt " + (held(level, door, Items.DIRT) - dirtBefore) + " flint " + (held(level, door, Items.FLINT) - flintBefore)
                    + ", delivery chest " + heldAll(level, delivery) + ", line end " + heldAll(level, Engineers.cellsOf(m, 'H').get(Engineers.cellsOf(m, 'H').size() - 1))
                    + ", the unit it runs into " + heldAll(level, Engineers.cellsOf(m, 'X').get(0)) + " (" + level.getBlockState(Engineers.cellsOf(m, 'X').get(0)) + ")");
            }
        });
        helper.succeedWhen(() -> {
            int na = held(level, chests.get(0), a), nb = held(level, chests.get(4), b), nc = held(level, chests.get(9), c);
            int dirt = held(level, door, Items.DIRT) - dirtBefore, flint = held(level, door, Items.FLINT) - flintBefore;
            helper.assertTrue(dirt == 7 && flint == 3, "what no filter wants carried on into the storehouse: dirt " + dirt + ", flint " + flint);
            helper.assertTrue(na >= 39 && nb >= 11 && nc >= 4, "each good in its own chest: " + na + "/40, " + nb + "/12, " + nc + "/5");
            for (int i = 0; i < chests.size(); i++) {
                for (Item other : sorts) if (other != sorts[i]) helper.assertTrue(held(level, chests.get(i), other) == 0, "nothing in the wrong chest");
            }
            int stray = held(level, chests.get(0), Items.DIRT) + held(level, chests.get(0), Items.FLINT);
            helper.assertTrue(stray == 0 && heldAll(level, delivery) == 0, "the delivery chest emptied");
            for (int i = 0; i < filters.size(); i++) {
                int n = held(level, filters.get(i), sorts[i]);
                helper.assertTrue(n >= 20 && n <= 22, "filter " + i + " keeps its twenty-two (one may wait in the output hopper): " + n);
            }
            Kit.log("rs06 PASS after " + tick[0] + " ticks: " + na + ", " + nb + ", " + nc + " sorted; dirt and flint into the storehouse");
        });
    }

    // ============================================================ rs07: the piston gate

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "rs07_gate")
    public static void rs07_gate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1392000, Villages.Age.DIAMOND, 48, 6, 6, StationTask.NONE, StationTask.NONE, StationTask.NONE, StationTask.GUARD);
        VillageFolkEntity e = engineer(helper, t);
        Engineers.quietForTests(t.id());
        UUID id = t.id();
        BuildGoal.stamp(level, "fortify", t.heart(), Direction.NORTH, TownPlan.PLAZA, Showcase.painter(Showcase.OAK));
        Villages.noteProject(id, "fortify", level.getGameTime());
        Villages.builtAtForTests(id, "fortify", t.heart());
        int gates = Watch.keep(level, t.v(), true);
        Engineers.Machine site = Engineers.siteForTests(level, t.v(), Engineers.Kind.GATE);
        helper.assertTrue(gates >= 1 && site != null, "the wall's gates hung, and a site for the piston gate: " + gates);
        BlockState wall = level.getBlockState(Machines.at(site.origin, site.back, -4, 1, 0));
        fill(t, lot(Items.STONE_BRICKS, 64), lot(wall.getBlock().asItem(), 8), lot(Items.OAK_PLANKS, 32), lot(Items.COBBLESTONE, 32),
            lot(Items.IRON_INGOT, 8), lot(Items.REDSTONE, 16), lot(Items.SLIME_BALL, 4), lot(Items.STICK, 4));
        int doorsBefore = stock(level, t, Items.SPRUCE_DOOR);
        Engineers.Machine m = Engineers.bookForTests(t.v(), site);
        build(helper, level, t, m, e);
        List<Machines.Placement> plan = Engineers.planOfForTests(level, t.v(), m);
        List<BlockPos> doorway = new ArrayList<>();
        BlockState leaf = null;
        for (Machines.Placement p : plan) {
            if (p.cell().key().air() && p.cell().dz() == 0 && p.cell().h() >= 0 && p.cell().h() <= 1) doorway.add(p.pos());
            if (p.cell().key().ch() == 'W') leaf = p.state();
        }
        BlockPos lever = Engineers.lever(id);
        int doorsBack = stock(level, t, Items.SPRUCE_DOOR) - doorsBefore;
        Kit.log("rs07 gate on the " + m.back + " side at " + m.origin.toShortString() + ", wall of " + wall.getBlock() + ", leaves of " + leaf
            + ", doorway " + doorway.size() + " cells, lever " + lever + ", wooden doors back " + doorsBack + ", sticky pistons made "
            + Engineers.partsMadeForTests(id).get("sticky_piston"));
        helper.assertTrue(doorway.size() == 4 && leaf != null && lever != null, "a doorway two wide and two high, its leaves and its lever");
        helper.assertTrue(leaf.is(wall.getBlock()), "the leaves of the wall's own stone");
        helper.assertTrue(doorsBack == 3, "the wooden gate's three doors back in the stores: " + doorsBack);
        VillageFolkEntity guard = t.folk().get(3);
        BlockPos stand = lever.relative(m.back.getOpposite(), 5);
        guard.moveTo(stand.getX() + 0.5, Kit.surface(level, stand.getX(), stand.getZ()).getY(), stand.getZ() + 0.5, 0.0F, 0.0F);
        BlockState leafState = leaf;
        int[] tick = { 0 }, phase = { 0 }, at = { 0 };
        helper.onEachTick(() -> {
            tick[0]++;
            boolean open = true, shut = true;
            for (BlockPos p : doorway) {
                BlockState s = level.getBlockState(p);
                if (!s.isAir()) open = false;
                if (!s.is(leafState.getBlock())) shut = false;
            }
            switch (phase[0]) {
                case 0 -> {
                    if (tick[0] < 10) return;
                    helper.assertTrue(open, "open at first");
                    ((LeverBlock) Blocks.LEVER).pull(level.getBlockState(lever), level, lever, null);
                    phase[0] = 1;
                    at[0] = tick[0];
                }
                case 1 -> {
                    if (tick[0] - at[0] < 8) return;
                    helper.assertTrue(shut, "the lever thrown: shut tight, four blocks of the wall's stone in the doorway");
                    ((LeverBlock) Blocks.LEVER).pull(level.getBlockState(lever), level, lever, null);
                    phase[0] = 2;
                    at[0] = tick[0];
                }
                case 2 -> {
                    if (tick[0] - at[0] < 8) return;
                    helper.assertTrue(open, "thrown back: open again, two wide and two high");
                    Kit.log("rs07 the lever shuts it tight and opens it; now the watch shuts the gates at dusk");
                    level.setDayTime(12600);
                    Watch.shut(level, id, true);
                    phase[0] = 3;
                    at[0] = tick[0];
                }
                case 3 -> {
                    if (!shut) {
                        if ((tick[0] - at[0]) % 100 == 0) Kit.log("rs07 waiting for the guard: " + guard.blockPosition().toShortString() + " to the lever at " + lever.toShortString());
                        return;
                    }
                    Kit.log("rs07 shut by " + guard.displayNameCap() + " " + (tick[0] - at[0]) + " ticks after the watch shut the gates; throws " + m.throwsAll());
                    helper.assertTrue(m.throwsAll() == 1 && level.getBlockState(lever).getValue(LeverBlock.POWERED), "the guard threw the lever");
                    level.setDayTime(24000 + 500);
                    Watch.shut(level, id, false);
                    phase[0] = 4;
                    at[0] = tick[0];
                }
                case 4 -> {
                    if (!open) return;
                    Kit.log("rs07 opened in the morning " + (tick[0] - at[0]) + " ticks after the watch opened the gates; throws " + m.throwsAll());
                    helper.assertTrue(m.throwsAll() == 2, "thrown back in the morning");
                    phase[0] = 5;
                }
                default -> { }
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(phase[0] == 5, "the gate's day: phase " + phase[0]));
    }

    // ============================================================ rs08: the auto-smelter

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "rs08_smelter")
    public static void rs08_smelter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1394000, Villages.Age.DIAMOND, 72, 8, 8, StationTask.NONE, StationTask.NONE, StationTask.NONE);
        VillageFolkEntity e = engineer(helper, t);
        Engineers.quietForTests(t.id());
        fill(t, lot(Items.STONE_BRICKS, 64), lot(Items.COBBLESTONE, 40), lot(Items.IRON_INGOT, 64), lot(Items.OAK_PLANKS, 192),
            lot(Items.REDSTONE, 4), lot(Items.QUARTZ, 4));
        Engineers.Machine site = Engineers.siteForTests(level, t.v(), Engineers.Kind.SMELTER);
        helper.assertTrue(site != null, "a site for the smelter");
        Engineers.Machine m = Engineers.bookForTests(t.v(), site);
        build(helper, level, t, m, e);
        Map<String, Integer> made = Engineers.partsMadeForTests(t.id());
        helper.assertTrue(made.getOrDefault("furnace", 0) == 4 && made.getOrDefault("hopper", 0) == 12, "four furnaces and twelve hoppers made: " + made);
        fill(t, lot(Items.RAW_IRON, 40), lot(Items.DRIED_KELP_BLOCK, 8));
        Engineers.roundForTests(level, t.v(), m, e);
        int rawLeft = stock(level, t, Items.RAW_IRON), kelpLeft = stock(level, t, Items.DRIED_KELP_BLOCK);
        BlockPos out = Engineers.cellsOf(m, 'K').get(0);
        Kit.log("rs08 fed from the stores: raw iron left " + rawLeft + ", kelp blocks left " + kelpLeft);
        helper.assertTrue(rawLeft == 8 && kelpLeft == 0, "the ore out of the stores (eight kept back) and the fuel: " + rawLeft + ", " + kelpLeft);
        int[] tick = { 0 };
        helper.onEachTick(() -> {
            if (++tick[0] % 200 == 0) Kit.log("rs08 t" + tick[0] + " ingots in its chest " + held(level, out, Items.IRON_INGOT));
        });
        helper.succeedWhen(() -> {
            int n = held(level, out, Items.IRON_INGOT);
            helper.assertTrue(n >= 12, "ingots down into the chest: " + n);
            int busy = 0;
            for (BlockPos f : Engineers.cellsOf(m, 'U')) {
                Container c = (Container) level.getBlockEntity(f);
                if (!c.getItem(1).isEmpty() || level.getBlockState(f).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)) busy++;
            }
            helper.assertTrue(busy == 4, "all four furnaces fed and burning: " + busy);
            Engineers.roundForTests(level, t.v(), m, e);
            int[] books = Economy.todayForTests(t.id(), "iron_ingot");
            helper.assertTrue(m.output() >= 12 && books[0] >= 12, "the ingots counted to the trade: " + m.output() + ", " + books[0]);
            Kit.log("rs08 PASS after " + tick[0] + " ticks: " + n + " ingots");
        });
    }

    // ============================================================ rs09: the parts, by their recipes

    /** One part made at the bench from these stores (and the reserve): what it took of each of these, and the part. */
    static Map<Item, Integer> makeFrom(GameTestHelper helper, ServerLevel level, Town t, VillageFolkEntity e, Item part, List<List<ItemStack>> stores,
                                      Item... watch) {
        @SuppressWarnings("unchecked") List<ItemStack>[] arr = stores.toArray(new List[0]);
        fill(t, arr);
        Map<Item, Integer> before = count(level, t, watch);
        boolean ok = Engineers.make(level, t.v(), e, part, 1);
        Map<Item, Integer> used = new HashMap<>();
        for (Item it : watch) used.put(it, before.get(it) - stock(level, t, it));
        used.put(part, -stock(level, t, part));
        Kit.log("rs09 " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(part).getPath() + ": made " + ok + ", taken " + used);
        helper.assertTrue(ok && used.get(part) <= -1, "made: " + part);
        return used;
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "rs09_parts")
    public static void rs09_parts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1396000, Villages.Age.DIAMOND, 40, 8, 8, StationTask.NONE, StationTask.NONE, StationTask.NONE);
        VillageFolkEntity e = engineer(helper, t);
        Engineers.quietForTests(t.id());
        Map<Item, Integer> l;
        l = makeFrom(helper, level, t, e, Items.REDSTONE_TORCH, List.of(lot(Items.STICK, 1), lot(Items.REDSTONE, 1)), Items.STICK, Items.REDSTONE);
        helper.assertTrue(l.get(Items.STICK) == 1 && l.get(Items.REDSTONE) == 1, "a redstone torch: a stick and a redstone");
        l = makeFrom(helper, level, t, e, Items.PISTON, List.of(lot(Items.OAK_PLANKS, 3), lot(Items.COBBLESTONE, 4), lot(Items.IRON_INGOT, 1),
            lot(Items.REDSTONE, 1)), Items.OAK_PLANKS, Items.COBBLESTONE, Items.IRON_INGOT, Items.REDSTONE);
        helper.assertTrue(l.get(Items.OAK_PLANKS) == 3 && l.get(Items.COBBLESTONE) == 4 && l.get(Items.IRON_INGOT) == 1 && l.get(Items.REDSTONE) == 1,
            "a piston: three planks, four cobblestone, an iron ingot and a redstone");
        l = makeFrom(helper, level, t, e, Items.STICKY_PISTON, List.of(lot(Items.OAK_PLANKS, 3), lot(Items.COBBLESTONE, 4), lot(Items.IRON_INGOT, 1),
            lot(Items.REDSTONE, 1), lot(Items.SLIME_BALL, 1)), Items.SLIME_BALL, Items.PISTON, Items.IRON_INGOT);
        helper.assertTrue(l.get(Items.SLIME_BALL) == 1 && l.get(Items.PISTON) == 0 && l.get(Items.IRON_INGOT) == 1, "a sticky piston: a piston and a slime ball");
        l = makeFrom(helper, level, t, e, Items.OBSERVER, List.of(lot(Items.COBBLESTONE, 6), lot(Items.REDSTONE, 2), lot(Items.QUARTZ, 1)),
            Items.COBBLESTONE, Items.REDSTONE, Items.QUARTZ);
        helper.assertTrue(l.get(Items.COBBLESTONE) == 6 && l.get(Items.REDSTONE) == 2 && l.get(Items.QUARTZ) == 1, "an observer: six cobblestone, two redstone, a quartz");
        l = makeFrom(helper, level, t, e, Items.HOPPER, List.of(lot(Items.IRON_INGOT, 5), lot(Items.OAK_PLANKS, 8)), Items.IRON_INGOT, Items.OAK_PLANKS);
        helper.assertTrue(l.get(Items.IRON_INGOT) == 5 && l.get(Items.OAK_PLANKS) == 8, "a hopper: five iron round a chest of eight planks");
        l = makeFrom(helper, level, t, e, Items.COMPARATOR, List.of(lot(Items.STICK, 3), lot(Items.REDSTONE, 3), lot(Items.QUARTZ, 1), lot(Items.STONE, 3)),
            Items.STICK, Items.REDSTONE, Items.QUARTZ, Items.STONE);
        helper.assertTrue(l.get(Items.STICK) == 3 && l.get(Items.REDSTONE) == 3 && l.get(Items.QUARTZ) == 1 && l.get(Items.STONE) == 3,
            "a comparator: three redstone torches, a quartz, three stone");
        l = makeFrom(helper, level, t, e, Items.REPEATER, List.of(lot(Items.STICK, 2), lot(Items.REDSTONE, 3), lot(Items.STONE, 3)),
            Items.STICK, Items.REDSTONE, Items.STONE);
        helper.assertTrue(l.get(Items.STICK) == 2 && l.get(Items.REDSTONE) == 3 && l.get(Items.STONE) == 3, "a repeater: two torches, a redstone, three stone");
        l = makeFrom(helper, level, t, e, Items.DAYLIGHT_DETECTOR, List.of(lot(Items.GLASS, 3), lot(Items.QUARTZ, 3), lot(Items.OAK_SLAB, 3)),
            Items.GLASS, Items.QUARTZ, Items.OAK_SLAB);
        helper.assertTrue(l.get(Items.GLASS) == 3 && l.get(Items.QUARTZ) == 3 && l.get(Items.OAK_SLAB) == 3,
            "a daylight sensor: three glass, three quartz and three wooden slabs");
        l = makeFrom(helper, level, t, e, Items.REDSTONE_LAMP, List.of(lot(Items.REDSTONE, 4), lot(Items.GLOWSTONE, 1)), Items.REDSTONE, Items.GLOWSTONE);
        helper.assertTrue(l.get(Items.REDSTONE) == 4 && l.get(Items.GLOWSTONE) == 1, "a redstone lamp: four redstone round a glowstone");
        l = makeFrom(helper, level, t, e, Items.DROPPER, List.of(lot(Items.COBBLESTONE, 7), lot(Items.REDSTONE, 1)), Items.COBBLESTONE, Items.REDSTONE);
        helper.assertTrue(l.get(Items.COBBLESTONE) == 7 && l.get(Items.REDSTONE) == 1, "a dropper: seven cobblestone and a redstone");
        // (Two string more than the bow takes: the town keeps two back for the fishers and the smith.)
        l = makeFrom(helper, level, t, e, Items.DISPENSER, List.of(lot(Items.COBBLESTONE, 7), lot(Items.REDSTONE, 1), lot(Items.STICK, 3), lot(Items.STRING, 5)),
            Items.COBBLESTONE, Items.REDSTONE, Items.STICK, Items.STRING);
        helper.assertTrue(l.get(Items.COBBLESTONE) == 7 && l.get(Items.REDSTONE) == 1 && l.get(Items.STICK) == 3 && l.get(Items.STRING) == 3,
            "a dispenser: a dropper's makings and a bow of three sticks and three string");
        l = makeFrom(helper, level, t, e, Items.LEVER, List.of(lot(Items.STICK, 1), lot(Items.COBBLESTONE, 1)), Items.STICK, Items.COBBLESTONE);
        helper.assertTrue(l.get(Items.STICK) == 1 && l.get(Items.COBBLESTONE) == 1, "a lever: a stick and a cobblestone");
        // Nothing out of nothing: an observer with no quartz in the stores is not made, and nothing is taken.
        fill(t, lot(Items.COBBLESTONE, 6), lot(Items.REDSTONE, 2));
        boolean none = Engineers.make(level, t.v(), e, Items.OBSERVER, 1);
        int cobble = stock(level, t, Items.COBBLESTONE), red = stock(level, t, Items.REDSTONE);
        Kit.log("rs09 an observer without quartz: made " + none + ", cobblestone " + cobble + ", redstone " + red);
        helper.assertTrue(!none && cobble == 6 + 64 && red == 2 && stock(level, t, Items.OBSERVER) == 0, "no quartz, no observer, and nothing taken");
        int[] books = Economy.todayForTests(t.id(), "observer");
        int[] quartzBooks = Economy.todayForTests(t.id(), "quartz");
        Kit.log("rs09 the books: observers made " + books[0] + ", quartz used " + quartzBooks[1] + "; parts " + Engineers.partsMadeForTests(t.id()));
        helper.assertTrue(books[0] >= 1 && quartzBooks[1] >= 1, "the parts on the town's books, made, and their quartz used");
        helper.succeed();
    }

    // ============================================================ rs10: the engineer's own day

    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "rs10_day")
    public static void rs10_day(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1398000, Villages.Age.DIAMOND, 48, 8, 8, StationTask.NONE, StationTask.NONE, StationTask.NONE);
        VillageFolkEntity e = engineer(helper, t);
        // A lamp's makings, and the town's own works (its streets, its builders) going on round it with stone and
        // timber of their own to spare.
        fill(t, lot(Items.GLOWSTONE, 1), lot(Items.REDSTONE, 4), lot(Items.GLASS, 3), lot(Items.QUARTZ, 3), lot(Items.OAK_PLANKS, 64),
            lot(Items.STONE_BRICKS, 128), lot(Items.OAK_LOG, 64), lot(Items.COBBLESTONE, 128), lot(Items.BREAD, 32));
        List<BlockPos> spots = Engineers.lampSpotsForTests(level, t.v());
        BlockPos near = spots.get(0);
        e.moveTo(near.getX() + 3.5, Kit.surface(level, near.getX() + 3, near.getZ()).getY(), near.getZ() + 0.5, 0.0F, 0.0F);
        level.setDayTime(3000);
        Kit.log("rs10 the stores can spare " + Engineers.spareForTests(level, t.v()) + "; a lamp affordable " + Engineers.affordableForTests(level, t.v(), Engineers.Kind.LAMP));
        int[] tick = { 0 };
        helper.onEachTick(() -> {
            if (++tick[0] % 100 == 0) {
                Engineers.Machine c = Engineers.currentForTests(t.id());
                Kit.log("rs10 t" + tick[0] + " the engineer at " + e.blockPosition().toShortString() + ": " + (c == null ? "nothing in hand"
                    : c.kind + " " + c.state() + " " + c.placed() + " laid" + (c.shortOf() != null ? ", short of " + c.shortOf() : ""))
                    + "; machines " + Engineers.machines(t.id()).size() + "; missing " + e.missingEssentials() + "; thinking: " + e.brainNoteForBooks());
            }
        });
        helper.succeedWhen(() -> {
            List<Engineers.Machine> ms = Engineers.machines(t.id());
            helper.assertTrue(!ms.isEmpty() && ms.get(0).kind == Engineers.Kind.LAMP && ms.get(0).state() == Engineers.State.WORKING,
                "the engineer planned a lamp and laid it by itself: " + ms.size());
            helper.assertTrue(level.getBlockState(ms.get(0).origin.above(2)).is(Blocks.REDSTONE_LAMP), "the lamp on its post");
            helper.assertTrue(told(t.id(), "finished a street lamp"), "the chronicle has it");
            Kit.log("rs10 PASS after " + tick[0] + " ticks: the lamp at " + ms.get(0).origin.toShortString());
        });
    }
}
