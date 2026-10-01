package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
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
        "market", "watchtower", "lighthouse", "monument", "gateway", "hall", "chapel", "barracks");

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
        java.util.Set<Long> taken = new java.util.HashSet<>();
        List<String> wanted = new ArrayList<>(List.of("storage", "market", "workshop", "smeltery", "hall", "chapel",
            "barracks", "watchtower", "watchtower", "watchtower", "watchtower", "granary", "guesthouse", "lighthouse",
            "gateway"));
        for (int i = 0; i < 22; i++) wanted.add("house");
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
                BuildGoal.stamp(level, name, heart.offset(lot.x(), 0, lot.z()),
                    com.jrpetty.mcassistant.entity.Villages.direction(lot.back()), 13, painter(PALETTES[k++ % PALETTES.length]));
                n++;
                break;
            }
        }
        return n;
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
