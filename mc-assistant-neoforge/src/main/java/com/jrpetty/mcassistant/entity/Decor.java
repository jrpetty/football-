package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A home that shows who lives in it. When the village furnishes its houses for the age (Interiors), each
 * household's house is given the things of its trades as well, set out tidily against the walls, and is
 * done up in its folk's favourite colour:
 * <ul>
 * <li><b>The trades.</b> The smith's anvil, the fisher's barrel with cod in it, the farmer's composter and
 *     a sack of seed, the miner's lantern and a pickaxe in a frame on the wall, the woodcutter's chopping
 *     block and an axe on the wall, the cook's smoker, the beekeeper's honey on a shelf, the guard's armour
 *     stand, the scout's map table and compass, the tailor's loom, the rancher's hay and shears, the
 *     smelter's furnace, the storekeeper's lectern, the brewer's stand, the enchanter's books, the
 *     shopkeeper's own bench, the hunter's fletching table and bow, the courier's crate.</li>
 * <li><b>The colour.</b> Every folk has a favourite colour, from what it loves (a gardener's is a
 *     flower's, a stargazer's the night's blue) — always a colour the meadows give a dye for. The house
 *     gets a rug by the bed in its owner's colour and a banner on the wall in its partner's, woven by the
 *     tailor of wool dyed with flowers.</li>
 * </ul>
 * Nothing is put in from nothing. Each thing comes out of the stores (a tool only if the village has more
 * of it than it keeps; the cod only out of a full larder); the plain joinery (a composter, a barrel, a
 * map table, an armour stand) is made there and then by the hand furnishing the house, of the stores'
 * planks and stone, by the game's own recipes (Bench); what wants a maker (the smith's anvil, the
 * tailor's dyed rug) waits for one — a want on the stores, which the makers see to (Luxuries.craft) —
 * and goes in on a later round. A household that moves out leaves its trade's things to be cleared back
 * into the stores for the next.
 *
 * <p>Where each thing goes is worked out from the house's drawing and checked against what stands there
 * now: on a sound floor against a standing wall, never across the doorway or the way in from it, nor
 * where it would cut a room in two, nor on the age's own furnishing; a frame or a banner on a solid wall.
 * A well-furnished home is worth more and its folk are the happier for it.
 */
public final class Decor {

    private Decor() {}

    // ------------------------------------------------------------------ the trades' things

    /** How a thing is set out in a house. */
    enum Shape {
        /** A block on the floor against a wall: the anvil, the loom, the smoker, a chopping block. */
        FLOOR,
        /** A lamp: on a table or a barrel if one has room on top, else on the floor by a wall. */
        LAMP,
        /** A barrel by a wall with something of the trade's in it: the fisher's cod, the farmer's seed. */
        BARREL,
        /** A tool of the trade in an item frame on the wall. */
        FRAME,
        /** An armour stand by a wall. */
        STAND,
        /** A rug in the household's colour, by the bed. */
        RUG,
        /** A banner in the household's colour, on the wall. */
        BANNER
    }

    private static final Predicate<ItemStack> NOTHING = s -> false;

    /**
     * One thing that shows a household's trade (or its colour): its name in the house's book, the trade,
     * how it is set out, what in the stores will do for it and what is made when there is none, what goes
     * in it (a frame's tool, a barrel's cod) and how many, whether the hand furnishing the house may make
     * it there and then out of the stores (a composter of planks: plain joinery; not an anvil), and how it
     * is put in words.
     */
    record Piece(String key, @Nullable StationTask trade, Shape shape, Predicate<ItemStack> what, Item item,
                 @Nullable Item inside, int insideCount, Predicate<ItemStack> insideWhat, boolean plain, String words) {}

    private static Piece floor(String key, StationTask t, Item it, boolean plain, String words) {
        return new Piece(key, t, Shape.FLOOR, s -> s.is(it), it, null, 0, NOTHING, plain, words);
    }

    private static Piece barrel(String key, StationTask t, @Nullable Item in, int n, String words) {
        return new Piece(key, t, Shape.BARREL, s -> s.is(Items.BARREL), Items.BARREL, in, n,
            in == null ? NOTHING : s -> s.is(in), true, words);
    }

    private static Piece frame(String key, StationTask t, Predicate<ItemStack> tool, Item want, String words) {
        return new Piece(key, t, Shape.FRAME, s -> s.is(Items.ITEM_FRAME), Items.ITEM_FRAME, want, 1, tool, true, words);
    }

    /** The things that show a trade, the one that tells it best first (a small house may have room for only that). */
    static List<Piece> piecesFor(StationTask t) {
        return switch (t) {
            case FARM -> List.of(floor("farm.composter", t, Items.COMPOSTER, true, "the farmer's composter"),
                barrel("farm.seed", t, Items.WHEAT_SEEDS, 8, "a sack of seed"));
            case WOOD -> List.of(new Piece("wood.block", t, Shape.FLOOR, s -> s.is(ItemTags.LOGS), Items.OAK_LOG, null, 0, NOTHING,
                    false, "a chopping block"),
                frame("wood.axe", t, s -> s.getItem() instanceof AxeItem, Items.WOODEN_AXE, "an axe on the wall"));
            case MINE -> List.of(new Piece("mine.lantern", t, Shape.LAMP, s -> s.is(Items.LANTERN), Items.LANTERN, null, 0, NOTHING,
                    false, "the miner's lantern"),
                frame("mine.pick", t, s -> s.getItem() instanceof PickaxeItem, Items.WOODEN_PICKAXE, "a pickaxe on the wall"));
            case RANCH -> List.of(floor("ranch.hay", t, Items.HAY_BLOCK, true, "a bale of hay"),
                frame("ranch.shears", t, s -> s.is(Items.SHEARS), Items.SHEARS, "shears on the wall"));
            case GUARD -> List.of(new Piece("guard.stand", t, Shape.STAND, s -> s.is(Items.ARMOR_STAND), Items.ARMOR_STAND, null, 0,
                NOTHING, true, "the guard's armour stand"));
            case SMELT -> List.of(floor("smelt.furnace", t, Items.FURNACE, true, "the smelter's own furnace"));
            case FISH -> List.of(barrel("fish.cod", t, Items.COD, 4, "a barrel with cod in it"),
                frame("fish.rod", t, s -> s.is(Items.FISHING_ROD), Items.FISHING_ROD, "a rod on the wall"));
            case STORE -> List.of(floor("store.lectern", t, Items.LECTERN, true, "the storekeeper's lectern"));
            case HAUL -> List.of(barrel("haul.crate", t, null, 0, "a courier's crate"));
            case SMITH -> List.of(new Piece("smith.anvil", t, Shape.FLOOR, s -> s.is(ItemTags.ANVIL), Items.ANVIL, null, 0, NOTHING,
                false, "the smith's anvil"));
            case TAILOR -> List.of(floor("tailor.loom", t, Items.LOOM, true, "the tailor's loom"));
            case BEEKEEP -> List.of(barrel("bee.honey", t, Items.HONEY_BOTTLE, 3, "a shelf of honey"));
            case BREW -> List.of(floor("brew.stand", t, Items.BREWING_STAND, false, "a brewing stand"));
            case ENCHANT -> List.of(floor("enchant.shelf", t, Items.BOOKSHELF, true, "a shelf of the enchanter's books"));
            case COOK -> List.of(floor("cook.smoker", t, Items.SMOKER, true, "the cook's smoker"));
            case SHOP -> List.of(floor("shop.bench", t, Items.CRAFTING_TABLE, true, "the shopkeeper's own bench"));
            // [caves] A lantern for the dark, and a pick on the wall.
            case CAVE -> List.of(new Piece("cave.lantern", t, Shape.LAMP, s -> s.is(Items.LANTERN), Items.LANTERN, null, 0, NOTHING,
                    false, "the cave dweller's lantern"),
                frame("cave.pick", t, s -> s.getItem() instanceof PickaxeItem, Items.WOODEN_PICKAXE, "a pickaxe on the wall"));
            // [emerald] An emerald in a frame, the first the trader brought home, and a barrel of the goods it trades.
            case EMERALD -> List.of(frame("emerald.frame", t, s -> s.is(Items.EMERALD), Items.EMERALD, "an emerald on the wall"));
            // [diver] A barrel of the dried kelp it packs, and a sea pickle off the bed glowing in a frame.
            case DIVER -> List.of(barrel("diver.kelp", t, Items.DRIED_KELP, 8, "a barrel of dried kelp"),
                frame("diver.pickle", t, s -> s.is(Items.SEA_PICKLE), Items.SEA_PICKLE, "a sea pickle on the wall"));
            case SCOUT -> List.of(floor("scout.table", t, Items.CARTOGRAPHY_TABLE, true, "a map table"),
                frame("scout.compass", t, s -> s.is(Items.COMPASS) || s.is(Items.MAP) || s.is(Items.FILLED_MAP), Items.COMPASS,
                    "a compass on the wall"));
            case HUNT -> List.of(floor("hunt.table", t, Items.FLETCHING_TABLE, true, "a fletching table"),
                frame("hunt.bow", t, s -> s.getItem() instanceof BowItem, Items.BOW, "a bow on the wall"));
            default -> List.of();
        };
    }

    /** Every piece of every trade, by its key (to read a house's book back). */
    private static volatile Map<String, Piece> BY_KEY;

    @Nullable
    static Piece piece(String key) {
        Map<String, Piece> m = BY_KEY;
        if (m == null) {
            m = new HashMap<>();
            for (StationTask t : StationTask.values()) for (Piece p : piecesFor(t)) m.put(p.key(), p);
            BY_KEY = m;
        }
        Piece p = m.get(key);
        if (p != null) return p;
        // A colour: "colour.rug.blue", "colour.banner.light_blue".
        String[] k = key.split("\\.");
        if (k.length == 3 && k[0].equals("colour")) {
            DyeColor c = DyeColor.byName(k[2], null);
            if (c != null) return k[1].equals("rug") ? rug(c) : k[1].equals("banner") ? banner(c) : null;
        }
        return null;
    }

    // ------------------------------------------------------------------ the colours

    /** The colours a favourite can be: each one the meadows give a dye for (purple of red and blue). */
    private static final DyeColor[] FLOWER_COLOURS = { DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.BLUE,
        DyeColor.LIGHT_BLUE, DyeColor.MAGENTA, DyeColor.PINK, DyeColor.WHITE, DyeColor.PURPLE, DyeColor.LIGHT_GRAY };

    /**
     * A folk's favourite colour, from what it loves: a gardener's is a flower's, a stargazer's the night's
     * blue, one that loves gems the diamond's, gold the sun's, books a binding's, fish the sea's, sweets a
     * sugared pink, music a royal purple; the rest its own. The same every time it is asked.
     */
    public static DyeColor colour(VillageFolkEntity f) {
        Persona p = f.persona();
        DyeColor[] choose = switch (p.loves()) {
            case FLOWERS -> new DyeColor[]{ DyeColor.RED, DyeColor.YELLOW, DyeColor.PINK, DyeColor.ORANGE, DyeColor.MAGENTA };
            case GEMS -> new DyeColor[]{ DyeColor.LIGHT_BLUE, DyeColor.BLUE, DyeColor.PURPLE };
            case GOLD -> new DyeColor[]{ DyeColor.YELLOW, DyeColor.ORANGE };
            case WOOL -> new DyeColor[]{ DyeColor.WHITE, DyeColor.LIGHT_GRAY };
            case BOOKS -> new DyeColor[]{ DyeColor.BLUE, DyeColor.PURPLE };
            case FISH -> new DyeColor[]{ DyeColor.LIGHT_BLUE, DyeColor.BLUE };
            case SWEETS -> new DyeColor[]{ DyeColor.PINK, DyeColor.MAGENTA };
            case TOOLS -> new DyeColor[]{ DyeColor.LIGHT_GRAY, DyeColor.ORANGE };
            case MUSIC -> new DyeColor[]{ DyeColor.PURPLE, DyeColor.MAGENTA };
            default -> FLOWER_COLOURS;
        };
        if (p.hobby() == Persona.Hobby.GARDENING) choose = new DyeColor[]{ DyeColor.RED, DyeColor.YELLOW, DyeColor.PINK, DyeColor.ORANGE };
        else if (p.hobby() == Persona.Hobby.STARGAZING) choose = new DyeColor[]{ DyeColor.BLUE, DyeColor.PURPLE, DyeColor.LIGHT_BLUE };
        return choose[Math.floorMod(f.getUUID().hashCode(), choose.length)];
    }

    /** "light blue". */
    public static String colourWord(DyeColor c) {
        return c.getName().replace('_', ' ');
    }

    /** A thing of the game's own in a colour: "blue" + "_carpet". */
    static Item coloured(DyeColor c, String suffix) {
        Item it = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(c.getName() + suffix));
        return it == Items.AIR ? Items.WHITE_CARPET : it;
    }

    static Piece rug(DyeColor c) {
        Item it = coloured(c, "_carpet");
        return new Piece("colour.rug." + c.getName(), null, Shape.RUG, s -> s.is(it), it, null, 0, NOTHING, false,
            "a " + colourWord(c) + " rug");
    }

    static Piece banner(DyeColor c) {
        Item it = coloured(c, "_banner");
        return new Piece("colour.banner." + c.getName(), null, Shape.BANNER, s -> s.is(it), it, null, 0, NOTHING, false,
            "a " + colourWord(c) + " banner");
    }

    /**
     * What a household's house should show: the things of each grown folk's trade (the first of each before
     * any second, the eldest's first), a rug in the owner's colour by its bed, and a banner in the partner's
     * colour (the owner's, living alone).
     */
    static List<Piece> wanted(List<VillageFolkEntity> household) {
        List<VillageFolkEntity> grown = new ArrayList<>();
        for (VillageFolkEntity f : household) if (!f.isBaby()) grown.add(f);
        List<Piece> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int round = 0; round < 2; round++) {
            for (VillageFolkEntity f : grown) {
                List<Piece> mine = piecesFor(f.stationTask());
                if (round < mine.size() && seen.add(mine.get(round).key())) out.add(mine.get(round));
            }
        }
        if (!grown.isEmpty()) {
            DyeColor own = colour(grown.get(0));
            out.add(0, rug(own));
            Piece b = banner(grown.size() > 1 ? colour(grown.get(1)) : own);
            out.add(Math.min(out.size(), 3), b);
        }
        return out;
    }

    // ------------------------------------------------------------------ the rooms

    /**
     * A house's rooms as its drawing has them: the floor (a cell the drawing leaves empty over its floor,
     * walled round and roofed), the sides of each floor cell with a wall (or a window) against them, what
     * is kept clear (the doorway and the way in, a bed's head, a ladder's foot, the chest), the windows and
     * the cell inside each, the door, and the household's chests.
     */
    record Room(List<BlockPos> floor, Set<BlockPos> floorSet, Map<BlockPos, List<Direction>> walls, Set<BlockPos> clear,
                List<BlockPos> windows, Map<BlockPos, BlockPos> sills, @Nullable BlockPos door, List<BlockPos> chests,
                List<BlockPos> furniture) {}

    private static final Map<String, Room> ROOMS = new ConcurrentHashMap<>();

    static Room room(UUID village, Ledger.Building b) {
        String drawing = Ages.drawing(village, b);
        return ROOMS.computeIfAbsent(b.anchor().asLong() + ":" + drawing + ":" + b.facing(), k -> draw(drawing, b));
    }

    private static Room draw(String drawing, Ledger.Building b) {
        List<BuildGoal.Placement> placements = BuildGoal.plan(drawing, b.anchor(), b.facing(), 13);
        Map<BlockPos, BuildGoal.Placement> at = new HashMap<>();
        for (BuildGoal.Placement p : placements) if (p.part() != BuildGoal.Part.CLEAR) at.put(p.pos(), p);
        Set<BlockPos> clear = new HashSet<>();
        BlockPos door = null;
        List<BlockPos> windows = new ArrayList<>(), chests = new ArrayList<>();
        for (BuildGoal.Placement p : placements) {
            switch (p.part()) {
                case DOOR -> {
                    if (door == null || p.pos().getY() < door.getY()) door = p.pos();
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) clear.add(p.pos().offset(dx, 0, dz));
                }
                case BED -> {
                    Direction lie = p.way() == com.jrpetty.mcassistant.entity.goal.Blueprints.Way.UP ? b.facing()
                        : com.jrpetty.mcassistant.entity.goal.Blueprints.world(p.way(), b.facing());
                    clear.add(p.pos().relative(lie));
                }
                case LADDER, CHEST, ANVIL, BREWING, ENCHANTING, LECTERN, LOOM, GRINDSTONE, CAMPFIRE -> {
                    for (Direction d : Direction.Plane.HORIZONTAL) clear.add(p.pos().relative(d));
                    clear.add(p.pos());
                    if (p.part() == BuildGoal.Part.CHEST) chests.add(p.pos());
                }
                case WINDOW -> windows.add(p.pos());
                default -> { }
            }
        }
        if (door != null) for (int k = 0; k <= 12; k++) clear.add(door.relative(b.facing(), k));
        int[] half = BuildGoal.footprint(drawing);
        int r = Math.max(half[0], half[1]);
        List<BlockPos> floor = new ArrayList<>();
        for (BuildGoal.Placement p : placements) {
            BlockPos cell = p.pos().above();
            if (at.containsKey(cell) || at.containsKey(cell.above())) continue;
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            switch (p.style()) {
                case FLOOR, FOUNDATION, MASONRY, GENERIC -> { }
                default -> { continue; }
            }
            if (!enclosed(at, cell, 2 * r + 2)) continue;
            floor.add(cell);
        }
        floor.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        Map<BlockPos, List<Direction>> walls = new HashMap<>();
        for (BlockPos c : floor) {
            List<Direction> ds = new ArrayList<>();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BuildGoal.Placement n = at.get(c.relative(d));
                if (n != null && (n.part() == BuildGoal.Part.BLOCK || n.part() == BuildGoal.Part.WINDOW)) ds.add(d);
            }
            if (!ds.isEmpty()) walls.put(c, List.copyOf(ds));
        }
        Map<BlockPos, BlockPos> sills = new HashMap<>();
        for (BlockPos w : windows) {
            BlockPos best = null;
            double bd = flat(w, b.anchor());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = w.relative(d);
                if (at.containsKey(n)) continue;
                double nd = flat(n, b.anchor());
                if (nd < bd) { bd = nd; best = n; }
            }
            if (best != null) sills.put(w, best);
        }
        // The drawing's own furniture with room on top (the bench, the stove, a barrel): a table for a candle.
        List<BlockPos> furniture = new ArrayList<>();
        for (BuildGoal.Placement p : placements) {
            switch (p.part()) {
                case CRAFTING_TABLE, FURNACE, BARREL, BOOKSHELF, SMOKER, LOOM -> {
                    if (!at.containsKey(p.pos().above())) furniture.add(p.pos());
                }
                default -> { }
            }
        }
        return new Room(List.copyOf(floor), Set.copyOf(floor), walls, clear, List.copyOf(windows), sills, door, List.copyOf(chests),
            List.copyOf(furniture));
    }

    private static double flat(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** Walled within reach every way along the floor (not the slope of a roof) and roofed over: a room. */
    private static boolean enclosed(Map<BlockPos, BuildGoal.Placement> at, BlockPos c, int reach) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            boolean hit = false;
            for (int k = 1; k <= reach && !hit; k++) {
                BuildGoal.Placement n = at.get(c.relative(d, k));
                if (n == null) continue;
                if (wallish(n)) hit = true;
                else if (n.part() == BuildGoal.Part.BLOCK) break;
            }
            if (!hit) return false;
        }
        for (int k = 2; k <= 10; k++) if (at.containsKey(c.above(k))) return true;
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

    /** The cells the age's furnishing has (Interiors: its rug, barrel, pots, shelves, and the lamp and candles on them). */
    static Set<BlockPos> reserved(ServerLevel level, UUID village, Ledger.Building b) {
        Set<BlockPos> out = new HashSet<>();
        int tier = Interiors.tier(Villages.ageOf(village));
        if (tier <= 0) return out;
        for (Interiors.Piece p : Interiors.plan(level, village, b, tier)) out.add(p.pos());
        return out;
    }

    // ------------------------------------------------------------------ where a thing goes

    /** Where a thing goes: the cell, and the way it faces (out of the wall, into the room). */
    record Spot(BlockPos at, Direction facing) {}

    private static boolean sturdyUnder(Level level, BlockPos c) {
        BlockPos d = c.below();
        return level.getBlockState(d).isFaceSturdy(level, d, Direction.UP);
    }

    /** The side of a floor cell whose wall stands now, or null. */
    @Nullable
    private static Direction standingWall(Level level, Room room, BlockPos c) {
        for (Direction d : room.walls().getOrDefault(c, List.of())) {
            BlockPos w = c.relative(d);
            if (level.getBlockState(w).isFaceSturdy(level, w, d.getOpposite())) return d;
        }
        return null;
    }

    /** Can a body walk through here (a rug is nothing underfoot, a door opens)? */
    static boolean passable(Level level, BlockPos p) {
        BlockState feet = level.getBlockState(p), head = level.getBlockState(p.above());
        boolean f = feet.getCollisionShape(level, p).isEmpty() || feet.is(BlockTags.WOOL_CARPETS) || feet.getBlock() instanceof DoorBlock;
        boolean h = head.getCollisionShape(level, p.above()).isEmpty() || head.getBlock() instanceof DoorBlock;
        return f && h;
    }

    private static final int[][] RING = { { 0, -1 }, { 1, -1 }, { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 }, { -1, 0 }, { -1, -1 } };

    /**
     * Would something standing here cut the way through: are the ways in and out of this cell (its four
     * sides that can be walked) joined round it some other way? Counted round the eight cells about it: each
     * run of walkable cells is one way round; two runs that both have a side of this cell in them are two
     * parts of the room this cell joins, and a thing set down here would part them.
     */
    static boolean cutsTheWay(Level level, BlockPos c) {
        boolean[] w = new boolean[8];
        int any = -1;
        for (int i = 0; i < 8; i++) {
            w[i] = passable(level, c.offset(RING[i][0], 0, RING[i][1]));
            if (!w[i] && any < 0) any = i;
        }
        if (any < 0) return false;                               // open all round
        int runs = 0;
        boolean inRun = false, sideInRun = false;
        for (int k = 1; k <= 8; k++) {
            int i = (any + k) % 8;
            if (w[i]) {
                if (!inRun) { inRun = true; sideInRun = false; }
                if (i % 2 == 0) sideInRun = true;
            } else if (inRun) {
                if (sideInRun) runs++;
                inRun = false;
            }
        }
        if (inRun && sideInRun) runs++;
        return runs >= 2;
    }

    /**
     * A cell of floor against a standing wall for a thing that stands (an anvil, a barrel, an armour stand):
     * empty, on a sound floor, not kept clear, not the age's furnishing's, not by a door, and not where it
     * would cut the room in two. The nearest to {@code near} (the owner's bed), corners first. Null if none.
     */
    @Nullable
    static Spot floorSpot(ServerLevel level, Room room, BlockPos near, Set<BlockPos> taken) {
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : room.floor()) {
            if (!room.walls().containsKey(c) || room.clear().contains(c) || taken.contains(c)) continue;
            if (!level.isLoaded(c) || !level.getBlockState(c).isAir() || !sturdyUnder(level, c)) continue;
            Direction wall = standingWall(level, room, c);
            if (wall == null || byADoor(level, c)) continue;
            if (cutsTheWay(level, c)) continue;
            double score = c.distSqr(near) - 3.0 * room.walls().get(c).size();
            if (score < bestScore) { bestScore = score; best = new Spot(c.immutable(), wall.getOpposite()); }
        }
        return best;
    }

    private static boolean byADoor(Level level, BlockPos c) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(c.relative(d)).getBlock() instanceof DoorBlock) return true;
        }
        return false;
    }

    /**
     * The top of something standing in a room with room on it (a barrel, a table, the furnace, a chopping
     * block): a window's sill first, then the nearest to {@code near}. For a lamp, a candle, a pot of flowers.
     */
    @Nullable
    static Spot topSpot(ServerLevel level, Room room, BlockPos near, Set<BlockPos> taken) {
        Set<BlockPos> sills = new HashSet<>(room.sills().values());
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        List<BlockPos> stands = new ArrayList<>(room.floor());
        stands.addAll(room.furniture());
        for (BlockPos f : stands) {
            if (!level.isLoaded(f)) continue;
            BlockState under = level.getBlockState(f);
            if (under.isAir() || under.getBlock() instanceof BedBlock || !under.isFaceSturdy(level, f, Direction.UP)) continue;
            BlockPos top = f.above();
            if (taken.contains(top) || room.floorSet().contains(top) || !level.getBlockState(top).isAir()) continue;
            double score = top.distSqr(near) - (sills.contains(top) ? 1000.0 : 0.0);
            if (score < bestScore) {
                bestScore = score;
                Direction wall = standingWall(level, room, f);
                best = new Spot(top.immutable(), wall == null ? Direction.NORTH : wall.getOpposite());
            }
        }
        return best;
    }

    /**
     * A place on a wall at so many blocks above the floor for a frame, a picture or a banner: the air in
     * front of a solid face of wall, with nothing hung there already and not beside a door. Every one, the
     * nearest to {@code near} first.
     */
    static List<Spot> wallSpots(ServerLevel level, Room room, BlockPos near, int up, Set<BlockPos> taken) {
        List<Spot> out = new ArrayList<>();
        for (BlockPos f : room.floor()) {
            List<Direction> sides = room.walls().get(f);
            if (sides == null || !level.isLoaded(f) || byADoor(level, f) || byADoor(level, f.above())) continue;
            BlockPos h = f.above(up);
            if (taken.contains(h) || !level.getBlockState(h).isAir()) continue;
            if (!level.getEntitiesOfClass(HangingEntity.class, new AABB(h).inflate(0.05)).isEmpty()) continue;
            for (Direction d : sides) {
                BlockPos w = h.relative(d);
                BlockState ws = level.getBlockState(w);
                if (!ws.isFaceSturdy(level, w, d.getOpposite()) || ws.getBlock() instanceof DoorBlock) continue;
                out.add(new Spot(h.immutable(), d.getOpposite()));
            }
        }
        out.sort(Comparator.comparingDouble(s -> s.at().distSqr(near)));
        return out;
    }

    /**
     * A cell of floor for a rug: empty, on a sound floor, not the age's own rug's (its rug is laid by its
     * own rules), the nearest the bed. A rug is nothing underfoot, so the way in will do for it (but not the
     * doorway itself).
     */
    @Nullable
    static Spot rugSpot(ServerLevel level, Room room, BlockPos near, Set<BlockPos> taken) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : room.floor()) {
            if (taken.contains(c) || !level.isLoaded(c) || !level.getBlockState(c).isAir() || !sturdyUnder(level, c)) continue;
            if (room.door() != null && c.distManhattan(room.door()) <= 1) continue;
            double score = c.distSqr(near);
            if (score < bestScore) { bestScore = score; best = c; }
        }
        return best == null ? null : new Spot(best.immutable(), Direction.NORTH);
    }

    /** The windows standing open (built without glass, or broken since): for panes. */
    static List<BlockPos> openWindows(ServerLevel level, Room room) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos w : room.windows()) if (level.isLoaded(w) && level.getBlockState(w).isAir()) out.add(w);
        return out;
    }

    // ------------------------------------------------------------------ the house's book

    /** What has been set out in a house, and where: "key=pos;key=pos" in the village's ledger. */
    static Map<String, Long> book(UUID village, BlockPos anchor) {
        Map<String, Long> out = new LinkedHashMap<>();
        String s = Ledger.note(village, "decor/" + anchor.asLong());
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static void save(UUID village, BlockPos anchor, Map<String, Long> book) {
        if (book.isEmpty()) {
            Ledger.forget(village, "decor/" + anchor.asLong());
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : book.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        Ledger.note(village, "decor/" + anchor.asLong(), sb.toString());
    }

    /** Is the thing the book has at this spot still there (not broken, not carried off)? True if the ground there is not loaded. */
    static boolean present(ServerLevel level, String key, BlockPos pos) {
        if (!level.isLoaded(pos)) return true;
        BlockState s = level.getBlockState(pos);
        if (key.startsWith("lux.")) {
            String kind = key.split("\\.")[1];
            return switch (kind) {
                case "painting" -> !level.getEntitiesOfClass(Painting.class, new AABB(pos).inflate(2.0), p -> p.getPos().equals(pos)).isEmpty();
                case "rug" -> s.is(BlockTags.WOOL_CARPETS);
                case "pane" -> s.is(Blocks.GLASS_PANE) || s.is(Blocks.YELLOW_STAINED_GLASS_PANE);
                case "candle" -> s.getBlock() instanceof AbstractCandleBlock && s.is(BlockTags.CANDLES);
                case "pot" -> s.getBlock() instanceof FlowerPotBlock;
                case "lantern" -> s.getBlock() instanceof LanternBlock;
                case "banner" -> s.getBlock() instanceof WallBannerBlock;
                case "bookshelf" -> s.is(Blocks.BOOKSHELF);
                default -> !s.isAir();
            };
        }
        Piece p = piece(key);
        if (p == null) return false;
        return switch (p.shape()) {
            case FRAME -> !level.getEntitiesOfClass(ItemFrame.class, new AABB(pos), e -> e.getTags().contains(TAG)).isEmpty();
            case STAND -> !level.getEntitiesOfClass(ArmorStand.class, new AABB(pos).inflate(0.2), e -> e.getTags().contains(TAG)).isEmpty();
            case BANNER -> s.getBlock() instanceof WallBannerBlock && p.what().test(new ItemStack(s.getBlock().asItem()));
            default -> !s.isAir() && p.what().test(new ItemStack(s.getBlock().asItem()));
        };
    }

    /** The tag on a frame or a stand the village set out in a house. */
    static final String TAG = "mca_decor";

    // ------------------------------------------------------------------ wants on the stores

    /** Something a house waits on the stores for: what, for which house and whose trade, in words. */
    public record Want(Item item, String forWhat) {}

    private static final Map<UUID, Map<String, Want>> WANTS = new ConcurrentHashMap<>();

    /** What the village's houses wait on the stores for (the makers' list: Luxuries.craft). */
    public static List<Want> wants(UUID village) {
        Map<String, Want> m = WANTS.get(village);
        return m == null ? List.of() : List.copyOf(m.values());
    }

    private static void want(UUID village, BlockPos anchor, Piece p, Item item, String where) {
        WANTS.computeIfAbsent(village, k -> new ConcurrentHashMap<>())
            .putIfAbsent(anchor.asLong() + "/" + p.key(), new Want(item, p.words() + " at " + where));
    }

    private static void wanted(UUID village, BlockPos anchor, String key) {
        Map<String, Want> m = WANTS.get(village);
        if (m != null) m.remove(anchor.asLong() + "/" + key);
    }

    // ------------------------------------------------------------------ the work

    /**
     * The age's furnishing first (Interiors), then this: so many things set out or cleared away at most, by
     * day (a hand is not called out at night for a piece of furniture, as with all the town's work).
     * Returns how many.
     */
    public static synchronized int work(ServerLevel level, Villages.Village v, int budget) {
        UUID id = v.id();
        if (Interiors.tier(Villages.ageOf(id)) == 0 || budget <= 0) return 0;
        long t = level.getDayTime() % 24000L;
        if (!anyHour && t >= 12500L && t < 23500L) return 0;
        int done = 0;
        long now = level.getGameTime();
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            if (h.members.isEmpty()) continue;
            Long next = LOOKED.get(h.anchor.asLong());
            if (next != null && now < next && now >= next - 1200L) continue;
            Ledger.Building b = Homes.building(id, h.anchor);
            if (b == null || Ledger.raising(id, b.anchor()) || !Land.areaLoaded(level, b.anchor(), 10)) continue;
            List<VillageFolkEntity> household = Homes.loadedMembers(id, h);
            if (household.isEmpty()) continue;
            int[] got = furnish(level, v, h, b, household, budget - done);
            done += got[0];
            // A house with nothing more it can be given just now is looked at again in a minute.
            if (got[0] == 0 && got[1] == 0) LOOKED.put(h.anchor.asLong(), now + 1200L);
            else LOOKED.remove(h.anchor.asLong());
            if (got[1] != 0 || done >= budget) break;              // the hand is busy elsewhere, or the turn is spent
        }
        return done;
    }

    /** When a house that had nothing more to be given is next looked at (by anchor). */
    private static final Map<Long, Long> LOOKED = new ConcurrentHashMap<>();

    /** Furnishing now, whatever the hour, a hand at every spot at once (/village decor now, the showcase). */
    private static boolean anyHour;

    /** Every home furnished now, as far as the stores run to, whatever the hour (/village decor now, the showcase). */
    public static synchronized int workNow(ServerLevel level, Villages.Village v, int budget) {
        anyHour = true;
        LOOKED.clear();
        TownJobs.instantForTests(true);
        try {
            return work(level, v, budget);
        } finally {
            anyHour = false;
            TownJobs.instantForTests(false);
        }
    }

    /** One house's turn: {things done, 1 if it had to stop (no hand free)}. */
    private static int[] furnish(ServerLevel level, Villages.Village v, Homes.Home h, Ledger.Building b,
                                 List<VillageFolkEntity> household, int budget) {
        UUID id = v.id();
        Room room = room(id, b);
        Map<String, Long> book = book(id, h.anchor);
        book.entrySet().removeIf(e -> !present(level, e.getKey(), BlockPos.of(e.getValue())));
        List<Piece> wanted = wanted(household);
        Set<String> keys = new HashSet<>();
        for (Piece p : wanted) keys.add(p.key());
        String where = Homes.address(id, v, h);
        int done = 0;
        // What the household before left: back into the stores for the next.
        for (Map.Entry<String, Long> e : new ArrayList<>(book.entrySet())) {
            if (e.getKey().startsWith("lux.") || keys.contains(e.getKey())) continue;
            if (done >= budget) { save(id, h.anchor, book); return new int[]{ done, 0 }; }
            BlockPos at = BlockPos.of(e.getValue());
            if (!TownJobs.atWork(level, v, "interiors", at, "clearing the last household's things out of " + where)) {
                save(id, h.anchor, book);
                return new int[]{ done, 1 };
            }
            takeDown(level, v, e.getKey(), at);
            book.remove(e.getKey());
            done++;
        }
        Set<BlockPos> taken = reserved(level, id, b);
        for (Long p : book.values()) taken.add(BlockPos.of(p));
        // Its own things for each trade, near the bed of the folk that works it.
        for (Piece p : wanted) {
            if (book.containsKey(p.key())) { wanted(id, h.anchor, p.key()); continue; }
            if (done >= budget) break;
            BlockPos near = nearFor(household, p, h.anchor);
            Spot spot = spotFor(level, room, p, near, taken);
            if (spot == null) { wanted(id, h.anchor, p.key()); continue; }            // no room for it: a small house shows less
            // [econ-prices] A house its household owns is furnished at its own cost once the town has a shop (Purchases);
            // short of the coin, the thing waits. One the town lets, it furnishes as its landlord.
            int cost = Purchases.homePrice(level, id, h.tenure == Homes.Tenure.OWNED, new ItemStack(p.item()));
            if (cost > 0 && Homes.purses(household) < cost) continue;
            List<ItemStack> got = obtain(level, v, h, p, where);
            if (got == null) continue;
            if (!TownJobs.atWork(level, v, "interiors", spot.at(), "setting out " + p.words() + " in " + where)) {
                for (ItemStack s : got) if (!s.isEmpty()) Crafts.store(level, v, s);
                save(id, h.anchor, book);
                return new int[]{ done, 1 };
            }
            if (!put(level, spot, p, got)) {
                for (ItemStack s : got) if (!s.isEmpty()) Crafts.store(level, v, s);
                continue;
            }
            wanted(id, h.anchor, p.key());
            if (cost > 0 && Homes.pay(household, cost)) {
                com.jrpetty.mcassistant.village.Ledger.addCoins(id, cost);
                Purchases.homeBought(level, id, new ItemStack(p.item()), cost);    // [econ-prices]
            }
            book.put(p.key(), spot.at().asLong());
            taken.add(spot.at());
            done++;
        }
        save(id, h.anchor, book);
        remember(level, id, h, b, book);
        return new int[]{ done, 0 };
    }

    /** Whose bed a thing goes by: the folk of its trade (a colour, the owner's), else the house. */
    private static BlockPos nearFor(List<VillageFolkEntity> household, Piece p, BlockPos anchor) {
        for (VillageFolkEntity f : household) {
            if (f.isBaby() || f.bedPos() == null) continue;
            if (p.trade() == null || f.stationTask() == p.trade()) return f.bedPos();
        }
        return anchor;
    }

    @Nullable
    private static Spot spotFor(ServerLevel level, Room room, Piece p, BlockPos near, Set<BlockPos> taken) {
        return switch (p.shape()) {
            case FLOOR, BARREL, STAND -> floorSpot(level, room, near, taken);
            case LAMP -> {
                Spot top = topSpot(level, room, near, taken);
                yield top != null ? top : floorSpot(level, room, near, taken);
            }
            case FRAME -> {
                List<Spot> all = wallSpots(level, room, near, 1, taken);
                yield all.isEmpty() ? null : all.get(0);
            }
            case RUG -> rugSpot(level, room, near, taken);
            case BANNER -> {
                List<Spot> high = wallSpots(level, room, near, 2, taken);
                if (!high.isEmpty()) yield high.get(0);
                List<Spot> low = wallSpots(level, room, near, 1, taken);
                yield low.isEmpty() ? null : low.get(0);
            }
        };
    }

    /**
     * The thing out of the stores, with what goes in it: the plain joinery made there and then of the stores'
     * makings if there is none; a workstation only when its trade's own building has one standing already; a
     * tool or the barrel's goods only out of what the village has over what it keeps. Null if the stores
     * cannot run to it yet: the house waits for it, a want on the stores.
     */
    @Nullable
    private static List<ItemStack> obtain(ServerLevel level, Villages.Village v, Homes.Home h, Piece p, String where) {
        UUID id = v.id();
        if (!canSpare(level, v, p)) {
            want(id, h.anchor, p, p.item(), where);
            return null;
        }
        ItemStack inside = ItemStack.EMPTY;
        if (p.inside() != null) {
            inside = overKept(level, v, p.insideWhat(), p.insideCount());
            if (inside.isEmpty() && whittled(p.inside())) {
                // None to spare: a plain one of wood (the pick it learned on), whittled of the stores' planks for the wall.
                Bench.Hand hand = Bench.handOf(level, v, null, null);
                Bench.Plan plan = Bench.plan(level, v, p.inside(), 1, hand);
                Item it = p.inside();
                if (plan.ok() && !Bench.make(level, v, plan, null, hand).isEmpty() && Crafts.take(level, v, s -> s.is(it), 1)) {
                    inside = new ItemStack(it);
                }
            }
            if (inside.isEmpty()) {
                want(id, h.anchor, p, p.inside(), where);
                return null;
            }
        }
        ItemStack thing = Crafts.takeOne(level, v, p.what());
        if (thing.isEmpty() && p.plain()) {
            // Plain joinery: made on the spot, the whole way from the stores (Bench), by the hand at the work.
            Bench.Hand hand = Bench.handOf(level, v, null, null);
            Bench.Plan plan = Bench.plan(level, v, p.item(), 1, hand);
            if (plan.ok() && !Bench.make(level, v, plan, null, hand).isEmpty()) thing = Crafts.takeOne(level, v, p.what());
        }
        if (thing.isEmpty()) {
            if (!inside.isEmpty()) Crafts.store(level, v, inside);
            want(id, h.anchor, p, p.item(), where);
            return null;
        }
        List<ItemStack> out = new ArrayList<>();
        out.add(thing);
        out.add(inside);
        return out;
    }

    /** What the hand furnishing a house may make there and then of plain wood and string for a frame on the wall. */
    private static boolean whittled(Item it) {
        return it == Items.WOODEN_PICKAXE || it == Items.WOODEN_AXE || it == Items.FISHING_ROD || it == Items.BOW;
    }

    /** A trade's own workstation is the trade's first: a house has one only once its building has its own, or the stores have two. */
    private static boolean canSpare(ServerLevel level, Villages.Village v, Piece p) {
        String works = switch (p.key()) {
            case "smith.anvil" -> "smithy";
            case "tailor.loom" -> "workshop";
            case "cook.smoker" -> "cafe";
            case "brew.stand" -> "brewery";
            default -> null;
        };
        if (works != null && Villages.hasBuilt(v.id(), works)) {
            Block block = Block.byItem(p.item());
            if (!Bench.standsIn(level, v, works, block) && Market.stock(level, v.id(), p.what()) < 2) return false;
        }
        // A log for a chopping block, never out of the builders' working timber.
        if (p.key().equals("wood.block") && Market.stock(level, v.id(), s -> s.is(ItemTags.LOGS)) <= 16) return false;
        if (p.key().equals("enchant.shelf")) {
            for (Ledger.Building lb : Ledger.buildings(v.id())) {
                if (lb.structure().equals("library") && level.isLoaded(lb.anchor()) && !Crafts.emptyShelves(level, v, lb).isEmpty()) return false;
            }
        }
        return true;
    }

    /**
     * Up to so many of what matches, out of what the stores hold over what the village keeps of it (Budget):
     * the cheapest kind first (an old stone pick for the wall before an iron one). Empty if there is none.
     */
    static ItemStack overKept(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, int most) {
        List<ItemStack> kinds = new ArrayList<>();
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                boolean seen = false;
                for (ItemStack k : kinds) if (ItemStack.isSameItemSameComponents(k, s)) { seen = true; break; }
                if (!seen) kinds.add(s.copyWithCount(1));
            }
        }
        kinds.sort(Comparator.comparingDouble(s -> Prices.each(s.getItem()) * (s.isDamaged() ? 0.5 : 1.0)));
        for (ItemStack k : kinds) {
            int held = Market.stock(level, v.id(), s -> ItemStack.isSameItemSameComponents(s, k));
            int over = held - Budget.keep(level, v.id(), k);
            if (over <= 0) continue;
            int n = Math.min(most, over);
            if (Crafts.take(level, v, s -> ItemStack.isSameItemSameComponents(s, k), n)) return k.copyWithCount(n);
        }
        return ItemStack.EMPTY;
    }

    /** Set a thing out at its spot. False (nothing changed) if it will not go. */
    private static boolean put(ServerLevel level, Spot spot, Piece p, List<ItemStack> got) {
        ItemStack thing = got.get(0), inside = got.size() > 1 ? got.get(1) : ItemStack.EMPTY;
        BlockPos at = spot.at();
        switch (p.shape()) {
            case FLOOR, LAMP -> {
                Block block = Block.byItem(thing.getItem());
                if (block == Blocks.AIR) return false;
                BlockState st = facing(block.defaultBlockState(), spot.facing());
                if (!st.canSurvive(level, at)) return false;
                level.setBlock(at, st, 3);
                sound(level, at, st);
            }
            case BARREL -> {
                BlockState st = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
                level.setBlock(at, st, 3);
                if (!inside.isEmpty() && level.getBlockEntity(at) instanceof net.minecraft.world.Container c) {
                    c.setItem(0, inside.copy());
                    c.setChanged();
                }
                sound(level, at, st);
            }
            case FRAME -> {
                ItemFrame frame = new ItemFrame(level, at, spot.facing());
                if (!frame.survives()) return false;
                frame.setItem(inside.copy(), false);
                frame.addTag(TAG);
                level.addFreshEntity(frame);
                frame.playPlacementSound();
            }
            case STAND -> {
                ArmorStand stand = EntityType.ARMOR_STAND.create(level);
                if (stand == null) return false;
                stand.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, spot.facing().toYRot(), 0.0F);
                stand.addTag(TAG);
                level.addFreshEntity(stand);
                level.playSound(null, at, net.minecraft.sounds.SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            }
            case RUG -> {
                Block block = Block.byItem(thing.getItem());
                if (block == Blocks.AIR) return false;
                level.setBlock(at, block.defaultBlockState(), 3);
                sound(level, at, block.defaultBlockState());
            }
            case BANNER -> {
                if (!hangBanner(level, at, spot.facing(), thing)) return false;
            }
        }
        return true;
    }

    /** A banner on the wall, its patterns and all. */
    static boolean hangBanner(ServerLevel level, BlockPos at, Direction facing, ItemStack banner) {
        DyeColor c = banner.getItem() instanceof net.minecraft.world.item.BannerItem bi ? bi.getColor() : DyeColor.WHITE;
        Block wall = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(c.getName() + "_wall_banner"));
        if (!(wall instanceof WallBannerBlock)) return false;
        BlockState st = wall.defaultBlockState().setValue(WallBannerBlock.FACING, facing);
        if (!st.canSurvive(level, at)) return false;
        level.setBlock(at, st, 3);
        if (level.getBlockEntity(at) instanceof BannerBlockEntity be) {
            be.applyComponentsFromItemStack(banner);
            be.setChanged();
        }
        sound(level, at, st);
        return true;
    }

    private static void sound(ServerLevel level, BlockPos at, BlockState st) {
        level.playSound(null, at, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
    }

    /** A block turned to face into the room from its wall; an anvil lies along the wall; a log stands up. */
    static BlockState facing(BlockState s, Direction out) {
        if (s.getBlock() instanceof AnvilBlock) return s.setValue(AnvilBlock.FACING, out.getClockWise());
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.setValue(BlockStateProperties.HORIZONTAL_FACING, out);
        if (s.hasProperty(BlockStateProperties.AXIS)) return s.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
        return s;
    }

    /** A thing the last household had, cleared back into the stores: the block or the frame, and what was in it. */
    private static void takeDown(ServerLevel level, Villages.Village v, String key, BlockPos at) {
        if (!level.isLoaded(at)) return;
        Piece p = piece(key);
        if (p != null && p.shape() == Shape.FRAME) {
            for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(at), e -> e.getTags().contains(TAG))) {
                if (!f.getItem().isEmpty()) Crafts.store(level, v, f.getItem().copy());
                Crafts.store(level, v, new ItemStack(Items.ITEM_FRAME));
                f.discard();
            }
            return;
        }
        if (p != null && p.shape() == Shape.STAND) {
            for (ArmorStand s : level.getEntitiesOfClass(ArmorStand.class, new AABB(at).inflate(0.2), e -> e.getTags().contains(TAG))) {
                Crafts.store(level, v, new ItemStack(Items.ARMOR_STAND));
                s.discard();
            }
            return;
        }
        BlockState s = level.getBlockState(at);
        if (s.isAir()) return;
        if (level.getBlockEntity(at) instanceof net.minecraft.world.Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack in = c.getItem(i);
                if (!in.isEmpty()) Crafts.store(level, v, in.copy());
            }
            c.clearContent();
        }
        Item it = s.getBlock().asItem();
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        if (it != Items.AIR) Crafts.store(level, v, new ItemStack(it));
    }

    // ------------------------------------------------------------------ how well a home is furnished

    /**
     * How well a home is furnished, out of ten: the age's furnishing (two), the things of its trades (two),
     * its colours (two) and the luxuries its folk bought for it (four, a point a kind). What of that the
     * village put in (the furnishing, the trades', the colours) it counts in the house's price. With the
     * words for it ("a blue rug, the smith's anvil, two paintings, candles lit") and what it waits on.
     */
    public record Furnished(int score, int fitted, String line, String waiting) {}

    private static final Map<String, Furnished> SCORES = new ConcurrentHashMap<>();

    public static synchronized void resetForTests() {
        ROOMS.clear();
        WANTS.clear();
        SCORES.clear();
        LOOKED.clear();
        BY_KEY = null;
    }

    /** Count a house up afresh and keep the count (the price, the folk's spirits, the books read it). */
    static Furnished remember(ServerLevel level, UUID village, Homes.Home h, Ledger.Building b, Map<String, Long> book) {
        Furnished f = count(level, village, h, b, book);
        SCORES.put(village + "/" + h.anchor.asLong(), f);
        return f;
    }

    /** The last count of a house (none yet: a bare one). */
    @Nullable
    static Furnished known(UUID village, BlockPos anchor) {
        return SCORES.get(village + "/" + anchor.asLong());
    }

    static Furnished count(ServerLevel level, UUID village, Homes.Home h, Ledger.Building b, Map<String, Long> book) {
        int[] age = Interiors.progress(level, village, b);
        int furnishing = age[1] > 0 && age[0] >= age[1] ? 2 : age[0] > 0 ? 1 : 0;
        List<String> trades = new ArrayList<>();
        Map<String, Integer> colours = new LinkedHashMap<>();
        Map<String, Integer> lux = new LinkedHashMap<>();
        int lit = 0;
        for (Map.Entry<String, Long> e : book.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("lux.")) {
                String kind = key.split("\\.")[1];
                lux.merge(kind, 1, Integer::sum);
                if (kind.equals("candle")) {
                    BlockPos at = BlockPos.of(e.getValue());
                    BlockState s = level.isLoaded(at) ? level.getBlockState(at) : Blocks.AIR.defaultBlockState();
                    if (s.getBlock() instanceof AbstractCandleBlock && AbstractCandleBlock.isLit(s)) lit++;
                }
                continue;
            }
            Piece p = piece(key);
            if (p == null) continue;
            if (key.startsWith("colour.")) {
                String[] k = key.split("\\.");
                colours.merge(k[2].replace('_', ' '), k[1].equals("rug") ? 1 : 2, Integer::sum);
            } else {
                trades.add(p.words());
            }
        }
        int tradePts = Math.min(2, trades.size());
        int colourPts = 0;
        for (int c : colours.values()) colourPts += c == 3 ? 2 : 1;
        colourPts = Math.min(2, colourPts);
        int luxPts = Math.min(4, lux.size());
        int fitted = furnishing + tradePts + colourPts;
        int score = Math.min(10, fitted + luxPts);
        List<String> words = new ArrayList<>();
        for (Map.Entry<String, Integer> c : colours.entrySet()) {
            words.add(c.getValue() == 3 ? "a " + c.getKey() + " rug and banner" : c.getValue() == 1 ? "a " + c.getKey() + " rug"
                : "a " + c.getKey() + " banner");
        }
        words.addAll(trades);
        for (Map.Entry<String, Integer> e : lux.entrySet()) words.add(luxWords(e.getKey(), e.getValue(), lit));
        StringBuilder waiting = new StringBuilder();
        Map<String, Want> w = WANTS.get(village);
        if (w != null) {
            for (Map.Entry<String, Want> e : w.entrySet()) {
                if (!e.getKey().startsWith(h.anchor.asLong() + "/")) continue;
                if (waiting.length() > 0) waiting.append(", ");
                waiting.append(Bench.words(e.getValue().item(), 1));
            }
        }
        return new Furnished(score, fitted, String.join(", ", words), waiting.toString());
    }

    private static final String[] NUMBERS = { "no", "a", "two", "three", "four", "five", "six", "seven", "eight" };

    static String number(int n) {
        return n >= 0 && n < NUMBERS.length ? NUMBERS[n] : Integer.toString(n);
    }

    /** "two paintings", "candles lit", "glass in the windows". */
    private static String luxWords(String kind, int n, int lit) {
        return switch (kind) {
            case "rug" -> n == 1 ? "a carpet" : number(n) + " carpets";
            case "painting" -> n == 1 ? "a painting" : number(n) + " paintings";
            case "pane" -> "glass in the windows";
            case "candle" -> lit > 0 ? (n == 1 ? "a candle lit" : "candles lit") : n == 1 ? "a candle" : number(n) + " candles";
            case "pot" -> n == 1 ? "a pot of flowers" : number(n) + " pots of flowers";
            case "lantern" -> n == 1 ? "a lantern" : number(n) + " lanterns";
            case "banner" -> n == 1 ? "a banner" : number(n) + " banners";
            case "bookshelf" -> n == 1 ? "a bookshelf" : number(n) + " bookshelves";
            default -> n + " " + kind;
        };
    }

    /** How a house is furnished now, counted afresh (the folk's card and the books). */
    @Nullable
    static Furnished of(ServerLevel level, UUID village, Homes.Home h) {
        Ledger.Building b = Homes.building(village, h.anchor);
        if (b == null || !level.isLoaded(h.anchor)) return known(village, h.anchor);
        Map<String, Long> book = book(village, h.anchor);
        book.entrySet().removeIf(e -> !present(level, e.getKey(), BlockPos.of(e.getValue())));
        return remember(level, village, h, b, book);
    }

    // ------------------------------------------------------------------ what it does for a home

    /** A furnished home is worth more: three in the hundred on its price for each point the village put in. */
    public static int pricePercent(UUID village, BlockPos anchor) {
        Furnished f = known(village, anchor);
        return f == null ? 100 : 100 + 3 * f.fitted();
    }

    /** A home to be proud of lifts its folk's spirits: a little for a well-kept one, more for a fine one. */
    public static int moodBonus(VillageFolkEntity f) {
        UUID village = f.ownerId();
        BlockPos home = village == null ? null : Homes.homeOf(f);
        Furnished h = home == null ? null : known(village, home);
        if (h == null) return 0;
        return h.score() >= 7 ? 4 : h.score() >= 4 ? 2 : 0;
    }

    /** Why a folk is happy at home, in its own words. */
    public static String moodWords(VillageFolkEntity f) {
        UUID village = f.ownerId();
        BlockPos home = village == null ? null : Homes.homeOf(f);
        Furnished h = home == null ? null : known(village, home);
        if (h == null || h.line().isEmpty()) return "I've a home I'm proud of.";
        return FolkTalk.pick(f.getRandom(), "My home's a picture: " + h.line() + ".",
            "You should see my house — " + h.line() + ".", "Home's lovely just now: " + h.line() + ".");
    }

    /** Its card's line: "a blue rug and banner, the smith's anvil, two paintings, candles lit; blue's its colour". */
    public static String cardLine(VillageFolkEntity f) {
        if (f.isBaby()) return "";
        String colour = colourWord(colour(f)) + " is its colour";
        UUID village = f.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, f.getUUID());
        if (h == null || !(f.level() instanceof ServerLevel level)) return colour;
        Furnished fu = of(level, village, h);
        if (fu == null || fu.line().isEmpty()) return "nothing of its own at home yet; " + colour;
        return fu.line() + " (furnished " + fu.score() + " of 10)" + (fu.waiting().isEmpty() ? "" : "; waiting on " + fu.waiting())
            + "; " + colour;
    }

    // ------------------------------------------------------------------ the books

    /**
     * For the town's books: each house with a household — how it is furnished out of ten, what of that the
     * village put in, what is in it, its colours, what it waits on — and the village's wants, its luxuries
     * bought this week by its folk for their homes, and how many homes are well furnished.
     */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        ListTag houses = new ListTag();
        int n = 0, sum = 0, well = 0, bare = 0;
        for (Homes.Home h : Homes.homes(village).values()) {
            if (h.members.isEmpty()) continue;
            Furnished f = of(level, village, h);
            CompoundTag t = new CompoundTag();
            t.putLong("anchor", h.anchor.asLong());
            t.putInt("score", f == null ? 0 : f.score());
            t.putInt("fitted", f == null ? 0 : f.fitted());
            t.putString("line", f == null ? "" : f.line());
            t.putString("waiting", f == null ? "" : f.waiting());
            List<String> colours = new ArrayList<>();
            for (VillageFolkEntity m : Homes.loadedMembers(village, h)) if (!m.isBaby()) colours.add(m.displayNameCap() + " " + colourWord(colour(m)));
            t.putString("colours", String.join(", ", colours));
            houses.add(t);
            n++;
            int s = f == null ? 0 : f.score();
            sum += s;
            if (s >= 7) well++;
            if (s == 0) bare++;
        }
        out.put("houses", houses);
        out.putInt("homes", n);
        out.putInt("avg10", n == 0 ? 0 : Math.round(sum * 10f / n));
        out.putInt("well", well);
        out.putInt("bare", bare);
        ListTag wants = new ListTag();
        for (Want w : wants(village)) {
            if (wants.size() >= 12) break;
            wants.add(StringTag.valueOf(Bench.words(w.item(), 1) + " for " + w.forWhat()));
        }
        out.put("wants", wants);
        int[] bought = Luxuries.boughtThisWeek(level, village);
        out.putInt("bought7", bought[0]);
        out.putInt("coin7", bought[1]);
        out.putInt("boughtToday", bought[2]);
        return out;
    }

    /** /village decor: a line a house. */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Homes.Home h : Homes.homes(v.id()).values()) {
            if (h.members.isEmpty()) continue;
            Furnished f = of(level, v.id(), h);
            List<String> who = new ArrayList<>();
            for (VillageFolkEntity m : Homes.loadedMembers(v.id(), h)) {
                if (!m.isBaby()) who.add(m.displayNameCap() + " (" + m.stationTask().title.toLowerCase(Locale.ROOT) + ", " + colourWord(colour(m)) + ")");
            }
            out.add(Homes.address(v.id(), v, h) + ": " + String.join(" and ", who) + " — furnished " + (f == null ? 0 : f.score()) + "/10"
                + (f == null || f.line().isEmpty() ? "" : ": " + f.line()) + (f == null || f.waiting().isEmpty() ? "" : "; waiting on " + f.waiting()));
        }
        return out;
    }
}
