package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * The Village Board: a notice board ten blocks wide and five high that stands on the
 * village's square from the day it is founded, and says what the village is doing —
 * what it is building and what comes next, what it is short of, the elder's orders, how
 * its stores and its folk are, and what it is working towards.
 *
 * <p>Fifty panels make one board. Each knows its place in it (column 0..9 from the left
 * as you face it, row 0..4 from the bottom), so the frame is drawn round the edge; the
 * bottom-left panel holds the writing (VillageBoardBlockEntity), drawn across the whole
 * face (client/VillageBoardRenderer). Take one panel down and the whole board comes down
 * with it, as one Village Board you can put up again.
 */
public class VillageBoardBlock extends Block implements EntityBlock {

    public static final int WIDE = 10, HIGH = 5;
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final IntegerProperty COL = IntegerProperty.create("col", 0, WIDE - 1);
    public static final IntegerProperty ROW = IntegerProperty.create("row", 0, HIGH - 1);

    // A plank-thick face against the back of the cell; the writing faces FACING.
    private static final VoxelShape SOUTH = Block.box(0, 0, 0, 16, 16, 3);
    private static final VoxelShape NORTH = Block.box(0, 0, 13, 16, 16, 16);
    private static final VoxelShape EAST = Block.box(0, 0, 0, 3, 16, 16);
    private static final VoxelShape WEST = Block.box(13, 0, 0, 16, 16, 16);

    /** Set while the board takes itself down, so the panels going do not each start it again. */
    private static final ThreadLocal<Boolean> TAKING_DOWN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public VillageBoardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(COL, 0).setValue(ROW, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, COL, ROW);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case WEST -> WEST;
            default -> SOUTH;
        };
    }

    // ------------------------------------------------------------------ the shape of a board

    /** Which way is "right" along the board, as you stand in front of it reading. */
    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** The bottom-left panel (the one with the writing) of the board this panel is part of. */
    public static BlockPos anchor(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        return pos.relative(right(facing), -state.getValue(COL)).below(state.getValue(ROW));
    }

    /** The cell for a column and row of a board with this anchor. */
    public static BlockPos cell(BlockPos anchor, Direction facing, int col, int row) {
        return anchor.relative(right(facing), col).above(row);
    }

    public static BlockState panel(Direction facing, int col, int row) {
        return McAssistantMod.VILLAGE_BOARD.get().defaultBlockState()
            .setValue(FACING, facing).setValue(COL, col).setValue(ROW, row);
    }

    /** Is there room for a board here: every cell free, and the ground under it to stand on? */
    public static boolean fits(LevelReader level, BlockPos anchor, Direction facing) {
        for (int c = 0; c < WIDE; c++) {
            for (int r = 0; r < HIGH; r++) {
                BlockPos p = cell(anchor, facing, c, r);
                if (!level.getBlockState(p).canBeReplaced()) return false;
            }
        }
        return true;
    }

    /** Put a whole board up, its bottom-left panel at {@code anchor}, writing facing {@code facing}. */
    public static void raise(Level level, BlockPos anchor, Direction facing, @Nullable java.util.UUID village) {
        for (int r = 0; r < HIGH; r++) {
            for (int c = 0; c < WIDE; c++) {
                level.setBlock(cell(anchor, facing, c, r), panel(facing, c, r), 3);
            }
        }
        if (level.getBlockEntity(anchor) instanceof VillageBoardBlockEntity board) {
            board.adopt(village);
        }
    }

    // ------------------------------------------------------------------ the writing

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(COL) == 0 && state.getValue(ROW) == 0 ? new VillageBoardBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != McAssistantMod.VILLAGE_BOARD_BE.get()) return null;
        return (lvl, pos, st, be) -> VillageBoardBlockEntity.serverTick(lvl, pos, st, (VillageBoardBlockEntity) be);
    }

    /** Reading it close up: the whole of it in the village journal. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp
                && level.getBlockEntity(anchor(pos, state)) instanceof VillageBoardBlockEntity board) {
            board.showTo(sp);
        }
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------------ coming down

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && !TAKING_DOWN.get()) {
            takeDown(level, pos, state, !player.getAbilities().instabuild);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** The whole board down, and (unless {@code drop} is false) one Village Board for it. */
    private static void takeDown(Level level, BlockPos pos, BlockState state, boolean drop) {
        BlockPos anchor = anchor(pos, state);
        Direction facing = state.getValue(FACING);
        TAKING_DOWN.set(Boolean.TRUE);
        try {
            for (int c = 0; c < WIDE; c++) {
                for (int r = 0; r < HIGH; r++) {
                    BlockPos p = cell(anchor, facing, c, r);
                    if (p.equals(pos)) continue;
                    BlockState s = level.getBlockState(p);
                    if (s.getBlock() instanceof VillageBoardBlock && anchor(p, s).equals(anchor)) {
                        level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        } finally {
            TAKING_DOWN.set(Boolean.FALSE);
        }
        if (drop) {
            ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                new ItemStack(McAssistantMod.VILLAGE_BOARD_ITEM.get()));
            item.setDefaultPickUpDelay();
            level.addFreshEntity(item);
        }
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(McAssistantMod.VILLAGE_BOARD_ITEM.get());
    }
}
