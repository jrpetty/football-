package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.TownLife;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Every building a village can raise, set out to be looked at (/village showcase).
 *
 * <p>{@code buildings}: each building on a lot of its own on a flat stage, in a row,
 * its door to the south. {@code town}: a whole town laid out to the plan (TownPlan)
 * on a flat stage — the square, the wall, the streets and their lamps, and every
 * kind of building on the lots the plan would give it, the way a grown village
 * looks. Built out of a chosen palette, not out of anybody's stores: this is for
 * the eye, and for the pictures the build takes of itself.
 */
public final class Showcase {

    private Showcase() {}

    /** The buildings, in the order they are shown. */
    public static final List<String> ORDER = List.of(
        "house", "guesthouse", "storage", "shelter", "well", "smeltery", "workshop", "granary",
        "market", "watchtower", "lighthouse", "monument", "gateway", "hall", "chapel", "barracks",
        "smithy", "brewery", "library", "cafe", "shop", "tavern", "graveyard", "house2");

    /** A palette: the woods and stones a building is made of. */
    public record Palette(Block walls, Block frame, Block roofStair, Block roofSlab, Block roofBlock, Block floor,
                          Block door, Block fence, Block gate, Block bed, Block carpet) {}

    public static final Palette OAK = new Palette(Blocks.OAK_PLANKS, Blocks.SPRUCE_LOG, Blocks.DARK_OAK_STAIRS,
        Blocks.DARK_OAK_SLAB, Blocks.DARK_OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE,
        Blocks.SPRUCE_FENCE_GATE, Blocks.RED_BED, Blocks.RED_CARPET);
    public static final Palette BIRCH = new Palette(Blocks.BIRCH_PLANKS, Blocks.DARK_OAK_LOG, Blocks.SPRUCE_STAIRS,
        Blocks.SPRUCE_SLAB, Blocks.SPRUCE_PLANKS, Blocks.OAK_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.DARK_OAK_FENCE,
        Blocks.DARK_OAK_FENCE_GATE, Blocks.BLUE_BED, Blocks.BLUE_CARPET);
    public static final Palette SPRUCE = new Palette(Blocks.SPRUCE_PLANKS, Blocks.STRIPPED_OAK_LOG, Blocks.BRICK_STAIRS,
        Blocks.BRICK_SLAB, Blocks.BRICKS, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE,
        Blocks.OAK_FENCE_GATE, Blocks.GREEN_BED, Blocks.GREEN_CARPET);
    public static final Palette[] PALETTES = { OAK, BIRCH, SPRUCE };

    private static final Block[] FLOWERS = { Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.ALLIUM,
        Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY };

