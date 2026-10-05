package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The insides of the village's buildings, furnished better as the village comes up in the world,
 * so a room in an old town looks lived in and a hall looks like a hall.
 * <ul>
 * <li><b>The Stone Age:</b> a rug down the middle of every room, a barrel by the wall for the
 *     household's odds and ends, and a pot of flowers.</li>
 * <li><b>The Iron Age:</b> the rug gets a border of another colour, a shelf of books goes up by the
 *     wall (two in the great rooms), and a lantern stands on the barrel.</li>
 * <li><b>The Diamond Age:</b> candles on the shelves, a second pot of flowers, and another shelf.</li>
 * </ul>
 * Every piece is a real thing out of the village's stores, put in by one of its hands as town
 * work: a carpet the tailor made (or two wool for three of them), a barrel or a candle or a pot from
 * the shop's bench, a bookshelf, a lantern, a flower picked for the pot. What the stores do not
 * have is left out until they do. Where each piece goes is worked out from the building's own
 * drawing, so it is the same every time it is looked at: the doorway and the way in from it are
 * kept clear, beds and ladders are left room, and nothing goes where something already stands.
 */
public final class Interiors {

    private Interiors() {}

    /** The buildings that are furnished: homes and the rooms folk gather in, not the works. */
    static final Set<String> FURNISHED = Set.of("house", "manor", "townhall", "hall", "tavern", "cafe", "shop", "library",
        "chapel", "guesthouse", "barracks");

    /** What goes where: a cell of a room and what is put in it. */
    enum Kind { RUG, BORDER, BARREL, POT, SHELF, LAMP, CANDLE }

    record Piece(BlockPos pos, Kind kind) {}

    /** Each building's furnishing worked out for an age (by anchor and age), kept while the building stands as it is. */
    private static final Map<String, List<Piece>> PLANS = new HashMap<>();
    /** Each building's age once it is furnished for it (by anchor), so it is not looked at again. */
    private static final Map<Long, Integer> DONE = new HashMap<>();

    public static synchronized void resetForTests() {
        PLANS.clear();
        DONE.clear();
        STATS.clear();
    }

    /** The age's furnishing: how far along a building's insides are (0 none, 1 Stone Age, 2 Iron, 3 Diamond and after). */
    static int tier(Villages.Age age) {
        return Math.min(3, Math.max(0, age.ordinal() - Villages.Age.WOOD.ordinal()));
    }

    // ------------------------------------------------------------------ the work

