package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Building from blueprints, placed block-by-block (bottom-up) from what the
 * assistant actually carries — nothing is conjured. Structural blocks come
 * from any building material in its pack (planks, logs, cobble, stone,
 * dirt); functional parts (furnaces, chests, crafting tables, ladders,
 * torches) are real items it must have crafted or been given first.
 *
 * Blueprints (each on a 5x5 or 3x3 footprint, door facing the assistant):
 *   wall, platform, shelter               — structure only
 *   smeltery  — 3 furnaces along the back, 2 chests, crafting table, torch
 *   storage   — 4 chests lining the walls, torch
 *   workshop  — crafting table + furnace + chest, torch
 *   watchtower — 3x3 tower with a ladder shaft up to a walled platform
 */
public class BuildGoal extends Goal {

    public static final Set<String> STRUCTURES = Set.of(
        "wall", "platform", "shelter", "smeltery", "storage", "workshop", "watchtower",
        "house", "room", "pen", "fortify", "lighthouse", "column", "well", "hall",
        "market", "chapel", "gateway", "granary", "barracks", "monument", "guesthouse",
        // the crafts' buildings and the amenities: missing here, a village's builder was told
        // "I can build: ..." and the café, the shop and the smithy never went up
        "cafe", "shop", "smithy", "brewery", "library", "tavern", "graveyard", "house2",
        // the Village Storehouse laid into a storehouse shed built before there were units
        "storehouse",
        // what the later ages add: a fountain on the square, a manor house, a bell tower
        "fountain", "manor", "belltower",
        // the leader's hall, the best and biggest in the town, and the courtyard before the board
        "townhall", "court",
        // the museum, where the town's rare finds go on show and its chronicle is kept as books (Museum)
        "museum");

    /** Half the width of a structure's footprint: the meeting hall, the market, the
     *  barracks, the chapel's length and the gateway's step are seven across, the rest
     *  five or less. The ground work and the stocking read it. */
    public static int halfOf(String structure) {
        if (Blueprints.has(structure)) {
            int[] g = Blueprints.groundHalf(structure);
            return Math.max(g[0], g[1]);
        }
        return switch (structure) {
            case "hall", "market", "barracks", "chapel", "gateway" -> 3;
            default -> 2;
        };
    }

    /** What goes in a blueprint cell. */
    public enum Part { BLOCK, FURNACE, CHEST, CRAFTING_TABLE, TORCH, LADDER, FENCE, GATE, WINDOW, BED, OBSIDIAN,
        /** Not a part: something natural in the building's way that comes down first (leaves). */
        CLEAR,
        /** The finishing: a door, a lantern, water in a well, hay, barrels, flowers, a rug, an anvil,
         *  a cauldron, a bell. Each is put in if the village has it, and left out if not. */
        DOOR, LANTERN, WATER, HAY, BARREL, FLOWER, CARPET, ANVIL, CAULDRON, BELL,
        /** The furniture of the crafts' buildings: put in if the village has it (or can make it). */
        BOOKSHELF, LECTERN, ENCHANTING, BREWING, SMOKER, LOOM, GRINDSTONE,
        /** A tavern's hearth fire and its note blocks. */
        CAMPFIRE, NOTE_BLOCK,
        /** A storehouse unit: twenty-seven laid in a cube join into the Village Storehouse. */
        STOREHOUSE }

    /** One block of a building: where, what part, what it is for (Blueprints.Style), and which way it faces. */
    public record Placement(BlockPos pos, Part part, Blueprints.Style style, Blueprints.Way way) {
        Placement(BlockPos pos, Part part) { this(pos, part, Blueprints.Style.GENERIC, Blueprints.Way.UP); }
        Placement at(BlockPos where) { return new Placement(where, part, style, way); }
    }

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private final List<Placement> plan = new ArrayList<>();
    private Direction facing = Direction.NORTH;
    private int cursor;
    private int placed;
    /** What we set out to build, kept so the finish can report it. */
    @Nullable private String building;
    /** Toward the next block, in hundredths of a tick (a hundred a tick; buildPaceHundredths a block). */
    private int workTicks;
    private int stuckTicks;
    /** Ticks spent waiting for the builder's own feet to move off a cell. */
    private int standTicks;
    /** Cells put off to the end of the plan because the builder stood in them, and how often. */
    private final java.util.Map<Long, Integer> putOff = new java.util.HashMap<>();
    /** The nearest the builder has got to the cell it is walking to. */
    private double nearest = Double.MAX_VALUE;
    /** Cells given up on since the last block went down. */
    private int skipped;
    /** Cells given up on the way through (out of reach, or stood in once too often): each gets one
     *  more look when the rest is down. A frame a block short, a floor with a hole, is no building. */
    private final java.util.List<Placement> missed = new java.util.ArrayList<>();
    private boolean lastLook;
    private int myGen;
    private int perimeterRadius = 5; // fortify ring radius (overridable for a big compound)

