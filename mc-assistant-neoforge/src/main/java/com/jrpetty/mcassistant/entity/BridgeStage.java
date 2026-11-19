package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [bridges18] The bridges' and the steps' stage, for the pictures (/village region stage bridges, operators) and the tests
 * (BridgeStairGameTests): flat ground with a river, a hill, and houses whose doors stand off the ground, and on it the
 * folk's own builders at work: a road between two towns made a path (its timber bridge) and another made a stone road
 * (its stone bridge), a colony's road over the river, a stone road over a stepped hill, and the ways in at the doors.
 */
public final class BridgeStage {

    private BridgeStage() {}

    /** A road scene: its two towns, the ground's height and the river's west edge. */
    public record Scene(Villages.Village a, Villages.Village b, int y, int river, int width) {}

    /** Flat grass, its top at y - 1, clear air above. */
    public static void flat(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
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
    }

    public static void box(ServerLevel level, int x0, int x1, int y0, int y1, int z0, int z1, BlockState st) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) level.setBlock(new BlockPos(x, y, z), st, 2 | 16);
    }

    /** A river from x0 to x1 the whole way from z0 to z1: its water's top at y - 1, three deep, a sand bed. */
    public static void river(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        box(level, x0, x1, y - 4, y - 4, z0, z1, Blocks.SAND.defaultBlockState());
        box(level, x0, x1, y - 3, y - 1, z0, z1, Blocks.WATER.defaultBlockState());
    }

    /** A town of so many folk round (x, z), its stores stocked for roads and bridges of any grade. */
    @Nullable
    public static Villages.Village town(ServerLevel level, int x, int z, int y, int folk) {
        BlockPos heart = new BlockPos(x, y, z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        if (first == null || first.ownerId() == null) return null;
        for (int i = 1; i < folk; i++) VillageFolkSpawnerBlock.raise(level, new BlockPos(x + 2 + i % 4, y, z + 2 + i / 4), 0.0F);
        ItemStack[][] chests = {
            { new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
              new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.STICK, 64), new ItemStack(Items.STICK, 64),
              new ItemStack(Items.SPRUCE_LOG, 64), new ItemStack(Items.SPRUCE_LOG, 64), new ItemStack(Items.LANTERN, 32), new ItemStack(Items.TORCH, 32),
              new ItemStack(Items.DIRT, 64), new ItemStack(Items.DIRT, 64) },
            { new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.GRAVEL, 64),
              new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.GRAVEL, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
              new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64) },
            { new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
              new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
              new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
              new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
              new ItemStack(Items.STONE_BRICK_SLAB, 64), new ItemStack(Items.STONE_BRICK_STAIRS, 64), new ItemStack(Items.STONE_BRICK_STAIRS, 64),
              new ItemStack(Items.STONE_BRICK_WALL, 64) } };
        for (int k = 0; k < chests.length; k++) {
            BlockPos c = heart.offset(-3 - k, 0, -5);
            level.setBlock(c, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, c);
            if (level.getBlockEntity(c) instanceof Container box) {
                for (int i = 0; i < chests[k].length; i++) box.setItem(i, chests[k][i]);
                box.setChanged();
            }
        }
        Villages.forgetStock();
        return Villages.get(first.ownerId());
    }

    /**
     * Two towns 220 apart (the second {@code dz} to the side, so the road meets the river slantwise) on flat ground, a river
     * nine wide across the way between them; their road graded up to {@code grade} by both gangs, out of their stores.
     */
    @Nullable
    public static Scene road(ServerLevel level, int x, int z, int y, int dz, int grade, Villages.Age age) {
        flat(level, x - 30, x + 250, z - 50, z + dz + 50, y);
        int rx = x + 106;
        river(level, rx, rx + 8, z - 50, z + dz + 50, y);
        Villages.Village a = town(level, x, z, y, 7), b = town(level, x + 220, z + dz, y, 7);
        if (a == null || b == null) return null;
        grade(level, a, b, grade, age);
        return new Scene(a, b, y, rx, 9);
    }

    /** Both halves of the road between two towns graded up to {@code grade} (each grade over the whole half). */
    public static void grade(ServerLevel level, Villages.Village a, Villages.Village b, int grade, Villages.Age age) {
        Villages.ageForTests(a.id(), age);
        Villages.ageForTests(b.id(), age);
        RoadWorks.Road r = RoadWorks.road(a, b);
        if (r == null) return;
        for (int g = 1; g <= grade; g++) {
            for (Villages.Village v : new Villages.Village[]{ a, b }) {
                UUID o = r.other(v.id());
                RoadWorks.setHalfForTests(v.id(), o, g - 1, 0, 40);
                for (int k = 0; k < 40 && RoadWorks.halfState(v.id(), o)[0] < g; k++) RoadWorks.work(level, v, r, 4000, true);
            }
        }
    }

    /** A colony's road from its mother 210 off, over a river, laid out of the mother's stores. */
    @Nullable
    public static Scene colony(ServerLevel level, int x, int z, int y) {
        flat(level, x - 30, x + 240, z - 40, z + 60, y);
        int rx = x + 100;
        river(level, rx, rx + 6, z - 40, z + 60, y);
        Villages.Village a = town(level, x, z, y, 7), b = town(level, x + 210, z + 10, y, 5);
        if (a == null || b == null) return null;
        boolean was = TownJobs.instantNow();
        TownJobs.instantForTests(true);
        try {
            for (int k = 0; k < 40 && Roads.progress(b.id()) < 1.0; k++) Roads.lay(level, a, b, 400);
        } finally {
            TownJobs.instantForTests(was);
        }
        return new Scene(a, b, y, rx, 7);
    }

    /** [tests] The colony road's line, mother to colony. */
    public static List<int[]> colonyLineForTests(Scene s) {
        return Roads.line(s.a(), s.b());
    }

    /** A stone road over a stepped hill (a block up every three, six high, then down again) between two towns 220 apart. */
    @Nullable
    public static Scene hill(ServerLevel level, int x, int z, int y) {
        flat(level, x - 30, x + 250, z - 90, z + 90, y);
        for (int k = 0; k < 6; k++) {
            int x0 = x + 80 + 3 * k, x1 = x + 140 - 3 * k;
            box(level, x0, x1, y + k, y + k, z - 80, z + 80, Blocks.GRASS_BLOCK.defaultBlockState());
            if (k > 0) box(level, x0, x1, y + k - 1, y + k - 1, z - 80, z + 80, Blocks.DIRT.defaultBlockState());
        }
        Villages.Village a = town(level, x, z, y, 7), b = town(level, x + 220, z, y, 7);
        if (a == null || b == null) return null;
        grade(level, a, b, 3, Villages.Age.IRON);
        return new Scene(a, b, y, x + 80, 60);
    }

    /** A stone-brick house five across, its door in the middle of its south wall, its floor at `floor`. */
    public static BlockPos house(ServerLevel level, BlockPos centre, int floor) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(new BlockPos(centre.getX() + dx, floor - 1, centre.getZ() + dz), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                for (int h = 0; h <= 3; h++) {
                    boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2 || h == 3;
                    level.setBlock(new BlockPos(centre.getX() + dx, floor + h, centre.getZ() + dz),
                        wall ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        BlockPos door = new BlockPos(centre.getX(), floor, centre.getZ() + 2);
        BlockState d = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        level.setBlock(door, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(door.above(), d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        return door;
    }

    /** Houses with their doors one, two and three blocks off the ground on plinths, and one sunk a block; their ways in made. */
    public static List<BlockPos> doors(ServerLevel level, int x, int z, int y) {
        flat(level, x - 20, x + 40, z - 20, z + 30, y);
        Villages.Village v = town(level, x, z, y, 3);
        List<BlockPos> out = new ArrayList<>();
        if (v == null) return out;
        int[] lifts = { 1, 2, 3, -1 };
        for (int k = 0; k < lifts.length; k++) {
            BlockPos c = new BlockPos(x - 6 + 12 * k, y, z + 10);
            int floor = y + lifts[k];
            if (lifts[k] > 0) box(level, c.getX() - 2, c.getX() + 2, y, floor - 2, c.getZ() - 2, c.getZ() + 2, Blocks.STONE_BRICKS.defaultBlockState());
            BlockPos door = house(level, c, floor);
            if (lifts[k] < 0) box(level, door.getX() - 1, door.getX() + 1, y - 1, y - 1, door.getZ() + 1, door.getZ() + 4, Blocks.DIRT.defaultBlockState());
            com.jrpetty.mcassistant.village.Ledger.built(v.id(), "house", c.atY(floor), Direction.SOUTH);
            out.add(door);
        }
        boolean was = TownJobs.instantNow();
        TownJobs.instantForTests(true);
        try {
            for (int i = 0; i < 16; i++) DoorWays.tick(level, v);
        } finally {
            TownJobs.instantForTests(was);
        }
        return out;
    }

    /** The whole stage beside (x, z), each scene 220 on from the last (nearer, their towns would be one town): the timber bridge, the stone bridge, the colony's bridge, the hill road and the doors. */
    static List<String> stage(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        int x = at.getX() + 40, z = at.getZ();
        int y = Math.max(level.getSeaLevel() + 2, at.getY());
        // Each scene's ground held while it is built, and let go after (the whole stage held at once is a great deal of ground).
        force(level, x, z, true);
        view(out, "timber", road(level, x, z, y, 24, 1, Villages.Age.STONE));
        force(level, x, z, false);
        force(level, x, z + 220, true);
        view(out, "stone", road(level, x, z + 220, y, 24, 3, Villages.Age.IRON));
        force(level, x, z + 220, false);
        force(level, x, z + 440, true);
        view(out, "colony", colony(level, x, z + 440, y));
        force(level, x, z + 440, false);
        force(level, x, z + 660, true);
        if (hill(level, x, z + 660, y) != null) out.add("VIEW hill " + (x + 110) + " " + (y + 14) + " " + (z + 660 + 22) + " " + (x + 110) + " " + (y + 5) + " " + (z + 660));
        force(level, x, z + 660, false);
        force(level, x, z + 880, true);
        if (!doors(level, x, z + 880, y).isEmpty()) out.add("VIEW doors " + (x + 12) + " " + (y + 6) + " " + (z + 880 + 26) + " " + (x + 12) + " " + (y + 1) + " " + (z + 890));
        force(level, x, z + 880, false);
        return out;
    }

    private static void force(ServerLevel level, int x, int z, boolean on) {
        for (int cx = (x - 48) >> 4; cx <= (x + 260) >> 4; cx++) for (int cz = (z - 96) >> 4; cz <= (z + 120) >> 4; cz++) level.setChunkForced(cx, cz, on);
    }

    private static void view(List<String> out, String name, @Nullable Scene s) {
        if (s == null) {
            out.add(name + ": no towns raised");
            return;
        }
        RoadWorks.Road r = RoadWorks.road(s.a(), s.b());
        List<int[]> line = name.equals("colony") || r == null ? Roads.line(s.a(), s.b()) : r.line();
        int mz = s.a().centre().getZ();
        for (int[] p : line) if (p[0] == s.river() + s.width() / 2) mz = p[1];
        int mx = s.river() + s.width() / 2;
        out.add("VIEW " + name + " " + (mx + 14) + " " + (s.y() + 7) + " " + (mz + 14) + " " + mx + " " + s.y() + " " + mz);
    }
}
