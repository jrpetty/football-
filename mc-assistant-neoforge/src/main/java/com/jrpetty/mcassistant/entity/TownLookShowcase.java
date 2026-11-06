package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.ArrayList;
import java.util.List;

/**
 * The town's look set out to be looked at (/village townlook showcase, operators: the pictures) [batchE]: the
 * windmill with its sails hung, the bakery with window boxes under its front windows, the inn with its sign,
 * the orchard with its oaks grown and the allotments with their crops coming on, in a row on a level stage,
 * their doors onto a stretch of avenue with its lamp posts, the trees on its verge midway between them, a
 * bench on the far side and the notice board. Put down as the showcase puts its buildings down, out of
 * nothing (it is a picture, not a town). Returns where to stand to look ("VIEW name x y z tx ty tz").
 */
public final class TownLookShowcase {

    private TownLookShowcase() {}

    /** The buildings, in a row going east, fifteen apart. */
    static final String[] ROW = { TownLook.WINDMILL, TownLook.ORCHARD, TownLook.ALLOTMENTS, TownLook.BAKERY, TownLook.INN, "house" };

    public static List<String> showcase(ServerLevel level, BlockPos at) {
        int x0 = at.getX(), y = at.getY(), z0 = at.getZ();
        Showcase.stage(level, x0 - 8, x0 + 85, z0 - 8, z0 + 18, y);
        List<Ledger.Building> built = new ArrayList<>();
        for (int i = 0; i < ROW.length; i++) {
            BlockPos a = new BlockPos(x0 + i * 15, y, z0);
            BuildGoal.stamp(level, ROW[i], a, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            built.add(new Ledger.Building(ROW[i], a, Direction.NORTH));
        }
        // The windmill's sails, hung in white.
        for (BlockPos p : Windmill.cloth(built.get(0))) level.setBlock(p, Blocks.WHITE_WOOL.defaultBlockState(), 2 | 16);
        // The orchard's four oaks, grown.
        for (int i = 0; i < Orchard.TREES.length; i++) grow(level, Orchard.tree(built.get(1), i));
        // The allotments: furrows, and a crop coming on in each plot.
        Block[] crops = { Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS, Blocks.WHEAT };
        for (int plot = 0; plot < Allotments.PLOTS.length; plot++) {
            List<BlockPos> soil = Allotments.soil(built.get(2), plot);
            for (int k = 0; k < soil.size(); k++) {
                BlockPos p = soil.get(k);
                level.setBlock(p, Blocks.FARMLAND.defaultBlockState(), 2 | 16);
                CropBlock crop = (CropBlock) crops[plot];
                level.setBlock(p.above(), crop.getStateForAge(Math.min(crop.getMaxAge(), 2 + k % (crop.getMaxAge() - 1))), 2 | 16);
            }
        }
        // A well-off household's cottage at the end of the row, a window box under each of its side windows on
        // the near side (its front windows have the lamp posts by its door under them: StreetFurniture.boxes).
        Ledger.Building house = built.get(5);
        int k = 0;
        for (StreetFurniture.Box box : StreetFurniture.boxes(house)) {
            if (box.out() != Direction.EAST || k >= StreetFurniture.BOXES || !StreetFurniture.boxFits(level, box)) continue;
            level.setBlock(box.ledge(), Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.FACING, box.out()), 2 | 16);
            level.setBlock(box.pot(), (k++ == 0 ? Blocks.POTTED_POPPY : Blocks.POTTED_CORNFLOWER).defaultBlockState(), 2 | 16);
        }
        // The inn's sign.
        BlockPos sign = Inn.signAt(built.get(4));
        level.setBlock(sign, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.SOUTH), 3);
        if (level.getBlockEntity(sign) instanceof SignBlockEntity s) {
            TownLife.write(s, new String[]{ "The Inn", "Showcase", "Rooms " + Inn.ROOM + " coins", "Click for a room" });
        }
        // The avenue in front of them: five wide, lamp posts on its near edge every six, a tree midway between each two.
        int az0 = z0 + 9, az1 = z0 + 13;
        for (int x = x0 - 8; x <= x0 + 85; x++) {
            for (int z = az0; z <= az1; z++) level.setBlock(new BlockPos(x, y - 1, z), Blocks.DIRT_PATH.defaultBlockState(), 2 | 16);
            if (Math.floorMod(x - x0, Avenues.EVERY) == 0) {
                BlockPos post = new BlockPos(x, y, az0);
                level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 2 | 16);
                level.setBlock(post.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 2 | 16);
                level.setBlock(post.above(2), Blocks.LANTERN.defaultBlockState(), 2 | 16);
            }
            if (Math.floorMod(x - x0, Avenues.EVERY) == Avenues.MIDWAY && Math.floorMod(x - x0, 15) > 8) {
                grow(level, new BlockPos(x, y, az1 + 1));
            }
        }
        // A bench across the avenue from the bakery, and the notice board beside it.
        BlockPos bench = new BlockPos(x0 + 45, y, az1 + 1);
        level.setBlock(bench, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH), 2 | 16);
        String[][] text = { { "Town News", "Showcase", "Day 1", "12 folk" },
            StreetFurniture.wrap("The windmill's sails were hung, and the bakery's ovens lit.", 15, 4) };
        for (int n = 0; n < 2; n++) {
            BlockPos p = new BlockPos(x0 + 49 + n, y, az1 + 1);
            level.setBlock(p, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8), 3);
            if (level.getBlockEntity(p) instanceof SignBlockEntity s) TownLife.write(s, text[n]);
        }
        List<String> views = new ArrayList<>();
        views.add(view("tl-1-farmland", x0 + 22, y + 14, z0 + 30, x0 + 15, y + 3, z0));
        views.add(view("tl-2-street", x0 + 84, y + 4, z0 + 16, x0 + 60, y + 2, z0 + 5));
        views.add(view("tl-3-windmill", x0 + 7, y + 5, z0 + 13, x0, y + 6, z0 - 1));
        return views;
    }

    /** A sapling put down and grown as the game grows one (a few goes; a sapling that will not grow is left). */
    private static void grow(ServerLevel level, BlockPos p) {
        level.setBlock(p, Blocks.OAK_SAPLING.defaultBlockState(), 2 | 16);
        for (int k = 0; k < 20 && level.getBlockState(p).getBlock() instanceof SaplingBlock s; k++) {
            s.advanceTree(level, p, level.getBlockState(p), level.getRandom());
        }
    }

    private static String view(String name, int x, int y, int z, int tx, int ty, int tz) {
        return "VIEW " + name + " " + x + " " + y + " " + z + " " + tx + " " + ty + " " + tz;
    }
}