    /** What goes in a block of a building, for this palette. */
    public static Function<BuildGoal.Placement, BlockState> painter(Palette p) {
        return c -> {
            Block b = switch (c.part()) {
                case BLOCK -> switch (c.style()) {
                    case FOUNDATION, WALL_LOW, GENERIC -> Blocks.COBBLESTONE;
                    case MASONRY -> Blocks.STONE_BRICKS;
                    case BRICK -> Blocks.BRICKS;
                    case FLOOR -> p.floor();
                    case WALL -> p.walls();
                    case ROOF_BLOCK -> p.roofBlock();
                    case POST, BEAM_ACROSS, BEAM_ALONG -> p.frame();
                    case ROOF_STAIR, ROOF_STAIR_TOP -> p.roofStair();
                    case ROOF_SLAB, ROOF_SLAB_TOP -> p.roofSlab();
                    case STONE_SLAB -> Blocks.STONE_BRICK_SLAB;
                    case STONE_STAIR -> Blocks.STONE_BRICK_STAIRS;
                    case SOIL -> Blocks.GRASS_BLOCK;
                    default -> Blocks.COBBLESTONE;
                };
                case WINDOW -> c.style() == Blueprints.Style.GLASS ? Blocks.GLASS : Blocks.GLASS_PANE;
                case DOOR -> p.door();
                case FENCE -> p.fence();
                case GATE -> p.gate();
                case CHEST -> Blocks.CHEST;
                case FURNACE -> Blocks.FURNACE;
                case CRAFTING_TABLE -> Blocks.CRAFTING_TABLE;
                case TORCH -> Blocks.TORCH;
                case LADDER -> Blocks.LADDER;
                case BED -> p.bed();
                case OBSIDIAN -> Blocks.OBSIDIAN;
                case LANTERN -> Blocks.LANTERN;
                case WATER -> Blocks.WATER;
                case HAY -> Blocks.HAY_BLOCK;
                case BARREL -> Blocks.BARREL;
                case FLOWER -> FLOWERS[Math.floorMod(c.pos().hashCode(), FLOWERS.length)];
                case CARPET -> p.carpet();
                case ANVIL -> Blocks.ANVIL;
                case CAULDRON -> Blocks.CAULDRON;
                case BELL -> Blocks.BELL;
                case BOOKSHELF -> Blocks.BOOKSHELF;
                case LECTERN -> Blocks.LECTERN;
                case ENCHANTING -> Blocks.ENCHANTING_TABLE;
                case BREWING -> Blocks.BREWING_STAND;
                case SMOKER -> Blocks.SMOKER;
                case LOOM -> Blocks.LOOM;
                case GRINDSTONE -> Blocks.GRINDSTONE;
                case CAMPFIRE -> Blocks.CAMPFIRE;
                case NOTE_BLOCK -> Blocks.NOTE_BLOCK;
                case CLEAR -> null;
            };
            return b == null ? null : b.defaultBlockState();
        };
    }

