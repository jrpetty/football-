package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.item.KitchenItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * [kitchen] The cheese wheel: the cook's cheese, of the rancher's milk, waxed in its rind on a board of its own. It keeps:
 * the town's reserve against short rations (entity/Kitchen cuts one for the stores), and set out on the café's and the
 * tavern's tables it is eaten a slice at a time by folk taking their meal there. Four slices to a wheel, a quarter cut
 * away with each, showing the pale inside where it was cut (the models: cheese_wheel, _cut1, _cut2, _cut3). A player
 * eats a slice by right-clicking it, as a cake; broken, a whole wheel drops itself, a cut one its slices left.
 */
public class CheeseWheelBlock extends Block {

    /** Slices to a wheel. */
    public static final int SLICES = 4;
    /** How many are cut and gone: none to three (the fourth takes the wheel). */
    public static final IntegerProperty CUT = IntegerProperty.create("cut", 0, SLICES - 1);

    /** The board under it, and the wheel's four quarters (north-west, north-east, south-east, south-west), each an
     *  octagon's corner: the slices go the north-east first, then round. */
    private static final VoxelShape BOARD = Block.box(1.0, 0.0, 1.0, 15.0, 1.0, 15.0);
    private static final VoxelShape[] QUARTERS = {
        Shapes.or(Block.box(3, 1, 1, 8, 7, 8), Block.box(1, 1, 3, 3, 7, 8), Block.box(2, 1, 2, 3, 7, 3)),
        Shapes.or(Block.box(8, 1, 1, 13, 7, 8), Block.box(13, 1, 3, 15, 7, 8), Block.box(13, 1, 2, 14, 7, 3)),
        Shapes.or(Block.box(8, 1, 8, 13, 7, 15), Block.box(13, 1, 8, 15, 7, 13), Block.box(13, 1, 13, 14, 7, 14)),
        Shapes.or(Block.box(3, 1, 8, 8, 7, 15), Block.box(1, 1, 8, 3, 7, 13), Block.box(2, 1, 13, 3, 7, 14))
    };
    /** The order the quarters go in: north-east, south-east, south-west; the north-west is the last slice. */
    private static final int[] GOES = { 1, 2, 3 };
    private static final VoxelShape[] SHAPES = new VoxelShape[SLICES];

    static {
        for (int cut = 0; cut < SLICES; cut++) {
            VoxelShape s = BOARD;
            for (int q = 0; q < 4; q++) {
                boolean gone = false;
                for (int i = 0; i < cut; i++) if (GOES[i] == q) gone = true;
                if (!gone) s = Shapes.or(s, QUARTERS[q]);
            }
            SHAPES[cut] = s;
        }
    }

    public CheeseWheelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CUT, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CUT);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[state.getValue(CUT)];
    }

    /** The slices left on a wheel (0 for anything else). */
    public static int slicesLeft(BlockState state) {
        return state.getBlock() instanceof CheeseWheelBlock ? SLICES - state.getValue(CUT) : 0;
    }

    /**
     * A slice off the wheel here: a quarter cut away, the last one taking the board with it. False if there is no wheel.
     * (Who eats it is the caller's business: a player, or a folk at its meal.)
     */
    public static boolean slice(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof CheeseWheelBlock)) return false;
        int cut = st.getValue(CUT);
        if (cut < SLICES - 1) {
            level.setBlock(pos, st.setValue(CUT, cut + 1), 3);
        } else {
            level.removeBlock(pos, false);
            level.gameEvent(null, GameEvent.BLOCK_DESTROY, pos);
        }
        level.playSound(null, pos, SoundEvents.GENERIC_EAT, SoundSource.BLOCKS, 0.7F, 0.9F + level.getRandom().nextFloat() * 0.2F);
        return true;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            if (eat(level, pos, player).consumesAction()) return InteractionResult.SUCCESS;
            if (player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) return InteractionResult.CONSUME;
        }
        return eat(level, pos, player);
    }

    /** A player's slice: if it can eat, the slice's hunger and saturation, and the wheel a quarter less. */
    static InteractionResult eat(Level level, BlockPos pos, Player player) {
        if (!player.canEat(false)) {
            if (!level.isClientSide) player.displayClientMessage(Component.literal("You're too full for cheese just now."), true);
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            player.getFoodData().eat(KitchenItems.CHEESE_FOOD);
            level.gameEvent(player, GameEvent.EAT, pos);
            slice(level, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        // On a table: a slab, a block, or the post of a fence with a cloth or none (the café's and the tavern's tables).
        BlockPos under = pos.below();
        return Block.canSupportCenter(level, under, Direction.UP) || level.getBlockState(under).isFaceSturdy(level, under, Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        return dir == Direction.DOWN && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, dir, other, level, pos, otherPos);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return slicesLeft(state) * 3;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }
}
