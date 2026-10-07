package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CaveDwellers;
import com.jrpetty.mcassistant.entity.CaveGuests;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Lodge;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Museum;
import com.jrpetty.mcassistant.entity.Quests;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.TownMine;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.gametest.CaveDwellerGameTests.Cave;
import com.jrpetty.mcassistant.gametest.CaveDwellerGameTests.Town;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.MuseumRecords;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [caves] The world round the cave team (Lodge, CaveGuests, TownMine's lead, the museum's plaques, the quest board's
 * caves, the stage). Each test on its own ground (x 940000 to 949000, z 66000), in a batch of its own, on the cave
 * team's test towns (CaveDwellerGameTests.town).
 *
 * <ul>
 * <li>cl01: the Delvers' Lodge: wanted in the Iron Age once the town keeps a team (not in the Stone Age); built, its
 *     bunks the team's; its walls fitted out by one of the team at home: four maps of the cave country hung (every
 *     cave marked), a trophy on its wall named for who brought it up and from where (and never the town's last), the
 *     team's log on its lectern; the team's post moves to it, and the team gathers there of a morning.</li>
 * <li>cl02: a player buys a copy of the cave map from one of the team: a real filled map, the caves marked on it,
 *     paid for in coin; one who is not of the team sends it to them.</li>
 * <li>cl03: "Cave team, look east": the team's next trip goes out east to find a cave, and says so; the ask is done
 *     with.</li>
 * <li>cl04: "find us diamonds" with only stone picks: declined, and why; "find us iron": the team makes for the cave
 *     on its list with an iron vein, west of the town, over the nearer one east.</li>
 * <li>cl05: a player asks to go along (for a share): booked; of a morning the team waits for it; it comes, and goes
 *     with the team; home, its share is kept at the storehouse and handed over when it asks.</li>
 * <li>cl06: the town's mine follows the cave team's lead: a miner's next face goes over a rich iron vein the team
 *     listed within the mine's reach, dug to its depth and held there; the vein is the mine's, off the team's list.</li>
 * <li>cl07: the museum's plaque names the finder and the cave (CaveDwellers.foundWhere: the cave it is in now).</li>
 * <li>cl08: a cave the team turned back from for its monsters goes up on the quest board, to be cleared, once.</li>
 * <li>cl09: the stage's cave lit as the team lights a cave (the pictures were black).</li>
 * <li>cl10: a trip from and home to the lodge: the finds in the town's report while the team is still down there;
 *     home to the lodge's door, then the haul on to the storehouse.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CaveLodgeGameTests {

    private static final String EMPTY = CaveDwellerGameTests.EMPTY;
    private static final int Z = CaveDwellerGameTests.Z;

    /** The lodge put up (out of nothing, as a showcase's) and on the town's books: its anchor. */
    private static Ledger.Building lodge(ServerLevel level, Town t, int dx, int dz) {
        BlockPos ground = Kit.surface(level, t.heart().getX() + dx, t.heart().getZ() + dz);
        Showcase.stage(level, ground.getX() - 8, ground.getX() + 8, ground.getZ() - 8, ground.getZ() + 12, ground.getY());
        BuildGoal.stamp(level, Lodge.STRUCTURE, ground, Direction.NORTH, 13, Showcase.painter(Showcase.SPRUCE));
        Ledger.built(t.village(), Lodge.STRUCTURE, ground, Direction.NORTH);
        return Lodge.of(t.village());
    }

    private static List<ItemFrame> frames(ServerLevel level, BlockPos around) {
        return level.getEntitiesOfClass(ItemFrame.class, new AABB(around).inflate(8), f -> f.getTags().contains(Lodge.TAG));
    }

    private static int coins(Player p) {
        return Market.coinsHeld(p);
    }

    // ============================================================ cl01: the lodge

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl01_lodge")
    public static void cl01_lodge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 940000;
        Town t = CaveDwellerGameTests.town(helper, x, Villages.Age.STONE, StationTask.CAVE, StationTask.CAVE, StationTask.MINE);
        UUID id = t.village();
        boolean stone = Lodge.wanted(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        boolean iron = Lodge.wanted(id);
        String why = Lodge.why(id);
        Kit.log("cl01 wanted: stone " + stone + ", iron " + iron + " (" + why + ")");
        helper.assertTrue(!stone && iron && why.contains("Delvers' Lodge"), "a lodge wanted from the Iron Age, with a team: " + why);
        Ledger.Building b = lodge(level, t, -20, -24);
        helper.assertTrue(b != null && !Lodge.wanted(id) && Lodge.hall(id) != null, "the lodge on the town's books, wanted no more");
        int bunks = 0;
        for (BlockPos p : BlockPos.betweenClosed(b.anchor().offset(-6, 0, -6), b.anchor().offset(6, 2, 6))) {
            if (level.getBlockState(p).getBlock() instanceof BedBlock && Lodge.isLodgeBed(id, p)) bunks++;
        }
        helper.assertTrue(bunks >= 2, "its bunks, the team's: " + bunks + " bed blocks");
        // The cave country: three caves found, one far out west.
        CaveDwellers.recordForTests(id, t.heart().offset(90, -20, 10), "a big cave", 20, 300);
        CaveDwellers.recordForTests(id, t.heart().offset(-110, -30, 40), "a great cave", 30, 700);
        CaveDwellers.recordForTests(id, t.heart().offset(20, -12, -100), "a ravine", 25, 200);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 64), new ItemStack(Items.ITEM_FRAME, 12), new ItemStack(Items.BOOK, 3),
            new ItemStack(Items.DIAMOND, 3), new ItemStack(Items.EMERALD, 1), new ItemStack(Items.BREAD, 64));
        long day = level.getDayTime() / 24000L;
        Lodge.broughtUp(id, new ItemStack(Items.DIAMOND), "Ada", "the big cave east", day);
        Lodge.broughtUp(id, new ItemStack(Items.EMERALD), "Ada", "the great cave west", day);
        // An afternoon at home (no trip to set out on). The frames go up a second on: hung the tick the lodge's ground was
        // first held, they stood in a part of the world not yet open to a look for them, and the test (and the wall's own
        // look for a frame already there) found none.
        level.setDayTime(day * 24000L + 8000);
        level.updateSkyBrightness();
        VillageFolkEntity d = t.folk().get(0);
        BlockPos hall = Lodge.hall(id);
        helper.runAfterDelay(20, () -> fitted(helper, level, t, b, d, hall, day));
    }

    private static void fitted(GameTestHelper helper, ServerLevel level, Town t, Ledger.Building b, VillageFolkEntity d, BlockPos hall, long day) {
        UUID id = t.village();
        // One of the team at home, in the hall.
        d.moveTo(hall.getX() + 0.5, hall.getY(), hall.getZ() + 0.5, 0.0F, 0.0F);
        Lodge.tick(level, t.v(), day);
        Lodge.tick(level, t.v(), day);
        List<ItemFrame> fr = frames(level, hall);
        int maps = 0, marks = 0, trophies = 0;
        String label = "";
        for (ItemFrame f : fr) {
            ItemStack s = f.getItem();
            if (s.is(Items.FILLED_MAP)) {
                maps++;
                MapDecorations m = s.get(DataComponents.MAP_DECORATIONS);
                if (m != null) for (String k : m.decorations().keySet()) if (k.startsWith("caves")) marks++;
            } else if (!s.isEmpty()) {
                trophies++;
                label = s.getHoverName().getString();
            }
        }
        boolean log = level.getBlockEntity(Lodge.at(b, 3, 0, -2)) instanceof LecternBlockEntity lec && lec.hasBook()
            && lec.getBook().is(Items.WRITTEN_BOOK);
        int diamonds = CaveDwellerGameTests.stock(level, id, Items.DIAMOND), emeralds = CaveDwellerGameTests.stock(level, id, Items.EMERALD);
        Kit.log("cl01 fitted: " + maps + " maps (" + marks + " caves marked), " + trophies + " trophies (" + label + "), log " + log
            + "; diamonds left " + diamonds + ", emeralds " + emeralds + "; " + CaveDwellers.lodgeLine(level, t.v()));
        helper.assertTrue(maps == 4 && marks >= 3, "the map wall: four sheets of the cave country, every cave marked: " + maps + " maps, " + marks + " marks");
        helper.assertTrue(trophies >= 1 && label.contains("brought up by Ada") && label.contains("the big cave east"),
            "a trophy on the wall, named for who brought it up and from where: " + label);
        helper.assertTrue(diamonds == 2 && emeralds == 1, "out of what the stores can spare, never the last: diamonds " + diamonds + ", emeralds " + emeralds);
        helper.assertTrue(log, "the team's log on the lectern");
        // The post moves to the lodge (an afternoon at home), and of a morning the team gathers there.
        level.setDayTime(day * 24000L + 8000);
        CaveDwellers.work(d, level);
        helper.assertTrue(d.stationPos() != null && d.stationPos().distSqr(hall) <= 4, "its post at the lodge: " + d.stationPos());
        VillageFolkEntity e = t.folk().get(1);
        e.moveTo(t.heart().getX() + 0.5, t.heart().getY(), t.heart().getZ() + 0.5, 0.0F, 0.0F);
        level.setDayTime(day * 24000L + 1500);
        level.updateSkyBrightness();
        boolean waits = CaveDwellers.gatheringForTests(d, level), walks = CaveDwellers.gatheringForTests(e, level);
        e.moveTo(hall.getX() + 1.5, hall.getY(), hall.getZ() + 0.5, 0.0F, 0.0F);
        boolean off = CaveDwellers.gatheringForTests(d, level);
        Kit.log("cl01 the gathering: waits " + waits + ", walks " + walks + ", then off " + !off);
        helper.assertTrue(waits && walks && !off, "the team gathers at the lodge of a morning, and goes once all are in");
        helper.succeed();
    }

    // ============================================================ cl02: a copy of the cave map

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl02_map")
    public static void cl02_map(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 941000;
        Town t = CaveDwellerGameTests.town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.MINE);
        UUID id = t.village();
        CaveDwellers.recordForTests(id, t.heart().offset(80, -20, 0), "a big cave", 20, 300);
        CaveDwellers.recordForTests(id, t.heart().offset(-60, -10, 60), "a cave", 10, 120);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 20), new ItemStack(Items.BREAD, 64));
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        you.setPos(t.heart().getX() + 0.5, t.heart().getY(), t.heart().getZ() + 1.5);
        you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 10));
        VillageFolkEntity miner = t.folk().get(1), d = t.folk().get(0);
        String no = FolkTalk.answer(miner, you, TalkTopic.SAY, "Could I buy a copy of the cave map?");
        String yes = FolkTalk.answer(d, you, TalkTopic.SAY, "Could I buy a copy of the cave map?");
        ItemStack map = ItemStack.EMPTY;
        for (ItemStack s : you.getInventory().items) if (s.is(Items.FILLED_MAP)) map = s;
        MapDecorations m = map.isEmpty() ? null : map.get(DataComponents.MAP_DECORATIONS);
        int marks = m == null ? 0 : m.decorations().size();
        Kit.log("cl02 the miner: " + no + " | the cave dweller: " + yes + " | map " + (map.isEmpty() ? "none" : map.getHoverName().getString())
            + ", " + marks + " marks; coins " + coins(you));
        helper.assertTrue(no.contains("cave team") && no.contains("lodge"), "one not of the team sends it to them: " + no);
        helper.assertTrue(!map.isEmpty() && marks >= 3 && coins(you) == 10 - Lodge.MAP_PRICE,
            "a real filled map, the caves and the town marked, for " + Lodge.MAP_PRICE + " coins: " + marks + " marks, coins " + coins(you));
        helper.succeed();
    }

    // ============================================================ cl03: "look east"

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl03_ask_way")
    public static void cl03_ask_way(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 942000;
        Town t = CaveDwellerGameTests.town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.CAVE, StationTask.MINE);
        UUID id = t.village();
        CaveDwellerGameTests.fill(t, CaveDwellerGameTests.kit(2, true, 4));
        // A cave known to the south: without the ask, the team would make for it.
        CaveDwellers.recordForTests(id, t.heart().offset(0, -20, 80), "a big cave", 20, 300);
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        you.setPos(t.heart().getX() + 0.5, t.heart().getY(), t.heart().getZ() + 1.5);
        String said = FolkTalk.answer(t.folk().get(2), you, TalkTopic.SAY, "Cave team, look east");
        CaveGuests.Ask ask = CaveGuests.ask(id, level.getDayTime() / 24000L);
        CaveDwellers.Party p = CaveDwellers.setOutForTests(level, t.v());
        BlockPos to = p == null ? null : CaveDwellers.targetForTests(level, p);
        CaveGuests.Ask after = CaveGuests.ask(id, level.getDayTime() / 24000L);
        Kit.log("cl03 asked: " + said + " | ask " + ask + " | set out " + (p == null ? "no" : CaveDwellers.askedForTests(p)) + " toward "
            + (to == null ? "?" : (to.getX() - t.heart().getX()) + ", " + (to.getZ() - t.heart().getZ())) + " | after " + after);
        helper.assertTrue(ask != null && ask.bearing() >= 0 && "east".equals(ask.way()), "the ask kept: " + ask);
        helper.assertTrue(p != null && to != null && to.getX() - t.heart().getX() >= 40 && Math.abs(to.getZ() - t.heart().getZ()) <= 25,
            "out east to find a cave, not to the known one south: " + to);
        helper.assertTrue(CaveDwellers.askedForTests(p).contains("out east"), "and it says so: " + CaveDwellers.askedForTests(p));
        helper.assertTrue(after == null, "the ask done with: " + after);
        helper.succeed();
    }

    // ============================================================ cl04: "find us iron"

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl04_ask_ore")
    public static void cl04_ask_ore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 943000;
        Town t = CaveDwellerGameTests.town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.CAVE, StationTask.MINE);
        UUID id = t.village();
        CaveDwellerGameTests.fill(t, CaveDwellerGameTests.kit(2, false, 4));
        for (VillageFolkEntity f : List.of(t.folk().get(0), t.folk().get(1))) {
            for (EquipmentSlot slot : EquipmentSlot.values()) if (CaveDwellers.isPickaxe(f.getItemBySlot(slot))) f.setItemSlot(slot, ItemStack.EMPTY);
            var pack = f.getInventoryItems();
            for (int i = 0; i < pack.size(); i++) if (CaveDwellers.isPickaxe(pack.get(i))) pack.set(i, ItemStack.EMPTY);
            f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        }
        BlockPos near = t.heart().offset(70, -10, 0), west = t.heart().offset(-90, -20, 10);
        CaveDwellers.recordForTests(id, near, "a cave", 10, 150);
        CaveDwellers.listForTests(id, near, List.of(new CaveDwellers.Vein("coal", near.offset(2, 0, 1), 4, 0, "todo")));
        CaveDwellers.recordForTests(id, west, "a big cave", 20, 300);
        CaveDwellers.listForTests(id, west, List.of(new CaveDwellers.Vein("iron", west.offset(3, 1, 2), 6, 0, "todo")));
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        you.setPos(t.heart().getX() + 0.5, t.heart().getY(), t.heart().getZ() + 1.5);
        VillageFolkEntity d = t.folk().get(0);
        String diamonds = FolkTalk.answer(d, you, TalkTopic.SAY, "Could you find us some diamonds?");
        String iron = FolkTalk.answer(d, you, TalkTopic.SAY, "Could you find us some iron?");
        CaveDwellers.Party p = CaveDwellers.setOutForTests(level, t.v());
        BlockPos to = p == null ? null : CaveDwellers.targetForTests(level, p);
        Kit.log("cl04 diamonds: " + diamonds + " | iron: " + iron + " | set out " + (p == null ? "no" : CaveDwellers.askedForTests(p)) + " to " + to);
        helper.assertTrue(diamonds.contains("iron pick"), "diamonds declined, with only stone picks, and why: " + diamonds);
        helper.assertTrue(iron.contains("on our list"), "iron: there's a vein of it on the list: " + iron);
        helper.assertTrue(p != null && west.equals(to), "the team makes for the cave with the iron, west, over the nearer one: " + to);
        helper.assertTrue(CaveDwellers.askedForTests(p).contains("after iron"), "and says so: " + CaveDwellers.askedForTests(p));
        helper.succeed();
    }

    // ============================================================ cl05: going along

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl05_guest")
    public static void cl05_guest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 944000;
        Town t = CaveDwellerGameTests.town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.CAVE, StationTask.MINE);
        UUID id = t.village();
        CaveDwellerGameTests.fill(t, CaveDwellerGameTests.kit(2, true, 4, new ItemStack(Items.RAW_IRON, 9)));
        long day = level.getDayTime() / 24000L;
        level.setDayTime(day * 24000L + 800);                          // before first light
        level.updateSkyBrightness();
        // The player in the world (the team looks for it there, and waits for it); its words said through a stand-in of
        // the same id that is not in the world (the talk screen's buttons are a payload a test's player cannot take).
        ServerPlayer you = helper.makeMockServerPlayerInLevel();
        you.teleportTo(t.heart().getX() + 60.5, t.heart().getY(), t.heart().getZ() + 0.5);
        Player talker = helper.makeMockPlayer(GameType.SURVIVAL);
        talker.setUUID(you.getUUID());
        talker.setPos(you.getX(), you.getY(), you.getZ());
        VillageFolkEntity d = t.folk().get(0), e = t.folk().get(1);
        d.ensurePersona();
        d.persona().feelFor(you.getUUID(), you.getName().getString(), 12);
        String said = FolkTalk.answer(d, talker, TalkTopic.SAY, "Can I come along with the cave team, for a share?");
        CaveGuests.Guest g = CaveGuests.guest(id);
        level.setDayTime(day * 24000L + 1500);                         // first light: the team gathers
        level.updateSkyBrightness();
        BlockPos post = d.blockPosition();
        e.moveTo(post.getX() + 1.5, post.getY(), post.getZ() + 0.5, 0.0F, 0.0F);
        boolean waits = CaveDwellers.gatheringForTests(d, level);
        String hobby = d.hobbyNow();
        // It comes.
        you.teleportTo(post.getX() + 0.5, post.getY(), post.getZ() + 2.5);
        boolean stillWaits = CaveDwellers.gatheringForTests(d, level);
        CaveDwellers.Party p = CaveDwellers.setOutForTests(level, t.v());
        String with = p == null ? "" : CaveDwellers.askedForTests(p);
        Kit.noLeftoverPlayers(level);                                   // out of the world again, before anything can fail
        Kit.log("cl05 booked: " + said + " | " + g + " | waits " + waits + " (" + hobby + "), then " + stillWaits + " | set out: " + with);
        helper.assertTrue(g != null && g.share() && g.player().equals(you.getUUID()), "booked to go along, for a share: " + said);
        helper.assertTrue(waits && hobby != null && hobby.contains("waiting") && !stillWaits, "the team waits for it of a morning, and goes when it comes");
        helper.assertTrue(p != null && with.contains("with " + you.getName().getString()) && with.contains("share"), "it goes with the team: " + with);
        // Home with nine raw iron into the storehouse: a third is its (two of the team and it), kept for it, and handed over.
        String home = CaveGuests.homeForTests(level, p, you, true, Map.of(Items.RAW_IRON, 9));
        int before = talker.getInventory().countItem(Items.RAW_IRON);
        String handed = FolkTalk.answer(e, talker, TalkTopic.SAY, "Is my share of the haul ready?");
        int after = talker.getInventory().countItem(Items.RAW_IRON);
        Kit.log("cl05 home: " + home + " | handed: " + handed + " | raw iron " + before + " -> " + after + ", stores "
            + CaveDwellerGameTests.stock(level, id, Items.RAW_IRON));
        helper.assertTrue(after - before == 3 && handed.contains("share"), "its share (a third) kept and handed over: " + handed);
        helper.assertTrue(CaveDwellerGameTests.stock(level, id, Items.RAW_IRON) == 6, "the rest the town's");
        helper.succeed();
    }

    // ============================================================ cl06: the town's mine follows the lead

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl06_mine_lead")
    public static void cl06_mine_lead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 945000;
        Town t = CaveDwellerGameTests.town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.MINE, StationTask.GUARD);
        UUID id = t.village();
        VillageFolkEntity miner = t.folk().get(1);
        miner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        // The mine: its first face ninety blocks east of the heart; this miner on a plot of its own out west, off the
        // mine's faces.
        BlockPos site = new BlockPos(t.heart().getX() + 90, 0, t.heart().getZ());
        TownMine.forgetForTests(id);
        Ledger.note(id, "mine.site", site.getX() + "," + site.getZ());
        miner.assignPlot(WorkZone.around(Kit.surface(level, t.heart().getX() - 28, t.heart().getZ() + 40), 8, 16), "a mine of its own");
        // The team's find: a big cave by the mine with a rich iron vein under its next face east.
        BlockPos veinAt = new BlockPos(site.getX() + 17, t.heart().getY() - 3, site.getZ() + 2);
        BlockPos cave = veinAt.offset(-4, 0, 3);
        CaveDwellers.recordForTests(id, cave, "a big cave", 20, 300);
        CaveDwellers.listForTests(id, cave, List.of(new CaveDwellers.Vein("iron", veinAt, 8, 1, "todo"),
            new CaveDwellers.Vein("coal", veinAt.offset(2, 0, 0), 9, 0, "todo")));
        boolean moved = miner.seekTheSeamForTests();
        WorkZone z = miner.workZone();
        long day = level.getDayTime() / 24000L;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(site.getX() + 17, 0, site.getZ()));
        int depth = Math.max(level.getMinBuildHeight() + 14, Math.min(top.getY() - 8, veinAt.getY()));
        String veins = CaveDwellers.veinsForTests(id, cave);
        boolean held = !miner.seekTheSeamForTests();
        WorkZone z2 = miner.workZone();
        List<String> report = TownMine.report(id, t.heart());
        Kit.log("cl06 moved " + moved + " to " + (z == null ? "none" : z.center().toShortString() + " down to Y" + z.depth()) + " (want Y" + depth
            + "); veins " + veins + "; held " + held + " (" + (z2 == null ? "" : "Y" + z2.depth()) + "); " + report);
        helper.assertTrue(moved && z != null && z.center().getX() == site.getX() + 17 && z.center().getZ() == site.getZ(),
            "the miner's next face goes over the team's iron: " + (z == null ? "none" : z.center().toShortString()));
        helper.assertTrue(z.depth() == depth, "dug to the vein's depth: Y" + z.depth() + ", want Y" + depth);
        helper.assertTrue(veins.contains("iron 8 mine") && veins.contains("coal 9 todo"), "the iron the mine's now, off the team's list: " + veins);
        helper.assertTrue(held && z2 != null && z2.depth() == depth && TownMine.onALead(id, z2.center(), day), "and held there while it follows the lead");
        helper.assertTrue(String.join(" ", report).contains("toward the cave team's iron"), "the mine's report says so: " + report);
        helper.succeed();
    }

    // ============================================================ cl07: the museum's plaque

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl07_plaque")
    public static void cl07_plaque(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 946000;
        Town t = CaveDwellerGameTests.town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.MINE);
        UUID id = t.village();
        VillageFolkEntity d = t.folk().get(0);
        String atHome = CaveDwellers.foundWhere(d);
        CaveDwellerGameTests.fill(t, CaveDwellerGameTests.kit(1, true, 2));
        BlockPos cave = t.heart().offset(70, -12, -6);
        CaveDwellers.recordForTests(id, cave, "a great cave", 30, 700);
        boolean sent = CaveDwellers.sendForTests(d, level, cave);
        String down = CaveDwellers.foundWhere(d);
        MuseumRecords.Shown s = new MuseumRecords.Shown();
        s.label = "Diamond";
        s.finder = d.displayNameCap();
        s.trade = "cave dweller";
        s.how = down;
        s.found = 12;
        String[] plaque = Museum.labelLinesForTests(s);
        Kit.log("cl07 at home: " + atHome + " | sent " + sent + ": " + down + " | plaque: " + String.join(" / ", plaque));
        helper.assertTrue(atHome.equals("brought up from the caves"), "at home, no cave to name: " + atHome);
        helper.assertTrue(sent && down.equals("brought up from the great cave east"), "down there, the cave it is in: " + down);
        helper.assertTrue(plaque[1].contains(d.displayNameCap()) && plaque[2].contains("great cave") && plaque[3].equals("day 12"),
            "the plaque names the finder and the cave: " + String.join(" / ", plaque));
        helper.succeed();
    }

    // ============================================================ cl08: the quest board's caves

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl08_quest")
    public static void cl08_quest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 947000;
        Town t = CaveDwellerGameTests.town(helper, x, Villages.Age.IRON, StationTask.CAVE, StationTask.MINE, StationTask.GUARD, StationTask.FARM);
        UUID id = t.village();
        BlockPos at = t.heart().offset(80, -20, 4);
        long day = level.getDayTime() / 24000L;
        Ledger.note(id, "caves.trouble", "zombies|6|the cave east|" + at.getX() + "|" + at.getY() + "|" + at.getZ() + "|" + day);
        Quests.Posting q = Quests.post(level, t.v(), day);
        String again = Ledger.note(id, "caves.trouble");
        Kit.log("cl08 posted: " + (q == null ? "nothing" : q.kind + " " + q.count + " " + q.mob + " at " + q.placeName + " (" + q.place + "), "
            + q.reward + " coins") + "; the note after: '" + again + "'");
        helper.assertTrue(q != null && q.kind.equals("clear") && q.mob.equals("zombies") && q.count == 6 && "the cave east".equals(q.placeName)
            && at.equals(q.place), "the cave goes up on the board to be cleared");
        helper.assertTrue(again == null || again.isEmpty(), "and only once");
        helper.succeed();
    }

    // ============================================================ cl09: the stage, lit

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl09_stage_lit")
    public static void cl09_stage_lit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x0 = 948000, z0 = Z;
        Kit.hold(level, x0 + 12, z0, 40);
        Kit.prepare(level, x0 + 12, z0, 40);
        int g = Kit.surface(level, x0 + 12, z0).getY() + 14;
        for (int xx = x0; xx <= x0 + 24; xx++) {
            for (int zz = z0 - 8; zz <= z0 + 8; zz++) {
                for (int y = g - 13; y <= g - 1; y++) level.setBlock(new BlockPos(xx, y, zz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        CaveDwellers.cutForTests(level, x0, z0, g);
        BlockPos vein = new BlockPos(x0 + 18, g - 6, z0), chest = new BlockPos(x0 + 10, g - 6, z0 - 4);
        int dark = level.getBrightness(LightLayer.BLOCK, vein);
        CaveDwellers.lightForTests(level, x0, z0, g);
        helper.runAfterDelay(10, () -> {
            int v = level.getBrightness(LightLayer.BLOCK, vein), c = level.getBrightness(LightLayer.BLOCK, chest);
            Kit.log("cl09 the stage's light: by the vein " + dark + " -> " + v + ", by the chest " + c);
            helper.assertTrue(v >= 7 && c >= 7, "the cave lit by the vein and by the chest: " + v + ", " + c);
            helper.succeed();
        });
    }

    // ============================================================ cl10: from the lodge, and home to it

    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "cl10_lodge_trip")
    public static void cl10_lodge_trip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 949000;
        Town t = CaveDwellerGameTests.town(helper, x, Villages.Age.IRON, StationTask.CAVE);
        UUID id = t.village();
        VillageFolkEntity d = t.folk().get(0);
        Cave c = CaveDwellerGameTests.cave(level, x + CaveDwellerGameTests.OUT, Z, false);
        lodge(level, t, -10, -22);
        BlockPos door = Lodge.door(id);
        CaveDwellerGameTests.fill(t, CaveDwellerGameTests.kit(1, true, 2));
        CaveDwellers.quickForTests(true);
        CaveDwellerGameTests.toTheMouth(t, c, d);
        boolean sent = CaveDwellers.sendForTests(d, level, c.chamber());
        CaveDwellers.Party p = CaveDwellers.partyOf(d);
        helper.assertTrue(sent && p != null && door != null, "out from the lodge's town");
        int[] liveFinds = { -1 };
        double[] atStore = { -1 };
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (d.expedition() != null) {
                if (liveFinds[0] < 0 && !p.homeward() && CaveDwellers.report(id).size() >= 2) liveFinds[0] = CaveDwellers.report(id).size();
                if (atStore[0] < 0 && "store".equals(p.phase())) atStore[0] = Math.sqrt(d.blockPosition().distSqr(door));
                if (helper.getTick() % 200 == 0) Kit.log("cl10 tick " + helper.getTick() + ": " + p.phase() + ", mined " + p.mined() + "; " + d.hobbyNow());
                CaveDwellerGameTests.pace(level, d);
                return;
            }
            done[0] = true;
            int raw = CaveDwellerGameTests.inStorehouse(t, Items.RAW_IRON);
            Kit.log("cl10 home at tick " + helper.getTick() + " (" + p.why() + "): finds in the report while down there " + liveFinds[0]
                + "; at the store phase " + String.format("%.1f", atStore[0]) + " from the lodge's door; raw iron in the storehouse " + raw);
            helper.assertTrue(liveFinds[0] >= 2, "the finds in the town's report while the team was still down there: " + liveFinds[0]);
            helper.assertTrue(atStore[0] >= 0 && atStore[0] <= 15, "home to the lodge's door first: " + atStore[0]);
            helper.assertTrue(raw >= 1, "then the haul on to the storehouse: " + raw);
            helper.succeed();
        });
    }
}