    /** A flat stage: grass at {@code y - 1}, clear air above it to {@code y + 24}. */
    public static void stage(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                level.setBlock(new BlockPos(x, y - 2, z), Blocks.DIRT.defaultBlockState(), 2 | 16);
                level.setBlock(new BlockPos(x, y - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
                for (int h = 0; h <= 24; h++) {
                    BlockPos p = new BlockPos(x, y + h, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
    }

    /**
     * Every building in a row going east from {@code start}, each on its own lot, door to
     * the south. Returns a line per building: "name x y z", the building's centre.
     */
    public static List<String> buildings(ServerLevel level, BlockPos start) {
        List<String> out = new ArrayList<>();
        int x = start.getX();
        int y = start.getY();
        int z = start.getZ();
        int k = 0;
        for (String name : ORDER) {
            int[] half = Blueprints.fullHalf(name);
            int across = half[0];
            x += across + 3;
            stage(level, x - across - 3, x + across + 3, z - half[1] - 4, z + half[1] + 4, y);
            BlockPos at = new BlockPos(x, y, z);
            BuildGoal.stamp(level, name, at, Direction.NORTH, 13, painter(PALETTES[k++ % PALETTES.length]));
            out.add(name + " " + at.getX() + " " + at.getY() + " " + at.getZ());
            x += across + 3;
        }
        return out;
    }

    /**
     * A whole town, to the plan, centred on {@code heart}: the square paved and walled, the
     * streets laid and lit, and on its lots the buildings a grown village has. Returns how
     * many buildings went up.
     */
    public static int town(ServerLevel level, BlockPos heart) {
        int reach = TownPlan.RING + 2 * TownPlan.PERIOD + 4;
        int y = heart.getY();
        stage(level, heart.getX() - reach, heart.getX() + reach, heart.getZ() - reach, heart.getZ() + reach, y);
        // The square and the streets.
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                BlockPos ground = heart.offset(dx, -1, dz);
                if (TownPlan.isSquare(dx, dz)) {
                    boolean edge = (Math.abs(dx) + Math.abs(dz)) % 2 == 0;
                    level.setBlock(ground, (edge ? Blocks.STONE_BRICKS : Blocks.POLISHED_ANDESITE).defaultBlockState(), 2 | 16);
                } else if (TownPlan.isStreet(dx, dz)) {
                    boolean avenue = Math.abs(dx) <= TownPlan.AVENUE || Math.abs(dz) <= TownPlan.AVENUE;
                    level.setBlock(ground, (avenue ? Blocks.COBBLESTONE : Blocks.DIRT_PATH).defaultBlockState(), 2 | 16);
                }
            }
        }
        int n = 0;
        // The wall round the square, with its gates; the well and a monument on it.
        BuildGoal.stamp(level, "fortify", heart, Direction.NORTH, TownPlan.PLAZA, painter(OAK));
        for (TownPlan.Lot s : TownPlan.squareSpots()) {
            if (s.use().equals("well") || (s.use().equals("monument") && s.z() < 0)) {
                BuildGoal.stamp(level, s.use(), heart.offset(s.x(), 0, s.z()),
                    com.jrpetty.mcassistant.entity.Villages.direction(s.back()), 13, painter(OAK));
                n++;
            }
        }
        // The founders' camp, still about the heart, and the stores.
        level.setBlock(heart, Blocks.CHEST.defaultBlockState(), 3);
        VillageSpawner.pitchCamp(level, heart, 8);
        // Lamps along the avenues and the ring street.
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (!lamp(dx, dz)) continue;
                BlockPos p = heart.offset(dx, 0, dz);
                level.setBlock(p, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                level.setBlock(p.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                level.setBlock(p.above(2), Blocks.LANTERN.defaultBlockState(), 3);
            }
        }
        // The buildings, each where the plan puts it.
        STAGED.clear();
        VIEWS.clear();
        java.util.Set<Long> taken = new java.util.HashSet<>();
        List<String> wanted = new ArrayList<>(List.of("storage", "market", "cafe", "shop", "tavern", "workshop", "smeltery",
            "smithy", "brewery", "library", "hall", "chapel", "graveyard",
            "barracks", "watchtower", "watchtower", "watchtower", "watchtower", "granary", "guesthouse", "lighthouse",
            "gateway"));
        // An old town: the houses near the square have grown a second storey.
        for (int i = 0; i < 22; i++) wanted.add(i % 3 == 0 ? "house2" : "house");
        int k = 0;
        for (String name : wanted) {
            int[] half = BuildGoal.footprint(name);
            for (TownPlan.Lot lot : TownPlan.candidates(name)) {
                if (lot.kind() == TownPlan.Kind.SQUARE) continue;
                if (lot.distance() + Math.max(lot.halfAcross(), lot.halfDeep()) > reach) continue;
                if (half[0] > lot.halfAcross() || half[1] > lot.halfDeep()) continue;
                boolean free = true;
                for (long c : lot.cells()) if (taken.contains(c)) { free = false; break; }
                if (!free) continue;
                for (long c : lot.cells()) taken.add(c);
                Direction back = com.jrpetty.mcassistant.entity.Villages.direction(lot.back());
                BuildGoal.stamp(level, name, heart.offset(lot.x(), 0, lot.z()), back, 13, painter(PALETTES[k++ % PALETTES.length]));
                STAGED.add(new Ledger.Building(name, heart.offset(lot.x(), 0, lot.z()), back));
                n++;
                break;
            }
        }
        // A field on a lot of its own, with its scarecrow.
        BlockPos field = null;
        for (TownPlan.Lot lot : TownPlan.candidates("house")) {
            if (lot.kind() != TownPlan.Kind.LOT || lot.distance() + lot.halfAcross() > reach) continue;
            boolean free = true;
            for (long c : lot.cells()) if (taken.contains(c)) { free = false; break; }
            if (!free) continue;
            for (long c : lot.cells()) taken.add(c);
            field = heart.offset(lot.x(), 0, lot.z());
            break;
        }
        if (field != null) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos g = field.offset(dx, -1, dz);
                    if (dx == 0 && dz == 0) { level.setBlock(g, Blocks.WATER.defaultBlockState(), 3); continue; }
                    level.setBlock(g, Blocks.FARMLAND.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7), 2 | 16);
                    Block crop = (dx + 4) / 3 == 1 ? Blocks.CARROTS : (dx + 4) / 3 == 2 ? Blocks.POTATOES : Blocks.WHEAT;
                    level.setBlock(g.above(), ((net.minecraft.world.level.block.CropBlock) crop).getStateForAge(7), 2 | 16);
                }
            }
            TownLife.scarecrow(level, field, 5);
        }
        // The town's life: chimneys, numbers and names by the doors, washing, stalls, street signs.
        TownLife.dressNow(level, SHOWCASE, heart, STAGED, List.of(
            net.minecraft.world.item.Items.BREAD, net.minecraft.world.item.Items.CARROT, net.minecraft.world.item.Items.APPLE,
            net.minecraft.world.item.Items.PUMPKIN, net.minecraft.world.item.Items.MELON_SLICE, net.minecraft.world.item.Items.EGG,
            net.minecraft.world.item.Items.WHITE_WOOL, net.minecraft.world.item.Items.IRON_INGOT, net.minecraft.world.item.Items.HONEYCOMB,
            net.minecraft.world.item.Items.COOKED_COD, net.minecraft.world.item.Items.SWEET_BERRIES, net.minecraft.world.item.Items.POTATO));
        // The wall's gates, the watch's ladders and the alarm bell.
        com.jrpetty.mcassistant.entity.Villages.Village home = new com.jrpetty.mcassistant.entity.Villages.Village(SHOWCASE, heart, level.dimension());
        com.jrpetty.mcassistant.entity.Watch.keepAt(level, staged, heart, true);
        com.jrpetty.mcassistant.entity.Watch.bell(level, staged, true);
        // The graveyard's first graves, and the tavern of an evening.
        String[][] dead = { { "Old Bramble", "of old age" }, { "Wren", "by misfortune" }, { "Ash", "of old age" },
            { "Holt", "when the raiders came" }, { "Fen", "of old age" } };
        for (Ledger.Building b : STAGED) {
            if (b.structure().equals("graveyard")) {
                Direction back = b.facing(), right = back.getClockWise();
                for (int i = 0; i < dead.length; i++) {
                    int[] plot = com.jrpetty.mcassistant.entity.Graves.PLOTS[i];
                    BlockPos mound = b.anchor().relative(right, plot[0]).relative(back, plot[1]);
                    com.jrpetty.mcassistant.entity.Graves.headstone(level, mound.relative(back), mound, back.getOpposite(),
                        new Ledger.Grave(dead[i][0], 2 + i, 40 + 7 * i, dead[i][1], "", "", "Farmer"));
                }
                view("t20-graveyard", b.anchor().relative(back.getOpposite(), 7).above(3), b.anchor());
            } else if (b.structure().equals("tavern")) {
                com.jrpetty.mcassistant.entity.Tavern.board(level, b);
                Direction back = b.facing(), right = back.getClockWise();
                int guest = 0;
                for (int[] at : new int[][]{ { 1, -2 }, { 3, -2 }, { 1, 1 }, { 3, 1 }, { -1, 0 } }) {
                    com.jrpetty.mcassistant.entity.VillageFolkEntity f = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
                    if (f == null) continue;
                    BlockPos p = b.anchor().relative(right, at[0]).relative(back, at[1]);
                    float yaw = right.getOpposite().toYRot() + (guest % 2 == 0 ? 0.0F : 180.0F);
                    f.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, yaw, 0.0F);
                    f.setYHeadRot(yaw);
                    f.setYBodyRot(yaw);
                    com.jrpetty.mcassistant.entity.AssistantEntity.StationTask[] trades = {
                        com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.FARM, com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.MINE,
                        com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.SMITH, com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.COOK,
                        com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.WOOD };
                    f.makeShowcase(trades[guest % trades.length]);
                    f.rename(trades[guest % trades.length].title);
                    f.addTag("folk_lineup");
                    level.addFreshEntity(f);
                    guest++;
                }
                view("t19-tavern", b.anchor().relative(back.getOpposite(), 3).relative(right, 3).above(1),
                    b.anchor().relative(back, 3).relative(right, -1));
            }
        }
        // The café's counter and the shop's, set out (the showcase has no stores to set them from).
        for (Ledger.Building b : STAGED) {
            if (b.structure().equals("cafe")) {
                List<net.minecraft.world.item.ItemStack> menu = new ArrayList<>();
                for (int i = 0; i < 3; i++) menu.add(com.jrpetty.mcassistant.entity.Cafe.drink(com.jrpetty.mcassistant.entity.Cafe.DRINKS.get(i)));
                menu.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PUMPKIN_PIE));
                com.jrpetty.mcassistant.entity.Cafe.setOut(level, SHOWCASE, b, menu);
                view("t15-cafe", inside(b, 2), b.anchor().relative(b.facing(), 1));
            } else if (b.structure().equals("shop")) {
                com.jrpetty.mcassistant.entity.Cafe.setOut(level, SHOWCASE, b, List.of(
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE),
                    net.minecraft.world.item.alchemy.PotionContents.createItemStack(net.minecraft.world.item.Items.POTION,
                        net.minecraft.world.item.alchemy.Potions.HEALING),
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RED_BED),
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE)));
                view("t16-shop", inside(b, 2), b.anchor());
            }
        }
        // Where to stand to look at each of them.
        view("t7-stall", heart.offset(3, 1, 9), heart.offset(8, 1, 8));
        view("t8-street-sign", heart.offset(0, 1, 14), heart.offset(3, 1, 17));
        for (Ledger.Building b : STAGED) {
            if (!b.structure().equals("house")) continue;
            Direction front = b.facing().getOpposite(), right = b.facing().getClockWise();
            view("t9-house-number", b.anchor().relative(front, 7).relative(right, 1), b.anchor().relative(front, 4).relative(right, 1).above());
            view("t12-chimneys", b.anchor().relative(front, 14).relative(right, 6).above(9), b.anchor().above(7));
            break;
        }
        for (Ledger.Building b : STAGED) {
            if (!b.structure().equals("house") || (b.facing() != Direction.NORTH && b.facing() != Direction.WEST)) continue;
            Direction right = b.facing().getClockWise();
            view("t10-washing", b.anchor().relative(b.facing(), 7).relative(right, 6),
                b.anchor().relative(b.facing(), 6).relative(right, -1).above(2));
            break;
        }
        if (field != null) view("t11-scarecrow", field.offset(-8, 3, -8), field.offset(2, 0, 2));
        view("t13-night-street", heart.offset(1, 1, 50), heart.offset(0, 3, 14));
        // The quest board on the meeting hall, with the sort of thing a village posts.
        for (Ledger.Building b : STAGED) {
            if (!b.structure().equals("hall")) continue;
            List<BlockPos> notes = com.jrpetty.mcassistant.entity.Quests.paintOn(level, b,
                com.jrpetty.mcassistant.entity.Quests.samples(level.getDayTime() / 24000L));
            if (!notes.isEmpty()) {
                BlockPos first = notes.get(0);
                Direction front = b.facing().getOpposite();
                view("t22-quest-board", first.relative(front, 4).above(1), first);
            }
            break;
        }
        // Houses grown up with their village: one in stone with its garden, one in brick with a
        // second storey, a slate roof and its garden.
        com.jrpetty.mcassistant.entity.Villages.Village home = new com.jrpetty.mcassistant.entity.Villages.Village(SHOWCASE, heart, level.dimension());
        int grownUp = 0;
        for (Ledger.Building b : STAGED) {
            if (!b.structure().equals("house") || b.facing() != Direction.NORTH) continue;
            com.jrpetty.mcassistant.entity.Villages.Age age = grownUp == 0
                ? com.jrpetty.mcassistant.entity.Villages.Age.IRON : com.jrpetty.mcassistant.entity.Villages.Age.STONE;
            com.jrpetty.mcassistant.entity.Grow.now(level, home, b, age);
            if (grownUp == 0) {
                Direction front = b.facing().getOpposite(), right = b.facing().getClockWise();
                view("t23-grown-house", b.anchor().relative(front, 12).relative(right, 5).above(5), b.anchor().above(3));
            }
            if (++grownUp == 2) break;
        }
        // The waterfront, outside the town: a pond with a jetty and a boat, and an irrigated field.
        int wx = heart.getX() + reach + 10, wz = heart.getZ();
        stage(level, wx - 8, wx + 34, wz - 12, wz + 12, y);
        for (int x = wx; x <= wx + 11; x++) {
            for (int z = wz - 6; z <= wz + 6; z++) {
                for (int d = 1; d <= 3; d++) level.setBlock(new BlockPos(x, y - d, z), Blocks.WATER.defaultBlockState(), 2 | 16);
                level.setBlock(new BlockPos(x, y - 4, z), Blocks.SAND.defaultBlockState(), 2 | 16);
            }
        }
        com.jrpetty.mcassistant.entity.Waterfront.Dock dock = com.jrpetty.mcassistant.entity.Waterfront.site(level,
            new BlockPos(wx, y - 1, wz), 3);
        if (dock != null) {
            com.jrpetty.mcassistant.entity.Waterfront.build(level, dock);
            com.jrpetty.mcassistant.entity.Waterfront.moor(level, dock);
            view("t24-jetty", dock.start().relative(dock.out().getOpposite(), 6).relative(dock.out().getClockWise(), 6).above(5),
                dock.start().relative(dock.out(), 3));
        }
        BlockPos fieldAt = new BlockPos(wx + 24, y - 1, wz);
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                BlockPos soil = fieldAt.offset(dx, 0, dz);
                level.setBlock(soil, Blocks.FARMLAND.defaultBlockState(), 2 | 16);
                level.setBlock(soil.above(), Blocks.WHEAT.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CropBlock.AGE, 3 + Math.floorMod(dx * 7 + dz * 3, 5)), 2 | 16);
            }
        }
        for (int i = 0; i < 6; i++) com.jrpetty.mcassistant.entity.Waterfront.irrigate(level, SHOWCASE, fieldAt, 8, 64);
        view("t25-irrigation", fieldAt.offset(-12, 9, -12), fieldAt);
        // A hero's statue on the square, in the likeness of whoever is looking.
        List<net.minecraft.server.level.ServerPlayer> lookers = level.players();
        BlockPos spot = heart.offset(5, 0, -5);
        BlockPos plinth = com.jrpetty.mcassistant.entity.Citizens.raise(level, spot, heart,
            lookers.isEmpty() ? "A Hero" : lookers.get(0).getName().getString(),
            lookers.isEmpty() ? null : lookers.get(0).getGameProfile(), "Showcase");
        if (plinth != null) {
            Direction toHeart = Direction.getNearest(heart.getX() - spot.getX(), 0, heart.getZ() - spot.getZ());
            view("t21-statue", plinth.relative(toHeart, 4).relative(toHeart.getClockWise(), 1).above(2), plinth.above(1));
        }
        return n;
    }

    /**
     * A raid on the staged town, after dark: the gates shut, the watch on the north wall with
     * their bows, and a band of zombies and a skeleton at the north gate. Returns where to stand
     * to look at it ("VIEW name x y z tx ty tz").
     */
    public static List<String> raid(ServerLevel level, BlockPos heart) {
        List<String> out = new ArrayList<>();
        com.jrpetty.mcassistant.entity.Watch.shut(level, SHOWCASE, true);
        for (com.jrpetty.mcassistant.entity.Watch.Post p : com.jrpetty.mcassistant.entity.Watch.postsAt(level, SHOWCASE, heart)) {
            if (p.out() != Direction.NORTH) continue;
            com.jrpetty.mcassistant.entity.VillageFolkEntity g = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (g == null) continue;
            g.moveTo(p.stand().getX() + 0.5, p.stand().getY(), p.stand().getZ() + 0.5, 180.0F, 0.0F);
            g.setYHeadRot(180.0F);
            g.setYBodyRot(180.0F);
            g.makeShowcase(com.jrpetty.mcassistant.entity.AssistantEntity.StationTask.GUARD);
            g.rename("Guard");
            g.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
            g.addTag("folk_lineup");
            level.addFreshEntity(g);
        }
        BlockPos gate = heart.relative(Direction.NORTH, com.jrpetty.mcassistant.village.TownPlan.PLAZA);
        for (int i = 0; i < 6; i++) {
            BlockPos at = gate.relative(Direction.NORTH, 3 + (i % 2) * 2).relative(Direction.EAST, (i - 3) * 2 + 1);
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
            net.minecraft.world.entity.Mob m = i == 5 ? net.minecraft.world.entity.EntityType.SKELETON.create(level)
                : net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
            if (m == null) continue;
            if (i == 5) m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
            m.moveTo(at.getX() + 0.5, y, at.getZ() + 0.5, 0.0F, 0.0F);
            m.setYHeadRot(0.0F);
            m.setYBodyRot(0.0F);
            m.setNoAi(true);
            m.setPersistenceRequired();
            m.addTag("folk_lineup");
            level.addFreshEntity(m);
        }
        // From inside the square, beside the monument, looking up at the watch on the north wall;
        // and from out on the avenue, where the band is, looking back at the shut gate.
        out.add("VIEW t17-raid-wall " + (heart.getX() + 5) + " " + (heart.getY() + 4) + " " + (heart.getZ() - 3)
            + " " + heart.getX() + " " + (heart.getY() + 3) + " " + (gate.getZ()));
        out.add("VIEW t18-raid-gate " + (heart.getX() + 1) + " " + (heart.getY() + 3) + " " + (gate.getZ() - 11)
            + " " + heart.getX() + " " + (heart.getY() + 2) + " " + gate.getZ());
        return out;
    }

    /** The buildings of the last town laid out, for its lights (/village showcase lights). */
    public static final List<Ledger.Building> STAGED = new ArrayList<>();
    /** Where to stand to look at the town's details: "VIEW name x y z tx ty tz". */
    public static final List<String> VIEWS = new ArrayList<>();
    /** The showcase town's own name for its streets. */
    public static final java.util.UUID SHOWCASE = java.util.UUID.nameUUIDFromBytes("mca-showcase".getBytes());

    /** A spot inside a building, this far in from its door, at eye height. */
    private static BlockPos inside(Ledger.Building b, int fromDoor) {
        int[] half = BuildGoal.footprint(b.structure());
        Direction front = b.facing().getOpposite();
        return b.anchor().relative(front, Math.max(1, half[1] - fromDoor)).above();
    }

    private static void view(String name, BlockPos eye, BlockPos at) {
        VIEWS.add("VIEW " + name + " " + eye.getX() + " " + eye.getY() + " " + eye.getZ()
            + " " + at.getX() + " " + at.getY() + " " + at.getZ());
    }

    private static boolean lamp(int dx, int dz) {
        int ax = Math.abs(dx), az = Math.abs(dz);
        int every = 6;
        if (ax == TownPlan.AVENUE && az > TownPlan.RING + TownPlan.STREET && az % every == 0) return true;
        if (az == TownPlan.AVENUE && ax > TownPlan.RING + TownPlan.STREET && ax % every == 0) return true;
        int outer = TownPlan.RING + TownPlan.STREET - 1;
        if (ax == outer && az <= outer && az > TownPlan.AVENUE + 1 && az % every == 0) return true;
        return az == outer && ax <= outer && ax > TownPlan.AVENUE + 1 && ax % every == 0;
    }
}
