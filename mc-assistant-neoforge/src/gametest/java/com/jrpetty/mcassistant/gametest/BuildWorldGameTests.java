package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Ages;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.DoorWays;
import com.jrpetty.mcassistant.entity.Interiors;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * [build-world] What the player sees, inside and out, at every turn of the lot. Every drawing (and every one a storey is
 * put on) is built four times over, its back to each of the four ways in turn, by a builder's own goal (BuildGoal, its
 * plan, its ground work and its hands, each block out of its pack: buildNowForTests), on a rough lot: a tree on it,
 * grass and flowers and snow, sand and gravel, the ground a block or two up here and down there. Then it is furnished
 * as a town furnishes it (Interiors), the way in at each door made (DoorWays), and made over for the Diamond Age
 * (Ages: slate and copper roofs, stone walls, mossy footings, lamp posts by the doors). At each step the blocks are
 * walked and the world asked whether it is right: stairs, slabs, doors, beds, logs, ovens, chests and ladders set the
 * way the drawing says at that turn; every stair's corner and every pane's and fence's joins as the game would make
 * them; doors hung from their walls; nothing standing that cannot; nothing of the lot left inside; nothing afloat; every
 * room reachable from the door and lit; the furnishing in nobody's way; the ways in walkable.
 *
 * <p>A fault seen at only some of the four turns is the game's (the turning is wrong), as is any fault of the setting of
 * a block against its drawing: those fail the test. A fault the same at all four turns that is about where the drawing
 * puts things (a stove facing a wall, a dark corner, a lamp with nothing to hang from) is the drawing's: it is logged,
 * "bw01 drawing ...", for the drawings' own test (BlueprintSoundnessTest) to take up.
 *
 * <p>On ground of its own: x 2360000 to 2360400, z 70000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BuildWorldGameTests {

    private static final String EMPTY = "empty";
    private static final int X = 2360000, Z = 70000;
    private static final Direction[] TURNS = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
    /** The lots, one for each turn, this far apart along x. */
    private static final int LOT_STEP = 80;

    /** The rules: a game's fault (true) or a drawing's (false) when it is the same at every turn. */
    enum Rule {
        STAIR(true), SLAB(true), LOG(true), DOOR(true), HINGE(true), BED(true), TURNED(true), SHAPE(true),
        LEFT_INSIDE(true), EARTH_LAID(true), MISSING(true), FURNISHING(true), MADE_OVER(true),
        FACES_WALL(false), CANNOT_STAND(false), AFLOAT(false), DARK(false), RAIN(false), UNREACHABLE(false),
        BED_PLACE(false), WAY_IN(false);

        final boolean game;

        Rule(boolean game) { this.game = game; }
    }

    /** One fault: where in the drawing (right +, up, back +), and what is wrong. */
    record Seen(String drawing, Rule rule, int dx, int h, int dz, String what) {}

    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "bw01_every_drawing_every_turn")
    public static void bw01_every_drawing_every_turn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Interiors.resetForTests();
        Ages.resetForTests();
        int cx = X + LOT_STEP * 2, cz = Z;
        Kit.hold(level, cx, cz, LOT_STEP * 2 + 40);
        Kit.prepare(level, cx, cz, LOT_STEP * 2 + 40);
        int y0 = Math.max(80, Kit.surface(level, cx, cz).getY() + 2);
        List<String> drawings = drawings(level);
        VillageFolkEntity builder = McAssistantMod.VILLAGE_FOLK.get().create(level);   // never put in the world: its hands only
        builder.moveTo(X - 300, y0 + 40, Z, 0, 0);
        Map<String, Map<Direction, String>> faultsAt = new LinkedHashMap<>();
        Map<String, Seen> seen = new LinkedHashMap<>();
        Map<String, Integer> tally = new java.util.TreeMap<>();
        List<String> notBuilt = new ArrayList<>();
        int[] next = { 0 };
        Kit.log("bw01 " + drawings.size() + " drawings: " + drawings);
        helper.onEachTick(() -> {
            if (next[0] > drawings.size()) return;
            if (next[0] == drawings.size()) {
                next[0]++;
                report(helper, "bw01", faultsAt, seen, tally, notBuilt, true);
                return;
            }
            String name = drawings.get(next[0]++);
            if (!BuildGoal.STRUCTURES.contains(name)) return;   // a storey put on, a block's extra floors: set down (bw02)
            for (int t = 0; t < TURNS.length; t++) {
                Direction facing = TURNS[t];
                BlockPos anchor = new BlockPos(X + LOT_STEP * t, y0, Z);
                try {
                    one(level, builder, name, anchor, facing, faultsAt, seen, tally, notBuilt);
                } catch (RuntimeException e) {
                    notBuilt.add(name + "@" + facing + ": " + e);
                    Kit.LOG.error("[VT] bw01 " + name + " " + facing, e);
                }
                clearLot(level, anchor, name);
            }
        });
    }

    /**
     * The other way a building goes up: set down at once out of a palette (the showcase, a storey put on a house or a
     * tavern, a bed made up, an admin's build), a layer at a time and each layer in two batches, as a storey goes up a
     * few blocks a visit (Grow.raise). Every block set the way its drawing says at every turn, and fitted to the
     * neighbours laid in the other batch.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "bw02_stamped_every_turn")
    public static void bw02_stamped_every_turn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Ages.resetForTests();
        int x0 = X + 2000;
        int cx = x0 + LOT_STEP * 2, cz = Z;
        Kit.hold(level, cx, cz, LOT_STEP * 2 + 40);
        Kit.prepare(level, cx, cz, LOT_STEP * 2 + 40);
        int y0 = Math.max(80, Kit.surface(level, cx, cz).getY() + 2);
        List<String> drawings = drawings(level);
        Map<String, Map<Direction, String>> faultsAt = new LinkedHashMap<>();
        Map<String, Seen> seen = new LinkedHashMap<>();
        Map<String, Integer> tally = new java.util.TreeMap<>();
        List<String> notBuilt = new ArrayList<>();
        int[] next = { 0 };
        helper.onEachTick(() -> {
            if (next[0] > drawings.size()) return;
            if (next[0] == drawings.size()) {
                next[0]++;
                report(helper, "bw02", faultsAt, seen, tally, notBuilt, false);
                return;
            }
            String name = drawings.get(next[0]++);
            for (int t = 0; t < TURNS.length; t++) {
                Direction facing = TURNS[t];
                BlockPos anchor = new BlockPos(x0 + LOT_STEP * t, y0, Z);
                try {
                    flatLot(level, anchor, name);
                    List<BuildGoal.Placement> plan = BuildGoal.plan(name, anchor, facing, 13);
                    var paint = com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK);
                    TreeSet<Integer> layers = new TreeSet<>();
                    for (BuildGoal.Placement p : plan) layers.add(p.pos().getY());
                    for (int y : layers) {
                        for (int half = 0; half < 2; half++) {
                            final int h = half;
                            BuildGoal.stampOnly(level, name, anchor, facing, 13, paint,
                                p -> p.pos().getY() == y && Math.floorMod(p.pos().getX() + p.pos().getZ(), 2) == h);
                        }
                    }
                    Look look = new Look(level, name, anchor, facing, plan);
                    List<Seen> out = new ArrayList<>();
                    look.states(out, true);
                    Set<String> was = new HashSet<>();
                    for (Seen s : out) was.add(s.rule() + "@" + s.dx() + "," + s.h() + "," + s.dz());
                    Ages.now(level, null, new Ledger.Building(name, anchor, facing), Villages.Age.DIAMOND);
                    List<Seen> after = new ArrayList<>();
                    look.states(after, true);
                    for (Seen s : after) {
                        if (was.contains(s.rule() + "@" + s.dx() + "," + s.h() + "," + s.dz())) continue;
                        out.add(new Seen(s.drawing(), Rule.MADE_OVER, s.dx(), s.h(), s.dz(), "made over (" + s.rule() + "): " + s.what()));
                    }
                    note(out, facing, faultsAt, seen);
                    tally.merge("buildings", 1, Integer::sum);
                } catch (RuntimeException e) {
                    notBuilt.add(name + "@" + facing + ": " + e);
                    Kit.LOG.error("[VT] bw02 " + name + " " + facing, e);
                }
                clearLot(level, anchor, name);
            }
        });
    }

    /**
     * The rooms a town furnishes, furnished by the town itself (Interiors.work, out of its stores, its hands there at
     * once), in the Iron Age and the Diamond, at every turn: every piece its plan has goes in, and every one of those
     * buildings has a plan (the schoolroom's desks were taken for a roof coming down, and it had none).
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bw03_the_town_furnishes_every_room")
    public static void bw03_the_town_furnishes_every_room(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Interiors.resetForTests();
        int x0 = X + 4000;
        Kit.hold(level, x0 + 120, Z, 200);
        Kit.prepare(level, x0 + 120, Z, 200);
        int y0 = Math.max(80, Kit.surface(level, x0, Z).getY() + 2);
        BlockPos heart = new BlockPos(x0 - 60, y0, Z);
        flatLot(level, heart, "house");
        Villages.Village v = Villages.found(level, heart);
        com.jrpetty.mcassistant.VillageSpawner.supplyChest(level, heart.east(2));
        List<String> rooms = new ArrayList<>(List.of("house", "manor", "townhall", "hall", "tavern", "cafe", "shop", "library",
            "chapel", "guesthouse", "barracks", "school"));
        rooms.removeIf(n -> !Interiors.furnishedForTests(n) || !Blueprints.has(n));
        List<String> faults = new ArrayList<>();
        StringBuilder seen = new StringBuilder();
        for (Villages.Age age : new Villages.Age[]{ Villages.Age.IRON, Villages.Age.DIAMOND }) {
            Villages.ageForTests(v.id(), age);
            int tier = age == Villages.Age.IRON ? 2 : 3;
            for (int t = 0; t < TURNS.length; t++) {
                Ledger.forget(v.id());
                Interiors.resetForTests();
                List<Ledger.Building> built = new ArrayList<>();
                for (int i = 0; i < rooms.size(); i++) {
                    String name = rooms.get(i);
                    BlockPos anchor = new BlockPos(x0 + 40 * i, y0, Z + 60 * t);
                    flatLot(level, anchor, name);
                    BuildGoal.stamp(level, name, anchor, TURNS[t], 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
                    Ledger.built(v.id(), name, anchor, TURNS[t]);
                    built.add(new Ledger.Building(name, anchor, TURNS[t]));
                }
                ItemStack[] stock = { new ItemStack(Items.RED_CARPET, 64), new ItemStack(Items.RED_CARPET, 64), new ItemStack(Items.RED_CARPET, 64),
                    new ItemStack(Items.YELLOW_CARPET, 64), new ItemStack(Items.BARREL, 48), new ItemStack(Items.BOOKSHELF, 64),
                    new ItemStack(Items.LANTERN, 48), new ItemStack(Items.CANDLE, 48), new ItemStack(Items.FLOWER_POT, 48),
                    new ItemStack(Items.POPPY, 24), new ItemStack(Items.DANDELION, 24) };
                for (ItemStack s : stock) com.jrpetty.mcassistant.entity.Market.intoStoresForTests(level, v.id(), s);
                for (int i = 0; i < 12; i++) if (Interiors.work(level, v, 4000) == 0) break;
                for (Ledger.Building b : built) {
                    List<String> audit = Interiors.auditForTests(level, v, b, tier);
                    long in = audit.stream().filter(a -> a.contains(" in ")).count();
                    if (t == 0) seen.append(b.structure()).append(" ").append(age).append(" ").append(in).append("/").append(audit.size()).append("; ");
                    if (audit.isEmpty()) faults.add(b.structure() + " " + age + " " + TURNS[t] + ": nothing planned");
                    for (String a : audit) if (!a.contains(" in ")) faults.add(b.structure() + " " + age + " " + TURNS[t] + ": " + a);
                }
                for (Ledger.Building b : built) clearLot(level, b.anchor(), b.structure());
            }
        }
        Kit.log("bw03 furnished " + seen + " faults " + faults.size() + " " + faults.subList(0, Math.min(40, faults.size())));
        helper.assertTrue(faults.isEmpty(), "every room furnished as planned: " + faults.subList(0, Math.min(10, faults.size())));
        helper.succeed();
    }

    private static void note(List<Seen> out, Direction facing, Map<String, Map<Direction, String>> faultsAt, Map<String, Seen> seen) {
        for (Seen s : out) {
            String key = s.drawing() + "|" + s.rule() + "|" + s.dx() + "," + s.h() + "," + s.dz();
            faultsAt.computeIfAbsent(key, k -> new EnumMap<>(Direction.class)).putIfAbsent(facing, s.what());
            seen.putIfAbsent(key, s);
        }
    }

    /** A flat lot of grass, clear above. */
    private static void flatLot(ServerLevel level, BlockPos a, String name) {
        int r = reachOf(name), top = topOf(name) + 8;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int y = a.getY() - 8; y <= a.getY() + top; y++) {
                    Block b = y < a.getY() - 4 ? Blocks.STONE : y < a.getY() - 1 ? Blocks.DIRT : y == a.getY() - 1 ? Blocks.GRASS_BLOCK : Blocks.AIR;
                    level.setBlock(new BlockPos(a.getX() + dx, y, a.getZ() + dz), b.defaultBlockState(), 2 | 16);
                }
            }
        }
    }

    // ------------------------------------------------------------------ one building at one turn

    private static void one(ServerLevel level, VillageFolkEntity builder, String name, BlockPos anchor, Direction facing,
                            Map<String, Map<Direction, String>> faultsAt, Map<String, Seen> seen, Map<String, Integer> tally,
                            List<String> notBuilt) {
        roughLot(level, anchor, name, facing);
        String said = BuildGoal.buildNowForTests(builder, name, anchor, facing, BuildWorldGameTests::stock);
        if (!said.startsWith("Done")) notBuilt.add(name + "@" + facing + ": " + said);
        List<BuildGoal.Placement> plan = BuildGoal.plan(name, anchor, facing, 13);
        Look look = new Look(level, name, anchor, facing, plan);
        List<Seen> out = new ArrayList<>();
        look.states(out, false);
        look.inside(out);
        look.standing(out);
        look.afloat(out);
        Set<BlockPos> reachBefore = look.reach();
        // Furnished as a town furnishes it, for the Diamond Age (every piece the plan has).
        String structure = structureOf(name);
        if (Interiors.furnishedForTests(structure)) {
            List<BlockPos> put = Interiors.furnishForTests(level, new Ledger.Building(structure, anchor, facing), name, 3);
            tally.merge("furnished pieces", put.size(), Integer::sum);
            look.furnishing(out, put, reachBefore);
        }
        // The ways in made, as the town's rounds make them.
        for (DoorWays.Door d : look.streetDoors()) {
            if (DoorWays.mendForTests(level, d) < 0 || !DoorWays.walkable(level, d)) {
                out.add(look.seen(Rule.WAY_IN, d.pos(), "no way in at the door, out " + d.out() + ": " + look.ahead(d)));
            }
        }
        Set<BlockPos> reach = look.reach();
        look.rooms(out, reach);
        // Made over for the Diamond Age: the turning kept through the change of every block.
        List<Seen> before = new ArrayList<>(), after = new ArrayList<>();
        look.states(before, true);
        look.standing(before);
        Set<String> was = new HashSet<>();
        for (Seen s : before) was.add(s.rule() + "@" + s.dx() + "," + s.h() + "," + s.dz());
        Ages.now(level, null, new Ledger.Building(name, anchor, facing), Villages.Age.DIAMOND);
        look.states(after, true);
        look.standing(after);
        for (Seen s : after) {
            if (was.contains(s.rule() + "@" + s.dx() + "," + s.h() + "," + s.dz())) continue;     // so before it, too
            out.add(new Seen(s.drawing(), Rule.MADE_OVER, s.dx(), s.h(), s.dz(), "made over (" + s.rule() + "): " + s.what()));
        }
        note(out, facing, faultsAt, seen);
        tally.merge("buildings", 1, Integer::sum);
    }

    /** The building a drawing is of, for the furnishing (a raised one is still its building; a house2 a house). */
    private static String structureOf(String drawing) {
        String s = drawing.endsWith(Blueprints.TALL) ? drawing.substring(0, drawing.length() - Blueprints.TALL.length()) : drawing;
        return s.equals("house2") ? "house" : s;
    }

    private static void report(GameTestHelper helper, String tag, Map<String, Map<Direction, String>> faultsAt,
                               Map<String, Seen> seen, Map<String, Integer> tally, List<String> notBuilt, boolean drawingsToo) {
        Map<String, Integer> gameKinds = new java.util.TreeMap<>(), drawingKinds = new java.util.TreeMap<>();
        List<String> game = new ArrayList<>(), drawn = new ArrayList<>();
        for (Map.Entry<String, Map<Direction, String>> e : faultsAt.entrySet()) {
            Seen s = seen.get(e.getKey());
            boolean everyTurn = e.getValue().size() == TURNS.length;
            boolean gameFault = s.rule().game || !everyTurn;
            String line = s.drawing() + " " + s.rule() + " at dx " + s.dx() + " h " + s.h() + " dz " + s.dz()
                + (everyTurn ? " (every turn)" : " (turns " + e.getValue().keySet() + ")") + ": " + e.getValue().values().iterator().next();
            if (gameFault) {
                game.add(line);
                gameKinds.merge(s.rule().name(), 1, Integer::sum);
            } else {
                drawn.add(line);
                drawingKinds.merge(s.rule().name(), 1, Integer::sum);
            }
        }
        for (String l : game) Kit.log(tag + " game " + l);
        if (drawingsToo) for (String l : drawn) Kit.log(tag + " drawing " + l);
        for (String l : notBuilt) Kit.log(tag + " unbuilt " + l);
        Kit.log(tag + " " + tally + "; game faults " + gameKinds + "; drawing faults " + drawingKinds + "; unbuilt " + notBuilt.size());
        helper.assertTrue(notBuilt.isEmpty(), "every drawing built at every turn: " + notBuilt.subList(0, Math.min(8, notBuilt.size())));
        helper.assertTrue(game.isEmpty(), game.size() + " faults of the game's in what was built, e.g. "
            + game.subList(0, Math.min(8, game.size())));
        helper.succeed();
    }

    /** Every drawing of a building there is, and every one with a storey put on. */
    static List<String> drawings(ServerLevel level) {
        Set<String> names = new TreeSet<>();
        for (ResourceLocation rl : level.getServer().getResourceManager().listResources("blueprints",
                r -> r.getNamespace().equals("mc_assistant") && r.getPath().endsWith(".txt")).keySet()) {
            String p = rl.getPath().substring("blueprints/".length(), rl.getPath().length() - 4);
            if (p.indexOf('/') >= 0) continue;                 // the engineers' machines and the themes' own: drawn otherwise
            if (Blueprints.has(p)) names.add(p);
        }
        for (String t : Ages.TALL) if (Blueprints.has(t + Blueprints.TALL)) names.add(t + Blueprints.TALL);
        return new ArrayList<>(names);
    }

    // ------------------------------------------------------------------ the builder's pack

    /** What a builder of oak and spruce, cobble and stone brick carries for a block of each kind. */
    private static Item kit(Blueprints.Style s) {
        return switch (s) {
            case MASONRY -> Items.STONE_BRICKS;
            case BRICK -> Items.BRICKS;
            case FLOOR -> Items.SPRUCE_PLANKS;
            case WALL -> Items.OAK_PLANKS;
            case ROOF_BLOCK -> Items.DARK_OAK_PLANKS;
            case POST, BEAM_ACROSS, BEAM_ALONG -> Items.SPRUCE_LOG;
            case ROOF_STAIR, ROOF_STAIR_TOP -> Items.DARK_OAK_STAIRS;
            case ROOF_SLAB, ROOF_SLAB_TOP -> Items.DARK_OAK_SLAB;
            case STONE_SLAB -> Items.STONE_BRICK_SLAB;
            case STONE_STAIR -> Items.STONE_BRICK_STAIRS;
            case SOIL -> Items.DIRT;
            case PANE -> Items.GLASS_PANE;
            case GLASS -> Items.GLASS;
            default -> Items.COBBLESTONE;
        };
    }

    private static Item kit(BuildGoal.Placement p) {
        return switch (p.part()) {
            case BLOCK -> kit(p.style());
            case FURNACE -> Items.FURNACE;
            case CHEST -> Items.CHEST;
            case CRAFTING_TABLE -> Items.CRAFTING_TABLE;
            case TORCH -> Items.TORCH;
            case LADDER -> Items.LADDER;
            case FENCE -> Items.SPRUCE_FENCE;
            case GATE -> Items.SPRUCE_FENCE_GATE;
            case WINDOW -> p.style() == Blueprints.Style.GLASS ? Items.GLASS : Items.GLASS_PANE;
            case BED -> Items.RED_BED;
            case OBSIDIAN -> Items.OBSIDIAN;
            case DOOR -> Items.SPRUCE_DOOR;
            case LANTERN -> Items.LANTERN;
            case WATER -> Items.WATER_BUCKET;
            case HAY -> Items.HAY_BLOCK;
            case BARREL -> Items.BARREL;
            case FLOWER -> Items.POPPY;
            case CARPET -> Items.RED_CARPET;
            case ANVIL -> Items.ANVIL;
            case CAULDRON -> Items.CAULDRON;
            case BELL -> Items.BELL;
            case BOOKSHELF -> Items.BOOKSHELF;
            case LECTERN -> Items.LECTERN;
            case ENCHANTING -> Items.ENCHANTING_TABLE;
            case BREWING -> Items.BREWING_STAND;
            case SMOKER -> Items.SMOKER;
            case LOOM -> Items.LOOM;
            case GRINDSTONE -> Items.GRINDSTONE;
            case CAMPFIRE -> Items.CAMPFIRE;
            case NOTE_BLOCK -> Items.NOTE_BLOCK;
            case STOREHOUSE -> McAssistantMod.STOREHOUSE_ITEM.get();
            case CARTOGRAPHY -> Items.CARTOGRAPHY_TABLE;
            case CLEAR -> Items.AIR;
        };
    }

    /** The pack filled for the cell the builder comes to (or, before it starts, with what it asks to have). */
    private static void stock(AssistantEntity f, BuildGoal.Placement p) {
        NonNullList<ItemStack> inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) inv.set(i, ItemStack.EMPTY);
        if (p == null) {
            Item[] want = { Items.COBBLESTONE, Items.FURNACE, Items.CHEST, Items.CRAFTING_TABLE, Items.LADDER, Items.LADDER,
                Items.LADDER, Items.LADDER, Items.SPRUCE_FENCE, Items.SPRUCE_FENCE_GATE, Items.OBSIDIAN,
                McAssistantMod.STOREHOUSE_ITEM.get() };
            for (int i = 0; i < want.length; i++) inv.set(i, new ItemStack(want[i], want[i].getDefaultMaxStackSize()));
            return;
        }
        Item it = kit(p);
        if (it != Items.AIR) inv.set(0, new ItemStack(it, it.getDefaultMaxStackSize()));
    }

    // ------------------------------------------------------------------ the lot

    /** How far out the lot runs from its anchor, and how high the building stands. */
    private static int reachOf(String name) {
        int[] f = Blueprints.fullHalf(name);
        return Math.max(f[0], f[1]) + 7;
    }

    private static int topOf(String name) {
        int top = 0;
        for (Blueprints.Cell c : Blueprints.cells(name)) top = Math.max(top, c.h());
        return top;
    }

    /**
     * A rough lot, as the world makes one: grass over earth over stone, a block or two up here and down there, sand and
     * gravel in places, tall grass, flowers and snow strewn over it, a tree inside where the walls will go and another at
     * the edge.
     */
    private static void roughLot(ServerLevel level, BlockPos a, String name, Direction facing) {
        int r = reachOf(name), top = topOf(name) + 8;
        Direction right = facing.getClockWise();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                // Laid out in the building's own terms (across, and toward its back), so the lot is the same lot under it
                // at every turn: what differs between the turns is the turning, not the ground.
                int x = dx * right.getStepX() + dz * right.getStepZ(), z = dx * facing.getStepX() + dz * facing.getStepZ();
                BlockPos column = a.offset(dx, 0, dz);
                for (int y = a.getY() - 8; y <= a.getY() + top; y++) {
                    BlockState st = y < a.getY() - 4 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(column.atY(y), st, 2 | 16);
                }
                int k = Math.floorMod(x * 73428767 ^ z * 912931 ^ (x * z), 23);
                int ground = a.getY() - 1 + switch (k) { case 0, 1 -> 1; case 2 -> 2; case 3, 4 -> -1; case 5 -> -2; default -> 0; };
                for (int y = a.getY() - 4; y <= ground; y++) {
                    Block b = y == ground ? (k == 6 ? Blocks.SAND : k == 7 ? Blocks.GRAVEL : Blocks.GRASS_BLOCK) : Blocks.DIRT;
                    level.setBlock(column.atY(y), b.defaultBlockState(), 2 | 16);
                }
                BlockPos over = column.atY(ground + 1);
                boolean soil = k != 6 && k != 7;
                switch (soil ? Math.floorMod(x * 31 + z * 17, 7) : 2) {
                    case 0 -> level.setBlock(over, Blocks.SHORT_GRASS.defaultBlockState(), 2 | 16);
                    case 1 -> {
                        level.setBlock(over, Blocks.TALL_GRASS.defaultBlockState(), 2 | 16);
                        level.setBlock(over.above(), Blocks.TALL_GRASS.defaultBlockState()
                            .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 2 | 16);
                    }
                    case 2 -> level.setBlock(over, Blocks.SNOW.defaultBlockState(), 2 | 16);
                    case 3 -> level.setBlock(over, Blocks.DANDELION.defaultBlockState(), 2 | 16);
                    default -> { }
                }
            }
        }
        tree(level, a.relative(right, 1).relative(facing, 2));
        tree(level, a.relative(right, -r + 3).relative(facing, r - 3));
    }

    /** A tree as the world grows one: its trunk on the ground there, its crown of leaves that are nobody's. */
    private static void tree(ServerLevel level, BlockPos at) {
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        BlockPos base = new BlockPos(at.getX(), ground, at.getZ());
        for (int i = 0; i < 6; i++) level.setBlock(base.above(i), Blocks.OAK_LOG.defaultBlockState(), 2 | 16);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 6; dy++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    BlockPos p = base.offset(dx, dy, dz);
                    if (!level.getBlockState(p).isAir()) continue;
                    level.setBlock(p, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1), 2 | 16);
                }
            }
        }
    }

    /** The lot cleared for the next building: every block and everything lying on it. */
    private static void clearLot(ServerLevel level, BlockPos a, String name) {
        int r = reachOf(name) + 3, top = topOf(name) + 10;
        AABB box = new AABB(a.getX() - r, a.getY() - 10, a.getZ() - r, a.getX() + r + 1, a.getY() + top + 1, a.getZ() + r + 1);
        for (Entity e : level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player))) e.discard();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int y = a.getY() - 8; y <= a.getY() + top; y++) {
                    BlockPos p = new BlockPos(a.getX() + dx, y, a.getZ() + dz);
                    if (!level.getBlockState(p).isAir()) {
                        if (level.getBlockEntity(p) instanceof net.minecraft.world.Container c) c.clearContent();
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                }
            }
        }
        for (Entity e : level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player))) e.discard();
    }

    // ------------------------------------------------------------------ looking at what was built

    /** A building as it stands, looked at against its drawing. */
    static final class Look {
        final ServerLevel level;
        final String name;
        final BlockPos a;
        final Direction facing, right;
        final Map<BlockPos, BuildGoal.Placement> at = new HashMap<>();
        final int top, gx, gz;
        /** The highest cell of the drawing over each column (by x,z). */
        final Map<Long, Integer> roof = new HashMap<>();
        final int r;

        Look(ServerLevel level, String name, BlockPos a, Direction facing, List<BuildGoal.Placement> plan) {
            this.level = level;
            this.name = name;
            this.a = a;
            this.facing = facing;
            this.right = facing.getClockWise();
            int t = 0;
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.CLEAR) continue;
                at.put(p.pos(), p);
                t = Math.max(t, p.pos().getY() - a.getY());
                roof.merge(col(p.pos()), p.pos().getY(), Math::max);
            }
            this.top = t;
            int[] g = Blueprints.groundHalf(name);
            boolean turned = facing.getAxis() == Direction.Axis.X;
            this.gx = turned ? g[1] : g[0];
            this.gz = turned ? g[0] : g[1];
            this.r = reachOf(name);
        }

        static long col(BlockPos p) {
            return BlockPos.asLong(p.getX(), 0, p.getZ());
        }

        Seen seen(Rule rule, BlockPos q, String what) {
            int ox = q.getX() - a.getX(), oz = q.getZ() - a.getZ();
            int dx = ox * right.getStepX() + oz * right.getStepZ();
            int dz = ox * facing.getStepX() + oz * facing.getStepZ();
            return new Seen(name, rule, dx, q.getY() - a.getY(), dz, what);
        }

        boolean inGround(BlockPos q) {
            return Math.abs(q.getX() - a.getX()) <= gx && Math.abs(q.getZ() - a.getZ()) <= gz;
        }

        Iterable<BlockPos> box(int out, int under, int over) {
            return BlockPos.betweenClosed(a.getX() - r - out, a.getY() - under, a.getZ() - r - out,
                a.getX() + r + out, a.getY() + top + over, a.getZ() + r + out);
        }

        static String s(BlockState st) {
            return st.toString().replace("Block{minecraft:", "").replace("}", "");
        }

        /** Each block set the way its drawing says, at this turn. */
        void states(List<Seen> out, boolean madeOver) {
            for (BuildGoal.Placement p : at.values()) {
                BlockPos q = p.pos();
                BlockState st = level.getBlockState(q);
                Direction way = Blueprints.world(p.way(), facing);
                switch (p.part()) {
                    case BLOCK -> {
                        if (st.getBlock() instanceof StairBlock) {
                            Direction want = way.getAxis().isHorizontal() ? way : facing;
                            Half half = p.style() == Blueprints.Style.ROOF_STAIR_TOP ? Half.TOP : Half.BOTTOM;
                            if (st.getValue(StairBlock.FACING) != want || st.getValue(StairBlock.HALF) != half) {
                                out.add(seen(Rule.STAIR, q, s(st) + " for " + p.style() + " " + p.way() + ", wanted " + want + " " + half));
                            }
                        } else if (st.getBlock() instanceof SlabBlock) {
                            SlabType want = p.style() == Blueprints.Style.ROOF_SLAB_TOP ? SlabType.TOP : SlabType.BOTTOM;
                            if (st.getValue(SlabBlock.TYPE) != want) out.add(seen(Rule.SLAB, q, s(st) + " for " + p.style()));
                        }
                        if (st.hasProperty(BlockStateProperties.AXIS) && st.is(BlockTags.LOGS)) {
                            Direction.Axis want = switch (p.style()) {
                                case BEAM_ACROSS -> right.getAxis();
                                case BEAM_ALONG -> facing.getAxis();
                                default -> Direction.Axis.Y;
                            };
                            if (st.getValue(BlockStateProperties.AXIS) != want) out.add(seen(Rule.LOG, q, s(st) + " for " + p.style()));
                        }
                        if (p.style() != Blueprints.Style.SOIL && (BuildGoal.bareEarth(st) || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL))) {
                            out.add(seen(Rule.EARTH_LAID, q, s(st) + " laid for " + p.style()));
                        }
                        if (!madeOver && st.isAir()) out.add(seen(Rule.MISSING, q, "nothing for " + p.style()));
                    }
                    case DOOR -> door(out, p, st, way);
                    case BED -> bed(out, p, st, way);
                    case FURNACE, CHEST, LADDER -> {
                        Direction want = p.way() == Blueprints.Way.UP || p.way() == Blueprints.Way.FRONT ? facing.getOpposite() : way;
                        if (!st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                            if (!madeOver) out.add(seen(Rule.MISSING, q, s(st) + " for " + p.part()));
                        } else if (st.getValue(BlockStateProperties.HORIZONTAL_FACING) != want) {
                            out.add(seen(Rule.TURNED, q, s(st) + " for " + p.part() + " " + p.way() + ", wanted " + want));
                        }
                    }
                    case LECTERN, LOOM, SMOKER, GRINDSTONE -> {
                        Direction want = way.getAxis().isHorizontal() ? way : facing.getOpposite();
                        if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && st.getValue(BlockStateProperties.HORIZONTAL_FACING) != want) {
                            out.add(seen(Rule.TURNED, q, s(st) + " for " + p.part() + ", wanted " + want));
                        }
                    }
                    case ANVIL -> {
                        Direction want = way.getAxis().isHorizontal() ? way : facing;
                        if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && st.getValue(BlockStateProperties.HORIZONTAL_FACING) != want) {
                            out.add(seen(Rule.TURNED, q, s(st) + " for " + p.part() + ", wanted " + want));
                        }
                    }
                    case WINDOW, FENCE, GATE, TORCH, LANTERN, CRAFTING_TABLE, OBSIDIAN, HAY, BARREL, BOOKSHELF, ENCHANTING, BREWING,
                         CAULDRON, NOTE_BLOCK, CARTOGRAPHY, CAMPFIRE, BELL, FLOWER, CARPET -> {
                        if (!madeOver && st.isAir()) out.add(seen(Rule.MISSING, q, "nothing for " + p.part()));
                    }
                    default -> { }
                }
            }
            // Every block whose shape is its neighbours' business, as the game would have it.
            for (BlockPos q : box(1, 1, 2)) {
                BlockState st = level.getBlockState(q);
                if (!BuildGoal.fitsNeighbours(st)) continue;
                BlockState fit = Block.updateFromNeighbourShapes(st, level, q);
                if (fit != st) out.add(seen(Rule.SHAPE, q, s(st) + " should be " + s(fit)));
            }
        }

        private void door(List<Seen> out, BuildGoal.Placement p, BlockState st, Direction way) {
            BlockPos q = p.pos();
            Direction want = way.getAxis().isHorizontal() ? way : facing;
            if (!(st.getBlock() instanceof DoorBlock)) {
                out.add(seen(Rule.MISSING, q, s(st) + " for a door"));
                return;
            }
            BlockState up = level.getBlockState(q.above());
            if (st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || !(up.getBlock() instanceof DoorBlock)
                    || up.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER || up.getValue(DoorBlock.FACING) != st.getValue(DoorBlock.FACING)
                    || up.getValue(DoorBlock.HINGE) != st.getValue(DoorBlock.HINGE)) {
                out.add(seen(Rule.DOOR, q, "halves " + s(st) + " / " + s(up)));
            }
            Direction f = st.getValue(DoorBlock.FACING);
            if (f != want) out.add(seen(Rule.DOOR, q, s(st) + ", wanted facing " + want));
            Direction hingeSide = st.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT ? f.getCounterClockWise() : f.getClockWise();
            BlockState byHinge = level.getBlockState(q.relative(hingeSide)), other = level.getBlockState(q.relative(hingeSide.getOpposite()));
            if (byHinge.getBlock() instanceof DoorBlock && !(other.getBlock() instanceof DoorBlock)) {
                out.add(seen(Rule.HINGE, q, "hung from the side of the door beside it: " + s(st)));
            } else if (!full(q.relative(hingeSide)) && full(q.relative(hingeSide.getOpposite())) && !(other.getBlock() instanceof DoorBlock)) {
                out.add(seen(Rule.HINGE, q, "hung from its open side, the wall on the other: " + s(st)));
            }
        }

        private void bed(List<Seen> out, BuildGoal.Placement p, BlockState st, Direction way) {
            BlockPos q = p.pos();
            Direction lie = p.way() == Blueprints.Way.UP ? facing : way;
            BlockState head = level.getBlockState(q.relative(lie));
            if (!(st.getBlock() instanceof BedBlock) || !(head.getBlock() instanceof BedBlock)) {
                out.add(seen(Rule.MISSING, q, "bed " + s(st) + " / " + s(head)));
                return;
            }
            if (st.getValue(BedBlock.PART) != BedPart.FOOT || head.getValue(BedBlock.PART) != BedPart.HEAD
                    || st.getValue(BedBlock.FACING) != lie || head.getValue(BedBlock.FACING) != lie) {
                out.add(seen(Rule.BED, q, s(st) + " / " + s(head) + ", wanted to lie " + lie));
            }
            BlockPos h = q.relative(lie);
            if (!full(h.relative(lie))) out.add(seen(Rule.BED_PLACE, q, "its head is not against a wall"));
        }

        boolean full(BlockPos q) {
            return level.getBlockState(q).isCollisionShapeFullBlock(level, q);
        }

        /** Nothing of the lot left inside: no earth, sand, gravel, snow, grass, leaves, wood or water. */
        void inside(List<Seen> out) {
            for (int dx = -gx; dx <= gx; dx++) {
                for (int dz = -gz; dz <= gz; dz++) {
                    for (int dy = 0; dy <= top + 1; dy++) {
                        BlockPos q = a.offset(dx, dy, dz);
                        if (at.containsKey(q)) continue;
                        BlockState st = level.getBlockState(q);
                        if (st.isAir()) continue;
                        boolean wild = BuildGoal.isWildPlant(st) || st.is(Blocks.SNOW) || st.is(Blocks.SNOW_BLOCK)
                            || st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || st.is(BlockTags.DIRT) || st.is(BlockTags.SAND)
                            || st.is(Blocks.GRAVEL) || st.is(Blocks.STONE) || st.is(Blocks.TALL_GRASS) || !st.getFluidState().isEmpty();
                        if (wild) out.add(seen(Rule.LEFT_INSIDE, q, s(st)));
                    }
                }
            }
        }

        /** Nothing standing that cannot (a torch on nothing, a ladder off its wall), and every oven, chest and desk
         *  with its front to the floor, not a wall. */
        void standing(List<Seen> out) {
            for (BlockPos q : box(1, 1, 2)) {
                BlockState st = level.getBlockState(q);
                if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                if (!st.canSurvive(level, q)) {
                    out.add(seen(Rule.CANNOT_STAND, q, s(st)));
                    continue;
                }
                Block b = st.getBlock();
                boolean fronted = b instanceof net.minecraft.world.level.block.AbstractFurnaceBlock || b instanceof ChestBlock
                    || b instanceof net.minecraft.world.level.block.LecternBlock || b instanceof net.minecraft.world.level.block.LoomBlock
                    || b instanceof net.minecraft.world.level.block.DispenserBlock
                    || (b instanceof net.minecraft.world.level.block.BarrelBlock && st.getValue(BlockStateProperties.FACING).getAxis().isHorizontal());
                if (!fronted) continue;
                Direction f = st.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? st.getValue(BlockStateProperties.HORIZONTAL_FACING)
                    : st.getValue(BlockStateProperties.FACING);
                if (full(q.relative(f))) out.add(seen(Rule.FACES_WALL, q, s(st) + " faces " + s(level.getBlockState(q.relative(f)))));
                else if (b instanceof ChestBlock && ChestBlock.isChestBlockedAt(level, q)) out.add(seen(Rule.FACES_WALL, q, "the chest's lid cannot lift"));
            }
        }

        /** Nothing afloat: every block of it joined, face to face, to the ground. */
        void afloat(List<Seen> out) {
            Set<BlockPos> solid = new HashSet<>();
            ArrayDeque<BlockPos> todo = new ArrayDeque<>();
            for (BlockPos q : box(1, 1, 2)) {
                BlockState st = level.getBlockState(q);
                if (st.isAir() || !st.getFluidState().isEmpty() && st.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) continue;
                if (st.is(BlockTags.LEAVES) && !st.getValue(LeavesBlock.PERSISTENT)) continue;     // a felled tree's crown: it rots
                BlockPos p = q.immutable();
                solid.add(p);
                if (p.getY() < a.getY()) todo.add(p);
            }
            Set<BlockPos> held = new HashSet<>(todo);
            while (!todo.isEmpty()) {
                BlockPos p = todo.poll();
                boolean bulky = bulky(p);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int m = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                            if (m == 0 || m == 3) continue;
                            BlockPos n = p.offset(dx, dy, dz);
                            if (!solid.contains(n) || held.contains(n)) continue;
                            // Edge to edge only between solid pieces (a stepped roof's rings, stair on stair); a post
                            // or a lamp holds only what sits square on it.
                            if (m == 2 && !(bulky && bulky(n))) continue;
                            held.add(n);
                            todo.add(n);
                        }
                    }
                }
            }
            for (BlockPos p : solid) if (!held.contains(p)) out.add(seen(Rule.AFLOAT, p, s(level.getBlockState(p))));
        }

        boolean bulky(BlockPos q) {
            BlockState st = level.getBlockState(q);
            return st.isCollisionShapeFullBlock(level, q) || st.getBlock() instanceof StairBlock || st.getBlock() instanceof SlabBlock;
        }

        /** The doors that open onto the street: on the ground floor, the far side out of the building. */
        List<DoorWays.Door> streetDoors() {
            List<DoorWays.Door> out = new ArrayList<>();
            for (DoorWays.Door d : DoorWays.doorsOf(level, new Ledger.Building(name, a, facing))) {
                if (d.pos().getY() != a.getY()) continue;
                BlockPos step = d.pos().relative(d.out());
                if (inGround(step) && roofed(step)) continue;              // a door between rooms
                out.add(d);
            }
            return out;
        }

        /** What stands in front of a door, a column at a time (for the log). */
        String ahead(DoorWays.Door d) {
            StringBuilder b = new StringBuilder();
            for (int k = 1; k <= 3; k++) {
                BlockPos c = d.pos().relative(d.out(), k);
                b.append(k).append(":");
                for (int dy = -2; dy <= 2; dy++) b.append(s(level.getBlockState(c.above(dy))).replaceAll("\\[.*", "")).append(dy < 2 ? "/" : " ");
            }
            return b.toString();
        }

        boolean roofed(BlockPos q) {
            Integer t = roof.get(col(q));
            return t != null && t > q.getY() + 1;
        }

        boolean passable(BlockPos q) {
            BlockState st = level.getBlockState(q);
            return st.getCollisionShape(level, q).isEmpty() || st.is(BlockTags.WOOL_CARPETS) || st.getBlock() instanceof DoorBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock || st.is(Blocks.LADDER);
        }

        boolean standable(BlockPos q) {
            if (!passable(q) || !passable(q.above())) return false;
            if (level.getBlockState(q).is(Blocks.LADDER)) return true;
            BlockPos d = q.below();
            BlockState under = level.getBlockState(d);
            return !under.getCollisionShape(level, d).isEmpty() && !(under.getBlock() instanceof net.minecraft.world.level.block.FenceBlock)
                && !(under.getBlock() instanceof net.minecraft.world.level.block.WallBlock) || under.is(Blocks.LADDER);
        }

        /** Every cell a body can stand in, from the ground in front of its street doors (or, with none, round about). */
        Set<BlockPos> reach() {
            Set<BlockPos> seen = new HashSet<>();
            ArrayDeque<BlockPos> todo = new ArrayDeque<>();
            for (DoorWays.Door d : streetDoors()) {
                BlockPos s = d.pos().relative(d.out());
                for (int dy = 1; dy >= -2; dy--) if (standable(s.above(dy)) && seen.add(s.above(dy).immutable())) { todo.add(s.above(dy).immutable()); break; }
                if (standable(d.pos()) && seen.add(d.pos())) todo.add(d.pos());
            }
            int lim = r + 1;
            while (!todo.isEmpty()) {
                BlockPos p = todo.poll();
                List<BlockPos> next = new ArrayList<>();
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos n = p.relative(d);
                    next.add(n);
                    if (passable(p.above(2))) next.add(n.above());
                    next.add(n.below());
                    next.add(n.below(2));
                }
                if (level.getBlockState(p).is(Blocks.LADDER)) { next.add(p.above()); next.add(p.below()); }
                if (level.getBlockState(p.below()).is(Blocks.LADDER)) next.add(p.below());
                for (BlockPos n : next) {
                    if (Math.abs(n.getX() - a.getX()) > lim || Math.abs(n.getZ() - a.getZ()) > lim) continue;
                    if (n.getY() < a.getY() - 3 || n.getY() > a.getY() + top + 1) continue;
                    if (!standable(n) || !seen.add(n.immutable())) continue;
                    todo.add(n.immutable());
                }
            }
            return seen;
        }

        /** The floor of its rooms: the cells under its roof, inside its walls, a body could stand in. */
        List<BlockPos> floor() {
            List<BlockPos> out = new ArrayList<>();
            for (int dx = -gx; dx <= gx; dx++) {
                for (int dz = -gz; dz <= gz; dz++) {
                    for (int dy = 0; dy <= top; dy++) {
                        BlockPos q = a.offset(dx, dy, dz);
                        if (at.containsKey(q) && at.get(q).part() != BuildGoal.Part.CARPET && at.get(q).part() != BuildGoal.Part.LADDER) continue;
                        if (!roofed(q) || !standable(q)) continue;
                        if (level.getBlockState(q).is(Blocks.LADDER)) continue;
                        out.add(q);
                    }
                }
            }
            return out;
        }

        /** Every room reachable from its door, lit, and under a roof that keeps the rain out. */
        void rooms(List<Seen> out, Set<BlockPos> reach) {
            List<BlockPos> floor = floor();
            if (floor.isEmpty()) return;
            Map<BlockPos, Integer> light = light();
            for (BlockPos q : floor) {
                if (!reach.isEmpty() && !reach.contains(q)) out.add(seen(Rule.UNREACHABLE, q, "no way to it from the door"));
                if (light.getOrDefault(q, 0) == 0) out.add(seen(Rule.DARK, q, "no light at all on the floor here"));
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, q.getX(), q.getZ()) <= q.getY()) {
                    out.add(seen(Rule.RAIN, q, "open to the sky"));
                }
            }
        }

        /** The block light over the building, worked out as the game works it out (from every lamp, through what lets light by). */
        Map<BlockPos, Integer> light() {
            Map<BlockPos, Integer> lit = new HashMap<>();
            ArrayDeque<BlockPos> todo = new ArrayDeque<>();
            int lim = r + 2;
            for (BlockPos q : box(2, 1, 2)) {
                int e = level.getBlockState(q).getLightEmission(level, q);
                if (e > 0) {
                    lit.put(q.immutable(), e);
                    todo.add(q.immutable());
                }
            }
            while (!todo.isEmpty()) {
                BlockPos p = todo.poll();
                int l = lit.get(p);
                for (Direction d : Direction.values()) {
                    BlockPos n = p.relative(d);
                    if (Math.abs(n.getX() - a.getX()) > lim || Math.abs(n.getZ() - a.getZ()) > lim) continue;
                    if (n.getY() < a.getY() - 2 || n.getY() > a.getY() + top + 3) continue;
                    BlockState st = level.getBlockState(n);
                    int block = st.getLightBlock(level, n);
                    if (block >= 15) continue;
                    int to = l - Math.max(1, block);
                    if (to > lit.getOrDefault(n, 0)) {
                        lit.put(n, to);
                        todo.add(n);
                    }
                }
            }
            return lit;
        }

        /** The furnishing in nobody's way: no door, bed, oven, chest or way through shut by it, nothing of it afloat. */
        void furnishing(List<Seen> out, List<BlockPos> put, Set<BlockPos> reachBefore) {
            Set<BlockPos> mine = new HashSet<>(put);
            for (BlockPos q : put) {
                BlockState st = level.getBlockState(q);
                if (!st.canSurvive(level, q)) out.add(seen(Rule.FURNISHING, q, s(st) + " cannot stand there"));
                if (st.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock
                        && st.getValue(BlockStateProperties.FACING).getAxis().isHorizontal()) {
                    out.add(seen(Rule.FURNISHING, q, "a barrel on its side: " + s(st)));
                }
                if (st.is(BlockTags.WOOL_CARPETS)) continue;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos n = q.relative(d);
                    BlockState ns = level.getBlockState(n);
                    if (ns.getBlock() instanceof DoorBlock) out.add(seen(Rule.FURNISHING, q, s(st) + " in a doorway's way"));
                    if (ns.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock && ns.getValue(BlockStateProperties.HORIZONTAL_FACING) == d.getOpposite()) {
                        out.add(seen(Rule.FURNISHING, q, s(st) + " at an oven's mouth"));
                    }
                    if (ns.getBlock() instanceof ChestBlock && ns.getValue(ChestBlock.FACING) == d.getOpposite()) {
                        out.add(seen(Rule.FURNISHING, q, s(st) + " in front of a chest"));
                    }
                    if (ns.getBlock() instanceof LadderBlock && ns.getValue(LadderBlock.FACING) == d.getOpposite()) {
                        out.add(seen(Rule.FURNISHING, q, s(st) + " at the foot of a ladder"));
                    }
                }
            }
            // A bed still to be got into from the floor beside it.
            for (BuildGoal.Placement p : at.values()) {
                if (p.part() != BuildGoal.Part.BED) continue;
                Direction lie = p.way() == Blueprints.Way.UP ? facing : Blueprints.world(p.way(), facing);
                boolean way = false, wayBefore = false;
                for (BlockPos c : new BlockPos[]{ p.pos(), p.pos().relative(lie) }) {
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos n = c.relative(d);
                        if (n.equals(p.pos()) || n.equals(p.pos().relative(lie))) continue;
                        if (standable(n)) way = true;
                        if (mine.contains(n) || standable(n)) wayBefore = true;
                    }
                }
                if (!way && wayBefore) out.add(seen(Rule.FURNISHING, p.pos(), "the bed shut in by the furnishing"));
                if (!wayBefore) out.add(seen(Rule.BED_PLACE, p.pos(), "no floor beside the bed to get into it from"));
            }
            Set<BlockPos> reach = reach();
            for (BlockPos q : reachBefore) {
                if (reach.contains(q) || mine.contains(q) || mine.contains(q.below())) continue;
                if (inGround(q) && roofed(q)) out.add(seen(Rule.FURNISHING, q, "cut off by the furnishing"));
            }
        }
    }
}
