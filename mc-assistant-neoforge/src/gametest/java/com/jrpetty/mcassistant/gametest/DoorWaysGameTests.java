package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.DoorWays;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * A way in at every door (entity/DoorWays): one house whose door opens onto a bank of earth a block
 * high, one whose door opens over a drop of three. On the town's rounds the bank in the doorway is
 * dug out and a step cut up onto it, and the drop gets a doorstep and steps down; the walls, the
 * porch and the town's fields are never touched.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class DoorWaysGameTests {

    private static final String EMPTY = "empty";

    /** A stone-brick box five across, its door in the middle of the south wall; its floor at `floor`. */
    private static BlockPos house(ServerLevel level, BlockPos centre, int floor) {
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

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "dw01_door_ways")
    public static void dw01_door_ways(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x = 540000, z = 60000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        Villages.Village v = Villages.get(f.ownerId());
        // The stores, with earth and stone for the steps.
        BlockPos chest = heart.offset(3, 0, -3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container stores = (Container) level.getBlockEntity(chest);
        stores.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
        stores.setItem(1, new ItemStack(Items.DIRT, 32));
        int g = heart.getY();

        // A house sunk a block: the ground in front of its door is a bank a block high.
        BlockPos aCentre = heart.offset(-12, 0, 8);
        BlockPos aDoor = house(level, aCentre, g - 1);
        for (int k = 1; k <= 4; k++) {
            for (int dx = -1; dx <= 1; dx++) level.setBlock(aDoor.offset(dx, 0, k), Blocks.DIRT.defaultBlockState(), 2);
        }
        Ledger.built(v.id(), "house", aCentre.atY(g - 1), Direction.SOUTH);

        // A house on a plinth: a drop of three in front of its door.
        BlockPos bCentre = heart.offset(12, 0, 8);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int h = 0; h < 3; h++)
            level.setBlock(bCentre.offset(dx, h, dz), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        BlockPos bDoor = house(level, bCentre, g + 3);
        Ledger.built(v.id(), "house", bCentre.atY(g + 3), Direction.SOUTH);

        List<DoorWays.Door> aDoors = DoorWays.doorsOf(level, new Ledger.Building("house", aCentre.atY(g - 1), Direction.SOUTH));
        List<DoorWays.Door> bDoors = DoorWays.doorsOf(level, new Ledger.Building("house", bCentre.atY(g + 3), Direction.SOUTH));
        Kit.log("dw01 doors: sunk " + aDoors + ", raised " + bDoors);
        helper.assertTrue(aDoors.size() == 1 && aDoors.get(0).out() == Direction.SOUTH, "the sunk house's door, facing out south: " + aDoors);
        helper.assertTrue(bDoors.size() == 1 && bDoors.get(0).out() == Direction.SOUTH, "the raised house's door, facing out south: " + bDoors);
        helper.assertTrue(!DoorWays.walkable(level, aDoors.get(0)), "the bank across the sunk house's door is in the way");
        helper.assertTrue(!DoorWays.walkable(level, bDoors.get(0)), "the drop before the raised house's door is too far to step");

        // The town's rounds, a few times over.
        for (int i = 0; i < 12; i++) DoorWays.tick(level, v);
        boolean aOk = DoorWays.walkable(level, aDoors.get(0)), bOk = DoorWays.walkable(level, bDoors.get(0));
        Kit.log("dw01 after the rounds: the sunk house's way in walkable " + aOk + " (doorway " + level.getBlockState(aDoor.south()).getBlock()
            + "/" + level.getBlockState(aDoor.south().above()).getBlock() + "), the raised house's " + bOk + " (doorstep under "
            + level.getBlockState(bDoor.south().below()).getBlock() + ")");
        helper.assertTrue(aOk, "the bank dug out of the sunk house's doorway and a step cut up onto it");
        helper.assertTrue(level.getBlockState(aDoor.south()).isAir() && level.getBlockState(aDoor.south().above()).isAir(),
            "the doorway of the sunk house is clear");
        helper.assertTrue(bOk, "the raised house has a doorstep and steps down to the ground");
        helper.assertTrue(!level.getBlockState(bDoor.south().below()).isAir(), "a doorstep laid level with the raised house's door");
        helper.assertTrue(level.getBlockState(aCentre.offset(2, 0, 0).atY(g)).is(Blocks.STONE_BRICKS)
            && level.getBlockState(bCentre.offset(-2, 0, 0).atY(g + 4)).is(Blocks.STONE_BRICKS), "the walls are untouched");
        helper.succeed();
    }
}
