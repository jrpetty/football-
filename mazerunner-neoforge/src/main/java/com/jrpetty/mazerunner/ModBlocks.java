package com.jrpetty.mazerunner;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MazeRunnerMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MazeRunnerMod.MODID);

    /** Unbreakable, blast-proof maze wall (bedrock-grade). */
    public static final DeferredBlock<Block> MAZE_WALL = BLOCKS.register("maze_wall",
        () -> new Block(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(-1.0F, 3600000.0F)
            .sound(SoundType.DEEPSLATE_BRICKS)
            .isValidSpawn((state, level, pos, type) -> false)));

    /** Walk-through escape portal; lit and functional only for the day's layout. */
    public static final DeferredBlock<ExitPortalBlock> EXIT_PORTAL = BLOCKS.register("exit_portal",
        () -> new ExitPortalBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_CYAN)
            .strength(-1.0F, 3600000.0F)
            .noCollission()
            .noLootTable()
            .lightLevel(state -> state.getValue(ExitPortalBlock.ACTIVE) ? 15 : 3)));

    public static final DeferredItem<BlockItem> MAZE_WALL_ITEM =
        ITEMS.registerSimpleBlockItem("maze_wall", MAZE_WALL);
    public static final DeferredItem<BlockItem> EXIT_PORTAL_ITEM =
        ITEMS.registerSimpleBlockItem("exit_portal", EXIT_PORTAL);

    private ModBlocks() {}
}