    public BuildGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /**
     * Is this something to build a wall out of?
     *
     * <p>This was a list of six items, which meant a crew standing on a pile
     * of deepslate, bricks, sandstone or wool it had just made could not put a
     * single one of them in a wall — it would go and cut more oak instead. The
     * question is now asked of the block itself: does it stack, stay put and
     * fill its own cube, and does it have no better job to be doing? Every
     * brick, tile, plank and stone the game has answers yes, including ones
     * added by other mods, and sand answers no because a sand wall arrives on
     * the floor.
     */
    public static boolean isBuildingBlock(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) return false;
        return com.jrpetty.mcassistant.BlockLore.structural(s);
    }

    /**
     * What a blueprint is made of, counted from the blueprint itself — so the
     * people who stock a build do it from the same drawing the builder works
     * from, and cannot drift out of step with it. (The hand-kept tallies had
     * no ladders for the watchtower and lighthouse, no fences for the pen, and
     * said ninety blocks for a wall that wants three hundred.)
     */
    public static Map<Part, Integer> partCounts(String structure, int perimeterRadius) {
        List<Placement> cells = new ArrayList<>();
        layout(structure, new BlockPos(0, 64, 0), Direction.NORTH, true,
            Math.max(4, Math.min(16, perimeterRadius)), cells);
        Map<Part, Integer> counts = new EnumMap<>(Part.class);
        for (Placement p : cells) counts.merge(p.part(), 1, Integer::sum);
        return counts;
    }

    /** How many blocks of each style a building wants (its drawing's count), for the stocking. */
    public static Map<Blueprints.Style, Integer> styleCounts(String structure) {
        return Blueprints.has(structure) ? Blueprints.styleCounts(structure) : Map.of();
    }

    /** Every block of a building, laid out at an anchor facing a way: for the showcase and the tests. */
    public static List<Placement> plan(String structure, BlockPos anchor, Direction facing, int radius) {
        List<Placement> out = new ArrayList<>();
        layout(structure, anchor, facing, true, Math.max(4, Math.min(16, radius)), out);
        return out;
    }

    /**
     * Put a whole building up at once, out of a chosen palette and not out of anybody's
     * pack: the showcase (/village showcase), where every building a village can raise is
     * set out to be looked at. Returns how many blocks went down.
     */
    public static int stamp(net.minecraft.server.level.ServerLevel level, String structure, BlockPos anchor,
                            Direction facing, int radius, java.util.function.Function<Placement, BlockState> palette) {
        return stampOnly(level, structure, anchor, facing, radius, palette, p -> true);
    }

    /** As stamp, but only the placements chosen (a storey at a time, the missing cells...). */
    public static int stampOnly(net.minecraft.server.level.ServerLevel level, String structure, BlockPos anchor, Direction facing,
                                int radius, java.util.function.Function<Placement, BlockState> palette,
                                java.util.function.Predicate<Placement> only) {
        List<Placement> cells = new java.util.ArrayList<>(plan(structure, anchor, facing, radius));
        cells.removeIf(only.negate());
        cells.sort(java.util.Comparator.comparingInt((Placement p) -> finishing(p) ? 1 : 0)
            .thenComparingInt(p -> p.pos().getY()));
        int n = 0;
        for (Placement p : cells) {
            BlockState st = palette.apply(p);
            if (st == null) continue;
            BlockPos pos = p.pos();
            if (p.part() == Part.BED) {
                Direction lie = p.way() == Blueprints.Way.UP ? facing : Blueprints.world(p.way(), facing);
                BlockState bed = st.setValue(net.minecraft.world.level.block.BedBlock.FACING, lie);
                level.setBlock(pos, bed.setValue(net.minecraft.world.level.block.BedBlock.PART,
                    net.minecraft.world.level.block.state.properties.BedPart.FOOT), 2 | 16);
                level.setBlock(pos.relative(lie), bed.setValue(net.minecraft.world.level.block.BedBlock.PART,
                    net.minecraft.world.level.block.state.properties.BedPart.HEAD), 2 | 16);
                n += 2;
                continue;
            }
            if (p.part() == Part.FURNACE || p.part() == Part.CHEST || p.part() == Part.LADDER) {
                Direction d = p.way() == Blueprints.Way.UP || p.way() == Blueprints.Way.FRONT
                    ? facing.getOpposite() : Blueprints.world(p.way(), facing);
                st = st.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, d);
            } else {
                st = orient(st, p, facing, level, pos);
            }
            if (p.part() == Part.DOOR) {
                hangDoor(level, pos, st);
            } else {
                level.setBlock(pos, st, 2 | 16);
            }
            n++;
        }
        // Now everything is in place, join the panes and the fences up.
        for (Placement p : cells) {
            BlockState st = level.getBlockState(p.pos());
            BlockState joined = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(st, level, p.pos());
            if (joined != st) level.setBlock(p.pos(), joined, 2 | 16);
        }
        return n;
    }

    /** The item a part is placed from, for the people stocking a build. */
    public static Predicate<ItemStack> itemForPart(Part part) {
        return itemFor(part);
    }

    /** Parts placed from any matching item (block taken from the item itself). */
    private static boolean isBlockPart(Part part) {
        return part == Part.BLOCK || part == Part.FENCE || part == Part.GATE || part == Part.WINDOW
            || part == Part.OBSIDIAN || part == Part.DOOR || part == Part.LANTERN || part == Part.HAY
            || part == Part.BARREL || part == Part.FLOWER || part == Part.CARPET || part == Part.ANVIL
            || part == Part.CAULDRON || part == Part.BELL || isFurniture(part);
    }

    /** The crafts' furniture: a bookshelf, a lectern, an enchanting table, a brewing stand,
     *  a smoker, a loom, a grindstone. */
    public static boolean isFurniture(Part part) {
        return part == Part.BOOKSHELF || part == Part.LECTERN || part == Part.ENCHANTING || part == Part.BREWING
            || part == Part.SMOKER || part == Part.LOOM || part == Part.GRINDSTONE || part == Part.CAMPFIRE
            || part == Part.NOTE_BLOCK;
    }

    /** Decorative parts skipped (not blocked-on) when we lack the item. */
    private static boolean isDecorative(Part part) {
        // A bed is skipped, not blocked on: wool takes a rancher or a lucky
        // hunt, and a house with no bed is still a house. It gets one the next
        // time a house goes up with wool in the stores.
        return part == Part.TORCH || part == Part.WINDOW || part == Part.BED || part == Part.DOOR
            || part == Part.LANTERN || part == Part.WATER || part == Part.HAY || part == Part.BARREL
            || part == Part.FLOWER || part == Part.CARPET || part == Part.ANVIL || part == Part.CAULDRON
            || part == Part.BELL || isFurniture(part);
    }

    /** Things that hang from, stand on or lie on something else go in last, when it is there. */
    private static boolean finishing(Placement p) {
        return p.part() == Part.LANTERN || p.part() == Part.TORCH || p.part() == Part.FLOWER
            || p.part() == Part.CARPET || p.part() == Part.WATER || p.part() == Part.BELL;
    }

    private static Predicate<ItemStack> itemFor(Part part) {
        return switch (part) {
            case BLOCK -> BuildGoal::isBuildingBlock;
            case FURNACE -> s -> s.is(Items.FURNACE);
            case CHEST -> s -> s.is(Items.CHEST);
            case CRAFTING_TABLE -> s -> s.is(Items.CRAFTING_TABLE);
            case TORCH -> s -> s.is(Items.TORCH);
            case LADDER -> s -> s.is(Items.LADDER);
            case FENCE -> s -> s.is(ItemTags.FENCES);
            case GATE -> s -> s.is(ItemTags.FENCE_GATES);
            case WINDOW -> s -> s.is(Items.GLASS) || s.is(Items.GLASS_PANE);
            case BED -> s -> s.is(ItemTags.BEDS);
            case OBSIDIAN -> s -> s.is(Items.OBSIDIAN);
            case CLEAR -> s -> false;
            case DOOR -> s -> s.is(ItemTags.WOODEN_DOORS);
            case LANTERN -> s -> s.is(Items.LANTERN) || s.is(Items.SOUL_LANTERN);
            case WATER -> s -> s.is(Items.WATER_BUCKET);
            case HAY -> s -> s.is(Items.HAY_BLOCK);
            case BARREL -> s -> s.is(Items.BARREL);
            case FLOWER -> s -> s.is(ItemTags.SMALL_FLOWERS);
            case CARPET -> s -> s.is(ItemTags.WOOL_CARPETS);
            case ANVIL -> s -> s.is(ItemTags.ANVIL);
            case CAULDRON -> s -> s.is(Items.CAULDRON);
            case BELL -> s -> s.is(Items.BELL);
            case BOOKSHELF -> s -> s.is(Items.BOOKSHELF);
            case LECTERN -> s -> s.is(Items.LECTERN);
            case ENCHANTING -> s -> s.is(Items.ENCHANTING_TABLE);
            case BREWING -> s -> s.is(Items.BREWING_STAND);
            case SMOKER -> s -> s.is(Items.SMOKER);
            case LOOM -> s -> s.is(Items.LOOM);
            case GRINDSTONE -> s -> s.is(Items.GRINDSTONE);
            case CAMPFIRE -> s -> s.is(Items.CAMPFIRE);
            case NOTE_BLOCK -> s -> s.is(Items.NOTE_BLOCK);
            case STOREHOUSE -> s -> s.is(com.jrpetty.mcassistant.McAssistantMod.STOREHOUSE_ITEM.get());
        };
    }

    private static String craftHint(Part part) {
        return switch (part) {
            case FURNACE -> "furnace (\"craft a furnace\" — 8 cobblestone)";
            case CHEST -> "chest (\"craft a chest\" — 8 planks)";
            case CRAFTING_TABLE -> "crafting table (\"craft a crafting table\")";
            case LADDER -> "ladders (\"craft ladders\" — 7 sticks makes 3)";
            case TORCH -> "torches";
            case BLOCK -> "building blocks";
            case FENCE -> "fences (\"craft fences\")";
            case GATE -> "a fence gate (\"craft a fence gate\")";
            case WINDOW -> "glass";
            case BED -> "a bed (\"craft a bed\" — 3 wool, 3 planks)";
            case OBSIDIAN -> "obsidian";
            case CLEAR -> "nothing";
            case DOOR -> "a door";
            case LANTERN -> "a lantern";
            case WATER -> "a bucket of water";
            case HAY -> "hay";
            case BARREL -> "a barrel";
            case FLOWER -> "flowers";
            case CARPET -> "a rug";
            case ANVIL -> "an anvil";
            case CAULDRON -> "a cauldron";
            case BELL -> "a bell";
            case BOOKSHELF -> "bookshelves";
            case LECTERN -> "a lectern";
            case ENCHANTING -> "an enchanting table";
            case BREWING -> "a brewing stand";
            case SMOKER -> "a smoker";
            case LOOM -> "a loom";
            case GRINDSTONE -> "a grindstone";
            case CAMPFIRE -> "a fire for the hearth";
            case NOTE_BLOCK -> "note blocks";
            case STOREHOUSE -> "storehouse units (six planks and four sticks each)";
        };
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.BUILD && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.cursor = 0;
        this.placed = 0;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.standTicks = 0;
        this.putOff.clear();
        this.missed.clear();
        this.lastLook = false;
        this.nearest = Double.MAX_VALUE;
        this.skipped = 0;
        this.perimeterRadius = 5;
        this.plan.clear();

        // arg is "structure" or, for the anchored homestead builds, an encoded
        // "structure|x,y,z,facing[,radius]" so the compound is placed on a fixed
        // grid around home rather than wherever the bot happens to be standing.
        String rawArg = job != null ? job.arg() : null;
        String structure = rawArg;
        BlockPos anchor = null;
        // captured below, once the encoded form has been split
        Direction anchorFacing = null;
        if (rawArg != null && rawArg.indexOf('|') >= 0) {
            String[] head = rawArg.split("\\|", 2);
            structure = head[0];
            String[] p = head[1].split(",");
            try {
                anchor = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                anchorFacing = Direction.byName(p[3]);
                if (p.length > 4) this.perimeterRadius = Math.max(4, Math.min(16, Integer.parseInt(p[4])));
            } catch (Exception e) { anchor = null; anchorFacing = null; }
        }
        this.building = structure;
        if (structure == null || !STRUCTURES.contains(structure)) {
            finish("I can build: " + String.join(", ", STRUCTURES) + ".");
            return;
        }
        boolean centered = anchor != null;
        this.facing = centered && anchorFacing != null ? anchorFacing
            : Direction.fromYRot(assistant.getYRot());
        // Anchored: build centered on the given spot. Otherwise fortify rings the
        // base (home if set) and everything else builds in front of the bot.
        BlockPos base = centered ? anchor
            : ("fortify".equals(structure) && assistant.getHome() != null
                ? assistant.getHome() : assistant.feetPos());
        layout(structure, base, facing, centered, perimeterRadius, plan);
        // A village with its storehouse standing (raised on an earlier run of this building, or
        // anywhere on its square) lays no second one: the cube's cells are left be.
        if (assistant.isSettler() && assistant.ownerId() != null
                && com.jrpetty.mcassistant.entity.Storehouses.stands(assistant.ownerId())) {
            plan.removeIf(p -> p.part() == Part.STOREHOUSE);
        }
        // A settlement's builders level and clear the ground they build on; a hired
        // assistant building beside a player's own trees and terraces does not.
        if (centered && assistant.isSettler()) {
            if ("fortify".equals(structure)) followTheGround(base); else addTerrainWork(base, structure);
        }
        // What is in the way comes down first; then bottom-up, so nothing floats while we work;
        // and the lanterns, flowers and rugs last, when what they hang from or stand on is there.
        // Within a layer, row by row and back along the next (a big building walked cell by
        // cell in order of distance had the builder crossing it and back for every block).
        this.plan.sort(java.util.Comparator
            .comparingInt((Placement p) -> p.part() == Part.CLEAR ? 0 : finishing(p) ? 2 : 1)
            .thenComparingInt((Placement p) -> p.pos().getY())
            .thenComparingInt((Placement p) -> p.pos().getZ())
            .thenComparingInt(p -> (p.pos().getZ() & 1) == 0 ? p.pos().getX() : -p.pos().getX()));

        // Tally what's still needed vs what we carry — no cheating: every
        // furnace/chest/table/ladder is a real item from the pack.
        Map<Part, Integer> pending = new EnumMap<>(Part.class);
        for (Placement p : plan) {
            if (soft(assistant.level().getBlockState(p.pos()))) {
                pending.merge(p.part(), 1, Integer::sum);
            }
        }
        int totalPending = pending.values().stream().mapToInt(Integer::intValue).sum();
        if (totalPending == 0) {
            // Every cell is already filled: the last run finished the work and
            // was cut off before it could say so. It counts.
            if (structure != null) assistant.noteBuilt(structure);
            finish("Looks already built.");
            return;
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Part, Integer> e : pending.entrySet()) {
            Part part = e.getKey();
            if (isDecorative(part)) continue; // torches/windows: skipped if absent
            int have = assistant.countMatching(itemFor(part));
            if (part == Part.BLOCK) {
                if (have == 0) missing.add(e.getValue() + " building blocks (planks/cobble/dirt)");
            } else if (part == Part.FENCE) {
                if (have == 0) missing.add(e.getValue() + " " + craftHint(part));
            } else if (have < e.getValue()) {
                missing.add((e.getValue() - have) + " " + craftHint(part));
            }
        }
        if (!missing.isEmpty()) {
            finish("Before I can build the " + structure + " I still need: " + String.join(", ", missing) + ".");
            return;
        }
        assistant.say("fortify".equals(structure)
            ? "Fortifying the base — walling a perimeter, " + totalPending + " blocks to place."
            : "Building a " + structure + " — " + totalPending + " parts to place.");
    }

    /**
     * The ground is not always level. A building is laid out at one height, and
     * the ground under its footprint is whatever the world made: a column that
     * stops short of the floor is built up to it (so nothing hangs over a drop),
     * and natural leaves in the space it will occupy come down first. Together
     * they are what lets a village raise its storehouse on a hillside instead of
     * waiting for the one flat acre the world may not have made.
     */
    private void addTerrainWork(BlockPos base, String structure) {
        int[] g = footprint(structure);
        int[] all = Blueprints.has(structure) ? Blueprints.fullHalf(structure) : new int[]{ g[0], g[1] };
        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        for (Placement p : plan) taken.add(p.pos());
        for (BlockPos c : fillCells(assistant.level(), base, facing, g[0], g[1])) {
            if (taken.add(c)) plan.add(new Placement(c, Part.BLOCK, Blueprints.Style.FOUNDATION, Blueprints.Way.UP));
        }
        Direction right = facing.getClockWise();
        int top = Blueprints.has(structure) ? 12 : 5;
        for (int dx = -all[0] - 1; dx <= all[0] + 1; dx++) {
            for (int dz = -all[1] - 1; dz <= all[1] + 1; dz++) {
                for (int dy = 0; dy <= top; dy++) {
                    BlockPos c = cell(base, right, facing, dx, dz).above(dy);
                    if (isInTheWay(assistant.level(), c, assistant.level().getBlockState(c))) plan.add(new Placement(c, Part.CLEAR));
                }
            }
        }
    }

    /**
     * A wall round a village is three blocks high above the GROUND, wherever the ground is.
     * The blueprint is drawn at one height: on a hillside half the ring would be buried in the
     * slope and the rest hang in the air over the valley, which keeps out nothing. Each
     * column is lifted or dropped to stand on the ground it is at.
     */
    private void followTheGround(BlockPos base) {
        List<Placement> moved = new ArrayList<>(plan.size());
        Map<Long, Integer> ground = new java.util.HashMap<>();
        Map<Long, Boolean> fits = new java.util.HashMap<>();
        Map<Long, Integer> beds = new java.util.HashMap<>();
        java.util.Set<Long> footed = new java.util.HashSet<>();
        for (Placement p : plan) {
            int x = p.pos().getX();
            int z = p.pos().getZ();
            long key = BlockPos.asLong(x, 0, z);
            int g = ground.computeIfAbsent(key,
                k -> assistant.level().hasChunk(x >> 4, z >> 4) ? groundTop(assistant.level(), x, z) : base.getY());
            // The ring is laid out before anybody looks at what stands on it. Thirteen
            // blocks out from the heart it crosses houses, fields and ponds: a wall through
            // a house fills its rooms, across a field it buries the crop. It goes round them.
            if (!fits.computeIfAbsent(key, k -> wallFits(assistant.level(), x, z, g))) {
                // Except water: a pond or a river on the line gets a footing of stone up from
                // its bed, and the wall stands on it. The long game's wall had two whole sides
                // missing where the ring ran into a lake, and no gate or post on either.
                int bed = beds.computeIfAbsent(key, k -> wetBed(assistant.level(), x, z, g));
                if (bed == Integer.MIN_VALUE) continue;
                if (footed.add(key)) {
                    for (int y = bed; y < g; y++) {
                        moved.add(new Placement(new BlockPos(x, y, z), Part.BLOCK, Blueprints.Style.FOUNDATION, Blueprints.Way.UP));
                    }
                }
            }
            moved.add(p.at(new BlockPos(x, g + (p.pos().getY() - base.getY()), z)));
        }
        plan.clear();
        plan.addAll(moved);
    }

    /**
     * Where a wall column over water would stand its footing: the lowest water cell above a firm
     * bed, if the water is no more than six deep (the builder's reach) and nothing stands over it.
     * Integer.MIN_VALUE where it is not water, too deep, or lava.
     */
    static int wetBed(net.minecraft.world.level.Level level, int x, int z, int g) {
        if (!level.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
        BlockPos top = new BlockPos(x, g - 1, z);
        if (!level.getFluidState(top).is(net.minecraft.tags.FluidTags.WATER)) return Integer.MIN_VALUE;
        for (int dy = 0; dy <= 4; dy++) {
            BlockState st = level.getBlockState(new BlockPos(x, g + dy, z));
            if (!st.isAir() && !(soft(st) && st.getFluidState().isEmpty())) return Integer.MIN_VALUE;
        }
        int y = g - 1;
        while (y > g - 7 && level.getFluidState(new BlockPos(x, y - 1, z)).is(net.minecraft.tags.FluidTags.WATER)) y--;
        BlockState under = level.getBlockState(new BlockPos(x, y - 1, z));
        if (!under.getFluidState().isEmpty() || !under.isSolid()) return Integer.MIN_VALUE;   // deeper than six, or no bed
        return y;
    }

    /**
     * Ground a building may go up over: anything the game lets you place a block into
     * (grass, snow) and the flowers a gardener plants by its door, which are not.
     */
    public static boolean soft(BlockState st) {
        return st.canBeReplaced() || st.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS);
    }

    /** May the wall stand on this column: open ground the world made, not a floor, a
     *  field, a path, water, or anything somebody has put up there? */
    public static boolean wallFits(net.minecraft.world.level.Level level, int x, int z, int g) {
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        BlockState ground = level.getBlockState(new BlockPos(x, g - 1, z));
        if (!ground.getFluidState().isEmpty()) return false;                     // a pond, a river
        if (ground.is(Blocks.FARMLAND) || ground.is(Blocks.DIRT_PATH)) return false;
        if (ground.is(net.minecraft.tags.BlockTags.PLANKS) || ground.is(Blocks.COBBLESTONE)
            || ground.is(net.minecraft.tags.BlockTags.SLABS) || ground.is(net.minecraft.tags.BlockTags.STAIRS)
            || ground.hasBlockEntity()) {
            return false;                                                         // somebody's floor
        }
        for (int dy = 0; dy <= 4; dy++) {
            BlockState st = level.getBlockState(new BlockPos(x, g + dy, z));
            if (st.isAir() || isNaturalLeaves(st)) continue;
            if (soft(st) && st.getFluidState().isEmpty()) continue;   // grass, flowers, snow
            return false;                                                         // a crop, a fence, a wall
        }
        return true;
    }

    /** Leaves nobody placed: a tree's own, which rot and are in the way. */
    private static boolean isNaturalLeaves(BlockState st) {
        return st.is(net.minecraft.tags.BlockTags.LEAVES)
            && !(st.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                && st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT));
    }

    /** Is this log part of a growing tree — has a tree's own leaves within three
     *  blocks — rather than somebody's wall? Only a tree's is ever taken down. */
    public static boolean isTreeLog(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!level.getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS)) return false;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (isNaturalLeaves(level.getBlockState(pos.offset(dx, dy, dz)))) return true;
                }
            }
        }
        return false;
    }

    /** Is this something the ground work takes down: a tree's leaves or trunk? */
    private static boolean isInTheWay(net.minecraft.world.level.Level level, BlockPos pos, BlockState st) {
        return isNaturalLeaves(st) || (st.is(net.minecraft.tags.BlockTags.LOGS) && isTreeLog(level, pos));
    }

    /** The height of the ground at a column — the first free block above it — seeing
     *  through a tree's trunk: a tree standing on a lot is something to fell, not a
     *  five-block cliff. */
    public static int groundTop(net.minecraft.world.level.Level level, int x, int z) {
        int h = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        while (h > level.getMinBuildHeight() + 1 && isTreeLog(level, new BlockPos(x, h - 1, z))) h--;
        return h;
    }

    /**
     * The cells under a building's footprint that the ground does not reach: the
     * columns between the top of the ground and the floor level. Counted by the
     * people who stock a build as well as by the builder, so the two cannot
     * disagree about how much stone a hillside takes.
     */
    public static List<BlockPos> fillCells(net.minecraft.world.level.Level level, BlockPos anchor) {
        return fillCells(level, anchor, 2);
    }

    /** Half the width and half the depth of a building's footing (its drawing's, or the old square). */
    public static int[] footprint(String structure) {
        if (Blueprints.has(structure)) return Blueprints.groundHalf(structure);
        int h = halfOf(structure);
        return new int[]{ h, h };
    }

    /** As fillCells, for a footprint {@code hw} across and {@code hd} deep, turned to face {@code facing}. */
    public static List<BlockPos> fillCells(net.minecraft.world.level.Level level, BlockPos anchor, Direction facing,
                                           int hw, int hd) {
        boolean turned = facing.getAxis() == Direction.Axis.X;
        int wx = turned ? hd : hw, wz = turned ? hw : hd;
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -wx; dx <= wx; dx++) {
            for (int dz = -wz; dz <= wz; dz++) {
                int x = anchor.getX() + dx;
                int z = anchor.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = groundTop(level, x, z);
                for (int y = Math.max(top, anchor.getY() - 8); y < anchor.getY(); y++) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    /** As above, for a footprint {@code half} blocks each side of the middle. */
    public static List<BlockPos> fillCells(net.minecraft.world.level.Level level, BlockPos anchor, int half) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int x = anchor.getX() + dx;
                int z = anchor.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = groundTop(level, x, z);
                for (int y = Math.max(top, anchor.getY() - 8); y < anchor.getY(); y++) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    @Override
    public void stop() {
        this.job = null;
        assistant.getNavigation().stop();
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private void finish(String message) {
        if (assistant.isSettler()) {
            LOG.info("[MCA-BUILD] tick {}: the {} (builder at {}, {} placed): {}", assistant.level().getGameTime(),
                building, assistant.blockPosition().toShortString(), placed, message);
        }
        assistant.say(message);
        assistant.noteJobOutcome(placed > 0);
        assistant.pollJob();
        this.job = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null) return;

        // Advance to the next cell that still needs its part.
        Placement target = null;
        while (cursor < plan.size()) {
            Placement p = plan.get(cursor);
            BlockState st = assistant.level().getBlockState(p.pos());
            // (A cell was marked for clearing when the plan was made, while the tree still had
            // its leaves; by the time its turn comes the leaves round a trunk may be gone,
            // so it is only asked whether there is still a trunk or a leaf there.)
            if (p.part() == Part.CLEAR) {
                if (st.is(net.minecraft.tags.BlockTags.LOGS) || isNaturalLeaves(st)) { target = p; break; }
            } else if (soft(st)) {
                if (!assistant.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p.pos()))) {
                    target = p;
                    break;
                }
                // The builder is standing in it. Step aside and come back to this cell: it used
                // to be skipped for good, which left holes in the floor where the builder had
                // stood while it filled the low side of a hillside.
                BlockPos at = p.pos();
                if (assistant.getNavigation().isDone()) {
                    int side = (standTicks / 25) % 4;
                    int ox = side == 0 ? 3 : side == 2 ? -3 : 0;
                    int oz = side == 1 ? 3 : side == 3 ? -3 : 0;
                    assistant.getNavigation().moveTo(at.getX() + 0.5 + ox, at.getY() + 1, at.getZ() + 0.5 + oz, 1.1D);
                }
                if (++standTicks > 100) {
                    // A village's builder that can't walk out of the cell (on a dais between a
                    // frame's posts) is set down beside it, and the cell is laid after all: one
                    // skipped for good left the gateway a block short of its ten obsidian.
                    if (assistant.isSettler() && assistant.putBeside(at)
                            && !assistant.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(at))) {
                        standTicks = 0;
                        return;
                    }
                    // Put off to the end, when the rest is down and there is somewhere else to
                    // stand — not skipped: skipped, the gateway's tenth obsidian went back to the
                    // stores and the frame stood a block short.
                    if (putOff.merge(at.asLong(), 1, Integer::sum) <= 3) plan.add(p);
                    else missed.add(p);
                    cursor++;                                                  // not for ever
                    standTicks = 0;
                }
                return;
            }
            cursor++;
            stuckTicks = 0;
            standTicks = 0;
            nearest = Double.MAX_VALUE;
        }
        if (target == null && !lastLook && !missed.isEmpty()) {
            // A last look round: what was missed on the way through, now that the rest is
            // down, there is somewhere else to stand, and the builder may be set down nearer.
            lastLook = true;
            plan.addAll(missed);
            missed.clear();
            standTicks = 0;
            stuckTicks = 0;
            nearest = Double.MAX_VALUE;
            return;
        }
        if (target == null) {
            // Only a building that actually has parts in the ground goes on the
            // village's list. A run that could not reach a single cell used to
            // walk off the end of its plan and report "Done" all the same.
            if (building != null && placed > 0) assistant.noteBuilt(building);
            else if (building != null) assistant.noteBuildAbandoned(building);   // not one block went down: not this ground
            finish(placed > 0 ? "Done — placed " + placed + " parts."
                : "Could not get at any of it — I'll try again.");
            return;
        }

        BlockPos pos = target.pos();
        double distSq = assistant.getEyePosition().distanceToSqr(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        assistant.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        double reach2 = AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH;
        boolean inReach = distSq <= reach2;
        if (!inReach && assistant.isSettler()) {
            // A village's builder works off its scaffolding: a roof or a tower top within arm's
            // length across is within its reach, however high. Walking for every block of a roof
            // nine up it could never stand next to, a hall took most of a day.
            double hx = assistant.getX() - (pos.getX() + 0.5), hz = assistant.getZ() - (pos.getZ() + 0.5);
            double up = pos.getY() - assistant.getY();
            inReach = hx * hx + hz * hz <= reach2 && up <= 24 && up >= -6;
        }

        if (!inReach) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.1D);
            }
            // Stuck is NOT GETTING NEARER, not "has been walking a while": a lot on
            // the far side of a hill is a long walk, and counting the walk as stuck
            // skipped cell after cell of the building before the builder arrived —
            // and, when it never could arrive, spent ten thousand ticks doing it.
            if (distSq < nearest - 1.0) {
                nearest = distSq;
                stuckTicks = 0;
            } else if (++stuckTicks > (assistant.getNavigation().isDone() ? 100 : 300)) {
                // (A route that goes round a hill takes the builder away from the
                // cell before it brings it back, so one still being followed gets longer.)
                // A settler with no way to the ground it was given — a river between, a cliff —
                // is set down beside it, as a hand that cannot reach its plot is. Only when that
                // too is impossible is the cell given up.
                if (assistant.isSettler() && assistant.putBeside(pos)) {
                    stuckTicks = 0;
                    nearest = Double.MAX_VALUE;
                    return;
                }
                if (!lastLook) missed.add(target);
                cursor++; // can't get there — skip that cell
                stuckTicks = 0;
                nearest = Double.MAX_VALUE;
                if (++skipped >= 3 && placed == 0 && building != null) {
                    // Three cells in a row out of reach and nothing standing: the
                    // ground is at fault, not the cells. Let the village choose another.
                    assistant.noteBuildAbandoned(building);
                    finish("I can't get to the ground for it — I'll look for another lot.");
                }
            }
            return;
        }

        // A block about every third of a second: quicker for a practised builder and in a happy
        // village, slower in a sad one. Counted in hundredths of a tick (buildPaceHundredths), and
        // what is over carried to the next block, so a pace of 5.4 lays five blocks in 27 ticks:
        // in whole ticks most of a builder's levels changed nothing at all.
        workTicks += 100;
        int pace = assistant.buildPaceHundredths();
        if (workTicks < pace) {
            return;
        }
        workTicks = Math.min(99, workTicks - pace);

        BlockState state;
        Part part = target.part();
        if (part == Part.CLEAR) {
            // Taken down like anybody would: what it gives goes in the pack (a tree's
            // trunk is wood the village wants), not on the floor to rot.
            BlockState there = assistant.level().getBlockState(pos);
            if (assistant.level() instanceof net.minecraft.server.level.ServerLevel server) {
                for (ItemStack drop : net.minecraft.world.level.block.Block.getDrops(
                        there, server, pos, null, assistant, assistant.getMainHandItem())) {
                    ItemStack left = assistant.insertItem(drop);
                    if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(server, pos, left);
                }
            }
            assistant.level().destroyBlock(pos, false, assistant);
            assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            cursor++;
            return;
        }
        if (part == Part.WATER) {
            // Water for a well: a bucket of it poured in, the bucket kept.
            if (assistant.removeMatching(itemFor(part), 1) < 1) { cursor++; return; }
            ItemStack left = assistant.insertItem(new ItemStack(Items.BUCKET));
            if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(assistant.level(), pos, left);
            assistant.level().setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
            assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            placed++;
            cursor++;
            return;
        }
        if (part == Part.BLOCK && target.style() != Blueprints.Style.GENERIC) {
            // What the drawing wants here, if the builder has it; any building block if not.
            state = takeStyled(target.style());
            if (state == null) {
                finish("Ran out of " + craftHint(part) + " — placed " + placed + " parts so far.");
                return;
            }
            state = orient(state, target, pos);
        } else if (part == Part.LANTERN) {
            // A lantern if the village has made one; a torch until it can (a lantern is eight iron
            // nuggets and a torch, the smith's: Masonry), and nothing where a torch would not hold.
            state = takeBlockMatching(itemFor(part));
            if (state != null) {
                state = orient(state, target, pos);
            } else {
                state = com.jrpetty.mcassistant.entity.Masonry.torchFor(assistant.level(), pos);
                if (state != null && assistant.removeMatching(s -> s.is(Items.TORCH), 1) < 1) state = null;
            }
            if (state == null) { cursor++; return; }
        } else if (part == Part.WINDOW) {
            state = takeBlockMatching(target.style() == Blueprints.Style.GLASS ? Blueprints_GLASS : Blueprints_PANE);
            if (state == null) state = takeBlockMatching(itemFor(part));
            if (state == null) { cursor++; return; }             // no glass: the window stays open
            state = orient(state, target, pos);
        } else if (isBlockPart(part)) {
            state = takeBlockMatching(itemFor(part));
            if (state == null) {
                if (isDecorative(part)) { cursor++; return; } // no glass: skip the window
                finish("Ran out of " + craftHint(part) + " — placed " + placed + " parts so far.");
                return;
            }
            state = orient(state, target, pos);
        } else if (part == Part.BED) {
            // Only spent once it will fit: a bed taken out of the pack for a head cell that was
            // blocked used to vanish. And the bed it carries, in its own colour (not always red).
            BlockPos head = pos.relative(bedHead(target));
            if (!assistant.level().getBlockState(head).canBeReplaced()
                    || !assistant.level().getBlockState(head.below()).isSolid()) { cursor++; return; }
            ItemStack bedItem = ItemStack.EMPTY;
            for (int tries = 0; tries < 2 && bedItem.isEmpty(); tries++) {
                for (ItemStack st : assistant.getInventoryItems()) {
                    if (st.is(ItemTags.BEDS)) { bedItem = st; break; }
                }
                // None in the pack: one of the founders' beds comes in from the camp, now it will be laid.
                if (bedItem.isEmpty() && (tries > 0 || !assistant.bedFromTheCamp())) break;
            }
            if (bedItem.isEmpty()) { cursor++; return; }                  // none: Grow.furnish brings one later
            net.minecraft.world.item.Item kind = bedItem.getItem();
            if (assistant.removeMatching(st -> st.is(kind), 1) < 1) { cursor++; return; }
            state = net.minecraft.world.level.block.Block.byItem(kind) instanceof net.minecraft.world.level.block.BedBlock bb
                ? bb.defaultBlockState() : Blocks.RED_BED.defaultBlockState();
        } else {
            if (assistant.removeMatching(itemFor(part), 1) < 1) {
                if (isDecorative(part)) { cursor++; return; } // no torches: skip, keep building
                finish("Ran out of " + craftHint(part) + " — placed " + placed + " parts so far.");
                return;
            }
            state = stateFor(part, target.way());
        }
        if (part == Part.BED) {
            // A bed is TWO cells, which no other part is. If the second cell
            // is not free the whole thing is skipped rather than half a bed
            // being left in the wall — and the item is already spent, so the
            // next house gets it instead.
            if (!layBed(pos, state, bedHead(target))) { cursor++; return; }
        } else if (part == Part.DOOR) {
            if (!hangDoor(assistant.level(), pos, state)) { cursor++; return; }
        } else if (part == Part.STOREHOUSE) {
            // One unit of the storehouse laid; the last of the twenty-seven joins them, its door
            // toward the building's own door.
            com.jrpetty.mcassistant.block.StorehouseBlock.hintFront(
                Blueprints.world(Blueprints.Way.FRONT, facing));
            try {
                assistant.level().setBlockAndUpdate(pos, state);
            } finally {
                com.jrpetty.mcassistant.block.StorehouseBlock.hintFront(null);
            }
        } else {
            assistant.level().setBlockAndUpdate(pos, state);
            // What a settlement builds is the settlement's: the furnaces of its smeltery carry
            // its name. Its stores are the Village Storehouse alone: a chest or a barrel in a
            // building is furniture, not one more place for the village's goods to scatter to.
            if (assistant.isSettler() && part == Part.FURNACE && !"guesthouse".equals(building)) {
                com.jrpetty.mcassistant.entity.ZoneChests.mark(assistant.level(), pos);
            }
        }
        assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        placed++;
        assistant.note(AssistantEntity.Deed.BLOCKS_BUILT, 1);
        skipped = 0;
        nearest = Double.MAX_VALUE;
        assistant.noteBuildProgress();
        cursor++;
    }

    /** Lay a bed foot-first into the room, head against the wall behind it.
     *  Somewhere to sleep is what turns a shell into a house, and it is how
     *  anybody living here skips a night. */
    private Direction bedHead(Placement p) {
        return p.way() == Blueprints.Way.UP ? facing : Blueprints.world(p.way(), facing);
    }

    private boolean layBed(BlockPos foot, BlockState carried, Direction lie) {
        BlockPos head = foot.relative(lie);
        if (!assistant.level().getBlockState(head).canBeReplaced()) return false;
        if (!assistant.level().getBlockState(head.below()).isSolid()) return false;
        BlockState bed = carried.getBlock() instanceof net.minecraft.world.level.block.BedBlock
            ? carried : Blocks.RED_BED.defaultBlockState();
        bed = bed.setValue(net.minecraft.world.level.block.BedBlock.FACING, lie);
        assistant.level().setBlockAndUpdate(foot, bed.setValue(
            net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        assistant.level().setBlockAndUpdate(head, bed.setValue(
            net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        return true;
    }

    private BlockState stateFor(Part part, Blueprints.Way way) {
        Direction toDoor = way == Blueprints.Way.UP || way == Blueprints.Way.FRONT ? facing.getOpposite()
            : Blueprints.world(way, facing);       // face the entrance (or the way the drawing says)
        return switch (part) {
            // Block-from-item parts are placed inline via takeBlockMatching, never here.
            case BLOCK, FENCE, GATE, WINDOW, OBSIDIAN -> Blocks.COBBLESTONE.defaultBlockState();
            case FURNACE -> Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, toDoor);
            case CHEST -> Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, toDoor);
            case CRAFTING_TABLE -> Blocks.CRAFTING_TABLE.defaultBlockState();
            case TORCH -> Blocks.TORCH.defaultBlockState();
            case LADDER -> Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, toDoor);
            // Beds are laid by layBed, which needs two cells; this is only the
            // fallback the switch demands.
            case BED -> Blocks.RED_BED.defaultBlockState();
            case CLEAR -> Blocks.AIR.defaultBlockState();
            // The finishing is placed from the item itself (isBlockPart); this is the switch's fallback.
            case DOOR -> Blocks.OAK_DOOR.defaultBlockState();
            case LANTERN -> Blocks.LANTERN.defaultBlockState();
            case WATER -> Blocks.WATER.defaultBlockState();
            case HAY -> Blocks.HAY_BLOCK.defaultBlockState();
            case BARREL -> Blocks.BARREL.defaultBlockState();
            case FLOWER -> Blocks.POPPY.defaultBlockState();
            case CARPET -> Blocks.RED_CARPET.defaultBlockState();
            case ANVIL -> Blocks.ANVIL.defaultBlockState();
            case CAULDRON -> Blocks.CAULDRON.defaultBlockState();
            case BELL -> Blocks.BELL.defaultBlockState();
            case BOOKSHELF -> Blocks.BOOKSHELF.defaultBlockState();
            case LECTERN -> Blocks.LECTERN.defaultBlockState();
            case ENCHANTING -> Blocks.ENCHANTING_TABLE.defaultBlockState();
            case BREWING -> Blocks.BREWING_STAND.defaultBlockState();
            case SMOKER -> Blocks.SMOKER.defaultBlockState();
            case LOOM -> Blocks.LOOM.defaultBlockState();
            case GRINDSTONE -> Blocks.GRINDSTONE.defaultBlockState();
            case CAMPFIRE -> Blocks.CAMPFIRE.defaultBlockState();
            case NOTE_BLOCK -> Blocks.NOTE_BLOCK.defaultBlockState();
            case STOREHOUSE -> com.jrpetty.mcassistant.block.StorehouseBlock.loose();
        };
    }

    // ------------------------------ the right material, set the right way ----------------

    static final Predicate<ItemStack> Blueprints_PANE = s -> {
        String p = path(s);
        return p.endsWith("glass_pane");
    };
    static final Predicate<ItemStack> Blueprints_GLASS = s -> {
        String p = path(s);
        return p.equals("glass") || (p.endsWith("_stained_glass")) || p.equals("tinted_glass");
    };

    private static String path(ItemStack s) {
        return s.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    /** Stone of the kind a footing or a wall is made of: cobble, stone, stone bricks and their kin. */
    public static boolean isStoneLike(ItemStack s) {
        if (!isBuildingBlock(s)) return false;
        String p = path(s);
        return p.contains("cobble") || p.contains("stone_brick") || p.equals("stone") || p.equals("smooth_stone")
            || p.contains("deepslate") || p.contains("andesite") || p.contains("diorite") || p.contains("granite")
            || p.contains("tuff") || p.contains("blackstone") || p.equals("mud_bricks") || p.contains("sandstone");
    }

    /** Stone that has been worked: cut, polished, smoothed, chiselled, or fired into brick. */
    public static boolean isDressed(ItemStack s) {
        String p = path(s);
        return p.contains("brick") || p.contains("tiles") || p.startsWith("polished") || p.startsWith("smooth")
            || p.startsWith("chiseled") || p.startsWith("cut_");
    }

    /** Rough stone, as it comes out of the ground: what a footing is laid in, leaving the dressed
     *  stone for the courses that show. */
    public static boolean isRoughStone(ItemStack s) {
        return isStoneLike(s) && !isDressed(s);
    }

    /** Is this style part of a roof? Nothing precious goes up there (Masonry.fitForARoof). */
    public static boolean isRoof(Blueprints.Style style) {
        return style == Blueprints.Style.ROOF_STAIR || style == Blueprints.Style.ROOF_STAIR_TOP
            || style == Blueprints.Style.ROOF_SLAB || style == Blueprints.Style.ROOF_SLAB_TOP
            || style == Blueprints.Style.ROOF_BLOCK;
    }

    private static boolean isSoil(ItemStack s) {
        return s.is(Items.DIRT) || s.is(Items.GRASS_BLOCK) || s.is(Items.COARSE_DIRT) || s.is(Items.ROOTED_DIRT)
            || s.is(Items.PODZOL) || s.is(Items.MUD) || s.is(Items.MYCELIUM);
    }

    /** What a builder looks for first for a block of this style. */
    public static Predicate<ItemStack> preferred(Blueprints.Style style) {
        return switch (style) {
            // Rough stone in the footing first: the stone bricks the masons cut are for the courses
            // that show (a footing laid of them left the walls above in cobble).
            case FOUNDATION, WALL_LOW -> BuildGoal::isRoughStone;
            case MASONRY -> s -> isStoneLike(s) && (path(s).contains("brick") || path(s).startsWith("polished"));
            case BRICK -> s -> s.is(Items.BRICKS);
            case FLOOR, WALL, ROOF_BLOCK -> s -> s.is(ItemTags.PLANKS);
            case POST, BEAM_ACROSS, BEAM_ALONG -> s -> s.is(ItemTags.LOGS);
            case ROOF_STAIR, ROOF_STAIR_TOP -> s -> s.is(ItemTags.WOODEN_STAIRS);
            case ROOF_SLAB, ROOF_SLAB_TOP -> s -> s.is(ItemTags.WOODEN_SLABS);
            case STONE_SLAB -> s -> s.is(ItemTags.SLABS) && !s.is(ItemTags.WOODEN_SLABS);
            case STONE_STAIR -> s -> s.is(ItemTags.STAIRS) && !s.is(ItemTags.WOODEN_STAIRS);
            case SOIL -> BuildGoal::isSoil;
            case PANE -> Blueprints_PANE;
            case GLASS -> Blueprints_GLASS;
            default -> BuildGoal::isBuildingBlock;
        };
    }

    /** What will do instead: the next best thing, and then any building block that is not earth. */
    private static Predicate<ItemStack> secondBest(Blueprints.Style style) {
        return switch (style) {
            case MASONRY, BRICK, FOUNDATION, WALL_LOW -> BuildGoal::isStoneLike;
            case ROOF_STAIR, ROOF_STAIR_TOP, ROOF_SLAB, ROOF_SLAB_TOP, POST, BEAM_ACROSS, BEAM_ALONG -> s -> s.is(ItemTags.PLANKS);
            case STONE_SLAB, STONE_STAIR -> BuildGoal::isStoneLike;
            case FLOOR, WALL, ROOF_BLOCK -> s -> s.is(ItemTags.LOGS);
            default -> s -> false;
        };
    }

    @Nullable
    private BlockState takeStyled(Blueprints.Style style) {
        // Whatever a roof falls back on, it is never iron or anything else precious: a roof of the
        // village's iron is its tools and armour gone (Masonry.fitForARoof). No wall of it either.
        Predicate<ItemStack> fit = isRoof(style) ? com.jrpetty.mcassistant.entity.Masonry::fitForARoof
            : s -> !com.jrpetty.mcassistant.entity.Palettes.precious(s);
        // The village's own look first (Palettes): its wood and its stone for this part, best first.
        for (net.minecraft.world.item.Item it : com.jrpetty.mcassistant.entity.Palettes.ranked(assistant.ownerId(), style)) {
            BlockState st = takeBlockMatching(s -> s.is(it) && fit.test(s));
            if (st != null) return st;
        }
        BlockState st = takeBlockMatching(preferred(style).and(fit));
        if (st == null) st = takeBlockMatching(secondBest(style).and(fit));
        if (st == null && style != Blueprints.Style.SOIL) st = takeBlockMatching(s -> isBuildingBlock(s) && !isSoil(s) && fit.test(s));
        if (st == null) st = takeBlockMatching(s -> isBuildingBlock(s) && fit.test(s));
        return st;
    }

    /** Set a block the way the drawing has it: stairs facing and the right way up, logs lying the
     *  right way, slabs high or low, a lantern hung, a barrel standing, panes and fences joined up. */
    private BlockState orient(BlockState st, Placement p, BlockPos pos) {
        return orient(st, p, facing, assistant.level(), pos);
    }

    public static BlockState orient(BlockState st, Placement p, Direction facing,
                                    net.minecraft.world.level.LevelAccessor level, BlockPos pos) {
        Blueprints.Style style = p.style();
        Direction way = Blueprints.world(p.way(), facing);
        if (st.hasProperty(net.minecraft.world.level.block.StairBlock.FACING) && st.getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
            Direction d = way.getAxis().isHorizontal() ? way : facing;
            st = st.setValue(net.minecraft.world.level.block.StairBlock.FACING, d)
                .setValue(net.minecraft.world.level.block.StairBlock.HALF,
                    style == Blueprints.Style.ROOF_STAIR_TOP ? net.minecraft.world.level.block.state.properties.Half.TOP
                        : net.minecraft.world.level.block.state.properties.Half.BOTTOM);
        }
        if (st.getBlock() instanceof net.minecraft.world.level.block.SlabBlock) {
            st = st.setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                style == Blueprints.Style.ROOF_SLAB_TOP ? net.minecraft.world.level.block.state.properties.SlabType.TOP
                    : net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
        }
        if (st.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)) {
            Direction.Axis axis = switch (style) {
                case BEAM_ACROSS -> facing.getClockWise().getAxis();
                case BEAM_ALONG -> facing.getAxis();
                default -> Direction.Axis.Y;
            };
            st = st.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, axis);
        }
        if (st.getBlock() instanceof net.minecraft.world.level.block.LanternBlock) {
            st = st.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, style == Blueprints.Style.HANGING);
        }
        if (st.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock) {
            st = st.setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.UP);
        }
        if (st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.AnvilBlock) {
            Direction d = way.getAxis().isHorizontal() ? way : facing;
            st = st.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, d);
        }
        if (st.getBlock() instanceof net.minecraft.world.level.block.LecternBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.LoomBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.SmokerBlock) {
            Direction d = way.getAxis().isHorizontal() ? way : facing.getOpposite();
            st = st.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, d);
        }
        if (st.getBlock() instanceof net.minecraft.world.level.block.GrindstoneBlock) {
            Direction d = way.getAxis().isHorizontal() ? way : facing.getOpposite();
            st = st.setValue(net.minecraft.world.level.block.GrindstoneBlock.FACING, d)
                .setValue(net.minecraft.world.level.block.GrindstoneBlock.FACE,
                    net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
        }
        if (st.getBlock() instanceof net.minecraft.world.level.block.BellBlock) {
            Direction d = way.getAxis().isHorizontal() ? way : facing;
            st = st.setValue(net.minecraft.world.level.block.BellBlock.FACING, d);
        }
        if (st.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.WallBlock) {
            st = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(st, level, pos);
        }
        return st;
    }

    /** A door is two blocks: the lower half here, the upper above it. */
    public static boolean hangDoor(net.minecraft.world.level.Level level, BlockPos lower, BlockState st) {
        if (!(st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
            level.setBlockAndUpdate(lower, st);
            return true;
        }
        if (!level.getBlockState(lower.above()).canBeReplaced()) return false;
        level.setBlock(lower, st.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2 | 16);
        level.setBlock(lower.above(), st.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
        level.blockUpdated(lower, st.getBlock());
        return true;
    }

    /** What a block costs the village to spend on a wall: stone and the like are
     *  free for the digging, planks are the tools and chests of the day after,
     *  and logs are the planks. A builder spends the cheapest it has, so a
     *  village short of trees does not wall itself in with the last of its wood
     *  and then stand about with no pickaxe. */
    public static int blockCost(ItemStack s) {
        if (s.is(net.minecraft.tags.ItemTags.LOGS)) return 2;
        if (s.is(net.minecraft.tags.ItemTags.PLANKS)) return 1;
        return 0;
    }

    /** Consume one matching BlockItem from the pack; its block is what we place. */
    @Nullable
    private BlockState takeBlockMatching(Predicate<ItemStack> pred) {
        var inv = assistant.getInventoryItems();
        int best = -1;
        int bestCost = Integer.MAX_VALUE;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !pred.test(s) || !(s.getItem() instanceof BlockItem)) continue;
            int cost = blockCost(s);
            if (cost < bestCost) { best = i; bestCost = cost; }
            if (cost == 0) break;
        }
        if (best < 0) return null;
        ItemStack s = inv.get(best);
        BlockState state = ((BlockItem) s.getItem()).getBlock().defaultBlockState();
        s.shrink(1);
        if (s.isEmpty()) inv.set(best, ItemStack.EMPTY);
        return state;
    }

    // ------------------------------ blueprints ------------------------------

    private static void layout(String structure, BlockPos feet, Direction facing,
                               boolean centered, int perimeterRadius, List<Placement> out) {
        Direction right = facing.getClockWise();
        if (Blueprints.has(structure)) {
            // Drawn: every block where the drawing has it. Built in front of a hired hand
            // that was told to build one where it stands.
            int[] half = Blueprints.fullHalf(structure);
            BlockPos center = centered ? feet : feet.relative(facing, half[1] + 2);
            for (Blueprints.Cell c : Blueprints.cells(structure)) {
                out.add(new Placement(cell(center, right, facing, c.dx(), c.dz()).above(c.h()),
                    c.key().part(), c.key().style(), c.key().way()));
            }
            return;
        }
        switch (structure) {
            case "platform" -> {
                BlockPos center = centered ? feet : feet.relative(facing, 3);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        out.add(new Placement(cell(center, right, facing, dx, dz).below(), Part.BLOCK));
                    }
                }
            }
            case "wall" -> {
                BlockPos base = centered ? feet : feet.relative(facing, 2);
                for (int w = -2; w <= 2; w++) {
                    for (int h = 0; h <= 2; h++) {
                        out.add(new Placement(base.relative(right, w).above(h), Part.BLOCK));
                    }
                }
            }
            case "room" -> {
                BlockPos center = centered ? feet : feet.relative(facing, 4);
                shell(out, center, right, facing);       // walls + roof
                floor(out, center, right, facing, 2);     // a proper floor
                out.add(new Placement(cell(center, right, facing, 0, 0), Part.TORCH));
            }
            case "pen" -> {
                BlockPos center = centered ? feet : feet.relative(facing, 5);
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        if (Math.abs(dx) != 3 && Math.abs(dz) != 3) continue; // perimeter only
                        BlockPos col = cell(center, right, facing, dx, dz);
                        boolean isGate = dx == 0 && dz == -3;
                        out.add(new Placement(col, isGate ? Part.GATE : Part.FENCE));
                    }
                }
            }
            case "fortify" -> {
                // A defensive perimeter wall around the base, 3 blocks high, with
                // a one-wide chokepoint doorway on the side we're facing and a
                // torch on each corner so nothing spawns along it. `feet` here is
                // the center (home if set) — the ring is built around it. The
                // radius widens for a whole compound (perimeterRadius from the job).
                // A village's wall rings its square and opens onto each of the four avenues
                // (village/TownPlan): a gate five wide on every side, with a pillar and a lantern
                // either side of it; battlements along the top; a squat tower at each corner.
                // A hired hand's wall round a homestead keeps its one doorway at the front.
                int r = perimeterRadius;
                boolean town = r >= 12;
                int gate = town ? com.jrpetty.mcassistant.village.TownPlan.AVENUE : 0;
                Blueprints.Style stone = Blueprints.Style.MASONRY;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // perimeter only
                        boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                        int along = Math.abs(dx) == r ? dz : dx;
                        if (town ? Math.abs(along) <= gate && !corner : (dx == 0 && dz == -r)) continue;   // the gates
                        BlockPos col = cell(feet, right, facing, dx, dz);
                        boolean pillar = town && Math.abs(along) == gate + 1 && !corner;
                        int top = corner ? 4 : pillar ? 4 : 2;
                        for (int h = 0; h <= top; h++) {
                            out.add(new Placement(col.above(h), Part.BLOCK, stone, Blueprints.Way.UP));
                        }
                        if (corner || pillar) {
                            out.add(new Placement(col.above(top + 1), Part.LANTERN, Blueprints.Style.NONE, Blueprints.Way.UP));
                        } else if (town && Math.floorMod(along, 2) == 0) {
                            out.add(new Placement(col.above(3), Part.BLOCK, Blueprints.Style.STONE_SLAB, Blueprints.Way.UP));
                        }
                    }
                }
                if (!town) {
                    for (int sx = -1; sx <= 1; sx += 2) {
                        for (int sz = -1; sz <= 1; sz += 2) {
                            out.add(new Placement(
                                cell(feet, right, facing, sx * r, sz * r).above(3), Part.TORCH));
                        }
                    }
                }
            }
            case "column" -> {
                // A simple marker pillar with a torch on top — cheap, and handy as
                // a beacon it can build anywhere to mark a spot.
                BlockPos base = centered ? feet : feet.relative(facing, 2);
                for (int h = 0; h <= 4; h++) out.add(new Placement(base.above(h), Part.BLOCK));
                out.add(new Placement(base.above(5), Part.TORCH));
            }
            default -> { }
        }
    }

    /** 5x5 hollow room: walls 3 high with a doorway facing the assistant, flat roof. */
    private static void shell(List<Placement> out, BlockPos center, Direction right, Direction facing) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                BlockPos col = cell(center, right, facing, dx, dz);
                if (edge) {
                    boolean isDoor = dx == 0 && dz == -2;
                    for (int h = 0; h <= 2; h++) {
                        if (isDoor && h <= 1) continue;
                        out.add(new Placement(col.above(h), Part.BLOCK));
                    }
                }
                out.add(new Placement(col.above(3), Part.BLOCK)); // roof
                // A raised middle to the roof, so it reads as a building and not a crate.
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) out.add(new Placement(col.above(4), Part.BLOCK));
            }
        }
    }

    /** A solid floor under a radius x radius footprint. */
    private static void floor(List<Placement> out, BlockPos center, Direction right, Direction facing, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                out.add(new Placement(cell(center, right, facing, dx, dz).below(), Part.BLOCK));
            }
        }
    }

    private static BlockPos cell(BlockPos center, Direction right, Direction facing, int dx, int dz) {
        return center.relative(right, dx).relative(facing, dz);
    }
}
