package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CaveDwellers;
import com.jrpetty.mcassistant.entity.Cartographers;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.InterviewBook;
import com.jrpetty.mcassistant.entity.Interviews;
import com.jrpetty.mcassistant.entity.MapArchive;
import com.jrpetty.mcassistant.entity.MapFinds;
import com.jrpetty.mcassistant.entity.MapRoom;
import com.jrpetty.mcassistant.entity.MapSurveys;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Prices;
import com.jrpetty.mcassistant.entity.QuestBook;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.gametest.CaveDwellerGameTests.Town;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [cartographer] The cartographer (Cartographers, MapSurveys, MapFinds, MapArchive). Each test on ground of its own
 * (x 1400000 to 1418000, z 66000), in a batch of its own, on the cave tests' little towns (CaveDwellerGameTests.town:
 * folk raised at a heart, a storehouse their only stores). Real maps throughout: the game's MapItem.update fills the
 * sheets, the game's banner markers mark them, the game's locked maps hang on the wall, and the explorer maps are made
 * as the game makes them, to structure starts the world holds (a gametest world makes no structures of its own: the
 * mineshaft is made by the game's own mineshaft generator, the monument is the game's monument piece, both set into
 * the world's chunks as world generation sets them).
 * <ul>
 * <li>ca01: the trade opens in the Stone Age with scouts out (or at thirty folk), not in the Wood Age; the map room goes
 *     on the town's list; once it stands, the town appoints its best candidate, at the map room. Fallen vacant with three
 *     who want it, the place is held open for its interview, and the panel's choice keeps the map room.</li>
 * <li>ca02: the map room built: its cartography table, lectern, chests and bookcases; a table made of the stores' two
 *     paper and four planks when it is gone.</li>
 * <li>ca03: a sheet really fills as the cartographer walks: blank, then filled only round where it stands, then the
 *     rest as it moves on; each pixel the colour of the ground under it; and the live walk fills as it goes.</li>
 * <li>ca04: the wall hung in the hall: four locked sheets in frames, the hall, the storehouse and the market marked
 *     with named banners as the game's banner markers, a sign under it; out of the stores' paper, panes and wool.</li>
 * <li>ca05: a real mineshaft found (one too far out, where the town has never been, is not) and handed to the cave team
 *     as its next lead, with an explorer map to it; on the caves' report and the atlas.</li>
 * <li>ca06: a player buys an ocean explorer map that points to a real monument, priced by its distance and rarity.</li>
 * <li>ca07: paper of the stores' sugar cane and a compass of their iron and redstone, counted in the day's production.</li>
 * <li>ca08: the week's redraw replaces the wall; the old one goes to the archive named for the town in its age; and
 *     the next one too.</li>
 * <li>ca09: a commission: "map me the land to the east", paid, walked, left ready in the map room's chest, handed over.</li>
 * <li>ca10: the region's map framed; a caravan's copy of it fills the hall's as the traveller walks; a far quest comes
 *     with a map to its place.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CartographerGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ the ground

    /** A cave-test town, the ground round it held and made out to this far besides. */
    private static Town town(GameTestHelper helper, int x, Villages.Age age, int hold, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Town t = CaveDwellerGameTests.town(helper, x, age, trades);
        Kit.hold(level, x, Z, hold);
        Kit.prepare(level, x, Z, hold);
        level.setDayTime(level.getDayTime() / 24000L * 24000L + 2000L);
        level.updateSkyBrightness();
        return t;
    }

    /** A building put up at once (a palette's, as the showcase's are) on a cleared lot this far from the heart, on the
     *  town's books. */
    private static Ledger.Building build(ServerLevel level, Town t, String structure, int dx, int dz) {
        BlockPos ground = Kit.surface(level, t.heart().getX() + dx, t.heart().getZ() + dz);
        int[] half = Blueprints.fullHalf(structure);
        Showcase.stage(level, ground.getX() - half[0] - 2, ground.getX() + half[0] + 2, ground.getZ() - half[1] - 3,
            ground.getZ() + half[1] + 3, ground.getY());
        BuildGoal.stamp(level, structure, ground, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(t.village(), structure, ground, Direction.NORTH);
        for (Ledger.Building b : Ledger.buildings(t.village())) if (b.structure().equals(structure) && b.anchor().equals(ground)) return b;
        return null;
    }

    private static int stock(ServerLevel level, UUID village, Item item) {
        return Market.stock(level, village, s -> s.is(item));
    }

    private static VillageFolkEntity cartographer(GameTestHelper helper, ServerLevel level, Town t) {
        VillageFolkEntity c = Cartographers.appointForTests(level, t.v());
        helper.assertTrue(c != null && c.stationTask() == StationTask.CARTOGRAPHER, "a cartographer appointed");
        return c;
    }

    private static MapItemSavedData data(ServerLevel level, int id) {
        return level.getMapData(new MapId(id));
    }

    private static int coloured(ServerLevel level, List<Integer> ids) {
        int n = 0;
        for (int id : ids) n += MapSurveys.coloured(data(level, id));
        return n;
    }

    /** The real colour the game's map gives the ground at a column: its top block's map colour (the base colour). */
    private static int groundColour(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos p = new BlockPos(x, y, z);
        return level.getBlockState(p).getMapColor(level, p).id;
    }

    /** A structure start set into the world's chunks as world generation sets one: its start in its chunk, a reference
     *  to it in every chunk it covers. */
    private static void setIntoTheWorld(ServerLevel level, Structure s, StructureStart start) {
        ChunkPos cp = start.getChunkPos();
        level.getChunk(cp.x, cp.z).setStartForStructure(s, start);
        BoundingBox bb = start.getBoundingBox();
        for (int cx = bb.minX() >> 4; cx <= bb.maxX() >> 4; cx++) {
            for (int cz = bb.minZ() >> 4; cz <= bb.maxZ() >> 4; cz++) {
                LevelChunk c = level.getChunk(cx, cz);
                c.addReferenceForStructure(s, cp.toLong());
                c.setUnsaved(true);
            }
        }
        level.getChunk(cp.x, cp.z).setUnsaved(true);
    }

    /** A real mineshaft, made by the game's own mineshaft generator in this chunk, set into the world. */
    private static StructureStart mineshaft(ServerLevel level, BlockPos at) {
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Structure s = reg.getOrThrow(BuiltinStructures.MINESHAFT);
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        StructureStart start = s.generate(level.registryAccess(), gen, gen.getBiomeSource(), level.getChunkSource().randomState(),
            level.getStructureManager(), level.getSeed(), new ChunkPos(at), 0, level, b -> true);
        if (start.isValid()) setIntoTheWorld(level, s, start);
        return start;
    }

    /** A real ocean monument: the game's monument building piece here, its start set into the world. */
    private static StructureStart monument(ServerLevel level, BlockPos at) {
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Structure s = reg.getOrThrow(BuiltinStructures.OCEAN_MONUMENT);
        OceanMonumentPieces.MonumentBuilding piece = new OceanMonumentPieces.MonumentBuilding(RandomSource.create(7331L), at.getX(), at.getZ(),
            Direction.NORTH);
        StructureStart start = new StructureStart(s, new ChunkPos(at), 0, new PiecesContainer(List.of(piece)));
        setIntoTheWorld(level, s, start);
        return start;
    }

    private static BlockPos centre(StructureStart s) {
        BoundingBox bb = s.getBoundingBox();
        return new BlockPos(bb.getCenter().getX(), bb.getCenter().getY(), bb.getCenter().getZ());
    }

    private static ItemStack held(Player p, java.util.function.Predicate<ItemStack> what) {
        for (ItemStack s : p.getInventory().items) if (what.test(s)) return s;
        return ItemStack.EMPTY;
    }

    private static String key(ItemStack s) {
        Component n = s.get(DataComponents.ITEM_NAME);
        return n != null && n.getContents() instanceof TranslatableContents tc ? tc.getKey() : n == null ? "" : n.getString();
    }

    // ============================================================ ca01: the trade opens, the right folk takes it

    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "ca01_opens")
    public static void ca01_opens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1400000, Villages.Age.STONE, 40, StationTask.FARM, StationTask.FARM, StationTask.WOOD, StationTask.MINE,
            StationTask.FARM);
        UUID id = t.village();
        boolean small = Cartographers.wanted(id);
        // The rule at thirty: a town of twenty-nine with no scouts has no use for one; thirty has.
        boolean at29 = Cartographers.opens(Villages.Age.STONE, 29, false), at30 = Cartographers.opens(Villages.Age.STONE, 30, false);
        boolean wood30 = Cartographers.opens(Villages.Age.WOOD, 30, true);
        // A scout out: wanted at once.
        VillageFolkEntity scout = t.folk().get(4);
        scout.setJob(StationTask.SCOUT);
        boolean scouted = Cartographers.wanted(id);
        List<String> wish = Villages.projectsWanted(id);
        Villages.ageForTests(id, Villages.Age.WOOD);
        boolean wood = Cartographers.wanted(id);
        Villages.ageForTests(id, Villages.Age.STONE);
        Kit.log("ca01 wanted: five folk " + small + ", 29 " + at29 + ", 30 " + at30 + ", Wood Age " + wood30 + "; a scout " + scouted
            + " (Wood Age " + wood + "); the town's list " + wish);
        helper.assertTrue(!small && !at29 && at30 && !wood30 && scouted && !wood, "the Stone Age, with scouts or thirty folk");
        helper.assertTrue(wish.contains(Cartographers.STRUCTURE) && Cartographers.wantsMapRoom(id) && !Cartographers.ready(id),
            "the map room on the town's list, no cartographer till it stands: " + wish);
        // Nobody takes it up on its own: the town chooses.
        boolean neededAlone = Villages.needed(id) == StationTask.CARTOGRAPHER;
        Ledger.Building room = build(level, t, Cartographers.STRUCTURE, -26, 30);
        helper.assertTrue(room != null && Cartographers.ready(id) && !Cartographers.wantsMapRoom(id), "the map room stands: ready");
        List<VillageFolkEntity> cands = Cartographers.candidates(t.v());
        VillageFolkEntity best = cands.isEmpty() ? null : cands.get(0);
        Cartographers.tickNowForTests(level, t.v());
        VillageFolkEntity c = Cartographers.cartographer(id);
        Kit.log("ca01 candidates " + cands.stream().map(f -> f.displayNameCap() + " " + f.stationTask() + " " + Cartographers.score(f)).toList()
            + "; chosen " + (c == null ? "nobody" : c.displayNameCap() + " (was the scout: " + (c == scout) + ")"));
        helper.assertTrue(!neededAlone, "not taken by whoever asks first");
        helper.assertTrue(c != null && c == best && c.stationTask() == StationTask.CARTOGRAPHER,
            "the best of the candidates appointed: " + (c == null ? "nobody" : c.displayNameCap()));
        helper.assertTrue(c.stationPos() != null && c.stationPos().distManhattan(room.anchor()) <= 6, "its post at the map room: " + c.stationPos());
        Cartographers.tickNowForTests(level, t.v());
        int n = 0;
        for (VillageFolkEntity f : t.folk()) if (f.stationTask() == StationTask.CARTOGRAPHER) n++;
        helper.assertTrue(n == 1 && Villages.wants(id, StationTask.CARTOGRAPHER), "one cartographer, however long it is looked at: " + n);
        // [interviews] The place falls vacant (the cartographer back to its scouting) and three of the town want it: it is
        // held open for its interview, not given, and the town's own look waits on it. The panel sits, and its choice
        // keeps the map room.
        c.setJob(StationTask.SCOUT);
        List<VillageFolkEntity> keen = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            VillageFolkEntity f = t.folk().get(i);
            f.setJob(StationTask.NONE);
            Interviews.keenForTests(f);
            keen.add(f);
        }
        CaveDwellerGameTests.fill(t, new ItemStack(Items.OAK_PLANKS, 32), new ItemStack(Items.PAPER, 8), new ItemStack(Items.INK_SAC, 8),
            new ItemStack(Items.BREAD, 16));
        Interviews.hurryForTests(true);
        Interviews.fairDayForTests(true);
        Interviews.liveForTests(true);
        Cartographers.tickNowForTests(level, t.v());
        InterviewBook.Interview iv = Interviews.pendingForTests(id, "cartographer");
        Interviews.liveForTests(false);
        VillageFolkEntity meanwhile = Cartographers.cartographer(id);
        Cartographers.tickNowForTests(level, t.v());
        VillageFolkEntity stillNobody = Cartographers.cartographer(id);
        Kit.log("ca01 vacant: " + (iv == null ? "no interview" : "the interview for the " + iv.title() + ", " + iv.cands().size() + " standing")
            + "; given meanwhile to " + (meanwhile == null ? "nobody" : meanwhile.displayNameCap()) + "; the board " + Interviews.board(level, id));
        helper.assertTrue(iv != null && iv.cands().size() >= 2 && meanwhile == null && stillNobody == null,
            "the place held open for its interview, not given: " + (meanwhile == null ? "nobody yet" : meanwhile.displayNameCap()));
        helper.assertTrue(Interviews.board(level, id).stream().anyMatch(l -> l.contains("cartographer")), "the board shows it");
        // The interview is set for its own day (the next, as the town sets one in the morning): the clock is held at that
        // day's interview hour, so the panel and the candidates come to the table.
        long base = Math.max(level.getDayTime() / 24000L, iv.dueDay()) * 24000L;
        helper.onEachTick(() -> {
            level.setDayTime(base + 3000L);                                  // the interviews' own hour, held there
            level.updateSkyBrightness();
            Interviews.stepForTests(level, t.v());
            if (iv.stage() != InterviewBook.Stage.DONE) {
                if (helper.getTick() > 5500) helper.fail("the interview never finished: " + iv.stage());
                return;
            }
            VillageFolkEntity got = Cartographers.cartographer(id);
            Kit.log("ca01 the interview: " + iv.winnerName() + " chosen (" + iv.reason() + "); the map room is "
                + (got == null ? "nobody's" : got.displayNameCap() + "'s"));
            helper.assertTrue(got != null && got.getUUID().equals(iv.winner()) && got.stationPos() != null
                && got.stationPos().distManhattan(room.anchor()) <= 6, "the panel's choice keeps the map room: " + (got == null ? "nobody" : got.displayNameCap()));
            int now = 0;
            for (VillageFolkEntity f : t.folk()) if (f.stationTask() == StationTask.CARTOGRAPHER) now++;
            helper.assertTrue(now == 1, "still one cartographer: " + now);
            helper.succeed();
        });
    }

    // ============================================================ ca02: the map room

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ca02_maproom")
    public static void ca02_maproom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1402000, Villages.Age.STONE, 40, StationTask.SCOUT, StationTask.FARM, StationTask.FARM);
        UUID id = t.village();
        Ledger.Building room = build(level, t, Cartographers.STRUCTURE, 24, -24);
        int tables = 0, lecterns = 0, chests = 0, shelves = 0;
        for (BlockPos p : BlockPos.betweenClosed(room.anchor().offset(-4, -1, -4), room.anchor().offset(4, 3, 4))) {
            var st = level.getBlockState(p);
            if (st.is(Blocks.CARTOGRAPHY_TABLE)) tables++;
            if (st.is(Blocks.LECTERN)) lecterns++;
            if (st.is(Blocks.CHEST)) chests++;
            if (st.is(Blocks.BOOKSHELF)) shelves++;
        }
        Kit.log("ca02 the map room at " + room.anchor().toShortString() + ": " + tables + " table, " + lecterns + " lectern, " + chests + " chests, "
            + shelves + " bookcases; on the list of buildings " + BuildGoal.STRUCTURES.contains(Cartographers.STRUCTURE));
        helper.assertTrue(tables == 1 && lecterns == 1 && chests == 2 && shelves == 2, "its table, lectern, chests and bookcases");
        helper.assertTrue(BuildGoal.STRUCTURES.contains(Cartographers.STRUCTURE) && BuildGoal.itemForPart(BuildGoal.Part.CARTOGRAPHY)
            .test(new ItemStack(Items.CARTOGRAPHY_TABLE)), "a building the builders know, its table a part they stock");
        VillageFolkEntity c = cartographer(helper, level, t);
        BlockPos table = Cartographers.tableAt(level, id);
        helper.assertTrue(table != null, "the cartographer's table found");
        // The table gone: one made of the stores' two paper and four planks, and set down in its place.
        level.setBlock(table, Blocks.AIR.defaultBlockState(), 3);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 5), new ItemStack(Items.OAK_PLANKS, 6), new ItemStack(Items.BREAD, 16));
        String set = Cartographers.setTableForTests(level, t.v(), c);
        Kit.log("ca02 the table set again: " + set + "; paper " + stock(level, id, Items.PAPER) + ", planks " + stock(level, id, Items.OAK_PLANKS));
        helper.assertTrue(set != null && level.getBlockState(table).is(Blocks.CARTOGRAPHY_TABLE), "the table back where the drawing has it: " + set);
        helper.assertTrue(stock(level, id, Items.PAPER) == 3 && stock(level, id, Items.OAK_PLANKS) == 2, "two paper and four planks out of the stores");
        helper.succeed();
    }

    // ============================================================ ca03: a map fills as the cartographer walks

    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "ca03_fills")
    public static void ca03_fills(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1404000, Villages.Age.STONE, 150, StationTask.SCOUT, StationTask.FARM, StationTask.FARM);
        UUID id = t.village();
        build(level, t, "hall", -30, -40);
        build(level, t, Cartographers.STRUCTURE, 30, -40);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 16), new ItemStack(Items.GLASS_PANE, 8), new ItemStack(Items.ITEM_FRAME, 8),
            new ItemStack(Items.BREAD, 32));
        VillageFolkEntity c = cartographer(helper, level, t);
        MapSurveys.Survey s = Cartographers.beginWallForTests(level, t.v(), c);
        helper.assertTrue(s != null && s.sheets().size() == 4, "the town's wall begun: four sheets");
        List<Integer> ids = new ArrayList<>(s.sheets());
        int blank = coloured(level, ids);
        // The sheets are north-west, north-east, south-west, south-east: stand at the north-east one's middle.
        int hx = t.heart().getX(), hz = t.heart().getZ();
        MapItemSavedData nw = data(level, ids.get(0)), ne = data(level, ids.get(1));
        c.moveTo(hx + 64 + 0.5, Kit.surface(level, hx + 64, hz - 64).getY(), hz - 64 + 0.5, 0.0F, 0.0F);
        for (int k = 0; k < 4; k++) MapSurveys.strokeForTests(level, c);
        int neA = MapSurveys.coloured(ne), nwA = MapSurveys.coloured(nw);
        int westColumn = 0;
        for (int pz = 0; pz < 128; pz++) if (nw.colors[4 + pz * 128] != 0) westColumn++;
        // Walk over to the north-west sheet's middle: its west side fills now.
        c.moveTo(hx - 64 + 0.5, Kit.surface(level, hx - 64, hz - 64).getY(), hz - 64 + 0.5, 0.0F, 0.0F);
        for (int k = 0; k < 4; k++) MapSurveys.strokeForTests(level, c);
        int nwB = MapSurveys.coloured(nw);
        int westColumnB = 0;
        for (int pz = 0; pz < 128; pz++) if (nw.colors[4 + pz * 128] != 0) westColumnB++;
        // Each pixel is the ground's own colour: under the cartographer, the top block's map colour.
        int px = 64, pz = 64;                                                // the middle of the north-west sheet
        int mine = (nw.colors[px + pz * 128] & 0xFF) >> 2, ground = groundColour(level, hx - 64, hz - 64);
        Kit.log("ca03 blank " + blank + "; at the north-east sheet: NE " + neA + ", NW " + nwA + " (its west column " + westColumn
            + "); at the north-west sheet: NW " + nwB + " (west column " + westColumnB + "); the pixel underfoot " + mine + ", the ground "
            + ground + " (" + MapColor.byId(ground) + ")");
        helper.assertTrue(blank == 0, "blank sheets to begin with: " + blank);
        helper.assertTrue(neA > 14000 && nwA > 0 && nwA < 12000 && westColumn == 0,
            "only round where it stood: NE " + neA + ", NW " + nwA + ", NW's west column " + westColumn);
        helper.assertTrue(nwB > nwA + 3000 && westColumnB > 100, "the north-west sheet fills as it walks there: " + nwA + " -> " + nwB);
        helper.assertTrue(mine == ground && mine != 0, "the ground's own colour, not painted: " + mine + " vs " + ground);
        // And the survey walked for real: a fresh one, the cartographer let loose; its sheets fill as it goes.
        MapSurveys.forgetForTests(id);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 16), new ItemStack(Items.GLASS_PANE, 8), new ItemStack(Items.ITEM_FRAME, 8),
            new ItemStack(Items.BREAD, 32));
        c.moveTo(hx + 0.5, Kit.surface(level, hx, hz + 70).getY(), hz + 70 + 0.5, 0.0F, 0.0F);
        MapSurveys.Survey live = Cartographers.beginWallForTests(level, t.v(), c);
        helper.assertTrue(live != null, "a second walk begun");
        List<Integer> liveIds = new ArrayList<>(live.sheets());
        BlockPos from = c.blockPosition();
        int[] best = { 0 };
        helper.onEachTick(() -> {
            if (c.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
        });
        helper.succeedWhen(() -> {
            int now = coloured(level, liveIds);
            best[0] = Math.max(best[0], now);
            double moved = Math.sqrt(c.blockPosition().distSqr(from));
            helper.assertTrue(moved > 20 && now > 20000, "walking and filling: moved " + (int) moved + ", " + now + " pixels, stop "
                + (MapSurveys.surveyForTests(id) == null ? "-" : MapSurveys.surveyForTests(id).stop()));
            Kit.log("ca03 the live walk: moved " + (int) moved + " blocks, " + now + " pixels filled across the four sheets");
        });
    }

    // ============================================================ ca04: the wall in the hall, its places marked

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ca04_wall")
    public static void ca04_wall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1406000, Villages.Age.STONE, 150, StationTask.SCOUT, StationTask.FARM, StationTask.FARM);
        UUID id = t.village();
        Ledger.Building hall = build(level, t, "hall", -30, -40);
        build(level, t, "market", 34, -36);
        build(level, t, Cartographers.STRUCTURE, 30, 34);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 16), new ItemStack(Items.GLASS_PANE, 8), new ItemStack(Items.ITEM_FRAME, 8),
            new ItemStack(Items.WHITE_WOOL, 24), new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.BREAD, 32));
        VillageFolkEntity c = cartographer(helper, level, t);
        int paper0 = stock(level, id, Items.PAPER), panes0 = stock(level, id, Items.GLASS_PANE), wool0 = stock(level, id, Items.WHITE_WOOL);
        MapSurveys.Survey s = Cartographers.beginWallForTests(level, t.v(), c);
        helper.assertTrue(s != null, "the wall begun: " + MapSurveys.surveyForTests(id));
        MapSurveys.runForTests(level, c);
        List<BlockPos> frames = MapRoom.framesForTests(id);
        List<ItemStack> maps = MapRoom.mapsForTests(level, id);
        Set<String> marked = new HashSet<>();
        int locked = 0, filled = 0;
        for (ItemStack m : maps) {
            MapItemSavedData d = MapItem.getSavedData(m, level);
            if (d == null) continue;
            if (d.locked) locked++;
            filled += MapSurveys.coloured(d);
            for (MapBanner b : d.getBanners()) b.name().ifPresent(n -> marked.add(n.getString()));
        }
        int inHall = 0;
        for (BlockPos p : frames) if (Math.abs(p.getX() - hall.anchor().getX()) <= 6 && Math.abs(p.getZ() - hall.anchor().getZ()) <= 10) inHall++;
        BlockPos hallBanner = MapSurveys.bannerForTests(id, "Hall"), marketBanner = MapSurveys.bannerForTests(id, "Market");
        boolean signed = false;
        for (BlockPos p : BlockPos.betweenClosed(hall.anchor().offset(-6, -1, -10), hall.anchor().offset(6, 4, 10))) {
            if (level.getBlockState(p).getBlock() instanceof WallSignBlock && level.getBlockEntity(p) instanceof SignBlockEntity sign
                && sign.getFrontText().getMessage(0, false).getString().equals("Map of")) signed = true;
        }
        Kit.log("ca04 frames " + frames + " (" + inHall + " in the hall); " + locked + " locked; " + filled + " pixels; marked " + marked
            + "; banners at the hall " + hallBanner + ", the market " + marketBanner + "; sign " + signed + "; paper " + paper0 + " -> "
            + stock(level, id, Items.PAPER) + ", panes " + panes0 + " -> " + stock(level, id, Items.GLASS_PANE) + ", wool " + wool0 + " -> "
            + stock(level, id, Items.WHITE_WOOL));
        helper.assertTrue(frames.size() == 4 && inHall == 4 && maps.stream().allMatch(m -> m.is(Items.FILLED_MAP)), "four maps hang in the hall");
        helper.assertTrue(frames.get(0).getY() == frames.get(2).getY() + 1 && frames.get(0).distManhattan(frames.get(1)) == 1,
            "a two-by-two on one wall: " + frames);
        helper.assertTrue(locked == 4 && filled > 4 * 12000, "locked under glass, walked full: " + locked + " locked, " + filled + " pixels");
        helper.assertTrue(marked.contains("Hall") && marked.contains("Market") && marked.contains("Storehouse"),
            "the town's places marked with the game's banner markers: " + marked);
        helper.assertTrue(hallBanner != null && level.getBlockState(hallBanner).getBlock() instanceof BannerBlock
            && marketBanner != null && level.getBlockState(marketBanner).getBlock() instanceof BannerBlock, "the named banners stand");
        helper.assertTrue(signed, "a sign under the wall");
        helper.assertTrue(paper0 - stock(level, id, Items.PAPER) == 4 && panes0 - stock(level, id, Items.GLASS_PANE) == 4
            && wool0 - stock(level, id, Items.WHITE_WOOL) >= 18, "a sheet of paper and a pane each, and six wool a banner, out of the stores");
        helper.assertTrue(MapSurveys.surveyForTests(id) == null && Cartographers.keepsTheHallMap(id), "the walk done; the clerk's map waits");
        helper.succeed();
    }

    // ============================================================ ca05: a mineshaft found, for the cave team

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ca05_mineshaft")
    public static void ca05_mineshaft(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1408000, Villages.Age.IRON, 120, StationTask.CAVE, StationTask.FARM, StationTask.FARM, StationTask.MINE,
            StationTask.SCOUT);                                              // the scout: the one the town can spare for its maps
        UUID id = t.village();
        build(level, t, Cartographers.STRUCTURE, -30, 30);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 16), new ItemStack(Items.COMPASS, 2), new ItemStack(Items.BREAD, 32));
        VillageFolkEntity c = cartographer(helper, level, t);
        VillageFolkEntity dweller = t.folk().get(0);
        // One far out where the town has never been: not found, however real.
        Kit.prepare(level, t.heart().getX() + 640, Z + 40, 24);
        StructureStart far = mineshaft(level, t.heart().offset(640, 0, 40));
        String none = MapFinds.lookForTests(level, t.v(), c, MapFinds.Place.MINESHAFT);
        // One near, in the town's own country.
        StructureStart near = mineshaft(level, t.heart().offset(90, 0, 60));
        BlockPos at = centre(near);
        String found = MapFinds.lookForTests(level, t.v(), c, MapFinds.Place.MINESHAFT);
        List<MapFinds.Found> finds = MapFinds.foundForTests(id);
        ItemStack map = ItemStack.EMPTY;
        for (ItemStack s : dweller.getInventoryItems()) if (s.is(Items.FILLED_MAP)) map = s;
        BlockPos target = map.isEmpty() ? null : MapFinds.target(map);
        // Where the map's cross is, the game's own mineshaft: the world's structure manager finds its start there.
        boolean real = target != null && level.structureManager().getStructureAt(new BlockPos(target.getX(), at.getY(), target.getZ()),
            level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.MINESHAFT)).isValid();
        boolean reported = false;
        for (CaveDwellers.Find x : CaveDwellers.report(id)) if (x.kind() == CaveDwellers.Kind.MINESHAFT && x.at().distSqr(at) < 4) reported = true;
        BlockPos lead = Cartographers.caveLead(id, t.heart());
        Kit.log("ca05 far " + far.isValid() + " at " + centre(far).toShortString() + ": " + none + "; near " + near.isValid() + " at "
            + at.toShortString() + ": " + found + "; the dweller's map " + (map.isEmpty() ? "none" : key(map) + " to " + target) + ", real " + real
            + "; reported " + reported + "; lead " + lead + "; paper " + stock(level, id, Items.PAPER) + ", compasses " + stock(level, id, Items.COMPASS));
        helper.assertTrue(far.isValid() && near.isValid(), "two real mineshafts in the world");
        helper.assertTrue(none == null, "the far one not found: the town has never been out there");
        helper.assertTrue(found != null && finds.size() == 1 && finds.get(0).place() == MapFinds.Place.MINESHAFT, "the near one found: " + found);
        helper.assertTrue(!map.isEmpty() && MapFinds.marks(map).contains(MapDecorationTypes.RED_X) && target != null
            && Math.abs(target.getX() - at.getX()) <= 1 && Math.abs(target.getZ() - at.getZ()) <= 1, "an explorer map to it in the cave team's hands");
        helper.assertTrue(real, "where it points, the game's own mineshaft");
        helper.assertTrue(reported && at.equals(lead), "on the caves' report, and the cave team's next lead: " + lead);
        helper.assertTrue(stock(level, id, Items.PAPER) == 8 && stock(level, id, Items.COMPASS) == 1, "eight paper and a compass out of the stores");
        helper.succeed();
    }

    // ============================================================ ca06: an ocean explorer map to a real monument

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ca06_ocean_map")
    public static void ca06_ocean_map(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1410000, Villages.Age.STONE, 150, StationTask.SCOUT, StationTask.FARM, StationTask.FARM);
        UUID id = t.village();
        build(level, t, Cartographers.STRUCTURE, -30, 30);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 16), new ItemStack(Items.COMPASS, 2), new ItemStack(Items.BREAD, 32));
        VillageFolkEntity c = cartographer(helper, level, t);
        StructureStart mon = monument(level, t.heart().offset(70, 0, -90));
        BlockPos at = centre(mon);
        int dist = (int) Math.sqrt(Math.pow(at.getX() - t.heart().getX(), 2) + Math.pow(at.getZ() - t.heart().getZ(), 2));
        int price = Prices.mapOf(dist, MapFinds.Place.MONUMENT.rarity);
        Player poor = helper.makeMockPlayer(GameType.SURVIVAL);
        poor.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 1));
        String no = FolkTalk.answer(c, poor, TalkTopic.SAY, "I'd like an ocean explorer map");
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        you.setPos(c.getX() + 1, c.getY(), c.getZ());
        you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
        int coins0 = Market.coinsHeld(you);
        String yes = FolkTalk.answer(c, you, TalkTopic.SAY, "I'd like an ocean explorer map");
        ItemStack map = held(you, s -> s.is(Items.FILLED_MAP));
        BlockPos target = map.isEmpty() ? null : MapFinds.target(map);
        MapItemSavedData d = map.isEmpty() ? null : MapItem.getSavedData(map, level);
        Structure monument = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.OCEAN_MONUMENT);
        boolean real = target != null && level.structureManager().getStructureAt(new BlockPos(target.getX(), at.getY(), target.getZ()),
            monument).isValid();
        Kit.log("ca06 the monument at " + at.toShortString() + " (" + dist + " blocks, " + price + " coins); too poor: " + no + " | bought: " + yes
            + " | the map " + (map.isEmpty() ? "none" : key(map) + ", marks " + MapFinds.marks(map) + ", to " + target + ", scale "
            + (d == null ? "?" : d.scale) + ", biome preview " + MapSurveys.coloured(d) + " pixels") + "; real " + real + "; coins " + coins0 + " -> "
            + Market.coinsHeld(you));
        helper.assertTrue(held(poor, s -> s.is(Items.FILLED_MAP)).isEmpty() && no.contains(Integer.toString(price)), "no coin, no map: " + no);
        helper.assertTrue(!map.isEmpty() && key(map).equals("filled_map.monument") && MapFinds.marks(map).contains(MapDecorationTypes.OCEAN_MONUMENT),
            "the game's own ocean explorer map: " + key(map));
        helper.assertTrue(target != null && Math.abs(target.getX() - at.getX()) <= 1 && Math.abs(target.getZ() - at.getZ()) <= 1 && real,
            "it points at the monument: " + target + " vs " + at);
        // At the explorer's scale, the game's biome preview drawn on it: that shows only the coasts (the test world is all
        // plains, so it is logged, not counted on), with the cross where the monument is and the holder's own mark.
        helper.assertTrue(d != null && d.scale == 2 && !d.locked && d.dimension == level.dimension(), "an explorer map at the explorer's scale");
        helper.assertTrue(coins0 - Market.coinsHeld(you) == price, "priced by its distance and rarity: " + price);
        helper.assertTrue(stock(level, id, Items.PAPER) == 8 && stock(level, id, Items.COMPASS) == 1, "eight paper and a compass out of the stores");
        helper.succeed();
    }

    // ============================================================ ca07: paper and compasses from the stores

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ca07_makings")
    public static void ca07_makings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1412000, Villages.Age.STONE, 40, StationTask.SCOUT, StationTask.FARM, StationTask.FARM);
        UUID id = t.village();
        build(level, t, Cartographers.STRUCTURE, -26, 26);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.SUGAR_CANE, 12), new ItemStack(Items.IRON_INGOT, 6), new ItemStack(Items.REDSTONE, 2),
            new ItemStack(Items.BREAD, 16));
        VillageFolkEntity c = cartographer(helper, level, t);
        int[] paperBook0 = Economy.todayForTests(id, "paper"), compassBook0 = Economy.todayForTests(id, "compass");
        String first = Cartographers.makingsForTests(level, t.v(), c);
        String second = Cartographers.makingsForTests(level, t.v(), c);
        int[] paperBook = Economy.todayForTests(id, "paper"), compassBook = Economy.todayForTests(id, "compass");
        Kit.log("ca07 " + first + "; " + second + "; cane " + stock(level, id, Items.SUGAR_CANE) + ", paper " + stock(level, id, Items.PAPER) + ", iron "
            + stock(level, id, Items.IRON_INGOT) + ", redstone " + stock(level, id, Items.REDSTONE) + ", compasses " + stock(level, id, Items.COMPASS)
            + "; the day's books: paper made " + (paperBook[0] - paperBook0[0]) + ", compasses made " + (compassBook[0] - compassBook0[0]));
        helper.assertTrue(stock(level, id, Items.SUGAR_CANE) == 0 && stock(level, id, Items.PAPER) == 12, "twelve paper of the twelve cane");
        helper.assertTrue(stock(level, id, Items.COMPASS) == 1 && stock(level, id, Items.IRON_INGOT) == 2 && stock(level, id, Items.REDSTONE) == 1,
            "a compass of four iron and a redstone");
        helper.assertTrue(paperBook[0] - paperBook0[0] == 12 && compassBook[0] - compassBook0[0] == 1, "counted in the day's production");
        helper.assertTrue(Cartographers.counted(id, "paper") == 12 && Cartographers.counted(id, "compasses") == 1, "and in the map room's own books");
        helper.succeed();
    }

    // ============================================================ ca08: the week's redraw, and the archive

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ca08_redraw")
    public static void ca08_redraw(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1414000, Villages.Age.STONE, 150, StationTask.SCOUT, StationTask.FARM, StationTask.FARM);
        UUID id = t.village();
        build(level, t, "hall", -30, -40);
        build(level, t, "museum", 34, -38);
        build(level, t, Cartographers.STRUCTURE, 30, 34);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 32), new ItemStack(Items.GLASS_PANE, 16), new ItemStack(Items.ITEM_FRAME, 16),
            new ItemStack(Items.WHITE_WOOL, 32), new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.BREAD, 32));
        VillageFolkEntity c = cartographer(helper, level, t);
        long day = level.getDayTime() / 24000L;
        helper.assertTrue(Cartographers.beginWallForTests(level, t.v(), c) != null, "the first wall begun");
        MapSurveys.runForTests(level, c);
        Set<Object> first = new HashSet<>();
        for (ItemStack m : MapRoom.mapsForTests(level, id)) first.add(m.get(DataComponents.MAP_ID));
        String notYet = String.valueOf(MapSurveys.wallDueForTests(level, id, day));
        // A week on.
        MapSurveys.wallDrawnForTests(id, day - 7);
        String due = MapSurveys.wallDueForTests(level, id, day);
        helper.assertTrue(Cartographers.beginWallForTests(level, t.v(), c) != null, "the week's wall begun");
        MapSurveys.runForTests(level, c);
        Set<Object> second = new HashSet<>();
        for (ItemStack m : MapRoom.mapsForTests(level, id)) second.add(m.get(DataComponents.MAP_ID));
        List<ItemStack> kept = new ArrayList<>(MapArchive.inMuseumForTests(level, id));
        kept.addAll(MapArchive.inChestForTests(level, id));
        Set<Object> keptIds = new HashSet<>();
        boolean named = !kept.isEmpty();
        for (ItemStack k : kept) {
            keptIds.add(k.get(DataComponents.MAP_ID));
            if (!k.getHoverName().getString().contains("in its Stone Age")) named = false;
        }
        List<MapArchive.Kept> books = MapArchive.kept(id);
        Kit.log("ca08 a day on: " + notYet + "; a week on: " + due + "; the first wall " + first + ", the second " + second + "; kept "
            + kept.size() + " (" + (kept.isEmpty() ? "-" : kept.get(0).getHoverName().getString()) + "): " + books);
        helper.assertTrue(notYet.equals("null") && due != null && due.contains("week"), "due a week on, not before: " + due);
        helper.assertTrue(first.size() == 4 && second.size() == 4 && first.stream().noneMatch(second::contains), "the wall replaced: four fresh sheets");
        helper.assertTrue(keptIds.containsAll(first) && named, "the old wall in the archive, named for the town in its age");
        helper.assertTrue(books.size() == 1 && books.get(0).sheets() == 4 && books.get(0).age().contains("Stone Age"), "on the archive's books: " + books);
        // And the next week's: kept as well, beside it.
        MapSurveys.wallDrawnForTests(id, day - 7);
        helper.assertTrue(Cartographers.beginWallForTests(level, t.v(), c) != null, "the third wall begun");
        MapSurveys.runForTests(level, c);
        List<ItemStack> keptAll = new ArrayList<>(MapArchive.inMuseumForTests(level, id));
        keptAll.addAll(MapArchive.inChestForTests(level, id));
        Set<Object> allIds = new HashSet<>();
        for (ItemStack k : keptAll) allIds.add(k.get(DataComponents.MAP_ID));
        Kit.log("ca08 the third week: " + MapArchive.kept(id) + "; " + keptAll.size() + " kept maps");
        helper.assertTrue(allIds.containsAll(first) && allIds.containsAll(second) && MapArchive.kept(id).size() == 2,
            "both old walls kept: " + MapArchive.kept(id));
        helper.succeed();
    }

    // ============================================================ ca09: a commission

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ca09_commission")
    public static void ca09_commission(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1416000, Villages.Age.STONE, 40, StationTask.SCOUT, StationTask.FARM, StationTask.FARM);
        UUID id = t.village();
        Kit.hold(level, t.heart().getX() + 192, Z, 136);
        Kit.prepare(level, t.heart().getX() + 192, Z, 136);
        build(level, t, Cartographers.STRUCTURE, -26, 26);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 8), new ItemStack(Items.GLASS_PANE, 4), new ItemStack(Items.BREAD, 32));
        VillageFolkEntity c = cartographer(helper, level, t);
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 30));
        int coins0 = Market.coinsHeld(you);
        String asked = FolkTalk.answer(c, you, TalkTopic.SAY, "Map me the land to the east, please");
        int paid = coins0 - Market.coinsHeld(you);
        MapSurveys.Survey s = Cartographers.beginCommissionForTests(level, t.v(), c);
        helper.assertTrue(s != null && s.kind() == MapSurveys.Kind.COMMISSION, "the commission walked: " + asked);
        int sheet = s.sheets().get(0);
        MapSurveys.runForTests(level, c);
        String wait = FolkTalk.answer(c, you, TalkTopic.SAY, "Is my map ready?");
        ItemStack map = held(you, x -> x.is(Items.FILLED_MAP));
        MapItemSavedData d = map.isEmpty() ? null : MapItem.getSavedData(map, level);
        Kit.log("ca09 asked: " + asked + " (" + paid + " coins) | handed: " + wait + " | the map " + (map.isEmpty() ? "none" : map.getHoverName().getString()
            + ", centred " + (d == null ? "?" : d.centerX + "," + d.centerZ) + ", " + MapSurveys.coloured(d) + " pixels, locked " + (d != null && d.locked)));
        helper.assertTrue(paid == Cartographers.commissionPrice() && paid > 0, "paid for when asked: " + paid);
        helper.assertTrue(!map.isEmpty() && map.getHoverName().getString().contains("east"), "handed over, named for it: " + wait);
        helper.assertTrue(d != null && d.centerX > t.heart().getX() + 150 && Math.abs(d.centerZ - t.heart().getZ()) < 20 && d.locked
            && MapSurveys.coloured(d) > 8000, "the land to the east, walked and filled, locked under glass");
        helper.assertTrue(map.get(DataComponents.MAP_ID) != null && map.get(DataComponents.MAP_ID).id() != sheet, "the locked copy, as the table makes one");
        helper.succeed();
    }

    // ============================================================ ca10: the region's map on the road; a quest's map

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ca10_region")
    public static void ca10_region(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1418000, Villages.Age.STONE, 160, StationTask.SCOUT, StationTask.FARM, StationTask.FARM, StationTask.HAUL);
        UUID id = t.village();
        build(level, t, "hall", -30, -40);
        build(level, t, Cartographers.STRUCTURE, 30, 34);
        CaveDwellerGameTests.fill(t, new ItemStack(Items.PAPER, 24), new ItemStack(Items.ITEM_FRAME, 4), new ItemStack(Items.COMPASS, 2),
            new ItemStack(Items.BREAD, 32));
        VillageFolkEntity c = cartographer(helper, level, t);
        MapSurveys.Survey s = Cartographers.beginRegionForTests(level, t.v(), c);
        helper.assertTrue(s != null && s.kind() == MapSurveys.Kind.REGION && s.scale() >= 2, "the region's sheet begun");
        MapSurveys.finishNowForTests(level, c);
        int region = MapSurveys.regionMap(id);
        MapItemSavedData d = data(level, region);
        ItemFrame framed = null;
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(t.heart()).inflate(80), f -> f.getItem().is(Items.FILLED_MAP)
            && f.getItem().get(DataComponents.MAP_ID) != null && f.getItem().get(DataComponents.MAP_ID).id() == region)) framed = f;
        int before = MapSurveys.coloured(d);
        // A caravan's carrier sets out with a copy: the same map. It walks, and the hall's copy fills in as it goes.
        VillageFolkEntity carrier = t.folk().get(3);
        boolean given = MapSurveys.roadCopy(level, t.v(), carrier);
        carrier.moveTo(t.heart().getX() + 20.5, Kit.surface(level, t.heart().getX() + 20, Z).getY(), Z + 0.5, 0.0F, 0.0F);
        for (int k = 0; k < 16; k++) MapSurveys.walkWith(level, carrier);
        int after = MapSurveys.coloured(d);
        boolean same = false;
        for (ItemStack x : carrier.getInventoryItems()) if (x.is(Items.FILLED_MAP) && x.get(DataComponents.MAP_ID).id() == region) same = true;
        Kit.log("ca10 the region's map " + region + " (scale " + (d == null ? "?" : d.scale) + ", locked " + (d != null && d.locked) + ") framed "
            + (framed == null ? "nowhere" : framed.getPos().toShortString()) + "; a copy for the road " + given + " (same map " + same + "); filled "
            + before + " -> " + after + " as the carrier walked");
        helper.assertTrue(d != null && !d.locked && framed != null, "the region's map framed, open");
        helper.assertTrue(given && same, "the carrier given a copy: the same map");
        // At 1:4 the game fills a circle of thirty-two pixels' radius round the holder (its rim every other pixel):
        // some three thousand pixels from one spot.
        helper.assertTrue(after > before + 2400, "the hall's copy fills in as the traveller walks: " + before + " -> " + after);
        // A quest that sends a player far: a map to its place.
        QuestBook.Quest q = new QuestBook.Quest();
        q.village = id;
        q.title = "The old well";
        QuestBook.Step go = new QuestBook.Step(QuestBook.StepType.GO, "well", "Go to the old well out east");
        go.at = t.heart().offset(150, 0, 30);
        go.dim = level.dimension().location().toString();
        q.steps.add(go);
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        int paper0 = stock(level, id, Items.PAPER);
        Cartographers.questMap(level, q, you);
        ItemStack qm = held(you, x -> x.is(Items.FILLED_MAP));
        BlockPos to = qm.isEmpty() ? null : MapFinds.target(qm);
        Kit.log("ca10 the quest's map: " + (qm.isEmpty() ? "none" : qm.getHoverName().getString() + " to " + to) + "; paper " + paper0 + " -> "
            + stock(level, id, Items.PAPER));
        helper.assertTrue(!qm.isEmpty() && to != null && to.getX() == go.at.getX() && to.getZ() == go.at.getZ()
            && MapFinds.marks(qm).contains(MapDecorationTypes.TARGET_X), "a map to the quest's place, marked");
        helper.assertTrue(paper0 - stock(level, id, Items.PAPER) == 8, "eight paper and a compass for it");
        helper.succeed();
    }
}