    /** Up to so many pieces put in across the village's buildings. Returns how many. */
    public static synchronized int work(ServerLevel level, Villages.Village v, int budget) {
        UUID id = v.id();
        int tier = tier(Villages.ageOf(id));
        if (tier == 0 || budget <= 0) return 0;
        int done = 0;
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (!FURNISHED.contains(b.structure()) || Ledger.raising(id, b.anchor())) continue;
            if (DONE.getOrDefault(b.anchor().asLong(), 0) >= tier) continue;
            if (!Land.areaLoaded(level, b.anchor(), 12)) continue;
            List<Piece> plan = plan(level, id, b, tier);
            int left = 0;
            for (Piece p : plan) {
                if (done >= budget) return done;
                BlockState now = level.getBlockState(p.pos());
                if (!now.isAir()) {
                    // A plain rug that wants its border colour now (a piece swapped, not added).
                    if (!(p.kind() == Kind.BORDER && now.is(BlockTags.WOOL_CARPETS))) continue;
                }
                BlockState put = take(level, v, p, now);
                if (put == null) { left++; continue; }
                // Put in by a hand of the village, there in the room.
                if (!TownJobs.atWork(level, v, "interiors", p.pos(), "furnishing the " + Villages.spoken(b.structure()).replaceFirst("^(the|a) ", ""))) {
                    giveBack(level, v, put);
                    return done;
                }
                level.setBlock(p.pos(), put, 3);
                done++;
            }
            if (left == 0) DONE.put(b.anchor().asLong(), tier);
            if (done >= budget) break;
        }
        return done;
    }

    /** The block for this piece, paid for out of the stores; or null if the stores cannot pay for it. */
    @Nullable
    private static BlockState take(ServerLevel level, Villages.Village v, Piece p, BlockState now) {
        return switch (p.kind()) {
            case RUG -> carpet(level, v, false, now);
            case BORDER -> carpet(level, v, true, now);
            case BARREL -> one(level, v, s -> s.is(Items.BARREL), Blocks.BARREL.defaultBlockState());
            case SHELF -> one(level, v, s -> s.is(Items.BOOKSHELF), Blocks.BOOKSHELF.defaultBlockState());
            case LAMP -> one(level, v, s -> s.is(Items.LANTERN), Blocks.LANTERN.defaultBlockState());
            case CANDLE -> {
                ItemStack c = Crafts.takeOne(level, v, s -> s.is(ItemTags.CANDLES));
                Block b = c.isEmpty() ? null : Block.byItem(c.getItem());
                yield b == null || b == Blocks.AIR ? null : b.defaultBlockState();
            }
            case POT -> pot(level, v);
        };
    }

    @Nullable
    private static BlockState one(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, BlockState state) {
        return Crafts.take(level, v, what, 1) ? state : null;
    }

    /** A rug of the stores' carpet (two wool cut into three when there is none): the commonest colour for
     *  the middle, another for the border once the village can run to one. */
    @Nullable
    private static BlockState carpet(ServerLevel level, Villages.Village v, boolean border, BlockState now) {
        List<Item> colours = carpetColours(level, v);
        if (colours.isEmpty()) {
            // Two wool, cut into three carpets (one laid, two into the stores).
            ItemStack wool = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL));
            if (wool.isEmpty()) return null;
            if (!Crafts.take(level, v, s -> s.is(wool.getItem()), 1)) { Crafts.store(level, v, wool); return null; }
            Item carpet = carpetOf(wool.getItem());
            if (carpet == null) return null;
            Crafts.store(level, v, new ItemStack(carpet, 2));
            colours = carpetColours(level, v);
            if (colours.isEmpty()) return null;
        }
        Item want = border && colours.size() > 1 ? colours.get(1) : colours.get(0);
        if (border && now.is(BlockTags.WOOL_CARPETS) && now.getBlock().asItem() == want) return null;     // already so
        if (!Crafts.take(level, v, s -> s.is(want), 1)) return null;
        if (border && now.is(BlockTags.WOOL_CARPETS)) Crafts.store(level, v, new ItemStack(now.getBlock().asItem()));
        Block b = Block.byItem(want);
        return b == Blocks.AIR ? null : b.defaultBlockState();
    }

    /** The carpet colours the stores hold, most first. */
    private static List<Item> carpetColours(ServerLevel level, Villages.Village v) {
        Map<Item, Integer> n = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && s.is(ItemTags.WOOL_CARPETS)) n.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        List<Item> out = new ArrayList<>(n.keySet());
        out.sort(Comparator.<Item>comparingInt(n::get).reversed()
            .thenComparing(i -> BuiltInRegistries.ITEM.getKey(i).getPath()));
        return out;
    }

    @Nullable
    static Item carpetOf(Item wool) {
        String colour = BuiltInRegistries.ITEM.getKey(wool).getPath().replace("_wool", "");
        Item it = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(colour + "_carpet"));
        return it == Items.AIR ? null : it;
    }

    /** A pot of flowers: an empty pot and a flower out of the stores, potted. */
    @Nullable
    private static BlockState pot(ServerLevel level, Villages.Village v) {
        if (Market.stock(level, v.id(), s -> s.is(Items.FLOWER_POT)) < 1) return null;
        ItemStack flower = Crafts.takeOne(level, v, s -> s.is(ItemTags.SMALL_FLOWERS) && potted(s.getItem()) != null);
        if (flower.isEmpty()) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.FLOWER_POT), 1)) { Crafts.store(level, v, flower); return null; }
        Block p = potted(flower.getItem());
        return p == null ? Blocks.FLOWER_POT.defaultBlockState() : p.defaultBlockState();
    }

    @Nullable
    static Block potted(Item flower) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(
            "potted_" + BuiltInRegistries.ITEM.getKey(flower).getPath()));
        return b == Blocks.AIR ? null : b;
    }

    private static void giveBack(ServerLevel level, Villages.Village v, BlockState s) {
        Item it = s.getBlock().asItem();
        if (s.getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
            Crafts.store(level, v, new ItemStack(Items.FLOWER_POT));
            it = pot.getPotted().asItem();
        }
        if (it != Items.AIR) Crafts.store(level, v, new ItemStack(it));
    }

    // ------------------------------------------------------------------ where it all goes

    /**
     * The furnishing of a building for an age, worked out from its drawing: every room's floor (a cell
     * the drawing leaves empty, on something the drawing put down, with room above it and walls round
     * it), the cells by the walls for the furniture and the rest for the rug — but not the doorway,
     * the way in from it, the cells by a ladder, a bed or a chest, nor anywhere something stands.
     */
    static List<Piece> plan(ServerLevel level, UUID village, Ledger.Building b, int tier) {
        String key = b.anchor().asLong() + ":" + tier + ":" + Ages.drawing(village, b);
        List<Piece> got = PLANS.get(key);
        if (got != null) return got;
        String drawing = Ages.drawing(village, b);
        List<BuildGoal.Placement> placements = BuildGoal.plan(drawing, b.anchor(), b.facing(), 13);
        Map<BlockPos, BuildGoal.Placement> at = new HashMap<>();
        for (BuildGoal.Placement p : placements) if (p.part() != BuildGoal.Part.CLEAR) at.put(p.pos(), p);
        Set<BlockPos> keepClear = new HashSet<>();
        Set<BlockPos> heads = new HashSet<>();
        BlockPos door = null;
        for (BuildGoal.Placement p : placements) {
            switch (p.part()) {
                case DOOR -> {
                    if (door == null || p.pos().getY() < door.getY()) door = p.pos();
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) keepClear.add(p.pos().offset(dx, 0, dz));
                }
                // A bed's head lies a cell beyond its foot (the drawing has the foot): that cell is the bed's.
                case BED -> {
                    Direction lie = p.way() == com.jrpetty.mcassistant.entity.goal.Blueprints.Way.UP ? b.facing()
                        : com.jrpetty.mcassistant.entity.goal.Blueprints.world(p.way(), b.facing());
                    keepClear.add(p.pos().relative(lie));
                    heads.add(p.pos().relative(lie));
                }
                // Room to get at a ladder, a chest, a stand or a table of the crafts (a bed is got into from
                // anywhere, a bench or an oven from the side).
                case LADDER, CHEST, ANVIL, BREWING, ENCHANTING, LECTERN, LOOM, GRINDSTONE, CAMPFIRE -> {
                    for (Direction d : Direction.Plane.HORIZONTAL) keepClear.add(p.pos().relative(d));
                    keepClear.add(p.pos());
                }
                default -> { }
            }
        }
        // The way in: from the door, straight into the building, kept clear.
        Direction back = b.facing();
        if (door != null) {
            for (int k = 0; k <= 12; k++) keepClear.add(door.relative(back, k));
        }
        int[] half = BuildGoal.footprint(drawing);
        int r = Math.max(half[0], half[1]);
        List<BlockPos> floor = new ArrayList<>();
        for (BuildGoal.Placement p : placements) {
            BlockPos cell = p.pos().above();
            if (at.containsKey(cell) || at.containsKey(cell.above()) || keepClear.contains(cell)) continue;
            // On a floor (boards, stone, the dais), not on the furniture or a bench.
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            switch (p.style()) {
                case FLOOR, FOUNDATION, MASONRY, GENERIC -> { }
                default -> { continue; }
            }
            // A room: walls round it, a roof over it (not the attic). Looked for right across the building:
            // a cell by one wall of a great room is the whole width of it from the other.
            if (!enclosed(at, cell, 2 * r + 2)) continue;
            // Not where something stands already (a bed bought since, a folk's own chest), unless it is
            // one of the furnishing's own pieces: a building looked at again after a restart keeps its plan.
            BlockState there = level.getBlockState(cell);
            if (!there.isAir() && !ours(there)) continue;
            floor.add(cell);
        }
        floor.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        // By the walls and in the open, floor by floor.
        Map<Integer, List<BlockPos>> walls = new HashMap<>(), open = new HashMap<>();
        for (BlockPos c : floor) {
            boolean byWall = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BuildGoal.Placement n = at.get(c.relative(d));
                if (n != null && (n.part() == BuildGoal.Part.BLOCK || n.part() == BuildGoal.Part.WINDOW)) { byWall = true; break; }
            }
            STATS.merge(b.anchor().asLong() + (byWall ? ":walls" : ":open"), 1, Integer::sum);
            (byWall ? walls : open).computeIfAbsent(c.getY(), k -> new ArrayList<>()).add(c);
        }
        boolean great = !b.structure().equals("house") && !b.structure().equals("barracks");
        List<Piece> out = new ArrayList<>();
        for (Map.Entry<Integer, List<BlockPos>> e : open.entrySet()) {
            List<BlockPos> cells = e.getValue();
            if (cells.size() < 2) continue;
            // The rug: the open floor; its outer ring a border from the Iron Age on.
            Set<BlockPos> rug = new HashSet<>(cells);
            for (BlockPos c : cells) {
                boolean edge = false;
                for (Direction d : Direction.Plane.HORIZONTAL) if (!rug.contains(c.relative(d))) { edge = true; break; }
                out.add(new Piece(c, edge && tier >= 2 ? Kind.BORDER : Kind.RUG));
            }
        }
        for (Map.Entry<Integer, List<BlockPos>> e : walls.entrySet()) {
            List<BlockPos> cells = new ArrayList<>(e.getValue());
            if (cells.isEmpty()) continue;
            // The furniture along the walls, spread out: from the corners in, each a few cells from the last.
            cells.sort(Comparator.<BlockPos>comparingInt(c -> -corner(at, c)).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
            List<BlockPos> picked = new ArrayList<>();
            for (BlockPos c : cells) {
                boolean near = false;
                for (BlockPos o : picked) if (o.distManhattan(c) < 2) { near = true; break; }
                if (!near) picked.add(c);
            }
            List<Kind> pieces = new ArrayList<>();
            pieces.add(Kind.BARREL);
            pieces.add(Kind.POT);
            if (tier >= 2) { pieces.add(Kind.SHELF); if (great) pieces.add(Kind.SHELF); }
            if (tier >= 3) { pieces.add(Kind.POT); pieces.add(Kind.SHELF); }
            BlockPos barrel = null, shelf = null;
            for (int i = 0; i < pieces.size() && i < picked.size(); i++) {
                out.add(new Piece(picked.get(i), pieces.get(i)));
                if (pieces.get(i) == Kind.BARREL) barrel = picked.get(i);
                if (pieces.get(i) == Kind.SHELF && shelf == null) shelf = picked.get(i);
            }
            // A lantern on the barrel (Iron Age), candles on the first shelf (Diamond).
            if (tier >= 2 && barrel != null && !at.containsKey(barrel.above())) out.add(new Piece(barrel.above(), Kind.LAMP));
            if (tier >= 3 && shelf != null && !at.containsKey(shelf.above())) out.add(new Piece(shelf.above(), Kind.CANDLE));
        }
        List<Piece> fixed = List.copyOf(out);
        PLANS.put(key, fixed);
        return fixed;
    }

    /**
     * How far a building's insides are along for the village's age, for the town's books: {pieces in,
     * pieces planned}; {0, 0} for a building that is not furnished or not yet looked at (it reads a plan
     * already worked out, and never works one out itself).
     */
    public static synchronized int[] progress(ServerLevel level, UUID village, Ledger.Building b) {
        int tier = tier(Villages.ageOf(village));
        if (tier == 0 || !FURNISHED.contains(b.structure())) return new int[2];
        List<Piece> plan = PLANS.get(b.anchor().asLong() + ":" + tier + ":" + Ages.drawing(village, b));
        if (plan == null) return new int[2];
        if (DONE.getOrDefault(b.anchor().asLong(), 0) >= tier) return new int[]{ plan.size(), plan.size() };
        int in = 0;
        for (Piece p : plan) if (level.isLoaded(p.pos()) && ours(level.getBlockState(p.pos()))) in++;
        return new int[]{ in, plan.size() };
    }

    /** The furnishing's own kinds of block: a rug, a barrel, a shelf, a lamp, a candle, a pot. */
    private static boolean ours(BlockState s) {
        return s.is(BlockTags.WOOL_CARPETS) || s.is(Blocks.BARREL) || s.is(Blocks.BOOKSHELF) || s.is(Blocks.LANTERN)
            || s.is(BlockTags.CANDLES) || s.is(BlockTags.FLOWER_POTS);
    }

    /** Tests: how many floor cells of each sort a building's plan found. */
    private static final Map<String, Integer> STATS = new HashMap<>();

    public static synchronized String statsForTests(Ledger.Building b) {
        return "walls " + STATS.getOrDefault(b.anchor().asLong() + ":walls", 0) + ", open " + STATS.getOrDefault(b.anchor().asLong() + ":open", 0);
    }

    /** How cornered a cell is: how many of its sides the drawing has walled. */
    private static int corner(Map<BlockPos, BuildGoal.Placement> at, BlockPos c) {
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) if (at.containsKey(c.relative(d))) n++;
        return n;
    }

    /**
     * Walls within reach in every direction along the floor (a wall, a post, a window or a door: not the
     * slope of a roof, so the space under the rafters is not a room) and a roof over it: a room, not the
     * yard or the attic.
     */
    private static boolean enclosed(Map<BlockPos, BuildGoal.Placement> at, BlockPos c, int reach) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            boolean hit = false;
            for (int k = 1; k <= reach && !hit; k++) {
                BuildGoal.Placement n = at.get(c.relative(d, k));
                if (n == null) continue;
                if (wallish(n)) hit = true;
                else if (n.part() == BuildGoal.Part.BLOCK) break;                    // a roof slope or a beam first: not a room
            }
            if (!hit) return false;
        }
        for (int k = 2; k <= 10; k++) if (at.containsKey(c.above(k))) return true;     // under a roof
        return false;
    }

    private static boolean wallish(BuildGoal.Placement p) {
        if (p.part() == BuildGoal.Part.WINDOW || p.part() == BuildGoal.Part.DOOR) return true;
        if (p.part() != BuildGoal.Part.BLOCK) return false;
        return switch (p.style()) {
            case WALL, WALL_LOW, MASONRY, BRICK, POST, GENERIC, GLASS, FOUNDATION -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the pieces a building is furnished with for an age (by kind). */
    public static Map<String, Integer> planForTests(ServerLevel level, UUID village, Ledger.Building b, int tier) {
        Map<String, Integer> n = new HashMap<>();
        for (Piece p : plan(level, village, b, tier)) n.merge(p.kind().name(), 1, Integer::sum);
        return n;
    }
}
