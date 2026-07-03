package com.jrpetty.mazerunner;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MazeRunnerMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MazeRunnerMod.MODID);

    /** Unbreakable, blast-proof maze wall (bedrock-grade; legacy uniform look). */
    public static final DeferredBlock<Block> MAZE_WALL = BLOCKS.register("maze_wall",
        () -> new Block(wallProps()));

    /**
     * The weathering palette, dark (index 0, wall base) to light (index 5,
     * wall top): polished tuff, tuff, polished andesite, mossy stone bricks,
     * stone bricks, andesite. All bedrock-grade unbreakable; only the skin
     * differs.
     */
    public static final java.util.List<DeferredBlock<Block>> WALL_PALETTE = java.util.List.of(
        BLOCKS.register("maze_wall_polished_tuff", () -> new Block(wallProps())),
        BLOCKS.register("maze_wall_tuff", () -> new Block(wallProps())),
        BLOCKS.register("maze_wall_polished_andesite", () -> new Block(wallProps())),
        BLOCKS.register("maze_wall_mossy_bricks", () -> new Block(wallProps())),
        BLOCKS.register("maze_wall_bricks", () -> new Block(wallProps())),
        BLOCKS.register("maze_wall_andesite", () -> new Block(wallProps())));

    private static BlockBehaviour.Properties wallProps() {
        return BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(-1.0F, 3600000.0F)
            .sound(SoundType.DEEPSLATE_BRICKS)
            .isValidSpawn((state, level, pos, type) -> false);
    }

    /** Walk-through escape portal; lit and functional only for the day's layout. */
    public static final DeferredBlock<ExitPortalBlock> EXIT_PORTAL = BLOCKS.register("exit_portal",
        () -> new ExitPortalBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_CYAN)
            .strength(-1.0F, 3600000.0F)
            .noCollission()
            .noLootTable()
            .lightLevel(state -> state.getValue(ExitPortalBlock.ACTIVE) ? 15 : 3)));

    /** Non-spreading wall ivy (vanilla-vine look and climbability). */
    public static final DeferredBlock<MazeVineBlock> MAZE_VINE = BLOCKS.register("maze_vine",
        () -> new MazeVineBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.PLANT)
            .replaceable()
            .noCollission()
            .strength(0.2F)
            .sound(SoundType.VINE)
            .ignitedByLava()
            .pushReaction(PushReaction.DESTROY)));

    public static final DeferredItem<BlockItem> MAZE_WALL_ITEM =
        ITEMS.registerSimpleBlockItem("maze_wall", MAZE_WALL);
    public static final DeferredItem<BlockItem> EXIT_PORTAL_ITEM =
        ITEMS.registerSimpleBlockItem("exit_portal", EXIT_PORTAL);
    public static final DeferredItem<BlockItem> MAZE_VINE_ITEM =
        ITEMS.registerSimpleBlockItem("maze_vine", MAZE_VINE);

    private ModBlocks() {}
}
