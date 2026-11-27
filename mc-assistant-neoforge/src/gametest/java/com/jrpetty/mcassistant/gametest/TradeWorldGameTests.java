package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Harbours;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Migration;
import com.jrpetty.mcassistant.entity.RoadWorks;
import com.jrpetty.mcassistant.entity.Roadside;
import com.jrpetty.mcassistant.entity.ShipCalls;
import com.jrpetty.mcassistant.entity.TradeWorld;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Visitors;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [trade-world] Harbours, roads and migration (Harbours, ShipCalls, ShipHull, Seafarers, ShipTrade, RoadWorks, Roadside,
 * Migration).
 *
 * <ul>
 * <li><b>tw01</b>: a Stone Age town of twelve by the sea finds its shore (the office's and the light's places on the bank,
 *     the lane out to sea), wants its harbour office, builds its pier out of its stores' stone (posts to the bottom, a
 *     deck, bollards, lamp posts), keeps a harbourmaster, and its light is lit at dusk with a coal from the stores, put
 *     out at dawn, and left dark (and said so) with nothing to burn.</li>
 * <li><b>tw02</b>: a ship of Vellmark is sighted and sails in a block at a time to lie alongside the pier, its gangplank
 *     down; it pays its dues (a quarter to the harbourmaster), its captain and a sailor come ashore in their land's colours;
 *     the town buys the glass it lacks off it out of the treasury into its stores and sells it its surplus; then it is
 *     called aboard, turns about, sails out and is gone, the sea put back where it lay, and the next ship is due.</li>
 * <li><b>tw03</b>: a player trades at the captain's table: a lot out of the hold for coin (the coin into the ship's purse),
 *     and goods sold to the ship for coin out of it; the ship read back from the town's notes as after a restart is the
 *     same ship; a storm, ice in the harbour and a war each hold a ship up; a sailor killed on the quay by a hand turns its
 *     land against the town and the ship makes ready to sail.</li>
 * <li><b>tw04</b>: two neighbouring towns' road: a Stone Age town with traffic and gravel lays its half in gravel (the
 *     earth back into its stores, a milestone lettered both ways), folk walk faster on it, a good road carries more on a
 *     caravan; in the Iron Age it is cobbled with kerbs and lamp posts near town (the gravel back into the stores); the
 *     other town's half is worn to a path; traffic counts both halves.</li>
 * <li><b>tw05</b>: a long road between two towns: the richer pays for an inn halfway out of its stores, its road crew
 *     builds it, its beds go in, an innkeeper takes it, its larder is stocked; a player takes a room (three coins, and
 *     wakes there after) and a meal out of the larder; a traveller takes a room out of its purse; at war, it is shut.</li>
 * <li><b>tw06</b>: a hungry town and a well-fed neighbour: a folk thinks of leaving (its card, its flag, the talk option);
 *     a player's raise keeps it (its coin into the folk's purse) and the chronicle says so; another folk, left alone, says
 *     its goodbyes and walks the road to the other town (off one roll, onto the other, both chronicles, the counts); a
 *     town of eight lets nobody go.</li>
 * <li><b>tw07</b>: a folk on the road to a new town turns back halfway (the road unsafe), its household back on the old
 *     town's roll and walking home, both chronicles telling it.</li>
 * <li><b>tw08</b>: a master its town needs, taken on elsewhere, is paid by its elder to stay (out of the treasury into its
 *     purse), and the other town hears it; a notice says when a house goes with it.</li>
 * <li><b>tw09</b>: the talk: the harbourmaster says when the next ship is due and which is in, anybody else what it has
 *     heard and who to ask; the roads in words; the captain opens its trading table; the menu's new options only where
 *     they belong, its tabs still six.</li>
 * </ul>
 * Band x 2,240,000 to 2,259,999, z 70,000; a batch each.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TradeWorldGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;
    private static final long DAY = 24000L * 4;

    // ------------------------------------------------------------------ helpers

    /** Flat grass round here, clear air above it. Returns the ground's top (the first air). */
    private static BlockPos flat(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = -4; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(x0, y, z0);
    }

    /** The sea: water three deep over sand, its surface where the grass was (y - 1), from x0 to x1 and z0 to z1. */
    private static void sea(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = -5; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    net.minecraft.world.level.block.state.BlockState want = dy < -4 ? Blocks.SAND.defaultBlockState()
                        : dy < 0 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    if (!level.getBlockState(p).equals(want)) level.setBlock(p, want, 2 | 16);
                }
            }
        }
    }

    /** A town of this many folk round x on flat ground (the first is its founder). */
    private static List<VillageFolkEntity> town(GameTestHelper helper, int x, int z, int y, int folk) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> out = new ArrayList<>();
        BlockPos heart = new BlockPos(x, y, z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null && first.ownerId() != null, "a village at " + x);
        out.add(first);
        for (int i = 1; i < folk; i++) {
            BlockPos at = new BlockPos(x + 2 + (i % 5), y, z + 2 + i / 5);
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
            helper.assertTrue(f != null && first.ownerId().equals(f.ownerId()), "folk " + i + " of the village");
            f.setAgeForTests(26 + i);
            f.insertItem(new ItemStack(Items.COOKED_BEEF, 16));
            out.add(f);
        }
        return out;
    }

    private static void fresh(GameTestHelper helper, long time) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        TradeWorld.resetForTests();
        Visitors.resetForTests();
        level.setDayTime(time);
        level.setWeatherParameters(24000, 0, false, false);
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

    private static void clearOf(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) c.setItem(i, ItemStack.EMPTY);
            c.setChanged();
        }
        Villages.forgetStock();
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    private static String history(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Chronicle.Entry e : Chronicle.of(village)) sb.append(" | ").append(e.text());
        return sb.toString();
    }

    private static int coinsOf(ServerPlayer p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(McAssistantMod.VILLAGE_COIN.get())) n += s.getCount();
        return n;
    }

    /**
     * A Stone Age harbour town of twelve at x: flat land with the sea to its east from x + 30, its shore found, its
     * office stamped where it goes, its pier laid out of a chest of stone in its stores. Returns the town's folk.
     */
    private static List<VillageFolkEntity> harbourTown(GameTestHelper helper, int x, boolean pier) {
        ServerLevel level = helper.getLevel();
        Kit.hold(level, x + 40, Z, 112);
        Kit.prepare(level, x + 40, Z, 112);
        int y = Kit.surface(level, x, Z).getY();
        flat(level, x - 44, x + 29, Z - 60, Z + 60, y);
        sea(level, x + 30, x + 150, Z - 60, Z + 60, y);
        List<VillageFolkEntity> folk = town(helper, x, Z, y, 12);
        UUID id = folk.get(0).ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        if (pier) {
            Villages.Village v = Villages.get(id);
            helper.assertTrue(Harbours.surveyForTests(level, v) != null, "a shore found");
            Harbours.officeForTests(level, v);
            chestAt(level, new BlockPos(x - 3, y, Z - 5), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.TORCH, 8));
            for (int i = 0; i < 20 && !Harbours.pierDone(id); i++) Harbours.layPierForTests(level, v, 40);
            helper.assertTrue(Harbours.pierDone(id), "the pier laid: " + Ledger.note(id, "tw.pier"));
        }
        return folk;
    }

    // ============================================================ tw01: the harbour, the pier and the light

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "tw01_harbour")
    public static void tw01_harbour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2240000;
        fresh(helper, DAY + 2000);
        List<VillageFolkEntity> folk = harbourTown(helper, x, false);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        int y = Kit.surface(level, x - 20, Z).getY();
        helper.runAtTickTime(5, () -> {
            Harbours.Shore s = Harbours.surveyForTests(level, v);
            helper.assertTrue(s != null, "a shore found: " + Ledger.note(id, "tw.shore"));
            Kit.log("tw01 shore water " + s.water().toShortString() + " out " + s.out() + " office " + s.office().toShortString() + " light "
                + (s.light() == null ? "none" : s.light().toShortString()));
            helper.assertTrue(s.out() == Direction.EAST && s.water().getX() == x + 30, "the shore is the sea's edge to the east: " + s.water().toShortString());
            helper.assertTrue(s.office().getX() < x + 30 && s.light() != null && s.light().getX() < x + 30, "the office and the light on the bank");
            List<String> want = new ArrayList<>();
            Harbours.wanted(id, Villages.headcount(id), want);
            helper.assertTrue(want.contains(Harbours.OFFICE), "a Stone Age town of twelve by the sea wants its harbour office: " + want);
            Villages.Site site = Villages.siteFor(level, id, Harbours.OFFICE);
            helper.assertTrue(site != null && site.anchor().equals(s.office()) && site.facing() == Direction.WEST,
                "the builders put it on the bank, its door to the water: " + site);
            // The office built; the pier laid out of the stores' stone, a piece at a time.
            Harbours.officeForTests(level, v);
            clearOf(level, id, st -> st.is(Items.STONE_BRICKS) || st.is(Items.COBBLESTONE));
            chestAt(level, new BlockPos(x - 3, y, Z - 5), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
                new ItemStack(Items.COBBLESTONE, 16), new ItemStack(Items.OAK_PLANKS, 32), new ItemStack(Items.TORCH, 8));
            int bricks0 = stock(level, id, st -> st.is(Items.STONE_BRICKS)), cobble0 = stock(level, id, st -> st.is(Items.COBBLESTONE));
            int laid = 0;
            for (int i = 0; i < 20 && !Harbours.pierDone(id); i++) laid += Harbours.layPierForTests(level, v, 40);
            int bricks1 = stock(level, id, st -> st.is(Items.STONE_BRICKS)), cobble1 = stock(level, id, st -> st.is(Items.COBBLESTONE));
            Kit.log("tw01 pier: " + laid + " pieces; bricks " + bricks0 + " -> " + bricks1 + ", cobble " + cobble0 + " -> " + cobble1);
            helper.assertTrue(Harbours.pierDone(id), "the pier is finished: " + Ledger.note(id, "tw.pier"));
            int deck = Harbours.deckForTests(s);
            for (int k = 0; k < 14; k++) {
                BlockPos d = Harbours.atForTests(s, k, 0, 0).atY(deck);
                helper.assertTrue(level.getBlockState(d).is(Blocks.STONE_BRICKS), "the deck is stone out to its end, k=" + k + ": " + level.getBlockState(d));
            }
            BlockPos post = Harbours.atForTests(s, 1, 1, 0).atY(deck - 1);
            helper.assertTrue(level.getBlockState(post).is(Blocks.STONE_BRICKS) && level.getBlockState(post.below()).getFluidState().isEmpty(),
                "a post down to the bottom under the deck: " + level.getBlockState(post.below()));
            helper.assertTrue(level.getBlockState(Harbours.atForTests(s, 3, 1, 0).atY(deck + 1)).is(Blocks.COBBLESTONE_WALL), "a bollard on the ships' side");
            helper.assertTrue(level.getBlockState(Harbours.atForTests(s, 13, -1, 0).atY(deck + 1)).is(Blocks.SPRUCE_FENCE), "a lamp post at the pier's end");
            helper.assertTrue(bricks0 - bricks1 >= 42, "the stone came out of the stores: " + (bricks0 - bricks1) + " bricks");
            helper.assertTrue(told(id, "pier was finished"), "the chronicle has the pier: " + history(id));
            helper.assertTrue(Harbours.ready(id), "the harbour is ready for ships");
            // The harbourmaster: a fisher first.
            folk.get(3).setStation(folk.get(3).blockPosition(), com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FISH);
            VillageFolkEntity m = Harbours.master(level, v);
            helper.assertTrue(m != null && Harbours.isMaster(m), "a harbourmaster is kept");
            Kit.log("tw01 harbourmaster " + m.displayNameCap() + " (" + m.stationTask() + ")");
            // The light: lit at dusk with a coal from the stores, out at dawn, dark with nothing to burn.
            helper.assertTrue(Harbours.lightForTests(level, v), "the light stands");
            BlockPos beacon = Harbours.beaconForTests(id);
            helper.assertTrue(beacon != null && level.getBlockState(beacon).getBlock() instanceof CampfireBlock, "a beacon fire in the lamp room");
            level.setBlock(beacon, level.getBlockState(beacon).setValue(CampfireBlock.LIT, false), 3);
            clearOf(level, id, st -> st.is(Items.COAL) || st.is(Items.CHARCOAL) || st.is(net.minecraft.tags.ItemTags.LOGS));
            chestAt(level, new BlockPos(x - 3, y, Z - 7), new ItemStack(Items.COAL, 2));
            level.setDayTime(DAY + 11500);
            Harbours.keepLightForTests(level, v);
            helper.assertTrue(level.getBlockState(beacon).getValue(CampfireBlock.LIT), "lit at dusk");
            helper.assertTrue(stock(level, id, st -> st.is(Items.COAL)) == 1, "with a coal from the stores");
            helper.assertTrue(told(id, "lighthouse was lit for the first time"), "the chronicle has the first lighting: " + history(id));
            level.setDayTime(DAY + 23600);
            Harbours.keepLightForTests(level, v);
            helper.assertTrue(!level.getBlockState(beacon).getValue(CampfireBlock.LIT), "put out at dawn");
            clearOf(level, id, st -> st.is(Items.COAL));
            level.setDayTime(DAY + 24000 + 11500);
            Harbours.keepLightForTests(level, v);
            helper.assertTrue(!level.getBlockState(beacon).getValue(CampfireBlock.LIT) && told(id, "stood dark"),
                "nothing to burn: dark, and the chronicle says so: " + history(id));
            List<String> page = Harbours.lines(level, v);
            Kit.log("tw01 page: " + String.join(" / ", page));
            helper.assertTrue(String.join(" ", page).contains("Next ship due"), "the Harbour page says when the next ship is due");
            helper.succeed();
        });
    }

    // ============================================================ tw02: a ship comes, trades and goes

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "tw02_ship")
    public static void tw02_ship(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2242000;
        fresh(helper, DAY + 2000);
        List<VillageFolkEntity> folk = harbourTown(helper, x, true);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        int y = Kit.surface(level, x - 20, Z).getY();
        helper.runAtTickTime(5, () -> {
            Harbours.Shore s = Harbours.shore(id);
            Ledger.addCoins(id, 300);
            folk.get(3).setStation(folk.get(3).blockPosition(), com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FISH);
            String name = ShipCalls.sightNow(level, v, "vellmark");
            helper.assertTrue(name != null && ShipCalls.stateForTests(id).startsWith("in 44"), "a ship sighted far out: " + ShipCalls.stateForTests(id));
            ShipCalls.sailForTests(level, v);
            helper.assertTrue(ShipCalls.stateForTests(id).contains("drawn") && ShipCalls.hullForTests(level, v) > 100,
                "its hull is on the water: " + ShipCalls.stateForTests(id) + " " + ShipCalls.hullForTests(level, v));
            int treasury0 = Ledger.coins(id);
            int steps = 0;
            while (!ShipCalls.stateForTests(id).startsWith("docked") && steps++ < 60) ShipCalls.sailForTests(level, v);
            Kit.log("tw02 " + name + " in after " + steps + " steps: " + ShipCalls.stateForTests(id) + "; hull " + ShipCalls.hullForTests(level, v));
            helper.assertTrue(ShipCalls.stateForTests(id).startsWith("docked 3 drawn"), "alongside the pier: " + ShipCalls.stateForTests(id));
            helper.assertTrue(steps >= 40, "it came in a block at a time: " + steps);
            BlockPos plank = Harbours.atForTests(s, 3 + 6, 2, 0);
            helper.assertTrue(level.getBlockState(plank).is(Blocks.SPRUCE_PLANKS), "its gangplank down to the pier");
            int[] pl = ShipCalls.purseAndLotsForTests(id);
            int dues = Integer.parseInt(Ledger.note(id, "tw.dues"));
            VillageFolkEntity m = Harbours.master(level, v);
            Kit.log("tw02 dues " + dues + "; treasury " + treasury0 + " -> " + Ledger.coins(id) + "; purse " + pl[0] + ", lots " + pl[1]
                + "; master " + (m == null ? "-" : m.displayNameCap() + " " + m.purse()));
            helper.assertTrue(dues >= 4 && Ledger.coins(id) - treasury0 == dues - dues / 4, "the dues into the treasury, a quarter to the harbourmaster");
            helper.assertTrue(told(id, name + ", a merchant ship of Vellmark, came into harbour"), "the chronicle has it in: " + history(id));
            List<UUID> crew = ShipCalls.crewForTests(id);
            helper.assertTrue(crew.size() == 2, "a captain and a sailor ashore: " + crew);
            VillageFolkEntity captain = (VillageFolkEntity) level.getEntity(crew.get(0));
            helper.assertTrue(captain != null && Visitors.is(captain) && captain.ownerId() == null, "of no village");
            helper.assertTrue(captain.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE)
                && captain.getItemBySlot(EquipmentSlot.CHEST).has(net.minecraft.core.component.DataComponents.DYED_COLOR), "in its land's colours");
            // The town buys what it needs (glass, its stores bare of it), out of the treasury.
            clearOf(level, id, st -> st.is(Items.GLASS) || st.is(Items.GLASS_PANE));
            ShipCalls.holdForTests(id, Items.GLASS, 16, 10);
            int[] before = ShipCalls.purseAndLotsForTests(id);
            int coins0 = Ledger.coins(id);
            level.setDayTime(DAY + 3000);
            ShipCalls.townTickForTests(level, v);
            int glass = stock(level, id, st -> st.is(Items.GLASS));
            int[] after = ShipCalls.purseAndLotsForTests(id);
            Kit.log("tw02 town bought: glass " + glass + "; treasury " + coins0 + " -> " + Ledger.coins(id) + "; purse " + before[0] + " -> " + after[0]);
            helper.assertTrue(glass == 16 && coins0 - Ledger.coins(id) == 10 && after[0] - before[0] == 10, "the glass into the stores for its price");
            helper.assertTrue(told(id, "the town bought glass (16)"), "the chronicle has the purchase: " + history(id));
            // The town sells it its surplus.
            chestAt(level, new BlockPos(x - 5, y, Z - 9), new ItemStack(Items.SAND, 64), new ItemStack(Items.SAND, 64), new ItemStack(Items.SAND, 64),
                new ItemStack(Items.SAND, 64), new ItemStack(Items.SAND, 64), new ItemStack(Items.SAND, 64));
            com.jrpetty.mcassistant.entity.Budget.forget(id);
            int coins1 = Ledger.coins(id);
            for (int i = 0; i < 4; i++) ShipCalls.townTickForTests(level, v);
            int[] sold = ShipCalls.purseAndLotsForTests(id);
            Kit.log("tw02 town sold: treasury " + coins1 + " -> " + Ledger.coins(id) + "; purse " + after[0] + " -> " + sold[0] + "; " + history(id));
            helper.assertTrue(Ledger.coins(id) > coins1 && sold[0] < after[0], "the town's surplus sold to the ship for its coin");
            // Away: called aboard, turned about, out down the lane, gone, the sea put back.
            ShipCalls.castOffForTests(level, v);
            helper.assertTrue(ShipCalls.stateForTests(id).startsWith("out 3 drawn turned"), "turned about to sail: " + ShipCalls.stateForTests(id));
            helper.assertTrue(!level.getBlockState(plank).is(Blocks.SPRUCE_PLANKS), "the gangplank up");
            helper.assertTrue(captain.isRemoved(), "its people went aboard with it");
            steps = 0;
            while (!ShipCalls.stateForTests(id).isEmpty() && steps++ < 80) ShipCalls.sailForTests(level, v);
            helper.assertTrue(ShipCalls.stateForTests(id).isEmpty(), "gone over the horizon: " + ShipCalls.stateForTests(id));
            for (int k = 3; k <= 54; k += 3) {
                BlockPos p = Harbours.atForTests(s, k, 5, 0);
                helper.assertTrue(level.getBlockState(p).is(Blocks.WATER) && level.getBlockState(p.above()).isAir(), "the sea put back at k=" + k + ": "
                    + level.getBlockState(p) + " / " + level.getBlockState(p.above()));
            }
            long day = level.getDayTime() / 24000L;
            helper.assertTrue(ShipCalls.nextForTests(id) > day, "the next ship is due: " + ShipCalls.nextForTests(id));
            helper.assertTrue(told(id, name + " sailed for Vellmark"), "the chronicle has it gone: " + history(id));
            helper.assertTrue(ShipCalls.regardForTests(id, "vellmark") >= 1, "a visit that traded leaves its land the warmer");
            helper.succeed();
        });
    }

    // ============================================================ tw03: a player's trade, a restart, the hold-ups

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "tw03_shiptrade")
    public static void tw03_shiptrade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2244000;
        fresh(helper, DAY + 2000);
        List<VillageFolkEntity> folk = harbourTown(helper, x, true);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        helper.runAtTickTime(5, () -> {
            Harbours.Shore s = Harbours.shore(id);
            ShipCalls.sightNow(level, v, "vellmark");
            for (int i = 0; i < 60 && !ShipCalls.stateForTests(id).startsWith("docked"); i++) ShipCalls.sailForTests(level, v);
            helper.assertTrue(ShipCalls.stateForTests(id).startsWith("docked"), "in harbour");
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
            List<String> table = ShipCalls.tableForTests(level, id);
            Kit.log("tw03 table: " + table);
            helper.assertTrue(!table.isEmpty() && table.get(0).startsWith("sell "), "the hold's lots on the table first");
            int[] pl0 = ShipCalls.purseAndLotsForTests(id);
            int price = Integer.parseInt(table.get(0).substring(table.get(0).lastIndexOf(' ') + 1));
            String item = table.get(0).split(" ")[1];
            int coins0 = coinsOf(p);
            helper.assertTrue(ShipCalls.tradeForTests(p, id, 0), "the lot bought");
            int[] pl1 = ShipCalls.purseAndLotsForTests(id);
            int got = 0;
            for (ItemStack st : p.getInventory().items) {
                if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(item)) got += st.getCount();
            }
            Kit.log("tw03 bought " + item + " x" + got + " for " + price + ": coins " + coins0 + " -> " + coinsOf(p) + "; purse " + pl0[0] + " -> " + pl1[0]
                + "; lots " + pl0[1] + " -> " + pl1[1]);
            helper.assertTrue(coins0 - coinsOf(p) == price && pl1[0] - pl0[0] == price && pl0[1] - pl1[1] == 1 && got > 0,
                "the coin into the ship's purse, the lot out of its hold");
            // Selling to the ship: what its land wants (Vellmark: cobblestone), for coin out of its purse.
            List<String> buys = new ArrayList<>();
            int buyAt = -1;
            for (int i = 0; i < table.size(); i++) {
                if (table.get(i).startsWith("buy minecraft:cobblestone")) buyAt = i;
                if (table.get(i).startsWith("buy ")) buys.add(table.get(i));
            }
            helper.assertTrue(buyAt >= 0, "the ship buys its land's wants: " + buys);
            // (The table is laid afresh at each look: the buying offers come after the hold's lots still left.)
            table = ShipCalls.tableForTests(level, id);
            for (int i = 0; i < table.size(); i++) if (table.get(i).startsWith("buy minecraft:cobblestone")) buyAt = i;
            p.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
            int coins1 = coinsOf(p);
            helper.assertTrue(ShipCalls.tradeForTests(p, id, buyAt), "sold to the ship");
            int[] pl2 = ShipCalls.purseAndLotsForTests(id);
            Kit.log("tw03 sold cobblestone: coins " + coins1 + " -> " + coinsOf(p) + "; purse " + pl1[0] + " -> " + pl2[0]);
            helper.assertTrue(coinsOf(p) > coins1 && pl2[0] < pl1[0] && pl1[0] - pl2[0] == coinsOf(p) - coins1, "its coin out of its purse into yours");
            // A restart: the ship read back from the town's notes is the same ship, where it lay.
            String state = ShipCalls.stateForTests(id);
            ShipCalls.forgetForTests(id);
            int[] back = ShipCalls.purseAndLotsForTests(id);
            helper.assertTrue(ShipCalls.stateForTests(id).equals(state) && back[0] == pl2[0] && back[1] == pl2[1],
                "the same ship after a restart: " + ShipCalls.stateForTests(id) + " " + back[0] + "/" + back[1]);
            // A sailor killed on the quay by a hand: its land turns against the town, and the ship makes ready to sail.
            List<UUID> crew = ShipCalls.crewForTests(id);
            VillageFolkEntity sailor = (VillageFolkEntity) level.getEntity(crew.get(crew.size() - 1));
            sailor.hurt(level.damageSources().playerAttack(p), 1000.0F);
            helper.assertTrue(!sailor.isAlive(), "the sailor is dead");
            Kit.log("tw03 regard after the killing: " + ShipCalls.regardForTests(id, "vellmark"));
            helper.assertTrue(ShipCalls.regardForTests(id, "vellmark") < 0, "Vellmark thinks ill of the town now");
            helper.assertTrue(told(id, "was killed on the quay"), "the chronicle has it: " + history(id));
            // The hold-ups for a ship due: a storm, ice in the harbour, a war.
            helper.assertTrue(ShipCalls.heldUpForTests(level, v).isEmpty(), "nothing holds a ship up on a fair day: " + ShipCalls.heldUpForTests(level, v));
            com.jrpetty.mcassistant.entity.Weather.stormForTests(true);
            String storm = ShipCalls.heldUpForTests(level, v);
            com.jrpetty.mcassistant.entity.Weather.stormForTests(null);
            helper.assertTrue(storm.contains("storm"), "a storm holds it in port: " + storm);
            BlockPos berth = Harbours.atForTests(s, 3 + 5, 5, 0);
            net.minecraft.world.level.block.state.BlockState was = level.getBlockState(berth);
            level.setBlock(berth, Blocks.ICE.defaultBlockState(), 3);
            helper.assertTrue(ShipCalls.heldUpForTests(level, v).contains("iced"), "ice in the harbour: " + ShipCalls.heldUpForTests(level, v));
            level.setBlock(berth, was, 3);
            List<VillageFolkEntity> other = town(helper, x - 200, Z, Kit.surface(level, x - 200, Z).getY(), 1);
            UUID enemy = other.get(0).ownerId();
            com.jrpetty.mcassistant.entity.Wars.begin(id, enemy, level.getDayTime() / 24000L);
            helper.assertTrue(ShipCalls.heldUpForTests(level, v).contains("war"), "no ship sails into a war: " + ShipCalls.heldUpForTests(level, v));
            com.jrpetty.mcassistant.entity.Wars.end(id, enemy);
            helper.succeed();
        });
    }

    // ============================================================ tw04: the road made better

    /** Two towns at x and x + far on flat ground with a flat strip between, held. Returns {town A's folk, town B's folk}. */
    private static List<List<VillageFolkEntity>> twoTowns(GameTestHelper helper, int x, int far, int folkA, int folkB) {
        ServerLevel level = helper.getLevel();
        int y = Kit.surface(level, x, Z).getY();
        for (int cx = x; cx <= x + far; cx += 160) {
            Kit.hold(level, cx, Z, 60);
            Kit.prepare(level, cx, Z, 60);
        }
        Kit.hold(level, x + far, Z, 60);
        Kit.prepare(level, x + far, Z, 60);
        flat(level, x - 44, x + 44, Z - 44, Z + 44, y);
        flat(level, x + far - 44, x + far + 44, Z - 44, Z + 44, y);
        flat(level, x + 44, x + far - 44, Z - 12, Z + 12, y);
        List<List<VillageFolkEntity>> out = new ArrayList<>();
        out.add(town(helper, x, Z, y, folkA));
        out.add(town(helper, x + far, Z, y, folkB));
        return out;
    }

    /** The top block of the ground at a column (the road's surface). */
    private static net.minecraft.world.level.block.state.BlockState top(ServerLevel level, int x, int z) {
        return level.getBlockState(Kit.surface(level, x, z).below());
    }

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "tw04_roads")
    public static void tw04_roads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2246000;
        fresh(helper, DAY + 2000);
        List<List<VillageFolkEntity>> towns = twoTowns(helper, x, 200, 6, 3);
        UUID a0 = towns.get(0).get(0).ownerId(), b0 = towns.get(1).get(0).ownerId();
        UUID first = RoadWorks.firstForTests(a0, b0), other = first.equals(a0) ? b0 : a0;
        VillageFolkEntity walker = (first.equals(a0) ? towns.get(0) : towns.get(1)).get(1);
        Villages.Village v = Villages.get(first), w = Villages.get(other);
        int y = Kit.surface(level, x, Z).getY();
        helper.runAtTickTime(5, () -> {
            List<int[]> line = RoadWorks.lineForTests(first, other);
            helper.assertTrue(line != null && line.size() > 60, "the two towns are on a road: " + (line == null ? "none" : line.size()));
            int n = line.size(), mid = n / 2;
            Kit.log("tw04 road " + n + " steps, from " + line.get(0)[0] + " to " + line.get(n - 1)[0] + "; first " + Villages.name(first));
            // Traffic counts both halves.
            RoadWorks.travelled(first, other);
            helper.assertTrue(RoadWorks.halfForTests(first, other)[2] == 1 && RoadWorks.halfForTests(other, first)[2] == 1, "a trip along it counts for both");
            // Gravel: a Stone Age town with traffic, coin and gravel lays its half.
            Villages.ageForTests(first, Villages.Age.STONE);
            RoadWorks.setHalfForTests(first, other, 1, 0, 8);
            Ledger.addCoins(first, 100);
            helper.assertTrue(RoadWorks.wantsForTests(level, v, other).isEmpty(), "ready to gravel: " + RoadWorks.wantsForTests(level, v, other));
            clearOf(level, first, st -> st.is(Items.GRAVEL) || st.is(Items.DIRT));
            chestAt(level, new BlockPos(v.centre().getX() - 3, y, Z - 5), new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.GRAVEL, 64),
                new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.GRAVEL, 64), new ItemStack(com.jrpetty.mcassistant.item.WorkItems.MILESTONE_ITEM.get(), 4));
            int gravel0 = stock(level, first, st -> st.is(Items.GRAVEL));
            int laid = RoadWorks.workForTests(level, v, other, 1000);
            int gravel1 = stock(level, first, st -> st.is(Items.GRAVEL)), dirt1 = stock(level, first, st -> st.is(Items.DIRT));
            int[] h = RoadWorks.halfForTests(first, other);
            Kit.log("tw04 gravel: " + laid + " steps; gravel " + gravel0 + " -> " + gravel1 + ", dirt back " + dirt1 + "; half " + h[0] + "/" + h[1]);
            helper.assertTrue(h[0] == 2 && laid == mid, "its half made a gravel road: " + h[0] + ", " + laid + " of " + mid);
            int out = 0, gravelTops = 0;
            for (int i = 0; i < mid; i++) {
                int[] p = line.get(i);
                if (Math.abs(p[0] - v.centre().getX()) <= Villages.townReach(first)) continue;
                out++;
                if (top(level, p[0], p[1]).is(Blocks.GRAVEL)) gravelTops++;
            }
            helper.assertTrue(out > 20 && gravelTops == out, "gravel along its half outside the town: " + gravelTops + " of " + out);
            helper.assertTrue(gravel0 - gravel1 == 3 * out && dirt1 == 3 * out, "three gravel a step from the stores, the earth back into them");
            boolean stone = false;
            for (int i = 0; i < mid; i++) {
                if (!(i == 3 || i % RoadWorks.LEAGUE == RoadWorks.LEAGUE / 2) || i <= 6 && i != 3) continue;
                int[] p = line.get(i);
                if (Math.abs(p[0] - v.centre().getX()) <= Villages.townReach(first)) continue;
                BlockPos at = Kit.surface(level, p[0], p[1] - 2).below();
                if (level.getBlockState(at).getBlock() instanceof com.jrpetty.mcassistant.block.MilestoneBlock
                    || level.getBlockState(at.above()).getBlock() instanceof com.jrpetty.mcassistant.block.MilestoneBlock) stone = true;
            }
            helper.assertTrue(stone, "a milestone along it");
            // Quicker going on it, and more carried on a good road.
            int[] g = line.get(mid - 4);
            walker.moveTo(g[0] + 0.5, Kit.surface(level, g[0], g[1]).getY(), g[1] + 0.5, 0, 0);
            helper.assertTrue(Math.abs(RoadWorks.pace(walker) - 1.2) < 1e-6, "a fifth quicker on gravel: " + RoadWorks.pace(walker));
            helper.assertTrue(RoadWorks.extraLots(first, other) == 0, "no more carried till the whole road is gravel");
            RoadWorks.setHalfForTests(other, first, 2, 0, 8);
            helper.assertTrue(RoadWorks.extraLots(first, other) == 1, "a lot more on a gravel road");
            // Stone, in the Iron Age: cobbles, kerbs, lamp posts near town; the gravel back into the stores.
            Villages.ageForTests(first, Villages.Age.IRON);
            RoadWorks.setHalfForTests(first, other, 2, 0, 20);
            Ledger.addCoins(first, 200);
            clearOf(level, first, st -> st.is(Items.GRAVEL) || st.is(Items.COBBLESTONE));
            chestAt(level, new BlockPos(v.centre().getX() - 5, y, Z - 5), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.TORCH, 16), new ItemStack(Items.OAK_PLANKS, 64));
            laid = RoadWorks.workForTests(level, v, other, 1000);
            h = RoadWorks.halfForTests(first, other);
            int gravelBack = stock(level, first, st -> st.is(Items.GRAVEL));
            Kit.log("tw04 stone: " + laid + " steps; half " + h[0] + "; gravel back " + gravelBack);
            helper.assertTrue(h[0] == 3, "its half made a stone road: " + h[0]);
            helper.assertTrue(gravelBack == 3 * out, "the gravel back into the stores: " + gravelBack);
            int cobbled = 0, kerbed = 0;
            boolean lamp = false;
            for (int i = 0; i < mid; i++) {
                int[] p = line.get(i);
                if (Math.abs(p[0] - v.centre().getX()) <= Villages.townReach(first)) continue;
                if (top(level, p[0], p[1]).is(Blocks.COBBLESTONE)) cobbled++;
                if (level.getBlockState(Kit.surface(level, p[0], p[1] + 2).below()).getBlock() instanceof net.minecraft.world.level.block.SlabBlock) kerbed++;
                BlockPos lit = Kit.surface(level, p[0], p[1] + 3);
                for (BlockPos q : new BlockPos[]{ lit, lit.below() }) {
                    if (level.getBlockState(q).is(Blocks.LANTERN) || level.getBlockState(q).is(Blocks.TORCH)) lamp = true;
                }
            }
            Kit.log("tw04 cobbled " + cobbled + ", kerbed " + kerbed + " of " + out + "; lamp " + lamp);
            helper.assertTrue(cobbled == out && kerbed >= out / 2, "cobbled with kerbs: " + cobbled + ", " + kerbed + " of " + out);
            helper.assertTrue(lamp, "a lamp post near town");
            // The other town's half: worn to a path by its own traffic.
            RoadWorks.setHalfForTests(other, first, 0, 0, 3);
            int path = RoadWorks.workForTests(level, w, first, 1000);
            int[] hw = RoadWorks.halfForTests(other, first);
            int[] q = line.get(mid + 10);
            Kit.log("tw04 path: " + path + " steps, half " + hw[0] + "; top " + top(level, q[0], q[1]));
            helper.assertTrue(hw[0] == 1 && top(level, q[0], q[1]).is(Blocks.DIRT_PATH), "the other half worn to a path");
            List<String> page = RoadWorks.lines(level, v);
            Kit.log("tw04 page: " + String.join(" / ", page));
            helper.assertTrue(String.join(" ", page).contains("To " + Villages.name(other)), "the Roads page has the road");
            helper.succeed();
        });
    }

    // ============================================================ tw05: the inn halfway along the road

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "tw05_inn")
    public static void tw05_inn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2248000;
        fresh(helper, DAY + 2000);
        List<List<VillageFolkEntity>> towns = twoTowns(helper, x, 440, 12, 3);
        UUID a = towns.get(0).get(0).ownerId(), b = towns.get(1).get(0).ownerId();
        Villages.Village v = Villages.get(a);
        int y = Kit.surface(level, x, Z).getY();
        helper.runAtTickTime(5, () -> {
            flat(level, x + 160, x + 280, Z - 34, Z + 34, y);
            Villages.ageForTests(a, Villages.Age.STONE);
            RoadWorks.setHalfForTests(a, b, 1, 0, 6);
            RoadWorks.setHalfForTests(b, a, 1, 0, 6);
            Ledger.addCoins(a, 300);
            chestAt(level, new BlockPos(x - 3, y, Z - 5), new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_LOG, 64),
                new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.STONE_BRICKS, 64),
                new ItemStack(Items.GLASS, 32), new ItemStack(Items.GLASS_PANE, 32), new ItemStack(Items.TORCH, 32),
                new ItemStack(Items.WHITE_WOOL, 16), new ItemStack(Items.CHEST, 4));
            chestAt(level, new BlockPos(x - 5, y, Z - 5), new ItemStack(Items.BREAD, 32), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.RED_BED, 4), new ItemStack(Items.BARREL, 2));
            Roadside.considerForTests(level, v);
            Kit.log("tw05 planned: '" + Roadside.stateForTests(a, b) + "'");
            helper.assertTrue(Roadside.stateForTests(a, b).startsWith("building"), "the inn paid for and begun: " + Roadside.stateForTests(a, b)
                + " " + history(a));
            for (int i = 0; i < 80 && !Roadside.stateForTests(a, b).startsWith("open"); i++) Roadside.tickForTests(level, v);
            Kit.log("tw05 built: '" + Roadside.stateForTests(a, b) + "'");
            helper.assertTrue(Roadside.stateForTests(a, b).startsWith("open") && Roadside.stateForTests(a, b).endsWith("kept"),
                "the inn open, its keeper come: " + Roadside.stateForTests(a, b));
            BlockPos inn = Roadside.anchorForTests(a, b);
            helper.assertTrue(inn.getX() > x + 150 && inn.getX() < x + 290, "halfway along the road: " + inn.toShortString());
            helper.assertTrue(told(a, "the inn on the road to " + Villages.name(b) + " opened") && told(b, "opened its doors"),
                "both chronicles have it: " + history(a));
            int larder = Roadside.larderForTests(level, a, b);
            helper.assertTrue(larder > 0, "its larder stocked out of the stores: " + larder);
            // A player: a room (and the bed to wake at), a meal.
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            p.moveTo(inn.getX() + 0.5, inn.getY(), inn.getZ() + 0.5);
            p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 10));
            String room = Roadside.roomForTests(p, a, b, false);
            Kit.log("tw05 room: " + room + "; coins " + coinsOf(p) + "; respawn " + p.getRespawnPosition());
            helper.assertTrue(coinsOf(p) == 7 && p.getRespawnPosition() != null
                && level.getBlockState(p.getRespawnPosition()).getBlock() instanceof net.minecraft.world.level.block.BedBlock, "a room for three coins, and its bed to wake at");
            String meal = Roadside.roomForTests(p, a, b, true);
            int food = 0;
            for (ItemStack st : p.getInventory().items) if (st.get(net.minecraft.core.component.DataComponents.FOOD) != null) food += st.getCount();
            Kit.log("tw05 meal: " + meal + "; coins " + coinsOf(p) + "; food " + food);
            helper.assertTrue(coinsOf(p) == 5 && food == 1 && Roadside.larderForTests(level, a, b) == larder - 1, "a meal out of the larder for two");
            // A traveller takes a room out of its purse.
            VillageFolkEntity t = towns.get(1).get(1);
            t.moveTo(inn.getX() + 3.5, inn.getY(), inn.getZ() + 3.5, 0, 0);
            t.earn(5);
            int purse = t.purse();
            BlockPos bed = Roadside.lodgeForTests(level, t);
            helper.assertTrue(bed != null && t.purse() == purse - 3, "a traveller's room out of its purse: " + bed + " " + t.purse());
            // At war: shut.
            com.jrpetty.mcassistant.entity.Wars.begin(a, b, level.getDayTime() / 24000L);
            String shut = Roadside.roomForTests(p, a, b, true);
            helper.assertTrue(shut.contains("shut"), "shut while the war is on the road: " + shut);
            com.jrpetty.mcassistant.entity.Wars.end(a, b);
            helper.succeed();
        });
    }

    // ============================================================ tw06: thinking of leaving; talked round; gone

    /** A hungry town (nothing in its stores to eat) at x and a well-fed one at x + far. */
    private static List<List<VillageFolkEntity>> hungryAndFed(GameTestHelper helper, int x, int far, int folkA) {
        ServerLevel level = helper.getLevel();
        List<List<VillageFolkEntity>> towns = twoTowns(helper, x, far, folkA, 3);
        UUID a = towns.get(0).get(0).ownerId(), b = towns.get(1).get(0).ownerId();
        int y = Kit.surface(level, x, Z).getY();
        clearOf(level, a, st -> st.get(net.minecraft.core.component.DataComponents.FOOD) != null);
        chestAt(level, new BlockPos(x + far - 3, y, Z - 5), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64),
            new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64));
        return towns;
    }

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "tw06_migration")
    public static void tw06_migration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2250000;
        fresh(helper, DAY + 2000);
        List<List<VillageFolkEntity>> towns = hungryAndFed(helper, x, 240, 12);
        UUID a = towns.get(0).get(0).ownerId(), b = towns.get(1).get(0).ownerId();
        Villages.Village va = Villages.get(a);
        VillageFolkEntity f = towns.get(0).get(5);
        VillageFolkEntity[] gg = { null };
        ServerPlayer[] p = new ServerPlayer[1];
        int[] phase = { 0 };
        int[] waited = { 0 };
        helper.runAtTickTime(5, () -> {
            com.jrpetty.mcassistant.entity.Contentment.resetForTests();               // worked out afresh, with the stores as they are now
            int sa = com.jrpetty.mcassistant.entity.Contentment.of(level, a).score(), sb = com.jrpetty.mcassistant.entity.Contentment.of(level, b).score();
            int[] pt = Migration.pushAndTiesForTests(level, f);
            Kit.log("tw06 contentment " + sa + " against " + sb + "; push " + pt[0] + ", ties " + pt[1]);
            helper.assertTrue(sb - sa >= 12, "the fed town is the better place: " + sa + " / " + sb);
            UUID to = Migration.thinkNowForTests(level, f);
            helper.assertTrue(b.equals(to), "it thinks of leaving for the fed town: " + to);
            helper.assertTrue(Migration.cardLine(f).contains("thinking of leaving for " + Villages.name(b)), "its card says so: " + Migration.cardLine(f));
            helper.assertTrue(TradeWorld.contextFlags(f).contains("leaving"), "the talk screen knows: " + TradeWorld.contextFlags(f));
            com.jrpetty.mcassistant.client.TalkMenu.Context c = com.jrpetty.mcassistant.client.TalkMenu.Context.of("v1 trade=FARM leaving", 0, false, false, false);
            boolean suggested = false;
            for (com.jrpetty.mcassistant.client.TalkMenu.Entry e : com.jrpetty.mcassistant.client.TalkMenu.suggested(c)) if (e.id().equals("mig_stay")) suggested = true;
            helper.assertTrue(suggested, "\"Won't you stay?\" is suggested first");
            // A player's raise keeps it.
            p[0] = helper.makeMockServerPlayerInLevel();
            p[0].getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 10));
            int purse = f.purse();
            String said = Migration.persuadeForTests(level, f, p[0]);
            Kit.log("tw06 persuaded: '" + said + "'; coins " + coinsOf(p[0]) + "; purse " + purse + " -> " + f.purse());
            helper.assertTrue(coinsOf(p[0]) == 10 - Migration.RAISE && f.purse() == purse + Migration.RAISE, "the raise out of your purse into its own");
            Migration.decideForTests(level, f);
            helper.assertTrue(!Migration.thinking(f) && a.equals(f.ownerId()), "it stays");
            helper.assertTrue(told(a, f.displayNameCap() + " had been thinking of leaving us, but stayed: was talked round"), "the chronicle has it: " + history(a));
            helper.assertTrue(Migration.countsForTests(a)[3] == 1, "talked round, on the books");
            // Another, left alone (the one with least to keep it): off to the fed town by the road.
            int best = Integer.MIN_VALUE;
            for (VillageFolkEntity o : towns.get(0)) {
                if (o == f || o == towns.get(0).get(0)) continue;
                int[] q = Migration.pushAndTiesForTests(level, o);
                if (q[0] - q[1] > best) { best = q[0] - q[1]; gg[0] = o; }
            }
            VillageFolkEntity g = gg[0];
            UUID to2 = Migration.thinkNowForTests(level, g);
            helper.assertTrue(b.equals(to2), "another thinks of it: " + to2);
            Migration.decideForTests(level, g);
            Kit.log("tw06 " + g.displayNameCap() + " decided: " + history(a));
            helper.assertTrue(!Migration.thinking(g), "it has made up its mind");
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            if (phase[0] != 1 || helper.getTick() % 20 != 0) return;
            VillageFolkEntity g = gg[0];
            if (!b.equals(g.ownerId())) {
                if (++waited[0] % 10 == 0) Kit.log("tw06 @" + helper.getTick() + " " + g.displayNameCap() + " still of " + Villages.name(g.ownerId())
                    + ": " + g.offWorkWhy());
                return;
            }
            phase[0] = 2;
            Kit.log("tw06 " + g.displayNameCap() + " gone: " + history(a));
            helper.assertTrue(com.jrpetty.mcassistant.entity.JobSeekers.travelling(g), "it is on the road");
            helper.assertTrue(told(a, g.displayNameCap() + " left for " + Villages.name(b)), "the old town's chronicle has it: " + history(a));
            helper.assertTrue(Migration.countsForTests(a)[1] == 1 && Migration.countsForTests(b)[0] == 1, "one out of the one, into the other");
            // A town of eight lets nobody go.
            List<VillageFolkEntity> small = town(helper, x - 260, Z, Kit.surface(level, x - 260, Z).getY(), 8);
            Kit.hold(level, x - 260, Z, 60);
            UUID c8 = small.get(0).ownerId();
            List<String> went = Migration.migrateNow(level, Villages.get(c8), 2);
            Kit.log("tw06 town of eight: " + went);
            for (String l : went) helper.assertTrue(!l.contains("->"), "nobody leaves a town of eight: " + went);
            helper.succeed();
        });
    }

    // ============================================================ tw07: turned back halfway

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "tw07_turnback")
    public static void tw07_turnback(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2252000;
        fresh(helper, DAY + 2000);
        List<List<VillageFolkEntity>> towns = hungryAndFed(helper, x, 240, 12);
        UUID a = towns.get(0).get(0).ownerId(), b = towns.get(1).get(0).ownerId();
        VillageFolkEntity[] g = new VillageFolkEntity[1];
        int[] phase = { 0 };
        helper.runAtTickTime(5, () -> {
            Migration.ROAD_DANGER = (l, pos) -> "outlaws on the road";
            List<String> went = Migration.migrateNow(level, Villages.get(a), 1);
            Kit.log("tw07 went: " + went);
            helper.assertTrue(went.size() == 1 && went.get(0).contains("-> " + Villages.name(b)), "one sets out: " + went);
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            if (phase[0] == 0 || helper.getTick() % 10 != 0) return;
            try {
                if (phase[0] == 1) {
                    for (VillageFolkEntity f : towns.get(0)) {
                        if (b.equals(f.ownerId()) && com.jrpetty.mcassistant.entity.JobSeekers.travelling(f)) g[0] = f;
                    }
                    if (g[0] == null) return;
                    helper.assertTrue(com.jrpetty.mcassistant.entity.JobSeekers.halfwayForTests(level, g[0]), "set down halfway");
                    phase[0] = 2;
                    return;
                }
                if (phase[0] == 2) {
                    if (!a.equals(g[0].ownerId())) return;
                    Kit.log("tw07 turned back: " + history(a) + " || " + history(b));
                    helper.assertTrue(com.jrpetty.mcassistant.entity.JobSeekers.travelling(g[0]), "walking home");
                    helper.assertTrue(told(a, "turned back halfway, outlaws on the road"), "the old town's chronicle: " + history(a));
                    helper.assertTrue(told(b, "turned back: outlaws on the road"), "the other town's chronicle: " + history(b));
                    helper.assertTrue(Migration.countsForTests(a)[2] == 1 && Migration.countsForTests(a)[1] == 0, "turned back, on the books");
                    phase[0] = 3;
                    Migration.ROAD_DANGER = (l, pos) -> null;
                    helper.succeed();
                }
            } catch (RuntimeException e) {
                Migration.ROAD_DANGER = (l, pos) -> null;
                throw e;
            }
        });
    }

    // ============================================================ tw08: kept by a counter-offer; a house with the notice

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "tw08_counter")
    public static void tw08_counter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2254000;
        fresh(helper, DAY + 2000);
        List<List<VillageFolkEntity>> towns = twoTowns(helper, x, 240, 10, 3);
        UUID a = towns.get(0).get(0).ownerId(), b = towns.get(1).get(0).ownerId();
        int y = Kit.surface(level, x, Z).getY();
        helper.runAtTickTime(5, () -> {
            chestAt(level, new BlockPos(x - 3, y, Z - 5), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64));
            Ledger.addCoins(a, 300);
            com.jrpetty.mcassistant.entity.Contentment.resetForTests();
            VillageFolkEntity smith = towns.get(0).get(4);
            smith.setStation(smith.blockPosition(), com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FISH);
            smith.tradeXpForTests(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FISH, 900000);
            int lv = smith.tradeLevel(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FISH);
            int coins = Ledger.coins(a), purse = smith.purse();
            int score = com.jrpetty.mcassistant.entity.Contentment.of(level, a).score();
            boolean kept = Migration.counterOfferForTests(level, smith, b);
            Kit.log("tw08 level " + lv + ", contentment " + score + ": kept " + kept + "; treasury " + coins + " -> " + Ledger.coins(a)
                + ", purse " + purse + " -> " + smith.purse());
            helper.assertTrue(lv >= 4 && kept, "a master its town can pay to keep is kept");
            helper.assertTrue(coins - Ledger.coins(a) == smith.purse() - purse && smith.purse() > purse, "paid out of the treasury into its purse");
            helper.assertTrue(told(a, "the elder paid " + smith.displayNameCap()) && told(b, "its own town paid it to stay"),
                "both chronicles: " + history(a) + " || " + history(b));
            // With no coin to spare in the treasury, no counter-offer.
            Ledger.takeCoins(a, Ledger.coins(a));
            helper.assertTrue(!Migration.counterOfferForTests(level, smith, b), "an empty treasury keeps nobody");
            // A notice with a house to be had says so.
            com.jrpetty.mcassistant.entity.JobMarket.Opening o = com.jrpetty.mcassistant.entity.JobMarket.postFor(level, Villages.get(b),
                com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FARM);
            if (o != null) {
                String notice = Migration.noticeForTests(b, o);
                int room = Villages.housing(b) - Villages.headcount(b);
                Kit.log("tw08 notice (room " + room + "): " + notice);
                helper.assertTrue(notice.contains("and a house") == (room >= 2), "the notice says whether a house goes with it: " + notice);
            }
            helper.succeed();
        });
    }

    // ============================================================ tw09: the talk

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "tw09_talk")
    public static void tw09_talk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2256000;
        fresh(helper, DAY + 2000);
        List<VillageFolkEntity> folk = harbourTown(helper, x, true);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        helper.runAtTickTime(5, () -> {
            folk.get(3).setStation(folk.get(3).blockPosition(), com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FISH);
            VillageFolkEntity master = Harbours.master(level, v);
            helper.assertTrue(master != null, "a harbourmaster");
            String name = ShipCalls.sightNow(level, v, "qadira");
            for (int i = 0; i < 60 && !ShipCalls.stateForTests(id).startsWith("docked"); i++) ShipCalls.sailForTests(level, v);
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            String m = com.jrpetty.mcassistant.entity.FolkTalk.answer(master, p, com.jrpetty.mcassistant.entity.TalkTopic.SAY, "When's the next ship due?");
            Kit.log("tw09 harbourmaster: " + m);
            helper.assertTrue(m.contains(name) && m.contains("in harbour"), "the harbourmaster knows the ship in: " + m);
            VillageFolkEntity other = folk.get(6) == master ? folk.get(7) : folk.get(6);
            String o = com.jrpetty.mcassistant.entity.FolkTalk.answer(other, p, com.jrpetty.mcassistant.entity.TalkTopic.SAY, "When's the next ship due?");
            Kit.log("tw09 another: " + o);
            helper.assertTrue(o.contains(name) && o.contains("Ask " + master.displayNameCap()), "anybody else points to the harbourmaster: " + o);
            String r = com.jrpetty.mcassistant.entity.FolkTalk.answer(other, p, com.jrpetty.mcassistant.entity.TalkTopic.SAY, "How are the roads?");
            Kit.log("tw09 roads: " + r);
            helper.assertTrue(r.toLowerCase(java.util.Locale.ROOT).contains("road"), "the roads in words: " + r);
            String flags = TradeWorld.contextFlags(master);
            helper.assertTrue(flags.contains("harbourmaster") && flags.contains("harbour"), "the talk screen's flags: " + flags);
            // The captain opens its trading table.
            VillageFolkEntity captain = (VillageFolkEntity) level.getEntity(ShipCalls.crewForTests(id).get(0));
            String t = com.jrpetty.mcassistant.entity.FolkTalk.answer(captain, p, com.jrpetty.mcassistant.entity.TalkTopic.TRADE, "");
            Kit.log("tw09 captain: " + t + "; menu " + p.containerMenu.getClass().getSimpleName());
            helper.assertTrue(p.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu, "the trading table open: " + t);
            p.closeContainer();
            // The menu: each new option only where it belongs, the tabs still six at most.
            com.jrpetty.mcassistant.client.TalkMenu.Context harbour = com.jrpetty.mcassistant.client.TalkMenu.Context.of("v1 trade=FISH harbourmaster harbour", 0, false, false, false);
            com.jrpetty.mcassistant.client.TalkMenu.Context plain = com.jrpetty.mcassistant.client.TalkMenu.Context.of("v1 trade=FARM", 0, false, false, false);
            boolean suggested = false;
            for (com.jrpetty.mcassistant.client.TalkMenu.Entry e : com.jrpetty.mcassistant.client.TalkMenu.suggested(harbour)) if (e.id().equals("harbour_ship")) suggested = true;
            helper.assertTrue(suggested, "the harbourmaster's own suggested");
            helper.assertTrue(shows(harbour, "harbour_ship") && !shows(plain, "harbour_ship") && !shows(plain, "mig_stay") && shows(plain, "roads_how"),
                "each option only where it belongs");
            for (com.jrpetty.mcassistant.client.TalkMenu.Category cat : com.jrpetty.mcassistant.client.TalkMenu.Category.values()) {
                int n = com.jrpetty.mcassistant.client.TalkMenu.visible(cat.entries(), com.jrpetty.mcassistant.client.TalkMenu.Context.NONE).size();
                helper.assertTrue(n <= 6, cat + " still six at most: " + n);
            }
            helper.succeed();
        });
    }

    /** Does this option (by id) show anywhere in the menu, in this context? */
    private static boolean shows(com.jrpetty.mcassistant.client.TalkMenu.Context c, String id) {
        for (com.jrpetty.mcassistant.client.TalkMenu.Category cat : com.jrpetty.mcassistant.client.TalkMenu.Category.values()) {
            for (com.jrpetty.mcassistant.client.TalkMenu.Entry e : com.jrpetty.mcassistant.client.TalkMenu.visible(cat.entries(), c)) {
                if (e.id().equals(id)) return true;
                if (e.isGroup()) for (com.jrpetty.mcassistant.client.TalkMenu.Entry k : com.jrpetty.mcassistant.client.TalkMenu.visible(e.children(), c)) {
                    if (k.id().equals(id)) return true;
                }
            }
        }
        return false;
    }
}
